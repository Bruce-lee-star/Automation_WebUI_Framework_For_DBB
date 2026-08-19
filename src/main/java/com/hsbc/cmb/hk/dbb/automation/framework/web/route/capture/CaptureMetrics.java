package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

/**
 * 采集管道运行时指标快照 — 用于健康检查和监控。
 *
 * <p>由 {@link CaptureEngine#metrics()} 返回，测试框架可定期轮询或 JMX 暴露。
 */
public class CaptureMetrics {

    private final long ringBufferCapacity;
    private final long ringBufferPending;
    private final long ringBufferDropped;
    private final long ringBufferTotalPublished;
    private final long mergerCompletedCalls;
    private final long mergerFailedMerges;
    private final long mergerStaleSlots;
    private final long bodyFetchCount;
    private final long bodyFetchFailures;
    private final int threadPoolActiveCount;
    private final int threadPoolQueueSize;
    private final long threadPoolCompletedTaskCount;
    private final CaptureEngine.Health health;

    CaptureMetrics(long ringBufferCapacity,
                   long ringBufferPending, long ringBufferDropped,
                   long ringBufferTotalPublished,
                   long mergerCompletedCalls, long mergerFailedMerges,
                   long mergerStaleSlots, long bodyFetchCount,
                   long bodyFetchFailures, int threadPoolActiveCount,
                   int threadPoolQueueSize, long threadPoolCompletedTaskCount,
                   CaptureEngine.Health health) {
        this.ringBufferCapacity = ringBufferCapacity;
        this.ringBufferPending = ringBufferPending;
        this.ringBufferDropped = ringBufferDropped;
        this.ringBufferTotalPublished = ringBufferTotalPublished;
        this.mergerCompletedCalls = mergerCompletedCalls;
        this.mergerFailedMerges = mergerFailedMerges;
        this.mergerStaleSlots = mergerStaleSlots;
        this.bodyFetchCount = bodyFetchCount;
        this.bodyFetchFailures = bodyFetchFailures;
        this.threadPoolActiveCount = threadPoolActiveCount;
        this.threadPoolQueueSize = threadPoolQueueSize;
        this.threadPoolCompletedTaskCount = threadPoolCompletedTaskCount;
        this.health = health;
    }

    // ── Getters ──

    /** RingBuffer 容量 */
    public long ringBufferCapacity() { return ringBufferCapacity; }

    /** 待消费事件数 */
    public long ringBufferPending() { return ringBufferPending; }

    /** 累计丢弃事件数 */
    public long ringBufferDropped() { return ringBufferDropped; }

    /** 总发布事件数 */
    public long ringBufferTotalPublished() { return ringBufferTotalPublished; }

    /** 已完成合并的 API 调用数 */
    public long mergerCompletedCalls() { return mergerCompletedCalls; }

    /** 合并失败的调用数 */
    public long mergerFailedMerges() { return mergerFailedMerges; }

    /** 超时清理的 stale slot 数 */
    public long mergerStaleSlots() { return mergerStaleSlots; }

    /** Body 读取次数 */
    public long bodyFetchCount() { return bodyFetchCount; }

    /** Body 读取失败次数 */
    public long bodyFetchFailures() { return bodyFetchFailures; }

    /** 线程池活跃线程数 */
    public int threadPoolActiveCount() { return threadPoolActiveCount; }

    /** 线程池队列大小 */
    public int threadPoolQueueSize() { return threadPoolQueueSize; }

    /** 线程池已完成任务数 */
    public long threadPoolCompletedTaskCount() { return threadPoolCompletedTaskCount; }

    /** 引擎健康状态 */
    public CaptureEngine.Health health() { return health; }

    /** 丢弃率（0-1） */
    public double dropRate() {
        long total = ringBufferTotalPublished;
        return total > 0 ? (double) ringBufferDropped / total : 0;
    }

    /** 是否健康 */
    public boolean isHealthy() {
        return health == CaptureEngine.Health.HEALTHY;
    }

    /** 格式化输出 */
    public String toSummary() {
        return String.format(
                "CaptureMetrics{health=%s, ringBuf{pending=%d, dropped=%d, cap=%d}, "
                        + "merger{completed=%d, failed=%d, stale=%d}, "
                        + "bodyFetch{fetched=%d, failed=%d}, "
                        + "pool{active=%d, queue=%d, completed=%d}}",
                health, ringBufferPending, ringBufferDropped, ringBufferCapacity,
                mergerCompletedCalls, mergerFailedMerges,
                mergerStaleSlots, bodyFetchCount, bodyFetchFailures,
                threadPoolActiveCount, threadPoolQueueSize, threadPoolCompletedTaskCount);
    }
}