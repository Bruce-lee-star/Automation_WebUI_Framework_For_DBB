package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * WEB-P1-5 种子测试：监听器 per-thread 守卫与失败日志去重（无浏览器）。
 * 状态经 {@code TestContextHolder} 按线程隔离，用例结束后主动 {@code clearForThread()} 防跨用例残留。
 */
public class ListenerGuardTest {

    @After
    public void clearThreadState() {
        ListenerGuard.clearForThread();
    }

    @Test
    public void guards_isStableWithinThread() {
        ListenerGuard.ListenerGuardState first = ListenerGuard.guards();
        assertSame("同一线程内守卫状态应惰性复用同一实例", first, ListenerGuard.guards());
    }

    @Test
    public void clearForThread_recreatesGuards() {
        ListenerGuard.ListenerGuardState before = ListenerGuard.guards();

        ListenerGuard.clearForThread();

        assertNotSame("清空后应重建守卫状态，避免跨 scenario 残留", before, ListenerGuard.guards());
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

        assertTrue("首次失败应完整打印", ListenerGuard.shouldReportFailure(t, "summary"));
        assertFalse("同一异常实例重复出现应去重", ListenerGuard.shouldReportFailure(t, "summary"));
    }

    @Test
    public void shouldReportFailure_distinguishesDifferentInstances() {
        assertTrue(ListenerGuard.shouldReportFailure(new RuntimeException("boom"), "summary"));
        assertTrue("不同异常实例（identityHashCode 不同）应各自报告一次",
                ListenerGuard.shouldReportFailure(new RuntimeException("boom"), "summary"));
    }

    @Test
    public void shouldReportFailure_handlesNullThrowable() {
        assertTrue(ListenerGuard.shouldReportFailure(null, "no-throwable"));
        assertFalse("null 异常同样按摘要去重", ListenerGuard.shouldReportFailure(null, "no-throwable"));
    }

    @Test
    public void clearForThread_resetsFailureDeduplication() {
        Throwable t = new RuntimeException("boom");
        assertTrue(ListenerGuard.shouldReportFailure(t, "summary"));

        ListenerGuard.clearForThread();

        assertTrue("清空后同一异常可再次报告（新 scenario 归零）",
                ListenerGuard.shouldReportFailure(t, "summary"));
    }
}
