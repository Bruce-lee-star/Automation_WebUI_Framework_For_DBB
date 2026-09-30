package com.hsbc.cmb.hk.dbb.automation.framework.route.claim;

import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RouteClaim / PendingGuard / ClaimRegistry 并发语义验证：
 * <ul>
 *   <li>同一 Route 恰好一个 claim（putIfAbsent 唯一性）；</li>
 *   <li>多线程并发终结，恰好一个线程抢占成功（CAS 单终结者）；</li>
 *   <li>挂起额度上限与恰好一次释放；</li>
 *   <li>sweep 强制落定超龄 IO_AWAIT claim。</li>
 * </ul>
 */
public class RouteClaimConcurrencyTest {

    private Route mockRoute(String url) {
        Route route = mock(Route.class);
        Request request = mock(Request.class);
        when(route.request()).thenReturn(request);
        when(request.url()).thenReturn(url);
        when(request.method()).thenReturn("GET");
        when(request.headers()).thenReturn(java.util.Map.of());
        return route;
    }

    @Test
    public void sameRouteGetsSingleClaim() {
        ClaimRegistry registry = new ClaimRegistry(new PendingGuard(16), Duration.ofSeconds(35));
        Route route = mockRoute("https://x/api/1");

        RouteClaim first = registry.tryClaim(route);
        RouteClaim second = registry.tryClaim(route);

        assertNotNull(first);
        assertNull( "同一 Route 第二次 claim 必须返回 null（防重放）", second);
        assertEquals((long) 1, (long) registry.size());
    }

    @Test
    public void concurrentTerminalExactlyOneWinner() throws Exception {
        RouteClaim claim = new RouteClaim(mockRoute("https://x/api/2"));
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger(0);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return claim.tryTerminal(true).granted();
            }));
        }
        start.countDown();
        for (Future<Boolean> f : futures) {
            if (f.get(5, TimeUnit.SECONDS)) {
                winners.incrementAndGet();
            }
        }
        pool.shutdownNow();
        assertEquals("16 线程并发终结必须恰好 1 个抢占成功", (long) 1, (long) winners.get());
        assertTrue(claim.isTerminal());
        assertFalse( "终结后再次抢占必须失败", claim.tryTerminal(true).granted());
    }

    @Test
    public void pendingGuardEnforcesLimitAndReleasesExactlyOnce() {
        PendingGuard guard = new PendingGuard(2);
        assertTrue(guard.tryAcquire());
        assertTrue(guard.tryAcquire());
        assertFalse( "超过上限必须拒绝", guard.tryAcquire());
        assertEquals((long) 2, (long) guard.pendingCount());

        guard.release();
        guard.release();
        guard.release(); // 重复释放有下限保护
        assertEquals("重复释放不得把计数打成负数", (long) 0, (long) guard.pendingCount());
        assertTrue( "释放后可再次占用", guard.tryAcquire());
    }

    @Test
    public void ioAwaitReleasesPendingSlotOnTerminal() {
        PendingGuard guard = new PendingGuard(2);
        ClaimRegistry registry = new ClaimRegistry(guard, Duration.ofSeconds(35));
        Route route = mockRoute("https://x/api/3");

        RouteClaim claim = registry.tryClaim(route);
        assertTrue( "先占用挂起额度（dispatcher 的 acquireIoSlot 顺序）", guard.tryAcquire());
        assertTrue(claim.toIoAwait());
        assertEquals((long) 1, (long) guard.pendingCount());

        assertTrue(registry.markTerminal(claim, true));
        assertEquals("终结必须恰好释放一次额度", (long) 0, (long) guard.pendingCount());
        assertEquals((long) 0, (long) registry.pendingCount());
        assertEquals((long) 0, (long) registry.size());
    }

    @Test
    public void sweepForcesStaleIoAwaitToFallback() throws Exception {
        PendingGuard guard = new PendingGuard(16);
        // 超龄阈值 50ms，便于测试
        ClaimRegistry registry = new ClaimRegistry(guard, Duration.ofMillis(50));
        Route route = mockRoute("https://x/api/4");

        RouteClaim claim = registry.tryClaim(route);
        assertTrue(guard.tryAcquire());
        assertTrue(claim.toIoAwait());
        assertEquals((long) 1, (long) guard.pendingCount());

        Thread.sleep(120);
        registry.sweep();
        assertTrue( "超龄 IO_AWAIT claim 必须被巡检强制落定", claim.isTerminal());
        assertEquals("巡检落定后额度必须释放", (long) 0, (long) guard.pendingCount());
        assertEquals((long) 0, (long) registry.size());
    }
}
