package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 撤销执行器 —— 与 IO 池隔离的<b>专用单线程</b>执行器（2026-09-29，闭合竞态 R-4）。
 *
 * <p><b>为什么必须与 IO 池隔离</b>：注销是同步协议调用（{@code setNetworkInterceptionPatterns}，
 * 默认上界 {@link com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding.GuardedDriverCall#UNROUTE_BOUND_MS}），
 * 撤销线程会<b>等待</b>回包。若与 body 断言共用 {@link RouteIoExecutor}，一次不响应的注销就会占住 IO 线程
 * ⇒ 断言任务被拒（{@code rejectedBodyAssertions}，覆盖损失，且属"框架自身导致的观测缺失"）。独立单线程
 * 使两类工作互不挤压。</p>
 *
 * <p><b>设计约束</b>：
 * <ul>
 *   <li><b>单线程</b>：撤销是低频、幂等的辅助动作，串行即可，天然避免并发注销同一绑定；</li>
 *   <li><b>有界队列 + 拒绝即 fail-open</b>：队满返回 {@code false}，调用方转为收尾兜底（绝不阻塞、绝不抛）；</li>
 *   <li><b>任务异常隔离</b>：单个任务抛错只记 WARN，绝不影响执行器本身与后续任务；</li>
 *   <li><b>无对外配置</b>：容量/超时均为内部常量（不引入新的配置面）。</li>
 * </ul>
 *
 * <p>并发安全：{@link ThreadPoolExecutor} 自带同步；{@code rejectedCount} 为原子计数。
 */
public final class RouteRetireExecutor implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteRetireExecutor.class);

    /** 队列容量（内部常量：撤销是低频动作，32 足够；队满即 fail-open 转收尾兜底）。 */
    private static final int QUEUE_CAPACITY = 32;

    /** 优雅停机等待上界（毫秒）：内部常量（单条注销自身已有界，此处再兜一层）。 */
    private static final long CLOSE_WAIT_MS = 5_000L;

    private final ThreadPoolExecutor pool;
    private final AtomicLong rejectedCount = new AtomicLong(0);

    public RouteRetireExecutor(String contextTag) {
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "route-v2-retire-" + contextTag);
            t.setDaemon(true);
            return t;
        };
        this.pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 提交一次撤销任务。
     *
     * @return true=已提交；false=队列满/已关闭（调用方必须 fail-open：转收尾兜底并计数）
     */
    public boolean trySubmit(String taskName, Runnable task) {
        if (pool.isShutdown()) {
            rejectedCount.incrementAndGet();
            return false;
        }
        try {
            pool.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    // 撤销失败绝不影响执行器与后续撤销（fail-open）
                    LOGGER.warn("[RouteV2] retire task '{}' failed: {}", taskName, t.toString());
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            rejectedCount.incrementAndGet();
            LOGGER.warn("[RouteV2] retire queue full, rejecting task '{}' (caller must fail-open)", taskName);
            return false;
        }
    }

    /** 被拒绝（队列满/已关闭）的撤销任务数（可观测性）。 */
    public long rejectedCount() {
        return rejectedCount.get();
    }

    /** 当前排队/执行中的撤销任务数。 */
    public int activeCount() {
        return pool.getActiveCount() + pool.getQueue().size();
    }

    /**
     * 立即停机（不等待在途撤销）：**仅用于 context 已销毁的收尾路径**，避免在 Playwright 消息泵线程上
     * 长时间阻塞（context 销毁时驱动侧 handler 由 Playwright 一并释放，无需等待本池收尾）。
     */
    public void closeNoWait() {
        pool.shutdown();
    }

    /** 优雅停机：拒绝新任务 + 有界等待在途撤销完成（幂等）。 */
    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                pool.shutdownNow();
                LOGGER.warn("[RouteV2] retire executor did not terminate in {}ms, forcing shutdown",
                        CLOSE_WAIT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
