package com.hsbc.cmb.hk.dbb.automation.framework.web.page.recording;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.Cookie;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.SerenityReporter;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ConfigurationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.NavigationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.binding.RoleLocatorFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.PageElementList;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.RoleElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.RoleOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.CookieManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.LocatorFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.PageAccessibility;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.PageFrameShadow;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.PageInteractions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.PageNavigation;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.PageViewport;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.PageWaits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Layer B 录制门面（framework-internal）：把原 {@code SerenityBasePage} 旧类（已删除）的约 40 个录制方法体
 * 收敛为可复用助手，使该旧类退化为薄委托壳并最终删除（Phase 3 → Phase 4）。
 *
 * <p>每个方法 = 「{@link SerenityRecorder} 录制（flushPendingApiOperations + verbose 日志 + per-page 测试数据）
 * + 委派到对应工具类（{@code LocatorFactory}/{@code PageWaits}/{@code PageInteractions}/{@code PageViewport}/
 * {@code PageFrameShadow}/{@code PageAccessibility}/{@code CookieManager}/{@code PageNavigation}）」。
 * 录制语义与该旧类逐字迁移版完全一致（数据键、异常转换、零开销开关均不变）。
 *
 * <p>per-page 测试数据状态由持有的 {@link SerenityRecorder} 实例承载（与该旧类的 {@code serenityTestData} 等价）；
 * 原生操作（Layer A）走 {@link RecordingPageProxy} 装饰，本门面只负责框架自有方法（Layer B）。
 *
 * @apiNote framework-internal：业务 Page 不得直接调用；录制经持有的装饰 Page / 本门面透明获得。
 *      注意：本类注释中的 {@code SerenityBasePage} 均指<b>已删除的旧基类</b>；现行同名类型是
 *      {@link com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.SerenityBasePage}（页面能力契约接口）。
 */
public final class SerenityPageRecorder {

    private static final Logger logger = LoggerFactory.getLogger(SerenityPageRecorder.class);

    /** per-page 录制状态（serenityTestData 等），与逐字迁移版一致。 */
    private final SerenityRecorder recorder = new SerenityRecorder();

    /** 暴露 per-page 录制状态，供页面对象（{@link BasePage}）读取/清理测试数据。 */
    public SerenityRecorder recorder() {
        return recorder;
    }

    // ==================== element ====================

    public PageElement element(BasePage bp, String selector) {
        SerenityReporter.flushPendingApiOperations();
        if (VerboseLogging.isVerboseEnabled()) {
            VerboseLogging.logInfoIfVerbose(logger, "Creating element: {}", selector);
        }
        recorder.addSerenityTestData("lastActionElement", selector);
        // 直接构造 PageElement，避免回调 bp.element() 形成递归（BasePage.element 已委托本门面）
        return new PageElement(selector, bp);
    }

    // ==================== elementByRole / elementsByRole（角色定位，运行期 API） ====================

    /**
     * 元素创建的统一前置（与 {@link #element(BasePage, String)} 同语义）：
     * 刷新待报告 API 数据 + verbose 日志 + 记录「最近操作元素」（报告/失败诊断据此定位元素）。
     */
    private void recordElementCreation(String desc) {
        SerenityReporter.flushPendingApiOperations();
        if (VerboseLogging.isVerboseEnabled()) {
            VerboseLogging.logInfoIfVerbose(logger, "Creating role element: {}", desc);
        }
        recorder.addSerenityTestData("lastActionElement", desc);
    }

    /**
     * 角色定位的<b>唯一构造路径</b>：名称片段 + 选项 → 描述串 → 懒定位器。
     * 所有 {@code elementByRole*} 重载都汇聚到此，保证描述格式与录制语义只有一份实现。
     */
    private PageElement roleElement(BasePage bp, AriaRole role, String namePart, Supplier<Locator> locator,
                                    RoleOptions options) {
        String desc = RoleLocatorFactory.describe(role, namePart,
                RoleLocatorFactory.describeOptionsSuffix(options));
        recordElementCreation(desc);
        return new PageElement(locator, desc, bp);
    }

