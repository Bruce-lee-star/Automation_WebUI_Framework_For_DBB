package com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SerenityReporter;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Mock 响应 Handler。
 *
 * <p>两种工作模式：
 * <ul>
 *   <li><b>纯 Mock</b>（默认）：直接返回 mockBody 设置的自定义响应，不访问真实服务器</li>
 *   <li><b>拦截真实响应</b>（interceptRealResponse=true）：先 route.fetch() 获取真实响应，
 *       再应用 mockReplaceField 字段替换后 fulfill 给前端</li>
 * </ul>
 *
 * <p>安全设计：
 * <ul>
 *   <li>mockBody 为 null 时设为默认空字符串 ""，避免 Playwright 空指针</li>
 *   <li>mockStatus 合法性校验（100 ≤ status &lt; 600），非法时 fallback 到 200</li>
 *   <li>route.fulfill() 包裹 try-catch，失败时 resume 兜底，避免请求永久挂起</li>
 * </ul>
 */
public class MockHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MockHandler.class);

    public static void handle(Route route, RouteRule rule) {
        String url = route.request().url();
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[MockHandler] ── handle() START: pattern='{}', url='{}', mockStatus={}, replaceFields={}, interceptRealResponse={} ──",
                rule.getUrlPattern(), url, rule.getMockStatus(),
                rule.getMockReplaceFields() != null ? rule.getMockReplaceFields().size() : 0,
                rule.isInterceptRealResponse());

        // ═══ 拦截真实响应模式：route.fetch() → 修改 → fulfill ═══
        if (rule.isInterceptRealResponse()) {
            handleInterceptRealResponse(route, rule, url);
            return;
        }

        // ── 1. 状态码校验与 fallback ──────────────────────────────
        int status = rule.getMockStatus();
        if (status < 100 || status >= 600) {
            LOGGER.warn("[MockHandler] Invalid mock status: {} for pattern '{}', using 200 instead",
                    status, rule.getUrlPattern());
            status = 200;
        }

        // ── 2. 响应体处理 — 纯 Mock 模式直接用 mockBody ───────────
        String body = rule.getMockBody();
        if (body == null) {
            body = "";
            LOGGER.debug("[MockHandler] mockBody is null for pattern '{}', using empty string",
                    rule.getUrlPattern());
        }

        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[MockHandler] Body prepared: pattern='{}', bodyLen={}",
                rule.getUrlPattern(), body.length());

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

        // ── 6. 返回 Mock 响应（异常安全，失败时 resume 兜底）──────
        try {
            route.fulfill(opts);
            LOGGER.info("[MockHandler] Fulfilled: url={}, pattern='{}', status={}, bodyLength={}",
                    RouteUtil.sanitizeUrl(url), rule.getUrlPattern(), status, body.length());
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
                        System.currentTimeMillis(),
                        url    // 实际请求 URL，用于毫秒级精确检索
                );
                ApiCaptureContext ctx = ApiCaptureContext.getCurrent();
                ctx.storeApiCall(call);
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[MockHandler] Stored to ApiCaptureContext: endpoint='{}', method={}, status={}",
                        rule.getUrlPattern(), method, status);
            } catch (Exception e) {
                LOGGER.debug("[MockHandler] Failed to store mock call to ApiCaptureContext: {}", e.getMessage());
            }
        } catch (PlaywrightException e) {
            LOGGER.error("[MockHandler] Failed to fulfill route for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage(), e);
            // 兜底：fulfill 失败时 resume 放行请求，避免请求永久挂起
            try { route.resume(); } catch (Exception ignored) {
                LOGGER.error("[MockHandler] Failed to resume route after fulfill failure for pattern '{}'",
                        rule.getUrlPattern());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 真实响应拦截模式（route.fetch() → 修改 → fulfill）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 拦截真实 API 响应：先通过 {@code route.fetch()} 从真实服务器获取响应，
     * 再应用 {@code mockReplaceFields} 替换字段，最后 {@code route.fulfill()} 返回前端。
     *
     * <p>与纯 Mock 模式（全程不访问真实服务器）不同，此模式保证请求到达服务器，
     * 返回的是<b>基于真实响应修改后</b>的结果，状态码和响应头也来自真实服务器。
     *
     * @param route Playwright Route 对象
     * @param rule  路由规则
     * @param url   请求 URL（已缓存，避免重复 JNI 调用）
     */
    private static void handleInterceptRealResponse(Route route, RouteRule rule, String url) {
        LOGGER.info("[MockHandler] Intercepting real response: pattern='{}', url='{}'",
                rule.getUrlPattern(), url);

        Map<String, Object> replaceFields = rule.getMockReplaceFields();
        boolean hasReplaceFields = replaceFields != null && !replaceFields.isEmpty();

        try {
            // ── 1. route.fetch() — 真实发送请求到服务器，获取真实响应 ──
            //    无参 fetch 默认继承原请求的 method/headers/cookies
            APIResponse realResp = route.fetch();
            int status = realResp.status();
            byte[] bodyBytes = realResp.body();
            String body = bodyBytes != null ? new String(bodyBytes) : "";

            LOGGER.info("[MockHandler] Real response fetched: pattern='{}', status={}, bodyLength={}",
                    rule.getUrlPattern(), status, body.length());
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[MockHandler] Real response body (first 500 chars): {}",
                    body.length() > 500 ? body.substring(0, 500) + "..." : body);

            // ── 2. 合并响应头（真实响应头 + 用户自定义 mockHeaders）──
            Map<String, String> respHeaders = new HashMap<>(realResp.headers());
            if (rule.getMockHeaders() != null) {
                respHeaders.putAll(rule.getMockHeaders());
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[MockHandler] Merged {} custom mock header(s): {}",
                        rule.getMockHeaders().size(), rule.getMockHeaders().keySet());
            }

            // ── 3. 应用字段替换（对真实响应体执行通配符批量替换）─────
            if (hasReplaceFields && !body.isEmpty()) {
                try {
                    LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                            "[MockHandler] Applying {} replace field(s) to real response: {}",
                            replaceFields.size(), replaceFields.keySet());
                    body = ModifyHandler.replaceBatchByWildcard(body, replaceFields);
                    LOGGER.info("[MockHandler] Applied {} replace field(s) to real response for pattern '{}'",
                            replaceFields.size(), rule.getUrlPattern());
                } catch (Exception e) {
                    LOGGER.warn("[MockHandler] Failed to apply replace fields to real response for pattern '{}': {}",
                            rule.getUrlPattern(), e.getMessage());
                    // 替换失败不阻塞 — 使用原始真实响应体
                }
            }

            // ── 4. fulfill 修改后的响应给前端 ───────────────────────
            Route.FulfillOptions opts = new Route.FulfillOptions()
                    .setStatus(status)
                    .setBody(body);
            if (!respHeaders.isEmpty()) {
                opts.setHeaders(respHeaders);
            }

            route.fulfill(opts);
            LOGGER.info("[MockHandler] Fulfilled modified real response: url={}, pattern='{}', status={}, bodyLength={}",
                    RouteUtil.sanitizeUrl(url), rule.getUrlPattern(), status, body.length());

            // ── 5. 存储到 ApiCaptureContext ─────────────────────────
            storeInterceptedCall(route, rule, url, status, respHeaders, body);

        } catch (PlaywrightException e) {
            LOGGER.error("[MockHandler] Failed to intercept real response for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage(), e);
            // 兜底：fetch/fulfill 失败时 resume 放行，避免请求永久挂起
            try { route.resume(); } catch (Exception ignored) {
                LOGGER.error("[MockHandler] Failed to resume after intercept failure for pattern '{}'",
                        rule.getUrlPattern());
            }
        }
    }

    /** 将被拦截修改后的调用存入 ApiCaptureContext。 */
    private static void storeInterceptedCall(Route route, RouteRule rule, String url,
                                              int status, Map<String, String> respHeaders, String body) {
        try {
            String method = route.request().method();
            CapturedApiCall call = new CapturedApiCall(
                    rule.getUrlPattern(),
                    method,
                    null,
                    status,
                    respHeaders,
                    body,
                    System.currentTimeMillis(),
                    url
            );
            ApiCaptureContext ctx = ApiCaptureContext.getCurrent();
            ctx.storeApiCall(call);
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[MockHandler] Stored intercepted call to ApiCaptureContext: endpoint='{}', method={}, status={}",
                    rule.getUrlPattern(), method, status);
        } catch (Exception e) {
            LOGGER.debug("[MockHandler] Failed to store intercepted call to ApiCaptureContext: {}", e.getMessage());
        }
    }
}
