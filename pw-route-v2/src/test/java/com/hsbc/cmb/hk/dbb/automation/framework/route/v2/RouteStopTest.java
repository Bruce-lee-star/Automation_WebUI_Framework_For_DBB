package com.hsbc.cmb.hk.dbb.automation.framework.route.v2;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding.RoutePatterns;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteDsl2;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * stop 系列（按能力停止，P0）：
 * <ul>
 *   <li>stopMock/stopModify/stopDelay/stopMonitor/stopApi 只停指定 pattern 的指定能力，路由仍注册；</li>
 *   <li>已停能力请求走 fallback 链式裁决（不 fulfill、不悬挂）；</li>
 *   <li>不影响其它 pattern；幂等；未注册返回 false；重新注册恢复能力；</li>
 *   <li>并发 stop/register 线性化（CAS 无撕裂）；关闭后 fail-safe。</li>
 * </ul>
 */
public class RouteStopTest {

    private BrowserContext ctx;

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        org.mockito.Mockito.doAnswer(inv -> null).when(ctx).onClose(any());
        return ctx;
    }

    @After
    public void cleanup() {
        if (ctx != null) {
            RouteEngine2.shutdown(ctx);
        }
    }

    private Consumer<Route> captureHandler(String pattern) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Route>> captor = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(ctx).route(eq(RoutePatterns.normalize(pattern)), captor.capture(), any());
        return captor.getValue();
    }

    private Request mockApiRequest(String url) {
        Request request = mock(Request.class);
        when(request.url()).thenReturn(url);
        when(request.method()).thenReturn("GET");
        when(request.headers()).thenReturn(Map.of());
        when(request.resourceType()).thenReturn("xhr");
        when(request.isNavigationRequest()).thenReturn(false);
        Frame frame = mock(Frame.class);
        when(frame.parentFrame()).thenReturn(null);
        when(request.frame()).thenReturn(frame);
        return request;
    }

    private Route mockRoute(String url) {
        Request request = mockApiRequest(url);
        Route route = mock(Route.class);
        when(route.request()).thenReturn(request);
        return route;
    }

    @Test
    public void stopMockCausesFallbackInsteadOfFulfill() {
        mockContext();
        RouteDsl2.on(ctx).api("/api/a").mock().status(200).body("{}").register();
        Consumer<Route> handler = captureHandler("/api/a");

        assertTrue( "stop 已注册规则必须返回 true", RouteDsl2.stopMock(ctx, "/api/a"));

        Route route = mockRoute("https://host/api/a");
        handler.accept(route);

        verify(route).fallback();
        verify(route, never()).fulfill(any());
        verify(route, never()).resume();
    }

    @Test
    public void stopDoesNotAffectOtherPatterns() {
        mockContext();
        RouteDsl2.on(ctx).api("/api/a").mock().status(200).body("{}").register();
        RouteDsl2.on(ctx).api("/api/b").mock().status(201).body("{}").register();
        Consumer<Route> handlerB = captureHandler("/api/b");

        assertTrue(RouteDsl2.stopMock(ctx, "/api/a"));

        // /api/b 未停：仍执行 MOCK fulfill
        Route routeB = mockRoute("https://host/api/b");
        handlerB.accept(routeB);
        verify(routeB, never()).fallback();
        verify(routeB).fulfill(any());
    }

    @Test
    public void stopApiStopsCurrentCapability() {
        mockContext();
        RouteDsl2.on(ctx).api("/api/d").delay(5).done().start(); // 级联提交延迟规则
        Consumer<Route> handler = captureHandler("/api/d");

        assertTrue( "stopApi 必须停掉当前能力", RouteDsl2.stopApi(ctx, "/api/d"));
        assertFalse( "已停能力再次 stop 幂等返回 false", RouteDsl2.stopDelay(ctx, "/api/d"));

        Route route = mockRoute("https://host/api/d");
        handler.accept(route);
        verify(route).fallback();
        verify(route, never()).resume();
    }

    @Test
    public void stopUnknownPatternReturnsFalse() {
        mockContext();
        assertFalse( "未注册 pattern 必须返回 false", RouteDsl2.stopMock(ctx, "/api/none"));
    }

    @Test
    public void stopIsIdempotent() {
        mockContext();
        RouteDsl2.on(ctx).api("/api/m").mock().status(200).body("{}").register();
        assertTrue( "第一次 stop 生效", RouteDsl2.stopMock(ctx, "/api/m"));
        assertFalse( "第二次 stop 幂等返回 false", RouteDsl2.stopMock(ctx, "/api/m"));
    }

    @Test
    public void reRegisterRestoresCapability() {
        mockContext();
        RouteDsl2.on(ctx).api("/api/r").mock().status(200).body("{}").register();
        assertTrue(RouteDsl2.stopMock(ctx, "/api/r"));

        // 重新注册 = 全新规则（无 disabled 掩码）→ 能力恢复（binder 复用，handler 不变）
        RouteDsl2.on(ctx).api("/api/r").mock().status(200).body("{\"v\":1}").register();
        Consumer<Route> handler = captureHandler("/api/r");

        Route route = mockRoute("https://host/api/r");
        handler.accept(route);

        verify(route, never()).fallback();
        verify(route).fulfill(any());
    }

    @Test
    public void stopMonitorAfterDispatchDoesNotAffectCompletedRequest() {
        mockContext();
        RouteDsl2.on(ctx).api("/api/s").monitor().expectStatus(200).register();
        Consumer<Route> handler = captureHandler("/api/s");

        // 请求 1：dispatch 完成（claim 已终结，resume 无参放行）
        Route route1 = mockRoute("https://host/api/s");
        handler.accept(route1);
        verify(route1).resume();

        // dispatch 完成后再 stop：不影响已处理请求（在途不打断契约）
        assertTrue(RouteDsl2.stopMonitor(ctx, "/api/s"));

        // 请求 2：能力已停 → fallback 链式裁决
        Route route2 = mockRoute("https://host/api/s");
        handler.accept(route2);
        verify(route2, never()).resume();
        verify(route2).fallback();
    }

    @Test
    public void concurrentStopAndRegisterStayLinearizable() throws Exception {
        mockContext();
        RouteRuntime runtime = RouteEngine2.runtimeOf(ctx);

        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger stopTrue = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int seed = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        String pattern = "/api/c" + seed + "-" + (i % 3);
                        if ((seed + i) % 2 == 0) {
                            RouteDsl2.on(ctx).api(pattern).mock().status(200).body("{}").register();
                        } else if (RouteDsl2.stopMock(ctx, pattern)) {
                            stopTrue.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue( "并发 stop/register 必须在预算内完成", done.await(15, TimeUnit.SECONDS));
        pool.shutdownNow();

        // 线性化校验：代递增、无悬挂 claim、快照一致（未被撕裂）
        RouteRuntime.RouteV2Metrics metrics = runtime.metrics();
        assertTrue( "代必须递增", metrics.generation() > 0);
        assertEquals("无悬挂 claim", (long) 0, (long) metrics.inFlightClaims());
    }

    @Test
    public void stopOnClosedRuntimeReturnsFalse() {
        mockContext();
        RouteDsl2.on(ctx).api("/api/z").mock().status(200).body("{}").register();
        RouteEngine2.shutdown(ctx);
        assertFalse( "关闭后 stop 必须 fail-safe 返回 false", RouteDsl2.stopMock(ctx, "/api/z"));
    }
}
