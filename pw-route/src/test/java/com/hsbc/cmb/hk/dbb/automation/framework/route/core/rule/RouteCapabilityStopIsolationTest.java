package com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约测试：「按能力维度显式停止」<b>只影响该能力，同 pattern 其余能力不受影响</b>。
 *
 * <p><b>为什么需要本测试（真实缺陷回归守卫）</b>：{@code @route-capability-stop} 用例实测
 * ① <i>stopModify 后 MONITOR 不再采集</i>、② <i>stopMonitor 后 MODIFY 失效</i>。
 * 二者同源 —— 分发期能力选择是<b>单选</b>（{@code PriorityPolicy.selectCapability}），
 * 一旦把「纯 DELAY 分支」排在 MONITOR 之前，叠加场景的监控基线就会被静默吞掉
 * （纯 DELAY 分支只做延迟放行、不监控真实响应），而框架契约明确：
 * <b>MONITOR 是不可覆盖的基线，MODIFY / DELAY 叠加其上</b>。
 *
 * <p>本测试在<b>纯规则层</b>固定该契约（合并 → 注入停止标记 → 能力选择），
 * 不依赖浏览器 / 网络，秒级可跑，防止上述缺陷再次回退。
 */
class RouteCapabilityStopIsolationTest {

    /** 构造与用例一致的「同 pattern 三能力」合并规则：MONITOR(链头) + MODIFY(X-Cap) + DELAY(2s)。 */
    private static RouteRule mergedRule() {
        RouteRule monitor = new RouteRule();
        monitor.setUrlPattern("/web/api/echo");
        monitor.setType(RouteHandleType.MONITOR);
        monitor.setMonitorEnabled(true);

        RouteRule modify = new RouteRule();
        modify.setUrlPattern("/web/api/echo");
        modify.setRequestHeadersToSet(Map.of("X-Cap", "ALIVE"));

        RouteRule delay = new RouteRule();
        delay.setUrlPattern("/web/api/echo");
        delay.setDelayMs(2000);

        //  与 RouteUnifiedResolution.resolveChain 完全一致：链头 copyForMerge + 依次 mergeFrom
        RouteRule merged = monitor.copyForMerge();
        merged.mergeFrom(modify);
        merged.mergeFrom(delay);
        return merged;
    }

    @Test
    @DisplayName("MERGE-1 合并后三能力共存（modify 头 + delay + monitor 基线）")
    void mergedRuleCarriesAllThreeCapabilities() {
        RouteRule merged = mergedRule();
        assertEquals("ALIVE", merged.getRequestHeadersToSet().get("X-Cap"), "MODIFY 字段应随合并保留");
        assertEquals(2000, merged.getDelayMs(), "DELAY 应随合并保留");
        assertTrue(merged.isMonitorEnabled(), "MONITOR 基线应随合并保留");
        assertEquals(RouteHandleType.MODIFY, PriorityPolicy.selectCapability(merged),
                "未停止任何能力时，应选 MODIFY（MODIFY 优先于 MONITOR/DELAY，且 handler 内叠加监控）");
    }

    @Test
    @DisplayName("STOP-1 停止 MONITOR 不影响 MODIFY（不得退化为放行）")
    void stopMonitorKeepsModify() {
        RouteRule merged = mergedRule();
        merged.stopCapability(RouteHandleType.MONITOR);

        assertTrue(merged.isCapabilityStopped(RouteHandleType.MONITOR));
        assertEquals(RouteHandleType.MODIFY, PriorityPolicy.selectCapability(merged),
                "停止 monitor 后仍应选 MODIFY（否则请求头不会被改写）");
        assertNotNull(merged.getRequestHeadersToSet(), "停止 monitor 不得清空 MODIFY 配置");
    }

    @Test
    @DisplayName("STOP-2 停止 MODIFY 后 MONITOR 基线仍执行（不得被纯 DELAY 分支吞掉）")
    void stopModifyKeepsMonitorBaseline() {
        RouteRule merged = mergedRule();
        merged.stopCapability(RouteHandleType.MODIFY);

        assertTrue(merged.isCapabilityStopped(RouteHandleType.MODIFY));
        assertEquals(RouteHandleType.MONITOR, PriorityPolicy.selectCapability(merged),
                "停止 modify 后应选 MONITOR（MONITOR 是先于 DELAY 的基线；选 DELAY 会静默丢失监控记录）");
    }

    @Test
    @DisplayName("STOP-3 停止 DELAY 不影响 MODIFY / MONITOR")
    void stopDelayKeepsModifyAndMonitor() {
        RouteRule merged = mergedRule();
        merged.stopCapability(RouteHandleType.DELAY);

        assertEquals(RouteHandleType.MODIFY, PriorityPolicy.selectCapability(merged),
                "停止 delay 后应选 MODIFY");
        assertTrue(merged.isMonitorEnabled(), "停止 delay 不得关闭 monitor 基线");
    }

    @Test
    @DisplayName("STOP-4 停止 MOCK 对非 MOCK 规则无影响")
    void stopMockDoesNotAffectNonMockRule() {
        RouteRule merged = mergedRule();
        merged.stopCapability(RouteHandleType.MOCK);

        assertEquals(RouteHandleType.MODIFY, PriorityPolicy.selectCapability(merged));
    }

    @Test
    @DisplayName("STOP-5 停止全部能力 → 无能力可选（放行真实请求）")
    void stopAllYieldsNoCapability() {
        RouteRule merged = mergedRule();
        for (RouteHandleType t : RouteHandleType.values()) {
            merged.stopCapability(t);
        }

        assertNull(PriorityPolicy.selectCapability(merged),
                "全部能力被停止后应返回 null（调用方 resume 放行真实请求）");
    }

    @Test
    @DisplayName("STOP-6 纯 DELAY（无 modify、无 monitor）仍选 DELAY")
    void pureDelayStillSelectsDelay() {
        RouteRule delayOnly = new RouteRule();
        delayOnly.setUrlPattern("/web/api/echo");
        delayOnly.setDelayMs(1500);

        assertEquals(RouteHandleType.DELAY, PriorityPolicy.selectCapability(delayOnly),
                "无 MONITOR 基线时，纯 DELAY 规则应走延迟放行分支");
    }
}
