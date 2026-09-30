package com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteContextState;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle.RouteMonitorSession;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 确定性生命周期回归守卫：route 规则必须在注册时登记到 in-JVM 状态表、
 * case 结束（clearContext）时从状态表摘除，杜绝跨用例规则污染。
 *
 * <p>原生路由句柄随 Context 销毁由驱动侧统一释放，框架<b>不再</b>缓存/显式 unroute
 * （见 {@code RouteContextState} 设计：每 case 重建+销毁模型下 unroute 是冗余的浏览器往返）。
 */
class RouteAutoCloseableLifecycleTest {

    @Test
    @DisplayName("注册时登记规则链到 in-JVM 状态表，clearContext 时确定性摘除")
    void ruleChainRegisteredAndClearedDeterministically() throws Exception {
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.isClosed()).thenReturn(false);

        RouteRule rule = new RouteRule();
        rule.setUrlPattern("/api/test/**");
        rule.setType(RouteHandleType.MOCK);

        RuleRepository.register(ctx, List.of(rule));

        // 注册后：规则链已按 context 登记到 in-JVM 状态表
        String normalized = RouteEngine.normalizePattern("/api/test/**");
        assertTrue(RouteContextState.CONTEXT_RULES_BY_CONTEXT.containsKey(ctx), "注册后状态表应包含该 context");
        Map<String, List<RouteRule>> chains = RouteContextState.CONTEXT_RULES_BY_CONTEXT.get(ctx);
        assertTrue(chains != null && chains.containsKey(normalized), "规则链应按归一化 pattern 登记");

        // case 结束：规则链从状态表摘除（纯内存，无浏览器往返）
        RuleRepository.clearContext(ctx);
        assertFalse(RouteContextState.CONTEXT_RULES_BY_CONTEXT.containsKey(ctx), "clearContext 后状态表应已摘除该 context");
    }

    /**
     * case 结束<b>不得被 route 收尾阻塞</b>：无论 monitor 的 timeout 配多长，
     * {@code clearContext} 都必须立即返回，并把 monitor / delay / mock / modify 的规则与会话
     * <b>全部清理干净</b>（与其 timeout 配置无关）。
     */
    @Test
    @DisplayName("case 结束：不阻塞主线程，且全部 monitor/delay/mock/modify 一并清理（与 timeout 无关）")
    void clearContextIsNonBlockingAndClearsAllTypesRegardlessOfTimeout() throws Exception {
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.isClosed()).thenReturn(false);
        AutoCloseable handle = () -> { };
        when(ctx.route(anyString(), any())).thenReturn(handle);

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
                "case 结束不得被阻塞（实测 " + elapsed + "ms）");
        assertEquals(0, RouteMonitorSession.sessionCount(),
                "monitor 会话必须全部清理，与其 timeout 配置无关");
        assertFalse(RouteContextState.CONTEXT_RULES_BY_CONTEXT.containsKey(ctx), "规则链状态表应在主线程同步摘除");
    }
}
