package com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine;

import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * WEB-P1-N9 回归：页面切换锁由全局静态锁改为 per-context，解除并行场景串行化。
 *
 * <p>核心不变式：
 * <ul>
 *   <li>不同 {@link BrowserContext} 取到<b>不同</b>锁实例 → 页面切换可真并行（修复点）；</li>
 *   <li>同一 {@link BrowserContext} 取到<b>稳定且相同</b>的锁实例 → 上下文内仍串行（正确性）；</li>
 *   <li>无法解析 Context（null）时回退全局兜底锁 → 等价于旧全局锁语义，退化单线程场景行为不变。</li>
 * </ul>
 *
 * <p>无浏览器依赖：直接驱动 {@link BasePage#pageSwitchLockFor(BrowserContext)}（包级私有测试入口），
 * 配合线程阻塞/释放验证跨 context 不互锁、同 context 被串行。
 *
 * <p><b>2026-09-20 并发断言加固（消灭 flaky）</b>：原实现用 {@code Thread.sleep(50)}「确保对方线程已持锁」，
 * 再以耗时下界（{@code waited >= 300}）断言「确实等够了」。这是典型的<b>时序假设型 flake</b>——
 * 负载高时持锁线程可能尚未被调度，等待方反而先拿到锁，耗时下界断言随即假失败（与产品缺陷无关，
 * 却会把重跑变成掩盖真实问题的常规手段）。现改为：
 * <ol>
 *   <li><b>确定性握手</b>：持锁线程进入临界区后立即 {@link CountDownLatch#countDown()}，主线程
 *       {@code await} 到该信号才继续——不再用睡眠猜调度；</li>
 *   <li><b>断言互斥不变式</b>（取代耗时下界）：持锁期间等待线程<b>绝不可</b>进入临界区；</li>
 *   <li><b>断言无永久阻塞</b>：释放后等待线程<b>必须</b>立刻进入——两条合起来比"等了约 400ms"更强，
 *       且不依赖任何绝对时长。</li>
 * </ol>
 * 等待方在尝试进入前先 {@code countDown()}「就绪」信号，使「不可进入」的观测窗口有意义
 * （排除"等待方还没被调度"导致的假通过）。超时上界仅用于防挂死，不作为通过判据。
 */
public class BasePagePageSwitchLockConcurrencyTest {

    /** 线程间握手/收尾的等待上界：仅防挂死，不作为通过判据。 */
    private static final long HANDSHAKE_TIMEOUT_MS = 10_000L;

    /** 「应立即可进行」的宽松上界（无竞争的监视器进入；仅防挂死，不作为通过判据）。 */
    private static final long PROMPT_TIMEOUT_MS = 5_000L;

    /** 「不可进入」的观测窗口：在等待方已就绪后，持续观察其是否违规进入临界区。 */
    private static final long NON_ENTRY_OBSERVE_MS = 300L;

    @Test
    public void distinctContextsGetDistinctLocks() {
        BrowserContext a = mock(BrowserContext.class);
        BrowserContext b = mock(BrowserContext.class);
        assertNotSame(
                BasePage.pageSwitchLockFor(a),  BasePage.pageSwitchLockFor(b), "不同 Context 必须映射不同锁实例（否则并行场景仍被串行化）");
    }

    @Test
    public void sameContextReturnsStableLock() {
        BrowserContext a = mock(BrowserContext.class);
        assertSame(
                BasePage.pageSwitchLockFor(a),  BasePage.pageSwitchLockFor(a), "同一 Context 必须始终映射同一锁实例（保证上下文内串行）");
    }

    @Test
    public void nullContextFallsBackToSharedGlobalLock() {
        Object nullLock1 = BasePage.pageSwitchLockFor(null);
        Object nullLock2 = BasePage.pageSwitchLockFor(null);
        assertSame( nullLock1,  nullLock2, "null Context 应稳定回退到同一全局兜底锁");
        assertNotSame(
                nullLock1,  BasePage.pageSwitchLockFor(mock(BrowserContext.class)), "全局兜底锁必须与任意 per-context 锁区分（证明 per-context 路径被采用）");
    }

    /**
     * 跨 Context 不互锁：持锁线程 A 停在临界区内（由握手信号确认），B 进入<b>另一个</b> Context 的
     * 临界区应立即可行 —— 证明并行场景未被串行化。
     */
    @Test
    public void parallelContextsDoNotSerialize() throws Exception {
        Object lockA = BasePage.pageSwitchLockFor(mock(BrowserContext.class));
        Object lockB = BasePage.pageSwitchLockFor(mock(BrowserContext.class));

        CountDownLatch aHolding = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        AtomicBoolean bAcquired = new AtomicBoolean(false);

        Thread tA = new Thread(() -> {
            synchronized (lockA) {
                aHolding.countDown();     // 确定性握手：A 已真正进入临界区并持锁
                awaitQuietly(releaseA);
            }
        }, "page-switch-holder-A");
        tA.start();
        assertTrue(aHolding.await(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                "线程 A 应在超时前取得 Context A 的页面切换锁");

        Thread tB = new Thread(() -> {
            synchronized (lockB) {
                bAcquired.set(true);
            }
        }, "page-switch-holder-B");
        tB.start();
        try {
            assertTrue(awaitTrue(bAcquired, PROMPT_TIMEOUT_MS),
                    "不同 Context 的页面切换锁应互不阻塞（A 持锁期间 B 仍应立即进入自己的临界区）");
        } finally {
            releaseA.countDown();
            tA.join(HANDSHAKE_TIMEOUT_MS);
        }
    }

    /**
     * 同 Context 仍串行：持锁线程 t1 停在临界区内，t2 尝试进入<b>同一</b> Context 的临界区时必须被挡住，
     * 直至 t1 释放。断言互斥不变式（不可进入）+ 无永久阻塞（释放后可进入），
     * 不使用任何绝对耗时下界（原实现的 flake 来源）。
     */
    @Test
    public void sameContextStillSerializes() throws Exception {
        BrowserContext ctxA = mock(BrowserContext.class);
        Object lockA = BasePage.pageSwitchLockFor(ctxA);

        CountDownLatch t1Holding = new CountDownLatch(1);
        CountDownLatch releaseT1 = new CountDownLatch(1);
        CountDownLatch t2Ready = new CountDownLatch(1);
        AtomicBoolean t2Acquired = new AtomicBoolean(false);

        Thread t1 = new Thread(() -> {
            synchronized (lockA) {
                t1Holding.countDown();    // 确定性握手：t1 已真正持锁（不再用 sleep 猜）
                awaitQuietly(releaseT1);
            }
        }, "page-switch-holder");
        t1.start();
        assertTrue(t1Holding.await(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                "t1 应在超时前取得同一 Context 的页面切换锁");

        Thread t2 = new Thread(() -> {
            t2Ready.countDown();          // 即将尝试进入临界区（使后续观测窗口有意义）
            synchronized (lockA) {
                t2Acquired.set(true);
            }
        }, "page-switch-waiter");
        t2.start();
        try {
            assertTrue(t2Ready.await(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "t2 应在超时前就绪（即将尝试进入临界区）");

            assertFalse(awaitTrue(t2Acquired, NON_ENTRY_OBSERVE_MS),
                    "t1 持锁期间 t2 不得进入同一 Context 的页面切换临界区（同 Context 必须串行化）");

            releaseT1.countDown();
            t2.join(HANDSHAKE_TIMEOUT_MS);
            assertTrue(t2Acquired.get(), "t1 释放后 t2 应立即获得锁（不得被永久阻塞）");
            assertFalse(t2.isAlive(), "t2 应在 t1 释放后结束");
        } finally {
            releaseT1.countDown();
            t1.join(HANDSHAKE_TIMEOUT_MS);
        }
    }

    /** 有界轮询等待标志变真（超时返回当前值；上界仅防挂死，不作为通过判据）。 */
    private static boolean awaitTrue(AtomicBoolean flag, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!flag.get()) {
            if (System.nanoTime() - deadline >= 0) {
                return flag.get();
            }
            Thread.sleep(5);
        }
        return true;
    }

    /** 有界等待闩锁（中断则恢复中断位；超时仅防挂死，不作为通过判据）。 */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
