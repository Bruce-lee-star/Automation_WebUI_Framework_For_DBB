package com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * N-06（doc 21 HIGH）：关闭 / 收尾阶段清理失败的<b>累计数</b>。
     *
     * <p><b>为何需要它</b>：关闭期的清理失败（孤儿浏览器进程 / 未释放线程 / 未关闭句柄）此前只有
     * verbose 门控日志，默认日志级别下<b>完全无输出</b>——而它恰恰是「JVM 外内存耗尽（hs_err
     * native OOM）」的唯一归因手段。计数使这种"无信号失败"变成可被套件末尾 / CI <b>断言</b>的事实。</p>
     */
    private static final AtomicLong failureCount = new AtomicLong();

    /**
     * N-22（doc 21 补记）：JVM 退出期的执行预算 —— 单任务上限（毫秒）。
     *
     * <p><b>要防的缺陷（实测）</b>：某次 pw-web-ui 单测退出时，本编排器在 {@code pw-core} 任务
     * （{@code PlaywrightManager.cleanupAll()}）内耗时约 <b>27s</b>，越过 Surefire 的
     * 「{@code System.exit(0)} 之后 30s 宽限」→ 日志出现
     * {@code [ERROR] Surefire is going to kill self fork JVM}，且<b>后续任务从未执行</b> ——
     * 原设计目标（W-1：避免硬杀留下孤儿浏览器进程）恰被这次硬杀推翻；硬杀还会截断日志/报告落盘，
     * 且没有任何可断言信号。故改为<b>有界执行</b>：超时任务被放弃（daemon 线程随 JVM 消亡）
     * 并计入 {@link #getFailureCount()} + ERROR。</p>
     *
     * <p>取值依据：正常清理为毫秒级（实测其它模块 7~9 个任务合计 &lt; 200ms），
     * 8s/20s 既远大于正常值、又明显小于 Surefire 的 30s 宽限。非正值<b>不</b>表示"无上限"
     * （与 N-16 页面超时 / N-19 面板会话上限同一纪律），而是回落默认并 WARN。</p>
     */
    public static final long DEFAULT_TASK_TIMEOUT_MS = 8_000L;

    /** N-22：全部关闭任务的总预算（毫秒）。见 {@link #DEFAULT_TASK_TIMEOUT_MS}。 */
    public static final long DEFAULT_TOTAL_BUDGET_MS = 20_000L;

    /** N-22：单任务耗时达到该值即在默认日志级别 WARN（正常清理为毫秒级，故不制造噪音）。 */
    private static final long SLOW_TASK_WARN_MS = 3_000L;

    /** N-22：单任务上限的系统属性名（覆盖 {@link #DEFAULT_TASK_TIMEOUT_MS}）。 */
    public static final String TASK_TIMEOUT_PROPERTY = "framework.shutdown.taskTimeoutMs";

    /** N-22：总预算的系统属性名（覆盖 {@link #DEFAULT_TOTAL_BUDGET_MS}）。 */
    public static final String TOTAL_BUDGET_PROPERTY = "framework.shutdown.totalBudgetMs";

    /**
     * 关闭任务。
     *
     * <p><b>不实现 {@link Comparable}</b>：任务间的顺序只由 {@code order} 决定，而身份语义仍是
     * 「对象同一性」（同名去重走 {@code name}），二者并不一致 —— 让 {@code Task} 扮演 {@code Comparable}
     * 会隐含「order 相同即相等」的错误语义（SpotBugs {@code EQ_COMPARETO_USE_OBJECT_EQUALS}）。
     * 排序因此改为注册处显式传入 {@link #BY_ORDER}，语义只在真正需要排序的地方表达。
     */
    private static final class Task {
        final int order;
        final String name;
        final Runnable action;
        Task(int order, String name, Runnable action) {
            this.order = order;
            this.name = name;
            this.action = action;
        }
    }

    /** 执行顺序：{@code order} 升序；{@code order} 相同则保持注册先后（{@link List#sort} 稳定排序）。 */
    private static final Comparator<Task> BY_ORDER = Comparator.comparingInt(t -> t.order);

    private ShutdownCoordinator() {}

    /** 注册一个关闭任务（幂等注册 JVM 钩子，同名去重）。线程安全。 */
    public static synchronized void register(int order, String name, Runnable action) {
        for (Task t : TASKS) {
            if (t.name.equals(name)) {
                return; // 已注册（如 reset 后重 init），跳过避免重复执行
            }
        }
        TASKS.add(new Task(order, name, action));
        TASKS.sort(BY_ORDER);
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

    /**
     * N-06：登记一次「关闭 / 收尾阶段的清理失败」—— 供<b>自行吞掉子步骤异常</b>的组件调用
     * （它们的失败不会冒泡到 {@link #runAll()}，若只打 verbose 日志就等于零信号）。
     *
     * <p>语义：一定会写一条<b>非 verbose 门控</b>的 ERROR（含堆栈），并递增可断言的失败计数。
     * 调用方无需再自行记日志。</p>
     *
     * @param step 失败步骤的可定位名称（建议 {@code "模块/阶段/动作"} 形态）
     * @param t    失败原因（可为 {@code null}）
     */
    public static void recordFailure(String step, Throwable t) {
        long cumulative = failureCount.incrementAndGet();
        LOGGER.error("[ShutdownCoordinator] Cleanup step '{}' FAILED (cumulative failures: {}); "
                        + "resources may leak (orphan browser / thread / handle) — this failure must stay observable",
                step, cumulative, t);
    }

    /** N-06：关闭 / 收尾阶段清理失败的累计数（{@code 0} = 全绿）。供套件末尾 / CI 断言。 */
    public static long getFailureCount() {
        return failureCount.get();
    }

    /**
     * 按 order 升序执行所有任务，且整体受<b>预算约束</b>（N-22）。并发重入保护（running 闩）；
     * 执行结束后复位 running，使 reset() 后能再次 runAll（修复 CORE-P0-3 一次性不可复位的限制）。
     *
     * <p>预算语义见 {@link #DEFAULT_TASK_TIMEOUT_MS}：单任务超上限即放弃并记账；总预算耗尽则停止后续任务
     * 并记账（替代"被 Surefire 硬杀后静默截断"）。</p>
     */
    public static void runAll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            long taskTimeoutMs = resolveBudget(TASK_TIMEOUT_PROPERTY, DEFAULT_TASK_TIMEOUT_MS);
            long totalBudgetMs = resolveBudget(TOTAL_BUDGET_PROPERTY, DEFAULT_TOTAL_BUDGET_MS);
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalBudgetMs);
            LOGGER.info("[ShutdownCoordinator] Running {} shutdown task(s) (per-task limit {}ms, total budget {}ms)",
                    TASKS.size(), taskTimeoutMs, totalBudgetMs);
            for (int i = 0; i < TASKS.size(); i++) {
                Task t = TASKS.get(i);
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                if (remainingMs <= 0) {
                    //  N-22：预算耗尽 —— 显式记账后停止。此前这是"被硬杀后静默截断"，无任何信号。
                    failureCount.incrementAndGet();
                    LOGGER.error("[ShutdownCoordinator] total budget {}ms exhausted before task '{}' — {} task(s) SKIPPED ({}); "
                                    + "a hard kill would have truncated them silently; resources may leak",
                            totalBudgetMs, t.name, TASKS.size() - i, remainingTaskNames(i));
                    break;
                }
                runTaskBounded(t, Math.min(taskTimeoutMs, remainingMs));
            }
            LOGGER.info("[ShutdownCoordinator] Shutdown complete");
        } finally {
            running.set(false);
        }
    }

    /**
     * 执行单个任务，最多等待 {@code timeoutMs}（N-22）。
     *
     * <p><b>为何移到专属 daemon 线程</b>：本方法的调用者之一是 JVM 关闭钩子线程，在其上同步执行就无法设限 ——
     * 一旦任务卡住，JVM 退出必然被 Surefire 硬杀（正是要消除的路径）。任务异常仍在此统一"记 ERROR + 计数"，
     * 与 N-06 语义保持一致。</p>
     *
     * @param task      待执行任务
     * @param timeoutMs 本次等待上限（已按剩余总预算收敛）
     */
    private static void runTaskBounded(Task task, long timeoutMs) {
        LOGGER.info("[ShutdownCoordinator] -> {}", task.name);
        long startNanos = System.nanoTime();
        Thread worker = new Thread(() -> {
            try {
                task.action.run();
            } catch (Throwable e) {
                //  N-06：任务失败同时递增失败计数（原实现只记日志，无法被断言）
                failureCount.incrementAndGet();
                LOGGER.error("[ShutdownCoordinator] Task '{}' failed: {}", task.name, e.getMessage(), e);
            }
        }, "shutdown-task-" + task.name);
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(timeoutMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        if (worker.isAlive()) {
            failureCount.incrementAndGet();
            LOGGER.error("[ShutdownCoordinator] Task '{}' exceeded the {}ms limit (elapsed {}ms) — ABANDONED so that JVM exit is "
                            + "not hard-killed; its thread is a daemon and dies with the JVM. Resources may leak "
                            + "(orphan browser / thread / handle)",
                    task.name, timeoutMs, elapsedMs);
        } else if (elapsedMs >= SLOW_TASK_WARN_MS) {
            LOGGER.warn("[ShutdownCoordinator] Task '{}' took {}ms (slow cleanup — watch for orphan resources)",
                    task.name, elapsedMs);
        }
    }

    /** 列出尚未执行的任务名（用于预算耗尽的错误信息，使"被跳过的清理"可直接定位）。 */
    private static String remainingTaskNames(int fromIndex) {
        StringBuilder names = new StringBuilder();
        for (int i = fromIndex; i < TASKS.size(); i++) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(TASKS.get(i).name);
        }
        return names.toString();
    }

    /**
     * 解析预算系统属性（N-22）：非正值<b>不</b>表示"无上限"（那正是本预算要消除的隐患），
     * 而是回落默认值并 WARN —— 与 N-16（页面超时）/ N-19（面板会话上限）同一纪律。
     *
     * <p>包级可见以便单测直接断言"非法值回落"这一契约（不扩大公共 API）。</p>
     */
    static long resolveBudget(String property, long defaultValue) {
        long value = Long.getLong(property, defaultValue);
        if (value <= 0) {
            LOGGER.warn("[ShutdownCoordinator] {}={} 非法（0/负数不表示「无上限」，那正是本预算要消除的隐患）；已回落默认 {}ms",
                    property, value, defaultValue);
            return defaultValue;
        }
        return value;
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
        //  N-06：失败计数一并复位（与"清空已注册任务"同义：进入全新的生命周期）
        failureCount.set(0);
    }
}
