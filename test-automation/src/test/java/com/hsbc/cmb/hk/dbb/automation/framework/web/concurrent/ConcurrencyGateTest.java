package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link ConcurrencyGate} 单测（固化设计文档附录 A.8）：
 * ① 同 key 两线程互斥；② release 后另一线程获得；③ key==null 直接放行、不进 Map；
 * ④ 不同 key 互不阻塞（并行）；⑤ key 等价性（顺序 / 空白无关）；⑥ TagOverride 覆盖自动推导；
 * ⑦ 解析器链回退语义；⑧ per-key permits=N 时允许 N 路并发。
 *
 * <p>经系统属性临时启用闸门（默认关闭，生产行为零回归）。</p>
 */
public class ConcurrencyGateTest {

    @BeforeClass
    public static void enableGate() {
        System.setProperty("serenity.playwright.concurrent.partition.enabled", "true");
    }

    @AfterClass
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

    @Test
    public void nullKeyIsNoOpAndDoesNotThrow() {
        assertTrue("闸门应经 @BeforeClass 启用", ConcurrencyGate.isEnabled());
        ConcurrencyGate.acquire(null);
        ConcurrencyGate.release(null);
    }

    @Test
    public void sameKeyMutualExclusion() throws Exception {
        ConcurrencyPartitionKey k = key("SIT1", "alice");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
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
            ConcurrencyGate.acquire(k); // 应阻塞直到 t1 释放
            secondAcquired.set(true);
            ConcurrencyGate.release(k);
        });
        t1.start();
        t2.start();
        assertTrue("t1 应已持锁", held.await(3, TimeUnit.SECONDS));
        sleep(300); // 给 t2 机会去 acquire 并阻塞
        assertFalse("t2 在 t1 释放前应被阻塞", secondAcquired.get());
        proceed.countDown();
        t1.join(3_000);
        t2.join(3_000);
        assertTrue("t2 应在 t1 释放后获得锁", secondAcquired.get());
        assertTrue("应记录到一次串行化", ConcurrencyGate.stats().serializedIdentityCount >= 1);
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
    public void tagOverrideResolverParsesSso() {
        TagOverrideKeyResolver r = TagOverrideKeyResolver.fromTags(Map.of("sso", "UAT:bob"));
        Optional<ConcurrencyPartitionKey> k = r.resolve();
        assertTrue(k.isPresent());
        assertEquals("UAT", k.get().dimensions().get("environment"));
        assertEquals("bob", k.get().dimensions().get("username"));
    }

    @Test
    public void tagOverrideResolverParsesConcurrencyKey() {
        TagOverrideKeyResolver r = TagOverrideKeyResolver.fromTags(
                Map.of("concurrencyKey", "environment=SIT1;username=alice;tenant=t1"));
        Optional<ConcurrencyPartitionKey> k = r.resolve();
        assertTrue(k.isPresent());
        assertEquals("SIT1", k.get().dimensions().get("environment"));
        assertEquals("alice", k.get().dimensions().get("username"));
        assertEquals("t1", k.get().dimensions().get("tenant"));
    }

    @Test
    public void resolverChainFallsBackToLoginIdentity() {
        ConcurrencyIdentity.publish(Map.of("environment", "SIT1", "username", "alice"));
        try {
            ConcurrencyKeyResolver chain = ConcurrencyKeyResolvers.chain(
                    TagOverrideKeyResolver.defaultSource(),  // 无 tag → empty
                    LoginIdentityKeyResolver.defaultSource());
            Optional<ConcurrencyPartitionKey> k = chain.resolve();
            assertTrue(k.isPresent());
            assertEquals("alice", k.get().dimensions().get("username"));
        } finally {
            ConcurrencyIdentity.clear();
        }
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
            assertTrue("两路应同时获得（permits=2）", bothAcquired.await(3, TimeUnit.SECONDS));
            t1.join(3_000);
            t2.join(3_000);
            assertTrue(t1done.get());
            assertTrue(t2done.get());
        } finally {
            System.clearProperty("serenity.playwright.concurrent.partition.per.key.permits");
        }
    }
}
