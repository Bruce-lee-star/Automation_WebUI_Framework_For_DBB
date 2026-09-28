package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding.GuardedDriverCall.OnTimeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 测试替身：记录每次 {@link #guarded} 调用的（opName / boundMs / policy），但<b>不执行</b> action。
 *
 * <p>用途：行为级防回归 —— 验证 {@link PatternBinder} 的 bind / unroute 确实经由
 * {@link GuardedDriverCall} 收口、且携带正确的 opName 前缀与 {@link OnTimeout} 策略，
 * 而无需真实驱动协议调用（避免 mock {@code BrowserContext.route} 的重载歧义）。与
 * {@link StubGuardedDriverCall}（执行 action，验证 SPI 换实现全链路）互补。</p>
 *
 * <p>@apiNote 测试设施，非生产代码。</p>
 */
public final class CapturingGuardedDriverCall implements GuardedDriverCall {

    /** 单次守护调用的快照（参数 + 是否被执行）。 */
    public static final class Call {
        public final String opName;
        public final long boundMs;
        public final OnTimeout policy;
        public final boolean executed;

        Call(String opName, long boundMs, OnTimeout policy, boolean executed) {
            this.opName = opName;
            this.boundMs = boundMs;
            this.policy = policy;
            this.executed = executed;
        }
    }

    private final List<Call> calls = new ArrayList<>();

    /** 已记录的调用（按发生顺序，不可变快照）。 */
    public List<Call> calls() {
        return new ArrayList<>(calls);
    }

    /** 是否记录到至少一个 opName 以给定前缀开头的调用。 */
    public boolean hasCallWithOpPrefix(String prefix) {
        for (Call c : calls) {
            if (c.opName != null && c.opName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public <T> T guarded(String opName, long boundMs, OnTimeout policy, Callable<T> action) {
        // 不执行 action：本替身仅用于参数断言，避免依赖真实驱动协议调用（E2E 卡死根因所在）
        calls.add(new Call(opName, boundMs, policy, false));
        return null;
    }
}
