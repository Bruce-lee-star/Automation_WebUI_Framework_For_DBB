package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import java.util.List;
import java.util.Map;

/**
 * E-2：单场运行快照（写入 {@code <reportDir>/trend-history/<buildId>.json}，供跨场对比）。
 *
 * @param buildId            快照标识（同时作为文件名，建议用可排序的时间戳）
 * @param timestamp          ISO-8601 运行时间
 * @param total              用例总数
 * @param passed             通过数
 * @param failed             失败/错误数
 * @param scenarioDurationMs 场景名 → 耗时（毫秒）
 * @param failedScenarios    失败/错误场景名（用于跨场 flaky 判定）
 */
public record RunSummary(String buildId,
                         String timestamp,
                         int total,
                         int passed,
                         int failed,
                         Map<String, Long> scenarioDurationMs,
                         List<String> failedScenarios) {

    /**
     * 防御性拷贝（SpotBugs {@code EI_EXPOSE_REP2} / {@code EI_EXPOSE_REP}）。
     *
     * <p>快照写出后即代表「那一刻的运行结果」：若共享调用方的可变集合，调用方后续改动会**悄悄篡改历史快照**，
     * 跨场趋势与 flaky 判定随之失真。构造时拷贝 + 访问器返回不可变视图，两侧同时收口。
     */
    public RunSummary {
        scenarioDurationMs = scenarioDurationMs == null ? Map.of() : Map.copyOf(scenarioDurationMs);
        failedScenarios = failedScenarios == null ? List.of() : List.copyOf(failedScenarios);
    }
}
