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
        return ENGINES.compute(context, (ignored, existing) ->
                existing == null || existing.state() == ContextRouteEngine.State.CLOSED
                        ? new ContextRouteEngine(context) : existing);
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
        if (engine != null) engine.close();
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
