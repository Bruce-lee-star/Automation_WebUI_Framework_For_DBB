package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * API 监控失败 / 数据丢失的汇总数据（dependency-inversion 载体）。
 *
 * <p>由 route 侧 {@link MonitorFailureReportSink#collectData()} 经 SPI 产出，
 * reporting 模块据此渲染 HTML 显示区域，避免编译期依赖 route 具体类（打破循环依赖）。
 *
 * <p>所有集合/数值字段均不可为 {@code null}（{@link #empty()} 以空集合/0 表示无内容），
 * 方便 Freemarker 模板安全遍历。
 */
public class MonitorFailureReportData {

    private final List<MonitorOwnerBlock> owners;
    private final Map<String, Long> dataLossByCategory;
    private final long totalDataLoss;
    private final int ownerCount;
    private final int failureCount;

    public MonitorFailureReportData(List<MonitorOwnerBlock> owners,
                                   Map<String, Long> dataLossByCategory,
                                   long totalDataLoss, int ownerCount, int failureCount) {
        // 防御性拷贝为不可变集合，避免返回内部可变引用（EI_EXPOSE_REP / EI_EXPOSE_REP2）
        this.owners = owners == null ? List.of() : List.copyOf(owners);
        this.dataLossByCategory = dataLossByCategory == null ? Map.of() : Map.copyOf(dataLossByCategory);
        this.totalDataLoss = totalDataLoss;
        this.ownerCount = ownerCount;
        this.failureCount = failureCount;
    }

    public List<MonitorOwnerBlock> getOwners() {
        return owners;
    }

    public Map<String, Long> getDataLossByCategory() {
        return dataLossByCategory;
    }

    public long getTotalDataLoss() {
        return totalDataLoss;
    }

    public int getOwnerCount() {
        return ownerCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    /**
     * 是否包含任何需展示的内容（有失败记录或数据丢失）。
     * 供 reporting 模块决定是否渲染显示区域，避免无内容时污染报告。
     */
    public boolean hasContent() {
        return (owners != null && !owners.isEmpty())
                || (dataLossByCategory != null && !dataLossByCategory.isEmpty());
    }

    public static MonitorFailureReportData empty() {
        return new MonitorFailureReportData(
                Collections.emptyList(), Collections.emptyMap(), 0L, 0, 0);
    }
}
