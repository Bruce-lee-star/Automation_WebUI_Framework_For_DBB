package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.RouteRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteCapability;
import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 行为级防回归（不依赖真实浏览器）：固化 {@link PatternBinder} 的协议调用收口 ——
 * <ul>
 *   <li>{@code bind} 必须经由 {@link GuardedDriverCall}（opName 以 {@code bind:} 开头，
 *       {@link GuardedDriverCall.OnTimeout#FAIL_FAST}，{@link GuardedDriverCall#BIND_BOUND_MS}）；</li>
 *   <li>{@code close}（unroute）必须经由 {@link GuardedDriverCall}（opName 以 {@code unroute:} 开头，
 *       {@link GuardedDriverCall.OnTimeout#WARN_AND_ABANDON}，{@link GuardedDriverCall#UNROUTE_BOUND_MS}）。</li>
 * </ul>
 *
 * <p><b>为什么这是必要的</b>：E2E 实测（test-automation 1.txt）证明，直接调
 * {@code context.route()} / {@code context.unroute()} 在 Node 驱动不响应时会无限阻塞调用线程
 * （route 注册卡 14 分钟、unroute 卡 11 分钟）。任何"绕过 {@link GuardedDriverCall} 直接调
 * 原生协议调用"的改动都会让本测试失败，从而把卡死根因挡在单测期。</p>
 *
 * <p><b>与 HangWatchdog 的关系</b>：本测试是 route2 的<b>根因设界</b>守护（治本），与 web 模块
 * 临时的 {@code HangWatchdog} 诊断（治标）正交 —— route2 成为主流后靠本收口自洽，不依赖 web 诊断；
 * {@code HangWatchdog} 后续去除时本测试与 route2 不受影响。</p>
 */
public class PatternBinderGuardedCallTest {

    @After
    public void reset() {
        GuardedDriverCallRegistry.reset();
    }

    @Test
    public void bindRoutesThroughGuardedDriverCallFailFast() {
        CapturingGuardedDriverCall capturing = new CapturingGuardedDriverCall();
        GuardedDriverCallRegistry.setInstance(capturing);

        BrowserContext ctx = org.mockito.Mockito.mock(BrowserContext.class);
        RouteRuntime runtime = org.mockito.Mockito.mock(RouteRuntime.class);
        ApiSpec spec = ApiSpec.builder("/api/users", RouteCapability.MONITOR).build();

        PatternBinder binder = PatternBinder.bind(ctx, spec, runtime);

        assertTrue("bind 必须经由 GuardedDriverCall（opName 以 bind: 开头）",
                capturing.hasCallWithOpPrefix("bind:"));
        boolean failFast = capturing.calls().stream().anyMatch(c ->
                c.opName.startsWith("bind:")
                        && c.policy == GuardedDriverCall.OnTimeout.FAIL_FAST
                        && c.boundMs == GuardedDriverCall.BIND_BOUND_MS);
        assertTrue("bind 必须 FAIL_FAST 且 boundMs=" + GuardedDriverCall.BIND_BOUND_MS, failFast);
        assertFalse("Capturing 不执行 action（无需真实驱动协议调用）",
                capturing.calls().stream().anyMatch(c -> c.executed));
        org.junit.Assert.assertNotNull("bind 必须返回非 null 绑定", binder);
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
                        && c.boundMs == GuardedDriverCall.UNROUTE_BOUND_MS);
        assertTrue("close(unroute) 必须 WARN_AND_ABANDON 且 boundMs=" + GuardedDriverCall.UNROUTE_BOUND_MS, warn);
    }
}
