package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link GuardedDriverCall} 默认实现：daemon 线程执行 + 主线程有界等待（行为零回归）。
 *
 * <p>经 {@code META-INF/services} 注册为 {@link GuardedDriverCall} 的 SPI 默认实现；
 * classpath 无显式注册时由 {@link GuardedDriverCallRegistry} 回退到本类。
 */
public final class GuardedDriverCallImpl implements GuardedDriverCall {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuardedDriverCallImpl.class);

    @Override
    public <T> T guarded(String opName, long boundMs, OnTimeout policy, Callable<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                error.set(t);
            }
        }, "route-v2-" + opName);
        caller.setDaemon(true);
        caller.start();
        try {
            caller.join(boundMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (caller.isAlive()) {
            // 驱动对协议调用无响应：按策略处置
            if (policy == OnTimeout.FAIL_FAST) {
                LOGGER.error("[RouteV2] driver call '{}' exceeded {}ms — driver unresponsive, failing fast",
                        opName, boundMs);
                throw new IllegalStateException("[RouteV2] driver call '" + opName
                        + "' timed out after " + boundMs + "ms (driver unresponsive)");
            }
            LOGGER.warn("[RouteV2] driver call '{}' exceeded {}ms — abandoning wait; "
                    + "driver-side handler released on context close", opName, boundMs);
            return null;
        }
        if (error.get() != null) {
            if (policy == OnTimeout.WARN_AND_ABANDON) {
                LOGGER.warn("[RouteV2] driver call '{}' failed: {}", opName, error.get().toString());
                return null;
            }
            throw new IllegalStateException("[RouteV2] driver call '" + opName + "' failed", error.get());
        }
        return result.get();
    }
}
