package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-P0-2 验证：上下文容器可观测 + 防泄漏。
 * 用 {@link TestContextHolder#activeContextCount()} 观测活跃线程上下文数，验证
 * get 登记、resetForCurrentThread 回落、线程池线程登记等行为。
 */
public class ThreadContextRegistryLeakTest {

    @Test
    public void contextCountTracksCurrentThreadAndResets() {
        // 测试隔离：先清空当前线程上下文，保证基线不含主线程既有登记（避免跨用例污染）。
        TestContextHolder.resetForCurrentThread();
        int base = TestContextHolder.activeContextCount();
        ContextKey<String> k = ContextKey.of("leak-track", String.class);
        TestContextHolder.get().set(k, "main");

        assertTrue(
                TestContextHolder.activeContextCount() == base + 1, "主线程登记后活跃计数应 +1");

        TestContextHolder.resetForCurrentThread();
        assertTrue(
                TestContextHolder.activeContextCount() == base, "resetForCurrentThread 后计数应回落至基线");
    }

    @Test
    public void threadPoolThreadsAreCounted() throws Exception {
        int base = TestContextHolder.activeContextCount();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                TestContextHolder.get().set(
                        ContextKey.of("t" + Thread.currentThread().getId(), String.class), "v");
                return null;
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        int afterPool = TestContextHolder.activeContextCount();
        assertTrue(
                afterPool >= base + threads, "线程池线程应已登记上下文（base=" + base + ", after=" + afterPool + "）");
    }
}
