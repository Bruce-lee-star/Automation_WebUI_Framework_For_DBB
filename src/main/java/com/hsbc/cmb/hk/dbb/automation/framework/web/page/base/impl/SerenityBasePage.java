package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.impl;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.BoundingBox;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ConfigurationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementNotClickableException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.TimeoutException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.Predicate;
import java.util.function.Consumer;

/**
 * Serenity 基础页面类
 * 继承自BasePage，添加了Serenity BDD集成功能
 */
public abstract class SerenityBasePage extends BasePage {

    private static final Logger logger = LoggerFactory.getLogger(SerenityBasePage.class);

    // 存储Serenity测试数据
    private final Map<String, Object> serenityTestData = new HashMap<>();

    /**
     * 构造函数
     */
    public SerenityBasePage() {
        // 调用父类构造函数
        super();
        try {
            LoggingConfigUtil.logInfoIfVerbose(
                    logger, "Initializing Serenity Base Page");

            // 记录页面初始化到Serenity报告
            addSerenityTestData("pageInitialized", true);
            addSerenityTestData("pageClass", this.getClass().getSimpleName());
        } catch (Exception e) {
            logger.error("Failed to initialize Serenity Base Page", e);
            throw new ConfigurationException("Failed to initialize Serenity Base Page", e);
        }
    }

    /**
     * 获取当前页面的Page对象
     * 覆盖父类方法，添加Serenity集成
     */
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

    /**
     * 获取BrowserContext对象
     * 覆盖父类方法，公开访问权限并添加Serenity集成
     */
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

    /**
     * 添加测试数据到本地存储
     */
    protected void addSerenityTestData(String key, Object value) {
        try {
            serenityTestData.put(key, value);

            LoggingConfigUtil.logDebugIfVerbose(
                    logger, "Added Serenity test data: {} = {}", key, value);
        } catch (Exception e) {
            logger.error("Failed to add Serenity test data: {} = {}", key, value, e);
            throw new ConfigurationException("Failed to add Serenity test data: " + key + " = " + value, e);
        }
    }

    /**
     * 获取Serenity测试数据
     */
    protected Object getSerenityTestData(String key) {
        return serenityTestData.get(key);
    }

    /**
     * 验证页面标题是否包含指定文本
     */
    public boolean verifyPageTitleContains(String expectedText) {
        try {
            String actualTitle = getTitle();
            boolean contains = actualTitle.contains(expectedText);

            if (contains) {
                addSerenityTestData("titleVerification", "PASS");
                addSerenityTestData("expectedTitle", expectedText);
                addSerenityTestData("actualTitle", actualTitle);
            } else {
                addSerenityTestData("titleVerification", "FAIL");
                addSerenityTestData("expectedTitle", expectedText);
                addSerenityTestData("actualTitle", actualTitle);
            }

            return contains;
        } catch (Exception e) {
            logger.error("Failed to verify page title contains: {}", expectedText, e);
            throw new ElementException("Failed to verify page title contains: " + expectedText, e);
        }
    }

    /**
     * 验证页面标题是否等于指定文本
     */
    public boolean verifyPageTitleEquals(String expectedText) {
        try {
            String actualTitle = getTitle();
            boolean equals = actualTitle.equals(expectedText);

            if (equals) {
                addSerenityTestData("titleVerification", "PASS");
                addSerenityTestData("expectedTitle", expectedText);
                addSerenityTestData("actualTitle", actualTitle);
            } else {
                addSerenityTestData("titleVerification", "FAIL");
                addSerenityTestData("expectedTitle", expectedText);
                addSerenityTestData("actualTitle", actualTitle);
            }

            return equals;
        } catch (Exception e) {
            logger.error("Failed to verify page title equals: {}", expectedText, e);
            throw new ElementException("Failed to verify page title equals: " + expectedText, e);
        }
    }

