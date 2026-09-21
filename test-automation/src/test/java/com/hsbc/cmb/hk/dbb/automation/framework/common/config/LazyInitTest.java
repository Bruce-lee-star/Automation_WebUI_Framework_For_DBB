package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import com.hsbc.cmb.hk.dbb.automation.framework.common.async.AsyncPool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-2 回归：把「配置失败快」从 static 初始化块移到<b>首次使用</b>。
 *
 * <p><b>原失效形态</b>：{@code AsyncPool} 在 {@code static {}} 里读配置（经 {@code ConfigSource} →
 * {@code SecretValue} 解密失败即抛）。JVM 会把 static 块异常包装为 {@link ExceptionInInitializerError}，
 * 且该类在同一 JVM 内<b>永久不可用</b>（后续访问直接 {@code NoClassDefFoundError}）——
 * 错误形态难懂，且失败后连"改配置重试"都不可能。</p>
 *
 * <p>本测试两层固化：① {@link LazyInit} 的语义（只初始化一次 / 失败抛清晰异常且不重试 /
 * {@code Error} 原样抛出 / 并发下只初始化一次）；② {@code AsyncPool} 的接线是「惰性」结构
 * —— 池字段<b>非 final</b>（final 只可能由 static 块赋值，那正是要消除的形态）。</p>
 */
public class LazyInitTest {

    @Test
    public void initializerRunsExactlyOnce() {
        AtomicInteger runs = new AtomicInteger();
        LazyInit init = new LazyInit("Demo", runs::incrementAndGet);

        init.ensure();
        init.ensure();
        init.ensure();

        assertEquals(1, runs.get(), "初始化必须只执行一次（幂等）");
        assertTrue(init.isInitialized(), "成功后 isInitialized 应为 true");
    }

    @Test
    public void failureSurfacesAsClearIllegalStateExceptionNotExceptionInInitializerError() {
        IllegalStateException rootCause = new IllegalStateException("配置值解密失败（主密钥缺失或与密文不匹配）");
        LazyInit init = new LazyInit("AsyncPool", () -> {
            throw rootCause;
        });

        IllegalStateException thrown = assertThrows(IllegalStateException.class, init::ensure);

        //  P2-2：无需再断言「不是 ExceptionInInitializerError」—— 二者无继承关系，
        //  `thrown instanceof ExceptionInInitializerError` 会被 javac 判为不可转换类型（编译期即证）。
        assertTrue(thrown.getMessage().contains("AsyncPool"),
                "错误消息应含组件名以便定位，实际：" + thrown.getMessage());
        assertSame(rootCause, thrown.getCause(), "原始 cause 必须保留（否则丢失根因）");
        assertFalse(init.isInitialized(), "失败后不得视为已初始化");
    }

    @Test
    public void failedInitializationIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        LazyInit init = new LazyInit("Demo", () -> {
            attempts.incrementAndGet();
            throw new RuntimeException("boom");
        });

        assertThrows(IllegalStateException.class, init::ensure);
        assertThrows(IllegalStateException.class, init::ensure);

        assertEquals(1, attempts.get(),
                "失败不重试：避免失败路径被高频调用反复触发（失败原因通常是配置本身，不会自愈）");
    }

    @Test
    public void errorIsPropagatedAsIs() {
        Error fatal = new Error("simulated fatal error");
        LazyInit init = new LazyInit("Demo", () -> {
            throw fatal;
        });

        Error thrown = assertThrows(Error.class, init::ensure);

        assertSame(fatal, thrown, "Error（如 OOM）应原样抛出，不做 IllegalStateException 包装");
    }

    @Test
    public void concurrentEnsureInitializesOnce() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        LazyInit init = new LazyInit("Concurrent", () -> {
            runs.incrementAndGet();
            Thread.yield();
        });
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        init.ensure();
                    } catch (Throwable t) {
                        synchronized (errors) {
                            errors.add(t);
                        }
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "并发探测线程应正常结束");
        } finally {
            pool.shutdownNow();
        }

        assertTrue(errors.isEmpty(), "并发 ensure 不应抛异常：" + errors);
        assertEquals(1, runs.get(), "双重检查锁下并发 ensure 只能初始化一次");
    }

    /**
     * 接线结构断言：池字段必须<b>非 final</b>。
     *
     * <p>{@code final} 静态字段只能由声明或 static 块赋值 —— 而「在 static 块里读配置 + 建池」
     * 正是 P2-2 要消除的形态（配置失败即 {@code ExceptionInInitializerError} + 类永久不可用）。
     * 故这里以 final 性作为「是否已惰性化」的结构代理：任何回退都会让本断言变红。</p>
     */
    @Test
    public void asyncPoolFieldsAreNotClassInitAssigned() throws Exception {
        for (String name : new String[]{"POOL", "SCHEDULER"}) {
            Field f = AsyncPool.class.getDeclaredField(name);
            assertFalse(Modifier.isFinal(f.getModifiers()),
                    "P2-2：" + name + " 不得为 final —— final 意味着它在 static 块里赋值，"
                            + "配置失败会变成 ExceptionInInitializerError 且类永久不可用");
        }
        // 访问类本身不得触发任何配置读取（惰性化后类初始化是纯声明）
        assertNotNull(AsyncPool.class.getName());

        // 端到端仍可用：首次真实使用触发惰性初始化，且重复使用不重建
        AsyncPool.run(() -> { });
        String snapshot = AsyncPool.getStatusSnapshot();
        assertTrue(snapshot.contains("pool="), "首次使用后应能取到池状态（惰性初始化已生效）：" + snapshot);
        AsyncPool.run(() -> { });
        assertTrue(AsyncPool.getStatusSnapshot().contains("pool="), "重复使用不得破坏已初始化的池");
    }
}
