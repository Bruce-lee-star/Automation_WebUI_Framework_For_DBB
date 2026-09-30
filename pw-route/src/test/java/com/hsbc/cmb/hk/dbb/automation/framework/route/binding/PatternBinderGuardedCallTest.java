package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 行为级防回归（不依赖真实浏览器）：固化 {@link PatternBinder} 的协议调用收口 ——
 * <ul>
 *   <li>{@code bind} 必须经由 {@link GuardedDriverCall}（opName 以 {@code bind:} 开头，
 *       阈值 = {@link GuardedDriverCall#bindBoundMs()}）；<b>策略按规则能力选择</b> ——
 *       MONITOR 用 {@link GuardedDriverCall.OnTimeout#WARN_AND_ABANDON}（可降级），
 *       行为类能力（MOCK/MODIFY_REQUEST/DELAY）用 {@link GuardedDriverCall.OnTimeout#FAIL_FAST}（fail-closed）；</li>
 *   <li>{@code close}（unroute）必须经由 {@link GuardedDriverCall}（opName 以 {@code unroute:} 开头，
 *       {@link GuardedDriverCall.OnTimeout#WARN_AND_ABANDON}，{@link GuardedDriverCall#unrouteBoundMs()}）。</li>
 * </ul>
 *
 * <p><b>为什么这是必要的</b>：该协议调用在客户端是 NO_TIMEOUT 且由调用线程自己泵消息，驱动真卡死时会拖住调用线程
 * （早期 E2E 实证），故必须收敛到有界守护。任何"绕过 {@link GuardedDriverCall} 直接调原生协议调用"的改动
 * 都会让本测试失败，从而把卡死根因挡在单测期。</p>
 *
 * <p><b>2026-09-29 更正</b>：早期"route 注册卡 14 分钟、unroute 卡 11 分钟"的描述不准确 —— 实测该调用在
 * 界值后约 3~5 秒即抛 {@code Object doesn't exist}（客户端 dispatch 未隔离），已由 DBBN-PATCH-01 修复。</p>
 */

public class PatternBinderGuardedCallTest {

    @After
    public void reset() {
        GuardedDriverCallRegistry.reset();
    }

    @Test
    public void bindRoutesThroughGuardedDriverCallWithDegradeByDefault() {
        CapturingGuardedDriverCall capturing = new CapturingGuardedDriverCall();
        GuardedDriverCallRegistry.setInstance(capturing);

        BrowserContext ctx = org.mockito.Mockito.mock(BrowserContext.class);
        RouteRuntime runtime = org.mockito.Mockito.mock(RouteRuntime.class);
        ApiSpec spec = ApiSpec.builder("/api/users", RouteCapability.MONITOR).build();

        PatternBinder binder = PatternBinder.bind(ctx, spec, runtime);

        assertTrue("bind 必须经由 GuardedDriverCall（opName 以 bind: 开头）",
                capturing.hasCallWithOpPrefix("bind:"));
        boolean routed = capturing.calls().stream().anyMatch(c ->
                c.opName.startsWith("bind:")
                        && c.policy == GuardedDriverCall.OnTimeout.WARN_AND_ABANDON
                        && c.boundMs == GuardedDriverCall.bindBoundMs());
        assertTrue("bind 必须带生效阈值 " + GuardedDriverCall.bindBoundMs()
                + " 且默认策略为 WARN_AND_ABANDON（超时降级）", routed);
        assertFalse("Capturing 不执行 action（无需真实驱动协议调用）",
                capturing.calls().stream().anyMatch(c -> c.executed));
        assertNotNull("bind 必须返回非 null 绑定", binder);
        assertTrue("默认策略下替身返回 null（等价超时）必须标记 degraded", binder.isDegraded());
    }

    @Test
    public void closeUnroutesThroughGuardedDriverCallWarnAndAbandon() {
        CapturingGuardedDriverCall capturing = new CapturingGuardedDriverCall();
        GuardedDriverCallRegistry.setInstance(capturing);

        BrowserContext ctx = org.mockito.Mockito.mock(BrowserContext.class);
        RouteRuntime runtime = org.mockito.Mockito.mock(RouteRuntime.class);
        ApiSpec spec = ApiSpec.builder("/api/users", RouteCapability.MONITOR).build();

        PatternBinder binder = PatternBinder.bind(ctx, spec, runtime);
        binder.close();

        boolean warn = capturing.calls().stream().anyMatch(c ->
                c.opName.startsWith("unroute:")
                        && c.policy == GuardedDriverCall.OnTimeout.WARN_AND_ABANDON
                        && c.boundMs == GuardedDriverCall.unrouteBoundMs());
        assertTrue("close(unroute) 必须 WARN_AND_ABANDON 且 boundMs="
                + GuardedDriverCall.unrouteBoundMs(), warn);
    }
}
