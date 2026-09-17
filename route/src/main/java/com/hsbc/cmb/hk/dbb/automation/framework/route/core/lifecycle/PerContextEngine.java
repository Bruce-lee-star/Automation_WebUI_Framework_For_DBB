package com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.common.async.AsyncPool;
import com.microsoft.playwright.BrowserContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.DelayScheduler;

/**
 * 每个 BrowserContext 独立的路由引擎实例：持有 per-Context 延迟调度器，管理其生命周期状态。
 *
 * <p>原内联于 {@code RouteEngine} 的 private 内部类（T2-4 拆分提取为同包顶层类），
 * 使 {@code RouteContextState} 得以集中持有 {@code CONTEXT_ENGINES} 注册表。
 *
 * <p><b>并发/生命周期评审修复（2026-09-17）</b>：
 * <ol>
 *   <li>{@code contextId} 改为「自增序号 + identityHashCode」——它是 {@code AsyncPool.CONTEXT_SCHEDULERS}
 *       的 key，而该 Map 用「覆盖 put / 按 key remove」维护，key 不唯一会导致旧池失去跟踪、
 *       remove 误删他池条目（泄漏判据失真）；</li>
 *   <li>{@link #close()} 在 {@code shutdownNow()} 之后<b>立即执行被取消的 DELAY 任务</b>——
 *       DELAY 任务的动作是 {@code route.resume()}（放行请求），被取消即代表该请求永不 resume，
 *       会一直挂到 Playwright 超时（详见 {@link DelayScheduler#drainAbandonedDelayTasks}）。</li>
 * </ol>
 *
 * @apiNote framework-internal：框架内部类型，非公开 API。跨子包 public 可见性仅为分层迁移需要，外部不得依赖。
 */
public final class PerContextEngine {

    /**
     * 全局自增序号（36 进制），保证 {@link #contextId} 唯一。
     *
     * <p>原实现只用 {@code System.identityHashCode(context)}：该值<b>不保证唯一</b>（32 位、按对象地址派生），
     * 一旦两个存活 context 碰撞，{@code AsyncPool.CONTEXT_SCHEDULERS} 的 put 会覆盖旧条目、
     * {@code removeContextScheduler} 会误删他池条目 —— 表现为「活跃 context 调度器数」失真、
     * 真泄漏被漏报。序号保证唯一，hash 保留在 id 中便于日志排查。
     */
    private static final AtomicLong CONTEXT_SEQ = new AtomicLong();

    final BrowserContext context;
    final String contextId;
    final ScheduledThreadPoolExecutor delayScheduler;

    /**
     * 引擎生命周期状态。
     * <p><b>评审收口（2026-09-17）</b>：原为 {@code public volatile} 字段——外部可任意改写引擎状态
     * （SpotBugs PA_PUBLIC_PRIMITIVE_ATTRIBUTE），已改为私有 + {@link #state()} 只读访问器，
     * 状态迁移唯一由本类的 {@link #close()} / {@code delayScheduler()} 控制。
     */
    private volatile EngineState state = EngineState.RUNNING;

    public PerContextEngine(BrowserContext context) {
        this.context = context;
        this.contextId = Long.toString(CONTEXT_SEQ.incrementAndGet(), 36)
                + "-" + Integer.toHexString(System.identityHashCode(context));
        this.delayScheduler = AsyncPool.newContextScheduler(contextId, 2);
    }

    public ScheduledExecutorService delayScheduler() {
        if (state != EngineState.RUNNING) {
            throw new IllegalStateException("Context route engine is not running");
        }
        return delayScheduler;
    }

    /** 当前引擎状态（只读访问器；状态迁移由本类独占控制）。 */
    public EngineState state() {
        return state;
    }

    /**
     * 优雅关闭：取出待执行的 DELAY 任务 → 关池 → 立即执行它们，从而<b>即时归还线程</b>且<b>必不放行请求</b>。
     *
     * <p><b>为何不能只调 shutdown()/shutdownNow()</b>：{@code ScheduledThreadPoolExecutor} 的
     * {@code DelayedWorkQueue.drainTo} 只排空<b>已到期</b>任务，且默认
     * {@code executeExistingDelayedTasksAfterShutdownPolicy = true} —— 两者叠加的结果是：
     * 尚未到期的 DELAY 任务<b>既不会被取消、也不会被 shutdownNow 返回</b>，而是被<b>保留到原定延时后才执行</b>。
     * 对本类而言这意味着：{@code close()} 已把池移出 {@code AsyncPool} 跟踪表，而池与其线程仍会存活到
     * 那一刻（长 DELAY 下长期滞留，且因已不在跟踪表中而<b>不可观测</b>）。
     *
     * <p>这些任务的动作是 {@code route.resume()}（放行被拦截请求），所以既不能丢、也不值得为它滞留线程：
     * 本方法显式取出队列、清空、关闭池，再立即执行取出的任务 —— 早 resume 只是把「延迟」变短，
     * 请求必被放行，线程即时归还。
     */
    public void close() {
        if (state != EngineState.RUNNING) return;
        state = EngineState.CLOSING;

        //  顺序很关键：必须在池【仍处于 RUNNING】时取出并执行待发的 DELAY 任务。
        //  ScheduledThreadPoolExecutor 队列里的是 ScheduledFutureTask，其 run() 会先按
        //  「executeExistingDelayedTasksAfterShutdownPolicy + 池状态」自判是否执行：
        //  池进入 SHUTDOWN 且策略为 false、或进入 STOP（shutdownNow）时，run() 会【自我取消】
        //  —— 于是"取出后再关闭、最后执行"的写法等于把任务丢掉（实测复现：被拦请求永不 resume）。
        //  故：① 先取队列并立即执行（延迟被截短，但请求必被放行）→ ② 再关池，等在途任务自然结束。
        List<Runnable> pending = new ArrayList<>(delayScheduler.getQueue());
        delayScheduler.getQueue().clear();
        DelayScheduler.drainAbandonedDelayTasks(pending, "per-context engine " + contextId);

        delayScheduler.shutdown();
        try {
            if (!delayScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                delayScheduler.shutdownNow();
                delayScheduler.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            delayScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            AsyncPool.removeContextScheduler(contextId);
            state = EngineState.CLOSED;
        }
    }
}
