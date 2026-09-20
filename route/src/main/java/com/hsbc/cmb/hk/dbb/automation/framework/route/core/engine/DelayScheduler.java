package com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Route;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle.EngineState;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle.PerContextEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle.RouteLifecycleOwner;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRegistry;

/**
 * 网络延迟调度器（T2-4 拆分，自 {@code RouteEngine} 提取）。
 *
 * <p>持有全局 {@code DELAY_SCHEDULER}（守护线程池）与 per-context 引擎调度器的选择逻辑：
 * 请求级延迟优先落在所属 BrowserContext 的引擎调度器（context 关闭或不可用时回退全局调度器），
 * 保证「同一 context 的延迟任务互不干扰、context 关闭后其任务不再复活」。
 *
 * <p>懒重建锁沿用 {@code RouteEngine.class}（与原实现完全一致的锁对象，零并发语义变更）；
 * {@code shutdown()} 由 {@code RouteEngine} 的 JVM 关闭钩子与 {@code RouteEngine.shutdown()} 门面触发。
 *
 * @apiNote framework-internal：框架内部类型，非公开 API。跨子包 public 可见性仅为分层迁移需要，外部不得依赖。
 */
public final class DelayScheduler {

    /**
     * 网络延迟调度器（多线程池，支持并发请求同时延迟）。
     * <p>具体类型保留 {@link ScheduledThreadPoolExecutor}（而非接口）以便关闭时排空「尚未到期」的待执行队列。
     */
    private static volatile ScheduledThreadPoolExecutor DELAY_SCHEDULER = newDelayScheduler();

    /** 标记调度器是否已关闭 */
    private static final AtomicBoolean scheduledShutdown = new AtomicBoolean(false);

    /**
     * 取 route 所属 context 的引擎调度器；context 不可用 / 已停时回退全局调度器。
     */
    static ScheduledExecutorService delayScheduler(Route route) {
        //  评审修复：全局已拆除时不再创建 per-context 引擎 —— 否则会在 AsyncPool.shutdown() 之后
        //  注册新的 per-context 池，而该池不再被任何扫描关闭（与 C1 修复的"全局池拆除后复活"同源，
        //  只是当初漏了 per-context 路径）。回退已关闭的全局池即可：scheduleDeferred 会捕获
        //  RejectedExecutionException 并立即执行动作（失败安全，请求不会挂起）。
        if (scheduledShutdown.get()) {
            return delayScheduler();
        }
        try {
            if (route != null && route.request() != null && route.request().frame() != null
                    && route.request().frame().page() != null) {
                BrowserContext context = route.request().frame().page().context();
                PerContextEngine contextEngine = RouteLifecycleOwner.getOrStartContextEngine(context);
                if (contextEngine.state() == EngineState.RUNNING)  {return contextEngine.delayScheduler();} 
            }
        } catch (Exception e) {
            // Page/Context 已销毁时回退兼容调度器（生命周期收尾期预期竞争，但不得静默，D7-3）
            RouteEngine.LOGGER.debug("[RouteEngine] delayScheduler: context engine unavailable, "
                    + "fallback to global scheduler: {}", e.toString());
        }
        return delayScheduler();
    }

