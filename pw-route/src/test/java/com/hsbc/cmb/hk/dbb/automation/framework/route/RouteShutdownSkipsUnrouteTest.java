package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.CapturingGuardedDriverCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.GuardedDriverCallRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「Context 正被主动关闭时跳过逐条 unroute」契约（T8-5，FIX_PLAN §10.3 C-4）。
 *
 * <p><b>为什么必须有这个用例</b>：{@link RouteRuntimeImpl} 里早有 {@code contextClosing} 短路，
 * 但它<b>只在 {@code context.onClose} 事件路径置位</b>；而框架主动收尾是
 * {@code stopContextEngine}（→ {@code runtime.close()}）<b>先于</b>真实的 {@code context.close()}，
 * 此刻 Context 仍存活 ⇒ 不显式传意图就会把"马上要丢弃的 Context"上的规则再逐条撤销一遍。
 * 每撤销一条就是一次 {@code setNetworkInterceptionPatterns}（客户端无超时、实测有 10s 未确证的记录），
 * 这正是「收尾未确证 ⇒ 状态分叉 ⇒ 病态 Context 遗传」的来源。
 *
 * <p>本用例用 {@link CapturingGuardedDriverCall}（只记录、不执行 action）断言<b>协议调用面</b>：
 * 传 {@code true} ⇒ 不出现任何 {@code unroute:}；不传（或传 false）⇒ 仍会出现（行为不变，供 feature 档
 * 「Context 仍需存活」的路径使用）。
 */
public class RouteShutdownSkipsUnrouteTest {

    private BrowserContext ctx;

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        Mockito.doAnswer(inv -> null).when(ctx).onClose(any());
        return ctx;
    }

    private CapturingGuardedDriverCall installCapturingGuard() {
        CapturingGuardedDriverCall guard = new CapturingGuardedDriverCall();
        GuardedDriverCallRegistry.setInstance(guard);
        return guard;
    }

    /** MONITOR 能力：bind 未确证时走降级（WARN_AND_ABANDON），不会因替身不执行 action 而抛错。 */
    private ApiSpec monitorSpec() {
        return ApiSpec.builder("notifications/streams", RouteCapability.MONITOR)
                .expectStatus(200)
                .build();
    }

    @After
    public void cleanup() {
        if (ctx != null) {
            RouteEngine.shutdown(ctx);
        }
        GuardedDriverCallRegistry.reset();
    }

    @Test
    public void contextBeingClosed_skipsUnrouteEntirely() {
        CapturingGuardedDriverCall guard = installCapturingGuard();
        BrowserContext context = mockContext();
        RouteRuntime runtime = RouteEngine.runtimeOf(context);
        runtime.register(monitorSpec());

        RouteEngine.shutdown(context, true);

        assertTrue("bind 仍须经 GuardedDriverCall 收口", guard.hasCallWithOpPrefix("bind:"));
        assertFalse("Context 正被关闭 ⇒ 不得再发起任何 unroute（规则随 close 原生释放）",
                guard.hasCallWithOpPrefix("unroute"));
    }

    @Test
    public void withoutIntent_unrouteIsStillIssued_soLiveContextsStayClean() {
        CapturingGuardedDriverCall guard = installCapturingGuard();
        BrowserContext context = mockContext();
        RouteRuntime runtime = RouteEngine.runtimeOf(context);
        runtime.register(monitorSpec());

        RouteEngine.shutdown(context, false);

        assertTrue("Context 仍需存活的路径（如 feature 档跨用例复用）必须照旧逐条 unroute",
                guard.hasCallWithOpPrefix("unroute"));
    }
}
