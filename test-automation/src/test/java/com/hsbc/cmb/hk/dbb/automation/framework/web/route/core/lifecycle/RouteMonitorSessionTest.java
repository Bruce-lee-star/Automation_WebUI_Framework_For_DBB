package com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle.RouteMonitorSession;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRule;

/**
 * 固化 Phase 5 拆分出的 Monitor 会话域（{@link RouteMonitorSession}）纯逻辑契约。
 *
 * <p>与 {@link RouteMonitorSession} 同包，可直接访问其 package-private 方法与
 * {@link RouteMonitorSession.MonitorSession} 内部状态（如 {@code stopped}）。
 * <b>不依赖 Playwright</b> —— 仅校验 SESSIONS 注册表的创建 / 计数 / 匹配 / 停止 / 清理不变量，
 * 与拆分前的行为严格一致（拆分只迁移实现，不改语义）。
 */
public class RouteMonitorSessionTest {

    private final Object ctx = new Object();
    private final String pattern = "/api/order";

    @After
    public void tearDown() {
        RouteMonitorSession.clearAll();
    }

    private static RouteRule monitorRule(boolean autoStop, int minMatches, long timeoutMs) {
        RouteRule r = new RouteRule();
        r.setUrlPattern("/api/order");
        r.setType(RouteHandleType.MONITOR);
        r.setMonitorEnabled(true);
        r.setAutoStopOnMatch(autoStop);
        r.setMinMatches(minMatches);
        r.setTimeoutMs(timeoutMs);
        return r;
    }

    @Test
    public void start_createsSession_and_countReflects() {
        RouteRule r = monitorRule(true, 1, 0);
        RouteMonitorSession.startMonitorSession(ctx, r, pattern);
        assertEquals(1, RouteMonitorSession.sessionCount());
    }

    @Test
    public void noSessionNeeded_whenNoTimeoutNoAutoStop() {
        // autoStop=false 且 timeout=0 → 无限监控/拦截，无需会话
        RouteRule r = monitorRule(false, 1, 0);
        RouteMonitorSession.startMonitorSession(ctx, r, pattern);
        assertEquals(0, RouteMonitorSession.sessionCount());
    }

    @Test
    public void onMonitorMatch_increments_and_autoStops_atMinMatches() {
        RouteRule r = monitorRule(true, 2, 0);
        RouteMonitorSession.startMonitorSession(ctx, r, pattern);
        RouteMonitorSession.MonitorSession s = RouteMonitorSession.sessionForRoute(null, r);
        assertNotNull(s);
        assertFalse(s.stopped.get());

        RouteMonitorSession.onMonitorMatch(r); // count 1, < minMatches
        assertFalse(s.stopped.get());
        RouteMonitorSession.onMonitorMatch(r); // count 2 == minMatches → autoStop
        assertTrue(s.stopped.get());
    }

    @Test
    public void onMonitorMatch_noOp_whenSessionStopped() {
        RouteRule r = monitorRule(true, 1, 0);
        RouteMonitorSession.startMonitorSession(ctx, r, pattern);
        RouteMonitorSession.onMonitorMatch(r); // count 1 == minMatches → autoStop
        RouteMonitorSession.MonitorSession s = RouteMonitorSession.sessionForRoute(null, r);
        assertTrue(s.stopped.get());
        // 已停止的会话再次 onMonitorMatch 不应抛错（幂等）
        RouteMonitorSession.onMonitorMatch(r);
        assertTrue(s.stopped.get());
    }

    @Test
    public void stopSessionsFor_stopsMatchingSession() {
        RouteRule r = monitorRule(true, 1, 0);
        RouteMonitorSession.startMonitorSession(ctx, r, pattern);
        RouteMonitorSession.stopSessionsFor(ctx, pattern);
        RouteMonitorSession.MonitorSession s = RouteMonitorSession.sessionForRoute(null, r);
        assertNotNull(s);
        assertTrue(s.stopped.get());
    }

    @Test
    public void clearMonitorSessions_removesContextSessions() {
        RouteRule r = monitorRule(true, 1, 0);
        RouteMonitorSession.startMonitorSession(ctx, r, pattern);
        RouteMonitorSession.clearMonitorSessions(ctx);
        assertEquals(0, RouteMonitorSession.sessionCount());
    }

    @Test
    public void clearAll_emptiesRegistry() {
        RouteRule r = monitorRule(true, 1, 0);
        RouteMonitorSession.startMonitorSession(ctx, r, pattern);
        RouteMonitorSession.clearAll();
        assertEquals(0, RouteMonitorSession.sessionCount());
    }
}