    /**
     * 验证当前URL是否包含指定文本
     */
    public boolean verifyUrlContains(String expectedText) {
        try {
            String actualUrl = getCurrentUrl();
            boolean contains = actualUrl.contains(expectedText);

            if (contains) {
                addSerenityTestData("urlVerification", "PASS");
                addSerenityTestData("expectedUrlFragment", expectedText);
                addSerenityTestData("actualUrl", actualUrl);
            } else {
                addSerenityTestData("urlVerification", "FAIL");
                addSerenityTestData("expectedUrlFragment", expectedText);
                addSerenityTestData("actualUrl", actualUrl);
            }

            return contains;
        } catch (Exception e) {
            logger.error("Failed to verify URL contains: {}", expectedText, e);
            throw new ElementException("Failed to verify URL contains: " + expectedText, e);
        }
    }

    /**
     * 点击元素 - 覆盖父类方法，添加Serenity集成
     */
    @Override
    public void click(String selector) {
        try {
            logger.info("[Serenity] Clicking element: {}", selector);
            addSerenityTestData("lastAction", "click");
            addSerenityTestData("lastActionElement", selector);
            super.click(selector);
        } catch (Exception e) {
            logger.error("Failed to click element: {}", selector, e);
            throw new ElementNotClickableException(selector, e);
        }
    }

    @Override
    public void jsClick(String selector) {
        logger.info("[Serenity] JS Clicking element: {}", selector);
        addSerenityTestData("lastAction", "jsClick");
        addSerenityTestData("lastActionElement", selector);
        super.jsClick(selector);
    }

    /**
     * 输入文本 - 覆盖父类方法，添加Serenity集成
     */
    @Override
    public void type(String selector, String text) {
        try {
            logger.info("[Serenity] Typing text '{}' into element: {}", text, selector);
            addSerenityTestData("lastAction", "type");
            addSerenityTestData("lastActionElement", selector);
            addSerenityTestData("lastActionValue", text);
            super.type(selector, text);
        } catch (Exception e) {
            logger.error("Failed to type text '{}' into element: {}", text, selector, e);
            throw new ElementException("Failed to type text '" + text + "' into element: " + selector, e);
        }
    }

    @Override
    public void clear(String selector) {
        logger.info("[Serenity] Clearing element: {}", selector);
        addSerenityTestData("lastAction", "clear");
        addSerenityTestData("lastActionElement", selector);
        super.clear(selector);
    }

    @Override
    public void append(String selector, String text) {
        logger.info("[Serenity] Appending text '{}' into element: {}", text, selector);
        addSerenityTestData("lastAction", "append");
        addSerenityTestData("lastActionElement", selector);
        addSerenityTestData("lastActionValue", text);
        super.append(selector, text);
    }

    @Override
    public String getInputValue(String selector) {
        String value = super.getInputValue(selector);
        addSerenityTestData("getInputValue", selector);
        return value;
    }

    /**
     * 导航到指定URL - 覆盖父类方法，添加Serenity集成
     */
    @Override
    public void navigateTo(String url) {
        try {
            logger.info("[Serenity] Navigating to URL: {}", url);
            addSerenityTestData("lastAction", "navigate");
            addSerenityTestData("navigateUrl", url);
            super.navigateTo(url);
        } catch (Exception e) {
            logger.error("Failed to navigate to URL: {}", url, e);
            throw new ElementException("Failed to navigate to URL: " + url, e);
        }
    }

    @Override
    public void selectByVisibleText(String selector, String text) {
        logger.info("[Serenity] Selecting text '{}' on element: {}", text, selector);
        addSerenityTestData("selectByText", text);
        addSerenityTestData("selectElement", selector);
        super.selectByVisibleText(selector, text);
    }

    @Override
    public void check(String selector) {
        logger.info("[Serenity] Checking element: {}", selector);
        addSerenityTestData("checkElement", selector);
        super.check(selector);
    }

    @Override
    public void uncheck(String selector) {
        logger.info("[Serenity] Unchecking element: {}", selector);
        addSerenityTestData("uncheckElement", selector);
        super.uncheck(selector);
    }

    @Override
    public boolean isChecked(String selector) {
        boolean result = super.isChecked(selector);
        addSerenityTestData("isChecked", selector + " = " + result);
        return result;
    }

