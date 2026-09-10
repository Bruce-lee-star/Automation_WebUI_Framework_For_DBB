package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 固化 {@code ROUTE_FRAMEWORK_GUIDE.md} §1.3「四类能力矩阵」与「选择优先级」契约。
 *
 * <p>覆盖两层：
 * <ul>
 *   <li>{@link RouteHandleType} 枚举的 priority / executionOrder / terminal 静态矩阵；</li>
 *   <li>{@link RouteEngine#selectCapability} 的运行时裁决（MOCK 最先被选中 → MODIFY → DELAY → MONITOR），
 *       即文档所述「选择优先级决定一次请求由哪个 Handler 执行动作」。</li>
 * </ul>
 *
 * 本测试<b>不依赖 Playwright</b>（纯逻辑），可在 CI 中零成本运行，防止 §1.3 矩阵回归漂移。
 */
public class RouteCapabilityContractTest {

    // ───────────────── §1.3 枚举静态矩阵 ─────────────────

    @Test
    public void mock_matrix() {
        assertEquals(100, RouteHandleType.MOCK.getPriority());
        assertEquals(3, RouteHandleType.MOCK.getExecutionOrder());
        assertEquals(true, RouteHandleType.MOCK.isTerminal());
    }

    @Test
    public void modify_matrix() {
        assertEquals(200, RouteHandleType.MODIFY.getPriority());
        assertEquals(2, RouteHandleType.MODIFY.getExecutionOrder());
        assertEquals(false, RouteHandleType.MODIFY.isTerminal());
    }

    @Test
    public void delay_matrix() {
        assertEquals(300, RouteHandleType.DELAY.getPriority());
        assertEquals(1, RouteHandleType.DELAY.getExecutionOrder());
        assertEquals(false, RouteHandleType.DELAY.isTerminal());
    }

    @Test
    public void monitor_matrix() {
        assertEquals(999, RouteHandleType.MONITOR.getPriority());
        assertEquals(4, RouteHandleType.MONITOR.getExecutionOrder());
        assertEquals(false, RouteHandleType.MONITOR.isTerminal());
    }

    // ───────────────── 选择优先级排序 ─────────────────

    /** 选择优先级升序：MOCK(100) < MODIFY(200) < DELAY(300) < MONITOR(999) */
    @Test
    public void byPriority_order() {
        List<RouteHandleType> sorted = new ArrayList<>(List.of(
                RouteHandleType.MONITOR, RouteHandleType.DELAY,
                RouteHandleType.MODIFY, RouteHandleType.MOCK));
        sorted.sort(Comparator.comparingInt(RouteHandleType::getPriority));
        assertEquals(List.of(RouteHandleType.MOCK, RouteHandleType.MODIFY,
                RouteHandleType.DELAY, RouteHandleType.MONITOR), sorted);
    }

    /** 执行时序升序：DELAY(1) → MODIFY(2) → MOCK(3) → MONITOR(4)，与选择优先级方向相反 */
    @Test
    public void byExecutionOrder_order() {
        List<RouteHandleType> sorted = new ArrayList<>(List.of(
                RouteHandleType.MONITOR, RouteHandleType.MOCK,
                RouteHandleType.MODIFY, RouteHandleType.DELAY));
        sorted.sort(Comparator.comparingInt(RouteHandleType::getExecutionOrder));
        assertEquals(List.of(RouteHandleType.DELAY, RouteHandleType.MODIFY,
                RouteHandleType.MOCK, RouteHandleType.MONITOR), sorted);
    }

    // ───────────────── 运行时裁决：selectCapability ─────────────────

    private static RouteRule rule(RouteHandleType type) {
        RouteRule r = new RouteRule();
        r.setUrlPattern("/api/x");
        r.setType(type);
        return r;
    }

    /** MOCK 优先于一切（唯一 terminal，命中即短路）。 */
    @Test
    public void select_mockWins_overModifyDelayMonitor() {
        RouteRule r = rule(RouteHandleType.MOCK);
        r.setMonitorEnabled(true);
        r.setDelayMs(1000);
        r.setRequestHeadersToSet(Map.of("X", "1"));
        assertEquals(RouteHandleType.MOCK, RouteEngine.selectCapability(r));
    }

    /** 非 MOCK 时，MODIFY 能力位（存在请求改写项）先于 DELAY / MONITOR 被选中。 */
    @Test
    public void select_modifyWins_overDelay() {
        RouteRule r = rule(RouteHandleType.DELAY);
        r.setRequestHeadersToSet(Map.of("X", "1"));
        assertEquals(RouteHandleType.MODIFY, RouteEngine.selectCapability(r));
    }

    /** 纯 DELAY（无 modify 无 monitor）：选中 DELAY。 */
    @Test
    public void select_pureDelay() {
        assertEquals(RouteHandleType.DELAY, RouteEngine.selectCapability(rule(RouteHandleType.DELAY)));
    }

    /** monitor 能力位开启（非 MOCK/MODIFY/DELAY）：选中 MONITOR。 */
    @Test
    public void select_monitorWhenEnabled() {
        RouteRule r = rule(RouteHandleType.MONITOR);
        r.setMonitorEnabled(true);
        assertEquals(RouteHandleType.MONITOR, RouteEngine.selectCapability(r));
    }

    /** 无任能力位（MONITOR 但 monitorEnabled=false、无改写、无 delay）→ 返回 null（调用方 resume 放行）。 */
    @Test
    public void select_none_returnsNull() {
        RouteRule r = rule(RouteHandleType.MONITOR); // 构造默认 monitorEnabled=false
        assertTrue(!r.isMonitorEnabled());
        assertEquals(null, RouteEngine.selectCapability(r));
    }

    /** MODIFY + MONITOR 叠加：selectCapability 选 MODIFY（动作分支），monitor 作为并存观察维度。 */
    @Test
    public void select_modifyWithMonitor_stillModify() {
        RouteRule r = rule(RouteHandleType.MODIFY);
        r.setRequestHeadersToSet(Map.of("X", "1")); // 必须有实质改写项才会触发 MODIFY 能力位
        r.setMonitorEnabled(true);
        assertEquals(RouteHandleType.MODIFY, RouteEngine.selectCapability(r));
    }
}
