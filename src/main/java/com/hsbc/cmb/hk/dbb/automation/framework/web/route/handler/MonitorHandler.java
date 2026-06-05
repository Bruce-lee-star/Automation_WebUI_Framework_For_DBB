package com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.MonitorCallback;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteAsyncPool;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SerenityReporter;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.jayway.jsonpath.JsonPath;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API 监控 Handler — 在 Playwright 事件线程中同步读取响应 body，
 * 拷贝 byte[] 后交给 RouteAsyncPool 异步执行断言和报告记录。
 *
 * <p>关键设计原则：
 * <ul>
 *   <li>response.body() 在 Playwright 事件线程同步调用（线程安全）</li>
 *   <li>byte[] 拷贝后传给异步线程，避免跨线程访问 Response 对象</li>
 *   <li>断言结果通过 {@link ApiCaptureContext} 通知测试生命周期</li>
 *   <li>失败详情（URL、类型、预期值、实际值）记录到上下文供测试结束报告</li>
 *   <li>Serenity 报告写入通过 {@link SerenityReporter} 统一处理</li>
 *   <li>route.resume() 包裹 try-catch，避免单请求失败导致整个路由崩溃</li>
 * </ul>
 */
public class MonitorHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorHandler.class);

    // ═══════════════════════════════════════════════════════════════
    // ⭐ 性能优化：JsonPath 编译缓存（避免每次断言都重新编译表达式）
    // ═══════════════════════════════════════════════════════════════
    private static final Map<String, JsonPath> JSONPATH_CACHE = new ConcurrentHashMap<>();
    private static final int JSONPATH_CACHE_MAX = 200;

    /**
     * 处理单个 route 的监控逻辑（带断言）。
     *
     * <p><b>⭐⭐⭐ 重要架构变更 — 同步断言 + Fail-Fast</b>：
     * <ul>
     *   <li>断言（状态码 / JSONPath）在 Playwright 事件线程上<b>同步执行</b>，
     *       不再提交到 RouteAsyncPool 异步线程</li>
     *   <li>断言失败 → 调用 {@code context.signalFailFast()} 中断主测试线程，
     *       主线程当前阻塞的 Playwright IO 操作立即感知中断，Step 即刻失败</li>
     *   <li>响应体存储、CapturedApiCall 快照、Serenity 报告记录仍提交到
     *       RouteAsyncPool 异步执行（繁重操作不阻塞事件线程）</li>
     * </ul>
     */
    public static void handle(Route route, RouteRule rule) {
        // 获取 API 监控上下文并增加活动请求计数
        ApiCaptureContext context = ApiCaptureContext.getCurrent();

        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[MonitorHandler] ── handle() START: pattern='{}', expectStatus={}, jsonPathAssertions={} ──",
                rule.getUrlPattern(), rule.getExpectedStatus(),
                rule.getJsonPathAssertions() != null ? rule.getJsonPathAssertions().size() : 0);

        // 放行请求（异常安全，不阻塞页面）
        try {
            route.resume();
        } catch (PlaywrightException e) {
            LOGGER.error("[MonitorHandler] Failed to resume route for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage(), e);
            return;
        }

        // 在 Playwright 事件线程中同步读取 Response body（线程安全）
        Request req = route.request();
        Response res = req.response();
        if (res == null) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[MonitorHandler] No response available for pattern '{}', url='{}'",
                    rule.getUrlPattern(), req.url());
            return;
        }

        byte[] bodyBytes;
        try {
            bodyBytes = res.body();
        } catch (Exception e) {
            LOGGER.debug("[MonitorHandler] Failed to read response body for {}: {}", req.url(), e.getMessage());
            LoggingConfigUtil.logWarnIfVerbose(LOGGER,
                    "[MonitorHandler] Cannot read response body: pattern='{}', url='{}', error='{}'",
                    rule.getUrlPattern(), req.url(), e.getMessage());
            return;
        }

        // ═══════════════════════════════════════════════════════════════
        // ⭐⭐⭐ 同步断言：在 Playwright 事件线程上立即执行
        // ═══════════════════════════════════════════════════════════════
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        String url = req.url();
        int status = res.status();
        String urlPattern = rule.getUrlPattern();

        LOGGER.info("[MonitorHandler] Captured: url={}, status={}, bodyLength={}, pattern='{}'",
                RouteUtil.sanitizeUrl(url), status, body.length(), urlPattern);

        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[MonitorHandler] Response headers: {}", snapshotHeadersSafely(res.headers()));
        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[MonitorHandler] Response body (first 500 chars): {}",
                body.length() > 500 ? body.substring(0, 500) + "..." : body);

        // 同步执行断言 — 失败立即中断测试线程（不等待异步任务）
        boolean assertionsPassed = executeAssertions(rule, url, status, body, context);
        if (!assertionsPassed) {
            LoggingConfigUtil.logErrorIfVerbose(LOGGER,
                    "[MonitorHandler] ═══ ASSERTIONS FAILED: pattern='{}', url='{}' ═══", urlPattern, url);
            // ═══ (existing fail-fast code unchanged) ═══
            // ⭐⭐⭐ 双路径 Fail-Fast：
            //   Path 1: thread.interrupt() — 最佳努力，依赖 Playwright 内部响应
            //   Path 2: 异步关闭 Page — 100% 可靠，Playwright page.close()
            //           会立即中断主线程上所有 pending 操作（包括 waitForSelector）
            //           异步提交到 RouteAsyncPool 执行，避免 CDP 重入
            context.signalFailFast();

            final com.microsoft.playwright.Page failPage = route.request().frame().page();
            RouteAsyncPool.run(() -> {
                try {
                    LOGGER.warn("[MonitorHandler] Closing page to force abort main thread Playwright operations");
                    failPage.close();
                } catch (Exception e) {
                    LOGGER.debug("[MonitorHandler] Page close after assertion failure: {}", e.getMessage());
                }
            });

            // ⭐ 抛出 ApiAssertionException，dispatchRoute 捕获后记录
            throw new RouteException.ApiAssertionException(
                    urlPattern, "ASSERTION",
                    rule.getExpectedStatus() != null ? String.valueOf(rule.getExpectedStatus()) : "N/A",
                    String.valueOf(status));
        }

        // ═══════════════════════════════════════════════════════════════
        // 异步任务前置工作：在 Playwright 事件线程上提前快照跨线程数据
        // ═══════════════════════════════════════════════════════════════
        // ⭐ 性能优化：传递已解码的 body String（不可变，线程安全），
        //    避免异步池中重复执行 new String(byte[], charset)
        final String fBody = body;
        final ApiCaptureContext fContext = context;
        final String fAsyncUrl = req.url();
        final int fAsyncStatus = res.status();
        final String fReqMethod = req.method();
        // ⭐ 性能优化：复用头部快照（不再为日志和异步任务分别拷贝）
        final Map<String, String> fReqHeaders = snapshotHeadersSafely(req.headers());
        final Map<String, String> fResHeaders = snapshotHeadersSafely(res.headers());

        fContext.incrementActiveRequests();

        RouteAsyncPool.run(() -> {
            try {
                // ⭐ 直接使用已解码的 String，避免重复 new String(byte[], charset)
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[MonitorHandler] Async storage START: pattern='{}', url='{}', status={}, bodyLen={}",
                        urlPattern, fAsyncUrl, fAsyncStatus, fBody.length());

                // 存储 response body（向后兼容）
                fContext.storeResponse(urlPattern, fBody);

                // 存储完整的 CapturedApiCall（含 headers 等完整信息）
                CapturedApiCall call = new CapturedApiCall(
                        urlPattern,     // 存储 key = 用户配置的 urlPattern
                        fReqMethod,
                        fReqHeaders,
                        fAsyncStatus,
                        fResHeaders,
                        fBody,
                        System.currentTimeMillis(),
                        fAsyncUrl      // 实际请求 URL，用于毫秒级精确检索
                );
                fContext.storeApiCall(call);

                // 记录到 Serenity 报告
                if (rule.isRecord()) {
                    SerenityReporter.recordApiOperation("MONITOR", fAsyncUrl,
                            String.format("Status: %d\nBody: %s", fAsyncStatus,
                                    fBody.length() > 2000 ? fBody.substring(0, 2000) + "..." : fBody));
                }

                // 通知 RouteEngine 完成一次匹配（触发 auto-stop / minMatches 检查）
                RouteEngine.onMonitorMatch(rule);

                // ═══════════════════════════════════════════════════════════════
                // 执行用户注册的 Monitor 响应回调
                // ═══════════════════════════════════════════════════════════════
                invokeCallbacks(rule, fAsyncUrl, fAsyncStatus, fBody,
                        fResHeaders, fReqMethod);

                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[MonitorHandler] Async storage DONE: pattern='{}', url='{}'", urlPattern, fAsyncUrl);

            } catch (Exception e) {
                LOGGER.error("[MonitorHandler] Error in async storage: {}", e.getMessage(), e);
            } finally {
                fContext.decrementActiveRequests();
            }
        });
    }

    /**
     * 执行 RouteRule 中配置的断言（状态码 + JSONPath），
     * 失败时通过 {@code context} 记录详细信息。
     *
     * @param rule    路由规则
     * @param url     请求 URL
     * @param status  HTTP 状态码
     * @param body    响应 body
     * @param context ApiCaptureContext（可为 null）
     * @return true 所有断言通过，false 有断言失败
     */
    private static boolean executeAssertions(RouteRule rule, String url, int status,
                                              String body, ApiCaptureContext context) {
        boolean allPassed = true;

        // 状态码断言
        Integer expectedStatus = rule.getExpectedStatus();
        if (expectedStatus != null) {
            boolean statusMatch = (status == expectedStatus);
            if (!statusMatch) {
                LOGGER.warn("[MonitorHandler] Status assertion failed for {}: expected={}, actual={}",
                        url, expectedStatus, status);
                if (context != null) {
                    context.recordAssertionFailure(url, "STATUS",
                            String.valueOf(expectedStatus), String.valueOf(status),
                            null);
                }
                allPassed = false;
            } else {
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[MonitorHandler] Status assertion PASSED: {}, expected={}, actual={}",
                        url, expectedStatus, status);
            }
        }

        // JSONPath 断言（使用缓存编译）
        Map<String, Object> jsonPathAssertions = rule.getJsonPathAssertions();
        if (jsonPathAssertions != null && !jsonPathAssertions.isEmpty()) {
            for (Map.Entry<String, Object> entry : jsonPathAssertions.entrySet()) {
                String jsonPathExpr = entry.getKey();
                try {
                    // ⭐ 从缓存获取或编译 JsonPath（避免每次重新编译）
                    JsonPath compiled = getOrCompileJsonPath(jsonPathExpr);
                    Object actual = compiled.read(body);
                    boolean match = compareValues(actual, entry.getValue());
                    if (!match) {
                        String actualStr = actual != null ? actual.toString() : "null";
                        LOGGER.warn("[MonitorHandler] JSONPath assertion failed for {}: path={}, expected={}, actual={}",
                                url, jsonPathExpr, entry.getValue(), actualStr);
                        if (context != null) {
                            context.recordAssertionFailure(url, "JSONPATH",
                                    entry.getValue() != null ? entry.getValue().toString() : "null",
                                    actualStr,
                                    "path=" + jsonPathExpr);
                        }
                        allPassed = false;
                    } else {
                        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                                "[MonitorHandler] JSONPath assertion PASSED: {}, path='{}', expected='{}', actual='{}'",
                                url, jsonPathExpr, entry.getValue(), actual);
                    }
                } catch (Exception e) {
                    LOGGER.warn("[MonitorHandler] JSONPath evaluation error for {}: path={}, error={}",
                            url, jsonPathExpr, e.getMessage(), e);
                    if (context != null) {
                        context.recordAssertionFailure(url, "JSONPATH",
                                entry.getValue() != null ? entry.getValue().toString() : "null",
                                "ERROR",
                                "path=" + jsonPathExpr + ", error=" + e.getMessage());
                    }
                    allPassed = false;
                }
            }
        }

        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[MonitorHandler] executeAssertions RESULT: allPassed={}, url={}, pattern='{}'",
                allPassed, url, rule.getUrlPattern());
        return allPassed;
    }

    /**
     * 从缓存获取或编译 JsonPath 表达式（伪 LRU 容量保护）。
     */
    private static JsonPath getOrCompileJsonPath(String expression) {
        JsonPath cached = JSONPATH_CACHE.get(expression);
        if (cached != null) return cached;

        // ⭐ #7 伪 LRU：超限时移除 ~25% 条目（避免全量清空）
        if (JSONPATH_CACHE.size() >= JSONPATH_CACHE_MAX) {
            evictOldestQuarter(JSONPATH_CACHE);
        }
        return JSONPATH_CACHE.computeIfAbsent(expression, JsonPath::compile);
    }

    /**
     * ⭐ #7 伪 LRU 淘汰：从 ConcurrentHashMap 中移除约 25% 的条目。
     */
    private static void evictOldestQuarter(Map<?, ?> map) {
        int evictCount = Math.max(1, map.size() / 4);
        Iterator<?> it = map.keySet().iterator();
        for (int i = 0; i < evictCount && it.hasNext(); i++) {
            it.next();
            it.remove();
        }
    }

    /**
     * 值比较（支持 Number 类型的松散比较）
     */
    private static boolean compareValues(Object actual, Object expected) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;

        if (actual instanceof Number && expected instanceof Number) {
            boolean match = ((Number) actual).doubleValue() == ((Number) expected).doubleValue();
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[MonitorHandler] compareValues (Number): actual={}, expected={}, match={}",
                    ((Number) actual).doubleValue(), ((Number) expected).doubleValue(), match);
            return match;
        }

        boolean match = actual.toString().equals(expected.toString());
        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[MonitorHandler] compareValues (String): actual='{}', expected='{}', match={}",
                actual.toString(), expected.toString(), match);
        return match;
    }

    /**
     * 安全快照 Playwright headers 对象（避免跨线程访问）。
     * 复制为普通 HashMap，与 Playwright 事件线程解耦。
     */
    private static Map<String, String> snapshotHeadersSafely(Map<String, String> headers) {
        if (headers == null) return null;
        try {
            return new java.util.HashMap<>(headers);
        } catch (Exception e) {
            LOGGER.warn("[MonitorHandler] Failed to snapshot headers: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 调用 RouteRule 中注册的所有 Monitor 响应回调。
     * <p>每个回调独立 try-catch，单个回调失败不影响其他回调执行。
     *
     * @param rule            路由规则
     * @param url             请求 URL
     * @param status          HTTP 状态码
     * @param body            响应体字符串
     * @param responseHeaders 响应头快照（线程安全的 Map 副本）
     * @param method          请求方法
     */
    private static void invokeCallbacks(RouteRule rule, String url, int status,
                                         String body, Map<String, String> responseHeaders,
                                         String method) {
        java.util.List<MonitorCallback> callbacks = rule.getMonitorCallbacks();
        if (callbacks == null || callbacks.isEmpty()) return;

        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[MonitorHandler] Invoking {} monitor callback(s) for pattern='{}', url='{}'",
                callbacks.size(), rule.getUrlPattern(), url);

        for (int i = 0; i < callbacks.size(); i++) {
            try {
                callbacks.get(i).onResponse(url, status, body, responseHeaders, method);
            } catch (Exception e) {
                LOGGER.error("[MonitorHandler] Monitor callback #{} failed for pattern='{}', url='{}': {}",
                        i, rule.getUrlPattern(), url, e.getMessage(), e);
            }
        }
    }

}
