package com.hsbc.cmb.hk.dbb.automation.framework.route.core;

import com.microsoft.playwright.BrowserContext;

/**
 * 每个 BrowserContext 路由引擎实例的生命周期管理（T2-4 拆分，自 {@code RouteEngine} 提取）。
 *
 * <p>持有 per-context 引擎注册表（{@code RouteContextState.CONTEXT_ENGINES}）的增删查，
 * 不直接触发规则/会话清理（由 {@code RouteEngine} 在 {@code stopContextEngine} 门面中负责），
 * 职责单一：引擎实例的 start / get / stop（含优雅关闭）。
 */
final class RouteLifecycleOwner {

    static PerContextEngine startContextEngine(BrowserContext context) {
        if (context == null) throw new IllegalArgumentException("BrowserContext must not be null");
        return RouteContextState.CONTEXT_ENGINES.compute(context, (ignored, existing) ->
                existing == null || existing.state == EngineState.CLOSED
                        ? new PerContextEngine(context) : existing);
    }

    static PerContextEngine getContextEngine(BrowserContext context) {
        return context == null ? null : RouteContextState.CONTEXT_ENGINES.get(context);
    }

    static PerContextEngine getOrStartContextEngine(BrowserContext context) {
        PerContextEngine engine = getContextEngine(context);
        return engine != null && engine.state == EngineState.RUNNING ? engine : startContextEngine(context);
    }

    /** 停止并关闭指定 context 的引擎（仅移除注册 + 优雅关闭，规则/会话清理由调用方负责）。 */
    static void stopContextEngine(BrowserContext context) {
        if (context == null) return;
        PerContextEngine engine = RouteContextState.CONTEXT_ENGINES.remove(context);
        if (engine != null) {
            engine.close();
        }
    }
}
