package com.hsbc.cmb.hk.dbb.automation.framework.route.exec;

import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteConfig;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * IO 执行器自适应并发验证。
 *
 * <p><b>为什么需要自适应</b>：登录期十几个请求要同时读响应体 / 做字段替换 / 拉真实响应，
 * 全部挤在 2 个 IO 线程上（实测 MOCK intercept 的 {@code elapsed} 达 463–923ms，其中排队占相当一部分）。
 * 但把并发直接调大又会在常态下浪费线程、影响隔离性 —— 故改为：<b>有积压就抬、连续空闲就回落</b>，
 * 不引入任何配置项（基线仍是构造入参，上限是内部常量）。</p>
 */
public class RouteIoExecutorTest {

    /** 队列出现积压时必须抬升 core（否则有界队列"等队满再扩容"已经晚了：任务已被拒绝）。 */
    @Test
    public void growsWhenQueueHasBacklog() throws Exception {
        RouteIoExecutor io = new RouteIoExecutor("t", 1, 64);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        try {
            io.trySubmit("blocker", () -> awaitQuietly(blockerStarted, release));
            assertTrue("占位任务须已开始执行", blockerStarted.await(5, TimeUnit.SECONDS));

            // 基线线程被占住 ⇒ 后续任务的入队会让队列非空 ⇒ 提交侧应主动扩容
            for (int i = 0; i < 4; i++) {
                io.trySubmit("queued-" + i, () -> awaitQuietly(null, release));
            }

            assertTrue("队列积压必须触发扩容（core 从基线抬升）",
                    io.coreThreads() > io.baselineThreads());
            assertTrue("扩容次数须可观测", io.grownCount() > 0);
            assertTrue("观测到的最大 core 不得超过内部上限",
                    io.maxCoreObserved() <= RouteConfig.DEFAULT_OPS_MAX_CONCURRENT);
        } finally {
            release.countDown();
            io.close();
        }
    }

    /** 突发过后（连续空闲任务）必须回落到基线，避免"涨上去不下来"把基线配置架空。 */
    @Test
    public void returnsToBaselineAfterIdleTasks() throws Exception {
        RouteIoExecutor io = new RouteIoExecutor("t", 1, 64);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        try {
            io.trySubmit("blocker", () -> awaitQuietly(blockerStarted, release));
            assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 4; i++) {
                io.trySubmit("queued-" + i, () -> awaitQuietly(null, release));
            }
            assertTrue("前置条件：已扩容", io.coreThreads() > io.baselineThreads());

            release.countDown(); // 放行所有占位任务
            // 按"轻载"节奏提交：每次等上一个任务跑完再提交下一个 ⇒ 完成时刻队列必然为空，
            // 连续空闲计数得以累积（一次性塞一批属于重载，那不是回落的适用场景）。
            for (int i = 0; i < 80; i++) {
                io.trySubmit("idle-" + i, () -> { });
                awaitUntil(() -> io.activeCount() == 0, 5_000L);
            }

            awaitUntil(() -> io.coreThreads() == io.baselineThreads(), 5_000L);
            assertEquals("空闲后必须回落基线", io.baselineThreads(), io.coreThreads());
            assertTrue("回落次数须可观测", io.shrunkCount() > 0);
        } finally {
            release.countDown();
            io.close();
        }
    }

    /** 基线高于内部上限时以基线为准（不得把用户要求降到更低并发，更不得构造失败）。 */
    @Test
    public void baselineAboveInternalCeilingIsHonoured() {
        RouteIoExecutor io = new RouteIoExecutor("t", RouteConfig.DEFAULT_OPS_MAX_CONCURRENT + 4, 8);
        try {
            assertEquals(RouteConfig.DEFAULT_OPS_MAX_CONCURRENT + 4, io.baselineThreads());
            assertEquals("基线高于内部上限时，core 就是基线（不退化）",
                    RouteConfig.DEFAULT_OPS_MAX_CONCURRENT + 4, io.coreThreads());
        } finally {
            io.close();
        }
    }

    private static void awaitQuietly(CountDownLatch started, CountDownLatch release) {
        if (started != null) {
            started.countDown();
        }
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitUntil(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
