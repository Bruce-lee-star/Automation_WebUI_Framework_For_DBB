package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

/**
 * 路由生命周期钩子（核心层 SPI）。
 *
 * <p>用于打破 {@code web ↔ route} 的循环依赖：web 的生命周期类
 * （PlaywrightListener / PlaywrightManager / PlaywrightContextManager）原本直接 import
 * {@code framework.web.route} 的核心类来清理/重置路由状态，导致 web 与 route 互相依赖、
 * 无法拆成两个独立 Maven 模块。
 *
 * <p>现改为 web 只依赖本核心层接口，由 route 模块在启动时通过
 * {@link RouteLifecycleRegistry} 自注册实现（依赖倒置，与 T1-4 的 RouteHandlerRegistry 同思路）。
 */
public interface RouteLifecycle {

    /** 重置当前线程的 ApiCapture 上下文（含语言 ThreadLocal 清理）。等价于 {@code ApiCaptureContext.resetCurrent()}。 */
    void resetCaptureCurrent();

    /** 停止当前 ApiCapture 采集。等价于 {@code ApiCaptureContext.stop()}。 */
    void stopCapture();

    /** 取当前采集上下文（用于 markStepStart 等实例操作）。 */
    CaptureContext getCurrentCapture();

    /** 设置当前场景名（MonitorFailureCollector 去重归属）。 */
    void setMonitorScenario(String name);

    /** 清除当前场景（MonitorFailureCollector）。 */
    void clearMonitorScenario();

    /** 清除当前 feature（MonitorFailureCollector）。 */
    void clearMonitorFeature();

    /** 全量复位：清路由规则 + 停采集引擎 + 清上下文。等价于 {@code RouteDsl.resetAll()}。 */
    void resetAll();

    /** 清理指定上下文在 RouteRegistry 中的条目。等价于 {@code RouteRegistry.clearContext(ctx)}。 */
    void clearContext(Object ctx);

    /** 清空 RouteRegistry 全量。等价于 {@code RouteRegistry.clearAll()}。 */
    void clearAll();

    /** 清空已分发的路由。等价于 {@code RouteEngine.clearDispatchedRoutes()}。 */
    void clearDispatchedRoutes();

    /** 停止指定上下文的路由引擎。等价于 {@code RouteEngine.stopContextEngine(ctx)}。 */
    void stopContextEngine(Object ctx);

    /** 停止所有上下文的路由引擎。等价于 {@code RouteEngine.stopAllContextEngines()}。 */
    void stopAllContextEngines();

    /** 全量关闭路由引擎（JVM 关闭钩子 / 框架退出时调用）。等价于 {@code RouteEngine.shutdown()}。 */
    void shutdownRouteEngine();

    /** 对 URL 做敏感信息脱敏（原 RouteUtil.sanitizeUrl）。 */
    String sanitizeUrl(String url);
}
