package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.Test;

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
 */
public class BasePagePageSwitchLockConcurrencyTest {

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

    @Test
    public void parallelContextsDoNotSerialize() throws Exception {
        BrowserContext ctxA = mock(BrowserContext.class);
        BrowserContext ctxB = mock(BrowserContext.class);
        Object lockA = BasePage.pageSwitchLockFor(ctxA);
        Object lockB = BasePage.pageSwitchLockFor(ctxB);

        // T_A 持有 lockA 并睡眠，模拟 context A 正在切换页面
        Thread tA = new Thread(() -> {
            synchronized (lockA) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        tA.start();
        Thread.sleep(50); // 确保 tA 已持锁

        // T_B 抢占 lockB 应立即成功（与 lockA 独立）→ 证明跨 context 不互锁
        boolean[] acquired = {false};
        Thread tB = new Thread(() -> { synchronized (lockB) { acquired[0] = true; } });
        tB.start();
        tB.join(1000);
        assertTrue( acquired[0], "不同 Context 的页面切换锁应互不阻塞（并行不被串行化）");
        tA.join();
    }

    @Test
    public void sameContextStillSerializes() throws Exception {
        BrowserContext ctxA = mock(BrowserContext.class);
        Object lockA = BasePage.pageSwitchLockFor(ctxA);

        Thread t1 = new Thread(() -> {
            synchronized (lockA) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        t1.start();
        Thread.sleep(50); // 确保 t1 已持锁

        boolean[] acquired = {false};
        long start = System.currentTimeMillis();
        Thread t2 = new Thread(() -> { synchronized (lockA) { acquired[0] = true; } });
        t2.start();
        t2.join(2000);
        long waited = System.currentTimeMillis() - start;

        assertTrue( acquired[0], "同一 Context 内页面切换仍应串行（t2 须等待 t1 释放）");
        assertTrue( waited >= 300, "同 Context 应被串行化（等待时长应接近持有时长）");
        t1.join();
    }
}
