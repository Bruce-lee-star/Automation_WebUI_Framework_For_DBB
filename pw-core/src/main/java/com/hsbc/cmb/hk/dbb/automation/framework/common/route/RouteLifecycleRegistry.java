package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 路由生命周期实现注册表（核心层）。
 *
 * <p>route 模块的实现类（{@code framework.route.lifecycle.RouteLifecycleImpl}）在类加载时
 * 通过 {@link #register(RouteLifecycle)} 自注册；web 侧只调用 {@link #get()} 获取实现，
 * 无需在编译期依赖 route 模块，从而打破 {@code web ↔ route} 循环依赖。
 *
 * <p>若 route 模块不在 classpath 上（如纯 web 测试），{@link #get()} 返回 null，
 * 调用方应做空判断或忽略（保持原有"route 未启用则跳过清理"的语义）。
 */
public final class RouteLifecycleRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteLifecycleRegistry.class);

    private static volatile RouteLifecycle instance;
    private static final CopyOnWriteArrayList<RouteLifecycle> ADDITIONAL = new CopyOnWriteArrayList<>();
    private static volatile boolean initialized;

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
        if (!initialized) {
            synchronized (RouteLifecycleRegistry.class) {
                if (!initialized) {
                    try {
                        // 延迟加载 route 模块实现（触发其静态注册块），失败则说明 route 未启用
                        Class.forName(
                                "com.hsbc.cmb.hk.dbb.automation.framework.route.lifecycle.RouteLifecycleImpl");
                    } catch (Exception | LinkageError e) {
                        // route 模块缺失或尚未初始化：保持 instance 为 null（预期降级，但不得静默，D7-3）
                        LOGGER.debug("[RouteLifecycleRegistry] route module not available: {}", e.toString());
                    }
                    initialized = true;
                }
            }
        }
        // 多实现聚合：primary 语义不变，additions（如 Route V2 lifecycle）随同一挂点被驱动
        return RouteLifecycleComposite.of(instance, ADDITIONAL);
    }
}