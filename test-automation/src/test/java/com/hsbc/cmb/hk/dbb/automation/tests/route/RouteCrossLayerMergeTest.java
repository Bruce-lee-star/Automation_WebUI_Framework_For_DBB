package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRule;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固化跨层（Page + Context）合并纯函数 {@link RouteEngine#mergeCrossLayer} 的契约。
 *
 * <p>本测试<b>不依赖 Playwright</b>（纯逻辑），是 Phase 5 统一绑定模型的可验证核心。
 * 覆盖 {@code ROUTE_SCOPE_AND_PRIORITY.md} §5 同 API 多层组合表的关键行，并钉死两条关键不变量：
 * <ul>
 *   <li>输入规则绝不被就地修改（copyForMerge，杜绝跨请求行为漂移）；</li>
 *   <li>DELAY 取 max；ctx 层 delay 始终保留，page 层 delay 仅当 ctx 为终结者（MOCK）时丢弃；</li>
 *   <li>MOCK 为唯一终结者：任一层为 MOCK → 有效规则 type=MOCK（短路）。</li>
 * </ul>
 */
public class RouteCrossLayerMergeTest {

    private static RouteRule rule(RouteHandleType type) {
        RouteRule r = new RouteRule();
        r.setUrlPattern("/api/order");
        r.setType(type);
        return r;
    }

    private static List<RouteRule> ctxChain(RouteRule r) {
        return Collections.singletonList(r);
    }

    // ───────────────────────── §5 组合表 ─────────────────────────

    /** MODIFY | MOCK → context MOCK fulfill（page MODIFY 请求改写失效） */
    @Test
    public void modifyPage_ctxMock_isMockTerminated() {
        RouteRule page = rule(RouteHandleType.MODIFY);
        page.setRequestHeadersToSet(Map.of("X-Page", "p"));
        RouteRule ctx = rule(RouteHandleType.MOCK);

        RouteEngine.CrossLayerMergeResult m = RouteEngine.mergeCrossLayer(page, ctxChain(ctx));

        assertEquals(RouteHandleType.MOCK, m.rule.getType());
        assertTrue(m.delayMerged);
    }

    /** MOCK | MONITOR → page MOCK fulfill + MONITOR 断言（两者都执行） */
    @Test
    public void mockPage_ctxMonitor_isMockWithMonitor() {
        RouteRule page = rule(RouteHandleType.MOCK);
        RouteRule ctx = rule(RouteHandleType.MONITOR);
        ctx.setMonitorEnabled(true);

        RouteEngine.CrossLayerMergeResult m = RouteEngine.mergeCrossLayer(page, ctxChain(ctx));

        assertEquals(RouteHandleType.MOCK, m.rule.getType());
        assertTrue(m.rule.isMonitorEnabled());
    }

    /** MONITOR | MOCK → context MOCK fulfill + MONITOR 断言（page MONITOR 被保留） */
    @Test
    public void monitorPage_ctxMock_isMockWithMonitor() {
        RouteRule page = rule(RouteHandleType.MONITOR);
        page.setMonitorEnabled(true);
        RouteRule ctx = rule(RouteHandleType.MOCK);

        RouteEngine.CrossLayerMergeResult m = RouteEngine.mergeCrossLayer(page, ctxChain(ctx));

        assertEquals(RouteHandleType.MOCK, m.rule.getType());
        assertTrue(m.rule.isMonitorEnabled());
    }

    /** MODIFY | MONITOR → resume(改后 opts) + 监控真实响应（改请求 + 监控共存） */
    @Test
    public void modifyPage_ctxMonitor_isModifyWithMonitor() {
        RouteRule page = rule(RouteHandleType.MODIFY);
        page.setRequestHeadersToSet(Map.of("X-Page", "p"));
        RouteRule ctx = rule(RouteHandleType.MONITOR);
        ctx.setMonitorEnabled(true);

        RouteEngine.CrossLayerMergeResult m = RouteEngine.mergeCrossLayer(page, ctxChain(ctx));

        assertEquals(RouteHandleType.MODIFY, m.rule.getType());
        assertTrue(m.rule.isMonitorEnabled());
        assertEquals("p", m.rule.getRequestHeadersToSet().get("X-Page"));
    }

    /**
     * DELAY | MOCK → MOCK 立即返回（无真实响应可延迟）。
     * <p>⚠️ 注意：此断言与 {@code ROUTE_SCOPE_AND_PRIORITY.md} §5 第 5 行「延迟后 MOCK fulfill」
     * 的文字描述<b>不一致</b>——实现（n51：ctx 终结 → pageDelay=0 → MOCK 立即返回）以 delay=0 为准。
     * 测试以实现为真相源钉死该行为，文档 §5 需随后修正。
     */
    @Test
    public void delayPage_ctxMock_isMockImmediateNoDelay() {
        RouteRule page = rule(RouteHandleType.DELAY);
        page.setDelayMs(300);
        RouteRule ctx = rule(RouteHandleType.MOCK);

        RouteEngine.CrossLayerMergeResult m = RouteEngine.mergeCrossLayer(page, ctxChain(ctx));

        assertEquals(RouteHandleType.MOCK, m.rule.getType());
        assertEquals(0L, m.delayMs);
    }

    /** MODIFY + MONITOR | MOCK → MOCK fulfill + 断言 */
    @Test
    public void modifyMonitorPage_ctxMock_isMockWithMonitor() {
        RouteRule page = rule(RouteHandleType.MODIFY);
        page.setMonitorEnabled(true);
        page.setRequestHeadersToSet(Map.of("X-Page", "p"));
        RouteRule ctx = rule(RouteHandleType.MOCK);

        RouteEngine.CrossLayerMergeResult m = RouteEngine.mergeCrossLayer(page, ctxChain(ctx));

        assertEquals(RouteHandleType.MOCK, m.rule.getType());
        assertTrue(m.rule.isMonitorEnabled());
    }

    // ───────────────────────── DELAY / 能力位 OR ─────────────────────────

    /** page DELAY(300) + ctx DELAY(500) → 取 max = 500，type 保持 page(DELAY) */
    @Test
    public void delayMax_keepsMaxAndPageType() {
        RouteRule page = rule(RouteHandleType.DELAY);
        page.setDelayMs(300);
        RouteRule ctx = rule(RouteHandleType.DELAY);
        ctx.setDelayMs(500);

        RouteEngine.CrossLayerMergeResult m = RouteEngine.mergeCrossLayer(page, ctxChain(ctx));

        assertEquals(500L, m.delayMs);
        assertEquals(RouteHandleType.DELAY, m.rule.getType());
    }

    /** n53：ctx DELAY(500) + page MOCK → MOCK 延迟 500ms 后 fulfill */
    @Test
    public void ctxDelayWithPageMock_delayAppliesToMock() {
        RouteRule page = rule(RouteHandleType.MOCK);
        RouteRule ctx = rule(RouteHandleType.DELAY);
        ctx.setDelayMs(500);

        RouteEngine.CrossLayerMergeResult m = RouteEngine.mergeCrossLayer(page, ctxChain(ctx));

        assertEquals(RouteHandleType.MOCK, m.rule.getType());
        assertEquals(500L, m.delayMs);
    }

    /** 能力位 OR：page MONITOR + ctx MODIFY(headers) → MONITOR + ctx 头部被合并 */
    @Test
    public void capabilityOr_mergesMonitorAndModifyHeaders() {
        RouteRule page = rule(RouteHandleType.MONITOR);
        page.setMonitorEnabled(true);
        RouteRule ctx = rule(RouteHandleType.MODIFY);
        ctx.setRequestHeadersToSet(Map.of("X-Ctx", "c"));

        RouteEngine.CrossLayerMergeResult m = RouteEngine.mergeCrossLayer(page, ctxChain(ctx));

        assertEquals(RouteHandleType.MONITOR, m.rule.getType());
        assertTrue(m.rule.isMonitorEnabled());
        assertEquals("c", m.rule.getRequestHeadersToSet().get("X-Ctx"));
    }

    // ───────────────────────── 不变量：输入不可变 ─────────────────────────

    /** mergeCrossLayer 绝不就地修改 page / ctx 输入规则 */
    @Test
    public void inputsAreNeverMutated() {
        RouteRule page = rule(RouteHandleType.MODIFY);
        page.setMonitorEnabled(true);
        RouteRule ctx = rule(RouteHandleType.MONITOR);
        ctx.setMonitorEnabled(true);

        RouteEngine.CrossLayerMergeResult m = RouteEngine.mergeCrossLayer(page, ctxChain(ctx));

        // 输入对象身份与方法结果不同（copyForMerge 产生新实例）
        assertNotSame(page, m.rule);
        assertNotSame(ctx, m.rule);
        // 输入自身字段不被改动
        assertSame(RouteHandleType.MODIFY, page.getType());
        assertTrue(page.isMonitorEnabled());
        assertSame(RouteHandleType.MONITOR, ctx.getType());
        assertTrue(ctx.isMonitorEnabled());
        // page 上的 ctx 能力位不应反向污染 ctx 规则
        assertFalse( ctx.isMonitorEnabled() && ctx.getType() == RouteHandleType.MODIFY, "ctx 规则不应被写入 page 的 MODIFY 能力");
    }
}