    /** 由「匹配方式 + 层级」构造选项（供不带状态三态的简易重载内部使用）。 */
    private static RoleOptions roleOptions(boolean exact, int level) {
        RoleOptions options = RoleOptions.defaults().level(level);
        return exact ? options.exact() : options.partial();
    }

    /** 纯角色定位（不限名称）。 */
    public PageElement elementByRole(BasePage bp, AriaRole role) {
        return roleElement(bp, role, RoleLocatorFactory.namePartRole(),
                () -> RoleLocatorFactory.byRole(bp, role), RoleOptions.defaults());
    }

    /** 角色 + 可访问名（精确匹配）。 */
    public PageElement elementByRole(BasePage bp, AriaRole role, String name) {
        return elementByRole(bp, role, name, RoleOptions.defaults());
    }

    /** 角色 + 可访问名（可指定精确/模糊）。 */
    public PageElement elementByRole(BasePage bp, AriaRole role, String name, boolean exact) {
        return elementByRole(bp, role, name, roleOptions(exact, 0));
    }

    /** 角色 + 可访问名 + 标题层级（仅 HEADING 有意义）。 */
    public PageElement elementByRole(BasePage bp, AriaRole role, String name, boolean exact, int level) {
        return elementByRole(bp, role, name, roleOptions(exact, level));
    }

    /**
     * 角色 + 可访问名 + 选项（层级 / 匹配方式 / 可访问状态三态，语义化）。
     * <pre>{@code
     * page.elementByRole(AriaRole.BUTTON, "Submit", RoleOptions.defaults().enabledOnly());
     * }</pre>
     */
    public PageElement elementByRole(BasePage bp, AriaRole role, String name, RoleOptions options) {
        final RoleOptions opts = options == null ? RoleOptions.defaults() : options;
        return roleElement(bp, role, RoleLocatorFactory.namePartName(name),
                () -> RoleLocatorFactory.byName(bp, role, name, opts.isExact(), opts.level(),
                        opts.disabledState(), opts.pressedState(), opts.expandedState()),
                opts);
    }

    /** 角色 + 正则可访问名（模板值/多语言正则）+ 标题层级（Playwright 原生正则语义，exact=false）。 */
    public PageElement elementByRole(BasePage bp, AriaRole role, Pattern namePattern, int level) {
        return elementByRole(bp, role, namePattern, roleOptions(false, level));
    }

    /**
     * 角色 + 正则可访问名 + 匹配方式 + 标题层级。
     * {@code exact=true} 时由框架把正则锚定为整串匹配（Playwright 原生对正则忽略 exact，
     * 详见 {@link RoleLocatorFactory#resolvePattern}）。
     */
    public PageElement elementByRole(BasePage bp, AriaRole role, Pattern namePattern, boolean exact, int level) {
        return elementByRole(bp, role, namePattern, roleOptions(exact, level));
    }

    /** 角色 + 正则可访问名 + 选项（层级 / 匹配方式 / 可访问状态三态，语义化）。 */
    public PageElement elementByRole(BasePage bp, AriaRole role, Pattern namePattern, RoleOptions options) {
        final RoleOptions opts = options == null ? RoleOptions.defaults() : options;
        final Pattern effective = RoleLocatorFactory.resolvePattern(namePattern, opts.isExact());
        return roleElement(bp, role, RoleLocatorFactory.namePartPattern(namePattern, opts.isExact()),
                () -> RoleLocatorFactory.byPattern(bp, role, effective, opts.level(),
                        opts.disabledState(), opts.pressedState(), opts.expandedState()),
                opts);
    }

    /**
     * 角色 + NLS 键（多语言）+ 标题层级：按 {@code roleFileClass} 上的类级 {@code @RoleFile}
     * 在运行时解析当前语言的可访问名，{@code NLSUtils.setLanguage("xx")} 后再次定位自动生效。
     *
     * @param roleFileClass 声明 {@code @RoleFile} 的页面类（业务 POJO 自身）
     * @param nlsKey        nls 键
     * @param level         标题层级（仅 HEADING 有意义）
     */
    public PageElement elementByRoleKey(BasePage bp, Class<?> roleFileClass, AriaRole role, String nlsKey, int level) {
        return elementByRoleKey(bp, roleFileClass, role, nlsKey, RoleOptions.defaults().level(level));
    }

