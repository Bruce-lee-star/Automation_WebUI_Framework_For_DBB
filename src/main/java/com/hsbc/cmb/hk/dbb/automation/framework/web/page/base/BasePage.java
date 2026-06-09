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
        new PageElement(selector, this).waitForExists(timeout);
    }

    public void waitForElementNotExists(String selector, int timeout) {
        new PageElement(selector, this).waitForNotExists(timeout);
    }

    public void waitForElementEditable(String selector, int timeout) {
        new PageElement(selector, this).waitForEditable(timeout);
    }

    public void waitForElementEnabled(String selector, int timeout) {
        new PageElement(selector, this).waitForEnabled(timeout);
    }

    public void waitForElementDisabled(String selector, int timeout) {
        new PageElement(selector, this).waitForDisabled(timeout);
    }

    public void waitForElementChecked(String selector, int timeout) {
        new PageElement(selector, this).waitForChecked(timeout);
    }

    public void waitForElementNotChecked(String selector, int timeout) {
        new PageElement(selector, this).waitForNotChecked(timeout);
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

    public void waitForVisibleWithRetry(String selector, int timeout, int retries) {
        retry(() -> new PageElement(selector, this).waitForVisible(timeout), retries, 500, "wait visible with retry: " + selector);
    }

    public void waitForHiddenWithRetry(String selector, int timeout, int retries) {
        retry(() -> new PageElement(selector, this).waitForNotVisible(timeout), retries, 500, "wait hidden with retry: " + selector);
    }

    public void clickWithRetry(String selector, int retries) {
        retry(() -> click(selector), retries, 500, "click with retry: " + selector);
    }

    public void typeWithRetry(String selector, String text, int retries) {
        retry(() -> type(selector, text), retries, 500, "type with retry: " + selector);
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

    public void click(String selector) {
        try {
            // 委托给 PageElement 以利用企业级重试机制
            new PageElement(selector, this).click();
        } catch (ElementOperationException e) {
            // 已经是 ElementOperationException，直接重新抛出
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
        // 委托给 PageElement 以利用企业级重试机制
        new PageElement(selector, this).type(text);
    }

    public void append(String selector, String text) {
        // 委托给 PageElement 以利用企业级重试机制
        PageElement pe = new PageElement(selector, this);
        pe.focus();
        String current = pe.getValue();
        if (current == null) current = "";
        pe.fill(current + text);
    }

    public void clear(String selector) {
        // 委托给 PageElement 以利用企业级重试机制
        new PageElement(selector, this).clear();
    }

    // ===================== 读取文本 全部归一化 =====================
    public String getText(String selector) {
        return new PageElement(selector, this).getText();
    }

    public String getInputValue(String selector) {
        return new PageElement(selector, this).getValue();
    }

    public String getAttribute(String selector, String attr) {
        return new PageElement(selector, this).getAttribute(attr);
    }

    public String getAttributeValue(String selector, String attr, String defaultValue) {
        String val = getAttribute(selector, attr);
        return val == null ? defaultValue : normalizeText(val);
    }

    public void selectOption(String selector, int index) {
        new PageElement(selector, this).selectByIndex(index);
    }

    public void selectByVisibleText(String selector, String text) {
        new PageElement(selector, this).selectByVisibleText(text);
    }

    public void check(String selector) {
        new PageElement(selector, this).check();
    }

    public void uncheck(String selector) {
        new PageElement(selector, this).uncheck();
    }

    public boolean isChecked(String selector) {
        return new PageElement(selector, this).isChecked();
    }

    public boolean isEnabled(String selector) {
        return new PageElement(selector, this).isEnabled();
    }

    public boolean isDisabled(String selector) {
        return new PageElement(selector, this).isDisabled();
    }

    public boolean isVisible(String selector) {
        return new PageElement(selector, this).isVisible();
    }

    public boolean isHidden(String selector) {
        return new PageElement(selector, this).isNotVisible();
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

    public void switchToPage(int index) {
        ensureContextValid();
        List<Page> pages = context.pages();
        if (index < 0 || index >= pages.size()) throw new IndexOutOfBoundsException("Invalid page index");
        page = pages.get(index);
        PlaywrightManager.setPage(page); // 同步到 PlaywrightManager，使其他 Page 实例可感知
        currentFrame.remove(); // 切换页面后重置 iframe 上下文
        setCurrentPage();
        initializeAnnotatedFields(); // 页面切换后重新绑定元素
    }

    public void switchToLatestPage() {
        ensureContextValid();
        List<Page> pages = context.pages();
        page = pages.getLast();
        PlaywrightManager.setPage(page); // 同步到 PlaywrightManager，使其他 Page 实例可感知
        currentFrame.remove(); // 切换页面后重置 iframe 上下文
        setCurrentPage();
        initializeAnnotatedFields(); // 页面切换后重新绑定元素
    }

    public Page waitForNewPage(int timeout) {
        ensureContextValid();
        int before = context.pages().size();
        if (!waitForCondition(() -> context.pages().size() > before, timeout, "new page opened")) {
            throw new TimeoutException("No new page");
        }
        List<Page> pages = context.pages();
        if (pages.isEmpty()) {
            throw new TimeoutException("No pages available");
        }
        return pages.getLast();
    }

    /**
     * 等待新页面打开并自动切换 — 组合 waitForNewPage + switchToLatestPage。
     *
     * <p>典型场景：点击一个 target="_blank" 链接，期望新 tab 打开。
     * 本方法等待新 Page 出现后自动切换当前页面指针到新 Page，
     * 并重新绑定所有 @Element 注解字段，确保后续操作在新页面上执行。
     *
     * <p>内置 ensureContextValid()，确保 context 不会为 null。
     *
     * <pre>{@code
     * myPage.switchNewPage(10);
     * // 此后所有元素操作都指向新打开的 page
     * }</pre>
     *
     * @param timeoutSecs 等待新页面打开的超时秒数
     * @return 新打开的 Page 实例
     * @throws TimeoutException 如果在超时时间内未检测到新页面
     */
    public Page switchNewPage(int timeoutSecs) {
        ensureContextValid();
        Page newPage = waitForNewPage(timeoutSecs);
        page = newPage;
        PlaywrightManager.setPage(page); // 同步到 PlaywrightManager，使其他 Page 实例可感知
        page.bringToFront();
        currentFrame.remove(); // 切换页面后重置 iframe 上下文
        setCurrentPage();
        initializeAnnotatedFields();
        logger.info("Switch to new page: url={}, title={}", page.url(), page.title());
        return newPage;
    }

    public void closeCurrentPageAndSwitchBack() {
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
            // 确保当前 page 引用指向唯一可用页面
            Page onlyPage = pages.get(0);
            if (page != onlyPage) {
                page = onlyPage;
                currentFrame.remove(); // 切换页面后重置 iframe 上下文
                setCurrentPage();
                initializeAnnotatedFields();
            }
            return;
        }

        int currentIndex = pages.indexOf(page);
        // 安全关闭：跳过已关闭的 page，避免对已关闭页面调用 close() 抛异常
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
        page = updatedPages.get(targetIndex);
        currentFrame.remove(); // 切换页面后重置 iframe 上下文
        setCurrentPage();
        initializeAnnotatedFields(); // 页面切换后重新绑定元素
    }

    /**
     * 关闭所有多余页面，保留并切换回主页面（第一个页面）
     * 用于 Feature 模式下 case 切换时清理上一个 case 遗留的弹窗/新tab
     * 
     * 策略:
     * - 总是保留 pages.get(0) 作为主页面
     * - 关闭其他所有页面（包括当前 page 指向的非主页）
     * - 最终将 page 指针设回主页
     * - 即使当前就在主页也安全（不会关闭自己）
     */
    public void resetToMainPage() {
        ensureContextValid();
        List<Page> pages = context.pages();
        
        if (pages.isEmpty()) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "No pages available in context, nothing to reset");
            page = null;
            return;
        }
        
        Page mainPage = pages.get(0);
        
        if (pages.size() == 1) {
            // 只有一个页面，直接切换过去即可（可能之前 page 指针漂移了）
            if (page != mainPage) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "Single page available, switching page reference to main page");
                page = mainPage;
                currentFrame.remove(); // 切换页面后重置 iframe 上下文
                setCurrentPage();
                initializeAnnotatedFields();
            } else {
                LoggingConfigUtil.logDebugIfVerbose(logger, "Already on the only page (main page)");
            }
            return;
        }
        
        // 多个页面：关闭除主页面外的所有页面
        LoggingConfigUtil.logInfoIfVerbose(logger, 
            "Resetting to main page: closing {} extra page(s) (total pages: {})", pages.size() - 1, pages.size());
        
        for (int i = pages.size() - 1; i >= 1; i--) {
            Page extraPage = pages.get(i);
            try {
                if (!extraPage.isClosed()) {
                    extraPage.close();
                    LoggingConfigUtil.logDebugIfVerbose(logger, "Closed extra page at index {}", i);
                }
            } catch (Exception e) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to close page at index {}: {}", i, e.getMessage());
            }
        }
        
        // 确保最终指向主页
        page = mainPage;
        currentFrame.remove(); // 切换页面后重置 iframe 上下文
        setCurrentPage();
        initializeAnnotatedFields();
        LoggingConfigUtil.logInfoIfVerbose(logger, "Successfully reset to main page");
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

    // ===================== iframe 切换 =====================

    /**
     * 按 name / id 属性切换到 iframe，并自动将当前页面上下文指向 iframe 内部。
     * <p>此后所有通过 {@code myPage.click(selector)}、{@code myPage.type(selector)}
     * 以及 {@code @Element} 注解字段的操作都会自动在 iframe 内查找元素，
     * 不再出现 "not found in DOM" 的问题。
     *
     * <pre>{@code
     * FrameLocator frame = myPage.switchToFrame("myFrame");
     * // 两种用法均可 —— 效果相同：
     * frame.locator("#inputField").fill("hello");          // 直接用 FrameLocator
     * myPage.type("#inputField", "hello");                 // 通过 BasePage 自动适配
     * }</pre>
     *
     * @param frameName iframe 的 name 或 id
     * @return Playwright FrameLocator（仍可链式调用）
     */
    public FrameLocator switchToFrame(String frameName) {
        ensurePageValid();
        // 优先用 Playwright 原生 frame(name) 获取 Frame
        Frame frame = page.frame(frameName);
        if (frame == null) {
            throw new RuntimeException("Frame not found by name/id: '" + frameName
                + "'. Available frames: " + page.frames().size());
        }
        currentFrame.set(frame);
        // 刷新所有 @Element 注解字段的 Locator 缓存，使其指向 iframe 内的 DOM
        initializeAnnotatedFields();
        logger.info("Switched to iframe: name='{}'", frameName);
        // CSS 选择器需对单引号做转义，防止选择器注入
        String escaped = frameName.replace("\\", "\\\\").replace("'", "\\'");
        return page.frameLocator("iframe[name='" + escaped + "'], iframe[id='" + escaped + "']");
    }

    /**
     * 按 CSS 选择器切换到 iframe，并自动将当前页面上下文指向 iframe 内部。
     *
     * <pre>{@code
     * FrameLocator frame = myPage.switchToFrameBySelector("iframe.embedded-view");
     * myPage.click(".submit-btn");  // 自动在 iframe 内查找
     * }</pre>
     *
     * @param iframeSelector iframe 元素的 CSS 选择器
     * @return Playwright FrameLocator
     */
    public FrameLocator switchToFrameBySelector(String iframeSelector) {
        ensurePageValid();
        try {
            com.microsoft.playwright.ElementHandle iframeEl = page.locator(iframeSelector).elementHandle();
            Frame cf = iframeEl.contentFrame();
            if (cf == null) {
                throw new RuntimeException("Cannot get content frame from selector: " + iframeSelector);
            }
            currentFrame.set(cf);
        } catch (Exception e) {
            logger.error("Failed to switch to iframe by selector '{}': {}", iframeSelector, e.getMessage());
            throw new RuntimeException("Failed to switch to iframe by selector: " + iframeSelector, e);
        }
        // 刷新所有 @Element 注解字段的 Locator 缓存
        initializeAnnotatedFields();
        logger.info("Switched to iframe by selector: '{}'", iframeSelector);
        return page.frameLocator(iframeSelector);
    }

    /**
     * 按索引切换到 iframe，并自动将当前页面上下文指向 iframe 内部。
     *
     * @param index iframe 索引（从 0 开始，0 通常是主页面）
     * @return Playwright Frame
     */
    public Frame switchToFrameByIndex(int index) {
        ensurePageValid();
        List<Frame> frames = page.frames();
        if (index < 0 || index >= frames.size()) {
            throw new IndexOutOfBoundsException("Invalid frame index: " + index + " (total: " + frames.size() + ")");
        }
        Frame selectedFrame = frames.get(index);
        currentFrame.set(selectedFrame);
        // 刷新所有 @Element 注解字段的 Locator 缓存
        initializeAnnotatedFields();
        logger.info("Switched to iframe by index: {} (total: {})", index, frames.size());
        return selectedFrame;
    }

    /**
     * 切换回主页面 DOM（退出 iframe 上下文）。
     * <p>此后所有元素操作恢复在主页面 DOM 中查找。
     */
    public void switchToMainFrame() {
        if (currentFrame.get() != null) {
            currentFrame.remove();
            // 刷新所有 @Element 注解字段的 Locator 缓存，使其指向主页面 DOM
            initializeAnnotatedFields();
            logger.info("Switched back to main frame (top-level page)");
        }
    }

    /**
     * 获取当前 Page 中所有 Frame 列表。
     *
     * @return Frame 列表
     */
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