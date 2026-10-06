package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * WEB-P1-5 种子测试：监听器计数/统计/测试数据存储（无浏览器）。
 * 计数为全局静态状态，故每个用例后 {@code resetStats()} 复位，避免污染同 JVM 内其它测试。
 */
public class ListenerPerfStatsTest {

    @After
    public void tearDown() {
        ListenerPerfStats.resetStats();
    }

    @Test
    public void counters_reflectInPerformanceStats() {
        ListenerPerfStats.markTotal();
        ListenerPerfStats.markTotal();
        ListenerPerfStats.markTotal();
        ListenerPerfStats.markPassed();
        ListenerPerfStats.markFailed();
        ListenerPerfStats.markSkipped();
        ListenerPerfStats.incrementScreenshot();

        String stats = ListenerPerfStats.getPerformanceStats();
        assertTrue("应统计总用例数：" + stats, stats.contains("Total Tests: 3"));
        assertTrue("应统计通过数：" + stats, stats.contains("Passed: 1"));
        assertTrue("应统计失败数：" + stats, stats.contains("Failed: 1"));
        assertTrue("应统计跳过数：" + stats, stats.contains("Skipped: 1"));
        assertTrue("应统计截图数：" + stats, stats.contains("Screenshots Taken: 1"));
    }

    @Test
    public void percentage_isZeroWhenNoTestsRecorded() {
        String stats = ListenerPerfStats.getPerformanceStats();
        assertTrue("无用例时百分比应为 0.0%，避免除零：" + stats, stats.contains("Passed: 0 (0.0%)"));
    }

    @Test
    public void resetStats_clearsAllCounters() {
        ListenerPerfStats.markTotal();
        ListenerPerfStats.markPassed();
        ListenerPerfStats.incrementScreenshot();

        ListenerPerfStats.resetStats();

        String stats = ListenerPerfStats.getPerformanceStats();
        assertTrue(stats.contains("Total Tests: 0"));
        assertTrue(stats.contains("Screenshots Taken: 0"));
    }

    @Test
    public void record_withNullArguments_isSilentlyIgnored() {
        ListenerPerfStats.record(null, "key", "value");
        ListenerPerfStats.record("test", null, "value");
        ListenerPerfStats.record("test", "key", null);

        // 非法入参被忽略且不抛异常；计数器不受影响
        assertTrue(ListenerPerfStats.getPerformanceStats().contains("Total Tests: 0"));
    }
}
