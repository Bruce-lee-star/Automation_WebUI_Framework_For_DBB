package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试替身（真实实现类，满足"每接口≥2实现：生产 GuardedDriverCallImpl + 测试替身"验收）。
 *
 * <p>直接执行 action（无 daemon 守护），记录调用次数，按 {@link GuardedDriverCall.OnTimeout} 策略
 * 模拟超时等价语义（清理侧异常返回 {@code null}，注册侧异常抛出）—— 用于验证工厂/SPI 换实现全链路生效。
 */
public final class StubGuardedDriverCall implements GuardedDriverCall {

    private final AtomicInteger callCount = new AtomicInteger(0);

    /** 已执行的守护调用次数（验证注入替身被实际使用）。 */
    public int callCount() {
        return callCount.get();
    }

    @Override
    public <T> T guarded(String opName, long boundMs, OnTimeout policy, Callable<T> action) {
        callCount.incrementAndGet();
        try {
            return action.call();
        } catch (RuntimeException | Error e) {
            if (policy == OnTimeout.WARN_AND_ABANDON) {
                return null;
            }
            // FAIL_FAST contract (GuardedDriverCall#guarded): wrap, mirroring GuardedDriverCallImpl.
            throw new IllegalStateException("[stub] driver call '" + opName + "' failed", e);
        } catch (Exception e) {
            if (policy == OnTimeout.WARN_AND_ABANDON) {
                return null;
            }
            throw new IllegalStateException("[stub] driver call '" + opName + "' failed", e);
        }
    }
}
