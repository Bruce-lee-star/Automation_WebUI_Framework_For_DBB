package com.hsbc.cmb.hk.dbb.automation.framework.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
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
 */
public final class ShutdownCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShutdownCoordinator.class);

    /** 预定义顺序：数字越小越先执行。先落库/停止接收新工作，后关闭线程池与框架状态。 */
    public static final int ORDER_API_MONITOR_FLUSH = 100;
    public static final int ORDER_ROUTE_ENGINE     = 200;
    public static final int ORDER_MONITOR_HANDLER  = 300;
    public static final int ORDER_ASYNC_POOL       = 400;
    public static final int ORDER_SESSION_IO       = 500;
    public static final int ORDER_DIAGNOSTICS      = 600;
    public static final int ORDER_FRAMEWORK_CORE   = 900;

    private static final List<Task> TASKS = new ArrayList<>();
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

    /** 按 order 升序执行所有任务。幂等，可被显式调用（如测试或 FrameworkCore 清理）。 */
    public static void runAll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
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
    }
}
