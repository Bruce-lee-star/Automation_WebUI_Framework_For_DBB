package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import com.hsbc.cmb.hk.dbb.automation.framework.web.core.RuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 专项 19（会话缓存并发治理）Phase 2 表征测试：经 WEB-P0-2 {@link RuntimeProvider} 测试 seam
 * 注入 mock {@link BrowserContext}，<b>无真浏览器</b>地表征 {@link SessionManager#restoreSession(String)}
 * 的 per-sessionKey 单飞协调，与命中路径并发复用（{@code STORAGE_CONTENT_CACHE} 单飞）。
 *
 * <p>背景：doc18 §4.5 指出唯一共享层是 {@code SessionManager} 的 per-sessionKey 文件 / 缓存，
 * 其中「同 key 并行首次无 session」由 {@code acquireOrAwait}（{@code ConcurrentHashMap.putIfAbsent}
 * 单飞）协调——仅一个 leader 真实登录，follower 等待后复用落盘，避免 SSO 单会话互踢。本测试以
 * 表征先行锁定该契约（无行为变更）：
 * ① 无 session 同 key 并行 → 恰好一个 leader 返回 false 并落盘，其余 follower 复用返回 true；
 * ② 已存在 session 同 key 并行 → 全部命中复用（返回 true），并发读盘经 Guava 单飞（无异常 / 不重复登录）。
 *
 * <p>手法同源 {@code BasePageSeamTest}/{@code WebRuntimeSeamTest}：{@code setProvider(mock)} 注入
 * mock Browser/BrowserContext，{@code @After} 统一 {@code resetProvider()} 防污染。
 */
public class SessionManagerRestoreSingleFlightTest {

    private final String key = "UNITTEST_SINGLEFLIGHT_" + UUID.randomUUID();
    private final String homeUrl = "https://home.example.com/flight-" + key;

    private Path metaPath() {
        return Paths.get("target/.sessions", key + ".meta");
    }

    private Path sessionPath() {
        return Paths.get("target/.sessions", key + ".json");
    }

    private void writeValidSessionFiles() throws Exception {
        Files.createDirectories(Paths.get("target/.sessions"));
        Properties props = new Properties();
        props.setProperty("homeUrl", homeUrl);
        props.setProperty("lastAccessTime", String.valueOf(System.currentTimeMillis()));
        try (var w = Files.newBufferedWriter(metaPath(), StandardCharsets.UTF_8)) {
            props.store(w, "unit-test");
        }
        Files.write(sessionPath(), new byte[0]);
    }

    /** 并发收集异常，断言"零未捕获异常"。 */
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
            assertTrue("并发执行不应抛出任何异常，实际: " + faults, faults.isEmpty());
        }
    }

    @After
    public void tearDown() {
        PlaywrightManager.resetProvider();
    }

    /** 注入 mock provider（无真浏览器），返回 mock context 供 restoreSession 复用路径使用。 */
    private RuntimeProvider installMockProvider() {
        RuntimeProvider provider = mock(RuntimeProvider.class);
        BrowserContext ctx = mock(BrowserContext.class);
        // storageState() 无参重载须返回非空（否则 saveSession 内 STORAGE_CONTENT_CACHE.put(null) 抛 NPE）
        when(ctx.storageState()).thenReturn("{}");
        when(provider.getContext()).thenReturn(ctx);
        when(provider.getBrowser()).thenReturn(mock(Browser.class));
        when(provider.getPlaywright()).thenReturn(mock(Playwright.class));
        when(provider.getPage()).thenReturn(mock(Page.class));
        PlaywrightManager.setProvider(provider);
        return provider;
    }

    @Test
    public void concurrentRestoreSession_noSession_singleFlight_onlyOneLeader() throws Exception {
        final int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        FaultCollector faults = new FaultCollector();
        List<Boolean> results = new CopyOnWriteArrayList<>();
        AtomicInteger loginInvocations = new AtomicInteger();

        installMockProvider();

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> faults.run(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException | BrokenBarrierException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    boolean restored = SessionManager.restoreSession(key);
                    if (!restored) {
                        // leader：模拟业务登录后落盘，释放单飞守卫唤醒 follower
                        loginInvocations.incrementAndGet();
                        SessionManager.saveSession(key, homeUrl);
                    }
                    results.add(restored);
                    done.countDown();
                }));
            }
            assertTrue("单飞应在超时内完成", done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        faults.assertNone();

        long leaders = results.stream().filter(r -> !r).count();
        long reused = results.stream().filter(r -> r).count();
        // 单飞铁证：仅一个 leader 真实登录（返回 false 并触发 saveSession），其余 follower 复用返回 true
        assertEquals("单飞：仅一个 leader 真实登录（返回 false）", 1, leaders);
        assertEquals("单飞：其余 follower 复用落盘（返回 true）", threads - 1, reused);
        assertEquals("saveSession 仅被 leader 调用一次", 1, loginInvocations.get());
        // 落盘恰好一次，session 可见（META_CACHE 已由 saveSession 刷新）
        assertEquals(homeUrl, SessionManager.loadHomeUrl(key));
    }

    @Test
    public void concurrentRestoreSession_existingSession_allReuse_noException() throws Exception {
        writeValidSessionFiles();
        final int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        FaultCollector faults = new FaultCollector();
        List<Boolean> results = new CopyOnWriteArrayList<>();

        installMockProvider();

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> faults.run(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException | BrokenBarrierException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    results.add(SessionManager.restoreSession(key));
                    done.countDown();
                }));
            }
            assertTrue("命中复用应在超时内完成", done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        faults.assertNone();
        // 全部命中复用（STORAGE_CONTENT_CACHE 单飞读盘 + getContext 复用），无重复登录、无异常
        assertEquals("所有线程应命中复用（返回 true）", (long) threads,
                results.stream().filter(r -> r).count());
        assertFalse("session 文件应仍存在", Files.notExists(sessionPath()));
    }
}
