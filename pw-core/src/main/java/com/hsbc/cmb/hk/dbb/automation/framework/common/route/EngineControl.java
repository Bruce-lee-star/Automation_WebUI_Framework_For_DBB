package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

/**
 * 引擎控制面——{@link RouteLifecycle} 接口隔离拆分（N-11）后的三个正交子接口之一。
 *
 * <p>职责：路由引擎按上下文停止、全量关闭（JVM 关闭钩子 / 框架退出），以及 URL 敏感信息脱敏。
 */
public interface EngineControl {

    /** 停止指定上下文的路由引擎。等价于 {@code RouteEngine.stopContextEngine(ctx)}。 */
    void stopContextEngine(Object ctx);

    /**
     * 停止指定上下文的路由引擎，并告知「该 Context <b>正被主动关闭</b>」（T8-5）。
     *
     * <p><b>为什么需要这个意图信号</b>：路由规则（{@code context.route()} 注册）的生命周期本可终结于两种途径 ——
     * ① 逐条 {@code unroute}（Context 仍需存活，如 feature 档跨用例复用）；
     * ② 随 {@code context.close()} 由驱动<b>原生释放</b>。
     * 二者互斥：既然 Context 马上要关，逐条 unroute 就是纯浪费的<b>同步协议往返</b>
     * （每条一次 {@code setNetworkInterceptionPatterns}，客户端无超时、实测有 10s 未确证的记录），
     * 且这些往返正是「收尾未确证 ⇒ 状态分叉 ⇒ 病态 Context 遗传」的来源（FIX_PLAN §4）。
     *
     * <p>实现侧收到 {@code contextBeingClosed=true} 后应<b>跳过全部 unroute</b>，只做内存收尾。
     *
     * <p>未覆写时退化为 {@link #stopContextEngine(Object)}（保持既有实现与测试替身零改动）。
     *
     * @param ctx                目标 Page / BrowserContext
     * @param contextBeingClosed true = 调用方紧接着就会关闭该 Context（native 释放路由，无需 unroute）
     */
    default void stopContextEngine(Object ctx, boolean contextBeingClosed) {
        stopContextEngine(ctx);
    }

    /** 停止所有上下文的路由引擎。等价于 {@code RouteEngine.stopAllContextEngines()}。 */
    void stopAllContextEngines();

    /** 全量关闭路由引擎（JVM 关闭钩子 / 框架退出时调用）。等价于 {@code RouteEngine.shutdown()}。 */
    void shutdownRouteEngine();


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