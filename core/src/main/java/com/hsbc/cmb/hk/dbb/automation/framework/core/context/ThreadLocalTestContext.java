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

    /**
     * 内部存储。类型显式声明为 {@link ConcurrentHashMap}（而非 {@link ConcurrentMap} 接口），
     * 以保证 {@link #computeIfAbsent} 走的是 CHM 的<b>原子</b>实现（D5-2）。
     */
    private final ConcurrentHashMap<ContextKey<?>, Object> store = new ConcurrentHashMap<>();

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

    /**
     * 原子版 {@code computeIfAbsent}（D5-2）。
     *
     * <p><b>修复前的竞态</b>：原实现是「get → 判空 → 计算 → put」四步非原子操作，
     * 并发下多个线程会同时判定"不存在"并各自执行 supplier，产生两个后果：
     * <ol>
     *   <li>supplier 被重复执行 —— 惰性单例（如 {@code ListenerGuardState}、per-thread 锁对象）
     *       被创建多份，违背"同一上下文内唯一"的语义；</li>
     *   <li>后写覆盖先写，<b>先拿到实例的调用方其后续状态更新会静默丢失</b> ——
     *       这类问题表现为偶发的"标记没生效 / 状态被重置"，极难定位。</li>
     * </ol>
     *
     * <p><b>约束（CHM 语义）</b>：mapping function 内部<b>不得</b>再修改本 store
     * （否则可能触发递归更新异常或死锁）。当前所有调用方的 supplier 均为纯构造
     * （{@code ArrayList::new} / {@code ListenerGuardState::new} / {@code Object::new}），
     * 满足该约束；新增调用方请保持 supplier 无副作用。
     */
    @Override
    public <T> T computeIfAbsent(ContextKey<T> key, Supplier<? extends T> supplier) {
        Object v = store.computeIfAbsent(key, k -> supplier.get());
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
