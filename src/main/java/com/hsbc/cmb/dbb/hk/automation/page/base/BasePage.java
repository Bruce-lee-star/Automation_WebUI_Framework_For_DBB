package com.hsbc.cmb.dbb.hk.automation.page.base;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.*;
import com.hsbc.cmb.dbb.hk.automation.framework.core.FrameworkCore;
import com.hsbc.cmb.dbb.hk.automation.framework.lifecycle.PlaywrightManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.microsoft.playwright.options.BoundingBox;

import com.hsbc.cmb.dbb.hk.automation.framework.exceptions.ElementException;
import com.hsbc.cmb.dbb.hk.automation.framework.exceptions.ElementNotClickableException;
import com.hsbc.cmb.dbb.hk.automation.framework.exceptions.ElementNotFoundException;
import com.hsbc.cmb.dbb.hk.automation.framework.exceptions.ElementNotVisibleException;
import com.hsbc.cmb.dbb.hk.automation.framework.exceptions.TimeoutException;

/**
 * 基础页面类 - 所有PageObject的父类
 * 提供通用的页面操作方法和元素定位方法
 * 
 * 使用示例：
 * <pre>
 * public class LoginPage extends BasePage {
 *     private static final String USERNAME_INPUT = "#username";
 *     private static final String PASSWORD_INPUT = "#password";
 *     private static final String LOGIN_BUTTON = "#login-btn";
 *     
 *     public void login(String username, String password) {
 *         type(USERNAME_INPUT, username);
 *         type(PASSWORD_INPUT, password);
 *         click(LOGIN_BUTTON);
 *     }
 * }
 * </pre>
 */
public abstract class BasePage {
    protected static final Logger logger = LoggerFactory.getLogger(BasePage.class);
    
    protected Page page;
    protected BrowserContext context;
    
    /**
     * 构造函数 - 初始化页面对象
     */
    public BasePage() {
        // 确保框架已初始化
        if (!FrameworkCore.getInstance().isInitialized()) {
            FrameworkCore.getInstance().initialize();
        }
        // 不在构造函数中初始化，延迟到第一次使用时
    }

    /**
     * 确保page有效（如果已关闭则重新获取）
     */
    private void ensurePageValid() {
        if (page == null || page.isClosed()) {
            page = PlaywrightManager.getPage();
        }
    }

    /**
     * 确保context有效（如果已关闭则重新获取）
     */
    private void ensureContextValid() {
        if (context == null || context.pages().isEmpty()) {
            context = PlaywrightManager.getContext();
        }
    }

    /**
     * 获取BrowserContext对象
     */
    protected BrowserContext getContext() {
        ensureContextValid();
        return this.context;
    }

    /**
     * 获取当前页面的 Page 对象
     */
    public Page getPage() {
        ensurePageValid();
        return this.page;
    }

    // ==================== 元素定位方法 ====================

    /**
     * 通过选择器查找单个元素
     */
    protected Locator locator(String selector) {
        ensurePageValid();
        return page.locator(selector);
    }

    /**
     * 通过选择器查找多个元素
     */
    protected List<Locator> locators(String selector) {
        ensurePageValid();
        return page.locator(selector).all();
    }

    /**
     * 通过文本查找元素（包含指定文本）
     */
    protected Locator byText(String text) {
        ensurePageValid();
        return page.getByText(text);
    }

    /**
     * 通过文本查找元素（精确匹配）
     */
    protected Locator byExactText(String text) {
        ensurePageValid();
        return page.getByText(text, new Page.GetByTextOptions().setExact(true));
    }

    /**
     * 通过占位符查找输入框
     */
    protected Locator byPlaceholder(String placeholder) {
        ensurePageValid();
        return page.getByPlaceholder(placeholder);
    }

    /**
     * 通过标签查找元素
     */
    protected Locator byLabel(String label) {
        ensurePageValid();
        return page.getByLabel(label);
    }

    /**
     * 通过Alt文本查找图片
     */
    protected Locator byAltText(String altText) {
        ensurePageValid();
        return page.getByAltText(altText);
    }

    /**
     * 通过标题查找元素
     */
    protected Locator byTitle(String title) {
        ensurePageValid();
        return page.getByTitle(title);
    }

    /**
     * 通过Role查找元素
     */
    protected Locator byRole(AriaRole role) {
        ensurePageValid();
        return page.getByRole(role);
    }

    // ==================== 基础操作方法 ====================

    /**
     * 点击元素
     */
    public void click(String selector) {
        try {
            logger.info("Clicking element: {}", selector);
            locator(selector).click();
        } catch (Exception e) {
            logger.error("Failed to click element: {}", selector, e);
            throw new ElementNotClickableException(selector, e);
        }
    }

    /**
     * 双击元素
     */
    public void doubleClick(String selector) {
        try {
            logger.info("Double clicking element: {}", selector);
            locator(selector).dblclick();
        } catch (Exception e) {
            logger.error("Failed to double click element: {}", selector, e);
            throw new ElementNotClickableException(selector, e);
        }
    }

    /**
     * 右键点击元素
     */
    public void rightClick(String selector) {
        try {
            logger.info("Right clicking element: {}", selector);
            locator(selector).click(new Locator.ClickOptions().setButton(MouseButton.RIGHT));
        } catch (Exception e) {
            logger.error("Failed to right click element: {}", selector, e);
            throw new ElementNotClickableException(selector, e);
        }
    }

    /**
     * 悬停在元素上
     */
    public void hover(String selector) {
        try {
            logger.info("Hovering over element: {}", selector);
            locator(selector).hover();
        } catch (Exception e) {
            logger.error("Failed to hover over element: {}", selector, e);
            throw new ElementNotVisibleException(selector, e);
        }
    }

    /**
     * 输入文本
     */
    public void type(String selector, String text) {
        try {
            logger.info("Typing text '{}' into element: {}", text, selector);
            locator(selector).fill(text);
        } catch (Exception e) {
            logger.error("Failed to type text '{}' into element: {}", text, selector, e);
            throw new ElementException("Failed to type text '" + text + "' into element: " + selector, e);
        }
    }

    /**
     * 清空输入框
     */
    public void clear(String selector) {
        try {
            logger.info("Clearing element: {}", selector);
            locator(selector).clear();
        } catch (Exception e) {
            logger.error("Failed to clear element: {}", selector, e);
            throw new ElementException("Failed to clear element: " + selector, e);
        }
    }

    /**
     * 追加文本
     */
    public void append(String selector, String text) {
        try {
            logger.info("Appending text '{}' to element: {}", text, selector);
            locator(selector).fill(text);
        } catch (Exception e) {
            logger.error("Failed to append text '{}' to element: {}", text, selector, e);
            throw new ElementException("Failed to append text '" + text + "' to element: " + selector, e);
        }
    }

    /**
     * 获取元素文本
     */
    public String getText(String selector) {
        try {
            String text = locator(selector).innerText();
            logger.info("Getting text from element {}: {}", selector, text);
            return text;
        } catch (Exception e) {
            logger.error("Failed to get text from element: {}", selector, e);
            throw new ElementException("Failed to get text from element: " + selector, e);
        }
    }

    /**
     * 获取输入框的值
     */
    public String getValue(String selector) {
        try {
            String value = locator(selector).inputValue();
            logger.info("Getting value from element {}: {}", selector, value);
            return value;
        } catch (Exception e) {
            logger.error("Failed to get value from element: {}", selector, e);
            throw new ElementException("Failed to get value from element: " + selector, e);
        }
    }

    /**
     * 获取元素的属性值
     */
    public String getAttribute(String selector, String attributeName) {
        try {
            String value = locator(selector).getAttribute(attributeName);
            logger.info("Getting attribute '{}' from element {}: {}", attributeName, selector, value);
            return value;
        } catch (Exception e) {
            logger.error("Failed to get attribute '{}' from element: {}", attributeName, selector, e);
            throw new ElementException("Failed to get attribute '" + attributeName + "' from element: " + selector, e);
        }
    }

