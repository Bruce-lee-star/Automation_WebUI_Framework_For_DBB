package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl;

/**
 * 路由能力（对一次被拦截请求采取的行为）。
 *
 * <p>枚举是规则语义的单一事实来源：新模块只有四种能力，每种能力的执行路径
 * 在 {@code RouteDispatcher} 中对应一条确定的无竞态执行链。
 */
public enum RouteCapability {

    /** 观测：记录请求快照并放行（fail-open，绝不影响业务请求）。 */
    MONITOR,

    /** 拦截并伪造响应：静态伪造（直接 fulfill）或拦截真实响应（IO 线程 fetch 后 fulfill）。 */
    MOCK,

    /** 修改请求后放行：增删改请求头 / 方法 / 体（经 resume 的 overrides 语义）。 */
    MODIFY_REQUEST,

    /** 延迟放行：拦截请求，到点后 resume（fail-open，超时后立即放行）。 */
    DELAY
}
