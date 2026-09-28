package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.util;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteCapability;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Request;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ApiMatcher 匹配条件验证：method / resourceType / onlyApiCall / header / query /
 * bodyRegex / contentType / frame 语义，全部对齐现有 RouteDsl。
 */
public class ApiMatcherTest {

    private static final String URL = "https://host/api/login?env=test&lang=zh";

    private Request baseRequest() {
        Request request = mock(Request.class);
        when(request.method()).thenReturn("POST");
        when(request.url()).thenReturn(URL);
        when(request.headers()).thenReturn(Map.of(
                "content-type", "application/json;charset=UTF-8",
                "referer", "https://app/page1",
                "origin", "https://app",
                "x-token", "abc123"));
        when(request.postData()).thenReturn("{\"user\":\"admin\"}");
        when(request.resourceType()).thenReturn("xhr");
        when(request.isNavigationRequest()).thenReturn(false);
        Frame main = mock(Frame.class);
        when(main.parentFrame()).thenReturn(null);
        when(main.url()).thenReturn("https://app/main");
        when(request.frame()).thenReturn(main);
        return request;
    }

    @Test
    public void matchesMethodCaseInsensitive() {
        ApiMatcher m = ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR)
                .matchMethod("post").build());
        assertTrue( "POST 必须匹配 'post'（忽略大小写）", m.matches(baseRequest()));
        assertFalse(ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR)
                .matchMethod("GET").build()).matches(baseRequest()));
    }

    @Test
    public void matchesResourceTypes() {
        ApiMatcher xhrOnly = ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR)
                .onlyXhr().build());
        assertTrue(xhrOnly.matches(baseRequest()));

        Request fetchReq = baseRequest();
        when(fetchReq.resourceType()).thenReturn("fetch");
        assertTrue(ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR)
                .onlyApi().build()).matches(fetchReq));
        assertFalse( "onlyXhr 不得匹配 fetch", xhrOnly.matches(fetchReq));
    }

    @Test
    public void onlyApiCallSkipsNavigationAndDefaultsToXhrFetch() {
        ApiSpec spec = ApiSpec.builder("/x", RouteCapability.MONITOR).onlyApiCall(true).build();
        ApiMatcher m = ApiMatcher.from(spec);

        Request nav = baseRequest();
        when(nav.isNavigationRequest()).thenReturn(true);
        assertFalse( "onlyApiCall 必须跳过导航请求", m.matches(nav));

        Request image = baseRequest();
        when(image.resourceType()).thenReturn("image");
        assertFalse( "onlyApiCall 未显式 resourceType 时只匹配 xhr/fetch", m.matches(image));

        assertTrue( "xhr + 非导航必须匹配", m.matches(baseRequest()));
    }

    @Test
    public void matchesHeadersAllSatisfied() {
        ApiMatcher m = ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR)
                .matchHeader("X-Token", "abc123")
                .matchHeader("Content-Type", "application/json;charset=UTF-8")
                .build());
        assertTrue(m.matches(baseRequest()));

        ApiMatcher wrong = ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR)
                .matchHeader("X-Token", "other").build());
        assertFalse(wrong.matches(baseRequest()));
    }

    @Test
    public void matchesQueryParamsAllSatisfied() {
        ApiMatcher m = ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR)
                .matchQuery("env", "test")
                .matchQuery("lang", "zh")
                .build());
        assertTrue(m.matches(baseRequest()));

        ApiMatcher wrong = ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR)
                .matchQuery("env", "prod").build());
        assertFalse(wrong.matches(baseRequest()));
    }

    @Test
    public void matchesBodyRegexOnlyWithBody() {
        ApiMatcher m = ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR)
                .matchBodyRegex("\\\"user\\\"\\s*:\\s*\\\"admin\\\"").build());
        assertTrue(m.matches(baseRequest()));

        ApiMatcher noMatch = ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR)
                .matchBodyRegex("\\\"role\\\"").build());
        assertFalse(noMatch.matches(baseRequest()));

        Request noBody = baseRequest();
        when(noBody.postData()).thenReturn(null);
        assertFalse( "无 body 必须不匹配 bodyRegex 条件", m.matches(noBody));
    }

    @Test
    public void matchesContentTypeContains() {
        ApiMatcher m = ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR)
                .matchContentType("json").build());
        assertTrue( "'json' 必须包含匹配 application/json;charset=UTF-8", m.matches(baseRequest()));

        ApiMatcher wrong = ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR)
                .matchContentType("xml").build());
        assertFalse(wrong.matches(baseRequest()));
    }

    @Test
    public void matchesReferrerAndOriginContains() {
        ApiMatcher m = ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR)
                .matchReferrer("https://app/page")
                .matchOrigin("https://app")
                .build());
        assertTrue(m.matches(baseRequest()));

        ApiMatcher wrong = ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR)
                .matchOrigin("https://evil").build());
        assertFalse(wrong.matches(baseRequest()));
    }

    @Test
    public void onlyMainFrameSkipsIframeAndWorker() {
        Request iframe = baseRequest();
        Frame child = mock(Frame.class);
        when(child.parentFrame()).thenReturn(mock(Frame.class));
        when(iframe.frame()).thenReturn(child);
        assertFalse( "onlyMainFrame 默认 true 必须跳过 iframe 请求", ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR).build())
                .matches(iframe));

        Request worker = baseRequest();
        when(worker.frame()).thenReturn(null);
        assertFalse( "frame 为 null（worker）必须跳过", ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR).build())
                .matches(worker));

        assertTrue( "allowAllFrames 必须放行 iframe", ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR)
                .allowAllFrames().build()).matches(iframe));
    }

    @Test
    public void matchesFrameUrlContains() {
        ApiMatcher m = ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR)
                .matchFrameUrl("app/main").build());
        assertTrue(m.matches(baseRequest()));
        assertFalse(ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR)
                .matchFrameUrl("other/page").build()).matches(baseRequest()));
    }

    @Test
    public void noConditionsMatchesEverything() {
        assertTrue(ApiMatcher.from(ApiSpec.builder("/x", RouteCapability.MONITOR).build())
                .matches(baseRequest()));
    }

    @Test
    public void parseQueryDecodesUtf8AndHandlesMissingValue() {
        assertEquals((Object) Map.of("env", "test"), (Object) ApiMatcher.parseQuery("https://h/a?env=test"));
        String encoded = java.net.URLEncoder.encode("张 三", java.nio.charset.StandardCharsets.UTF_8);
        assertEquals((Object) Map.of("name", "张 三"), (Object) ApiMatcher.parseQuery("https://h/a?name=" + encoded));
        assertEquals((Object) Map.of("flag", ""), (Object) ApiMatcher.parseQuery("https://h/a?flag"));
        assertEquals((Object) Map.of("k", "v"), (Object) ApiMatcher.parseQuery("https://h/a?k=v#frag"));
    }
}
