package com.hsbc.cmb.hk.dbb.automation.framework.route.exec;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 撤销执行器契约（2026-09-29，S2 / 竞态 R-4）。
 *
 * <p>守护三条语义：① 提交即执行（单线程串行）；② <b>任务异常不传染</b>（单个撤销抛错不影响执行器与后续撤销）；
 * ③ <b>关闭后拒绝新任务且不抛</b>（fail-open，调用方据此转收尾兜底）。</p>
 */
public class RouteRetireExecutorTest {

    @Test
    public void submittedTaskRuns() throws Exception {
        RouteRetireExecutor exec = new RouteRetireExecutor("t1");
        try {
            CountDownLatch done = new CountDownLatch(1);
            assertTrue(exec.trySubmit("retire:a", done::countDown));
            assertTrue("撤销任务必须被执行", done.await(3, TimeUnit.SECONDS));
        } finally {
            exec.close();
        }
    }

    @Test
    public void taskFailureDoesNotAffectSubsequentTasks() throws Exception {
        RouteRetireExecutor exec = new RouteRetireExecutor("t2");
        try {
            AtomicInteger ran = new AtomicInteger();
            exec.trySubmit("retire:boom", () -> {
                throw new IllegalStateException("simulated unroute failure");
            });
            CountDownLatch second = new CountDownLatch(1);
            exec.trySubmit("retire:ok", () -> {
                ran.incrementAndGet();
                second.countDown();
            });
            assertTrue("前一个撤销抛错不得影响后续撤销", second.await(3, TimeUnit.SECONDS));
            assertTrue(ran.get() >= 1);
        } finally {
            exec.close();
        }
    }

    @Test
    public void closeRejectsFurtherTasksWithoutThrowing() {
        RouteRetireExecutor exec = new RouteRetireExecutor("t3");
        exec.close();
        AtomicBoolean ran = new AtomicBoolean(false);
        assertFalse("关闭后提交必须 fail-open 返回 false（不抛）", exec.trySubmit("retire:late", () -> ran.set(true)));
        assertFalse(ran.get());
        exec.close(); // 幂等：重复关闭不得抛
        assertTrue(exec.rejectedCount() >= 1);
    }
}
