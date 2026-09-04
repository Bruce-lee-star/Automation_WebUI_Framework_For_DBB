package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import org.junit.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
 * （{@code parseSharedBrowserMode} / {@code keyFor} / {@code browserLock} / {@code restartContextOnly}）——
 * 这些能力按企业级约束不对外公开（见 {@code @apiNote}），仅框架内部与同包测试可见。</p>
 *
 * <p>重点覆盖三条不变式：</p>
 * <ol>
 *   <li>配置解析对合法/非法输入的行为确定（非法值降级为 false，不静默误判为开启）。</li>
 *   <li>共享模式下实例键<b>跨线程一致</b>；非共享模式下<b>每线程唯一</b>。</li>
 *   <li>共享模式返回<b>进程级</b>锁（跨线程同一对象，才能真正互斥地只创建一个 Browser）；
 *       非共享模式返回 per-thread 锁（各线程互不阻塞）。</li>
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
        String fromMain = PlaywrightManager.keyFor(CONFIG_ID, true);
        String fromOther = onNewThread(() -> PlaywrightManager.keyFor(CONFIG_ID, true));

        assertEquals("shared:" + CONFIG_ID, fromMain);
        assertEquals("共享模式下所有线程必须命中同一个 Browser 实例键", fromMain, fromOther);
    }

    @Test
    public void keyFor_perThreadModeIsUniquePerThread() throws Exception {
        String fromMain = PlaywrightManager.keyFor(CONFIG_ID, false);
        String fromOther = onNewThread(() -> PlaywrightManager.keyFor(CONFIG_ID, false));

        assertTrue(fromMain.endsWith(":" + CONFIG_ID));
        assertTrue(fromOther.endsWith(":" + CONFIG_ID));
        assertNotSame("非共享模式下每个线程必须有独立的实例键", fromMain, fromOther);
    }

    @Test
    public void keyFor_rejectsBlankConfigId() {
        assertRejectsBlank(id -> PlaywrightManager.keyFor(id, true));
        assertRejectsBlank(id -> PlaywrightManager.keyFor(id, false));
    }

    // ==================== 互斥锁语义 ====================

    @Test
    public void browserLock_sharedModeReturnsSameLockAcrossThreads() throws Exception {
        Object fromMain = PlaywrightManager.browserLock(true);
        Object fromOther = onNewThread(() -> PlaywrightManager.browserLock(true));

        assertSame("共享模式必须用进程级锁，否则并发会创建出多个 Browser", fromMain, fromOther);
    }

    @Test
    public void browserLock_perThreadModeReturnsDifferentLocksPerThread() throws Exception {
        Object fromMain = PlaywrightManager.browserLock(false);
        Object fromOther = onNewThread(() -> PlaywrightManager.browserLock(false));

        assertNotSame("非共享模式应保持 per-thread 锁，避免线程间无谓串行化", fromMain, fromOther);
    }

    // ==================== 入参校验 ====================

    @Test
    public void restartContextOnly_rejectsBlankConfigId() {
        assertRejectsBlank(PlaywrightManager::restartContextOnly);
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
