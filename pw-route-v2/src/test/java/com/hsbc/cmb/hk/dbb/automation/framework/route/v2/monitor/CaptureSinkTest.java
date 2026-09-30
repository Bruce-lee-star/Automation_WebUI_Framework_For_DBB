package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec.RouteIoExecutor;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CaptureSink 采集验证：请求/响应配对、敏感头脱敏、响应体截断、超时定案、
 * 消费式幂等、队列上限与 fail-open 语义。
 *
 * <p>并发模型与 {@link MonitorSink} 一致：record/onResponse 在事件线程（本测试直接调用），
 * body 读取在 IO 线程（本测试用 io.close() 优雅停机等待），dump 在业务线程消费。
 */
public class CaptureSinkTest {

    private RouteIoExecutor io;

    private CaptureSink newSink(int maxCaptured, long timeoutMs) {
        return new CaptureSink(io, maxCaptured, timeoutMs);
    }

    @After
    public void tearDown() {
        if (io != null) {
            io.close();
            io = null;
        }
    }

    private Request mockRequest(String url) {
        Request request = mock(Request.class);
        when(request.method()).thenReturn("POST");
        when(request.url()).thenReturn(url);
        when(request.headers()).thenReturn(Map.of("content-type", "application/json",
                "authorization", "Bearer secret-token-abc"));
        when(request.postData()).thenReturn("{\"name\":\"u\"}");
        return request;
    }

    private Response mockResponse(String url, int status) {
        return mockResponse(url, status, "{\"code\":0,\"data\":{\"name\":\"ok\"}}");
    }

