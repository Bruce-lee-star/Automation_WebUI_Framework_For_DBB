package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.claim;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 挂起额度守卫 —— 限制同一 context 内「等待 IO 延迟终结」的请求数。
 *
 * <p>为什么必要（对应 playwright-java-1.62.0 源码事实）：handler 返回未终结时驱动返回
 * {@code Router.HandleResult.PendingHandler}，请求在浏览器侧**无限期挂起**直到终结。
 * 若 IO 线程积压（慢 fetch / 队列拥塞），页面批量请求会全部挂起导致假死。因此挂起
 * 必须是有额度的：额度耗尽时新请求立即 fallback（fail-open 放行）。
 *
 * <p>并发安全：{@link AtomicInteger} 计数，{@link #tryAcquire()} 与 {@link #release()}
 * 一一对应，{@code release} 有下限保护（不会因重复释放把计数打成负数）。
 */
public final class PendingGuard {

    private final int maxPending;
    private final AtomicInteger pending = new AtomicInteger(0);

    public PendingGuard(int maxPending) {
        if (maxPending <= 0) {
            throw new IllegalArgumentException("maxPending must be > 0");
        }
        this.maxPending = maxPending;
    }

    public int maxPending() {
        return maxPending;
    }

    /** 当前挂起数。 */
    public int pendingCount() {
        return pending.get();
    }

    /** 尝试占用一个挂起额度。 */
    public boolean tryAcquire() {
        while (true) {
            int cur = pending.get();
            if (cur >= maxPending) {
                return false;
            }
            if (pending.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    /** 释放一个挂起额度（带下限保护）。 */
    public void release() {
        while (true) {
            int cur = pending.get();
            if (cur <= 0) {
                return;
            }
            if (pending.compareAndSet(cur, cur - 1)) {
                return;
            }
        }
    }
}
