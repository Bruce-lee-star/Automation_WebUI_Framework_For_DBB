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
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.binding.RoleElementBinder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPageGenerator;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine;
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
import java.util.regex.Pattern;

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

    /**
     * 当前 open shadowRoot 上下文栈（自外向内，保存各层宿主的 CSS 选择器），使用 ThreadLocal 使所有 Page 实例共享。
     * <p>空栈表示当前在普通 DOM（主页面或已切到的 iframe）内操作。shadow 可嵌套，故用栈保存每一层宿主。
     * <p>定位时把栈内宿主用 Playwright 官方的 {@code >>>} shadow 穿透组合器拼接成前缀，例如
     * {@code #app-host >>> comp-menu#menu >>> #inner}，从而显式穿透 open shadowRoot（对标 page.pause() 的
     * shadow piercing 录制）。{@code >>>} 是选择器引擎内置语法，可一次性穿透任意层 open shadow，无需
     * 逐层调用 shadowRoot()（Playwright Java 的 ElementHandle 未暴露该便捷方法）。
     */
    private static final ThreadLocal<java.util.Deque<String>> currentShadow =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);

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

    /** 首次注解字段初始化标志——页面切换时复用已有对象而非重建 */
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
     * 后续调用（页面切换）：复用已有对象（避免重建对象和反射赋值的开销），
     * Locator 不再缓存，每次调用 locator() 自动绑定新 Page 实例。
     */
    private void initializeAnnotatedFields() {
        Class<?> clazz = this.getClass();
        while (clazz != null && clazz != BasePage.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(RoleElement.class)) {
                    RoleElement a = field.getAnnotation(RoleElement.class);
                    field.setAccessible(true);

                    if (annotatedFieldsInitialized) {
                        // 页面切换后——复用已有对象，Locator 由 locator() 动态绑定新 Page
                        try {
                            Object existing = field.get(this);
                            if (existing == null || !(existing instanceof PageElement)) {
                                new RoleElementBinder(this).bind(field, a);
                            }
                        } catch (IllegalAccessException e) {
                            new RoleElementBinder(this).bind(field, a);
                        }
                        continue;
                    }

                    new RoleElementBinder(this).bind(field, a);
                } else if (field.isAnnotationPresent(Element.class)) {
                    Element elementAnnotation = field.getAnnotation(Element.class);
                    String selector = elementAnnotation.value();
                    // 对齐 page.pause() 的 frameLocator 录制：iframe 内元素用 frame() 逐层下钻。
                    List<String> frameSegs = Arrays.asList(elementAnnotation.frame());
                    field.setAccessible(true);

                    if (annotatedFieldsInitialized) {
                        // 页面切换后——复用已有对象，Locator 由 locator() 动态绑定新 Page
                        try {
                            Object existing = field.get(this);
                            if (existing == null || !(existing instanceof PageElement || existing instanceof PageElementList)) {
                                createField(field, selector, frameSegs);
                            }
                        } catch (IllegalAccessException e) {
                            // get 失败，回退到重新创建
                            createField(field, selector, frameSegs);
                        }
                        continue;
                    }

                    createField(field, selector, frameSegs);
                }
            }
            clazz = clazz.getSuperclass();
        }
        annotatedFieldsInitialized = true;
    }

    /** 创建 PageElement 或 PageElementList 实例并赋值给字段 */
    private void createField(Field field, String selector) {
        createField(field, selector, null);
    }

    /** 创建 PageElement / PageElementList（含 iframe 嵌套路径 frameSegs，对齐 page.pause 的 frameLocator 录制） */
    private void createField(Field field, String selector, List<String> frameSegs) {
        try {
            if (List.class.isAssignableFrom(field.getType())) {
                field.set(this, new PageElementList(selector, this, frameSegs));
            } else {
                field.set(this, new PageElement(selector, this, frameSegs));
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

    /**
     * 一键清理当前线程所有静态 ThreadLocal —— 必须在 BrowserContext 关闭之后调用，
     * 否则线程池复用的下一条测试会读取到旧 Page 的 iframe/shadow 上下文。
     * <p>
     * 适用场景：{@code @AfterMethod} / TestNG {@code afterTestMethod} / 线程池 {@code afterExecute}。
     */
    public static void clearAllThreadLocals() {
        currentPage.remove();
        currentFrame.remove();
        currentShadow.remove();
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
                page.waitForTimeout((double) intervalMs);
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
            page.waitForTimeout((double) retryIntervalMs);
        }
        return false;
    }

    /**
     * 等待元素可见（已内置等待+超时机制，无需外层再包裹 retry）。
     *
     * @param selector 元素选择器
     * @param timeout  超时秒数
     */
    public void waitForVisible(String selector, int timeout) {
        element(selector).waitForVisible(timeout);
    }

    /**
     * 等待元素隐藏（已内置等待+超时机制，无需外层再包裹 retry）。
     *
     * @param selector 元素选择器
     * @param timeout  超时秒数
     */
    public void waitForHidden(String selector, int timeout) {
        element(selector).waitForNotVisible(timeout);
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
        // shadow 上下文高于 iframe/DOM 层：用 >>> 穿透组合器把宿主前缀拼到选择器前。
        java.util.Deque<String> shadowStack = currentShadow.get();
        if (shadowStack != null && !shadowStack.isEmpty()) {
            StringBuilder prefix = new StringBuilder();
            for (String host : shadowStack) {
                prefix.append(host).append(" >>> ");
            }
            selector = prefix.append(selector).toString();
        }
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
     * 必须将 currentFrame 置为 null 并刷新 @Element 注解字段（确保后续 locator() 绑定新 Page），
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
     * <p>同时自动将旧页面的路由规则重新注册到新页面上，确保 API 监控、Mock 等规则在跨页面场景下不丢失。
     */
    private void setPageReference(Page target) {
        Page oldPage = this.page;
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
     * <p>与 {@link #waitForNewPage(Runnable, int)} 不同，本方法跳过事件监听，直接使用调用方已捕获的 Page 引用。
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
     * myPage.waitForNewPage(() -> myPage.element("#link").click(), 15);
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
    public Page waitForNewPage(Runnable trigger, int timeoutSecs) {
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
     * myPage.waitForNewPage(15);  // 等待最多 15 秒
     * }</pre>
     *
     * @param timeoutSecs 等待超时秒数
     * @return 新打开的 Page 实例
     */
    public Page waitForNewPage(int timeoutSecs) {
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

    /**
     * 等待下载：在 {@code trigger} 触发的一次下载完成前阻塞，对齐 {@code page.pause()} 录制出的
     * {@code page.waitForDownload(() -> element.click())}。
     * <p>典型用于点击“下载”链接 / 按钮：anchor 带 {@code download} 属性、href 指向文件 URL，
     * 或 JS 触发的下载。框架已通过 {@code setAcceptDownloads(true)} 开启下载能力，
     * 下载文件自动保存到配置的下载目录。
     *
     * <pre>{@code
     * // 推荐：把触发操作传入，一步完成“点击 + 等待下载”
     * myPage.waitForDownload(() -> myPage.element("#downloadLink").click(), 15);
     *
     * // 与弹窗配合（嵌套：先弹窗再下载）
     * myPage.waitForDownload(() ->
     *         myPage.waitForNewPage(() -> myPage.element("#link").click(), 15), 15);
     * }</pre>
     *
     * @param trigger     触发下载的操作（如点击下载链接）
     * @param timeoutSecs 等待超时秒数
     */
    public void waitForDownload(Runnable trigger, int timeoutSecs) {
        ensurePageValid();
        try {
            page.waitForDownload(new Page.WaitForDownloadOptions().setTimeout((long) timeoutSecs * 1000),
                    () -> trigger.run());
        } catch (PlaywrightException e) {
            throw new TimeoutException("Waiting for download timed out after " + timeoutSecs + " seconds", e);
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
                // 标记本页为"框架主动关闭"：onClose 据此不再补登记 closeCurrentPage 步骤
                // （代码已显式调用 closeCurrentPage，重复登记会导致回放重复关闭）。
                RoleElementPicker.markFrameworkClose(page);
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
        int targetIndex = Math.max(0, Math.min(currentIndex, updatedPages.size()) - 1);
        setPageReference(updatedPages.get(targetIndex));
        onPageSwitched();
    }

    /**
     * 关闭除当前页面之外的所有其他页面，保持当前页面为 context 内唯一页面。
     * <p>对标 Selenium 中手动遍历关闭多余窗口的操作。
     * <p>若仅剩 1 个页面或 context 为空则不执行任何关闭操作。
     */
    public void closeOtherPages() {
        ensureContextValid();
        List<Page> pages = context.pages();
        if (pages.size() <= 1) {
            LoggingConfigUtil.logInfoIfVerbose(logger,
                    "closeOtherPages skipped: page count={}, nothing to close", pages.size());
            return;
        }

        for (Page p : pages) {
            if (p == page) continue;
            try {
                if (!p.isClosed()) {
                    // 标记为"框架主动关闭"，onClose 不再补登记 closeCurrentPage 步骤。
                    RoleElementPicker.markFrameworkClose(p);
                    p.close();
                }
            } catch (Exception e) {
                LoggingConfigUtil.logWarnIfVerbose(logger,
                        "Exception while closing other page: {}", e.getMessage());
            }
        }
        LoggingConfigUtil.logInfoIfVerbose(logger,
                "closeOtherPages done: closed {} other pages, current page retained",
                pages.size() - 1);
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
     * @return 切换后的 Playwright Frame（与 {@link #switchToFrame(int)} 返回类型一致）
     */
    public Frame switchToFrame(String nameOrSelector) {
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
        return frame;
    }

    // ===================== shadowRoot 切换（对齐 page.pause() 的 >>> shadow 穿透录制） =====================

    /**
     * 显式切换到指定 shadowRoot：把宿主选择器压入 shadow 上下文栈。
     * 对标 page.pause() 录制的 {@code host >>> #inner} shadow 穿透。
     * <p>之后通过 {@link #locator(String)} 创建的所有 Locator 都会以 {@code host >>>} 为前缀，
     * 由 Playwright 选择器引擎一次性穿透该层 open shadowRoot。shadow 可嵌套调用
     * （在 shadow 内再 switchToShadow 会叠加为 {@code host1 >>> host2 >>>}）。
     *
     * <pre>{@code
     * myPage.switchToShadow("#app-host");         // 进入 app 的 shadow
     * myPage.switchToShadow("comp-menu#menu");    // 再进入嵌套的 shadow
     * myPage.element("#innerBtn").click();         // 在 shadow 内操作（自动 >>> 穿透）
     * myPage.switchToDefaultShadow();             // 退出一层 shadow，回到外层
     * }</pre>
     *
     * <p>注意：Playwright Java 的 {@code ElementHandle} 未暴露 {@code shadowRoot()}，故此处不调用该 API，
     * 而是采用官方 {@code >>>} 穿透组合器实现，兼容任意层 open shadow。
     *
     * @param hostSelector 宿主元素的 CSS 选择器（在当前查找域内定位）
     */
    public void switchToShadow(String hostSelector) {
        if (hostSelector == null || hostSelector.isBlank()) {
            throw new RuntimeException("switchToShadow: hostSelector 不能为空");
        }
        currentShadow.get().push(hostSelector.trim());
        logger.info("Switched into shadowRoot of '{}' (depth={})", hostSelector, currentShadow.get().size());
    }

    /**
     * 退出当前最内层 shadow，回到上一层 shadow（或无 shadow 的 DOM）。
     * 对标 page.pause() 录制中离开 shadow 穿透后的查找域。
     * <p>若当前不在任何 shadow 内，则无操作（不报错）。
     *
     * @return 弹出的宿主选择器，或 null（当前本就不在 shadow 内）
     */
    public String switchToDefaultShadow() {
        java.util.Deque<String> stack = currentShadow.get();
        if (stack.isEmpty()) {
            return null;
        }
        String popped = stack.pop();
        logger.info("Exited one shadowRoot (depth now {})", stack.size());
        return popped;
    }

    /**
     * 退出【所有】嵌套 shadow，回到最外层 DOM（主页面或当前 iframe）。
     * 对应 iframe 的 {@link #switchToDefaultContent()}：switchToDefaultContent 回主文档，
     * switchToDefaultShadowAll 回 DOM 顶层（仍可处于某 iframe 内）。
     */
    public void switchToDefaultShadowAll() {
        currentShadow.get().clear();
        logger.info("Exited all shadowRoots");
    }

    /**
     * 【监听器范式】切换到指定的 iframe：在 {@code trigger} 触发动作执行期间，用
     * {@link Page#onFrameAttached(Consumer)} 事件监听收集 frame 挂载，一旦命中目标 iframe
     * 立即捕获并返回，不轮询、不 sleep。对标 {@code page.waitForPopup(() -> click())} 的范式——
     * 触发动作包裹进回调，frame 由事件驱动捕获。
     *
     * <p>适用：iframe 是【动态出现 / 异步加载】的场景（点击按钮后注入、SPA 动态插入、延迟 {@code src}）。
     * 对已存在（静态）的 iframe 直接用 {@link #switchToFrame(String)} 即可。
     *
     * <p>匹配优先级（与 {@link #switchToFrame(String)} 的语义保持兼容）：
     * <ol>
     *   <li>按 frame 的 {@code name} / {@code id} 精确匹配（step 生成默认用 {@code iframe[name="x"]}）；</li>
     *   <li>按 frame 的 {@code url} 包含给定片段兜底（便于用 url 片段定位）。</li>
     * </ol>
     *
     * @param trigger        触发 iframe 出现的动作（如点击打开 iframe 的按钮）；可为 null，此时等价于
     *                       仅做一次静态查找 + 等待一次 attach 事件
     * @param nameOrSelector iframe 的 name / id / url 片段
     * @param timeoutSecs    等待超时秒数（<=0 时使用框架默认导航超时）
     * @return 切换后的 Playwright Frame（已就绪）
     */
    public Frame switchToFrameAndWait(Runnable trigger, String nameOrSelector, int timeoutSecs) {
        ensurePageValid();
        int timeoutMs = (timeoutSecs > 0) ? timeoutSecs * 1000
                : (int) PlaywrightManager.config().getNavigationTimeout();
        // 若 iframe 已挂载（静态场景），直接切，无需走事件监听
        Frame existing = matchFrame(nameOrSelector);
        if (existing != null) {
            currentFrame.set(existing);
            initializeAnnotatedFields();
            logger.info("Switched to iframe (already attached): '{}'", nameOrSelector);
            return existing;
        }
        // 纯事件驱动：注册 onFrameAttached 监听，命中即放行（用 latch 等待，无轮询）
        final Frame[] matched = {null};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.function.Consumer<Frame> listener = f -> {
            if (matched[0] == null && frameMatches(f, nameOrSelector)) {
                matched[0] = f;
                latch.countDown();
            }
        };
        page.onFrameAttached(listener);
        try {
            if (trigger != null) trigger.run();   // 执行触发动作，期间监听捕获目标 frame
            boolean got = false;
            try { got = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            if (!got || matched[0] == null) {
                throw new RuntimeException("Timed out (" + (timeoutMs / 1000) + "s) waiting for iframe to attach: '"
                        + nameOrSelector + "'. Available frames: " + page.frames().size());
            }
        } finally {
            try { page.offFrameAttached(listener); } catch (Exception ignore) {}
        }
        currentFrame.set(matched[0]);
        initializeAnnotatedFields();
        logger.info("Switched to iframe (waited & ready via onFrameAttached): '{}'", nameOrSelector);
        return matched[0];
    }

    /**
     * {@link #switchToFrameAndWait(Runnable, String, int)} 的默认超时重载。
     */
    public Frame switchToFrameAndWait(Runnable trigger, String nameOrSelector) {
        return switchToFrameAndWait(trigger, nameOrSelector, 0);
    }

    /**
     * 无触发动作的兼容重载：仅做一次静态查找，未找到则等待一次 attach 事件（事件驱动，无轮询）。
     * 适用于"iframe 可能在监听注册前后才 attach"的非交互场景。
     */
    public Frame switchToFrameAndWait(String nameOrSelector, int timeoutSecs) {
        return switchToFrameAndWait((Runnable) null, nameOrSelector, timeoutSecs);
    }

    /**
     * {@link #switchToFrameAndWait(String, int)} 的默认超时重载。
     */
    public Frame switchToFrameAndWait(String nameOrSelector) {
        return switchToFrameAndWait((Runnable) null, nameOrSelector, 0);
    }

    /** 按 name/id 精确匹配或 url 片段兜底查找已存在的 frame。 */
    private Frame matchFrame(String nameOrSelector) {
        Frame f = page.frame(nameOrSelector);
        if (f != null) return f;
        for (Frame fr : page.frames()) {
            if (frameMatches(fr, nameOrSelector)) return fr;
        }
        return null;
    }

    /** frame 是否匹配给定的 name/id 或 url 片段。 */
    private boolean frameMatches(Frame f, String nameOrSelector) {
        if (f == null) return false;
        if (nameOrSelector.equals(f.name())) return true;          // name/id 精确匹配
        String url = f.url();
        return url != null && url.contains(nameOrSelector);          // url 片段兜底
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

    /**
     * 按可访问性角色 + 名称定位元素（名称精确匹配，大小写敏感）。
     * 经此定位可配合 {@code NLSUtils} 实现多语言 name 解析。
     *
     * @param role 可访问性角色，如 {@link AriaRole#TEXTBOX}、{@link AriaRole#BUTTON}
     * @param name 可访问名称（由当前语言决定，通常来自 {@code NLSUtils.get(...)}）
     * @return 对应的 Playwright Locator
     */
    public Locator byRole(AriaRole role, String name) {
        // 显式 setExact(true) 与本方法 javadoc 对齐：Playwright 的 name 匹配默认
        // exact=false（忽略大小写子串匹配，见官方 GetByRoleOptions javadoc），不显式设置会与文档描述相反。
        return byRole(role, name, true);
    }

    /**
     * 按可访问性角色 + 名称正则定位元素，等价于官方 {@code GetByRoleOptions.setName(Pattern)}。
     * 用于 NLS 模板值（含 {@code {{var}}} 占位符）编译出的正则；正则模式下 exact 被 Playwright 忽略。
     *
     * @param role        可访问性角色
     * @param namePattern 可访问名称正则（通常来自 {@code NLSUtils.templatePattern(...)}）
     * @return 对应的 Playwright Locator
     */
    public Locator byRole(AriaRole role, Pattern namePattern) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null)
                ? frame.getByRole(role, new Frame.GetByRoleOptions().setName(namePattern))
                : page.getByRole(role, new Page.GetByRoleOptions().setName(namePattern));
    }

    /**
     * 按可访问性角色 + 名称定位元素，可控制是否精确匹配。
     *
     * @param role   可访问性角色
     * @param name   可访问名称
     * @param exact  true=精确匹配（大小写敏感，默认行为）；false=子串/忽略大小写匹配
     * @return 对应的 Playwright Locator
     */
    public Locator byRole(AriaRole role, String name, boolean exact) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null)
                ? frame.getByRole(role, new Frame.GetByRoleOptions().setName(name).setExact(exact))
                : page.getByRole(role, new Page.GetByRoleOptions().setName(name).setExact(exact));
    }

    /**
     * 按可访问性角色 + 名称定位元素，可控制是否精确匹配，并可指定标题层级（仅 heading 角色生效）。
     * {@code level>0} 时等价于官方 {@code GetByRoleOptions.setLevel(level)}（对齐 {@code getByRole(HEADING).setLevel(n)}）；
     * {@code level<=0} 表示不限定层级。
     */
    public Locator byRole(AriaRole role, String name, boolean exact, int level) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        Page.GetByRoleOptions popts = new Page.GetByRoleOptions().setName(name).setExact(exact);
        Frame.GetByRoleOptions fopts = new Frame.GetByRoleOptions().setName(name).setExact(exact);
        if (level > 0) {
            popts = popts.setLevel(level);
            fopts = fopts.setLevel(level);
        }
        return (frame != null) ? frame.getByRole(role, fopts) : page.getByRole(role, popts);
    }

    /**
     * 按可访问性角色 + 名称正则定位元素，并可指定标题层级（仅 heading 角色生效）。
     * 用于 NLS 模板值（含 {@code {{var}}} 占位符）编译出的正则；{@code level>0} 时附加层级过滤。
     */
    public Locator byRole(AriaRole role, Pattern namePattern, int level) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        Page.GetByRoleOptions popts = new Page.GetByRoleOptions().setName(namePattern);
        Frame.GetByRoleOptions fopts = new Frame.GetByRoleOptions().setName(namePattern);
        if (level > 0) {
            popts = popts.setLevel(level);
            fopts = fopts.setLevel(level);
        }
        return (frame != null) ? frame.getByRole(role, fopts) : page.getByRole(role, popts);
    }

    /**
     * 按可访问性角色 + 名称定位元素，并可指定标题层级与可访问状态过滤（对齐 page.pause() 的 getByRole）。
     * 状态过滤为三态（{@code RoleElement.State}）：{@code ANY}=不调用 setXxx（匹配任意）；
     * {@code YES}=setXxx(true)（只匹配处于该状态）；{@code NO}=setXxx(false)（只匹配不处于该状态）。
     *
     * @param role     可访问性角色
     * @param name     可访问名称
     * @param exact    名称是否精确匹配（大小写敏感）
     * @param level    标题层级，<=0 表示不限
     * @param disabled 禁用状态过滤（{@link RoleElement.State}）
     * @param pressed  按下状态过滤（toggle button，{@link RoleElement.State}）
     * @param expanded 展开状态过滤（{@link RoleElement.State}）
     */
    public Locator byRole(AriaRole role, String name, boolean exact, int level,
                          RoleElement.State disabled, RoleElement.State pressed, RoleElement.State expanded) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        Page.GetByRoleOptions popts = new Page.GetByRoleOptions().setName(name).setExact(exact);
        Frame.GetByRoleOptions fopts = new Frame.GetByRoleOptions().setName(name).setExact(exact);
        if (level > 0) {
            popts = popts.setLevel(level);
            fopts = fopts.setLevel(level);
        }
        if (disabled != null && disabled != RoleElement.State.ANY) popts = popts.setDisabled(disabled == RoleElement.State.YES);
        if (pressed != null && pressed != RoleElement.State.ANY) popts = popts.setPressed(pressed == RoleElement.State.YES);
        if (expanded != null && expanded != RoleElement.State.ANY) popts = popts.setExpanded(expanded == RoleElement.State.YES);
        if (disabled != null && disabled != RoleElement.State.ANY) fopts = fopts.setDisabled(disabled == RoleElement.State.YES);
        if (pressed != null && pressed != RoleElement.State.ANY) fopts = fopts.setPressed(pressed == RoleElement.State.YES);
        if (expanded != null && expanded != RoleElement.State.ANY) fopts = fopts.setExpanded(expanded == RoleElement.State.YES);
        return (frame != null) ? frame.getByRole(role, fopts) : page.getByRole(role, popts);
    }

    /**
     * 按可访问性角色 + 名称正则定位元素，并可指定标题层级与可访问状态过滤（对齐 page.pause() 的 getByRole）。
     * 状态过滤语义同 {@link #byRole(AriaRole, String, boolean, int, RoleElement.State, RoleElement.State, RoleElement.State)}。
     * 用于 NLS 模板值（含 {@code {{var}}}）编译出的正则；此模式下 exact 被 Playwright 忽略。
     */
    public Locator byRole(AriaRole role, Pattern namePattern, int level,
                          RoleElement.State disabled, RoleElement.State pressed, RoleElement.State expanded) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        Page.GetByRoleOptions popts = new Page.GetByRoleOptions().setName(namePattern);
        Frame.GetByRoleOptions fopts = new Frame.GetByRoleOptions().setName(namePattern);
        if (level > 0) {
            popts = popts.setLevel(level);
            fopts = fopts.setLevel(level);
        }
        if (disabled != null && disabled != RoleElement.State.ANY) popts = popts.setDisabled(disabled == RoleElement.State.YES);
        if (pressed != null && pressed != RoleElement.State.ANY) popts = popts.setPressed(pressed == RoleElement.State.YES);
        if (expanded != null && expanded != RoleElement.State.ANY) popts = popts.setExpanded(expanded == RoleElement.State.YES);
        if (disabled != null && disabled != RoleElement.State.ANY) fopts = fopts.setDisabled(disabled == RoleElement.State.YES);
        if (pressed != null && pressed != RoleElement.State.ANY) fopts = fopts.setPressed(pressed == RoleElement.State.YES);
        if (expanded != null && expanded != RoleElement.State.ANY) fopts = fopts.setExpanded(expanded == RoleElement.State.YES);
        return (frame != null) ? frame.getByRole(role, fopts) : page.getByRole(role, popts);
    }

    /**
     * 打印当前页面中可交互元素的 {@code role = name}，
     * 便于据此编写 {@code @RoleElement(role = ..., key = ...)} 注解。
     * 用法：临时在测试里调用 {@code loginPage.dumpAccessibilityRoles();}，
     * 查看控制台输出后，把每行 {@code role = name} 抄进注解即可。
     * <p>注意：基于注入脚本遍历 DOM 的 computedRole/computedName（兼容无 Playwright
     * accessibilitySnapshot API 的版本），仅覆盖主 frame；iframe 内元素请对对应 frame 调用。
     */
    public void dumpAccessibilityRoles() {
        ensurePageValid();
        List<RoleEntry> entries = RoleElementPageGenerator.collectFromPage(page);
        if (entries.isEmpty()) {
            logger.warn("[a11y] 未采集到可交互元素（页面可能尚未就绪或无匹配角色）");
            return;
        }
        StringBuilder sb = new StringBuilder(
                "\n========== Accessibility roles (role = name) ==========\n");
        for (RoleEntry e : entries) {
            sb.append(e.getRole()).append(" = ")
              .append(e.getName() == null ? "" : e.getName()).append('\n');
        }
        logger.info(sb.toString());
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
        // P1 兼容性：Playwright 的 getByTestId 仅认 data-testid，但拾取器会识别
        // data-testid/data-test-id/data-test/data-qa 四种常见测试属性（对齐 page.pause 的 testId 族）。
        // 为让生成的 @RoleElement(testId=...) 对任意一种属性都能定位，构造覆盖全部四种属性的
        // CSS 选择器（同一值只会出现在其中一种属性上，不会误匹配多个）。
        String sel = "[data-testid=\"" + testId + "\"],[data-test-id=\"" + testId
                + "\"],[data-test=\"" + testId + "\"],[data-qa=\"" + testId + "\"]";
        return (frame != null) ? frame.locator(sel) : page.locator(sel);
    }

    /**
     * 按可见文本定位元素（精确匹配，大小写敏感），等价于 {@code page.getByText(text, {exact:true})}。
     */
    public Locator byText(String text) {
        return byText(text, true);
    }

    /**
     * 按可见文本定位元素，可控制是否精确匹配。
     *
     * @param text  可见文本
     * @param exact true=精确匹配（大小写敏感）；false=子串/忽略大小写匹配
     * @return 对应的 Playwright Locator
     */
    public Locator byText(String text, boolean exact) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null)
                ? frame.getByText(text, new Frame.GetByTextOptions().setExact(exact))
                : page.getByText(text, new Page.GetByTextOptions().setExact(exact));
    }

    /**
     * 按 alt 文本定位元素（精确匹配），等价于 {@code page.getByAltText(text, {exact:true})}。
     */
    public Locator byAltText(String altText, boolean exact) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null)
                ? frame.getByAltText(altText, new Frame.GetByAltTextOptions().setExact(exact))
                : page.getByAltText(altText, new Page.GetByAltTextOptions().setExact(exact));
    }

    /**
     * 按 title 属性定位元素（精确匹配），等价于 {@code page.getByTitle(title, {exact:true})}。
     */
    public Locator byTitle(String title, boolean exact) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null)
                ? frame.getByTitle(title, new Frame.GetByTitleOptions().setExact(exact))
                : page.getByTitle(title, new Page.GetByTitleOptions().setExact(exact));
    }

    /**
     * 按 placeholder 定位表单控件（精确匹配，大小写敏感），等价于 {@code page.getByPlaceholder(text, {exact:true})}。
     */
    public Locator byPlaceholder(String placeholder) {
        return byPlaceholder(placeholder, true);
    }

    /**
     * 按 placeholder 定位表单控件，可控制是否精确匹配。
     *
     * @param placeholder 占位文本
     * @param exact       true=精确匹配（大小写敏感）；false=子串/忽略大小写匹配
     * @return 对应的 Playwright Locator
     */
    public Locator byPlaceholder(String placeholder, boolean exact) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null)
                ? frame.getByPlaceholder(placeholder, new Frame.GetByPlaceholderOptions().setExact(exact))
                : page.getByPlaceholder(placeholder, new Page.GetByPlaceholderOptions().setExact(exact));
    }

    /**
     * 按关联 label 文本定位对应的表单控件（input/select/textarea 等），
     * 等价于 Playwright 的 {@code page.getByLabel(text)}，与 page.pause() 生成的定位器对齐。
     * <p>通过 &lt;label&gt; 可见文本 / {@code aria-labelledby} / {@code aria-label} 反查到控件；
     * 与 {@link #byRole(AriaRole, String)}（按可访问名）是两条独立策略，但都定位到同一 input 控件。
     * 需要单独定位 &lt;label&gt; 文本本身时，用 {@link #byText(String)}。
     *
     * @param label 关联 label 的可见文本
     * @return 对应的 Playwright Locator（定位控件）
     */
    public Locator byLabel(String label) {
        return byLabel(label, true);
    }

    /**
     * 按关联 label 文本定位表单控件，可控制是否精确匹配，等价于 {@code page.getByLabel(text, {exact})}。
     *
     * @param label 关联 label 的可见文本
     * @param exact true=精确匹配（大小写敏感）；false=子串 / 忽略大小写匹配
     * @return 对应的 Playwright Locator（定位控件）
     */
    public Locator byLabel(String label, boolean exact) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null)
                ? frame.getByLabel(label, new Frame.GetByLabelOptions().setExact(exact))
                : page.getByLabel(label, new Page.GetByLabelOptions().setExact(exact));
    }

    // ===== 模板变量（{{var}}）正则定位重载 =====
    // 当 nls 值含模板变量时，用正则 Pattern 匹配“被页面注入真实值后的可见文本”。

    public Locator byText(Pattern text) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null) ? frame.getByText(text) : page.getByText(text);
    }

    public Locator byAltText(Pattern altText) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null) ? frame.getByAltText(altText) : page.getByAltText(altText);
    }

    public Locator byTitle(Pattern title) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null) ? frame.getByTitle(title) : page.getByTitle(title);
    }

    public Locator byPlaceholder(Pattern placeholder) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null) ? frame.getByPlaceholder(placeholder) : page.getByPlaceholder(placeholder);
    }

    public Locator byLabel(Pattern label) {
        ensurePageValid();
        Frame frame = currentFrame.get();
        return (frame != null) ? frame.getByLabel(label) : page.getByLabel(label);
    }

    /**
     * 按 nls 解析后的文本值定位：若值含模板变量（{{var}}），用正则 {@link Pattern} 匹配注入真实值后的可见文本；
     * 否则走普通字面定位。供 {@code @RoleElement(key=...)} 的语义 / key-only 分支统一调用。
     */


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
        page.waitForTimeout((double) milliseconds);
    }

    public void acceptAlert() {
        ensurePageValid();
        page.onceDialog(Dialog::accept);
    }

    public void dismissAlert() {
        ensurePageValid();
        page.onceDialog(Dialog::dismiss);
    }

    /**
     * 注册“接受弹窗”处理器后，立即执行触发动作并等待其完成。
     * 避免 {@link #acceptAlert()} 仅挂载监听、却忘记执行触发操作的常见漏用。
     *
     * <pre>{@code
     * page.acceptAlert(() -> page.click("button#delete"));
     * }</pre>
     *
     * @param trigger 会触发 dialog 的动作（如点击某个按钮）
     */
    public void acceptAlert(Runnable trigger) {
        ensurePageValid();
        page.onceDialog(Dialog::accept);
        if (trigger != null) {
            trigger.run();
        }
    }

    /**
     * 注册“拒绝弹窗”处理器后，立即执行触发动作并等待其完成。
     *
     * @param trigger 会触发 dialog 的动作（如点击某个按钮）
     */
    public void dismissAlert(Runnable trigger) {
        ensurePageValid();
        page.onceDialog(Dialog::dismiss);
        if (trigger != null) {
            trigger.run();
        }
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

    // ===================== Cookie 操作 =====================

    /**
     * 获取当前 BrowserContext 中所有 Cookie
     * @return Cookie 列表
     */
    public List<Cookie> getCookies() {
        ensureContextValid();
        return context.cookies();
    }

    /**
     * 获取指定 URL 相关的 Cookie
     * @param url 目标 URL
     * @return Cookie 列表
     */
    public List<Cookie> getCookies(String url) {
        ensureContextValid();
        return context.cookies(url);
    }

    /**
     * 获取多个 URL 相关的 Cookie
     * @param urls 目标 URL 列表
     * @return Cookie 列表
     */
    public List<Cookie> getCookies(List<String> urls) {
        ensureContextValid();
        return context.cookies(urls);
    }

    /**
     * 根据名称获取指定 Cookie
     * @param name Cookie 名称
     * @return Cookie 对象，不存在时返回 null
     */
    public Cookie getCookie(String name) {
        ensureContextValid();
        return context.cookies().stream()
                .filter(c -> c.name.equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * 检查指定名称的 Cookie 是否存在
     * @param name Cookie 名称
     * @return 存在返回 true
     */
    public boolean hasCookie(String name) {
        return getCookie(name) != null;
    }

    /**
     * 添加单个 Cookie
     * @param cookie Playwright Cookie 对象
     */
    public void addCookie(Cookie cookie) {
        ensureContextValid();
        context.addCookies(List.of(cookie));
    }

    /**
     * 批量添加 Cookie
     * @param cookies Cookie 列表
     */
    public void addCookies(List<Cookie> cookies) {
        ensureContextValid();
        context.addCookies(cookies);
    }

    /**
     * 根据名称删除指定 Cookie
     * @param name Cookie 名称
     */
    public void deleteCookie(String name) {
        ensureContextValid();
        context.clearCookies(new BrowserContext.ClearCookiesOptions().setName(name));
    }

    /**
     * 清除当前 BrowserContext 中的所有 Cookie
     */
    public void clearCookies() {
        ensureContextValid();
        context.clearCookies();
    }

    /**
     * 获取当前页面 URL 关联的所有 Cookie
     * @return Cookie 列表
     */
    public List<Cookie> getCookiesForCurrentPage() {
        ensurePageValid();
        ensureContextValid();
        return context.cookies(page.url());
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