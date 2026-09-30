package com.hsbc.cmb.hk.dbb.automation.framework.route.v2;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteDsl2;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 匹配条件 → 分发裁决验证（mock BrowserContext，不依赖真实浏览器）：
 * <ul>
 *   <li>条件不满足 → 驱动级 fallback（进 Router 链，落到下一个匹配 pattern）；claim 立即清理；</li>
 *   <li>条件满足 → 能力正常执行（MOCK fulfill / MONITOR resume）；</li>
 *   <li>DSL 匹配方法（matchMethod/onlyApi/matchHeader/…）可流式链到各能力。</li>
 * </ul>
 */
public class RouteMatchFilterTest {

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

    private Route mockRoute(String url, String method, String resourceType) {
        Route route = mock(Route.class);
        Request request = mock(Request.class);
        when(route.request()).thenReturn(request);
        when(request.url()).thenReturn(url);
        when(request.method()).thenReturn(method);
        when(request.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(request.postData()).thenReturn("{\"a\":1}");
        when(request.resourceType()).thenReturn(resourceType);
        when(request.isNavigationRequest()).thenReturn(false);
        com.microsoft.playwright.Frame frame = mock(com.microsoft.playwright.Frame.class);
        when(frame.parentFrame()).thenReturn(null);
        when(request.frame()).thenReturn(frame);
        return route;
    }

    /** 注册规则并取出驱动 handler（模拟事件线程触发）。 */
    private Consumer<Route> captureHandler() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Route>> captor = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(ctx).route(anyString(), captor.capture(), any());
        return captor.getValue();
    }

    @Test
    public void methodMismatchFallsBackAndCleansClaim() {
        mockContext();
        RouteRuntime runtime = RouteEngine2.runtimeOf(ctx);

        RouteDsl2.on(ctx)
                .api("/api/login").matchMethod("POST")
                .mock().status(200).body("{}").register();
        Consumer<Route> handler = captureHandler();

        // GET 请求命中 pattern 但条件不满足 → fallback（驱动链继续），不 fulfill
        Route route = mockRoute("https://host/api/login", "GET", "xhr");
        handler.accept(route);

        verify(route, never()).fulfill(any());
        verify(route).fallback();
        assertEquals("条件不满足必须立即清理 claim，不残留", (long) 0, (long) runtime.claims().size());
    }

    @Test
    public void conditionsSatisfiedExecutesCapability() {
        mockContext();
        RouteRuntime runtime = RouteEngine2.runtimeOf(ctx);

        RouteDsl2.on(ctx)
                .api("/api/login")
                .onlyApi()
                .matchMethod("POST")
                .matchHeader("content-type", "application/json")
                .matchQuery("env", "test")
                .mock().status(200).body("{\"token\":\"t\"}").register();
        Consumer<Route> handler = captureHandler();

        Route route = mockRoute("https://host/api/login?env=test", "POST", "xhr");
        handler.accept(route);

        verify(route).fulfill(any());
        assertEquals("MOCK 立即终结，不应残留 claim", (long) 0, (long) runtime.claims().size());
    }

    @Test
    public void monitorWithMatchConditionsResumesOnlyOnMatch() {
        mockContext();
        RouteDsl2.on(ctx)
                .api("/api/users/**").onlyApi().matchQuery("page", "1")
                .monitor().expectStatus(200).register();
        Consumer<Route> handler = captureHandler();

        // 条件满足 → resume
        Route matched = mockRoute("https://host/api/users?page=1", "GET", "xhr");
        handler.accept(matched);
        verify(matched).resume();

        // 条件不满足 → fallback
        Route unmatched = mockRoute("https://host/api/users?page=2", "GET", "xhr");
        handler.accept(unmatched);
        verify(unmatched).fallback();
    }

    @Test
    public void delayWithConditionsKeepsChainSemantics() throws Exception {
        mockContext();
        RouteDsl2.on(ctx)
                .api("/api/pay/**").onlyFetch()
                .delay(10).done().start();
        //  句柄刻意保持开启（不再链式 .close()）：本用例断言的是"派发 / 链式裁决"语义；
        //  自 P2 起句柄 close() 会同时注销内存规则表条目（旧实现只摘驱动绑定、内存规则仍可派发 —— 语义缺口）。
        Consumer<Route> handler = captureHandler();

        // fetch 满足 → 走延迟 IO 分支（事件线程不得抛异常；最终由 IO/sweep 终结）
        Route fetchReq = mockRoute("https://host/api/pay/1", "GET", "fetch");
        handler.accept(fetchReq);
        verify(fetchReq, never()).fallback();

        // xhr 不满足 onlyFetch → fallback
        Route xhrReq = mockRoute("https://host/api/pay/2", "GET", "xhr");
        handler.accept(xhrReq);
        verify(xhrReq).fallback();
    }

    @Test
    public void allowAllRequestsDisablesAllFilters() {
        mockContext();
        RouteDsl2.on(ctx)
                .api("/api/asset/**").allowAllRequests()
                .mock().status(204).body("").register();
        Consumer<Route> handler = captureHandler();

        // 导航 + iframe 请求（默认 onlyMainFrame 会跳过）→ allowAllRequests 放行
        Route route = mockRoute("https://host/api/asset/logo.png", "GET", "document");
        when(route.request().frame().parentFrame()).thenReturn(mock(com.microsoft.playwright.Frame.class));
        when(route.request().isNavigationRequest()).thenReturn(true);
        handler.accept(route);

        verify(route).fulfill(any());
    }

    @Test
    public void matcherCacheIsSharedAcrossDispatches() {
        mockContext();
        RouteRuntime runtime = RouteEngine2.runtimeOf(ctx);
        RouteDsl2.on(ctx)
                .api("/api/s").matchMethod("POST")
                .monitor().expectStatus(200).register();
        Consumer<Route> handler = captureHandler();

        // 同一 spec 多次 dispatch：全部走同一编译匹配器，行为一致
        for (int i = 0; i < 3; i++) {
            Route matched = mockRoute("https://host/api/s", "POST", "xhr");
            handler.accept(matched);
            verify(matched).resume();
        }
        assertNotNull(runtime.metrics());
    }
}