    /**
     * 角色 + NLS 键（多语言）+ 选项（层级 / 匹配方式 / 可访问状态三态，语义化）。
     * <pre>{@code
     * page.elementByRoleKey(AriaRole.BUTTON, "favorite", RoleOptions.defaults().notPressed());
     * }</pre>
     *
     * @param roleFileClass 声明 {@code @RoleFile} 的页面类（业务 POJO 自身）
     * @param nlsKey        nls 键
     */
    public PageElement elementByRoleKey(BasePage bp, Class<?> roleFileClass, AriaRole role, String nlsKey,
                                        RoleOptions options) {
        final RoleOptions opts = options == null ? RoleOptions.defaults() : options;
        List<String> files = RoleLocatorFactory.resolveFiles(roleFileClass, "",
                "elementByRoleKey(role=" + role + ", key=" + nlsKey + ")");
        final Supplier<String> nlsValue = RoleLocatorFactory.nlsValueSupplier(files, nlsKey);
        return roleElement(bp, role, RoleLocatorFactory.namePartKey(files.get(0), nlsKey),
                () -> RoleLocatorFactory.byNlsValue(bp, role, nlsValue.get(), opts.isExact(), opts.level(),
                        opts.disabledState(), opts.pressedState(), opts.expandedState()),
                opts);
    }

    /** 角色定位的多元素集合（不限名称）。 */
    public PageElementList elementsByRole(BasePage bp, AriaRole role) {
        String desc = RoleLocatorFactory.describeRole(role);
        recordElementCreation(desc);
        return new PageElementList(() -> RoleLocatorFactory.byRole(bp, role), desc, bp);
    }

    /** 角色定位的多元素集合（按可访问名精确匹配）。 */
    public PageElementList elementsByRole(BasePage bp, AriaRole role, String name) {
        String desc = RoleLocatorFactory.describeName(role, name);
        recordElementCreation(desc);
        return new PageElementList(() -> RoleLocatorFactory.byName(bp, role, name, true, 0,
                RoleElement.State.ANY, RoleElement.State.ANY, RoleElement.State.ANY), desc, bp);
    }

    // ==================== getPage / getContext ====================

    public Page getPage(BasePage bp) {
        try {
            Page page = bp.getPageRaw();
            if (page != null) {
                recorder.addSerenityTestData("currentUrl", page.url());
                recorder.addSerenityTestData("pageTitle", page.title());
            }
            return page;
        } catch (Exception e) {
            logger.error("Failed to get page", e);
            throw new ConfigurationException("Failed to get page", e);
        }
    }

    public BrowserContext getContext(BasePage bp) {
        try {
            BrowserContext context = bp.getContextRaw();
            if (context != null) {
                recorder.addSerenityTestData("contextRetrieved", true);
                VerboseLogging.logDebugIfVerbose(logger, "BrowserContext retrieved successfully");
            }
            return context;
        } catch (Exception e) {
            logger.error("Failed to get browser context", e);
            throw new ConfigurationException("Failed to get browser context", e);
        }
    }

    // ==================== 导航 / 生命周期 ====================

    public void navigateTo(BasePage bp, String url) {
        try {
            SerenityReporter.flushPendingApiOperations();
            if (VerboseLogging.isVerboseEnabled()) {
                VerboseLogging.logInfoIfVerbose(logger, "Navigating to URL: {}", url);
            }
            recorder.addSerenityTestData("lastAction", "navigate");
            recorder.addSerenityTestData("navigateUrl", url);
            PageNavigation.navigateTo(bp, url);
        } catch (NavigationException e) {
            logger.debug("Navigation failed to URL: {}", url, e);
            throw e;
        } catch (Exception e) {
            logger.debug("Failed to navigate to URL: {}", url, e);
            throw new NavigationException(url, "Navigation failed: " + e.getMessage(), e);
        }
    }

