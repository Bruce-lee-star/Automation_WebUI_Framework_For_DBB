package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.lock;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

import java.util.function.Supplier;

/**
 * 生命周期锁中介（Mediator，doc16 Phase 3）：<b>集中锁顺序、隐藏锁对象</b>。
 *
 * <h2>为什么需要它（解决真问题，而非装饰）</h2>
 * <p>在此之前 {@code CONTEXT_LOCK}/{@code PAGE_LOCK}/{@code SHARED_BROWSER_LOCK} 是
 * {@code public} 监视器对象，任何包都能按<b>任意顺序</b> {@code synchronized} 它们——
 * 「PAGE_LOCK → CONTEXT_LOCK」的顺序仅靠注释约定，且锁对象可被外部劫持
 * （外部长期持锁即可阻塞框架）。本中介把锁收为 {@code private}，只暴露<b>有序临界区执行器</b>：
 * 调用方无法拿到锁对象，也就无法逆序加锁或劫持。</p>
 *
 * <h2>并发模型（与共享 Browser 模式一致，逐字保持既有语义）</h2>
 * <ul>
 *   <li><b>共享模式</b>（{@code serenity.playwright.shared.browser.enabled=true}）：
 *       单个 Browser 实例被所有 worker 线程共享，隔离性由 per-thread 的 BrowserContext 保证
 *       （Playwright 官方并发模型）。因此 Browser 的<b>创建与类型切换必须进程级互斥</b>
 *       → {@link #withSharedBrowserLock} / {@code withBrowserLock(true, ...)}。</li>
 *   <li><b>非共享模式</b>（默认，T3-2 线程隔离）：每个 worker 线程持有<b>独立的</b> Browser 实例
 *       （key 含 threadId），创建互不阻塞 → {@link #withPerThreadBrowserLock} /
 *       {@code withBrowserLock(false, ...)}。此处<b>不可</b>退化为进程级锁，否则会全局串行化。</li>
 *   <li><b>Context / Page</b>：始终 per-thread，与 Browser 是否共享无关，
 *       故 {@link #withContextLock} / {@link #withPageLock} 不随模式切换。</li>
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

    /**
     * 共享 Browser 模式下的【进程级】互斥锁。
     *
     * <p>该模式下 Browser 被所有 worker 线程共享，必须用真正的静态锁保证并发只有一个 Browser 被创建；
     * per-thread 锁在共享模式下无法提供跨线程互斥（每个线程拿到的都是各自的锁对象）。</p>
     */
    private static final Object SHARED_BROWSER_LOCK = new Object();

    /** Context 细粒度锁：保护 Context 创建/销毁。 */
    private static final Object CONTEXT_LOCK = new Object();

    /** Page 细粒度锁：保护 Page 创建/销毁。 */
    private static final Object PAGE_LOCK = new Object();

    /**
     * per-thread Browser 锁的上下文键（T3-1 收拢：原 {@code ThreadLocal withInitial(Object::new)} 迁入 TestContext）。
     *
     * <p>惰性创建并缓存在上下文中 → 同一线程内多次取到<b>同一锁对象</b>，与原 ThreadLocal 语义一致。
     * 非共享模式下各线程独立 Browser，故用 per-thread 锁避免全局串行化。</p>
     */
    private static final ContextKey<Object> BROWSER_LOCK_KEY =
            ContextKey.of("playwrightManager.browserLock", Object.class);

    private LifecycleLockMediator() {
    }

    /** 取本线程的锁对象（严格等价原 {@code BROWSER_LOCK.get()}，不随共享模式切换）。 */
    private static Object perThreadBrowserLock() {
        return TestContextHolder.get().computeIfAbsent(BROWSER_LOCK_KEY, Object::new);
    }

    // ==================== Browser 锁（按共享模式派发） ====================

    /**
     * 在 Browser 锁保护下执行（按当前 JVM 的共享模式自动派发）。
     *
     * @param block 临界区
     */
    public static void withBrowserLock(Runnable block) {
        withBrowserLock(PlaywrightManager.isSharedBrowserMode(), block);
    }

    /** {@link #withBrowserLock(Runnable)} 的带返回值版本。 */
    public static <T> T withBrowserLock(Supplier<T> block) {
        return withBrowserLock(PlaywrightManager.isSharedBrowserMode(), block);
    }

    /**
     * 在 Browser 锁保护下执行（显式指定共享模式，便于单测覆盖两种模式）。
     *
     * @param sharedMode true 用进程级锁（共享 Browser）；false 用 per-thread 锁（每线程独立 Browser）
     * @param block      临界区
     */
    public static void withBrowserLock(boolean sharedMode, Runnable block) {
        synchronized (sharedMode ? SHARED_BROWSER_LOCK : perThreadBrowserLock()) {
            block.run();
        }
    }

    /** {@link #withBrowserLock(boolean, Runnable)} 的带返回值版本。 */
    public static <T> T withBrowserLock(boolean sharedMode, Supplier<T> block) {
        synchronized (sharedMode ? SHARED_BROWSER_LOCK : perThreadBrowserLock()) {
            return block.get();
        }
    }

    /** 显式在【进程级】共享 Browser 锁内执行（不受共享模式开关影响）。 */
    public static void withSharedBrowserLock(Runnable block) {
        synchronized (SHARED_BROWSER_LOCK) {
            block.run();
        }
    }

    /** {@link #withSharedBrowserLock(Runnable)} 的带返回值版本。 */
    public static <T> T withSharedBrowserLock(Supplier<T> block) {
        synchronized (SHARED_BROWSER_LOCK) {
            return block.get();
        }
    }

    /** 显式在【per-thread】Browser 锁内执行（不受共享模式开关影响）。 */
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
