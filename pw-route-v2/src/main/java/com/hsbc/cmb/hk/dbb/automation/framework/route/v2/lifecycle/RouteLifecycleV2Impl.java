package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.RouteEngine2;
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
 * <p>注册时机：本类静态块自注册；由 {@link RouteEngine2} 静态块在业务首次使用 V2 时触发加载。
 * 若 classpath 无 pw-route（V2 独立使用），primary 为空、Composite 直接驱动本实现，语义不变。
 */
public class RouteLifecycleV2Impl implements RouteLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteLifecycleV2Impl.class);

    static {
        RouteLifecycleRegistry.registerAdditional(new RouteLifecycleV2Impl());
        LOGGER.debug("[RouteV2] RouteLifecycleV2Impl registered as additional lifecycle");
    }

    @Override
    public void stopContextEngine(Object ctx) {
        if (ctx instanceof BrowserContext bc) {
            RouteEngine2.shutdown(bc);
        }
    }

    @Override
    public void clearContext(Object ctx) {
        // V2 规则随 runtime 生命周期；clearContext 语义与 stopContextEngine 等价（幂等）
        stopContextEngine(ctx);
    }

    @Override
    public void stopAllContextEngines() {
        RouteEngine2.shutdownAll();
    }

    @Override
    public void shutdownRouteEngine() {
        RouteEngine2.shutdownAll();
    }

    @Override
    public void drainForSuiteTeardown() {
        // V2 无跨用例共享线程池：套件收尾即关闭全部 per-context 运行时（幂等）
        RouteEngine2.shutdownAll();
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
