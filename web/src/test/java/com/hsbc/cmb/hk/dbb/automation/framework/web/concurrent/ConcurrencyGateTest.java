package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WEB-P1-5 种子测试：SSO 感知并发闸门（无浏览器，纯逻辑）。
 *
 * <p>注意：{@link ConcurrencyGate} 的 {@code GATES} 为静态 Map，{@code release} 不移除条目（仅惰性淘汰），
 * 故 {@code stats().activeGates} 为「累计不同身份键数」。测试以「基线增量」方式断言，避免跨用例静态态串扰。
 */
public class ConcurrencyGateTest {

    private static final String ENABLED = WebFrameworkConfig.CONCURRENCY_PARTITION_ENABLED.getKey();
    private static final String PERMITS = WebFrameworkConfig.CONCURRENCY_PARTITION_PER_KEY_PERMITS.getKey();

    @AfterEach
    public void tearDown() {
        System.clearProperty(ENABLED);
        System.clearProperty(PERMITS);
    }

    @Test
    public void disabledByDefault_noNewGateCreated() {
        System.clearProperty(ENABLED);
        assertFalse(ConcurrencyGate.isEnabled());
        int baseline = ConcurrencyGate.stats().activeGates;
        ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(dim("env", "sit1", "user", "alice-noop"));
        ConcurrencyGate.acquire(key);
        ConcurrencyGate.release(key);
        // 禁用时 acquire/release 为 no-op，不应向 GATES 写入任何闸门
        assertEquals( baseline,  ConcurrencyGate.stats().activeGates, "禁用时不得创建闸门");
    }

    @Test
    public void nullKey_noNewGateCreated_evenWhenEnabled() {
        System.setProperty(ENABLED, "true");
        assertTrue(ConcurrencyGate.isEnabled());
        int baseline = ConcurrencyGate.stats().activeGates;
        ConcurrencyGate.acquire(null);
        ConcurrencyGate.release(null);
        assertEquals( baseline,  ConcurrencyGate.stats().activeGates, "null key 不得创建闸门");
    }

    @Test
    public void enabled_createsOneGatePerUniqueKey() {
        System.setProperty(ENABLED, "true");
        System.setProperty(PERMITS, "1");
        int baseline = ConcurrencyGate.stats().activeGates;
        ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(dim("env", "sit1", "user", "alice-enabled"));
        ConcurrencyGate.acquire(key);
        assertEquals( baseline + 1,  ConcurrencyGate.stats().activeGates, "启用后应为该 key 创建一道闸门");
        ConcurrencyGate.release(key);
        // release 不移除条目（仅惰性淘汰），条目保留
        assertEquals(baseline + 1, ConcurrencyGate.stats().activeGates);
    }

    @Test
    public void differentIdentities_useDistinctGates() {
        System.setProperty(ENABLED, "true");
        int baseline = ConcurrencyGate.stats().activeGates;
        ConcurrencyPartitionKey alice = ConcurrencyPartitionKey.of(dim("env", "sit1", "user", "alice-distinct"));
        ConcurrencyPartitionKey bob = ConcurrencyPartitionKey.of(dim("env", "sit1", "user", "bob-distinct"));
        ConcurrencyGate.acquire(alice);
        ConcurrencyGate.acquire(bob);
        assertEquals( baseline + 2,  ConcurrencyGate.stats().activeGates, "不同身份应使用不同闸门");
        ConcurrencyGate.release(alice);
        ConcurrencyGate.release(bob);
    }

    private static Map<String, String> dim(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
