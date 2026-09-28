package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.util;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ConditionalField;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.PathNotFoundException;

import java.util.Locale;

/**
 * 条件字段修改的条件评估器（对齐现有 RouteDsl 的 ConditionalFieldRule 语义）。
 *
 * <p>支持的 op（忽略大小写）：EQUALS / NOT_EQUALS / CONTAINS / NOT_CONTAINS / REGEX /
 * EXISTS / NOT_EXISTS / GT / LT / GTE / LTE。
 * Number 比较按数值（double）；REGEX 为全字符串 matches 语义（对齐老版 String.matches）。
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    /**
     * 评估条件是否满足（仅 JSON body 生效；非 JSON 返回 false，由调用方记录 not-applicable）。
     */
    public static boolean evaluate(DocumentContext ctx, ConditionalField field) {
        Object actual;
        try {
            actual = ctx.read(field.whenPath());
        } catch (PathNotFoundException e) {
            actual = null;
        }
        return evaluate(actual, field.op(), field.expected());
    }

    static boolean evaluate(Object actual, String op, Object expected) {
        String normalized = op.toUpperCase(Locale.ROOT);
        switch (normalized) {
            case "EQUALS":
                return eq(actual, expected);
            case "NOT_EQUALS":
                return !eq(actual, expected);
            case "CONTAINS":
                return contains(actual, expected);
            case "NOT_CONTAINS":
                return !contains(actual, expected);
            case "REGEX":
                return actual != null && String.valueOf(actual).matches(String.valueOf(expected));
            case "EXISTS":
                return actual != null;
            case "NOT_EXISTS":
                return actual == null;
            case "GT":
                return cmp(actual, expected) > 0;
            case "LT":
                return cmp(actual, expected) < 0;
            case "GTE":
                return cmp(actual, expected) >= 0;
            case "LTE":
                return cmp(actual, expected) <= 0;
            default:
                throw new IllegalArgumentException("unsupported condition op: " + op);
        }
    }

    private static boolean eq(Object actual, Object expected) {
        if (actual instanceof Number an && expected instanceof Number en) {
            return Double.compare(an.doubleValue(), en.doubleValue()) == 0;
        }
        return actual != null && actual.equals(expected);
    }

    private static boolean contains(Object actual, Object expected) {
        if (actual == null) {
            return false;
        }
        if (actual instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (eq(item, expected)) {
                    return true;
                }
            }
            return false;
        }
        return String.valueOf(actual).contains(String.valueOf(expected));
    }

    private static int cmp(Object actual, Object expected) {
        if (actual instanceof Number an && expected instanceof Number en) {
            return Double.compare(an.doubleValue(), en.doubleValue());
        }
        throw new IllegalArgumentException("GT/LT/GTE/LTE requires numeric operands, got: "
                + (actual == null ? "null" : actual.getClass().getSimpleName()) + " vs "
                + (expected == null ? "null" : expected.getClass().getSimpleName()));
    }
}
