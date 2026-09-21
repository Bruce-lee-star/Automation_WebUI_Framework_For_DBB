package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WEB-P1-5 种子测试：监听器 per-thread 守卫与失败日志去重（无浏览器）。
 * 状态经 {@code TestContextHolder} 按线程隔离，用例结束后主动 {@code clearForThread()} 防跨用例残留。
 */
public class ListenerGuardTest {

    @AfterEach
    public void clearThreadState() {
        ListenerGuard.clearForThread();
    }

    @Test
    public void guards_isStableWithinThread() {
        ListenerGuard.ListenerGuardState first = ListenerGuard.guards();
        assertSame( first,  ListenerGuard.guards(), "同一线程内守卫状态应惰性复用同一实例");
    }

    @Test
    public void clearForThread_recreatesGuards() {
        ListenerGuard.ListenerGuardState before = ListenerGuard.guards();

        ListenerGuard.clearForThread();

        assertNotSame( before,  ListenerGuard.guards(), "清空后应重建守卫状态，避免跨 scenario 残留");
    }

    @Test
    public void guardState_roundTripsAllFlags() {
        ListenerGuard.ListenerGuardState state = ListenerGuard.guards();

        state.setFailureScreenshotsAlreadySent(true);
        state.setStepFinishProcessed(true);
        state.setStepFinishReentrant(true);
        state.setApiFailureAlreadyHandled(true);

        assertTrue(state.isFailureScreenshotsAlreadySent());
        assertTrue(state.isStepFinishProcessed());
        assertTrue(state.isStepFinishReentrant());
        assertTrue(state.isApiFailureAlreadyHandled());
    }

    @Test
    public void shouldReportFailure_reportsOnlyOncePerThrowableInstance() {
        Throwable t = new RuntimeException("boom");

        assertTrue( ListenerGuard.shouldReportFailure(t, "summary"), "首次失败应完整打印");
        assertFalse( ListenerGuard.shouldReportFailure(t, "summary"), "同一异常实例重复出现应去重");
    }

    @Test
    public void shouldReportFailure_distinguishesDifferentInstances() {
        assertTrue(ListenerGuard.shouldReportFailure(new RuntimeException("boom"), "summary"));
        assertTrue(
                ListenerGuard.shouldReportFailure(new RuntimeException("boom"), "summary"), "不同异常实例（identityHashCode 不同）应各自报告一次");
    }

    @Test
    public void shouldReportFailure_handlesNullThrowable() {
        assertTrue(ListenerGuard.shouldReportFailure(null, "no-throwable"));
        assertFalse( ListenerGuard.shouldReportFailure(null, "no-throwable"), "null 异常同样按摘要去重");
    }

    @Test
    public void clearForThread_resetsFailureDeduplication() {
        Throwable t = new RuntimeException("boom");
        assertTrue(ListenerGuard.shouldReportFailure(t, "summary"));

        ListenerGuard.clearForThread();

        assertTrue(
                ListenerGuard.shouldReportFailure(t, "summary"), "清空后同一异常可再次报告（新 scenario 归零）");
    }
}
