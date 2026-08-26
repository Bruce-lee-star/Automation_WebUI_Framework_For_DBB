package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.microsoft.playwright.BrowserContext;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** BrowserContext 级 RouteEngine 生命周期注册表。 */
public final class ContextRouteEngineManager {
    private static final Map<BrowserContext, ContextRouteEngine> ENGINES =
            new ConcurrentHashMap<>();

    private ContextRouteEngineManager() {
    }

    public static ContextRouteEngine start(BrowserContext context) {
        if (context == null) throw new IllegalArgumentException("BrowserContext must not be null");
        ContextRouteEngine engine = ENGINES.compute(context, (ignored, existing) ->
                existing == null || existing.state() == ContextRouteEngine.State.CLOSED
                        ? new ContextRouteEngine(context) : existing);
        return engine;
    }

    public static ContextRouteEngine get(BrowserContext context) {
        return context == null ? null : ENGINES.get(context);
    }

    public static ContextRouteEngine getOrStart(BrowserContext context) {
        ContextRouteEngine engine = get(context);
        return engine != null && engine.isRunning() ? engine : start(context);
    }

    public static void stop(BrowserContext context) {
        if (context == null) return;
        ContextRouteEngine engine = ENGINES.remove(context);
        if (engine != null) {
            engine.close();
            // ⭐ context 生命周期结束：清理旧 Context 的规则索引与引擎合并引用
            //   （保留重建快照 CONTEXT_RULE_STORE，供后续新建 Context 重绑规则）。
            RouteEngine.cleanupClosedContext(context);
        }
    }

    public static int size() {
        return ENGINES.size();
    }

    public static void stopAll() {
        for (BrowserContext context : new ArrayList<>(ENGINES.keySet())) {
            stop(context);
        }
    }
}
