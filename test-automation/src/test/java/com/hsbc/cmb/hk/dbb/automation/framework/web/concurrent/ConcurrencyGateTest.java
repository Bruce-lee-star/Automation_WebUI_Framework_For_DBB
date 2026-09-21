package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConcurrencyGate} 单测（固化设计文档附录 A.8）：
 * ① 同 key 两线程互斥；② release 后另一线程获得；③ key==null 直接放行、不进 Map；
 * ④ 不同 key 互不阻塞（并行）；⑤ key 等价性（顺序 / 空白无关）；⑥ per-key permits=N 时允许 N 路并发。
 *
 * <p><b>2026-09-21 收口（评审 F-11）</b>：原「TagOverride 覆盖 + 登录身份自动推导」解析链无任何生产
 * 接入点（{@code ConcurrencyIdentity} 从未被 publish → 恒空转），属"看似生效、实则空转"的误导性 seam，
 * 已整体删除：身份键一律由调用方<b>显式构造</b> {@link ConcurrencyPartitionKey}。</p>
 *
 * <p>经系统属性临时启用闸门（默认关闭，生产行为零回归）。</p>
 */
public class ConcurrencyGateTest {

    @BeforeAll
    public static void enableGate() {
        System.setProperty("serenity.playwright.concurrent.partition.enabled", "true");
    }

    @AfterAll
    public static void restore() {
        System.clearProperty("serenity.playwright.concurrent.partition.enabled");
        System.clearProperty("serenity.playwright.concurrent.partition.per.key.permits");
    }

    private static ConcurrencyPartitionKey key(String env, String user) {
        return ConcurrencyPartitionKey.of(Map.of("environment", env, "username", user));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** 「不可进入」的观测窗口：在等待方已就绪后，持续观察其是否违规进入临界区。 */
    private static final long NON_ENTRY_OBSERVE_MS = 300L;

    /** 有界轮询等待标志变真（超时返回当前值；上界仅防挂死，不作为通过判据）。 */
    private static boolean awaitTrue(AtomicBoolean flag, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!flag.get()) {
            if (System.nanoTime() - deadline >= 0) {
                return flag.get();
            }
            Thread.sleep(5);
        }
        return true;
    }

    @Test
    public void nullKeyIsNoOpAndDoesNotThrow() {
        assertTrue( ConcurrencyGate.isEnabled(), "闸门应经 @BeforeAll 启用");
        ConcurrencyGate.acquire(null);
        ConcurrencyGate.release(null);
    }

    @Test
    public void sameKeyMutualExclusion() throws Exception {
        ConcurrencyPartitionKey k = key("SIT1", "alice");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        CountDownLatch t2Ready = new CountDownLatch(1);
        AtomicBoolean secondAcquired = new AtomicBoolean(false);

        Thread t1 = new Thread(() -> {
            ConcurrencyGate.acquire(k);
            held.countDown();
            try {
                proceed.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ConcurrencyGate.release(k);
        });
        Thread t2 = new Thread(() -> {
            try {
                held.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            t2Ready.countDown();            // 即将尝试进入临界区（使观测窗口有意义）
            ConcurrencyGate.acquire(k);     // 应阻塞直到 t1 释放
            secondAcquired.set(true);
            ConcurrencyGate.release(k);
        });
        t1.start();
        t2.start();
        assertTrue( held.await(3, TimeUnit.SECONDS), "t1 应已持锁");
        assertTrue( t2Ready.await(3, TimeUnit.SECONDS), "t2 应在超时前就绪");
        assertFalse( awaitTrue(secondAcquired, NON_ENTRY_OBSERVE_MS),
                "t2 在 t1 释放前应被阻塞（不得违规进入）");
        proceed.countDown();
        t1.join(3_000);
        t2.join(3_000);
        assertTrue( secondAcquired.get(), "t2 应在 t1 释放后获得锁");
        assertTrue( ConcurrencyGate.stats().serializedIdentityCount >= 1, "应记录到一次串行化");
    }

    @Test
    public void differentKeysRunInParallel() {
        ConcurrencyPartitionKey a = key("SIT1", "alice");
        ConcurrencyPartitionKey b = key("SIT1", "bob");
        try (ConcurrencyScope s1 = ConcurrencyGate.enter(a);
             ConcurrencyScope s2 = ConcurrencyGate.enter(b)) {
            assertTrue(ConcurrencyGate.isEnabled());
        }
    }

    @Test
    public void keyEquivalenceIsOrderAndWhitespaceInsensitive() {
        ConcurrencyPartitionKey k1 = ConcurrencyPartitionKey.of(Map.of("environment", "SIT1", "username", "alice"));
        ConcurrencyPartitionKey k2 = ConcurrencyPartitionKey.of(Map.of("username", "alice", "environment", "SIT1"));
        ConcurrencyPartitionKey k3 = ConcurrencyPartitionKey.of(Map.of("environment", " SIT1 ", "username", "alice"));
        assertEquals(k1, k2);
        assertEquals(k1, k3);
        assertEquals(k1.hashCode(), k2.hashCode());
        assertEquals(k1.fingerprint(), k3.fingerprint());
    }

    @Test
    public void usernameCaseIsPreserved() {
        ConcurrencyPartitionKey k1 = ConcurrencyPartitionKey.of(Map.of("environment", "SIT1", "username", "Alice"));
        ConcurrencyPartitionKey k2 = ConcurrencyPartitionKey.of(Map.of("environment", "SIT1", "username", "alice"));
        assertNotEquals(k1, k2);
    }

    @Test
    public void perKeyPermitsAllowsNConcurrent() throws Exception {
        System.setProperty("serenity.playwright.concurrent.partition.per.key.permits", "2");
        try {
            ConcurrencyPartitionKey k = key("PERMIT", "n2");
            CountDownLatch bothAcquired = new CountDownLatch(2);
            AtomicBoolean t1done = new AtomicBoolean();
            AtomicBoolean t2done = new AtomicBoolean();
            Thread t1 = new Thread(() -> {
                ConcurrencyGate.acquire(k);
                bothAcquired.countDown();
                sleep(300);
                ConcurrencyGate.release(k);
                t1done.set(true);
            });
            Thread t2 = new Thread(() -> {
                ConcurrencyGate.acquire(k);
                bothAcquired.countDown();
                sleep(300);
                ConcurrencyGate.release(k);
                t2done.set(true);
            });
            t1.start();
            t2.start();
            assertTrue( bothAcquired.await(3, TimeUnit.SECONDS), "两路应同时获得（permits=2）");
            t1.join(3_000);
            t2.join(3_000);
            assertTrue(t1done.get());
            assertTrue(t2done.get());
        } finally {
            System.clearProperty("serenity.playwright.concurrent.partition.per.key.permits");
        }
    }
}
