package com.hsbc.cmb.hk.dbb.automation.framework.route.core;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Route;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网络延迟调度器（T2-4 拆分，自 {@code RouteEngine} 提取）。
 *
 * <p>持有全局 {@code DELAY_SCHEDULER}（守护线程池）与 per-context 引擎调度器的选择逻辑：
 * 请求级延迟优先落在所属 BrowserContext 的引擎调度器（context 关闭或不可用时回退全局调度器），
 * 保证「同一 context 的延迟任务互不干扰、context 关闭后其任务不再复活」。
 *
 * <p>懒重建锁沿用 {@code RouteEngine.class}（与原实现完全一致的锁对象，零并发语义变更）；
 * {@code shutdown()} 由 {@code RouteEngine} 的 JVM 关闭钩子与 {@code RouteEngine.shutdown()} 门面触发。
 */
final class DelayScheduler {

    /** 网络延迟调度器（多线程池，支持并发请求同时延迟） */
    private static volatile ScheduledExecutorService DELAY_SCHEDULER = newDelayScheduler();

    /** 标记调度器是否已关闭 */
    private static final AtomicBoolean scheduledShutdown = new AtomicBoolean(false);

    /**
     * 取 route 所属 context 的引擎调度器；context 不可用 / 已停时回退全局调度器。
     */
    static ScheduledExecutorService delayScheduler(Route route) {
        try {
            if (route != null && route.request() != null && route.request().frame() != null
                    && route.request().frame().page() != null) {
                BrowserContext context = route.request().frame().page().context();
                PerContextEngine contextEngine = RouteLifecycleOwner.getOrStartContextEngine(context);
                if (contextEngine.state == EngineState.RUNNING) return contextEngine.delayScheduler();
            }
        } catch (Exception ignored) {
            // Page/Context 已销毁时回退兼容调度器。
        }
        return delayScheduler();
    }

    /**
     * 将动作延迟到「延迟线程」执行（delayMs<=0 则立即执行）。
     * <p>B 方案核心：观测统一在 Playwright 事件线程发起，实际 resume 经本方法调度到延迟线程，
     * 避免事件线程被长时间阻塞、也避免调度线程直接驱动 waitForResponse 的竞态。
     */
    static void scheduleDeferred(Route route, long delayMs, Runnable action) {
        if (action == null) return;
        if (delayMs <= 0) {
            action.run();
            return;
        }
        try {
            delayScheduler(route).schedule(action, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException | NullPointerException e) {
            // 调度器已关闭（常见于 shutdown 后的收尾窗口）：降级为立即执行，
            // 避免延迟动作（如 safeResume）永不触发导致请求永久挂起。
            RouteEngine.LOGGER.debug("[RouteEngine] Deferred scheduler unavailable after shutdown, running action immediately");
            action.run();
        }
    }

    /**  优雅关闭调度器线程池（JVM 退出前 / 显式 shutdown 调用）。 */
    static void shutdown() {
        if (!scheduledShutdown.compareAndSet(false, true)) return;

        RouteEngine.LOGGER.info("[RouteEngine] Shutting down schedulers...");

        //  清理所有上下文路由注册表（含 Playwright 层的 unroute）
        RouteRegistry.clearAll();
        RouteEngine.clearAllMonitorSessions();
        RouteContextState.DISPATCHED_ROUTES.clear();

        // 关闭网络延迟调度器
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

    /** 已 shutdown 则不再懒重建，避免框架拆除后调度器复活、绕过生命周期（修复 C1）。 */
    private static ScheduledExecutorService delayScheduler() {
        ScheduledExecutorService current = DELAY_SCHEDULER;
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

    private static ScheduledExecutorService newDelayScheduler() {
        return Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "route-network-delay");
            t.setDaemon(true);
            return t;
        });
    }
}
