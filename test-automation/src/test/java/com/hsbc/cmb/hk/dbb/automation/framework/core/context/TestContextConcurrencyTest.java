package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
        assertTrue("每个线程应只读到自身写入的 per-thread 上下文，互不串扰: " + results,
                results.stream().allMatch(Boolean::booleanValue));
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

        assertNull("其他线程的 per-thread 上下文不应看到主线程写入的值", seenByOther);
        assertTrue("主线程自身仍读到自己的值", "main".equals(ctx.get(k)));
        TestContextHolder.resetForCurrentThread();
    }

    @Test
    public void resetClearsState() {
        ContextKey<String> key = ContextKey.of("reset-test", String.class);
        TestContext ctx = TestContextHolder.get();
        ctx.set(key, "x");
        TestContextHolder.resetForCurrentThread();
        assertNull("resetForCurrentThread 后应清空状态", ctx.get(key));
    }
}
