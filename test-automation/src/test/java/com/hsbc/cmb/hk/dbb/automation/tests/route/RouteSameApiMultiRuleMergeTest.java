package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 固化 {@code ROUTE_FRAMEWORK_GUIDE.md} 关于「同一 API 写多条独立规则」的合并语义。
 *
 * <p>关键事实：无论用链式 DSL（一条 {@code .api()} 内连续 {@code .mock().monitor()...}）还是
 * 分写多条 {@code .api()}（每条只声明一种能力），它们最终都进入<b>同一 pattern 的规则链</b>，
 * 在分发期经 {@code resolveChain → copyForMerge + mergeFrom} 合并为<b>一条有效规则</b>（能力位 OR）。
 * 本测试直接验证 {@link RouteRule#mergeFrom} 的 OR 合并 + {@link RouteEngine#selectCapability} 的
 * 能力裁决，覆盖文档中用户最关心的几种分写组合，证明「分写 monitor/mock/delay/modify 不会失联 / 不会被吞掉」。
 *
 * <p>纯逻辑测试，不依赖 Playwright。
 */
public class RouteSameApiMultiRuleMergeTest {

    private static RouteRule modify(String header, String value) {
        RouteRule r = new RouteRule();
        r.setType(RouteHandleType.MODIFY);
        r.setAutoStopOnMatch(false);
        r.setRequestHeadersToSet(Map.of(header, value));
        return r;
    }

    private static RouteRule monitor() {
        RouteRule r = new RouteRule();
        r.setType(RouteHandleType.MONITOR);
        r.setMonitorEnabled(true);
        r.setAutoStopOnMatch(true);
        r.setMinMatches(1);
        return r;
    }

    private static RouteRule mock() {
        RouteRule r = new RouteRule();
        r.setType(RouteHandleType.MOCK);
        r.setAutoStopOnMatch(false);
        r.setMockStatus(200);
        r.setMockBody("MOCK");
        return r;
    }

    private static RouteRule delay(long secs) {
        RouteRule r = new RouteRule();
        r.setType(RouteHandleType.DELAY);
        r.setAutoStopOnMatch(false);
        r.setDelayMs(secs * 1000);
        return r;
    }

    /** 分写：先 modify 后 monitor（monitor 在链尾）。monitor 能力位必须被保留，且动作选 MODIFY。 */
    @Test
    public void separate_modifyThenMonitor_monitorCapabilityPreserved_andActionModify() {
        RouteRule effective = modify("X-Page", "1");
        effective.mergeFrom(monitor()); // 模拟「先写 modify，后写 monitor」

        assertTrue("monitor 能力位应经 OR 合并保留（不分写顺序而丢失）", effective.isMonitorEnabled());
        assertEquals(RouteHandleType.MODIFY, RouteEngine.selectCapability(effective));
    }

    /** 分写：先 monitor 后 modify（monitor 在链头）。同样两能力共存。 */
    @Test
    public void separate_monitorThenModify_bothActive() {
        RouteRule effective = monitor();
        effective.mergeFrom(modify("X-Page", "1"));

        assertTrue(effective.isMonitorEnabled());
        // modify 能力位存在 → 动作选 MODIFY（monitor 作为并存观察维度）
        assertEquals(RouteHandleType.MODIFY, RouteEngine.selectCapability(effective));
    }

    /**
     * 分写：先 delay 后 monitor。
     * ⚠️ 注意能力裁决优先级 MOCK > MODIFY > DELAY > MONITOR：当规则同时带 DELAY 与 MONITOR（无 modify/mock）时，
     * {@link RouteEngine#selectCapability} 返回 DELAY（动作类别），但 monitor 能力位仍被 OR 保留；
     * 分发期 scheduleDelay 会按 {@code isMonitorEnabled()} 走 MonitorHandler，断言与记录照常执行（不会因顺序失联）。
     */
    @Test
    public void separate_delayThenMonitor_delayApplied_monitorActive() {
        RouteRule effective = delay(2);
        effective.mergeFrom(monitor());

        assertEquals(2000, effective.getDelayMs());
        assertTrue(effective.isMonitorEnabled());
        assertEquals(RouteHandleType.DELAY, RouteEngine.selectCapability(effective));
    }

    /** 分写：先 monitor 后 delay（顺序反转），结果一致：动作类别 DELAY，monitor 能力保留。 */
    @Test
    public void separate_monitorThenDelay_sameResult() {
        RouteRule effective = monitor();
        effective.mergeFrom(delay(2));

        assertEquals(2000, effective.getDelayMs());
        assertTrue(effective.isMonitorEnabled());
        assertEquals(RouteHandleType.DELAY, RouteEngine.selectCapability(effective));
    }

    /**
     * 分写 mock + monitor：monitor 能力位被 OR 保留，但 MOCK 是唯一终结者，
     * selectCapability 返回 MOCK —— 真实响应被短路，monitor 无可观测对象。
     * 这对应文档「MONITOR 唯一失效场景是 MOCK 短路」：组合本身有效，只是 monitor 不触发。
     */
    @Test
    public void separate_mockThenMonitor_capabilityOr_butMockTerminal() {
        RouteRule effective = mock();
        effective.mergeFrom(monitor());

        assertTrue("monitor 能力位仍被 OR 合并保留", effective.isMonitorEnabled());
        assertEquals(RouteHandleType.MOCK, RouteEngine.selectCapability(effective));
        assertFalse("MOCK 终结：monitor 无真实响应可观测，不应作为动作分支",
                RouteEngine.selectCapability(effective) == RouteHandleType.MONITOR);
    }

    /** 三条分写（modify + delay + monitor，无 mock）：三能力共存，动作选 MODIFY，delay 生效。 */
    @Test
    public void separate_modifyDelayMonitor_allCoexist() {
        RouteRule effective = modify("X-Page", "1");
        effective.mergeFrom(delay(3));
        effective.mergeFrom(monitor());

        assertEquals(3000, effective.getDelayMs());
        assertTrue(effective.isMonitorEnabled());
        assertEquals(RouteHandleType.MODIFY, RouteEngine.selectCapability(effective));
    }
}
