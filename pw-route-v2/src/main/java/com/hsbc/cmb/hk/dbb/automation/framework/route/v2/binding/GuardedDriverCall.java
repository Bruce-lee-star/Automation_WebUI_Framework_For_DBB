package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding;

import java.util.concurrent.Callable;

/**
 * Playwright 同步协议调用的有界防护角色契约（2026-09-28 复盘收口，经 SPI 可替换）。
 *
 * <p>Playwright 的 {@code context.route()} / {@code context.unroute()} 等协议调用在 Node 驱动不响应时
 * （{@code updateInterceptionPatterns → sendMessage → PipeTransport.poll}）会无限阻塞调用线程
 * （E2E 实测：unroute 卡 11 分钟、route 注册卡 14 分钟，见 test-automation 1.txt）。本角色把所有此类驱动协议调用
 * 统一收口：在 daemon 线程执行 + 主线程有界等待，杜绝遗漏。
 *
 * <p>超时策略（语义不同，必须显式区分）：
 * <ul>
 *   <li>{@link OnTimeout#FAIL_FAST} —— 注册等「必须成功才有意义」的调用：超时抛 {@link IllegalStateException}，
 *       让 step 快速失败而非死等；</li>
 *   <li>{@link OnTimeout#WARN_AND_ABANDON} —— 注销等「清理期」调用：超时仅 WARN 放弃等待、action 异常也仅 WARN，
 *       残留驱动层 handler 随 context 关闭自动释放，绝不阻塞清理主链。</li>
 * </ul>
 *
 * <p><b>API 边界</b>：本接口是 framework-internal 原语（仅 V2 binding 包 {@code PatternBinder} 使用，
 * 业务不得直接依赖）。创建收口经 {@link GuardedDriverCallRegistry} SPI，可经
 * {@link GuardedDriverCallRegistry#setInstance} 整体替换（真多态 + 测试可注入替身）。
 */
public interface GuardedDriverCall {

    /** 注册类协议调用有界等待（毫秒）。 */
    long BIND_BOUND_MS = 5_000L;

    /** 注销类协议调用有界等待（毫秒）。 */
    long UNROUTE_BOUND_MS = 3_000L;

    /** 超时策略。 */
    enum OnTimeout {
        /** 超时/异常均 fail-fast（注册侧）。 */
        FAIL_FAST,
        /** 超时/异常均仅 WARN 放弃（清理侧）。 */
        WARN_AND_ABANDON
    }

    /**
     * 在 daemon 线程执行驱动协议调用并设界等待。
     *
     * @param opName  操作名（建议含 pattern，用于线程名与日志定位）
     * @param boundMs 有界等待上限（毫秒）
     * @param policy  超时策略
     * @param action  驱动协议调用（在 daemon 线程执行，可能返回 {@code null}）
     * @param <T>     返回值类型
     * @return action 的结果；{@link OnTimeout#WARN_AND_ABANDON} 在超时或 action 异常未产出结果时返回 {@code null}
     * @throws IllegalStateException 当 {@code policy=FAIL_FAST} 且超时或 action 抛异常
     */
    <T> T guarded(String opName, long boundMs, OnTimeout policy, Callable<T> action);
}
