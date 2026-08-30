package com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ConditionalFieldRule;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SerenityReporter;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
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

    /** route.fetch() 的默认超时（毫秒），可用环境变量 ROUTE_FETCH_TIMEOUT_MS 覆盖 */
    private static final double ROUTE_FETCH_TIMEOUT_MS =
            RouteUtil.getEnvDouble("ROUTE_FETCH_TIMEOUT_MS", 30000);

    public static void handle(Route route, RouteRule rule, long delayMs) {
        String url = route.request().url();
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[MockHandler] ── handle() START: pattern='{}', url='{}', mockStatus={}, replaceFields={}, interceptRealResponse={} ──",
                rule.getUrlPattern(), url, rule.getMockStatus(),
                rule.getMockReplaceFields() != null ? rule.getMockReplaceFields().size() : 0,
                rule.isInterceptRealResponse());

        // ═══ 页面/上下文已关闭的短路保护 ═══
        // 场景：page 或 context 已关闭/重建，但 route handler 仍注册（未被 unroute），
        // 此时若进入 route.fetch() 会因底层连接失效而长时间阻塞（甚至永久挂起），
        // 导致后续测试被该请求 block 住。此处检测到页面已关闭时直接 resume 放行，
        // 不执行任何 mock/拦截逻辑，避免请求悬挂。
        if (isPageClosed(route)) {
            LOGGER.warn("[MockHandler] Page/context already closed, skip handling (resume to avoid blocking): url='{}', pattern='{}'",
                    RouteUtil.sanitizeUrl(url), rule.getUrlPattern());
            RouteUtil.safeResume(route);
            return;
        }

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

        // ── 2. 响应体处理 — 优先使用二进制（byte[]），fallback 到 String ──
        byte[] bodyBytes = rule.getMockBodyBytes();
        String body;
        boolean useBytes = (bodyBytes != null);

        if (useBytes) {
            body = "[binary data]";  // 日志占位
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[MockHandler] Using binary body: pattern='{}', byteLength={}",
                    rule.getUrlPattern(), bodyBytes.length);
        } else {
            body = rule.getMockBody();
            if (body == null) {
                body = "";
                LOGGER.debug("[MockHandler] mockBody is null for pattern '{}', using empty string",
                        rule.getUrlPattern());
            }
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[MockHandler] Body prepared: pattern='{}', bodyLen={}",
                    rule.getUrlPattern(), body.length());
        }

        // ── 4. 构建响应选项 ───────────────────────────────────────
        Route.FulfillOptions opts = new Route.FulfillOptions()
                .setStatus(status);
        if (useBytes) {
            opts.setBodyBytes(bodyBytes);
        } else {
            opts.setBody(body);
        }

        // ── 5. 附带自定义响应头 ────────────────────────────────────
        // ⚠️ 跨域 mock（如 matchOrigin 跨域请求）必须带 CORS 头，否则浏览器 CORS 拦截导致 fetch 失败。
        Map<String, String> respHeaders = new HashMap<>();
        if (rule.getMockHeaders() != null) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[MockHandler] Including {} custom mock header(s): {}",
                    rule.getMockHeaders().size(), rule.getMockHeaders().keySet());
            respHeaders.putAll(rule.getMockHeaders());
        }
        respHeaders.putIfAbsent("Access-Control-Allow-Origin", "*");
        if (!respHeaders.isEmpty()) {
            opts.setHeaders(respHeaders);
        }

        // ── 6. 同步存储 Mock 调用到 ApiCaptureContext（⭐ 必须在 fulfill 之前）────
        //    ⭐ MOCK 的 route.fulfill() 不发真实网络请求，不会有真实响应到达，
        //       因此必须由本 Handler 自行落库，否则该调用在 ApiCaptureContext 中完全不可见。
        //    ⭐ 时序契约（与 ModifyHandler 一致，修复 c21 竞态）：
        //       fulfill 会立即 resolve 浏览器侧的 fetch Promise，测试代码随即被唤醒并查询
        //       getLastApiCall / waitForApi。若 store 在 fulfill 之后，查询线程完全可能
        //       先于 store 执行 —— 拿到 null 而误判"mock 未生效"。故先 store 再 fulfill。
        //    ⭐ handleType 显式标记为 MOCK：MOCK 是 terminal（短路），
        //       不产生真实网络响应，也不叠加 MONITOR 断言（见 RouteHandleType）。
        storeMockCall(route, rule, url, status, body);

        // ── 7. 返回 Mock 响应（异常安全，失败时 resume 兜底）──────
        try {
            RouteUtil.safeFulfill(route, opts);
            LOGGER.info("[MockHandler] Fulfilled: url={}, pattern='{}', status={}, bodyLength={}",
                    RouteUtil.sanitizeUrl(url), rule.getUrlPattern(), status, body.length());
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[MockHandler] Mock body content (first 500 chars): {}",
                    body.length() > 500 ? body.substring(0, 500) + "..." : body);
            SerenityReporter.recordApiOperation("MOCK", url,
                    String.format("Pattern: %s\nStatus: %d\nBody: %s",
                            rule.getUrlPattern(), status,
                            body.length() > 500 ? body.substring(0, 500) + "..." : body));
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

    /**
     * 落库纯 Mock 调用的快照（在 fulfill 之前调用，规避 c21 查询竞态）。
     */
    private static void storeMockCall(Route route, RouteRule rule, String url, int status, String body) {
        try {
            ApiCaptureContext ctx = RouteUtil.captureContext(route);
            if (ctx == null) return;
            CapturedApiCall call = new CapturedApiCall(
                    rule.getUrlPattern(),
                    route.request().method(),
                    new HashMap<>(route.request().headers()),
                    status,
                    rule.getMockHeaders() != null ? new HashMap<>(rule.getMockHeaders()) : null,
                    body,
                    System.currentTimeMillis(),
                    url,
                    route.request().postData(),
                    RouteHandleType.MOCK
            );
            ctx.storeApiCall(call);
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[MockHandler] Stored to ApiCaptureContext: endpoint='{}', method={}, status={}",
                    rule.getUrlPattern(), call.method(), status);
        } catch (Exception e) {
            LOGGER.debug("[MockHandler] Failed to store mock call to ApiCaptureContext: {}", e.getMessage());
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
        // ⭐ 修复 C-2：url 可能含 token（?token=），统一脱敏后再记录
        LOGGER.info("[MockHandler] Intercepting real response: pattern='{}', url='{}'",
                rule.getUrlPattern(), RouteUtil.sanitizeUrl(url));

        Map<String, Object> replaceFields = rule.getMockReplaceFields();
        boolean hasReplaceFields = replaceFields != null && !replaceFields.isEmpty();
        List<ConditionalFieldRule> conditionalFields = rule.getConditionalFields();
        boolean hasConditionalFields = conditionalFields != null && !conditionalFields.isEmpty();

        // 进入 fetch 前再次确认页面未关闭（避免 handle() 检查后、fetch 阻塞期间页面被关闭）
        if (isPageClosed(route)) {
            LOGGER.warn("[MockHandler] Page/context closed before fetch, resume to avoid blocking: pattern='{}', url='{}'",
                    rule.getUrlPattern(), url);
            try { route.resume(); } catch (Exception ignored) {}
            return;
        }

        // ⭐ route 生命周期契约守卫：任何异常路径（含非 PlaywrightException 的 RuntimeException）
        //    都由 finally 兜底终结，避免「已 fetch 但未终结」导致浏览器端请求永久 pending。
        boolean routeSettled = false;
        try {
            // ── 1. route.fetch() — 真实发送请求到服务器，获取真实响应 ──
            //    无参 fetch 默认继承原请求的 method/headers/cookies
            //    【优化】显式设置 fetch 超时（默认 30s，可用环境变量 ROUTE_FETCH_TIMEOUT_MS 覆盖）：
            //    Playwright 的 route.fetch() 会在【事件线程】同步等待真实服务器返回；若服务器无响应，
            //    默认会阻塞到浏览器全局超时（通常 30s+）。显式超时可避免后端慢/挂起时事件线程被长时间
            //    占住，进而拖慢同 context 后续所有请求的路由分发。
            Route.FetchOptions fetchOpts = new Route.FetchOptions()
                    .setTimeout(ROUTE_FETCH_TIMEOUT_MS);
            APIResponse realResp = route.fetch(fetchOpts);
            int status = realResp.status();
            byte[] bodyBytes = realResp.body();
            // 【优化】显式 UTF-8 解码（避免依赖平台默认 charset 导致响应体中文乱码）
            String body = bodyBytes != null ? new String(bodyBytes, StandardCharsets.UTF_8) : "";

            LOGGER.info("[MockHandler] Real response fetched: pattern='{}', status={}, bodyLength={}",
                    rule.getUrlPattern(), status, body.length());
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[MockHandler] Real response body (first 500 chars): {}",
                    body.length() > 500 ? body.substring(0, 500) + "..." : body);

            // ── 2. 应用字段替换（对真实响应体执行通配符批量替换）─────
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

            // ── 2b. 条件字段修改（仅 interceptRealResponse 模式生效）─────
            //   当响应里某 JSONPath 满足条件时才修改另一字段，不满足保留原值（不影响其它数据）。
            //   在 replaceFields 之后独立评估，多个规则可叠加。
            if (hasConditionalFields && !body.isEmpty()) {
                try {
                    LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                            "[MockHandler] Applying {} conditional field rule(s) to real response: {}",
                            conditionalFields.size(), conditionalFields);
                    body = ModifyHandler.applyConditionalFields(body, conditionalFields);
                    LOGGER.info("[MockHandler] Applied {} conditional field rule(s) to real response for pattern '{}'",
                            conditionalFields.size(), rule.getUrlPattern());
                } catch (Exception e) {
                    LOGGER.warn("[MockHandler] Failed to apply conditional fields to real response for pattern '{}': {}",
                            rule.getUrlPattern(), e.getMessage());
                    // 失败不阻塞 — 使用现有响应体
                }
            }

            // ── 3. 同步存储（⭐ 必须在 fulfill 之前，规避 c21 查询竞态）──────
            //    ⭐ 拦截真实响应模式下，请求由 route.fetch() 在 route 内部发出，
            //       浏览器侧不会为它生成独立的网络响应事件（响应由 fulfill 直接注入），
            //       因此没有任何其它通道会记录本次调用 —— 必须在此同步落库。
            //    ⭐ handleType = MOCK：最终对外响应是 fulfill 注入的（已替换字段），
            //       对前端与查询方而言这是一次 mock 响应，非真实网络响应。
            //    ⭐ 时序同纯 Mock 分支：fulfill 会立即 resolve 浏览器侧 fetch，
            //       先 store 才能保证调用方唤醒后查得到。
            storeInterceptedCall(route, rule, url, status, body, realResp.headers());

            // ── 4. fulfill 给前端 ────────────────────────────────────
            // 【对齐 Playwright】无字段替换且无自定义响应头时，直接透传真实响应：
            //   fulfill(setResponse(realResp)) 保留全部真实响应头，且 Playwright 协议层
            //   对 fetch 结果做 fetchResponseUid 优化（同连接不重复传 body）。
            boolean hasCustomHeaders = rule.getMockHeaders() != null && !rule.getMockHeaders().isEmpty();
            if (!hasReplaceFields && !hasConditionalFields && !hasCustomHeaders) {
                route.fulfill(new Route.FulfillOptions().setResponse(realResp));
                routeSettled = true;
                LOGGER.info("[MockHandler] Fulfilled real response (passthrough): url={}, pattern='{}', status={}, bodyLength={}",
                        RouteUtil.sanitizeUrl(url), rule.getUrlPattern(), status, body.length());
            } else {
                Route.FulfillOptions opts = new Route.FulfillOptions()
                        .setStatus(status)
                        .setBody(body);
                // 合并真实响应头：过滤实体头（body 已解码，content-encoding / content-length /
                // transfer-encoding 不再适用，保留会导致前端按压缩格式解析纯文本）。
                Map<String, String> respHeaders = new HashMap<>();
                for (Map.Entry<String, String> entry : realResp.headers().entrySet()) {
                    String name = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                    if ("content-encoding".equals(name) || "content-length".equals(name)
                            || "transfer-encoding".equals(name)) {
                        continue;
                    }
                    respHeaders.put(entry.getKey(), entry.getValue());
                }
                if (hasCustomHeaders) {
                    respHeaders.putAll(rule.getMockHeaders());
                    LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                            "[MockHandler] Merged {} custom mock header(s): {}",
                            rule.getMockHeaders().size(), rule.getMockHeaders().keySet());
                }
                // ⚠️ 跨域拦截（如 matchOrigin 跨域请求）补 CORS 头，避免浏览器 CORS 拦截
                respHeaders.putIfAbsent("Access-Control-Allow-Origin", "*");
                if (!respHeaders.isEmpty()) {
                    opts.setHeaders(respHeaders);
                }
                route.fulfill(opts);
                routeSettled = true;
                LOGGER.info("[MockHandler] Fulfilled modified real response: url={}, pattern='{}', status={}, bodyLength={}",
                        RouteUtil.sanitizeUrl(url), rule.getUrlPattern(), status, body.length());
            }

        } catch (PlaywrightException e) {
            LOGGER.error("[MockHandler] Failed to intercept real response for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage(), e);
            // 兜底：fetch/fulfill 失败时 resume 放行，避免请求永久挂起
            try { route.resume(); } catch (Exception ignored) {
                LOGGER.error("[MockHandler] Failed to resume after intercept failure for pattern '{}'",
                        rule.getUrlPattern());
            }
            routeSettled = true;
        } finally {
            // ⭐ 生命周期契约最终守卫：非 PlaywrightException（如字段替换逻辑抛出的
            //    RuntimeException）逃逸时 route 仍未终结，此处兜底放行。
            if (!routeSettled) {
                LOGGER.warn("[MockHandler] Route not settled on exit (runtime exception escaped), "
                        + "resuming to honor route lifecycle contract: pattern='{}'", rule.getUrlPattern());
                try { route.resume(); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * 落库「拦截真实响应后改写」的调用快照。
     *
     * <p>存储的是<b>改写后的最终响应</b>（即前端实际收到的内容），
     * 与纯 Mock 分支一致地标记为 {@link RouteHandleType#MOCK}。
     */
    private static void storeInterceptedCall(Route route, RouteRule rule, String url,
                                             int status, String body,
                                             Map<String, String> realRespHeaders) {
        try {
            ApiCaptureContext ctx = RouteUtil.captureContext(route);
            if (ctx == null) return;
            CapturedApiCall call = new CapturedApiCall(
                    rule.getUrlPattern(),
                    route.request().method(),
                    new HashMap<>(route.request().headers()),
                    status,
                    realRespHeaders != null ? new HashMap<>(realRespHeaders) : null,
                    body,
                    System.currentTimeMillis(),
                    url,
                    route.request().postData(),
                    RouteHandleType.MOCK
            );
            ctx.storeApiCall(call);
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[MockHandler] Stored intercepted call: endpoint='{}', status={}",
                    rule.getUrlPattern(), status);
        } catch (Exception e) {
            LOGGER.debug("[MockHandler] Failed to store intercepted call: {}", e.getMessage());
        }
    }

    /**
     * 判断承载该请求的页面/上下文是否已被关闭。
     *
     * <p>route 本身不持有 page 引用，但可通过
     * {@code route.request().frame().page().isClosed()} 间接获取。
     * 任一环节抛异常（如页面已释放导致对象不存在）一律按"已关闭"处理，
     * 以保守方式避免对已销毁页面执行 route.fetch() 造成长阻塞。
     *
     * @param route Playwright Route 对象
     * @return true 表示页面已关闭，handler 应直接 resume 放行
     */
    private static boolean isPageClosed(Route route) {
        return RouteUtil.isPageClosed(route);
    }
}
