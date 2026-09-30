package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * JsonPathAssertor 断言语义：值类型自动推断（Number 数值比较）、路径不存在、
 * 失败明细格式、null 比较。
 */
public class JsonPathAssertorTest {

    private static final String BODY = "{\"code\":0,\"total\":3.5,\"ok\":true,\"name\":\"t\",\"nested\":{\"v\":2}}";

    @Test
    public void allPassWhenValuesMatch() {
        List<String> failures = JsonPathAssertor.assertAll(Map.of(
                "$.code", 0,
                "$.total", 3.5,
                "$.ok", true,
                "$.name", "t"), BODY);
        assertTrue(failures.isEmpty());
    }

    @Test
    public void numericComparisonIgnoresIntDoubleBoundary() {
        // 期望 2（int）实际 2（int，来自嵌套）→ 通过
        List<String> failures = JsonPathAssertor.assertAll(Map.of("$.nested.v", 2), BODY);
        assertTrue( "int 2 == int 2 必须通过", failures.isEmpty());
        // 期望 2.0（double）实际 2（int）→ 数值比较通过
        failures = JsonPathAssertor.assertAll(Map.of("$.nested.v", 2.0), BODY);
        assertTrue( "2.0 == 2 数值比较必须通过", failures.isEmpty());
    }

    @Test
    public void mismatchProducesDetail() {
        List<String> failures = JsonPathAssertor.assertAll(Map.of("$.code", 1), BODY);
        assertEquals((long) 1, (long) failures.size());
        assertTrue( "失败明细必须含路径", failures.get(0).contains("$.code"));
        assertTrue( "失败明细必须含期望值", failures.get(0).contains("expected=1"));
        assertTrue( "失败明细必须含实际值", failures.get(0).contains("actual=0"));
    }

    @Test
    public void missingPathIsFailure() {
        List<String> failures = JsonPathAssertor.assertAll(Map.of("$.nope", "x"), BODY);
        assertEquals((long) 1, (long) failures.size());
        assertTrue(failures.get(0).contains("not found"));
    }

    @Test
    public void nullExpectedAndActualNull() {
        java.util.Map<String, Object> nullField = new java.util.HashMap<>();
        nullField.put("$.nullField", null);
        assertTrue(JsonPathAssertor.assertAll(nullField, "{\"nullField\":null}").isEmpty());
        java.util.Map<String, Object> codeNull = new java.util.HashMap<>();
        codeNull.put("$.code", null);
        List<String> failures = JsonPathAssertor.assertAll(codeNull, BODY);
        assertEquals("实际 0 期望 null 必须失败", (long) 1, (long) failures.size());
    }
}
