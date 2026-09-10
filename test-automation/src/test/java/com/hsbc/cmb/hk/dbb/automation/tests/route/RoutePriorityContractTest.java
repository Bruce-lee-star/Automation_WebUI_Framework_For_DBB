package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRule;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 固化「四能力优先级与合并」契约（对齐 route-priority-and-context-design.md）。
 *
 * <p>核心是把<b>两套顺序</b>拆开，避免历史混淆：
 * <ul>
 *   <li><b>选择优先级</b> {@link RouteHandleType#getPriority()} —— 谁被选中执行动作，MOCK 最先（terminal 短路）；</li>
 *   <li><b>执行时序</b> {@link RouteHandleType#getExecutionOrder()} —— 请求内部动作的实际顺序，DELAY 最先。</li>
 * </ul>
 */
public class RoutePriorityContractTest {

    private static RouteRule rule(RouteHandleType type) {
        RouteRule r = new RouteRule();
        r.setUrlPattern("/api/order");
        r.setType(type);
        return r;
    }

    // ───────────────────────── 两套顺序 ─────────────────────────

    @Test
    public void selectionPriority_isMockFirstMonitorLast() {
        assertTrue(RouteHandleType.MOCK.getPriority() < RouteHandleType.MODIFY.getPriority());
        assertTrue(RouteHandleType.MODIFY.getPriority() < RouteHandleType.DELAY.getPriority());
        assertTrue(RouteHandleType.DELAY.getPriority() < RouteHandleType.MONITOR.getPriority());
    }

    @Test
    public void executionOrder_isDelayFirstMockLast() {
        assertEquals(1, RouteHandleType.DELAY.getExecutionOrder());
        assertEquals(2, RouteHandleType.MODIFY.getExecutionOrder());
        assertEquals(3, RouteHandleType.MOCK.getExecutionOrder());
        assertEquals(4, RouteHandleType.MONITOR.getExecutionOrder());
    }

    @Test
    public void onlyMockIsTerminal() {
        assertTrue(RouteHandleType.MOCK.isTerminal());
        for (RouteHandleType t : new RouteHandleType[]{
                RouteHandleType.MODIFY, RouteHandleType.DELAY, RouteHandleType.MONITOR}) {
            assertFalse(t.name() + " 不应是 terminal", t.isTerminal());
        }
    }

    // ───────────────────────── 拦截器选择 ─────────────────────────

    @Test
    public void mockShortCircuitsModify() {
        // MOCK（terminal）+ MODIFY 叠加 → MOCK 胜出，MODIFY 不执行
        RouteRule r = rule(RouteHandleType.MOCK);
        r.setRequestHeadersToSet(Map.of("X-Trace", "t"));

        RouteHandleType selected = RouteEngine.selectCapability(r);
        assertNotNull(selected);
        assertEquals(RouteHandleType.MOCK, selected);
        assertTrue("MOCK 必须短路", selected.isTerminal());
    }

    @Test
    public void modifyWinsOverDelayAndMonitor() {
        RouteRule r = rule(RouteHandleType.MONITOR);
        r.setMonitorEnabled(true);
        r.setDelayMs(3000);
        r.setRequestHeadersToSet(Map.of("X-Trace", "t"));

        // MODIFY 选择优先级高于 DELAY/MONITOR → 由 ModifyHandler 执行；
        // 延迟由 dispatchRoute 在 handler 前调度，监控由 ModifyHandler 内 assertAndRecord 叠加
        assertEquals(RouteHandleType.MODIFY, RouteEngine.selectCapability(r));
    }

    @Test
    public void pureDelayIsHandledByDelayBranch() {
        RouteRule r = rule(RouteHandleType.MONITOR);
        r.setDelayMs(3000);

        // 无改写项、仅延迟 → DELAY 分支（放行后由 captureDelayedMonitor 叠加监控）
        assertEquals(RouteHandleType.DELAY, RouteEngine.selectCapability(r));
    }

    @Test
    public void monitorOnlyFallsToMonitorHandler() {
        RouteRule r = rule(RouteHandleType.MONITOR);
        r.setMonitorEnabled(true);

        assertEquals(RouteHandleType.MONITOR, RouteEngine.selectCapability(r));
    }

    @Test
    public void noCapabilityYieldsNull() {
        RouteRule r = rule(RouteHandleType.MONITOR);
        assertNull("无任何能力位时应返回 null，由调用方 resume 放行", RouteEngine.selectCapability(r));
    }

    // ───────────────────────── 跨层合并四条铁律 ─────────────────────────

    @Test
    public void crossLayerDelayTakesMax() {
        RouteRule page = rule(RouteHandleType.MONITOR);
        page.setDelayMs(5_000);
        RouteRule ctx = rule(RouteHandleType.MONITOR);
        ctx.setDelayMs(3_000);

        RouteRule merged = page.copyForMerge();
        merged.mergeFrom(ctx);
        assertEquals("跨层 DELAY 取 max，而非 sum", 5_000L, merged.getDelayMs());

        RouteRule reversed = ctx.copyForMerge();
        reversed.mergeFrom(page);
        assertEquals("反向合并同样取 max（Page=5s 仍胜出）", 5_000L, reversed.getDelayMs());
    }

    @Test
    public void crossLayerMonitorIsOrNeverDowngraded() {
        RouteRule page = rule(RouteHandleType.MODIFY);
        page.setMonitorEnabled(false);
        RouteRule ctx = rule(RouteHandleType.MONITOR);
        ctx.setMonitorEnabled(true);

        RouteRule merged = page.copyForMerge();
        merged.mergeFrom(ctx);
        assertTrue("MONITOR 能力位 OR：基线不可被关", merged.isMonitorEnabled());
    }

    @Test
    public void crossLayerModifyMergesFields() {
        RouteRule page = rule(RouteHandleType.MODIFY);
        page.setRequestHeadersToSet(Map.of("X-Page", "1"));
        RouteRule ctx = rule(RouteHandleType.MODIFY);
        ctx.setRequestHeadersToSet(Map.of("X-Context", "1"));

        RouteRule merged = page.copyForMerge();
        merged.mergeFrom(ctx);

        Map<String, String> headers = merged.getRequestHeadersToSet();
        assertEquals("跨层 MODIFY 字段 putAll 累加", 2, headers.size());
        assertTrue(headers.containsKey("X-Page"));
        assertTrue(headers.containsKey("X-Context"));
    }
}
