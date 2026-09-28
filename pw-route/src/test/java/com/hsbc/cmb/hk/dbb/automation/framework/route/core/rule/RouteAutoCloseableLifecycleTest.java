package com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteContextState;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle.RouteMonitorSession;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 确定性生命周期回归守卫：route(...) 返回的 AutoCloseable 句柄必须在注册时捕获、
 * case 结束（clearContext）时经有界守护线程确定性 close()，并随后从句柄表摘除，
 * 杜绝「清链但闭包仍挂 context」导致的原生路由累积 / 跨用例污染。
 */
class RouteAutoCloseableLifecycleTest {

    @Test
    @DisplayName("注册时捕获 AutoCloseable 句柄，clearContext 时确定性 close 并摘表")
    void capturedHandleClosedDeterministicallyOnClearContext() throws Exception {
        BrowserContext ctx = mock(BrowserContext.class);
        AtomicBoolean closed = new AtomicBoolean(false);
        AutoCloseable handle = () -> closed.set(true);
        when(ctx.route(anyString(), any())).thenReturn(handle);
        when(ctx.isClosed()).thenReturn(false);

        RouteRule rule = new RouteRule();
        rule.setUrlPattern("/api/test/**");
        rule.setType(RouteHandleType.MOCK);

        RuleRepository.register(ctx, List.of(rule));

        // 注册后：句柄已按 (context, normalizedPattern) 记账
        String normalized = RouteEngine.normalizePattern("/api/test/**");
        assertTrue(RouteContextState.ROUTE_HANDLES.containsKey(ctx), "注册后句柄表应包含该 context");
        Map<String, AutoCloseable> handles = RouteContextState.ROUTE_HANDLES.get(ctx);
        assertSame(handle, handles.get(normalized), "句柄应为 context.route 的返回值");

        // case 结束：确定性 close + 摘表。
        //  close 已改为在守护线程<b>异步</b>执行（不再 join，避免浏览器不响应时阻塞主线程），
        //  故此处以有界轮询等待其完成，而非断言同步可见。
        RuleRepository.clearContext(ctx);

        assertFalse(RouteContextState.ROUTE_HANDLES.containsKey(ctx), "clearContext 后句柄表应已摘除该 context");
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!closed.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertTrue(closed.get(), "clearContext 必须 close 注册时捕获的 AutoCloseable 句柄（异步完成）");
    }

    /**
     * case 结束<b>不得被 route 收尾阻塞</b>：即便原生 unroute 很慢（浏览器不响应）、
     * monitor 的 timeout 配得再长，{@code clearContext} 也必须立即返回，
     * 并把 monitor / delay / mock / modify 的规则与会话<b>全部清理干净</b>（与其 timeout 配置无关）。
     */
    @Test
    @DisplayName("case 结束：不阻塞主线程，且全部 monitor/delay/mock/modify 一并清理（与 timeout 无关）")
    void clearContextIsNonBlockingAndClearsAllTypesRegardlessOfTimeout() throws Exception {
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.isClosed()).thenReturn(false);
        //  模拟「浏览器不响应」：原生 unroute 句柄慢 3s —— 若收尾在主线程同步等待，本用例必然被拖慢。
        AutoCloseable slowHandle = () -> Thread.sleep(3_000L);
        when(ctx.route(anyString(), any())).thenReturn(slowHandle);

        RouteRule mockRule = new RouteRule();
        mockRule.setUrlPattern("/api/slow/**");
        mockRule.setType(RouteHandleType.MOCK);
        RuleRepository.register(ctx, List.of(mockRule));

        //  monitor：timeout 故意配 10 分钟 —— 收尾绝不能等它到点
        RouteRule monitorRule = new RouteRule();
        monitorRule.setUrlPattern("/api/monitor/**");
        monitorRule.setTimeoutMs(600_000L);
        RouteMonitorSession.startMonitorSession(ctx, monitorRule,
                RouteEngine.normalizePattern("/api/monitor/**"));
        assertTrue(RouteMonitorSession.sessionCount() > 0, "前置：应已创建 monitor 会话");

        long t0 = System.currentTimeMillis();
        RuleRepository.clearContext(ctx);
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(elapsed < 2_000L,
                "case 结束不得被原生 unroute / monitor timeout 阻塞（实测 " + elapsed + "ms）");
        assertEquals(0, RouteMonitorSession.sessionCount(),
                "monitor 会话必须全部清理，与其 timeout 配置无关");
        assertFalse(RouteContextState.ROUTE_HANDLES.containsKey(ctx), "句柄表应在主线程同步摘除");
    }
}