    @Override
    public boolean isEnabled(String selector) {
        boolean result = super.isEnabled(selector);
        addSerenityTestData("isEnabled", selector + " = " + result);
        return result;
    }

    @Override
    public boolean isDisabled(String selector) {
        boolean result = super.isDisabled(selector);
        addSerenityTestData("isDisabled", selector + " = " + result);
        return result;
    }

    @Override
    public boolean isVisible(String selector) {
        boolean result = super.isVisible(selector);
        addSerenityTestData("isVisible", selector + " = " + result);
        return result;
    }

    @Override
    public int getElementCount(String selector) {
        int count = super.getElementCount(selector);
        addSerenityTestData("elementCount", selector + " = " + count);
        return count;
    }

    @Override
    public void refresh() {
        logger.info("[Serenity] Refreshing page");
        addSerenityTestData("lastAction", "refresh");
        super.refresh();
    }

    @Override
    public void back() {
        logger.info("[Serenity] Going back");
        addSerenityTestData("lastAction", "back");
        super.back();
    }

    @Override
    public void forward() {
        logger.info("[Serenity] Going forward");
        addSerenityTestData("lastAction", "forward");
        super.forward();
    }

    @Override
    public void hover(String selector) {
        logger.info("[Serenity] Hovering element: {}", selector);
        addSerenityTestData("hoverElement", selector);
        super.hover(selector);
    }

    @Override
    public void keyDown(String selector, String key) {
        addSerenityTestData("keyDown", key);
        super.keyDown(selector, key);
    }

    @Override
    public void keyUp(String selector, String key) {
        addSerenityTestData("keyUp", key);
        super.keyUp(selector, key);
    }

    @Override
    public void press(String selector, String key) {
        addSerenityTestData("pressKey", key);
        super.press(selector, key);
    }

    @Override
    public void acceptAlert() {
        logger.info("[Serenity] Accepting alert");
        addSerenityTestData("alertAction", "accept");
        super.acceptAlert();
    }

    @Override
    public void dismissAlert() {
        logger.info("[Serenity] Dismissing alert");
        addSerenityTestData("alertAction", "dismiss");
        super.dismissAlert();
    }

    @Override
    public byte[] takeScreenshot() {
        byte[] screenshot = super.takeScreenshot();
        addSerenityTestData("screenshot", "fullPage");
        return screenshot;
    }

    @Override
    public byte[] takeElementScreenshot(String selector) {
        byte[] screenshot = super.takeElementScreenshot(selector);
        addSerenityTestData("elementScreenshot", selector);
        return screenshot;
    }

    @Override
    public Frame getFrame(String name) {
        Frame frame = super.getFrame(name);
        addSerenityTestData("getFrame", name);
        return frame;
    }

    @Override
    public void executeInFrame(String frameName, Consumer<Frame> action) {
        addSerenityTestData("executeInFrame", frameName);
        super.executeInFrame(frameName, action);
    }

    @Override
    public void waitForTimeout(int milliseconds) {
        addSerenityTestData("waitForTimeout", milliseconds);
        super.waitForTimeout(milliseconds);
    }

    /**
     * 获取Serenity测试数据映射
     */
    public Map<String, Object> getSerenityTestDataMap() {
        return new HashMap<>(serenityTestData);
    }

    /**
     * 清除Serenity测试数据
     */
    public void clearSerenityTestData() {
        serenityTestData.clear();
        logger.debug("🧹 Cleared all Serenity test data");
    }

    /**
     * 记录页面验证信息
     */
    protected void recordPageVerification(String verificationName, boolean passed) {
        String status = passed ? "PASS" : "FAIL";
        addSerenityTestData("verification_" + verificationName, status);
        logger.debug(" Verification '{}': {}", verificationName, status);
    }

    // ==================== 时间范围操作方法 ====================

    /**
     * 在指定时间范围内等待元素可见（Serenity集成版）
     *
     * @param selector       元素选择器
     * @param timeoutSeconds 最大超时时间（秒）
     */
    @Override
    public void waitForElementVisibleWithinTime(String selector, int timeoutSeconds) {
        super.waitForElementVisibleWithinTime(selector, timeoutSeconds);
        recordPageVerification("elementVisible_" + selector, true);
    }