    public void append(BasePage bp, String selector, String text) {
        recorder.record("append", selector + "=" + text, () -> bp.append(selector, text));
    }

    public void refresh(BasePage bp) {
        recorder.record("refresh", null, () -> bp.getPageRaw().reload());
    }

    public void back(BasePage bp) {
        recorder.record("back", null, () -> bp.getPageRaw().goBack());
    }

    public void forward(BasePage bp) {
        recorder.record("forward", null, () -> bp.getPageRaw().goForward());
    }

    // ==================== 可见性断言 ====================

    public void shouldBeVisible(BasePage bp, String selector) {
        try {
            SerenityReporter.flushPendingApiOperations();
            PageWaits.shouldBeVisible(bp, selector);
            recorder.recordVerification("elementVisible_" + selector, true);
        } catch (Exception e) {
            logger.debug("Failed to verify element should be visible: {}", selector, e);
            throw new ElementException("Failed to verify element should be visible: " + selector, e);
        }
    }

    public void shouldBeNotVisible(BasePage bp, String selector) {
        try {
            SerenityReporter.flushPendingApiOperations();
            PageWaits.shouldBeNotVisible(bp, selector);
            recorder.recordVerification("elementNotVisible_" + selector, true);
        } catch (Exception e) {
            logger.debug("Failed to verify element should not be visible: {}", selector, e);
            throw new ElementException("Failed to verify element should not be visible: " + selector, e);
        }
    }

    // ==================== 交互 ====================

    public void keyDown(BasePage bp, String selector, String key) {
        recorder.record("keyDown", selector + ":" + key, () -> PageInteractions.keyDown(bp, selector, key));
    }

    public void keyUp(BasePage bp, String selector, String key) {
        recorder.record("keyUp", selector + ":" + key, () -> PageInteractions.keyUp(bp, selector, key));
    }

    public void press(BasePage bp, String selector, String key) {
        recorder.record("press", selector + ":" + key, () -> PageInteractions.press(bp, selector, key));
    }

    public void acceptAlert(BasePage bp) {
        recorder.record("acceptAlert", null, () -> PageInteractions.acceptAlert(bp));
    }

    public void dismissAlert(BasePage bp) {
        recorder.record("dismissAlert", null, () -> PageInteractions.dismissAlert(bp));
    }

    public void setContent(BasePage bp, String html) {
        recorder.record("setContent", null, () -> PageNavigation.setContent(bp, html));
    }

    public void setViewportSize(BasePage bp, int w, int h) {
        recorder.record("setViewportSize", w + "x" + h, () -> PageViewport.setViewportSize(bp, w, h));
    }

    public void executeInFrame(BasePage bp, String frameName, Consumer<Frame> action) {
        recorder.record("executeInFrame", frameName,
                () -> PageFrameShadow.executeInFrame(bp, frameName, action));
    }

    // ==================== 状态 ====================

    public boolean isClosed(BasePage bp) {
        return recorder.recordAndReturn("isClosed", null, () -> PageInteractions.isClosed(bp));
    }

    public byte[] takeScreenshot(BasePage bp) {
        return recorder.recordAndReturn("screenshot", "fullPage",
                () -> PageInteractions.takeScreenshot(bp));
    }

    public byte[] takeElementScreenshot(BasePage bp, String s) {
        return recorder.recordAndReturn("elementScreenshot", s,
                () -> PageInteractions.takeElementScreenshot(bp, s));
    }

    public BoundingBox getElementBoundingBox(BasePage bp, String s) {
        return recorder.recordAndReturn("elementBoundingBox", s,
                () -> PageInteractions.getElementBoundingBox(bp, s));
    }

    // ==================== frame / shadow ====================

    public Frame getFrame(BasePage bp, String name) {
        return recorder.recordAndReturn("getFrame", name, () -> PageFrameShadow.getFrame(bp, name));
    }

    public Frame switchToFrame(BasePage bp, String nameOrSelector) {
        return recorder.recordAndReturn("switchToFrame", nameOrSelector,
                () -> PageFrameShadow.switchToFrame(bp, nameOrSelector));
    }

