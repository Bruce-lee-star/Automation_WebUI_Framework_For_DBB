package com.hsbc.cmb.hk.dbb.automation.framework.route.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine;
import com.microsoft.playwright.BrowserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Route V2 的生命周期 SPI 实现 —— 挂到核心层 {@link RouteLifecycleRegistry} 的追加位
 * （primary 仍由 pw-route 的 {@code RouteLifecycleImpl} 占据，二者经 Composite 聚合分发）。
 *
 * <p>职责边界（V2 无 API 捕获、无全局规则表，只实现「引擎收尾」）：
 * <ul>
 *   <li>{@link #stopContextEngine(Object)} / {@link #clearContext(Object)} → 关闭指定 context 的 V2 运行时
 *       （幂等；context 关闭事件本也会触发，这里提供显式生命周期挂点）；</li>
 *   <li>{@link #stopAllContextEngines()} / {@link #shutdownRouteEngine()} / {@link #drainForSuiteTeardown()}
 *       → 关闭全部 V2 运行时（套件收尾）；</li>
 *   <li>采集 / 注册表 / 脱敏：V2 无对应状态 → no-op 或原样返回（Composite 查询型方法由 primary 优先）。</li>
 * </ul>
 *
 * <p>注册时机：本类静态块自注册；由 {@link RouteEngine} 静态块在业务首次使用 V2 时触发加载。
 * 若 classpath 无 pw-route（V2 独立使用），primary 为空、Composite 直接驱动本实现，语义不变。
 */
public class RouteLifecycleImpl implements RouteLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteLifecycleImpl.class);

    static {
        RouteLifecycleRegistry.registerAdditional(new RouteLifecycleImpl());
        LOGGER.debug("[Route] RouteLifecycleImpl registered as additional lifecycle");
    }

    @Override
    public void stopContextEngine(Object ctx) {
        if (ctx instanceof BrowserContext bc) {
            RouteEngine.shutdown(bc);
        }
    }

    /**
     * 带「Context 正被主动关闭」意图的停止（T8-5，{@code EngineControl} 默认方法的 V2 覆写）。
     *
     * <p>传 {@code true} 时 runtime 跳过逐条 unroute —— 规则随 {@code context.close()} 原生释放。
     * 这是 credential 档"路由辅助设施零清理"的实现落点（FIX_PLAN §10.3 C-4）。
     */
    @Override
    public void stopContextEngine(Object ctx, boolean contextBeingClosed) {
        if (ctx instanceof BrowserContext bc) {
            RouteEngine.shutdown(bc, contextBeingClosed);
        }
    }

    /**
     * 清规则（<b>保留</b> runtime 与 Context）—— 2026-09-30 改为【档 B 纯内存解绑】。
     *
     * <p>原实现等价于 {@code stopContextEngine}（拆 runtime + 逐条 unroute）。而 web 的
     * {@code PlaywrightManager.clearCurrentThreadRouteState()} 在<b>每个 scenario 收尾</b>都调本方法
     * ⇒ 每用例 N 次 {@code setNetworkInterceptionPatterns}，正是 30s 卡死 / 信道污染 / 下一用例
     * bind 挂死的引信（FIX_PLAN §4.1）。现改为 {@link RouteEngine#detachRules}：规则表内存清空，
     * 驱动侧 handler 常驻（命中即 fail-open），<b>零协议调用</b>。</p>
     *
     * <p>Context 真的要关闭时，由 {@link #stopContextEngine(Object, boolean)}（{@code true}）随
     * {@code context.close()} 原生释放 —— 同样不 unroute。</p>
     */
    @Override
    public void clearContext(Object ctx) {
        if (ctx instanceof BrowserContext bc) {
            RouteEngine.detachRules(bc);
        }
    }

    @Override
    public void stopAllContextEngines() {
        RouteEngine.shutdownAll();
    }

    @Override
    public void shutdownRouteEngine() {
        RouteEngine.shutdownAll();
    }

    @Override
    public void drainForSuiteTeardown() {
        // V2 无跨用例共享线程池：套件收尾即关闭全部 per-context 运行时（幂等）
        RouteEngine.shutdownAll();
    }

    /**
     * V2 对「连接是否已不可靠」的回答（2026-09-29 政策落地：让 web 的用例起点恢复真正覆盖 V2）。
     *
     * <p>V2 唯一的"协议往返不可靠"来源，是<b>驱动信道被判定不可继续</b>：存在未收尾的在途协议调用
     * （{@code GuardedDriverCallImpl.handleTimeout} 在"先摘后判"后仍未确认收尾时<b>不新建线程</b>、
     * 只标记信道不可用）。对外 API 里没有"只复位连接"的手段，故 web 侧必须重建 {@code Playwright}
     * 实例（= 换 Node 驱动进程 = 换 Connection）；该重建经 {@code stopAllContextEngines()} →
     * {@code RouteEngine.shutdownAll()} 连带复位框架驱动信道。</p>
     */
    @Override
    public boolean isConnectionUnresponsive(Object ctx) {
        return !RouteEngine.isDriverChannelUsable();
    }

    @Override
    public String sanitizeUrl(String url) {
        // 查询型：Composite 由 primary（pw-route）优先处理；独立使用 V2 时原样返回
        return url;
    }

    // ── V2 无对应状态：no-op / null（保持 Composite 降级语义）──

    @Override
    public void resetCaptureCurrent() {
        // no-op
    }

    @Override
    public void stopCapture() {
        // no-op
    }

    @Override
    public CaptureContext getCurrentCapture() {
        return null;
    }

    @Override
    public CaptureContext resolveFailureCapture() {
        return null;
    }

    @Override
    public void setMonitorScenario(String name) {
        // no-op
    }

    @Override
    public void clearMonitorScenario() {
        // no-op
    }

    @Override
    public void clearMonitorFeature() {
        // no-op
    }

    @Override
    public void resetAll() {
        // no-op（V2 规则随 runtime，无全局注册表）
    }

    @Override
    public void clearAll() {
        // no-op
    }

    @Override
    public void clearDispatchedRoutes() {
        // no-op（V2 claim 表随 runtime 关闭自动落定）
    }

    @Override
    public void stopCaptureFor(Object context) {
        // no-op（V2 无 API 捕获）
    }
}
