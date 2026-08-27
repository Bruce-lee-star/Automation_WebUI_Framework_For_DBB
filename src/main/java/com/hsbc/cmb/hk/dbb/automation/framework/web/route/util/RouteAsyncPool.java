package com.hsbc.cmb.hk.dbb.automation.framework.web.route.util;

import com.hsbc.cmb.hk.dbb.automation.framework.common.async.AsyncPool;

/**
 * 路由域异步任务门面 — 委托 {@link AsyncPool} 通用异步池。
 *
 * <p>保留原 {@code RouteAsyncPool} 的全部静态 API（{@code run} / {@code runWithTimeout} /
 * 监控指标 / {@code shutdown}），调用方（如 {@code MonitorResultRecorder}）无需改动。
 * 实际执行、超时、队列限流、阈值告警、优雅关闭均由 {@link AsyncPool} 统一承担，
 * 实现"全项目单一异步入口 + 集中监控"。
 *
 * <p>如需路由域专属配置（线程名、容量阈值），由 {@code AsyncPool} 的 {@code ASYNC_*} 环境变量统一控制。
 */
public final class RouteAsyncPool {

    private RouteAsyncPool() {}

    /** 异步执行任务（无超时）。 */
    public static void run(Runnable task) {
        AsyncPool.run(task);
    }

    /** 异步执行任务，带超时（毫秒，≤0 表示无限制）。 */
    public static void runWithTimeout(Runnable task, long timeoutMs) {
        AsyncPool.runWithTimeout(task, timeoutMs);
    }

    // ─── 监控指标（透传 AsyncPool）────────────────────────────

    public static int getActiveCount() { return AsyncPool.getActiveCount(); }
    public static int getPoolSize() { return AsyncPool.getPoolSize(); }
    public static int getQueueSize() { return AsyncPool.getQueueSize(); }
    public static long getCompletedTaskCount() { return AsyncPool.getCompletedTaskCount(); }
    public static long getRejectedCount() { return AsyncPool.getRejectedCount(); }
    public static long getTimeoutCount() { return AsyncPool.getTimeoutCount(); }
    public static long getPendingTimeoutCount() { return AsyncPool.getPendingTimeoutCount(); }
    public static double getQueueUsage() { return AsyncPool.getQueueUsage(); }
    public static double getThreadUsage() { return AsyncPool.getThreadUsage(); }

    /** 导出线程池状态快照。 */
    public static String getStatusSnapshot() { return AsyncPool.getStatusSnapshot(); }

    /** 手动关闭。 */
    public static void shutdown() { AsyncPool.shutdown(); }
}
