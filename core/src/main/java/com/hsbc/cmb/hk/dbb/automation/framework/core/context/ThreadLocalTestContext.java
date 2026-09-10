package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * {@link TestContext} 的默认实现（CORE-P0-2：内部存储改为并发安全）。
 *
 * <p>实例由 {@link TestContextHolder} 经 {@link ThreadContextRegistry} 的 per-thread
 * {@code ConcurrentHashMap} 持有。内部用 {@link ConcurrentMap} 替代原 {@code HashMap}，
 * 移除对「线程隔离」的隐式依赖，防御同一上下文实例被多线程误用的竞态。
 */
public class ThreadLocalTestContext implements TestContext {

    private final ConcurrentMap<ContextKey<?>, Object> store = new ConcurrentHashMap<>();

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

    /**
     * 返回当前存储的浅拷贝快照（键不变，值为引用）。用于跨线程传播桥（N-10）：
     * 提交线程经 {@link TestContextHolder#capture()} 取此快照，交由工作线程恢复。
     */
    public Map<ContextKey<?>, Object> snapshot() {
        return new ConcurrentHashMap<>(store);
    }

    /**
     * 用快照覆盖当前存储：先浅拷贝入参，避免外部持有引用导致跨线程串扰（N-10）。
     * 入参为 {@code null} 时等价于 {@link #clear()}。
     */
    public void loadSnapshot(Map<ContextKey<?>, Object> snapshot) {
        store.clear();
        if (snapshot != null) {
            store.putAll(snapshot);
        }
    }
}
