package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import java.util.Objects;

/**
 * 条件字段修改规则 — 仅对 {@code interceptRealResponse=true} 模式生效。
 *
 * <p>语义：当响应里 {@code whenJsonPath} 取值满足 {@code op}/{@code expected} 时，
 * 才对 {@code thenSetJsonPath} 设置 {@code setValue}；不满足条件则保留原值（不影响其它数据）。
 *
 * <p>支持 JSONPath 通配符 {@code [*]}，逐元素独立评估：
 * 例如 {@code whenJsonPath=$.users[*].status}、{@code thenSetJsonPath=$.users[*].flag}，
 * 则每个 user 元素的 status 达标时才改该元素的 flag，互不影响。
 *
 * <p>示例（DSL）：
 * <pre>{@code
 * route("/api/users")
 *     .interceptRealResponse()
 *     .when("$.data.status", "EQUALS", "ACTIVE").thenSet("$.data.vip", true)
 *     .when("$.users[*].age", "GT", 18).thenSet("$.users[*].canVote", true);
 * }</pre>
 */
public class ConditionalFieldRule {

    /** 条件操作符 */
    public enum ConditionOp {
        EQUALS,
        NOT_EQUALS,
        CONTAINS,
        NOT_CONTAINS,
        REGEX,
        EXISTS,
        NOT_EXISTS,
        GT,
        LT,
        GTE,
        LTE;

        /** 从字符串解析（忽略大小写），便于 DSL / JSON 配置使用 */
        public static ConditionOp from(String s) {
            return ConditionOp.valueOf(s.trim().toUpperCase());
        }
    }

    private String whenJsonPath;
    private ConditionOp op;
    private Object expected;
    private String thenSetJsonPath;
    private Object setValue;

    public ConditionalFieldRule() {}

    public ConditionalFieldRule(String whenJsonPath, ConditionOp op, Object expected,
                                String thenSetJsonPath, Object setValue) {
        this.whenJsonPath = whenJsonPath;
        this.op = op;
        this.expected = expected;
        this.thenSetJsonPath = thenSetJsonPath;
        this.setValue = setValue;
    }

    public String getWhenJsonPath() { return whenJsonPath; }
    public void setWhenJsonPath(String v) { this.whenJsonPath = v; }

    public ConditionOp getOp() { return op; }
    public void setOp(ConditionOp v) { this.op = v; }

    public Object getExpected() { return expected; }
    public void setExpected(Object v) { this.expected = v; }

    public String getThenSetJsonPath() { return thenSetJsonPath; }
    public void setThenSetJsonPath(String v) { this.thenSetJsonPath = v; }

    public Object getSetValue() { return setValue; }
    public void setSetValue(Object v) { this.setValue = v; }

    /** 校验规则完整性（when / then 路径与操作符必备） */
    public boolean isValid() {
        return whenJsonPath != null && !whenJsonPath.trim().isEmpty()
                && op != null
                && thenSetJsonPath != null && !thenSetJsonPath.trim().isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConditionalFieldRule)) return false;
        ConditionalFieldRule r = (ConditionalFieldRule) o;
        return Objects.equals(whenJsonPath, r.whenJsonPath)
                && op == r.op
                && Objects.equals(expected, r.expected)
                && Objects.equals(thenSetJsonPath, r.thenSetJsonPath)
                && Objects.equals(setValue, r.setValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(whenJsonPath, op, expected, thenSetJsonPath, setValue);
    }

    @Override
    public String toString() {
        return "ConditionalFieldRule{when=" + whenJsonPath + " " + op + " " + expected
                + " -> set " + thenSetJsonPath + " = " + setValue + "}";
    }
}
