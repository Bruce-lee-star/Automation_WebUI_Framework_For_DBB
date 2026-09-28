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

    /**
     * 跨用例收尾栅栏：有界等待指定 context 的<b>在途 unroute 收尾</b>完成
     * （下一个用例复用同一 Context 之前调用）。等价于 {@code RouteEngine.awaitInFlightUnroute(ctx, timeoutMs)}。
     *
     * <p>teardown worker 是 fire-and-forget 守护线程，feature 模式下常在下一个用例开始后才收工；
     * 该窗口内两个线程并发操作同一个 Playwright {@code Connection}，会触发
     * {@code Object doesn't exist: response@...}（详见实现侧注释）。故 web 侧在 scenario 初始化时经本方法
     * 有界等待。无在途收尾时<b>零开销</b>，未实现时视为"无在途收尾"（兼容测试替身）。
     *
     * @param ctx       目标 Page / BrowserContext
     * @param timeoutMs 等待上限（毫秒）
     * @return true = 无在途收尾或已在超时内完成；false = 超时（调用方继续，但需知晓仍可能与收尾并发）
     */
    default boolean awaitTeardownFor(Object ctx, long timeoutMs) {
        return true;
    }

    /** 对 URL 做敏感信息脱敏（原 RouteUtil.sanitizeUrl）。 */
    String sanitizeUrl(String url);

    /**
     * 该 context 是否已被判定「浏览器/连接无响应」。等价于 {@code RouteEngine.isConnectionUnresponsive(ctx)}。
     *
     * <p>判定来源：Playwright 中存在<b>无客户端超时</b>的协议往返（实测
     * {@code setNetworkInterceptionPatterns}，即 {@code context.route()/unroute()}），浏览器不回 ACK 时调用方
     * 会永久阻塞。框架为该往返加了有界预算，超时即登记本标记。
     *
     * <p>web 侧在<b>用例初始化</b>时查询：为真则关闭并重建该线程的 Context / 浏览器，
     * 使"连接损坏"不会级联到后续用例（未实现时返回 false，兼容测试替身）。
     *
     * @param ctx Page / BrowserContext
     * @return true 表示该 Context 的协议往返已不可靠，应重建
     */
    default boolean isConnectionUnresponsive(Object ctx) {
        return false;
    }
}
