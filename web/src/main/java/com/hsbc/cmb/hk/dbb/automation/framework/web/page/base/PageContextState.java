package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;


import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.Element;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElementList;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.binding.RoleElementBinder;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 页面上下文状态机（企业级封装单元，WEB-P1-2 Phase 6 收口）。
 *
 * <p>承载 {@link BasePage} 散落的 per-instance 可变状态与关联行为：iframe / open-shadow
 * 上下文槽、per-context 页面切换锁、页面有效性守卫（{@code ensurePageValid}）、
 * 注解字段初始化，以及供同包协作者（{@code PageFrameShadow} / {@code PageLifecycleCoordinator}）
 * 委派的框架内部 seam（{@code activateFrame} / {@code deactivateFrame} / {@code pushShadow} /
 * {@code popShadow} / {@code clearShadows} / {@code getShadowDepth} / {@code peekShadow} /
 * {@code onPageSwitched} / {@code setPageReference} / {@code safeBringToFront} /
 * {@code findLastAvailablePage}）。
 *
 * <h3>Java 三大特性取舍（呼应 WEB-P1-6 整改复盘）</h3>
 * <ul>
 *   <li><b>封装 ✅</b>：原散落在 BasePage 的 6+ 处可变状态与约 330 行编排逻辑收口到本实例，
 *       外部仅经 BasePage 提供的稳定公开 / 包级 API 访问，状态不可被业务 Page 直接触碰。</li>
 *   <li><b>继承 ❌</b>：刻意不用继承（组合优于继承）。BasePage 持有本实例引用完成委托，
 *       本类为 {@code final}，不存在可替换的子类层次。</li>
 *   <li><b>多态 🔶</b>：本状态机为单一实现，无多实现需求，故不强行引入接口（遵循
 *       “仅在确有多实现需求时引入接口”的过度抽象防护铁律）；门面委托风格与
 *       WEB-P0-2 的 DI seam 一致。</li>
 * </ul>
 *
 * <p><b>企业级约束：</b>线程安全由 BasePage 既有的 per-instance 状态隔离 + per-context 锁语义保证，
 * 本类不引入新的共享可变状态；日志统一路由回 {@link BasePage#logger} 以保持生产溯源一致。
 *
 * @apiNote Framework-internal — 仅由 {@link BasePage} 构造并持有，业务代码请勿直接依赖。
 */
final class PageContextState {

    private static final Logger logger = LoggerFactory.getLogger(BasePage.class);

    private final BasePage owner;

    /**
     * 线程归属标记（WEB 多线程强化，对齐 playwright-java 1.58.0 官方并发模型）：
     * 记录首次使用本状态机的 scenario 线程。Playwright 的 Page/Context/Locator 非线程安全，
     * 一个 BasePage 实例只应被绑定它的线程使用；跨线程访问
     * {@code ensurePageValid/ensureContextValid} 将抛 {@link IllegalStateException} 快速失败，
     * 把"跨线程共享 Page 导致 pipe closed"类诡异失败转为清晰异常。
     */
    private volatile Thread ownerThread;

    /**
     * 当前 iframe 上下文（Playwright Frame），按 BasePage 实例隔离（非 ThreadLocal）。
     * <p>null 表示当前在主页面 DOM 中操作。
     */
    private final FrameSlot currentFrame = new FrameSlot();

    /**
     * 当前 open shadowRoot 上下文栈（自外向内，保存各层宿主的 CSS 选择器），按 BasePage 实例隔离。
     */
    private final ShadowSlot currentShadow = new ShadowSlot();

    // ===================== 页面切换锁（per-context，WEB-P1-N9 修复） =====================
    private static final Map<BrowserContext, Object> PAGE_SWITCH_LOCKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 兜底锁：当无法解析到 BrowserContext 时使用，等价于旧全局锁语义。 */
    private static final Object GLOBAL_PAGE_SWITCH_LOCK = new Object();

    /** 首次注解字段初始化标志——页面切换时复用已有对象而非重建。 */
    private volatile boolean annotatedFieldsInitialized = false;

    PageContextState(BasePage owner) {
        this.owner = owner;
    }

    /** 返回指定 Context 的页面切换锁（稳定且 per-context 隔离）；ctx 为 null 时回退全局兜底锁。 */
    static Object pageSwitchLockFor(BrowserContext ctx) {
        if (ctx == null) {
            return GLOBAL_PAGE_SWITCH_LOCK;
        }
        return PAGE_SWITCH_LOCKS.computeIfAbsent(ctx, c -> new Object());
    }

    /** 解析页面切换锁应归属的 Context：优先已绑定的 {@code context}，其次从当前 {@code page} 推断。 */
    private BrowserContext resolveSwitchLockContext() {
        try {
            if (owner.context != null) {
                return owner.context;
            }
        } catch (Exception ignored) {
            //  context 尚未初始化：退化到从 page 推断
        }
        if (owner.page != null) {
            try {
                return owner.page.context();
            } catch (Exception ignored) {
                //  page 已失效：回退全局兜底锁
            }
        }
        return null;
    }

    /**
     * 确保当前 Page 有效（最外层 per-context 切换锁内），并在失效时重建并重置 iframe/shadow 上下文。
     * <p>等价于改造前 BasePage.ensurePageValid 的语义，逐字迁移。
     */
    void ensurePageValid() {
        assertOwningThread();
        if (owner.page == null || isPageClosed(owner.page)) {
            // WEB-P1-N9：per-context 页面切换锁（最外层），不同 Context 互不阻塞、可并行
            synchronized (pageSwitchLockFor(resolveSwitchLockContext())) {
                // 双重检查：锁内再次确认 page 仍无效
                if (owner.page == null || isPageClosed(owner.page)) {
                    owner.page = PlaywrightManager.getPage();
                    resetFrameAndShadowContext(); // 页面重建后重置 iframe/shadow 上下文
                }
            }
        } else {
            // 检测 PlaywrightManager 中的 page 是否已被其他实例切换（如 switchToPage/switchNewPage）
            Page managerPage = PlaywrightManager.getPage();
            if (managerPage != owner.page) {
                owner.page = managerPage;
                resetFrameAndShadowContext();
            } else {
                // 防御：页面内导航可能已使当前 iframe Frame detached，自动清理避免状态泄漏（C1/C5）
                clearStaleFrameContextIfNeeded();
            }
        }
    }

    /**
     * 重置 iframe 与 open-shadow 上下文（同时清理，避免 shadow 宿主选择器跨页面泄漏）。
     * 页面切换/重建后调用，确保后续 locator() 解析到新 Page 而非失效的旧 Frame/shadow。
     */
    void resetFrameAndShadowContext() {
        currentFrame.remove();
        currentShadow.get().clear();
        initializeAnnotatedFields();
    }

    /**
     * 防御性清理：若当前已切入 iframe，但其所属 Frame 已 detached 或指向其它 Page
     * （页面内导航导致旧 Frame 失效的常见场景），则自动清掉 iframe/shadow 上下文。
     */
    private void clearStaleFrameContextIfNeeded() {
        Frame f = currentFrame.get();
        if (f == null) return;
        try {
            Page fp = f.page();
            if (fp == null || fp.isClosed() || fp != owner.page) {
                resetFrameAndShadowContext();
            }
        } catch (Exception e) {
            // Frame 已 detached，访问其 page 会抛异常 —— 直接清理
            resetFrameAndShadowContext();
        }
    }

    /**
     * 安全检查 Page 是否已关闭（避免 isClosed() 抛异常导致流程中断）。
     * @apiNote Framework-internal — 仅供同包 BasePage / PageLifecycleCoordinator 委派调用。
     */
    boolean isPageClosed(Page p) {
        if (p == null) return true;
        try {
            return p.isClosed();
        } catch (Exception e) {
            VerboseLogging.logWarnIfVerbose(logger, "page.isClosed() threw exception, treating as closed: {}", e.getMessage());
            return true;
        }
    }

    /** 确保当前 BrowserContext 有效（无效时从 PlaywrightManager 拉取）。 */
    void ensureContextValid() {
        assertOwningThread();
        if (owner.context == null) {
            owner.context = PlaywrightManager.getContext();
        }
    }

    /**
     * 线程归属守卫：一个 BasePage 实例只能由首次使用它的线程（scenario 线程）访问。
     * <p>惰性确立归属（首次调用时记录当前线程），后续调用若来自不同线程则快速失败，
     * 把 Playwright 非线程安全的"跨线程共享 Page 导致 pipe closed"类诡异失败转为清晰异常。
     */
    private void assertOwningThread() {
        Thread current = Thread.currentThread();
        Thread owner = this.ownerThread;
        if (owner == null) {
            this.ownerThread = current;
            return;
        }
        if (owner != current) {
            throw new IllegalStateException(
                    "BasePage 实例归属线程 [" + owner.getName() + "]，禁止跨线程访问（当前线程 ["
                            + current.getName() + "]）。Playwright 的 Page/Context/Locator 非线程安全，"
                            + "请为每个线程/场景使用独立的 BasePage 实例。");
        }
    }

    /**
     * 初始化/刷新注解字段。
     * 首次调用：创建 PageElement/PageElementList 对象。
     * 后续调用（页面切换）：复用已有对象，Locator 由 locator() 动态绑定新 Page。
     */
    void initializeAnnotatedFields() {
        Class<?> clazz = owner.getClass();
        while (clazz != null && clazz != BasePage.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(RoleElement.class)) {
                    RoleElement a = field.getAnnotation(RoleElement.class);
                    field.setAccessible(true);

                    if (annotatedFieldsInitialized) {
                        // 页面切换后——复用已有对象，Locator 由 locator() 动态绑定新 Page
                        try {
                            Object existing = field.get(owner);
                            if (existing == null || !(existing instanceof PageElement)) {
                                new RoleElementBinder(owner).bind(field, a);
                            }
                        } catch (IllegalAccessException e) {
                            new RoleElementBinder(owner).bind(field, a);
                        }
                        continue;
                    }

                    new RoleElementBinder(owner).bind(field, a);
                } else if (field.isAnnotationPresent(Element.class)) {
                    Element elementAnnotation = field.getAnnotation(Element.class);
                    String selector = elementAnnotation.value();
                    // 对齐 page.pause() 的 frameLocator 录制：iframe 内元素用 frame() 逐层下钻。
                    List<String> frameSegs = Arrays.asList(elementAnnotation.frame());
                    field.setAccessible(true);

                    if (annotatedFieldsInitialized) {
                        // 页面切换后——复用已有对象，Locator 由 locator() 动态绑定新 Page
                        try {
                            Object existing = field.get(owner);
                            if (existing == null || !(existing instanceof PageElement || existing instanceof PageElementList)) {
                                createField(field, selector, frameSegs);
                            }
                        } catch (IllegalAccessException e) {
                            // get 失败，回退到重新创建
                            createField(field, selector, frameSegs);
                        }
                        continue;
                    }

                    createField(field, selector, frameSegs);
                }
            }
            clazz = clazz.getSuperclass();
        }
        annotatedFieldsInitialized = true;
    }

    /** 创建 PageElement 或 PageElementList 实例并赋值给字段 */
    private void createField(Field field, String selector) {
        createField(field, selector, null);
    }

    /** 创建 PageElement / PageElementList（含 iframe 嵌套路径 frameSegs，对齐 page.pause 的 frameLocator 录制） */
    private void createField(Field field, String selector, List<String> frameSegs) {
        try {
            if (List.class.isAssignableFrom(field.getType())) {
                field.set(owner, new PageElementList(selector, owner, frameSegs));
            } else {
                field.set(owner, new PageElement(selector, owner, frameSegs));
            }
        } catch (Exception e) {
            throw new ElementException("Init field failed: " + field.getName(), e);
        }
    }

    // ===================== 上下文读取（供 BasePage.locatorInternal 使用） =====================

    /** 当前 iframe Frame（未切入 iframe 时返回 null）。 */
    Frame currentFrame() {
        return currentFrame.get();
    }

    /** 当前 open-shadow 上下文栈（自外向内）。 */
    Deque<String> shadowStack() {
        return currentShadow.get();
    }

    // ===================== 框架内部上下文 seam（包级，仅供同包协作者委派调用） =====================

    /** 页面切换后统一后置处理：重置 iframe/shadow 上下文、刷新 @Element 字段。 */
    void onPageSwitched() {
        resetFrameAndShadowContext();
    }

    /** 设置当前 page 引用并同步到 PlaywrightManager，使同 context 内其它 PageObject 实例可感知。 */
    void setPageReference(Page target) {
        owner.page = target;
        PlaywrightRuntime.instance().pageRegistry.setPage(owner.page);
    }

    /** 安全 bringToFront：page 已关闭或异常时仅 warn 不抛异常。 */
    void safeBringToFront() {
        try {
            owner.page.bringToFront();
        } catch (Exception e) {
            VerboseLogging.logWarnIfVerbose(logger, "bringToFront() failed: {}", e.getMessage());
        }
    }

    /** 从后往前找第一个未关闭的页面（兜底逻辑，供 switchToPage 负数索引使用）。 */
    Page findLastAvailablePage(List<Page> pages, int startFrom) {
        for (int i = startFrom; i >= 0; i--) {
            try {
                if (!pages.get(i).isClosed()) {
                    VerboseLogging.logWarnIfVerbose(logger,
                            "Latest window was closed, falling back to window at index {}", i);
                    return pages.get(i);
                }
            } catch (Exception ignored) { /* 页面状态探测：忽略探测过程中的异常，继续向前回退 */ }
        }
        return pages.get(startFrom); // 全部已关闭，返回原目标由调用方 isClosed 抛异常
    }

    /** 激活指定 iframe 上下文：设置 currentFrame 并重新绑定 @Element 注解字段。 */
    void activateFrame(Frame frame) {
        currentFrame.set(frame);
        initializeAnnotatedFields();
    }

    /** 退出 iframe 回到主文档上下文（若当前处于 iframe 内）。 */
    void deactivateFrame() {
        if (currentFrame.get() != null) {
            currentFrame.remove();
            initializeAnnotatedFields();
        }
    }

    /** 将宿主选择器压入 shadow 上下文栈。 */
    void pushShadow(String hostSelector) {
        currentShadow.get().push(hostSelector);
    }

    /** 弹出最内层 shadow 宿主；栈空时返回 null。 */
    String popShadow() {
        Deque<String> stack = currentShadow.get();
        return stack.isEmpty() ? null : stack.pop();
    }

    /** 清空整个 shadow 上下文栈。 */
    void clearShadows() {
        currentShadow.get().clear();
    }

    /** 当前 shadow 嵌套深度。 */
    int getShadowDepth() {
        return currentShadow.get().size();
    }

    /** 当前最内层 shadow 宿主（栈顶），栈空时返回 null。 */
    String peekShadow() {
        return currentShadow.get().peek();
    }

    // ===================== 内部槽类（按实例隔离，替代原 static ThreadLocal） =====================

    /** iframe 上下文槽：按实例隔离，提供与原 ThreadLocal 一致的方法签名（C1 根因修复）。 */
    private static final class FrameSlot {
        private volatile Frame value;
        Frame get() { return value; }
        void set(Frame f) { value = f; }
        void remove() { value = null; }
    }

    /** open-shadow 上下文栈槽：按实例隔离，提供与原 ThreadLocal 一致的方法签名（C1 根因修复）。 */
    private static final class ShadowSlot {
        private final Deque<String> stack = new ArrayDeque<>();
        Deque<String> get() { return stack; }
        void remove() { stack.clear(); }
    }
}
