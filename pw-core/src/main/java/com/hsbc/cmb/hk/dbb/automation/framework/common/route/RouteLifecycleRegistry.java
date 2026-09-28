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

    /**
     * 跨用例收尾栅栏的等待上限（毫秒）——<b>单一定义点</b>（web 侧 scenario 初始化与 route 侧注册路径共用）。
     *
     * <p>解析顺序：系统属性 {@code -Droute.teardown.fence.ms} 优先，其次环境变量
     * {@code ROUTE_TEARDOWN_FENCE_MS}，最后默认 {@value #DEFAULT_TEARDOWN_FENCE_MS}ms。
     *
     * <p>取值依据：teardown worker 实测耗时 0.2～1.7s（在途排空 + 逐句柄 close）。默认值足以覆盖正常收尾，
     * 又足够短，使"浏览器真不响应"时下个用例不会被长时间拖住（超时仅告警并继续，语义不退化）。
     */
    public static long teardownFenceMs() {
        String prop = System.getProperty("route.teardown.fence.ms");
        Long parsed = parsePositiveLong(prop);
        if (parsed != null) {
            return parsed;
        }
        Long fromEnv = parsePositiveLong(System.getenv("ROUTE_TEARDOWN_FENCE_MS"));
        return fromEnv != null ? fromEnv : DEFAULT_TEARDOWN_FENCE_MS;
    }

    /** 栅栏等待上限默认值（毫秒）。 */
    public static final long DEFAULT_TEARDOWN_FENCE_MS = 2_000L;

    /** 解析正 long；null / 非法 / 负数一律返回 null（交由调用方回退）。 */
    private static Long parsePositiveLong(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
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
