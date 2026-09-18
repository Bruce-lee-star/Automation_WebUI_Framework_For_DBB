package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WEB-P1-5 种子测试：SSO 感知并发闸门（无浏览器，纯逻辑）。
 *
 * <p><b>条目生命周期语义（2026-09-17 评审修复后）</b>：闸门条目在其<b>最后一个持有者</b> {@code release}
 * 时即被移除，故 {@code stats().activeGates} 表示「<b>在途</b>身份数」，正常收尾后应回落到基线。
 * 原实现在 {@code release} 中不移除条目、依赖 {@code MAX_GATES=4096} + 「按许可余量判空闲」的淘汰，
 * 该判据在 {@code computeIfAbsent → acquire} 窗口内会把即将取用的闸门误判为空闲（串行化破裂），
 * 且淘汰后 {@code release(key)} 会把许可错记到新条目上。修复与论证见 {@code GateEntry} 的类注释。
 *
 * <p>测试以「基线增量」方式断言，避免跨用例静态态串扰。
 */
public class ConcurrencyGateTest {

    private static final String ENABLED = WebFrameworkConfig.CONCURRENCY_PARTITION_ENABLED.getKey();
    private static final String PERMITS = WebFrameworkConfig.CONCURRENCY_PARTITION_PER_KEY_PERMITS.getKey();

    @AfterEach
    public void tearDown() {
        System.clearProperty(ENABLED);
        System.clearProperty(PERMITS);
        System.clearProperty(CUCUMBER_PARALLEL);
        System.clearProperty(JUNIT_PARALLEL);
    }

    /** 引擎级并行开关（auto 判据）。 */
    private static final String CUCUMBER_PARALLEL = "cucumber.execution.parallel.enabled";
    private static final String JUNIT_PARALLEL = "junit.jupiter.execution.parallel.enabled";

    /**
     * {@code auto}（默认三态）：<b>并行开启即自动启用、串行时 no-op、显式 false 为逃生舱</b>。
     *
     * <p>这是「同一 sessionKey 串行、不同 sessionKey 并行」成为<b>并行默认语义</b>的基础：
     * 若默认恒 off，并行下同身份会并发共用会话（SSO 互踢）——即用户反馈的缺陷。
     */
    @Test
    public void auto_enabledIffEngineParallelEnabled_explicitFalseWins() {
        System.clearProperty(ENABLED);
        assertFalse(ConcurrencyGate.isEnabled(), "串行运行（auto）不应启用闸门");

        System.setProperty(CUCUMBER_PARALLEL, "true");
        assertTrue(ConcurrencyGate.isEnabled(), "引擎级并行开启时 auto 应自动启用");
        System.clearProperty(CUCUMBER_PARALLEL);

        System.setProperty(JUNIT_PARALLEL, "true");
        assertTrue(ConcurrencyGate.isEnabled(), "JUnit5 并行开启时 auto 也应自动启用");
        System.clearProperty(JUNIT_PARALLEL);

        System.setProperty(ENABLED, "false");
        System.setProperty(CUCUMBER_PARALLEL, "true");
        assertFalse(ConcurrencyGate.isEnabled(), "显式 false 必须压过 auto（逃生舱）");
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
        // 条目随最后一个持有者释放即移除（Map 大小 == 在途身份数）
        assertEquals(baseline, ConcurrencyGate.stats().activeGates, "最后一个持有者释放后条目应被移除");
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
        assertEquals(baseline, ConcurrencyGate.stats().activeGates, "释放后不应残留条目");
    }

    /** 条目仅在持有期间存在：acquire 后出现、release 后消失（回归守卫：旧实现永不移除）。 */
    @Test
    public void entryExistsOnlyWhileHeld() {
        System.setProperty(ENABLED, "true");
        int baseline = ConcurrencyGate.stats().activeGates;
        ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(dim("env", "sit1", "user", "held-once"));
        ConcurrencyGate.acquire(key);
        assertEquals(baseline + 1, ConcurrencyGate.stats().activeGates, "持有时应存在条目");
        ConcurrencyGate.release(key);
        assertEquals(baseline, ConcurrencyGate.stats().activeGates, "最后一个持有者释放后条目应被移除");
    }

    /** 顺序使用海量身份后不残留：Map 天然有界（不再依赖 4096 上限 + 惰性淘汰兜底）。 */
    @Test
    public void manySequentialIdentities_doNotAccumulate() {
        System.setProperty(ENABLED, "true");
        int baseline = ConcurrencyGate.stats().activeGates;
        for (int i = 0; i < 5000; i++) {
            ConcurrencyPartitionKey k = ConcurrencyPartitionKey.of(dim("env", "sit1", "user", "seq-" + i));
            ConcurrencyGate.acquire(k);
            ConcurrencyGate.release(k);
        }
        assertEquals(baseline, ConcurrencyGate.stats().activeGates,
                "顺序使用 5000 个身份后应无残留条目（旧实现会残留累计条目）");
    }

    /**
     * 关键不变量：某身份被持有期间，另一线程对<b>同一</b>身份必须被阻塞 —— 即便同时有海量其它身份
     * 在 churn（旧实现的淘汰机制正是被该 churn 触发，可能把"将被取用"的闸门误判为空闲而破坏串行化）。
     */
    @Test
    public void heldGateSurvivesChurn_andStillSerializes() throws Exception {
        System.setProperty(ENABLED, "true");
        System.setProperty(PERMITS, "1");
        ConcurrencyPartitionKey held = ConcurrencyPartitionKey.of(dim("env", "sit1", "user", "held-identity"));
        ConcurrencyGate.acquire(held);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> blocked = pool.submit(() -> {
                ConcurrencyGate.acquire(held);
                ConcurrencyGate.release(held);
            });

            // churn：制造大量其它身份，反复取用/释放（旧实现在此路径上执行淘汰）
            for (int i = 0; i < 5000; i++) {
                ConcurrencyPartitionKey other =
                        ConcurrencyPartitionKey.of(dim("env", "sit1", "user", "churn-" + i));
                ConcurrencyGate.acquire(other);
                ConcurrencyGate.release(other);
            }

            assertThrows(TimeoutException.class, () -> blocked.get(300, TimeUnit.MILLISECONDS),
                    "同一身份被持有时，另一线程必须被阻塞（churn 不得破坏串行化）");

            ConcurrencyGate.release(held);
            blocked.get(5, TimeUnit.SECONDS); // 释放后应立即可进入
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, String> dim(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