    /**
     * 将动作延迟到「延迟线程」执行（delayMs<=0 则立即执行）。
     * <p>B 方案核心：观测统一在 Playwright 事件线程发起，实际 resume 经本方法调度到延迟线程，
     * 避免事件线程被长时间阻塞、也避免调度线程直接驱动 waitForResponse 的竞态。
     */
    static void scheduleDeferred(Route route, long delayMs, Runnable action) {
        if (action == null)  {return;} 
        if (delayMs <= 0) {
            action.run();
            return;
        }
        ScheduledExecutorService scheduler = delayScheduler(route);
        //  评审修复：原以 `catch (RejectedExecutionException | NullPointerException)` 兜底，其中 NPE
        //  依赖「调度器为 null 时调用 .schedule() 抛 NPE」这一隐式行为（SpotBugs DCN_NULLPOINTER_EXCEPTION），
        //  把正常控制流建在异常上。改为**显式判定**：null / 已关闭一律直接放行（失败安全：绝不丢 resume），
        //  只捕获真实存在的 RejectedExecutionException（提交与关闭并发时的窗口）。
        if (scheduler == null || scheduler.isShutdown()) {
            RouteEngine.LOGGER.debug("[RouteEngine] Deferred scheduler unavailable ({}), running action immediately",
                    scheduler == null ? "null" : "shutdown");
            action.run();
            return;
        }
        try {
            scheduler.schedule(action, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            RouteEngine.LOGGER.debug("[RouteEngine] Deferred scheduler rejected task, running action immediately");
            action.run();
        }
    }

    /**  优雅关闭调度器线程池（JVM 退出前 / 显式 shutdown 调用）。 */
    static void shutdown() {
        if (!scheduledShutdown.compareAndSet(false, true))  {return;} 

        RouteEngine.LOGGER.info("[RouteEngine] Shutting down schedulers...");

        //  清理所有上下文路由注册表（含 Playwright 层的 unroute）
        RouteRegistry.clearAll();
        RouteEngine.clearAllMonitorSessions();
        RouteContextState.DISPATCHED_ROUTES.clear();

        // 关闭网络延迟调度器（评审修复，与 PerContextEngine.close() 同口径）。
        // 顺序关键：必须趁池仍 RUNNING 时取出并执行待发 DELAY 任务 —— 队列中是 ScheduledFutureTask，
        // 池关闭（SHUTDOWN+策略 false，或 STOP）后其 run() 会自我取消，等于把放行动作丢掉。
        // 若不先取：ScheduledThreadPoolExecutor 默认会把未到期任务保留到原定延时后才执行
        //（shutdownNow() 既不返回也不取消它们），池与线程随之滞留到那一刻。
        List<Runnable> pending = new ArrayList<>(DELAY_SCHEDULER.getQueue());
        DELAY_SCHEDULER.getQueue().clear();
        drainAbandonedDelayTasks(pending, "global DELAY_SCHEDULER");
        DELAY_SCHEDULER.shutdownNow();
        try {
            if (!DELAY_SCHEDULER.awaitTermination(2, TimeUnit.SECONDS)) {
                RouteEngine.LOGGER.warn("[RouteEngine] DELAY_SCHEDULER did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 超时调度已委托通用异步池 AsyncPool，由其在 JVM 关闭钩子/显式 shutdown 时统一关闭
        RouteEngine.LOGGER.info("[RouteEngine] All schedulers shut down");
    }

    /**
     * 立即执行关闭时从调度器队列取出、尚未到期的延迟任务（失败安全）。
     *
     * <p><b>为什么必须执行而不是丢弃</b>：{@link #scheduleDeferred} 调度的动作是
     * {@code RouteUtil.safeResume(route)}（放行被拦截的请求）。丢弃即该请求<b>既不会 resume 也不会
     * continue</b>，会一直挂到 Playwright 的请求/导航超时（现象是用例卡死或莫名导航超时，且与根因
     * 完全脱节）。立即执行只把「延迟」变短，语义上仍是放行 —— 早 resume 远优于挂着不放。
     *
     * <p><b>为什么由调用方显式取队列，而不是等 {@code shutdownNow()} 返回</b>：
     * {@code ScheduledThreadPoolExecutor} 的 {@code DelayedWorkQueue.drainTo} 只排空<b>已到期</b>任务，
     * 且默认 {@code executeExistingDelayedTasksAfterShutdownPolicy=true} —— 未到期任务既不会被取消、
     * 也不会被返回，而是<b>保留到原定延时后才执行</b>（池与线程随之后延释放）。调用方
     * （{@code PerContextEngine.close()} / {@link #shutdown()}）故先取队列、再关池，把「池与线程的存活期」
     * 从「原定延迟」压缩到「即刻」。
     *
     * <p><b>调用时机约束（踩坑记录）</b>：取出的元素是 {@code ScheduledFutureTask}，其 {@code run()} 会先按
     * 「策略 + 池运行状态」自判：池处于 SHUTDOWN 且策略为 false、或已进入 STOP（{@code shutdownNow}）时，
     * {@code run()} 会<b>自我取消</b>而不执行动作。因此必须<b>趁池仍 RUNNING 时</b>执行本方法
     * （先取队列 → 调本方法 → 再关池），否则等于把放行动作丢掉（实测：请求永不 resume）。
     *
     * <p>注：{@link #scheduleDeferred} 的 catch 只覆盖「<b>提交时</b>被拒」（{@code RejectedExecutionException}），
     * 覆盖不到「<b>已入队</b>的任务」，故此处是必要补充。
     *
     * @param abandoned 关闭前从队列取出的待执行任务（可为空）
     * @param source    调用来源，仅用于日志定位（如 {@code "per-context engine 3f-a1b2"}）
     */
    public static void drainAbandonedDelayTasks(List<Runnable> abandoned, String source) {
        if (abandoned == null || abandoned.isEmpty()) {
            return;
        }
        RouteEngine.LOGGER.warn("[RouteEngine] {} closed with {} pending DELAY task(s) — running them "
                + "immediately to avoid hanging intercepted requests", source, abandoned.size());
        for (Runnable task : abandoned) {
            try {
                task.run();
            } catch (Throwable t) {
                // 单个任务失败不影响其余；不得静默（D7-3）
                RouteEngine.LOGGER.warn("[RouteEngine] pending DELAY task failed during drain ({}): {}",
                        source, t.toString());
            }
        }
    }

    /**
     * 取全局延迟调度器（懒重建）。
     * <p>已 shutdown 则不再重建，避免框架拆除后调度器复活、绕过生命周期（修复 C1）。
     * <p>返回具体类型 {@link ScheduledThreadPoolExecutor}：关闭路径需排空其「未到期」任务队列
     * （见 {@link #shutdown()} 与 {@link #drainAbandonedDelayTasks}）。
     */
    private static ScheduledThreadPoolExecutor delayScheduler() {
        ScheduledThreadPoolExecutor current = DELAY_SCHEDULER;
        if (current != null && !current.isShutdown() && !current.isTerminated()) {
            return current;
        }
        if (scheduledShutdown.get()) {
            return current;
        }
        synchronized (RouteEngine.class) {
            current = DELAY_SCHEDULER;
            if (current != null && !current.isShutdown() && !current.isTerminated()) {
                return current;
            }
            if (scheduledShutdown.get()) {
                return current;
            }
            DELAY_SCHEDULER = current = newDelayScheduler();
        }
        return current;
    }

    private static ScheduledThreadPoolExecutor newDelayScheduler() {
        return new ScheduledThreadPoolExecutor(4, r -> {
            Thread t = new Thread(r, "route-network-delay");
            t.setDaemon(true);
            return t;
        });
    }
}
