package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link TestContext} 的默认实现：内部以普通 {@link Map} 存储，实例由
 * {@link TestContextHolder} 的 per-thread {@code ThreadLocal} 持有，故无需并发安全。
 */
public class ThreadLocalTestContext implements TestContext {

    private final Map<ContextKey<?>, Object> store = new HashMap<>();

    @Override
    public void clear() {
        store.clear();
    }

    @Override
    public <T> T get(ContextKey<T> key) {
        return key.cast(store.get(key));
    }

    @Override
    public <T> void set(ContextKey<T> key, T value) {
        store.put(key, value);
    }

    @Override
    public <T> void remove(ContextKey<T> key) {
        store.remove(key);
    }

    @Override
    public <T> boolean contains(ContextKey<T> key) {
        return store.containsKey(key);
    }

    @Override
    public <T> T computeIfAbsent(ContextKey<T> key, Supplier<? extends T> supplier) {
        Object v = store.get(key);
        if (v == null) {
            v = supplier.get();
            store.put(key, v);
        }
        return key.cast(v);
    }
}
