package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

/**
 * 引擎控制面——{@link RouteLifecycle} 接口隔离拆分（N-11）后的三个正交子接口之一。
 *
 * <p>职责：路由引擎按上下文停止、全量关闭（JVM 关闭钩子 / 框架退出），以及 URL 敏感信息脱敏。
 */
public interface EngineControl {

    /** 停止指定上下文的路由引擎。等价于 {@code RouteEngine.stopContextEngine(ctx)}。 */
    void stopContextEngine(Object ctx);

    /** 停止所有上下文的路由引擎。等价于 {@code RouteEngine.stopAllContextEngines()}。 */
    void stopAllContextEngines();

    /** 全量关闭路由引擎（JVM 关闭钩子 / 框架退出时调用）。等价于 {@code RouteEngine.shutdown()}。 */
    void shutdownRouteEngine();

    /** 对 URL 做敏感信息脱敏（原 RouteUtil.sanitizeUrl）。 */
    String sanitizeUrl(String url);
}
