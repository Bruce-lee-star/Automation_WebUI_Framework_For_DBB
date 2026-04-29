package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkCore;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementNotClickableException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.TimeoutException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.Element;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElementList;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public abstract class BasePage {
    protected static final Logger logger = LoggerFactory.getLogger(BasePage.class);

    protected Page page;
    protected BrowserContext context;
    private static final ThreadLocal<BasePage> currentPage = new ThreadLocal<>();

    // ===================== 全局文本统一格式化工具 =====================
    protected String normalizeText(String raw) {
        if (raw == null) {
            return "";
        }
        // 1. 替换&nbsp不间断空格  \u00A0
        // 2. 合并所有空白(空格/换行/制表)为单个空格
        // 3. 首尾去空
        return raw.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    public BasePage() {
        if (!FrameworkCore.getInstance().isInitialized()) {
            FrameworkCore.getInstance().initialize();
        }
        initializeAnnotatedFields();
    }

    private void ensurePageValid() {
        if (page == null || page.isClosed()) {
            page = PlaywrightManager.getPage();
            setCurrentPage();
        }
    }

    private void ensureContextValid() {
        if (context == null) {
            context = PlaywrightManager.getContext();
        }
    }

    private void initializeAnnotatedFields() {
        for (Field field : this.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(Element.class)) {
                Element elementAnnotation = field.getAnnotation(Element.class);
                String selector = elementAnnotation.value();
                try {
                    field.setAccessible(true);
                    if (List.class.isAssignableFrom(field.getType())) {
                        field.set(this, new PageElementList(selector, this));
                    } else {
                        field.set(this, new PageElement(selector, this));
                    }
                } catch (Exception e) {
                    throw new ElementException("Init field failed: " + field.getName());
                }
            }
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

    private boolean waitForCondition
            (BooleanSupplier condition, int timeoutSeconds, String desc) {
        ensurePageValid();
        long end = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        while (System.currentTimeMillis() < end) {
            try {
                if (condition.getAsBoolean()) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "✅ Condition passed: {}", desc);
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

    public void waitForElementVisibleWithinTime(String selector, int timeout) {
        if (!waitForCondition(() -> locator(selector).isVisible(), timeout, "visible: " + selector)) {
            throw new TimeoutException("Element not visible: " + selector);
        }
    }

    public void waitForElementHiddenWithinTime(String selector, int timeout) {
        if (!waitForCondition(() -> locator(selector).isHidden(), timeout, "hidden: " + selector)) {
            throw new TimeoutException("Element not hidden: " + selector);
        }
    }

    public void waitForElementExists(String selector, int timeout) {
        if (!waitForCondition(() -> locator(selector).count() > 0, timeout, "exists: " + selector)) {
            throw new TimeoutException("Element not exists: " + selector);
        }
    }

    public void waitForElementNotExists(String selector, int timeout) {
        if (!waitForCondition(() -> locator(selector).count() == 0, timeout, "not exists: " + selector)) {
            throw new TimeoutException("Element still exists: " + selector);
        }
    }

    public void waitForElementEditable(String selector, int timeout) {
        waitForElementEnabled(selector, timeout);
    }

    public void waitForElementEnabled(String selector, int timeout) {
        if (!waitForCondition(() -> locator(selector).isEnabled(), timeout, "enabled: " + selector)) {
            throw new TimeoutException("Element not enabled: " + selector);
        }
    }

    public void waitForElementDisabled(String selector, int timeout) {
        if (!waitForCondition(() -> locator(selector).isDisabled(), timeout, "disabled: " + selector)) {
            throw new TimeoutException("Element not disabled: " + selector);
        }
    }

    public void waitForElementChecked(String selector, int timeout) {
        if (!waitForCondition(() -> locator(selector).isChecked(), timeout, "checked: " + selector)) {
            throw new TimeoutException("Element not checked: " + selector);
        }
    }

    public void waitForElementNotChecked(String selector, int timeout) {
        if (!waitForCondition(() -> !locator(selector).isChecked(), timeout, "not checked: " + selector)) {
            throw new TimeoutException("Element still checked: " + selector);
        }
    }

    public void waitForElementAttributeEquals(String selector, String attr, String value, int timeout) {
        String desc = "attr " + attr + " equals " + value + " for " + selector;
        if (!waitForCondition(() -> {
            String actual = locator(selector).getAttribute(attr);
            return Objects.equals(normalizeText(value), normalizeText(actual));
        }, timeout, desc)) {
            throw new TimeoutException(desc);
        }
    }

    public void waitForElementAttributeContains(String selector, String attr, String value, int timeout) {
        String desc = "attr " + attr + " contains " + value + " for " + selector;
        if (!waitForCondition(() -> {
            String actual = locator(selector).getAttribute(attr);
            return normalizeText(actual).contains(normalizeText(value));
        }, timeout, desc)) {
            throw new TimeoutException(desc);
        }
    }

    // ===================== 已修复：全量文本归一化 =====================
    public void waitForElementTextEquals(String selector, String text, int timeout) {
        String desc = "text equals " + text + " for " + selector;
        if (!waitForCondition(() -> {
            String actualRaw = locator(selector).innerText();
            String actual = normalizeText(actualRaw);
            String expect = normalizeText(text);
            return expect.equals(actual);
        }, timeout, desc)) {
            throw new TimeoutException(desc);
        }
    }

    public void waitForElementTextContains(String selector, String text, int timeout) {
        String desc = "text contains " + text + " for " + selector;
        if (!waitForCondition(() -> {
            String actualRaw = locator(selector).innerText();
            String actual = normalizeText(actualRaw);
            String expect = normalizeText(text);
            return actual.contains(expect);
        }, timeout, desc)) {
            throw new TimeoutException(desc);
        }
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

    public void waitForTitleContainsWithinTime(String keyword, int timeout) {
        if (!waitForCondition(() -> normalizeText(getTitle()).contains(normalizeText(keyword)), timeout, "title contains: " + keyword)) {
            throw new TimeoutException("Title does not contain: " + keyword);
        }
    }

    public void waitForUrlContainsWithinTime(String keyword, int timeout) {
        if (!waitForCondition(() -> getCurrentUrl().contains(keyword), timeout, "url contains: " + keyword)) {
            throw new TimeoutException("URL does not contain: " + keyword);
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

    public boolean retryWithValidation(Runnable operation, Predicate<Void> validation, int maxRetries, String desc) {
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

    public boolean retryWithValidation(Runnable operation, Predicate<Void> validation,
                                       int maxRetries, int retryIntervalMs, String desc) {
        ensurePageValid();
        for (int i = 0; i <= maxRetries; i++) {
            try {
                operation.run();
                if (validation.test(null)) return true;
            } catch (Exception ignored) {
            }
            page.waitForTimeout(retryIntervalMs);
        }
        return false;
    }

    public void waitForVisibleWithRetry(String selector, int timeout, int retries) {
        retry(() -> waitForElementVisibleWithinTime(selector, timeout), retries, 500, "wait visible with retry: " + selector);
    }

    public void waitForHiddenWithRetry(String selector, int timeout, int retries) {
        retry(() -> waitForElementHiddenWithinTime(selector, timeout), retries, 500, "wait hidden with retry: " + selector);
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

    public Locator locator(String selector) {
        ensurePageValid();
        return page.locator(selector);
    }

    public void click(String selector) {
        try {
            // 委托给 PageElement 以利用企业级重试机制
            new PageElement(selector, this).click();
        } catch (Exception e) {
            throw new ElementNotClickableException(selector, e);
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
        page.navigate(url, options);
        // 额外等待页面达到稳定状态
        try {
            page.waitForLoadState(LoadState.LOAD,
                new Page.WaitForLoadStateOptions().setTimeout((long) PlaywrightManager.config().getNavigationTimeout()));
        } catch (TimeoutError e) {
            logger.warn("Page did not reach LOAD state within timeout, continuing anyway. URL: {}", url);
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
    }

    public void back() {
        ensurePageValid();
        page.goBack();
    }

    public void forward() {
        ensurePageValid();
        page.goForward();
    }

    public void switchToPage(int index) {
        ensureContextValid();
        List<Page> pages = context.pages();
        if (index < 0 || index >= pages.size()) throw new IndexOutOfBoundsException("Invalid page index");
        page = pages.get(index);
        setCurrentPage();
    }

    public void switchToLatestPage() {
        ensureContextValid();
        List<Page> pages = context.pages();
        page = pages.get(pages.size() - 1);
        setCurrentPage();
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

    public void closeCurrentPageAndSwitchBack() {
        ensurePageValid();
        ensureContextValid();
        List<Page> pages = context.pages();
        if (pages.size() <= 1) {
            throw new IllegalStateException("Cannot close the only page");
        }
        int currentIndex = pages.indexOf(page);
        page.close();
        List<Page> updatedPages = context.pages();
        if (updatedPages.isEmpty()) {
            throw new IllegalStateException("No pages available after closing");
        }
        int targetIndex = Math.max(0, Math.min(currentIndex - 1, updatedPages.size() - 1));
        Page targetPage = updatedPages.get(targetIndex);
        page = targetPage;
        setCurrentPage();
    }

    public Frame getFrame(String name) {
        ensurePageValid();
        return page.frame(name);
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
        return page.evaluate(script, args);
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
        return normalizeText(page.content()).contains(normalizeText(text));
    }

    /**
     * 获取当前页面的完整 HTML 源码
     */
    public String getPageSource() {
        ensurePageValid();
        return page.content();
    }

    /**
     * 获取当前页面 viewport 尺寸（宽 x 高）
     * @return 格式如 "1366x768" 的字符串
     */
    public String getPageSize() {
        ensurePageValid();
        ViewportSize size = page.viewportSize();
        if (size == null) return "unknown";
        return size.width + "x" + size.height;
    }

    public void tap(String selector) {
        locator(selector).tap();
    }

    public BoundingBox getElementBoundingBox(String selector) {
        return locator(selector).boundingBox();
    }

    public boolean isClosed() {
        return page.isClosed();
    }

    public void bringToFront() {
        page.bringToFront();
    }

    public void setContent(String html) {
        page.setContent(html);
    }

    public void setViewportSize(int width, int height) {
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
        return page.getByAltText(altText);
    }

    public Locator byRole(AriaRole role) {
        ensurePageValid();
        return page.getByRole(role);
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
        return page.getByTitle(title);
    }

    public Locator byTestId(String testId) {
        ensurePageValid();
        return page.getByTestId(testId);
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

    public void waitForElementClickableWithinTime(String selector, int timeout) {
        if (!waitForCondition(() -> locator(selector).isVisible() && locator(selector).isEnabled(),
                timeout, "clickable: " + selector)) {
            throw new TimeoutException("Element not clickable: " + selector);
        }
    }

    public void waitForTimeout(int milliseconds) {
        ensurePageValid();
        page.waitForTimeout(milliseconds);
    }

    public void acceptAlert() {
        page.onDialog(dialog -> dialog.accept());
    }

    public void dismissAlert() {
        page.onDialog(dialog -> dialog.dismiss());
    }

    public byte[] takeScreenshot() {
        return page.screenshot();
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