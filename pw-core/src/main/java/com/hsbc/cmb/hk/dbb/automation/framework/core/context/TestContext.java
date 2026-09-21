package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import java.util.function.Supplier;

/**
 * per-scenario 测试上下文：承载原先散落在各处的 {@code static ThreadLocal} 状态，
 * 为并行执行（多 scenario 并发）扫清全局静态状态障碍（remediation T3-1）。
 *
 * <p>实例本身由 {@link TestContextHolder} 以 per-thread 方式持有，因此本接口的方法
 * 不需要额外的并发保护；跨 scenario 的隔离由线程隔离保证。
 *
 * <p>收拢策略（remediation T3-1）：先「收拢」后「删除」——各模块现有 {@code static ThreadLocal}
 * 的读写逐步改走本接口，行为保持不变；待全部收拢完成并验证后，再考虑构造注入以消灭 Holder。
 */
public interface TestContext {

    /** scenario 结束时清理所有 per-scenario 状态。 */
    void clear();

    /** 读取键对应的值，未设置时返回 {@code null}。 */
    <T> T get(ContextKey<T> key);

    /** 写入键对应的值（覆盖旧值）。 */
    <T> void set(ContextKey<T> key, T value);

    /** 移除键（等价于置为 {@code null}）。 */
    <T> void remove(ContextKey<T> key);

    /** 是否含该键（含值为 {@code null} 的情况也返回 true）。 */
    <T> boolean contains(ContextKey<T> key);

    /**
     * 若不存在则按 supplier 计算并写入，返回最终值。
     *
     * <p><b>原子性契约（D5-2）</b>：并发调用下 supplier <b>至多执行一次</b>，
     * 且所有调用方拿到的是<b>同一个</b>实例（不存在 get-then-put 竞态导致的
     * "重复创建 / 状态静默丢失"）。
     *
     * <p><b>调用方约束</b>：supplier 应为无副作用的纯构造，<b>不得</b>在计算过程中
     * 再修改本上下文（底层依赖 {@code ConcurrentHashMap.computeIfAbsent}）。
     */
    <T> T computeIfAbsent(ContextKey<T> key, Supplier<? extends T> supplier);
}
