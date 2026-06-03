package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.impl;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.BoundingBox;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ConfigurationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementOperationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.NavigationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.TimeoutException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SerenityReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.Consumer;

/**
 * Serenity 基础页面类。
 * 继承自 BasePage，通过 Serenity 测试数据记录和验证标记做轻量增强。
 *
 * <h3>设计原则</h3>
 * 本类不复制父类方法——通过 {@link #record(String, Object, Runnable)} 和
 * {@link #recordAndReturn(String, Object, Supplier)} 两个 reusable interceptor
 * 消除所有冗余 {@code @Override super.xxx() + addSerenityTestData(...)} 模式。
 *
 * @see BasePage
 */
public abstract class SerenityBasePage extends BasePage {

    private static final Logger logger = LoggerFactory.getLogger(SerenityBasePage.class);

    // 存储Serenity测试数据
    private final Map<String, Object> serenityTestData = new HashMap<>();

    /**
     * 是否启用详细日志记录（每个操作都记 info 日志 + 存 Map）。
     * 默认关闭以提升大量操作时的性能。通过系统属性 serenity.verbose.logging=true 开启。
     */
    private static final boolean VERBOSE_LOGGING = Boolean.parseBoolean(
        System.getProperty("serenity.verbose.logging", "false"));

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
        if (VERBOSE_LOGGING) logger.info("[Serenity] {}", action);
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
            if (VERBOSE_LOGGING) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "Initializing Serenity Base Page");
            }
            addSerenityTestData("pageInitialized", true);
            addSerenityTestData("pageClass", this.getClass().getSimpleName());
        } catch (Exception e) {
            logger.error("Failed to initialize Serenity Base Page", e);
            throw new ConfigurationException("Failed to initialize Serenity Base Page", e);
        }
    }

    // ==================== 需要特殊异常处理的 Override（保留） ====================

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
                LoggingConfigUtil.logDebugIfVerbose(logger, "BrowserContext retrieved successfully");
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
     * 仅在 VERBOSE_LOGGING 开启时才写入 HashMap，成功路径零开销。
     */
    protected void addSerenityTestData(String key, Object value) {
        if (!VERBOSE_LOGGING) return;
        try {
            serenityTestData.put(key, value);
            LoggingConfigUtil.logDebugIfVerbose(logger, "Added Serenity test data: {} = {}", key, value);
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

    // ==================== 有特殊异常处理逻辑的 Override（保留） ====================

    @Override
    public void click(String selector) {
        try {
            SerenityReporter.flushPendingApiOperations();
            if (VERBOSE_LOGGING) logger.info("[Serenity] Clicking element: {}", selector);
            addSerenityTestData("lastAction", "click");
            addSerenityTestData("lastActionElement", selector);
            super.click(selector);
        } catch (ElementOperationException e) {
            logger.debug("Failed to click element: {}", selector, e);
            throw e;
        } catch (Exception e) {
            logger.debug("Failed to click element: {}", selector, e);
            throw new ElementOperationException("click", selector, "Failed to click element: " + selector, e);
        }
    }

    @Override
    public void type(String selector, String text) {
        try {
            SerenityReporter.flushPendingApiOperations();
            if (VERBOSE_LOGGING) logger.info("[Serenity] Typing text '{}' into element: {}", text, selector);
            addSerenityTestData("lastAction", "type");
            addSerenityTestData("lastActionElement", selector);
            addSerenityTestData("lastActionValue", text);
            super.type(selector, text);
        } catch (Exception e) {
            logger.debug("Failed to type text '{}' into element: {}", text, selector, e);
            throw new ElementException("Failed to type text '" + text + "' into element: " + selector, e);
        }
    }

    @Override
    public void navigateTo(String url) {
        try {
            SerenityReporter.flushPendingApiOperations();
            if (VERBOSE_LOGGING) logger.info("[Serenity] Navigating to URL: {}", url);
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

    // ==================== 时间范围操作 + 验证（保留，有验证逻辑） ====================

    public boolean performActionWithTimeout(Runnable action, Supplier<Boolean> validation, int timeoutSeconds, String actionDescription) {
        try {
            SerenityReporter.flushPendingApiOperations();
            boolean result = super.performActionWithTimeout(action, validation, timeoutSeconds, actionDescription);
            recordVerification("action_" + actionDescription, result);
            return result;
        } catch (Exception e) {
            logger.debug("Failed to perform action with timeout: {}", actionDescription, e);
            throw new TimeoutException("Failed to perform action with timeout: " + actionDescription, e);
        }
    }

    public boolean getPageSourceContains(String text) {
        try {
            SerenityReporter.flushPendingApiOperations();
            boolean result = super.getPageSourceContains(text);
            recordVerification("pageSourceContains_" + text, result);
            return result;
        } catch (Exception e) {
            logger.debug("Failed to check if page source contains text: {}", text, e);
            throw new ElementException("Failed to check if page source contains text: " + text, e);
        }
    }

    public String getAttributeValue(String selector, String attributeName, String expectedValue) {
        try {
            SerenityReporter.flushPendingApiOperations();
            String attributeValue = super.getAttributeValue(selector, attributeName, expectedValue);
            recordVerification("attribute_" + selector + "_" + attributeName, true);
            return attributeValue;
        } catch (Exception e) {
            logger.debug("Failed to verify attribute value for element: {}", selector, e);
            throw new ElementException("Failed to verify attribute value for element: " + selector, e);
        }
    }

    // ==================== 通过 Interceptor 消除的冗余 Override（替换原 87 个方法） ====================

    // --- 简单操作（无返回值） ---
    @Override public void jsClick(String selector) { record("jsClick", selector, () -> super.jsClick(selector)); }
    @Override public void clear(String selector) { record("clear", selector, () -> super.clear(selector)); }
    @Override public void append(String selector, String text) { record("append", selector + "=" + text, () -> super.append(selector, text)); }
    @Override public void selectByVisibleText(String selector, String text) { record("selectByText", selector + "=" + text, () -> super.selectByVisibleText(selector, text)); }
    @Override public void check(String selector) { record("check", selector, () -> super.check(selector)); }
    @Override public void uncheck(String selector) { record("uncheck", selector, () -> super.uncheck(selector)); }
    @Override public void refresh() { record("refresh", null, super::refresh); }
    @Override public void back() { record("back", null, super::back); }
    @Override public void forward() { record("forward", null, super::forward); }
    @Override public void hover(String selector) { record("hover", selector, () -> super.hover(selector)); }
    @Override public void keyDown(String selector, String key) { record("keyDown", selector + ":" + key, () -> super.keyDown(selector, key)); }
    @Override public void keyUp(String selector, String key) { record("keyUp", selector + ":" + key, () -> super.keyUp(selector, key)); }
    @Override public void press(String selector, String key) { record("press", selector + ":" + key, () -> super.press(selector, key)); }
    @Override public void acceptAlert() { record("acceptAlert", null, super::acceptAlert); }
    @Override public void dismissAlert() { record("dismissAlert", null, super::dismissAlert); }
    @Override public void tap(String selector) { record("tap", selector, () -> super.tap(selector)); }
    @Override public void focus(String selector) { record("focus", selector, () -> super.focus(selector)); }
    @Override public void scrollToElementCenter(String selector) { record("scrollToElementCenter", selector, () -> super.scrollToElementCenter(selector)); }
    @Override public void scrollTo(String selector, int x, int y) { record("scrollTo", selector + "->" + x + "," + y, () -> super.scrollTo(selector, x, y)); }
    @Override public void scrollBy(String selector, int x, int y) { record("scrollBy", selector + "->" + x + "," + y, () -> super.scrollBy(selector, x, y)); }
    @Override public void scrollToBottomOf(String selector) { record("scrollToBottom", selector, () -> super.scrollToBottomOf(selector)); }
    @Override public void scrollToTopOf(String selector) { record("scrollToTop", selector, () -> super.scrollToTopOf(selector)); }
    @Override public void dragAndDrop(String src, String tgt) { record("dragAndDrop", src + "->" + tgt, () -> super.dragAndDrop(src, tgt)); }
    @Override public void switchToPage(int index) { record("switchToPage", index, () -> super.switchToPage(index)); }
    @Override public void switchToLatestPage() { record("switchToLatestPage", null, super::switchToLatestPage); }
    @Override public void closeCurrentPageAndSwitchBack() { record("closeCurrentPageAndSwitchBack", null, super::closeCurrentPageAndSwitchBack); }
    @Override public void bringToFront() { record("bringToFront", null, super::bringToFront); }
    @Override public void setContent(String html) { record("setContent", null, () -> super.setContent(html)); }
    @Override public void setViewportSize(int w, int h) { record("setViewportSize", w + "x" + h, () -> super.setViewportSize(w, h)); }
    @Override public void setInputFiles(String selector, String... paths) { record("setInputFiles", selector, () -> super.setInputFiles(selector, paths)); }
    @Override public void pause() { record("pause", null, super::pause); }

    // --- 状态检查（有返回值） ---
    @Override public boolean isChecked(String s) { return recordAndReturn("isChecked", s, () -> super.isChecked(s)); }
    @Override public boolean isEnabled(String s) { return recordAndReturn("isEnabled", s, () -> super.isEnabled(s)); }
    @Override public boolean isDisabled(String s) { return recordAndReturn("isDisabled", s, () -> super.isDisabled(s)); }
    @Override public boolean isVisible(String s) { return recordAndReturn("isVisible", s, () -> super.isVisible(s)); }
    @Override public boolean isHidden(String s) { return recordAndReturn("isHidden", s, () -> super.isHidden(s)); }
    @Override public boolean isClosed() { return recordAndReturn("isClosed", null, super::isClosed); }
    @Override public int getElementCount(String s) { return recordAndReturn("elementCount", s, () -> super.getElementCount(s)); }
    @Override public String getInputValue(String s) { return recordAndReturn("getInputValue", s, () -> super.getInputValue(s)); }
    @Override public String innerHTML(String s) { return recordAndReturn("innerHTML", s, () -> super.innerHTML(s)); }
    @Override public String textContent(String s) { return recordAndReturn("textContent", s, () -> super.textContent(s)); }
    @Override public byte[] takeScreenshot() { return recordAndReturn("screenshot", "fullPage", super::takeScreenshot); }
    @Override public byte[] takeElementScreenshot(String s) { return recordAndReturn("elementScreenshot", s, () -> super.takeElementScreenshot(s)); }
    @Override public BoundingBox getElementBoundingBox(String s) { return recordAndReturn("elementBoundingBox", s, () -> super.getElementBoundingBox(s)); }

    // --- Locator 构建 ---
    @Override public Locator byAltText(String text) { return recordAndReturn("byAltText", text, () -> super.byAltText(text)); }
    @Override public Locator byRole(AriaRole role) { return recordAndReturn("byRole", role, () -> super.byRole(role)); }
    @Override public Locator byTitle(String title) { return recordAndReturn("byTitle", title, () -> super.byTitle(title)); }
    @Override public Locator byTestId(String testId) { return recordAndReturn("byTestId", testId, () -> super.byTestId(testId)); }

    // --- Frame ---
    @Override public Frame getFrame(String name) { return recordAndReturn("getFrame", name, () -> super.getFrame(name)); }
    @Override public void executeInFrame(String fn, Consumer<Frame> action) { record("executeInFrame", fn, () -> super.executeInFrame(fn, action)); }

    // --- 等待操作 ---
    @Override public void waitForTimeout(int ms) { record("waitForTimeout", ms, () -> super.waitForTimeout(ms)); }
    @Override public void waitForElementExists(String s, int t) { super.waitForElementExists(s, t); recordVerification("elementExists_" + s, true); }
    @Override public void waitForElementNotExists(String s, int t) { super.waitForElementNotExists(s, t); recordVerification("elementNotExists_" + s, true); }
    @Override public void waitForElementEditable(String s, int t) { super.waitForElementEditable(s, t); recordVerification("elementEditable_" + s, true); }
    @Override public void waitForElementDisabled(String s, int t) { super.waitForElementDisabled(s, t); recordVerification("elementDisabled_" + s, true); }
    @Override public void waitForElementEnabled(String s, int t) { super.waitForElementEnabled(s, t); recordVerification("elementEnabled_" + s, true); }
    @Override public void waitForElementChecked(String s, int t) { super.waitForElementChecked(s, t); recordVerification("elementChecked_" + s, true); }
    @Override public void waitForElementNotChecked(String s, int t) { super.waitForElementNotChecked(s, t); recordVerification("elementNotChecked_" + s, true); }
    @Override public void waitForElementCount(String s, int c, int to) { super.waitForElementCount(s, c, to); recordVerification("elementCount_" + s, true); }
    @Override public void waitForElementCountAtLeast(String s, int c, int to) { super.waitForElementCountAtLeast(s, c, to); recordVerification("elementCountAtLeast_" + s, true); }
    @Override public void waitForNetworkIdle(int to) { super.waitForNetworkIdle(to); recordVerification("networkIdle", true); }
    @Override public void waitForPageFullyLoaded(int to) { super.waitForPageFullyLoaded(to); recordVerification("pageFullyLoaded", true); }
    @Override public void waitForDOMContentLoaded(int to) { super.waitForDOMContentLoaded(to); recordVerification("domContentLoaded", true); }
    @Override public void waitForUrlEquals(String url, int to) { super.waitForUrlEquals(url, to); recordVerification("urlEquals", true); }
    @Override public void waitForUrlStartsWith(String pfx, int to) { super.waitForUrlStartsWith(pfx, to); recordVerification("urlStartsWith", true); }
    @Override public void waitForCustomCondition(Supplier<Boolean> c, int to, String desc) { super.waitForCustomCondition(c, to, desc); recordVerification("custom_" + desc, true); }

    // --- 带重试的方法 ---
    @Override public void waitForVisibleWithRetry(String s, int t, int r) { SerenityReporter.flushPendingApiOperations(); super.waitForVisibleWithRetry(s, t, r); recordVerification("waitVisibleWithRetry_" + s, true); }
    @Override public void waitForHiddenWithRetry(String s, int t, int r) { SerenityReporter.flushPendingApiOperations(); super.waitForHiddenWithRetry(s, t, r); recordVerification("waitHiddenWithRetry_" + s, true); }
    @Override public void clickWithRetry(String s, int r) { SerenityReporter.flushPendingApiOperations(); super.clickWithRetry(s, r); addSerenityTestData("clickWithRetry_" + s, "completed"); }
    @Override public void typeWithRetry(String s, String t, int r) { SerenityReporter.flushPendingApiOperations(); super.typeWithRetry(s, t, r); addSerenityTestData("typeWithRetry_" + s, "completed"); }
    @Override public void navigateToWithRetry(String url, int r) { SerenityReporter.flushPendingApiOperations(); super.navigateToWithRetry(url, r); addSerenityTestData("navigateToWithRetry", "completed"); }
    @Override public void retry(Runnable op, int maxR, int interval, String desc) { SerenityReporter.flushPendingApiOperations(); super.retry(op, maxR, interval, desc); addSerenityTestData("retry_" + desc, "completed"); }
    @Override public void retry(Runnable op, String desc) { SerenityReporter.flushPendingApiOperations(); super.retry(op, desc); addSerenityTestData("retry_" + desc, "completed"); }
    @Override public boolean retryWithValidation(Runnable op, BooleanSupplier v, int maxR, int interval, String desc) {
        SerenityReporter.flushPendingApiOperations();
        boolean result = super.retryWithValidation(op, v, maxR, interval, desc);
        recordVerification("retryWithValidation_" + desc, result);
        return result;
    }
}
