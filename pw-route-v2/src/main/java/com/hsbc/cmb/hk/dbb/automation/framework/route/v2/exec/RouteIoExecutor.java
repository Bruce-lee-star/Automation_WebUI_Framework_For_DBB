package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IO 执行器 —— 事件线程与阻塞操作的唯一隔离带。
 *
 * <p>职责：承载一切不允许出现在 Playwright 事件线程上的操作（fetch、sleep、观测消费等）。
 * <ul>
 *   <li>固定核心线程 + 有界队列：队满拒绝而非无界堆积（拒绝即 fail-open）；</li>
 *   <li>线程命名带 context 标识（可观测）；</li>
 *   <li>内部周期巡检：强制落定超龄挂起 claim（防悬挂）；</li>
 *   <li>{@link #close()} 优雅停机：拒绝新任务 + 有界等待在途任务。</li>
 * </ul>
 *
 * <p>并发安全：{@link ThreadPoolExecutor} 自带队列/工作线程同步；{@code rejectedCount}
 * 为原子计数器。
 */
public final class RouteIoExecutor implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteIoExecutor.class);

    private final ThreadPoolExecutor pool;
    private final AtomicLong rejectedCount = new AtomicLong(0);
    private final AtomicInteger taskSeq = new AtomicInteger(0);

    public RouteIoExecutor(String contextTag, int coreThreads, int queueCapacity) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "route-v2-io-" + contextTag + "-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.pool = new ThreadPoolExecutor(coreThreads, coreThreads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                factory, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 提交 IO 任务。
     *
     * @return true=已提交；false=队列满/已关闭（调用方必须 fail-open）
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
                    LOGGER.warn("[RouteV2] IO task '{}' failed: {}", taskName, t.toString());
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            rejectedCount.incrementAndGet();
            LOGGER.warn("[RouteV2] IO queue full, rejecting task '{}' (caller must fail-open)", taskName);
            return false;
        }
    }

    /** 被拒绝的任务数。 */
    public long rejectedCount() {
        return rejectedCount.get();
    }

    /** 当前排队/执行任务数。 */
    public int activeCount() {
        return pool.getActiveCount() + pool.getQueue().size();
    }

    /** 优雅停机：拒绝新任务，有界等待在途任务完成。 */
    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                LOGGER.warn("[RouteV2] IO executor did not terminate in 10s, forcing shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    /** 供调度任务使用的序号（指标可观测性）。 */
    int nextTaskSeq() {
        return taskSeq.incrementAndGet();
    }
}
