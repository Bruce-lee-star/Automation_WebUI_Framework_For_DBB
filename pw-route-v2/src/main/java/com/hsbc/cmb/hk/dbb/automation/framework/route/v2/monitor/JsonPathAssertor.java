package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.monitor;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSONPath 断言器 —— MONITOR 能力的响应体断言（IO 线程执行）。
 *
 * <p>语义对齐现有 {@code MonitorApiDsl.expectJsonPath}：值为任意 {@code Object}，
 * 断言时做「值类型自动推断」比较：
 * <ul>
 *   <li>期望与实际均为 {@link Number} → 按 double 数值比较（{@code 2 == 2.0} 通过）；</li>
 *   <li>其余按 {@code equals} 精确比较；</li>
 *   <li>路径不存在（{@link PathNotFoundException}）→ 记为失败明细。</li>
 * </ul>
 *
 * <p>纯函数、无状态、无 IO —— 可安全并发调用。
 */
public final class JsonPathAssertor {

    private JsonPathAssertor() {
    }

    /**
     * 对响应体执行全部 JSONPath 断言。
     *
     * @return 失败明细列表；空列表 = 全部通过。明细格式：
     *         {@code path '<jsonPath>' expected=<expected> actual=<actual>} 或
     *         {@code path '<jsonPath>' not found}
     */
    public static List<String> assertAll(Map<String, Object> assertions, String body) {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Object> e : assertions.entrySet()) {
            Object actual;
            try {
                actual = JsonPath.read(body, e.getKey());
            } catch (PathNotFoundException ex) {
                failures.add("path '" + e.getKey() + "' not found");
                continue;
            } catch (Exception ex) {
                failures.add("path '" + e.getKey() + "' error: " + ex.getMessage());
                continue;
            }
            if (!valueEquals(actual, e.getValue())) {
                failures.add("path '" + e.getKey() + "' expected=" + render(e.getValue())
                        + " actual=" + render(actual));
            }
        }
        return failures;
    }

    /** 值比较：Number 按数值，其余 equals。 */
    static boolean valueEquals(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        if (expected instanceof Number && actual instanceof Number) {
            return Double.compare(((Number) expected).doubleValue(), ((Number) actual).doubleValue()) == 0;
        }
        return actual.equals(expected);
    }

    private static String render(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "'" + value + "'";
        }
        return String.valueOf(value);
    }
}
