package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.monitor;

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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MonitorSink 观测与响应断言验证：请求记录、按 pattern 匹配响应断言、
 * JSONPath 断言（IO 线程异步）、超时标记与 drain 消费。
 */
public class MonitorSinkTest {

    private RouteIoExecutor io;

    private MonitorSink newSink() {
        return new MonitorSink(io);
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
        when(request.method()).thenReturn("GET");
        when(request.url()).thenReturn(url);
        when(request.headers()).thenReturn(Map.of("x-test", "1"));
        when(request.postData()).thenReturn(null);
        return request;
    }

    private Response mockResponse(String url, int status) {
        return mockResponse(url, status, "{\"code\":0,\"data\":{\"name\":\"ok\"}}");
    }

    private Response mockResponse(String url, int status, String body) {
        Response response = mock(Response.class);
        when(response.url()).thenReturn(url);
        when(response.status()).thenReturn(status);
        when(response.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    @Test
    public void recordsRequestAndAssertsMatchingResponse() {
        ApiSpec spec = ApiSpec.builder("/api/users/**", RouteCapability.MONITOR)
                .expectStatus(200)
                .build();
        MonitorSink sink = newSink();

        sink.recordRequest(mockRequest("https://host/api/users/1"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/users/1", 200));

        List<CapturedExchange> drained = sink.drain();
        assertEquals((long) 1, (long) drained.size());
        CapturedExchange exchange = drained.get(0);
        assertEquals((Object) "GET", (Object) exchange.method());
        assertEquals((Object) "https://host/api/users/1", (Object) exchange.url());
        assertEquals((long) 200L, (long) exchange.responseStatus());
        assertTrue( "期望 200 且响应 200 必须断言通过", exchange.assertionPassed());
    }

    @Test
    public void responseDeliveredForOtherSpecDoesNotTouchThisSpec() {
        ApiSpec spec = ApiSpec.builder("/api/users/**", RouteCapability.MONITOR)
                .expectStatus(200)
                .build();
        ApiSpec other = ApiSpec.builder("/api/other/**", RouteCapability.MONITOR)
                .expectStatus(200)
                .build();
        MonitorSink sink = newSink();

        sink.recordRequest(mockRequest("https://host/api/users/1"), spec);
        // 响应侧按 spec 精确配对：投递给别的规则绝不消费本规则的 pending（取代原 URL 匹配语义）
        sink.onResponseForSpec(other, mockResponse("https://host/api/users/1", 200));

        CapturedExchange exchange = sink.drain().get(0);
        assertNull( "按 spec 配对，绝不跨规则串扰", exchange.responseStatus());
        assertNull(exchange.assertionPassed());
    }

    @Test
    public void assertionFailsOnStatusMismatch() {
        ApiSpec spec = ApiSpec.builder("/api/login", RouteCapability.MONITOR)
                .expectStatus(200)
                .build();
        MonitorSink sink = newSink();

        sink.recordRequest(mockRequest("https://host/api/login"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/login", 500));

        CapturedExchange exchange = sink.drain().get(0);
        assertEquals((long) 500L, (long) exchange.responseStatus());
        assertFalse( "期望 200 响应 500 必须断言失败", exchange.assertionPassed());
    }

    @Test
    public void jsonPathAssertionsPassOnMatch() throws Exception {
        io = new RouteIoExecutor("t", 2, 64);
        ApiSpec spec = ApiSpec.builder("/api/user", RouteCapability.MONITOR)
                .expectStatus(200)
                .expectJsonPath("$.code", 0)
                .expectJsonPath("$.data.name", "ok")
                .build();
        MonitorSink sink = newSink();

        sink.recordRequest(mockRequest("https://host/api/user"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/user", 200));

        CapturedExchange exchange = awaitJsonPathAssertion(sink);
        assertEquals((long) 200L, (long) exchange.responseStatus());
        assertTrue( "status+jsonpath 全过必须断言通过", exchange.assertionPassed());
        assertTrue(exchange.bodyAssertionFailures().isEmpty());
    }

    @Test
    public void jsonPathAssertionsFailOnMismatch() throws Exception {
        io = new RouteIoExecutor("t", 2, 64);
        ApiSpec spec = ApiSpec.builder("/api/user", RouteCapability.MONITOR)
                .expectStatus(200)
                .expectJsonPath("$.code", 1)          // 实际 0 → 失败
                .expectJsonPath("$.data.missing", "x") // 路径不存在 → 失败
                .build();
        MonitorSink sink = newSink();

        sink.recordRequest(mockRequest("https://host/api/user"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/user", 200));

        CapturedExchange exchange = awaitJsonPathAssertion(sink);
        assertFalse( "任一 jsonpath 失败必须整体断言失败", exchange.assertionPassed());
        assertEquals((long) 2, (long) exchange.bodyAssertionFailures().size());
    }

    @Test
    public void bodyContainsWorksOnHtmlResponse() throws Exception {
        io = new RouteIoExecutor("t", 2, 64);
        ApiSpec spec = ApiSpec.builder("/page", RouteCapability.MONITOR)
                .expectBodyContains("transaction ok")
                .build();
        MonitorSink sink = newSink();

        Response html = mock(Response.class);
        when(html.url()).thenReturn("https://host/page");
        when(html.status()).thenReturn(200);
        when(html.headers()).thenReturn(Map.of("content-type", "text/html; charset=utf-8"));
        when(html.body()).thenReturn("<html>transaction ok</html>".getBytes(StandardCharsets.UTF_8));

        sink.recordRequest(mockRequest("https://host/page"), spec);
        sink.onResponseForSpec(spec, html);

        CapturedExchange exchange = awaitJsonPathAssertion(sink);
        assertTrue( "HTML 文本包含断言必须通过", exchange.assertionPassed());
        assertTrue(exchange.bodyAssertionFailures().isEmpty());
    }

    @Test
    public void formFieldWorksOnFormUrlEncodedResponse() throws Exception {
        io = new RouteIoExecutor("t", 2, 64);
        ApiSpec spec = ApiSpec.builder("/form", RouteCapability.MONITOR)
                .expectFormField("token", "abc")
                .build();
        MonitorSink sink = newSink();

        Response form = mock(Response.class);
        when(form.url()).thenReturn("https://host/form");
        when(form.status()).thenReturn(200);
        when(form.headers()).thenReturn(Map.of("content-type", "application/x-www-form-urlencoded"));
        when(form.body()).thenReturn("token=abc&count=3".getBytes(StandardCharsets.UTF_8));

        sink.recordRequest(mockRequest("https://host/form"), spec);
        sink.onResponseForSpec(spec, form);

        CapturedExchange exchange = awaitJsonPathAssertion(sink);
        assertTrue( "表单字段断言必须通过", exchange.assertionPassed());
        assertTrue(exchange.bodyAssertionFailures().isEmpty());
    }

    @Test
    public void jsonPathOnNonJsonResponseFailsWithNotApplicable() throws Exception {
        io = new RouteIoExecutor("t", 2, 64);
        ApiSpec spec = ApiSpec.builder("/xml-api", RouteCapability.MONITOR)
                .expectJsonPath("$.code", 0)
                .build();
        MonitorSink sink = newSink();

        Response xml = mock(Response.class);
        when(xml.url()).thenReturn("https://host/xml-api");
        when(xml.status()).thenReturn(200);
        when(xml.headers()).thenReturn(Map.of("content-type", "application/xml"));
        when(xml.body()).thenReturn("<result><code>0</code></result>".getBytes(StandardCharsets.UTF_8));

        sink.recordRequest(mockRequest("https://host/xml-api"), spec);
        sink.onResponseForSpec(spec, xml);

        CapturedExchange exchange = awaitJsonPathAssertion(sink);
        assertFalse( "非 JSON 响应 + jsonpath 断言必须明确失败", exchange.assertionPassed());
        assertEquals((long) 1, (long) exchange.bodyAssertionFailures().size());
        assertTrue(
                "失败原因必须是 not applicable，而非误导性 JSON 解析错误", exchange.bodyAssertionFailures().get(0).contains("not applicable"));
    }

    @Test
    public void timedOutRequestIsMarkedOnDrain() throws Exception {
        ApiSpec spec = ApiSpec.builder("/api/slow", RouteCapability.MONITOR)
                .expectStatus(200)
                .monitorTimeoutMs(50)
                .build();
        MonitorSink sink = newSink();

        sink.recordRequest(mockRequest("https://host/api/slow"), spec);
        Thread.sleep(120); // 超过 monitorTimeoutMs

        CapturedExchange exchange = sink.drain().get(0);
        assertTrue( "超时未响应必须标记", exchange.responseTimedOut());
        assertFalse(exchange.assertionPassed());
        assertEquals((long) -1, (long) exchange.durationMs());
    }

    @Test
    public void monitorWithoutExpectationIsNotRecorded() {
        ApiSpec spec = ApiSpec.builder("/api/plain", RouteCapability.MONITOR).build();
        MonitorSink sink = newSink();

        // 无任何响应侧期望 → 不进入观测队列（旧行为会记录，导致 drain 时被超时扫描误判，见 recordRequest 注释）
        assertFalse( "无期望的 MONITOR 规则不得进入观测队列",
                sink.recordRequest(mockRequest("https://host/api/plain"), spec));
        assertTrue(sink.drain().isEmpty());
    }

    /**
     * 回归守卫（2026-09-28 实测误报，P0）：MOCK 规则的请求曾被无条件记入观测队列，却永远不会被配对
     * （{@link MonitorSink#onResponseForSpec} 对非 MONITOR 能力直接返回），于是步骤存活超过
     * monitorTimeoutMs 后 {@link MonitorSink#drainSettledFailures()} 把一次完全正常的 mock 请求判成
     * 「监控超时」并归因场景失败，日志实测：
     * {@code [timeout] POST …/user/profile/list (pattern=profile/list, expect=null, timeoutMs=30000)}。
     */
    @Test
    public void mockCapabilityRequestIsNotRecordedNorSettledAsTimeout() throws Exception {
        ApiSpec mock = ApiSpec.builder("/api/profile/list", RouteCapability.MOCK)
                .monitorTimeoutMs(30)
                .build();
        MonitorSink sink = newSink();

        assertFalse( "非 MONITOR 能力不得进入观测队列",
                sink.recordRequest(mockRequest("https://host/api/profile/list"), mock));
        Thread.sleep(80); // 早已越过 monitorTimeoutMs

        assertTrue( "MOCK 请求不得出现在观测快照中", sink.drain().isEmpty());
        assertTrue( "MOCK 请求不得产出 [timeout] 误报失败", sink.drainSettledFailures().isEmpty());
    }

    /** 正对照（防本次修复把真问题一起吞掉）：有响应期望的规则超时未响应时，仍必须如实上报。 */
    @Test
    public void expectedButNeverArrivingResponseStillReportsTimeout() throws Exception {
        ApiSpec spec = ApiSpec.builder("/api/slow", RouteCapability.MONITOR)
                .expectStatus(200)
                .monitorTimeoutMs(30)
                .build();
        MonitorSink sink = newSink();

        assertTrue( "有期望的 MONITOR 规则必须进入等待响应索引",
                sink.recordRequest(mockRequest("https://host/api/slow"), spec));
        Thread.sleep(80);

        List<CapturedExchange> failures = sink.drainSettledFailures();
        assertEquals("有期望未响应必须上报", (long) 1, (long) failures.size());
        assertTrue(failures.get(0).responseTimedOut());
        assertEquals((Object) "/api/slow", (Object) failures.get(0).pattern());
    }

    /**
     * P1 诊断：content-type 缺失且断言失败时，失败明细须附「响应形态」（header 名 + body 字节数），
     * 使一次运行即可区分"服务端确实没发 Content-Type"与"空体/异常响应（如会话失效的空 200）"。
     * 只上报 header 名，绝不带上值/响应体内容。
     */
    @Test
    public void missingContentTypeFailureCarriesResponseDiagnosis() throws Exception {
        io = new RouteIoExecutor("t", 2, 64);
        ApiSpec spec = ApiSpec.builder("/api/leftmenu", RouteCapability.MONITOR)
                .expectStatus(200)
                .expectJsonPath("$.enableAdminTools", "Y")
                .build();
        MonitorSink sink = newSink();

        Response noContentType = mock(Response.class);
        when(noContentType.url()).thenReturn("https://host/api/leftmenu");
        when(noContentType.status()).thenReturn(200);
        when(noContentType.headers()).thenReturn(Map.of("content-length", "0", "Date", "Mon, 28 Sep 2026"));
        when(noContentType.body()).thenReturn(new byte[0]);

        sink.recordRequest(mockRequest("https://host/api/leftmenu"), spec);
        sink.onResponseForSpec(spec, noContentType);

        CapturedExchange exchange = awaitJsonPathAssertion(sink);
        assertFalse( "空体 + 无 content-type 必须判失败（不得静默通过）", exchange.assertionPassed());
        List<String> failures = exchange.bodyAssertionFailures();
        assertTrue( "必须附响应形态诊断",
                failures.stream().anyMatch(f -> f.contains("response diagnosis:") && f.contains("bodyBytes=0")));
        assertTrue( "诊断须含 header 名（小写、排序）且不含值",
                failures.stream().anyMatch(f -> f.contains("headerNames=[content-length, date]")));
        assertFalse( "诊断不得泄露 header 值",
                failures.stream().anyMatch(f -> f.contains("Mon, 28 Sep 2026")));
    }

    /**
     * 等待 IO 线程完成 JSONPath 断言：关闭 IO 池会等待在途任务完成（优雅停机），
     * 之后 drain 即拿到含 jsonPath 断言结果的快照。无需轮询，无竞态。
     */
    private CapturedExchange awaitJsonPathAssertion(MonitorSink sink) {
        io.close(); // awaitTermination 等待 jsonPath 断言任务落定
        return sink.drain().get(0);
    }
}

