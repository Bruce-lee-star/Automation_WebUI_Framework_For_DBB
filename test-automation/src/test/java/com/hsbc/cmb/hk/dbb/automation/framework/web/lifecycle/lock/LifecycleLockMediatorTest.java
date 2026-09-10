package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.lock;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.lock.LifecycleLockMediator;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * doc16 §9.4 / §11.2 偏差 A 收口：锁顺序 + 锁隐藏守护（纯并发单测，零浏览器依赖）。
 *
 * <p><b>背景</b>：doc16 Phase 3 把 {@code CONTEXT_LOCK}/{@code PAGE_LOCK}/{@code SHARED_BROWSER_LOCK}
 * 由 {@code public} 监视器收为 {@code private}（{@link LifecycleLockMediator} 内部），只暴露
 * 有序临界区执行器。本测试固化两条不变式：</p>
 * <ol>
 *   <li><b>锁顺序不 deadlock</b>：规范序（PAGE → CONTEXT）多线程竞争无 ABBA；逆规范序的单线程嵌套
 *       （CONTEXT 内再 PAGE）必然可完成（单线程持两把不同监视器不会自死锁）。</li>
 *   <li><b>锁隐藏（编译期不可达的运行期守护）</b>：三把锁 + per-thread 锁键字段保持 {@code private}；
 *       中介不对外暴露任何返回锁对象（{@code Object}）的公开方法 → 外部无法 {@code synchronized}
 *       引用锁对象（劫持 / 逆序死锁被根绝）。</li>
 * </ol>
 *
 * <p>@apiNote 逆规范序的<b>跨线程</b>嵌套（线程 A 先 PAGE 后 CONTEXT、线程 B 先 CONTEXT 后 PAGE）会
 * 构成标准 ABBA 死锁——该风险由调用点纪律（{@code BrowserRegistryImpl#handleBrowserTypeSwitch} 把
 * closePage/closeContext 移到 Browser 锁之外）规避，<b>不</b>由本中介强制；本中介的保证是「锁对象不可
 * 被外部拿到」，故调用方即便想逆序，也只能通过本中介的单一执行器，而无法长期持锁劫持。
 */
public class LifecycleLockMediatorTest {

    private static final int THREADS = 16;
    private static final long DEADLOCK_TIMEOUT_MS = 30_000;

    // ==================== ① 锁顺序 ====================

    /**
     * 规范序（PAGE → CONTEXT）单线程嵌套：两把不同监视器顺序获取，必然可完成。
     */
    @Test
    public void canonicalOrderNestedSingleThreadNoDeadlock() {
        AtomicInteger observed = new AtomicInteger();
        LifecycleLockMediator.withPageLock(() ->
                LifecycleLockMediator.withContextLock(observed::incrementAndGet));
        assertEquals(1, observed.get());
    }

    /**
     * 逆规范序（CONTEXT → PAGE）单线程嵌套（doc16 §9.4 建议项①字面）：单线程持两把不同监视器不会自死锁。
     */
    @Test
    public void inverseOrderNestedSingleThreadNoDeadlock() {
        AtomicInteger observed = new AtomicInteger();
        LifecycleLockMediator.withContextLock(() ->
                LifecycleLockMediator.withPageLock(observed::incrementAndGet));
        assertEquals(1, observed.get());
    }

    /**
     * 规范序多线程竞争不 deadlock（真死锁预防验证）：所有线程同序 → 无 ABBA；若中介引入隐藏的
     * 逆序获取或锁对象被错误共享，竞争将挂起 → 超时失败。
     */
    @Test(timeout = DEADLOCK_TIMEOUT_MS)
    public void canonicalOrderConcurrentNoDeadlock() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger counter = new AtomicInteger();
        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 所有线程均按规范序 PAGE → CONTEXT 获取，争夺同一组监视器但不交叉逆序。
                    LifecycleLockMediator.withPageLock(() ->
                            LifecycleLockMediator.withContextLock(counter::incrementAndGet));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue("锁竞争发生死锁（规范序亦应无 ABBA）", done.await(DEADLOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(THREADS, counter.get());
        pool.shutdownNow();
    }

    /**
     * 进程级共享 Browser 锁与 per-thread Browser 锁各自在多线竞争下不 deadlock（互补交叉验证）。
     */
    @Test(timeout = DEADLOCK_TIMEOUT_MS)
    public void browserLocksConcurrentNoDeadlock() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger counter = new AtomicInteger();
        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    LifecycleLockMediator.withSharedBrowserLock(() ->
                            LifecycleLockMediator.withPerThreadBrowserLock(counter::incrementAndGet));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue("Browser 锁竞争发生死锁", done.await(DEADLOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(THREADS, counter.get());
        pool.shutdownNow();
    }

    // ==================== ② 锁隐藏（编译期不可达的运行期守护） ====================

    /**
     * 三把锁字段 + per-thread 锁键保持 {@code private}：一旦被误升为包级/公开，外部即可
     * {@code synchronized} 引用 → 锁劫持与逆序死锁风险回归，本测试即失败。
     */
    @Test
    public void lockFieldsRemainPrivate() throws Exception {
        for (String name : new String[]{
                "SHARED_BROWSER_LOCK", "CONTEXT_LOCK", "PAGE_LOCK", "BROWSER_LOCK_KEY"}) {
            Field f = LifecycleLockMediator.class.getDeclaredField(name);
            assertTrue(name + " 必须为 private（否则外部可 synchronized 引用 → 锁劫持/逆序死锁）",
                    Modifier.isPrivate(f.getModifiers()));
        }
    }

    /**
     * 中介不对外暴露任何返回锁对象（{@code Object}）的公开方法：泛型 {@code <T>} Supplier 重载经类型擦除
     * 运行期返回 {@code Object}，但属 {@link TypeVariable}（非真实锁对象），予以排除；真实锁访问器
     * 必为 {@code Object} 返回且非泛型 → 一旦新增即被捕获。
     */
    @Test
    public void noPublicLockAccessorExposed() {
        for (Method m : LifecycleLockMediator.class.getMethods()) {
            if (m.getGenericReturnType() instanceof TypeVariable) {
                continue; // 泛型 <T> 擦除为 Object，非锁对象
            }
            assertFalse("公开 API 不得返回锁对象（" + m.getName() + "）",
                    m.getReturnType() == Object.class);
        }
    }
}
