package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import java.util.Objects;

/**
 * 类型安全的 per-scenario 上下文键。
 *
 * <p>用于 {@link TestContext} 中按类型存取状态，避免 String 键的类型擦除问题。
 * 以 {@code name} 作为唯一标识（equals/hashCode 仅基于 name）。
 *
 * @param <T> 值的类型
 */
public final class ContextKey<T> {

    private final String name;
    private final Class<T> type;

    private ContextKey(String name, Class<T> type) {
        this.name = Objects.requireNonNull(name, "context key name");
        this.type = Objects.requireNonNull(type, "context key type");
    }

    public static <T> ContextKey<T> of(String name, Class<T> type) {
        return new ContextKey<>(name, type);
    }

    public String name() {
        return name;
    }

    public Class<T> type() {
        return type;
    }

    /** 从存储 Map 取值时做类型安全的强制转换（package-private，供实现类使用）。 */
    @SuppressWarnings("unchecked")
    T cast(Object value) {
        return value == null ? null : type.cast(value);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ContextKey<?> k && name.equals(k.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return "ContextKey[" + name + ":" + type.getSimpleName() + "]";
    }
}
