package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 当前线程的登录身份快照：维度名 → 值（如 {@code environment=SIT1, username=alice}）。
 *
 * <p>由框架登录层（{@code SessionManager}/{@code LoginGuard} 单飞登录成功后）经 {@link #publish} 发布，
 * 供 {@link LoginIdentityKeyResolver} 自动推导并发分区键。以 {@code TestContext} 持有 → 与 Serenity
 * per-thread 模型一致；scenario 结束后随 {@code TestContextHolder.resetForCurrentThread()} 清理。</p>
 *
 * <p>⚠️ 本类仅为"身份来源"提供解耦 seam，不负责互斥；互斥由 {@link ConcurrencyGate} 承担。</p>
 */
public final class ConcurrencyIdentity {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final ContextKey<Map<String, String>> IDENTITY_KEY =
            (ContextKey) ContextKey.of("concurrency.identity.dimensions", Map.class);

    private ConcurrencyIdentity() {
    }

    /** 发布完整维度映射（null 维度名 / 值或空值维度会被忽略）。 */
    public static void publish(Map<String, String> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return;
        }
        Map<String, String> merged = new HashMap<>(current());
        for (Map.Entry<String, String> e : dimensions.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            String name = e.getKey().trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            merged.put(name, e.getValue().trim());
        }
        TestContextHolder.get().set(IDENTITY_KEY, Collections.unmodifiableMap(merged));
    }

    /** 发布单个维度。 */
    public static void publish(String dimension, String value) {
        if (dimension == null || value == null) {
            return;
        }
        publish(Map.of(dimension, value));
    }

    /** 读取当前线程某维度值（不存在返回 empty）。 */
    public static Optional<String> value(String dimension) {
        if (dimension == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(current().get(dimension.toLowerCase(Locale.ROOT)));
    }

    /** 读取当前线程全部身份维度（不可变副本；无则返回空 map）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, String> current() {
        Map<String, String> m = TestContextHolder.get().get(IDENTITY_KEY);
        return m == null ? Collections.emptyMap() : m;
    }

    /** 清除当前线程身份快照（通常无需手动调用，scenario 结束由 TestContext 清理）。 */
    public static void clear() {
        TestContextHolder.get().set(IDENTITY_KEY, Collections.emptyMap());
    }
}
