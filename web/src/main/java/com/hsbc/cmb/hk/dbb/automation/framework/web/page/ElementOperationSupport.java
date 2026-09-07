package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementNotFoundException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementOperationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * 元素操作的「执行模板 + 重试 + 诊断 + 错误构建」横切支撑（包级私有）。
 *
 * <p>从 {@link PageElement} 下沉：元素动作 / 取值类方法只描述「做什么」，
 * 而异常翻译、Playwright 等待失败诊断、失败截图、详细错误信息构建等
 * 企业级错误处理横切逻辑统一收口于此，使 {@link PageElement} 退化为
 * 「能力门面 + 薄委托」，公开 API 零变更。
 *
 * <p>日志路由回 {@link PageElement} 的 logger（同名），保持生产溯源一致；
 * 全部为静态方法、不持有任何实例状态，线程安全。
 */
final class ElementOperationSupport {

    private static final Logger logger = LoggerFactory.getLogger(PageElement.class);

    private ElementOperationSupport() {
    }

    // ==================== Safe Execution Template ====================
    /**
     * 安全的 Locator 操作执行模板——统一处理 Playwright 异常转换 + 自动诊断收集。
     * 失败时自动收集 DOM 诊断信息并捕获截图（经 {@link #captureDiagnosticsAndLog}）。
     */
    static <T> T executeSafely(Supplier<Locator> locatorFn, String selector,
                               BasePage page, Supplier<T> action, String operation) {
        try {
            return action.get();
        } catch (TimeoutError e) {
            ElementNotFoundException ex = new ElementNotFoundException(selector, e);
            captureDiagnosticsAndLog(locatorFn, selector, page, operation, ex);
            throw ex;
        } catch (PlaywrightException e) {
            ElementOperationException ex = new ElementOperationException(operation, selector,
                "Failed: " + operation, e);
            captureDiagnosticsAndLog(locatorFn, selector, page, operation, ex);
            throw ex;
        }
    }

    /**
     * 失败时自动收集诊断信息 + 截图（executeSafely 的失败路径）。
     * 这里只收集基础诊断信息用于快速定位问题，不做完整的 DOM 上下文分析。
     */
    private static void captureDiagnosticsAndLog(Supplier<Locator> locatorFn, String selector,
                                                 BasePage page, String operation, RuntimeException ex) {
        try {
            ElementDiagnosticsCollector diagnostics = new ElementDiagnosticsCollector(
                    locatorFn.get(), selector, page.getPageRaw(), page.getCurrentFrame());
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
     * 带成功检查的重试机制（无 testName 重载）。
     *
     * <p>Playwright 的动作类 API（click / fill / check / hover / dispatchEvent 等）已内置
     * actionability 自动等待（attached→visible→stable→enabled→receives events），
     * 并在其超时窗口内持续探测；框架再做「sleep + 轮询」式重试会与 Playwright 原生等待叠加，
     * 引入额外延迟与抖动，违背 Playwright 的设计哲学。故此处仅单次执行动作，失败即收集诊断并抛出。
     */
    static void executeWithRetry(Supplier<Locator> locatorFn, String selector,
                                 BasePage page, Supplier<Boolean> action, String operation) {
        executeWithRetry(locatorFn, selector, page, action, operation, null);
    }

    static void executeWithRetry(Supplier<Locator> locatorFn, String selector,
                                 BasePage page, Supplier<Boolean> action,
                                 String operation, String testName) {
        try {
            action.get();
        } catch (RuntimeException e) {
            Exception lastEx = e;
            ElementDiagnosticsCollector diagnostics = new ElementDiagnosticsCollector(
                    locatorFn.get(), selector, page.getPageRaw(), page.getCurrentFrame());
            ElementOperationException.DiagnosticInfo info = diagnostics.collect();
            info.retryCount(1);

            String elementState = determineElementState(info);
            String customMessage = buildDetailedErrorMessage(operation, lastEx, diagnostics,
                    0, elementState, selector);

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
    }

    private static String determineElementState(ElementOperationException.DiagnosticInfo diag) {
        if (!diag.existsInDom()) return "NOT_FOUND_IN_DOM";
        if (!diag.isVisible()) return "NOT_VISIBLE";
        if (!diag.isEnabled()) return "NOT_ENABLED";
        if (!diag.isEditable()) return "NOT_EDITABLE";
        return "INTERACTABLE_BUT_FAILED";
    }

    private static String buildDetailedErrorMessage(String operation, Exception lastEx,
            ElementDiagnosticsCollector dc, int maxRetry, String elementState, String selector) {
        // 简洁一行格式 — 详细诊断信息（DOM context, HTML snippet 等）可到 Serenity 报告查看
        String cause = lastEx instanceof TimeoutError ? "TimeoutError"
            : (lastEx != null ? lastEx.getClass().getSimpleName() : "unknown");
        return String.format("[%s] %s failed after %d attempts on '%s' | page=%s title=%s obstruction=%s | cause=%s",
            elementState, operation, maxRetry + 1, selector,
            dc.getPageUrl(), dc.getPageTitle(), dc.getObstructingElements(), cause);
    }

    private static void captureFailureAndLog(String operation, String testName,
                                             ElementOperationException ex,
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
}
