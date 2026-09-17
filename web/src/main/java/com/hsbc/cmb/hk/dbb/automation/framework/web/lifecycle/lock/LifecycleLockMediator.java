package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.lock;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

import java.util.function.Supplier;

/**
 * 生命周期锁中介（Mediator，doc16 Phase 3）：<b>集中锁顺序、隐藏锁对象</b>。
 *
 * <h2>为什么需要它（解决真问题，而非装饰）</h2>
 * <p>在此之前 {@code CONTEXT_LOCK}/{@code PAGE_LOCK} 是 {@code public} 监视器对象，任何包都能按<b>任意顺序</b>
 * {@code synchronized} 它们——「PAGE_LOCK → CONTEXT_LOCK」的顺序仅靠注释约定，且锁对象可被外部劫持
 * （外部长期持锁即可阻塞框架）。本中介把锁收为 {@code private}，只暴露<b>有序临界区执行器</b>：
 * 调用方无法拿到锁对象，也就无法逆序加锁或劫持。</p>
 *
 * <h2>并发模型（每线程独立 Browser）</h2>
 * <ul>
 *   <li><b>Browser</b>：每线程独立 Browser 实例（{@code keyFor} 含 threadId），创建互不阻塞
 *       → 用 per-thread 锁（{@link #withBrowserLock}），<b>不可</b>退化进程级锁，否则全局串行化。</li>
 *   <li><b>Context / Page</b>：<b>进程级锁</b>（{@code CONTEXT_LOCK}/{@code PAGE_LOCK} 为 {@code static final}
 *       单例）。<b>此处曾文档与实现不符（2026-09-17 评审纠正）</b>：原文写作"始终 per-thread"，而实现是进程级。
 *       核对结论：全部调用点（{@code ContextRegistryImpl} / {@code PageRegistryImpl}）操作的都是<b>本线程</b>的
 *       Context/Page（自 {@code TestContextHolder} 读取），故进程级锁在正确性上<b>非必需</b>，代价是并行
 *       （{@code -Pparallel}）下 Context/Page 的创建与关闭<b>全局串行</b> —— 属吞吐税，非正确性问题。
 *       跨线程关闭路径（{@code BrowserCleanupImpl} 遍历 {@code browser.contexts()}）本就<b>不</b>经过本锁，
 *       而是由 {@code ConcurrentContextExecutor.isConcurrentModeActive()} 断言拦在"并发窗口"之外；
 *       因此是否改成 per-thread 属<b>待决策项</b>（可回收该吞吐税，但需先确认无其它跨线程依赖）。</li>
 * </ul>
 *
 * <h2>锁顺序纪律（不变式）</h2>
 * <p>规范顺序为 {@code PAGE_LOCK → CONTEXT_LOCK}。本中介<b>刻意不提供</b>嵌套组合执行器
 * （如 {@code withPageThenContextLock}）：当前全部临界区均为单层，
 * 提前提供嵌套 API 会诱导调用方扩大临界区（长临界区是吞吐与死锁的主要风险源）。
 * 真正的防死锁手段是「Browser 切换时把 closePage/closeContext 移到 Browser 锁<b>之外</b>」
 * （见 {@code BrowserRegistryImpl#handleBrowserTypeSwitch}），该纪律已在调用点保持。</p>
 *
 * <p><b>语义等价性</b>：每个执行器等价于 {@code synchronized (原锁对象) { return block.get(); }}，
 * 锁实例、作用域、可见性均与迁移前一致，仅锁对象不再对外可见。</p>
 *
 * @apiNote <b>框架内部能力</b>：仅供 {@code framework.web.lifecycle} 包树的生命周期协作者使用；
 *          业务代码不得依赖（由 {@code ArchitectureTest} 的 L7 规则构建期守护）。
 */
public final class LifecycleLockMediator {

    /** 进程内唯一中介（无状态，饿汉单例）。 */
    static final LifecycleLockMediator INSTANCE = new LifecycleLockMediator();

    /** Context 细粒度锁：保护 Context 创建/销毁。 */
    private static final Object CONTEXT_LOCK = new Object();

    /** Page 细粒度锁：保护 Page 创建/销毁。 */
    private static final Object PAGE_LOCK = new Object();

    /**
     * per-thread Browser 锁的上下文键（T3-1 收拢：原 {@code ThreadLocal withInitial(Object::new)} 迁入 TestContext）。
     *
     * <p>惰性创建并缓存在上下文中 → 同一线程内多次取到<b>同一锁对象</b>，与原 ThreadLocal 语义一致。
     * 每线程独立 Browser 模型下各线程独立 Browser，故用 per-thread 锁避免全局串行化。</p>
     */
    private static final ContextKey<Object> BROWSER_LOCK_KEY =
            ContextKey.of("playwrightManager.browserLock", Object.class);

    private LifecycleLockMediator() {
    }

    /** 取本线程的锁对象（严格等价原 {@code BROWSER_LOCK.get()}）。 */
    private static Object perThreadBrowserLock() {
        return TestContextHolder.get().computeIfAbsent(BROWSER_LOCK_KEY, Object::new);
    }

    // ==================== Browser 锁（per-thread，每线程独立 Browser 模型） ====================

    /**
     * 在 Browser 锁（per-thread）保护下执行。
     *
     * <p>每线程独立 Browser 模型下，Browser 创建 / 类型切换在<b>本线程</b>锁内完成，
     * 不退化进程级锁（否则会全局串行化并发）。</p>
     *
     * @param block 临界区
     */
    public static void withBrowserLock(Runnable block) {
        synchronized (perThreadBrowserLock()) {
            block.run();
        }
    }

    /** {@link #withBrowserLock(Runnable)} 的带返回值版本。 */
    public static <T> T withBrowserLock(Supplier<T> block) {
        synchronized (perThreadBrowserLock()) {
            return block.get();
        }
    }

    /** 显式在【per-thread】Browser 锁内执行（与 {@link #withBrowserLock} 等价，便于语义化调用点）。 */
    public static void withPerThreadBrowserLock(Runnable block) {
        synchronized (perThreadBrowserLock()) {
            block.run();
        }
    }

    /** {@link #withPerThreadBrowserLock(Runnable)} 的带返回值版本。 */
    public static <T> T withPerThreadBrowserLock(Supplier<T> block) {
        synchronized (perThreadBrowserLock()) {
            return block.get();
        }
    }

    // ==================== Context / Page 锁 ====================

    /** 在 Context 锁保护下执行。 */
    public static void withContextLock(Runnable block) {
        synchronized (CONTEXT_LOCK) {
            block.run();
        }
    }

    /** {@link #withContextLock(Runnable)} 的带返回值版本。 */
    public static <T> T withContextLock(Supplier<T> block) {
        synchronized (CONTEXT_LOCK) {
            return block.get();
        }
    }

    /** 在 Page 锁保护下执行。 */
    public static void withPageLock(Runnable block) {
        synchronized (PAGE_LOCK) {
            block.run();
        }
    }

    /** {@link #withPageLock(Runnable)} 的带返回值版本。 */
    public static <T> T withPageLock(Supplier<T> block) {
        synchronized (PAGE_LOCK) {
            return block.get();
        }
    }
}
