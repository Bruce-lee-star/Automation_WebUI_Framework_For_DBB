package com.hsbc.cmb.dbb.hk.automation.page.base.impl;

import com.microsoft.playwright.Page;
import com.hsbc.cmb.dbb.hk.automation.framework.utils.LoggingConfigUtil;

import com.hsbc.cmb.dbb.hk.automation.framework.exceptions.ConfigurationException;
import com.hsbc.cmb.dbb.hk.automation.framework.exceptions.ElementException;
import com.hsbc.cmb.dbb.hk.automation.framework.exceptions.ElementNotClickableException;
import com.hsbc.cmb.dbb.hk.automation.framework.exceptions.TimeoutException;
import com.hsbc.cmb.dbb.hk.automation.page.base.BasePage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

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
                logger, "🚀 Initializing Serenity Base Page");
            
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
     * 添加测试数据到本地存储
     */
    protected void addSerenityTestData(String key, Object value) {
        try {
            serenityTestData.put(key, value);
            
            LoggingConfigUtil.logDebugIfVerbose(
                logger, "📝 Added Serenity test data: {} = {}", key, value);
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
        logger.debug("✅ Verification '{}': {}", verificationName, status);
    }

    // ==================== 时间范围操作方法 ====================

    /**
     * 在指定时间范围内等待元素可见（Serenity集成版）
     * @param selector 元素选择器
     * @param timeoutMillis 最大超时时间（毫秒）
     * @return 如果元素在指定时间内可见则返回true，否则返回false
     */
    public boolean waitForElementVisibleWithinTime(String selector, int timeoutMillis) {
        try {
            boolean result = super.waitForElementVisibleWithinTime(selector, timeoutMillis);
            recordPageVerification("elementVisible_" + selector, result);
            return result;
        } catch (Exception e) {
            logger.error("Failed to wait for element to be visible within time: {}", selector, e);
            throw new TimeoutException("Failed to wait for element to be visible within time: " + selector, e);
        }
    }

    /**
     * 在指定时间范围内等待元素隐藏（Serenity集成版）
     * @param selector 元素选择器
     * @param timeoutMillis 最大超时时间（毫秒）
     * @return 如果元素在指定时间内隐藏则返回true，否则返回false
     */
    public boolean waitForElementHiddenWithinTime(String selector, int timeoutMillis) {
        try {
            boolean result = super.waitForElementHiddenWithinTime(selector, timeoutMillis);
            recordPageVerification("elementHidden_" + selector, result);
            return result;
        } catch (Exception e) {
            logger.error("Failed to wait for element to be hidden within time: {}", selector, e);
            throw new TimeoutException("Failed to wait for element to be hidden within time: " + selector, e);
        }
    }

    /**
     * 在指定时间范围内等待元素可点击（Serenity集成版）
     * @param selector 元素选择器
     * @param timeoutMillis 最大超时时间（毫秒）
     * @return 如果元素在指定时间内可点击则返回true，否则返回false
     */
    public boolean waitForElementClickableWithinTime(String selector, int timeoutMillis) {
        try {
            boolean result = super.waitForElementClickableWithinTime(selector, timeoutMillis);
            recordPageVerification("elementClickable_" + selector, result);
            return result;
        } catch (Exception e) {
            logger.error("Failed to wait for element to be clickable within time: {}", selector, e);
            throw new TimeoutException("Failed to wait for element to be clickable within time: " + selector, e);
        }
    }

    /**
     * 在指定时间范围内等待页面标题包含文本（Serenity集成版）
     * @param expectedTitle 期望的标题文本
     * @param timeoutMillis 最大超时时间（毫秒）
     * @return 如果页面标题在指定时间内包含文本则返回true，否则返回false
     */
    public boolean waitForTitleContainsWithinTime(String expectedTitle, int timeoutMillis) {
        try {
            boolean result = super.waitForTitleContainsWithinTime(expectedTitle, timeoutMillis);
            recordPageVerification("titleContains_" + expectedTitle, result);
            return result;
        } catch (Exception e) {
            logger.error("Failed to wait for title to contain within time: {}", expectedTitle, e);
            throw new TimeoutException("Failed to wait for title to contain within time: " + expectedTitle, e);
        }
    }

    /**
     * 在指定时间范围内等待URL包含文本（Serenity集成版）
     * @param expectedUrlFragment 期望的URL片段
     * @param timeoutMillis 最大超时时间（毫秒）
     * @return 如果URL在指定时间内包含片段则返回true，否则返回false
     */
    public boolean waitForUrlContainsWithinTime(String expectedUrlFragment, int timeoutMillis) {
        try {
            boolean result = super.waitForUrlContainsWithinTime(expectedUrlFragment, timeoutMillis);
            recordPageVerification("urlContains_" + expectedUrlFragment, result);
            return result;
        } catch (Exception e) {
            logger.error("Failed to wait for URL to contain within time: {}", expectedUrlFragment, e);
            throw new TimeoutException("Failed to wait for URL to contain within time: " + expectedUrlFragment, e);
        }
    }

    /**
     * 在指定时间范围内执行操作并验证结果（Serenity集成版）
     * @param action 要执行的操作
     * @param validation 验证逻辑
     * @param timeoutMillis 最大超时时间（毫秒）
     * @param actionDescription 操作描述
     * @return 如果在指定时间内操作成功并验证通过则返回true，否则返回false
     */
    public boolean performActionWithTimeout(Runnable action, Supplier<Boolean> validation, int timeoutMillis, String actionDescription) {
        try {
            boolean result = super.performActionWithTimeout(action, validation, timeoutMillis, actionDescription);
            recordPageVerification("action_" + actionDescription, result);
            return result;
        } catch (Exception e) {
            logger.error("Failed to perform action with timeout: {}", actionDescription, e);
            throw new TimeoutException("Failed to perform action with timeout: " + actionDescription, e);
        }
    }

    /**
     * 断言元素应该可见（Serenity集成版）
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
     * @param selector 元素选择器
     * @param attributeName 属性名
     * @param expectedValue 期望的属性值
     * @throws RuntimeException 如果属性值不匹配期望值
     */
    public void getAttributeValue(String selector, String attributeName, String expectedValue) {
        try {
            super.getAttributeValue(selector, attributeName, expectedValue);
            recordPageVerification("attribute_" + selector + "_" + attributeName, true);
        } catch (Exception e) {
            logger.error("Failed to verify attribute value for element: {}", selector, e);
            throw new ElementException("Failed to verify attribute value for element: " + selector, e);
        }
    }
}
