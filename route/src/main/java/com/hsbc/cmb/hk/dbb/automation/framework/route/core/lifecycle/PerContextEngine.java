package com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.common.async.AsyncPool;
import com.microsoft.playwright.BrowserContext;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.DelayScheduler;

/**
 * 每个 BrowserContext 独立的路由引擎实例：持有 per-Context 延迟调度器，管理其生命周期状态。
 *
 * <p>原内联于 {@code RouteEngine} 的 private 内部类（T2-4 拆分提取为同包顶层类），
 * 使 {@code RouteContextState} 得以集中持有 {@code CONTEXT_ENGINES} 注册表。
 *
 * @apiNote framework-internal：框架内部类型，非公开 API。跨子包 public 可见性仅为分层迁移需要，外部不得依赖。
 */
public final class PerContextEngine {

    final BrowserContext context;
    final String contextId;
    final ScheduledThreadPoolExecutor delayScheduler;
    public volatile EngineState state = EngineState.RUNNING;

    public PerContextEngine(BrowserContext context) {
        this.context = context;
        this.contextId = Integer.toHexString(System.identityHashCode(context));
        this.delayScheduler = AsyncPool.newContextScheduler(contextId, 2);
    }

    public ScheduledExecutorService delayScheduler() {
        if (state != EngineState.RUNNING) {
            throw new IllegalStateException("Context route engine is not running");
        }
        return delayScheduler;
    }

    /** 优雅关闭：不中断在途 DELAY 任务（），让其 sleep 结束自然 resume。 */
    public void close() {
        if (state != EngineState.RUNNING) return;
        state = EngineState.CLOSING;
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
