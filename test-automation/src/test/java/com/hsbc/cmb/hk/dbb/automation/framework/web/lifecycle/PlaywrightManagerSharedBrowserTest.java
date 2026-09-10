package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.lock.LifecycleLockMediator;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistryImpl;
import org.junit.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 「共享 Browser 模式（一个 Browser + 多 Context）」专属单测。
 *
 * <p>本测试位于与 {@link PlaywrightManager} <b>相同的包</b>，因此可白盒访问其包级私有能力
 * （{@code parseSharedBrowserMode} / {@code keyFor} / {@code restartContextOnly}）——
 * 这些能力按企业级约束不对外公开（见 {@code @apiNote}），仅框架内部与同包测试可见。</p>
 *
 * <p>重点覆盖三条不变式：</p>
 * <ol>
 *   <li>配置解析对合法/非法输入的行为确定（非法值降级为 false，不静默误判为开启）。</li>
 *   <li>共享模式下实例键<b>跨线程一致</b>；非共享模式下<b>每线程唯一</b>。</li>
 *   <li>共享模式下 Browser 临界区<b>进程级互斥</b>；非共享模式下<b>各线程互不阻塞</b>
 *       （doc16 Phase 3：锁对象已收为 {@link LifecycleLockMediator} 的 {@code private} 成员，
 *       故改为验证互斥<b>行为</b>而非锁对象身份）。</li>
 * </ol>
 */
public class PlaywrightManagerSharedBrowserTest {

    private static final String CONFIG_ID = "chromium_headless";

    // ==================== 配置解析 ====================

    @Test
    public void parseSharedBrowserMode_acceptsTrueVariants() {
        assertTrue(PlaywrightManager.parseSharedBrowserMode("true"));
        assertTrue(PlaywrightManager.parseSharedBrowserMode("TRUE"));
        assertTrue(PlaywrightManager.parseSharedBrowserMode(" true "));
    }

    @Test
    public void parseSharedBrowserMode_defaultsToFalseWhenBlankOrAbsent() {
        assertFalse(PlaywrightManager.parseSharedBrowserMode(null));
        assertFalse(PlaywrightManager.parseSharedBrowserMode(""));
        assertFalse(PlaywrightManager.parseSharedBrowserMode("   "));
        assertFalse(PlaywrightManager.parseSharedBrowserMode("false"));
    }

    /**
     * 非法值必须降级为 false 而不是抛异常（避免配置笔误导致整个套件启动失败），
     * 且实现会打 WARN 告警——此处只校验降级结果。
     */
    @Test
    public void parseSharedBrowserMode_unrecognizedValueFallsBackToFalse() {
        assertFalse(PlaywrightManager.parseSharedBrowserMode("yes"));
        assertFalse(PlaywrightManager.parseSharedBrowserMode("1"));
        assertFalse(PlaywrightManager.parseSharedBrowserMode("on"));
    }

    // ==================== 实例键（并发隔离模型的核心） ====================

    @Test
    public void keyFor_sharedModeIsStableAcrossThreads() throws Exception {
        String fromMain = BrowserRegistryImpl.INSTANCE.keyFor(CONFIG_ID, true);
        String fromOther = onNewThread(() -> BrowserRegistryImpl.INSTANCE.keyFor(CONFIG_ID, true));

        assertEquals("shared:" + CONFIG_ID, fromMain);
        assertEquals("共享模式下所有线程必须命中同一个 Browser 实例键", fromMain, fromOther);
    }

    @Test
    public void keyFor_perThreadModeIsUniquePerThread() throws Exception {
        String fromMain = BrowserRegistryImpl.INSTANCE.keyFor(CONFIG_ID, false);
        String fromOther = onNewThread(() -> BrowserRegistryImpl.INSTANCE.keyFor(CONFIG_ID, false));

        assertTrue(fromMain.endsWith(":" + CONFIG_ID));
        assertTrue(fromOther.endsWith(":" + CONFIG_ID));
        assertNotSame("非共享模式下每个线程必须有独立的实例键", fromMain, fromOther);
    }

    @Test
    public void keyFor_rejectsBlankConfigId() {
        assertRejectsBlank(id -> BrowserRegistryImpl.INSTANCE.keyFor(id, true));
        assertRejectsBlank(id -> BrowserRegistryImpl.INSTANCE.keyFor(id, false));
    }

