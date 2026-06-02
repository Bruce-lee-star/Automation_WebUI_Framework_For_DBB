package com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SerenityReporter;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Mock 响应 Handler — 拦截请求，直接返回自定义响应。
 *
 * <p>核心改进：
 * <ul>
 *   <li>mockBody 为 null 时设置为默认空字符串 ""，避免 Playwright 空指针</li>
 *   <li>mockStatus 合法性校验（100 ≤ status &lt; 600），非法时 fallback 到 200</li>
 *   <li>route.fulfill() 包裹 try-catch，避免单请求失败导致整个路由崩溃</li>
 *   <li><b>批量字段替换</b>：支持通过 {@code mockReplaceField()} 对 Mock JSON body
 *       进行通配符批量替换（如 {@code $[*].name}、{@code $.users[*].orders[*].price}）</li>
 * </ul>
 */
public class MockHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MockHandler.class);

    public static void handle(Route route, RouteRule rule) {
        String url = route.request().url();
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[MockHandler] ── handle() START: pattern='{}', url='{}', mockStatus={}, replaceFields={} ──",
                rule.getUrlPattern(), url, rule.getMockStatus(),
                rule.getMockReplaceFields() != null ? rule.getMockReplaceFields().size() : 0);

        // ── 1. 状态码校验与 fallback ──────────────────────────────
        int status = rule.getMockStatus();
        if (status < 100 || status >= 600) {
            LOGGER.warn("[MockHandler] Invalid mock status: {} for pattern '{}', using 200 instead",
                    status, rule.getUrlPattern());
            status = 200;
        }

        // ── 2. 响应体默认值处理 ────────────────────────────────────
        String body = rule.getMockBody();
        if (body == null) {
            LOGGER.debug("[MockHandler] mockBody is null for pattern '{}', using empty string",
                    rule.getUrlPattern());
            body = "";
        }

        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[MockHandler] Body prepared: pattern='{}', bodyLen={}, hasReplaceFields={}",
                rule.getUrlPattern(), body.length(),
                rule.getMockReplaceFields() != null && !rule.getMockReplaceFields().isEmpty());

        // ── 3. 批量字段替换（支持通配符 [*]）────────────────────────
        Map<String, String> replaceFields = rule.getMockReplaceFields();
        if (replaceFields != null && !replaceFields.isEmpty() && !body.isEmpty()) {
            try {
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[MockHandler] Applying {} replace field(s): {}",
                        replaceFields.size(), replaceFields.keySet());
                body = ModifyHandler.replaceBatchByWildcard(body, replaceFields);
                LOGGER.debug("[MockHandler] Applied {} mock replace fields for pattern '{}'",
                        replaceFields.size(), rule.getUrlPattern());
            } catch (Exception e) {
                LOGGER.warn("[MockHandler] Failed to apply mock replace fields for pattern '{}': {}",
                        rule.getUrlPattern(), e.getMessage());
            }
        }

        // ── 4. 构建响应选项 ───────────────────────────────────────
        Route.FulfillOptions opts = new Route.FulfillOptions()
                .setStatus(status)
                .setBody(body);

        // ── 5. 附带自定义响应头 ────────────────────────────────────
        if (rule.getMockHeaders() != null) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[MockHandler] Including {} custom mock header(s): {}",
                    rule.getMockHeaders().size(), rule.getMockHeaders().keySet());
            opts.setHeaders(new java.util.HashMap<>(rule.getMockHeaders()));
        }

        // ── 6. 返回 Mock 响应（异常安全）───────────────────────────
        try {
            route.fulfill(opts);
            LOGGER.info("[MockHandler] Fulfilled: url={}, pattern='{}', status={}, bodyLength={}",
                    url, rule.getUrlPattern(), status, body.length());
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[MockHandler] Mock body content (first 500 chars): {}",
                    body.length() > 500 ? body.substring(0, 500) + "..." : body);
            SerenityReporter.recordApiOperation("MOCK", url,
                    String.format("Pattern: %s\nStatus: %d\nBody: %s",
                            rule.getUrlPattern(), status,
                            body.length() > 500 ? body.substring(0, 500) + "..." : body));

            // ── 7. 存储 Mock 调用到 ApiCaptureContext ───────────────
            try {
                String method = route.request().method();
                CapturedApiCall call = new CapturedApiCall(
                        rule.getUrlPattern(),
                        method,
                        null,  // Mock 场景无请求头
                        status,
                        rule.getMockHeaders(),  // Mock 自定义响应头
                        body,
                        System.currentTimeMillis()
                );
                ApiCaptureContext.getCurrent().storeApiCall(call);
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[MockHandler] Stored to ApiCaptureContext: endpoint='{}', method={}, status={}",
                        rule.getUrlPattern(), method, status);
            } catch (Exception e) {
                LOGGER.debug("[MockHandler] Failed to store mock call to ApiCaptureContext: {}", e.getMessage());
            }
        } catch (PlaywrightException e) {
            LOGGER.error("[MockHandler] Failed to fulfill route for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage(), e);
        }
    }
}
