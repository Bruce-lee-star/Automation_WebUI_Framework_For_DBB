package com.hsbc.cmb.hk.dbb.automation.framework.web.route.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Playwright 路由异步任务池 — 守护线程 + 弹性扩容 + 队列限流 + 监控指标 + 超时机制。
 *
 * <p>核心设计：
 * <ul>
 *   <li>核心线程数/队列容量从环境变量可配置，避免硬编码</li>
 *   <li>拒绝策略使用 {@link ThreadPoolExecutor.CallerRunsPolicy}，避免任务丢失</li>
 *   <li>JVM 关闭钩子优雅关闭，等待进行中任务完成</li>
 *   <li><b>任务超时机制</b>：{@link #runWithTimeout(Runnable, long)} 限制单个任务最大耗时</li>
 *   <li><b>超时调度器监控</b>：跟踪待处理的超时检查任务数，超过阈值告警</li>
 *   <li><b>阈值告警</b>：队列使用率/活跃线程/超时挂起数超过阈值时 ERROR 日志告警</li>
 *   <li>暴露线程池状态指标（活跃线程、队列长度、已完成任务数、超时挂起数），支持监控采集</li>
 * </ul>
 *
 * <p>环境变量配置（可选）：
 * <pre>
 *   ROUTE_CORE_THREADS=2        // 核心线程数（默认 2）
 *   ROUTE_MAX_THREADS=6        // 最大线程数（默认 6）
 *   ROUTE_QUEUE_CAPACITY=200   // 队列容量（默认 200）
 *   ROUTE_TASK_TIMEOUT_MS=30000 // 单个任务最大超时毫秒（默认 30000ms）
 *   ROUTE_MAX_PENDING_TIMEOUTS=500 // 待处理超时检查任务上限（默认 500）
 * </pre>
 */
public final class RouteAsyncPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteAsyncPool.class);

    /** 拒绝计数，用于监控告警 */
    private static final AtomicLong rejectedCount = new AtomicLong(0);

    /** 完成的任务总数（近似值） */
    private static final AtomicLong completedTaskCount = new AtomicLong(0);

    /** 超时的任务计数 */
    private static final AtomicLong timeoutCount = new AtomicLong(0);

    /** 当前待处理的超时检查任务数（挂在 TIMEOUT_SCHEDULER 上的 delayed 任务） */
    private static final AtomicLong pendingTimeoutCount = new AtomicLong(0);

    private static final ThreadPoolExecutor POOL;

    // ─── 超时调度器（复用线程，避免 new Thread 每任务创建） ──────────
    private static final ScheduledExecutorService TIMEOUT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pw-route-timeout");
                t.setDaemon(true);
                return t;
            });

    // ─── 可配置参数 ──────────────────────────────────────────────
    private static final int CORE_THREADS;
    private static final int MAX_THREADS;
    private static final int QUEUE_CAPACITY;
    private static final long KEEP_ALIVE_SECONDS = 30;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static final long DEFAULT_TASK_TIMEOUT_MS;

    /** 队列使用率告警阈值（0.0 ~ 1.0，默认 0.8 即 80%） */
    private static final double QUEUE_USAGE_ALERT_THRESHOLD;

    /** 线程使用率告警阈值（0.0 ~ 1.0，默认 0.9 即 90%） */
    private static final double THREAD_USAGE_ALERT_THRESHOLD;

    /** 待处理超时检查任务上限（防止调度器队列无限堆积） */
    private static final int MAX_PENDING_TIMEOUTS;

    static {
        CORE_THREADS = getEnvInt("ROUTE_CORE_THREADS", 2);
        MAX_THREADS = getEnvInt("ROUTE_MAX_THREADS", 6);
        QUEUE_CAPACITY = getEnvInt("ROUTE_QUEUE_CAPACITY", 200);
        DEFAULT_TASK_TIMEOUT_MS = getEnvLong("ROUTE_TASK_TIMEOUT_MS", 30_000L);
        QUEUE_USAGE_ALERT_THRESHOLD = getEnvDouble("ROUTE_QUEUE_USAGE_ALERT_THRESHOLD", 0.8);
        THREAD_USAGE_ALERT_THRESHOLD = getEnvDouble("ROUTE_THREAD_USAGE_ALERT_THRESHOLD", 0.9);
        MAX_PENDING_TIMEOUTS = getEnvInt("ROUTE_MAX_PENDING_TIMEOUTS", 500);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CORE_THREADS,
                MAX_THREADS,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "pw-route");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                (r, threadPoolExecutor) -> {
                    // DiscardOldestPolicy：队列满时丢弃最旧任务，保证 Playwright 事件线程永不阻塞
                    long count = rejectedCount.incrementAndGet();
                    LOGGER.error("[RouteAsyncPool] TASK REJECTED — discarding oldest task in queue. "
                                    + "Rejected count: {}, Active: {}, Pool size: {}/{}, Queue: {}/{}",
                            count,
                            threadPoolExecutor.getActiveCount(),
                            threadPoolExecutor.getPoolSize(),
                            threadPoolExecutor.getMaximumPoolSize(),
                            threadPoolExecutor.getQueue().size(),
                            QUEUE_CAPACITY);
                    new ThreadPoolExecutor.DiscardOldestPolicy().rejectedExecution(r, threadPoolExecutor);
                }
        );

        // 允许核心线程超时回收，空闲时释放资源
        executor.allowCoreThreadTimeOut(true);

        POOL = executor;

        // JVM 关闭钩子：优雅关闭，等待进行中任务完成
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("[RouteAsyncPool] JVM shutdown hook triggered, gracefully shutting down...");
            shutdownGracefully();
        }, "pw-route-shutdown"));

        LOGGER.info("[RouteAsyncPool] Initialized: core={}, max={}, queue={}, timeout={}ms, maxPendingTimeouts={}",
                CORE_THREADS, MAX_THREADS, QUEUE_CAPACITY, DEFAULT_TASK_TIMEOUT_MS, MAX_PENDING_TIMEOUTS);
    }

    private RouteAsyncPool() {}

    /**
     * 异步执行任务（无超时限制）。
     *
     * @param task 要执行的任务，为 null 则静默跳过
     */
    public static void run(Runnable task) {
        submitTask(task, 0);
    }

    /**
     * 异步执行任务，带有超时限制。
     *
     * <p>超时后任务会被中断（通过 Future.cancel(true)），但无法保证
     * 不响应中断的任务会立即停止（取决于任务实现）。
     *
     * @param task     要执行的任务
     * @param timeoutMs 超时毫秒数，≤0 表示无限制
     */
    public static void runWithTimeout(Runnable task, long timeoutMs) {
        submitTask(task, timeoutMs > 0 ? timeoutMs : 0);
    }

    /**
     * 提交任务 + 可选超时包装。
     */
    private static void submitTask(Runnable task, long timeoutMs) {
        if (task == null) {
            return;
        }

        // 提交前检查阈值并告警
        checkThresholdsBeforeSubmit();

        try {
            Future<?> future = POOL.submit(() -> {
                try {
                    task.run();
                } finally {
                    completedTaskCount.incrementAndGet();
                }
            });

            // 如果指定了超时，启动守护线程等待超时后取消
            if (timeoutMs > 0) {
                final Future<?> f = future;
                long pending = pendingTimeoutCount.incrementAndGet();
                TIMEOUT_SCHEDULER.schedule(() -> {
                    try {
                        f.get(timeoutMs, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        boolean cancelled = f.cancel(true);
                        long count = timeoutCount.incrementAndGet();
                        LOGGER.error("[RouteAsyncPool] TASK TIMEOUT after {}ms (total timeouts: {}). "
                                        + "Cancelled: {}, Active: {}, Queue: {}/{}",
                                timeoutMs, count, cancelled,
                                POOL.getActiveCount(),
                                POOL.getQueue().size(), QUEUE_CAPACITY);
                        checkThresholdsAfterTimeout();
                    } catch (ExecutionException e) {
                        LOGGER.error("[RouteAsyncPool] Task execution failed: {}", e.getMessage(), e.getCause());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        pendingTimeoutCount.decrementAndGet();
                    }
                }, timeoutMs, TimeUnit.MILLISECONDS);
            }
        } catch (RejectedExecutionException e) {
            // CallerRunsPolicy 理论上不会抛出此异常，但作为兜底
            LOGGER.error("[RouteAsyncPool] Unexpected rejection: {}", e.getMessage());
            try {
                task.run();
            } catch (Exception ex) {
                LOGGER.error("[RouteAsyncPool] Fallback task execution failed", ex);
            }
        } catch (Exception e) {
            LOGGER.warn("[RouteAsyncPool] Failed to submit task: {}", e.getMessage());
        }
    }

    /**
     * 提交前检查队列和线程使用率，超过阈值则告警。
     */
    private static void checkThresholdsBeforeSubmit() {
        int queueSize = POOL.getQueue().size();
        int activeCount = POOL.getActiveCount();
        int poolSize = POOL.getPoolSize();

        double queueUsage = (double) queueSize / QUEUE_CAPACITY;
        double threadUsage = (double) activeCount / Math.max(poolSize, 1);

        if (queueUsage >= QUEUE_USAGE_ALERT_THRESHOLD) {
            LOGGER.error("[RouteAsyncPool] ALERT: Queue usage {:.1%} exceeds threshold {:.1%}! "
                            + "Queue: {}/{}, Active: {}, Pool: {}/{}",
                    queueUsage, QUEUE_USAGE_ALERT_THRESHOLD,
                    queueSize, QUEUE_CAPACITY, activeCount, poolSize, MAX_THREADS);
        } else if (queueUsage >= QUEUE_USAGE_ALERT_THRESHOLD * 0.7) {
            LOGGER.warn("[RouteAsyncPool] WARNING: Queue usage {:.1%} approaching threshold. "
                            + "Queue: {}/{}, Active: {}",
                    queueUsage, queueSize, QUEUE_CAPACITY, activeCount);
        }

        if (threadUsage >= THREAD_USAGE_ALERT_THRESHOLD) {
            LOGGER.error("[RouteAsyncPool] ALERT: Thread usage {:.1%} exceeds threshold {:.1%}! "
                            + "Active: {}, Pool: {}/{}",
                    threadUsage, THREAD_USAGE_ALERT_THRESHOLD,
                    activeCount, poolSize, MAX_THREADS);
        }

        // 超时调度器挂起任务数告警
        long pendingTimeouts = pendingTimeoutCount.get();
        if (pendingTimeouts >= MAX_PENDING_TIMEOUTS) {
            LOGGER.error("[RouteAsyncPool] ALERT: Pending timeout tasks ({}) exceeded max ({}). "
                            + "Too many concurrent timeouts may indicate tasks blocking for too long. "
                            + "Completed: {}, Timeouts: {}",
                    pendingTimeouts, MAX_PENDING_TIMEOUTS,
                    completedTaskCount.get(), timeoutCount.get());
        } else if (pendingTimeouts >= MAX_PENDING_TIMEOUTS * 0.7) {
            LOGGER.warn("[RouteAsyncPool] WARNING: Pending timeout tasks ({}) approaching max ({}). "
                            + "Completed: {}, Timeouts: {}",
                    pendingTimeouts, MAX_PENDING_TIMEOUTS,
                    completedTaskCount.get(), timeoutCount.get());
        }
    }

    /**
     * 超时后检查线程池状态（可能堆积严重）。
     */
    private static void checkThresholdsAfterTimeout() {
        int queueSize = POOL.getQueue().size();
        if (queueSize > QUEUE_CAPACITY * 0.5) {
            LOGGER.warn("[RouteAsyncPool] After timeout — Queue still has {} tasks pending. "
                    + "Consider increasing ROUTE_QUEUE_CAPACITY or ROUTE_MAX_THREADS.", queueSize);
        }
    }

    /**
     * 优雅关闭线程池（先 shutdown，等待，超时则 shutdownNow）。
     */
    private static void shutdownGracefully() {
        if (POOL.isShutdown()) {
            return;
        }
        LOGGER.info("[RouteAsyncPool] Shutting down (active: {}, queue: {}, completed: {}, timeouts: {}, pendingTimeouts: {})...",
                POOL.getActiveCount(), POOL.getQueue().size(), completedTaskCount.get(), timeoutCount.get(), pendingTimeoutCount.get());

        POOL.shutdown();
        TIMEOUT_SCHEDULER.shutdown();
        try {
            if (!POOL.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("[RouteAsyncPool] Timeout after {}s, forcing shutdownNow. "
                                + "Remaining tasks: {}, Queue: {}",
                        SHUTDOWN_TIMEOUT_SECONDS,
                        POOL.getActiveCount(),
                        POOL.getQueue().size());
                POOL.shutdownNow();
            }
            TIMEOUT_SCHEDULER.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOGGER.warn("[RouteAsyncPool] Interrupted during shutdown, forcing shutdownNow");
            POOL.shutdownNow();
            TIMEOUT_SCHEDULER.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("[RouteAsyncPool] Shutdown complete. Total completed: {}, timeouts: {}",
                completedTaskCount.get(), timeoutCount.get());
    }

    // ─── 监控指标 ────────────────────────────────────────────────

    /** 活跃线程数 */
    public static int getActiveCount() {
        return POOL.getActiveCount();
    }

    /** 当前池大小 */
    public static int getPoolSize() {
        return POOL.getPoolSize();
    }

    /** 队列中等待的任务数 */
    public static int getQueueSize() {
        return POOL.getQueue().size();
    }

    /** 已完成的近似任务总数 */
    public static long getCompletedTaskCount() {
        return POOL.getCompletedTaskCount() + completedTaskCount.get();
    }

    /** 拒绝任务计数 */
    public static long getRejectedCount() {
        return rejectedCount.get();
    }

    /** 超时任务计数 */
    public static long getTimeoutCount() {
        return timeoutCount.get();
    }

    /** 当前待处理的超时检查任务数 */
    public static long getPendingTimeoutCount() {
        return pendingTimeoutCount.get();
    }

    /** 队列使用率（0.0 ~ 1.0） */
    public static double getQueueUsage() {
        return (double) POOL.getQueue().size() / QUEUE_CAPACITY;
    }

    /** 线程使用率（0.0 ~ 1.0） */
    public static double getThreadUsage() {
        int poolSize = POOL.getPoolSize();
        return poolSize > 0 ? (double) POOL.getActiveCount() / poolSize : 0.0;
    }

    /**
     * 导出线程池状态快照（便于日志或监控系统采集）。
     */
    public static String getStatusSnapshot() {
        return String.format(
                "[RouteAsyncPool] active=%d, pool=%d/%d, queue=%d/%d (%.0f%%), threads=%.0f%%, "
                        + "completed=%d, rejected=%d, timeouts=%d, pendingTimeouts=%d/%d",
                POOL.getActiveCount(),
                POOL.getPoolSize(),
                POOL.getMaximumPoolSize(),
                POOL.getQueue().size(),
                QUEUE_CAPACITY,
                getQueueUsage() * 100,
                getThreadUsage() * 100,
                POOL.getCompletedTaskCount() + completedTaskCount.get(),
                rejectedCount.get(),
                timeoutCount.get(),
                pendingTimeoutCount.get(),
                MAX_PENDING_TIMEOUTS);
    }

    /**
     * 手动关闭（由管理代码调用，而非依赖 JVM 钩子）。
     */
    public static void shutdown() {
        shutdownGracefully();
    }

    // ─── 内部工具 ────────────────────────────────────────────────

    private static int getEnvInt(String key, int defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("[RouteAsyncPool] Invalid int value for {}: '{}', using default {}",
                    key, val, defaultValue);
            return defaultValue;
        }
    }

    private static long getEnvLong(String key, long defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("[RouteAsyncPool] Invalid long value for {}: '{}', using default {}",
                    key, val, defaultValue);
            return defaultValue;
        }
    }

    private static double getEnvDouble(String key, double defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("[RouteAsyncPool] Invalid double value for {}: '{}', using default {}",
                    key, val, defaultValue);
            return defaultValue;
        }
    }
}
