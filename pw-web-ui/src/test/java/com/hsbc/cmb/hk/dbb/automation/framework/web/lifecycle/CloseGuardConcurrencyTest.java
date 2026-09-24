package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关闭看门狗并发契约：超时<b>不遗留后台线程</b>。
 *
 * <p>历史上 {@code runBounded} 超时只是"放任 daemon 继续跑"，原生 {@code close()} 挂死时该线程残留到 JVM 退出。
 * 加固后：超时即中断 worker、worker 结束自行移除、并暴露 {@link CloseGuard#inFlightCount()} 供断言。
 * 本测试覆盖：可中断挂死被中断回收、不响应中断的阻塞在外部解除（=driver 被强收）后回收、并发超时不泄漏。</p>
 *
 * <p>纯并发单测，不依赖浏览器运行时。</p>
 */
@DisplayName("CloseGuard：超时中断 + 在途线程确定性回收（无后台线程泄漏）")
class CloseGuardConcurrencyTest {

    @Test
    @DisplayName("可中断的挂死动作：超时后 worker 被中断并回收（inFlightCount 回到基线）")
    void interruptibleHangIsReclaimed() throws InterruptedException {
        int base = CloseGuard.inFlightCount();

        boolean completed = CloseGuard.runBounded("test/interruptible", () -> {
            try {
                Thread.sleep(30_000); // 可中断
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return; // 被看门狗中断 → worker 正常结束
            }
        }, 200);

        assertFalse(completed, "超时必须放弃等待");
        awaitInFlightBackTo(base, 2_000);
    }

    @Test
    @DisplayName("不响应中断的阻塞：外部解除阻塞（=driver 被强收）后 worker 被回收，不泄漏")
    void nonInterruptibleBlockReclaimedAfterExternalRelease() throws InterruptedException {
        int base = CloseGuard.inFlightCount();
        CountDownLatch released = new CountDownLatch(1);

        boolean completed = CloseGuard.runBounded("test/native-like", () -> {
            //  模拟原生 CDP 调用：忽略中断，只在外因（driver 被强收）解除阻塞后才返回
            while (released.getCount() != 0) {
                try {
                    released.await(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // 忽略中断，继续等（= 原生调用行为）
                }
            }
        }, 200);

        assertFalse(completed, "超时必须放弃等待");
        //  模拟调用方在超时路径「强收本线程 driver」→ 阻塞条件解除
        released.countDown();
        awaitInFlightBackTo(base, 2_000);
    }

    @Test
    @DisplayName("并发超时：多个挂死动作同时超时，全部 worker 最终被回收（无泄漏）")
    void concurrentTimeoutsAllReclaimed() throws InterruptedException {
        int base = CloseGuard.inFlightCount();
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch released = new CountDownLatch(1);
        try {
            for (int i = 0; i < n; i++) {
                pool.execute(() -> CloseGuard.runBounded("test/concurrent", () -> {
                    while (released.getCount() != 0) {
                        try {
                            released.await(50, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }, 150));
            }
        } finally {
            pool.shutdown();
        }
        released.countDown(); // 外部解除（= driver 强收）
        awaitInFlightBackTo(base, 3_000);
    }

    @Test
    @DisplayName("并发快动作：全部判为完成，无残留 worker")
    void concurrentFastActionsCompleteAndReclaim() throws InterruptedException {
        int base = CloseGuard.inFlightCount();
        int n = 32;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            for (int i = 0; i < n; i++) {
                pool.execute(() -> CloseGuard.runBounded("test/fast", () -> {
                }, 2_000));
            }
        } finally {
            pool.shutdown();
        }
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        awaitInFlightBackTo(base, 2_000);
    }

    private static void awaitInFlightBackTo(int base, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (CloseGuard.inFlightCount() > base) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("超时后 worker 必须被回收至基线（期望=" + base
                        + ", 实际=" + CloseGuard.inFlightCount() + "）—— 存在后台线程泄漏");
            }
            Thread.sleep(20);
        }
    }
}