    public void switchToShadow(BasePage bp, String hostSelector) {
        recorder.record("switchToShadow", hostSelector,
                () -> PageFrameShadow.switchToShadow(bp, hostSelector));
    }

    public String switchToDefaultShadow(BasePage bp) {
        return recorder.recordAndReturn("switchToDefaultShadow", null,
                () -> PageFrameShadow.switchToDefaultShadow(bp));
    }

    public void switchToDefaultShadowAll(BasePage bp) {
        recorder.record("switchToDefaultShadowAll", null, () -> PageFrameShadow.switchToDefaultShadowAll(bp));
    }

    public Frame switchToFrameAndWait(BasePage bp, Runnable trigger, String nameOrSelector, int timeoutSecs) {
        return recorder.recordAndReturn("switchToFrameAndWait", nameOrSelector,
                () -> PageFrameShadow.switchToFrameAndWait(bp, trigger, nameOrSelector, timeoutSecs));
    }

    public Frame switchToFrameAndWait(BasePage bp, Runnable trigger, String nameOrSelector) {
        return recorder.recordAndReturn("switchToFrameAndWait", nameOrSelector,
                () -> PageFrameShadow.switchToFrameAndWait(bp, trigger, nameOrSelector));
    }

    public Frame switchToFrameAndWait(BasePage bp, String nameOrSelector, int timeoutSecs) {
        return recorder.recordAndReturn("switchToFrameAndWait", nameOrSelector,
                () -> PageFrameShadow.switchToFrameAndWait(bp, nameOrSelector, timeoutSecs));
    }

    public Frame switchToFrameAndWait(BasePage bp, String nameOrSelector) {
        return recorder.recordAndReturn("switchToFrameAndWait", nameOrSelector,
                () -> PageFrameShadow.switchToFrameAndWait(bp, nameOrSelector));
    }

    public void switchToDefaultContent(BasePage bp) {
        recorder.record("switchToDefaultContent", null, () -> PageFrameShadow.switchToDefaultContent(bp));
    }

    public List<Frame> getAllFrames(BasePage bp) {
        return recorder.recordAndReturn("getAllFrames", null, () -> PageFrameShadow.getAllFrames(bp));
    }

    // ==================== 脚本 / 源码 ====================

    public Object executeJavaScript(BasePage bp, String script, Object... args) {
        return recorder.recordAndReturn("executeJavaScript", script,
                () -> PageInteractions.executeJavaScript(bp, script, args));
    }

    public String getPageSource(BasePage bp) {
        return recorder.recordAndReturn("getPageSource", null, () -> PageInteractions.getPageSource(bp));
    }

    public int getPageSize(BasePage bp) {
        return recorder.recordAndReturn("getPageSize", null, () -> PageInteractions.getPageSize(bp));
    }

    public void waitForTimeout(BasePage bp, int ms) {
        recorder.record("waitForTimeout", ms, () -> PageInteractions.waitForTimeout(bp, ms));
    }

    public void acceptAlert(BasePage bp, Runnable trigger) {
        recorder.record("acceptAlert", null, () -> PageInteractions.acceptAlert(bp, trigger));
    }

    public void dismissAlert(BasePage bp, Runnable trigger) {
        recorder.record("dismissAlert", null, () -> PageInteractions.dismissAlert(bp, trigger));
    }

    public void dumpAccessibilityRoles(BasePage bp) {
        recorder.record("dumpAccessibilityRoles", null, () -> PageAccessibility.dumpAccessibilityRoles(bp));
    }

    // ==================== 等待 ====================

    public void waitForNetworkIdle(BasePage bp, int to) {
        PageWaits.waitForNetworkIdle(bp, to);
        recorder.recordVerification("networkIdle", true);
    }

    public void waitForPageFullyLoaded(BasePage bp, int to) {
        PageWaits.waitForPageFullyLoaded(bp, to);
        recorder.recordVerification("pageFullyLoaded", true);
    }

    public void waitForDOMContentLoaded(BasePage bp, int to) {
        PageWaits.waitForDOMContentLoaded(bp, to);
        recorder.recordVerification("domContentLoaded", true);
    }

