package com.hsbc.cmb.hk.dbb.automation.framework.route;

/**
 * Route V2 运行时配置（不可变，发布后不修改）。
 *
 * <p>默认值面向企业级稳定性：挂起额度、IO 并发、预算并发都取保守值——
 * 越界行为一律 fail-open（fallback 放行），不会因配置失误阻塞业务请求。
 */
public final class RouteConfig {

    /** 每 context 最多同时挂起（等待 IO 延迟终结）的请求数。 */
    public static final int DEFAULT_MAX_PENDING = 16;
    /** IO 线程数（fetch / sleep / 观测消费）。 */
    public static final int DEFAULT_IO_THREADS = 2;
    /** IO 任务队列容量（队满即拒绝 → fail-open）。 */
    public static final int DEFAULT_IO_QUEUE_CAPACITY = 64;
    /** BoundedOps 最大并发（同步 fetch / pattern 重装类操作）。 */
    public static final int DEFAULT_OPS_MAX_CONCURRENT = 8;
    /** 挂起 claim 的超龄阈值（秒）：超过即被巡检强制 fallback。须大于 fetch 超时（30s）。 */
    public static final long DEFAULT_IO_AWAIT_TIMEOUT_SECONDS = 35L;
    /** 巡检周期（毫秒）。 */
    public static final long DEFAULT_SWEEP_INTERVAL_MS = 2_000L;
    /** 每 context 采集队列上限（条；超出丢弃新记录并计数，防内存失控）。 */
    public static final int DEFAULT_MAX_CAPTURED = 1_000;
    /** 未响应采集请求的超时定案窗口（毫秒，30s；与 monitor 默认超时一致）。 */
    public static final long DEFAULT_CAPTURE_TIMEOUT_MS = 30_000L;

    public static final RouteConfig DEFAULTS = new RouteConfig(
            DEFAULT_MAX_PENDING,
            DEFAULT_IO_THREADS,
            DEFAULT_IO_QUEUE_CAPACITY,
            DEFAULT_OPS_MAX_CONCURRENT,
            DEFAULT_IO_AWAIT_TIMEOUT_SECONDS,
            DEFAULT_SWEEP_INTERVAL_MS,
            DEFAULT_MAX_CAPTURED,
            DEFAULT_CAPTURE_TIMEOUT_MS);

    private final int maxPending;
    private final int ioThreads;
    private final int ioQueueCapacity;
    private final int opsMaxConcurrent;
    private final long ioAwaitTimeoutSeconds;
    private final long sweepIntervalMs;
    private final int maxCaptured;
    private final long captureTimeoutMs;

    public RouteConfig(int maxPending, int ioThreads, int ioQueueCapacity,
                         int opsMaxConcurrent, long ioAwaitTimeoutSeconds, long sweepIntervalMs,
                         int maxCaptured, long captureTimeoutMs) {
        if (maxPending <= 0 || ioThreads <= 0 || ioQueueCapacity <= 0 || opsMaxConcurrent <= 0
                || maxCaptured <= 0 || captureTimeoutMs <= 0) {
            throw new IllegalArgumentException("concurrency bounds must be > 0");
        }
        this.maxPending = maxPending;
        this.ioThreads = ioThreads;
        this.ioQueueCapacity = ioQueueCapacity;
        this.opsMaxConcurrent = opsMaxConcurrent;
        this.ioAwaitTimeoutSeconds = ioAwaitTimeoutSeconds;
        this.sweepIntervalMs = sweepIntervalMs;
        this.maxCaptured = maxCaptured;
        this.captureTimeoutMs = captureTimeoutMs;
    }

    public int maxPending() {
        return maxPending;
    }

    public int ioThreads() {
        return ioThreads;
    }

    public int ioQueueCapacity() {
        return ioQueueCapacity;
    }

    public int opsMaxConcurrent() {
        return opsMaxConcurrent;
    }

    public long ioAwaitTimeoutSeconds() {
        return ioAwaitTimeoutSeconds;
    }

    public long sweepIntervalMs() {
        return sweepIntervalMs;
    }

    public int maxCaptured() {
        return maxCaptured;
    }

    public long captureTimeoutMs() {
        return captureTimeoutMs;
    }
}
