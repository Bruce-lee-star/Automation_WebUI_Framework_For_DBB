package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.microsoft.playwright.BrowserContext;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * BrowserContext 级生命周期状态。路由规则迁移完成前，先作为 Context 的隔离生命周期边界。
 */
public final class ContextRouteEngine implements AutoCloseable {
    public enum State { RUNNING, CLOSING, CLOSED }

    private final BrowserContext context;
    private final ScheduledThreadPoolExecutor delayScheduler;
    private volatile State state = State.RUNNING;

    ContextRouteEngine(BrowserContext context) {
        this.context = context;
        this.delayScheduler = new ScheduledThreadPoolExecutor(2, runnable -> {
            Thread thread = new Thread(runnable, "route-delay-context");
            thread.setDaemon(true);
            return thread;
        });
        this.delayScheduler.setRemoveOnCancelPolicy(true);
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

    public void close() {
        if (state != State.RUNNING) return;
        state = State.CLOSING;
        delayScheduler.shutdownNow();
        try {
            delayScheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            state = State.CLOSED;
        }
    }
}