    // ==================== Cookie ====================

    public List<Cookie> getCookies(BasePage bp) {
        return recorder.recordAndReturn("getCookies", null, () -> CookieManager.getCookies(bp));
    }

    public List<Cookie> getCookies(BasePage bp, String url) {
        return recorder.recordAndReturn("getCookies", url, () -> CookieManager.getCookies(bp, url));
    }

    public List<Cookie> getCookies(BasePage bp, List<String> urls) {
        return recorder.recordAndReturn("getCookies", urls, () -> CookieManager.getCookies(bp, urls));
    }

    public Cookie getCookie(BasePage bp, String name) {
        return recorder.recordAndReturn("getCookie", name, () -> CookieManager.getCookie(bp, name));
    }

    public boolean hasCookie(BasePage bp, String name) {
        return recorder.recordAndReturn("hasCookie", name, () -> CookieManager.hasCookie(bp, name));
    }

    public void addCookie(BasePage bp, Cookie cookie) {
        recorder.record("addCookie", cookie.name, () -> CookieManager.addCookie(bp, cookie));
    }

    public void addCookies(BasePage bp, List<Cookie> cookies) {
        recorder.record("addCookies", "count=" + cookies.size(), () -> CookieManager.addCookies(bp, cookies));
    }

    public void deleteCookie(BasePage bp, String name) {
        recorder.record("deleteCookie", name, () -> CookieManager.deleteCookie(bp, name));
    }

    public void clearCookies(BasePage bp) {
        recorder.record("clearCookies", null, () -> CookieManager.clearCookies(bp));
    }

    public List<Cookie> getCookiesForCurrentPage(BasePage bp) {
        return recorder.recordAndReturn("getCookiesForCurrentPage", null,
                () -> CookieManager.getCookiesForCurrentPage(bp));
    }

    // ==================== 导航重试 / 重试 ====================

    public void navigateToWithRetry(BasePage bp, String url, int r) {
        SerenityReporter.flushPendingApiOperations();
        PageNavigation.navigateToWithRetry(bp, url, r);
        recorder.addSerenityTestData("navigateToWithRetry", "completed");
    }

    public void retry(BasePage bp, Runnable op, int maxR, int interval, String desc) {
        SerenityReporter.flushPendingApiOperations();
        PageWaits.retry(bp, op, maxR, interval, desc);
        recorder.addSerenityTestData("retry_" + desc, "completed");
    }

    public void retry(BasePage bp, Runnable op, String desc) {
        SerenityReporter.flushPendingApiOperations();
        PageWaits.retry(bp, op, desc);
        recorder.addSerenityTestData("retry_" + desc, "completed");
    }

    public boolean retryWithValidation(BasePage bp, Runnable op, BooleanSupplier v, int maxR, int interval, String desc) {
        SerenityReporter.flushPendingApiOperations();
        boolean result = PageWaits.retryWithValidation(bp, op, v, maxR, interval, desc);
        recorder.recordVerification("retryWithValidation_" + desc, result);
        return result;
    }

    // ==================== Locator 工厂（framework-internal，完整覆盖 BasePage 全部重载） ====================

    public Locator byAltText(BasePage bp, String altText) {
        return recorder.recordAndReturn("byAltText", altText, () -> LocatorFactory.byAltText(bp, altText));
    }

    public Locator byRole(BasePage bp, AriaRole role) {
        return recorder.recordAndReturn("byRole", role, () -> LocatorFactory.byRole(bp, role));
    }

    public Locator byRole(BasePage bp, AriaRole role, String name) {
        return recorder.recordAndReturn("byRole", role, () -> LocatorFactory.byRole(bp, role, name));
    }

    public Locator byRole(BasePage bp, AriaRole role, Pattern namePattern) {
        return recorder.recordAndReturn("byRole", role, () -> LocatorFactory.byRole(bp, role, namePattern));
    }

    public Locator byRole(BasePage bp, AriaRole role, String name, boolean exact) {
        return recorder.recordAndReturn("byRole", role, () -> LocatorFactory.byRole(bp, role, name, exact));
    }

