package com.hsbc.cmb.hk.dbb.automation.framework.route.handler;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.ApiMonitorOrchestrator;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.MonitorFailureCollector;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.AssertionFailureDetail;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteContextState;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteException;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandlerRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.persistence.DatabaseStoreMonitorCallback;
import com.hsbc.cmb.hk.dbb.automation.framework.route.persistence.FileStoreMonitorCallback;
import com.hsbc.cmb.hk.dbb.automation.framework.route.util.RouteUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.SerenityReporter;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.MonitorConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.jayway.jsonpath.JsonPath;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API 监控 Handler — <b>Playwright 事件线程零阻塞</b>（P0-3 / RT-F1）。
 *
 * <p>入口 {@link #handle} 仅做轻量登记，随后立即把「{@code waitForResponse} + body 读 +
 * 断言 + 记录」提交到 {@link #observationExecutor} 工作线程并返回，避免长阻塞把该 context
 * 下所有路由分发串行化（级联超时）。实际链路见 {@link #observeAndRecord}。
 *
 * <p>关键设计原则：
 * <ul>
 *   <li>事件线程只做页面关闭 / 上下文检查后即返回，<b>不在其上等待响应或读 body</b></li>
 *   <li>观测链路整体在受管工作线程执行（含 body 带重试读取）</li>
 *   <li>断言结果通过 {@link ApiCaptureContext} 通知测试生命周期</li>
 *   <li>失败详情（URL、类型、预期值、实际值）记录到上下文供测试结束报告</li>
 *   <li>Serenity 报告写入通过 {@link SerenityReporter} 统一处理</li>
 *   <li>route.resume() 包裹 try-catch，避免单请求失败导致整个路由崩溃</li>
 * </ul>
 */
public class MonitorHandler {

    static {
        RouteHandlerRegistry.register(RouteHandleType.MONITOR, MonitorHandler::handle);
    }

    /** 预算余量（毫秒）：留给「尝试总时长」之外的调度抖动。 */
    private static final long RETRY_BUDGET_MARGIN_MS = 5_000L;

    /**
     * 兜底读取真实响应的最大尝试次数（含首次）。
     *
     * <p>{@code waitForResponse} 失败多发生在「请求已被 resume、真实往返仍在途」的窗口，
     * 此时 {@code req.response()} 首次调用常为 null；只试一次即放弃会导致 MONITOR 记录静默丢失。
     */
    private static final int FALLBACK_MAX_ATTEMPTS = 5;

    /** 兜底读取真实响应的重试间隔（毫秒）。 */
    private static final long FALLBACK_RETRY_INTERVAL_MS = 200L;

    /**
     * body 读取重试调度器：用于 {@link #readResponseBodyWithRetry} 的异步退避，
     * 避免 Thread.sleep 阻塞 route 处理线程（线程契约）。
     *
     * <p><b>评审修复（2026-09-17）</b>：原为<b>单线程</b>（{@code newSingleThreadScheduledExecutor}）且被
     * <b>所有</b> BrowserContext 共享 —— 并行场景下各 context 的退避重试被串行到同一线程，成为跨 context
     * 的串行瓶颈。现改为线程数可配的调度池（{@code monitor.body.read.scheduler.threads}，默认 4，守护线程）。
     */
    private static final ScheduledThreadPoolExecutor bodyReadScheduler = newBodyReadScheduler();

    private static ScheduledThreadPoolExecutor newBodyReadScheduler() {
        int threads = Math.max(1, MonitorConfig.getInt(MonitorConfig.MONITOR_BODY_READ_SCHEDULER_THREADS, 4));
        AtomicInteger seq = new AtomicInteger(1);
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(threads, r -> {
            Thread t = new Thread(r, "monitor-body-retry-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        // 取消（上下文关闭/超时放弃）时立即从队列移除，避免残留任务占用调度窗口
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /**
     * 观测执行器（P0-3 / RT-F1）：承载「waitForResponse + body 读 + 断言 + 记录」这条<b>阻塞</b>链路，
     * 使 Playwright 事件线程在 {@link #handle} 中做完轻量登记后立即返回，不再被 20s+30s 的等待占住，
     * 从而消除「单 context 高 API 密度下路由分发被串行化 → 级联超时」。
     *
     * <p>有界队列 + 拒绝即放行：队列满时拒绝新任务并立即 {@code safeResume} 放行请求
     * （绝不反压事件线程，也不让请求永久挂起）。线程数与队列容量分别由
     * {@code monitor.observe.threads} / {@code monitor.observe.queue.capacity} 配置。
     */
    private static final ThreadPoolExecutor observationExecutor = newObservationExecutor();

    private static ThreadPoolExecutor newObservationExecutor() {
        int threads = Math.max(1, MonitorConfig.getInt(MonitorConfig.MONITOR_OBSERVE_THREADS, 8));
        int queue = Math.max(16, MonitorConfig.getInt(MonitorConfig.MONITOR_OBSERVE_QUEUE_CAPACITY, 4096));
        AtomicInteger seq = new AtomicInteger(1);
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queue),
                r -> {
                    Thread t = new Thread(r, "monitor-observe-" + seq.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 已读取的响应体 + 截断前原始字节数（C-10）。
     *
     * <p>两者必须<b>同路返回</b>：截断发生在读取路径上，若只回传截断后的内容，
     * 「数据被截断」这一事实就在框架内部丢失，断言失败/报告只能呈现残缺 body 而无法解释差异。
     *
     * @param bytes         实际内容（超过 {@code RouteUtil.MAX_BODY_BYTES} 时已截断）
     * @param originalBytes 截断前原始字节数（未截断时等于 {@code bytes.length}）
     */
    private record BodyRead(byte[] bytes, long originalBytes) {
    }

    //  ── 在途 body 读取重试的登记 / 取消 ─────────────────────────────
    //  登记表本身在 core（{@link RouteContextState} 的 per-context 在途任务表）：因为
    //  route.core.* 依 ArchUnit 规则<b>不得</b>依赖 route.handler.*，故「上下文关闭即取消在途任务」的能力
    //  必须由 core 提供，本类只负责登记 + 薄门面（cancelPendingBodyReadsFor）。

    //  注册 JVM 关闭钩子，确保进程退出时关闭 body 读取重试调度器，
    // 避免异常路径下任务堆积导致线程永久挂起。守护线程本不会阻止 JVM 退出，但显式 shutdown 更稳妥。
    static {
        com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator.register(
                com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator.ORDER_MONITOR_HANDLER,
                "monitor-handler", MonitorHandler::shutdownScheduler);
    }

    /** 关闭 body 读取重试调度器（幂等，等待进行中重试完成）。 */
    private static void shutdownScheduler() {
        try {
            //  先取消全部在途重试（让正阻塞在 future.get 的事件线程立即走兜底），再关池
            int cancelled = RouteContextState.cancelAllPendingTasks();
            if (cancelled > 0) {
                LOGGER.info("[MonitorHandler] cancelled {} pending body read(s) on shutdown", cancelled);
            }
            bodyReadScheduler.shutdownNow();
            if (!bodyReadScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("[MonitorHandler] bodyReadScheduler did not terminate in time");
            }
            // P0-3：一并关闭观测执行器（其任务同样持有 context/rule 引用，不应在收尾后残留）
            observationExecutor.shutdownNow();
            if (!observationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("[MonitorHandler] observationExecutor did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 套件收尾：取消全部在途观测/body 读任务并清空执行器队列（<b>不关闭</b>线程池本体）。
     *
     * <p>「需要收尾」：避免套件结束后，执行器队列里仍残留持有已关闭 context/rule 引用的任务；
     * 线程池终态关闭仍由 JVM {@code ShutdownCoordinator} 负责，故同 JVM 内可再次运行。
     */
    public static void drainForSuiteTeardown() {
        int cancelled = RouteContextState.cancelAllPendingTasks();
        int queued = observationExecutor.getQueue().size() + bodyReadScheduler.getQueue().size();
        observationExecutor.getQueue().clear();
        //  bodyReadScheduler 队列中的重试链对应的 future 已被上方取消（等待方立即走兜底），清队列安全
        bodyReadScheduler.getQueue().clear();
        if (cancelled > 0 || queued > 0) {
            LOGGER.info("[MonitorHandler] suite teardown: cancelled {} pending task(s), drained {} queued task(s)",
                    cancelled, queued);
        }
    }

    /** 登记一条在途重试链（委托 core 的 per-context 在途任务表，完成时自动注销）。 */
    private static void registerPendingBodyRead(BrowserContext context, CompletableFuture<byte[]> future) {
        RouteContextState.registerPendingTask(context, future);
    }

    /**
     * 取消指定上下文全部在途 body 读取重试（薄门面；实现在 {@link RouteContextState#cancelPendingTasksFor}）。
     *
     * <p>由 route 上下文生命周期收口调用：用例跑完后残链不再续投、等待方立即走兜底，
     * 从而"不因 timeout 卡住"。
     *
     * @param context 目标 BrowserContext；{@code null} 时 no-op
     * @return 实际被取消的重试链数量
     */
    public static int cancelPendingBodyReadsFor(BrowserContext context) {
        int cancelled = RouteContextState.cancelPendingTasksFor(context);
        if (cancelled > 0) {
            VerboseLogging.logDebugIfVerbose(LOGGER,
                    "[MonitorHandler] cancelled {} pending body read(s) for a closed context", cancelled);
        }
        return cancelled;
    }

    /** 取请求所属 BrowserContext（不可解析时返回 null，交由预算超时兜底）。 */
    private static BrowserContext contextOf(Request req) {
        try {
            if (req != null && req.frame() != null && req.frame().page() != null) {
                return req.frame().page().context();
            }
        } catch (Exception e) {
            // Page/Context 已销毁（收尾期预期竞争）：无归属，不登记也不取消
            VerboseLogging.logTraceIfVerbose(LOGGER,
                    "[MonitorHandler] contextOf(req) unavailable: {}", e.getMessage());
        }
        return null;
    }

    // ── 重试策略（可配 + 纯函数，便于单测与调参）──────────────────────

    /** 基础尝试次数（可配，默认 3）。 */
    static int baseAttempts() {
        return Math.max(1, MonitorConfig.getInt(MonitorConfig.MONITOR_BODY_READ_BASE_ATTEMPTS, 3));
    }

    /** 重试间隔毫秒（可配，默认 50）。 */
    static long retryIntervalMs() {
        return Math.max(1L, MonitorConfig.getLong(MonitorConfig.MONITOR_BODY_READ_RETRY_INTERVAL_MS));
    }

    /** 等待预算上限毫秒（可配，默认 30000；{@code <=0} 表示不设上限）。 */
    static long maxWaitMs() {
        return MonitorConfig.getLong(MonitorConfig.MONITOR_BODY_READ_MAX_WAIT_MS);
    }

    /** 纯函数：按 DELAY 推导总尝试次数（DELAY 越长，需要越多的重试窗口覆盖）。 */
    static int computeMaxAttempts(long effectiveDelayMs, int baseAttempts, long intervalMs) {
        long extra = effectiveDelayMs > 0 ? (effectiveDelayMs / intervalMs) + 1 : 0;
        long total = baseAttempts + extra;
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /**
     * 纯函数：等待预算 = {@code min(尝试总时长 + 余量, 上限)}。
     *
     * <p>必须有上限：预算即「route 事件线程度过的最大时长」。无上限时，长 DELAY 会把预算放大到分钟级
     * （例：DELAY 60s → 1200 次尝试 → 65s），一旦重试链中断/调度器异常，{@code future.get} 会把事件线程
     * 长期占住，表现为"程序卡死"。上限可用 {@code monitor.body.read.max.wait.ms} 调整。
     */
    static long computeBudgetMs(int maxAttempts, long intervalMs, long maxWaitMs) {
        long raw = (long) maxAttempts * intervalMs + RETRY_BUDGET_MARGIN_MS;
        if (maxWaitMs <= 0) {
            return raw;
        }
        return Math.min(raw, maxWaitMs);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorHandler.class);

    /** 等待真实响应 / 兜底请求的默认超时（毫秒），可用环境变量 ROUTE_FETCH_TIMEOUT_MS 覆盖 */
    private static final double ROUTE_FETCH_TIMEOUT_MS = RouteUtil.getEnvDouble("ROUTE_FETCH_TIMEOUT_MS", 30000);

    /**
     * 响应捕获预算上限（毫秒）—— 约束「路由级轮询 {@code Request#existingResponse()} 等待响应抵达」的最大时长。
     *
     * <p><b>语义（playwright-java @since 1.59 起可用 {@code existingResponse()}）</b>：本上限不再约束 page.waitForResponse，
     * 而是约束对本地持有的 Response 的轮询等待。{@code existingResponse()} 对「响应在途」与「响应已在监听器注册前返回
     * （竞态）」两种情形<b>一致处理</b>：已到达立即返回，未到达则短休眠轮询至本上限——彻底消除 waitForResponse 的
     * "竞态白等满超时"病理（历史 MONITOR Flake 根因：原 20s 白等使观测延迟远超 8s 断言窗口）。
     *
     * <p><b>账期约束</b>：观测总耗时 ≈ 本上限 + body 读取(~1s)，须 &lt; 8s 断言窗口；故上限必须 &lt; 7s。取 5s：
     * ① 在途/慢 API（&lt;5s）在真实抵达时即被捕获；② 稍慢（5s~6s）仍能在上限内拿到；③ 仅响应耗时 &gt;7s 才降级为
     * 不可用快照——而那本就超出 8s 断言窗口、无法被测试断言，不属于 Flake。
     *
     * <p>可用环境变量 {@code ROUTE_FETCH_TIMEOUT_MS} 进一步<b>调小</b>（min 取较小值），但本常数即其上界，不允许调大
     * （调大则竞态白等拖垮观测、Flake 复发）。
     */
    private static final long RESPONSE_AWAIT_CAP_MS = 5000L;

    /** 轮询 {@code existingResponse()} 的间隔（毫秒）：细粒度轮询，使已到达响应被近乎即时捕获，且不空转。 */
    private static final long EXISTING_RESPONSE_POLL_MS = 25L;

    /**
     * 路由级响应捕获：resume 后轮询 {@link com.microsoft.playwright.Request#existingResponse()}
     * （请求本地持有的 Response 字段，零协议往返）。
     *
     * <p>取代 page.waitForResponse：后者是 page 级事件监听器 + predicate，仅能在「响应在途」时捕获，
     * 响应已在监听器注册前返回（竞态）时只能白等满超时（历史 Flake 根因）；且 {@code req.response()} 的协议往返
     * 会在 server 端响应对象出表时抛 "Object doesn't exist"，污染连接。{@code existingResponse()} 对两种情形一致：
     * 已到达立即返回，未到达则短休眠轮询至 capMs，绝不做协议往返。
     *
     * <p>死句柄零触碰：轮询期 context/页面已关立即返回 null，交由调用方落降级快照（只消费 req 本地字段）。
     *
     * @return 本地持有的 Response；超时或 context/页面已关时返回 null
     */
    private static Response awaitExistingResponse(Route route, Request req,
                                                 BrowserContext observationContext, long capMs) {
        //  路由级轮询：用 CompletableFuture.delayedExecutor 做有界退避（与 readResponseBodyWithRetry 同源），
        //    严禁 Thread.sleep（框架代码 ArchUnit 禁止）——join 仅阻塞当前观测线程、不占用事件循环、不调 Thread.sleep。
        final long deadlineNs = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(capMs);
        final java.util.concurrent.CompletableFuture<Response> fut = new java.util.concurrent.CompletableFuture<>();
        scheduleExistingResponsePoll(fut, route, req, observationContext, deadlineNs);
        return fut.join();
    }

    /** 递归轮询 {@code req.existingResponse()}（本地字段、零协议往返）；到达/超时/死句柄即完成 future。 */
    private static void scheduleExistingResponsePoll(java.util.concurrent.CompletableFuture<Response> fut,
                                                     Route route, Request req,
                                                     BrowserContext observationContext, long deadlineNs) {
        if (fut.isDone()) return;
        //  死句柄零触碰：context/页面已关 → 立即停止轮询，交由调用方落降级快照（不触碰 server 对象）。
        if ((observationContext != null && RouteContextState.isContextClosed(observationContext))
                || RouteUtil.isPageClosed(route)) {
            fut.complete(null);
            return;
        }
        Response r = req.existingResponse();   //  本地持有，零协议往返，绝不抛 "Object doesn't exist"
        if (r != null) {
            fut.complete(r);
            return;
        }
        if (System.nanoTime() >= deadlineNs) {
            fut.complete(null);
            return;
        }
        java.util.concurrent.CompletableFuture.delayedExecutor((int) EXISTING_RESPONSE_POLL_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> scheduleExistingResponsePoll(fut, route, req, observationContext, deadlineNs));
    }

    //  P2-15：JsonPath 编译缓存已收敛至 RouteUtil.compileJsonPathCached（单一共享）

    /**
     * 处理单个 route 的监控逻辑（带断言）—— <b>Playwright 事件线程入口</b>。
     *
     * <p><b>P0-3（RT-F1）：事件线程不再阻塞。</b>本方法只做轻量登记（页面关闭 / 上下文检查），
     * 随后立即把「{@code waitForResponse} + body 读 + 断言 + 记录」提交到 {@link #observationExecutor}
     * 并<b>立即返回</b>；避免 20s+30s 的长阻塞把该 context 下所有路由分发串行化（级联超时）。
     * 实际观测与记录见 {@link #observeAndRecord}。
     */
    public static void handle(Route route, RouteRule rule, long delayMs) {
        // ═══ 页面关闭检查：页面已关闭时直接放行，避免对已销毁页面操作报错 ═══
        if (RouteUtil.isPageClosed(route)) {
            VerboseLogging.logDebugIfVerbose(LOGGER,
                    "[MonitorHandler] Page/Context already closed, resume & skip for pattern='{}'",
                    rule.getUrlPattern());
            RouteUtil.safeResume(route);
            return;
        }
        // 快速失败面：无 ApiCaptureContext 时直接放行（绝不让请求永久挂起）
        if (RouteUtil.captureContext(route) == null) {
            LOGGER.warn("[MonitorHandler] ApiCaptureContext is null, resuming & skipping assertion for pattern='{}'",
                    rule.getUrlPattern());
            RouteUtil.safeResume(route);
            return;
        }
        //  「context 关闭 → 所有活动立即停止」：已关闭的 context 直接放行，不登记也不提交观测。
        BrowserContext observationContext = contextOf(route.request());
        if (RouteContextState.isContextClosed(observationContext)) {
            VerboseLogging.logDebugIfVerbose(LOGGER,
                    "[MonitorHandler] Context already closed, resume & skip: pattern='{}'", rule.getUrlPattern());
            RouteUtil.safeResume(route);
            return;
        }
        //  G1：观测任务登记进 per-context 在途表 —— context 关闭时 cancelPendingTasksFor(context)
        //    会取消它，使"尚未开始"的观测立即放弃，不再空跑到 waitForResponse/body 读超时。
        final CompletableFuture<Void> observationTicket = new CompletableFuture<>();
        registerPendingObservation(observationContext, observationTicket);
        try {
            observationExecutor.execute(
                    () -> observeAndRecord(route, rule, delayMs, observationContext, observationTicket));
        } catch (RejectedExecutionException rex) {
            observationTicket.cancel(false);
            // 队列饱和（极端负载）：拒绝即放行，绝不反压事件线程、也不让请求永久挂起
            LOGGER.error("[MonitorHandler] Observation queue saturated, resuming & skipping for pattern='{}': {}",
                    rule.getUrlPattern(), rex.getMessage());
            RouteUtil.safeResume(route);
        }
    }

    /**
     * 观测任务包装（G1）：<b>仅在未被取消时</b>执行实际观测，结束时完成票据（从 per-context 在途表自动注销）。
     *
     * <p>上下文关闭 → {@code RouteContextState.cancelPendingTasksFor(context)} 取消票据 →
     * 排队/未开始的观测立即放弃，避免其继续持有 context/rule 并空跑至超时。
     */
    private static void observeAndRecord(Route route, RouteRule rule, long delayMs,
                                         BrowserContext context, CompletableFuture<Void> ticket) {
        try {
            if (ticket.isCancelled() || RouteContextState.isContextClosed(context)) {
                VerboseLogging.logDebugIfVerbose(LOGGER,
                        "[MonitorHandler] Observation cancelled before start (context closed): pattern='{}'",
                        rule.getUrlPattern());
                return;
            }
            observeAndRecordInternal(route, rule, delayMs, context);
        } catch (Throwable observationError) {
            //  观测链任何未预期异常都不得让请求挂起（否则浏览器转圈、测试 block）：
            //  强制放行兜底（resume 幂等，已放行时被 Playwright 忽略）。异常在此收口，绝不外抛。
            LOGGER.error("[MonitorHandler] Observation aborted unexpectedly, forcing resume: pattern='{}'",
                    rule.getUrlPattern(), observationError);
            RouteUtil.safeResume(route);
        } finally {
            ticket.complete(null);
        }
    }

    /** 登记一条在途观测任务（与 body 读取共用同一 per-context 在途表，便于 context 关闭时统一取消）。 */
    private static void registerPendingObservation(BrowserContext context, CompletableFuture<?> future) {
        RouteContextState.registerPendingTask(context, future);
    }

    /**
     * 实际观测与记录（在 {@link #observationExecutor} 工作线程执行）—— P0-3 / RT-F1。
     *
     * <p>承载原「事件线程内同步」的全部阻塞链路：{@code page.waitForResponse}（≤20s）、
     * body 带重试读取（{@code future.get} ≤30s）、同步断言（Fail-Fast 置标志）与记录。
     * 因运行在工作线程，这些阻塞不再影响 Playwright 事件线程的路由分发。
     *
     * <p><b> 重要架构约束 — 同步断言 + Fail-Fast</b>：
     * <ul>
     *   <li>断言（状态码 / JSONPath）<b>同步执行</b>（现位于本工作线程），不提交到 AsyncPool</li>
     *   <li>断言失败 → 调用 {@code context.signalFailFast()} 置失败标志（<b>不中断</b>主测试线程），
     *       由 PlaywrightListener 在步骤结束时经 {@code checkAndFailOnApiAssertions()} 抛 AssertionError，仅当前 Step 失败</li>
     *   <li>响应体存储、CapturedApiCall 快照、Serenity 报告记录在本工作线程内完成</li>
     * </ul>
     */
    private static void observeAndRecordInternal(Route route, RouteRule rule, long delayMs,
                                                 BrowserContext observationContext) {
        //  「context 关闭 → 所有活动立即停止」：进入即检查，已关闭则直接放行退出（不读 body、不断言、不记录）。
        if (RouteContextState.isContextClosed(observationContext)) {
            RouteUtil.safeResume(route);
            return;
        }
        // ═══ 页面关闭检查：页面已关闭时直接放行，避免对已销毁页面操作报错 ═══
        if (RouteUtil.isPageClosed(route)) {
            VerboseLogging.logDebugIfVerbose(LOGGER,
                    "[MonitorHandler] Page/Context already closed, resume & skip for pattern='{}'",
                    rule.getUrlPattern());
            RouteUtil.safeResume(route);
            return;
        }

        // 获取 API 监控上下文并增加活动请求计数
        ApiCaptureContext context = RouteUtil.captureContext(route);
        if (context == null) {
            LOGGER.warn("[MonitorHandler] ApiCaptureContext is null, resuming & skipping assertion for pattern='{}'",
                    rule.getUrlPattern());
            //  必须放行请求，否则请求会永久挂起
            RouteUtil.safeResume(route);
            return;
        }

        VerboseLogging.logDebugIfVerbose(LOGGER,
                "[MonitorHandler] ── handle() START: pattern='{}', expectStatus={}, jsonPathAssertions={} ──",
                rule.getUrlPattern(), rule.getExpectedStatus(),
                rule.getJsonPathAssertions() != null ? rule.getJsonPathAssertions().size() : 0);

        //  DELAY 与 MONITOR 叠加时，本 Handler 仍由 {@code RouteEngine#scheduleDelay} 在事件线程
        //    同步调用（见 RouteEngine.scheduleDelay 的 MONITOR 分支），并传入 delayMs；
        //    延迟由内部 resume 调度到延迟线程实现（B 方案）。
        //
        //  响应捕获改用「路由级轮询 {@code Request#existingResponse()}（playwright-java @since 1.59）」，
        //    取代 page.waitForResponse —— 源码级依据（playwright-java-1.62.0 Request.java / network.ts）：
        //    • {@code req.response()} 向 server 发起协议往返（{@code _channel.response}），当 server 端 Response
        //      对象已出对象表 → 抛 "Object doesn't exist"，并污染连接使后续无关命令也失败（跨场景 Flake 根因）。
        //    • {@code existingResponse()} 直接返回请求本地持有的 Response（响应事件到达时由 Playwright 赋值、
        //      不再做协议往返），绝不会触发该错；且对「响应在途」与「响应已在监听器注册前返回（竞态）」两种情形
        //      一致处理：已到达立即返回，未到达则短休眠轮询至上限，彻底消除 waitForResponse 的"竞态白等满超时"病理。
        Request req = route.request();
        Response res = null;
        //  放行幂等标记：本请求只允许 resume 一次。二次 resume 会让 Playwright 侧请求/响应对象失效，
        //  调用方（测试主线程读取响应）随后会抛 "Object doesn't exist: response@..."。
        final java.util.concurrent.atomic.AtomicBoolean resumed = new java.util.concurrent.atomic.AtomicBoolean(false);
        if (!RouteUtil.isPageClosed(route)) {
            resumed.set(true);
            //  B 方案：resume 经 RouteEngine.scheduleDeferred 调度到延迟线程（delayMs<=0 立即执行），
            //    避免阻塞事件线程、规避调度线程竞态。放行后请求继续完成，响应事件到达即填充 req 本地 _response。
            RouteEngine.scheduleDeferred(route, delayMs, () -> RouteUtil.safeResume(route));
        }
        //  捕获预算：min(上限, ROUTE_FETCH_TIMEOUT_MS)；<=0 时回落到上限（绝不 0：deadline=now 首轮即超时）。
        long capMs = Math.min(RESPONSE_AWAIT_CAP_MS, (long) ROUTE_FETCH_TIMEOUT_MS);
        if (capMs <= 0) capMs = RESPONSE_AWAIT_CAP_MS;
        res = awaitExistingResponse(route, req, observationContext, capMs);
        if (res == null) {
            //  轮询期 context/页面已关（死句柄）或超时未抵达 → 放行保证请求不挂起，并落降级快照（不静默丢弃）。
            //  死句柄零触碰：recordUnavailable 仅消费 req 的【本地】字段（method/headers/url/postData），
            //    绝不调用 req.response()/res.body() 等会触碰 server 对象的访问器。
            if (resumed.compareAndSet(false, true)) {
                RouteUtil.safeResume(route);
            }
            recordUnavailable(route, rule, req);
            return;
        }

        //  生命周期契约容错：route 回调中 res.body() 在并发/连续导航场景下可能偶发返回
        //    null（响应体尚未缓冲就绪），直接丢弃会导致该 call 丢失（getAllResponsesForUrl 少一条）。
        //    改为带短重试的读取（非阻塞：用 CompletableFuture.delayedExecutor 调度退避，
        //    绝不 Thread.sleep 阻塞线程），应对 body 未就绪的瞬时竞态，避免捕获计数漂移。
        BodyRead read = readResponseBodyWithRetry(res, rule, req, observationContext);
        if (read == null) {
            //  「不静默丢弃」：body 读不到也必须留下 MONITOR 记录（否则该调用对用例完全不可见）
            LOGGER.warn("[MonitorHandler] Response body unavailable after retry; recording degraded snapshot: "
                    + "pattern='{}', url='{}'", rule.getUrlPattern(), RouteUtil.sanitizeUrl(req.url()));
            recordUnavailable(route, rule, req);
            return;
        }

        String body = toSafeBodyString(read.bytes());
        String url = req.url();
        int status = res.status();
        String urlPattern = rule.getUrlPattern();

        LOGGER.info("[MonitorHandler] Captured: url={}, status={}, bodyLength={}, pattern='{}'",
                RouteUtil.sanitizeUrl(url), status, body.length(), urlPattern);

        //  复用统一的「断言 + 记录」逻辑（ModifyHandler 叠加监控时也调用此方法）
        assertAndRecord(route, rule, context, url, status, body,
                req.method(), req.postData(),                 snapshotHeadersSafely(req.headers()),
                snapshotHeadersSafely(res.headers()), read.originalBytes());
    }


    /**
     * 将响应体字节转为「文本安全」字符串，仅用于日志与捕获存储。
     *
     * <p>与 {@code ModifyHandler#toSafeBodyString} 对齐：UTF-8 解码无错且不含 NUL / 不可打印控制字符
     * （tab/换行/回车 除外）视为文本原样返回；非法 UTF-8 或含上述字符判定为二进制，返回占位串，
     * 避免把图片 / 压缩包 / protobuf 等二进制体刷成乱码写入日志与监控存储。
     * 空体返回 {@code ""}，与原有 {@code new String(bytes, UTF_8)} 行为一致，不引入新行为差异。
     */
    private static String toSafeBodyString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return bytes == null ? null : "";
        }
        try {
            CharBuffer cb = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            for (int i = 0; i < cb.length(); i++) {
                char c = cb.charAt(i);
                if (c == '\uFFFD' || c == '\u0000' || (c < 0x20 && c != '\t' && c != '\n' && c != '\r')) {
                    return String.format("(binary body, %d bytes, not displayed)", bytes.length);
                }
            }
            return cb.toString();
        } catch (CharacterCodingException e) {
            return String.format("(binary body, %d bytes, not displayed)", bytes.length);
        }
    }

    /**
     *  兜底读取真实响应：直接取 {@code request.response()}（master 实现采用的方式）。
     *
     * <p>适用场景：{@code page.waitForResponse} 超时/抛异常（如响应在监听器注册前已返回、
     * 或页面在等待期间被关闭）时，请求实际已完成真实网络往返，此时
     * {@code request.response()} 可能仍可拿到 Response。
     *
     * <p>不适用场景（返回 null 属正常）：响应尚未到达、Response 对象已被 GC 回收
     * （Playwright 对象表移除 → "Object doesn't exist"）。调用方收到 null 应静默放弃采集。
     *
     * @param req 当前请求
     * @return 可读的 Response；不可用时返回 null
     */
    private static Response fallbackResponse(Request req) {
        try {
            Response r = req.response();
            if (r == null) return null;
            // 预热一次 status()，尽早暴露 "Object doesn't exist" 等已失效信号
            r.status();
            VerboseLogging.logDebugIfVerbose(LOGGER,
                    "[MonitorHandler] fallbackResponse OK: url='{}'", RouteUtil.sanitizeUrl(req.url()));
            return r;
        } catch (Exception e) {
            //  「零触碰死句柄」：此处【不得】再调用 req.url() —— 句柄已失效时它同样会抛，
            //    等于对死句柄二次触碰（并进一步污染 Playwright 连接）。仅记录错误本身。
            VerboseLogging.logDebugIfVerbose(LOGGER,
                    "[MonitorHandler] fallbackResponse unavailable: error='{}'", e.getMessage());
            return null;
        }
    }

    /**
     * 兜底读取真实响应（<b>界内重试</b>）。
     *
     * <p>{@code waitForResponse} 超时/异常通常发生在「请求已被 resume 放行、真实网络往返仍在途」的窗口，
     * 此时 {@code req.response()} 首次调用常返回 null。原实现只尝试<b>一次</b>即放弃，导致 MONITOR 记录
     * 静默丢失（用例侧表现为「无 MONITOR 记录 / 响应体为空」且无失败信号）。现按固定间隔重试至多
     * {@link #FALLBACK_MAX_ATTEMPTS} 次，最大化捕获成功率。
     *
     * <p>本方法在<b>观测工作线程</b>执行（非 Playwright 事件线程）；退避用
     * {@code CompletableFuture.delayedExecutor}（与 {@link #awaitExistingResponse} 同源，框架 ArchUnit 禁止
     * {@code Thread.sleep}），总退避上限约 {@code (FALLBACK_MAX_ATTEMPTS-1) * FALLBACK_RETRY_INTERVAL_MS} 毫秒。
     *
     * @param req 当前请求
     * @return 可读 Response；重试后仍不可用则返回 null
     */
    static Response fallbackResponseWithRetry(Request req, BrowserContext observationContext) {
        //  「死句柄零触碰」：每轮尝试前确认观测 context 仍存活，已关闭则立即放弃 ——
        //    不再调用 req.response()（对已释放句柄的操作会报 "Cannot find parent object request@…"，
        //    并污染 Playwright 连接，使随后的无关命令（如 page.evaluate）也失败）。
        if (RouteContextState.isContextClosed(observationContext)) {
            return null;
        }
        Response first = fallbackResponse(req);
        if (first != null) {
            VerboseLogging.logDebugIfVerbose(LOGGER,
                    "[MonitorHandler] fallbackResponseWithRetry OK: url='{}'", RouteUtil.sanitizeUrl(req.url()));
            return first;
        }
        final java.util.concurrent.CompletableFuture<Response> fut = new java.util.concurrent.CompletableFuture<>();
        fallbackRetryPoll(fut, req, observationContext, 2);
        return fut.join();
    }

    /** 递归重试 {@code fallbackResponse(req)}（本地 req.response() 往返），界内退避、死句柄即停。 */
    private static void fallbackRetryPoll(java.util.concurrent.CompletableFuture<Response> fut,
                                          Request req, BrowserContext observationContext, int attempt) {
        if (fut.isDone()) return;
        if (RouteContextState.isContextClosed(observationContext)) {
            fut.complete(null);
            return;
        }
        if (attempt > FALLBACK_MAX_ATTEMPTS) {
            fut.complete(null);
            return;
        }
        Response r = fallbackResponse(req);
        if (r != null) {
            fut.complete(r);
            return;
        }
        java.util.concurrent.CompletableFuture.delayedExecutor((int) FALLBACK_RETRY_INTERVAL_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> fallbackRetryPoll(fut, req, observationContext, attempt + 1));
    }

    /**
     *  记录一条「观测不可用」的降级快照（status=0、无响应体）。
     *
     * <p><b>为什么不静默 return</b>：MONITOR 是「对真实响应的观察结果」的唯一写入点，一旦观测链在
     * response/body 阶段放弃就整条记录消失，用例只能看到「无记录」这一现象，既无法区分「规则没生效」
     * 与「观测未完成」，也违背框架自身的 ROUTE-P0-1 原则（宁可错报不可漏测）。故此处落一条显式
     * 降级记录：status=0 且 body=null，让调用方/报告能明确判定「观测不可用」而非「无数据」。
     *
     * @param route Playwright 路由对象（用于解析采集上下文）
     * @param rule  命中的规则
     * @param req   当前请求
     */
    private static void recordUnavailable(Route route, RouteRule rule, Request req) {
        //  落库走「无回退」查询：owner 已销毁 ⇒ 丢弃该记录，绝不写进当前场景的采集存储（跨场景污染）
        ApiCaptureContext captureContext = RouteUtil.captureContextForRecord(route);
        if (captureContext == null) {
            return;
        }
        try {
            CapturedApiCall degraded = new CapturedApiCall(
                    rule.getUrlPattern(), req.method(), snapshotHeadersSafely(req.headers()), 0, Map.of(), null,
                    System.currentTimeMillis(), req.url(), req.postData(), RouteHandleType.MONITOR);
            captureContext.storeApiCall(degraded);
        } catch (Exception e) {
            VerboseLogging.logDebugIfVerbose(LOGGER,
                    "[MonitorHandler] recordUnavailable degraded snapshot failed: {}", e.getMessage());
        }
    }

    /**
     * 带短重试地读取响应体，应对 route 回调中 res.body() 偶发返回 null（响应体尚未缓冲就绪）的情况。
     *
     * <p>生命周期契约容错：连续 page.navigate 到 API 端点或高并发下，Playwright 的
     * Response 对象偶发在 body 尚未就绪时被访问，res.body() 返回 null。简单地丢弃会导致
     * CapturedApiCall 漏存（触发调用方 getAllResponsesForUrl 计数漂移）。
     *
     * <p>线程契约：退避通过 {@link CompletableFuture#delayedExecutor} 调度到独立
     * {@link #bodyReadScheduler} 线程，当前 route 处理线程在退避期间不被 {@code Thread.sleep}
     * 阻塞（绝不占用线程等待），重试到期后再读取。最后一次成功或耗尽后通过 future 返回，
     * 调用方以 {@code join()} 获取结果（join 不阻塞线程池事件循环，仅当前任务等待自身结果）。
     *
     * @param res  响应对象
     * @param rule 路由规则（仅用于日志）
     * @param req  请求对象（仅用于日志）
     * @return 读取结果（含截断前原始长度）；全部重试后仍不可用则返回 {@code null}
     */
    private static BodyRead readResponseBodyWithRetry(Response res, RouteRule rule, Request req,
                                                      BrowserContext observationContext) {
        //  「context 关闭 → 所有活动立即停止」：context 已关闭则不再读取（避免 ~30s 预算空转）。
        if (RouteContextState.isContextClosed(observationContext)) {
            return null;
        }
        final int baseAttempts = baseAttempts();
        final long retryIntervalMs = retryIntervalMs();
        //  需求2：当规则含 DELAY 时，DELAY 延后了响应返回，MONITOR 读取 body 的退避/等待
        //   上限需相应 +delayMs（取 delayMs 与 delayMaxMs 的较大值，覆盖随机延迟范围），
        //   避免延迟响应尚未就绪就放弃读取导致 MONITOR 拿不到 body。
        long effectiveDelayMs = rule != null ? Math.max(rule.getDelayMs(), rule.getDelayMaxMs()) : 0;
        int maxAttempts = computeMaxAttempts(effectiveDelayMs, baseAttempts, retryIntervalMs);
        if (effectiveDelayMs > 0) {
            LOGGER.info("[MonitorHandler] Rule has DELAY ({}ms), extended body-read retries to {} attempts",
                    effectiveDelayMs, maxAttempts);
        }
        //  评审修复：预算 = min(尝试总时长 + 余量, monitor.body.read.max.wait.ms)。上限的意义是
        //  「事件线程等待有界」——长 DELAY 会把尝试数放大到数百次（预算分钟级），一旦重试链中断或
        //  调度器异常，无界等待就会拖死路由分发。任何放弃都走调用方兜底（返回 null）。
        long budgetMs = computeBudgetMs(maxAttempts, retryIntervalMs, maxWaitMs());
        long deadlineMs = System.currentTimeMillis() + budgetMs;
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        BrowserContext context = contextOf(req);
        registerPendingBodyRead(context, future);
        retryBodyOnce(res, rule, req, observationContext, 1, maxAttempts, retryIntervalMs, deadlineMs, future);
        try {
            //  超时上限：绝不用无界 join()。
            //   重试链依赖 bodyReadScheduler 调度；若该调度器已被关闭（JVM 收尾/异常路径）或
            //   重试链因上下文关闭被取消，future 永不完成 → join() 会永久阻塞 Playwright 事件线程，
            //   进而拖死整个路由分发（"卡主程序"）。故用有界 budget 主动放弃，改由调用方走兜底路径。
            byte[] raw = future.get(budgetMs, TimeUnit.MILLISECONDS);
            if (raw == null) {
                return null;
            }
            //  C-10（截断元数据）：截断在此处执行而非重试链内部 —— 这样「原始长度」与「截断结果」
            //  在同一栈帧内同时可得，截断事实才能随快照上报。原先在重试链内截断后原始长度即丢失，
            //  报告/断言只能看到残缺 body，无法解释与期望的差异。
            return new BodyRead(RouteUtil.truncateBody(raw), raw.length);
        } catch (Exception e) {
            future.cancel(true);
            VerboseLogging.logDebugIfVerbose(LOGGER,
                    "[MonitorHandler] Body read gave up (timeout/failure/cancelled) after up to {} attempts, "
                            + "budget={}ms: {}", maxAttempts, budgetMs, e.getMessage());
            return null;
        }
    }

    /** 递归异步重试读取 body：每次失败/空 body 后按固定间隔提交下一次读取（非阻塞），不占用当前线程。 */
    private static void retryBodyOnce(Response res, RouteRule rule, Request req,
                                       BrowserContext observationContext,
                                       int attempt, int maxAttempts, long intervalMs, long deadlineMs,
                                       CompletableFuture<byte[]> result) {
        //  「context 关闭 → 所有活动立即停止」：每轮重试前检查，关闭即停止续投（等待方立即走兜底）。
        if (RouteContextState.isContextClosed(observationContext)) {
            result.complete(null);
            return;
        }
        try {
            byte[] body = res.body();
            if (body != null) {
                //  原始字节直接完成；截断统一由 readResponseBodyWithRetry 在拿到结果后执行
                //  （C-10：需同时知道原始长度才能记录「被截断」这一事实）
                result.complete(body);
                return;
            }
        } catch (Exception e) {
            // Response 已失效：重试无意义，直接完成 null 由调用方决定降级
            VerboseLogging.logTraceIfVerbose(LOGGER,
                    "[MonitorHandler] res.body() threw on attempt {}/{} for {}: {}",
                    attempt, maxAttempts, req.url(), e.getMessage());
            result.complete(null);
            return;
        }
        if (attempt < maxAttempts && System.currentTimeMillis() < deadlineMs) {
            if (bodyReadScheduler.isShutdown()) {
                //  修复 Medium：调度器已关闭（JVM 收尾/异常路径）时不再重试，
                // 立即走兜底，避免向已停执行器提交触发 RejectedExecution + 浪费 join 超时窗口。
                result.complete(null);
                return;
            }
            CompletableFuture.runAsync(
                    () -> retryBodyOnce(res, rule, req, observationContext, attempt + 1, maxAttempts,
                            intervalMs, deadlineMs, result),
                    CompletableFuture.delayedExecutor(intervalMs, TimeUnit.MILLISECONDS, bodyReadScheduler));
        } else {
            // 超过尝试次数或已过截止时间（预算用尽）：停止续投，避免"用例已结束仍在空转"的残链
            result.complete(null);
        }
    }

    /**
     *  统一的「断言 + 记录」逻辑：供 {@link #handle(Route, RouteRule)}（纯监控）
     * 与 {@link ModifyHandler}（修改请求后叠加监控）共同复用。
     *
     * <p>行为：
     * <ul>
     *   <li>在 Playwright 事件线程上<b>同步断言</b>（状态码 / JSONPath），失败 → Fail-Fast 中断测试</li>
     *   <li>响应体存储、CapturedApiCall 快照、Serenity 报告记录走 {@link AsyncPool} 异步</li>
     * </ul>
     *
     * <p> 监控是<b>不可被覆盖的基线</b>：无论是否叠加 Modify/Delay，真实响应拿回后都会在此断言健康，
     * 断言失败即报错（对应「监控到 API 失败就报错」的诉求）。
     *
     * @param route        Playwright 路由对象（用于异常日志）
     * @param rule         路由规则（含断言配置）
     * @param context      当前 ApiCaptureContext（可为 null，null 时直接跳过）
     * @param url          实际请求 URL
     * @param status       HTTP 状态码（真实响应）
     * @param body         响应体（真实响应）
     * @param method       请求方法
     * @param reqBody      请求体
     * @param reqHeaders   请求头快照（线程安全副本）
     * @param resHeaders   响应头快照（线程安全副本）
     */
    public static void assertAndRecord(Route route, RouteRule rule, ApiCaptureContext context,
                                       String url, int status, String body,
                                       String method, String reqBody,
                                       Map<String, String> reqHeaders, Map<String, String> resHeaders) {
        // 未携带截断信息的调用方（如 ModifyHandler 叠加监控）：按「未截断」处理
        assertAndRecord(route, rule, context, url, status, body, method, reqBody,
                reqHeaders, resHeaders, -1L);
    }

    /**
     * 同上，但额外携带响应体<b>截断前的原始字节数</b>（C-10 截断元数据）。
     *
     * <p>当 {@code originalBodyBytes} 大于落库 body 长度时，快照会标记
     * {@link CapturedApiCall#bodyTruncated()} 并保留 {@link CapturedApiCall#originalBodyBytes()}，
     * 使断言失败信息与报告能显示「数据被截断」这一事实，避免与真实原因脱节。
     *
     * @param originalBodyBytes 截断前原始字节数；{@code -1} 表示调用方未提供（视为未截断）
     */
    public static void assertAndRecord(Route route, RouteRule rule, ApiCaptureContext context,
                                       String url, int status, String body,
                                       String method, String reqBody,
                                       Map<String, String> reqHeaders, Map<String, String> resHeaders,
                                       long originalBodyBytes) {
        String urlPattern = rule.getUrlPattern();

        VerboseLogging.logDebugIfVerbose(LOGGER,
                "[MonitorHandler] Response headers: {}", resHeaders);
        VerboseLogging.logTraceIfVerbose(LOGGER,
                "[MonitorHandler] Response body (first 500 chars): {}",
                body != null && body.length() > 500 ? body.substring(0, 500) + "..." : body);

        // ═══════════════════════════════════════════════════════════════
        //  同步断言：在 Playwright 事件线程上立即执行
        // ═══════════════════════════════════════════════════════════════
        boolean assertionsPassed = executeAssertions(rule, url, status, body, context);
        if (!assertionsPassed) {
            VerboseLogging.logErrorIfVerbose(LOGGER,
                    "[MonitorHandler] ═══ ASSERTIONS FAILED: pattern='{}', url='{}' ═══", urlPattern, url);
            if (context != null) {
                //  Fail-Fast（非中断模式）：
                //   仅置 hasAssertionFailures 标志 + notifyAll 唤醒 awaitCompletion，
                //   并不调用 Thread.interrupt()——否则中断标志会泄漏到后续 Scenario 的
                //   Playwright IO（page.waitForSelector 等）导致其抛异常。失败由
                //   PlaywrightListener.checkAndFailOnApiAssertions() 在步骤结束时统一抛 AssertionError，
                //   仅影响当前 Scenario（标志在下一 Scenario 启动时重置）。
                context.signalFailFast();
            }
            //  抛出 ApiAssertionException，dispatchRoute 捕获后记录
            throw new RouteException.ApiAssertionException(
                    urlPattern, "ASSERTION",
                    rule.getExpectedStatus() != null ? String.valueOf(rule.getExpectedStatus()) : "N/A",
                    String.valueOf(status));
        }

        // ═══════════════════════════════════════════════════════════════
        //  同步存储本调用（单一来源，覆盖所有 Page）：
        //   全局旁路采集已移除，本方法是 MONITOR 快照的<b>唯一</b>写入点。
        //   同步 storeApiCall（可靠、即时可查）而非异步投喂，避免测试
        //   在无 awaitCompletion 的情况下直接 getLastApiCall 时读到空。
        //   本方法其余部分仅负责：断言、匹配计数、回调、报告、持久化。
        // ═══════════════════════════════════════════════════════════════
        if (context == null) return;
        //  只构造一次 CapturedApiCall，同时用于 storeApiCall 与（断言失败时的）MonitorFailureCollector，
        //   消除重复构造（此前两处字段完全相同地 new 了一次）。
        //   handleType=MONITOR：无论本次调用是否叠加了 MODIFY / DELAY，落到本方法的快照
        //   都是「对真实响应的观察结果」，统一按 MONITOR 归类（MODIFY/DELAY 各自另有落库）。
        CapturedApiCall captured = new CapturedApiCall(
                urlPattern, method, reqHeaders, status, resHeaders, body,
                System.currentTimeMillis(), url, reqBody, RouteHandleType.MONITOR);
        //  C-10：记录截断证据。未截断时 markBodyTruncated 自身为 no-op（防误标），故可无条件调用。
        captured.markBodyTruncated(originalBodyBytes);
        //  BUG 修复：先将「活动请求」发布信号（increment）置于 storeApiCall 之前，
        // 避免主线程在 store 之后、increment 之前轮询到 activeRequests==0 而误判「无活动」提前返回；
        // 同时保证 finally 中 decrement 必然配对，防止计数只增不减导致 awaitCompletion 永久阻塞。
        context.incrementActiveRequests();
        //  落库改用「无回退」查询：owner 已销毁 ⇒ 丢弃本条记录（不回退到"当前"上下文，避免跨场景污染）；
        //    上方计数与下方失败上报仍走带回退的 context，保证断言失败可见性（D7-3）不受影响。
        ApiCaptureContext storeTarget = RouteUtil.captureContextForRecord(route);
        try {
            if (storeTarget != null) {
                storeTarget.storeApiCall(captured);
            } else {
                VerboseLogging.logDebugIfVerbose(LOGGER,
                        "[MonitorHandler] Observation owner context gone → record dropped "
                                + "(not stored into current context): pattern='{}'", rule.getUrlPattern());
            }
        } catch (Exception e) {
            VerboseLogging.logDebugIfVerbose(LOGGER,
                    "[MonitorHandler] Failed to store monitor call: {}", e.getMessage());
        }

        try {
            VerboseLogging.logTraceIfVerbose(LOGGER,
                    "[MonitorHandler] Record START: pattern='{}', url='{}', status={}, bodyLen={}",
                    urlPattern, url, status, body.length());

            // 监控断言失败 → 归集到失败收集器（按 owner 去重，供 CI 邮件发送）
            if (context.hasAssertionFailures()) {
                for (AssertionFailureDetail d : context.getFailureDetails()) {
                    if (d.url.equals(url) || d.url.equals(urlPattern)) {
                        String owner = ApiMonitorOrchestrator.getInstance().getOwner(urlPattern);
                        String reason = String.format("%s expected=%s actual=%s (%s)",
                                d.assertionType, d.expectedValue, d.actualValue, d.failMessage);
                        MonitorFailureCollector.getInstance().record(captured, urlPattern, owner, reason);
                    }
                }
            }

            // 记录到 Serenity 报告
            if (rule.isRecord()) {
                SerenityReporter.recordApiOperation("MONITOR", url,
                        String.format("Status: %d\nBody: %s", status,
                                body.length() > 2000 ? body.substring(0, 2000) + "..." : body));
            }

            // 通知 RouteEngine 完成一次匹配（触发 auto-stop / minMatches 检查）
            RouteEngine.onMonitorMatch(rule);

            // ═══════════════════════════════════════════════════════════════
            // 框架内置：根据配置自动决定是否持久化到数据库
            // 无需用户在业务层手动注册 DatabaseStoreMonitorCallback
            // ═══════════════════════════════════════════════════════════════
            DatabaseStoreMonitorCallback.INSTANCE.onResponse(url, status, body, reqHeaders, resHeaders, method);

            // ═══════════════════════════════════════════════════════════════
            // 框架内置：根据配置自动决定是否将监控数据写入文件
            // ═══════════════════════════════════════════════════════════════
            FileStoreMonitorCallback.INSTANCE.onResponse(url, urlPattern, status, body, reqHeaders, resHeaders, method);

            VerboseLogging.logTraceIfVerbose(LOGGER,
                    "[MonitorHandler] Record DONE: pattern='{}', url='{}'", urlPattern, url);

        } catch (Exception e) {
            LOGGER.error("[MonitorHandler] Error recording monitor match: {}", e.getMessage(), e);
        } finally {
            context.decrementActiveRequests();
        }
    }

    /**
     * 执行 RouteRule 中配置的断言（状态码 + JSONPath），
     * 失败时通过 {@code context} 记录详细信息。
     *
     * @param rule    路由规则
     * @param url     请求 URL
     * @param status  HTTP 状态码
     * @param body    响应 body
     * @param context ApiCaptureContext（可为 null）
     * @return true 所有断言通过，false 有断言失败
     */
    private static boolean executeAssertions(RouteRule rule, String url, int status,
                                              String body, ApiCaptureContext context) {
        boolean allPassed = true;

        // 状态码断言
        Integer expectedStatus = rule.getExpectedStatus();
        if (expectedStatus != null) {
            boolean statusMatch = (status == expectedStatus);
            if (!statusMatch) {
                //  修复 C-2：失败日志中的 url 可能含 token（?token=），统一脱敏后再记录
                LOGGER.warn("[MonitorHandler] Status assertion failed for {}: expected={}, actual={}",
                        RouteUtil.sanitizeUrl(url), expectedStatus, status);
                if (context != null) {
                    context.recordAssertionFailure(url, "STATUS",
                            String.valueOf(expectedStatus), String.valueOf(status),
                            null);
                }
                allPassed = false;
            } else {
                VerboseLogging.logDebugIfVerbose(LOGGER,
                        "[MonitorHandler] Status assertion PASSED: {}, expected={}, actual={}",
                        url, expectedStatus, status);
            }
        }

        // JSONPath 断言（使用缓存编译）
        Map<String, Object> jsonPathAssertions = rule.getJsonPathAssertions();
        if (jsonPathAssertions != null && !jsonPathAssertions.isEmpty()) {
            for (Map.Entry<String, Object> entry : jsonPathAssertions.entrySet()) {
                String jsonPathExpr = entry.getKey();
                try {
                    //  从缓存获取或编译 JsonPath（避免每次重新编译）
                    JsonPath compiled = getOrCompileJsonPath(jsonPathExpr);
                    Object actual = compiled.read(body);
                    boolean match = compareValues(actual, entry.getValue());
                    if (!match) {
                        String actualStr = actual != null ? actual.toString() : "null";
                        LOGGER.warn("[MonitorHandler] JSONPath assertion failed for {}: path={}, expected={}, actual={}",
                                url, jsonPathExpr, entry.getValue(), actualStr);
                        if (context != null) {
                            context.recordAssertionFailure(url, "JSONPATH",
                                    entry.getValue() != null ? entry.getValue().toString() : "null",
                                    actualStr,
                                    "path=" + jsonPathExpr);
                        }
                        allPassed = false;
                    } else {
                        VerboseLogging.logDebugIfVerbose(LOGGER,
                                "[MonitorHandler] JSONPath assertion PASSED: {}, path='{}', expected='{}', actual='{}'",
                                url, jsonPathExpr, entry.getValue(), actual);
                    }
                } catch (Exception e) {
                    LOGGER.warn("[MonitorHandler] JSONPath evaluation error for {}: path={}, error={}",
                            url, jsonPathExpr, e.getMessage(), e);
                    if (context != null) {
                        context.recordAssertionFailure(url, "JSONPATH",
                                entry.getValue() != null ? entry.getValue().toString() : "null",
                                "ERROR",
                                "path=" + jsonPathExpr + ", error=" + e.getMessage());
                    }
                    allPassed = false;
                }
            }
        }

        VerboseLogging.logDebugIfVerbose(LOGGER,
                "[MonitorHandler] executeAssertions RESULT: allPassed={}, url={}, pattern='{}'",
                allPassed, url, rule.getUrlPattern());
        return allPassed;
    }

    /**
     * 从缓存获取或编译 JsonPath 表达式（容量保护）。
     */
    private static JsonPath getOrCompileJsonPath(String expression) {
        //  P2-15：委托 RouteUtil 共享缓存
        return RouteUtil.compileJsonPathCached(expression);
    }

    /**
     * 值比较（支持 Number 类型的松散比较，使用 epsilon 避免浮点精度问题）。
     */
    private static boolean compareValues(Object actual, Object expected) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;

        if (actual instanceof Number && expected instanceof Number) {
            double a = ((Number) actual).doubleValue();
            double e = ((Number) expected).doubleValue();
            //  使用 epsilon 比较，避免 0.1+0.2 != 0.3 等浮点精度问题
            double epsilon = 1e-9;
            boolean match = Math.abs(a - e) < epsilon;
            VerboseLogging.logTraceIfVerbose(LOGGER,
                    "[MonitorHandler] compareValues (Number): actual={}, expected={}, match={}",
                    a, e, match);
            return match;
        }

        boolean match = actual.toString().equals(expected.toString());
        VerboseLogging.logTraceIfVerbose(LOGGER,
                "[MonitorHandler] compareValues (String): actual='{}', expected='{}', match={}",
                actual.toString(), expected.toString(), match);
        return match;
    }

    /**
     * 安全快照 Playwright headers 对象（避免跨线程访问）。
     * 复制为普通 HashMap，与 Playwright 事件线程解耦。
     */
    private static Map<String, String> snapshotHeadersSafely(Map<String, String> headers) {
        if (headers == null) return null;
        try {
            return new java.util.HashMap<>(headers);
        } catch (Exception e) {
            LOGGER.warn("[MonitorHandler] Failed to snapshot headers: {}", e.getMessage());
            return null;
        }
    }


}
