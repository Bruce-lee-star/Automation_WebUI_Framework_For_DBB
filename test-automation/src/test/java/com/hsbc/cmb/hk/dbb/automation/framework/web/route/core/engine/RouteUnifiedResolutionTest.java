package com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRuleScope;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteUnifiedResolution;

/**
 * 固化 Phase 5 抽离出的 {@link RouteUnifiedResolution} 纯函数契约（与拆分前语义严格一致）。
 *
 * <p>覆盖跨层合并核心不变量（原内联于 {@code RouteEngine}，现下沉为无状态纯函数）：
 * <ul>
 *   <li>{@code mergeCrossLayer}：能力位 OR、MOCK 为唯一终结者（短路）、DELAY 取 max、
 *       page 特定 &gt; context 全域（ctx 为 MOCK 终结者时丢弃 page delay）；</li>
 *   <li>{@code resolveUnified}：按 scope 分流（PAGE 仅绑定 page / CONTEXT 全域 / 混合委托 merge）、
 *       reqPage=null 时 page 级一律不命中、空链返回 null。</li>
 * </ul>
 * 结果载体 {@code CrossLayerMergeResult}/{@code ResolvedUnified} 为 {@link RouteEngine} 的 public 嵌套类型，
 * 与本类同包可直接访问。无 Playwright 依赖。
 */
public class RouteUnifiedResolutionTest {

    @Test
    public void mergeCrossLayer_pageTypeWins_whenNoMock() {
        RouteRule page = rule(RouteHandleType.MODIFY, RouteRuleScope.PAGE, null, 100);
        RouteRule ctx = rule(RouteHandleType.MONITOR, RouteRuleScope.CONTEXT, null, 500);
        RouteEngine.CrossLayerMergeResult r =
                RouteUnifiedResolution.mergeCrossLayer(page, Collections.singletonList(ctx));
        assertEquals(RouteHandleType.MODIFY, r.rule.getType());
        assertEquals("DELAY 取 max(100,500)", 500, r.delayMs);
        assertEquals(true, r.delayMerged);
    }

    @Test
    public void mergeCrossLayer_contextMockTerminates() {
        RouteRule page = rule(RouteHandleType.MODIFY, RouteRuleScope.PAGE, null, 100);
        RouteRule ctx = rule(RouteHandleType.MOCK, RouteRuleScope.CONTEXT, null, 500);
        ctx.setMockStatus(503);
        ctx.setMockBody("mock-body");
        RouteEngine.CrossLayerMergeResult r =
                RouteUnifiedResolution.mergeCrossLayer(page, Collections.singletonList(ctx));
        assertEquals(RouteHandleType.MOCK, r.rule.getType());
        // ctx 为 MOCK 终结者 → page delay 被丢弃，delayMs = ctx delay
        assertEquals(500, r.delayMs);
        assertEquals(503, r.rule.getMockStatus());
        assertEquals("mock-body", r.rule.getMockBody());
    }

    @Test
    public void mergeCrossLayer_pageMockWins_overContext() {
        RouteRule page = rule(RouteHandleType.MOCK, RouteRuleScope.PAGE, null, 100);
        page.setMockStatus(200);
        page.setMockBody("page-mock");
        RouteRule ctx = rule(RouteHandleType.MOCK, RouteRuleScope.CONTEXT, null, 500);
        ctx.setMockStatus(500);
        RouteEngine.CrossLayerMergeResult r =
                RouteUnifiedResolution.mergeCrossLayer(page, Collections.singletonList(ctx));
        assertEquals(RouteHandleType.MOCK, r.rule.getType());
        assertEquals(200, r.rule.getMockStatus());
        assertEquals("page-mock", r.rule.getMockBody());
        assertEquals("DELAY 仍取 max(100,500)", 500, r.delayMs);
    }

    @Test
    public void resolveUnified_nullPage_onlyContextMatches() {
        Object reqPage = new Object();
        RouteRule pageRule = rule(RouteHandleType.MODIFY, RouteRuleScope.PAGE, reqPage, 100);
        RouteRule ctxRule = rule(RouteHandleType.MONITOR, RouteRuleScope.CONTEXT, null, 200);
        RouteEngine.ResolvedUnified r = RouteUnifiedResolution.resolveUnified(
                Arrays.asList(pageRule, ctxRule), null); // reqPage=null → page 级不命中
        assertNotNull(r);
        assertEquals(RouteHandleType.MONITOR, r.rule.getType());
    }

    @Test
    public void resolveUnified_pageScope_matchesBoundPageOnly() {
        Object pageA = new Object();
        Object pageB = new Object();
        RouteRule pageRule = rule(RouteHandleType.MODIFY, RouteRuleScope.PAGE, pageA, 100);
        RouteEngine.ResolvedUnified hit = RouteUnifiedResolution.resolveUnified(
                Collections.singletonList(pageRule), pageA);
        assertNotNull(hit);
        assertEquals(RouteHandleType.MODIFY, hit.rule.getType());

        // 不同 page 不命中 → 返回 null（popup/iframe 专属隔离）
        RouteEngine.ResolvedUnified miss = RouteUnifiedResolution.resolveUnified(
                Collections.singletonList(pageRule), pageB);
        assertNull(miss);
    }

    @Test
    public void resolveUnified_emptyChain_returnsNull() {
        assertNull(RouteUnifiedResolution.resolveUnified(
                Collections.emptyList(), new Object()));
    }

    private static RouteRule rule(RouteHandleType type, RouteRuleScope scope, Object pageRef, long delayMs) {
        RouteRule r = new RouteRule();
        r.setType(type);
        r.setScope(scope);
        r.setPageRef(pageRef);
        r.setDelayMs(delayMs);
        return r;
    }
}
