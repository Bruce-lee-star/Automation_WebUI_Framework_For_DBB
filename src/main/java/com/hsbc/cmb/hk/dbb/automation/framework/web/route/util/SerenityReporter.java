package com.hsbc.cmb.hk.dbb.automation.framework.web.route.util;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import net.serenitybdd.core.Serenity;
import net.thucydides.core.steps.StepEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serenity 报告写入工具（主线程安全）
 *
 * <p>统一封装 Serenity.recordReportData() 调用，确保只在测试主线程写入报告。
 * 非 Serenity 环境（如纯 JUnit 测试）自动静默降级。
 *
 * <h3>待报告队列机制</h3>
 * <p>Route Handler（MonitorHandler / MockHandler / ModifyHandler）在 Playwright
 * 事件线程或 RouteAsyncPool worker 线程中触发，这些线程与 Serenity 测试主线程
 * 无 ThreadLocal 关联，直接调用 {@code Serenity.recordReportData()} 数据会丢失。
 *
 * <p>解决方案：
 * <ol>
 *   <li>Handler 调用 {@link #recordApiOperation(String, String, String)}
 *       将记录入队到线程安全的 {@code ConcurrentLinkedQueue}</li>
 *   <li>{@code SerenityBasePage} 的 {@code record()} / {@code recordAndReturn()}
 *       拦截器在主线程上调用 {@link #flushPendingApiOperations()} 批量写入报告</li>
 * </ol>
 */
public final class SerenityReporter {

    private static final Logger logger = LoggerFactory.getLogger(SerenityReporter.class);

    /**
     * 线程安全的待报告 API 操作队列。
     * Handler（Playwright 事件线程 / RouteAsyncPool worker 线程）入队，
     * SerenityBasePage 拦截器（主线程）出队写入 Serenity 报告。
     */
    private static final Queue<PendingApiRecord> pendingQueue = new ConcurrentLinkedQueue<>();

    /**
     * 待报告记录数（近似值，用于监控）。
     * 不使用 ConcurrentLinkedQueue.size() 是为了 O(1) 而非 O(n)。
     */
    private static final AtomicInteger pendingCount = new AtomicInteger(0);

    /**
     * 单条待报告记录上限（防止极端场景 OOM）。
     * 默认 500 条，超过后 WARN 日志 + 丢弃最旧记录。
     */
    private static final int MAX_PENDING_RECORDS = Integer.getInteger(
            "serenity.route.maxPendingRecords", 500);

    private SerenityReporter() {}

    /**
     * 待报告 API 操作记录 DTO。
     */
    private static class PendingApiRecord {
        final String operation;
        final String url;
        final String detail;

        PendingApiRecord(String operation, String url, String detail) {
            this.operation = operation;
            this.url = url;
            this.detail = detail;
        }
    }

    /**
     * 记录 API 操作到待报告队列（线程安全，可在任意线程调用）。
     *
     * <p>实际的 Serenity 报告写入由 {@link #flushPendingApiOperations()}
     * 在测试主线程上执行。
     *
     * @param operation 操作类型（MONITOR / MOCK / MODIFY）
     * @param url       请求 URL
     * @param detail    详情内容
     */
    public static void recordApiOperation(String operation, String url, String detail) {
        try {
            // 队列容量保护：超过上限丢弃最旧记录
            int current = pendingCount.incrementAndGet();
            if (current > MAX_PENDING_RECORDS) {
                pendingQueue.poll(); // 丢弃最旧的
                pendingCount.decrementAndGet();
                logger.warn("[SerenityReporter] Pending queue full ({}), dropped oldest record. "
                        + "Consider calling flushPendingApiOperations() more frequently.", MAX_PENDING_RECORDS);
            }
            pendingQueue.offer(new PendingApiRecord(operation, url, detail));
            LoggingConfigUtil.logTraceIfVerbose(logger,
                    "[SerenityReporter] recordApiOperation ENQUEUED: {} {}, pendingCount={}",
                    operation, url, pendingCount.get());
        } catch (Exception e) {
            logger.debug("[SerenityReporter] Failed to enqueue API operation: {}", e.getMessage());
        }
    }

    /**
     * 将待报告队列中的所有 API 操作刷入 Serenity 报告。
     *
     * <p><b>必须在测试主线程上调用</b>（由 {@code SerenityBasePage} 的
     * {@code record()} / {@code recordAndReturn()} 拦截器触发）。
     *
     * <p>调用时机：
     * <ul>
     *   <li>每个 {@code SerenityBasePage} 操作前（record / recordAndReturn 拦截）</li>
     *   <li>测试步骤结束时（可选，兜底刷新）</li>
     * </ul>
     */
    public static void flushPendingApiOperations() {
        if (pendingQueue.isEmpty()) {
            return;
        }

        // 快速检查：避免非 Serenity 环境写入报告时触发 ERROR 日志
        if (!StepEventBus.getEventBus().isBaseStepListenerRegistered()) {
            // 清空队列避免积压
            int drained = 0;
            while (pendingQueue.poll() != null) {
                drained++;
            }
            pendingCount.set(0);
            LoggingConfigUtil.logTraceIfVerbose(logger,
                    "[SerenityReporter] flushPendingApiOperations SKIP: no listener, drained {} records", drained);
            return;
        }

        int flushed = 0;
        PendingApiRecord record;
        while ((record = pendingQueue.poll()) != null) {
            try {
                String title = String.format("[API %s] %s", record.operation,
                        record.url.length() > 80 ? record.url.substring(0, 80) + "..." : record.url);
                Serenity.recordReportData()
                        .withTitle(title)
                        .andContents(record.detail);
                flushed++;
            } catch (Exception e) {
                logger.debug("[SerenityReporter] Failed to flush record: {} {} - {}",
                        record.operation, record.url, e.getMessage());
            }
        }
        pendingCount.set(0);

        if (flushed > 0) {
            LoggingConfigUtil.logDebugIfVerbose(logger,
                    "[SerenityReporter] flushPendingApiOperations DONE: flushed {} API record(s)", flushed);
        }
    }

    /**
     * 获取待报告记录数（近似值，用于监控）。
     */
    public static int getPendingCount() {
        return pendingCount.get();
    }
}
