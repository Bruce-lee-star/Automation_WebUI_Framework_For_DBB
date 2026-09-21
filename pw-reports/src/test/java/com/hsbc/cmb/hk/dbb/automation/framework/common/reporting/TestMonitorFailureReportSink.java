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
 */
public class TestMonitorFailureReportSink implements MonitorFailureReportSink {

    private static volatile MonitorFailureReportData sample = MonitorFailureReportData.empty();

    public static void setSampleData(MonitorFailureReportData data) {
        sample = (data == null) ? MonitorFailureReportData.empty() : data;
    }

    public static void reset() {
        sample = MonitorFailureReportData.empty();
    }

    @Override
    public int write() {
        return 0;
    }

    @Override
    public void clear() {
        // 测试桩不触碰真实归集器
    }

    @Override
    public MonitorFailureReportData collectData() {
        return sample;
    }
}