    private Response mockResponse(String url, int status, String body) {
        Response response = mock(Response.class);
        when(response.url()).thenReturn(url);
        when(response.status()).thenReturn(status);
        when(response.headers()).thenReturn(Map.of("content-type", "application/json",
                "set-cookie", "sid=abc123"));
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    @Test
    public void recordsRequestAndResponsePair() {
        ApiSpec spec = ApiSpec.builder("/api/users/**", RouteCapability.MONITOR)
                .capture(true)
                .build();
        CaptureSink sink = newSink(100, 30_000);

        sink.recordRequest(mockRequest("https://host/api/users/1"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/users/1", 200));

        List<CapturedApiCall> calls = sink.dump();
        assertEquals("请求-响应配对后必须产出 1 条快照", (long) 1, (long) calls.size());
        CapturedApiCall call = calls.get(0);
        assertEquals((Object) "POST", (Object) call.method());
        assertEquals((Object) "https://host/api/users/1", (Object) call.url());
        assertEquals((Object) "/api/users/**", (Object) call.pattern());
        assertEquals((long) 200L, (long) call.responseStatus());
        assertTrue(call.durationMs() >= 0);
        assertEquals((Object) "{\"name\":\"u\"}", (Object) call.requestBodyPreview());
        assertNull( "未开启 captureBody 时响应体必须为 null", call.responseBody());
        assertFalse(call.responseBodyTruncated());
        assertTrue(call.recordedAtMillis() > 0);
    }

    @Test
    public void sensitiveHeadersAreMasked() {
        ApiSpec spec = ApiSpec.builder("/api/login", RouteCapability.MONITOR)
                .capture(true)
                .build();
        CaptureSink sink = newSink(100, 30_000);

        sink.recordRequest(mockRequest("https://host/api/login"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/login", 200));

        CapturedApiCall call = sink.dump().get(0);
        assertEquals("请求头 authorization 值必须脱敏", (Object) SensitiveDataSanitizer.maskToken(),
                (Object) call.requestHeaders().get("authorization"));
        assertEquals("响应头 set-cookie 值必须脱敏", (Object) SensitiveDataSanitizer.maskToken(),
                (Object) call.responseHeaders().get("set-cookie"));
        assertEquals("非敏感头保持原样", (Object) "application/json", (Object) call.requestHeaders().get("content-type"));
    }

    @Test
    public void responseBodyCapturedWithTruncation() throws Exception {
        io = new RouteIoExecutor("t", 2, 64);
        ApiSpec spec = ApiSpec.builder("/api/big", RouteCapability.MONITOR)
                .capture(true)
                .captureBody(true)
                .captureBodyLimitBytes(8)
                .build();
        CaptureSink sink = newSink(100, 30_000);

        sink.recordRequest(mockRequest("https://host/api/big"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/big", 200,
                "{\"data\":\"very long payload\"}"));

        io.close();
        io = null;

        List<CapturedApiCall> calls = sink.dump();
        assertEquals((long) 1, (long) calls.size());
        CapturedApiCall call = calls.get(0);
        assertNotNull( "captureBody 开启后响应体必须非 null", call.responseBody());
        assertEquals("响应体必须按 limit 截断", (long) 8, (long) call.responseBody().length());
        assertTrue( "截断标志必须置位", call.responseBodyTruncated());
    }

    @Test
    public void pendingRequestTimesOutOnDump() throws Exception {
        ApiSpec spec = ApiSpec.builder("/api/slow", RouteCapability.MONITOR)
                .capture(true)
                .build();
        CaptureSink sink = newSink(100, 50); // 50ms 超时窗口

        sink.recordRequest(mockRequest("https://host/api/slow"), spec);
        Thread.sleep(120); // 超过超时窗口

        List<CapturedApiCall> calls = sink.dump();
        assertEquals("超时未响应必须定案为 timedOut 快照", (long) 1, (long) calls.size());
        CapturedApiCall call = calls.get(0);
        assertTrue(call.timedOut());
        assertNull(call.responseStatus());
        assertEquals((long) -1, (long) call.durationMs());
        assertTrue(sink.timedOutCount() >= 1);
    }

    @Test
    public void dumpIsIdempotent() {
        ApiSpec spec = ApiSpec.builder("/api/users/**", RouteCapability.MONITOR)
                .capture(true)
                .build();
        CaptureSink sink = newSink(100, 30_000);

        sink.recordRequest(mockRequest("https://host/api/users/1"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/users/1", 200));

        assertEquals((long) 1, (long) sink.dump().size());
        assertTrue( "消费式语义：第二次 dump 必须为空", sink.dump().isEmpty());
        assertEquals((long) 0, (long) sink.size());
    }

    @Test
    public void queueLimitDropsExcess() {
        ApiSpec spec = ApiSpec.builder("/api/users/**", RouteCapability.MONITOR)
                .capture(true)
                .build();
        CaptureSink sink = newSink(2, 30_000); // 上限 2 条

        for (int i = 1; i <= 3; i++) {
            sink.recordRequest(mockRequest("https://host/api/users/" + i), spec);
            sink.onResponseForSpec(spec, mockResponse("https://host/api/users/" + i, 200));
        }

        assertEquals("队列上限内保留", (long) 2, (long) sink.dump().size());
        assertEquals("超出部分丢弃并计数（fail-open）", (long) 1, (long) sink.droppedCount());
    }

    @Test
    public void nonCaptureRuleNotRecorded() {
        ApiSpec spec = ApiSpec.builder("/api/plain", RouteCapability.MONITOR)
                .build(); // 未开启 capture
        CaptureSink sink = newSink(100, 30_000);

        sink.recordRequest(mockRequest("https://host/api/plain"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/plain", 200));

        assertTrue( "未开启 capture 的规则不得采集", sink.dump().isEmpty());
        assertEquals((long) 0, (long) sink.size());
    }

    @Test
    public void pendingWithoutTimeoutIsKept() {
        ApiSpec spec = ApiSpec.builder("/api/pending", RouteCapability.MONITOR)
                .capture(true)
                .build();
        CaptureSink sink = newSink(100, 30_000);

        sink.recordRequest(mockRequest("https://host/api/pending"), spec);
        // 未响应、未超时：不得被 dump 误定案
        assertTrue(sink.dump().isEmpty());
        assertTrue(sink.timedOutCount() == 0);
    }

    @Test
    public void responseDeliveredForOtherSpecDoesNotSettleThisSpec() {
        ApiSpec spec = ApiSpec.builder("/api/users/**", RouteCapability.MONITOR)
                .capture(true)
                .build();
        ApiSpec other = ApiSpec.builder("/api/other/**", RouteCapability.MONITOR)
                .capture(true)
                .build();
        CaptureSink sink = newSink(100, 30_000);

        sink.recordRequest(mockRequest("https://host/api/users/1"), spec);
        // 响应侧按 spec 精确配对：投递给别的规则绝不消费本规则的 pending（取代原 URL 匹配语义）
        sink.onResponseForSpec(other, mockResponse("https://host/api/users/1", 200));

        assertTrue( "跨规则投递不得定案本规则的快照", sink.dump().isEmpty());
    }
}
