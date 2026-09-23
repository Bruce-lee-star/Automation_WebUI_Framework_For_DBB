package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

/**
 * 测试专用 {@link MonitorFailureReportSink} 实现：经 {@code META-INF/services} 注册到 reporting
 * 测试 classpath，用于在<b>无 route 模块</b>的 reporting 测试环境里向 {@link SummaryReportGenerator}
 * 注入样例监控数据，端到端验证「有监控失败 / 数据丢失时，summary report 完整 HTML 渲染出显示区域」。
 *
 * <p><b>门控</b>：默认返回 {@link MonitorFailureReportData#empty()}（不渲染区域），因此不会污染
 * golden/branch/trace 等基线断言；仅当 {@link #setSampleData(MonitorFailureReportData)} 被调用时才返回
 * 样例数据。{@link #reset()} 在用例 {@code @AfterEach} 复位，避免跨用例串扰。
 *
 * <p>{@code write()}/{@code clear()} 为无操作桩：测试只关心 {@code collectData()} 数据注入路径，
 * 不写文件、不清真归集器（与真实 route 实现的副作用隔离）。
 *
 * <p><b>N-05 支持</b>：{@link #setFailWrite(boolean)} 可令 {@code write()} 抛异常，
 * 配合 {@link #writeAttempts()} / {@link #clearCalls()} 计数，用于验证
 * 「write 失败的 sink <b>不得</b>被 clear（否则记录既未落盘也从内存消失 = 静默数据丢失）」。
 */
public class TestMonitorFailureReportSink implements MonitorFailureReportSink {

    private static volatile MonitorFailureReportData sample = MonitorFailureReportData.empty();

    /** N-05：置位后 {@link #write()} 抛异常（模拟磁盘满/权限/序列化失败）。 */
    private static volatile boolean failWrite = false;

    /** N-05 可观测计数：write 被调用次数、clear 被调用次数（断言"失败者不被清空"）。 */
    private static final java.util.concurrent.atomic.AtomicInteger WRITE_ATTEMPTS =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger CLEAR_CALLS =
            new java.util.concurrent.atomic.AtomicInteger();

    public static void setSampleData(MonitorFailureReportData data) {
        sample = (data == null) ? MonitorFailureReportData.empty() : data;
    }

    /** N-05：令后续 {@code write()} 抛异常（由 {@link #reset()} 复位）。 */
    public static void setFailWrite(boolean fail) {
        failWrite = fail;
    }

    /** N-05：{@code write()} 被调用次数。 */
    public static int writeAttempts() {
        return WRITE_ATTEMPTS.get();
    }

    /** N-05：{@code clear()} 被调用次数（写失败者应保持 0）。 */
    public static int clearCalls() {
        return CLEAR_CALLS.get();
    }

    public static void reset() {
        sample = MonitorFailureReportData.empty();
        failWrite = false;
        WRITE_ATTEMPTS.set(0);
        CLEAR_CALLS.set(0);
    }

    @Override
    public int write() {
        WRITE_ATTEMPTS.incrementAndGet();
        if (failWrite) {
            throw new IllegalStateException("simulated sink write failure (N-05 regression guard)");
        }
        return 0;
    }

    @Override
    public void clear() {
        CLEAR_CALLS.incrementAndGet();
        // 测试桩不触碰真实归集器
    }

    @Override
    public MonitorFailureReportData collectData() {
        return sample;
    }
}
