package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkCore;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementNotClickableException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementNotVisibleException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.TimeoutException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.Element;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElementList;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.TimeoutConfig;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

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

    private static final ThreadLocal<BasePage> currentPage = new ThreadLocal<>();
    
    /**
     * 构造函数 - 初始化页面对象
     */
    public BasePage() {
        // 确保框架已初始化
        if (!FrameworkCore.getInstance().isInitialized()) {
            FrameworkCore.getInstance().initialize();
        }
        // 自动初始化带@Element注解的字段
        initializeAnnotatedFields();
    }

    /**
     * 确保page有效（如果已关闭则重新获取）
     */
    private void ensurePageValid() {
        if (page == null || page.isClosed()) {
            page = PlaywrightManager.getPage();
            setCurrentPage();  // 设置当前线程的Page实例
        }
    }

    /**
     * 初始化所有带@Element注解的字段
     * 使用反射自动创建PageElement实例并赋值
     * 支持单个PageElement和List<PageElement>两种类型
     */
    private void initializeAnnotatedFields() {
        for (Field field : this.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(Element.class)) {
                Element elementAnnotation = field.getAnnotation(Element.class);
                String selector = elementAnnotation.value();
                String description = elementAnnotation.description();

                try {
                    field.setAccessible(true);
                    
                    // 检查字段类型是否为 List<PageElement>
                    if (isListPageElement(field)) {
                        // 创建 PageElementList（动态列表，每次访问都会重新查询）
                        PageElementList elementList = new PageElementList(selector, this);
                        field.set(this, elementList);
                        
                        if (LoggingConfigUtil.isVerboseLoggingEnabled() && !description.isEmpty()) {
                            LoggingConfigUtil.logDebugIfVerbose(logger, "Initialized element list: {} - {} ({})",
                                field.getName(), description, selector);
                        } else if (LoggingConfigUtil.isVerboseLoggingEnabled()) {
                            LoggingConfigUtil.logDebugIfVerbose(logger, "Initialized element list: {} ({})",
                                field.getName(), selector);
                        }
                    } else {
                        // 创建单个 PageElement
                        PageElement pageElement = new PageElement(selector, this);
                        field.set(this, pageElement);

                        if (LoggingConfigUtil.isVerboseLoggingEnabled() && !description.isEmpty()) {
                            LoggingConfigUtil.logDebugIfVerbose(logger, "Initialized element: {} - {} ({})",
                                field.getName(), description, selector);
                        } else if (LoggingConfigUtil.isVerboseLoggingEnabled()) {
                            LoggingConfigUtil.logDebugIfVerbose(logger, "Initialized element: {} ({})",
                                field.getName(), selector);
                        }
                    }
                } catch (IllegalAccessException e) {
                    LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to initialize field: {}", field.getName(), e);
                    throw new ElementException("Failed to initialize field: " + field.getName(), e);
                }
            }
        }
    }

    /**
     * 检查字段类型是否为 List<PageElement> 或 PageElementList
     */
    private boolean isListPageElement(Field field) {
        // 检查是否为 PageElementList 类型
        if (PageElementList.class.isAssignableFrom(field.getType())) {
            return true;
        }
        
        // 检查是否为 List 类型
        if (!List.class.isAssignableFrom(field.getType())) {
            return false;
        }
        
        // 检查泛型类型
        Type genericType = field.getGenericType();
        if (genericType instanceof ParameterizedType) {
            ParameterizedType pType = (ParameterizedType) genericType;
            Type[] typeArgs = pType.getActualTypeArguments();
            if (typeArgs.length == 1 && typeArgs[0].equals(PageElement.class)) {
                return true;
            }
        }
        return false;
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
     * 获取当前线程的Page实例
     * @return 当前Page实例
     */
    public static BasePage getCurrentPage() {
        return currentPage.get();
    }

    /**
     * 设置当前线程的Page实例
     */
    protected void setCurrentPage() {
        currentPage.set(this);
    }

    /**
     * 清除当前线程的Page实例
     */
    protected static void clearCurrentPage() {
        currentPage.remove();
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
    public Locator locator(String selector) {
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
    public Locator byText(String text) {
        ensurePageValid();
        return page.getByText(text);
    }

    /**
     * 通过文本查找元素（精确匹配）
     */
    public Locator byExactText(String text) {
        ensurePageValid();
        return page.getByText(text, new Page.GetByTextOptions().setExact(true));
    }

    /**
     * 通过占位符查找输入框
     */
    public Locator byPlaceholder(String placeholder) {
        ensurePageValid();
        return page.getByPlaceholder(placeholder);
    }

    /**
     * 通过标签查找元素
     */
    public Locator byLabel(String label) {
        ensurePageValid();
        return page.getByLabel(label);
    }

    /**
     * 通过Alt文本查找元素
     */
    public Locator byAltText(String altText) {
        ensurePageValid();
        return page.getByAltText(altText);
    }

    /**
     * 通过Role查找元素
     */
    public Locator byRole(AriaRole role) {
        ensurePageValid();
        return page.getByRole(role);
    }

    /**
     * 通过Title查找元素
     */
    public Locator byTitle(String title) {
        ensurePageValid();
        return page.getByTitle(title);
    }

    /**
     * 通过TestId查找元素
     */
    public Locator byTestId(String testId) {
        ensurePageValid();
        return page.getByTestId(testId);
    }

    /**
     * 创建 PageElement 实例，支持链式调用
     * @param selector 元素选择器
     * @return PageElement 实例
     */
    public PageElement element(String selector) {
        return new PageElement(selector, this);
    }

    // ==================== 基础操作方法 ====================

    /**
     * 点击元素
     */
    public void click(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Clicking element: {}", selector);
            locator(selector).click();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to click element: {}", selector, e);
            throw new ElementNotClickableException(selector, e);
        }
    }

    /**
     * 双击元素
     */
    public void doubleClick(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Double clicking element: {}", selector);
            locator(selector).dblclick();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to double click element: {}", selector, e);
            throw new ElementNotClickableException(selector, e);
        }
    }

    /**
     * 右键点击元素
     */
    public void rightClick(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Right clicking element: {}", selector);
            locator(selector).click(new Locator.ClickOptions().setButton(MouseButton.RIGHT));
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to right click element: {}", selector, e);
            throw new ElementNotClickableException(selector, e);
        }
    }

    /**
     * 悬停在元素上
     */
    public void hover(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Hovering over element: {}", selector);
            locator(selector).hover();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to hover over element: {}", selector, e);
            throw new ElementNotVisibleException(selector, e);
        }
    }

    /**
     * 输入文本
     */
    public void type(String selector, String text) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Typing text '{}' into element: {}", text, selector);
            locator(selector).fill(text);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to type text '{}' into element: {}", text, selector, e);
            throw new ElementException("Failed to type text '" + text + "' into element: " + selector, e);
        }
    }

    /**
     * 清空输入框
     */
    public void clear(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Clearing element: {}", selector);
            locator(selector).clear();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to clear element: {}", selector, e);
            throw new ElementException("Failed to clear element: " + selector, e);
        }
    }

    /**
     * 追加文本
     */
    public void append(String selector, String text) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Appending text '{}' to element: {}", text, selector);
            locator(selector).fill(text);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to append text '{}' to element: {}", text, selector, e);
            throw new ElementException("Failed to append text '" + text + "' to element: " + selector, e);
        }
    }

    /**
     * 获取元素文本
     */
    public String getText(String selector) {
        try {
            String text = locator(selector).innerText();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Getting text from element {}: {}", selector, text);
            return text;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get text from element: {}", selector, e);
            throw new ElementException("Failed to get text from element: " + selector, e);
        }
    }

    /**
     * 获取元素内部HTML
     */
    public String innerHTML(String selector) {
        try {
            String html = locator(selector).innerHTML();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Getting innerHTML from element {}: {}", selector, html);
            return html;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get innerHTML from element: {}", selector, e);
            throw new ElementException("Failed to get innerHTML from element: " + selector, e);
        }
    }

    /**
     * 获取元素文本内容
     */
    public String textContent(String selector) {
        try {
            String text = locator(selector).textContent();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Getting textContent from element {}: {}", selector, text);
            return text;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get textContent from element: {}", selector, e);
            throw new ElementException("Failed to get textContent from element: " + selector, e);
        }
    }

    /**
     * 获取输入框的值
     */
    public String getValue(String selector) {
        try {
            String value = locator(selector).inputValue();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Getting value from element {}: {}", selector, value);
            return value;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get value from element: {}", selector, e);
            throw new ElementException("Failed to get value from element: " + selector, e);
        }
    }

    /**
     * 获取元素的属性值
     */
    public String getAttribute(String selector, String attributeName) {
        try {
            String value = locator(selector).getAttribute(attributeName);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Getting attribute '{}' from element {}: {}", attributeName, selector, value);
            return value;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get attribute '{}' from element: {}", attributeName, selector, e);
            throw new ElementException("Failed to get attribute '" + attributeName + "' from element: " + selector, e);
        }
    }

    /**
     * 选择下拉框选项
     */
    public void selectOption(String selector, String value) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Selecting option '{}' from element: {}", value, selector);
            locator(selector).selectOption(value);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to select option '{}' from element: {}", value, selector, e);
            throw new ElementException("Failed to select option '" + value + "' from element: " + selector, e);
        }
    }

    /**
     * 选择下拉框选项（通过索引）
     */
    public void selectOption(String selector, int index) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Selecting option at index {} from element: {}", index, selector);
            locator(selector).selectOption(new SelectOption().setIndex(index));
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to select option at index {} from element: {}", index, selector, e);
            throw new ElementException("Failed to select option at index " + index + " from element: " + selector, e);
        }
    }

    /**
     * 勾选复选框
     */
    public void check(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Checking element: {}", selector);
            locator(selector).check();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to check element: {}", selector, e);
            throw new ElementException("Failed to check element: " + selector, e);
        }
    }

    /**
     * 取消勾选复选框
     */
    public void uncheck(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Unchecking element: {}", selector);
            locator(selector).uncheck();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to uncheck element: {}", selector, e);
            throw new ElementException("Failed to uncheck element: " + selector, e);
        }
    }

    /**
     * 点击元素（轻触）
     */
    public void tap(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Tapping element: {}", selector);
            locator(selector).tap();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to tap element: {}", selector, e);
            throw new ElementException("Failed to tap element: " + selector, e);
        }
    }

    /**
     * 聚焦元素
     */
    public void focus(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Focusing element: {}", selector);
            locator(selector).focus();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to focus element: {}", selector, e);
            throw new ElementException("Failed to focus element: " + selector, e);
        }
    }

    /**
     * 上传文件
     */
    public void uploadFile(String selector, String filePath) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Uploading file '{}' to element: {}", filePath, selector);
            locator(selector).setInputFiles(Paths.get(filePath));
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to upload file '{}' to element: {}", filePath, selector, e);
            throw new ElementException("Failed to upload file '" + filePath + "' to element: " + selector, e);
        }
    }

    // ==================== 等待方法 ====================

    /**
     * 等待元素可见
     */
    public void waitForVisible(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element to be visible: {}", selector);
            locator(selector).waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element to be visible: {}", selector, e);
            throw new TimeoutException("Failed to wait for element to be visible: " + selector, e);
        }
    }

    /**
     * 等待元素隐藏
     */
    public void waitForHidden(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element to be hidden: {}", selector);
            locator(selector).waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element to be hidden: {}", selector, e);
            throw new TimeoutException("Failed to wait for element to be hidden: " + selector, e);
        }
    }

    /**
     * 等待元素被附加到DOM
     */
    public void waitForAttached(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element to be attached: {}", selector);
            locator(selector).waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED));
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element to be attached: {}", selector, e);
            throw new TimeoutException("Failed to wait for element to be attached: " + selector, e);
        }
    }

    /**
     * 等待元素从DOM中分离
     */
    public void waitForDetached(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element to be detached: {}", selector);
            locator(selector).waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED));
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element to be detached: {}", selector, e);
            throw new RuntimeException("Failed to wait for element to be detached: " + selector, e);
        }
    }

    /**
     * 等待指定时间（毫秒）
     */
    public void waitForTimeout(int milliseconds) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for {} milliseconds", milliseconds);
            ensurePageValid();
            page.waitForTimeout(milliseconds);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for {} milliseconds", milliseconds, e);
            throw new RuntimeException("Failed to wait for " + milliseconds + " milliseconds", e);
        }
    }

    // ==================== 判断方法 ====================

    /**
     * 检查元素是否可见（带隐式等待）
     * 方法会重试检查直到超时，提高测试稳定性
     *
     * @param selector 元素选择器
     * @return 元素可见返回true，否则返回false
     */
    public boolean isVisible(String selector) {
        try {
            int timeout = TimeoutConfig.getElementCheckTimeout();
            int interval = TimeoutConfig.getPollingInterval();
            long endTime = System.currentTimeMillis() + timeout;

            // 重试检查元素可见性
            while (System.currentTimeMillis() < endTime) {
                try {
                    boolean visible = locator(selector).isVisible();
                    if (visible) {
                        LoggingConfigUtil.logInfoIfVerbose(logger, "Element {} is visible: true (after {}ms)",
                            selector, timeout - (endTime - System.currentTimeMillis()));
                        return true;
                    }
                } catch (Exception e) {
                    // 元素可能还没加载，继续重试
                }

                // 等待下一次检查
                if (System.currentTimeMillis() < endTime) {
                    waitForTimeout(interval);
                }
            }

            // 超时后最后一次检查
            boolean finalVisible = locator(selector).isVisible();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element {} is visible: {} (after {}ms timeout)",
                selector, finalVisible, timeout);
            return finalVisible;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to check if element is visible: {}", selector, e);
            throw new RuntimeException("Failed to check if element is visible: " + selector, e);
        }
    }

    /**
     * 检查元素是否隐藏
     *
     * @param selector 元素选择器
     * @return 元素隐藏返回true，否则返回false
     */
    public boolean isHidden(String selector) {
        try {
            boolean hidden = locator(selector).isHidden();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element {} is hidden: {}", selector, hidden);
            return hidden;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to check if element is hidden: {}", selector, e);
            throw new RuntimeException("Failed to check if element is hidden: " + selector, e);
        }
    }

    /**
     * 检查页面是否关闭
     *
     * @return 页面关闭返回true，否则返回false
     */
    public boolean isClosed() {
        try {
            boolean closed = page.isClosed();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Page is closed: {}", closed);
            return closed;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to check if page is closed", e);
            throw new RuntimeException("Failed to check if page is closed", e);
        }
    }

    /**
     * 检查元素是否存在（带隐式等待）
     * 方法会重试检查直到超时，提高测试稳定性
     *
     * @param selector 元素选择器
     * @return 元素存在返回true，否则返回false
     */
    public boolean exists(String selector) {
        try {
            int timeout = TimeoutConfig.getElementCheckTimeout();
            int interval = TimeoutConfig.getPollingInterval();
            long endTime = System.currentTimeMillis() + timeout;

            // 重试检查元素存在性
            while (System.currentTimeMillis() < endTime) {
                try {
                    boolean exists = locator(selector).count() > 0;
                    if (exists) {
                        LoggingConfigUtil.logInfoIfVerbose(logger, "Element {} exists: true (after {}ms)",
                            selector, timeout - (endTime - System.currentTimeMillis()));
                        return true;
                    }
                } catch (Exception e) {
                    // 元素可能还没加载，继续重试
                }

                // 等待下一次检查
                if (System.currentTimeMillis() < endTime) {
                    waitForTimeout(interval);
                }
            }

            // 超时后最后一次检查
            boolean finalExists = locator(selector).count() > 0;
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element {} exists: {} (after {}ms timeout)",
                selector, finalExists, timeout);
            return finalExists;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to check if element exists: {}", selector, e);
            throw new RuntimeException("Failed to check if element exists: " + selector, e);
        }
    }

    /**
     * 检查元素是否被选中（带隐式等待）
     * 方法会重试检查直到超时，提高测试稳定性
     *
     * @param selector 元素选择器
     * @return 元素被选中返回true，否则返回false
     */
    public boolean isChecked(String selector) {
        try {
            int timeout = TimeoutConfig.getElementCheckTimeout();
            int interval = TimeoutConfig.getPollingInterval();
            long endTime = System.currentTimeMillis() + timeout;

            // 重试检查元素选中状态
            while (System.currentTimeMillis() < endTime) {
                try {
                    boolean checked = locator(selector).isChecked();
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Element {} is checked: {}", selector, checked);
                    return checked;
                } catch (Exception e) {
                    // 元素可能还没加载，继续重试
                }

                // 等待下一次检查
                if (System.currentTimeMillis() < endTime) {
                    waitForTimeout(interval);
                }
            }

            // 超时后最后一次检查
            boolean finalChecked = locator(selector).isChecked();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element {} is checked: {} (after {}ms timeout)",
                selector, finalChecked, timeout);
            return finalChecked;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to check if element is checked: {}", selector, e);
            throw new RuntimeException("Failed to check if element is checked: " + selector, e);
        }
    }

    /**
     * 检查元素是否启用（带隐式等待）
     * 方法会重试检查直到超时，提高测试稳定性
     *
     * @param selector 元素选择器
     * @return 元素启用返回true，否则返回false
     */
    public boolean isEnabled(String selector) {
        try {
            int timeout = TimeoutConfig.getElementCheckTimeout();
            int interval = TimeoutConfig.getPollingInterval();
            long endTime = System.currentTimeMillis() + timeout;

            // 重试检查元素启用状态
            while (System.currentTimeMillis() < endTime) {
                try {
                    boolean enabled = locator(selector).isEnabled();
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Element {} is enabled: {}", selector, enabled);
                    return enabled;
                } catch (Exception e) {
                    // 元素可能还没加载，继续重试
                }

                // 等待下一次检查
                if (System.currentTimeMillis() < endTime) {
                    waitForTimeout(interval);
                }
            }

            // 超时后最后一次检查
            boolean finalEnabled = locator(selector).isEnabled();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element {} is enabled: {} (after {}ms timeout)",
                selector, finalEnabled, timeout);
            return finalEnabled;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to check if element is enabled: {}", selector, e);
            throw new RuntimeException("Failed to check if element is enabled: " + selector, e);
        }
    }

    /**
     * 检查元素是否禁用（带隐式等待）
     * 方法会重试检查直到超时，提高测试稳定性
     *
     * @param selector 元素选择器
     * @return 元素禁用返回true，否则返回false
     */
    public boolean isDisabled(String selector) {
        try {
            int timeout = TimeoutConfig.getElementCheckTimeout();
            int interval = TimeoutConfig.getPollingInterval();
            long endTime = System.currentTimeMillis() + timeout;

            // 重试检查元素禁用状态
            while (System.currentTimeMillis() < endTime) {
                try {
                    boolean disabled = locator(selector).isDisabled();
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Element {} is disabled: {}", selector, disabled);
                    return disabled;
                } catch (Exception e) {
                    // 元素可能还没加载，继续重试
                }

                // 等待下一次检查
                if (System.currentTimeMillis() < endTime) {
                    waitForTimeout(interval);
                }
            }

            // 超时后最后一次检查
            boolean finalDisabled = locator(selector).isDisabled();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element {} is disabled: {} (after {}ms timeout)",
                selector, finalDisabled, timeout);
            return finalDisabled;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to check if element is disabled: {}", selector, e);
            throw new RuntimeException("Failed to check if element is disabled: " + selector, e);
        }
    }

    // ==================== 页面导航方法 ====================

    /**
     * 导航到指定URL
     */
    public void navigateTo(String url) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Navigating to URL: {}", url);
            ensurePageValid();
            page.navigate(url);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to navigate to URL: {}", url, e);
            throw new RuntimeException("Failed to navigate to URL: " + url, e);
        }
    }

    /**
     * 将页面带到前台
     */
    public void bringToFront() {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Bringing page to front");
            page.bringToFront();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to bring page to front", e);
            throw new RuntimeException("Failed to bring page to front", e);
        }
    }

    /**
     * 设置页面HTML内容
     */
    public void setContent(String html) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Setting page content");
            page.setContent(html);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to set page content", e);
            throw new RuntimeException("Failed to set page content", e);
        }
    }

    /**
     * 切换到指定索引的页面
     * 线程安全：使用ThreadLocal确保每个线程有独立的当前页面实例
     * 
     * @param pageIndex 页面索引（0-based）
     * 
     * 使用示例：
     * <pre>
     * // 切换到第一个页面
     * switchToPage(0);
     * 
     * // 切换到第二个页面
     * switchToPage(1);
     * </pre>
     */
    public void switchToPage(int pageIndex) {
        try {
            BrowserContext context = getContext();
            List<Page> pages = context.pages();
            
            if (pageIndex < 0 || pageIndex >= pages.size()) {
                throw new IndexOutOfBoundsException(
                    "Page index out of bounds: " + pageIndex + ", available pages: " + pages.size());
            }
            
            Page targetPage = pages.get(pageIndex);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Switching to page {} of {}: {}", 
                pageIndex + 1, pages.size(), targetPage.url());
            
            // 更新当前页面
            page = targetPage;
            
            // 更新当前线程的Page实例
            setCurrentPage();
            
            LoggingConfigUtil.logInfoIfVerbose(logger, "✓ Successfully switched to page {}", pageIndex + 1);
            
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to switch to page: {}", pageIndex, e);
            throw new RuntimeException("Failed to switch to page: " + pageIndex, e);
        }
    }

    /**
     * 切换到最新打开的页面（最后一个页面）
     * 线程安全：使用ThreadLocal确保每个线程有独立的当前页面实例
     * 
     * 使用示例：
     * <pre>
     * // 点击链接打开新页面后，切换到最新页面
     * link.click();
     * switchToLatestPage();
     * type("#search", "hello");
     * </pre>
     */
    public void switchToLatestPage() {
        try {
            BrowserContext context = getContext();
            List<Page> pages = context.pages();
            
            if (pages.isEmpty()) {
                throw new IllegalStateException("No pages available");
            }
            
            Page latestPage = pages.get(pages.size() - 1);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Switching to latest page: {}", latestPage.url());
            
            // 更新当前页面
            page = latestPage;
            
            // 更新当前线程的Page实例
            setCurrentPage();
            
            LoggingConfigUtil.logInfoIfVerbose(logger, "✓ Successfully switched to latest page");
            
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to switch to latest page", e);
            throw new RuntimeException("Failed to switch to latest page", e);
        }
    }

    /**
     * 关闭当前页面并切换到上一个页面
     * 线程安全：使用ThreadLocal确保每个线程有独立的当前页面实例
     * 
     * 使用示例：
     * <pre>
     * // 打开新页面并操作
     * switchToLatestPage();
     * type("#search", "hello");
     * 
     * // 关闭当前页面并返回上一个页面
     * closeCurrentPageAndSwitchBack();
     * type("#continue", "data");
     * </pre>
     */
    public void closeCurrentPageAndSwitchBack() {
        try {
            BrowserContext context = getContext();
            List<Page> pages = context.pages();
            
            if (pages.size() <= 1) {
                throw new IllegalStateException("Cannot close the only page");
            }
            
            // 关闭当前页面
            page.close();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Closed current page");
            
            // 切换到最后一个页面（通常是上一个页面）
            Page lastPage = pages.get(pages.size() - 1);
            
            // 更新当前页面
            page = lastPage;
            
            // 更新当前线程的Page实例
            setCurrentPage();
            
            LoggingConfigUtil.logInfoIfVerbose(logger, "✓ Switched back to previous page");
            
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to close current page and switch back", e);
            throw new RuntimeException("Failed to close current page and switch back", e);
        }
    }

    /**
     * 刷新页面
     */
    public void refresh() {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Refreshing page");
            ensurePageValid();
            // 使用 domcontentloaded 而不是 load，避免因持续的网络请求（如SSE、长轮询）导致阻塞
            page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to refresh page", e);
            throw new RuntimeException("Failed to refresh page", e);
        }
    }
    
    /**
     * 刷新页面（等待 load 事件）
     * 注意：如果页面有持续的网络请求，可能会阻塞
     */
    public void refreshAndWaitForLoad() {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Refreshing page (waiting for load)");
            ensurePageValid();
            page.reload();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to refresh page", e);
            throw new RuntimeException("Failed to refresh page", e);
        }
    }

    /**
     * 前进
     */
    public void forward() {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Going forward");
            ensurePageValid();
            page.goForward();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to go forward", e);
            throw new RuntimeException("Failed to go forward", e);
        }
    }

    /**
     * 后退
     */
    public void back() {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Going back");
            ensurePageValid();
            page.goBack();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to go back", e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Current URL: {}", url);
            return url;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get current URL", e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Page title: {}", title);
            return title;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get page title", e);
            throw new RuntimeException("Failed to get page title", e);
        }
    }

    // ==================== JavaScript执行方法 ====================

    /**
     * 执行JavaScript代码
     */
    public Object executeJavaScript(String script, Object... args) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Executing JavaScript: {}", script);
            ensurePageValid();
            return page.evaluate(script, args);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to execute JavaScript: {}", script, e);
            throw new RuntimeException("Failed to execute JavaScript: " + script, e);
        }
    }

    /**
     * 滚动到页面顶部
     */
    public void scrollToTop() {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Scrolling to top of page");
            ensurePageValid();
            page.evaluate("window.scrollTo(0, 0)");
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to scroll to top of page", e);
            throw new RuntimeException("Failed to scroll to top of page", e);
        }
    }

    /**
     * 滚动到页面底部
     */
    public void scrollToBottom() {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Scrolling to bottom of page");
            ensurePageValid();
            page.evaluate("window.scrollTo(0, document.body.scrollHeight)");
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to scroll to bottom of page", e);
            throw new RuntimeException("Failed to scroll to bottom of page", e);
        }
    }

    /**
     * 滚动到指定元素
     */
    public void scrollToElement(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Scrolling to element: {}", selector);
            locator(selector).scrollIntoViewIfNeeded();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to scroll to element: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element to be visible within {}s: {}", timeoutSeconds, selector);
            ensurePageValid();
            locator(selector).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(timeoutMillis));
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element is now visible: {}", selector);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element to be visible: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element to be hidden within {}s: {}", timeoutSeconds, selector);
            ensurePageValid();
            locator(selector).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.HIDDEN)
                .setTimeout(timeoutMillis));
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element is now hidden: {}", selector);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element to be hidden: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element to be clickable within {}s: {}", timeoutSeconds, selector);
            ensurePageValid();
            locator(selector).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(timeoutMillis));
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element is now clickable: {}", selector);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element to be clickable: {}", selector, e);
            throw new TimeoutException("Element not clickable within timeout: " + selector, e);
        }
    }

    /**
     * 在指定时间范围内等待页面标题包含文本（失败时抛出异常）
     * @param expectedTitle 期望的标题文本
     * @param timeoutSeconds 最大超时时间（秒）
     * @throws TimeoutException 如果页面标题在超时时间内不包含指定文本
     */
    public void waitForTitleContainsWithinTime(String expectedTitle, int timeoutSeconds) {
        try {
            int timeoutMillis = timeoutSeconds * 1000;
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for title to contain '{}' within {}s", expectedTitle, timeoutSeconds);
            long startTime = System.currentTimeMillis();
            long endTime = startTime + timeoutMillis;

            while (System.currentTimeMillis() < endTime) {
                String currentTitle = page.title();
                if (currentTitle.contains(expectedTitle)) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Title contains expected text: '{}'", expectedTitle);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }

            LoggingConfigUtil.logWarnIfVerbose(logger, "Timeout waiting for title to contain: {}", expectedTitle);
            throw new TimeoutException("Title does not contain expected text within timeout: " + expectedTitle);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for title to contain: {}", expectedTitle, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for URL to contain '{}' within {}s", expectedUrlFragment, timeoutSeconds);
            long startTime = System.currentTimeMillis();
            long endTime = startTime + timeoutMillis;

            while (System.currentTimeMillis() < endTime) {
                String currentUrl = page.url();
                if (currentUrl.contains(expectedUrlFragment)) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "URL contains expected fragment: '{}'", expectedUrlFragment);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }

            LoggingConfigUtil.logWarnIfVerbose(logger, "Timeout waiting for URL to contain: {}", expectedUrlFragment);
            throw new TimeoutException("URL does not contain expected fragment within timeout: " + expectedUrlFragment);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for URL to contain: {}", expectedUrlFragment, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Performing '{}' within {}s", actionDescription, timeoutSeconds);
            long startTime = System.currentTimeMillis();
            long endTime = startTime + timeoutMillis;

            while (System.currentTimeMillis() < endTime) {
                action.run();
                if (validation.get()) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Action '{}' completed successfully", actionDescription);
                    return true;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }

            LoggingConfigUtil.logWarnIfVerbose(logger, "Timeout performing action: {}", actionDescription);
            return false;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to perform action: {}", actionDescription, e);
            throw new RuntimeException("Failed to perform action: " + actionDescription, e);
        }
    }

    // ==================== 弹窗处理方法 ====================

    /**
     * 接受弹窗
     */
    public void acceptAlert() {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Accepting alert");
            ensurePageValid();
            page.onDialog(Dialog::accept);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to accept alert", e);
            throw new RuntimeException("Failed to accept alert", e);
        }
    }

    /**
     * 取消弹窗
     */
    public void dismissAlert() {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Dismissing alert");
            ensurePageValid();
            page.onDialog(Dialog::dismiss);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to dismiss alert", e);
            throw new RuntimeException("Failed to dismiss alert", e);
        }
    }

    /**
     * 在弹窗中输入文本并接受
     */
    public void acceptPrompt(String text) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Accepting prompt with text: {}", text);
            ensurePageValid();
            page.onDialog(dialog -> {
                dialog.accept(text);
            });
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to accept prompt with text: {}", text, e);
            throw new RuntimeException("Failed to accept prompt with text: " + text, e);
        }
    }

    // ==================== 截图和记录方法 ====================

    /**
     * 截取页面截图（全页或视口，由 playwright.screenshot.fullpage 配置控制）
     */
    public void takeScreenshot() {
        try {
            boolean fullPage = FrameworkConfigManager
                .getBoolean(FrameworkConfig.PLAYWRIGHT_SCREENSHOT_FULLPAGE);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Taking screenshot (fullPage: {})", fullPage);
            ensurePageValid();
            page.screenshot(new Page.ScreenshotOptions().setFullPage(fullPage));
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to take screenshot", e);
            throw new RuntimeException("Failed to take screenshot", e);
        }
    }

    /**
     * 截取指定元素截图
     */
    public void takeElementScreenshot(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Taking screenshot of element: {}", selector);
            locator(selector).screenshot();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to take screenshot of element: {}", selector, e);
            throw new RuntimeException("Failed to take screenshot of element: " + selector, e);
        }
    }

    // ==================== 验证方法 ====================

    /**
     * 验证元素文本是否包含指定文本（非断言，返回boolean）
     */
    public boolean textContains(String selector, String expectedText) {
        try {
            String actualText = getText(selector);
            boolean contains = actualText.contains(expectedText);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Text verification - Element '{}' contains '{}': {}", selector, expectedText, contains);
            return contains;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to verify text contains for element: {}", selector, e);
            throw new RuntimeException("Failed to verify text contains for element: " + selector, e);
        }
    }



    // ==================== 表单操作方法 ====================

    /**
     * 填写表单
     *
     * @param formData 键值对，键为选择器，值为输入的文本
     */
    public void fillForm(Map<String, String> formData) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Filling form with {} fields", formData.size());
            for (Map.Entry<String, String> entry : formData.entrySet()) {
                type(entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to fill form with {} fields", formData.size(), e);
            throw new RuntimeException("Failed to fill form with " + formData.size() + " fields", e);
        }
    }

    /**
     * 提交表单
     */
    public void submitForm(String formSelector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Submitting form: {}", formSelector);
            locator(formSelector).evaluate("form => form.submit()");
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to submit form: {}", formSelector, e);
            throw new RuntimeException("Failed to submit form: " + formSelector, e);
        }
    }

    // ==================== Cookie和存储方法 ====================

    /**
     * 添加Cookie
     */
    public void addCookie(String name, String value) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Adding cookie: {} = {}", name, value);
            getContext().addCookies(Collections.singletonList(new Cookie(name, value)));
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to add cookie: {} = {}", name, value, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Clearing all cookies");
            getContext().clearCookies();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to clear all cookies", e);
            throw new RuntimeException("Failed to clear all cookies", e);
        }
    }

    // ==================== 调试方法 ====================

    /**
     * 暂停执行（用于调试）
     */
    public void pause() {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Pausing execution for debugging");
            ensurePageValid();
            page.pause();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to pause execution for debugging", e);
            throw new RuntimeException("Failed to pause execution for debugging", e);
        }
    }

    /**
     * 打印页面日志
     */
    public void logPageInfo() {
        try {
            ensurePageValid();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Page Information:");
            LoggingConfigUtil.logInfoIfVerbose(logger, "URL: {}", page.url());
            LoggingConfigUtil.logInfoIfVerbose(logger, "Title: {}", page.title());
            LoggingConfigUtil.logInfoIfVerbose(logger, "Viewport: {}", page.viewportSize());
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to log page information", e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Page contains text '{}': {}", text, contains);
            return contains;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to check if page contains text: {}", text, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for page to load with timeout: {}s", timeoutSeconds);
            ensurePageValid();
            page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(timeoutMillis));
            LoggingConfigUtil.logInfoIfVerbose(logger, "Page loaded successfully");
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for page load", e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element to be clickable: {}", selector);
            ensurePageValid();
            locator(selector).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.ATTACHED)
                .setTimeout(timeoutMillis));
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element is now clickable: {}", selector);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element to be clickable: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element to be not visible: {}", selector);
            ensurePageValid();
            locator(selector).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.HIDDEN)
                .setTimeout(timeoutMillis));
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element is now not visible: {}", selector);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element to be not visible: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Current page count: {}", pageCount);
            return pageCount;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get page count", e);
            throw new RuntimeException("Failed to get page count", e);
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
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get current page index", e);
            throw new RuntimeException("Failed to get current page index", e);
        }
    }

    /**
     * 关闭当前页面
     */
    public void closePage() {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Closing current page");
            ensurePageValid();
            page.close();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Page closed successfully");
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to close page", e);
            throw new RuntimeException("Failed to close page", e);
        }
    }

    /**
     * 设置视口大小
     */
    public void setViewportSize(int width, int height) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Setting viewport size to {}x{}", width, height);
            page.setViewportSize(width, height);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to set viewport size: {}x{}", width, height, e);
            throw new RuntimeException("Failed to set viewport size: " + width + "x" + height, e);
        }
    }

    /**
     * 设置输入文件
     */
    public void setInputFiles(String selector, String... filePaths) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Setting input files for element: {}", selector);
            Path[] paths = new Path[filePaths.length];
            for (int i = 0; i < filePaths.length; i++) {
                paths[i] = Paths.get(filePaths[i]);
            }
            locator(selector).setInputFiles(paths);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to set input files for element: {}", selector, e);
            throw new RuntimeException("Failed to set input files for element: " + selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for new page to open with timeout: {}s", timeoutSeconds);
            ensureContextValid();
            
            int initialPageCount = context.pages().size();
            long startTime = System.currentTimeMillis();
            long endTime = startTime + timeoutMillis;
            
            while (System.currentTimeMillis() < endTime) {
                if (context.pages().size() > initialPageCount) {
                    Page newPage = context.pages().get(initialPageCount);
                    LoggingConfigUtil.logInfoIfVerbose(logger, "New page opened: {}", newPage.url());
                    return newPage;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }

            throw new RuntimeException("Timeout waiting for new page to open");
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for new page", e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for title to contain: '{}' with timeout: {}s", expectedTitle, timeoutSeconds);
            ensurePageValid();

            long startTime = System.currentTimeMillis();
            long endTime = startTime + timeoutMillis;

            while (System.currentTimeMillis() < endTime) {
                String currentTitle = page.title();
                if (currentTitle.contains(expectedTitle)) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Title contains expected text: '{}'", expectedTitle);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }

            throw new RuntimeException("Timeout waiting for title to contain: " + expectedTitle);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for title to contain: {}", expectedTitle, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for URL to contain: '{}' with timeout: {}s", expectedUrlFragment, timeoutSeconds);
            ensurePageValid();
            
            long startTime = System.currentTimeMillis();
            long endTime = startTime + timeoutMillis;
            
            while (System.currentTimeMillis() < endTime) {
                String currentUrl = page.url();
                if (currentUrl.contains(expectedUrlFragment)) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "URL contains expected fragment: '{}'", expectedUrlFragment);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }

            throw new RuntimeException("Timeout waiting for URL to contain: " + expectedUrlFragment);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for URL to contain: {}", expectedUrlFragment, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Got page source, length: {} characters", pageSource.length());
            return pageSource;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get page source", e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Page source contains text '{}': {}", text, contains);
            return contains;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to check if page source contains text: {}", text, e);
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
                LoggingConfigUtil.logErrorIfVerbose(logger, errorMsg);
                throw new RuntimeException(errorMsg);
            }
            LoggingConfigUtil.logInfoIfVerbose(logger, "Attribute '{}' is '{}' as expected for element: {}", attributeName, expectedValue, selector);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to verify attribute value for element: {}", selector, e);
            throw new RuntimeException("Failed to verify attribute value for element: " + selector, e);
        }
    }


    /**
     * 检查元素是否可点击（带隐式等待）
     * 元素可点击需要同时满足：可见且启用
     * 方法会重试检查直到超时，提高测试稳定性
     *
     * @param selector 元素选择器
     * @return 元素可点击返回true，否则返回false
     */
    public boolean isElementClickable(String selector) {
        try {
            int timeout = TimeoutConfig.getElementCheckTimeout();
            int interval = TimeoutConfig.getPollingInterval();
            long endTime = System.currentTimeMillis() + timeout;

            // 重试检查元素可点击状态
            while (System.currentTimeMillis() < endTime) {
                try {
                    boolean clickable = locator(selector).isVisible() && locator(selector).isEnabled();
                    if (clickable) {
                        LoggingConfigUtil.logInfoIfVerbose(logger, "Element {} is clickable: true (after {}ms)",
                            selector, timeout - (endTime - System.currentTimeMillis()));
                        return true;
                    }
                } catch (Exception e) {
                    // 元素可能还没加载，继续重试
                }

                // 等待下一次检查
                if (System.currentTimeMillis() < endTime) {
                    waitForTimeout(interval);
                }
            }

            // 超时后最后一次检查
            boolean finalClickable = locator(selector).isVisible() && locator(selector).isEnabled();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element {} is clickable: {} (after {}ms timeout)",
                selector, finalClickable, timeout);
            return finalClickable;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to check if element is clickable: {}", selector, e);
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
                LoggingConfigUtil.logErrorIfVerbose(logger, errorMsg);
                throw new RuntimeException(errorMsg);
            }
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element is visible as expected: {}", selector);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to verify element should be visible: {}", selector, e);
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
                LoggingConfigUtil.logErrorIfVerbose(logger, errorMsg);
                throw new RuntimeException(errorMsg);
            }
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element is not visible as expected: {}", selector);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to verify element should not be visible: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element count for {}: {}", selector, count);
            return count;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get element count for: {}", selector, e);
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
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Operation '{}' succeeded on attempt {}/{}", operationDescription, attempt + 1, maxRetries + 1);
                }
                return;
            } catch (Exception e) {
                attempt++;
                if (attempt > maxRetries) {
                    LoggingConfigUtil.logErrorIfVerbose(logger, "Operation '{}' failed after {} attempts: {}", operationDescription, maxRetries + 1, e.getMessage());
                    throw new TimeoutException("Operation '" + operationDescription + "' failed after " + (maxRetries + 1) + " attempts", e);
                }
                LoggingConfigUtil.logWarnIfVerbose(logger, "Operation '{}' failed on attempt {}/{}, retrying in {}ms. Error: {}",
                    operationDescription, attempt, maxRetries + 1, retryIntervalMs, e.getMessage());
                waitForTimeout(retryIntervalMs);
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
                        LoggingConfigUtil.logInfoIfVerbose(logger, "Operation '{}' and validation succeeded on attempt {}/{}", operationDescription, attempt + 1, maxRetries + 1);
                    }
                    return true;
                }
            } catch (Exception e) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Operation '{}' failed on attempt {}/{}. Error: {}", 
                    operationDescription, attempt + 1, maxRetries + 1, e.getMessage());
            }
            
            attempt++;
            if (attempt > maxRetries) {
                LoggingConfigUtil.logErrorIfVerbose(logger, "Operation '{}' failed validation after {} attempts", operationDescription, maxRetries + 1);
                throw new TimeoutException("Operation '" + operationDescription + "' failed validation after " + (maxRetries + 1) + " attempts");
            }
            
            LoggingConfigUtil.logInfoIfVerbose(logger, "Retrying operation '{}' in {}ms (attempt {}/{})",
                operationDescription, retryIntervalMs, attempt + 1, maxRetries + 1);
            waitForTimeout(retryIntervalMs);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element to exist: {} (timeout: {}s)", selector, timeoutSeconds);
            ensurePageValid();
            locator(selector).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.ATTACHED)
                .setTimeout(timeoutMillis));
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element exists: {}", selector);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element to exist: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element to not exist: {} (timeout: {}s)", selector, timeoutSeconds);
            ensurePageValid();
            locator(selector).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.DETACHED)
                .setTimeout(timeoutMillis));
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element does not exist: {}", selector);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element to not exist: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element to be editable: {} (timeout: {}s)", selector, timeoutSeconds);
            long endTime = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                if (isEnabled(selector) && !isDisabled(selector)) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Element is now editable: {}", selector);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }
            
            throw new TimeoutException("Element not editable within timeout: " + selector);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element to be editable: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element to be disabled: {} (timeout: {}s)", selector, timeoutSeconds);
            long endTime = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                if (isDisabled(selector)) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Element is now disabled: {}", selector);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }

            throw new TimeoutException("Element not disabled within timeout: " + selector);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element to be disabled: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element to be enabled: {} (timeout: {}s)", selector, timeoutSeconds);
            long endTime = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                if (isEnabled(selector)) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Element is now enabled: {}", selector);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }
            
            throw new TimeoutException("Element not enabled within timeout: " + selector);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element to be enabled: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element attribute '{}' to equal '{}' within {}s: {}", 
                attributeName, expectedAttributeValue, timeoutSeconds, selector);
            long endTime = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                String actualValue = getAttribute(selector, attributeName);
                if (expectedAttributeValue.equals(actualValue)) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Element attribute '{}' now equals '{}': {}", attributeName, expectedAttributeValue, selector);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }

            throw new TimeoutException("Element attribute '" + attributeName + "' did not equal '" + expectedAttributeValue + "' within timeout");
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element attribute: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element attribute '{}' to contain '{}' within {}s: {}", 
                attributeName, expectedAttributeValue, timeoutSeconds, selector);
            long endTime = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                String actualValue = getAttribute(selector, attributeName);
                if (actualValue != null && actualValue.contains(expectedAttributeValue)) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Element attribute '{}' now contains '{}': {}", attributeName, expectedAttributeValue, selector);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }
            
            throw new TimeoutException("Element attribute '" + attributeName + "' did not contain '" + expectedAttributeValue + "' within timeout");
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element attribute: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element text to contain '{}' within {}s: {}", expectedText, timeoutSeconds, selector);
            long endTime = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                String actualText = getText(selector);
                if (actualText.contains(expectedText)) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Element text now contains '{}': {}", expectedText, selector);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }

            throw new TimeoutException("Element text did not contain '" + expectedText + "' within timeout");
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element text: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element text to equal '{}' within {}s: {}", expectedText, timeoutSeconds, selector);
            long endTime = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                String actualText = getText(selector);
                if (expectedText.equals(actualText)) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Element text now equals '{}': {}", expectedText, selector);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }
            
            throw new TimeoutException("Element text did not equal '" + expectedText + "' within timeout");
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element text: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element count to equal {} within {}s: {}", expectedCount, timeoutSeconds, selector);
            long endTime = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                int actualCount = getElementCount(selector);
                if (actualCount == expectedCount) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Element count now equals {}: {}", expectedCount, selector);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }

            int actualCount = getElementCount(selector);
            throw new TimeoutException("Element count did not equal " + expectedCount + " within timeout. Actual: " + actualCount);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element count: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element count to be at least {} within {}s: {}", minimumCount, timeoutSeconds, selector);
            long endTime = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                int actualCount = getElementCount(selector);
                if (actualCount >= minimumCount) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Element count now at least {}: {} (actual: {})", minimumCount, selector, actualCount);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }
            
            int actualCount = getElementCount(selector);
            throw new TimeoutException("Element count did not reach minimum " + minimumCount + " within timeout. Actual: " + actualCount);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element count: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for network to be idle (timeout: {}s)", timeoutSeconds);
            ensurePageValid();
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(timeoutMillis));
            LoggingConfigUtil.logInfoIfVerbose(logger, "Network is now idle");
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for network idle", e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for page to be fully loaded (timeout: {}s)", timeoutSeconds);
            ensurePageValid();
            page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(timeoutMillis));
            LoggingConfigUtil.logInfoIfVerbose(logger, "Page is now fully loaded");
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for page fully loaded", e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for DOM content to be loaded (timeout: {}s)", timeoutSeconds);
            ensurePageValid();
            page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(timeoutMillis));
            LoggingConfigUtil.logInfoIfVerbose(logger, "DOM content is now loaded");
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for DOM content loaded", e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element to be checked: {} (timeout: {}s)", selector, timeoutSeconds);
            long endTime = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                if (isChecked(selector)) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Element is now checked: {}", selector);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }

            throw new TimeoutException("Element not checked within timeout: " + selector);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element to be checked: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for element to be not checked: {} (timeout: {}s)", selector, timeoutSeconds);
            long endTime = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                if (!isChecked(selector)) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Element is now not checked: {}", selector);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }
            
            throw new TimeoutException("Element still checked within timeout: " + selector);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for element to be not checked: {}", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for URL to equal '{}' within {}s", expectedUrl, timeoutSeconds);
            long endTime = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                String currentUrl = page.url();
                if (expectedUrl.equals(currentUrl)) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "URL now equals '{}': {}", expectedUrl, currentUrl);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }

            throw new TimeoutException("URL does not equal '" + expectedUrl + "' within timeout");
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for URL equals: {}", expectedUrl, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for URL to start with '{}' within {}s", expectedPrefix, timeoutSeconds);
            long endTime = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                String currentUrl = page.url();
                if (currentUrl.startsWith(expectedPrefix)) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "URL now starts with '{}': {}", expectedPrefix, currentUrl);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }
            
            throw new TimeoutException("URL does not start with '" + expectedPrefix + "' within timeout");
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for URL starts with: {}", expectedPrefix, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for custom condition '{}' within {}s", conditionDescription, timeoutSeconds);
            long endTime = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
            
            while (System.currentTimeMillis() < endTime) {
                if (condition.get()) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Custom condition '{}' is now true", conditionDescription);
                    return;
                }
                waitForTimeout(TimeoutConfig.getPollingInterval());
            }

            throw new TimeoutException("Custom condition '" + conditionDescription + "' did not become true within timeout");
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for custom condition: {}", conditionDescription, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Dragging element '{}' to '{}'", sourceSelector, targetSelector);
            locator(sourceSelector).dragTo(locator(targetSelector));
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to drag element '{}' to '{}'", sourceSelector, targetSelector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Scrolling element '{}' to ({}, {})", selector, scrollX, scrollY);
            locator(selector).evaluate("element => { element.scrollTo(arguments[0], arguments[1]); }", new Object[]{scrollX, scrollY});
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to scroll element '{}' to ({}, {})", selector, scrollX, scrollY, e);
            throw new ElementException("Failed to scroll element '" + selector + "' to (" + scrollX + ", " + scrollY + ")", e);
        }
    }

    /**
     * 滚动元素到底部
     * @param selector 元素选择器
     */
    public void scrollToBottomOf(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Scrolling element '{}' to bottom", selector);
            locator(selector).evaluate("element => { element.scrollTo(0, element.scrollHeight); }");
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to scroll element '{}' to bottom", selector, e);
            throw new ElementException("Failed to scroll element '" + selector + "' to bottom", e);
        }
    }

    /**
     * 滚动元素到顶部
     * @param selector 元素选择器
     */
    public void scrollToTopOf(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Scrolling element '{}' to top", selector);
            locator(selector).evaluate("element => { element.scrollTo(0, 0); }");
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to scroll element '{}' to top", selector, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Scrolling element '{}' by offset ({}, {})", selector, offsetX, offsetY);
            locator(selector).evaluate("element => { element.scrollBy(arguments[0], arguments[1]); }", new Object[]{offsetX, offsetY});
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to scroll element '{}' by offset ({}, {})", selector, offsetX, offsetY, e);
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
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element '{}' bounding box: x={}, y={}, width={}, height={}", 
                selector, box.x, box.y, box.width, box.height);
            return box;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get bounding box of element '{}'", selector, e);
            throw new ElementException("Failed to get bounding box of element: " + selector, e);
        }
    }

    /**
     * 滚动到指定元素并居中
     * @param targetSelector 目标元素选择器
     */
    public void scrollToElementCenter(String targetSelector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Scrolling to element and centering: {}", targetSelector);
            locator(targetSelector).scrollIntoViewIfNeeded();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to scroll to element and center: {}", targetSelector, e);
            throw new ElementException("Failed to scroll to element and center: " + targetSelector, e);
        }
    }

    // ==================== Frame 操作方法 ====================

    /**
     * 获取所有 frames
     * @return 所有 frames 列表
     */
    public List<Frame> getFrames() {
        try {
            ensurePageValid();
            List<Frame> frames = page.frames();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Found {} frames in the page", frames.size());
            return frames;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get frames", e);
            throw new RuntimeException("Failed to get frames", e);
        }
    }

    /**
     * 通过名称获取 frame
     * @param name frame 名称
     * @return Frame 对象，如果找不到则返回 null
     */
    public Frame getFrame(String name) {
        try {
            ensurePageValid();
            Frame frame = page.frame(name);
            if (frame != null) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "Found frame with name: {}", name);
            } else {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Frame not found with name: {}", name);
            }
            return frame;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get frame by name: {}", name, e);
            throw new RuntimeException("Failed to get frame by name: " + name, e);
        }
    }

    /**
     * 通过 URL 获取 frame
     * @param url URL 模式（支持通配符）
     * @return Frame 对象，如果找不到则返回 null
     */
    public Frame getFrameByUrl(String url) {
        try {
            ensurePageValid();
            Frame frame = page.frameByUrl(Pattern.compile(url));
            if (frame != null) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "Found frame with URL pattern: {}", url);
            } else {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Frame not found with URL pattern: {}", url);
            }
            return frame;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get frame by URL: {}", url, e);
            throw new RuntimeException("Failed to get frame by URL: " + url, e);
        }
    }

    /**
     * 获取主 frame
     * @return 主 Frame 对象
     */
    public Frame getMainFrame() {
        try {
            ensurePageValid();
            Frame mainFrame = page.mainFrame();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Got main frame");
            return mainFrame;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get main frame", e);
            throw new RuntimeException("Failed to get main frame", e);
        }
    }

    /**
     * 通过选择器获取 frame 定位器
     * @param selector frame 元素选择器
     * @return FrameLocator 对象
     */
    public FrameLocator frameLocator(String selector) {
        try {
            ensurePageValid();
            FrameLocator frameLocator = page.frameLocator(selector);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Got frame locator for selector: {}", selector);
            return frameLocator;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get frame locator: {}", selector, e);
            throw new RuntimeException("Failed to get frame locator: " + selector, e);
        }
    }

    /**
     * 在指定 frame 中执行操作
     * @param frameName frame 名称
     * @param operation 要执行的操作
     */
    public void executeInFrame(String frameName, Runnable operation) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Executing operation in frame: {}", frameName);
            Frame frame = getFrame(frameName);
            if (frame == null) {
                throw new RuntimeException("Frame not found: " + frameName);
            }
            operation.run();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Operation executed successfully in frame: {}", frameName);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to execute operation in frame: {}", frameName, e);
            throw new RuntimeException("Failed to execute operation in frame: " + frameName, e);
        }
    }

    /**
     * 在指定 URL 的 frame 中执行操作
     * @param urlPattern URL 模式
     * @param operation 要执行的操作
     */
    public void executeInFrameByUrl(String urlPattern, Runnable operation) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Executing operation in frame with URL pattern: {}", urlPattern);
            Frame frame = getFrameByUrl(urlPattern);
            if (frame == null) {
                throw new RuntimeException("Frame not found with URL pattern: " + urlPattern);
            }
            operation.run();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Operation executed successfully in frame with URL pattern: {}", urlPattern);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to execute operation in frame with URL pattern: {}", urlPattern, e);
            throw new RuntimeException("Failed to execute operation in frame with URL pattern: " + urlPattern, e);
        }
    }

    /**
     * 等待 frame 加载完成
     * @param selector frame 元素选择器
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitForFrame(String selector, int timeoutSeconds) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Waiting for frame to load: {} (timeout: {}s)", selector, timeoutSeconds);
            ensurePageValid();
            int timeoutMillis = timeoutSeconds * 1000;
            page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(timeoutMillis));
            LoggingConfigUtil.logInfoIfVerbose(logger, "Frame loaded successfully: {}", selector);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to wait for frame: {}", selector, e);
            throw new TimeoutException("Frame not loaded within timeout: " + selector, e);
        }
    }

    // ==================== 键盘操作方法 ====================

    /**
     * 在元素上按键
     * @param selector 元素选择器
     * @param key 要按下的键（如 "Enter", "ArrowDown", "Control+a" 等）
     */
    public void press(String selector, String key) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Pressing key '{}' on element: {}", key, selector);
            locator(selector).press(key);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to press key '{}' on element: {}", key, selector, e);
            throw new ElementException("Failed to press key '" + key + "' on element: " + selector, e);
        }
    }

    /**
     * 在当前聚焦元素上按键
     * @param key 要按下的键
     */
    public void press(String key) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Pressing key: {}", key);
            ensurePageValid();
            page.keyboard().press(key);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to press key: {}", key, e);
            throw new ElementException("Failed to press key: " + key, e);
        }
    }

    /**
     * 在元素上输入文本（逐个字符，带延迟）
     * @param selector 元素选择器
     * @param text 要输入的文本
     */
    public void typeSlowly(String selector, String text) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Typing text slowly '{}' into element: {}", text, selector);
            locator(selector).fill(text, new Locator.FillOptions().setTimeout(50));
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to type text slowly '{}' into element: {}", text, selector, e);
            throw new ElementException("Failed to type text slowly '" + text + "' into element: " + selector, e);
        }
    }

    /**
     * 在当前聚焦元素上输入文本（逐个字符，带延迟）
     * @param text 要输入的文本
     */
    public void typeSlowly(String text) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Typing text slowly: {}", text);
            ensurePageValid();
            // 使用逐字符输入，模拟真实打字
            for (char c : text.toCharArray()) {
                page.keyboard().type(String.valueOf(c));
                page.waitForTimeout(50); // 每个字符之间延迟 50ms
            }
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to type text slowly: {}", text, e);
            throw new ElementException("Failed to type text slowly: " + text, e);
        }
    }

    /**
     * 在元素上插入文本（不覆盖现有内容）
     * @param selector 元素选择器
     * @param text 要插入的文本
     */
    public void insertText(String selector, String text) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Inserting text '{}' into element: {}", text, selector);
            locator(selector).evaluate("el => el.value += arguments[0]", text);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to insert text '{}' into element: {}", text, selector, e);
            throw new ElementException("Failed to insert text '" + text + "' into element: " + selector, e);
        }
    }

    /**
     * 在元素上按下键
     * @param selector 元素选择器
     * @param key 要按下的键
     */
    public void keyDown(String selector, String key) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Pressing down key '{}' on element: {}", key, selector);
            locator(selector).press(key);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to press down key '{}' on element: {}", key, selector, e);
            throw new ElementException("Failed to press down key '" + key + "' on element: " + selector, e);
        }
    }

    /**
     * 在元素上释放键
     * @param selector 元素选择器
     * @param key 要释放的键
     */
    public void keyUp(String selector, String key) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Releasing key '{}' on element: {}", key, selector);
            // Playwright 的 keyDown/keyUp 需要通过 keyboard 对象
            ensurePageValid();
            page.keyboard().up(key);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to release key '{}' on element: {}", key, selector, e);
            throw new ElementException("Failed to release key '" + key + "' on element: " + selector, e);
        }
    }

    /**
     * 选择所有文本（Ctrl+A / Cmd+A）
     * @param selector 元素选择器
     */
    public void selectAll(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Selecting all text in element: {}", selector);
            locator(selector).press("Control+A");
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to select all text in element: {}", selector, e);
            throw new ElementException("Failed to select all text in element: " + selector, e);
        }
    }

    /**
     * 复制文本（Ctrl+C / Cmd+C）
     * @param selector 元素选择器
     */
    public void copy(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Copying text from element: {}", selector);
            locator(selector).press("Control+C");
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to copy text from element: {}", selector, e);
            throw new ElementException("Failed to copy text from element: " + selector, e);
        }
    }

    /**
     * 粘贴文本（Ctrl+V / Cmd+V）
     * @param selector 元素选择器
     */
    public void paste(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Pasting text to element: {}", selector);
            locator(selector).press("Control+V");
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to paste text to element: {}", selector, e);
            throw new ElementException("Failed to paste text to element: " + selector, e);
        }
    }

    /**
     * 剪切文本（Ctrl+X / Cmd+X）
     * @param selector 元素选择器
     */
    public void cut(String selector) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Cutting text from element: {}", selector);
            locator(selector).press("Control+X");
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to cut text from element: {}", selector, e);
            throw new ElementException("Failed to cut text from element: " + selector, e);
        }
    }

    // ==================== 鼠标操作方法 ====================

    /**
     * 在指定坐标点击鼠标
     * @param x X 坐标
     * @param y Y 坐标
     */
    public void mouseClick(int x, int y) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Clicking mouse at ({}, {})", x, y);
            ensurePageValid();
            page.mouse().click(x, y);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to click mouse at ({}, {})", x, y, e);
            throw new ElementException("Failed to click mouse at (" + x + ", " + y + ")", e);
        }
    }

    /**
     * 在指定坐标双击鼠标
     * @param x X 坐标
     * @param y Y 坐标
     */
    public void mouseDoubleClick(int x, int y) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Double clicking mouse at ({}, {})", x, y);
            ensurePageValid();
            page.mouse().dblclick(x, y);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to double click mouse at ({}, {})", x, y, e);
            throw new ElementException("Failed to double click mouse at (" + x + ", " + y + ")", e);
        }
    }

    /**
     * 按下鼠标按钮
     */
    public void mouseDown() {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Pressing mouse down");
            ensurePageValid();
            page.mouse().down();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to press mouse down", e);
            throw new ElementException("Failed to press mouse down", e);
        }
    }

    /**
     * 释放鼠标按钮
     */
    public void mouseUp() {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Releasing mouse up");
            ensurePageValid();
            page.mouse().up();
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to release mouse up", e);
            throw new ElementException("Failed to release mouse up", e);
        }
    }

    /**
     * 移动鼠标到指定坐标
     * @param x X 坐标
     * @param y Y 坐标
     */
    public void mouseMove(int x, int y) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Moving mouse to ({}, {})", x, y);
            ensurePageValid();
            page.mouse().move(x, y);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to move mouse to ({}, {})", x, y, e);
            throw new ElementException("Failed to move mouse to (" + x + ", " + y + ")", e);
        }
    }

    /**
     * 滚动鼠标滚轮
     * @param deltaX X 轴滚动量（像素）
     * @param deltaY Y 轴滚动量（像素）
     */
    public void mouseWheel(int deltaX, int deltaY) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Scrolling mouse wheel by ({}, {})", deltaX, deltaY);
            ensurePageValid();
            page.mouse().wheel(deltaX, deltaY);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to scroll mouse wheel by ({}, {})", deltaX, deltaY, e);
            throw new ElementException("Failed to scroll mouse wheel by (" + deltaX + ", " + deltaY + ")", e);
        }
    }

    /**
     * 获取元素中心坐标
     * @param selector 元素选择器
     * @return 包含 x 和 y 坐标的数组
     */
    public int[] getElementCenter(String selector) {
        try {
            BoundingBox box = locator(selector).boundingBox();
            if (box == null) {
                throw new ElementException("Element not found: " + selector);
            }
            int centerX = (int) (box.x + box.width / 2);
            int centerY = (int) (box.y + box.height / 2);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Element '{}' center: ({}, {})", selector, centerX, centerY);
            return new int[]{centerX, centerY};
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to get element center: {}", selector, e);
            throw new ElementException("Failed to get element center: " + selector, e);
        }
    }

    /**
     * 点击元素中心（使用鼠标操作）
     * @param selector 元素选择器
     */
    public void clickAtCenter(String selector) {
        try {
            int[] center = getElementCenter(selector);
            mouseClick(center[0], center[1]);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to click at center of element: {}", selector, e);
            throw new ElementException("Failed to click at center of element: " + selector, e);
        }
    }

    /**
     * 拖拽元素到指定坐标
     * @param sourceSelector 源元素选择器
     * @param targetX 目标 X 坐标
     * @param targetY 目标 Y 坐标
     */
    public void dragToCoordinates(String sourceSelector, int targetX, int targetY) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Dragging element '{}' to ({}, {})", sourceSelector, targetX, targetY);
            int[] sourceCenter = getElementCenter(sourceSelector);
            
            mouseMove(sourceCenter[0], sourceCenter[1]);
            mouseDown();
            mouseMove(targetX, targetY);
            mouseUp();
            
            LoggingConfigUtil.logInfoIfVerbose(logger, "Drag completed successfully");
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to drag element '{}' to ({}, {})", sourceSelector, targetX, targetY, e);
            throw new ElementException("Failed to drag element '" + sourceSelector + "' to (" + targetX + ", " + targetY + ")", e);
        }
    }

    /**
     * Execute JavaScript code (helper method)
     * @param script JavaScript code
     * @param args arguments
     * @return execution result
     */
    private Object evaluate(String script, Object... args) {
        try {
            ensurePageValid();
            return page.evaluate(script, args);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to evaluate script: {}", script, e);
            throw new RuntimeException("Failed to evaluate script: " + script, e);
        }
    }
}
