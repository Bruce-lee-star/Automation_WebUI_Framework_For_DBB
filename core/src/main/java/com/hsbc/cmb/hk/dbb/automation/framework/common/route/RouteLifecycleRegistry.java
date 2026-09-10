package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

/**
 * 路由生命周期实现注册表（核心层）。
 *
 * <p>route 模块的实现类（{@code framework.route.core.lifecycle.RouteLifecycleImpl}）在类加载时
 * 通过 {@link #register(RouteLifecycle)} 自注册；web 侧只调用 {@link #get()} 获取实现，
 * 无需在编译期依赖 route 模块，从而打破 {@code web ↔ route} 循环依赖。
 *
 * <p>若 route 模块不在 classpath 上（如纯 web 测试），{@link #get()} 返回 null，
 * 调用方应做空判断或忽略（保持原有"route 未启用则跳过清理"的语义）。
 */
public final class RouteLifecycleRegistry {

    private static volatile RouteLifecycle instance;
    private static volatile boolean initialized;

    private RouteLifecycleRegistry() {}

    public static void register(RouteLifecycle impl) {
        instance = impl;
    }

    public static RouteLifecycle get() {
        if (!initialized) {
            synchronized (RouteLifecycleRegistry.class) {
                if (!initialized) {
                    try {
                        // 延迟加载 route 模块实现（触发其静态注册块），失败则说明 route 未启用
                        Class.forName(
                                "com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle.RouteLifecycleImpl");
                    } catch (Exception | LinkageError ignored) {
                        // route 模块缺失或尚未初始化：保持 instance 为 null
                    }
                    initialized = true;
                }
            }
        }
        return instance;
    }
}
