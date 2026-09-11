package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D5-2：{@link ThreadLocalTestContext#computeIfAbsent} 原子性契约测试。
 *
 * <p>锁定的语义（原 get-then-put 实现会同时违反这两条）：
 * <ol>
 *   <li>并发下 supplier <b>至多执行一次</b> —— 惰性单例不会被重复创建；</li>
 *   <li>所有调用方拿到<b>同一个</b>实例 —— 不会出现"后写覆盖先写导致状态静默丢失"。</li>
 * </ol>
 */
public class ThreadLocalTestContextConcurrencyTest {

    private static final ContextKey<Object> SINGLETON_KEY =
            ContextKey.of("d52.singleton", Object.class);

    /** 高并发下：supplier 只执行一次，且所有线程拿到同一实例。 */
    @Test
    public void concurrentComputeIfAbsentComputesOnceAndSharesInstance() throws Exception {
        final int threads = 32;
        ThreadLocalTestContext ctx = new ThreadLocalTestContext();
        AtomicInteger supplierCalls = new AtomicInteger(0);
        CyclicBarrier ready = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.await(10, TimeUnit.SECONDS);   //  尽量同时出发，放大竞态窗口
                    return ctx.computeIfAbsent(SINGLETON_KEY, () -> {
                        supplierCalls.incrementAndGet();
                        //  刻意加宽"计算中"的窗口：
                        //  若实现是 get-then-put，其余线程会在此窗口内同样判定"不存在"并各自计算；
                        //  原子实现则会阻塞等待首个计算结果并复用。
                        //  不加这层延迟时窗口仅微秒级，32 线程仍会错开，导致本用例空转（实测过）。
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return new Object();
                    });
                }));
            }

            Set<Object> seen = Collections.synchronizedSet(new HashSet<>());
            for (Future<Object> f : futures) {
                Object value = f.get(20, TimeUnit.SECONDS);
                assertNotNull(value);
                seen.add(value);
            }

            assertEquals(1, supplierCalls.get(), "并发下 supplier 必须只执行一次（惰性单例不得重复创建）");
            assertEquals(1, seen.size(), "所有调用方必须拿到同一个实例（否则状态更新会静默丢失）");
        } finally {
            pool.shutdownNow();
        }
    }

    /** 已存在值时不再执行 supplier，且返回既有实例。 */
    @Test
    public void existingValueSkipsSupplier() {
        ThreadLocalTestContext ctx = new ThreadLocalTestContext();
        Object existing = new Object();
        ctx.set(SINGLETON_KEY, existing);

        AtomicInteger calls = new AtomicInteger(0);
        Object result = ctx.computeIfAbsent(SINGLETON_KEY, () -> {
            calls.incrementAndGet();
            return new Object();
        });

        assertSame(existing, result, "应返回既有实例");
        assertEquals(0, calls.get(), "已存在时不得执行 supplier");
    }

    /** 顺序重复调用也应复用同一实例（回归防护）。 */
    @Test
    public void sequentialCallsReuseSameInstance() {
        ThreadLocalTestContext ctx = new ThreadLocalTestContext();
        Object first = ctx.computeIfAbsent(SINGLETON_KEY, Object::new);
        Object second = ctx.computeIfAbsent(SINGLETON_KEY, Object::new);
        assertSame(first, second);
        assertTrue(ctx.contains(SINGLETON_KEY));
    }
}
