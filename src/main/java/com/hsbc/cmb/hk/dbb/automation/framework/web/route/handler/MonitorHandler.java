package com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler;

import com.hsbc.cmb.hk.dbb.automation.framework.web.listener.PlaywrightListener;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiMonitorContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteAsyncPool;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SerenityReporter;
import com.jayway.jsonpath.JsonPath;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * API 监控 Handler — 在 Playwright 事件线程中同步读取响应 body，
 * 拷贝 byte[] 后交给 RouteAsyncPool 异步执行断言和报告记录。
 *
 * <p>关键设计原则：
 * <ul>
 *   <li>response.body() 在 Playwright 事件线程同步调用（线程安全）</li>
 *   <li>byte[] 拷贝后传给异步线程，避免跨线程访问 Response 对象</li>
 *   <li>断言结果通过 {@link ApiMonitorContext} 通知测试生命周期</li>
 *   <li>失败详情（URL、类型、预期值、实际值）记录到上下文供测试结束报告</li>
 *   <li>Serenity 报告写入通过 {@link SerenityReporter} 统一处理</li>
 *   <li>route.resume() 包裹 try-catch，避免单请求失败导致整个路由崩溃</li>
 * </ul>
 */
public class MonitorHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorHandler.class);

    /**
     * 处理单个 route 的监控逻辑（带断言）。
     */
    public static void handle(Route route, RouteRule rule) {
        // 获取 API 监控上下文并增加活动请求计数
        ApiMonitorContext context = PlaywrightListener.getCurrentApiMonitorContext();
        if (context != null) {
            context.incrementActiveRequests();
        }

        // 放行请求（异常安全，不阻塞页面）
        try {
            route.resume();
        } catch (PlaywrightException e) {
            LOGGER.error("[MonitorHandler] Failed to resume route for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage(), e);
            if (context != null) {
                context.decrementActiveRequests();
            }
            return;
        }

        // 在 Playwright 事件线程中同步读取 Response body（线程安全）
        Request req = route.request();
        Response res = req.response();
        if (res == null) {
            if (context != null) {
                context.decrementActiveRequests();
            }
            return;
        }

        byte[] bodyBytes;
        try {
            bodyBytes = res.body();
        } catch (Exception e) {
            LOGGER.debug("[MonitorHandler] Failed to read response body for {}: {}", req.url(), e.getMessage());
            if (context != null) {
                context.decrementActiveRequests();
            }
            return;
        }

        final byte[] fBodyBytes = bodyBytes;
        final ApiMonitorContext fContext = context;

        RouteAsyncPool.run(() -> {
            try {
                String body = new String(fBodyBytes, StandardCharsets.UTF_8);
                String url = req.url();
                int status = res.status();

                LOGGER.info("[MonitorHandler] Captured: url={}, status={}, bodyLength={}, pattern='{}'",
                        url, status, body.length(), rule.getUrlPattern());

                // 存储 response 到上下文（供后续修改 + Mock 阶段复用）
                if (fContext != null) {
                    fContext.storeResponse(url, body);
                }

                // 记录到 Serenity 报告
                if (rule.isRecord()) {
                    SerenityReporter.recordApiOperation("MONITOR", url,
                            String.format("Status: %d\nBody: %s", status,
                                    body.length() > 2000 ? body.substring(0, 2000) + "..." : body));
                }

                // 执行断言并记录详细失败信息
                boolean assertionsPassed = executeAssertions(rule, url, status, body, fContext);
                if (!assertionsPassed && fContext != null) {
                    fContext.setAssertionFailure();
                }

                // 通知 RouteEngine 完成一次匹配（触发 auto-stop / minMatches 检查）
                RouteEngine.onMonitorMatch(rule);

            } catch (Exception e) {
                LOGGER.error("[MonitorHandler] Error processing response: {}", e.getMessage(), e);
                if (fContext != null) {
                    fContext.recordAssertionFailure(req.url(), "PROCESSING",
                            "success", e.getClass().getSimpleName(),
                            e.getMessage());
                }
            } finally {
                if (fContext != null) {
                    fContext.decrementActiveRequests();
                }
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
     * @param context ApiMonitorContext（可为 null）
     * @return true 所有断言通过，false 有断言失败
     */
    private static boolean executeAssertions(RouteRule rule, String url, int status,
                                              String body, ApiMonitorContext context) {
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
            }
        }

        // JSONPath 断言
        Map<String, Object> jsonPathAssertions = rule.getJsonPathAssertions();
        if (jsonPathAssertions != null && !jsonPathAssertions.isEmpty()) {
            for (Map.Entry<String, Object> entry : jsonPathAssertions.entrySet()) {
                try {
                    Object actual = JsonPath.read(body, entry.getKey());
                    boolean match = compareValues(actual, entry.getValue());
                    if (!match) {
                        String actualStr = actual != null ? actual.toString() : "null";
                        LOGGER.warn("[MonitorHandler] JSONPath assertion failed for {}: path={}, expected={}, actual={}",
                                url, entry.getKey(), entry.getValue(), actualStr);
                        if (context != null) {
                            context.recordAssertionFailure(url, "JSONPATH",
                                    entry.getValue() != null ? entry.getValue().toString() : "null",
                                    actualStr,
                                    "path=" + entry.getKey());
                        }
                        allPassed = false;
                    }
                } catch (Exception e) {
                    LOGGER.warn("[MonitorHandler] JSONPath evaluation error for {}: path={}, error={}",
                            url, entry.getKey(), e.getMessage(), e);
                    if (context != null) {
                        context.recordAssertionFailure(url, "JSONPATH",
                                entry.getValue() != null ? entry.getValue().toString() : "null",
                                "ERROR",
                                "path=" + entry.getKey() + ", error=" + e.getMessage());
                    }
                    allPassed = false;
                }
            }
        }

        return allPassed;
    }

    /**
     * 值比较（支持 Number 类型的松散比较）
     */
    private static boolean compareValues(Object actual, Object expected) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;

        if (actual instanceof Number && expected instanceof Number) {
            return ((Number) actual).doubleValue() == ((Number) expected).doubleValue();
        }

        return actual.toString().equals(expected.toString());
    }
}
