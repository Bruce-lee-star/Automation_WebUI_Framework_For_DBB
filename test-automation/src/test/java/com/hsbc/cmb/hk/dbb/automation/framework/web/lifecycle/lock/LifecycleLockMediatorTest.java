package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.lock;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.lock.LifecycleLockMediator;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc16 §9.4 / §11.2 偏差 A 收口：锁顺序 + 锁隐藏守护（纯并发单测，零浏览器依赖）。
 *
 * <p><b>背景</b>：doc16 Phase 3 把 {@code CONTEXT_LOCK}/{@code PAGE_LOCK}
 * 由 {@code public} 监视器收为 {@code private}（{@link LifecycleLockMediator} 内部），只暴露
 * 有序临界区执行器。本测试固化两条不变式：</p>
 * <ol>
 *   <li><b>锁顺序不 deadlock</b>：规范序（PAGE → CONTEXT）多线程竞争无 ABBA；逆规范序的单线程嵌套
 *       （CONTEXT 内再 PAGE）必然可完成（单线程持两把不同监视器不会自死锁）。</li>
 *   <li><b>锁隐藏（编译期不可达的运行期守护）</b>：两把锁（CONTEXT/PAGE）+ per-thread 锁键字段保持 {@code private}；
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
    @Test
    public void canonicalOrderConcurrentNoDeadlock() throws InterruptedException {
        assertTimeoutPreemptively(Duration.ofMillis(DEADLOCK_TIMEOUT_MS), () -> {
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
            assertTrue(done.await(DEADLOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS), "锁竞争发生死锁（规范序亦应无 ABBA）");
            assertEquals(THREADS, counter.get());
            pool.shutdownNow();
        });
    }

    // ==================== ② 锁隐藏（编译期不可达的运行期守护） ====================

    /**
     * 三把锁（全部 per-thread，2026-09-21 起）的锁键字段保持 {@code private}：一旦被误升为包级/公开，
     * 外部即可 {@code synchronized} 引用 → 锁劫持与逆序死锁风险回归，本测试即失败。
     */
    @Test
    public void lockFieldsRemainPrivate() throws Exception {
        for (String name : new String[]{
                "CONTEXT_LOCK_KEY", "PAGE_LOCK_KEY", "BROWSER_LOCK_KEY"}) {
            Field f = LifecycleLockMediator.class.getDeclaredField(name);
            assertTrue(Modifier.isPrivate(f.getModifiers()),
                    name + " 必须为 private（否则外部可 synchronized 引用 → 锁劫持/逆序死锁）");
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
            assertFalse(
                    m.getReturnType() == Object.class, "公开 API 不得返回锁对象（" + m.getName() + "）");
        }
    }

    // ==================== ③ per-thread 隔离（2026-09-21 修复守护） ====================

    /** 持锁线程在临界区内停留的时长（模拟"建 Context / 启动 Chrome"慢 I/O，远大于邻居允许的等待）。 */
    private static final long HOLD_MS = 1000;

    /** 邻居线程进入同一执行器允许的最大等待；若锁退化为进程级，实测将≈{@link #HOLD_MS}。 */
    private static final long NEIGHBOR_MAX_WAIT_MS = 400;

    /**
     * PAGE 锁必须 per-thread：一个线程在临界区内长时间停留（建 Context / 启动 Chrome），
     * <b>不得</b>阻塞另一个线程（另一个 scenario 的取页/收尾关窗）。
     *
     * <p><b>回归背景（2026-09-21）</b>：{@code PAGE_LOCK} 原为进程级单例。实测 {@code -Pparallel -Dparallelism=8}
     * 下 {@code jstack} 显示 <b>7 个 worker 同时 {@code waiting to lock <同一个 Object>}</b>，持锁者临界区内
     * 在执行 {@code getContext → createContext → getBrowser → initializeBrowser}；后果是引擎级并行被完全
     * 串行化，且先跑完的用例关不了窗（8 个 Context 关闭挤在末尾 0.3s 内爆发）。本测试固化修复：锁 per-thread。</p>
     */
    @Test
    public void pageLockIsPerThreadNeighborNotBlocked() throws Exception {
        assertNeighborNotBlocked(LifecycleLockMediator::withPageLock, "PAGE");
    }

    /** CONTEXT 锁同样必须 per-thread（同类回归防护）。 */
    @Test
    public void contextLockIsPerThreadNeighborNotBlocked() throws Exception {
        assertNeighborNotBlocked(LifecycleLockMediator::withContextLock, "CONTEXT");
    }

    /** BROWSER 锁同样必须 per-thread（每线程独立 Browser 模型的既有契约）。 */
    @Test
    public void browserLockIsPerThreadNeighborNotBlocked() throws Exception {
        assertNeighborNotBlocked(LifecycleLockMediator::withBrowserLock, "BROWSER");
    }

    /**
     * 通用断言：holder 线程进入临界区后停留 {@link #HOLD_MS}，邻居线程必须能在
     * {@link #NEIGHBOR_MAX_WAIT_MS} 内进入同一执行器（进程级锁会让它等到 holder 释放）。
     */
    private void assertNeighborNotBlocked(Consumer<Runnable> executor, String label) throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> executor.accept(() -> {
            holding.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }), "holder-" + label);
        holder.setDaemon(true);
        holder.start();
        try {
            assertTrue(holding.await(5, TimeUnit.SECONDS), label + " 锁持有线程未按时进入临界区");

            long start = System.nanoTime();
            executor.accept(() -> { });
            long waitedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(waitedMs < NEIGHBOR_MAX_WAIT_MS,
                    label + " 锁必须 per-thread：邻居线程不应被持锁线程阻塞，实测等待 " + waitedMs
                            + "ms（阈值 " + NEIGHBOR_MAX_WAIT_MS + "ms，持锁 " + HOLD_MS + "ms）");
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }
}
