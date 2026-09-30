package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RouteLifecycle SPI 集成（第三批）：
 * <ul>
 *   <li>RouteEngine 首次使用即触发 RouteLifecycleImpl 自注册（追加位，不覆盖 pw-route primary）；</li>
 *   <li>web 侧统一收尾挂点（stopContextEngine / clearContext / stopAllContextEngines /
 *       shutdownRouteEngine / drainForSuiteTeardown）经 Composite 同步驱动 V2 运行时关闭——不再孤儿；</li>
 *   <li>查询型方法在 primary 缺失时按 V2 语义降级（null / 原样）。</li>
 * </ul>
 */
public class RouteLifecycleIntegrationTest {

    private BrowserContext ctx;

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        org.mockito.Mockito.doAnswer(inv -> null).when(ctx).onClose(any());
        return ctx;
    }

    @After
    public void cleanup() {
        if (ctx != null) {
            RouteEngine.shutdown(ctx);
        }
        RouteEngine.shutdownAll();
    }

    @Test
    public void engineLoadRegistersV2LifecycleOnAdditionalSlot() {
        mockContext();
        RouteDsl.on(ctx).api("/api/a").mock().status(200).body("{}").register();

        RouteLifecycle lifecycle = RouteLifecycleRegistry.get();
        assertNotNull( "RouteEngine 加载后 SPI 必须已注册（Composite，含 V2 追加位）", lifecycle);
    }

    @Test
    public void stopContextEngineClosesTargetRuntimeOnly() {
        mockContext();
        RouteDsl.on(ctx).api("/api/a").mock().status(200).body("{}").register();
        BrowserContext other = mockContext();
        RouteDsl.on(other).api("/api/b").mock().status(200).body("{}").register();
        assertEquals((long) 2, (long) RouteEngine.activeRuntimes());

        RouteLifecycleRegistry.get().stopContextEngine(ctx);

        assertEquals("stopContextEngine 只关目标 context，其余不受影响", (long) 1, (long) RouteEngine.activeRuntimes());
    }

    /**
     * clearContext 是<b>档 B 纯内存解绑</b>：只清规则、保留 runtime 与 Context（避免每个 scenario
     * 收尾重 bind 触发的 30s 卡死 / 信道污染）。真正关闭 runtime 仍由 stopContextEngine / shutdown 负责。
     */
    @Test
    public void clearContextDetachesRulesButKeepsRuntime() {
        mockContext();
        RouteDsl.on(ctx).api("/api/a").mock().status(200).body("{}").register();
        assertEquals((long) 1, (long) RouteEngine.activeRuntimes());

        RouteLifecycleRegistry.get().clearContext(ctx);

        // 档 B：clearContext 仅解绑规则、保留 runtime（不再等价 shutdown）
        assertEquals("clearContext 必须保留 runtime（仅解绑规则）", (long) 1, (long) RouteEngine.activeRuntimes());

        // 显式 stop 才真正关闭 runtime
        RouteLifecycleRegistry.get().stopContextEngine(ctx);
        assertEquals((long) 0, (long) RouteEngine.activeRuntimes());
    }

    @Test
    public void suiteTeardownHooksCloseAllRuntimes() {
        mockContext();
        RouteDsl.on(ctx).api("/api/a").mock().status(200).body("{}").register();
        BrowserContext other = mockContext();
        RouteDsl.on(other).api("/api/b").mock().status(200).body("{}").register();
        assertEquals((long) 2, (long) RouteEngine.activeRuntimes());

        RouteLifecycle lifecycle = RouteLifecycleRegistry.get();
        lifecycle.stopAllContextEngines();
        assertEquals("stopAllContextEngines 必须全关", (long) 0, (long) RouteEngine.activeRuntimes());
        // 全关后可重新注册（幂等重建）
        RouteDsl.on(ctx).api("/api/a").mock().status(200).body("{}").register();
        assertEquals((long) 1, (long) RouteEngine.activeRuntimes());

        RouteLifecycleRegistry.get().shutdownRouteEngine();
        assertEquals("shutdownRouteEngine 必须全关", (long) 0, (long) RouteEngine.activeRuntimes());

        RouteDsl.on(ctx).api("/api/a").mock().status(200).body("{}").register();
        RouteLifecycleRegistry.get().drainForSuiteTeardown();
        assertEquals("drainForSuiteTeardown 必须全关（V2 无跨用例池）", (long) 0, (long) RouteEngine.activeRuntimes());
    }

    @Test
    public void queryMethodsDegradeGracefullyWithoutPrimary() {
        mockContext();
        RouteDsl.on(ctx).api("/api/a").mock().status(200).body("{}").register();

        RouteLifecycle lifecycle = RouteLifecycleRegistry.get();
        assertNull( "V2 无 API 捕获 → null（等价未启用降级）", lifecycle.getCurrentCapture());
        assertNull( "V2 无断言上下文 → null", lifecycle.resolveFailureCapture());
        assertEquals("primary 缺失时 sanitizeUrl 原样返回", (Object) "https://api.example/x?a=1", (Object) lifecycle.sanitizeUrl("https://api.example/x?a=1"));
    }

    @Test
    public void registerNullKeepsAdditionalImplementationActive() {
        mockContext();
        RouteDsl.on(ctx).api("/api/a").mock().status(200).body("{}").register();

        RouteLifecycleRegistry.register(null); // 模拟 web 测试收尾清 primary

        RouteLifecycle lifecycle = RouteLifecycleRegistry.get();
        assertNotNull( "primary 清空后 V2 追加位必须仍随 Composite 驱动", lifecycle);
        lifecycle.stopContextEngine(ctx); // 不得 NPE，必须真实关闭
        assertEquals((long) 0, (long) RouteEngine.activeRuntimes());
    }

    @Test
    public void closedRuntimeStopIsIdempotentViaSpi() {
        mockContext();
        RouteDsl.on(ctx).api("/api/a").mock().status(200).body("{}").register();
        RouteLifecycle lifecycle = RouteLifecycleRegistry.get();

        lifecycle.stopContextEngine(ctx);
        lifecycle.stopContextEngine(ctx); // 幂等
        lifecycle.shutdownRouteEngine(); // 已空，幂等
        lifecycle.drainForSuiteTeardown();

        assertEquals((long) 0, (long) RouteEngine.activeRuntimes());
    }
}
