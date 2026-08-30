package com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.MonitorCallback;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.persistence.DatabaseStoreMonitorCallback;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.persistence.FileStoreMonitorCallback;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteAsyncPool;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SensitiveDataSanitizer;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SerenityReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 负责 Monitor 结果的单次记录和匹配计数，不依赖浏览器对象。 */
public final class MonitorResultRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorResultRecorder.class);

    /** ⭐ 单次采集存储的响应体字节上限（防止超大响应体打爆内存）。 */
    private static final int MAX_CAPTURE_BODY_BYTES = 2 * 1024 * 1024;

    private MonitorResultRecorder() {
    }

    public static CapturedApiCall record(MonitorRuleSnapshot rule, ApiCaptureContext context,
                                         MonitorResponse response) {
        return record(rule, context, response, null);
    }

    /**
     * 记录一次成功 Monitor；可在 callback/persistence 前执行内部完成动作。
     * 该重载仅供协调器保持既有 Route Monitor 的 auto-stop 时序。
     */
    static CapturedApiCall record(MonitorRuleSnapshot rule, ApiCaptureContext context,
                                  MonitorResponse response, Runnable beforeCallbacks) {
        if (context == null) return null;
        StoredBody storedBody = limitStoredBody(response.body());
        CapturedApiCall captured = new CapturedApiCall(
                rule.urlPattern(), response.method(),
                SensitiveDataSanitizer.sanitizeHeaders(response.requestHeaders()), response.status(),
                SensitiveDataSanitizer.sanitizeHeaders(response.responseHeaders()),
                storedBody.value(), System.currentTimeMillis(),
                response.url(), response.requestBody());
        if (storedBody.truncated()) captured.markBodyTruncated(storedBody.originalBytes());
        try {
            context.storeApiCall(captured);
        } catch (Exception e) {
            LOGGER.debug("Monitor call storage failed: {}", e.getMessage());
        }
        context.incrementActiveRequests();
        if (rule.record()) {
            String body = response.body() == null ? "" : response.body();
            SerenityReporter.recordApiOperation("MONITOR", response.url(),
                    String.format("Status: %d\nBody: %s", response.status(),
                            body.length() > 2000 ? body.substring(0, 2000) + "..." : body));
        }
        if (beforeCallbacks != null) beforeCallbacks.run();
        dispatchCallbacks(rule, response);
        return captured;
    }

    public static void recordFailure(MonitorRuleSnapshot rule, MonitorResponse response, String reason) {
        StoredBody storedBody = limitStoredBody(response.body());
        CapturedApiCall captured = new CapturedApiCall(rule.urlPattern(), response.method(),
                SensitiveDataSanitizer.sanitizeHeaders(response.requestHeaders()), response.status(),
                SensitiveDataSanitizer.sanitizeHeaders(response.responseHeaders()), storedBody.value(),
                System.currentTimeMillis(), response.url(), response.requestBody());
        if (storedBody.truncated()) captured.markBodyTruncated(storedBody.originalBytes());
        String owner = ApiMonitorOrchestrator.getInstance().getOwner(rule.urlPattern());
        MonitorFailureCollector.getInstance().record(captured, rule.urlPattern(), owner, reason);
    }

    private static StoredBody limitStoredBody(String body) {
        if (body == null) return new StoredBody(null, false, 0L);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_CAPTURE_BODY_BYTES) return new StoredBody(body, false, bytes.length);
        // 避免截断 UTF-8 多字节序列：先按字节截取再以 UTF-8 解码，替换不完整尾部字符。
        String limited = new String(bytes, 0, MAX_CAPTURE_BODY_BYTES, StandardCharsets.UTF_8);
        return new StoredBody(limited, true, bytes.length);
    }

    private record StoredBody(String value, boolean truncated, long originalBytes) {
    }

    private static void dispatchCallbacks(MonitorRuleSnapshot rule, MonitorResponse response) {
        // ⭐ P1-4: 持久化回调（DB / 文件 IO）卸载到异步线程池 —— 原实现直接在 Playwright
        //   事件线程 / 采集合并线程执行，FileStoreMonitorCallback 的 Files.write 会阻塞浏览器
        //   事件分发。改为 RouteAsyncPool 异步执行，事件线程零阻塞；失败仅 WARN，不影响断言。
        final String url = response.url();
        final int status = response.status();
        final String body = response.body();
        final Map<String, String> resHeaders = response.responseHeaders();
        final Map<String, String> reqHeaders = response.requestHeaders();
        final String method = response.method();
        final String pattern = rule.urlPattern();
        // ⭐ P2-18：monitorCallbacks() 在 MonitorRuleSnapshot 构造时已是不可变副本，无需二次 copyOf
        final List<Object> callbacks = rule.monitorCallbacksTyped();
        RouteAsyncPool.run(() -> {
            for (Object callback : callbacks) {
                if (!(callback instanceof MonitorCallback monitorCallback)) continue;
                try {
                    monitorCallback.onResponse(url, status, body, resHeaders, method);
                } catch (Exception e) {
                    LOGGER.warn("Monitor callback failed for pattern='{}': {}", pattern, e.getMessage());
                }
            }
            try {
                DatabaseStoreMonitorCallback.INSTANCE.onResponse(url, status, body, reqHeaders, resHeaders, method);
                FileStoreMonitorCallback.INSTANCE.onResponse(url, pattern, status, body, reqHeaders, resHeaders, method);
            } catch (Exception e) {
                LOGGER.warn("Monitor persistence callback failed for pattern='{}': {}", pattern, e.getMessage());
            }
        });
    }

    public static void completeMatch(RouteRule rule, ApiCaptureContext context) {
        if (context == null) return;
        try {
            RouteEngine.onMonitorMatch(rule);
        } finally {
            context.decrementActiveRequests();
        }
    }
}
