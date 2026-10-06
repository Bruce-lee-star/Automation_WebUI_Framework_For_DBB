package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * WEB-P1-5 种子测试：不可变并发分区键（无浏览器）。
 * 覆盖入参守卫、维度规范化（名小写去空白 / 值仅去空白保留大小写 / 空值维度剔除）、
 * 维度顺序无关的等价性与 hashCode、指纹与不可变性。
 */
public class ConcurrencyPartitionKeyTest {

    private static Map<String, String> dims(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    public void of_nullMap_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> ConcurrencyPartitionKey.of(null));
    }

    @Test
    public void of_emptyMap_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> ConcurrencyPartitionKey.of(Map.of()));
    }

    @Test
    public void of_allBlankValues_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> ConcurrencyPartitionKey.of(dims("env", "   ", "user", "")));
    }

    @Test
    public void of_nullDimensionName_throwsIllegalArgumentException() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(null, "sit1");
        assertThrows(IllegalArgumentException.class, () -> ConcurrencyPartitionKey.of(m));
    }

    @Test
    public void of_blankDimensionName_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> ConcurrencyPartitionKey.of(dims("   ", "sit1")));
    }

    @Test
    public void of_normalizesDimensionNamesAndTrimsValues() {
        ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(dims("  ENV  ", "  sit1  "));

        assertEquals("sit1", key.dimensions().get("env"));
        assertTrue("维度名应规范化为小写", key.dimensions().containsKey("env"));
    }

    @Test
    public void of_skipsBlankValuedDimensions() {
        ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(dims("env", "sit1", "tenant", "   "));

        assertEquals("空值维度不参与指纹", 1, key.dimensions().size());
        assertEquals("env=sit1", key.fingerprint());
    }

    @Test
    public void valueCase_isPreservedForCaseSensitiveIdentity() {
        ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(dims("username", "Alice"));

        assertEquals("IdP 用户名大小写敏感，值不得被规范化", "Alice", key.dimensions().get("username"));
    }

    @Test
    public void equalsAndHashCode_areOrderIndependent() {
        ConcurrencyPartitionKey a = ConcurrencyPartitionKey.of(dims("env", "sit1", "username", "alice"));
        ConcurrencyPartitionKey b = ConcurrencyPartitionKey.of(dims("username", "alice", "env", "sit1"));

        assertEquals("维度顺序无关：相同身份应映射到同一分区键", a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void equals_distinguishesDifferentIdentity() {
        ConcurrencyPartitionKey a = ConcurrencyPartitionKey.of(dims("username", "alice"));
        ConcurrencyPartitionKey b = ConcurrencyPartitionKey.of(dims("username", "bob"));

        assertNotEquals(a, b);
    }

    @Test
    public void fingerprint_sortsDimensionsAndIsDeterministic() {
        ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(dims("username", "alice", "env", "sit1"));

        assertEquals("env=sit1|username=alice", key.fingerprint());
        assertTrue(key.toString().contains("env=sit1|username=alice"));
    }

    @Test
    public void dimensions_isUnmodifiable() {
        ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(dims("env", "sit1"));

        assertThrows(UnsupportedOperationException.class, () -> key.dimensions().put("x", "y"));
    }
}
