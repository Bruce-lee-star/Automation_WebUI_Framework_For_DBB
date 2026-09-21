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
 * <h2>并发模型（每线程独立 Browser / Context / Page —— 三类锁<b>全部 per-thread</b>）</h2>
 * <ul>
 *   <li><b>三类资源均为每线程独立</b>：Browser 键含 threadId；Context/Page 存于 {@code TestContextHolder}
 *       与线程级记录。故三类锁一律 <b>per-thread</b>（{@link #withBrowserLock}/{@link #withPageLock}/
 *       {@link #withContextLock}），创建与关闭互不阻塞，<b>不可</b>退化进程级锁。</li>
 *   <li><b>2026-09-21 决策：CONTEXT/PAGE 由进程级单例改为 per-thread</b>（回收原 javadoc 所记的"待决策项"）。
 *       <b>决策依据（实测，非推断）</b>：{@code -Pparallel -Dparallelism=8} 下 ——<br>
 *       ① {@code jstack} 显示 <b>7 个 worker 同时 {@code waiting to lock <同一个 java.lang.Object>}</b>
 *       （即进程级 {@code PAGE_LOCK}），持锁者临界区内在执行
 *       {@code getContext → createContext → getBrowser → initializeBrowser}（Chrome 启动，1~10s）；<br>
 *       ② 日志时间线显示 launch 严格串行（下一个 scenario 要等前一个 cleanup 完才 launch），
 *       且 8 个 Context 关闭挤在末尾 0.3s 内爆发 —— 「先跑完的用例关不了窗，最后一起关」。<br>
 *       即：进程级锁把这些慢 I/O 变成<b>全局串行</b>（引擎级并行形同虚设），收尾关窗又被同锁排队。
 *       而全部调用点（{@code ContextRegistryImpl} / {@code PageRegistryImpl}）操作的都是<b>本线程</b>状态，
 *       跨线程关闭路径（{@code BrowserCleanupImpl} 遍历 {@code browser.contexts()}）本就<b>不</b>经本锁，
 *       故进程级锁在正确性上<b>非必需</b>。改为 per-thread 后仍保留「锁对象不可外泄」纪律与
 *       「同线程临界区」语义，仅不再跨线程互斥 —— 与"哪个线程做完就关它自己的"这一生命周期契约一致。</li>
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
     * per-thread Context 锁的上下文键（2026-09-21：由进程级单例改为 per-thread，见类注释决策依据）。
     *
     * <p>与 {@link #BROWSER_LOCK_KEY} 同款机制：惰性创建并缓存在用例级上下文中 → 同一线程内多次取到
     * <b>同一锁对象</b>（保持原语义），不同线程各自独立 → 一个线程的 Context 创建/关闭不再阻塞邻居线程。</p>
     */
    private static final ContextKey<Object> CONTEXT_LOCK_KEY =
            ContextKey.of("playwrightManager.contextLock", Object.class);

    /** per-thread Page 锁的上下文键（同 {@link #CONTEXT_LOCK_KEY}）。 */
    private static final ContextKey<Object> PAGE_LOCK_KEY =
            ContextKey.of("playwrightManager.pageLock", Object.class);

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

    /** 取本线程的 Context 锁对象（per-thread，2026-09-21 起）。 */
    private static Object perThreadContextLock() {
        return TestContextHolder.get().computeIfAbsent(CONTEXT_LOCK_KEY, Object::new);
    }

    /** 取本线程的 Page 锁对象（per-thread，2026-09-21 起）。 */
    private static Object perThreadPageLock() {
        return TestContextHolder.get().computeIfAbsent(PAGE_LOCK_KEY, Object::new);
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

    // ==================== Context / Page 锁（均为 per-thread，2026-09-21 起） ====================

    /**
     * 在 Context 锁（per-thread）保护下执行。
     *
     * <p>保护的是<b>本线程</b>的 Context 创建/销毁；邻居线程的同类操作互不阻塞（曾为进程级单例，
     * 导致引擎级并行被全局串行化 + 收尾关窗排队，见类注释决策依据）。</p>
     */
    public static void withContextLock(Runnable block) {
        synchronized (perThreadContextLock()) {
            block.run();
        }
    }

    /** {@link #withContextLock(Runnable)} 的带返回值版本。 */
    public static <T> T withContextLock(Supplier<T> block) {
        synchronized (perThreadContextLock()) {
            return block.get();
        }
    }

    /**
     * 在 Page 锁（per-thread）保护下执行。
     *
     * <p>与 {@link #withContextLock} 同理：本线程 Page 的创建/关闭不再阻塞邻居线程 ——
     * 「哪个线程执行完毕就关闭它自己的 Page/Context/Browser」由此得以即时生效，而非排在队尾统一发生。</p>
     */
    public static void withPageLock(Runnable block) {
        synchronized (perThreadPageLock()) {
            block.run();
        }
    }

    /** {@link #withPageLock(Runnable)} 的带返回值版本。 */
    public static <T> T withPageLock(Supplier<T> block) {
        synchronized (perThreadPageLock()) {
            return block.get();
        }
    }
}
