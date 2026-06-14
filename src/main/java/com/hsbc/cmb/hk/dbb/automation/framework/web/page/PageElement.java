package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementNotFoundException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementOperationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.TextNormalizer;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
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

    /**
     * 页面存活性保护 + Locator 重建。
     * 不再缓存 Locator——每次调用通过 {@code page.getPage()} 触发 ensurePageValid()，
     * 确保 Page 关闭重建后返回绑定到新 Page 实例的 Locator。
     */
    public Locator locator() {
        // 触发 ensurePageValid() → 如 page 已关闭则重建 page
        page.getPage();
        return page.locator(selector);
    }

    public Locator locator(String relativeSelector) {
        return locator().locator(relativeSelector);
    }

    // ==================== Safe Execution Template ====================
    /**
     * 安全的 Locator 操作执行模板——统一处理 Playwright 异常转换 + 自动诊断收集。
     * 消除 getText/getAttribute/getValue 等方法中重复的 try-catch 模板。
     * 失败时自动收集 DOM 诊断信息并捕获截图。
     */
    private <T> T executeSafely(Supplier<T> action, String operation) {
        try {
            return action.get();
        } catch (TimeoutError e) {
            ElementNotFoundException ex = new ElementNotFoundException(selector, e);
            captureDiagnosticsAndLog(operation, ex, selector);
            throw ex;
        } catch (PlaywrightException e) {
            ElementOperationException ex = new ElementOperationException(operation, selector,
                "Failed: " + operation, e);
            captureDiagnosticsAndLog(operation, ex, selector);
            throw ex;
        }
    }

    /**
     * 失败时自动收集诊断信息 + 截图（executeSafely 的失败路径）。
     * 与 executeWithRetry 中的 captureFailureAndLog 不同，
     * 这里只收集基础诊断信息用于快速定位问题，不做完整的 DOM 上下文分析。
     */
    private void captureDiagnosticsAndLog(String operation, RuntimeException ex, String selector) {
        try {
            ElementDiagnosticsCollector diagnostics = new ElementDiagnosticsCollector(locator(), selector, page.getPageRaw(), page.getCurrentFrame());
            ElementOperationException.DiagnosticInfo info = diagnostics.collect();
            String screenshotPath = diagnostics.captureFailureScreenshot(operation);
            logger.debug("[{}] failed on '{}' | exists={} visible={} enabled={} count={} | screenshot={}",
                operation, selector,
                info.existsInDom(), info.isVisible(), info.isEnabled(), info.elementCount(),
                screenshotPath != null ? screenshotPath : "N/A");
        } catch (Exception ignored) {
            // 诊断收集本身不应影响主异常抛出
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
        long startTime = System.currentTimeMillis();

        int maxRetry = PlaywrightManager.config().getElementMaxRetry();
        Exception lastEx = null;
        long deadline = startTime + PlaywrightManager.config().getElementOperationTimeout();

        for (int i = 0; i <= maxRetry; i++) {
            // 检查是否已经超过截止时间
            if (System.currentTimeMillis() > deadline) {
                logger.warn("[{}] Exceeded max wait time ({} ms) after {} attempts: {}",
                    operation, PlaywrightManager.config().getElementOperationTimeout(), i, selector);
                break;
            }

            try {
                // Playwright 内置 actionability check（attached→visible→stable→enabled→receives events）
                // 无需额外 pre-flight 检查
                action.get();
                logger.debug("[{}] success on attempt {}/{}: {}",
                    operation, i + 1, maxRetry + 1, selector);
                return;
            } catch (TimeoutError e) {
                lastEx = e;
                if (i == maxRetry || !isRetriable(e)) {
                    logger.warn("[{}] Timeout on attempt {}/{}: {}",
                        operation, i + 1, maxRetry + 1, selector);
                    break;
                }
                logger.warn("[Retry {}/{}] {} timed out: {}",
                    i + 1, maxRetry, operation, e.getMessage());
                page.waitForTimeout((int) PlaywrightManager.config().getElementRetryDelayMs());
            } catch (PlaywrightException e) {
                lastEx = e;
                if (i == maxRetry || !isRetriable(e)) {
                    break;
                }
                logger.warn("[Retry {}/{}] {} failed: {}",
                    i + 1, maxRetry, operation, e.getMessage());
                page.waitForTimeout((int) PlaywrightManager.config().getElementRetryDelayMs());
            } catch (Exception e) {
                lastEx = e;
                logger.warn("[{}] Non-retriable exception: {}", operation, e.getMessage());
                break;
            }
        }

        // Build detailed exception with full diagnostic info
        // 诊断收集器延迟创建——仅在失败路径才收集
        ElementDiagnosticsCollector diagnostics = new ElementDiagnosticsCollector(locator(), selector, page.getPageRaw(), page.getCurrentFrame());
        ElementOperationException.DiagnosticInfo info = diagnostics.collect();
        info.retryCount(maxRetry + 1);

        String elementState = determineElementState(info);
        String customMessage = buildDetailedErrorMessage(operation, lastEx, diagnostics, maxRetry, elementState);

        ElementOperationException ex = ElementOperationException.builder()
            .selector(selector)
            .operation(operation)
            .pageUrl(diagnostics.getPageUrl())
            .elementState(elementState)
            .diagnosticInfo(info)
            .cause(lastEx)
            .customMessage(customMessage)
            .build();

        captureFailureAndLog(operation, testName, ex, diagnostics);
        throw ex;
    }

    private static String determineElementState(ElementOperationException.DiagnosticInfo diag) {
        if (!diag.existsInDom()) return "NOT_FOUND_IN_DOM";
        if (!diag.isVisible()) return "NOT_VISIBLE";
        if (!diag.isEnabled()) return "NOT_ENABLED";
        if (!diag.isEditable()) return "NOT_EDITABLE";
        return "INTERACTABLE_BUT_FAILED";
    }

    private String buildDetailedErrorMessage(String operation, Exception lastEx,
            ElementDiagnosticsCollector dc, int maxRetry, String elementState) {
        // 简洁一行格式 — 详细诊断信息（DOM context, HTML snippet 等）可到 Serenity 报告查看
        String cause = lastEx instanceof TimeoutError ? "TimeoutError"
            : (lastEx != null ? lastEx.getClass().getSimpleName() : "unknown");
        return String.format("[%s] %s failed after %d attempts on '%s' | page=%s title=%s obstruction=%s | cause=%s",
            elementState, operation, maxRetry + 1, selector,
            dc.getPageUrl(), dc.getPageTitle(), dc.getObstructingElements(), cause);
    }

    private void captureFailureAndLog(String operation, String testName, ElementOperationException ex,
                                      ElementDiagnosticsCollector diagnostics) {
        String screenshotPath = null;
        try {
            screenshotPath = diagnostics.captureFailureScreenshot(
                testName != null ? testName : operation);
        } catch (Exception e) {
            logger.warn("Failed to capture failure screenshot: {}", e.getMessage());
        }

        if (screenshotPath != null) {
            logger.debug("Failure screenshot saved: {}", screenshotPath);
        }
        // 降级为 WARN，避免与上游重试日志 + 下游 Listener 层形成三重 error 重复输出
        // 异常最终会被抛出并由 PlaywrightListener.stepFailed() 统一记录 error 日志
        logger.warn("Element operation failed: {}", ex.getMessage());
    }

    /**
     * 判断异常是否值得重试。
     * <p>仅 TimeoutError 类型的异常可能重试（原因：Playwright 的所有可交互性检查
     * 超时后都以 TimeoutError 体现——元素被遮挡/动画中/DOM 分离等都是 TimeoutError）。
     * <p>非 TimeoutError（如网络错误、协议错误等）绝不可能通过重试解决，直接失败。
     */
    private boolean isRetriable(PlaywrightException e) {
        // 只有 TimeoutError 才可能是临时性问题（重试可能成功）
        if (!(e instanceof TimeoutError)) {
            return false;
        }

        String m = e.getMessage();
        if (m == null) {
            return true; // 消息为空时保守重试（TimeoutError 本身已限定范围）
        }
        m = m.toLowerCase();

        // 1. 元素被遮挡或拦截（等待后可能消失）
        if (m.contains("intercepted") || m.contains("obscured")) {
            return true;
        }

        // 2. 元素暂时不可交互（可能在动画中）
        if (m.contains("not interactable") || m.contains("not clickable") || m.contains("not visible")) {
            return true;
        }

        // 3. 元素从 DOM 分离（页面正在更新）
        if (m.contains("detached") || m.contains("not attached")) {
            return true;
        }

        // 以下 TimeoutError 不重试（重试也不会成功，浪费时间）：
        // - "element not found" → 选择器错误，重试没用
        // - "net::ERR_*" → 网络错误导致的 TimeoutError，重试没用
        // - 其他无法识别的 TimeoutError → 保守不重试，避免无限等待

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
            page.waitForTimeout((int) PlaywrightManager.config().getElementActionPostDelay());
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
            page.waitForTimeout((int) PlaywrightManager.config().getElementActionPostDelay());
            return true;
        }, "doubleClick");
        return this;
    }

    public PageElement rightClick() {
        executeWithRetry(() -> {
            locator().scrollIntoViewIfNeeded();
            locator().click(new Locator.ClickOptions().setButton(MouseButton.RIGHT));
            page.waitForTimeout((int) PlaywrightManager.config().getElementActionPostDelay());
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
            locator().scrollIntoViewIfNeeded();
            locator().clear();
            return true;
        }, "clear");
        return this;
    }

    public PageElement clearAndSetValue(String text) {
        executeWithRetry(() -> {
            locator().scrollIntoViewIfNeeded();
            locator().clear();
            locator().fill(text);
            return true;
        }, "clearAndSetValue");
        return this;
    }

    public PageElement clearAndTypeSequentially(String text) {
        executeWithRetry(() -> {
            locator().scrollIntoViewIfNeeded();
            locator().clear();
            locator().pressSequentially(text);
            return true;
        }, "clearAndTypeSequentially");
        return this;
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
        return isVisible(getDefaultTimeoutMs() / 1000);
    }

    public boolean isVisible(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout((long) timeoutSec * 1000));
            return true;
        } catch (PlaywrightException e) {
            return false;
        }
    }

    public boolean isNotVisible() {
        return isNotVisible(getDefaultTimeoutMs() / 1000);
    }

    public boolean isNotVisible(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout((long) timeoutSec * 1000));
            return true;
        } catch (PlaywrightException e) {
            return false;
        }
    }

    public boolean exists() {
        return exists(getDefaultTimeoutMs() / 1000);
    }

    public boolean exists(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout((long) timeoutSec * 1000));
            return true;
        } catch (PlaywrightException e) {
            return false;
        }
    }

    public boolean isEnabled() {
        return isEnabled(getDefaultTimeoutMs() / 1000);
    }

    public boolean isEnabled(int timeoutSec) {
        try {
            return locator().isEnabled(new Locator.IsEnabledOptions()
                    .setTimeout((double) timeoutSec * 1000));
        } catch (PlaywrightException e) {
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
        return isEditable(getDefaultTimeoutMs() / 1000);
    }

    public boolean isEditable(int timeoutSec) {
        try {
            return locator().isEditable(new Locator.IsEditableOptions()
                    .setTimeout((double) timeoutSec * 1000));
        } catch (PlaywrightException e) {
            return false;
        }
    }

    public boolean isChecked() {
        return isChecked(getDefaultTimeoutMs() / 1000);
    }

    public boolean isChecked(int timeoutSec) {
        try {
            return locator().isChecked(new Locator.IsCheckedOptions()
                    .setTimeout((double) timeoutSec * 1000));
        } catch (PlaywrightException e) {
            return false;
        }
    }

    // ==================== Text & Attribute ====================
    public String getText() {
        return executeSafely(() -> {
            String raw = locator().innerText();
            if (raw == null) {
                logger.warn("getText() returned null for selector: {}", selector);
                return "";
            }
            return TextNormalizer.normalize(raw);
        }, "getText");
    }

    /**
     * 获取原始文本，跳过标准化管道（性能优化：列表遍历场景避免 4 次正则）。
     */
    public String getTextRaw() {
        return executeSafely(() -> {
            String raw = locator().innerText();
            return raw != null ? raw : "";
        }, "getTextRaw");
    }

    public String getInnerHtml() {
        return executeSafely(() -> locator().innerHTML(), "getInnerHtml");
    }

    public List<String> getAllTextContents() {
        return executeSafely(() -> locator().allTextContents(), "getAllTextContents");
    }

    public String getAttribute(String attr) {
        return executeSafely(() -> locator().getAttribute(attr), "getAttribute");
    }

    public String getValue() {
        return executeSafely(() -> locator().inputValue(), "getValue");
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
    private int getDefaultTimeoutMs() {
        return PlaywrightManager.config().getElementCheckTimeout();
    }

    public PageElement waitForVisible() {
        return waitForVisible(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForVisible(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout((long) timeoutSec * 1000));
            return this;
        } catch (TimeoutError e) {
            throw new ElementOperationException("waitForVisible", selector, 
                "Element not visible within " + timeoutSec + " seconds: " + selector, e);
        } catch (PlaywrightException e) {
            throw new ElementOperationException("waitForVisible", selector, 
                "Failed to wait for element visible: " + selector, e);
        }
    }

    public PageElement waitForNotVisible() {
        return waitForNotVisible(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForNotVisible(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout((long) timeoutSec * 1000));
            return this;
        } catch (TimeoutError e) {
            throw new ElementOperationException("waitForNotVisible", selector, 
                "Element still visible after " + timeoutSec + " seconds: " + selector, e);
        } catch (PlaywrightException e) {
            throw new ElementOperationException("waitForNotVisible", selector, 
                "Failed to wait for element hidden: " + selector, e);
        }
    }

    public PageElement waitForExists() {
        return waitForExists(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForExists(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout((long) timeoutSec * 1000));
            return this;
        } catch (TimeoutError e) {
            throw new ElementNotFoundException(selector, e);
        } catch (PlaywrightException e) {
            throw new ElementOperationException("waitForExists", selector, 
                "Failed to wait for element exists: " + selector, e);
        }
    }

    public PageElement waitForNotExists() {
        return waitForNotExists(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForNotExists(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED).setTimeout((long) timeoutSec * 1000));
            return this;
        } catch (TimeoutError e) {
            throw new ElementOperationException("waitForNotExists", selector, 
                "Element still exists in DOM after " + timeoutSec + " seconds: " + selector, e);
        } catch (PlaywrightException e) {
            throw new ElementOperationException("waitForNotExists", selector, 
                "Failed to wait for element detached: " + selector, e);
        }
    }

    public PageElement waitForClickable() {
        return waitForClickable(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForClickable(int timeoutSec) {
        try {
            // Playwright click() 内置完整 actionability 检查（visible+stable+enabled+receives events）
            // waitForClickable 仅需确认元素已可见即可，实际可交互性由 click() 保证
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout((long) timeoutSec * 1000));
        } catch (TimeoutError e) {
            throw new ElementOperationException("waitForClickable", selector, 
                "Element is not clickable within " + timeoutSec + " seconds: " + selector, e);
        } catch (PlaywrightException e) {
            throw new ElementOperationException("waitForClickable", selector, 
                "Failed to check element state: " + selector, e);
        }
        return this;
    }

    public PageElement waitForEditable() {
        return waitForEditable(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForEditable(int timeoutSec) {
        try {
            if (!locator().isEditable(new Locator.IsEditableOptions()
                    .setTimeout((double) timeoutSec * 1000))) {
                throw new ElementOperationException("waitForEditable", selector, 
                    "Element is not editable: " + selector, null);
            }
        } catch (PlaywrightException e) {
            throw new ElementOperationException("waitForEditable", selector, 
                "Failed to check element state: " + selector, e);
        }
        return this;
    }

    public PageElement waitForEnabled() {
        return waitForEnabled(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForEnabled(int timeoutSec) {
        try {
            if (!locator().isEnabled(new Locator.IsEnabledOptions()
                    .setTimeout((double) timeoutSec * 1000))) {
                throw new ElementOperationException("waitForEnabled", selector, 
                    "Element is not enabled: " + selector, null);
            }
        } catch (PlaywrightException e) {
            throw new ElementOperationException("waitForEnabled", selector, 
                "Failed to check element state: " + selector, e);
        }
        return this;
    }

    public PageElement waitForDisabled() {
        return waitForDisabled(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForDisabled(int timeoutSec) {
        try {
            if (locator().isEnabled(new Locator.IsEnabledOptions()
                    .setTimeout((double) timeoutSec * 1000))) {
                throw new ElementOperationException("waitForDisabled", selector, 
                    "Element is not disabled (still enabled): " + selector, null);
            }
        } catch (PlaywrightException e) {
            throw new ElementOperationException("waitForDisabled", selector, 
                "Failed to check element state: " + selector, e);
        }
        return this;
    }

    public PageElement waitForChecked() {
        return waitForChecked(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForChecked(int timeoutSec) {
        try {
            if (!locator().isChecked(new Locator.IsCheckedOptions()
                    .setTimeout((double) timeoutSec * 1000))) {
                throw new ElementOperationException("waitForChecked", selector, 
                    "Element is not checked: " + selector, null);
            }
        } catch (PlaywrightException e) {
            throw new ElementOperationException("waitForChecked", selector, 
                "Failed to check element state: " + selector, e);
        }
        return this;
    }

    public PageElement waitForNotChecked() {
        return waitForNotChecked(getDefaultTimeoutMs() / 1000);
    }

    public PageElement waitForNotChecked(int timeoutSec) {
        try {
            if (locator().isChecked(new Locator.IsCheckedOptions()
                    .setTimeout((double) timeoutSec * 1000))) {
                throw new ElementOperationException("waitForNotChecked", selector, 
                    "Element is checked (expected not checked): " + selector, null);
            }
        } catch (PlaywrightException e) {
            throw new ElementOperationException("waitForNotChecked", selector, 
                "Failed to check element state: " + selector, e);
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
        }, "dispatchEventWithArg");
        return this;
    }

    // ==================== Hover / Focus / Check / Scroll ====================
    /**
     * 将元素滚动到可视区域（使用 JS 实现）
     */
    public PageElement scrollIntoView() {
        executeWithRetry(() -> {
            locator().evaluate("el => el.scrollIntoView({ behavior: 'instant', block: 'center' })");
            return true;
        }, "scrollIntoView");
        return this;
    }

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
        } catch (PlaywrightException e) {
            logger.error("screenshot failed: {}", selector, e);
            return null;
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
    /**
     * 元素定位器健康度检查。
     * 快速判断 Locator 是否仍然有效（定位符对应的 DOM 未发生结构性变化）。
     * count() >= 0 表示定位符仍可正常解析（即使返回 0 个匹配也是"健康"的，只是元素不存在）。
     *
     * @return true 表示 Locator 健康可用，false 表示 Locator 已失效（如页面已关闭或选择器语法错误）
     */
    public boolean isHealthy() {
        try {
            return locator().count() >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 安全获取元素边界框。Playwright 的 boundingBox() 自带自动等待（元素可见+稳定），
     * 不需要额外调用 waitForVisible()。
     */
    public BoundingBox getBoundingBoxSafe() {
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
    /**
     * 基于当前元素的 Locator 创建子元素定位器。
     * 使用 Playwright Locator.locator() 链式定位，而非字符串拼接 ">>"，
     * 避免无限嵌套导致的选择器过长/解析错误。
     */
    public PageElement child(String childSelector) {
        Objects.requireNonNull(childSelector, "childSelector must not be null");
        String clean = childSelector.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("childSelector must not be blank");
        }
        // 使用 Locator.locator() 实现真正的嵌套定位，而非字符串拼接
        return new ChildPageElement(selector, clean, page);
    }

    public PageElement child(String childSelector, int index) {
        return new PageElement(child(childSelector).getSelector() + " >> nth=" + index, page);
    }

    /**
     * 内部类：通过 Locator.locator() 实现嵌套定位，避免选择器字符串无限拼接。
     */
    private static final class ChildPageElement extends PageElement {
        private final String parentSelector;
        private final String childSelector;

        ChildPageElement(String parentSelector, String childSelector, BasePage page) {
            // 父类 selector 仅作为标识符使用，实际定位通过 locator() 的嵌套 Locator 实现
            super("parent[" + parentSelector + "] >> child[" + childSelector + "]", page);
            this.parentSelector = parentSelector;
            this.childSelector = childSelector;
        }

        @Override
        public String getSelector() {
            // 返回描述性字符串，与 locator() 行为一致（Locator.locator() 嵌套定位）
            return "parent[" + parentSelector + "] >> child[" + childSelector + "]";
        }

        @Override
        public Locator locator() {
            // 不使用父类缓存，直接用 Locator.locator() 实现真正的 DOM 层级定位
            return getPage().locator(parentSelector).locator(childSelector);
        }
    }

    @Override
    public String toString() {
        return "PageElement[" + selector + "]";
    }
}