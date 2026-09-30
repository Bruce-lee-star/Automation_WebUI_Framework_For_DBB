package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Route V2 断言探针注册表（核心层）。
 *
 * <p>与 {@link RouteLifecycleRegistry} 同构：pw-route 的 {@code RouteAssertionProbeImpl}
 * 在类加载时自注册；web 侧只调用 {@link #get()} 获取探针，无需在编译期依赖 route 模块，
 * 从而保持 {@code web ↔ route-v2} 的解耦（web 侧只依赖本注册表与接口）。
 *
 * <p>若 pw-route 不在 classpath 上（如纯 web 测试或未使用 V2），{@link #get()} 返回 null，
 * 调用方应做空判断或忽略（保持"V2 未启用则跳过断言检查"的语义，与老版 route 未启用一致）。
 *
 * <p>线程安全：探针实例 volatile 发布；{@code initialized} 双重检查锁保证只做一次延迟加载。
 * {@link #clear()} 供测试收尾复位（不触发重新加载，直接置 null）。
 */
public final class RouteAssertionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteAssertionRegistry.class);

    private static volatile RouteAssertionProbe probe;
    private static volatile boolean initialized;

    private RouteAssertionRegistry() {
    }

    /** 注册（替换）探针实现；null 表示清除（测试收尾）。 */
    public static void register(RouteAssertionProbe impl) {
        probe = impl;
    }

    /** 清除已注册探针并标记已初始化（测试收尾；不再触发延迟加载）。 */
    public static void clear() {
        probe = null;
        initialized = true;
    }

    /**
     * 获取探针；首次调用延迟加载 pw-route 实现（触发其静态注册块）。
     *
     * @return 探针实例；V2 模块不在 classpath 或未初始化时为 null（调用方应空判断）
     */
    public static RouteAssertionProbe get() {
        if (!initialized) {
            synchronized (RouteAssertionRegistry.class) {
                if (!initialized) {
                    try {
                        Class.forName("com.hsbc.cmb.hk.dbb.automation.framework.route.lifecycle"
                                + ".RouteAssertionProbeImpl");
                    } catch (Exception | LinkageError e) {
                        // V2 模块缺失或尚未初始化：保持 null（预期降级，但不静默，D7-3）
                        LOGGER.debug("[RouteAssertionRegistry] v2 probe not available: {}",
                                e.toString());
                    }
                    initialized = true;
                }
            }
        }
        return probe;
    }
}
