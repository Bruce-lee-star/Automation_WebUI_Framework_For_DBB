package com.hsbc.cmb.hk.dbb.automation.framework.route.v2;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteDsl2;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行时全链路验证（mock BrowserContext，不依赖真实浏览器）：
 * <ul>
 *   <li>DSL 注册 → 驱动 route() 绑定（恰好一次 / 同 pattern 覆盖不重建绑定）；</li>
 *   <li>事件线程分发（MONITOR → resume，不抛异常）；</li>
 *   <li>句柄 close 幂等注销；</li>
 *   <li>context 关闭事件自动清理运行时。</li>
 * </ul>
 */
public class RouteRuntimeRegisterTest {

    private BrowserContext ctx;
    private Consumer<BrowserContext> closeHandler;

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        org.mockito.Mockito.doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<BrowserContext> handler = inv.getArgument(0);
            closeHandler = handler;
            return null;
        }).when(ctx).onClose(any());
        return ctx;
    }

    @After
    public void cleanup() {
        if (ctx != null) {
            RouteEngine2.shutdown(ctx);
        }
    }

    private Route mockRoute(String url) {
        Route route = mock(Route.class);
        Request request = mock(Request.class);
        when(route.request()).thenReturn(request);
        when(request.url()).thenReturn(url);
        when(request.method()).thenReturn("GET");
        when(request.headers()).thenReturn(Map.of());
        when(request.postData()).thenReturn(null);
        when(request.resourceType()).thenReturn("xhr");
        when(request.isNavigationRequest()).thenReturn(false);
        com.microsoft.playwright.Frame frame = mock(com.microsoft.playwright.Frame.class);
        when(frame.parentFrame()).thenReturn(null);
        when(request.frame()).thenReturn(frame);
        return route;
    }

    @Test
    public void dslRegisterBindsOnceAndSamePatternOverwriteDoesNotRebind() {
        mockContext();
        RouteRuntime runtime = RouteEngine2.runtimeOf(ctx);

        AutoCloseable h1 = RouteDsl2.on(ctx)
                .api("/api/users/**").monitor().expectStatus(200).register();
        AutoCloseable h2 = RouteDsl2.on(ctx)
                .api("/api/users/**").monitor().expectStatus(201).register(); // 同 pattern 覆盖

        verify(ctx, org.mockito.Mockito.times(1)).route(anyString(), any(), any());
        assertNotNull(h1);
        assertNotNull(h2);
        assertEquals("两次 merge 必须产生两代（原子递增）", (long) 2, (long) runtime.metrics().generation());
        // 覆盖后规则表只保留新规则（代际原子发布）
        assertEquals((long) 201L, (long) runtime.generations().snapshot().specFor("/api/users/**").expectStatus());
    }

    @Test
    public void monitorDispatchResumesWithoutException() {
        mockContext();
        RouteRuntime runtime = RouteEngine2.runtimeOf(ctx);

        // 捕获驱动 handler
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Route>> handlerCaptor = ArgumentCaptor.forClass((Class) Consumer.class);
        RouteDsl2.on(ctx).api("/api/users/**").monitor().expectStatus(200).register();
        org.mockito.Mockito.verify(ctx).route(anyString(), handlerCaptor.capture(), any());

        Route route = mockRoute("https://host/api/users/1");
        Consumer<Route> handler = handlerCaptor.getValue();
        handler.accept(route); // 模拟事件线程

        org.mockito.Mockito.verify(route).resume();
        assertEquals("MONITOR 立即终结，不应有残留 claim", (long) 0, (long) runtime.claims().size());
    }

    @Test
    public void handleCloseUnroutesAndIsIdempotent() throws Exception {
        mockContext();
        AtomicInteger unrouteCount = new AtomicInteger(0);
        when(ctx.route(anyString(), any(), any())).thenAnswer(inv -> {
            unrouteCount.incrementAndGet();
            return (AutoCloseable) unrouteCount::incrementAndGet;
        });

        AutoCloseable handle = RouteDsl2.on(ctx)
                .api("/api/mock").mock().status(200).body("{}").register();
        assertEquals((long) 1, (long) unrouteCount.get());

        handle.close();
        assertEquals("route 绑定 + close 注销各一次", (long) 2, (long) unrouteCount.get());
        handle.close(); // 幂等
        assertEquals("重复 close 不得重复注销", (long) 2, (long) unrouteCount.get());
    }

    @Test
    public void contextCloseEventAutoCleansRuntime() {
        mockContext();
        RouteRuntime runtime = RouteEngine2.runtimeOf(ctx);
        assertSame( "runtimeOf 必须幂等", runtime,  RouteEngine2.runtimeOf(ctx));

        closeHandler.accept(ctx); // 模拟 context 关闭
        assertTrue(runtime.isClosed());
        assertEquals("context 关闭后运行时必须从注册表移除", (long) 0, (long) RouteEngine2.activeRuntimes());
    }

    @Test
    public void pageBindingUsesPageContext() {
        BrowserContext pageCtx = mockContext();
        Page page = mock(Page.class);
        when(page.context()).thenReturn(pageCtx);
        // 直接验证 on(page) 落到同一 runtime
        RouteRuntime fromPage = RouteEngine2.runtimeOf(page.context());
        RouteRuntime fromCtx = RouteEngine2.runtimeOf(pageCtx);
        assertSame(fromPage, fromCtx);
    }

    @Test
    public void dslAllCapabilitiesBuild() throws Exception {
        mockContext();
        RouteDsl2.on(ctx).api("/a").monitor().register();
        RouteDsl2.on(ctx).api("/b").mock().status(200).body("{}").register();
        RouteDsl2.on(ctx).api("/c").modifyRequest().setRequestHeader("X-Env", "mock").register();
        RouteDsl2.on(ctx).api("/d").delay(1).done().register().close(); // 级联统一提交后可精确注销

        org.mockito.Mockito.verify(ctx, org.mockito.Mockito.times(4)).route(anyString(), any(), any());
    }

    @Test
    public void closedRuntimeRejectsNewRegistration() {
        mockContext();
        RouteRuntime runtime = RouteEngine2.runtimeOf(ctx);
        closeHandler.accept(ctx);

        boolean threw = false;
        try {
            runtime.register(ApiSpec.builder("/api/x", RouteCapability.MONITOR).build());
        } catch (IllegalStateException expected) {
            threw = true;
        }
        assertTrue( "关闭后的运行时不得接受新注册", threw);
    }

    @Test
    public void unregisteredPatternDispatchesFailOpen() {
        mockContext();
        RouteRuntime runtime = RouteEngine2.runtimeOf(ctx);
        // 无任何注册时，直接分发（防御路径）——不抛异常
        Route route = mockRoute("https://host/api/none");
        runtime.dispatch(route, "/api/none");
        org.mockito.Mockito.verify(route, org.mockito.Mockito.atLeastOnce()).fallback();
    }
}