    /**
     * 在指定时间范围内等待元素隐藏（Serenity集成版）
     *
     * @param selector       元素选择器
     * @param timeoutSeconds 最大超时时间（秒）
     */
    @Override
    public void waitForElementHiddenWithinTime(String selector, int timeoutSeconds) {
        super.waitForElementHiddenWithinTime(selector, timeoutSeconds);
        recordPageVerification("elementHidden_" + selector, true);
    }

    /**
     * 在指定时间范围内等待元素可点击（Serenity集成版）
     *
     * @param selector       元素选择器
     * @param timeoutSeconds 最大超时时间（秒）
     */
    @Override
    public void waitForElementClickableWithinTime(String selector, int timeoutSeconds) {
        super.waitForElementClickableWithinTime(selector, timeoutSeconds);
        recordPageVerification("elementClickable_" + selector, true);
    }

    /**
     * 在指定时间范围内等待页面标题包含文本（Serenity集成版）
     *
     * @param expectedTitle  期望的标题文本
     * @param timeoutSeconds 最大超时时间（秒）
     */
    @Override
    public void waitForTitleContainsWithinTime(String expectedTitle, int timeoutSeconds) {
        super.waitForTitleContainsWithinTime(expectedTitle, timeoutSeconds);
        recordPageVerification("titleContains_" + expectedTitle, true);
    }

    /**
     * 在指定时间范围内等待URL包含文本（Serenity集成版）
     *
     * @param expectedUrlFragment 期望的URL片段
     * @param timeoutSeconds      最大超时时间（秒）
     */
    @Override
    public void waitForUrlContainsWithinTime(String expectedUrlFragment, int timeoutSeconds) {
        super.waitForUrlContainsWithinTime(expectedUrlFragment, timeoutSeconds);
        recordPageVerification("urlContains_" + expectedUrlFragment, true);
    }

    /**
     * 在指定时间范围内执行操作并验证结果（Serenity集成版）
     *
     * @param action            要执行的操作
     * @param validation        验证逻辑
     * @param timeoutSeconds    最大超时时间（秒）
     * @param actionDescription 操作描述
     * @return 如果在指定时间内操作成功并验证通过则返回true，否则返回false
     */
    public boolean performActionWithTimeout(Runnable action, Supplier<Boolean> validation, int timeoutSeconds, String actionDescription) {
        try {
            boolean result = super.performActionWithTimeout(action, validation, timeoutSeconds, actionDescription);
            recordPageVerification("action_" + actionDescription, result);
            return result;
        } catch (Exception e) {
            logger.error("Failed to perform action with timeout: {}", actionDescription, e);
            throw new TimeoutException("Failed to perform action with timeout: " + actionDescription, e);
        }
    }

    /**
     * 断言元素应该可见（Serenity集成版）
     *
     * @param selector 元素选择器
     * @throws RuntimeException 如果元素不可见
     */
    public void shouldBeVisible(String selector) {
        try {
            super.shouldBeVisible(selector);
            recordPageVerification("elementVisible_" + selector, true);
        } catch (Exception e) {
            logger.error("Failed to verify element should be visible: {}", selector, e);
            throw new ElementException("Failed to verify element should be visible: " + selector, e);
        }
    }

    /**
     * 断言元素不应该可见（Serenity集成版）
     *
     * @param selector 元素选择器
     * @throws RuntimeException 如果元素可见
     */
    public void shouldBeNotVisible(String selector) {
        try {
            super.shouldBeNotVisible(selector);
            recordPageVerification("elementNotVisible_" + selector, true);
        } catch (Exception e) {
            logger.error("Failed to verify element should not be visible: {}", selector, e);
            throw new ElementException("Failed to verify element should not be visible: " + selector, e);
        }
    }

