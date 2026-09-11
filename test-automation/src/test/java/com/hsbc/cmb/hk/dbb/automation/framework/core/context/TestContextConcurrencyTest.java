package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3-1 基础设施验证：{@link TestContextHolder} 以 per-thread 方式持有 {@link TestContext}，
 * 为收敛散落的 {@code static ThreadLocal} 提供集中接入点。本测试证明 per-thread 隔离正确。
 */
public class TestContextConcurrencyTest {

    @Test
    public void perThreadContextIsIsolatedAcrossThreads() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ContextKey<String> sharedKey = ContextKey.of("shared", String.class);
        List<Boolean> results = Collections.synchronizedList(new ArrayList<>());

        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int id = i;
            futures.add(pool.submit(() -> {
                start.await();
                TestContext ctx = TestContextHolder.get();
                ctx.set(sharedKey, "v" + id);
                Thread.yield();
                Thread.sleep(5);
                boolean ok = ("v" + id).equals(ctx.get(sharedKey));
                TestContextHolder.resetForCurrentThread();
                return ok;
            }));
        }
        start.countDown();
        for (Future<Boolean> f : futures) {
            results.add(f.get(5, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertTrue(
                results.stream().allMatch(Boolean::booleanValue), "每个线程应只读到自身写入的 per-thread 上下文，互不串扰: " + results);
    }

    @Test
    public void crossThreadIsolation() throws Exception {
        ContextKey<String> k = ContextKey.of("iso", String.class);
        TestContext ctx = TestContextHolder.get();
        ctx.set(k, "main");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        String seenByOther = pool.submit(() -> {
            TestContext otherCtx = TestContextHolder.get();
            return otherCtx.get(k);
        }).get(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertNull( seenByOther, "其他线程的 per-thread 上下文不应看到主线程写入的值");
        assertTrue( "main".equals(ctx.get(k)), "主线程自身仍读到自己的值");
        TestContextHolder.resetForCurrentThread();
    }

    @Test
    public void resetClearsState() {
        ContextKey<String> key = ContextKey.of("reset-test", String.class);
        TestContext ctx = TestContextHolder.get();
        ctx.set(key, "x");
        TestContextHolder.resetForCurrentThread();
        assertNull( ctx.get(key), "resetForCurrentThread 后应清空状态");
    }

    @Test
    public void threadPoolReuseDoesNotLeakBetweenScenarios() throws Exception {
        ContextKey<String> k = ContextKey.of("scenario", String.class);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int round = 0; round < 3; round++) {
            final String val = "scenario-" + round;
            Future<Boolean> f = pool.submit(() -> {
                TestContext ctx = TestContextHolder.get();
                ctx.set(k, val);
                Thread.yield();
                // 本 scenario 读到自身值；reset 后本线程上下文应清空，避免被下一 scenario 复用读到
                boolean isolated = val.equals(ctx.get(k));
                TestContextHolder.resetForCurrentThread();
                boolean cleared = ctx.get(k) == null;
                return isolated && cleared;
            });
            assertTrue( f.get(5, TimeUnit.SECONDS), "scenario " + round + " 间不应串扰（线程池复用安全）");
        }
        pool.shutdown();
    }
}
