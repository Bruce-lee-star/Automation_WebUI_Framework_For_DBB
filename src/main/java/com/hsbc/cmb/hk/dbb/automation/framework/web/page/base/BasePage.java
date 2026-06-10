package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkCore;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementOperationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.NavigationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.TimeoutException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.Element;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElementList;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.TextNormalizer;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public abstract class BasePage {
    protected static final Logger logger = LoggerFactory.getLogger(BasePage.class);

    protected Page page;
    protected BrowserContext context;
    private static final ThreadLocal<BasePage> currentPage = new ThreadLocal<>();

    /**
     * 当前 iframe 上下文（Playwright Frame），使用 ThreadLocal 使所有 Page 实例共享。
     * <p>null 表示当前在主页面 DOM 中操作。
     * <p>设置后，所有通过 {@link #locator(String)} 创建的 Locator 将自动在 iframe 内查找元素，
     * 从而解决"切到 iframe 后元素 not found in DOM"的经典问题。
     */
    private static final ThreadLocal<Frame> currentFrame = new ThreadLocal<>();

    // ===================== 全局文本统一格式化工具 =====================
    /**
     * 文本标准化：委托给 {@link TextNormalizer#normalize(String)} 统一实现，
     * 避免 BasePage 和 PageElement 重复定义相同的 Pattern 常量和 normalize 逻辑。
     */
    protected String normalizeText(String raw) {
        return TextNormalizer.normalize(raw);
    }

    public BasePage() {
        if (!FrameworkCore.getInstance().isInitialized()) {
            FrameworkCore.getInstance().initialize();
        }
        initializeAnnotatedFields();
    }

    // 页面切换锁：防止并发页面切换导致元素绑定错乱
    private static final Object PAGE_SWITCH_LOCK = new Object();

    /** 首次注解字段初始化标志——页面切换时走 invalidateCache 而非重建对象 */
    private volatile boolean annotatedFieldsInitialized = false;

    private void ensurePageValid() {
        if (page == null || isPageClosed(page)) {
            synchronized (PAGE_SWITCH_LOCK) {
                // 双重检查：锁内再次确认 page 仍无效
                if (page == null || isPageClosed(page)) {
                    page = PlaywrightManager.getPage();
                    currentFrame.remove(); // 页面重建后重置 iframe 上下文
                    setCurrentPage();
                    // 页面切换后重新绑定所有 @Element 注解字段到新 Page
                    initializeAnnotatedFields();
                }
            }
        } else {
            // 检测 PlaywrightManager 中的 page 是否已被其他实例切换（如 switchToPage/switchNewPage）
            Page managerPage = PlaywrightManager.getPage();
            if (managerPage != page) {
                page = managerPage;
                currentFrame.remove();
                setCurrentPage();
                initializeAnnotatedFields();
            }
        }
    }

    /**
     * 安全检查 Page 是否已关闭（避免 isClosed() 抛异常导致流程中断）
     */
    private boolean isPageClosed(Page p) {
        if (p == null) return true;
        try {
            return p.isClosed();
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "page.isClosed() threw exception, treating as closed: {}", e.getMessage());
            return true;
        }
    }

    public void ensureContextValid() {
        if (context == null) {
            context = PlaywrightManager.getContext();
        }
    }

    /**
     * 初始化/刷新注解字段。
     * 首次调用：创建 PageElement/PageElementList 对象。
     * 后续调用（页面切换）：仅调用 invalidateCache() 刷新 Locator 缓存，
     * 避免重建对象和反射赋值的开销。
     */
    private void initializeAnnotatedFields() {
        for (Field field : this.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(Element.class)) {
                Element elementAnnotation = field.getAnnotation(Element.class);
                String selector = elementAnnotation.value();
                field.setAccessible(true);

                if (annotatedFieldsInitialized) {
                    // 页面切换后——只需刷新缓存，不重建对象
                    try {
                        Object existing = field.get(this);
                        if (existing instanceof PageElement pe) {
                            pe.invalidateCache();
                        } else if (existing instanceof PageElementList pel) {
                            pel.invalidateCache();
                        }
                        // 如果 existing 为 null 或类型不匹配，回退到重新创建
                        if (existing == null || !(existing instanceof PageElement || existing instanceof PageElementList)) {
                            createField(field, selector);
                        }
                    } catch (IllegalAccessException e) {
                        // get 失败，回退到重新创建
                        createField(field, selector);
                    }
                    continue;
                }

                createField(field, selector);
            }
        }
        annotatedFieldsInitialized = true;
    }

    /** 创建 PageElement 或 PageElementList 实例并赋值给字段 */
    private void createField(Field field, String selector) {
        try {
            if (List.class.isAssignableFrom(field.getType())) {
                field.set(this, new PageElementList(selector, this));
            } else {
                field.set(this, new PageElement(selector, this));
            }
        } catch (Exception e) {
            throw new ElementException("Init field failed: " + field.getName());
        }
    }

    public static BasePage getCurrentPage() {
        return currentPage.get();
    }

    protected void setCurrentPage() {
        currentPage.set(this);
    }

    public static void clearCurrentPage() {
        currentPage.remove();
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

    private boolean waitForCondition(BooleanSupplier condition, int timeoutSeconds, String desc) {
        ensurePageValid();
        long end = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        while (System.currentTimeMillis() < end) {
            try {
                if (condition.getAsBoolean()) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Condition passed: {}", desc);
                    return true;
                }
            } catch (Exception e) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Condition check failed: {}", e.getMessage());
            }
            page.waitForTimeout(PlaywrightManager.config().getPollingInterval());
        }
        LoggingConfigUtil.logWarnIfVerbose(logger, "⏳ Timeout waiting for: {}", desc);
        return false;
    }

    public boolean performActionWithTimeout(Runnable action, Supplier<Boolean> condition, int timeoutSeconds, String desc) {
        ensurePageValid();
        long end = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        while (System.currentTimeMillis() < end) {
            try {
                action.run();
                if (condition.get()) return true;
            } catch (Exception ignored) {
            }
            page.waitForTimeout(PlaywrightManager.config().getPollingInterval());
        }
        throw new TimeoutException("Action timed out: " + desc);
    }

    public void waitForCustomCondition(Supplier<Boolean> condition, int timeout, String desc) {
        if (!waitForCondition(condition::get, timeout, desc)) {
            throw new TimeoutException("Custom condition failed: " + desc);
        }
    }

    public void waitForElementExists(String selector, int timeout) {
        element(selector).waitForExists(timeout);
    }

    public void waitForElementNotExists(String selector, int timeout) {
        element(selector).waitForNotExists(timeout);
    }

    public void waitForElementEditable(String selector, int timeout) {
        element(selector).waitForEditable(timeout);
    }

    public void waitForElementEnabled(String selector, int timeout) {
        element(selector).waitForEnabled(timeout);
    }

    public void waitForElementDisabled(String selector, int timeout) {
        element(selector).waitForDisabled(timeout);
    }

    public void waitForElementChecked(String selector, int timeout) {
        element(selector).waitForChecked(timeout);
    }

    public void waitForElementNotChecked(String selector, int timeout) {
        element(selector).waitForNotChecked(timeout);
    }

    public void waitForElementCount(String selector, int expected, int timeout) {
        String desc = "count equals " + expected + " for " + selector;
        if (!waitForCondition(() -> locator(selector).count() == expected, timeout, desc)) {
            throw new TimeoutException(desc);
        }
    }

    public void waitForElementCountAtLeast(String selector, int min, int timeout) {
        String desc = "count at least " + min + " for " + selector;
        if (!waitForCondition(() -> locator(selector).count() >= min, timeout, desc)) {
            throw new TimeoutException(desc);
        }
    }


    public void waitForUrlEquals(String url, int timeout) {
        if (!waitForCondition(() -> getCurrentUrl().equals(url), timeout, "url equals: " + url)) {
            throw new TimeoutException("URL not equals: " + url);
        }
    }

    public void waitForUrlStartsWith(String prefix, int timeout) {
        if (!waitForCondition(() -> getCurrentUrl().startsWith(prefix), timeout, "url starts with: " + prefix)) {
            throw new TimeoutException("URL not start with: " + prefix);
        }
    }

    public void waitForNetworkIdle(int timeout) {
        ensurePageValid();
        page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout((long) timeout * 1000));
    }

    public void waitForPageFullyLoaded(int timeout) {
        ensurePageValid();
        page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout((long) timeout * 1000));
    }

    public void waitForDOMContentLoaded(int timeout) {
        ensurePageValid();
        page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout((long) timeout * 1000));
    }

    public void shouldBeVisible(String selector) {
        if (!locator(selector).isVisible()) {
            throw new ElementException("Element should be visible: " + selector);
        }
    }

    public void shouldBeNotVisible(String selector) {
        if (!locator(selector).isHidden()) {
            throw new ElementException("Element should be hidden: " + selector);
        }
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
        return retryWithValidation(operation, validation, maxRetries, 500, desc);
    }

    public void retry(Runnable runnable, String desc) {
        retry(runnable, 3, 1000, desc);
    }

    public void retry(Runnable runnable, int retries, int intervalMs, String desc) {
        ensurePageValid();
        for (int i = 0; i <= retries; i++) {
            try {
                runnable.run();
                return;
            } catch (Exception e) {
                if (i == retries) throw new RuntimeException("Retry failed: " + desc, e);
                page.waitForTimeout(intervalMs);
            }
        }
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
        ensurePageValid();
        for (int i = 0; i <= maxRetries; i++) {
            try {
                operation.run();
                if (validation.getAsBoolean()) return true;
            } catch (Exception ignored) {
            }
            page.waitForTimeout(retryIntervalMs);
        }
        return false;
    }

    /**
     * 带重试的可见性等待。
     * <p>直接委托 PageElement.waitForVisible()（内部已含等待+超时机制），
     * 不再额外包裹 BasePage.retry() 避免双重重试。
     */
    public void waitForVisibleWithRetry(String selector, int timeout, int retries) {
        element(selector).waitForVisible(timeout);
    }

    /**
     * 带重试的隐藏等待。
     * <p>直接委托 PageElement.waitForNotVisible()（内部已含等待+超时机制），
     * 不再额外包裹 BasePage.retry() 避免双重重试。
     */
    public void waitForHiddenWithRetry(String selector, int timeout, int retries) {
        element(selector).waitForNotVisible(timeout);
    }

    /**
     * 带重试的点击操作。
     * <p>直接委托 PageElement.click()（内部已含 executeWithRetry 企业级重试机制），
     * 不再额外包裹 BasePage.retry() 避免双重重试。
     */
    public void clickWithRetry(String selector, int retries) {
        element(selector).click();
    }

    /**
     * 带重试的输入操作。
     * <p>直接委托 PageElement.type()（内部已含 executeWithRetry 企业级重试机制），
     * 不再额外包裹 BasePage.retry() 避免双重重试。
     */
    public void typeWithRetry(String selector, String text, int retries) {
        element(selector).type(text);
    }

    public void navigateToWithRetry(String url, int retries) {
        retry(() -> navigateTo(url), retries, 1000, "navigate to: " + url);
    }

    /**
     * 创建 Playwright Locator，自动适配 iframe 上下文。
     * <p>若当前处于 iframe 内（{@link #currentFrame} != null），则在 iframe DOM 中定位元素；
     * 否则在主页面 DOM 中定位。解决切到 iframe 后元素 "not found in DOM" 的问题。
     */
    public Locator locator(String selector) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        if (frame != null) {
            return frame.locator(selector);
        }
        return page.locator(selector);
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

    public void click(String selector) {
        try {
            element(selector).click();
        } catch (ElementOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new ElementOperationException("click", selector, 
                "Failed to click element: " + selector, e);
        }
    }

    public void jsClick(String selector) {
        locator(selector).evaluate("el => el.click()");
    }

    public void type(String selector, String text) {
        element(selector).type(text);
    }

    public void append(String selector, String text) {
        PageElement pe = element(selector);
        pe.focus();
        String current = pe.getValue();
        if (current == null) current = "";
        pe.fill(current + text);
    }

    public void clear(String selector) {
        element(selector).clear();
    }

    // ===================== 读取文本 全部归一化 =====================
    public String getText(String selector) {
        return element(selector).getText();
    }

    public String getInputValue(String selector) {
        return element(selector).getValue();
    }

    public String getAttribute(String selector, String attr) {
        return element(selector).getAttribute(attr);
    }

    public String getAttributeValue(String selector, String attr, String defaultValue) {
        String val = getAttribute(selector, attr);
        return val == null ? defaultValue : normalizeText(val);
    }

    public void selectOption(String selector, int index) {
        element(selector).selectByIndex(index);
    }

    public void selectByVisibleText(String selector, String text) {
        element(selector).selectByVisibleText(text);
    }

    public void check(String selector) {
        element(selector).check();
    }

    public void uncheck(String selector) {
        element(selector).uncheck();
    }

    public boolean isChecked(String selector) {
        return element(selector).isChecked();
    }

    public boolean isEnabled(String selector) {
        return element(selector).isEnabled();
    }

    public boolean isDisabled(String selector) {
        return element(selector).isDisabled();
    }

    public boolean isVisible(String selector) {
        return element(selector).isVisible();
    }

    public boolean isHidden(String selector) {
        return element(selector).isNotVisible();
    }

    public int getElementCount(String selector) {
        return locator(selector).count();
    }

    public void navigateTo(String url) {
        ensurePageValid();
        String pageLoadState = PlaywrightManager.config().getPageLoadState();
        Page.NavigateOptions options = new Page.NavigateOptions();
        options.setTimeout((long) PlaywrightManager.config().getNavigationTimeout());
        // 根据配置设置等待策略
        switch (pageLoadState.toLowerCase()) {
            case "networkidle":
                options.setWaitUntil(WaitUntilState.NETWORKIDLE);
                break;
            case "domcontentloaded":
                options.setWaitUntil(WaitUntilState.DOMCONTENTLOADED);
                break;
            case "commit":
                options.setWaitUntil(WaitUntilState.COMMIT);
                break;
            default:
                options.setWaitUntil(WaitUntilState.LOAD);
        }
        try {
            // navigate 已经根据 options 中的 waitUntil 等待页面加载
            // 不需要再额外 waitForLoadState，避免重复等待
            page.navigate(url, options);
            logger.debug("Navigation completed (waitUntil={}): {}", pageLoadState, url);
            resetFrameContextAfterNavigation();
        } catch (TimeoutError e) {
            // TimeoutError 必须放在 PlaywrightException 前面（因为 TimeoutError 继承 PlaywrightException）
            throw new NavigationException(url, PlaywrightManager.config().getNavigationTimeout(), e);
        } catch (PlaywrightException e) {
            throw new NavigationException(url, "Navigation failed: " + e.getMessage(), e);
        }
    }

    /**
     * 页面导航（navigate / refresh / back / forward）后重置 iframe 上下文。
     * <p>页面内容发生变化后，旧的 Frame 对象会变为 detached，
     * 必须将 currentFrame 置为 null 并刷新 @Element 注解字段的 Locator 缓存，
     * 否则后续元素操作会在已失效的 Frame 上执行导致报错。
     */
    private void resetFrameContextAfterNavigation() {
        if (currentFrame.get() != null) {
            currentFrame.remove();
            initializeAnnotatedFields();
            logger.debug("Reset iframe context after page navigation");
        }
    }

    public String getCurrentUrl() {
        ensurePageValid();
        return page.url();
    }

    public String getTitle() {
        ensurePageValid();
        return page.title();
    }

    public void refresh() {
        ensurePageValid();
        page.reload();
        resetFrameContextAfterNavigation();
    }

    public void back() {
        ensurePageValid();
        page.goBack();
        resetFrameContextAfterNavigation();
    }

    public void forward() {
        ensurePageValid();
        page.goForward();
        resetFrameContextAfterNavigation();
    }

    // ===================== 页面切换内部工具方法 =====================

    /**
     * 页面切换后的统一后置处理：重置 iframe 上下文、登记 ThreadLocal、刷新 @Element 字段。
     * <p>6 个 switch/reset 类方法统一走此入口，消除重复代码。
     */
    private void onPageSwitched() {
        currentFrame.remove();
        setCurrentPage();
        initializeAnnotatedFields();
    }

    /**
     * 设置当前 page 引用并同步到 PlaywrightManager，使同一 context 内的其他 PageObject 实例可感知。
     */
    private void setPageReference(Page target) {
        page = target;
        PlaywrightManager.setPage(page);
    }

    /**
     * 安全 bringToFront：page 已关闭或异常时仅 warn 不抛异常。
     */
    private void safeBringToFront() {
        try {
            page.bringToFront();
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "bringToFront() failed: {}", e.getMessage());
        }
    }

    /**
     * 安全记录页面切换日志（url/title 可能在 page 已关闭时抛异常）。
     */
    private void logPageSwitchInfo() {
        try {
            logger.info("Switch to page: url={}, title={}", page.url(), page.title());
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Unable to log new page info (url/title): {}", e.getMessage());
        }
    }

    // ===================== 页面切换方法（对标 Selenium switchTo().window()） =====================

    /**
     * 按索引切换到指定页面（Page），负数表示从末尾倒数（-1 = 最后一个）。
     * <p>对标 Selenium {@code switchTo().window()}：
     * <pre>{@code
     * myPage.switchToPage(0);   // 切换到第一个页面
     * myPage.switchToPage(-1);  // 切换到最后一个页面（替代 switchToLatestPage）
     * }</pre>
     *
     * <p>内置 isClosed 守卫：负数索引若目标已关闭，自动向前回退到第一个未关闭的页面。
     *
     * @param index 页面索引，支持负数（-1 = 最后一个，-2 = 倒数第二个…）
     */
    public void switchToPage(int index) {
        ensureContextValid();
        List<Page> pages = context.pages();
        if (pages.isEmpty()) throw new TimeoutException("No pages available in context");

        int resolved = index >= 0 ? index : pages.size() + index;
        if (resolved < 0 || resolved >= pages.size())
            throw new IndexOutOfBoundsException("Invalid page index: " + index);

        Page target = pages.get(resolved);

        // 负数索引场景：目标可能已关闭，从该位置向前回退
        if (index < 0 && isPageClosed(target)) {
            target = findLastAvailablePage(pages, resolved);
        }
        if (isPageClosed(target))
            throw new TimeoutException("Target page at index " + index + " is closed");

        setPageReference(target);
        safeBringToFront();
        onPageSwitched();
        logPageSwitchInfo();
    }

    /**
     * 切换到指定的 Page 实例（用于 waitForPopup 等 Playwright API 捕获到的外部 Page）。
     * <p>与 {@link #switchToNewPage(Runnable, int)} 不同，本方法跳过事件监听，直接使用调用方已捕获的 Page 引用。
     *
     * @param page 目标页面（Page 实例，不能为 null 或已关闭）
     */
    public Page switchToPage(Page page) {
        if (page == null) {
            throw new IllegalArgumentException("page must not be null");
        }
        if (page.isClosed()) {
            throw new TimeoutException("Target page is already closed");
        }
        ensureContextValid();
        setPageReference(page);
        safeBringToFront();
        onPageSwitched();
        logPageSwitchInfo();
        return page;
    }

    /**
     * 触发操作并等待新页面打开，对标 Selenium {@code switchTo().newWindow()}。
     * <p>基于 Playwright 原生 {@code context.waitForPage(action)} 在浏览器事件级捕获新 Tab，
     * 彻底消除轮询/计数方式的时序问题。
     *
     * <pre>{@code
     * // 推荐：将触发操作传入方法，一步完成"点击 + 等待新页面 + 切换"
     * myPage.switchToNewPage(() -> myPage.element("#link").click(), 15);
     *
     * // 也可以配合 waitForPopup（更适合精确捕获弹窗）
     * Page popup = myPage.getPage().waitForPopup(() -> { ... });
     * myPage.switchToPage(popup);
     * }</pre>
     *
     * @param trigger     触发新页面打开的操作（如点击链接）
     * @param timeoutSecs 等待超时秒数
     * @return 新打开的 Page 实例
     */
    public Page switchToNewPage(Runnable trigger, int timeoutSecs) {
        ensureContextValid();
        try {
            return acceptNewPage(context.waitForPage(() -> trigger.run()));
        } catch (PlaywrightException e) {
            throw new TimeoutException("Waiting for new page timed out after " + timeoutSecs + " seconds", e);
        }
    }

    /**
     * 仅等待新页面（不触发操作），适用场景：前序步骤已触发新 Tab，本方法负责等待+切换。
     * <p>先检查是否已有新页面（快速路径），若无则通过 {@code context.waitForPage()} 注册事件监听。
     *
     * <pre>{@code
     * myPage.switchToNewPage(15);  // 等待最多 15 秒
     * }</pre>
     *
     * @param timeoutSecs 等待超时秒数
     * @return 新打开的 Page 实例
     */
    public Page switchToNewPage(int timeoutSecs) {
        ensureContextValid();
        // 快速路径：前序步骤可能已触发新页面，直接检查是否已存在
        for (int i = context.pages().size() - 1; i >= 0; i--) {
            Page p = context.pages().get(i);
            if (p != page && !isPageClosed(p)) {
                return acceptNewPage(p);
            }
        }
        // 慢路径：注册 Playwright 原生 page 事件监听
        try {
            return acceptNewPage(context.waitForPage(() -> {}));
        } catch (PlaywrightException e) {
            throw new TimeoutException("Waiting for new page timed out after " + timeoutSecs + " seconds", e);
        }
    }

    /** 新页面校验 + 切换 + 日志，供两个重载共用 */
    private Page acceptNewPage(Page newPage) {
        try {
            if (newPage.isClosed()) {
                throw new TimeoutException("New page was created but already closed");
            }
        } catch (Exception e) {
            if (e instanceof TimeoutException) throw (TimeoutException) e;
            LoggingConfigUtil.logWarnIfVerbose(logger,
                    "isClosed() check failed, page may already be gone: {}", e.getMessage());
            throw new TimeoutException("New page is no longer available (closed/destroyed)");
        }
        setPageReference(newPage);
        safeBringToFront();
        onPageSwitched();
        logPageSwitchInfo();
        return newPage;
    }

    /**
     * 关闭当前页面并自动切换到前一个页面。
     * <p>若当前已是最前页面则切换到 index 0；不会关闭唯一页面。
     */
    public void closeCurrentPage() {
        ensureContextValid();
        List<Page> pages = context.pages();

        if (pages.isEmpty()) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "No pages available in context");
            page = null;
            return;
        }

        if (pages.size() <= 1) {
            LoggingConfigUtil.logWarnIfVerbose(logger,
                    "Only one page available (size={}), skipping close to avoid losing the last page", pages.size());
            Page onlyPage = pages.get(0);
            if (page != onlyPage) {
                setPageReference(onlyPage);
                onPageSwitched();
            }
            return;
        }

        int currentIndex = pages.indexOf(page);
        try {
            if (page != null && !page.isClosed()) {
                page.close();
            } else {
                LoggingConfigUtil.logDebugIfVerbose(logger,
                        "Current page reference is null or already closed, skip close()");
            }
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger,
                    "Exception while closing current page: {}", e.getMessage());
        }

        List<Page> updatedPages = context.pages();
        if (updatedPages.isEmpty()) {
            LoggingConfigUtil.logWarnIfVerbose(logger,
                    "No pages available after closing current page, page reference will be null");
            page = null;
            return;
        }
        int targetIndex = Math.max(0, Math.min(currentIndex - 1, updatedPages.size() - 1));
        setPageReference(updatedPages.get(targetIndex));
        onPageSwitched();
    }

    // ===================== 内部辅助 =====================

    /** 从后往前找第一个未关闭的页面（兜底逻辑，供 switchToPage 负数索引使用） */
    private Page findLastAvailablePage(List<Page> pages, int startFrom) {
        for (int i = startFrom; i >= 0; i--) {
            try {
                if (!pages.get(i).isClosed()) {
                    LoggingConfigUtil.logWarnIfVerbose(logger,
                            "Latest window was closed, falling back to window at index {}", i);
                    return pages.get(i);
                }
            } catch (Exception ignored) {}
        }
        return pages.get(startFrom); // 全部已关闭，返回原目标由调用方 isClosed 抛异常
    }

    public Frame getFrame(String name) {
        ensurePageValid();
        return page.frame(name);
    }

    /**
     * 获取当前 iframe 上下文（ThreadLocal 共享，所有 Page 实例可见）。
     * @return 当前 iframe Frame，未切入 iframe 时返回 null
     */
    public Frame getCurrentFrame() {
        return currentFrame.get();
    }

    // ===================== iframe 切换（对标 Selenium switchTo().frame() / defaultContent()） =====================

    /**
     * 按 name / id / CSS 选择器切换到 iframe，对标 Selenium {@code switchTo().frame(String)}。
     * <p>查找策略（按顺序尝试）：
     * <ol>
     *   <li>作为 frame name/id 查找</li>
     *   <li>作为 CSS 选择器查找</li>
     * </ol>
     * 一个方法替代了原 switchToFrame(name) + switchToFrameBySelector(selector)。
     *
     * <pre>{@code
     * myPage.switchToFrame("myFrame");           // name/id
     * myPage.switchToFrame("iframe.embedded-view"); // CSS selector
     * }</pre>
     *
     * @param nameOrSelector iframe 的 name、id 或 CSS 选择器
     * @return Playwright FrameLocator
     */
    public FrameLocator switchToFrame(String nameOrSelector) {
        ensurePageValid();
        // 策略 1：按 Playwright 原生 frame(name) 查找（匹配 name/id 属性）
        Frame frame = page.frame(nameOrSelector);
        if (frame == null) {
            // 策略 2：回退为 CSS 选择器
            try {
                com.microsoft.playwright.ElementHandle iframeEl = page.locator(nameOrSelector).elementHandle();
                frame = iframeEl.contentFrame();
            } catch (Exception e) {
                logger.error("Failed to switch to iframe by selector '{}': {}", nameOrSelector, e.getMessage());
            }
        }
        if (frame == null) {
            throw new RuntimeException("Frame not found: '" + nameOrSelector
                    + "'. Tried as name/id and CSS selector. Available frames: " + page.frames().size());
        }
        currentFrame.set(frame);
        initializeAnnotatedFields();
        logger.info("Switched to iframe: '{}'", nameOrSelector);
        String escaped = nameOrSelector.replace("\\", "\\\\").replace("'", "\\'");
        return page.frameLocator("iframe[name='" + escaped + "'], iframe[id='" + escaped + "'], " + nameOrSelector);
    }

    /**
     * 按索引切换到 iframe，对标 Selenium {@code switchTo().frame(int)}。
     *
     * @param index iframe 索引（从 0 开始，0 通常是主页面）
     * @return Playwright Frame
     */
    public Frame switchToFrame(int index) {
        ensurePageValid();
        List<Frame> frames = page.frames();
        if (index < 0 || index >= frames.size()) {
            throw new IndexOutOfBoundsException("Invalid frame index: " + index + " (total: " + frames.size() + ")");
        }
        Frame selectedFrame = frames.get(index);
        currentFrame.set(selectedFrame);
        initializeAnnotatedFields();
        logger.info("Switched to iframe by index: {} (total: {})", index, frames.size());
        return selectedFrame;
    }

    /**
     * 切换回主页面 DOM（退出 iframe），对标 Selenium {@code switchTo().defaultContent()}。
     */
    public void switchToDefaultContent() {
        if (currentFrame.get() != null) {
            currentFrame.remove();
            initializeAnnotatedFields();
            logger.info("Switched back to default content (top-level page)");
        }
    }

    /** 获取当前 Page 中所有 Frame 列表。 */
    public List<Frame> getAllFrames() {
        ensurePageValid();
        return page.frames();
    }

    public void executeInFrame(String frameName, Consumer<Frame> action) {
        Frame frame = getFrame(frameName);
        if (frame == null) throw new RuntimeException("Frame not found: " + frameName);
        action.accept(frame);
    }

    public void scrollToElementCenter(String selector) {
        locator(selector).scrollIntoViewIfNeeded();
    }

    public void scrollTo(String selector, int x, int y) {
        locator(selector).evaluate("el => el.scrollTo(" + x + "," + y + ")");
    }

    public void scrollBy(String selector, int x, int y) {
        locator(selector).evaluate("el => el.scrollBy(" + x + "," + y + ")");
    }

    public void scrollToTopOf(String selector) {
        locator(selector).evaluate("el => el.scrollTop = 0");
    }

    public void scrollToBottomOf(String selector) {
        locator(selector).evaluate("el => el.scrollTop = el.scrollHeight");
    }

    public Object executeJavaScript(String script, Object... args) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null) ? frame.evaluate(script, args) : page.evaluate(script, args);
    }

    public String innerHTML(String selector) {
        Object result = locator(selector).evaluate("el => el.innerHTML");
        return normalizeText(result != null ? result.toString() : "");
    }

    public String textContent(String selector) {
        Object result = locator(selector).evaluate("el => el.textContent");
        return normalizeText(result != null ? result.toString() : "");
    }

    public boolean getPageSourceContains(String text) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        String content = (frame != null) ? frame.content() : page.content();
        return normalizeText(content).contains(normalizeText(text));
    }

    /**
     * 获取当前页面的完整 HTML 源码（自动适配 iframe 上下文）
     */
    public String getPageSource() {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null) ? frame.content() : page.content();
    }

    /**
     * 获取当前打开的页面数量
     * @return 当前浏览器上下文中打开的页面数
     */
    public int getPageSize() {
        ensureContextValid();
        return context.pages().size();
    }

    public void tap(String selector) {
        locator(selector).tap();
    }

    public BoundingBox getElementBoundingBox(String selector) {
        return locator(selector).boundingBox();
    }

    public boolean isClosed() {
        return page != null && page.isClosed();
    }

    public void bringToFront() {
        ensurePageValid();
        page.bringToFront();
    }

    public void setContent(String html) {
        ensurePageValid();
        page.setContent(html);
        // 替换页面内容后，所有 iframe 均被销毁，必须重置 iframe 上下文
        resetFrameContextAfterNavigation();
    }

    public void setViewportSize(int width, int height) {
        ensurePageValid();
        page.setViewportSize(width, height);
    }

    public void setInputFiles(String selector, String... paths) {
        if (paths == null || paths.length == 0) {
            throw new IllegalArgumentException("Paths cannot be null or empty");
        }
        for (String path : paths) {
            if (path == null) {
                throw new IllegalArgumentException("Individual path cannot be null");
            }
        }
        Path[] pathArray = Arrays.stream(paths).map(Paths::get).toArray(Path[]::new);
        locator(selector).setInputFiles(pathArray);
    }

    public Locator byAltText(String altText) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null) ? frame.getByAltText(altText) : page.getByAltText(altText);
    }

    public Locator byRole(AriaRole role) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null) ? frame.getByRole(role) : page.getByRole(role);
    }

    public void dragAndDrop(String sourceSelector, String targetSelector) {
        locator(sourceSelector).dragTo(locator(targetSelector));
    }

    public void focus(String selector) {
        locator(selector).focus();
    }

    public void hover(String selector) {
        locator(selector).hover();
    }

    public Locator byTitle(String title) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null) ? frame.getByTitle(title) : page.getByTitle(title);
    }

    public Locator byTestId(String testId) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null) ? frame.getByTestId(testId) : page.getByTestId(testId);
    }

    public void keyDown(String selector, String key) {
        locator(selector).focus();
        page.keyboard().down(key);
    }

    public void keyUp(String selector, String key) {
        locator(selector).focus();
        page.keyboard().up(key);
    }

    public void press(String selector, String key) {
        locator(selector).press(key);
    }

    public void waitForTimeout(int milliseconds) {
        ensurePageValid();
        page.waitForTimeout(milliseconds);
    }

    public void acceptAlert() {
        ensurePageValid();
        page.onceDialog(Dialog::accept);
    }

    public void dismissAlert() {
        ensurePageValid();
        page.onceDialog(Dialog::dismiss);
    }

    public byte[] takeScreenshot() {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null)
                ? frame.frameElement().screenshot()
                : page.screenshot();
    }

    public byte[] takeElementScreenshot(String selector) {
        return locator(selector).screenshot();
    }

    /**
     * 判断当前是否为可调试本地环境
     * @return true=本地允许pause  false=Jenkins/BrowserStack禁止暂停
     */
    protected boolean isDebugEnvironment() {
        // 1. 识别 BrowserStack 云端环境
        boolean isBsEnv = System.getenv().containsKey("BROWSERSTACK_USERNAME")
                || System.getenv().containsKey("BROWSERSTACK_ACCESS_KEY");

        // 2. 识别 Jenkins CI 环境
        boolean isJenkinsEnv = System.getenv().containsKey("JENKINS_HOME")
                || System.getProperty("ci", "false").equalsIgnoreCase("true");

        // 云端/CI 直接判定为非调试环境
        return !isBsEnv && !isJenkinsEnv;
    }

    /**
     * 安全暂停方法
     * 本地IDE：正常pause调试
     * Jenkins / BrowserStack：自动跳过，杜绝流程阻塞
     */
    public void pause() {
        if (isDebugEnvironment()) {
            try {
                getPage().pause();
            } catch (Exception e) {
                logger.warn("Page pause failed, skip debug pause", e);
            }
        } else {
            logger.warn("[Security Control] Jenkins/BrowserStack environment, auto skip pause() to avoid block");
        }
    }
}