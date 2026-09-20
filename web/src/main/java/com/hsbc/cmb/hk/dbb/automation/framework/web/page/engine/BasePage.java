package com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.PageElementList;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.RoleElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.RoleOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.recording.SerenityPageRecorder;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.TextNormalizer;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

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
 * {@code PageNavigation}），由录制门面 {@link SerenityPageRecorder} 统一委派调用。
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

    /**
     * Layer B 录制门面（每实例一份，承载 per-page 测试数据）。
     * 原 {@code SerenityBasePage} 的全部录制方法已下沉为本类公开委托壳，业务 Page 改继承 BasePage 即可零改动获得同样的透明录制。
     * 递归防护：本类录制方法委托本门面时，门面内部一律调用工具类/裸 getter（如 {@code getPageRaw()}），
     * 绝不回调 BasePage 的录制方法，避免无限递归。
     */
    private final SerenityPageRecorder serenity = new SerenityPageRecorder();

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

    /**
     * G1 组合式注入点：把框架管理的（已装饰录制）Page/Context 注入本委托实例。
     * 业务 Page 经 {@code ManagedPageAware.setManagedPage} 拿到 {@code Supplier<Page>}，
     * 解析出具体 Page 后通过本方法挂到内部委托 BasePage，从而复用全部页面上下文能力
     * （element/locator/iframe/shadow/ensure/绑定等），无需继承本类。
     *
     * @apiNote 由组合式 Page Object（{@code AbstractManagedPage}）的 {@code setManagedPage} 调用。
     */
    public void attachManagedPage(Page managedPage) {
        if (managedPage == null) {
            throw new IllegalArgumentException("attachManagedPage: managedPage must not be null");
        }
        this.page = managedPage;
        this.context = managedPage.context();
    }

    /** 受管（装饰）页的惰性来源：非 null 时，页面在首次真正使用时才被解析/创建。 */
    private volatile Supplier<Page> managedPageSupplier;

    /**
     * G1 组合式注入点（<b>惰性</b>）：仅登记供应商，<b>不立即解析</b>。
     *
     * <p><b>为什么必须惰性</b>：{@code PageObjectFactory} 在<b>步骤类构造期</b>创建页面对象并注入
     * 供应商；若此处立即 {@code supplier.get()}，则"构造页面对象"就等于"创建 Browser/Context/Page"
     * —— 实测导致每个用例多建 Context+Page（`about:blank` 新 tab 的来源），并使页面创建发生在
     * 会话闸门获取<b>之前</b>（同 sessionKey 场景因此会先开浏览器，违背并行语义）。
     *
     * <p>解析点收敛到 {@link #resolveManagedPage()}，由页面上下文状态机在需要页面时调用；
     * 仍走原始供应商 ⇒ 保留 {@code RecordingPageProxy} 装饰与页面切换后的自动指向。
     */
    public void attachManagedPage(Supplier<Page> managedPageSupplier) {
        this.managedPageSupplier = managedPageSupplier;
    }

    /**
     * 惰性解析受管页（包级：供 {@link PageContextState} 在需要页面时调用）。
     *
     * <p>已有存活受管页 → 直接返回；否则经供应商解析并同步 {@link #context}。
     * 无供应商时返回 {@code null}（调用方自行决定回退策略）。
     * <b>注意</b>：{@link #getPageRaw()} 保持纯 getter 语义（不触发解析），供失败路径安全使用。
     */
    Page resolveManagedPage() {
        Page current = this.page;
        if (current != null && !current.isClosed()) {
            return current;
        }
        Supplier<Page> supplier = this.managedPageSupplier;
        if (supplier == null) {
            return null;
        }
        Page resolved = supplier.get();
        if (resolved != null) {
            this.page = resolved;
            this.context = resolved.context();
        }
        return resolved;
    }

    /**
     * G1 组合式 {@code @Element}/{@code @RoleElement} 绑定入口：字段宿主是业务 POJO，
     * 页面宿主是传入的委托 BasePage（详见 {@link PageContextState#bindAnnotatedFields(Object, BasePage)}）。
     */
    public static void bindAnnotatedFields(Object fieldOwner, BasePage pageOwner) {
        PageContextState.bindAnnotatedFields(fieldOwner, pageOwner);
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
     * 裸上下文读取（无 {@code ensureContextValid()} 副作用），供录制门面在跨包调用时读取状态，避免递归与包访问限制。
     */
    public BrowserContext getContextRaw() {
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
        serenity.waitForNetworkIdle(this, timeout);
    }

    public void waitForPageFullyLoaded(int timeout) {
        serenity.waitForPageFullyLoaded(this, timeout);
    }

    public void waitForDOMContentLoaded(int timeout) {
        serenity.waitForDOMContentLoaded(this, timeout);
    }

    public void shouldBeVisible(String selector) {
        serenity.shouldBeVisible(this, selector);
    }

    public void shouldBeNotVisible(String selector) {
        serenity.shouldBeNotVisible(this, selector);
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
        serenity.retry(this, runnable, desc);
    }

    public void retry(Runnable runnable, int retries, int intervalMs, String desc) {
        serenity.retry(this, runnable, retries, intervalMs, desc);
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
        return serenity.retryWithValidation(this, operation, validation, maxRetries, retryIntervalMs, desc);
    }

    public void navigateToWithRetry(String url, int retries) {
        serenity.navigateToWithRetry(this, url, retries);
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
     * 减少重复代码，并允许实现方（如录制门面 {@link SerenityPageRecorder}）通过覆写统一注入报告逻辑。
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
        return serenity.element(this, selector);
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

    // ===================== 角色定位（ARIA role，业务公开入口） =====================

    /**
     * 按 ARIA 角色定位元素，返回框架原生的 {@link PageElement}（不泄漏裸 Playwright {@code Locator}）。
     *
     * <p>与 {@link #element(String)} 同为业务公开定位入口：底层复用 {@code LocatorFactory.byRole}
     * （经 {@code RoleLocatorFactory} 统一），并具备与 {@code @RoleElement} 注解一致的语义——懒解析、
     * iframe/shadow 上下文适配、状态三态与标题层级；录制经 {@link SerenityPageRecorder}。
     *
     * <p>业务契约见 {@code SerenityBasePage#elementByRole*}；{@link #byRole(AriaRole)} 系列仍为
     * framework-internal（返回裸 {@code Locator}），受 {@code businessCodeMustNotUseInternalByLocators} 守护。
     */
    public PageElement elementByRole(AriaRole role) {
        return serenity.elementByRole(this, role);
    }

    /** 按角色 + 可访问名（精确匹配）定位。 */
    public PageElement elementByRole(AriaRole role, String name) {
        return serenity.elementByRole(this, role, name);
    }

    /** 按角色 + 可访问名（可指定精确/模糊）定位。 */
    public PageElement elementByRole(AriaRole role, String name, boolean exact) {
        return serenity.elementByRole(this, role, name, exact);
    }

    /** 按角色 + 可访问名 + 标题层级（仅 {@code AriaRole.HEADING} 有意义）定位。 */
    public PageElement elementByRole(AriaRole role, String name, boolean exact, int level) {
        return serenity.elementByRole(this, role, name, exact, level);
    }

    /** 按角色 + 正则可访问名（多语言模板值编译而来）+ 标题层级定位。 */
    public PageElement elementByRole(AriaRole role, Pattern namePattern, int level) {
        return serenity.elementByRole(this, role, namePattern, level);
    }

    /**
     * 按角色 + 正则可访问名 + 匹配方式 + 标题层级定位。
     * <p>{@code exact=true} 由框架把正则锚定为整串匹配（{@code ^(?:…)$}）——Playwright 原生对正则
     * 忽略 {@code exact}，故框架显式实现；{@code false} 为 Playwright 原生子串匹配。
     */
    public PageElement elementByRole(AriaRole role, Pattern namePattern, boolean exact, int level) {
        return serenity.elementByRole(this, role, namePattern, exact, level);
    }

    /**
     * 按角色 + 可访问名 + 选项（层级 / 匹配方式 / 可访问状态三态）定位。
     * 选项语义见 {@link RoleOptions}（{@code enabledOnly()}/{@code pressed()}/{@code collapsed()} …）。
     */
    public PageElement elementByRole(AriaRole role, String name, RoleOptions options) {
        return serenity.elementByRole(this, role, name, options);
    }

    /** 按角色 + 正则可访问名 + 选项（层级 / 匹配方式 / 可访问状态三态）定位。 */
    public PageElement elementByRole(AriaRole role, Pattern namePattern, RoleOptions options) {
        return serenity.elementByRole(this, role, namePattern, options);
    }

    /**
     * 按角色 + NLS 键定位（多语言）：用 {@code roleFileClass} 上的类级 {@code @RoleFile}
     * 在运行时把 key 解析为当前语言的可访问名（{@code NLSUtils.setLanguage} 切换后自动生效）。
     *
     * @param roleFileClass 声明 {@code @RoleFile} 的页面类（业务页自身；由契约层传入）
     */
    public PageElement elementByRoleKey(AriaRole role, String nlsKey, int level, Class<?> roleFileClass) {
        return serenity.elementByRoleKey(this, roleFileClass, role, nlsKey, level);
    }

    /** 按角色 + NLS 键 + 选项（层级 / 匹配方式 / 可访问状态三态）定位；NLS 上下文由契约层传入。 */
    public PageElement elementByRoleKey(AriaRole role, String nlsKey, RoleOptions options, Class<?> roleFileClass) {
        return serenity.elementByRoleKey(this, roleFileClass, role, nlsKey, options);
    }

    /** 角色定位的多元素集合（不限名称）。 */
    public PageElementList elementsByRole(AriaRole role) {
        return serenity.elementsByRole(this, role);
    }

    /** 角色定位的多元素集合（按可访问名精确匹配）。 */
    public PageElementList elementsByRole(AriaRole role, String name) {
        return serenity.elementsByRole(this, role, name);
    }

    public void append(String selector, String text) {
        PageElement pe = element(selector);
        pe.focus();
        String current = pe.getValue();
        if (current == null)  {current = "";} 
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
        serenity.navigateTo(this, url);
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
        serenity.refresh(this);
    }

    public void back() {
        serenity.back(this);
    }

    public void forward() {
        serenity.forward(this);
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

    // ========================================================================
    // Phase 4：原 SerenityBasePage 的 Layer B 录制方法已下沉为本类公开委托壳。
    // 业务 Page 经 AbstractManagedPage（面向 SerenityBasePage 接口）组合获得同样的透明录制（经 SerenityPageRecorder）。
    // 录制语义（flushPendingApiOperations + verbose 日志 + per-page 测试数据）与逐字迁移版完全一致。
    // ========================================================================

    // ==================== per-page 测试数据 ====================

    /** 记录一条 per-page 测试数据（供报告 / 排障读取）。 */
    protected void addSerenityTestData(String key, Object value) {
        serenity.recorder().addSerenityTestData(key, value);
    }

    /** 读取一条 per-page 测试数据。 */
    protected Object getSerenityTestData(String key) {
        return serenity.recorder().getSerenityTestData(key);
    }

    /** 返回本页全部 per-page 测试数据快照。 */
    public Map<String, Object> getSerenityTestDataMap() {
        return serenity.recorder().getSerenityTestDataMap();
    }

    /** 清空本页 per-page 测试数据。 */
    public void clearSerenityTestData() {
        serenity.recorder().clearSerenityTestData();
    }

    // ==================== 交互 ====================

    public void keyDown(String selector, String key) {
        serenity.keyDown(this, selector, key);
    }

    public void keyUp(String selector, String key) {
        serenity.keyUp(this, selector, key);
    }

    public void press(String selector, String key) {
        serenity.press(this, selector, key);
    }

    public void acceptAlert() {
        serenity.acceptAlert(this);
    }

    public void dismissAlert() {
        serenity.dismissAlert(this);
    }

    public void acceptAlert(Runnable trigger) {
        serenity.acceptAlert(this, trigger);
    }

    public void dismissAlert(Runnable trigger) {
        serenity.dismissAlert(this, trigger);
    }

    public void setContent(String html) {
        serenity.setContent(this, html);
    }

    public void setViewportSize(int width, int height) {
        serenity.setViewportSize(this, width, height);
    }

    public void executeInFrame(String frameName, Consumer<Frame> action) {
        serenity.executeInFrame(this, frameName, action);
    }

    // ==================== 状态 ====================

    public boolean isClosed() {
        return serenity.isClosed(this);
    }

    public byte[] takeScreenshot() {
        return serenity.takeScreenshot(this);
    }

    public byte[] takeElementScreenshot(String selector) {
        return serenity.takeElementScreenshot(this, selector);
    }

    public BoundingBox getElementBoundingBox(String selector) {
        return serenity.getElementBoundingBox(this, selector);
    }

    // ==================== frame / shadow ====================

    public Frame getFrame(String name) {
        return serenity.getFrame(this, name);
    }

    public Frame switchToFrame(String nameOrSelector) {
        return serenity.switchToFrame(this, nameOrSelector);
    }

    public void switchToShadow(String hostSelector) {
        serenity.switchToShadow(this, hostSelector);
    }

    public String switchToDefaultShadow() {
        return serenity.switchToDefaultShadow(this);
    }

    public void switchToDefaultShadowAll() {
        serenity.switchToDefaultShadowAll(this);
    }

    public Frame switchToFrameAndWait(Runnable trigger, String nameOrSelector, int timeoutSecs) {
        return serenity.switchToFrameAndWait(this, trigger, nameOrSelector, timeoutSecs);
    }

    public Frame switchToFrameAndWait(Runnable trigger, String nameOrSelector) {
        return serenity.switchToFrameAndWait(this, trigger, nameOrSelector);
    }

    public Frame switchToFrameAndWait(String nameOrSelector, int timeoutSecs) {
        return serenity.switchToFrameAndWait(this, nameOrSelector, timeoutSecs);
    }

    public Frame switchToFrameAndWait(String nameOrSelector) {
        return serenity.switchToFrameAndWait(this, nameOrSelector);
    }

    public void switchToDefaultContent() {
        serenity.switchToDefaultContent(this);
    }

    public List<Frame> getAllFrames() {
        return serenity.getAllFrames(this);
    }

    public void dumpAccessibilityRoles() {
        serenity.dumpAccessibilityRoles(this);
    }

    // ==================== 脚本 / 源码 ====================

    public Object executeJavaScript(String script, Object... args) {
        return serenity.executeJavaScript(this, script, args);
    }

    public String getPageSource() {
        return serenity.getPageSource(this);
    }

    public int getPageSize() {
        return serenity.getPageSize(this);
    }

    public void waitForTimeout(int milliseconds) {
        serenity.waitForTimeout(this, milliseconds);
    }

    // ==================== Cookie ====================

    public List<Cookie> getCookies() {
        return serenity.getCookies(this);
    }

    public List<Cookie> getCookies(String url) {
        return serenity.getCookies(this, url);
    }

    public List<Cookie> getCookies(List<String> urls) {
        return serenity.getCookies(this, urls);
    }

    public Cookie getCookie(String name) {
        return serenity.getCookie(this, name);
    }

    public boolean hasCookie(String name) {
        return serenity.hasCookie(this, name);
    }

    public void addCookie(Cookie cookie) {
        serenity.addCookie(this, cookie);
    }

    public void addCookies(List<Cookie> cookies) {
        serenity.addCookies(this, cookies);
    }

    public void deleteCookie(String name) {
        serenity.deleteCookie(this, name);
    }

    public void clearCookies() {
        serenity.clearCookies(this);
    }

    public List<Cookie> getCookiesForCurrentPage() {
        return serenity.getCookiesForCurrentPage(this);
    }

    // ==================== Locator 工厂（framework-internal，完整覆盖逐字迁移版重载） ====================
    // @apiNote 业务代码请走 @RoleElement + element()/locator()，勿直接调用 by*（受 ArchUnit businessCodeMustNotUseInternalByLocators 约束）。

    public Locator byAltText(String altText) {
        return serenity.byAltText(this, altText);
    }

    public Locator byAltText(String altText, boolean exact) {
        return serenity.byAltText(this, altText, exact);
    }

    public Locator byAltText(Pattern altText) {
        return serenity.byAltText(this, altText);
    }

    public Locator byRole(AriaRole role) {
        return serenity.byRole(this, role);
    }

    public Locator byRole(AriaRole role, String name) {
        return serenity.byRole(this, role, name);
    }

    public Locator byRole(AriaRole role, Pattern namePattern) {
        return serenity.byRole(this, role, namePattern);
    }

    public Locator byRole(AriaRole role, String name, boolean exact) {
        return serenity.byRole(this, role, name, exact);
    }

    public Locator byRole(AriaRole role, String name, boolean exact, int level) {
        return serenity.byRole(this, role, name, exact, level);
    }

    public Locator byRole(AriaRole role, Pattern namePattern, int level) {
        return serenity.byRole(this, role, namePattern, level);
    }

    public Locator byRole(AriaRole role, String name, boolean exact, int level,
                          RoleElement.State disabled, RoleElement.State pressed, RoleElement.State expanded) {
        return serenity.byRole(this, role, name, exact, level, disabled, pressed, expanded);
    }

    public Locator byRole(AriaRole role, Pattern namePattern, int level,
                          RoleElement.State disabled, RoleElement.State pressed, RoleElement.State expanded) {
        return serenity.byRole(this, role, namePattern, level, disabled, pressed, expanded);
    }

    public Locator byTitle(String title) {
        return serenity.byTitle(this, title);
    }

    public Locator byTitle(String title, boolean exact) {
        return serenity.byTitle(this, title, exact);
    }

    public Locator byTitle(Pattern title) {
        return serenity.byTitle(this, title);
    }

    public Locator byTestId(String testId) {
        return serenity.byTestId(this, testId);
    }

    public Locator byText(String text) {
        return serenity.byText(this, text);
    }

    public Locator byText(String text, boolean exact) {
        return serenity.byText(this, text, exact);
    }

    public Locator byText(Pattern text) {
        return serenity.byText(this, text);
    }

    public Locator byPlaceholder(String placeholder) {
        return serenity.byPlaceholder(this, placeholder);
    }

    public Locator byPlaceholder(String placeholder, boolean exact) {
        return serenity.byPlaceholder(this, placeholder, exact);
    }

    public Locator byPlaceholder(Pattern placeholder) {
        return serenity.byPlaceholder(this, placeholder);
    }

    public Locator byLabel(String label) {
        return serenity.byLabel(this, label);
    }

    public Locator byLabel(String label, boolean exact) {
        return serenity.byLabel(this, label, exact);
    }

    public Locator byLabel(Pattern label) {
        return serenity.byLabel(this, label);
    }
}
