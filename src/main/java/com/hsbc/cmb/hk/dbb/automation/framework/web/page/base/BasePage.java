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
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.TimeoutConfig;
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

    // -------------------------------------------------------------------------
    // ✅ 修复：日志占位符 + 异常捕获日志
    // -------------------------------------------------------------------------
    private boolean waitForCondition(BooleanSupplier condition, int timeoutSeconds, String desc) {
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
            page.waitForTimeout(TimeoutConfig.getPollingInterval());
        }
        LoggingConfigUtil.logWarnIfVerbose(logger, "⏳ Timeout waiting for: {}", desc);
        return false;
    }

    // -------------------------------------------------------------------------
    // ✅ 修复：返回值修正为 boolean（完全兼容子类）
    // -------------------------------------------------------------------------
    public boolean performActionWithTimeout(Runnable action, Supplier<Boolean> condition, int timeoutSeconds, String desc) {
        ensurePageValid();
        long end = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        while (System.currentTimeMillis() < end) {
            try {
                action.run();
                if (condition.get()) return true;
            } catch (Exception ignored) {}
            page.waitForTimeout(TimeoutConfig.getPollingInterval());
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

    // -------------------------------------------------------------------------
    // ✅ 修复：Objects.equals 空指针安全
    // -------------------------------------------------------------------------
    public void waitForElementAttributeEquals(String selector, String attr, String value, int timeout) {
        String desc = "attr " + attr + " equals " + value + " for " + selector;
        if (!waitForCondition(() -> {
            String actual = locator(selector).getAttribute(attr);
            return Objects.equals(value, actual == null ? "" : actual);
        }, timeout, desc)) {
            throw new TimeoutException(desc);
        }
    }

    public void waitForElementAttributeContains(String selector, String attr, String value, int timeout) {
        String desc = "attr " + attr + " contains " + value + " for " + selector;
        if (!waitForCondition(() -> {
            String actual = locator(selector).getAttribute(attr);
            return actual != null && actual.contains(value);
        }, timeout, desc)) {
            throw new TimeoutException(desc);
        }
    }

    public void waitForElementTextEquals(String selector, String text, int timeout) {
        String desc = "text equals " + text + " for " + selector;
        if (!waitForCondition(() -> {
            String actual = locator(selector).innerText();
            return text.equals((actual == null) ? "" : actual.trim());
        }, timeout, desc)) {
            throw new TimeoutException(desc);
        }
    }

    public void waitForElementTextContains(String selector, String text, int timeout) {
        String desc = "text contains " + text + " for " + selector;
        if (!waitForCondition(() -> {
            String actual = locator(selector).innerText();
            return (actual != null) && actual.trim().contains(text);
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
        if (!waitForCondition(() -> getTitle().contains(keyword), timeout, "title contains: " + keyword)) {
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

    // -------------------------------------------------------------------------
    // ✅ 修复：方法签名完全统一（Predicate<Void>）
    // -------------------------------------------------------------------------
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
            } catch (Exception ignored) {}
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

    // -------------------------------------------------------------------------
    // ✅ 修复：拼写正确
    // -------------------------------------------------------------------------
    public void navigateToWithRetry(String url, int retries) {
        retry(() -> navigateTo(url), retries, 1000, "navigate to: " + url);
    }

    public Locator locator(String selector) {
        ensurePageValid();
        return page.locator(selector);
    }

    public void click(String selector) {
        try {
            locator(selector).click();
        } catch (Exception e) {
            throw new ElementNotClickableException(selector, e);
        }
    }

    public void type(String selector, String text) {
        locator(selector).fill(text);
    }

    public void append(String selector, String text) {
        String current = locator(selector).inputValue();
        if (current == null) current = "";
        locator(selector).fill(current + text);
    }

    public void clear(String selector) {
        locator(selector).clear();
    }

    public String getText(String selector) {
        return locator(selector).innerText().trim();
    }

    public String getAttribute(String selector, String attr) {
        return locator(selector).getAttribute(attr);
    }

    public String getAttributeValue(String selector, String attr, String defaultValue) {
        String val = getAttribute(selector, attr);
        return val == null ? defaultValue : val;
    }

    public void selectOption(String selector, int index) {
        locator(selector).selectOption(new SelectOption().setIndex(index));
    }

    public void check(String selector) {
        locator(selector).check();
    }

    public void uncheck(String selector) {
        locator(selector).uncheck();
    }

    public boolean isChecked(String selector) {
        return locator(selector).isChecked();
    }

    public boolean isEnabled(String selector) {
        return locator(selector).isEnabled();
    }

    public boolean isDisabled(String selector) {
        return locator(selector).isDisabled();
    }

    public boolean isVisible(String selector) {
        return locator(selector).isVisible();
    }

    public boolean isHidden(String selector) {
        return locator(selector).isHidden();
    }

    public int getElementCount(String selector) {
        return locator(selector).count();
    }

    public void navigateTo(String url) {
        ensurePageValid();
        page.navigate(url);
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

    // -------------------------------------------------------------------------
    // ✅ 修复：空列表保护 + getLast()
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // ✅ 修复：空列表保护 + 安全索引
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // ✅ 修复：参数名正确
    // -------------------------------------------------------------------------
    public Object executeJavaScript(String script, Object... args) {
        ensurePageValid();
        return page.evaluate(script, args);
    }

    public String innerHTML(String selector) {
        Object result = locator(selector).evaluate("el => el.innerHTML");
        return result == null ? null : result.toString();
    }

    public String textContent(String selector) {
        Object result = locator(selector).evaluate("el => el.textContent");
        return result == null ? null : result.toString();
    }

    public boolean getPageSourceContains(String text) {
        return page.content().contains(text);
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

    // -------------------------------------------------------------------------
    // ✅ 修复：参数名正确 + 空路径检查
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // ✅ 修复：参数名正确
    // -------------------------------------------------------------------------
    public void waitForTimeout(int milliseconds) {
        ensurePageValid();
        page.waitForTimeout(milliseconds);
    }
}