    /**
     * 检查页面源代码是否包含指定文本（Serenity集成版）
     *
     * @param text 要检查的文本
     * @return 如果页面源代码包含指定文本则返回true，否则返回false
     */
    public boolean getPageSourceContains(String text) {
        try {
            boolean result = super.getPageSourceContains(text);
            recordPageVerification("pageSourceContains_" + text, result);
            return result;
        } catch (Exception e) {
            logger.error("Failed to check if page source contains text: {}", text, e);
            throw new ElementException("Failed to check if page source contains text: " + text, e);
        }
    }

    /**
     * 获取元素属性值并断言其值（Serenity集成版）
     *
     * @param selector      元素选择器
     * @param attributeName 属性名
     * @param expectedValue 期望的属性值
     * @throws RuntimeException 如果属性值不匹配期望值
     */
    public String getAttributeValue(String selector, String attributeName, String expectedValue) {
        try {
            String attributeValue = super.getAttributeValue(selector, attributeName, expectedValue);
            recordPageVerification("attribute_" + selector + "_" + attributeName, true);
            return attributeValue;
        } catch (Exception e) {
            logger.error("Failed to verify attribute value for element: {}", selector, e);
            throw new ElementException("Failed to verify attribute value for element: " + selector, e);
        }
    }

    // ==================== 重试机制（Serenity集成版）====================

    @Override
    public void retry(Runnable operation, int maxRetries, int retryIntervalMs, String operationDescription) {
        super.retry(operation, maxRetries, retryIntervalMs, operationDescription);
        addSerenityTestData("retry_" + operationDescription, "completed");
    }

    @Override
    public void retry(Runnable operation, String operationDescription) {
        super.retry(operation, operationDescription);
        addSerenityTestData("retry_" + operationDescription, "completed");
    }

    @Override
    public boolean retryWithValidation(Runnable operation, Predicate<Void> validation,
                                       int maxRetries, int retryIntervalMs, String operationDescription) {
        boolean result = super.retryWithValidation(operation, validation, maxRetries, retryIntervalMs, operationDescription);
        recordPageVerification("retryWithValidation_" + operationDescription, result);
        return result;
    }

    // ==================== 扩展等待方法（Serenity集成版）====================

    @Override
    public void waitForElementExists(String selector, int timeoutSeconds) {
        super.waitForElementExists(selector, timeoutSeconds);
        recordPageVerification("elementExists_" + selector, true);
    }

    @Override
    public void waitForElementNotExists(String selector, int timeoutSeconds) {
        super.waitForElementNotExists(selector, timeoutSeconds);
        recordPageVerification("elementNotExists_" + selector, true);
    }

    @Override
    public void waitForElementEditable(String selector, int timeoutSeconds) {
        super.waitForElementEditable(selector, timeoutSeconds);
        recordPageVerification("elementEditable_" + selector, true);
    }

    @Override
    public void waitForElementDisabled(String selector, int timeoutSeconds) {
        super.waitForElementDisabled(selector, timeoutSeconds);
        recordPageVerification("elementDisabled_" + selector, true);
    }

    @Override
    public void waitForElementEnabled(String selector, int timeoutSeconds) {
        super.waitForElementEnabled(selector, timeoutSeconds);
        recordPageVerification("elementEnabled_" + selector, true);
    }

    @Override
    public void waitForElementAttributeEquals(String selector, String attributeName, String expectedAttributeValue, int timeoutSeconds) {
        super.waitForElementAttributeEquals(selector, attributeName, expectedAttributeValue, timeoutSeconds);
        recordPageVerification("attributeEquals_" + selector + "_" + attributeName, true);
    }

    @Override
    public void waitForElementAttributeContains(String selector, String attributeName, String expectedAttributeValue, int timeoutSeconds) {
        super.waitForElementAttributeContains(selector, attributeName, expectedAttributeValue, timeoutSeconds);
        recordPageVerification("attributeContains_" + selector + "_" + attributeName, true);
    }

    @Override
    public void waitForElementTextContains(String selector, String expectedText, int timeoutSeconds) {
        super.waitForElementTextContains(selector, expectedText, timeoutSeconds);
        recordPageVerification("textContains_" + selector, true);
    }

