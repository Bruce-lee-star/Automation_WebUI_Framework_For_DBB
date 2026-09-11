package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElementList;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.delegate.PageNavigation;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.delegate.PageWaits;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.TextNormalizer;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 页面对象基类（框架核心门面）。
 *
 * <h3>API 边界（企业级约束，WEB-P1-2 Phase 6 后）</h3>
 * <p>本类仅保留页面对象的核心门面能力：元素定位（{@code element/locator/elements}）、
 * 页面生命周期（{@code getPage/getContext/navigateTo/refresh/back/forward/switchToPage/
 * waitForNewPage/waitForDownload/closeCurrentPage/closeOtherPages}）、文本归一化、
 * 注解字段绑定与 iframe/shadow 上下文 seam。
 * <p>所有"域能力"（frame/shadow 切换、Cookie、视口/截图/脚本/键盘交互、Locator 工厂、
 * 等待/重试）已下沉到各自的委派类（{@code PageFrameShadow}/{@code CookieManager}/
 * {@code PageViewport}/{@code PageInteractions}/{@code LocatorFactory}/{@code PageWaits}/
 * {@code PageNavigation}），由 {@code SerenityBasePage} 经录制层统一委派调用。
 * 故本类公开方法收敛至 ≤40，职责单一、便于测试与替换。
 *
 * <ul>
 *   <li><b>Tier-1 用户公开 API</b>：{@link #element(String)}/{@link #locator(String)}/{@link #elements(String)}
 *       （返回框架原生 {@code PageElement}/{@code PageElementList}）、{@link #navigateTo(String)} 等页面生命周期。</li>
 *   <li><b>Tier-2 框架内部 seam</b>：{@code activateFrame}/{@code deactivateFrame}/{@code pushShadow}/{@code popShadow}
 *       等为包级私有方法，仅供同包协作者（{@code PageFrameShadow}/{@code PageLifecycleCoordinator}）委派调用。</li>
 *   <li><b>{@code byRole/byText/byLabel/byAltText/byTitle/byTestId/byPlaceholder} 为 framework-internal 定位器工厂</b>，
 *       仅供 {@code web.page.binding.RoleElementBinder} 与 NLS 内部路由使用；
 *       业务 Page Object 应使用 {@code @RoleElement} 注解或 {@link #element(String)}/{@link #locator(String)}，
 *       <b>勿直接持有 Playwright {@code Locator}</b>（禁止类型泄漏，由 ArchitectureTest 门禁固化）。</li>
 * </ul>
 */
public abstract class BasePage {
    protected static final Logger logger = LoggerFactory.getLogger(BasePage.class);

    protected volatile Page page;
    protected volatile BrowserContext context;

    /**
     * 页面上下文状态机（WEB-P1-2 Phase 6 收口）。
     * <p>原散落在 BasePage 的 iframe/shadow 上下文槽、per-context 页面切换锁、ensure 守卫、
     * 注解字段初始化与内部 seam 全部下沉到 {@link PageContextState} 实例；本类仅持有其引用并
     * 提供稳定的公开 / 包级门面委托，外部协作者与业务 Page 零改动。
     */
    private final PageContextState pageContextState = new PageContextState(this);

    // ===================== 全局文本统一格式化工具 =====================

    /**
     * 文本标准化：委托给 {@link TextNormalizer#normalize(String)} 统一实现，
     * 避免 BasePage 和 PageElement 重复定义相同的 Pattern 常量和 normalize 逻辑。
     */
    public String normalizeText(String raw) {
        return TextNormalizer.normalize(raw);
    }

    public BasePage() {
        // WEB-P0-2：构造不再触发全局 FrameworkCore.initialize()——初始化由 Serenity listener
        // (beforeTest → FrameworkCore.beforeTest) 保证；单测经 PlaywrightManager.setProvider(mock) 注入。
        // 运行时若未初始化，getPage() 按原语义抛 IllegalStateException（行为等价改造前）。
        pageContextState.initializeAnnotatedFields();
    }

    // ── 页面切换锁（per-context，WEB-P1-N9 修复）────────────────────────────
    // 原实现为全局静态锁 PAGE_SWITCH_LOCK，导致并行场景下所有 scenario 的页面切换被串行化，
    // 彻底抵消并发收益。改为按 BrowserContext 隔离：同一 Context 内（含同 Context 的多个
    // BasePage 实例 / 多线程共享该 Context）仍串行，不同 Context 互不阻塞、可真并行。
    // 页面切换锁（per-context，WEB-P1-N9 修复）已下沉至 PageContextState，本类不再持有锁状态。

    /**
     * 确保当前 Page 有效（委托 {@link PageContextState}，语义逐字对齐改造前）。
     * @apiNote Framework-internal — 仅供同包 PageWaits/PageInteractions/LocatorFactory 等委派调用。
     */
    void ensurePageValid() {
        pageContextState.ensurePageValid();
    }

    /**
     * 安全检查 Page 是否已关闭（委托 {@link PageContextState}）。
     * @apiNote Framework-internal — 仅供同包 PageLifecycleCoordinator 委派调用，页面对象请勿直接使用。
     */
    boolean isPageClosed(Page p) {
        return pageContextState.isPageClosed(p);
    }

    /** 确保当前 BrowserContext 有效（委托 {@link PageContextState}）。 */
    public void ensureContextValid() {
        pageContextState.ensureContextValid();
    }

    public Page getPage() {
        ensurePageValid();
        return page;
    }

    /**
     * 直接返回 Playwright Page 引用，不触发 ensurePageValid() 副作用。
     * 仅供诊断/日志等只读场景使用（如 ElementDiagnosticsCollector、截图等），
     * 避免在失败路径中意外触发页面同步和字段重新绑定。
     *
     * @return 当前 Playwright Page（可能为 null 或已关闭）
     */
    public Page getPageRaw() {
        return page;
    }

    public BrowserContext getContext() {
        ensureContextValid();
        return context;
    }

    /**
     * 返回指定 Context 的页面切换锁（稳定且 per-context 隔离）；ctx 为 null 时回退全局兜底锁。
     * <p>委托 {@link PageContextState}，锁状态已收口到该实例（WEB-P1-2 Phase 6）。
     */
    static Object pageSwitchLockFor(BrowserContext ctx) {
        return PageContextState.pageSwitchLockFor(ctx);
    }

    public void waitForNetworkIdle(int timeout) {
        PageWaits.waitForNetworkIdle(this, timeout);
    }

    public void waitForPageFullyLoaded(int timeout) {
        PageWaits.waitForPageFullyLoaded(this, timeout);
    }

    public void waitForDOMContentLoaded(int timeout) {
        PageWaits.waitForDOMContentLoaded(this, timeout);
    }

    public void shouldBeVisible(String selector) {
        PageWaits.shouldBeVisible(this, selector);
    }

    public void shouldBeNotVisible(String selector) {
        PageWaits.shouldBeNotVisible(this, selector);
    }

    /**
     * 带验证的重试机制（便捷方法，使用默认重试间隔 500ms）
     *
     * @param operation   要执行的操作
     * @param validation  验证逻辑（BooleanSupplier，无参数）
     * @param maxRetries  最大重试次数
     * @param desc        操作描述
     * @return 验证通过返回 true，否则 false
     */
    public boolean retryWithValidation(Runnable operation, BooleanSupplier validation, int maxRetries, String desc) {
        return PageWaits.retryWithValidation(this, operation, validation, maxRetries, desc);
    }

    public void retry(Runnable runnable, String desc) {
        PageWaits.retry(this, runnable, desc);
    }

    public void retry(Runnable runnable, int retries, int intervalMs, String desc) {
        PageWaits.retry(this, runnable, retries, intervalMs, desc);
    }

    /**
     * 带验证的重试机制。
     *
     * @param operation       要执行的操作
     * @param validation      验证逻辑（BooleanSupplier 替代 Predicate\<Void\>，语义更准确，避免传递 null）
     * @param maxRetries      最大重试次数
     * @param retryIntervalMs 重试间隔（毫秒）
     * @param desc            操作描述
     * @return 验证通过返回 true，否则 false
     */
    public boolean retryWithValidation(Runnable operation, BooleanSupplier validation,
                                       int maxRetries, int retryIntervalMs, String desc) {
        return PageWaits.retryWithValidation(this, operation, validation, maxRetries, retryIntervalMs, desc);
    }

    public void navigateToWithRetry(String url, int retries) {
        PageNavigation.navigateToWithRetry(this, url, retries);
    }

    /**
     * 内部定位解析（T3-5）：自动适配 iframe / shadow 上下文，返回真实 Playwright {@code Locator}。
     * <p>仅供 BasePage 自身方法使用；业务代码请走 {@link #element(String)} / {@link #locator(String)} /
     * {@link #elements(String)} 等返回框架原生类型的入口，不直接接触 Playwright 类型。
     */
    /**
     * 当前 open-shadow 宿主栈拼成的 {@code >>>} 穿透前缀（含末尾空格），无 shadow 时返回空串。
     * <p>选择器解析（含 iframe / shadow 穿透）已下沉至 {@link LocatorFactory#bySelector}，
     * 本方法仅暴露状态视图供同包工厂读取。
     *
     * @apiNote Framework-internal — 仅供同包 {@link LocatorFactory} 委派调用，页面对象请勿直接使用。
     */
    String shadowPrefix() {
        java.util.Deque<String> shadowStack = pageContextState.shadowStack();
        if (shadowStack == null || shadowStack.isEmpty()) {
            return "";
        }
        StringBuilder prefix = new StringBuilder();
        for (String host : shadowStack) {
            prefix.append(host).append(" >>> ");
        }
        return prefix.toString();
    }

    /**
     * 内部定位解析（T3-5）：自动适配 iframe / shadow 上下文，返回真实 Playwright {@code Locator}。
     * <p>实现委托 {@link LocatorFactory#bySelector(BasePage, String)} —— 选择器解析（CSS / XPath +
     * iframe / shadow 穿透）已下沉到工厂，本类退化为稳定门面；业务代码请走 {@link #element(String)} /
     * {@link #locator(String)} / {@link #elements(String)} 等返回框架原生类型的入口。
     */
    public Locator locatorInternal(String selector) {
        return LocatorFactory.bySelector(this, selector);
    }

    /**
     * 按 CSS / XPath 选择器定位元素，返回框架原生的 {@link PageElement}。
     * <p>与 {@link #element(String)} 等价，统一以非泄漏的框架类型作为元素定位入口；
     * iframe / shadow 上下文的适配由内部 {@link #locatorInternal(String)} 负责（供 BasePage 自身方法使用）。
     */
    public PageElement locator(String selector) {
        return element(selector);
    }

    /**
     * 基于选择器创建 PageElement 实例，作为 BasePage 所有元素操作的统一入口。
     * <p>替代分散在各方法中的 {@code new PageElement(selector, this)} 模式，
     * 减少重复代码，并允许子类（如 SerenityBasePage）通过覆盖此方法统一注入报告逻辑。
     *
     * <pre>{@code
     * // 推荐新风格（链式调用）
     * myPage.element("#btn").click();
     * String text = myPage.element("#span").getText();
     *
     * // 传统风格仍然可用（向后兼容）
     * myPage.click("#btn");
     * }</pre>
     *
     * @param selector 元素 CSS/XPath 选择器
     * @return PageElement 实例
     */
    public PageElement element(String selector) {
        return new PageElement(selector, this);
    }

    /**
     * 基于选择器创建 {@link PageElementList}（多元素集合），返回框架原生的列表类型。
     * <p>与 {@link #element(String)} 单元素入口互补：当选择器预期匹配多个元素时使用本方法。
     *
     * @param selector 元素 CSS/XPath 选择器
     * @return PageElementList 实例
     */
    public PageElementList elements(String selector) {
        return new PageElementList(selector, this);
    }

    public void append(String selector, String text) {
        PageElement pe = element(selector);
        pe.focus();
        String current = pe.getValue();
        if (current == null) current = "";
        pe.fill(current + text);
    }

    // ===================== 读取文本 全部归一化 =====================
    public String getAttributeValue(String selector, String attr, String defaultValue) {
        String val = element(selector).getAttribute(attr);
        return val == null ? defaultValue : normalizeText(val);
    }

    public PlaywrightConfigManager getConfig() {
        return PlaywrightManager.config();
    }

    public void navigateTo(String url) {
        PageNavigation.navigateTo(this, url);
    }

    /**
     * 页面导航（navigate / refresh / back / forward）后重置 iframe 上下文。
     * <p>页面内容发生变化后，旧的 Frame 对象会变为 detached，
     * 必须将 currentFrame 置为 null 并刷新 @Element 注解字段（确保后续 locator() 绑定新 Page），
     * 否则后续元素操作会在已失效的 Frame 上执行导致报错。
     */
    public void resetFrameContextAfterNavigation() {
        // 页面导航后 Frame 会 detached，iframe 与 open-shadow 上下文均失效，统一清理（C1/C5）
        pageContextState.resetFrameAndShadowContext();
        logger.debug("Reset iframe/shadow context after page navigation");
    }

    public String getCurrentUrl() {
        return PageNavigation.getCurrentUrl(this);
    }

    public String getTitle() {
        return PageNavigation.getTitle(this);
    }

    public void refresh() {
        PageNavigation.refresh(this);
    }

    public void back() {
        PageNavigation.back(this);
    }

    public void forward() {
        PageNavigation.forward(this);
    }

    // ===================== 页面切换内部工具方法 =====================

    /**
     * 页面切换后的统一后置处理：重置 iframe 上下文、刷新 @Element 字段。
     * @apiNote Framework-internal — 仅供同包 PageLifecycleCoordinator 委派调用，页面对象请勿直接使用。
     */
    void onPageSwitched() {
        pageContextState.onPageSwitched();
    }

    /**
     * 设置当前 page 引用并同步到 PlaywrightManager，使同一 context 内的其他 PageObject 实例可感知。
     * @apiNote Framework-internal — 仅供同包 PageLifecycleCoordinator 委派调用，页面对象请勿直接使用。
     */
    void setPageReference(Page target) {
        pageContextState.setPageReference(target);
    }

    /**
     * 安全 bringToFront：page 已关闭或异常时仅 warn 不抛异常。
     * @apiNote Framework-internal — 仅供同包 PageLifecycleCoordinator 委派调用，页面对象请勿直接使用。
     */
    void safeBringToFront() {
        pageContextState.safeBringToFront();
    }

    // ===================== 页面切换方法（对标 Selenium switchTo().window()） =====================

    /**
     * 按索引切换到指定页面（Page），负数表示从末尾倒数（-1 = 最后一个）。
     * 内置 isClosed 守卫：负数索引若目标已关闭，自动向前回退到第一个未关闭的页面。
     * @param index 页面索引，支持负数（-1 = 最后一个，-2 = 倒数第二个…）
     * @see PageLifecycleCoordinator#switchToPage(BasePage, int)
     */
    public void switchToPage(int index) {
        PageLifecycleCoordinator.switchToPage(this, index);
    }

    /**
     * 切换到指定的 Page 实例（用于 waitForPopup 等捕获到的外部 Page），跳过事件监听。
     * @param page 目标页面（不能为 null 或已关闭）
     * @see PageLifecycleCoordinator#switchToPage(BasePage, Page)
     */
    public Page switchToPage(Page page) {
        return PageLifecycleCoordinator.switchToPage(this, page);
    }

    /**
     * 触发操作并等待新页面打开（基于 Playwright {@code context.waitForPage(action)} 事件级捕获新 Tab）。
     * @param trigger     触发新页面打开的操作（如点击链接）
     * @param timeoutSecs 等待超时秒数
     * @return 新打开的 Page 实例
     * @see PageLifecycleCoordinator#waitForNewPage(BasePage, Runnable, int)
     */
    public Page waitForNewPage(Runnable trigger, int timeoutSecs) {
        return PageLifecycleCoordinator.waitForNewPage(this, trigger, timeoutSecs);
    }

    /**
     * 仅等待新页面（不触发操作），先查快速路径，否则经 {@code context.waitForPage()} 监听。
     * @param timeoutSecs 等待超时秒数
     * @return 新打开的 Page 实例
     * @see PageLifecycleCoordinator#waitForNewPage(BasePage, int)
     */
    public Page waitForNewPage(int timeoutSecs) {
        return PageLifecycleCoordinator.waitForNewPage(this, timeoutSecs);
    }

    /**
     * 等待下载：在 {@code trigger} 触发的一次下载完成前阻塞，对齐 {@code page.waitForDownload(...)}。
     * 框架已开启下载能力，文件自动保存到配置目录；可与弹窗/新页面嵌套。
     * @param trigger     触发下载的操作（如点击下载链接）
     * @param timeoutSecs 等待超时秒数
     * @see PageLifecycleCoordinator#waitForDownload(BasePage, Runnable, int)
     */
    public void waitForDownload(Runnable trigger, int timeoutSecs) {
        PageLifecycleCoordinator.waitForDownload(this, trigger, timeoutSecs);
    }

    /**
     * 关闭当前页面并自动切换到前一个页面。
     * <p>若当前已是最前页面则切换到 index 0；不会关闭唯一页面。
     */
    public void closeCurrentPage() {
        PageLifecycleCoordinator.closeCurrentPage(this);
    }

    /**
     * 关闭除当前页面之外的所有其他页面，保持当前页面为 context 内唯一页面。
     * <p>对标 Selenium 中手动遍历关闭多余窗口的操作。
     * <p>若仅剩 1 个页面或 context 为空则不执行任何关闭操作。
     */
    public void closeOtherPages() {
        PageLifecycleCoordinator.closeOtherPages(this);
    }

    // ===================== 内部辅助 =====================

    /** 从后往前找第一个未关闭的页面（兜底逻辑，供 switchToPage 负数索引使用）。
     * @apiNote Framework-internal — 仅供同包 PageLifecycleCoordinator 委派调用，页面对象请勿直接使用。 */
    Page findLastAvailablePage(List<Page> pages, int startFrom) {
        return pageContextState.findLastAvailablePage(pages, startFrom);
    }

    /**
     * 获取当前 iframe 上下文（按 BasePage 实例隔离）。
     * @return 当前 iframe Frame，未切入 iframe 时返回 null
     */
    public Frame getCurrentFrame() {
        return pageContextState.currentFrame();
    }

    // ===================== 框架内部上下文 seam（包级私有，仅供同包 PageFrameShadow 委派调用） =====================
    // 以下 7 个方法为框架内部状态机的受控入口，不属于页面对象的公开 API，请勿直接调用。
    // 刻意设为包级私有（非 public）：业务 Page 处于不同包，编译期即无法访问，杜绝误用。

    /**
     * 激活指定 iframe 上下文：设置 currentFrame 并重新绑定 @Element 注解字段。
     * @apiNote Framework-internal — 仅供同包 PageFrameShadow 委派调用，页面对象请勿直接使用。
     */
    void activateFrame(Frame frame) {
        pageContextState.activateFrame(frame);
    }

    /** 退出 iframe 回到主文档上下文（若当前处于 iframe 内）。@apiNote Framework-internal — 仅供同包 PageFrameShadow 委派调用。 */
    void deactivateFrame() {
        pageContextState.deactivateFrame();
    }

    /** 将宿主选择器压入 shadow 上下文栈。@apiNote Framework-internal — 仅供同包 PageFrameShadow 委派调用。 */
    void pushShadow(String hostSelector) {
        pageContextState.pushShadow(hostSelector);
    }

    /** 弹出最内层 shadow 宿主；栈空时返回 null。@apiNote Framework-internal — 仅供同包 PageFrameShadow 委派调用。 */
    String popShadow() {
        return pageContextState.popShadow();
    }

    /** 清空整个 shadow 上下文栈。@apiNote Framework-internal — 仅供同包 PageFrameShadow 委派调用。 */
    void clearShadows() {
        pageContextState.clearShadows();
    }

    /** 当前 shadow 嵌套深度。@apiNote Framework-internal — 仅供同包 PageFrameShadow 委派调用。 */
    int getShadowDepth() {
        return pageContextState.getShadowDepth();
    }

    /** 当前最内层 shadow 宿主（栈顶），栈空时返回 null。@apiNote Framework-internal — 仅供同包 PageFrameShadow 委派调用。 */
    String peekShadow() {
        return pageContextState.peekShadow();
    }

    /**
     * 判断当前是否为可调试本地环境。
     * @return true=本地允许 pause；false=Jenkins/BrowserStack 禁止暂停
     * @see PageDebugControl#isDebugEnvironment()
     */
    protected boolean isDebugEnvironment() {
        return PageDebugControl.isDebugEnvironment();
    }

    /**
     * 安全暂停方法：本地 IDE 正常 pause 调试；Jenkins / BrowserStack 自动跳过，杜绝流程阻塞。
     * @see PageDebugControl#pause(BasePage)
     */
    public void pause() {
        PageDebugControl.pause(this);
    }
}
