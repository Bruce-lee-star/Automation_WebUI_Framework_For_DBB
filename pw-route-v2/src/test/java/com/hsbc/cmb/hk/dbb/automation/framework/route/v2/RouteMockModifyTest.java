package com.hsbc.cmb.hk.dbb.automation.framework.route.v2;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteDsl2;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第二批能力 dispatch 级验证（mock BrowserContext）：
 * <ul>
 *   <li>MOCK 静态体 + 字段替换（IO 线程替换后 fulfill 替换后 body）；</li>
 *   <li>MOCK intercept + 字段替换（快照 + context.request() 取真实响应 → 替换 → fulfill）；</li>
 *   <li>MODIFY body 级修改（IO 线程改 postData → resume 携带新 body）；</li>
 *   <li>mockBodyFromFile（classpath 资源读取）。</li>
 * </ul>
 */
public class RouteMockModifyTest {

    private BrowserContext ctx;

    /** runtime 所属 Context 的 APIRequestContext（intercept 取响应用），供用例 stub。 */
    private APIRequestContext lastApiCtx;

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        org.mockito.Mockito.doAnswer(inv -> null).when(ctx).onClose(any());
        // 方案 A：intercept 取响应走 runtime.request()（= context.request()），这里把 context 的 APIRequestContext 接上
        lastApiCtx = mock(APIRequestContext.class);
        when(ctx.request()).thenReturn(lastApiCtx);
        return ctx;
    }

    @After
    public void cleanup() {
        if (ctx != null) {
            RouteEngine2.shutdown(ctx);
        }
    }

    private Request mockRequestWithBody(String url, String method, String postData, String contentType) {
        Request request = mock(Request.class);
        when(request.url()).thenReturn(url);
        when(request.method()).thenReturn(method);
        when(request.headers()).thenReturn(contentType == null ? Map.of() : Map.of("content-type", contentType));
        when(request.postData()).thenReturn(postData);
        when(request.resourceType()).thenReturn("xhr");
        when(request.isNavigationRequest()).thenReturn(false);
        Frame frame = mock(Frame.class);
        when(frame.parentFrame()).thenReturn(null);
        when(request.frame()).thenReturn(frame);
        return request;
    }

    private Route mockRoute(Request request) {
        Route route = mock(Route.class);
        when(route.request()).thenReturn(request);
        return route;
    }

    /** 注册规则并取出驱动 handler。 */
    private Consumer<Route> captureHandler() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Route>> captor = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(ctx).route(anyString(), captor.capture(), any());
        return captor.getValue();
    }

    @Test
    public void mockStaticBodyWithReplacementFulfillsReplacedBody() throws Exception {
        mockContext();
        RouteRuntime runtime = RouteEngine2.runtimeOf(ctx);

        RouteDsl2.on(ctx)
                .api("/api/user")
                .mock().status(200).contentType("application/json")
                .body("{\"code\":0,\"name\":\"old\"}")
                .replaceField("$.code", 1)
                .register();
        Consumer<Route> handler = captureHandler();

        Route route = mockRoute(mockRequestWithBody("https://host/api/user", "GET", null, null));
        handler.accept(route);
        runtime.io().close(); // 等待 IO 替换任务落定

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Route.FulfillOptions> captor = ArgumentCaptor.forClass((Class) Route.FulfillOptions.class);
        verify(route).fulfill(captor.capture());
        assertEquals("静态体必须被字段替换", (Object) "{\"code\":1,\"name\":\"old\"}", (Object) captor.getValue().body);
        assertEquals((long) 200L, (long) captor.getValue().status);
    }

    @Test
    public void mockInterceptWithReplacementFetchesThenReplaces() throws Exception {
        mockContext();
        RouteRuntime runtime = RouteEngine2.runtimeOf(ctx);

        APIResponse apiResponse = mock(APIResponse.class);
        when(apiResponse.status()).thenReturn(200);
        when(apiResponse.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(apiResponse.body()).thenReturn("{\"code\":0,\"name\":\"real\"}".getBytes(StandardCharsets.UTF_8));

        RouteDsl2.on(ctx)
                .api("/api/profile")
                .mock().interceptResponse().mockReplaceField("$.code", 1)
                .register();
        Consumer<Route> handler = captureHandler();

        Request request = mockRequestWithBody("https://host/api/profile", "GET", null, null);
        Route route = mockRoute(request);
        // 方案 A：真实响应由 runtime 所属 Context 的 APIRequestContext 取（不再走 route.fetch）
        when(lastApiCtx.fetch(anyString(), any())).thenReturn(apiResponse);
        handler.accept(route);
        runtime.io().close();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Route.FulfillOptions> captor = ArgumentCaptor.forClass((Class) Route.FulfillOptions.class);
        verify(route).fulfill(captor.capture());
        assertEquals("真实响应必须被字段替换", (Object) "{\"code\":1,\"name\":\"real\"}", (Object) captor.getValue().body);
        assertEquals((long) 200L, (long) captor.getValue().status);
    }

    @Test
    public void modifyBodyResumesWithPostData() throws Exception {
        mockContext();
        RouteRuntime runtime = RouteEngine2.runtimeOf(ctx);

        RouteDsl2.on(ctx)
                .api("/api/login")
                .modifyRequest()
                .setRequestHeader("X-Env", "mock")
                .modifyRequestBody("$.password", "changed")
                .register();
        Consumer<Route> handler = captureHandler();

        Route route = mockRoute(mockRequestWithBody("https://host/api/login", "POST",
                "{\"user\":\"u\",\"password\":\"old\"}", "application/json"));
        handler.accept(route);
        runtime.io().close(); // 等待 body 修改任务落定

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Route.ResumeOptions> captor = ArgumentCaptor.forClass((Class) Route.ResumeOptions.class);
        verify(route).resume(captor.capture());
        Route.ResumeOptions options = captor.getValue();
        assertEquals("请求体必须被修改", (Object) "{\"user\":\"u\",\"password\":\"changed\"}", (Object) options.postData);
        assertEquals("header 修改必须同时生效", (Object) "mock", (Object) options.headers.get("X-Env"));
    }

    @Test
    public void modifyBodyOnNonJsonRequestFailsOpenWithOriginalBody() throws Exception {
        mockContext();
        RouteRuntime runtime = RouteEngine2.runtimeOf(ctx);

        RouteDsl2.on(ctx)
                .api("/api/bin")
                .modifyRequest().modifyRequestBody("$.a", "1")
                .register();
        Consumer<Route> handler = captureHandler();

        // 未知 content-type：明确拒绝修改，fail-open 原样 resume
        Route route = mockRoute(mockRequestWithBody("https://host/api/bin", "POST",
                "binary-data", "application/octet-stream"));
        handler.accept(route);
        runtime.io().close();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Route.ResumeOptions> captor = ArgumentCaptor.forClass((Class) Route.ResumeOptions.class);
        verify(route).resume(captor.capture());
        assertEquals("非适用类型必须原样 resume", (Object) "binary-data", (Object) captor.getValue().postData);
    }

    @Test
    public void mockBodyFromFileLoadsClasspathResource() throws Exception {
        mockContext();
        RouteRuntime runtime = RouteEngine2.runtimeOf(ctx);

        RouteDsl2.on(ctx)
                .api("/api/fromfile")
                .mock().mockBodyFromFile("mocks/login-template.json")
                .replaceField("$.token", "replaced")
                .status(200).contentType("application/json")
                .register();
        Consumer<Route> handler = captureHandler();

        Route route = mockRoute(mockRequestWithBody("https://host/api/fromfile", "GET", null, null));
        handler.accept(route);
        runtime.io().close();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Route.FulfillOptions> captor = ArgumentCaptor.forClass((Class) Route.FulfillOptions.class);
        verify(route).fulfill(captor.capture());
        String body = captor.getValue().body;
        assertTrue( "文件模板必须被字段替换", body.contains("replaced"));
        assertTrue( "文件内容必须被加载（未替换字段保留）", body.contains("\"user\":\"u\""));
    }

    @Test
    public void interceptConflictsWithStaticBodyAtBuildTime() {
        mockContext();
        boolean threw = false;
        try {
            RouteDsl2.on(ctx)
                    .api("/api/x")
                    .mock().interceptResponse().body("{}")
                    .register();
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assertTrue( "interceptResponse 与静态 body 必须互斥", threw);
    }
}
