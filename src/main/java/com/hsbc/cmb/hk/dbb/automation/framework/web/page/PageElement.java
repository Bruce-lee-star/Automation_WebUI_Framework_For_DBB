package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementNotFoundException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementOperationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.TimeoutException;
import com.microsoft.playwright.PlaywrightException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.MouseButton;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class PageElement {
    private static final Logger logger = LoggerFactory.getLogger(PageElement.class);

    private final String selector;
    private final BasePage page;

    // ==================== Constructor ====================
    public PageElement(String selector, BasePage page) {
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException("Selector cannot be null or blank");
        }
        if (page == null) {
            throw new IllegalArgumentException("BasePage cannot be null");
        }
        this.selector = selector;
        this.page = page;
    }

    public String getSelector() {
        return selector;
    }

    public BasePage getPage() {
        return page;
    }

    public Locator locator() {
        return page.locator(selector);
    }

    public Locator locator(String relativeSelector) {
        return locator().locator(relativeSelector);
    }

    // ==================== Element State Check ====================
    private boolean elementExists() {
        try {
            // 等待元素出现在 DOM 中，使用配置的检查超时（默认15秒）
            locator().first().waitFor(
                new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout(PlaywrightManager.config().getElementCheckTimeout())
            );
            return true;
        } catch (TimeoutError e) {
            logger.debug("elementExists() timeout: selector={}, timeout={}ms", 
                selector, PlaywrightManager.config().getElementCheckTimeout());
            return false;
        } catch (Exception e) {
            logger.debug("elementExists() error: selector={}, error={}", selector, e.getMessage());
            return false;
        }
    }

    private boolean elementIsVisible() {
        try {
            // 等待元素变为可见，使用配置的检查超时（默认15秒）
            locator().first().waitFor(
                new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(PlaywrightManager.config().getElementCheckTimeout())
            );
            return true;
        } catch (TimeoutError e) {
            logger.debug("elementIsVisible() timeout: selector={}, timeout={}ms", 
                selector, PlaywrightManager.config().getElementCheckTimeout());
            return false;
        } catch (Exception e) {
            logger.debug("elementIsVisible() error: selector={}, error={}", selector, e.getMessage());
            return false;
        }
    }

    private String getCurrentPageUrl() {
        try {
            return page.getCurrentUrl();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ==================== Retry Core (Enterprise-grade) ====================
    /**
     * 带成功检查的重试机制
     *
     * Enterprise-grade retry mechanism with:
     * - Idempotency guarantee
     * - Detailed diagnostics on failure
     * - Configurable retry parameters
     * - Custom exception with full context
     */
    private void executeWithRetry(Supplier<Boolean> action, String operation) {
        executeWithRetry(action, operation, null);
    }

    private void executeWithRetry(Supplier<Boolean> action, String operation, String testName) {
        ElementDiagnosticsCollector diagnostics = new ElementDiagnosticsCollector(locator(), selector, page.getPage());

        // Pre-flight check: element must exist in DOM
        // 使用配置的检查超时，如果超时则说明元素真的不存在，不重试
        if (!elementExists()) {
            ElementOperationException.DiagnosticInfo info = diagnostics.collect();
            info.retryCount(0);

            ElementOperationException ex = ElementOperationException.builder()
                .selector(selector)
                .operation(operation)
                .pageUrl(diagnostics.getPageUrl())
                .elementState("NOT_FOUND_IN_DOM")
                .diagnosticInfo(info)
                .customMessage(String.format(
                    "Element not found in DOM: [%s] on page [%s]. " +
                    "Possible reasons: 1) Selector is incorrect, 2) Element was removed from DOM, 3) Page navigation failed.",
                    selector, diagnostics.getPageUrl()))
                .build();

            captureFailureAndLog(operation, testName, ex);
            throw ex; // 直接抛出，不重试
        }

        int maxRetry = PlaywrightManager.config().getElementMaxRetry();
        Exception lastEx = null;
        long startTime = System.currentTimeMillis();
        long maxWaitTime = PlaywrightManager.config().getElementOperationTimeout();

        for (int i = 0; i <= maxRetry; i++) {
            // 检查是否已经超过最大等待时间
            if (System.currentTimeMillis() - startTime > maxWaitTime) {
                logger.error("[{}] Exceeded max wait time ({} ms), stop retrying: {}", 
                    operation, maxWaitTime, selector);
                break;
            }

            try {
                Boolean success = action.get();
                if (success != null && success) {
                    logger.debug("[{}] success on attempt {}/{}: {}",
                        operation, i + 1, maxRetry + 1, selector);
                    return;
                }
                if (i < maxRetry) {
                    logger.debug("[Retry {}/{}] {} needs retry: {}",
                        i + 1, maxRetry, operation, selector);
                    page.waitForTimeout(PlaywrightManager.config().getElementRetryDelayMs());
                }
            } catch (TimeoutError e) {
                lastEx = e;
                // TimeoutError 不重试（超时意味着操作真的失败了）
                logger.error("[{}] Timeout on attempt {}/{}: {}",
                    operation, i + 1, maxRetry + 1, selector);
                break;
            } catch (PlaywrightException e) {
                lastEx = e;
                if (i == maxRetry || !isRetriable(e)) {
                    break;
                }
                logger.warn("[Retry {}/{}] {} failed: {}",
                    i + 1, maxRetry, operation, e.getMessage());
                page.waitForTimeout(PlaywrightManager.config().getElementRetryDelayMs());
            } catch (Exception e) {
                lastEx = e;
                // 其他异常（如框架异常）不重试，直接抛出
                logger.error("[{}] Non-retriable exception: {}", operation, e.getMessage());
                break;
            }
        }

        // Build detailed exception with full diagnostic info
        ElementOperationException.DiagnosticInfo info = diagnostics.collect();
        info.retryCount(maxRetry + 1);

        String elementState = determineElementState(diagnostics);
        String customMessage = buildDetailedErrorMessage(operation, lastEx, diagnostics, maxRetry);

        ElementOperationException ex = ElementOperationException.builder()
            .selector(selector)
            .operation(operation)
            .pageUrl(diagnostics.getPageUrl())
            .elementState(elementState)
            .diagnosticInfo(info)
            .cause(lastEx)
            .customMessage(customMessage)
            .build();

        captureFailureAndLog(operation, testName, ex);
        throw ex;
    }

    private String determineElementState(ElementDiagnosticsCollector dc) {
        if (!dc.collect().existsInDom()) return "NOT_FOUND_IN_DOM";
        if (!dc.collect().isVisible()) return "NOT_VISIBLE";
        if (!dc.collect().isEnabled()) return "NOT_ENABLED";
        if (!dc.collect().isEditable()) return "NOT_EDITABLE";
        return "INTERACTABLE_BUT_FAILED";
    }

    private String buildDetailedErrorMessage(String operation, Exception lastEx,
            ElementDiagnosticsCollector dc, int maxRetry) {
        StringBuilder sb = new StringBuilder();

        sb.append(String.format("Operation [%s] failed after %d attempts on element [%s]%n%n",
            operation, maxRetry + 1, selector));
        sb.append(String.format("Current Page: %s%n", dc.getPageUrl()));
        sb.append(String.format("Page Title:  %s%n", dc.getPageTitle()));
        sb.append(String.format("Element State: %s%n", determineElementState(dc)));

        sb.append(String.format("%nDOM Context:%n  %s%n", dc.getDomContext()));
        sb.append(String.format("Obstruction: %s%n", dc.getObstructingElements()));

        if (lastEx instanceof TimeoutError) {
            sb.append(String.format("%nRoot Cause: Timeout Error%n"));
            sb.append(String.format("The element did not reach the expected state within the timeout period.%n"));
            sb.append(String.format("Common solutions:%n"));
            sb.append(String.format("  1. Wait for specific condition before action (e.g., waitForVisible())%n"));
            sb.append(String.format("  2. Verify the element is not hidden or covered by other elements%n"));
            sb.append(String.format("  3. Check if the page has fully loaded before interacting%n"));
        } else if (lastEx != null) {
            sb.append(String.format("%nRoot Cause: %s%n", lastEx.getMessage()));
        }

        sb.append(String.format("%nHTML Snippet:%n  %s%n",
            dc.getElementHtml().replace("\n", "\n  ")));

        return sb.toString();
    }

    private void captureFailureAndLog(String operation, String testName, ElementOperationException ex) {
        String screenshotPath = null;
        try {
            screenshotPath = new ElementDiagnosticsCollector(locator(), selector, page.getPage())
                .captureFailureScreenshot(testName != null ? testName : operation);
        } catch (Exception e) {
            logger.warn("Failed to capture failure screenshot: {}", e.getMessage());
        }

        if (screenshotPath != null) {
            logger.error("Screenshot saved: {}", screenshotPath);
        }
        logger.error("Element operation failed: {}", ex.getMessage());
    }

    private boolean isRetriable(PlaywrightException e) {
        // TimeoutError 已经在单独的 catch 块处理，这里不再判断
        String m = e.getMessage().toLowerCase();
        
        // 只重试真正值得重试的异常（临时性问题，重试可能成功）
        // 其他异常直接失败，避免浪费时间
        
        // 1. 元素被遮挡或拦截（等待后可能消失）
        if (m.contains("intercepted") || m.contains("obscured") || m.contains("obscured")) {
            return true;
        }
        
        // 2. 元素暂时不可交互（可能在动画中）
        if (m.contains("not interactable") || m.contains("not clickable")) {
            return true;
        }
        
        // 3. 元素从 DOM 分离（页面正在更新）
        if (m.contains("detached") || m.contains("not attached")) {
            return true;
        }
        
        // 以下情况不重试（重试也不会成功，浪费时间）：
        // - element not found（元素不存在，重试没用）
        // - navigation failed（导航失败，重试没用）
        // - net::ERR_*（网络错误，重试没用）
        // - TimeoutError（已在单独处理，默认不重试）
        
        return false;
    }

    // ==================== Execute with Test Name (for Screenshots) ====================
    /**
     * 执行操作并关联测试名称（用于失败截图命名）
     */
    public PageElement click(String testName) {
        executeWithRetry(() -> {
            locator().scrollIntoViewIfNeeded();
            locator().click(new Locator.ClickOptions().setDelay(100));
            page.waitForTimeout(PlaywrightManager.config().getElementActionPostDelay());
            return true;
        }, "click", testName);
        return this;
    }

    public PageElement fill(String text, String testName) {
        executeWithRetry(() -> {
            locator().scrollIntoViewIfNeeded();
            locator().fill(text);
            return true;
        }, "fill", testName);
        return this;
    }

    // ==================== Click ====================
    public PageElement click() {
        return click(null);
    }

    public PageElement doubleClick() {
        executeWithRetry(() -> {
            locator().scrollIntoViewIfNeeded();
            locator().dblclick();
            page.waitForTimeout(PlaywrightManager.config().getElementActionPostDelay());
            return true;
        }, "doubleClick");
        return this;
    }

    public PageElement rightClick() {
        executeWithRetry(() -> {
            locator().scrollIntoViewIfNeeded();
            locator().click(new Locator.ClickOptions().setButton(MouseButton.RIGHT));
            page.waitForTimeout(PlaywrightManager.config().getElementActionPostDelay());
            return true;
        }, "rightClick");
        return this;
    }

    // ==================== Input ====================
    public PageElement fill(String text) {
        return fill(text, null);
    }

    public PageElement type(String text) {
        executeWithRetry(() -> {
            locator().scrollIntoViewIfNeeded();
            locator().pressSequentially(text);
            return true;
        }, "type");
        return this;
    }

    public PageElement clear() {
        executeWithRetry(() -> {
            locator().clear();
            return true;
        }, "clear");
        return this;
    }

    public PageElement clearAndSetValue(String text) {
        return clear().fill(text);
    }

    public PageElement clearAndTypeSequentially(String text) {
        return clear().type(text);
    }

    // ==================== Keyboard ====================
    public PageElement press(String key) {
        executeWithRetry(() -> {
            locator().press(key);
            return true;
        }, "press");
        return this;
    }

    public PageElement selectText() {
        executeWithRetry(() -> {
            locator().selectText();
            return true;
        }, "selectText");
        return this;
    }

    // ==================== State Check ====================
    public boolean isVisible() {
        return isVisible(PlaywrightManager.config().getElementCheckTimeout() / 1000);
    }

    public boolean isVisible(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout((long) timeoutSec * 1000));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isNotVisible() {
        return isNotVisible(PlaywrightManager.config().getElementCheckTimeout() / 1000);
    }

    public boolean isNotVisible(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout((long) timeoutSec * 1000));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean exists() {
        return exists(PlaywrightManager.config().getElementCheckTimeout() / 1000);
    }

    public boolean exists(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout((long) timeoutSec * 1000));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isEnabled() {
        return isEnabled(PlaywrightManager.config().getElementCheckTimeout() / 1000);
    }

    public boolean isEnabled(int timeoutSec) {
        if (!exists(timeoutSec)) return false;
        try {
            return locator().isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isDisabled() {
        return !isEnabled();
    }

    public boolean isDisabled(int timeoutSec) {
        return !isEnabled(timeoutSec);
    }

    public boolean isEditable() {
        return isEditable(PlaywrightManager.config().getElementCheckTimeout() / 1000);
    }

    public boolean isEditable(int timeoutSec) {
        if (!exists(timeoutSec)) return false;
        try {
            return locator().isEditable();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isChecked() {
        return isChecked(PlaywrightManager.config().getElementCheckTimeout() / 1000);
    }

    public boolean isChecked(int timeoutSec) {
        if (!exists(timeoutSec)) return false;
        try {
            return locator().isChecked();
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Text & Attribute ====================
    public String getText() {
        try {
            String raw = locator().textContent();
            if (raw == null) {
                logger.warn("getText() returned null for selector: {}", selector);
                return "";
            }
            return raw.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            logger.warn("getText failed: {}", selector, e);
            return "";
        }
    }

    public String getInnerText() {
        try {
            return locator().innerText();
        } catch (Exception e) {
            logger.warn("getInnerText failed: {}", selector, e);
            return "";
        }
    }

    public String getInnerHtml() {
        try {
            return locator().innerHTML();
        } catch (Exception e) {
            logger.warn("getInnerHtml failed: {}", selector, e);
            return "";
        }
    }

    public List<String> getAllTextContents() {
        try {
            return locator().allTextContents();
        } catch (Exception e) {
            logger.warn("allTextContents failed: {}", selector, e);
            return List.of();
        }
    }

    public String getAttribute(String attr) {
        try {
            return locator().getAttribute(attr);
        } catch (Exception e) {
            logger.warn("getAttribute failed: {}", selector, e);
            return null;
        }
    }

    public String getValue() {
        try {
            return locator().inputValue();
        } catch (Exception e) {
            logger.warn("getValue failed: {}", selector, e);
            return "";
        }
    }

    // ==================== Select ====================
    public PageElement selectByValue(String value) {
        executeWithRetry(() -> {
            locator().selectOption(value);
            return true;
        }, "selectByValue");
        return this;
    }

    public PageElement selectByIndex(int index) {
        executeWithRetry(() -> {
            locator().selectOption(new SelectOption().setIndex(index));
            return true;
        }, "selectByIndex");
        return this;
    }

    public PageElement selectByVisibleText(String text) {
        executeWithRetry(() -> {
            locator().selectOption(new SelectOption().setLabel(text));
            return true;
        }, "selectByVisibleText");
        return this;
    }

    // ==================== WaitFor (Full Set) ====================
    private int getDefaultTimeoutSec() {
        return PlaywrightManager.config().getElementCheckTimeout() / 1000;
    }

    public PageElement waitForVisible() {
        return waitForVisible(getDefaultTimeoutSec());
    }

    public PageElement waitForVisible(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout((long) timeoutSec * 1000));
            return this;
        } catch (TimeoutError e) {
            throw new ElementOperationException("waitForVisible", selector, 
                "Element not visible within " + timeoutSec + " seconds: " + selector, e);
        } catch (Exception e) {
            throw new ElementOperationException("waitForVisible", selector, 
                "Failed to wait for element visible: " + selector, e);
        }
    }

    public PageElement waitForNotVisible() {
        return waitForNotVisible(getDefaultTimeoutSec());
    }

    public PageElement waitForNotVisible(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout((long) timeoutSec * 1000));
            return this;
        } catch (TimeoutError e) {
            throw new ElementOperationException("waitForNotVisible", selector, 
                "Element still visible after " + timeoutSec + " seconds: " + selector, e);
        } catch (Exception e) {
            throw new ElementOperationException("waitForNotVisible", selector, 
                "Failed to wait for element hidden: " + selector, e);
        }
    }

    public PageElement waitForExists() {
        return waitForExists(getDefaultTimeoutSec());
    }

    public PageElement waitForExists(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout((long) timeoutSec * 1000));
            return this;
        } catch (TimeoutError e) {
            throw new ElementNotFoundException(selector, e);
        } catch (Exception e) {
            throw new ElementOperationException("waitForExists", selector, 
                "Failed to wait for element exists: " + selector, e);
        }
    }

    public PageElement waitForNotExists() {
        return waitForNotExists(getDefaultTimeoutSec());
    }

    public PageElement waitForNotExists(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED).setTimeout((long) timeoutSec * 1000));
            return this;
        } catch (TimeoutError e) {
            throw new ElementOperationException("waitForNotExists", selector, 
                "Element still exists in DOM after " + timeoutSec + " seconds: " + selector, e);
        } catch (Exception e) {
            throw new ElementOperationException("waitForNotExists", selector, 
                "Failed to wait for element detached: " + selector, e);
        }
    }

    public PageElement waitForClickable() {
        return waitForClickable(getDefaultTimeoutSec());
    }

    public PageElement waitForClickable(int timeoutSec) {
        waitForVisible(timeoutSec);
        if (!locator().isEnabled()) {
            throw new ElementOperationException("waitForClickable", selector, 
                "Element is not clickable (not enabled): " + selector, null);
        }
        return this;
    }

    public PageElement waitForEditable() {
        return waitForEditable(getDefaultTimeoutSec());
    }

    public PageElement waitForEditable(int timeoutSec) {
        waitForVisible(timeoutSec);
        if (!locator().isEditable()) {
            throw new ElementOperationException("waitForEditable", selector, 
                "Element is not editable: " + selector, null);
        }
        return this;
    }

    public PageElement waitForEnabled() {
        return waitForEnabled(getDefaultTimeoutSec());
    }

    public PageElement waitForEnabled(int timeoutSec) {
        waitForExists(timeoutSec);
        if (!locator().isEnabled()) {
            throw new ElementOperationException("waitForEnabled", selector, 
                "Element is not enabled: " + selector, null);
        }
        return this;
    }

    public PageElement waitForDisabled() {
        return waitForDisabled(getDefaultTimeoutSec());
    }

    public PageElement waitForDisabled(int timeoutSec) {
        waitForExists(timeoutSec);
        if (locator().isEnabled()) {
            throw new ElementOperationException("waitForDisabled", selector, 
                "Element is not disabled (still enabled): " + selector, null);
        }
        return this;
    }

    public PageElement waitForChecked() {
        return waitForChecked(getDefaultTimeoutSec());
    }

    public PageElement waitForChecked(int timeoutSec) {
        waitForExists(timeoutSec);
        if (!locator().isChecked()) {
            throw new ElementOperationException("waitForChecked", selector, 
                "Element is not checked: " + selector, null);
        }
        return this;
    }

    public PageElement waitForNotChecked() {
        return waitForNotChecked(getDefaultTimeoutSec());
    }

    public PageElement waitForNotChecked(int timeoutSec) {
        waitForExists(timeoutSec);
        if (locator().isChecked()) {
            throw new ElementOperationException("waitForNotChecked", selector, 
                "Element is checked (expected not checked): " + selector, null);
        }
        return this;
    }

    // ==================== Event & JS ====================
    public PageElement dispatchEvent(String event) {
        executeWithRetry(() -> {
            locator().dispatchEvent(event);
            return true;
        }, "dispatchEvent");
        return this;
    }

    public PageElement dispatchEvent(String event, Object arg) {
        executeWithRetry(() -> {
            locator().dispatchEvent(event, arg);
            return true;
        }, "dispatchEvent");
        return this;
    }

    // ==================== Hover / Focus / Check ====================
    public PageElement hover() {
        executeWithRetry(() -> {
            locator().hover();
            return true;
        }, "hover");
        return this;
    }

    public PageElement focus() {
        executeWithRetry(() -> {
            locator().focus();
            return true;
        }, "focus");
        return this;
    }

    public PageElement check() {
        executeWithRetry(() -> {
            locator().check();
            return true;
        }, "check");
        return this;
    }

    public PageElement uncheck() {
        executeWithRetry(() -> {
            locator().uncheck();
            return true;
        }, "uncheck");
        return this;
    }

    // ==================== Upload / Screenshot / Drag ====================
    public PageElement uploadFile(String... paths) {
        Path[] pathArray = Arrays.stream(paths).map(Paths::get).toArray(Path[]::new);
        executeWithRetry(() -> {
            locator().setInputFiles(pathArray);
            return true;
        }, "uploadFile");
        return this;
    }

    public byte[] screenshot() {
        try {
            return locator().screenshot();
        } catch (Exception e) {
            logger.error("screenshot failed: {}", selector, e);
            return new byte[0];
        }
    }

    public PageElement dragTo(PageElement target) {
        executeWithRetry(() -> {
            locator().dragTo(target.locator());
            return true;
        }, "dragTo");
        return this;
    }

    // ==================== Utils ====================
    public BoundingBox getBoundingBoxSafe() {
        waitForVisible();
        BoundingBox box = locator().boundingBox();
        return Objects.requireNonNull(box, "BoundingBox null: " + selector);
    }

    public int count() {
        return locator().count();
    }

    public ElementHandle elementHandle() {
        return locator().elementHandle();
    }

    // ==================== Child Element ====================
    public PageElement child(String childSelector) {
        Objects.requireNonNull(childSelector, "childSelector must not be null");
        String clean = childSelector.trim();
        while (clean.startsWith(">>")) clean = clean.substring(2).trim();

        String parent = selector.trim();
        while (parent.endsWith(">>")) parent = parent.substring(0, parent.length() - 2).trim();

        return new PageElement(parent + " >> " + clean, page);
    }

    public PageElement child(String childSelector, int index) {
        return new PageElement(child(childSelector).getSelector() + " >> nth=" + index, page);
    }

    @Override
    public String toString() {
        return "PageElement[" + selector + "]";
    }
}