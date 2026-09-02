package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRuleScope;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 固化 Phase 3 统一绑定模型的核心纯函数 {@link RouteEngine#resolveUnified} 的契约。
 *
 * <p>本测试<b>不依赖 Playwright</b>（纯逻辑），覆盖：
 * <ul>
 *   <li>CONTEXT 规则作用于同 context 所有页面（含 reqPage=null）；</li>
 *   <li>PAGE 规则仅匹配其 {@code pageRef} 身份（弹窗/iframe 专属隔离，不串其它页）；</li>
 *   <li>同链多 PAGE 规则按 reqPage 精确命中；</li>
 *   <li>PAGE + CONTEXT 合并（page 特定 &gt; context 全域、能力位 OR、MOCK 终结、DELAY 取 max）；</li>
 *   <li>reqPage 不匹配 PAGE 规则时回退到 CONTEXT 规则；</li>
 *   <li>输入规则绝不被就地修改（copyForMerge 不变量）。</li>
 * </ul>
 */
public class RouteUnifiedScopeTest {

    private static RouteRule rule(RouteHandleType type) {
        RouteRule r = new RouteRule();
        r.setUrlPattern("/api/order");
        r.setType(type);
        return r;
    }

    private static RouteRule pageRule(RouteHandleType type, Object page) {
        RouteRule r = rule(type);
        r.setScope(RouteRuleScope.PAGE);
        r.setPageRef(page);
        return r;
    }

    private static RouteRule ctxRule(RouteHandleType type) {
        RouteRule r = rule(type);
        r.setScope(RouteRuleScope.CONTEXT);
        return r;
    }

    // ── 仅 CONTEXT 规则：任何页面（含 reqPage=null）均适用 ──
    @Test
    public void contextOnly_appliesToAnyPage() {
        Object page = new Object();
        List<RouteRule> chain = Arrays.asList(ctxRule(RouteHandleType.MONITOR));
        RouteEngine.ResolvedUnified r = RouteEngine.resolveUnified(chain, page);
        assertNotNull(r);
        assertEquals(RouteHandleType.MONITOR, r.rule.getType());

        // reqPage=null 也应命中 context 规则
        RouteEngine.ResolvedUnified r2 = RouteEngine.resolveUnified(chain, null);
        assertNotNull(r2);
    }

    // ── 仅 PAGE 规则且 pageRef 匹配 ──
    @Test
    public void pageRule_matchesOnlyItsPage() {
        Object pageA = new Object();
        Object pageB = new Object();
        RouteRule pr = pageRule(RouteHandleType.MOCK, pageA);

        RouteEngine.ResolvedUnified hit = RouteEngine.resolveUnified(Arrays.asList(pr), pageA);
        assertNotNull(hit);
        assertEquals(RouteHandleType.MOCK, hit.rule.getType());

        // 不同页 → 无适用规则
        RouteEngine.ResolvedUnified miss = RouteEngine.resolveUnified(Arrays.asList(pr), pageB);
        assertNull(miss);

        // reqPage=null → 无适用规则（page 规则要求精确身份）
        RouteEngine.ResolvedUnified missNull = RouteEngine.resolveUnified(Arrays.asList(pr), null);
        assertNull(missNull);
    }

    // ── 多 page 规则同链：仅 reqPage 那个生效（弹窗/iframe 隔离）──
    @Test
    public void perPageIsolation_inSameChain() {
        Object popup = new Object();
        Object main = new Object();
        RouteRule popupRule = pageRule(RouteHandleType.MOCK, popup);
        RouteRule mainRule = pageRule(RouteHandleType.MONITOR, main);

        RouteEngine.ResolvedUnified forPopup = RouteEngine.resolveUnified(Arrays.asList(popupRule, mainRule), popup);
        assertNotNull(forPopup);
        assertEquals(RouteHandleType.MOCK, forPopup.rule.getType());

        RouteEngine.ResolvedUnified forMain = RouteEngine.resolveUnified(Arrays.asList(popupRule, mainRule), main);
        assertNotNull(forMain);
        assertEquals(RouteHandleType.MONITOR, forMain.rule.getType());
    }

    // ── PAGE + CONTEXT 合并：非终结能力位跨层 OR 叠加 ──
    @Test
    public void pageAndContext_mergeCrossLayer() {
        Object page = new Object();
        RouteRule pr = pageRule(RouteHandleType.MONITOR, page);
        pr.setMonitorEnabled(true);
        RouteRule cr = ctxRule(RouteHandleType.MOCK);

        RouteEngine.ResolvedUnified r = RouteEngine.resolveUnified(Arrays.asList(pr, cr), page);
        assertNotNull(r);
        assertEquals(RouteHandleType.MOCK, r.rule.getType());
        assertTrue(r.rule.isMonitorEnabled());
    }

    // ── PAGE + CONTEXT 同为 MOCK：page 特定状态码/响应体优先（统一绑定「page 特定 > context 全域」）──
    @Test
    public void pageMockStatus_winsOverContextMockStatus() {
        Object page = new Object();
        RouteRule pr = pageRule(RouteHandleType.MOCK, page);
        pr.setMockStatus(200);
        pr.setMockBody("PAGE");
        RouteRule cr = ctxRule(RouteHandleType.MOCK);
        cr.setMockStatus(201);
        cr.setMockBody("CTX");

        RouteEngine.ResolvedUnified r = RouteEngine.resolveUnified(Arrays.asList(pr, cr), page);
        assertNotNull(r);
        assertEquals(200, r.rule.getMockStatus());
        assertEquals("PAGE", r.rule.getMockBody());
    }

    // ── PAGE + CONTEXT，但 reqPage 不匹配 page → 仅 context 生效 ──
    @Test
    public void pageNotMatching_reqPage_fallsBackToContext() {
        Object pageA = new Object();
        Object pageB = new Object();
        RouteRule pr = pageRule(RouteHandleType.MOCK, pageA);
        RouteRule cr = ctxRule(RouteHandleType.MONITOR);
        cr.setMonitorEnabled(true);

        RouteEngine.ResolvedUnified r = RouteEngine.resolveUnified(Arrays.asList(pr, cr), pageB);
        assertNotNull(r);
        // page 规则不命中 → 仅 context MONITOR 生效（不是 page MOCK）
        assertEquals(RouteHandleType.MONITOR, r.rule.getType());
        assertTrue(r.rule.isMonitorEnabled());
    }

    // ── DELAY 跨层取 max（对齐 §5）──
    @Test
    public void delay_mergedAsMax() {
        Object page = new Object();
        RouteRule pr = pageRule(RouteHandleType.MONITOR, page);
        pr.setDelayMs(1000);
        RouteRule cr = ctxRule(RouteHandleType.MONITOR);
        cr.setDelayMs(500);

        RouteEngine.ResolvedUnified r = RouteEngine.resolveUnified(Arrays.asList(pr, cr), page);
        assertNotNull(r);
        assertEquals(1000L, r.delayMs);
    }

    // ── 空链 / 全不匹配 → null ──
    @Test
    public void emptyChain_returnsNull() {
        assertNull(RouteEngine.resolveUnified(null, new Object()));
        assertNull(RouteEngine.resolveUnified(Collections.emptyList(), new Object()));
    }

    // ── 输入规则不被就地修改（copyForMerge 不变量）──
    @Test
    public void inputs_notMutated() {
        Object page = new Object();
        RouteRule pr = pageRule(RouteHandleType.MONITOR, page);
        pr.setRequestHeadersToSet(Map.of("X-Page", "p"));
        RouteRule cr = ctxRule(RouteHandleType.MOCK);
        cr.setMockBody("orig");
        int beforeHash = cr.hashCode();

        RouteEngine.resolveUnified(Arrays.asList(pr, cr), page);

        // ctx MOCK 的 mockBody 不应被 page 规则污染
        assertEquals("orig", cr.getMockBody());
        assertEquals(beforeHash, cr.hashCode());
    }
}
