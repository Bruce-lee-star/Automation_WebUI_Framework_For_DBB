package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 路由生命周期实现注册表（核心层）。
 *
 * <p>各路由实现（如 Route V2 的 {@code route.v2.lifecycle.RouteLifecycleV2Impl}）在类加载时
 * 通过 {@link #registerAdditional(RouteLifecycle)} 追加注册；web 侧只调用 {@link #get()} 获取实现，
 * 无需在编译期依赖路由模块，从而打破 {@code web ↔ route} 循环依赖。
 *
 * <p>若没有任何实现注册（如纯 web 测试），{@link #get()} 返回仅含空主体的聚合对象，
 * 调用方语义不变（"route 未启用则跳过清理"）。
 */
public final class RouteLifecycleRegistry {

    private static volatile RouteLifecycle instance;
    private static final CopyOnWriteArrayList<RouteLifecycle> ADDITIONAL = new CopyOnWriteArrayList<>();

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
        // 多实现聚合：primary 语义不变，additions（Route V2 lifecycle）随同一挂点被驱动
        return RouteLifecycleComposite.of(instance, ADDITIONAL);
    }
}
