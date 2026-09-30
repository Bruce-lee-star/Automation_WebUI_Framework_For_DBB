package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * V2-1 守卫（评审 23 号 P0）：DELAY 终结 / 响应轮询<b>不得</b>再落到
 * {@code CompletableFuture.delayedExecutor(ms, unit)} 的 {@code ForkJoinPool.commonPool()}。
 *
 * <p>该两参重载会污染 JVM 级共享池（并行度 = 核数 - 1），且与本模块"有界并发"原则冲突 ——
 * 改走 {@link RouteDelayScheduler}（模块自有、daemon、线程数有界）。</p>
 *
 * <p><b>可证伪</b>：若有人把调用点改回 {@code delayedExecutor} 两参，本测试对线程名前缀的断言即失败
 * （commonPool 的线程名为 {@code ForkJoinPool.commonPool-worker-N}）。</p>
 */
public class RouteDelaySchedulerTest {

    /** 核心守卫：任务必须跑在模块自有 daemon 线程上，绝不是 commonPool worker。 */
    @Test
    public void delayMustRunOnModuleOwnedDaemonThreadNotCommonPool() throws Exception {
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicReference<Boolean> daemon = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        RouteDelayScheduler.delay(1L, () -> {
            threadName.set(Thread.currentThread().getName());
            daemon.set(Thread.currentThread().isDaemon());
            done.countDown();
        });

        assertTrue("延迟任务必须在界内执行", done.await(5, TimeUnit.SECONDS));
        assertNotNull("必须捕获到线程名", threadName.get());
        assertTrue("V2-1：必须跑在模块自有线程（前缀 " + RouteDelayScheduler.THREAD_NAME_PREFIX + "），实际="
                + threadName.get(), threadName.get().startsWith(RouteDelayScheduler.THREAD_NAME_PREFIX));
        assertFalse("V2-1 回归：绝不能落到 ForkJoinPool.commonPool，实际=" + threadName.get(),
                threadName.get().startsWith("ForkJoinPool.commonPool"));
        assertTrue("必须是 daemon 线程（不得阻碍 JVM 退出）", daemon.get());
    }

    /** 负延迟按 0 处理，仍然执行（不抛）。 */
    @Test
    public void negativeDelayIsClampedAndStillRuns() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        RouteDelayScheduler.delay(-5L, done::countDown);
        assertTrue("负延迟必须按 0 处理并执行", done.await(5, TimeUnit.SECONDS));
    }

    /** null 任务安全忽略（绝不抛回事件线程）。 */
    @Test
    public void nullTaskIsIgnored() {
        RouteDelayScheduler.delay(1L, null);
    }
}
