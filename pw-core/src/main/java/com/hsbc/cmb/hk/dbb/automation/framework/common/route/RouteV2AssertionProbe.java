package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import java.util.List;

/**
 * Route V2 断言失败探针 SPI（核心层定义，web 侧消费，V2 模块实现）。
 *
 * <p>对齐 {@link RouteLifecycle} 的解耦模式：pw-route-v2 在类加载时实现并自注册到
 * {@link RouteV2AssertionRegistry}；web 侧（PlaywrightListener 的步骤/用例收尾）只依赖本接口
 * 调用 {@link #drainAndResolveFailures()}，编译期不触碰 route 模块，无循环依赖。
 *
 * <p>消费语义（幂等）：本方法一次性取走当前全部已定案的断言失败并清空；再次调用返回空列表。
 * 因此"步骤结束抛 AssertionError + testFinished 兜底标记"两条路径天然防重，无需额外的防重入标志。
 *
 * <p>定案定义（对齐 {@code MonitorSink}）：收到响应且（无 body 断言，或 body 断言已在 IO 线程
 * 评估完成）→ 定案；或超过 monitor 超时窗口仍未收到响应 → 定案为超时失败。未定案的请求
 * 留在观测队列继续等待（timeout 窗口语义），不会在步骤结束时被误判。
 */
public interface RouteV2AssertionProbe {

    /**
     * 取走全部已定案的断言失败（消费式，幂等）。
     *
     * @return 失败快照列表；无失败时为空列表（永不返回 null）
     */
    List<RouteV2AssertionFailure> drainAndResolveFailures();
}
