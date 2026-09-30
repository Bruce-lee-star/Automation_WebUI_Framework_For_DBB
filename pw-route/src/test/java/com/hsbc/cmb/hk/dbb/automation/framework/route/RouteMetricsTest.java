package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.CapturingGuardedDriverCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.GuardedDriverCallRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.StubGuardedDriverCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T1 度量基线契约（2026-09-29）：每用例统计 下发次数 / 路由命中数 / 每条规则 armed 时长 / 未确证撤销次数。
 *
 * <p>runtime 随 BrowserContext 生命周期（每 scenario 收尾即关闭重建），故这些计数天然"每用例"重置。</p>
 */
public class RouteMetricsTest {

    private BrowserContext ctx;

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        Mockito.doAnswer(inv -> null).when(ctx).onClose(any());
        return ctx;
    }

    @After
    public void cleanup() {
        GuardedDriverCallRegistry.reset();
        if (ctx != null) {
            RouteEngine.shutdown(ctx);
        }
    }

    /** 未确证撤销（无回包）必须被计数，且经 metrics() 暴露。 */
    @Test
    public void unconfirmedRetirementIsCounted() throws Exception {
        GuardedDriverCallRegistry.setInstance(new CapturingGuardedDriverCall()); // 首次与重发都无回包
        RouteRuntime runtime = RouteEngine.runtimeOf(mockContext());
        runtime.register(ApiSpec.builder("notifications/streams", RouteCapability.MONITOR).build());

        runtime.retireByPurpose("notifications/streams");
        // 未确证计数在撤销执行器异步任务中递增，轮询等待定稿
        for (int i = 0; i < 150 && runtime.unconfirmedRetirements() == 0; i++) {
            Thread.sleep(20);
        }
        assertEquals(1, runtime.unconfirmedRetirements());
        assertEquals(1, runtime.metrics().unconfirmedRetirements());
    }

    /** 目的达成即撤销必须计入 retiredByPurpose，且定稿该规则的 armed 时长。 */
    @Test
    public void purposeRetirementIsCountedAndArmedDurationRecorded() {
        GuardedDriverCallRegistry.setInstance(new StubGuardedDriverCall());
        RouteRuntime runtime = RouteEngine.runtimeOf(mockContext());
        runtime.register(ApiSpec.builder("/api/once", RouteCapability.MONITOR).expectStatus(200).build());

        runtime.retireByPurpose("/api/once"); // retiredByPurpose 与 armed 时长在提交前同步定稿

        assertEquals(1, runtime.metrics().retiredByPurpose());
        Map<String, Long> armed = runtime.metrics().ruleArmedDurationsMs();
        assertTrue("armed 时长应已记录该规则", armed.containsKey("/api/once"));
        assertTrue("armed 时长应 >= 0", armed.get("/api/once") >= 0);
    }

    /** dispatch 进入分发器计为"下发"，命中规则条件并触发能力计为"命中"。 */
    @Test
    public void dispatchAndHitAreCounted() {
        GuardedDriverCallRegistry.setInstance(new StubGuardedDriverCall());
        RouteRuntime runtime = RouteEngine.runtimeOf(mockContext());
        // allowAllRequests：关闭 onlyApiCall / onlyMainFrame 过滤，使匹配器对 mock 请求放行
        runtime.register(ApiSpec.builder("http://api/metrics", RouteCapability.MONITOR)
                .expectStatus(200).allowAllRequests().build());

        Request request = mock(Request.class);
        when(request.method()).thenReturn("GET");
        when(request.url()).thenReturn("http://api/metrics");
        Route route = mock(Route.class);
        when(route.request()).thenReturn(request);

        runtime.dispatch(route, "http://api/metrics");

        assertTrue("下发次数应 >= 1", runtime.metrics().dispatches() >= 1);
        assertTrue("命中次数应 >= 1", runtime.metrics().hits() >= 1);
    }
}
