package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ConcurrentCaseData} 纯逻辑护盾：不可变、列清洗、分区键提取、相等语义。
 */
public class ConcurrentCaseDataTest {

    @Test
    public void nameUsesEnvAndUsername() {
        ConcurrentCaseData c = new ConcurrentCaseData(Map.of("env", "O63_SIT1", "username", "alice"));
        assertEquals("O63_SIT1/alice", c.name());
    }

    @Test
    public void getTrimsColumnNameAndReturnsValue() {
        ConcurrentCaseData c = new ConcurrentCaseData(Map.of(" env ", "O63_SIT1", "username", "alice"));
        assertEquals("O63_SIT1", c.get("env"));
        assertEquals("O63_SIT1", c.get("  env "));
        assertNull(c.get("missing"));
    }

    @Test
    public void identityPartitionBuiltWhenEnvAndUsernamePresent() {
        ConcurrentCaseData c = new ConcurrentCaseData(Map.of("env", "O63_SIT1", "username", "alice"));
        ConcurrencyPartitionKey key = c.identityPartition();
        assertNotNull(key);
        assertEquals("O63_SIT1", key.dimensions().get("environment"));
        assertEquals("alice", key.dimensions().get("username"));
    }

    @Test
    public void identityPartitionNullWhenUsernameMissing() {
        ConcurrentCaseData c = new ConcurrentCaseData(Map.of("env", "O63_SIT1"));
        assertNull(c.identityPartition());
    }

    @Test
    public void identityPartitionNullWhenEnvBlank() {
        ConcurrentCaseData c = new ConcurrentCaseData(Map.of("env", "  ", "username", "alice"));
        assertNull(c.identityPartition());
    }

    @Test
    public void nullColumnNamesAreDropped() {
        Map<String, String> row = new java.util.LinkedHashMap<>();
        row.put("env", "O63_SIT1");
        row.put(null, "ignored"); // 框架构造期剔除 null 列名
        ConcurrentCaseData c = new ConcurrentCaseData(row);
        assertTrue(c.rawValues().containsKey("env"));
        assertFalse(c.rawValues().containsKey("null"));
    }

    @Test
    public void rawValuesAreImmutable() {
        ConcurrentCaseData c = new ConcurrentCaseData(Map.of("env", "O63_SIT1", "username", "alice"));
        try {
            c.rawValues().put("extra", "x");
            org.junit.Assert.fail("rawValues must be immutable");
        } catch (UnsupportedOperationException expected) {
            // 预期：不可变视图
        }
    }

    @Test
    public void equalityBasedOnImmutableValues() {
        ConcurrentCaseData a = new ConcurrentCaseData(Map.of("env", "O63_SIT1", "username", "alice"));
        ConcurrentCaseData b = new ConcurrentCaseData(Map.of("username", "alice", "env", "O63_SIT1"));
        ConcurrentCaseData c = new ConcurrentCaseData(Map.of("env", "O63_SIT1", "username", "bob"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c));
    }

    @Test
    public void rejectsEmptyRow() {
        try {
            new ConcurrentCaseData(Map.of());
            org.junit.Assert.fail("empty row must be rejected");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            new ConcurrentCaseData(null);
            org.junit.Assert.fail("null row must be rejected");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    @Test
    public void rejectsRowWithOnlyNullColumnNames() {
        Map<String, String> row = new java.util.LinkedHashMap<>();
        row.put(null, "x");
        try {
            new ConcurrentCaseData(row);
            org.junit.Assert.fail("row with only null column names must be rejected");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }
}
