package com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一 JVM 关闭编排器（修复 L1）。
 *
 * <p>框架各处原本各自调用 {@code Runtime.getRuntime().addShutdownHook(...)}，
 * JVM 退出时钩子执行顺序无保证。本类收口为<b>单一</b>关闭钩子，按注册时的
 * {@code order} 升序（越小越先）依次执行各组件的 shutdown 任务，单个任务异常
 * 不影响其余任务。重复触发幂等。
 *
 * <p>用法：组件静态块（或一次性 init）中调用 {@link #register(int, String, Runnable)}
 * 替代直接 {@code addShutdownHook}。同名任务自动去重（应对 reset 后重 init 的测试场景）。
 *
 * <h3>D5-1：下沉到 {@code framework.core.lifecycle}</h3>
 * <p>本类原位于 {@code framework.common}，导致 {@code core} 切片的组件
 * （如 {@code ThreadContextRegistry}）要使用它就必须 core→common，
 * 与既有的 common→core（{@code LanguageState} 依赖 {@code ContextKey}）形成循环，
 * 破坏 G1「framework 切片无循环」门禁 —— 结果是 core 侧<b>被迫放弃登记资源</b>。
 *
 * <p>下沉到 {@code core.lifecycle} 后依赖方向收敛为单向：
 * <ul>
 *   <li>{@code core.*} → {@code core.lifecycle}：同切片内，无跨切片依赖；</li>
 *   <li>{@code common.*} → {@code core.*}：与 {@code LanguageState} 同向，不新增循环。</li>
 * </ul>
 * 资源登记契约因此清晰：任何组件都能登记关闭任务，架构门禁不再被迫取舍。
 */
public final class ShutdownCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShutdownCoordinator.class);

    /**
     * 预定义顺序：数字越小越先执行。先落库/停止接收新工作，后关闭线程池与框架状态。
     * <p>{@link #ORDER_TEST_CONTEXT} 放在最后：其它关闭任务执行期间仍可能读取线程上下文，
     * 过早清空会破坏它们的收尾逻辑。
     */
    public static final int ORDER_API_MONITOR_FLUSH = 100;
    public static final int ORDER_ROUTE_ENGINE     = 200;
    public static final int ORDER_MONITOR_HANDLER  = 300;
    public static final int ORDER_ASYNC_POOL       = 400;
    public static final int ORDER_SESSION_IO       = 500;
    public static final int ORDER_DIAGNOSTICS      = 600;
    public static final int ORDER_FRAMEWORK_CORE   = 900;
    /** D5-1：线程上下文兜底清理（最后执行）。 */
    public static final int ORDER_TEST_CONTEXT     = 950;

    // 修复 CORE-P0-3/N5：CopyOnWriteArrayList 支持 runAll 执行期间并发 register 而不抛 CME；
    // 同时配合 reset() 支持"执行后重注册"的测试/重 init 场景。
    private static final List<Task> TASKS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean hookRegistered = new AtomicBoolean(false);
    private static final AtomicBoolean running = new AtomicBoolean(false);

    private static final class Task implements Comparable<Task> {
        final int order;
        final String name;
        final Runnable action;
        Task(int order, String name, Runnable action) {
            this.order = order;
            this.name = name;
            this.action = action;
        }
        @Override
        public int compareTo(Task o) {
            return Integer.compare(order, o.order);
        }
    }

    private ShutdownCoordinator() {}

    /** 注册一个关闭任务（幂等注册 JVM 钩子，同名去重）。线程安全。 */
    public static synchronized void register(int order, String name, Runnable action) {
        for (Task t : TASKS) {
            if (t.name.equals(name)) {
                return; // 已注册（如 reset 后重 init），跳过避免重复执行
            }
        }
        TASKS.add(new Task(order, name, action));
        TASKS.sort(null);
        ensureHookRegistered();
    }

    private static void ensureHookRegistered() {
        if (hookRegistered.compareAndSet(false, true)) {
            try {
                Runtime.getRuntime().addShutdownHook(
                        new Thread(ShutdownCoordinator::runAll, "framework-shutdown-coordinator"));
            } catch (IllegalStateException e) {
                // JVM 已处于 shutdown 阶段（极端时序），直接尝试执行
                runAll();
            }
        }
    }

    /** 按 order 升序执行所有任务。并发重入保护（running 闩）；执行结束后复位 running，
     *  使 reset() 后能再次 runAll（修复 CORE-P0-3 一次性不可复位的限制）。 */
    public static void runAll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            LOGGER.info("[ShutdownCoordinator] Running {} shutdown task(s)", TASKS.size());
            for (Task t : TASKS) {
                try {
                    LOGGER.info("[ShutdownCoordinator] -> {}", t.name);
                    t.action.run();
                } catch (Throwable e) {
                    LOGGER.error("[ShutdownCoordinator] Task '{}' failed: {}", t.name, e.getMessage(), e);
                }
            }
            LOGGER.info("[ShutdownCoordinator] Shutdown complete");
        } finally {
            running.set(false);
        }
    }

    /**
     * 清空已注册任务并复位运行标记（修复 CORE-P0-3）。用于测试或 FrameworkCore 重 init：
     * reset 后 {@link #register(int, String, Runnable)} 可再次注册，runAll 可再次执行。
     * 注意：若 JVM 已进入 shutdown 阶段，重置后已注册的钩子不会再被触发，需显式调用 runAll。
     */
    public static synchronized void reset() {
        TASKS.clear();
        running.set(false);
        hookRegistered.set(false);
    }
}
