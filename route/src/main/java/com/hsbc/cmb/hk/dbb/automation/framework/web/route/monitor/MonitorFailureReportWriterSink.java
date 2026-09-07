package com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorFailureReportSink;

/**
 * {@link MonitorFailureReportSink} 的 route 侧实现，委托既有静态写出器与归集器。
 *
 * <p>通过 {@code META-INF/services} 注册，由框架报告聚合器经 {@link java.util.ServiceLoader}
 * 发现，避免 reporting 模块在编译期依赖 route 具体类（打破循环依赖）。
 */
public class MonitorFailureReportWriterSink implements MonitorFailureReportSink {

    @Override
    public int write() {
        return MonitorFailureReportWriter.write();
    }

    @Override
    public void clear() {
        MonitorFailureCollector.getInstance().clear();
    }
}
