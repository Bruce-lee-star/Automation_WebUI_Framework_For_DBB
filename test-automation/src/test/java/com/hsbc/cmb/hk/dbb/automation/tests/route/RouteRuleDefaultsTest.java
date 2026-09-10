package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.RouteRule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 固化 {@code ROUTE_FRAMEWORK_GUIDE.md} §3.3「公共匹配条件默认行为」的契约，
 * 以及 §0 / §3.2 相关规则默认值。
 *
 * <p>文档关键声明：
 * <ul>
 *   <li>{@code onlyMainFrame} 默认 {@code true}（iframe 默认被挡，需显式 allowAllFrames）；</li>
 *   <li>{@code onlyApiCall} 默认 {@code false}（匹配所有请求类型）；</li>
 *   <li>{@code autoStopOnMatch} 默认 {@code true}（MONITOR 默认命中即停止）；</li>
 *   <li>{@code minMatches} 默认 1，{@code times} 默认 0（无限拦截）。</li>
 * </ul>
 *
 * 纯逻辑测试，不依赖 Playwright。
 */
public class RouteRuleDefaultsTest {

    @Test
    public void defaults_matchDocSection3_3() {
        RouteRule r = new RouteRule();
        // §3.3 onlyMainFrame 默认 true
        assertTrue("onlyMainFrame should default true (iframe blocked by default)", r.isOnlyMainFrame());
        // §3.3 onlyApiCall 默认 false（匹配所有请求类型）
        assertFalse("onlyApiCall should default false", r.isOnlyApiCall());
    }

    @Test
    public void defaults_lifecycleAndStop() {
        RouteRule r = new RouteRule();
        // §3.2 MONITOR autoStop 默认 true；§3.2/§3.3 minMatches 默认 1；§3.3 times 默认 0（无限）
        assertTrue("autoStopOnMatch should default true", r.isAutoStopOnMatch());
        assertEquals(1, r.getMinMatches());
        assertEquals(0, r.getTimes());
        // 监控能力位默认关闭（须显式 monitor() 才开）
        assertFalse("monitorEnabled should default false", r.isMonitorEnabled());
    }

    @Test
    public void allowAllFrames_clearsOnlyMainFrame() {
        RouteRule r = new RouteRule();
        assertTrue(r.isOnlyMainFrame());
        r.setOnlyMainFrame(false);
        assertFalse("allowAllFrames() equivalent: onlyMainFrame must be false", r.isOnlyMainFrame());
    }

    @Test
    public void onlyApiCall_toggle() {
        RouteRule r = new RouteRule();
        assertFalse(r.isOnlyApiCall());
        r.setOnlyApiCall(true);
        assertTrue(r.isOnlyApiCall());
    }

    @Test
    public void times_zeroMeansUnlimited() {
        RouteRule r = new RouteRule();
        assertEquals(0, r.getTimes());
        assertFalse("times=0 means unlimited, never exhausted", r.isTimesExhausted());
        r.setTimes(2);
        assertEquals(2, r.getTimes());
        assertFalse(r.isTimesExhausted());
        r.decrementTimes();
        assertFalse(r.isTimesExhausted());
        r.decrementTimes();
        assertTrue(r.isTimesExhausted());
    }
}
