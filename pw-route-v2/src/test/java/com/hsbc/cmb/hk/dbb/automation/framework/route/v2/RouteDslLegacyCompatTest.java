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

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 老仓 RouteDsl 语法全量兼容（业务层引用不受影响）：
 * <ul>
 *   <li>老版 javadoc 四类示例（monitor/mock/modify/delay）逐字编译运行；</li>
 *   <li>老命名别名：mockBody(String/byte[]/Object) / mockStatus / mockHeader /
 *       mockBodyFromFile(String, Map) / setRequestHeaders / modifyMethod；</li>
 *   <li>条件字段修改 when(...).thenSet(...)（dispatch 级行为验证）；</li>
 *   <li>randomDelay / record / minMatches / autoStopOnMatch；</li>
 *   <li>getContext / clear(实例+静态) / resetAll / clearAllRules / stopX(Object, String)。</li>
 * </ul>
 */
public class RouteDslLegacyCompatTest {

    private BrowserContext ctx;
    private RouteRuntime runtime;

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        org.mockito.Mockito.doAnswer(inv -> null).when(ctx).onClose(any());
        runtime = RouteEngine2.runtimeOf(ctx);
        return ctx;
    }

    @After
    public void cleanup() {
        if (ctx != null) {
            RouteEngine2.shutdown(ctx);
        }
    }

    private Consumer<Route> handlerFor(String urlPattern) {
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Route>> handlerCaptor = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(ctx, org.mockito.Mockito.atLeastOnce()).route(urlCaptor.capture(), handlerCaptor.capture(), any());
        List<String> urls = urlCaptor.getAllValues();
        List<Consumer<Route>> handlers = handlerCaptor.getAllValues();
        for (int i = urls.size() - 1; i >= 0; i--) {
            if (urls.get(i).equals(RoutePatterns.normalize(urlPattern))) {
                return handlers.get(i);
            }
        }
        throw new AssertionError("no handler bound for pattern: " + urlPattern);
    }

    private Request mockRequest(String url, String method) {
        Request request = mock(Request.class);
        when(request.url()).thenReturn(url);
        when(request.method()).thenReturn(method);
        when(request.headers()).thenReturn(Map.of());
        when(request.postData()).thenReturn(null);
        when(request.resourceType()).thenReturn("xhr");
        when(request.isNavigationRequest()).thenReturn(false);
        Frame frame = mock(Frame.class);
        when(frame.parentFrame()).thenReturn(null);
        when(request.frame()).thenReturn(frame);
        return request;
    }

    // ── 老版 javadoc 四类示例逐字兼容 ──

    @Test
    public void legacyMonitorSampleRunsVerbatim() {
        mockContext();
        RouteDsl2.on(ctx)
                .api("/api/users")
                .monitor()
                .expectStatus(200)
                .expectJsonPath("$.code", 200)
                .timeout(30)
                .done()
                .start();
        RouteDsl2.clear(ctx);
        assertTrue(true);
    }

    @Test
    public void legacyMockSampleRunsVerbatim() {
        mockContext();
        RouteDsl2.on(ctx)
                .api("/api/users/1")
                .mock()
                .mockBody("{\"code\":200}")
                .mockStatus(200)
                .done()
                .start();
        RouteDsl2.clear(ctx);
    }

    @Test
    public void legacyModifySampleRunsVerbatim() {
        mockContext();
        RouteDsl2.on(ctx)
                .api("/api/users")
                .modifyRequest()
                .modifyRequestBody("$.role", "ADMIN")
                .modifyMethod("PUT")
                .done()
                .start();
        RouteDsl2.clear(ctx);
    }

    @Test
    public void legacyDelaySampleRunsVerbatim() {
        mockContext();
        RouteDsl2.on(ctx)
                .api("/api/**")
                .delay(3)
                .done()
                .start();
        RouteDsl2.clear(ctx);
    }

    // ── 老命名别名 ──

    @Test
    public void mockBodyAsObjectSerializesJson() {
        mockContext();
        RouteDsl2.on(ctx)
                .api("/api/o").mock().mockBody(Map.of("code", 0, "ok", true)).mockStatus(200)
                .done().start();

        Consumer<Route> handler = handlerFor("/api/o");
        Request req = mockRequest("https://host/api/o", "GET");
        Route route = mock(Route.class);
        when(route.request()).thenReturn(req);
        handler.accept(route);

        ArgumentCaptor<Route.FulfillOptions> captor = ArgumentCaptor.forClass(Route.FulfillOptions.class);
        verify(route).fulfill(captor.capture());
        assertTrue( "Object mock body 必须序列化为 JSON", captor.getValue().body.contains("\"ok\":true"));
    }

    @Test
    public void mockBodyFromFileWithOverridesReplacesFields() {
        mockContext();
        RouteDsl2.on(ctx)
                .api("/api/login").mock()
                .mockBodyFromFile("mocks/login-template.json", Map.of("$.token", "fake-token"))
                .mockStatus(200)
                .done().start();

        Consumer<Route> handler = handlerFor("/api/login");
        Request req = mockRequest("https://host/api/login", "GET");
        Route route = mock(Route.class);
        when(route.request()).thenReturn(req);
        handler.accept(route);
        runtime.io().close();

        ArgumentCaptor<Route.FulfillOptions> captor = ArgumentCaptor.forClass(Route.FulfillOptions.class);
        verify(route).fulfill(captor.capture());
        assertTrue( "mockBodyFromFile(file,map) 必须批量替换字段", captor.getValue().body.contains("fake-token"));
    }

    // ── 条件字段修改 when(...).thenSet(...)（dispatch 级行为）──

    @Test
    public void conditionalWhenThenSetAppliesOnlyWhenConditionMet() {
        mockContext();
        RouteDsl2.on(ctx)
                .api("/api/c").mock().status(200).contentType("application/json")
                .body("{\"users\":[{\"status\":\"ACTIVE\",\"vip\":false},{\"status\":\"LOCKED\",\"vip\":false}]}")
                .when("$.users[?(@.status=='ACTIVE')].status", "EXISTS", null)
                .thenSet("$.users[0].vip", true)
                .done().start();

        Consumer<Route> handler = handlerFor("/api/c");
        Request req = mockRequest("https://host/api/c", "GET");
        Route route = mock(Route.class);
        when(route.request()).thenReturn(req);
        handler.accept(route);
        runtime.io().close();

        ArgumentCaptor<Route.FulfillOptions> captor = ArgumentCaptor.forClass(Route.FulfillOptions.class);
        verify(route).fulfill(captor.capture());
        assertTrue(
                "条件满足时 thenSet 必须生效", captor.getValue().body.contains("\"vip\":true"));
        assertTrue(
                "不影响其它元素", captor.getValue().body.contains("\"status\":\"LOCKED\""));
    }

    @Test
    public void conditionalWhenThenSetSkipsWhenConditionNotMet() {
        mockContext();
        RouteDsl2.on(ctx)
                .api("/api/c2").mock().status(200).contentType("application/json")
                .body("{\"users\":[{\"status\":\"LOCKED\",\"vip\":false}]}")
                .when("$.users[0].status", "EQUALS", "ACTIVE")
                .thenSet("$.users[0].vip", true)
                .done().start();

        Consumer<Route> handler = handlerFor("/api/c2");
        Request req = mockRequest("https://host/api/c2", "GET");
        Route route = mock(Route.class);
        when(route.request()).thenReturn(req);
        handler.accept(route);
        runtime.io().close();

        ArgumentCaptor<Route.FulfillOptions> captor = ArgumentCaptor.forClass(Route.FulfillOptions.class);
        verify(route).fulfill(captor.capture());
        assertTrue(
                "条件不满足时保留原值", captor.getValue().body.contains("\"vip\":false"));
    }

    // ── setRequestHeader/removeRequestHeader：合并语义（对齐官方 javadoc 示例）──

    @Test
    public void setRequestHeadersMergeWithOriginalHeaders() throws Exception {
        mockContext();
        RouteDsl2.on(ctx)
                .api("/api/h").modifyRequest()
                .setRequestHeader("x-new", "2")
                .removeRequestHeader("x-old")
                .done().start();
        Consumer<Route> handler = handlerFor("/api/h");

        Request request = mockRequest("https://host/api/h", "POST");
        when(request.headers()).thenReturn(new java.util.LinkedHashMap<>(Map.of(
                "x-keep", "1", "x-old", "gone")));
        Route route = mock(Route.class);
        when(route.request()).thenReturn(request);
        handler.accept(route);

        ArgumentCaptor<Route.ResumeOptions> captor = ArgumentCaptor.forClass(Route.ResumeOptions.class);
        verify(route).resume(captor.capture());
        Map<String, String> headers = captor.getValue().headers;
        assertEquals("未指定 header 必须保留（合并语义）", (Object) "1", (Object) headers.get("x-keep"));
        assertEquals("set 的 header 生效", (Object) "2", (Object) headers.get("x-new"));
        assertFalse( "remove 的 header 被移除", headers.containsKey("x-old"));
        // 与官方 javadoc 示例一致：resume(options.headers) 传的是「原集+增删」后的全量，
        // 而非只传修改项（只传修改项会丢掉其它 header）
    }

    // ── modifyMethod：resume(options.setMethod) 单请求实现（官方 ResumeOptions 支持 setMethod）──

    @Test
    public void modifyMethodResumesWithNewMethod() throws Exception {
        mockContext();
        RouteDsl2.on(ctx)
                .api("/api/m").modifyRequest().modifyMethod("PUT")
                .done().start();
        Consumer<Route> handler = handlerFor("/api/m");

        Request request = mockRequest("https://host/api/m", "POST");
        Route route = mock(Route.class);
        when(route.request()).thenReturn(request);
        handler.accept(route);
        // 无 body 修改 → 事件线程直接 resume，无需 IO 线程
        runtime.io().close();

        ArgumentCaptor<Route.ResumeOptions> resumeCaptor = ArgumentCaptor.forClass(Route.ResumeOptions.class);
        verify(route).resume(resumeCaptor.capture());
        assertEquals("resume 必须携带修改后的 method", (Object) "PUT", (Object) resumeCaptor.getValue().method);
        verify(route, org.mockito.Mockito.never()).fetch(any());
        verify(route, org.mockito.Mockito.never()).fulfill(any());
    }

    // ── 生命周期与门面 ──

    @Test
    public void getContextReturnsBoundContext() {
        mockContext();
        RouteDsl2 dsl = RouteDsl2.on(ctx);
        assertSame(ctx, dsl.getContext());
    }

    @Test
    public void instanceClearIsIdempotent() throws Exception {
        mockContext();
        RouteDsl2 dsl = RouteDsl2.on(ctx)
                .api("/a").monitor().expectStatus(200).timeout(60).done()
                .api("/b").mock().status(200).body("{}").done();
        dsl.start().close();
        dsl.clear();
        dsl.clear(); // 幂等
        assertTrue(true);
    }

    @Test
    public void staticClearAndResetAllShutdownRuntimes() {
        mockContext();
        RouteDsl2.on(ctx).api("/a").monitor().expectStatus(200).timeout(60).done().start();
        assertEquals((long) 1, (long) RouteEngine2.activeRuntimes());

        RouteDsl2.clear(ctx);
        assertEquals("clear(context) 必须关闭 V2 运行时", (long) 0, (long) RouteEngine2.activeRuntimes());

        mockContext();
        RouteDsl2.on(ctx).api("/a").monitor().expectStatus(200).timeout(60).done().start();
        RouteDsl2.clearAllRules();
        assertEquals("clearAllRules 必须全关", (long) 0, (long) RouteEngine2.activeRuntimes());

        mockContext();
        RouteDsl2.on(ctx).api("/a").monitor().expectStatus(200).timeout(60).done().start();
        RouteDsl2.resetAll();
        assertEquals("resetAll 必须全关", (long) 0, (long) RouteEngine2.activeRuntimes());
    }

    @Test
    public void stopXObjectOverloadResolvesPageAndContext() {
        mockContext();
        RouteDsl2.on(ctx).api("/api/s").mock().status(200).body("{}").done().start();

        // Object 重载：BrowserContext
        assertTrue( "Object 重载必须解析 BrowserContext", RouteDsl2.stopApi((Object) ctx, "/api/s"));

        // Object 重载：Page
        mockContext();
        com.microsoft.playwright.Page page = mock(com.microsoft.playwright.Page.class);
        when(page.context()).thenReturn(ctx);
        RouteDsl2.on(ctx).api("/api/s2").mock().status(200).body("{}").done().start();
        assertTrue( "Object 重载必须解析 Page", RouteDsl2.stopMock((Object) page, "/api/s2"));
    }
}
