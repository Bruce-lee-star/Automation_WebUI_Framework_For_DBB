package com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteContextState;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 跨用例<b>交接栅栏</b>（2026-09-26，第二次设计：抢占 + 短等）确定性回归守卫。
 *
 * <p><b>要守住的缺陷</b>：route teardown worker 是 fire-and-forget 守护线程，feature 模式下常在
 * <b>下一个用例已经开始之后</b>才收工。该窗口内两个线程并发操作同一个 Playwright {@code Connection}，
 * 实测触发 {@code Object doesn't exist: response@/worker@...}（导航失败 → 会话缓存误删 → 每轮全量重登）。
 *
 * <p><b>第一次设计的缺陷（本测试同时守住）</b>：仅"有界等待"是<b>必输的赌注</b> —— worker 的 drain 预算
 * 10s 远超栅栏预算 2s，内网实测连续两条 {@code Teardown fence timed out after 2002ms/2010ms}：
 * 白付延迟却仍并发。现改为<b>先抢占（置标志 + 中断）、再短等其真正退出</b>。
 *
 * <p>实现手段：让句柄 {@code close()} 阻塞在可控闩锁上，使 worker 的收工时刻由测试掌控（确定性，无 sleep 猜时序）。
 */
class RouteTeardownFenceTest {

    /**
     * 构造「收尾会阻塞在闩锁上」的 context，并返回该闩锁。
     *
     * <p>{@code CountDownLatch.await} 可被中断 ⇒ 抢占（{@code interrupt()}）能让 worker 立即退出。
     */
    private static CountDownLatch blockingContext(BrowserContext ctx, AtomicBoolean closeEntered) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(ctx.isClosed()).thenReturn(false);
        when(ctx.route(anyString(), any())).thenReturn((AutoCloseable) () -> {
            closeEntered.set(true);
            assertTrue(release.await(30, TimeUnit.SECONDS), "release not fired within 30s");
        });
        return release;
    }

    private static RouteRule mockRule(String pattern) {
        RouteRule rule = new RouteRule();
        rule.setUrlPattern(pattern);
        rule.setType(RouteHandleType.MOCK);
        return rule;
    }

    /** 有界等待条件成立（避免用固定 sleep 猜时序）。 */
    private static void awaitCondition(BooleanSupplier condition, long timeoutMs, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    @Test
    @DisplayName("栅栏 = 抢占 + 短等：worker 未收工时被抢占并立即退出（确定性交接，不赌超时）")
    void fencePreemptsInFlightUnrouteForDeterministicHandoff() throws Exception {
        BrowserContext ctx = mock(BrowserContext.class);
        AtomicBoolean closeEntered = new AtomicBoolean(false);
        CountDownLatch release = blockingContext(ctx, closeEntered); // 故意永不释放

        RuleRepository.register(ctx, List.of(mockRule("/api/preempt/**")));
        RuleRepository.clearContext(ctx);
        assertTrue(RouteContextState.hasPendingUnroute(ctx),
                "收尾登记必须在 worker 启动之前可见（否则下一个用例看不到在途收尾）");
        awaitCondition(closeEntered::get, 5_000L, "前置：worker 应已进入句柄 close()");

        long t0 = System.currentTimeMillis();
        assertTrue(RuleRepository.awaitInFlightUnroute(ctx, 5_000L),
                "抢占后 worker 必须停止（返回 true）");
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(elapsed < 1_000L,
                "交接必须是抢占式的确定性停止，而非等 latch 释放（实测 " + elapsed + "ms）");
        assertFalse(RouteContextState.hasPendingUnroute(ctx), "交接完成后登记必须注销");
        assertTrue(release.getCount() == 1L, "闩锁应从未被释放（证明是抢占令 worker 退出，而非自然收工）");
    }

    @Test
    @DisplayName("栅栏有界：worker 无视抢占时，栅栏在预算内返回 false（绝不无限等待）")
    void fenceIsBoundedEvenIfWorkerIgnoresPreempt() throws Exception {
        BrowserContext ctx = mock(BrowserContext.class);
        AtomicBoolean closeEntered = new AtomicBoolean(false);
        CountDownLatch release = new CountDownLatch(1);
        when(ctx.isClosed()).thenReturn(false);
        when(ctx.route(anyString(), any())).thenReturn((AutoCloseable) () -> {
            closeEntered.set(true);
            boolean done = false;
            while (!done) {
                try {
                    release.await();
                    done = true;
                } catch (InterruptedException ignored) {
                    //  故意忽略中断：验证"栅栏自身必须有界"，而不是依赖 worker 配合
                }
            }
        });
        try {
            RuleRepository.register(ctx, List.of(mockRule("/api/stubborn/**")));
            RuleRepository.clearContext(ctx);
            awaitCondition(closeEntered::get, 5_000L, "前置：worker 应已进入 close()");

            long t0 = System.currentTimeMillis();
            assertFalse(RuleRepository.awaitInFlightUnroute(ctx, 300L),
                    "worker 无视抢占时，栅栏必须在预算内超时返回 false");
            assertTrue(System.currentTimeMillis() - t0 < 3_000L, "栅栏必须有界（绝不无限等待）");
            assertTrue(RouteContextState.hasPendingUnroute(ctx),
                    "worker 尚未退出 ⇒ 登记应仍在（状态如实反映，不假装成功）");
        } finally {
            release.countDown(); // 放行，避免测试 JVM 残留自旋线程
        }
    }

    @Test
    @DisplayName("register 复用同 Context 前抢占上一用例收尾：快速返回，不再等 worker 自然收工")
    void registerPreemptsPrecedingTeardown() throws Exception {
        BrowserContext ctx = mock(BrowserContext.class);
        AtomicBoolean closeEntered = new AtomicBoolean(false);
        CountDownLatch release = blockingContext(ctx, closeEntered);

        try {
            RuleRepository.register(ctx, List.of(mockRule("/api/prev/**")));
            RuleRepository.clearContext(ctx);
            assertTrue(RouteContextState.hasPendingUnroute(ctx), "前置：上一用例收尾仍在途");

            long t0 = System.currentTimeMillis();
            RuleRepository.register(ctx, List.of(mockRule("/api/next/**"))); // 复用同一 Context
            long elapsed = System.currentTimeMillis() - t0;

            assertTrue(elapsed < 2_000L,
                    "register 应在抢占后快速返回，而不是等上一用例 worker 自然收工（实测 " + elapsed + "ms）");
            assertFalse(RouteContextState.hasPendingUnroute(ctx), "抢占后收尾应已结束");
        } finally {
            release.countDown();
            RuleRepository.clearContext(ctx);
        }
    }

    @Test
    @DisplayName("无在途收尾时栅栏零开销放行（不阻塞、不登记）")
    void fenceIsNoOpWhenNothingInFlight() {
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.isClosed()).thenReturn(false);

        assertFalse(RouteContextState.hasPendingUnroute(ctx), "无收尾时不应有登记");
        long t0 = System.currentTimeMillis();
        assertTrue(RuleRepository.awaitInFlightUnroute(ctx, 2_000L), "无在途收尾时必须立即返回 true");
        assertTrue(System.currentTimeMillis() - t0 < 100L, "无在途收尾时不得产生可观测等待");
    }
}