    /**
     * 选择下拉框选项
     */
    public void selectOption(String selector, String value) {
        try {
            logger.info("Selecting option '{}' from element: {}", value, selector);
            locator(selector).selectOption(value);
        } catch (Exception e) {
            logger.error("Failed to select option '{}' from element: {}", value, selector, e);
            throw new ElementException("Failed to select option '" + value + "' from element: " + selector, e);
        }
    }

    /**
     * 选择下拉框选项（通过索引）
     */
    public void selectOption(String selector, int index) {
        try {
            logger.info("Selecting option at index {} from element: {}", index, selector);
            locator(selector).selectOption(new SelectOption().setIndex(index));
        } catch (Exception e) {
            logger.error("Failed to select option at index {} from element: {}", index, selector, e);
            throw new ElementException("Failed to select option at index " + index + " from element: " + selector, e);
        }
    }

    /**
     * 勾选复选框
     */
    public void check(String selector) {
        try {
            logger.info("Checking element: {}", selector);
            locator(selector).check();
        } catch (Exception e) {
            logger.error("Failed to check element: {}", selector, e);
            throw new ElementException("Failed to check element: " + selector, e);
        }
    }

    /**
     * 取消勾选复选框
     */
    public void uncheck(String selector) {
        try {
            logger.info("Unchecking element: {}", selector);
            locator(selector).uncheck();
        } catch (Exception e) {
            logger.error("Failed to uncheck element: {}", selector, e);
            throw new ElementException("Failed to uncheck element: " + selector, e);
        }
    }

    /**
     * 上传文件
     */
    public void uploadFile(String selector, String filePath) {
        try {
            logger.info("Uploading file '{}' to element: {}", filePath, selector);
            locator(selector).setInputFiles(Paths.get(filePath));
        } catch (Exception e) {
            logger.error("Failed to upload file '{}' to element: {}", filePath, selector, e);
            throw new ElementException("Failed to upload file '" + filePath + "' to element: " + selector, e);
        }
    }

    // ==================== 等待方法 ====================

    /**
     * 等待元素可见
     */
    public void waitForVisible(String selector) {
        try {
            logger.info("Waiting for element to be visible: {}", selector);
            locator(selector).waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        } catch (Exception e) {
            logger.error("Failed to wait for element to be visible: {}", selector, e);
            throw new TimeoutException("Failed to wait for element to be visible: " + selector, e);
        }
    }

    /**
     * 等待元素隐藏
     */
    public void waitForHidden(String selector) {
        try {
            logger.info("Waiting for element to be hidden: {}", selector);
            locator(selector).waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
        } catch (Exception e) {
            logger.error("Failed to wait for element to be hidden: {}", selector, e);
            throw new TimeoutException("Failed to wait for element to be hidden: " + selector, e);
        }
    }

