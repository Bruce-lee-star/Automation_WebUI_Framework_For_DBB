package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

/**
 * API 监控失败报告的写出契约（依赖倒置点）。
 *
 * <p>框架级报告聚合器（SummaryReportGenerator）仅依赖本接口，不依赖 route 的具体实现；
 * 具体实现由 route 模块通过 {@code META-INF/services} SPI 提供，运行时由
 * {@link java.util.ServiceLoader} 发现。借此打破 reporting ↔ route 的编译期循环依赖。
 *
 * <p>契约：
 * <ul>
 *   <li>{@link #write()} — 写出失败报告，返回失败 owner 数（&gt;0 表示需通知）</li>
 *   <li>{@link #clear()} — 写出后清空归集器，防止多套件同 JVM 运行时的跨套件累积</li>
 * </ul>
 */
public interface MonitorFailureReportSink {

    /**
     * 写出 API 监控失败报告（如 {@code target/monitor-failures-by-owner.json}）。
     *
     * @return 失败 owner 数（&gt;0 表示有失败需要通知）
     */
    int write();

    /**
     * 清空内部归集状态（测试套件结束时调用）。
     */
    void clear();

    /**
     * 收集供 HTML 报告展示的监控失败 / 数据丢失汇总数据。
     *
     * <p>默认返回 {@link MonitorFailureReportData#empty()}；route 侧实现返回真实聚合。
     * 本方法<b>仅读取</b>、不写出文件、也不复位归集器（复位由 {@link #clear()} 负责），
     * 须先于 {@link #clear()} 调用，以免数据被复位后丢失。
     *
     * @return HTML 报告展示所需的汇总数据（非 {@code null}）
     */
    default MonitorFailureReportData collectData() {
        return MonitorFailureReportData.empty();
    }
}
