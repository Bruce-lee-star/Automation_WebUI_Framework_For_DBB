package com.hsbc.cmb.hk.dbb.automation.framework.common.async;

import com.hsbc.cmb.hk.dbb.automation.framework.common.assertion.SoftAssertions;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigKeys;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigSource;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.FrameworkFlags;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.LazyInit;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.CapturedContext;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用异步任务池 — 全项目统一的异步执行入口 + 集中监控。
 *
 * <p>由原路由域异步池抽取并泛化，供任意模块复用：
 * 数据库刷入器、超时调度、事件回调、报告记录等"不阻塞调用方线程"的任务。
 *
 * <p>核心能力：
 * <ul>
 *   <li><b>立即执行</b>：{@link #run(Runnable)} / {@link #runWithTimeout(Runnable, long)}</li>
 *   <li><b>延迟 / 周期执行</b>：{@link #schedule(Runnable, long)} / {@link #scheduleWithFixedDelay(Runnable, long, long)}</li>
 *   <li><b>任务超时</b>：{@code runWithTimeout} 超时不响应中断的任务会被 {@code Future.cancel(true)} 中止。
 *       CT2-17：由<b>单一周期裁决器</b>统一裁决（在途带超时任务登记表 + 1s 周期扫描），
 *       调度队列恒为 O(1)，<b>不存在任何「上限触顶后放弃执行超时保护」的降级路径</b>；
 *       代价仅为裁决粒度（最多晚一个周期执行 {@code cancel(true)}）。</li>
 *   <li><b>队列限流</b>：{@code DiscardOldestPolicy} + 告警，保证调用方（含 Playwright 事件线程）永不阻塞</li>
 *   <li><b>阈值告警</b>：队列/线程使用率、待处理超时数超阈值时告警</li>
 *   <li><b>集中监控</b>：{@link #getStatusSnapshot()} 暴露活跃/队列/完成/超时/挂起等指标</li>
 *   <li><b>优雅关闭</b>：JVM 关闭钩子 + {@link #shutdown()} 统一关闭主池与调度器</li>
 * </ul>
 *
 * <p>环境变量（可选，前缀 {@code ASYNC_}）：
 * <pre>
 *   ASYNC_CORE_THREADS=2
 *   ASYNC_MAX_THREADS=6
 *   ASYNC_QUEUE_CAPACITY=200
 *   ASYNC_TASK_TIMEOUT_MS=30000
 *   ASYNC_MAX_PENDING_TIMEOUTS=500
 * </pre>
 *
 * <p>线程均为守护线程，JVM 退出不会因本池而挂起；必要时由 {@link #shutdown()} 或关闭钩子等待进行中任务完成。
 */
public final class AsyncPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncPool.class);

    private static final AtomicLong rejectedCount = new AtomicLong(0);
    private static final AtomicLong completedTaskCount = new AtomicLong(0);
    private static final AtomicLong timeoutCount = new AtomicLong(0);
    private static final AtomicLong pendingTimeoutCount = new AtomicLong(0);
    private static final AtomicLong pendingScheduleCount = new AtomicLong(0);
    /** Monitor 回调因队列满/已关闭被丢弃的累计数（ 修复 H17：让静默丢弃变为可观测） */
    private static final AtomicLong monitorCallbackDroppedCount = new AtomicLong(0);

    /** 活跃的 per-Context 调度器（由 ContextRouteEngine 注册，关闭时移除） */
    private static final Map<String, ScheduledThreadPoolExecutor> CONTEXT_SCHEDULERS = new ConcurrentHashMap<>();

    /** 主池：P2-2 起改为<b>首次使用时</b>构造并发布（见 {@link #INIT} / {@link #doInit()}），故不再 final。 */
    private static volatile ThreadPoolExecutor POOL;

    /** 调度器：同 {@link #POOL}，惰性构造。 */
    private static volatile ScheduledThreadPoolExecutor SCHEDULER;

    /**
     *  串行单线程执行器 — 专用于 Monitor 用户回调（onResponse）。
     * <p>Playwright route 拦截在事件线程触发，若直接在该线程执行用户回调，用户无法预期
     * "回调里修改的全局/共享状态（如 NLSUtils.setLanguage）对主线程不可见"（ThreadLocal 隔离）。
     * 统一桥接到本串行线程后，所有回调在<b>同一受管上下文线程</b>顺序执行，配合已全局化的
     * 框架状态（NLSUtils 等），用户业务代码无需理解线程模型即可"影响主线程"。
     */
    /**
     *  Monitor 回调队列容量上限。
     * <p>原实现用 {@code Executors.newSingleThreadExecutor()}，其队列是
     * <b>无界</b> LinkedBlockingQueue：慢回调（如 DB 校验）持续积压会让队列无限增长直至 OOM；
     * 且无拒绝策略，积压只能靠消费者追上来消化。
     * <p>注意：<b>仍然保持单线程</b>。串行执行是本执行器的<b>语义契约</b>（见上：回调里修改的
     * 共享状态需在【同一受管线程】顺序生效），改成多线程会引入竞态并破坏用户可见性保证。
     * 因此这里只做「有界」，不做「并发」——慢回调的吞吐问题应通过把重活改投
     * {@link #run(Runnable)} 解决，而不是拆散本串行队列。
     */
    private static final int MONITOR_CALLBACK_QUEUE_CAPACITY = 10_000;

    /**
     * CT2-17：由 {@code static final} 改为 {@code volatile} + 工厂构造 —— 关闭后可复位重建，
     * 使同 JVM 内后续套件能重新初始化（见 {@link LazyInit#reset()} 与 {@link #shutdownGracefully()}）。
     * 非 null 由 {@link #ensureInitialized()} 保证（所有使用入口均已先调用它）。
     */
    private static volatile ExecutorService MONITOR_CALLBACK_EXECUTOR;

    private static ExecutorService newMonitorCallbackExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MONITOR_CALLBACK_QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "monitor-callback");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                // 队列满：丢弃 + 告警，绝不反压提交方。
                // 若用 CallerRunsPolicy，回调会在 Playwright 事件线程上执行，
                // 把「回调慢」放大成「路由拦截阻塞 → 整轮测试卡死」，违背永不卡死原则。
                (task, executor) -> {
                    long dropped = monitorCallbackDroppedCount.incrementAndGet();
                    LOGGER.error("[AsyncPool] Monitor callback queue full (capacity={}), dropping task to avoid OOM. "
                            + "Dropped total: {}. Consider moving heavy work out of onResponse into AsyncPool.run().",
                            MONITOR_CALLBACK_QUEUE_CAPACITY, dropped);
                });
    }

    /**
     * P2-3：因提交被拒（池关闭 / 饱和）而<b>丢弃</b>的任务数 —— 统一「绝不阻塞提交方」策略后的可观测计数。
     */
    private static final AtomicLong droppedOnRejectionCount = new AtomicLong();

    /**
     * CT2-17：<b>在途带超时任务</b>登记表（{@code Future → 截止时刻 nanos}）。
     *
     * <p>取代原「每任务向 SCHEDULER 投一个超时哨兵」的实现。原实现的哨兵会让
     * {@code ScheduledThreadPoolExecutor} 的<b>无界</b> DelayedWorkQueue 随并发线性增长，
     * 故设了 {@code ASYNC_MAX_PENDING_TIMEOUTS} 上限；一旦触顶便<b>不再投递哨兵</b>，
     * 即「超时强制取消」在全进程范围内<b>静默失效</b>（卡死任务长期占用池线程，无人能中断它）。
     *
     * <p>现改为「登记 + 单一周期裁决器」（{@link #reapExpiredTimeouts()}）：调度队列恒为 O(1)，
     * 超时保护<b>不再存在任何降级/失效路径</b>。表项最迟在各自截止时刻被裁决器移除，
     * 故规模自然有界（≈ 最近一个超时窗口内提交的带超时任务数），无需人工上限。
     */
    private static final ConcurrentHashMap<Future<?>, Long> PENDING_TIMEOUTS = new ConcurrentHashMap<>();

    /** CT2-17：超时裁决周期（毫秒）—— 「已超时」到「执行 cancel(true)」的最大额外延迟。 */
    private static final long TIMEOUT_REAPER_INTERVAL_MS = 1_000L;

    /** CT2-17：超时裁决器句柄（关闭时取消，避免终止后的 SCHEDULER 上残留周期任务）。 */
    private static volatile ScheduledFuture<?> timeoutReaper;

    //  P2-2：以下配置字段在首次使用时由 doInit() 赋值（故不再 final）；可见性由 INIT 的
    //  volatile 发布建立 happens-before 保证。
    private static int CORE_THREADS;
    private static int MAX_THREADS;
    private static int QUEUE_CAPACITY;
    private static long DEFAULT_TASK_TIMEOUT_MS;
    private static final long KEEP_ALIVE_SECONDS = 30;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static double QUEUE_USAGE_ALERT_THRESHOLD;
    private static double THREAD_USAGE_ALERT_THRESHOLD;
    private static int MAX_PENDING_TIMEOUTS;

    /**
     * P2-2：初始化守卫 —— 首次使用 AsyncPool 时才读配置 / 建池。
     *
     * <p>原实现把配置读取放在 {@code static {}} 块：一旦配置异常（如密文解密失败快），JVM 会包装为
     * {@link ExceptionInInitializerError}，且本类在同一 JVM 内<b>永久不可用</b>（后续访问直接
     * NoClassDefFoundError）。改为惰性后，失败以清晰的 {@link IllegalStateException}（带原始 cause）
     * 在<b>首次调用点</b>抛出，类不再被污染。</p>
     */
    private static final LazyInit INIT = new LazyInit("AsyncPool", AsyncPool::doInit);

    /** P2-2：所有真正使用池的入口必须先经过本方法（失败抛清晰异常）。 */
    private static void ensureInitialized() {
        INIT.ensure();
    }

    /**
     * 初始化动作（首次使用时执行）：读全部配置 → 构造并发布主池 / 调度器 → 注册关闭钩子。
     *
     * <p><b>顺序刻意如此</b>：先做全部<b>可能失败</b>的配置读取，再构造资源并赋值给字段 ——
     * 失败时 {@code POOL}/{@code SCHEDULER} 仍为 null（<b>不留半初始化状态</b>），
     * 使 {@link #shutdown()} 可安全短路，也避免"半初始化的池被子线程误用"。</p>
     */
    private static void doInit() {
        //  P2-1：键名与默认值统一取自 ConfigKeys 注册表（SSoT）—— 调用点不再持有字面量，
        //  避免「注册表默认值」与「读取点默认值」两处漂移（golden 快照只守得住前者）。
        int coreThreads = cfgInt(ConfigKeys.ASYNC_CORE_THREADS);
        int maxThreads = cfgInt(ConfigKeys.ASYNC_MAX_THREADS);
        int queueCapacity = cfgInt(ConfigKeys.ASYNC_QUEUE_CAPACITY);
        long defaultTaskTimeoutMs = cfgLong(ConfigKeys.ASYNC_TASK_TIMEOUT_MS);
        double queueUsageAlertThreshold = cfgDouble(ConfigKeys.ASYNC_QUEUE_USAGE_ALERT_THRESHOLD);
        double threadUsageAlertThreshold = cfgDouble(ConfigKeys.ASYNC_THREAD_USAGE_ALERT_THRESHOLD);
        int maxPendingTimeouts = cfgInt(ConfigKeys.ASYNC_MAX_PENDING_TIMEOUTS);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                coreThreads, maxThreads, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "async-pool");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                (r, threadPoolExecutor) -> {
                    long count = rejectedCount.incrementAndGet();
                    LOGGER.error("[AsyncPool] TASK REJECTED - discarding oldest. Rejected: {}, Active: {}, "
                                    + "Pool: {}/{}, Queue: {}/{}",
                            count, threadPoolExecutor.getActiveCount(),
                            threadPoolExecutor.getPoolSize(), threadPoolExecutor.getMaximumPoolSize(),
                            threadPoolExecutor.getQueue().size(), queueCapacity);
                    new ThreadPoolExecutor.DiscardOldestPolicy().rejectedExecution(r, threadPoolExecutor);
                });
        executor.allowCoreThreadTimeOut(true);

        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "async-sched");
            t.setDaemon(true);
            return t;
        });
        scheduler.setRemoveOnCancelPolicy(true);

        //  发布（此后对其它线程可见：LazyInit 借 volatile initialized 建立 happens-before）
        CORE_THREADS = coreThreads;
        MAX_THREADS = maxThreads;
        QUEUE_CAPACITY = queueCapacity;
        DEFAULT_TASK_TIMEOUT_MS = defaultTaskTimeoutMs;
        QUEUE_USAGE_ALERT_THRESHOLD = queueUsageAlertThreshold;
        THREAD_USAGE_ALERT_THRESHOLD = threadUsageAlertThreshold;
        MAX_PENDING_TIMEOUTS = maxPendingTimeouts;
        POOL = executor;
        SCHEDULER = scheduler;
        MONITOR_CALLBACK_EXECUTOR = newMonitorCallbackExecutor();
        // CT2-17：启动超时裁决器（取代「每任务一个哨兵」——调度队列恒为 O(1)，且无降级路径）
        startTimeoutReaper();

        com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator.register(
                com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator.ORDER_ASYNC_POOL,
                "async-pool", () -> {
                    LOGGER.info("[AsyncPool] JVM shutdown hook triggered.");
                    shutdownGracefully();
                });

        VerboseLogging.logInfoIfVerbose(LOGGER,
                "[AsyncPool] Initialized: core={}, max={}, queue={}, timeout={}ms, maxPendingTimeouts={}",
                CORE_THREADS, MAX_THREADS, QUEUE_CAPACITY, DEFAULT_TASK_TIMEOUT_MS, MAX_PENDING_TIMEOUTS);
    }

    // ─── CT2-17：超时裁决（取代每任务哨兵） ─────────────────────────

    /** 启动超时裁决器（幂等：重复调用先取消旧句柄）。 */
    private static void startTimeoutReaper() {
        ScheduledFuture<?> previous = timeoutReaper;
        if (previous != null) {
            previous.cancel(false);
        }
        timeoutReaper = SCHEDULER.scheduleWithFixedDelay(
                AsyncPool::reapExpiredTimeouts,
                TIMEOUT_REAPER_INTERVAL_MS, TIMEOUT_REAPER_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * CT2-17：登记一个带超时任务（{@code Future → 截止时刻}），交由 {@link #reapExpiredTimeouts()} 裁决。
     *
     * <p>不在此处移除表项：任务正常完成时任务体拿不到自身 {@code Future} 引用，故统一由裁决器
     * 在截止时刻移除并判定 —— 届时若 {@code isDone()} 则视为正常完成，不误报超时。
     */
    private static void registerPendingTimeout(Future<?> future, long timeoutMs) {
        if (future == null) {
            return;
        }
        PENDING_TIMEOUTS.put(future, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs));
        pendingTimeoutCount.incrementAndGet();
    }

    /**
     * 周期裁决：对已过截止时刻的在途带超时任务执行 {@code cancel(true)}。
     *
     * <p><b>不得向外抛异常</b>：本方法由 {@code scheduleWithFixedDelay} 驱动，而
     * {@link ScheduledThreadPoolExecutor} 在周期任务抛出异常后会<b>静默取消后续调度</b>
     * ——整个超时保护将失效且无任何提示。故整体包 {@code catch (Throwable)}。
     */
    private static void reapExpiredTimeouts() {
        try {
            long now = System.nanoTime();
            for (Map.Entry<Future<?>, Long> entry : PENDING_TIMEOUTS.entrySet()) {
                Future<?> future = entry.getKey();
                Long deadline = entry.getValue();
                if (deadline == null || deadline > now) {
                    continue;
                }
                if (!PENDING_TIMEOUTS.remove(future, deadline)) {
                    continue; // 已被并发移除
                }
                pendingTimeoutCount.decrementAndGet();
                if (future.isDone()) {
                    continue; // 已正常完成（只是尚未到裁决点）：不误报超时
                }
                boolean cancelled = future.cancel(true);
                long count = timeoutCount.incrementAndGet();
                //  CT2-17：裁决器与 shutdown 并发时 POOL 可能已被置空 —— 诊断日志不得抛 NPE（-1 表示不可用）
                ThreadPoolExecutor pool = POOL;
                LOGGER.error("[AsyncPool] TASK TIMEOUT (total: {}). Cancelled: {}, Active: {}, Queue: {}/{}",
                        count, cancelled,
                        pool == null ? -1 : pool.getActiveCount(),
                        pool == null ? -1 : pool.getQueue().size(), QUEUE_CAPACITY);
                checkThresholdsAfterTimeout();
            }
        } catch (Throwable t) {
            // 绝不外抛：周期任务抛异常会被 ScheduledThreadPoolExecutor 静默停掉，超时保护随之整体失效
            LOGGER.error("[AsyncPool] timeout reaper failed (enforcement continues next cycle): {}",
                    t.toString(), t);
        }
    }

    private AsyncPool() {}

    // ─── 立即执行 ──────────────────────────────────────────────

    /** 异步执行任务（无超时）。task 为 null 静默跳过。 */
    public static void run(Runnable task) {
        submitTask(task, 0);
    }

    /** 异步执行任务，带超时（毫秒，≤0 表示无限制）。 */
    public static void runWithTimeout(Runnable task, long timeoutMs) {
        submitTask(task, timeoutMs > 0 ? timeoutMs : 0);
    }

    private static void submitTask(Runnable task, long timeoutMs) {
        if (task == null)  {return;} 
        ensureInitialized();   // P2-2：首次使用时初始化（配置失败在此抛出清晰异常）
        //  CT2-17 回归防护：取一次【局部快照】。
        //    shutdown() 现在会把 POOL 置空（以支持同 JVM 复位重建），若与提交并发，
        //    直接解引用 POOL 会得到 NPE —— 而原实现 POOL 永不为 null（失败形态是干净的拒绝）。
        //    引入快照后：要么提交成功、要么得到明确的 RejectedExecutionException 走既有丢弃路径，
        //    绝不出现「换个方式崩溃」的新失败形态。
        final ThreadPoolExecutor pool = POOL;
        if (pool == null) {
            long dropped = droppedOnRejectionCount.incrementAndGet();
            LOGGER.error("[AsyncPool] submit skipped: executor unavailable (shutdown in progress) → task DROPPED "
                    + "(policy: never block the submitting thread). Dropped total: {}", dropped);
            return;
        }
        // N-10：捕获提交线程上下文，供工作线程恢复（执行后由 runWithContext 复位，隔离保留）
        final CapturedContext captured = TestContextHolder.capture();
        // C-5：捕获提交线程 MDC（日志诊断上下文：scenarioId / traceId / requestId 等），
        // 供工作线程恢复，使异步任务日志关联到同一链路；worker finally 中 clear 复位，避免线程复用污染。
        final Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        // F-13：捕获提交线程的软断言收集器交给工作线程共享 —— 使异步任务里记录的失败
        // 随父线程场景末 assertAll() 一并判红（原实现下其失败落在 worker 自己的收集器上，无人上报 → 静默假绿）。
        final SoftAssertions.Collector assertionCollector = SoftAssertions.captureCollector();
        checkThresholdsBeforeSubmit();
        VerboseLogging.logTraceIfVerbose(LOGGER,
                "[AsyncPool] submit: timeout={}ms, queue={}/{}, active={}",
                timeoutMs, pool.getQueue().size(), QUEUE_CAPACITY, pool.getActiveCount());
        try {
            Future<?> future = pool.submit(() -> {
                if (mdcContext != null) {
                    MDC.setContextMap(mdcContext);
                }
                SoftAssertions.Collector previousCollector = SoftAssertions.bindCollector(assertionCollector);
                try {
                    TestContextHolder.runWithContext(captured, task);
                } catch (Throwable t) {
                    LOGGER.error("[AsyncPool] Task threw exception: {}", t.getMessage(), t);
                } finally {
                    completedTaskCount.incrementAndGet();
                    SoftAssertions.unbindCollector(previousCollector);
                    MDC.clear();
                }
            });
            if (timeoutMs > 0) {
                //  CT2-17：改为「登记在途带超时任务 + 单一周期裁决器」（见 registerPendingTimeout /
                //  reapExpiredTimeouts）。原实现每任务向 SCHEDULER 投一个哨兵任务，因
                //  ScheduledThreadPoolExecutor 的 DelayedWorkQueue 无界，故在
                //  ASYNC_MAX_PENDING_TIMEOUTS 触顶后**跳过投递哨兵** ——
                //  即「超时强制取消」在全进程范围内静默失效（卡死任务长期占用池线程，无人能中断）。
                //  现调度队列恒为 O(1)，超时保护不再有任何降级/失效路径；
                //  唯一代价是裁决粒度（TIMEOUT_REAPER_INTERVAL_MS，最迟晚一个周期执行 cancel(true)）。
                registerPendingTimeout(future, timeoutMs);
            }
        } catch (RejectedExecutionException e) {
            //  P2-3：统一「是否阻塞提交方」策略 = 【绝不阻塞提交方】（与 runOnMonitorCallbackThread 的 H17
            //  决策一致）。原实现此处 task.run() 回退到调用方线程执行 —— 同一「拒绝」情境两套相反策略：
            //  本池的调用方含 Playwright 事件线程与路由处理线程，在其上跑任务会把"拒绝"放大成级联卡顿。
            //  现统一为：计数 + ERROR + 丢弃（可见、不静默）。AsyncPool 承载的都是 best-effort 异步任务
            //  （监控 / 日志 / 收尾），丢弃可观测且代价远低于阻塞事件线程。
            long dropped = droppedOnRejectionCount.incrementAndGet();
            LOGGER.error("[AsyncPool] Submit REJECTED (pool shutting down or saturated) → task DROPPED "
                    + "(policy: never block the submitting thread). Dropped total: {}", dropped, e);
        } catch (Exception e) {
            VerboseLogging.logWarnIfVerbose(LOGGER, "[AsyncPool] Submit failed: {}", e.getMessage());
        }
    }

    // ─── 延迟 / 周期执行 ────────────────────────────────────────

    /** 延迟 delayMs 毫秒后执行（单次）。 */
    public static ScheduledFuture<?> schedule(Runnable task, long delayMs) {
        if (task == null)  {return null;} 
        ensureInitialized();   // P2-2
        long pending = pendingScheduleCount.incrementAndGet();
        // C-5：捕获提交线程 MDC，调度线程执行时恢复，finally clear。
        final Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        // F-13：同样捕获软断言收集器，使延迟任务中的软断言失败随父线程场景末 assertAll() 判红。
        final SoftAssertions.Collector assertionCollector = SoftAssertions.captureCollector();
        try {
            return SCHEDULER.schedule(() -> {
                if (mdcContext != null) {
                    MDC.setContextMap(mdcContext);
                }
                SoftAssertions.Collector previousCollector = SoftAssertions.bindCollector(assertionCollector);
                try {
                    task.run();
                } finally {
                    pendingScheduleCount.decrementAndGet();
                    SoftAssertions.unbindCollector(previousCollector);
                    MDC.clear();
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // CT2-17：统一拒绝策略 —— 与 {run()} 一致（绝不阻塞提交方 + 计数 + ERROR + 丢弃）。
            //  原实现此处直接外抛 RejectedExecutionException，而同类的 run() 是「静默丢弃」，
            //  同一 JVM 内出现「调用点崩溃」与「静默丢任务」两种相反后果。
            //  返回 null 的调用契约：调用方必须容忍 null（唯一现有调用方 RouteMonitorSession 已 null-check）。
            pendingScheduleCount.decrementAndGet();
            long dropped = droppedOnRejectionCount.incrementAndGet();
            LOGGER.error("[AsyncPool] schedule REJECTED (pool shutting down or saturated) → task DROPPED "
                    + "(policy: never block the submitting thread). Dropped total: {}", dropped, e);
            return null;
        }
    }

    /** 固定延迟周期执行（initialDelay 后首次，之后每 delayMs 一次）。 */
    public static ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initialDelayMs, long delayMs) {
        if (task == null)  {return null;} 
        ensureInitialized();   // P2-2
        // F-13：周期性任务同样传播软断言收集器（每次执行 bind / finally unbind，池线程不留残留绑定）。
        final SoftAssertions.Collector assertionCollector = SoftAssertions.captureCollector();
        try {
            return SCHEDULER.scheduleWithFixedDelay(() -> {
                SoftAssertions.Collector previousCollector = SoftAssertions.bindCollector(assertionCollector);
                try {
                    task.run();
                } finally {
                    SoftAssertions.unbindCollector(previousCollector);
                }
            }, initialDelayMs, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // CT2-17：与 schedule / run 保持同一拒绝策略（绝不阻塞提交方，丢弃可观测）。
            long dropped = droppedOnRejectionCount.incrementAndGet();
            LOGGER.error("[AsyncPool] scheduleWithFixedDelay REJECTED (pool shutting down or saturated) → task DROPPED "
                    + "(policy: never block the submitting thread). Dropped total: {}", dropped, e);
            return null;
        }
    }

    /**
     * 为某个 BrowserContext 创建独立的延迟调度器，复用本池的线程工厂规范
     * （守护线程、{@code async-ctx-<id>} 命名、{@code removeOnCancel}），但拥有
     * 独立生命周期——由调用方（ContextRouteEngine）在 context 关闭时 shutdown。
     *
     * <p>注册到 {@link #CONTEXT_SCHEDULERS} 以便集中观测活跃数；关闭时必须调用
     * {@link #removeContextScheduler(String)} 解注册。
     *
     * @param contextId 上下文标识（如 BrowserContext 的 identity hash）
     * @param coreThreads 核心线程数
     * @return 该 context 专属的调度器
     */
    public static ScheduledThreadPoolExecutor newContextScheduler(String contextId, int coreThreads) {
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(
                Math.max(1, coreThreads),
                r -> {
                    Thread t = new Thread(r, "async-ctx-" + contextId);
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                });
        ex.setRemoveOnCancelPolicy(true);
        ScheduledThreadPoolExecutor previous = CONTEXT_SCHEDULERS.put(contextId, ex);
        if (previous != null) {
            //  评审：key 碰撞会让「旧池失去跟踪」且「按 key remove 会误删新池条目」，使
            //  getActiveContextSchedulerCount() 这一泄漏判据失真。PerContextEngine 已改为
            //  「自增序号 + identityHashCode」保证唯一；此处保留检测，任何未来回归都会显式告警，
            //  而不是静默计数失真。
            LOGGER.warn("[AsyncPool] context scheduler id collision on '{}' — previous pool lost tracking; "
                    + "context ids must be unique", contextId);
        }
        VerboseLogging.logInfoIfVerbose(LOGGER, "[AsyncPool] Created context scheduler '{}' (active ctx schedulers: {})",
                contextId, CONTEXT_SCHEDULERS.size());
        return ex;
    }

    /** 解除某个 context 调度器的注册（应在其 shutdown 后调用）。 */
    public static void removeContextScheduler(String contextId) {
        ScheduledThreadPoolExecutor ex = CONTEXT_SCHEDULERS.remove(contextId);
        if (ex != null) {
            VerboseLogging.logInfoIfVerbose(LOGGER, "[AsyncPool] Removed context scheduler '{}' (active ctx schedulers: {})",
                    contextId, CONTEXT_SCHEDULERS.size());
        }
    }

    /** 当前活跃的 per-Context 调度器数量。 */
    public static int getActiveContextSchedulerCount() {
        return CONTEXT_SCHEDULERS.size();
    }

    // ─── 阈值告警 ──────────────────────────────────────────────

    private static void checkThresholdsBeforeSubmit() {
        //  CT2-17：提交路径已保证入池前 POOL 非空；此处仍取快照，防并发 shutdown 造成的 NPE。
        ThreadPoolExecutor pool = POOL;
        if (pool == null) {
            return;
        }
        int queueSize = pool.getQueue().size();
        int activeCount = pool.getActiveCount();
        int poolSize = pool.getPoolSize();
        double queueUsage = (double) queueSize / QUEUE_CAPACITY;
        double threadUsage = (double) activeCount / Math.max(poolSize, 1);

        if (queueUsage >= QUEUE_USAGE_ALERT_THRESHOLD) {
            LOGGER.error("[AsyncPool] ALERT: Queue usage {} exceeds threshold {}. Queue: {}/{}, Active: {}, Pool: {}/{}",
                    String.format("%.1f%%", queueUsage * 100),
                    String.format("%.1f%%", QUEUE_USAGE_ALERT_THRESHOLD * 100),
                    queueSize, QUEUE_CAPACITY, activeCount, poolSize, MAX_THREADS);
        } else  {if (queueUsage >= QUEUE_USAGE_ALERT_THRESHOLD * 0.7) {
            LOGGER.warn("[AsyncPool] WARNING: Queue usage {} approaching threshold. Queue: {}/{}, Active: {}",
                    String.format("%.1f%%", queueUsage * 100), queueSize, QUEUE_CAPACITY, activeCount);
        }} 
        if (threadUsage >= THREAD_USAGE_ALERT_THRESHOLD) {
            LOGGER.error("[AsyncPool] ALERT: Thread usage {} exceeds threshold {}. Active: {}, Pool: {}/{}",
                    String.format("%.1f%%", threadUsage * 100),
                    String.format("%.1f%%", THREAD_USAGE_ALERT_THRESHOLD * 100),
                    activeCount, poolSize, MAX_THREADS);
        }
        long pending = pendingTimeoutCount.get();
        if (pending >= MAX_PENDING_TIMEOUTS) {
            LOGGER.error("[AsyncPool] ALERT: Pending timeouts ({}) exceeded max ({}). Completed: {}, Timeouts: {}",
                    pending, MAX_PENDING_TIMEOUTS, completedTaskCount.get(), timeoutCount.get());
        } else  {if (pending >= MAX_PENDING_TIMEOUTS * 0.7) {
            LOGGER.warn("[AsyncPool] WARNING: Pending timeouts ({}) approaching max ({}). Completed: {}, Timeouts: {}",
                    pending, MAX_PENDING_TIMEOUTS, completedTaskCount.get(), timeoutCount.get());
        }} 
    }

    private static void checkThresholdsAfterTimeout() {
        //  CT2-17：POOL 可能已被 shutdown() 置空（复位重建）—— 诊断日志绝不因此抛 NPE。
        ThreadPoolExecutor pool = POOL;
        if (pool == null) {
            return;
        }
        int queueSizeAfterTimeout = pool.getQueue().size();
        if (queueSizeAfterTimeout > QUEUE_CAPACITY * 0.5) {
            LOGGER.warn("[AsyncPool] After timeout - Queue still has {} pending. Consider raising ASYNC_QUEUE_CAPACITY/ASYNC_MAX_THREADS.",
                    queueSizeAfterTimeout);
        }
    }

    // ─── 优雅关闭 ──────────────────────────────────────────────

    private static void shutdownGracefully() {
        //  CT2-17：幂等 + 复位安全 —— 三资源在 doInit 中同批发布、在下方同批清空，
        //  故任一为 null 即表示「从未初始化」或「已复位」，此时无资源可关，直接返回。
        //  （也覆盖了「doInit 被执行两次 → ShutdownCoordinator 注册了两个钩子」的重复调用场景。）
        if (POOL == null || SCHEDULER == null || MONITOR_CALLBACK_EXECUTOR == null) {
            return;
        }
        if (POOL.isShutdown())  {return;} 
        LOGGER.info("[AsyncPool] Shutting down (active: {}, queue: {}, completed: {}, timeouts: {}, pendingTimeouts: {}, pendingSched: {})...",
                POOL.getActiveCount(), POOL.getQueue().size(), completedTaskCount.get(),
                timeoutCount.get(), pendingTimeoutCount.get(), pendingScheduleCount.get());
        POOL.shutdown();
        SCHEDULER.shutdown();
        // 关键遍历关闭每个 per-context Scheduler，避免线程池残留
        int ctxSchedulers = CONTEXT_SCHEDULERS.size();
        if (ctxSchedulers > 0) {
            LOGGER.info("[AsyncPool] Shutting down {} per-context scheduler(s)", ctxSchedulers);
            for (ScheduledExecutorService s : CONTEXT_SCHEDULERS.values()) {
                try {
                    s.shutdown();
                } catch (Exception e) {
                    // 单个池失败不影响其他池关闭，但不得静默（D7-3）
                    LOGGER.warn("[AsyncPool] failed to shutdown one per-context scheduler: {}", e.toString());
                }
            }
        }
        try {
            if (!POOL.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("[AsyncPool] Timeout {}s, forcing shutdownNow. Remaining: {}, Queue: {}",
                        SHUTDOWN_TIMEOUT_SECONDS, POOL.getActiveCount(), POOL.getQueue().size());
                POOL.shutdownNow();
            }
            SCHEDULER.awaitTermination(5, TimeUnit.SECONDS);
            //  关闭 Monitor 回调串行执行器
            MONITOR_CALLBACK_EXECUTOR.shutdown();
            try {
                if (!MONITOR_CALLBACK_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                    MONITOR_CALLBACK_EXECUTOR.shutdownNow();
                }
            } catch (InterruptedException ie) {
                MONITOR_CALLBACK_EXECUTOR.shutdownNow();
                Thread.currentThread().interrupt();
            }
            // per-context 池的终结等待放在 SCHEDULER 之后
            if (ctxSchedulers > 0) {
                for (ScheduledExecutorService s : CONTEXT_SCHEDULERS.values()) {
                    if (!s.isTerminated()) {
                        s.shutdownNow();
                    }
                }
            }
            //  修复 H18：关闭后清空映射，避免残留已终止调度器引用（getActiveContextSchedulerCount 误报 + 引用滞留）
            CONTEXT_SCHEDULERS.clear();
        } catch (InterruptedException e) {
            LOGGER.warn("[AsyncPool] Interrupted during shutdown, forcing shutdownNow");
            POOL.shutdownNow();
            SCHEDULER.shutdownNow();
            MONITOR_CALLBACK_EXECUTOR.shutdownNow();
            for (ScheduledExecutorService s : CONTEXT_SCHEDULERS.values()) {
                s.shutdownNow();
            }
            Thread.currentThread().interrupt();
        }
        LOGGER.info("[AsyncPool] Shutdown complete. Completed: {}, timeouts: {}", completedTaskCount.get(), timeoutCount.get());

        // CT2-17：释放完成后复位 —— 使同 JVM 内后续套件可重新初始化。
        //  原实现 shutdown 后永久不可恢复：后续 run() 静默丢任务、schedule* 抛
        //  RejectedExecutionException —— 同一 JVM 内出现两种相反后果。
        //  契约：先取消裁决器、清空资源字段，再 INIT.reset()，
        //  避免 LazyInit 复位后字段仍指向已关闭的池（形成"已复位但资源是死的"不一致状态）。
        cancelTimeoutReaper();
        PENDING_TIMEOUTS.clear();
        pendingTimeoutCount.set(0);
        //  CT2-17（回归防护）：**刻意不把 POOL / SCHEDULER / MONITOR_CALLBACK_EXECUTOR 置空**。
        //    置空虽能"释放引用"，但会在「shutdown 与 submit / 阈值诊断 / 裁决器日志并发」时引入
        //    NPE 这一**全新的失败形态**（原实现三字段永不为 null，失败形态是干净的
        //    RejectedExecutionException → 既有「拒绝即丢弃（计数 + ERROR）」路径）。
        //    可恢复性并不依赖置空：INIT.reset() 后下一次 ensureInitialized() 会重跑 doInit，
        //    由 doInit 用**新实例整体覆盖**这三个字段；在此之前它们指向已终止的池，
        //    此时提交同样走既有的拒绝分支，语义与改动前一致且可观测。
        INIT.reset();
    }

    /** CT2-17：取消超时裁决器（幂等）。 */
    private static void cancelTimeoutReaper() {
        ScheduledFuture<?> reaper = timeoutReaper;
        if (reaper != null) {
            reaper.cancel(false);
            timeoutReaper = null;
        }
    }

    // ─── 监控指标 ──────────────────────────────────────────────

    /** P2-2：取用主池前确保已初始化 —— 失败抛清晰的初始化异常，而不是 NPE。 */
    private static ThreadPoolExecutor pool() {
        ensureInitialized();
        return POOL;
    }

    public static int getActiveCount() { return pool().getActiveCount(); }
    public static int getPoolSize() { return pool().getPoolSize(); }
    public static int getQueueSize() { return pool().getQueue().size(); }
    // 修复 CORE-P2-N6：completedTaskCount 内部计数器与 POOL.getCompletedTaskCount() 统计同一批完成数，
    // 叠加会翻倍；统一以 ThreadPoolExecutor 自身计数作为唯一来源。
    public static long getCompletedTaskCount() { return pool().getCompletedTaskCount(); }
    public static long getRejectedCount() { return rejectedCount.get(); }
    public static long getTimeoutCount() { return timeoutCount.get(); }
    public static long getPendingTimeoutCount() { return pendingTimeoutCount.get(); }
    public static long getPendingScheduleCount() { return pendingScheduleCount.get(); }
    public static long getMonitorCallbackDroppedCount() { return monitorCallbackDroppedCount.get(); }

    public static double getQueueUsage() { return (double) pool().getQueue().size() / QUEUE_CAPACITY; }
    public static double getThreadUsage() {
        ThreadPoolExecutor p = pool();
        int poolSize = p.getPoolSize();
        return poolSize > 0 ? (double) p.getActiveCount() / poolSize : 0.0;
    }

    public static String getStatusSnapshot() {
        ThreadPoolExecutor p = pool();
        return String.format(
                "[AsyncPool] active=%d, pool=%d/%d, queue=%d/%d (%.0f%%), threads=%.0f%%, "
                        + "completed=%d, rejected=%d, timeouts=%d, pendingTimeouts=%d/%d, pendingSched=%d, ctxSched=%d, monitorDropped=%d",
                p.getActiveCount(), p.getPoolSize(), p.getMaximumPoolSize(),
                p.getQueue().size(), QUEUE_CAPACITY, getQueueUsage() * 100, getThreadUsage() * 100,
                p.getCompletedTaskCount(), rejectedCount.get(),
                timeoutCount.get(), pendingTimeoutCount.get(), MAX_PENDING_TIMEOUTS, pendingScheduleCount.get(),
                CONTEXT_SCHEDULERS.size(), monitorCallbackDroppedCount.get());
    }

    /**
     * 手动关闭（由管理代码调用）。
     *
     * <p>P2-2：以「资源是否真的存在」判定是否短路 —— 从未初始化或初始化失败时无资源可关（失败路径
     * 不留下半初始化状态），直接返回即可；反之即便 {@code initialized} 标志未及置位（如钩子注册失败），
     * 只要池已构造出来就必须关闭，避免线程泄漏。</p>
     */
    public static void shutdown() {
        if (POOL == null && SCHEDULER == null) {
            return;
        }
        shutdownGracefully();
    }

    /**
     *  在 Monitor 回调专用串行线程上执行任务（顺序、与主流程共享上下文）。
     * 用于 onResponse 回调，使用户在回调中修改的全局/共享状态对主线程可见。
     * task 为 null 静默跳过。
     */
    public static void runOnMonitorCallbackThread(Runnable task) {
        if (task == null)  {return;} 
        ensureInitialized();   // P2-2
        // C-5：捕获提交线程 MDC，monitor 串行线程执行时恢复，finally clear。
        final Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        // F-13：捕获软断言收集器，使用户在 onResponse 回调里记录的软断言失败可被上报。
        final SoftAssertions.Collector assertionCollector = SoftAssertions.captureCollector();
        //  修复 H17：监控回调串行队列满/已关闭时，绝不能回退到【调用方线程】同步执行
        // （调用方多为 Playwright 事件线程，同步执行用户回调会阻塞路由拦截 → 整轮测试卡死）。
        // 统一策略：准入控制 + 计数丢弃（可观测），但绝不阻塞提交方。
        if (MONITOR_CALLBACK_EXECUTOR.isShutdown()
                || ((ThreadPoolExecutor) MONITOR_CALLBACK_EXECUTOR).getQueue().size() >= MONITOR_CALLBACK_QUEUE_CAPACITY) {
            long dropped = monitorCallbackDroppedCount.incrementAndGet();
            LOGGER.error("[AsyncPool] Monitor callback DROPPED (queue full or shutdown). Dropped total: {}. "
                    + "Consider moving heavy work out of onResponse into AsyncPool.run().", dropped);
            return;
        }
        try {
            MONITOR_CALLBACK_EXECUTOR.execute(() -> {
                if (mdcContext != null) {
                    MDC.setContextMap(mdcContext);
                }
                SoftAssertions.Collector previousCollector = SoftAssertions.bindCollector(assertionCollector);
                try {
                    task.run();
                } catch (Throwable t) {
                    LOGGER.error("[AsyncPool] Monitor callback task threw exception: {}", t.getMessage(), t);
                } finally {
                    completedTaskCount.incrementAndGet();
                    SoftAssertions.unbindCollector(previousCollector);
                    MDC.clear();
                }
            });
        } catch (RejectedExecutionException e) {
            long dropped = monitorCallbackDroppedCount.incrementAndGet();
            LOGGER.error("[AsyncPool] Monitor callback REJECTED (DROPPED). Dropped total: {}. {}", dropped, e.getMessage());
        } catch (Exception e) {
            long dropped = monitorCallbackDroppedCount.incrementAndGet();
            LOGGER.error("[AsyncPool] Monitor callback submit failed (DROPPED). Dropped total: {}. {}", dropped, e.getMessage());
        }
    }

    // ─── 内部工具 ──────────────────────────────────────────────

    // 修复 CORE-P1-N7：配置改走统一配置门面 ConfigSource（合并 -D 系统属性 / 环境变量 /
    // serenity.properties 并透明解密），消除"直读 ASYNC_* 环境变量"这第三套割裂的配置体系，
    // 使 ASYNC_* 可被 serenity.properties 与 -D 覆盖、并支持 ENC() 加密。env 名映射与历史一致
    // （toEnvKey("ASYNC_CORE_THREADS") == "ASYNC_CORE_THREADS"），对现网零变更。
    /** P2-1：按注册表条目解析 int（键名 + 默认值均来自 SSoT）。 */
    private static int cfgInt(ConfigKeys key) {
        return getEnvInt(key.key(), Integer.parseInt(key.defaultValue().trim()));
    }

    /** P2-1：按注册表条目解析 long（键名 + 默认值均来自 SSoT）。 */
    private static long cfgLong(ConfigKeys key) {
        return getEnvLong(key.key(), Long.parseLong(key.defaultValue().trim()));
    }

    /** P2-1：按注册表条目解析 double（键名 + 默认值均来自 SSoT）。 */
    private static double cfgDouble(ConfigKeys key) {
        return getEnvDouble(key.key(), Double.parseDouble(key.defaultValue().trim()));
    }

    /**
     * 兼容解析（P2-1）：优先注册表键名；未命中时退回**历史大写下划线形式**。
     *
     * <p>注册表键名规范化（{@code async.core.threads}）后，<b>环境变量形式不变</b>
     * （{@code toEnvKey} ⇒ {@code ASYNC_CORE_THREADS}，与历史完全一致）；仅 {@code -D} 的旧大写形式需兜底，
     * 否则既有 {@code -DASYNC_CORE_THREADS=…} 会被静默忽略（历史文档只写了环境变量形式，但静默失效不可接受）。</p>
     */
    private static String resolveWithLegacy(String key) {
        String value = ConfigSource.resolve(key, null);
        if (value != null && !value.trim().isEmpty()) {
            return value;
        }
        String legacyKey = FrameworkFlags.toEnvKey(key);
        String legacy = ConfigSource.resolve(legacyKey, null);
        if (legacy != null && !legacy.trim().isEmpty()) {
            LOGGER.warn("[AsyncPool] 配置键 '{}' 为历史（未规范化）形式，仍生效：{}；请改用 '{}'",
                    legacyKey, legacy, key);
        }
        return legacy;
    }

    private static int getEnvInt(String key, int defaultValue) {
        String val = resolveWithLegacy(key);
        if (val == null || val.trim().isEmpty())  {return defaultValue;} 
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("[AsyncPool] Invalid int for {}: '{}', default {}", key, val, defaultValue);
            return defaultValue;
        }
    }

    private static long getEnvLong(String key, long defaultValue) {
        String val = resolveWithLegacy(key);
        if (val == null || val.trim().isEmpty())  {return defaultValue;} 
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("[AsyncPool] Invalid long for {}: '{}', default {}", key, val, defaultValue);
            return defaultValue;
        }
    }

    private static double getEnvDouble(String key, double defaultValue) {
        String val = resolveWithLegacy(key);
        if (val == null || val.trim().isEmpty())  {return defaultValue;} 
        try {
            double parsed = Double.parseDouble(val.trim());
            // 防御：拒绝 NaN / ±Infinity（如误配 "NaN"/"Infinity"），回退默认值（修复 L3）
            if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                LOGGER.warn("[AsyncPool] Invalid double for {}: '{}' is NaN/Infinity, default {}", key, val, defaultValue);
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException e) {
            LOGGER.warn("[AsyncPool] Invalid double for {}: '{}', default {}", key, val, defaultValue);
            return defaultValue;
        }
    }
}