    /**
     * 等待元素被附加到DOM
     */
    public void waitForAttached(String selector) {
        try {
            logger.info("Waiting for element to be attached: {}", selector);
            locator(selector).waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED));
        } catch (Exception e) {
            logger.error("Failed to wait for element to be attached: {}", selector, e);
            throw new TimeoutException("Failed to wait for element to be attached: " + selector, e);
        }
    }

    /**
     * 等待元素从DOM中分离
     */
    public void waitForDetached(String selector) {
        try {
            logger.info("Waiting for element to be detached: {}", selector);
            locator(selector).waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED));
        } catch (Exception e) {
            logger.error("Failed to wait for element to be detached: {}", selector, e);
            throw new RuntimeException("Failed to wait for element to be detached: " + selector, e);
        }
    }

    /**
     * 等待指定时间（毫秒）
     */
    public void waitForTimeout(int milliseconds) {
        try {
            logger.info("Waiting for {} milliseconds", milliseconds);
            ensurePageValid();
            page.waitForTimeout(milliseconds);
        } catch (Exception e) {
            logger.error("Failed to wait for {} milliseconds", milliseconds, e);
            throw new RuntimeException("Failed to wait for " + milliseconds + " milliseconds", e);
        }
    }

    // ==================== 判断方法 ====================

    /**
     * 检查元素是否可见
     */
    public boolean isVisible(String selector) {
        try {
            boolean visible = locator(selector).isVisible();
            logger.info("Element {} is visible: {}", selector, visible);
            return visible;
        } catch (Exception e) {
            logger.error("Failed to check if element is visible: {}", selector, e);
            throw new RuntimeException("Failed to check if element is visible: " + selector, e);
        }
    }

    /**
     * 检查元素是否存在
     */
    public boolean exists(String selector) {
        try {
            boolean exists = locator(selector).count() > 0;
            logger.info("Element {} exists: {}", selector, exists);
            return exists;
        } catch (Exception e) {
            logger.error("Failed to check if element exists: {}", selector, e);
            throw new RuntimeException("Failed to check if element exists: " + selector, e);
        }
    }

    /**
     * 检查元素是否被选中
     */
    public boolean isChecked(String selector) {
        try {
            boolean checked = locator(selector).isChecked();
            logger.info("Element {} is checked: {}", selector, checked);
            return checked;
        } catch (Exception e) {
            logger.error("Failed to check if element is checked: {}", selector, e);
            throw new RuntimeException("Failed to check if element is checked: " + selector, e);
        }
    }

    /**
     * 检查元素是否启用
     */
    public boolean isEnabled(String selector) {
        try {
            boolean enabled = locator(selector).isEnabled();
            logger.info("Element {} is enabled: {}", selector, enabled);
            return enabled;
        } catch (Exception e) {
            logger.error("Failed to check if element is enabled: {}", selector, e);
            throw new RuntimeException("Failed to check if element is enabled: " + selector, e);
        }
    }

    /**
     * 检查元素是否禁用
     */
    public boolean isDisabled(String selector) {
        try {
            boolean disabled = locator(selector).isDisabled();
            logger.info("Element {} is disabled: {}", selector, disabled);
            return disabled;
        } catch (Exception e) {
            logger.error("Failed to check if element is disabled: {}", selector, e);
            throw new RuntimeException("Failed to check if element is disabled: " + selector, e);
        }
    }

    // ==================== 页面导航方法 ====================

    /**
     * 导航到指定URL
     */
    public void navigateTo(String url) {
        try {
            logger.info("Navigating to URL: {}", url);
            ensurePageValid();
            page.navigate(url);
        } catch (Exception e) {
            logger.error("Failed to navigate to URL: {}", url, e);
            throw new RuntimeException("Failed to navigate to URL: " + url, e);
        }
    }

    /**
     * 刷新页面
     */
    public void refresh() {
        try {
            logger.info("Refreshing page");
            ensurePageValid();
            page.reload();
        } catch (Exception e) {
            logger.error("Failed to refresh page", e);
            throw new RuntimeException("Failed to refresh page", e);
        }
    }

    /**
     * 前进
     */
    public void forward() {
        try {
            logger.info("Going forward");
            ensurePageValid();
            page.goForward();
        } catch (Exception e) {
            logger.error("Failed to go forward", e);
            throw new RuntimeException("Failed to go forward", e);
        }
    }

    /**
     * 后退
     */
    public void back() {
        try {
            logger.info("Going back");
            ensurePageValid();
            page.goBack();
        } catch (Exception e) {
            logger.error("Failed to go back", e);
            throw new RuntimeException("Failed to go back", e);
        }
    }

    /**
     * 获取当前页面URL
     */
    public String getCurrentUrl() {
        try {
            ensurePageValid();
            String url = page.url();
            logger.info("Current URL: {}", url);
            return url;
        } catch (Exception e) {
            logger.error("Failed to get current URL", e);
            throw new RuntimeException("Failed to get current URL", e);
        }
    }

    /**
     * 获取页面标题
     */
    public String getTitle() {
        try {
            ensurePageValid();
            String title = page.title();
            logger.info("Page title: {}", title);
            return title;
        } catch (Exception e) {
            logger.error("Failed to get page title", e);
            throw new RuntimeException("Failed to get page title", e);
        }
    }

    // ==================== JavaScript执行方法 ====================

    /**
     * 执行JavaScript代码
     */
    public Object executeJavaScript(String script, Object... args) {
        try {
            logger.info("Executing JavaScript: {}", script);
            ensurePageValid();
            return page.evaluate(script, args);
        } catch (Exception e) {
            logger.error("Failed to execute JavaScript: {}", script, e);
            throw new RuntimeException("Failed to execute JavaScript: " + script, e);
        }
    }

    /**
     * 滚动到页面顶部
     */
    public void scrollToTop() {
        try {
            logger.info("Scrolling to top of page");
            ensurePageValid();
            page.evaluate("window.scrollTo(0, 0)");
        } catch (Exception e) {
            logger.error("Failed to scroll to top of page", e);
            throw new RuntimeException("Failed to scroll to top of page", e);
        }
    }

    /**
     * 滚动到页面底部
     */
    public void scrollToBottom() {
        try {
            logger.info("Scrolling to bottom of page");
            ensurePageValid();
            page.evaluate("window.scrollTo(0, document.body.scrollHeight)");
        } catch (Exception e) {
            logger.error("Failed to scroll to bottom of page", e);
            throw new RuntimeException("Failed to scroll to bottom of page", e);
        }
    }

    /**
     * 滚动到指定元素
     */
    public void scrollToElement(String selector) {
        try {
            logger.info("Scrolling to element: {}", selector);
            locator(selector).scrollIntoViewIfNeeded();
        } catch (Exception e) {
            logger.error("Failed to scroll to element: {}", selector, e);
            throw new RuntimeException("Failed to scroll to element: " + selector, e);
        }
    }

    // ==================== 时间范围操作方法 ====================

    /**
     * 在指定时间范围内等待元素可见（失败时抛出异常）
     * @param selector 元素选择器
     * @param timeoutSeconds 最大超时时间（秒）
     * @throws TimeoutException 如果元素在超时时间内不可见
     */
    public void waitForElementVisibleWithinTime(String selector, int timeoutSeconds) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            logger.info("Waiting for element to be visible within {}s: {}", timeoutSeconds, selector);
            ensurePageValid();
            locator(selector).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(timeoutMillis));
            logger.info("Element is now visible: {}", selector);
        } catch (Exception e) {
            logger.error("Failed to wait for element to be visible: {}", selector, e);
            throw new TimeoutException("Element not visible within timeout: " + selector, e);
        }
    }


    /**
     * 在指定时间范围内等待元素隐藏（失败时抛出异常）
     * @param selector 元素选择器
     * @param timeoutSeconds 最大超时时间（秒）
     * @throws TimeoutException 如果元素在超时时间内未隐藏
     */
    public void waitForElementHiddenWithinTime(String selector, int timeoutSeconds) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            logger.info("Waiting for element to be hidden within {}s: {}", timeoutSeconds, selector);
            ensurePageValid();
            locator(selector).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.HIDDEN)
                .setTimeout(timeoutMillis));
            logger.info("Element is now hidden: {}", selector);
        } catch (Exception e) {
            logger.error("Failed to wait for element to be hidden: {}", selector, e);
            throw new TimeoutException("Element not hidden within timeout: " + selector, e);
        }
    }

    /**
     * 在指定时间范围内等待元素可点击（失败时抛出异常）
     * @param selector 元素选择器
     * @param timeoutSeconds 最大超时时间（秒）
     * @throws TimeoutException 如果元素在超时时间内不可点击
     */
    public void waitForElementClickableWithinTime(String selector, int timeoutSeconds) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            logger.info("Waiting for element to be clickable within {}s: {}", timeoutSeconds, selector);
            ensurePageValid();
            locator(selector).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(timeoutMillis));
            logger.info("Element is now clickable: {}", selector);
        } catch (Exception e) {
            logger.error("Failed to wait for element to be clickable: {}", selector, e);
            throw new TimeoutException("Element not clickable within timeout: " + selector, e);
        }
    }

    /**
     * 在指定时间范围内等待页面标题包含文本
     * @param expectedTitle 期望的标题文本
     * @param timeoutSeconds 最大超时时间（秒）
     * @return 如果页面标题在指定时间内包含文本则返回true，否则返回false
     */
    public void waitForTitleContainsWithinTime(String expectedTitle, int timeoutSeconds) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            logger.info("Waiting for title to contain '{}' within {}s", expectedTitle, timeoutSeconds);
            long startTime = System.currentTimeMillis();
            long endTime = startTime + timeoutMillis;

            while (System.currentTimeMillis() < endTime) {
                String currentTitle = page.title();
                if (currentTitle.contains(expectedTitle)) {
                    logger.info("Title contains expected text: '{}'", expectedTitle);
                    return;
                }
                waitForTimeout(500);
            }

            logger.warn("Timeout waiting for title to contain: {}", expectedTitle);
            throw new TimeoutException("Title does not contain expected text within timeout: " + expectedTitle);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to wait for title to contain: {}", expectedTitle, e);
            throw new TimeoutException("Failed to wait for title to contain: " + expectedTitle, e);
        }
    }

    /**
     * 在指定时间范围内等待URL包含文本（失败时抛出异常）
     * @param expectedUrlFragment 期望的URL片段
     * @param timeoutSeconds 最大超时时间（秒）
     * @throws TimeoutException 如果URL在超时时间内不包含指定片段
     */
    public void waitForUrlContainsWithinTime(String expectedUrlFragment, int timeoutSeconds) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            logger.info("Waiting for URL to contain '{}' within {}s", expectedUrlFragment, timeoutSeconds);
            long startTime = System.currentTimeMillis();
            long endTime = startTime + timeoutMillis;

            while (System.currentTimeMillis() < endTime) {
                String currentUrl = page.url();
                if (currentUrl.contains(expectedUrlFragment)) {
                    logger.info("URL contains expected fragment: '{}'", expectedUrlFragment);
                    return;
                }
                waitForTimeout(500);
            }

            logger.warn("Timeout waiting for URL to contain: {}", expectedUrlFragment);
            throw new TimeoutException("URL does not contain expected fragment within timeout: " + expectedUrlFragment);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to wait for URL to contain: {}", expectedUrlFragment, e);
            throw new TimeoutException("Failed to wait for URL to contain: " + expectedUrlFragment, e);
        }
    }

    /**
     * 在指定时间范围内执行操作并验证结果
     * @param action 要执行的操作
     * @param validation 验证逻辑
     * @param timeoutSeconds 最大超时时间（秒）
     * @param actionDescription 操作描述
     * @return 如果在指定时间内操作成功并验证通过则返回true，否则返回false
     */
    public boolean performActionWithTimeout(Runnable action, Supplier<Boolean> validation, int timeoutSeconds, String actionDescription) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            logger.info("Performing '{}' within {}s", actionDescription, timeoutSeconds);
            long startTime = System.currentTimeMillis();
            long endTime = startTime + timeoutMillis;
            
            while (System.currentTimeMillis() < endTime) {
                action.run();
                if (validation.get()) {
                    logger.info("Action '{}' completed successfully", actionDescription);
                    return true;
                }
                waitForTimeout(500);
            }

            logger.warn("Timeout performing action: {}", actionDescription);
            return false;
        } catch (Exception e) {
            logger.error("Failed to perform action: {}", actionDescription, e);
            throw new RuntimeException("Failed to perform action: " + actionDescription, e);
        }
    }

    // ==================== 弹窗处理方法 ====================

    /**
     * 接受弹窗
     */
    public void acceptAlert() {
        try {
            logger.info("Accepting alert");
            ensurePageValid();
            page.onDialog(dialog -> dialog.accept());
        } catch (Exception e) {
            logger.error("Failed to accept alert", e);
            throw new RuntimeException("Failed to accept alert", e);
        }
    }

    /**
     * 取消弹窗
     */
    public void dismissAlert() {
        try {
            logger.info("Dismissing alert");
            ensurePageValid();
            page.onDialog(dialog -> dialog.dismiss());
        } catch (Exception e) {
            logger.error("Failed to dismiss alert", e);
            throw new RuntimeException("Failed to dismiss alert", e);
        }
    }

    /**
     * 在弹窗中输入文本并接受
     */
    public void acceptPrompt(String text) {
        try {
            logger.info("Accepting prompt with text: {}", text);
            ensurePageValid();
            page.onDialog(dialog -> {
                dialog.accept(text);
            });
        } catch (Exception e) {
            logger.error("Failed to accept prompt with text: {}", text, e);
            throw new RuntimeException("Failed to accept prompt with text: " + text, e);
        }
    }

    // ==================== 截图和记录方法 ====================

    /**
     * 截取全屏截图
     */
    public void takeScreenshot() {
        try {
            logger.info("Taking full page screenshot");
            ensurePageValid();
            page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
        } catch (Exception e) {
            logger.error("Failed to take full page screenshot", e);
            throw new RuntimeException("Failed to take full page screenshot", e);
        }
    }

    /**
     * 截取指定元素截图
     */
    public void takeElementScreenshot(String selector) {
        try {
            logger.info("Taking screenshot of element: {}", selector);
            locator(selector).screenshot();
        } catch (Exception e) {
            logger.error("Failed to take screenshot of element: {}", selector, e);
            throw new RuntimeException("Failed to take screenshot of element: " + selector, e);
        }
    }

    // ==================== 验证方法 ====================

    /**
     * 验证元素文本是否包含指定文本
     */
    public boolean textContains(String selector, String expectedText) {
        try {
            String actualText = getText(selector);
            boolean contains = actualText.contains(expectedText);
            logger.info("Text verification - Element '{}' contains '{}': {}", selector, expectedText, contains);
            return contains;
        } catch (Exception e) {
            logger.error("Failed to verify text contains for element: {}", selector, e);
            throw new RuntimeException("Failed to verify text contains for element: " + selector, e);
        }
    }

    /**
     * 验证元素文本是否等于指定文本
     */
    public boolean textEquals(String selector, String expectedText) {
        try {
            String actualText = getText(selector);
            boolean equals = actualText.equals(expectedText);
            logger.info("Text verification - Element '{}' equals '{}': {}", selector, expectedText, equals);
            return equals;
        } catch (Exception e) {
            logger.error("Failed to verify text equals for element: {}", selector, e);
            throw new RuntimeException("Failed to verify text equals for element: " + selector, e);
        }
    }

    /**
     * 验证元素文本是否匹配正则表达式
     */
    public boolean textMatches(String selector, String regex) {
        try {
            String actualText = getText(selector);
            boolean matches = Pattern.matches(regex, actualText);
            logger.info("Text verification - Element '{}' matches '{}': {}", selector, regex, matches);
            return matches;
        } catch (Exception e) {
            logger.error("Failed to verify text matches for element: {}", selector, e);
            throw new RuntimeException("Failed to verify text matches for element: " + selector, e);
        }
    }

    // ==================== 表单操作方法 ====================

    /**
     * 填写表单
     *
     * @param formData 键值对，键为选择器，值为输入的文本
     */
    public void fillForm(java.util.Map<String, String> formData) {
        try {
            logger.info("Filling form with {} fields", formData.size());
            for (java.util.Map.Entry<String, String> entry : formData.entrySet()) {
                type(entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            logger.error("Failed to fill form with {} fields", formData.size(), e);
            throw new RuntimeException("Failed to fill form with " + formData.size() + " fields", e);
        }
    }

    /**
     * 提交表单
     */
    public void submitForm(String formSelector) {
        try {
            logger.info("Submitting form: {}", formSelector);
            locator(formSelector).evaluate("form => form.submit()");
        } catch (Exception e) {
            logger.error("Failed to submit form: {}", formSelector, e);
            throw new RuntimeException("Failed to submit form: " + formSelector, e);
        }
    }

    // ==================== Cookie和存储方法 ====================

    /**
     * 添加Cookie
     */
    public void addCookie(String name, String value) {
        try {
            logger.info("Adding cookie: {} = {}", name, value);
            getContext().addCookies(Collections.singletonList(new Cookie(name, value)));
        } catch (Exception e) {
            logger.error("Failed to add cookie: {} = {}", name, value, e);
            throw new RuntimeException("Failed to add cookie: " + name + " = " + value, e);
        }
    }

    /**
     * 获取所有Cookies
     */
    public List<Cookie> getCookies() {
        return getContext().cookies();
    }

    /**
     * 清除所有Cookies
     */
    public void clearCookies() {
        try {
            logger.info("Clearing all cookies");
            getContext().clearCookies();
        } catch (Exception e) {
            logger.error("Failed to clear all cookies", e);
            throw new RuntimeException("Failed to clear all cookies", e);
        }
    }

    // ==================== 调试方法 ====================

    /**
     * 暂停执行（用于调试）
     */
    public void pause() {
        try {
            logger.info("Pausing execution for debugging");
            ensurePageValid();
            page.pause();
        } catch (Exception e) {
            logger.error("Failed to pause execution for debugging", e);
            throw new RuntimeException("Failed to pause execution for debugging", e);
        }
    }

    /**
     * 打印页面日志
     */
    public void logPageInfo() {
        try {
            ensurePageValid();
            logger.info("Page Information:");
            logger.info("URL: {}", page.url());
            logger.info("Title: {}", page.title());
            logger.info("Viewport: {}", page.viewportSize());
        } catch (Exception e) {
            logger.error("Failed to log page information", e);
            throw new RuntimeException("Failed to log page information", e);
        }
    }

    // ==================== 新增页面文本检查方法 ====================

    /**
     * 检查页面是否包含指定文本
     * @param text 要检查的文本
     * @return 如果页面包含指定文本则返回true，否则返回false
     */
    public boolean pageContainsText(String text) {
        try {
            String pageContent = page.content();
            boolean contains = pageContent.contains(text);
            logger.info("Page contains text '{}': {}", text, contains);
            return contains;
        } catch (Exception e) {
            logger.error("Failed to check if page contains text: {}", text, e);
            throw new RuntimeException("Failed to check if page contains text: " + text, e);
        }
    }

    /**
     * 等待页面加载完成
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForPageLoad(int timeoutSeconds) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            logger.info("Waiting for page to load with timeout: {}s", timeoutSeconds);
            ensurePageValid();
            page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(timeoutMillis));
            logger.info("Page loaded successfully");
        } catch (Exception e) {
            logger.error("Failed to wait for page load", e);
            throw new RuntimeException("Failed to wait for page load", e);
        }
    }

    /**
     * 等待元素可点击
     * @param selector 元素选择器
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForElementClickable(String selector, int timeoutSeconds) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            logger.info("Waiting for element to be clickable: {}", selector);
            ensurePageValid();
            locator(selector).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.ATTACHED)
                .setTimeout(timeoutMillis));
            logger.info("Element is now clickable: {}", selector);
        } catch (Exception e) {
            logger.error("Failed to wait for element to be clickable: {}", selector, e);
            throw new RuntimeException("Failed to wait for element to be clickable: " + selector, e);
        }
    }

    /**
     * 等待元素不可见
     * @param selector 元素选择器
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForElementNotVisible(String selector, int timeoutSeconds) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            logger.info("Waiting for element to be not visible: {}", selector);
            ensurePageValid();
            locator(selector).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.HIDDEN)
                .setTimeout(timeoutMillis));
            logger.info("Element is now not visible: {}", selector);
        } catch (Exception e) {
            logger.error("Failed to wait for element to be not visible: {}", selector, e);
            throw new RuntimeException("Failed to wait for element to be not visible: " + selector, e);
        }
    }

    // ==================== 新增页面管理方法 ====================

    /**
     * 获取当前页面的数量
     * @return 当前打开的页面数量
     */
    public int getPageCount() {
        try {
            ensureContextValid();
            int pageCount = context.pages().size();
            logger.info("Current page count: {}", pageCount);
            return pageCount;
        } catch (Exception e) {
            logger.error("Failed to get page count", e);
            throw new RuntimeException("Failed to get page count", e);
        }
    }

    /**
     * 切换到指定索引的页面
     * @param index 页面索引
     */
    public void switchToPage(int index) {
        try {
            logger.info("Switching to page with index: {}", index);
            ensureContextValid();
            List<Page> pages = context.pages();
            if (index >= 0 && index < pages.size()) {
                page = pages.get(index);
                logger.info("Switched to page: {}", index);
            } else {
                throw new RuntimeException("Invalid page index: " + index + ". Available pages: " + pages.size());
            }
        } catch (Exception e) {
            logger.error("Failed to switch to page: {}", index, e);
            throw new RuntimeException("Failed to switch to page: " + index, e);
        }
    }

    /**
     * 获取当前页面的索引
     * @return 当前页面的索引
     */
    public int getCurrentPageIndex() {
        try {
            ensureContextValid();
            List<Page> pages = context.pages();
            return pages.indexOf(page);
        } catch (Exception e) {
            logger.error("Failed to get current page index", e);
            throw new RuntimeException("Failed to get current page index", e);
        }
    }

    /**
     * 关闭当前页面
     */
    public void closePage() {
        try {
            logger.info("Closing current page");
            ensurePageValid();
            page.close();
            logger.info("Page closed successfully");
        } catch (Exception e) {
            logger.error("Failed to close page", e);
            throw new RuntimeException("Failed to close page", e);
        }
    }

    /**
     * 等待新页面打开
     * @param timeoutSeconds 超时时间（秒）
     * @return 新打开的页面
     */
    public Page waitForNewPage(int timeoutSeconds) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            logger.info("Waiting for new page to open with timeout: {}s", timeoutSeconds);
            ensureContextValid();
            
            int initialPageCount = context.pages().size();
            long startTime = System.currentTimeMillis();
            long endTime = startTime + timeoutMillis;
            
            while (System.currentTimeMillis() < endTime) {
                if (context.pages().size() > initialPageCount) {
                    Page newPage = context.pages().get(initialPageCount);
                    logger.info("New page opened: {}", newPage.url());
                    return newPage;
                }
                waitForTimeout(500);
            }

            throw new RuntimeException("Timeout waiting for new page to open");
        } catch (Exception e) {
            logger.error("Failed to wait for new page", e);
            throw new RuntimeException("Failed to wait for new page", e);
        }
    }

    /**
     * 等待页面标题包含指定文本
     * @param expectedTitle 期望的页面标题
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForTitleContains(String expectedTitle, int timeoutSeconds) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            logger.info("Waiting for title to contain: '{}' with timeout: {}s", expectedTitle, timeoutSeconds);
            ensurePageValid();
            
            long startTime = System.currentTimeMillis();
            long endTime = startTime + timeoutMillis;
            
            while (System.currentTimeMillis() < endTime) {
                String currentTitle = page.title();
                if (currentTitle.contains(expectedTitle)) {
                    logger.info("Title contains expected text: '{}'", expectedTitle);
                    return;
                }
                waitForTimeout(500);
            }

            throw new RuntimeException("Timeout waiting for title to contain: " + expectedTitle);
        } catch (Exception e) {
            logger.error("Failed to wait for title to contain: {}", expectedTitle, e);
            throw new RuntimeException("Failed to wait for title to contain: " + expectedTitle, e);
        }
    }

    /**
     * 等待URL包含指定文本
     * @param expectedUrlFragment 期望的URL片段
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForUrlContains(String expectedUrlFragment, int timeoutSeconds) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            logger.info("Waiting for URL to contain: '{}' with timeout: {}s", expectedUrlFragment, timeoutSeconds);
            ensurePageValid();
            
            long startTime = System.currentTimeMillis();
            long endTime = startTime + timeoutMillis;
            
            while (System.currentTimeMillis() < endTime) {
                String currentUrl = page.url();
                if (currentUrl.contains(expectedUrlFragment)) {
                    logger.info("URL contains expected fragment: '{}'", expectedUrlFragment);
                    return;
                }
                waitForTimeout(500);
            }

            throw new RuntimeException("Timeout waiting for URL to contain: " + expectedUrlFragment);
        } catch (Exception e) {
            logger.error("Failed to wait for URL to contain: {}", expectedUrlFragment, e);
            throw new RuntimeException("Failed to wait for URL to contain: " + expectedUrlFragment, e);
        }
    }

    /**
     * 获取页面源代码
     * @return 页面HTML源代码
     */
    public String getPageSource() {
        try {
            ensurePageValid();
            String pageSource = page.content();
            logger.info("Got page source, length: {} characters", pageSource.length());
            return pageSource;
        } catch (Exception e) {
            logger.error("Failed to get page source", e);
            throw new RuntimeException("Failed to get page source", e);
        }
    }

    /**
     * 检查页面源代码是否包含指定文本
     * @param text 要检查的文本
     * @return 如果页面源代码包含指定文本则返回true，否则返回false
     */
    public boolean getPageSourceContains(String text) {
        try {
            String pageSource = getPageSource();
            boolean contains = pageSource.contains(text);
            logger.info("Page source contains text '{}': {}", text, contains);
            return contains;
        } catch (Exception e) {
            logger.error("Failed to check if page source contains text: {}", text, e);
            throw new RuntimeException("Failed to check if page source contains text: " + text, e);
        }
    }

    /**
     * 获取元素属性值并断言其值
     * @param selector 元素选择器
     * @param attributeName 属性名
     * @param expectedValue 期望的属性值
     * @throws RuntimeException 如果属性值不匹配期望值
     */
    public void getAttributeValue(String selector, String attributeName, String expectedValue) {
        try {
            String actualValue = getAttribute(selector, attributeName);
            if (!actualValue.equals(expectedValue)) {
                String errorMsg = String.format("Attribute '%s' should be '%s' but was '%s' for element: %s", 
                    attributeName, expectedValue, actualValue, selector);
                logger.error(errorMsg);
                throw new RuntimeException(errorMsg);
            }
            logger.info("Attribute '{}' is '{}' as expected for element: {}", attributeName, expectedValue, selector);
        } catch (Exception e) {
            logger.error("Failed to verify attribute value for element: {}", selector, e);
            throw new RuntimeException("Failed to verify attribute value for element: " + selector, e);
        }
    }

    /**
     * 检查元素是否可点击
     * @param selector 元素选择器
     * @return 如果元素可点击则返回true，否则返回false
     */
    public boolean isElementClickable(String selector) {
        try {
            boolean clickable = locator(selector).isVisible() && locator(selector).isEnabled();
            logger.info("Element {} is clickable: {}", selector, clickable);
            return clickable;
        } catch (Exception e) {
            logger.error("Failed to check if element is clickable: {}", selector, e);
            throw new RuntimeException("Failed to check if element is clickable: " + selector, e);
        }
    }

    /**
     * 断言元素应该可见
     * @param selector 元素选择器
     * @throws RuntimeException 如果元素不可见
     */
    public void shouldBeVisible(String selector) {
        try {
            if (!isVisible(selector)) {
                String errorMsg = "Element should be visible but is not: " + selector;
                logger.error(errorMsg);
                throw new RuntimeException(errorMsg);
            }
            logger.info("Element is visible as expected: {}", selector);
        } catch (Exception e) {
            logger.error("Failed to verify element should be visible: {}", selector, e);
            throw new RuntimeException("Failed to verify element should be visible: " + selector, e);
        }
    }

    /**
     * 断言元素不应该可见
     * @param selector 元素选择器
     * @throws RuntimeException 如果元素可见
     */
    public void shouldBeNotVisible(String selector) {
        try {
            if (isVisible(selector)) {
                String errorMsg = "Element should not be visible but is: " + selector;
                logger.error(errorMsg);
                throw new RuntimeException(errorMsg);
            }
            logger.info("Element is not visible as expected: {}", selector);
        } catch (Exception e) {
            logger.error("Failed to verify element should not be visible: {}", selector, e);
            throw new RuntimeException("Failed to verify element should not be visible: " + selector, e);
        }
    }

    /**
     * 获取元素数量
     * @param selector 元素选择器
     * @return 匹配选择器的元素数量
     */
    public int getElementCount(String selector) {
        try {
            int count = locator(selector).count();
            logger.info("Element count for {}: {}", selector, count);
            return count;
        } catch (Exception e) {
            logger.error("Failed to get element count for: {}", selector, e);
            throw new RuntimeException("Failed to get element count for: " + selector, e);
        }
    }

    // ==================== 重试机制 ====================

    /**
     * 重试执行操作直到成功或达到最大重试次数
     * @param operation 要执行的操作
     * @param maxRetries 最大重试次数
     * @param retryIntervalMs 重试间隔（毫秒）
     * @param operationDescription 操作描述
     * @throws TimeoutException 如果所有重试都失败
     */
    public void retry(Runnable operation, int maxRetries, int retryIntervalMs, String operationDescription) {
        int attempt = 0;
        while (attempt <= maxRetries) {
            try {
                operation.run();
                if (attempt > 0) {
                    logger.info("Operation '{}' succeeded on attempt {}/{}", operationDescription, attempt + 1, maxRetries + 1);
                }
                return;
            } catch (Exception e) {
                attempt++;
                if (attempt > maxRetries) {
                    logger.error("Operation '{}' failed after {} attempts: {}", operationDescription, maxRetries + 1, e.getMessage());
                    throw new TimeoutException("Operation '" + operationDescription + "' failed after " + (maxRetries + 1) + " attempts", e);
                }
                logger.warn("Operation '{}' failed on attempt {}/{}, retrying in {}ms. Error: {}", 
                    operationDescription, attempt, maxRetries + 1, retryIntervalMs, e.getMessage());
                try {
                    Thread.sleep(retryIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
            }
        }
    }

    /**
     * 重试执行操作直到成功或达到最大重试次数（使用默认配置）
     * @param operation 要执行的操作
     * @param operationDescription 操作描述
     * @throws TimeoutException 如果所有重试都失败
     */
    public void retry(Runnable operation, String operationDescription) {
        retry(operation, 3, 1000, operationDescription);
    }

    /**
     * 带条件的重试执行操作
     * @param operation 要执行的操作
     * @param validation 验证条件
     * @param maxRetries 最大重试次数
     * @param retryIntervalMs 重试间隔（毫秒）
     * @param operationDescription 操作描述
     * @return 如果验证通过返回true，否则抛出TimeoutException
     * @throws TimeoutException 如果所有重试都失败或验证不通过
     */
    public boolean retryWithValidation(Runnable operation, Predicate<Void> validation, 
                                       int maxRetries, int retryIntervalMs, String operationDescription) {
        int attempt = 0;
        while (attempt <= maxRetries) {
            try {
                operation.run();
                if (validation.test(null)) {
                    if (attempt > 0) {
                        logger.info("Operation '{}' and validation succeeded on attempt {}/{}", operationDescription, attempt + 1, maxRetries + 1);
                    }
                    return true;
                }
            } catch (Exception e) {
                logger.warn("Operation '{}' failed on attempt {}/{}. Error: {}", 
                    operationDescription, attempt + 1, maxRetries + 1, e.getMessage());
            }
            
            attempt++;
            if (attempt > maxRetries) {
                logger.error("Operation '{}' failed validation after {} attempts", operationDescription, maxRetries + 1);
                throw new TimeoutException("Operation '" + operationDescription + "' failed validation after " + (maxRetries + 1) + " attempts");
            }
            
            logger.info("Retrying operation '{}' in {}ms (attempt {}/{})", 
                operationDescription, retryIntervalMs, attempt + 1, maxRetries + 1);
            try {
                Thread.sleep(retryIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Retry interrupted", ie);
            }
        }
        return false;
    }

    // ==================== 扩展等待方法 ====================

    /**
     * 等待元素存在（已附加到DOM）
     * @param selector 元素选择器
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForElementExists(String selector, int timeoutSeconds) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            logger.info("Waiting for element to exist: {} (timeout: {}s)", selector, timeoutSeconds);
            ensurePageValid();
            locator(selector).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.ATTACHED)
                .setTimeout(timeoutMillis));
            logger.info("Element exists: {}", selector);
        } catch (Exception e) {
            logger.error("Failed to wait for element to exist: {}", selector, e);
            throw new TimeoutException("Element does not exist within timeout: " + selector, e);
        }
    }

    /**
     * 等待元素不存在（从DOM中移除）
     * @param selector 元素选择器
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForElementNotExists(String selector, int timeoutSeconds) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            logger.info("Waiting for element to not exist: {} (timeout: {}s)", selector, timeoutSeconds);
            ensurePageValid();
            locator(selector).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.DETACHED)
                .setTimeout(timeoutMillis));
            logger.info("Element does not exist: {}", selector);
        } catch (Exception e) {
            logger.error("Failed to wait for element to not exist: {}", selector, e);
            throw new TimeoutException("Element still exists within timeout: " + selector, e);
        }
    }

    /**
     * 等待元素可编辑（输入框）
     * @param selector 元素选择器
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForElementEditable(String selector, int timeoutSeconds) {
        try {
            logger.info("Waiting for element to be editable: {} (timeout: {}s)", selector, timeoutSeconds);
            long endTime = System.currentTimeMillis() + timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                if (isEnabled(selector) && !isDisabled(selector)) {
                    logger.info("Element is now editable: {}", selector);
                    return;
                }
                waitForTimeout(500);
            }
            
            throw new TimeoutException("Element not editable within timeout: " + selector);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to wait for element to be editable: {}", selector, e);
            throw new TimeoutException("Failed to wait for element to be editable: " + selector, e);
        }
    }

    /**
     * 等待元素禁用
     * @param selector 元素选择器
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForElementDisabled(String selector, int timeoutSeconds) {
        try {
            logger.info("Waiting for element to be disabled: {} (timeout: {}s)", selector, timeoutSeconds);
            long endTime = System.currentTimeMillis() + timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                if (isDisabled(selector)) {
                    logger.info("Element is now disabled: {}", selector);
                    return;
                }
                waitForTimeout(500);
            }
            
            throw new TimeoutException("Element not disabled within timeout: " + selector);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to wait for element to be disabled: {}", selector, e);
            throw new TimeoutException("Failed to wait for element to be disabled: " + selector, e);
        }
    }

    /**
     * 等待元素启用
     * @param selector 元素选择器
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForElementEnabled(String selector, int timeoutSeconds) {
        try {
            logger.info("Waiting for element to be enabled: {} (timeout: {}s)", selector, timeoutSeconds);
            long endTime = System.currentTimeMillis() + timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                if (isEnabled(selector)) {
                    logger.info("Element is now enabled: {}", selector);
                    return;
                }
                waitForTimeout(500);
            }
            
            throw new TimeoutException("Element not enabled within timeout: " + selector);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to wait for element to be enabled: {}", selector, e);
            throw new TimeoutException("Failed to wait for element to be enabled: " + selector, e);
        }
    }

    /**
     * 等待元素属性值等于指定值
     * @param selector 元素选择器
     * @param attributeName 属性名
     * @param expectedAttributeValue 期望的属性值
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForElementAttributeEquals(String selector, String attributeName, String expectedAttributeValue, int timeoutSeconds) {
        try {
            logger.info("Waiting for element attribute '{}' to equal '{}' within {}s: {}", 
                attributeName, expectedAttributeValue, timeoutSeconds, selector);
            long endTime = System.currentTimeMillis() + timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                String actualValue = getAttribute(selector, attributeName);
                if (expectedAttributeValue.equals(actualValue)) {
                    logger.info("Element attribute '{}' now equals '{}': {}", attributeName, expectedAttributeValue, selector);
                    return;
                }
                waitForTimeout(500);
            }
            
            throw new TimeoutException("Element attribute '" + attributeName + "' did not equal '" + expectedAttributeValue + "' within timeout");
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to wait for element attribute: {}", selector, e);
            throw new TimeoutException("Failed to wait for element attribute: " + selector, e);
        }
    }

    /**
     * 等待元素属性值包含指定文本
     * @param selector 元素选择器
     * @param attributeName 属性名
     * @param expectedAttributeValue 期望的属性值片段
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForElementAttributeContains(String selector, String attributeName, String expectedAttributeValue, int timeoutSeconds) {
        try {
            logger.info("Waiting for element attribute '{}' to contain '{}' within {}s: {}", 
                attributeName, expectedAttributeValue, timeoutSeconds, selector);
            long endTime = System.currentTimeMillis() + timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                String actualValue = getAttribute(selector, attributeName);
                if (actualValue != null && actualValue.contains(expectedAttributeValue)) {
                    logger.info("Element attribute '{}' now contains '{}': {}", attributeName, expectedAttributeValue, selector);
                    return;
                }
                waitForTimeout(500);
            }
            
            throw new TimeoutException("Element attribute '" + attributeName + "' did not contain '" + expectedAttributeValue + "' within timeout");
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to wait for element attribute: {}", selector, e);
            throw new TimeoutException("Failed to wait for element attribute: " + selector, e);
        }
    }

    /**
     * 等待元素文本包含指定文本
     * @param selector 元素选择器
     * @param expectedText 期望的文本
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForElementTextContains(String selector, String expectedText, int timeoutSeconds) {
        try {
            logger.info("Waiting for element text to contain '{}' within {}s: {}", expectedText, timeoutSeconds, selector);
            long endTime = System.currentTimeMillis() + timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                String actualText = getText(selector);
                if (actualText.contains(expectedText)) {
                    logger.info("Element text now contains '{}': {}", expectedText, selector);
                    return;
                }
                waitForTimeout(500);
            }
            
            throw new TimeoutException("Element text did not contain '" + expectedText + "' within timeout");
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to wait for element text: {}", selector, e);
            throw new TimeoutException("Failed to wait for element text: " + selector, e);
        }
    }

    /**
     * 等待元素文本等于指定文本
     * @param selector 元素选择器
     * @param expectedText 期望的文本
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForElementTextEquals(String selector, String expectedText, int timeoutSeconds) {
        try {
            logger.info("Waiting for element text to equal '{}' within {}s: {}", expectedText, timeoutSeconds, selector);
            long endTime = System.currentTimeMillis() + timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                String actualText = getText(selector);
                if (expectedText.equals(actualText)) {
                    logger.info("Element text now equals '{}': {}", expectedText, selector);
                    return;
                }
                waitForTimeout(500);
            }
            
            throw new TimeoutException("Element text did not equal '" + expectedText + "' within timeout");
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to wait for element text: {}", selector, e);
            throw new TimeoutException("Failed to wait for element text: " + selector, e);
        }
    }

    /**
     * 等待元素数量等于指定值
     * @param selector 元素选择器
     * @param expectedCount 期望的数量
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForElementCount(String selector, int expectedCount, int timeoutSeconds) {
        try {
            logger.info("Waiting for element count to equal {} within {}s: {}", expectedCount, timeoutSeconds, selector);
            long endTime = System.currentTimeMillis() + timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                int actualCount = getElementCount(selector);
                if (actualCount == expectedCount) {
                    logger.info("Element count now equals {}: {}", expectedCount, selector);
                    return;
                }
                waitForTimeout(500);
            }
            
            int actualCount = getElementCount(selector);
            throw new TimeoutException("Element count did not equal " + expectedCount + " within timeout. Actual: " + actualCount);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to wait for element count: {}", selector, e);
            throw new TimeoutException("Failed to wait for element count: " + selector, e);
        }
    }

    /**
     * 等待元素数量至少等于指定值
     * @param selector 元素选择器
     * @param minimumCount 最小数量
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForElementCountAtLeast(String selector, int minimumCount, int timeoutSeconds) {
        try {
            logger.info("Waiting for element count to be at least {} within {}s: {}", minimumCount, timeoutSeconds, selector);
            long endTime = System.currentTimeMillis() + timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                int actualCount = getElementCount(selector);
                if (actualCount >= minimumCount) {
                    logger.info("Element count now at least {}: {} (actual: {})", minimumCount, selector, actualCount);
                    return;
                }
                waitForTimeout(500);
            }
            
            int actualCount = getElementCount(selector);
            throw new TimeoutException("Element count did not reach minimum " + minimumCount + " within timeout. Actual: " + actualCount);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to wait for element count: {}", selector, e);
            throw new TimeoutException("Failed to wait for element count: " + selector, e);
        }
    }

    /**
     * 等待网络空闲（无正在进行的网络请求）
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForNetworkIdle(int timeoutSeconds) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            logger.info("Waiting for network to be idle (timeout: {}s)", timeoutSeconds);
            ensurePageValid();
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(timeoutMillis));
            logger.info("Network is now idle");
        } catch (Exception e) {
            logger.error("Failed to wait for network idle", e);
            throw new TimeoutException("Network did not become idle within timeout", e);
        }
    }

    /**
     * 等待页面完全加载（包括所有资源）
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForPageFullyLoaded(int timeoutSeconds) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            logger.info("Waiting for page to be fully loaded (timeout: {}s)", timeoutSeconds);
            ensurePageValid();
            page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(timeoutMillis));
            logger.info("Page is now fully loaded");
        } catch (Exception e) {
            logger.error("Failed to wait for page fully loaded", e);
            throw new TimeoutException("Page did not fully load within timeout", e);
        }
    }

    /**
     * 等待DOM内容加载完成
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForDOMContentLoaded(int timeoutSeconds) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            logger.info("Waiting for DOM content to be loaded (timeout: {}s)", timeoutSeconds);
            ensurePageValid();
            page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(timeoutMillis));
            logger.info("DOM content is now loaded");
        } catch (Exception e) {
            logger.error("Failed to wait for DOM content loaded", e);
            throw new TimeoutException("DOM content did not load within timeout", e);
        }
    }

    /**
     * 等待元素被选中
     * @param selector 元素选择器
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForElementChecked(String selector, int timeoutSeconds) {
        try {
            logger.info("Waiting for element to be checked: {} (timeout: {}s)", selector, timeoutSeconds);
            long endTime = System.currentTimeMillis() + timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                if (isChecked(selector)) {
                    logger.info("Element is now checked: {}", selector);
                    return;
                }
                waitForTimeout(500);
            }
            
            throw new TimeoutException("Element not checked within timeout: " + selector);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to wait for element to be checked: {}", selector, e);
            throw new TimeoutException("Failed to wait for element to be checked: " + selector, e);
        }
    }

    /**
     * 等待元素未被选中
     * @param selector 元素选择器
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForElementNotChecked(String selector, int timeoutSeconds) {
        try {
            logger.info("Waiting for element to be not checked: {} (timeout: {}s)", selector, timeoutSeconds);
            long endTime = System.currentTimeMillis() + timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                if (!isChecked(selector)) {
                    logger.info("Element is now not checked: {}", selector);
                    return;
                }
                waitForTimeout(500);
            }
            
            throw new TimeoutException("Element still checked within timeout: " + selector);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to wait for element to be not checked: {}", selector, e);
            throw new TimeoutException("Failed to wait for element to be not checked: " + selector, e);
        }
    }

    /**
     * 等待URL完全匹配
     * @param expectedUrl 期望的URL
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForUrlEquals(String expectedUrl, int timeoutSeconds) {
        try {
            logger.info("Waiting for URL to equal '{}' within {}s", expectedUrl, timeoutSeconds);
            long endTime = System.currentTimeMillis() + timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                String currentUrl = page.url();
                if (expectedUrl.equals(currentUrl)) {
                    logger.info("URL now equals '{}': {}", expectedUrl, currentUrl);
                    return;
                }
                waitForTimeout(500);
            }
            
            throw new TimeoutException("URL does not equal '" + expectedUrl + "' within timeout");
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to wait for URL equals: {}", expectedUrl, e);
            throw new TimeoutException("Failed to wait for URL equals: " + expectedUrl, e);
        }
    }

    /**
     * 等待URL以指定文本开头
     * @param expectedPrefix 期望的URL前缀
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForUrlStartsWith(String expectedPrefix, int timeoutSeconds) {
        try {
            logger.info("Waiting for URL to start with '{}' within {}s", expectedPrefix, timeoutSeconds);
            long endTime = System.currentTimeMillis() + timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                String currentUrl = page.url();
                if (currentUrl.startsWith(expectedPrefix)) {
                    logger.info("URL now starts with '{}': {}", expectedPrefix, currentUrl);
                    return;
                }
                waitForTimeout(500);
            }
            
            throw new TimeoutException("URL does not start with '" + expectedPrefix + "' within timeout");
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to wait for URL starts with: {}", expectedPrefix, e);
            throw new TimeoutException("Failed to wait for URL starts with: " + expectedPrefix, e);
        }
    }

    /**
     * 等待自定义条件为真
     * @param condition 自定义条件
     * @param timeoutSeconds 超时时间（秒）
     * @param conditionDescription 条件描述
     */
    public void waitForCustomCondition(Supplier<Boolean> condition, int timeoutSeconds, String conditionDescription) {
        try {
            logger.info("Waiting for custom condition '{}' within {}s", conditionDescription, timeoutSeconds);
            long endTime = System.currentTimeMillis() + timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                if (condition.get()) {
                    logger.info("Custom condition '{}' is now true", conditionDescription);
                    return;
                }
                waitForTimeout(500);
            }
            
            throw new TimeoutException("Custom condition '" + conditionDescription + "' did not become true within timeout");
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to wait for custom condition: {}", conditionDescription, e);
            throw new TimeoutException("Failed to wait for custom condition: " + conditionDescription, e);
        }
    }

    // ==================== 带重试的等待方法 ====================

    /**
     * 带重试的等待元素可见
     * @param selector 元素选择器
     * @param timeoutSeconds 超时时间（秒）
     * @param maxRetries 最大重试次数
     */
    public void waitForVisibleWithRetry(String selector, int timeoutSeconds, int maxRetries) {
        retry(() -> waitForVisible(selector), maxRetries, 1000, "waitForVisible");
    }

    /**
     * 带重试的等待元素隐藏
     * @param selector 元素选择器
     * @param timeoutSeconds 超时时间（秒）
     * @param maxRetries 最大重试次数
     */
    public void waitForHiddenWithRetry(String selector, int timeoutSeconds, int maxRetries) {
        retry(() -> waitForHidden(selector), maxRetries, 1000, "waitForHidden");
    }

    /**
     * 带重试的点击元素
     * @param selector 元素选择器
     * @param maxRetries 最大重试次数
     */
    public void clickWithRetry(String selector, int maxRetries) {
        retry(() -> click(selector), maxRetries, 1000, "click");
    }

    /**
     * 带重试的输入文本
     * @param selector 元素选择器
     * @param text 输入的文本
     * @param maxRetries 最大重试次数
     */
    public void typeWithRetry(String selector, String text, int maxRetries) {
        retry(() -> type(selector, text), maxRetries, 1000, "type");
    }

    /**
     * 带重试的导航到URL
     * @param url 目标URL
     * @param maxRetries 最大重试次数
     */
    public void navigateToWithRetry(String url, int maxRetries) {
        retry(() -> navigateTo(url), maxRetries, 2000, "navigateTo");
    }

    // ==================== 移动元素操作方法 ====================

    /**
     * 拖拽元素到另一个元素
     * @param sourceSelector 源元素选择器
     * @param targetSelector 目标元素选择器
     */
    public void dragAndDrop(String sourceSelector, String targetSelector) {
        try {
            logger.info("Dragging element '{}' to '{}'", sourceSelector, targetSelector);
            locator(sourceSelector).dragTo(locator(targetSelector));
        } catch (Exception e) {
            logger.error("Failed to drag element '{}' to '{}'", sourceSelector, targetSelector, e);
            throw new ElementException("Failed to drag element '" + sourceSelector + "' to '" + targetSelector + "'", e);
        }
    }

    /**
     * 滚动元素到指定位置
     * @param selector 元素选择器
     * @param scrollX 水平滚动位置
     * @param scrollY 垂直滚动位置
     */
    public void scrollTo(String selector, int scrollX, int scrollY) {
        try {
            logger.info("Scrolling element '{}' to ({}, {})", selector, scrollX, scrollY);
            locator(selector).evaluate("element => { element.scrollTo(arguments[0], arguments[1]); }", new Object[]{scrollX, scrollY});
        } catch (Exception e) {
            logger.error("Failed to scroll element '{}' to ({}, {})", selector, scrollX, scrollY, e);
            throw new ElementException("Failed to scroll element '" + selector + "' to (" + scrollX + ", " + scrollY + ")", e);
        }
    }

    /**
     * 滚动元素到底部
     * @param selector 元素选择器
     */
    public void scrollToBottomOf(String selector) {
        try {
            logger.info("Scrolling element '{}' to bottom", selector);
            locator(selector).evaluate("element => { element.scrollTo(0, element.scrollHeight); }");
        } catch (Exception e) {
            logger.error("Failed to scroll element '{}' to bottom", selector, e);
            throw new ElementException("Failed to scroll element '" + selector + "' to bottom", e);
        }
    }

    /**
     * 滚动元素到顶部
     * @param selector 元素选择器
     */
    public void scrollToTopOf(String selector) {
        try {
            logger.info("Scrolling element '{}' to top", selector);
            locator(selector).evaluate("element => { element.scrollTo(0, 0); }");
        } catch (Exception e) {
            logger.error("Failed to scroll element '{}' to top", selector, e);
            throw new ElementException("Failed to scroll element '" + selector + "' to top", e);
        }
    }

    /**
     * 滚动元素到指定偏移量
     * @param selector 元素选择器
     * @param offsetX X轴偏移量（像素）
     * @param offsetY Y轴偏移量（像素）
     */
    public void scrollBy(String selector, int offsetX, int offsetY) {
        try {
            logger.info("Scrolling element '{}' by offset ({}, {})", selector, offsetX, offsetY);
            locator(selector).evaluate("element => { element.scrollBy(arguments[0], arguments[1]); }", new Object[]{offsetX, offsetY});
        } catch (Exception e) {
            logger.error("Failed to scroll element '{}' by offset ({}, {})", selector, offsetX, offsetY, e);
            throw new ElementException("Failed to scroll element '" + selector + "' by offset (" + offsetX + ", " + offsetY + ")", e);
        }
    }

    /**
     * 获取元素的位置和尺寸
     * @param selector 元素选择器
     * @return 元素的边界框信息
     */
    public BoundingBox getElementBoundingBox(String selector) {
        try {
            BoundingBox box = locator(selector).boundingBox();
            if (box == null) {
                throw new ElementException("Element not found: " + selector);
            }
            logger.info("Element '{}' bounding box: x={}, y={}, width={}, height={}", 
                selector, box.x, box.y, box.width, box.height);
            return box;
        } catch (Exception e) {
            logger.error("Failed to get bounding box of element '{}'", selector, e);
            throw new ElementException("Failed to get bounding box of element: " + selector, e);
        }
    }

    /**
     * 滚动到指定元素并居中
     * @param targetSelector 目标元素选择器
     */
    public void scrollToElementCenter(String targetSelector) {
        try {
            logger.info("Scrolling to element and centering: {}", targetSelector);
            locator(targetSelector).scrollIntoViewIfNeeded();
        } catch (Exception e) {
            logger.error("Failed to scroll to element and center: {}", targetSelector, e);
            throw new ElementException("Failed to scroll to element and center: " + targetSelector, e);
        }
    }
}