    // ==================== 互斥锁语义 ====================

    @Test
    public void sharedBrowserLock_isMutuallyExclusiveAcrossThreads() throws Exception {
        // 共享模式：Browser 被所有线程共享，创建必须进程级互斥（否则并发创建多个 Browser + 旧实例泄漏）
        assertFalse("共享模式必须用进程级锁：两个线程不得同时进入临界区",
                twoThreadsCanEnterConcurrently(true));
    }

    @Test
    public void perThreadBrowserLock_doesNotSerializeThreads() throws Exception {
        // 非共享模式：每线程独立 Browser（key 含 threadId），创建互不阻塞
        assertTrue("非共享模式应保持 per-thread 锁：两个线程必须能同时进入临界区，避免无谓串行化",
                twoThreadsCanEnterConcurrently(false));
    }

    /**
     * 判定两个线程能否<b>同时</b>进入 Browser 临界区（doc16 Phase 3）。
     *
     * <p>锁对象已收口为 {@code private}，无法再比较身份，故改为验证<b>互斥行为本身</b>——
     * 这比断言锁对象身份更本质：先进入的线程在临界区内等待同伴，
     * 若同伴能进来（未超时）说明两线程<b>不互斥</b>；若等待超时说明<b>互斥</b>。</p>
     *
     * @param sharedMode true 走进程级共享锁；false 走 per-thread 锁
     * @return true 表示两线程可并发进入（不互斥）
     */
    private static boolean twoThreadsCanEnterConcurrently(boolean sharedMode) throws Exception {
        final CountDownLatch inside = new CountDownLatch(2);
        final CountDownLatch finished = new CountDownLatch(2);
        final AtomicBoolean timedOut = new AtomicBoolean(false);

        Runnable criticalSection = () -> {
            inside.countDown();
            try {
                if (!inside.await(3, TimeUnit.SECONDS)) {
                    // 同伴没能进来 —— 说明本临界区是互斥的
                    timedOut.set(true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        };

        Thread first = new Thread(() -> LifecycleLockMediator.withBrowserLock(sharedMode, criticalSection));
        Thread second = new Thread(() -> LifecycleLockMediator.withBrowserLock(sharedMode, criticalSection));
        first.start();
        second.start();
        assertTrue("临界区执行超时（可能出现死锁）", finished.await(15, TimeUnit.SECONDS));
        first.join();
        second.join();

        return !timedOut.get();
    }

    // ==================== 入参校验 ====================

    @Test
    public void restartContextOnly_rejectsBlankConfigId() {
        // WEB-P1-1 Step 2b：restartContextOnly 已下沉至 BrowserRestartImpl（方法体逐字迁移，行为不变）。
        assertRejectsBlank(PlaywrightRuntime.instance().browserRestart::restartContextOnly);
    }

    // ==================== JVM 级不变式 ====================

    /**
     * 并发隔离模型一旦在类加载时确定，必须对整个 JVM 生命周期稳定：
     * 若开关可中途翻转，同一 configId 会映射到不同键，使已创建的 Browser 成为无法回收的孤儿实例。
     */
    @Test
    public void sharedBrowserModeIsImmutableWithinJvm() {
        assertEquals(PlaywrightManager.SHARED_BROWSER_MODE, PlaywrightManager.isSharedBrowserMode());
        assertEquals(PlaywrightManager.SHARED_BROWSER_MODE, PlaywrightManager.isSharedBrowserMode());
    }

    // ==================== 辅助方法 ====================

    /** 断言 null 与空白 configId 被拒绝（语义化异常，而非裸 NPE 或生成孤儿键）。 */
    private static void assertRejectsBlank(java.util.function.Consumer<String> invocation) {
        try {
            invocation.accept(null);
            fail("Expected IllegalArgumentException for null configId");
        } catch (IllegalArgumentException expected) {
            // 预期：语义化参数校验
        }
        try {
            invocation.accept("   ");
            fail("Expected IllegalArgumentException for blank configId");
        } catch (IllegalArgumentException expected) {
            // 预期：语义化参数校验
        }
    }

    /** 在独立线程执行任务并返回结果（用于验证 ThreadLocal / 线程维度相关行为）。 */
    private static <T> T onNewThread(Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            return pool.submit(task).get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
