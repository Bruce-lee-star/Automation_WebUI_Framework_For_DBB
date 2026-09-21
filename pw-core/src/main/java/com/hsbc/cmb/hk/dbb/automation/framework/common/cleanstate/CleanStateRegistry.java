package com.hsbc.cmb.hk.dbb.automation.framework.common.cleanstate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * B-6：用例级隔离状态注册表——框架统一复位与**断言式清理**的单一收口点。
 *
 * <p>替代「每个 glue 手写 {@code @Before} 重置」：业务把复位与残留判定注册为 {@link StateResolver}，
 * 框架钩子统一在 {@code @Before} 调 {@link #resetAll()}、{@code @After} 调 {@link #dirtyNames()} 断言。
 *
 * <p>线程安全：注册表用 {@link ConcurrentHashMap}；{@link #resetAll()}/{@link #dirtyNames()} 对已注册
 * 解析器逐一调用，实例本身须线程安全。所有方法幂等；空注册表时为空操作。
 */
public final class CleanStateRegistry {

    private static final ConcurrentMap<String, StateResolver> RESOLVERS = new ConcurrentHashMap<>();

    private CleanStateRegistry() {
    }

    /** 注册解析器（同名覆盖）。null 忽略。 */
    public static void register(StateResolver resolver) {
        if (resolver == null) {
            return;
        }
        Objects.requireNonNull(resolver.name(), "StateResolver.name() must not be null");
        RESOLVERS.put(resolver.name(), resolver);
    }

    /** 注销指定状态（测试隔离 / 动态卸载）。 */
    public static void unregister(String name) {
        if (name != null) {
            RESOLVERS.remove(name);
        }
    }

    /** 清空注册表（仅供测试隔离）。 */
    public static void clearResolvers() {
        RESOLVERS.clear();
    }

    /** 已注册状态名快照。 */
    public static Set<String> registeredNames() {
        return Set.copyOf(RESOLVERS.keySet());
    }

    /** 复位所有已注册状态（幂等；空注册表为空操作）。 */
    public static void resetAll() {
        for (StateResolver resolver : RESOLVERS.values()) {
            resolver.reset();
        }
    }

    /** 复位指定状态（未注册的忽略）。 */
    public static void reset(String name) {
        StateResolver resolver = name == null ? null : RESOLVERS.get(name);
        if (resolver != null) {
            resolver.reset();
        }
    }

    /**
     * 返回仍处于「脏」状态的状态名（按注册顺序无关的稳定排序）；空表示无残留。
     * 供框架 {@code @After} 断言式清理使用。
     */
    public static List<String> dirtyNames() {
        List<String> dirty = new ArrayList<>();
        RESOLVERS.forEach((name, resolver) -> {
            if (resolver.isDirty()) {
                dirty.add(name);
            }
        });
        dirty.sort(String::compareTo);
        return dirty;
    }
}
