package com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.PriorityPolicy;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约测试：{@code times}（一次性拦截）耗尽的<b>作用域</b> —— 只剔除该规则，不放弃整条规则链。
 *
 * <p><b>为什么需要本测试（真实缺陷回归守卫）</b>：{@code Dispatcher.dispatchRoute} 原实现对
 * 「链头 times 耗尽」执行 {@code safeResume} + {@code return}，把同 pattern 链上其它规则的
 * 能力（MONITOR 基线 / MODIFY / DELAY）<b>一并丢弃</b>（与 session-stopped 分支同一形态的缺陷）。
 * 现改为「剔除已耗尽规则后继续统一合并」，本测试固定该语义：
 * <ul>
 *   <li>单规则链耗尽 → 剔除后为空 ⇒ 行为与旧实现等价（放行）；</li>
 *   <li>多规则链中某规则耗尽 → <b>仅它退出</b>，其余规则能力照常生效；</li>
 *   <li>{@code times=0}（无限次）与「仍有余量」的规则永不被剔除；</li>
 *   <li>剔除必须返回<b>新列表</b>（原链由 Playwright route 闭包持有，就地修改会跨请求污染）。</li>
 * </ul>
 */
class RouteTimesExhaustionTest {

    private static RouteRule rule(RouteHandleType type) {
        RouteRule r = new RouteRule();
        r.setUrlPattern("/web/api/echo");
        r.setType(type);
        return r;
    }

    /** 一次性 MOCK 规则：允许 N 次拦截。 */
    private static RouteRule mockTimes(int times) {
        RouteRule r = rule(RouteHandleType.MOCK);
        r.setMockBody("MOCKED");
        r.setTimes(times);
        return r;
    }

    /** 无限次 MONITOR 规则（监控基线）。 */
    private static RouteRule monitor() {
        RouteRule r = rule(RouteHandleType.MONITOR);
        r.setMonitorEnabled(true);
        return r;
    }

    /** 无限次 DELAY 规则。 */
    private static RouteRule delay(long ms) {
        RouteRule r = rule(RouteHandleType.DELAY);
        r.setDelayMs(ms);
        return r;
    }

    @Test
    @DisplayName("TIMES-1 未耗尽时不剔除任何规则（含仍有余量的规则）")
    void notExhaustedKeepsWholeChain() {
        RouteRule mock = mockTimes(3);
        List<RouteRule> chain = new ArrayList<>(List.of(mock, monitor(), delay(2000)));

        assertFalse(Dispatcher.hasExhaustedTimesRule(chain), "余量充足时不应判定为存在耗尽规则");

        mock.decrementTimes();   // 用掉 1 次，仍余 2 次
        assertFalse(mock.isTimesExhausted());
        assertFalse(Dispatcher.hasExhaustedTimesRule(chain));
        assertEquals(3, Dispatcher.excludeExhaustedTimesRules(chain).size(),
                "有余量的规则必须保留");
    }

    @Test
    @DisplayName("TIMES-2 单规则链耗尽 → 剔除后为空（与既有『耗尽即放行』等价）")
    void singleRuleExhaustedYieldsEmptyChain() {
        RouteRule mock = mockTimes(1);
        List<RouteRule> chain = new ArrayList<>(List.of(mock));

        mock.decrementTimes();
        assertTrue(Dispatcher.hasExhaustedTimesRule(chain));

        List<RouteRule> effective = Dispatcher.excludeExhaustedTimesRules(chain);
        assertTrue(effective.isEmpty(), "剔除后无可选规则 ⇒ 调用方走放行分支（保持旧行为）");
        assertEquals(1, chain.size(), "原链不得被修改（route 闭包持有该列表）");
    }

    @Test
    @DisplayName("TIMES-3 多规则链：链头一次耗尽只退出它自己，MONITOR/DELAY 能力保留")
    void headExhaustedKeepsSiblingCapabilities() {
        RouteRule mock = mockTimes(2);
        RouteRule monitorRule = monitor();
        RouteRule delayRule = delay(2000);
        List<RouteRule> chain = new ArrayList<>(List.of(mock, monitorRule, delayRule));

        mock.decrementTimes();
        mock.decrementTimes();   // 配额用尽
        assertTrue(mock.isTimesExhausted());

        List<RouteRule> effective = Dispatcher.excludeExhaustedTimesRules(chain);
        assertEquals(2, effective.size(), "仅耗尽的 MOCK 规则退出，MONITOR/DELAY 保留");
        //  按【身份 + 顺序】断言（RouteRule.equals 为内容级且不含 type，contains 不可靠）
        assertSame(monitorRule, effective.get(0), "MONITOR 规则须按原顺序保留");
        assertSame(delayRule, effective.get(1), "DELAY 规则须按原顺序保留");

        // 剩余规则合并后的有效规则：不应再是 MOCK（该规则已退出），且监控基线可执行
        RouteRule merged = effective.get(0).copyForMerge();
        for (int i = 1; i < effective.size(); i++) {
            merged.mergeFrom(effective.get(i));
        }
        assertNotEquals(RouteHandleType.MOCK, PriorityPolicy.selectCapability(merged),
                "耗尽的 MOCK 不得继续生效（否则 times 形同无效）");
        assertEquals(RouteHandleType.MONITOR, PriorityPolicy.selectCapability(merged),
                "MONITOR 是不可覆盖基线：应继续执行（同链其它能力不被 times 牵连）");
        assertTrue(merged.getDelayMs() > 0, "DELAY 配置应随同链保留");
    }

