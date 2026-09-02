package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * ⭐ Phase 5 抽离：投递式 API 调用等待器（原 {@code ApiCaptureContext} 的 {@code apiCallWaiters} 域）。
 *
 * <p>点对点投递，替代「广播 notifyAll + 调用方重扫」模式：{@code storeApiCall} 入库时直接评估谓词，
 * 命中即完成对应 future；未命中则等待至超时/重置。本类仅负责等待器注册表的线程安全与投递逻辑，
 * 不触及响应存储域（{@code apiCallsPerUrl} 等仍由调用方在 {@code apiCallLock} 下管理）。
 *
 * <p><b>零行为变更</b>：注册 / 注销 / 投递 / 重置语义与原实现逐字一致；自持独立锁，
 * 与 {@code ApiCaptureContext#apiCallLock} 不再共享同一把锁，但 waiter 注册表自身的互斥语义保持不变。
 */
final class ApiCallAwaiter {

    private final Map<CompletableFuture<CapturedApiCall>, Predicate<CapturedApiCall>> waiters =
            new ConcurrentHashMap<>();
    private final Object lock = new Object();

    /** 注册一次性谓词，返回完成时携带命中调用的 future。 */
    CompletableFuture<CapturedApiCall> register(Predicate<CapturedApiCall> predicate) {
        CompletableFuture<CapturedApiCall> future = new CompletableFuture<>();
        synchronized (lock) {
            waiters.put(future, predicate);
        }
        return future;
    }

    /** 注销等待器（幂等）。 */
    void unregister(CompletableFuture<CapturedApiCall> waiter) {
        if (waiter == null) return;
        synchronized (lock) {
            waiters.remove(waiter);
        }
    }

    /**
     * 入库时投递：评估所有注册谓词，命中即移除并完成对应 future。
     * 谓词必须廉价（仅字段比对），绝不可阻塞。
     */
    void deliver(CapturedApiCall call) {
        synchronized (lock) {
            if (waiters.isEmpty()) return;
            Iterator<Map.Entry<CompletableFuture<CapturedApiCall>, Predicate<CapturedApiCall>>> it =
                    waiters.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<CompletableFuture<CapturedApiCall>, Predicate<CapturedApiCall>> entry = it.next();
                Predicate<CapturedApiCall> predicate = entry.getValue();
                if (predicate != null && predicate.test(call)) {
                    it.remove();
                    entry.getKey().complete(call);
                }
            }
        }
    }

    /** 重置：所有等待器立即以 null 完成（调用方返回 null），避免空等至超时；随后清空注册表。 */
    void reset() {
        synchronized (lock) {
            if (waiters.isEmpty()) return;
            for (CompletableFuture<CapturedApiCall> f : waiters.keySet()) {
                f.complete(null);
            }
            waiters.clear();
        }
    }
}
