package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import java.util.concurrent.Callable;

/**
 * Playwright 同步协议调用的有界防护角色契约（2026-09-28 复盘收口，经 SPI 可替换）。
 *
 * <p>Playwright 的 {@code context.route()} / {@code context.unroute()} 等协议调用在 Node 驱动不响应时
 * （{@code setNetworkInterceptionPatterns → sendMessage → PipeTransport.poll}）会无限阻塞调用线程。
 * 本角色把所有此类驱动协议调用统一收口：在 daemon 线程执行 + 主线程有界等待，杜绝遗漏。
 *
 * <p><b>2026-09-29 探针更正</b>：早期"unroute 卡 11 分钟 / route 注册卡 14 分钟"的描述不准确 —— 实测该调用
 * 在界值后约 3~5 秒即结束（抛 {@code Object doesn't exist: worker@/frame@}）。根因是客户端
 * {@code Connection.dispatch} 未按消息隔离异常（已由框架自建客户端 DBBN-PATCH-01 修复）；
 * 本层的有界等待是"驱动真卡死"的兜底。
 *
 * <p><b>为什么必须有界（2026-09-28 按 Playwright 1.62.0 源码复核）</b>：{@code context.route()} /
 * {@code unroute()} 最终都是客户端的 {@code sendMessage("setNetworkInterceptionPatterns", …, NO_TIMEOUT)}
 * （{@code BrowserContextImpl:745-747}，<b>客户端自身不设超时</b>）；且客户端<b>没有调度线程</b> ——
 * {@code Connection.sendMessage → ChannelOwner.runUntil → processOneMessage} 由<b>调用线程自己泵消息</b>。
 * 所以"有界等待"只能由本层提供。
 *
 * <p><b>阈值（固定 {@link #BIND_BOUND_MS} / {@link #UNROUTE_BOUND_MS}，不经系统属性覆盖）</b>：
 * 取值参考实测环境合法时延（{@code waitForVisible} 45~60s、完整登录 53s），既覆盖正常注册耗时、
 * 又杜绝驱动无响应时无限阻塞调用线程。
 *
 * <p>超时策略（语义不同，必须显式区分）：
 * <ul>
 *   <li>{@link OnTimeout#FAIL_FAST} —— 注册等"必须成功才有意义"的调用：超时抛 {@link IllegalStateException}，
 *       让 step 快速失败而非死等（{@code PatternBinder} 对行为类能力使用）；</li>
 *   <li>{@link OnTimeout#WARN_AND_ABANDON} —— 注销等"清理期"调用与纯观测调用：超时仅 WARN 放弃等待，
 *       残留驱动层 handler 随 context 关闭自动释放（{@code PatternBinder} 对 MONITOR 使用）。</li>
 * </ul>
 *
 * <p><b>API 边界</b>：本接口是 framework-internal 原语（仅 V2 binding 包 {@code PatternBinder} 使用，
 * 业务不得直接依赖）。创建收口经 {@link GuardedDriverCallRegistry} SPI，可经
 * {@link GuardedDriverCallRegistry#setInstance} 整体替换（真多态 + 测试可注入替身）。
 */

public interface GuardedDriverCall {

    /** 注册类协议调用有界等待（毫秒）：硬编码固定值，不经系统属性覆盖（2026-09-29 依用户裁定移除覆盖开关）。 */
    long BIND_BOUND_MS = 30_000L;

    /** 注销类协议调用有界等待（毫秒）：硬编码固定值，不经系统属性覆盖（2026-09-29 依用户裁定移除覆盖开关）。 */
    long UNROUTE_BOUND_MS = 10_000L;

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
    /**
     * 信道是否可确证可用（{@code false} ⇒ 存在未收尾的在途调用，见 {@link #guarded} 的超时处置）。
     *
     * <p>对外 API 里<b>没有</b>"复位连接"的手段（{@code unrouteAll} 内部同走全量下发；{@code close} 无 timeout），
     * 唯一的"换连接"是关闭并重建 {@code Playwright} 实例。故本方法只提供**可查询状态**，复位由会话层决定。</p>
     *
     * <p>默认实现恒为 {@code true}（替身/无状态实现不承载信道语义）。</p>
     */
    default boolean isChannelUsable() {
        return true;
    }

    /**
     * 生效的注册类有界等待（毫秒）：固定 {@link #BIND_BOUND_MS}，不经系统属性覆盖。
     *
     * <p>阈值选取依据实测环境合法时延（{@code waitForVisible} 45~60s、完整登录 53s），固定 30s
     * 既覆盖正常注册耗时、又杜绝驱动无响应时无限阻塞调用线程。</p>
     */
    static long bindBoundMs() {
        return BIND_BOUND_MS;
    }

    /** 生效的注销类有界等待（毫秒）：固定 {@link #UNROUTE_BOUND_MS}，不经系统属性覆盖。 */
    static long unrouteBoundMs() {
        return UNROUTE_BOUND_MS;
    }
}
