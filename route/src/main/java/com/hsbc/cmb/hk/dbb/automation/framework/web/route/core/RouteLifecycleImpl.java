package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.dsl.RouteDsl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor.MonitorFailureCollector;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import com.microsoft.playwright.BrowserContext;

/**
 * {@link RouteLifecycle} 的 route 模块实现，在类加载时自注册到 {@link RouteLifecycleRegistry}。
 * 所有方法 1:1 委托给 route 核心类，行为与原 web 直接调用完全一致。
 */
public class RouteLifecycleImpl implements RouteLifecycle {

    static {
        RouteLifecycleRegistry.register(new RouteLifecycleImpl());
    }

    @Override
    public void resetCaptureCurrent() {
        ApiCaptureContext.resetCurrent();
    }

    @Override
    public void stopCapture() {
        ApiCaptureContext.stop();
    }

    @Override
    public CaptureContext getCurrentCapture() {
        return ApiCaptureContext.getCurrent();
    }

    @Override
    public void setMonitorScenario(String name) {
        MonitorFailureCollector.getInstance().setCurrentScenario(name);
    }

    @Override
    public void clearMonitorScenario() {
        MonitorFailureCollector.getInstance().clearCurrentScenario();
    }

    @Override
    public void clearMonitorFeature() {
        MonitorFailureCollector.getInstance().clearCurrentFeature();
    }

    @Override
    public void resetAll() {
        RouteDsl.resetAll();
    }

    @Override
    public void clearContext(Object ctx) {
        RouteRegistry.clearContext(ctx);
    }

    @Override
    public void clearAll() {
        RouteRegistry.clearAll();
    }

    @Override
    public void clearDispatchedRoutes() {
        RouteEngine.clearDispatchedRoutes();
    }

    @Override
    public void stopContextEngine(Object ctx) {
        RouteEngine.stopContextEngine((BrowserContext) ctx);
    }

    @Override
    public void stopAllContextEngines() {
        RouteEngine.stopAllContextEngines();
    }

    @Override
    public void shutdownRouteEngine() {
        RouteEngine.shutdown();
    }

    @Override
    public String sanitizeUrl(String url) {
        return RouteUtil.sanitizeUrl(url);
    }
}
