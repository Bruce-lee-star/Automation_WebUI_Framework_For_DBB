package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 专项 19（会话缓存并发治理）Phase 1 表征测试：锁定 {@link SessionManager} 的
 * per-sessionKey 共享层（文件 + {@code META_CACHE}）在并发下的契约。
 *
 * <p>覆盖 §4.5（doc18）识别的唯一残叉并发风险——既往测试仅覆盖单线程缓存/淘汰
 * （{@code SessionManagerCacheTest}）与 per-thread Feature 标记隔离
 * （{@code SessionManagerConcurrencyTest}），per-sessionKey 共享层并发从未被表征。
 *
 * <p>纯文件 / 缓存层，不依赖 Playwright / 浏览器：复用 {@code SessionManagerCacheTest}
 * 的「直接写 {@code .meta}/{@code .json} 搭建状态」手法，经 public 的
 * {@link SessionManager#loadHomeUrl(String)} / {@link SessionManager#clearSession(String)}
 * 触发 {@code META_CACHE} 与 {@code evictIfExpired} 的并发路径。
 *
 * <p>契约断言维度（企业级）：线程安全（零未捕获异常）、并发读一致、删除/读取竞争终态稳定且不损坏文件、
 * 过期并发驱逐幂等。
 */
public class SessionManagerSharedConcurrencyTest {

    private static final String SESSION_DIR = "target/.sessions";
    // 实例级唯一 key：避免静态缓存跨 @Test 串扰（同 SessionManagerCacheTest 手法）
    private final String key = "UNITTEST_SHARED_" + UUID.randomUUID();
    private final String homeUrl = "https://home.example.com/unit-" + key;

    private Path metaPath() {
        return Paths.get(SESSION_DIR, key + ".meta");
    }

    private Path sessionPath() {
        return Paths.get(SESSION_DIR, key + ".json");
    }

    /** 写一个有效（未过期）的 session 文件对（.meta + 占位 .json）。 */
    private void writeValidSessionFiles() throws Exception {
        Files.createDirectories(Paths.get(SESSION_DIR));
        Properties props = new Properties();
        props.setProperty("homeUrl", homeUrl);
        props.setProperty("lastAccessTime", String.valueOf(System.currentTimeMillis()));
        try (var w = Files.newBufferedWriter(metaPath(), StandardCharsets.UTF_8)) {
            props.store(w, "unit-test");
        }
        Files.write(sessionPath(), new byte[0]); // 占位 session 文件（仅存在性校验）
    }

    /** 写一个**已过期**的 session 文件对（lastAccessTime 远早于任何合理超时阈值）。 */
    private void writeExpiredSessionFiles() throws Exception {
        Files.createDirectories(Paths.get(SESSION_DIR));
        Properties props = new Properties();
        props.setProperty("homeUrl", homeUrl);
        props.setProperty("lastAccessTime",
                String.valueOf(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000L));
        try (var w = Files.newBufferedWriter(metaPath(), StandardCharsets.UTF_8)) {
            props.store(w, "expired");
        }
        Files.write(sessionPath(), new byte[0]);
    }

    /** 并发收集异常，便于断言"零未捕获异常"。 */
    private static final class FaultCollector {
        private final List<Throwable> faults = new CopyOnWriteArrayList<>();
        void run(Runnable r) {
            try {
                r.run();
            } catch (Throwable t) {
                faults.add(t);
            }
        }
        void assertNone() {
            assertTrue("并发执行中不应抛出任何异常，实际: " + faults, faults.isEmpty());
        }
    }

    @Test
    public void concurrentLoadSameKey_allConsistentAndNoException() throws Exception {
        writeValidSessionFiles();
        final int threads = 8;
        final int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        FaultCollector faults = new FaultCollector();
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> faults.run(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException | BrokenBarrierException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < iterations; i++) {
                        // 同 key 并发读 → 全部应返回同一 homeUrl（META_CACHE 单飞 + 读盘一致）
                        assertEquals("并发 loadHomeUrl 必须一致", homeUrl, SessionManager.loadHomeUrl(key));
                    }
                    done.countDown();
                }));
            }
            assertTrue("并发读应在超时内完成", done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        faults.assertNone();
    }

    @Test
    public void concurrentClearAndLoad_terminalStateStableAndNoCorruption() throws Exception {
        writeValidSessionFiles();
        final int threads = 8;
        final int iterations = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        FaultCollector faults = new FaultCollector();
        try {
            for (int t = 0; t < threads; t++) {
                final int id = t;
                pool.submit(() -> faults.run(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException | BrokenBarrierException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < iterations; i++) {
                        if (id % 2 == 0) {
                            SessionManager.clearSession(key);
                        } else {
                            String loaded = SessionManager.loadHomeUrl(key);
                            // 读取永远只能观测到两种合法态：cleared(null) 或精确 homeUrl；
                            // 绝不返回半写/损坏串（files 不被并发写破坏）
                            assertTrue("loadHomeUrl 只能返回 null 或精确 homeUrl，实际: " + loaded,
                                    loaded == null || homeUrl.equals(loaded));
                        }
                    }
                    done.countDown();
                }));
            }
            assertTrue("并发 clear/load 应在超时内完成", done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        faults.assertNone();

        // 终态稳定性：竞争结束后，连续读取返回值恒定（不抖动），且为 null 或精确 homeUrl
        String terminal = SessionManager.loadHomeUrl(key);
        for (int i = 0; i < 10; i++) {
            assertEquals("终态读取应稳定", terminal, SessionManager.loadHomeUrl(key));
        }
        assertTrue("终态只能为 null 或精确 homeUrl", terminal == null || homeUrl.equals(terminal));
    }

    @Test
    public void concurrentExpiredEviction_idempotentAndNoException() throws Exception {
        writeExpiredSessionFiles();
        final int threads = 8;
        final int iterations = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        FaultCollector faults = new FaultCollector();
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> faults.run(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException | BrokenBarrierException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < iterations; i++) {
                        // 过期同 key 并发读 → 触发 evictIfExpired 删除；并发幂等：
                        // 重复删除被 catch 降级而非抛，所有线程应观测到 null
                        assertNull("过期 session 并发读必须返回 null", SessionManager.loadHomeUrl(key));
                    }
                    done.countDown();
                }));
            }
            assertTrue("并发过期驱逐应在超时内完成", done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        faults.assertNone();
        // 过期文件应已被删除（且删除仅一次，不抛异常）
        assertFalse("过期 .meta 应被删除", Files.exists(metaPath()));
        assertFalse("过期 .json 应被删除", Files.exists(sessionPath()));
    }
}
