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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DELAY 真实时延契约（P1 修复回归）：
 * <ul>
 *   <li><b>修复断言①</b>：dispatch（事件线程）返回后，延迟窗口内 <b>绝不能发生</b> resume——
 *       历史缺陷是 delayedExecutor 调度后又立即同步 resume + 终结，delay 空操作；</li>
 *   <li><b>修复断言②</b>：延迟窗口结束后 resume 必然发生，且真实时延 ≥ delay 值
 *       （允许调度余量，取 delay 的 5/6 作为下限）；</li>
 *   <li><b>对照 playwright-java-1.62.0</b> {@code Router.handle}：handler 返回 pending 后
 *       请求由驱动挂起，只能被异步终结——本测试断言的就是这条契约。</li>
 * </ul>
 */
public class DelayAppliesWaitTest {

    private BrowserContext ctx;

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        doAnswer(inv -> null).when(ctx).onClose(any());
        RouteEngine2.runtimeOf(ctx);
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

    private Route mockRoute(String url) {
        Request request = mockRequest(url, "GET");
        Route route = mock(Route.class);
        when(route.request()).thenReturn(request);
        return route;
    }

    @Test
    public void delayActuallyWaitsBeforeResume() throws Exception {
        long delayMs = 1000; // DSL 单位为秒：delay(1) = 1000ms
        mockContext();
        RouteDsl2.on(ctx)
                .api("/api/pay/**")
                .delay(1)
                .done()
                .start();
        Consumer<Route> handler = handlerFor("/api/pay/**");
        Route route = mockRoute("http://h/api/pay/1");
        AtomicLong resumeAtNanos = new AtomicLong(-1);
        doAnswer(inv -> {
            resumeAtNanos.set(System.nanoTime());
            return null;
        }).when(route).resume();

        long t0 = System.nanoTime();
        handler.accept(route); // 事件线程：应返回 pending，不阻塞

        // 修复断言①：dispatch 返回后延迟窗口内绝不可 resume（历史缺陷=立即放行）
        Thread.sleep(120);
        assertEquals("DELAY 期间请求被立即放行——delay 空操作（P1 缺陷未修复或回归）", (long) -1, (long) resumeAtNanos.get());

        // 修复断言②：延迟窗口结束后 resume 必然发生，真实时延 ≥ delay 的 5/6
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (resumeAtNanos.get() < 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(resumeAtNanos.get() - t0);
        assertTrue( "延迟任务未执行（claim 被 sweep 兜底或异常，resume 从未发生）", resumeAtNanos.get() > 0);
        assertTrue(
                "真实时延不足: " + elapsedMs + "ms（要求 delay=" + delayMs + "ms）", elapsedMs >= delayMs * 5 / 6);
    }

    @Test
    public void randomDelayWithInvertedRangeStillWaits() throws Exception {
        // 配置失误：min > max（randomDelay(1, 0) = min 1000ms > max 0ms）——不得产生负延迟/立即放行
        mockContext();
        RouteDsl2.on(ctx)
                .api("/api/order/**")
                .delay(1)
                .randomDelay(1, 0)
                .done()
                .start();
        Consumer<Route> handler = handlerFor("/api/order/**");
        Route route = mockRoute("http://h/api/order/1");
        AtomicLong resumeAtNanos = new AtomicLong(-1);
        doAnswer(inv -> {
            resumeAtNanos.set(System.nanoTime());
            return null;
        }).when(route).resume();

        long t0 = System.nanoTime();
        handler.accept(route);

        Thread.sleep(100);
        assertEquals("randomDelay 配置反转时仍立即放行——负延迟未被钳制", (long) -1, (long) resumeAtNanos.get());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (resumeAtNanos.get() < 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(resumeAtNanos.get() - t0);
        assertTrue( "randomDelay 任务未执行", resumeAtNanos.get() > 0);
        // 钳制后 min=1000ms、max=1000ms → delay=1000ms；断言真实等待 ≥ 800ms（钳制语义生效，非负）
        assertTrue( "randomDelay 反转区间钳制后时延仍不足: " + elapsedMs + "ms", elapsedMs >= 800);
    }
}
