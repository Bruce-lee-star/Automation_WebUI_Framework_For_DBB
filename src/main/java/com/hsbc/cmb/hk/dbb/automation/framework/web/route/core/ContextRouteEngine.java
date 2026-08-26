package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.common.async.AsyncPool;
import com.microsoft.playwright.BrowserContext;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
/**
 * BrowserContext 级生命周期状态。路由规则迁移完成前，先作为 Context 的隔离生命周期边界。
 *
 * <p>延迟调度器复用 {@link AsyncPool} 的线程工厂规范（守护线程、{@code async-ctx-<id>} 命名、
 * {@code removeOnCancel}），但保持 per-Context 独立生命周期——context 关闭即 shutdown，避免跨 context 干扰。
 */
public final class ContextRouteEngine implements AutoCloseable {
    public enum State { RUNNING, CLOSING, CLOSED }

    private final BrowserContext context;
    private final String contextId;
    private final ScheduledThreadPoolExecutor delayScheduler;
    private volatile State state = State.RUNNING;

    ContextRouteEngine(BrowserContext context) {
        this.context = context;
        this.contextId = Integer.toHexString(System.identityHashCode(context));
        // 复用 AsyncPool 的线程工厂范式，但独立生命周期
        this.delayScheduler = AsyncPool.newContextScheduler(contextId, 2);
    }

    public BrowserContext context() {
        return context;
    }

    public State state() {
        return state;
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public ScheduledExecutorService delayScheduler() {
        if (!isRunning()) {
            throw new IllegalStateException("Context route engine is not running");
        }
        return delayScheduler;
    }

    /**
     * 关闭引擎，释放路由与调度器资源。幂等：非 RUNNING 状态直接返回。
     *
     * <p>修复 P0-6：原实现用 {@code shutdownNow()} 暴力中断所有在途 DELAY 任务。若延迟任务正处于
     * {@code sleep(DELAY)} 期间被中断，会抛出 {@link InterruptedException} 导致 {@code route.resume()}
     * 永不执行，对应请求永久挂起 → page.waitForLoadState() 超时 → 后续测试全部卡死。
     * 现改为 {@code shutdown()} + 有限等待：允许已提交的延迟任务在其 sleep 结束后自然执行 resume 放行，
     * 仅当等待窗口超时（极端场景，DELAY 远大于窗口）才强制 shutdownNow 兜底，避免请求悬挂。</p>
     */
    public void close() {
        if (state != State.RUNNING) return;
        state = State.CLOSING;
        // 优雅关闭：不中断在途 DELAY 任务，让其 sleep 结束自然 resume（修复 P0-6）
        delayScheduler.shutdown();
        try {
            if (!delayScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                // 极端场景：仍有延迟任务未完成（如 DELAY 远超 3s），强制中断兜底
                delayScheduler.shutdownNow();
                delayScheduler.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            delayScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            AsyncPool.removeContextScheduler(contextId);
            state = State.CLOSED;
        }
    }
}
