package com.microsoft.playwright.impl;

import com.microsoft.playwright.Route;
import org.junit.Test;

import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DBBN-PATCH-02 守门测试（2026-09-29）。
 *
 * <p><b>被守卫的缺陷</b>：上游在**每一次命中 handler 之后**都向驱动重发全量拦截 pattern
 * （{@code BrowserContextImpl.handleRoute} 与 {@code PageImpl} 的 {@code route} 事件分支），
 * 而 {@code Router.handle()} 内部唯一会改变 pattern 列表的动作只有"某条规则的 {@code times} 配额用尽 ⇒ 条目被移除"。
 * 在长活端点（SSE 断流自动重连）上，每次重连都触发一次全量下发，把单线程客户端排满
 * ⇒ 其他协议调用（{@code context.route()} / {@code unroute()}）界内收不到 ack ⇒ 框架侧 30s 超时。</p>
 *
 * <p>本测试直接对 {@link Router#handleOutcome(RouteImpl)} 断言"是否需要重发"，因此**不依赖驱动/浏览器**，
 * 且能精确区分"命中但列表未变"（不得重发）与"times 用尽"（必须重发）两侧。</p>
 *
 * <p>依据文档：{@code docs/patches/playwright-java-1.62.0-dbb-patch-02.md}。</p>
 */
public class DbbPatch02GuardTest {

    @Test
    public void handledRouteWithoutTableChangeMustNotResendPatterns() {
        Router router = new Router();
        router.add(new UrlMatcher(Pattern.compile("api/x")), route -> { }, null);
        RouteImpl route = mockRoute("https://host/api/x", true);

        Router.HandleOutcome outcome = router.handleOutcome(route);

        assertEquals(Router.HandleResult.Handled, outcome.result);
        assertFalse("命中但规则表未变 ⇒ 不得重发全量 pattern（补丁前这里会重发）",
                outcome.patternsChanged);
    }

    @Test
    public void exhaustedTimesBudgetMustReportPatternChange() {
        Router router = new Router();
        router.add(new UrlMatcher(Pattern.compile("api/x")), route -> { }, 1);
        RouteImpl route = mockRoute("https://host/api/x", true);

        Router.HandleOutcome outcome = router.handleOutcome(route);

        assertEquals(Router.HandleResult.Handled, outcome.result);
        assertTrue("times 用尽 ⇒ 条目被移除 ⇒ 必须重发全量 pattern（不可收窄掉这条）",
                outcome.patternsChanged);
    }

    @Test
    public void fallbackWithoutTableChangeMustNotResendPatterns() {
        Router router = new Router();
        router.add(new UrlMatcher(Pattern.compile("api/y")), Route::fallback, null);
        RouteImpl route = mockRoute("https://host/api/y", false);
        doAnswer(inv -> {
            route.fallbackCalled = true;
            return null;
        }).when(route).fallback();

        Router.HandleOutcome outcome = router.handleOutcome(route);

        assertEquals(Router.HandleResult.Fallback, outcome.result);
        assertFalse("fallback 不改动规则表 ⇒ 不得重发全量 pattern（这正是本补丁消除的风暴）",
                outcome.patternsChanged);
    }

    /** 被测代码只读 {@code request().url()} 与 {@code isHandled()}，故替身足够。 */
    private static RouteImpl mockRoute(String url, boolean handled) {
        RouteImpl route = mock(RouteImpl.class);
        RequestImpl request = mock(RequestImpl.class);
        when(request.url()).thenReturn(url);
        when(route.request()).thenReturn(request);
        when(route.isHandled()).thenReturn(handled);
        return route;
    }
}
