package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAPTURE 与四能力共存的端到端链路验证，以及「绝不订阅 response 事件」的回归护栏。
 *
 * <p>验证点：
 * <ul>
 *   <li><b>零 response 订阅</b>：RouteRuntime 构造<b>禁止</b>向 context 注册 {@code onResponse}——
 *       该订阅是驱动侧 {@code Object doesn't exist: response@…} 竞态的唯一触发源；
 *       响应侧改由 route 通道轮询 {@code Request#existingResponse()} 获取；</li>
 *   <li><b>capture × MONITOR</b>：同一请求既被断言又被采集，两条管道无共享可变状态；</li>
 *   <li><b>capture × MOCK / MODIFY_REQUEST / DELAY</b>：dispatch 入口统一观测记录对所有
 *       能力生效（mock 走 IO fulfill、delay 挂起期间，请求侧快照均已记录）；</li>
 *   <li><b>消费式 dump</b>：跨能力 dump 不互相污染，幂等。</li>
 * </ul>
 */
public class CaptureWithCapabilitiesTest {

    private BrowserContext ctx;

    @Before
    public void setUp() {
        ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        // 刻意只 stub onClose / route：若实现再调用 context.onResponse，会被
        // noResponseSubscriptionIsInstalledOnRuntimeCreation 明确拦下
    }

    @After
    public void tearDown() {
        RouteEngine.shutdownAll();
    }

    private Request mockRequest(String url, Response response) {
        Request request = mock(Request.class);
        when(request.method()).thenReturn("POST");
        when(request.url()).thenReturn(url);
        when(request.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(request.postData()).thenReturn("{\"a\":1}");
        when(request.resourceType()).thenReturn("xhr"); // 通过 ApiMatcher 资源类型检查
        com.microsoft.playwright.Frame frame = mock(com.microsoft.playwright.Frame.class);
        when(frame.parentFrame()).thenReturn(null);     // 主 frame → 通过 onlyMainFrame 检查
        when(request.frame()).thenReturn(frame);
        // 响应侧观测通道：本地字段（零协议往返、不触碰对象表）。null = 响应未到达
        when(request.existingResponse()).thenReturn(response);
        return request;
    }

    private Response mockResponse(String url, int status) {
        Response response = mock(Response.class);
        when(response.url()).thenReturn(url);
        when(response.status()).thenReturn(status);
        when(response.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(response.body()).thenReturn("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        return response;
    }

    /** 已到达响应的请求（dispatch 后首轮轮询即命中 → 同步定案，测试确定无等待）。 */
    private Route arrivedRoute(String url, Response response) {
        // 先完成 request 的全部 stub，再 stub route.request()（避免 Mockito 嵌套 stubbing）
        Request request = mockRequest(url, response);
        Route route = mock(Route.class);
        when(route.request()).thenReturn(request);
        return route;
    }

    /** 响应未到达的请求（轮询窗口内不命中 → 由 sink 超时定案兜底）。 */
    private Route pendingRoute(String url) {
        return arrivedRoute(url, null);
    }

    @Test
    public void noResponseSubscriptionIsInstalledOnRuntimeCreation() {
        RouteEngine.runtimeOf(ctx);
        verify(ctx, never()).onResponse(any());
    }

    @Test
    public void captureWithMonitorRecordsAndAssertsWithoutInterference() {
        RouteDsl.on(ctx)
                .api("/api/users/**").monitor().expectStatus(200).capture()
                .register();

        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);
        runtime.dispatch(arrivedRoute("https://host/api/users/1",
                mockResponse("https://host/api/users/1", 200)), "/api/users/**");

        // CAPTURE 侧：完整快照（响应经 route 通道配对，无需任何事件订阅）
        List<CapturedApiCall> calls = RouteDsl.dumpCaptured(ctx);
        assertEquals((long) 1, (long) calls.size());
        assertEquals((long) 200L, (long) calls.get(0).responseStatus());
        assertEquals((Object) "/api/users/**", (Object) calls.get(0).pattern());

        // MONITOR 侧：断言通过不结算（互不干扰）
        assertTrue(
                "monitor 断言通过不得误报；capture 与 monitor 两管道无串扰",
                RouteEngine.drainSettledAssertionFailures().isEmpty());

        // 幂等：消费后再取为空
        assertTrue(RouteDsl.dumpCaptured(ctx).isEmpty());
    }

    @Test
    public void captureWithMockStillRecordsThroughDispatch() {
        RouteDsl.on(ctx)
                .api("/api/login").mock().status(200).body("{\"ok\":true}").contentType("application/json")
                .capture()
                .register();

        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);
        runtime.dispatch(arrivedRoute("https://host/api/login",
                mockResponse("https://host/api/login", 200)), "/api/login");

        List<CapturedApiCall> calls = RouteDsl.dumpCaptured(ctx);
        assertEquals("mock 规则 + capture 必须仍采集请求/响应快照", (long) 1, (long) calls.size());
        assertEquals((long) 200L, (long) calls.get(0).responseStatus());
    }

    @Test
    public void captureWithModifyRequestRecordsThroughDispatch() {
        RouteDsl.on(ctx)
                .api("/api/user").modifyRequest().setRequestHeader("X-Env", "mock").capture()
                .register();

        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);
        runtime.dispatch(arrivedRoute("https://host/api/user",
                mockResponse("https://host/api/user", 200)), "/api/user");

        assertEquals("modifyRequest 规则 + capture 必须采集快照", (long) 1, (long) RouteDsl.dumpCaptured(ctx).size());
    }

    @Test
    public void captureWithDelayRecordsRequestBeforeResume() {
        RouteDsl.on(ctx)
                .api("/api/pay/**").delay(1).capture().done() // 1ms 延迟（不会阻塞测试）
                .start();

        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);
        runtime.dispatch(pendingRoute("https://host/api/pay/1"), "/api/pay/**");

        // 请求侧已记录（pending），但响应未到 → dump 为空（未误定案）
        assertTrue(
                "delay 挂起期间不得把在途请求误判为已定案", RouteDsl.dumpCaptured(ctx).isEmpty());
    }

    @Test
    public void multipleCaptureRulesShareResponseWithoutCrossTalk() {
        RouteDsl.on(ctx)
                .api("/api/a").monitor().capture().done()
                .api("/api/b").monitor().capture().done()
                .start();

        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);
        runtime.dispatch(arrivedRoute("https://host/api/a", mockResponse("https://host/api/a", 200)), "/api/a");
        runtime.dispatch(arrivedRoute("https://host/api/b", mockResponse("https://host/api/b", 500)), "/api/b");

        List<CapturedApiCall> calls = RouteDsl.dumpCaptured(ctx);
        assertEquals("多条 capture 规则各自配对，互不污染", (long) 2, (long) calls.size());
        assertEquals((long) 200L, (long) calls.get(0).responseStatus());
        assertEquals((long) 500L, (long) calls.get(1).responseStatus());
    }
}
