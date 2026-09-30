package com.hsbc.cmb.hk.dbb.automation.framework.route.dsl;

/**
 * 条件字段修改（对齐现有 RouteDsl 的 {@code when(...).thenSet(...)}）。
 *
 * <p>语义：当响应中 {@code whenPath} 的取值满足 {@code op} 与 {@code expected} 时，
 * 才将 {@code thenPath} 设置为 {@code value}；不满足则保留原值（不影响其它字段）。
 *
 * <p>仅适用于 JSON 响应体（条件读取与目标替换均为 JSONPath）；非 JSON 响应 → 跳过并记录 failure。
 *
 * @param whenPath   条件判断的 JSONPath（读取响应中的字段）
 * @param op         条件操作符（忽略大小写）：EQUALS / NOT_EQUALS / CONTAINS / NOT_CONTAINS /
 *                   REGEX / EXISTS / NOT_EXISTS / GT / LT / GTE / LTE
 * @param expected   期望比对值（Number 按数值比较，其余 equals）
 * @param thenPath   满足条件时被修改的目标 JSONPath
 * @param value      满足条件时目标字段被设置成的值
 */
public record ConditionalField(String whenPath, String op, Object expected, String thenPath, Object value) {

    public ConditionalField {
        if (whenPath == null || whenPath.isBlank()) {
            throw new IllegalArgumentException("whenPath must not be blank");
        }
        if (op == null || op.isBlank()) {
            throw new IllegalArgumentException("op must not be blank");
        }
        if (thenPath == null || thenPath.isBlank()) {
            throw new IllegalArgumentException("thenPath must not be blank");
        }
    }
}
