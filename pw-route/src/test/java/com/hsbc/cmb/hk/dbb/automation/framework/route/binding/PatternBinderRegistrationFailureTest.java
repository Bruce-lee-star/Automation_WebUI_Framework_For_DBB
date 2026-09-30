package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.impl.TargetClosedError;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 注册失败处理契约（2026-09-28 新增 T4；<b>2026-09-29 修订为按能力 fail-closed</b>）。
 *
 * <p><b>背景</b>：{@code context.route()} 是客户端 {@code setNetworkInterceptionPatterns}（NO_TIMEOUT +
 * 调用线程自己泵消息）。Playwright 客户端曾因未按消息隔离异常，使一条"引用已释放对象"的事件带崩在途调用
 * （已由框架自建客户端 DBBN-PATCH-01 修复）。
 *
 * <p>本测试守护四条语义：
 * <ol>
 *   <li><b>行为类能力（MOCK）注册失败 → 抛出（fail-closed）</b>，绝不静默降级；</li>
 *   <li>action 抛<b>真实异常</b>（如 context 已关闭）→ 一律抛，且 cause 保留原异常（不吞错、不降级）；</li>
 *   <li>观测类能力（MONITOR）注册失败 → 降级绑定，其 {@code close()} 仍走 SPI（幂等、同构，清理链可观测）；</li>
 *   <li>runtime 级：行为类规则注册失败必须<b>响亮冒泡</b>（不再"标记降级并不抛"）。</li>
 * </ol></p>
 */

public class PatternBinderRegistrationFailureTest {

    @After
    public void reset() {
        GuardedDriverCallRegistry.reset();
    }

    /** 场景 1：行为类能力（MOCK）注册失败必须 fail-closed（抛出），绝不静默降级。 */
    @Test
    public void behaviourAffectingRegistrationFailureIsFailClosed() {
        // CONTRACT CHANGE (2026-09-29): behaviour-affecting rules are fail-closed; the old
        // "timeout degrades to an inert binder" semantics now applies to MONITOR only.
        GuardedDriverCallRegistry.setInstance(new CapturingGuardedDriverCall());

        BrowserContext ctx = Mockito.mock(BrowserContext.class);
        RouteRuntime runtime = Mockito.mock(RouteRuntime.class);
        ApiSpec spec = ApiSpec.builder("notifications/streams", RouteCapability.MOCK).build();

        try {
            PatternBinder.bind(ctx, spec, runtime);
            fail("MOCK registration failure must throw (fail-closed), never degrade silently");
        } catch (IllegalStateException expected) {
            assertTrue("must name the failing pattern",
                    expected.getMessage() != null && expected.getMessage().contains("route registration failed"));
        }
    }

    /** 场景 2：真实异常（context 已关闭）绝不能被降级吞掉。 */
    @Test
    public void realDriverExceptionIsNeverSwallowed() {
        GuardedDriverCallRegistry.setInstance(new StubGuardedDriverCall()); // 执行 action；WARN 策略下返回 null
        BrowserContext closedContext = Mockito.mock(BrowserContext.class,
                Mockito.withSettings().defaultAnswer(invocation -> {
                    if ("route".equals(invocation.getMethod().getName())) {
                        throw new TargetClosedError("simulated: context/page closed");
                    }
                    return null;
                }));

        RouteRuntime runtime = Mockito.mock(RouteRuntime.class);
        ApiSpec spec = ApiSpec.builder("profile/list", RouteCapability.MOCK).build();

        try {
            PatternBinder.bind(closedContext, spec, runtime);
            fail("真实异常必须抛出（不得伪装成超时降级）");
        } catch (IllegalStateException expected) {
            assertTrue("必须保留原始异常作为 cause",
                    expected.getCause() instanceof PlaywrightException);
        }
    }

    /** 场景 3：降级绑定的 close() 仍走 SPI（保持清理链可观测/可审计），且只注销一次。 */
    @Test
    public void degradedBinderStillUnroutesThroughGuardedCall() {
        CapturingGuardedDriverCall capturing = new CapturingGuardedDriverCall();
        GuardedDriverCallRegistry.setInstance(capturing);

        PatternBinder binder = PatternBinder.bind(Mockito.mock(BrowserContext.class),
                ApiSpec.builder("notifications/streams", RouteCapability.MONITOR).build(),
                Mockito.mock(RouteRuntime.class));
        binder.close();
        binder.close(); // 幂等

        assertEquals("降级绑定也必须恰好注销一次（幂等 + 同构）", 1,
                capturing.calls().stream().filter(c -> c.opName.startsWith("unroute:")).count());
    }

    /** 场景 4：runtime 级 —— 行为类规则注册失败必须响亮冒泡（不再"降级且不抛"）。 */
    @Test
    public void behaviourAffectingRegistrationFailurePropagatesLoudly() {
        // CONTRACT CHANGE (2026-09-29): a failed registration of a behaviour-affecting rule is loud, not silent.
        GuardedDriverCallRegistry.setInstance(new CapturingGuardedDriverCall());
        BrowserContext ctx = Mockito.mock(BrowserContext.class);
        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);
        try {
            runtime.register(ApiSpec.builder("notifications/streams", RouteCapability.MOCK).build());
            fail("runtime.register must propagate the failure (fail-closed), not degrade silently");
        } catch (IllegalStateException expected) {
            assertTrue("must name the failing pattern",
                    expected.getMessage() != null && expected.getMessage().contains("route registration failed"));
        } finally {
            RouteEngine.shutdown(ctx);
        }
    }
}
