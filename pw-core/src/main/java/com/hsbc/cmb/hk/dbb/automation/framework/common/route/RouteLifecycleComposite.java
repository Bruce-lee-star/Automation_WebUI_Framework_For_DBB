package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 多实现路由生命周期组合代理（核心层）。
 *
 * <p>背景：{@link RouteLifecycleRegistry} 原为「单实例可替换」（register 覆盖），web 侧经
 * {@code get()} 调用清理。新增 route V2 模块后，V2 也需要在同一生命周期挂点被驱动收尾
 * （否则 V2 runtime 成为"孤儿"）。本类在不改变 register 替换语义的前提下，为 get() 返回
 * 一个聚合代理：primary（可替换实现）+ additions（追加实现）。
 *
 * <p>分发契约：
 * <ul>
 *   <li><b>动作型方法</b>（void）：primary 先行——异常<b>向上抛</b>（保持现有调用方
 *       {@code safeClean} 契约不变）；随后逐个调用 additions——异常<b>fail-safe 隔离</b>
 *       （辅助实现不得阻断主流程）；</li>
 *   <li><b>查询型方法</b>（有返回值）：primary 优先返回（异常上抛）；primary 为空或返回 null
 *       时按注册顺序取首个 additions 的非 null 结果；全空返回 null（等价"未启用"降级语义）；</li>
 *   <li>primary 为 null 且 additions 为空时 {@link #of} 返回 null（兼容既有"未注册"判断）。</li>
 * </ul>
 */
public final class RouteLifecycleComposite implements RouteLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteLifecycleComposite.class);

    private final RouteLifecycle primary;
    private final List<RouteLifecycle> additions;

    private RouteLifecycleComposite(RouteLifecycle primary, List<RouteLifecycle> additions) {
        this.primary = primary;
        this.additions = Collections.unmodifiableList(new ArrayList<>(additions));
    }

    /** 构建组合；primary 与 additions 皆空时返回 null（保持"未启用"语义）。 */
    static RouteLifecycle of(RouteLifecycle primary, List<RouteLifecycle> additions) {
        if (primary == null && (additions == null || additions.isEmpty())) {
            return null;
        }
        return new RouteLifecycleComposite(primary,
                additions == null ? Collections.emptyList() : additions);
    }

    // ── 动作型：primary 异常上抛（契约不变），additions 逐项 fail-safe ──

    @Override
    public void resetCaptureCurrent() {
        if (primary != null) {
            primary.resetCaptureCurrent();
        }
        forEach(RouteLifecycle::resetCaptureCurrent);
    }

    @Override
    public void stopCapture() {
        if (primary != null) {
            primary.stopCapture();
        }
        forEach(RouteLifecycle::stopCapture);
    }

    @Override
    public void setMonitorScenario(String name) {
        if (primary != null) {
            primary.setMonitorScenario(name);
        }
        forEach(r -> r.setMonitorScenario(name));
    }

    @Override
    public void clearMonitorScenario() {
        if (primary != null) {
            primary.clearMonitorScenario();
        }
        forEach(RouteLifecycle::clearMonitorScenario);
    }

    @Override
    public void clearMonitorFeature() {
        if (primary != null) {
            primary.clearMonitorFeature();
        }
        forEach(RouteLifecycle::clearMonitorFeature);
    }

    @Override
    public void resetAll() {
        if (primary != null) {
            primary.resetAll();
        }
        forEach(RouteLifecycle::resetAll);
    }

    @Override
    public void clearContext(Object ctx) {
        if (primary != null) {
            primary.clearContext(ctx);
        }
        forEach(r -> r.clearContext(ctx));
    }

    @Override
    public void clearAll() {
        if (primary != null) {
            primary.clearAll();
        }
        forEach(RouteLifecycle::clearAll);
    }

    @Override
    public void clearDispatchedRoutes() {
        if (primary != null) {
            primary.clearDispatchedRoutes();
        }
        forEach(RouteLifecycle::clearDispatchedRoutes);
    }

    @Override
    public void stopContextEngine(Object ctx) {
        if (primary != null) {
            primary.stopContextEngine(ctx);
        }
        forEach(r -> r.stopContextEngine(ctx));
    }

    @Override
    public void stopAllContextEngines() {
        if (primary != null) {
            primary.stopAllContextEngines();
        }
        forEach(RouteLifecycle::stopAllContextEngines);
    }

    @Override
    public void shutdownRouteEngine() {
        if (primary != null) {
            primary.shutdownRouteEngine();
        }
        forEach(RouteLifecycle::shutdownRouteEngine);
    }

    @Override
    public void stopCaptureFor(Object context) {
        if (primary != null) {
            primary.stopCaptureFor(context);
        }
        forEach(r -> r.stopCaptureFor(context));
    }

    @Override
    public void drainForSuiteTeardown() {
        if (primary != null) {
            primary.drainForSuiteTeardown();
        }
        forEach(RouteLifecycle::drainForSuiteTeardown);
    }

    // ── 查询型：primary 优先（异常上抛）；否则首个 additions 非 null ──

    @Override
    public CaptureContext getCurrentCapture() {
        if (primary != null) {
            return primary.getCurrentCapture();
        }
        for (RouteLifecycle addition : additions) {
            try {
                CaptureContext result = addition.getCurrentCapture();
                if (result != null) {
                    return result;
                }
            } catch (RuntimeException e) {
                LOGGER.debug("[RouteLifecycle] additional getCurrentCapture failed (isolated): {}", e.toString());
            }
        }
        return null;
    }

    @Override
    public CaptureContext resolveFailureCapture() {
        if (primary != null) {
            return primary.resolveFailureCapture();
        }
        for (RouteLifecycle addition : additions) {
            try {
                CaptureContext result = addition.resolveFailureCapture();
                if (result != null) {
                    return result;
                }
            } catch (RuntimeException e) {
                LOGGER.debug("[RouteLifecycle] additional resolveFailureCapture failed (isolated): {}", e.toString());
            }
        }
        return null;
    }

    @Override
    public String sanitizeUrl(String url) {
        if (primary != null) {
            return primary.sanitizeUrl(url);
        }
        for (RouteLifecycle addition : additions) {
            try {
                String result = addition.sanitizeUrl(url);
                if (result != null) {
                    return result;
                }
            } catch (RuntimeException e) {
                LOGGER.debug("[RouteLifecycle] additional sanitizeUrl failed (isolated): {}", e.toString());
            }
        }
        return null;
    }

    /** additions 逐项 fail-safe 调用。 */
    private void forEach(java.util.function.Consumer<RouteLifecycle> action) {
        for (RouteLifecycle addition : additions) {
            try {
                action.accept(addition);
            } catch (RuntimeException e) {
                LOGGER.debug("[RouteLifecycle] additional action failed (isolated): {}", e.toString());
            }
        }
    }
}
