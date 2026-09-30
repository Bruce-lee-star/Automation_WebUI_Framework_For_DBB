package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 有界等待阈值契约（2026-09-28 依 Playwright 1.62.0 源码复核后新增；2026-09-29 依用户裁定移除系统属性覆盖）。
 *
 * <p><b>背景</b>：{@code context.route()} / {@code unroute()} 都是客户端
 * {@code sendMessage("setNetworkInterceptionPatterns", …, NO_TIMEOUT)}（{@code BrowserContextImpl:715-722}，
 * 客户端自身不设超时），且由调用线程自己泵消息 ⇒ "有界等待"只能由框架提供。原 5s/3s 与实测环境不符
 * （同环境 {@code waitForVisible(45~60s)}、完整登录 53s 均为合法时延），故固定 30s/10s。</p>
 *
 * <p><b>本测试守护两条不可回退的语义</b>：
 * <ol>
 *   <li>阈值必须与环境匹配（30s / 10s），且硬编码、不经系统属性覆盖（杜绝误配置取消有界保护）；</li>
 *   <li>{@link PatternBinder} 必须把固定阈值（30s/10s）传给 {@link GuardedDriverCall}。</li>
 * </ol>
 */
public class GuardedDriverCallBoundsTest {

    @After
    public void reset() {
        GuardedDriverCallRegistry.reset();
    }

    @Test
    public void defaultsMatchRealWorldLatency() {
        assertEquals("bind 阈值须为 30s（与环境实测时延匹配）",
                30_000L, GuardedDriverCall.bindBoundMs());
        assertEquals("unroute 阈值须为 10s", 10_000L, GuardedDriverCall.unrouteBoundMs());
        assertEquals(30_000L, GuardedDriverCall.BIND_BOUND_MS);
        assertEquals(10_000L, GuardedDriverCall.UNROUTE_BOUND_MS);
    }

    @Test
    public void patternBinderPassesFixedBoundsToGuardedCall() {
        CapturingGuardedDriverCall capturing = new CapturingGuardedDriverCall();
        GuardedDriverCallRegistry.setInstance(capturing);

        BrowserContext ctx = org.mockito.Mockito.mock(BrowserContext.class);
        RouteRuntime runtime = org.mockito.Mockito.mock(RouteRuntime.class);
        ApiSpec spec = ApiSpec.builder("/api/users", RouteCapability.MONITOR).build();

        PatternBinder binder = PatternBinder.bind(ctx, spec, runtime);
        binder.close();

        assertTrue("bind 必须携带固定阈值 30000ms",
                capturing.calls().stream().anyMatch(c -> c.opName.startsWith("bind:") && c.boundMs == 30_000L));
        assertTrue("unroute 必须携带固定阈值 10000ms",
                capturing.calls().stream().anyMatch(c -> c.opName.startsWith("unroute:") && c.boundMs == 10_000L));
    }
}
