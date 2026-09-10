package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.Callable;

/**
 * 持有当前线程的 {@link TestContext}（per-thread）。
 *
 * <p>这是 T3-1「先收拢」阶段的集中接入点：各模块把原 {@code static ThreadLocal} 改为
 * {@code TestContextHolder.get()...}。后续阶段可改为构造注入以彻底消灭本 Holder。
 *
 * <p>CORE-P0-2 现代化（替代裸 ThreadLocal）：
 * <ul>
 *   <li>内部委托 {@link ThreadContextRegistry}（{@code ConcurrentHashMap<Thread, TestContext>}），
 *       支持集中清理与泄漏观测，杜绝线程池复用串扰；</li>
 *   <li>跨线程传播桥（N-10）：{@link #capture()} / {@link #restore(CapturedContext)} /
 *       {@link #runWithContext(CapturedContext, Runnable)} 使异步 / 工作线程显式继承提交线程上下文；</li>
 *   <li>按需 {@link InheritableThreadLocal} 传播（{@link #setInheritMode(boolean)} /
 *       {@link #publishInheritance()}）：仅当开关开启且为真实派生线程时自动继承父上下文浅拷贝，
 *       线程池场景仍走显式 capture/restore（推荐）。快照浅拷贝，工作线程写入不回灌父线程（隔离保留）。</li>
 * </ul>
 */
public final class TestContextHolder {

    /** 继承标记：本线程上下文已由父快照加载一次，避免后续 get() 重复覆盖自身写入。 */
    private static final ContextKey<Boolean> INHERITED = ContextKey.of("__ctx.inherited", Boolean.class);

    /**
     * 按需继承快照通道（CORE-P0-2）。仅 {@link #setInheritMode(boolean)} 开启时生效：
     * 父线程 {@link #publishInheritance()} 后，新派生的子线程自动继承该快照的浅拷贝（childValue 拷贝）。
     * 线程池预创建线程不触发继承，故不影响线程池语义。
     */
    private static final InheritableThreadLocal<CapturedContext> INHERIT_SNAPSHOT =
            new InheritableThreadLocal<CapturedContext>() {
                @Override
                protected CapturedContext childValue(CapturedContext parentValue) {
                    // 浅拷贝，避免子线程写回影响父线程快照
                    return parentValue == null ? null
                            : new CapturedContext(new HashMap<>(parentValue.snapshot()));
                }
            };

    /** 按需继承开关（默认关：显式 capture/restore 优先，线程池安全）。 */
    private static volatile boolean INHERIT_MODE = false;

    private TestContextHolder() {
    }

    /** 开启 / 关闭「派生线程自动继承父上下文」能力（默认关）。 */
    public static void setInheritMode(boolean on) {
        INHERIT_MODE = on;
    }

    /** 取当前线程上下文（惰性创建）。开启继承模式时优先返回继承到的父快照副本（仅一次）。 */
    public static TestContext get() {
        if (INHERIT_MODE) {
            CapturedContext snap = INHERIT_SNAPSHOT.get();
            if (snap != null) {
                TestContext ctx = ThreadContextRegistry.get();
                if (ctx instanceof ThreadLocalTestContext
                        && !Boolean.TRUE.equals(ctx.get(INHERITED))) {
                    ((ThreadLocalTestContext) ctx).loadSnapshot(snap.snapshot());
                    ctx.set(INHERITED, Boolean.TRUE);
                }
                INHERIT_SNAPSHOT.remove();
                return ctx;
            }
        }
        return ThreadContextRegistry.get();
    }

    /** 父线程调用：把当前上下文快照发布为「可继承」，之后派生的子线程将自动继承（需 INHERIT_MODE=true）。 */
    public static void publishInheritance() {
        if (INHERIT_MODE) {
            INHERIT_SNAPSHOT.set(capture());
        }
    }

    /**
     * scenario 结束时调用：清理本线程上下文并解除绑定，避免线程池复用串扰。
     */
    public static void resetForCurrentThread() {
        ThreadContextRegistry.remove(Thread.currentThread());
        INHERIT_SNAPSHOT.remove();
    }

    /**
     * 上下文传播桥（N-10）：捕获当前线程上下文的不可变快照，供异步 / 工作线程恢复。
     * 快照为浅拷贝——工作线程对上下文的写入不会影响提交线程（隔离保留）。
     *
     * @return 当前上下文快照
     */
    public static CapturedContext capture() {
        TestContext ctx = get();
        if (ctx instanceof ThreadLocalTestContext) {
            return new CapturedContext(((ThreadLocalTestContext) ctx).snapshot());
        }
        // 非默认实现：退化为空快照（不传播），保持既有行为，不抛异常
        return new CapturedContext(Collections.emptyMap());
    }

    /**
     * 在当前线程恢复快照（覆盖现有上下文）。供工作线程任务起始调用。
     *
     * @param captured 由 {@link #capture()} 创建的快照
     */
    public static void restore(CapturedContext captured) {
        TestContext ctx = get();
        if (ctx instanceof ThreadLocalTestContext && captured != null) {
            ((ThreadLocalTestContext) ctx).loadSnapshot(captured.snapshot());
        }
    }

    /**
     * 在捕获的上下文环境中执行任务，结束后重置本线程上下文，避免线程池复用串扰。
     *
     * @param captured 由 {@link #capture()} 创建的快照
     * @param task     待执行任务
     */
    public static void runWithContext(CapturedContext captured, Runnable task) {
        try {
            restore(captured);
            task.run();
        } finally {
            resetForCurrentThread();
        }
    }

    /**
     * 在捕获的上下文环境中执行带返回值的任务，结束后重置本线程上下文。
     *
     * @param captured 由 {@link #capture()} 创建的快照
     * @param task     待执行任务
     * @param <V>      返回值类型
     * @return 任务返回值
     * @throws Exception 任务执行抛出的异常
     */
    public static <V> V callWithContext(CapturedContext captured, Callable<V> task) throws Exception {
        try {
            restore(captured);
            return task.call();
        } finally {
            resetForCurrentThread();
        }
    }

    /** 当前活跃线程上下文数（可观测 / 泄漏排查），委托 {@link ThreadContextRegistry}。 */
    public static int activeContextCount() {
        return ThreadContextRegistry.activeThreadCount();
    }
}
