package com.hsbc.cmb.hk.dbb.automation.framework.common.async;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用异步任务池 — 全项目统一的异步执行入口 + 集中监控。
 *
 * <p>从路由域的 {@code RouteAsyncPool} 抽取并泛化，供任意模块复用：
 * 数据库刷入器、超时调度、事件回调、报告记录等"不阻塞调用方线程"的任务。
 *
 * <p>核心能力：
 * <ul>
 *   <li><b>立即执行</b>：{@link #run(Runnable)} / {@link #runWithTimeout(Runnable, long)}</li>
 *   <li><b>延迟 / 周期执行</b>：{@link #schedule(Runnable, long)} / {@link #scheduleWithFixedDelay(Runnable, long, long)}</li>
 *   <li><b>任务超时</b>：{@code runWithTimeout} 超时不响应中断的任务会被 {@code Future.cancel(true)} 中止</li>
 *   <li><b>队列限流</b>：{@code DiscardOldestPolicy} + 告警，保证调用方（含 Playwright 事件线程）永不阻塞</li>
 *   <li><b>阈值告警</b>：队列/线程使用率、待处理超时数超阈值时告警</li>
 *   <li><b>集中监控</b>：{@link #getStatusSnapshot()} 暴露活跃/队列/完成/超时/挂起等指标</li>
 *   <li><b>优雅关闭</b>：JVM 关闭钩子 + {@link #shutdown()} 统一关闭主池与调度器</li>
 * </ul>
 *
 * <p>环境变量（可选，前缀 {@code ASYNC_}）：
 * <pre>
 *   ASYNC_CORE_THREADS=2
 *   ASYNC_MAX_THREADS=6
 *   ASYNC_QUEUE_CAPACITY=200
 *   ASYNC_TASK_TIMEOUT_MS=30000
 *   ASYNC_MAX_PENDING_TIMEOUTS=500
 * </pre>
 *
 * <p>线程均为守护线程，JVM 退出不会因本池而挂起；必要时由 {@link #shutdown()} 或关闭钩子等待进行中任务完成。
 */
public final class AsyncPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncPool.class);

    private static final AtomicLong rejectedCount = new AtomicLong(0);
    private static final AtomicLong completedTaskCount = new AtomicLong(0);
    private static final AtomicLong timeoutCount = new AtomicLong(0);
    private static final AtomicLong pendingTimeoutCount = new AtomicLong(0);
    private static final AtomicLong pendingScheduleCount = new AtomicLong(0);

    /** 活跃的 per-Context 调度器（由 ContextRouteEngine 注册，关闭时移除） */
    private static final Map<String, ScheduledThreadPoolExecutor> CONTEXT_SCHEDULERS = new ConcurrentHashMap<>();

    private static final ThreadPoolExecutor POOL;
    private static final ScheduledThreadPoolExecutor SCHEDULER;

    /**
     * ⭐ 串行单线程执行器 — 专用于 Monitor 用户回调（onResponse）。
     * <p>Playwright route 拦截在事件线程触发，若直接在该线程执行用户回调，用户无法预期
     * "回调里修改的全局/共享状态（如 NLSUtils.setLanguage）对主线程不可见"（ThreadLocal 隔离）。
     * 统一桥接到本串行线程后，所有回调在<b>同一受管上下文线程</b>顺序执行，配合已全局化的
     * 框架状态（NLSUtils 等），用户业务代码无需理解线程模型即可"影响主线程"。
     */
    /**
     * ⭐ 修复 P1：Monitor 回调队列容量上限。
     * <p>原实现用 {@code Executors.newSingleThreadExecutor()}，其队列是
     * <b>无界</b> LinkedBlockingQueue：慢回调（如 DB 校验）持续积压会让队列无限增长直至 OOM；
     * 且无拒绝策略，积压只能靠消费者追上来消化。
     * <p>注意：<b>仍然保持单线程</b>。串行执行是本执行器的<b>语义契约</b>（见上：回调里修改的
     * 共享状态需在【同一受管线程】顺序生效），改成多线程会引入竞态并破坏用户可见性保证。
     * 因此这里只做「有界」，不做「并发」——慢回调的吞吐问题应通过把重活改投
     * {@link #run(Runnable)} 解决，而不是拆散本串行队列。
     */
    private static final int MONITOR_CALLBACK_QUEUE_CAPACITY = 10_000;

    private static final ExecutorService MONITOR_CALLBACK_EXECUTOR =
            new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(MONITOR_CALLBACK_QUEUE_CAPACITY),
                    r -> {
                        Thread t = new Thread(r, "monitor-callback");
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY - 1);
                        return t;
                    },
                    // 队列满：丢弃 + 告警，绝不反压提交方。
                    // 若用 CallerRunsPolicy，回调会在 Playwright 事件线程上执行，
                    // 把「回调慢」放大成「路由拦截阻塞 → 整轮测试卡死」，违背永不卡死原则。
                    (task, executor) -> LOGGER.warn(
                            "[AsyncPool] Monitor callback queue full (capacity={}), dropping task to avoid OOM. "
                                    + "Consider moving heavy work out of onResponse into AsyncPool.run().",
                            MONITOR_CALLBACK_QUEUE_CAPACITY));

    private static final int CORE_THREADS;
    private static final int MAX_THREADS;
    private static final int QUEUE_CAPACITY;
    private static final long DEFAULT_TASK_TIMEOUT_MS;
    private static final long KEEP_ALIVE_SECONDS = 30;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static final double QUEUE_USAGE_ALERT_THRESHOLD;
    private static final double THREAD_USAGE_ALERT_THRESHOLD;
    private static final int MAX_PENDING_TIMEOUTS;

    static {
        CORE_THREADS = getEnvInt("ASYNC_CORE_THREADS", 2);
        MAX_THREADS = getEnvInt("ASYNC_MAX_THREADS", 6);
        QUEUE_CAPACITY = getEnvInt("ASYNC_QUEUE_CAPACITY", 200);
        DEFAULT_TASK_TIMEOUT_MS = getEnvLong("ASYNC_TASK_TIMEOUT_MS", 30_000L);
        QUEUE_USAGE_ALERT_THRESHOLD = getEnvDouble("ASYNC_QUEUE_USAGE_ALERT_THRESHOLD", 0.8);
        THREAD_USAGE_ALERT_THRESHOLD = getEnvDouble("ASYNC_THREAD_USAGE_ALERT_THRESHOLD", 0.9);
        MAX_PENDING_TIMEOUTS = getEnvInt("ASYNC_MAX_PENDING_TIMEOUTS", 500);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CORE_THREADS, MAX_THREADS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "async-pool");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                (r, threadPoolExecutor) -> {
                    long count = rejectedCount.incrementAndGet();
                    LOGGER.error("[AsyncPool] TASK REJECTED — discarding oldest. Rejected: {}, Active: {}, "
                                    + "Pool: {}/{}, Queue: {}/{}",
                            count, threadPoolExecutor.getActiveCount(),
                            threadPoolExecutor.getPoolSize(), threadPoolExecutor.getMaximumPoolSize(),
                            threadPoolExecutor.getQueue().size(), QUEUE_CAPACITY);
                    new ThreadPoolExecutor.DiscardOldestPolicy().rejectedExecution(r, threadPoolExecutor);
                });
        executor.allowCoreThreadTimeOut(true);
        POOL = executor;

        SCHEDULER = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "async-sched");
            t.setDaemon(true);
            return t;
        });
        SCHEDULER.setRemoveOnCancelPolicy(true);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("[AsyncPool] JVM shutdown hook triggered.");
            shutdownGracefully();
        }, "async-shutdown"));

        LoggingConfigUtil.logInfoIfVerbose(LOGGER,
                "[AsyncPool] Initialized: core={}, max={}, queue={}, timeout={}ms, maxPendingTimeouts={}",
                CORE_THREADS, MAX_THREADS, QUEUE_CAPACITY, DEFAULT_TASK_TIMEOUT_MS, MAX_PENDING_TIMEOUTS);
    }

    private AsyncPool() {}

    // ─── 立即执行 ──────────────────────────────────────────────

    /** 异步执行任务（无超时）。task 为 null 静默跳过。 */
    public static void run(Runnable task) {
        submitTask(task, 0);
    }

    /** 异步执行任务，带超时（毫秒，≤0 表示无限制）。 */
    public static void runWithTimeout(Runnable task, long timeoutMs) {
        submitTask(task, timeoutMs > 0 ? timeoutMs : 0);
    }

    private static void submitTask(Runnable task, long timeoutMs) {
        if (task == null) return;
        checkThresholdsBeforeSubmit();
        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[AsyncPool] submit: timeout={}ms, queue={}/{}, active={}",
                timeoutMs, POOL.getQueue().size(), QUEUE_CAPACITY, POOL.getActiveCount());
        try {
            Future<?> future = POOL.submit(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    LOGGER.error("[AsyncPool] Task threw exception: {}", t.getMessage(), t);
                } finally {
                    completedTaskCount.incrementAndGet();
                }
            });
            if (timeoutMs > 0) {
                final Future<?> f = future;
                pendingTimeoutCount.incrementAndGet();
                SCHEDULER.schedule(() -> {
                    try {
                        f.get(0, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        boolean cancelled = f.cancel(true);
                        long count = timeoutCount.incrementAndGet();
                        LOGGER.error("[AsyncPool] TASK TIMEOUT after {}ms (total: {}). Cancelled: {}, Active: {}, Queue: {}/{}",
                                timeoutMs, count, cancelled, POOL.getActiveCount(), POOL.getQueue().size(), QUEUE_CAPACITY);
                        checkThresholdsAfterTimeout();
                    } catch (ExecutionException e) {
                        LOGGER.error("[AsyncPool] Task failed: {}", e.getMessage(), e.getCause());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        pendingTimeoutCount.decrementAndGet();
                    }
                }, timeoutMs, TimeUnit.MILLISECONDS);
            }
        } catch (RejectedExecutionException e) {
            LOGGER.error("[AsyncPool] Unexpected rejection: {}", e.getMessage());
            try {
                task.run();
            } catch (Exception ex) {
                LOGGER.error("[AsyncPool] Fallback execution failed", ex);
            }
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(LOGGER, "[AsyncPool] Submit failed: {}", e.getMessage());
        }
    }

    // ─── 延迟 / 周期执行 ────────────────────────────────────────

    /** 延迟 delayMs 毫秒后执行（单次）。 */
    public static ScheduledFuture<?> schedule(Runnable task, long delayMs) {
        if (task == null) return null;
        long pending = pendingScheduleCount.incrementAndGet();
        ScheduledFuture<?> f = SCHEDULER.schedule(() -> {
            try {
                task.run();
            } finally {
                pendingScheduleCount.decrementAndGet();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        return f;
    }

    /** 固定延迟周期执行（initialDelay 后首次，之后每 delayMs 一次）。 */
    public static ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initialDelayMs, long delayMs) {
        if (task == null) return null;
        return SCHEDULER.scheduleWithFixedDelay(task, initialDelayMs, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 为某个 BrowserContext 创建独立的延迟调度器，复用本池的线程工厂规范
     * （守护线程、{@code async-ctx-<id>} 命名、{@code removeOnCancel}），但拥有
     * 独立生命周期——由调用方（ContextRouteEngine）在 context 关闭时 shutdown。
     *
     * <p>注册到 {@link #CONTEXT_SCHEDULERS} 以便集中观测活跃数；关闭时必须调用
     * {@link #removeContextScheduler(String)} 解注册。
     *
     * @param contextId 上下文标识（如 BrowserContext 的 identity hash）
     * @param coreThreads 核心线程数
     * @return 该 context 专属的调度器
     */
    public static ScheduledThreadPoolExecutor newContextScheduler(String contextId, int coreThreads) {
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(
                Math.max(1, coreThreads),
                r -> {
                    Thread t = new Thread(r, "async-ctx-" + contextId);
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                });
        ex.setRemoveOnCancelPolicy(true);
        CONTEXT_SCHEDULERS.put(contextId, ex);
        LoggingConfigUtil.logInfoIfVerbose(LOGGER, "[AsyncPool] Created context scheduler '{}' (active ctx schedulers: {})",
                contextId, CONTEXT_SCHEDULERS.size());
        return ex;
    }

    /** 解除某个 context 调度器的注册（应在其 shutdown 后调用）。 */
    public static void removeContextScheduler(String contextId) {
        ScheduledThreadPoolExecutor ex = CONTEXT_SCHEDULERS.remove(contextId);
        if (ex != null) {
            LoggingConfigUtil.logInfoIfVerbose(LOGGER, "[AsyncPool] Removed context scheduler '{}' (active ctx schedulers: {})",
                    contextId, CONTEXT_SCHEDULERS.size());
        }
    }

    /** 当前活跃的 per-Context 调度器数量。 */
    public static int getActiveContextSchedulerCount() {
        return CONTEXT_SCHEDULERS.size();
    }

    // ─── 阈值告警 ──────────────────────────────────────────────

    private static void checkThresholdsBeforeSubmit() {
        int queueSize = POOL.getQueue().size();
        int activeCount = POOL.getActiveCount();
        int poolSize = POOL.getPoolSize();
        double queueUsage = (double) queueSize / QUEUE_CAPACITY;
        double threadUsage = (double) activeCount / Math.max(poolSize, 1);

        if (queueUsage >= QUEUE_USAGE_ALERT_THRESHOLD) {
            LOGGER.error("[AsyncPool] ALERT: Queue usage {} exceeds threshold {}. Queue: {}/{}, Active: {}, Pool: {}/{}",
                    String.format("%.1f%%", queueUsage * 100),
                    String.format("%.1f%%", QUEUE_USAGE_ALERT_THRESHOLD * 100),
                    queueSize, QUEUE_CAPACITY, activeCount, poolSize, MAX_THREADS);
        } else if (queueUsage >= QUEUE_USAGE_ALERT_THRESHOLD * 0.7) {
            LOGGER.warn("[AsyncPool] WARNING: Queue usage {} approaching threshold. Queue: {}/{}, Active: {}",
                    String.format("%.1f%%", queueUsage * 100), queueSize, QUEUE_CAPACITY, activeCount);
        }
        if (threadUsage >= THREAD_USAGE_ALERT_THRESHOLD) {
            LOGGER.error("[AsyncPool] ALERT: Thread usage {} exceeds threshold {}. Active: {}, Pool: {}/{}",
                    String.format("%.1f%%", threadUsage * 100),
                    String.format("%.1f%%", THREAD_USAGE_ALERT_THRESHOLD * 100),
                    activeCount, poolSize, MAX_THREADS);
        }
        long pending = pendingTimeoutCount.get();
        if (pending >= MAX_PENDING_TIMEOUTS) {
            LOGGER.error("[AsyncPool] ALERT: Pending timeouts ({}) exceeded max ({}). Completed: {}, Timeouts: {}",
                    pending, MAX_PENDING_TIMEOUTS, completedTaskCount.get(), timeoutCount.get());
        } else if (pending >= MAX_PENDING_TIMEOUTS * 0.7) {
            LOGGER.warn("[AsyncPool] WARNING: Pending timeouts ({}) approaching max ({}). Completed: {}, Timeouts: {}",
                    pending, MAX_PENDING_TIMEOUTS, completedTaskCount.get(), timeoutCount.get());
        }
    }

    private static void checkThresholdsAfterTimeout() {
        if (POOL.getQueue().size() > QUEUE_CAPACITY * 0.5) {
            LOGGER.warn("[AsyncPool] After timeout — Queue still has {} pending. Consider raising ASYNC_QUEUE_CAPACITY/ASYNC_MAX_THREADS.",
                    POOL.getQueue().size());
        }
    }

    // ─── 优雅关闭 ──────────────────────────────────────────────

    private static void shutdownGracefully() {
        if (POOL.isShutdown()) return;
        LOGGER.info("[AsyncPool] Shutting down (active: {}, queue: {}, completed: {}, timeouts: {}, pendingTimeouts: {}, pendingSched: {})...",
                POOL.getActiveCount(), POOL.getQueue().size(), completedTaskCount.get(),
                timeoutCount.get(), pendingTimeoutCount.get(), pendingScheduleCount.get());
        POOL.shutdown();
        SCHEDULER.shutdown();
        // 关键修复 P3-23：遍历关闭每个 per-context Scheduler，避免线程池残留
        int ctxSchedulers = CONTEXT_SCHEDULERS.size();
        if (ctxSchedulers > 0) {
            LOGGER.info("[AsyncPool] Shutting down {} per-context scheduler(s)", ctxSchedulers);
            for (ScheduledExecutorService s : CONTEXT_SCHEDULERS.values()) {
                try {
                    s.shutdown();
                } catch (Exception ignore) { /* 单个池失败不影响其他池关闭 */ }
            }
        }
        try {
            if (!POOL.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("[AsyncPool] Timeout {}s, forcing shutdownNow. Remaining: {}, Queue: {}",
                        SHUTDOWN_TIMEOUT_SECONDS, POOL.getActiveCount(), POOL.getQueue().size());
                POOL.shutdownNow();
            }
            SCHEDULER.awaitTermination(5, TimeUnit.SECONDS);
            // ⭐ 关闭 Monitor 回调串行执行器
            MONITOR_CALLBACK_EXECUTOR.shutdown();
            try {
                if (!MONITOR_CALLBACK_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                    MONITOR_CALLBACK_EXECUTOR.shutdownNow();
                }
            } catch (InterruptedException ie) {
                MONITOR_CALLBACK_EXECUTOR.shutdownNow();
                Thread.currentThread().interrupt();
            }
            // per-context 池的终结等待放在 SCHEDULER 之后
            if (ctxSchedulers > 0) {
                for (ScheduledExecutorService s : CONTEXT_SCHEDULERS.values()) {
                    if (!s.isTerminated()) {
                        s.shutdownNow();
                    }
                }
            }
        } catch (InterruptedException e) {
            LOGGER.warn("[AsyncPool] Interrupted during shutdown, forcing shutdownNow");
            POOL.shutdownNow();
            SCHEDULER.shutdownNow();
            MONITOR_CALLBACK_EXECUTOR.shutdownNow();
            for (ScheduledExecutorService s : CONTEXT_SCHEDULERS.values()) {
                s.shutdownNow();
            }
            Thread.currentThread().interrupt();
        }
        LOGGER.info("[AsyncPool] Shutdown complete. Completed: {}, timeouts: {}", completedTaskCount.get(), timeoutCount.get());
    }

    // ─── 监控指标 ──────────────────────────────────────────────

    public static int getActiveCount() { return POOL.getActiveCount(); }
    public static int getPoolSize() { return POOL.getPoolSize(); }
    public static int getQueueSize() { return POOL.getQueue().size(); }
    public static long getCompletedTaskCount() { return POOL.getCompletedTaskCount() + completedTaskCount.get(); }
    public static long getRejectedCount() { return rejectedCount.get(); }
    public static long getTimeoutCount() { return timeoutCount.get(); }
    public static long getPendingTimeoutCount() { return pendingTimeoutCount.get(); }
    public static long getPendingScheduleCount() { return pendingScheduleCount.get(); }

    public static double getQueueUsage() { return (double) POOL.getQueue().size() / QUEUE_CAPACITY; }
    public static double getThreadUsage() {
        int poolSize = POOL.getPoolSize();
        return poolSize > 0 ? (double) POOL.getActiveCount() / poolSize : 0.0;
    }

    public static String getStatusSnapshot() {
        return String.format(
                "[AsyncPool] active=%d, pool=%d/%d, queue=%d/%d (%.0f%%), threads=%.0f%%, "
                        + "completed=%d, rejected=%d, timeouts=%d, pendingTimeouts=%d/%d, pendingSched=%d, ctxSched=%d",
                POOL.getActiveCount(), POOL.getPoolSize(), POOL.getMaximumPoolSize(),
                POOL.getQueue().size(), QUEUE_CAPACITY, getQueueUsage() * 100, getThreadUsage() * 100,
                POOL.getCompletedTaskCount() + completedTaskCount.get(), rejectedCount.get(),
                timeoutCount.get(), pendingTimeoutCount.get(), MAX_PENDING_TIMEOUTS, pendingScheduleCount.get(),
                CONTEXT_SCHEDULERS.size());
    }

    /** 手动关闭（由管理代码调用）。 */
    public static void shutdown() { shutdownGracefully(); }

    /**
     * ⭐ 在 Monitor 回调专用串行线程上执行任务（顺序、与主流程共享上下文）。
     * 用于 onResponse 回调，使用户在回调中修改的全局/共享状态对主线程可见。
     * task 为 null 静默跳过。
     */
    public static void runOnMonitorCallbackThread(Runnable task) {
        if (task == null) return;
        try {
            MONITOR_CALLBACK_EXECUTOR.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    LOGGER.error("[AsyncPool] Monitor callback task threw exception: {}", t.getMessage(), t);
                } finally {
                    completedTaskCount.incrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            LOGGER.error("[AsyncPool] Monitor callback executor rejected: {}", e.getMessage());
            try {
                task.run();
            } catch (Exception ex) {
                LOGGER.error("[AsyncPool] Monitor callback fallback failed", ex);
            }
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(LOGGER, "[AsyncPool] Monitor callback submit failed: {}", e.getMessage());
        }
    }

    // ─── 内部工具 ──────────────────────────────────────────────

    private static int getEnvInt(String key, int defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.trim().isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("[AsyncPool] Invalid int for {}: '{}', default {}", key, val, defaultValue);
            return defaultValue;
        }
    }

    private static long getEnvLong(String key, long defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.trim().isEmpty()) return defaultValue;
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("[AsyncPool] Invalid long for {}: '{}', default {}", key, val, defaultValue);
            return defaultValue;
        }
    }

    private static double getEnvDouble(String key, double defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.trim().isEmpty()) return defaultValue;
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("[AsyncPool] Invalid double for {}: '{}', default {}", key, val, defaultValue);
            return defaultValue;
        }
    }
}
