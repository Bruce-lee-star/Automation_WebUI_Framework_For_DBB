package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.impl;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.Cookie;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ConfigurationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.NavigationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.CookieManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.LocatorFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.PageAccessibility;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.PageFrameShadow;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.PageInteractions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.PageViewport;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.delegate.PageNavigation;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.delegate.PageWaits;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.SerenityReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Serenity 基础页面类。
 * 继承自 {@link BasePage}，通过 Serenity 测试数据记录和验证标记做轻量增强。
 *
 * <h3>设计原则</h3>
 * 本类不复制父类逻辑——通过 {@link #record(String, Object, Runnable)} 和
 * {@link #recordAndReturn(String, Object, Supplier)} 两个 reusable interceptor
 * 消除所有冗余 {@code @Override super.xxx() + addSerenityTestData(...)} 模式。
 *
 * <p><b>WEB-P1-2 Phase 6（废弃方法删除）</b>：{@code BasePage} 已将 frame / cookie / viewport /
 * interactions / locator 工厂等域的实现下沉到各自的委派类（{@code PageFrameShadow} /
 * {@code CookieManager} / {@code PageViewport} / {@code PageInteractions} /
 * {@code LocatorFactory} / {@code PageWaits} / {@code PageNavigation}）。
 * 本类不再 {@code @Override} 那些已从 {@code BasePage} 移除的壳方法，而是直接委派到对应委派类
 * 并保留 {@code record} 录制，从而在不破坏 Serenity 报告录制的前提下，使 {@code BasePage}
 * 收敛为精简门面（公开方法 ≤40）。
 *
 * @see BasePage
 */
public abstract class SerenityBasePage extends BasePage {

    private static final Logger logger = LoggerFactory.getLogger(SerenityBasePage.class);

    // 存储Serenity测试数据
    private final Map<String, Object> serenityTestData = new HashMap<>();

    /**
     * 是否启用详细日志记录（每个操作都记 info 日志 + 存 Map）。
     * 默认关闭以提升大量操作时的性能；经 VerboseLogging 读取 serenity.logging=VERBOSE/TRACE 开启。
     */
    private static boolean isVerboseLogging() {
        return VerboseLogging.isVerboseEnabled();
    }

    // ==================== Interceptor 层覆盖 element() ====================

    /**
     * 覆盖父类 {@link BasePage#element(String)}，为新风格的链式调用
     * {@code myPage.element("#btn").click()} 提供 Serenity 报告记录能力。
     *
     * <p>与 {@link #record} / {@link #recordAndReturn} 不同，此方法仅记录上下文信息
     * （选择的元素），实际操作由调用方在返回的 PageElement 上执行时记录。
     */
    @Override
    public PageElement element(String selector) {
        SerenityReporter.flushPendingApiOperations();
        if (isVerboseLogging()) logger.info("[Serenity] Creating element: {}", selector);
        addSerenityTestData("lastActionElement", selector);
        return super.element(selector);
    }

    // ==================== Reusable Interceptors（消除 87 个冗余 Override） ====================

    /**
     * 无返回值操作的 Serenity 记录拦截器。
     * 替代所有 {@code @Override void xxx() { super.xxx(); addSerenityTestData(...); }} 模式。
     *
     * <p>操作前刷新 Route Handler 产生的待报告 API 数据到 Serenity 报告。
     * Handler 在 Playwright 事件线程/异步池线程中无法直接写入 Serenity 报告
     * （ThreadLocal 隔离），通过此拦截器在主线程上批量写入。
     */
    private void record(String action, Object detail, Runnable operation) {
        SerenityReporter.flushPendingApiOperations();
        if (isVerboseLogging()) logger.info("[Serenity] {}", action);
        addSerenityTestData(action, detail != null ? detail : "executed");
        operation.run();
    }

    /**
     * 有返回值操作的 Serenity 记录拦截器。
     * 替代所有 {@code @Override T xxx() { T r = super.xxx(); addSerenityTestData(...); return r; }} 模式。
     *
     * <p>操作前刷新 Route Handler 产生的待报告 API 数据到 Serenity 报告。
     */
    private <T> T recordAndReturn(String action, Object detail, Supplier<T> operation) {
        SerenityReporter.flushPendingApiOperations();
        T result = operation.get();
        addSerenityTestData(action, detail != null ? detail : result);
        return result;
    }

    /**
     * 验证操作的 Serenity 记录拦截器。
     * 自动记录 PASS/FAIL 验证结果。
     *
     * <p>验证前刷新 Route Handler 产生的待报告 API 数据。
     */
    private void recordVerification(String verificationName, boolean passed) {
        SerenityReporter.flushPendingApiOperations();
        String status = passed ? "PASS" : "FAIL";
        addSerenityTestData("verification_" + verificationName, status);
        logger.debug(" Verification '{}': {}", verificationName, status);
    }

    // ==================== 构造 ====================

    public SerenityBasePage() {
        super();
        try {
            if (isVerboseLogging()) {
                VerboseLogging.logInfoIfVerbose(logger, "Initializing Serenity Base Page");
            }
            addSerenityTestData("pageInitialized", true);
            addSerenityTestData("pageClass", this.getClass().getSimpleName());
        } catch (Exception e) {
            logger.error("Failed to initialize Serenity Base Page", e);
            throw new ConfigurationException("Failed to initialize Serenity Base Page", e);
        }
    }

    // ==================== 需要特殊异常处理的 Override（保留，父类仍提供实现） ====================

    @Override
    public Page getPage() {
        try {
            Page page = super.getPage();
            if (page != null) {
                addSerenityTestData("currentUrl", page.url());
                addSerenityTestData("pageTitle", page.title());
            }
            return page;
        } catch (Exception e) {
            logger.error("Failed to get page", e);
            throw new ConfigurationException("Failed to get page", e);
        }
    }

    @Override
    public BrowserContext getContext() {
        try {
            BrowserContext context = super.getContext();
            if (context != null) {
                addSerenityTestData("contextRetrieved", true);
                VerboseLogging.logDebugIfVerbose(logger, "BrowserContext retrieved successfully");
            }
            return context;
        } catch (Exception e) {
            logger.error("Failed to get browser context", e);
            throw new ConfigurationException("Failed to get browser context", e);
        }
    }

    // ==================== Serenity 数据管理 ====================

    /**
     * 添加测试数据到本地存储。
     * 仅在详细日志开启时才写入 HashMap，成功路径零开销。
     */
    protected void addSerenityTestData(String key, Object value) {
        if (!isVerboseLogging()) return;
        try {
            serenityTestData.put(key, value);
            VerboseLogging.logDebugIfVerbose(logger, "Added Serenity test data: {} = {}", key, value);
        } catch (Exception e) {
            logger.error("Failed to add Serenity test data: {} = {}", key, value, e);
            throw new ConfigurationException("Failed to add Serenity test data: " + key + " = " + value, e);
        }
    }

    protected Object getSerenityTestData(String key) {
        return serenityTestData.get(key);
    }

    public Map<String, Object> getSerenityTestDataMap() {
        return new HashMap<>(serenityTestData);
    }

    public void clearSerenityTestData() {
        serenityTestData.clear();
        logger.debug("Cleared all Serenity test data");
    }

    // ==================== Serenity 特有验证方法 ====================

    public boolean verifyPageTitleContains(String expectedText) {
        try {
            SerenityReporter.flushPendingApiOperations();
            String actualTitle = getTitle();
            boolean contains = actualTitle.contains(expectedText);
            addSerenityTestData("titleVerification", contains ? "PASS" : "FAIL");
            addSerenityTestData("expectedTitle", expectedText);
            addSerenityTestData("actualTitle", actualTitle);
            return contains;
        } catch (Exception e) {
            logger.debug("Failed to verify page title contains: {}", expectedText, e);
            throw new ElementException("Failed to verify page title contains: " + expectedText, e);
        }
    }

    public boolean verifyPageTitleEquals(String expectedText) {
        try {
            SerenityReporter.flushPendingApiOperations();
            String actualTitle = getTitle();
            boolean equals = actualTitle.equals(expectedText);
            addSerenityTestData("titleVerification", equals ? "PASS" : "FAIL");
            addSerenityTestData("expectedTitle", expectedText);
            addSerenityTestData("actualTitle", actualTitle);
            return equals;
        } catch (Exception e) {
            logger.debug("Failed to verify page title equals: {}", expectedText, e);
            throw new ElementException("Failed to verify page title equals: " + expectedText, e);
        }
    }

    public boolean verifyUrlContains(String expectedText) {
        try {
            SerenityReporter.flushPendingApiOperations();
            String actualUrl = getCurrentUrl();
            boolean contains = actualUrl.contains(expectedText);
            addSerenityTestData("urlVerification", contains ? "PASS" : "FAIL");
            addSerenityTestData("expectedUrlFragment", expectedText);
            addSerenityTestData("actualUrl", actualUrl);
            return contains;
        } catch (Exception e) {
            logger.debug("Failed to verify URL contains: {}", expectedText, e);
            throw new ElementException("Failed to verify URL contains: " + expectedText, e);
        }
    }

    // ==================== 有特殊异常处理逻辑的 Override（保留，父类仍提供实现） ====================

    @Override
    public void navigateTo(String url) {
        try {
            SerenityReporter.flushPendingApiOperations();
            if (isVerboseLogging()) logger.info("[Serenity] Navigating to URL: {}", url);
            addSerenityTestData("lastAction", "navigate");
            addSerenityTestData("navigateUrl", url);
            super.navigateTo(url);
        } catch (NavigationException e) {
            logger.debug("Navigation failed to URL: {}", url, e);
            throw e;
        } catch (Exception e) {
            logger.debug("Failed to navigate to URL: {}", url, e);
            throw new NavigationException(url, "Navigation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void append(String selector, String text) {
        record("append", selector + "=" + text, () -> super.append(selector, text));
    }

    @Override
    public void refresh() { record("refresh", null, super::refresh); }

    @Override
    public void back() { record("back", null, super::back); }

    @Override
    public void forward() { record("forward", null, super::forward); }

    @Override
    public void switchToPage(int index) { record("switchToPage", index, () -> super.switchToPage(index)); }

    @Override
    public void closeCurrentPage() { record("closeCurrentPage", null, super::closeCurrentPage); }

    @Override
    public void pause() { record("pause", null, super::pause); }

    // ==================== 页面可见性断言（父类仍提供实现，保留录制） ====================

    public void shouldBeVisible(String selector) {
        try {
            SerenityReporter.flushPendingApiOperations();
            super.shouldBeVisible(selector);
            recordVerification("elementVisible_" + selector, true);
        } catch (Exception e) {
            logger.debug("Failed to verify element should be visible: {}", selector, e);
            throw new ElementException("Failed to verify element should be visible: " + selector, e);
        }
    }

    public void shouldBeNotVisible(String selector) {
        try {
            SerenityReporter.flushPendingApiOperations();
            super.shouldBeNotVisible(selector);
            recordVerification("elementNotVisible_" + selector, true);
        } catch (Exception e) {
            logger.debug("Failed to verify element should not be visible: {}", selector, e);
            throw new ElementException("Failed to verify element should not be visible: " + selector, e);
        }
    }

    public boolean getPageSourceContains(String text) {
        try {
            SerenityReporter.flushPendingApiOperations();
            boolean result = PageInteractions.getPageSourceContains(this, text);
            recordVerification("pageSourceContains_" + text, result);
            return result;
        } catch (Exception e) {
            logger.debug("Failed to check if page source contains text: {}", text, e);
            throw new ElementException("Failed to check if page source contains text: " + text, e);
        }
    }

    // ==================== 委派到域类的录制方法（BasePage 已移除对应壳，此处自包含实现） ====================

    // --- 简单操作（无返回值） ---

    public void keyDown(String selector, String key) {
        record("keyDown", selector + ":" + key, () -> PageInteractions.keyDown(this, selector, key));
    }

    public void keyUp(String selector, String key) {
        record("keyUp", selector + ":" + key, () -> PageInteractions.keyUp(this, selector, key));
    }

    public void press(String selector, String key) {
        record("press", selector + ":" + key, () -> PageInteractions.press(this, selector, key));
    }

    public void acceptAlert() {
        record("acceptAlert", null, () -> PageInteractions.acceptAlert(this));
    }

    public void dismissAlert() {
        record("dismissAlert", null, () -> PageInteractions.dismissAlert(this));
    }

    public void scrollTo(String selector, int x, int y) {
        record("scrollTo", selector + "->" + x + "," + y, () -> PageViewport.scrollTo(this, selector, x, y));
    }

    public void scrollBy(String selector, int x, int y) {
        record("scrollBy", selector + "->" + x + "," + y, () -> PageViewport.scrollBy(this, selector, x, y));
    }

    public void scrollToBottomOf(String selector) {
        record("scrollToBottom", selector, () -> PageViewport.scrollToBottomOf(this, selector));
    }

    public void scrollToTopOf(String selector) {
        record("scrollToTop", selector, () -> PageViewport.scrollToTopOf(this, selector));
    }

    public void bringToFront() {
        record("bringToFront", null, () -> PageInteractions.bringToFront(this));
    }

    public void setContent(String html) {
        record("setContent", null, () -> PageNavigation.setContent(this, html));
    }

    public void setViewportSize(int w, int h) {
        record("setViewportSize", w + "x" + h, () -> PageViewport.setViewportSize(this, w, h));
    }

    public void executeInFrame(String frameName, Consumer<Frame> action) {
        record("executeInFrame", frameName, () -> PageFrameShadow.executeInFrame(this, frameName, action));
    }

    // --- 状态检查（有返回值） ---

    public boolean isClosed() {
        return recordAndReturn("isClosed", null, () -> PageInteractions.isClosed(this));
    }

    public byte[] takeScreenshot() {
        return recordAndReturn("screenshot", "fullPage", () -> PageInteractions.takeScreenshot(this));
    }

    public byte[] takeElementScreenshot(String s) {
        return recordAndReturn("elementScreenshot", s, () -> PageInteractions.takeElementScreenshot(this, s));
    }

    public BoundingBox getElementBoundingBox(String s) {
        return recordAndReturn("elementBoundingBox", s, () -> PageInteractions.getElementBoundingBox(this, s));
    }

    // --- Frame / Shadow ---

    public Frame getFrame(String name) {
        return recordAndReturn("getFrame", name, () -> PageFrameShadow.getFrame(this, name));
    }

    public Frame switchToFrame(String nameOrSelector) {
        return recordAndReturn("switchToFrame", nameOrSelector,
                () -> PageFrameShadow.switchToFrame(this, nameOrSelector));
    }

    public Frame switchToFrame(int index) {
        return recordAndReturn("switchToFrame", index, () -> PageFrameShadow.switchToFrame(this, index));
    }

    public void switchToShadow(String hostSelector) {
        record("switchToShadow", hostSelector, () -> PageFrameShadow.switchToShadow(this, hostSelector));
    }

    public String switchToDefaultShadow() {
        return recordAndReturn("switchToDefaultShadow", null, () -> PageFrameShadow.switchToDefaultShadow(this));
    }

    public void switchToDefaultShadowAll() {
        record("switchToDefaultShadowAll", null, () -> PageFrameShadow.switchToDefaultShadowAll(this));
    }

    public Frame switchToFrameAndWait(Runnable trigger, String nameOrSelector, int timeoutSecs) {
        return recordAndReturn("switchToFrameAndWait", nameOrSelector,
                () -> PageFrameShadow.switchToFrameAndWait(this, trigger, nameOrSelector, timeoutSecs));
    }

    public Frame switchToFrameAndWait(Runnable trigger, String nameOrSelector) {
        return recordAndReturn("switchToFrameAndWait", nameOrSelector,
                () -> PageFrameShadow.switchToFrameAndWait(this, trigger, nameOrSelector));
    }

    public Frame switchToFrameAndWait(String nameOrSelector, int timeoutSecs) {
        return recordAndReturn("switchToFrameAndWait", nameOrSelector,
                () -> PageFrameShadow.switchToFrameAndWait(this, nameOrSelector, timeoutSecs));
    }

    public Frame switchToFrameAndWait(String nameOrSelector) {
        return recordAndReturn("switchToFrameAndWait", nameOrSelector,
                () -> PageFrameShadow.switchToFrameAndWait(this, nameOrSelector));
    }

    public void switchToDefaultContent() {
        record("switchToDefaultContent", null, () -> PageFrameShadow.switchToDefaultContent(this));
    }

    public List<Frame> getAllFrames() {
        return recordAndReturn("getAllFrames", null, () -> PageFrameShadow.getAllFrames(this));
    }

    // --- 交互 / 源码 / 脚本 ---

    public Object executeJavaScript(String script, Object... args) {
        return recordAndReturn("executeJavaScript", script, () -> PageInteractions.executeJavaScript(this, script, args));
    }

    public String getPageSource() {
        return recordAndReturn("getPageSource", null, () -> PageInteractions.getPageSource(this));
    }

    public int getPageSize() {
        return recordAndReturn("getPageSize", null, () -> PageInteractions.getPageSize(this));
    }

    public void waitForTimeout(int ms) {
        record("waitForTimeout", ms, () -> PageInteractions.waitForTimeout(this, ms));
    }

    public void acceptAlert(Runnable trigger) {
        record("acceptAlert", null, () -> PageInteractions.acceptAlert(this, trigger));
    }

    public void dismissAlert(Runnable trigger) {
        record("dismissAlert", null, () -> PageInteractions.dismissAlert(this, trigger));
    }

    public void dumpAccessibilityRoles() {
        record("dumpAccessibilityRoles", null, () -> PageAccessibility.dumpAccessibilityRoles(this));
    }

    // --- 等待操作 ---

    public void waitForNetworkIdle(int to) {
        super.waitForNetworkIdle(to);
        recordVerification("networkIdle", true);
    }

    public void waitForPageFullyLoaded(int to) {
        super.waitForPageFullyLoaded(to);
        recordVerification("pageFullyLoaded", true);
    }

    public void waitForDOMContentLoaded(int to) {
        super.waitForDOMContentLoaded(to);
        recordVerification("domContentLoaded", true);
    }

    // --- Cookie 操作 ---

    public List<Cookie> getCookies() {
        return recordAndReturn("getCookies", null, () -> CookieManager.getCookies(this));
    }

    public List<Cookie> getCookies(String url) {
        return recordAndReturn("getCookies", url, () -> CookieManager.getCookies(this, url));
    }

    public List<Cookie> getCookies(List<String> urls) {
        return recordAndReturn("getCookies", urls, () -> CookieManager.getCookies(this, urls));
    }

    public Cookie getCookie(String name) {
        return recordAndReturn("getCookie", name, () -> CookieManager.getCookie(this, name));
    }

    public boolean hasCookie(String name) {
        return recordAndReturn("hasCookie", name, () -> CookieManager.hasCookie(this, name));
    }

    public void addCookie(Cookie cookie) {
        record("addCookie", cookie.name, () -> CookieManager.addCookie(this, cookie));
    }

    public void addCookies(List<Cookie> cookies) {
        record("addCookies", "count=" + cookies.size(), () -> CookieManager.addCookies(this, cookies));
    }

    public void deleteCookie(String name) {
        record("deleteCookie", name, () -> CookieManager.deleteCookie(this, name));
    }

    public void clearCookies() {
        record("clearCookies", null, () -> CookieManager.clearCookies(this));
    }

    public List<Cookie> getCookiesForCurrentPage() {
        return recordAndReturn("getCookiesForCurrentPage", null, () -> CookieManager.getCookiesForCurrentPage(this));
    }

    // --- 导航重试 / 重试 ---

    public void navigateToWithRetry(String url, int r) {
        SerenityReporter.flushPendingApiOperations();
        PageNavigation.navigateToWithRetry(this, url, r);
        addSerenityTestData("navigateToWithRetry", "completed");
    }

    public void retry(Runnable op, int maxR, int interval, String desc) {
        SerenityReporter.flushPendingApiOperations();
        PageWaits.retry(this, op, maxR, interval, desc);
        addSerenityTestData("retry_" + desc, "completed");
    }

    public void retry(Runnable op, String desc) {
        SerenityReporter.flushPendingApiOperations();
        PageWaits.retry(this, op, desc);
        addSerenityTestData("retry_" + desc, "completed");
    }

    public boolean retryWithValidation(Runnable op, BooleanSupplier v, int maxR, int interval, String desc) {
        SerenityReporter.flushPendingApiOperations();
        boolean result = PageWaits.retryWithValidation(this, op, v, maxR, interval, desc);
        recordVerification("retryWithValidation_" + desc, result);
        return result;
    }

    // ==================== Locator 工厂（framework-internal，完整覆盖 BasePage 全部重载） ====================

    public Locator byAltText(String altText) {
        return recordAndReturn("byAltText", altText, () -> LocatorFactory.byAltText(this, altText));
    }

    public Locator byRole(AriaRole role) {
        return recordAndReturn("byRole", role, () -> LocatorFactory.byRole(this, role));
    }

    public Locator byRole(AriaRole role, String name) {
        return recordAndReturn("byRole", role, () -> LocatorFactory.byRole(this, role, name));
    }

    public Locator byRole(AriaRole role, Pattern namePattern) {
        return recordAndReturn("byRole", role, () -> LocatorFactory.byRole(this, role, namePattern));
    }

    public Locator byRole(AriaRole role, String name, boolean exact) {
        return recordAndReturn("byRole", role, () -> LocatorFactory.byRole(this, role, name, exact));
    }

    public Locator byRole(AriaRole role, String name, boolean exact, int level) {
        return recordAndReturn("byRole", role, () -> LocatorFactory.byRole(this, role, name, exact, level));
    }

    public Locator byRole(AriaRole role, Pattern namePattern, int level) {
        return recordAndReturn("byRole", role, () -> LocatorFactory.byRole(this, role, namePattern, level));
    }

    public Locator byRole(AriaRole role, String name, boolean exact, int level,
                          RoleElement.State disabled, RoleElement.State pressed, RoleElement.State expanded) {
        return recordAndReturn("byRole", role,
                () -> LocatorFactory.byRole(this, role, name, exact, level, disabled, pressed, expanded));
    }

    public Locator byRole(AriaRole role, Pattern namePattern, int level,
                          RoleElement.State disabled, RoleElement.State pressed, RoleElement.State expanded) {
        return recordAndReturn("byRole", role,
                () -> LocatorFactory.byRole(this, role, namePattern, level, disabled, pressed, expanded));
    }

    public Locator byTitle(String title) {
        return recordAndReturn("byTitle", title, () -> LocatorFactory.byTitle(this, title));
    }

    public Locator byTestId(String testId) {
        return recordAndReturn("byTestId", testId, () -> LocatorFactory.byTestId(this, testId));
    }

    public Locator byText(String text) {
        return recordAndReturn("byText", text, () -> LocatorFactory.byText(this, text));
    }

    public Locator byText(String text, boolean exact) {
        return recordAndReturn("byText", text, () -> LocatorFactory.byText(this, text, exact));
    }

    public Locator byAltText(String altText, boolean exact) {
        return recordAndReturn("byAltText", altText, () -> LocatorFactory.byAltText(this, altText, exact));
    }

    public Locator byTitle(String title, boolean exact) {
        return recordAndReturn("byTitle", title, () -> LocatorFactory.byTitle(this, title, exact));
    }

    public Locator byPlaceholder(String placeholder) {
        return recordAndReturn("byPlaceholder", placeholder, () -> LocatorFactory.byPlaceholder(this, placeholder));
    }

    public Locator byPlaceholder(String placeholder, boolean exact) {
        return recordAndReturn("byPlaceholder", placeholder,
                () -> LocatorFactory.byPlaceholder(this, placeholder, exact));
    }

    public Locator byLabel(String label) {
        return recordAndReturn("byLabel", label, () -> LocatorFactory.byLabel(this, label));
    }

    public Locator byLabel(String label, boolean exact) {
        return recordAndReturn("byLabel", label, () -> LocatorFactory.byLabel(this, label, exact));
    }

    public Locator byText(Pattern text) {
        return recordAndReturn("byText", text, () -> LocatorFactory.byText(this, text));
    }

    public Locator byAltText(Pattern altText) {
        return recordAndReturn("byAltText", altText, () -> LocatorFactory.byAltText(this, altText));
    }

    public Locator byTitle(Pattern title) {
        return recordAndReturn("byTitle", title, () -> LocatorFactory.byTitle(this, title));
    }

    public Locator byPlaceholder(Pattern placeholder) {
        return recordAndReturn("byPlaceholder", placeholder, () -> LocatorFactory.byPlaceholder(this, placeholder));
    }

    public Locator byLabel(Pattern label) {
        return recordAndReturn("byLabel", label, () -> LocatorFactory.byLabel(this, label));
    }
}
