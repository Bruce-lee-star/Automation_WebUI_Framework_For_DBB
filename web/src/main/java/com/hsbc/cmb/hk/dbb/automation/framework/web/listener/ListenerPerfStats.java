package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 *  收口 {@code PlaywrightListener} 的测试计数、性能统计与测试数据存储（原 totalTests/passedTests/.../testData 簇）。
 *
 * <p>纯数据职责，与监听器生命周期完全解耦：计数器经原子变量保证并发可见性，
 * {@code testData} 经 ConcurrentHashMap 保证线程安全。对外暴露的 {@link #getPerformanceStats()} / {@link #resetStats()}
 * 作为原公开静态 API 的门面，保持零变更。</p>
 */
public final class ListenerPerfStats {

    private static final Logger logger = LoggerFactory.getLogger(ListenerPerfStats.class);

    private static final AtomicLong totalTests = new AtomicLong(0);
    private static final AtomicLong passedTests = new AtomicLong(0);
    private static final AtomicLong failedTests = new AtomicLong(0);
    private static final AtomicLong skippedTests = new AtomicLong(0);
    private static final AtomicLong screenshotCounter = new AtomicLong(0);

    private static final ConcurrentHashMap<String, Object> testData = new ConcurrentHashMap<>();

    private ListenerPerfStats() {
    }

    static void markTotal() {
        totalTests.incrementAndGet();
    }

    static void markPassed() {
        passedTests.incrementAndGet();
    }

    static void markFailed() {
        failedTests.incrementAndGet();
    }

    static void markSkipped() {
        skippedTests.incrementAndGet();
    }

    static void incrementScreenshot() {
        screenshotCounter.incrementAndGet();
    }

    /**
     * 记录测试数据（等价原 PlaywrightListener.recordTestData）。
     *
     * @param testName 当前测试名（由调用方解析）
     * @param key      数据键
     * @param value    数据值
     */
    static void record(String testName, String key, Object value) {
        if (testName != null && key != null && value != null) {
            String dataKey = testName + "." + key;
            testData.put(dataKey, value);
            VerboseLogging.logDebugIfVerbose(logger, "Test data recorded: {} = {}", key, value);
        } else {
            if (testName == null) {
                logger.debug("Skip recording test data: testName is null (key={})", key);
            }
            if (key == null) {
                logger.debug("Skip recording test data: key is null");
            }
        }
    }

    static String getPerformanceStats() {
        return String.format(
                "Performance Statistics:%n"
                        + "Total Tests: %d%n"
                        + "Passed: %d (%.1f%%)%n"
                        + "Failed: %d (%.1f%%)%n"
                        + "Skipped: %d (%.1f%%)%n"
                        + "Screenshots Taken: %d",
                totalTests.get(),
                passedTests.get(),
                calculatePercentage(passedTests.get()),
                failedTests.get(),
                calculatePercentage(failedTests.get()),
                skippedTests.get(),
                calculatePercentage(skippedTests.get()),
                screenshotCounter.get());
    }

    private static double calculatePercentage(long value) {
        return totalTests.get() > 0 ? (value * 100.0 / totalTests.get()) : 0.0;
    }

    static void resetStats() {
        totalTests.set(0);
        passedTests.set(0);
        failedTests.set(0);
        skippedTests.set(0);
        screenshotCounter.set(0);
        testData.clear();
    }
}
