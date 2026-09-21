package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 单个并发用例的不可变数据（即 feature DataTable 的一行）。
 *
 * <p>框架 glue 把 {@code DataTable.asMaps} 的每一行包装为本类型后交由 {@link ConcurrentScenarioExecutor}
 * 驱动；业务动作经 {@link #get(String)} 读取各列。本类型额外提供
 * {@link #identityPartition()} 供 {@link ConcurrencyGate} 抽取 SSO 互斥分区键（默认取
 * {@code env} + {@code username} 维度）。</p>
 */
public final class ConcurrentCaseData {

    private final Map<String, String> values; // 不可变、去除 null 列

    /**
     * 由 DataTable 一行（原始映射）构建；null 值列被剔除，避免污染分区键。
     *
     * @param row 原始行（键=列名，值=单元格）；空 / null 抛 {@link IllegalArgumentException}
     */
    public ConcurrentCaseData(Map<String, String> row) {
        if (row == null || row.isEmpty()) {
            throw new IllegalArgumentException("ConcurrentCaseData requires a non-empty row");
        }
        Map<String, String> cleaned = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : row.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            cleaned.put(e.getKey().trim(), e.getValue() == null ? "" : e.getValue());
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("ConcurrentCaseData requires at least one non-null column name");
        }
        this.values = Collections.unmodifiableMap(cleaned);
    }

    /** 取某列值（列名忽略前后空白）；不存在返回 {@code null}。 */
    public String get(String column) {
        return column == null ? null : values.get(column.trim());
    }

    /** 原始行（不可变视图）。 */
    public Map<String, String> rawValues() {
        return values;
    }

    /** 人类可读名称：{@code env/username}，用于日志与结果追踪。 */
    public String name() {
        return values.getOrDefault("env", "?") + "/" + values.getOrDefault("username", "?");
    }

    /**
     * SSO 互斥分区键：取 {@code env} + {@code username} 维度构建 {@link ConcurrencyPartitionKey}；
     * 二者任一缺失（空白）时返回 {@code null}，表示本用例不参与互斥（闸门恒放行）。
     */
    public ConcurrencyPartitionKey identityPartition() {
        String env = get("env");
        String username = get("username");
        if (env == null || env.isBlank() || username == null || username.isBlank()) {
            return null;
        }
        return ConcurrencyPartitionKey.of(Map.of("environment", env.trim(), "username", username.trim()));
    }

    @Override
    public String toString() {
        return "ConcurrentCaseData{" + name() + '}';
    }

    /** 防御性相等（基于不可变值映射）。 */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConcurrentCaseData)) {
            return false;
        }
        return values.equals(((ConcurrentCaseData) o).values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values);
    }
}
