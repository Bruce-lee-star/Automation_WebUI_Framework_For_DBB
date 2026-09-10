package com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteHandler;

/**
 * Handler 注册表 — 打破 {@code route.core ⇄ route.handler} 循环依赖（T1-4 轻量变体）。
 *
 * <p>原 {@link RouteEngine} 直接 import 四个具体 Handler 类（Mock / Modify / Monitor / Delay），
 * 形成 core → handler 的越层依赖。本注册表把"能力类型 → Handler"的映射收口到 core 内部，
 * 由 handler 包在<b>类加载时自注册</b>（见各 Handler 的 {@code static} 块），
 * {@link RouteEngine} 仅通过 {@link #resolve} 取用 {@link RouteHandler} 接口，
 * 不再依赖任何具体 Handler 类。
 *
 * <p>自注册通过 {@link #ensureLoaded()} 的 {@code Class.forName} 触发，全程以字符串类名引用，
 * 避免 core 对 handler 产生编译期依赖（满足 ArchUnit C1：core 不得依赖 handler）。
 * 映射关系（MOCK/MODIFY/MONITOR）与原 {@code resolveCapabilityHandler} 完全一致，行为不变。
 */
public final class RouteHandlerRegistry {

    private static final Map<RouteHandleType, RouteHandler> HANDLERS =
            new EnumMap<>(RouteHandleType.class);
    private static final List<Runnable> CACHE_CLEARERS = new ArrayList<>();
    private static volatile boolean loaded = false;

    private RouteHandlerRegistry() {}

    /** 由 handler 自注册：绑定某能力类型到其 Handler 实现。 */
    public static void register(RouteHandleType type, RouteHandler handler) {
        HANDLERS.put(type, handler);
    }

    /** 由 handler 自注册：登记一个缓存清理钩子（如 ModifyHandler 的 JsonPath 缓存）。 */
    public static void registerCacheClearer(Runnable clearer) {
        CACHE_CLEARERS.add(clearer);
    }

    /**
     * 解析某能力类型对应的 Handler；首次调用时确保 handler 类已加载并自注册。
     *
     * @return 对应 Handler；无匹配实现时返回 {@code null}（调用方降级处理）
     */
    public static RouteHandler resolve(RouteHandleType type) {
        ensureLoaded();
        return HANDLERS.get(type);
    }

    /** 清空所有已注册 Handler 的缓存（如 ModifyHandler 的 JsonPath 缓存）。 */
    public static void clearCaches() {
        for (Runnable r : CACHE_CLEARERS) {
            try {
                r.run();
            } catch (Exception ignored) {
                // 单个清理失败不影响其它
            }
        }
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (RouteHandlerRegistry.class) {
            if (loaded) return;
            for (String fqn : HANDLER_CLASS_NAMES) {
                try {
                    Class.forName(fqn);
                } catch (ClassNotFoundException e) {
                    // Handler 缺失：对应能力 resolve 返回 null，由调用方降级处理
                }
            }
            loaded = true;
        }
    }

    /** 需自注册的 Handler 全限定类名（字符串引用，避免 core → handler 编译依赖）。 */
    private static final String[] HANDLER_CLASS_NAMES = new String[]{
            "com.hsbc.cmb.hk.dbb.automation.framework.route.handler.MockHandler",
            "com.hsbc.cmb.hk.dbb.automation.framework.route.handler.ModifyHandler",
            "com.hsbc.cmb.hk.dbb.automation.framework.route.handler.MonitorHandler"
    };
}
