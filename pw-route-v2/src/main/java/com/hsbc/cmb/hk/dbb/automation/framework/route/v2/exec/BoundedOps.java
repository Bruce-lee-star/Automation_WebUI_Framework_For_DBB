package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 有界操作预算 —— 为「同步且可能无客户端超时」的 Playwright 调用提供限流与逃生。
 *
 * <p>为什么必须（对应 playwright-java-1.62.0 源码事实）：
 * <ul>
 *   <li>{@code setNetworkInterceptionPatterns} 是同步命令且无客户端超时（BrowserContextImpl:720-722），
 *       阻塞会直接卡死事件线程 → 框架自己的注册/注销路径必须限流；</li>
 *   <li>{@code route.fetch()} 是同步 HTTP（默认约 30s 超时，RouteImpl:79-102），
 *       批量拦截时并发过高会耗尽浏览器侧连接 → IO 路径必须限流。</li>
 * </ul>
 *
 * <p>预算耗尽/超时的处理永远不是抛异常，而是返回 {@link Optional#empty()} —— 调用方
 * 执行 fail-open（fallback 放行请求），保证请求永不悬挂。
 *
 * <p>并发安全：{@link Semaphore} 信号量 + 原子计数器（指标），无共享可变状态。
 */
public final class BoundedOps {

    private static final Logger LOGGER = LoggerFactory.getLogger(BoundedOps.class);

    private final Semaphore semaphore;
    private final long acquireTimeoutMs;
    private final AtomicLong rejected = new AtomicLong(0);
    private final AtomicLong accepted = new AtomicLong(0);

    public BoundedOps(int maxConcurrent, Duration acquireTimeout) {
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("maxConcurrent must be > 0");
        }
        this.semaphore = new Semaphore(maxConcurrent);
        this.acquireTimeoutMs = acquireTimeout.toMillis();
    }

    /** 已拒绝（预算耗尽）次数。 */
    public long rejectedCount() {
        return rejected.get();
    }

    /** 已放行次数。 */
    public long acceptedCount() {
        return accepted.get();
    }

    /**
     * 在有界预算内执行同步动作。
     *
     * @param op     操作名（日志/指标用）
     * @param action 同步动作（不得阻塞超过其自身超时上限）
     * @return 动作结果；{@link Optional#empty()} 表示预算耗尽或等待超时（调用方必须 fail-open）
     */
    public <T> Optional<T> tryRun(String op, Supplier<T> action) {
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("[RouteV2] BoundedOps interrupted while acquiring for op='{}'", op);
            rejected.incrementAndGet();
            return Optional.empty();
        }
        if (!acquired) {
            rejected.incrementAndGet();
            LOGGER.warn("[RouteV2] BoundedOps budget exhausted for op='{}', caller must fail-open", op);
            return Optional.empty();
        }
        accepted.incrementAndGet();
        try {
            return Optional.ofNullable(action.get());
        } catch (RuntimeException e) {
            LOGGER.warn("[RouteV2] BoundedOps op='{}' failed: {}", op, e.toString());
            return Optional.empty();
        } finally {
            semaphore.release();
        }
    }

    /** 有界执行无返回值动作。 */
    public boolean tryRunVoid(String op, Runnable action) {
        return tryRun(op, () -> {
            action.run();
            return Boolean.TRUE;
        }).isPresent();
    }
}