    @Test
    @DisplayName("TIMES-4 非链头规则耗尽 → 只退出该规则，链头保留")
    void nonHeadExhaustedOnlyDropsItself() {
        RouteRule monitorRule = monitor();
        RouteRule delayRule = delay(1000);
        delayRule.setTimes(1);
        List<RouteRule> chain = new ArrayList<>(List.of(monitorRule, delayRule));

        delayRule.decrementTimes();
        assertTrue(delayRule.isTimesExhausted());

        List<RouteRule> effective = Dispatcher.excludeExhaustedTimesRules(chain);
        assertEquals(1, effective.size());
        assertSame(monitorRule, effective.get(0), "链头（监控基线）必须保留");
    }

    @Test
    @DisplayName("TIMES-5 times=0（无限次）永不被剔除")
    void unlimitedRulesAreNeverExcluded() {
        RouteRule mock = rule(RouteHandleType.MOCK);   // 未调用 times → 0 = 无限次
        mock.setMockBody("MOCKED");
        RouteRule monitorRule = monitor();
        List<RouteRule> chain = new ArrayList<>(List.of(mock, monitorRule));

        assertFalse(Dispatcher.hasExhaustedTimesRule(chain));
        assertEquals(2, Dispatcher.excludeExhaustedTimesRules(chain).size(),
                "times=0 表示无限拦截，任何情况下都不得被剔除");
    }

    @Test
    @DisplayName("TIMES-6 剔除返回新列表：原链内容与顺序不受影响")
    void exclusionDoesNotMutateOriginalChain() {
        RouteRule mock = mockTimes(1);
        RouteRule monitorRule = monitor();
        List<RouteRule> chain = new ArrayList<>(List.of(mock, monitorRule));

        mock.decrementTimes();
        List<RouteRule> effective = Dispatcher.excludeExhaustedTimesRules(chain);

        assertEquals(1, effective.size());
        assertEquals(2, chain.size(), "原链必须保持 2 条（route 闭包直接持有该 List 引用）");
        assertSame(mock, chain.get(0));
        assertSame(monitorRule, chain.get(1));
        assertNotSame(chain, effective, "必须返回新列表，避免跨请求污染");
    }

    @Test
    @DisplayName("TIMES-7 setTimes 重置余量：重新注册/改次数后不再视为耗尽")
    void setTimesResetsRemainingQuota() {
        RouteRule mock = mockTimes(1);
        mock.decrementTimes();
        assertTrue(mock.isTimesExhausted());

        mock.setTimes(2);
        assertFalse(mock.isTimesExhausted(), "setTimes 应重置剩余次数（对齐 Route.setTimes 语义）");
        assertEquals(2, mock.getTimes());
    }

    @Test
    @DisplayName("TIMES-8 空链 / null 链安全（不抛异常、不误判）")
    void emptyAndNullChainSafe() {
        assertFalse(Dispatcher.hasExhaustedTimesRule(null));
        assertFalse(Dispatcher.hasExhaustedTimesRule(List.of()));
        assertTrue(Dispatcher.excludeExhaustedTimesRules(null).isEmpty());
        assertTrue(Dispatcher.excludeExhaustedTimesRules(List.of()).isEmpty());
    }

    @Test
    @DisplayName("TIMES-9 times 与其它能力正交：同链叠加 MODIFY 时，MOCK 耗尽后 MODIFY 继续生效")
    void exhaustedMockStillKeepsModify() {
        RouteRule mock = mockTimes(1);
        RouteRule modify = rule(RouteHandleType.MODIFY);
        modify.setRequestHeadersToSet(Map.of("X-Cap", "ALIVE"));
        List<RouteRule> chain = new ArrayList<>(List.of(mock, modify));

        mock.decrementTimes();
        List<RouteRule> effective = Dispatcher.excludeExhaustedTimesRules(chain);
        assertEquals(1, effective.size());

        RouteRule merged = effective.get(0).copyForMerge();
        assertEquals(RouteHandleType.MODIFY, PriorityPolicy.selectCapability(merged),
                "MOCK 耗尽后仍应执行 MODIFY（能力隔离）");
        assertEquals("ALIVE", merged.getRequestHeadersToSet().get("X-Cap"));
    }
}