    @Override
    public void waitForElementTextEquals(String selector, String expectedText, int timeoutSeconds) {
        super.waitForElementTextEquals(selector, expectedText, timeoutSeconds);
        recordPageVerification("textEquals_" + selector, true);
    }

    @Override
    public void waitForElementCount(String selector, int expectedCount, int timeoutSeconds) {
        super.waitForElementCount(selector, expectedCount, timeoutSeconds);
        recordPageVerification("elementCount_" + selector, true);
    }

    @Override
    public void waitForElementCountAtLeast(String selector, int minimumCount, int timeoutSeconds) {
        super.waitForElementCountAtLeast(selector, minimumCount, timeoutSeconds);
        recordPageVerification("elementCountAtLeast_" + selector, true);
    }

    @Override
    public void waitForNetworkIdle(int timeoutSeconds) {
        super.waitForNetworkIdle(timeoutSeconds);
        recordPageVerification("networkIdle", true);
    }

    @Override
    public void waitForPageFullyLoaded(int timeoutSeconds) {
        super.waitForPageFullyLoaded(timeoutSeconds);
        recordPageVerification("pageFullyLoaded", true);
    }

    @Override
    public void waitForDOMContentLoaded(int timeoutSeconds) {
        super.waitForDOMContentLoaded(timeoutSeconds);
        recordPageVerification("domContentLoaded", true);
    }

    @Override
    public void waitForElementChecked(String selector, int timeoutSeconds) {
        super.waitForElementChecked(selector, timeoutSeconds);
        recordPageVerification("elementChecked_" + selector, true);
    }

    @Override
    public void waitForElementNotChecked(String selector, int timeoutSeconds) {
        super.waitForElementNotChecked(selector, timeoutSeconds);
        recordPageVerification("elementNotChecked_" + selector, true);
    }

    @Override
    public void waitForUrlEquals(String expectedUrl, int timeoutSeconds) {
        super.waitForUrlEquals(expectedUrl, timeoutSeconds);
        recordPageVerification("urlEquals", true);
    }

    @Override
    public void waitForUrlStartsWith(String expectedPrefix, int timeoutSeconds) {
        super.waitForUrlStartsWith(expectedPrefix, timeoutSeconds);
        recordPageVerification("urlStartsWith", true);
    }

    @Override
    public void waitForCustomCondition(Supplier<Boolean> condition, int timeoutSeconds, String conditionDescription) {
        super.waitForCustomCondition(condition, timeoutSeconds, conditionDescription);
        recordPageVerification("customCondition_" + conditionDescription, true);
    }

    // ==================== 带重试的便捷方法（Serenity集成版）====================

    @Override
    public void waitForVisibleWithRetry(String selector, int timeoutSeconds, int maxRetries) {
        super.waitForVisibleWithRetry(selector, timeoutSeconds, maxRetries);
        recordPageVerification("waitForVisibleWithRetry_" + selector, true);
    }

    @Override
    public void waitForHiddenWithRetry(String selector, int timeoutSeconds, int maxRetries) {
        super.waitForHiddenWithRetry(selector, timeoutSeconds, maxRetries);
        recordPageVerification("waitForHiddenWithRetry_" + selector, true);
    }

    @Override
    public void clickWithRetry(String selector, int maxRetries) {
        super.clickWithRetry(selector, maxRetries);
        addSerenityTestData("clickWithRetry_" + selector, "completed");
    }

    @Override
    public void typeWithRetry(String selector, String text, int maxRetries) {
        super.typeWithRetry(selector, text, maxRetries);
        addSerenityTestData("typeWithRetry_" + selector, "completed");
    }

    @Override
    public void navigateToWithRetry(String url, int maxRetries) {
        super.navigateToWithRetry(url, maxRetries);
        addSerenityTestData("navigateToWithRetry", "completed");
    }

    // ==================== Web UI 操作（Serenity集成版）====================

    @Override
    public void switchToPage(int pageIndex) {
        super.switchToPage(pageIndex);
        addSerenityTestData("switchToPage", "index_" + pageIndex);
    }

    @Override
    public void switchToLatestPage() {
        super.switchToLatestPage();
        addSerenityTestData("switchToLatestPage", "completed");
    }

