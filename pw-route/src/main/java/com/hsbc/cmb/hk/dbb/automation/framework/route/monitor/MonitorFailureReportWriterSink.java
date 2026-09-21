package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorFailureReportData;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorFailureItem;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorFailureReportSink;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorOwnerBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        // R-4：报告写出后复位数据丢失汇总，避免同 JVM 多 runner 跨套件累积
        MonitorDataLossReporter.instance().reset();
    }

    @Override
    public MonitorFailureReportData collectData() {
        MonitorFailureCollector collector = MonitorFailureCollector.getInstance();
        Map<String, List<MonitorFailureCollector.FailedApiCall>> byOwner = collector.getFailuresByOwner();

        List<MonitorOwnerBlock> owners = new ArrayList<>();
        int failureCount = 0;
        for (Map.Entry<String, List<MonitorFailureCollector.FailedApiCall>> entry : byOwner.entrySet()) {
            List<MonitorFailureItem> items = new ArrayList<>();
            for (MonitorFailureCollector.FailedApiCall call : entry.getValue()) {
                items.add(new MonitorFailureItem(
                        call.getOwner(),
                        dashIfEmpty(call.getFeature()),
                        call.getPattern(),
                        call.getStatus(),
                        dashIfEmpty(call.getMethod()),
                        dashIfEmpty(call.getRequestUrl()),
                        dashIfEmpty(call.getReason()),
                        call.getScenarios(),
                        dashIfEmpty(call.getRequestBody()),
                        dashIfEmpty(call.getResponseBody())));
                failureCount++;
            }
            owners.add(new MonitorOwnerBlock(entry.getKey(), items));
        }

        MonitorDataLossReporter loss = MonitorDataLossReporter.instance();
        return new MonitorFailureReportData(
                owners, loss.lossByCategory(), loss.totalLoss(), byOwner.size(), failureCount);
    }

    private static String dashIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? "-" : s;
    }
}
