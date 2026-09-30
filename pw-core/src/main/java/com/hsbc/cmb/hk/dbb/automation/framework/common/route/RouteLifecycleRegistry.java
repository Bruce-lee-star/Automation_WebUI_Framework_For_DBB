package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 路由生命周期实现注册表（核心层）。
 *
 * <p>路由实现的类加载自注册机制（<b>编译期零依赖</b>，打破 {@code web ↔ route} 循环依赖）：
 * {@link #get()} 首次调用时懒加载当前路由模块的 SPI 实现类，触发其静态块经
 * {@link #registerAdditional(RouteLifecycle)} 追加注册；此后由 {@link RouteLifecycleComposite}
 * 把 primary（{@link #register} 替换语义）与追加实现一起分发。</p>
 *
 * <p><b>为什么必须保持懒加载</b>：web 侧多处调用点（{@code SuiteTeardownListener}、
 * {@code PlaywrightManager}、{@code BrowserCleanupImpl}…）直接以 {@code get().xxx()} 形式使用，
 * <b>不做空判断</b>。懒加载保证"路由模块在 classpath 上 ⇒ get() 恒非空"；
 * 只有路由模块确实缺失（如纯 web 测试）时才返回 {@code null}（既有"未启用"降级语义），
 * 调用方需按 {@code null} 判定跳过清理。</p>
 */
public final class RouteLifecycleRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteLifecycleRegistry.class);

    /** 路由模块 SPI 实现类（懒加载触发其静态自注册块）。 */
    private static final String ROUTE_MODULE_IMPL =
            "com.hsbc.cmb.hk.dbb.automation.framework.route.lifecycle.RouteLifecycleImpl";

    private static volatile RouteLifecycle instance;
    private static final CopyOnWriteArrayList<RouteLifecycle> ADDITIONAL = new CopyOnWriteArrayList<>();
    private static volatile boolean implLoaded;

    private RouteLifecycleRegistry() {}

    /** 注册（替换）主实现；null 表示清除主实现（测试收尾）。 */
    public static void register(RouteLifecycle impl) {
        instance = impl;
    }

    /**
     * 追加辅助实现（幂等：同一实例只注册一次）。
     * 主实现语义不变；辅助实现经 RouteLifecycleComposite 分发。
     */
    public static void registerAdditional(RouteLifecycle impl) {
        if (impl == null) {
            return;
        }
        ADDITIONAL.addIfAbsent(impl);
    }

    public static RouteLifecycle get() {
        ensureRouteModuleLoaded();
        // 多实现聚合：primary 语义不变，additions（如 Route V2 lifecycle）随同一挂点被驱动
        return RouteLifecycleComposite.of(instance, ADDITIONAL);
    }

    /**
     * 懒加载路由模块实现（触发其静态注册块），失败则说明路由模块未启用。
     *
     * <p>与既有机制一致：路由模块缺失或尚未初始化时保持 {@code instance} 为 null（预期降级），
     * 仅以 debug 记录，不阻断调用方。</p>
     */
    private static void ensureRouteModuleLoaded() {
        if (implLoaded) {
            return;
        }
        synchronized (RouteLifecycleRegistry.class) {
            if (implLoaded) {
                return;
            }
            try {
                Class.forName(ROUTE_MODULE_IMPL);
            } catch (Exception | LinkageError e) {
                LOGGER.debug("[RouteLifecycleRegistry] route module not available: {}", e.toString());
            }
            implLoaded = true;
        }
    }
}
