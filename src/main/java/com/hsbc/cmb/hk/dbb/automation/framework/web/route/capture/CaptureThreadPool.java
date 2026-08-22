package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 采集管道专用线程池 — 与业务线程池完全隔离，JMX 可观测。
 *
 * <p>三个线程池各司其职：
 * <ul>
 *   <li><b>mergerPool</b> — 1 线程，{@link EventMerger} 消费 RingBuffer 并合并事件。</li>
 *   <li><b>bodyFetchPool</b> — 2-4 线程，惰性读取响应体。</li>
 *   <li><b>cleanupPool</b> — 1 线程，超时清理 stale MergingSlot。</li>
 * </ul>
 */
public class CaptureThreadPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureThreadPool.class);

    /** 线程名前缀，便于故障排查 */
    private static final String THREAD_PREFIX = "capture";
    private static final AtomicInteger THREAD_ID = new AtomicInteger();

    private final ThreadPoolExecutor mergerPool;
    private final ThreadPoolExecutor bodyFetchPool;
    private final ScheduledExecutorService cleanupPool;

    /**
     * @param mergerThreads   merger 线程数（推荐 1）
     * @param bodyFetchThreads body fetch 线程数（推荐 2-4）
     */
    public CaptureThreadPool(int mergerThreads, int bodyFetchThreads) {
        this.mergerPool = createPool(mergerThreads, "merger");
        this.bodyFetchPool = createPool(bodyFetchThreads, "body-fetch");
        this.cleanupPool = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, THREAD_PREFIX + "-cleanup-" + THREAD_ID.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public CaptureThreadPool() {
        this(1, 2);
    }

    // ── 提交任务 ──

    /** 提交 merger 任务（EventMerger 主循环），返回 Future 便于关闭时等待 */
    public Future<?> submitMerger(Runnable task) {
        return mergerPool.submit(task);
    }

    /** 提交 body fetch 任务 */
    public CompletableFuture<Void> submitBodyFetch(Runnable task) {
        return CompletableFuture.runAsync(task, bodyFetchPool);
    }

    /** 提交定时清理任务 */
    public ScheduledFuture<?> scheduleCleanup(Runnable task, long intervalMs) {
        return cleanupPool.scheduleAtFixedRate(task, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    // ── 指标 ──

    public int mergerActiveCount() { return mergerPool.getActiveCount(); }
    public int mergerQueueSize() { return mergerPool.getQueue().size(); }
    public long mergerCompletedCount() { return mergerPool.getCompletedTaskCount(); }

    public int bodyFetchActiveCount() { return bodyFetchPool.getActiveCount(); }
    public int bodyFetchQueueSize() { return bodyFetchPool.getQueue().size(); }
    public long bodyFetchCompletedCount() { return bodyFetchPool.getCompletedTaskCount(); }

    // ── 生命周期 ──

    /** 优雅关闭：先停止接受新任务，等待已有任务完成，超时强制终止 */
    public void shutdown(long timeoutMs) {
        LOGGER.info("[CaptureThreadPool] Shutting down (timeout={}ms)...", timeoutMs);

        cleanupPool.shutdownNow();

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        shutdownPool(mergerPool, "merger", remainingMillis(deadline));
        shutdownPool(bodyFetchPool, "body-fetch", remainingMillis(deadline));

        LOGGER.info("[CaptureThreadPool] Shutdown complete");
    }

    private static long remainingMillis(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private void shutdownPool(ThreadPoolExecutor pool, String name, long timeoutMs) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                LOGGER.warn("[CaptureThreadPool] {} pool did not terminate in {}ms, forcing shutdown",
                        name, timeoutMs);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOGGER.warn("[CaptureThreadPool] Interrupted while shutting down {} pool", name);
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── 内部 ──

    private static ThreadPoolExecutor createPool(int nThreads, String name) {
        return new ThreadPoolExecutor(
                nThreads, nThreads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(2048),
                r -> {
                    Thread t = new Thread(r, THREAD_PREFIX + "-" + name + "-" + THREAD_ID.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                (r, executor) -> LOGGER.warn("[CaptureThreadPool] {} pool rejected task: {}", name, r)
        );
    }
}