    public Locator byRole(BasePage bp, AriaRole role, String name, boolean exact, int level) {
        return recorder.recordAndReturn("byRole", role, () -> LocatorFactory.byRole(bp, role, name, exact, level));
    }

    public Locator byRole(BasePage bp, AriaRole role, Pattern namePattern, int level) {
        return recorder.recordAndReturn("byRole", role, () -> LocatorFactory.byRole(bp, role, namePattern, level));
    }

    public Locator byRole(BasePage bp, AriaRole role, String name, boolean exact, int level,
                          RoleElement.State disabled, RoleElement.State pressed, RoleElement.State expanded) {
        return recorder.recordAndReturn("byRole", role,
                () -> LocatorFactory.byRole(bp, role, name, exact, level, disabled, pressed, expanded));
    }

    public Locator byRole(BasePage bp, AriaRole role, Pattern namePattern, int level,
                          RoleElement.State disabled, RoleElement.State pressed, RoleElement.State expanded) {
        return recorder.recordAndReturn("byRole", role,
                () -> LocatorFactory.byRole(bp, role, namePattern, level, disabled, pressed, expanded));
    }

    public Locator byTitle(BasePage bp, String title) {
        return recorder.recordAndReturn("byTitle", title, () -> LocatorFactory.byTitle(bp, title));
    }

    public Locator byTestId(BasePage bp, String testId) {
        return recorder.recordAndReturn("byTestId", testId, () -> LocatorFactory.byTestId(bp, testId));
    }

    public Locator byText(BasePage bp, String text) {
        return recorder.recordAndReturn("byText", text, () -> LocatorFactory.byText(bp, text));
    }

    public Locator byText(BasePage bp, String text, boolean exact) {
        return recorder.recordAndReturn("byText", text, () -> LocatorFactory.byText(bp, text, exact));
    }

    public Locator byAltText(BasePage bp, String altText, boolean exact) {
        return recorder.recordAndReturn("byAltText", altText, () -> LocatorFactory.byAltText(bp, altText, exact));
    }

    public Locator byTitle(BasePage bp, String title, boolean exact) {
        return recorder.recordAndReturn("byTitle", title, () -> LocatorFactory.byTitle(bp, title, exact));
    }

    public Locator byPlaceholder(BasePage bp, String placeholder) {
        return recorder.recordAndReturn("byPlaceholder", placeholder, () -> LocatorFactory.byPlaceholder(bp, placeholder));
    }

    public Locator byPlaceholder(BasePage bp, String placeholder, boolean exact) {
        return recorder.recordAndReturn("byPlaceholder", placeholder,
                () -> LocatorFactory.byPlaceholder(bp, placeholder, exact));
    }

    public Locator byLabel(BasePage bp, String label) {
        return recorder.recordAndReturn("byLabel", label, () -> LocatorFactory.byLabel(bp, label));
    }

    public Locator byLabel(BasePage bp, String label, boolean exact) {
        return recorder.recordAndReturn("byLabel", label, () -> LocatorFactory.byLabel(bp, label, exact));
    }

    public Locator byText(BasePage bp, Pattern text) {
        return recorder.recordAndReturn("byText", text, () -> LocatorFactory.byText(bp, text));
    }

    public Locator byAltText(BasePage bp, Pattern altText) {
        return recorder.recordAndReturn("byAltText", altText, () -> LocatorFactory.byAltText(bp, altText));
    }

    public Locator byTitle(BasePage bp, Pattern title) {
        return recorder.recordAndReturn("byTitle", title, () -> LocatorFactory.byTitle(bp, title));
    }

    public Locator byPlaceholder(BasePage bp, Pattern placeholder) {
        return recorder.recordAndReturn("byPlaceholder", placeholder, () -> LocatorFactory.byPlaceholder(bp, placeholder));
    }

    public Locator byLabel(BasePage bp, Pattern label) {
        return recorder.recordAndReturn("byLabel", label, () -> LocatorFactory.byLabel(bp, label));
    }
}
