package com.hsbc.cmb.hk.dbb.automation.framework.common.assertion;

import com.hsbc.cmb.hk.dbb.automation.framework.common.async.AsyncPool;
import com.hsbc.cmb.hk.dbb.automation.framework.common.logging.TestLogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评审 F-13 / P1-5 回归：<b>异步任务里的软断言失败必须被判红</b>，不得静默假绿。
 *
 * <p><b>修复前的失效机理</b>：{@code SoftAssertions} 的失败收集器是纯 {@code ThreadLocal}，
 * 异步任务在<b>工作线程</b>的收集器上记录失败，而场景末只对<b>主线程</b>的收集器
 * （由 Serenity 监听器调 {@code assertAll()}）聚合 —— 工作线程那份<b>无人上报</b>，
 * 异步断言失败＝用例仍绿。</p>
 *
 * <p><b>修复后</b>：{@code AsyncPool} 三条投递路径（立即 / 延迟 / monitor 回调，另含周期任务）
 * 在提交线程侧捕获收集器、工作线程侧绑定共享，故工作线程写入的失败与父线程同源，
 * 随父线程场景末 {@code assertAll()} 一并判红；同时新增「场景收尾后仍写入」的显式 WARN 守卫。</p>
 */
public class SoftAssertionsCrossThreadTest {

    private static final long AWAIT_SECONDS = 5;

    @AfterEach
    void tearDown() {
        SoftAssertions.clearForCurrentThread();
    }

    @Test
    void asyncTaskFailureIsReportedByParentAssertAll() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        AsyncPool.run(() -> {
            try {
                worker.set(Thread.currentThread());
                SoftAssertions.assertEquals("expected", "actual", "async-body-mismatch");
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(AWAIT_SECONDS, TimeUnit.SECONDS), "异步任务应在 5s 内完成");
        assertNotSame(Thread.currentThread(), worker.get(), "断言必须确实发生在异步线程上（否则本测试失去意义）");

        FrameworkAssertionError error = assertThrows(FrameworkAssertionError.class, SoftAssertions::assertAll,
                "F-13：异步任务记录的软断言失败必须由父线程场景末 assertAll() 判红");
        assertTrue(error.getMessage().contains("async-body-mismatch"),
                "聚合错误应包含异步任务的失败信息，实际：" + error.getMessage());
    }

    @Test
    void scheduledTaskFailureIsReportedByParentAssertAll() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AsyncPool.schedule(() -> {
            try {
                SoftAssertions.fail("scheduled-task-failure");
            } finally {
                done.countDown();
            }
        }, 10);

        assertTrue(done.await(AWAIT_SECONDS, TimeUnit.SECONDS), "延迟任务应在 5s 内完成");
        assertThrows(FrameworkAssertionError.class, SoftAssertions::assertAll,
                "F-13：延迟任务的软断言失败同样必须被上报");
    }

    @Test
    void handoffApiSharesCollectorAcrossThreadsAndUnbindRestoresBinding() throws Exception {
        SoftAssertions.Collector parentCollector = SoftAssertions.captureCollector();
        AtomicBoolean workerSawFailure = new AtomicBoolean();
        AtomicBoolean workerCleanAfterUnbind = new AtomicBoolean();

        Thread worker = new Thread(() -> {
            SoftAssertions.Collector previous = SoftAssertions.bindCollector(parentCollector);
            try {
                SoftAssertions.fail("from-worker-thread");
                workerSawFailure.set(SoftAssertions.hasFailures());
            } finally {
                SoftAssertions.unbindCollector(previous); // previous == null：该线程原本无绑定 → 摘除条目
            }
            workerCleanAfterUnbind.set(!SoftAssertions.hasFailures());
        }, "handoff-probe");
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        assertFalse(worker.isAlive(), "探测线程应在超时前结束");

        assertTrue(workerSawFailure.get(), "工作线程绑定后应能读到（并写入）共享收集器");
        assertTrue(workerCleanAfterUnbind.get(), "unbind(null) 必须摘除绑定，避免线程池复用时残留上一用例的收集器");
        assertTrue(SoftAssertions.hasFailures(), "父线程收集器应收到子线程记录的失败（跨线程同源）");

        FrameworkAssertionError error = assertThrows(FrameworkAssertionError.class, SoftAssertions::assertAll);
        assertTrue(error.getMessage().contains("from-worker-thread"));
    }

    @Test
    void failuresRecordedAfterScenarioEndAreWarnedNotSilentlyDropped() throws Exception {
        String marker = TestLogCapture.newMarker("late-soft-assert-");
        try (TestLogCapture capture = TestLogCapture.of(SoftAssertions.class, "%level %msg%n")) {
            SoftAssertions.Collector shared = SoftAssertions.captureCollector();
            CountDownLatch bound = new CountDownLatch(1);
            CountDownLatch scenarioEnded = new CountDownLatch(1);
            CountDownLatch recordedLate = new CountDownLatch(1);

            // 逾期异步任务：在场景收尾前已 bind 共享收集器，收尾之后才落回失败
            Thread lateWorker = new Thread(() -> {
                SoftAssertions.Collector previous = SoftAssertions.bindCollector(shared);
                try {
                    bound.countDown();
                    awaitQuietly(scenarioEnded);
                    SoftAssertions.fail(marker);
                } finally {
                    SoftAssertions.unbindCollector(previous);
                    recordedLate.countDown();
                }
            }, "late-soft-assert-worker");
            lateWorker.start();

            assertTrue(bound.await(AWAIT_SECONDS, TimeUnit.SECONDS), "工作线程应先完成绑定");
            SoftAssertions.clearForCurrentThread(); // 场景收尾：关闭并摘除收集器（父线程不再持有）
            scenarioEnded.countDown();
            assertTrue(recordedLate.await(AWAIT_SECONDS, TimeUnit.SECONDS), "逾期写入应已完成");
            lateWorker.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));

            String content = capture.content();
            assertTrue(content.contains("NOT be reported"),
                    "F-13 守卫：逾期写入必须打 WARN 明示「该失败不会被上报」（不得静默丢弃），实际日志：" + content);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void nextScenarioDoesNotInheritPreviousAsyncFailures() throws Exception {
        // 场景 1：异步失败 → 场景末聚合抛出（同时清理收集器）
        CountDownLatch first = new CountDownLatch(1);
        AsyncPool.run(() -> {
            try {
                SoftAssertions.fail("scenario-1-failure");
            } finally {
                first.countDown();
            }
        });
        assertTrue(first.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertThrows(FrameworkAssertionError.class, SoftAssertions::assertAll);
        assertFalse(SoftAssertions.hasFailures(), "场景末清理后不得残留失败（否则会算到下一用例头上）");

        // 场景 2：工作线程必须看不到上一个场景的失败
        AtomicBoolean sawStale = new AtomicBoolean(true);
        CountDownLatch second = new CountDownLatch(1);
        AsyncPool.run(() -> {
            try {
                sawStale.set(SoftAssertions.hasFailures());
            } finally {
                second.countDown();
            }
        });
        assertTrue(second.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertFalse(sawStale.get(), "线程池线程不得残留上一场景收集器的内容（跨用例串扰）");
    }
}