    @Override
    public void closeCurrentPageAndSwitchBack() {
        super.closeCurrentPageAndSwitchBack();
        addSerenityTestData("closeCurrentPageAndSwitchBack", "completed");
    }

    @Override
    public void dragAndDrop(String sourceSelector, String targetSelector) {
        super.dragAndDrop(sourceSelector, targetSelector);
        addSerenityTestData("dragAndDrop", "from_" + sourceSelector + "_to_" + targetSelector);
    }

    @Override
    public void scrollTo(String selector, int scrollX, int scrollY) {
        super.scrollTo(selector, scrollX, scrollY);
        addSerenityTestData("scrollTo", "element_" + selector + "_to_" + scrollX + "_" + scrollY);
    }

    @Override
    public void scrollToBottomOf(String selector) {
        super.scrollToBottomOf(selector);
        addSerenityTestData("scrollToBottom", "element_" + selector);
    }

    @Override
    public void scrollToTopOf(String selector) {
        super.scrollToTopOf(selector);
        addSerenityTestData("scrollToTop", "element_" + selector);
    }

    @Override
    public void scrollBy(String selector, int offsetX, int offsetY) {
        super.scrollBy(selector, offsetX, offsetY);
        addSerenityTestData("scrollBy", "element_" + selector + "_by_" + offsetX + "_" + offsetY);
    }

    @Override
    public BoundingBox getElementBoundingBox(String selector) {
        BoundingBox box = super.getElementBoundingBox(selector);
        addSerenityTestData("elementBoundingBox", "element_" + selector);
        return box;
    }

    @Override
    public void scrollToElementCenter(String targetSelector) {
        super.scrollToElementCenter(targetSelector);
        addSerenityTestData("scrollToElementCenter", targetSelector);
    }

    // ==================== 新增方法覆盖（Serenity集成版）====================

    @Override
    public void tap(String selector) {
        super.tap(selector);
        addSerenityTestData("tap", "element_" + selector);
    }

    @Override
    public void focus(String selector) {
        super.focus(selector);
        addSerenityTestData("focus", "element_" + selector);
    }

    @Override
    public String innerHTML(String selector) {
        String html = super.innerHTML(selector);
        addSerenityTestData("innerHTML", "element_" + selector);
        return html;
    }

    @Override
    public String textContent(String selector) {
        String text = super.textContent(selector);
        addSerenityTestData("textContent", "element_" + selector);
        return text;
    }

    @Override
    public boolean isHidden(String selector) {
        boolean hidden = super.isHidden(selector);
        addSerenityTestData("isHidden", "element_" + selector + "_" + hidden);
        return hidden;
    }

    @Override
    public boolean isClosed() {
        boolean closed = super.isClosed();
        addSerenityTestData("isClosed", closed);
        return closed;
    }

    @Override
    public void bringToFront() {
        super.bringToFront();
        addSerenityTestData("bringToFront", "completed");
    }

    @Override
    public void setContent(String html) {
        super.setContent(html);
        addSerenityTestData("setContent", "completed");
    }

    @Override
    public void setViewportSize(int width, int height) {
        super.setViewportSize(width, height);
        addSerenityTestData("setViewportSize", width + "x" + height);
    }

    @Override
    public void setInputFiles(String selector, String... filePaths) {
        super.setInputFiles(selector, filePaths);
        addSerenityTestData("setInputFiles", "element_" + selector);
    }

    @Override
    public Locator byAltText(String altText) {
        Locator locator = super.byAltText(altText);
        addSerenityTestData("byAltText", altText);
        return locator;
    }

    @Override
    public Locator byRole(AriaRole role) {
        Locator locator = super.byRole(role);
        addSerenityTestData("byRole", role.toString());
        return locator;
    }

    @Override
    public Locator byTitle(String title) {
        Locator locator = super.byTitle(title);
        addSerenityTestData("byTitle", title);
        return locator;
    }

    @Override
    public Locator byTestId(String testId) {
        Locator locator = super.byTestId(testId);
        addSerenityTestData("byTestId", testId);
        return locator;
    }
}