package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

/**
 * 注册表层——{@link RouteLifecycle} 接口隔离拆分（N-11）后的三个正交子接口之一。
 *
 * <p>职责：RouteRegistry 条目清理、全量复位（{@code RouteDsl.resetAll()}）与已分发路由清理。
 * 侧重于"路由注册表状态"的收口，与引擎启停（{@link EngineControl}）解耦。
 */
public interface RegistryControl {

    /** 全量复位：清路由规则 + 停采集引擎 + 清上下文。等价于 {@code RouteDsl.resetAll()}。 */
    void resetAll();

    /** 清理指定上下文在 RouteRegistry 中的条目。等价于 {@code RouteRegistry.clearContext(ctx)}。 */
    void clearContext(Object ctx);

    /** 清空 RouteRegistry 全量。等价于 {@code RouteRegistry.clearAll()}。 */
    void clearAll();

    /** 清空已分发的路由。等价于 {@code RouteEngine.clearDispatchedRoutes()}。 */
    void clearDispatchedRoutes();
}
