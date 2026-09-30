package com.hsbc.cmb.hk.dbb.automation.framework.route;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.RoutePatterns;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 级联 DSL（对齐现有 RouteDsl 的 done()/start() 语法）：
 * <ul>
 *   <li>一次 on() 声明多段 api(...).capability(...).done()，start() 统一提交——逐条 CAS 注册，无竞态；</li>
 *   <li>done() 仅内存累积（注册前 route() 零绑定）；start() 返回合并注销句柄（优于老版 void）；</li>
 *   <li>同 pattern 后段覆盖前段（V2 replace 语义）；timeout(long) 秒版对齐老版单位。</li>
 * </ul>
 */
public class RouteDslCascadeTest {

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
            RouteEngine.shutdown(ctx);
        }
    }

    private Request mockRequest(String url) {
        Request request = mock(Request.class);
        when(request.url()).thenReturn(url);
        when(request.method()).thenReturn("GET");
        when(request.headers()).thenReturn(Map.of());
        when(request.postData()).thenReturn(null);
        when(request.resourceType()).thenReturn("xhr");
        when(request.isNavigationRequest()).thenReturn(false);
        Frame frame = mock(Frame.class);
        when(frame.parentFrame()).thenReturn(null);
        when(request.frame()).thenReturn(frame);
        return request;
    }

    /** 取出绑定到指定 pattern 的驱动 handler（级联多 pattern 时取最后一个注册的生效 handler）。 */
    private Consumer<Route> handlerFor(String urlPattern) {
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Route>> handlerCaptor = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(ctx, org.mockito.Mockito.atLeastOnce()).route(urlCaptor.capture(), handlerCaptor.capture(), any());
        java.util.List<String> urls = urlCaptor.getAllValues();
        java.util.List<Consumer<Route>> handlers = handlerCaptor.getAllValues();
        for (int i = urls.size() - 1; i >= 0; i--) {
            if (urls.get(i).equals(RoutePatterns.normalize(urlPattern))) {
                return handlers.get(i);
            }
        }
        throw new AssertionError("no handler bound for pattern: " + urlPattern);
    }

    /** 用户提供的真实业务级联写法（逐字兼容，monitor×4 + mock×1）。 */
    @Test
    public void legacyStyleCascadeRegistersEverySegment() throws Exception {
        mockContext();

        RouteDsl dsl = RouteDsl.on(ctx)
                .api("/error/profile-error.jsp").monitor().expectStatus(200).timeout(60).done()
                .api("auth/assert").monitor().expectStatus(200).timeout(60).done()
                .api("j_spring_security-check_v2").monitor().expectStatus(302).timeout(60).done()
                .api("leftmenu/permissionLeftMenuConfig").monitor().expectStatus(200)
                .expectJsonPath("enableAdminTools", "Y").timeout(60).done()
                .api("profile/list").mock().interceptResponse()
                .mockReplaceField("updateContctOverlayFlag", false)
                .mockReplaceField("isOverBlockedDate", false)
                .done();
        dsl.start();

        org.mockito.Mockito.verify(ctx, org.mockito.Mockito.times(5)).route(anyString(), any(), any());
        dsl.clear(); // 幂等注销
        dsl.clear();
        assertTrue(true);
    }

    @Test
    public void doneOnlyAccumulatesWithoutBindingRoutes() {
        mockContext();

        RouteDsl.on(ctx)
                .api("/a").monitor().expectStatus(200).timeout(60).done()
                .api("/b").mock().status(200).body("{}").done();

        verify(ctx, never()).route(anyString(), any(), any());

        RouteDsl.on(ctx)
                .api("/c").mock().status(200).body("{}").register();

        verify(ctx).route(anyString(), any(), any()); // register 才绑定
    }

    @Test
    public void cascadeMockSegmentFulfillsOnDispatch() {
        mockContext();
        RouteDsl.on(ctx)
                .api("/a").monitor().expectStatus(200).timeout(60).done()
                .api("/b").mock().status(200).body("{\"b\":1}").done()
                .start();

        Consumer<Route> handler = handlerFor("/b");
        Request req = mockRequest("/b");
        Route route = mock(Route.class);
        when(route.request()).thenReturn(req);
        handler.accept(route);

        ArgumentCaptor<Route.FulfillOptions> captor =
                ArgumentCaptor.forClass(Route.FulfillOptions.class);
        verify(route).fulfill(captor.capture());
        assertEquals((Object) "{\"b\":1}", (Object) captor.getValue().body);
    }

    @Test
    public void laterSegmentOverridesSamePattern() {
        mockContext();
        RouteDsl.on(ctx)
                .api("/dup").mock().status(200).body("{\"v\":1}").done()
                .api("/dup").mock().status(200).body("{\"v\":2}").done()
                .start();

        Consumer<Route> handler = handlerFor("/dup");
        Request req = mockRequest("/dup");
        Route route = mock(Route.class);
        when(route.request()).thenReturn(req);
        handler.accept(route);

        ArgumentCaptor<Route.FulfillOptions> captor =
                ArgumentCaptor.forClass(Route.FulfillOptions.class);
        verify(route).fulfill(captor.capture());
        assertEquals("同 pattern 后段覆盖前段（V2 replace 语义）", (Object) "{\"v\":2}", (Object) captor.getValue().body);
    }

    @Test
    public void monitorSegmentDoesNotIntervene() {
        mockContext();
        RouteDsl.on(ctx)
                .api("/a").monitor().expectStatus(200).timeout(60).done()
                .api("/m").monitor().expectStatus(200).timeout(60).done()
                .start();

        Consumer<Route> handler = handlerFor("/m");
        Request req = mockRequest("/m");
        Route route = mock(Route.class);
        when(route.request()).thenReturn(req);
        APIResponse resp = mock(APIResponse.class);
        when(resp.status()).thenReturn(200);
        when(resp.body()).thenReturn("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(route.fetch(any())).thenReturn(resp);
        handler.accept(route); // 事件线程零阻塞，不得抛

        verify(route, never()).fulfill(any());
        verify(route, never()).resume(any());
    }

    @Test
    public void registerCommitsAllPendingIncludingDoneSegments() throws Exception {
        mockContext();
        AutoCloseable handle = RouteDsl.on(ctx)
                .api("/a").monitor().expectStatus(200).timeout(60).done()
                .api("/b").mock().status(200).body("{}")
                .register(); // register 提交 pending 中全部（含 done 累积段）

        Consumer<Route> handler = handlerFor("/b");
        Request req = mockRequest("/b");
        Route route = mock(Route.class);
        when(route.request()).thenReturn(req);
        handler.accept(route);
        verify(route).fulfill(any());

        handle.close(); // 合并句柄幂等
        handle.close();
    }

    @Test
    public void timeoutRejectsNegative() {
        mockContext();
        try {
            RouteDsl.on(ctx).api("/a").monitor().timeout(-1);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期抛出
        }
    }
}
