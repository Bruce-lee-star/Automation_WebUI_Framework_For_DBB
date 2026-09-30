package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionFailure;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.GenerationRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.PatternBinder;
import com.hsbc.cmb.hk.dbb.automation.framework.route.diag.HangWatchdog;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.RouteBinderRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.RuleGeneration;
import com.hsbc.cmb.hk.dbb.automation.framework.route.claim.ClaimRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.claim.PendingGuard;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dispatch.RouteDispatcher;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.exec.BoundedOps;
import com.hsbc.cmb.hk.dbb.automation.framework.route.exec.RouteDelayScheduler;
import com.hsbc.cmb.hk.dbb.automation.framework.route.exec.RouteIoExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.route.exec.RouteRetireExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CaptureSink;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CapturedExchange;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.MonitorSink;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Route V2 运行时（每 BrowserContext 一份）——{@link RouteRuntime} 角色的生产实现。
 *
 * <p>并发安全设计（为什么每个 context 一份、为什么不再有全局 static 可变表）：见 {@link RouteRuntime} 契约。
 *
 * <p>创建收口：实例仅经 {@link RouteRuntimeFactory}（默认 {@link DefaultRouteRuntimeFactory}）
 * 创建，{@link RouteEngine} 不再直接 new；可经 SPI/factory 替换为测试替身（真多态）。
 *
 * <p>线程清单：事件线程（Playwright 回调）、IO 线程池、巡检线程（daemon）。
 */
public final class RouteRuntimeImpl implements RouteRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteRuntimeImpl.class);

    private final BrowserContext context;
    private final RouteConfig config;
    private final GenerationRegistry generations = new GenerationRegistry();
    private final PendingGuard pendingGuard;
    private final ClaimRegistry claims;
    private final BoundedOps ops;
    private final RouteIoExecutor io;
    /** 独立撤销执行器（S2，R-4）：与 IO 池隔离，避免撤销等待占住线程、挤压 body 断言/采集。 */
    private final RouteRetireExecutor retire;
    private final MonitorSink monitor;
    private final CaptureSink capture;
    private final RouteDispatcher dispatcher = new RouteDispatcher();
    private final ConcurrentMap<String, PatternBinder> binders = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** runtime 是否已降级（注册曾失败）；context 复用方据此丢弃该 Context。 */
    private final AtomicBoolean degraded = new AtomicBoolean(false);
    /**
     * 本次关闭是否由 {@code context.onClose} 触发（context 正在销毁）。
     * 该路径只做内存收尾，绝不发起协议调用 —— 见 {@link #closeAfterContextClosed()}。
     */
    private volatile boolean contextClosing;
    /** T2+：按"目的达成"撤销的规则数（观测指标）。 */
    private final AtomicInteger retiredByPurpose = new AtomicInteger(0);
    /** T2+：未被确证的撤销次数（>0 ⇒ 本用例路由状态"不可确证干净"，见不变式 I-8 / I-9）。 */
    private final AtomicInteger unconfirmedRetirements = new AtomicInteger(0);
    /** T1：每条规则 armed 起点（纳秒，单调时钟），用于计算 armed 时长。 */
    private final ConcurrentMap<String, Long> armedAtNs = new ConcurrentHashMap<>();
    /** T1：每条规则 armed 时长（毫秒），撤销/收尾时定稿（per-rule）。 */
    private final ConcurrentMap<String, Long> armedDurationsMs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;
    private final HangWatchdog hangWatchdog;
    private final ConcurrentMap<BrowserContext, RouteRuntime> ownerRegistry;
    private final AtomicInteger contextSeq = new AtomicInteger(0);

    private RouteRuntimeImpl(BrowserContext context, RouteConfig config,
                             ConcurrentMap<BrowserContext, RouteRuntime> ownerRegistry) {
        this.context = context;
        this.config = config;
        this.ownerRegistry = ownerRegistry;
        this.pendingGuard = new PendingGuard(config.maxPending());
        this.claims = new ClaimRegistry(pendingGuard, Duration.ofSeconds(config.ioAwaitTimeoutSeconds()));
        this.ops = new BoundedOps(config.opsMaxConcurrent(), Duration.ofMillis(500));
        String tag = String.valueOf(contextSeq.incrementAndGet());
        this.io = new RouteIoExecutor(tag, config.ioThreads(), config.ioQueueCapacity());
        this.retire = new RouteRetireExecutor(tag);
        this.monitor = new MonitorSink(io, this::retireByPurpose);
        this.capture = new CaptureSink(io, config.maxCaptured(), config.captureTimeoutMs());
        this.sweeper = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "route-v2-sweep-" + tag);
                t.setDaemon(true);
                return t;
            }
        });
        this.sweeper.scheduleWithFixedDelay(this::sweep, config.sweepIntervalMs(), config.sweepIntervalMs(),
                TimeUnit.MILLISECONDS);
        // context 关闭 → 运行时自动关闭（幂等）
        context.onClose(ignored -> closeAfterContextClosed());
        // 刻意【不订阅】context.onResponse：该订阅是驱动侧 "Object doesn't exist: response@…" 竞态的
        // 唯一触发源——Playwright 在 BrowserContextImpl.handleEvent 的 "response" 分支会为每个事件句柄
        // 调用 Connection.getExistingObject(guid)，句柄一旦被服务端回收即抛异常；异常发生在业务 lambda
        // 之前无法被拦截，且因 Connection 为共享单连接，会以「此刻在等结果的任意调用线程」为宿主抛出
        // （实测：会话校验导航被炸 → 误判会话失效 → 删缓存 → 每轮全量重登）。
        // 响应侧观测改走 route 通道：recordObservation 持有 Request，轮询 existingResponse()（本地字段）。
        VerboseLogging.logInfoIfVerbose(LOGGER, "[Route] runtime installed for context @{} (maxPending={}, ioThreads={})",
                System.identityHashCode(context), config.maxPending(), config.ioThreads());
        // 临时诊断看门狗（与 web HangWatchdog 同源，待去除）：按本 runtime 生命周期武装
        this.hangWatchdog = new HangWatchdog("ctx@" + System.identityHashCode(context));
        this.hangWatchdog.arm();
        VerboseLogging.logInfoIfVerbose(LOGGER,
                "[Route] HangWatchdog armed (diag, temporary) for context @{}", System.identityHashCode(context));
    }

    /** 响应轮询间隔（毫秒）：细粒度使已到达的响应近乎即时定案，且不空转。 */
    private static final long RESPONSE_POLL_INTERVAL_MS = 25L;

    /**
     * 响应侧观测轮询（route 通道）——取代原 {@code context.onResponse} 事件订阅。
     *
     * <p><b>为什么能取代</b>：{@link Request#existingResponse()} 是请求本地持有的字段
     * （playwright-java 1.59+），零协议往返、不触碰 Connection 对象表；route 分发入口本就持有
     * {@link Request}，因此无需任何事件订阅即可完成请求-响应配对（配对键为 dispatch 时的
     * {@link ApiSpec} 实例，比原先按 URL 匹配更精确）。
     *
     * <p><b>线程模型</b>：经 {@link RouteDelayScheduler}（模块自有 daemon 调度池）递归调度，
     * 不占用 IO 线程、不使用 {@code Thread.sleep}（框架 ArchUnit 明令禁止）；命中即投递 sink
     * （sink 内部无阻塞：body 读取另交 IO 线程）。窗口内未命中则静默结束——由 sink 的
     * drain 超时定案路径兜底（与事件驱动时代语义一致）。
     */
    private void scheduleResponsePoll(Request request, ApiSpec spec,
                                      boolean monitorPending, boolean capturePending, long deadlineNs) {
        if (closed.get()) {
            return; // runtime 已关闭（context 收尾）→ 停止轮询
        }
        Response response = safeExistingResponse(request);
        if (response != null) {
            deliverResponse(spec, response, monitorPending, capturePending);
            return;
        }
        if (System.nanoTime() >= deadlineNs) {
            return; // 窗口内未到达：交由 sink 的超时定案
        }
        RouteDelayScheduler.delay(RESPONSE_POLL_INTERVAL_MS,
                () -> scheduleResponsePoll(request, spec, monitorPending, capturePending, deadlineNs));
    }

    /** {@code existingResponse()} 为本地字段读取；防御性兜底，绝不因取响应失败而中断观测链。 */
    private static Response safeExistingResponse(Request request) {
        try {
            return request == null ? null : request.existingResponse();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按 spec 投递响应；单条管道异常不得影响另一条，更不得逃逸到调度线程。 */
    private void deliverResponse(ApiSpec spec, Response response,
                                 boolean monitorPending, boolean capturePending) {
        if (monitorPending) {
            try {
                monitor.onResponseForSpec(spec, response);
            } catch (Throwable t) {
                LOGGER.warn("[Route] monitor onResponse failed for pattern='{}': {}", spec.pattern(), t.toString());
            }
        }
        if (capturePending) {
            try {
                capture.onResponseForSpec(spec, response);
            } catch (Throwable t) {
                LOGGER.warn("[Route] capture onResponse failed for pattern='{}': {}", spec.pattern(), t.toString());
            }
        }
    }

    /** 安装运行时（静态工厂，由 RouteRuntimeFactory / DefaultRouteRuntimeFactory 调用）。 */
    static RouteRuntime install(BrowserContext context, RouteConfig config,
                                ConcurrentMap<BrowserContext, RouteRuntime> ownerRegistry) {
        return new RouteRuntimeImpl(context, config, ownerRegistry);
    }

    // ── 访问器（供 RouteDispatcher / PatternBinder 使用；模块内部契约）──

    @Override
    public GenerationRegistry generations() {
        return generations;
    }

    @Override
    public ClaimRegistry claims() {
        return claims;
    }

    @Override
    public PendingGuard pending() {
        return pendingGuard;
    }

    @Override
    public BoundedOps ops() {
        return ops;
    }

    @Override
    public RouteIoExecutor io() {
        return io;
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public boolean isDegraded() {
        return degraded.get();
    }

    /**
     * 标记路由设施降级（注册失败，或撤销自愈后仍不可确证）。
     *
     * <p><b>语义边界（2026-09-29 裁定）</b>：这是<b>可观测信号</b>（报告/诊断用），
     * <b>不表示要重建 Context</b> —— feature 模式下同一 sessionKey 不重建（不变式 I-1），
     * 路由状态分叉由全量快照重发（{@link PatternBinder#retryUnroute()}）自愈。</p>
     */
    void markDegraded() {
        if (degraded.compareAndSet(false, true)) {
            LOGGER.warn("[Route] route facility degraded for context @{} (registration/unroute not confirmed) — "
                    + "observability only, context is NOT rebuilt (I-1)", System.identityHashCode(context));
        }
    }

    @Override
    public APIRequestContext request() {
        return context.request();
    }

    @Override
    public void recordObservation(Request request, ApiSpec spec) {
        // MONITOR 能力位已停止（stop* / 目的退役）⇒ 不再为其做响应侧观测轮询；
        // capture 维度不受 MONITOR 停止影响，照常采集。
        boolean monitorPending = spec.isStopped(RouteCapability.MONITOR) ? false : monitor.recordRequest(request, spec);
        boolean capturePending = capture.recordRequest(request, spec);
        if (!monitorPending && !capturePending) {
            return; // 本规则不需要响应（无响应侧断言 / 未开 capture）→ 不启动任何轮询
        }
        // 等待窗口：monitor 规则用其 timeout（0=永不超时 → 退化为采集超时窗口，避免无限轮询）；
        // 纯 capture 规则用采集超时窗口。窗口外未到达由 sink 的 drain 超时定案路径兜底。
        long capMs = spec.monitorTimeoutMs() > 0 ? spec.monitorTimeoutMs() : config.captureTimeoutMs();
        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(capMs);
        scheduleResponsePoll(request, spec, monitorPending, capturePending, deadlineNs);
    }

    @Override
    public List<CapturedApiCall> dumpCapturedApis() {
        return capture.dump();
    }

    @Override
    public List<RouteAssertionFailure> drainSettledAssertionFailures() {
        List<RouteAssertionFailure> result = new ArrayList<>();
        for (CapturedExchange e : monitor.drainSettledFailures()) {
            result.add(new RouteAssertionFailure(
                    e.pattern(), e.method(), e.url(), e.expectStatus(), e.responseStatus(),
                    e.bodyAssertionFailures(), e.responseTimedOut(), e.monitorTimeoutMs()));
        }
        return result;
    }

    @Override
    public AutoCloseable register(ApiSpec spec) {
        if (closed.get()) {
            throw new IllegalStateException("RouteRuntime is closed for context @" + System.identityHashCode(context));
        }
        // 1) 先发布规则（读侧无锁即刻可见）
        generations.merge(spec);
        // 2) 绑定驱动（每 pattern 恰好一次；同 pattern 覆盖不重建绑定）。
        //    注册失败标记 runtime 降级，供 context 复用方丢弃该 Context。
        try {
            PatternBinder binder = binders.computeIfAbsent(spec.pattern(),
                    p -> RouteBinderRegistry.instance().bind(context, spec, this));
            //  注册超时被降级（规则已在内存发布，但驱动层未生效）→ 标记 runtime 降级仅用于
            //  **可观测/自愈判定**；绝不因此重建 Context（2026-09-29 裁定：feature 模式下同一
            //  sessionKey 不重建 —— 路由是辅助设施，其可用性不得绑架会话/Context 生命周期）。
            if (binder.isDegraded()) {
                markDegraded();
            }
            //  T2+ 规则生命上限（2026-09-29 裁定）：带监控窗口的 MONITOR 规则若在 monitorTimeoutMs
            //  内既未匹配、也未定案，同样"清理结束"该规则 —— 确保每条规则都有确定生命终点。
            armedAtNs.put(spec.pattern(), System.nanoTime()); // T1：规则 armed 计时起点
            schedulePurposeDeadline(spec);
            LOGGER.debug("[Route] registered pattern='{}' capability={}", spec.pattern(), spec.capability());
            return () -> {
                //  令牌化摘除（P2，单规则多能力位版）：仅当该能力位在当前合并规则中仍活跃时，
                //  将其剥离（清空字段 + disabled）。若该 pattern 剥离后已无任何活跃能力位，再摘除原生绑定。
                //  否则同 pattern 仍有其它能力位存活 ⇒ 本句柄只对自身能力位生效，不得摘掉同伴能力位的绑定。
                if (generations.removeCapabilityIfCurrent(spec.pattern(), spec.capability(), spec)
                        && generations.snapshot().specFor(spec.pattern()) == null) {
                    PatternBinder current = binders.get(spec.pattern());
                    if (current != null && binders.remove(spec.pattern(), current)) {
                        current.close();
                    }
                }
            };

        } catch (RuntimeException e) {
            //  绑定失败 ⇒ 必须把刚发布到内存规则表的规则摘掉：否则表里会留下"无驱动绑定、也无句柄可摘"的
            //  幽灵规则（stop* / metrics / clearRules 的计数与实际生命周期不一致）。
            removeGenerationIfCurrent(spec);
            markDegraded();
            throw e;
        }
    }

    @Override
    public boolean stop(RouteCapability capability, String pattern) {
        if (closed.get()) {
            return false;
        }
        // 单规则多能力位：stop 仅停用该能力位（标记 disabled，dispatch 链式裁决时跳过），
        // 不影响同 pattern 的其它能力位。并发安全由 GenerationRegistry 的 CAS 保证。
        boolean ok = generations.disableCapability(pattern, capability);
        if (ok) {
            LOGGER.debug("[Route] stopped capability={} for pattern='{}' (gen {})",
                    capability, pattern, generations.generation());
        }
        return ok;
    }

    @Override
    public boolean stopApi(String pattern) {
        ApiSpec current = generations.snapshot().specFor(pattern);
        return current != null && stop(current.capability(), pattern);
    }

    @Override
    public void dispatch(Route route, String pattern) {
        if (closed.get()) {
            // context 关闭中 → fail-open
            try {
                route.fallback();
            } catch (Exception ignored) {
                LOGGER.trace("[Route] fallback on closed context ignored: {}", ignored.toString());
            }
            return;
        }
        dispatcher.dispatch(route, pattern, this);
    }

    /** 巡检（调度线程）：强制落定超龄挂起 claim。 */
    private void sweep() {
        try {
            claims.sweep();
        } catch (Exception e) {
            LOGGER.warn("[Route] sweep failed: {}", e.toString());
        }
    }
    /**
     * 令牌化移除内存规则表条目：仅当该 pattern 当前仍是本实例时移除。
     *
     * <p><b>为什么必须令牌化</b>：句柄关闭者可能晚于"同 pattern 重新注册"发生 —— 若按 pattern 无条件移除，
     * 旧句柄就会摘掉<b>新规则</b>（与 {@code retireByPurpose} 的令牌语义保持一致）。</p>
     */
    private void removeGenerationIfCurrent(ApiSpec spec) {
        if (spec == null) {
            return;
        }
        // 单规则多能力位：注册绑定失败回滚时，只撤掉本能力位（合并规则下整条已变化，
        // 不能按整条实例比对），保留同 pattern 其它能力位。
        generations.removeCapabilityIfCurrent(spec.pattern(), spec.capability(), spec);
    }

    /**
     * 只清规则、保留 runtime 与 Context（V2-2；契约见 {@link RouteRuntime#clearRules()}）。
     */
    @Override
    public int clearRules() {
        if (closed.get()) {
            return 0; // 收尾中：close() 会统一 flush 剩余绑定
        }
        //  先把内存规则表原子清空：方法返回后"不再有新请求命中"是确定性的；而驱动侧撤销是异步的
        // （与"目的达成撤销"同路径：独立执行器 + 确证 + 未确证则重同步自愈）。故先清内存，让调用方
        //  无需等待即可断定"规则已清"，撤销结论仍由 isClean() / unconfirmedRetirements() 完整暴露。
        clearGenerations();
        int retired = 0;
        for (String pattern : binders.keySet().toArray(new String[0])) {
            if (retireByPurpose(pattern)) {
                retired++;
            }
        }
        VerboseLogging.logInfoIfVerbose(LOGGER,
                "[Route] clearRules @{}: {} rule(s) retired, runtime and context kept",
                System.identityHashCode(context), retired);
        return retired;
    }

    /**
     * 【档 B】纯内存解绑：只清规则表，保留驱动侧绑定与 runtime（<b>零协议调用</b>，契约见
     * {@link RouteRuntime#detachRules()}）。
     *
     * <p><b>刻意不做</b>：不调 {@code retireByPurpose}、不调 {@code binder.close()}（二者都会发
     * {@code setNetworkInterceptionPatterns}）。handler 常驻 + 空规则表 ⇒ 命中即 fail-open
     * （见 {@code RouteDispatcher.dispatch} 的 {@code spec == null} 分支），与"未注册"等价。</p>
     */
    @Override
    public int detachRules() {
        if (closed.get()) {
            return 0; // 收尾中：close() 会统一处理剩余绑定
        }
        int cleared = generations.snapshot().rules().size();
        clearGenerations();
        VerboseLogging.logInfoIfVerbose(LOGGER,
                "[Route] detachRules @{}: {} rule(s) detached in memory; {} native binding(s) kept "
                        + "(zero protocol calls — hits fall back to pass-through)",
                System.identityHashCode(context), cleared, binders.size());
        return cleared;
    }

    /** 原子清空内存规则表（代际单调递增，读侧无锁即刻可见）。 */
    private void clearGenerations() {
        while (true) {
            RuleGeneration base = generations.snapshot();
            if (base.rules().isEmpty()) {
                return;
            }
            if (generations.compareAndSet(base, RuleGeneration.next(base, Map.of()))) {
                return;
            }
        }
    }

    /**
     * {@code context.onClose} 回调路径：context 正在销毁 ⇒ 只做内存收尾。
     *
     * <p><b>为什么这里不 unroute（2026-09-29 全新评审，P1）</b>：
     * <ol>
     *   <li>该回调运行在 Playwright 的<b>消息泵线程</b>上，而客户端是"调用者自己泵消息"
     *       （{@code Connection.sendMessage → ChannelOwner.runUntil → processOneMessage}）——
     *       在此发起同步协议调用等于在泵线程里做嵌套下发；每条 unroute 的界值是 {@code unrouteBoundMs()}，
     *       再加上线程池 {@code awaitTermination}，最长可阻塞消息泵数十秒（30s 级卡顿的同类机制）；</li>
     *   <li>context 正在销毁时，<b>驱动侧 route handler 由 Playwright 随 context 一并释放</b>，
     *       逐条 unroute 是纯多余开销。</li>
     * </ol>
     * 真正需要逐条 unroute 的只有"context 仍存活、而 runtime 被显式关闭"（如 feature 模式的用例边界清理）。</p>
     */
    private void closeAfterContextClosed() {
        markContextClosing();
        close();
    }

    /**
     * 标记「Context 正被主动关闭」（T8-5）：随后 {@link #close()} 跳过逐条 unroute，只做内存收尾。
     *
     * <p>与 {@link #closeAfterContextClosed()} 的区别仅在于<b>意图来源</b>：
     * 后者由 {@code context.onClose} 事件驱动（Context 已死），本方法由框架主动收尾驱动
     * （Context 仍存活、但紧接着就会被 {@code context.close()}）。
     */
    @Override
    public void markContextClosing() {
        contextClosing = true;
    }

    @Override
    public void close() {
        // 解除临时诊断看门狗武装（与 web HangWatchdog 一并去除）
        if (hangWatchdog != null) {
            hangWatchdog.disarm();
        }
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        VerboseLogging.logInfoIfVerbose(LOGGER, "[Route] closing runtime for context @{}",
                System.identityHashCode(context));
        sweeper.shutdownNow();
        claims.closeAll();
        //  收尾兜底 flush（T2+）：把"未被目的触发撤销"的剩余规则一并撤销，并**汇总确证结论** ——
        //  这是把"用例收尾恢复如初"从『执行了动作』升级为『可判定的状态』的关键一步：
        //  全部确证 ⇒ 干净；任一未确证 ⇒ 记入 unconfirmedRetirements + 降级（不变式 I-9：<b>重同步自愈 +
        //  可见告警，绝不重建 Context</b> —— 路由是辅助设施，其状态不确定性不得绑架会话/Context 生命周期）。
        //  注：close() 可能由 context.onClose 在事件线程触发，此处 flush 是同步协议调用（沿用原行为、
        //  经 GuardedDriverCall 有界）；T6（驱动调用单线程化）会一并收口该路径。
        int confirmed = 0;
        int unconfirmed = 0;
        for (Map.Entry<String, PatternBinder> e : binders.entrySet()) {
            recordArmedDuration(e.getKey()); // T1：定稿剩余规则的 armed 时长
            if (contextClosing || e.getValue().closeConfirmed()) {
                confirmed++;
            } else {
                unconfirmed++;
            }
        }
        binders.clear();
        if (unconfirmed > 0) {
            unconfirmedRetirements.addAndGet(unconfirmed);
            markDegraded();
            LOGGER.error("[Route] teardown flush UNCONFIRMED for {} rule(s) on context @{} — route state not "
                            + "verifiable after resync. Context/session are NOT rebuilt (I-1); observability signal only",
                    unconfirmed, System.identityHashCode(context));
        }
        VerboseLogging.logInfoIfVerbose(LOGGER,
                "[Route] route state: {} rule(s) retired by purpose, {} flushed at teardown, {} unconfirmed",
                retiredByPurpose.get(), confirmed, unconfirmed);
        // T1 度量基线：每用例（= 每 runtime 生命周期）汇总一行，报表可见、真跑归档。
        LOGGER.info("[Route] case metrics @{}: dispatches={}, hits={}, retiredByPurpose={}, "
                + "unconfirmedRetirements={}, rulesArmedMs={}",
                System.identityHashCode(context), dispatcher.dispatchCount(), dispatcher.hitCount(),
                retiredByPurpose.get(), unconfirmedRetirements.get(), armedDurationsMs);
        if (contextClosing) { retire.closeNoWait(); } else { retire.close(); } // 收尾 flush 是同步的；此处等待在途的"目的驱动撤销"完成（不阻塞、有界）
        if (contextClosing) { io.closeNoWait(); } else {
            io.close();
        }
        if (ownerRegistry != null) {
            ownerRegistry.remove(context, this);
        }
    }

    /**
     * 目的达成即撤销（<b>令牌化入口</b>，2026-09-29：消除"旧触发者撤掉新规则"竞态）。
     *
     * <p><b>为何必须令牌化</b>：撤销触发者（监控窗口到期任务、响应定案回调）都持有<b>注册时的规则实例</b>，
     * 而 pattern 槽位可能已被"重新注册的新规则"接管。若只按 pattern 撤销，旧触发者就会撤掉<b>新规则</b>
     * ⇒ 辅助设施提前退场 ⇒ 行为漂移（正是要避免的"非业务层失败"）。</p>
     *
     * <p><b>令牌判定</b>：以框架既有的代际表 {@code generations.snapshot().specFor(pattern)} 为
     * "当前注册的规则实例"；与传入实例<b>不一致 ⇒ 已被替换/停止</b> ⇒ <b>拒绝撤销</b>
     * （新规则有自己的生命周期终点，不受旧触发者影响）。</p>
     *
     * @param spec 注册时的规则实例（令牌）
     * @return true=已提交撤销 / 无需撤销 / 令牌不一致（拒绝撤新规则）；false=IO 队列满（已归还收尾兜底）
     */
    public boolean retireByPurpose(ApiSpec spec) {
        if (spec == null || closed.get()) {
            return true;
        }
        // 令牌化摘除本能力位：仅当当前仍是本触发者注册的规则实例时才退役
        // （拒绝误撤新规则 / 已停规则）；同 pattern 其它能力位（MOCK/MODIFY/DELAY）不受影响。
        // 使用「移除」语义（而非仅标记 disabled）使该能力位彻底脱离合并视图；
        // 若移除后 pattern 已无任何活跃能力位，则关闭其原生绑定（handler 撤销）。
        generations.removeCapabilityIfCurrent(spec.pattern(), spec.capability(), spec);
        if (generations.snapshot().specFor(spec.pattern()) == null) {
            PatternBinder binder = binders.remove(spec.pattern());
            if (binder != null) {
                retiredByPurpose.incrementAndGet();
                recordArmedDuration(spec.pattern());
                boolean submitted = retire.trySubmit("retire:" + spec.pattern(),
                        () -> confirmRetirement(spec.pattern(), binder));
                if (!submitted) {
                    PatternBinder previous = binders.putIfAbsent(spec.pattern(), binder);
                    if (previous != null) {
                        LOGGER.warn("[Route] retire '{}' could not be re-queued "
                                + "(pattern re-registered concurrently); its driver-side handler is "
                                + "released on context close", spec.pattern());
                    }
                    markUnconfirmedRetirement(spec.pattern(),
                            "retire queue full — deferred to teardown flush");
                }
            }
        }
        return true;
    }

    /**
     * 目的达成即撤销该 pattern 的绑定（T2+ 核心入口：<b>规则随目的生灭</b>取代"随用例生灭"）。
     *
     * <p><b>为什么不在调用线程同步执行</b>：本方法由"目的定案"路径回调（如 MONITOR 断言结算），
     * 而该路径运行在事件/轮询线程上；{@code unroute} 是同步协议调用
     * （{@code setNetworkInterceptionPatterns}，且"调用线程自己泵消息"）⇒ 在事件线程内同步撤销等于
     * <b>嵌套下发</b>（客户端本身每命中已嵌套下发一次）。因此统一提交<b>独立撤销执行器</b>
     * （与 IO 池隔离，闭合竞态 R-4）：撤销不阻塞事件线程，也不挤压 body 断言/采集。</p>
     *
     * <p><b>失败处置（不引入"假设干净"、绝不重建 Context）</b>：撤销队列满 ⇒ 把绑定归还给规则表，
     * 交由收尾兜底 flush 撤销，并按"暂不可确证"记入(降级)；界内无回包 ⇒ 重发一次重同步自愈，仍失败 ⇒
     * 记入 {@link #unconfirmedRetirements} + {@link #markDegraded()}（不变式 I-9：<b>重同步自愈 + 可见告警，
     * 绝不重建 Context</b>）。</p>
     *
     * @param pattern 规则 pattern（{@link ApiSpec#pattern()}）
     * @return true=已提交撤销（或无需撤销 / 收尾中）；false=撤销队列满（已归还给收尾兜底并降级）
     */
    @Override
    public boolean retireByPurpose(String pattern) {
        if (pattern == null) {
            return true;
        }
        if (closed.get()) {
            return true; // 收尾中：close() 会统一 flush 剩余绑定
        }
        PatternBinder binder = binders.remove(pattern);
        if (binder == null) {
            return true; // 未注册 / 已被目的撤销 → 无待撤状态（幂等）
        }
        retiredByPurpose.incrementAndGet();
        recordArmedDuration(pattern); // T1：定稿 armed 时长
        boolean submitted = retire.trySubmit("retire:" + pattern, () -> confirmRetirement(pattern, binder));
        if (!submitted) {
            PatternBinder previous = binders.putIfAbsent(pattern, binder);
            if (previous != null) {
                // 极端竞态：同 pattern 已被新注册占用 → 旧绑定无法归还；其驱动层 handler 随 context 关闭释放
                LOGGER.warn("[Route] retire '{}' could not be re-queued (pattern re-registered concurrently); "
                        + "its driver-side handler is released on context close", pattern);
            }
            markUnconfirmedRetirement(pattern, "retire queue full — deferred to teardown flush");
            return false;
        }
        LOGGER.debug("[Route] purpose met → retiring rule '{}' (unroute submitted to dedicated retire executor)",
                pattern);
        return true;
    }

    /** IO 线程上执行撤销并确证；未确证则**重发一次重同步自愈**，仍失败才计入"不可确证"。 */
    private void confirmRetirement(String pattern, PatternBinder binder) {
        if (binder.closeConfirmed()) {
            LOGGER.debug("[Route] rule '{}' retired by purpose (unroute confirmed)", pattern);
            return;
        }
        if (binder.retryUnroute()) {
            LOGGER.info("[Route] rule '{}' unroute unconfirmed on first attempt — resync succeeded "
                            + "(driver pattern list re-synced; no rebuild needed)",
                    pattern);
            return;
        }
        markUnconfirmedRetirement(pattern, "no ack within unroute bound (initial + resync)");
    }

    /**
     * 记一次"未确证的撤销"（自愈后仍失败）：计数 + 响亮 ERROR。
     *
     * <p><b>绝不因此重建 Context</b>（2026-09-29 裁定）：路由是辅助设施，其状态不确定性不得绑架
     * 会话/Context 生命周期（feature 模式下同一 sessionKey 不重建、保留免登录）。状态分叉本身由
     * {@link PatternBinder#retryUnroute()} 的重同步修复；本计数与 ERROR 仅用于<b>可观测与根因定位</b>。</p>
     */
    private void markUnconfirmedRetirement(String pattern, String reason) {
        unconfirmedRetirements.incrementAndGet();
        markDegraded();
        LOGGER.error("[Route] unroute UNCONFIRMED for '{}' ({}) on context @{} — route state not verifiable "
                        + "after resync. Context/session are NOT rebuilt (I-1); this is an observability signal.",
                pattern, reason, System.identityHashCode(context));
    }

    /** T1：定稿某规则的 armed 时长（毫秒）；未记录起点（如从未 armed）则跳过。 */
    private void recordArmedDuration(String pattern) {
        Long start = armedAtNs.remove(pattern);
        if (start != null) {
            armedDurationsMs.put(pattern, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    @Override
    public int unconfirmedRetirements() {
        return unconfirmedRetirements.get();
    }

    @Override
    public boolean isClean() {
        return unconfirmedRetirements.get() == 0;
    }

    /**
     * 为"参与目的撤销"的规则安排<b>生命上限</b>（2026-09-29 裁定）：到 {@code monitorTimeoutMs}
     * 仍未达成目的（未匹配到 / 未定案）⇒ 同样"清理结束"该规则。
     *
     * <p><b>为什么需要</b>：若规则只因"没匹配到"就永久 armed，就会跨步骤/跨用例残留（正是"规则随用例
     * 生灭"的坏处）。窗口到期即清理，使"没匹配到"也有确定生命终点；窗口内已定案则由 {@code MonitorSink}
     * 提前撤销（{@link #retireByPurpose(String)} 幂等，本定时任务随即成为空操作）。</p>
     *
     * <p>{@code monitorTimeoutMs == 0}（对齐 {@code RouteDsl.timeout(0)} 的"永不超时"语义）⇒ 不设上限，
     * 交由用例收尾兜底 flush；{@code autoStopOnMatch} 类规则若未达标，同样由收尾 flush 清理。</p>
     */
    private void schedulePurposeDeadline(ApiSpec spec) {
        if (spec.capability() != RouteCapability.MONITOR
                || (spec.expectStatus() == null && !spec.hasBodyAssertions())
                || spec.monitorTimeoutMs() <= 0) {
            return;
        }
        try {
            sweeper.schedule(() -> retireByPurpose(spec), spec.monitorTimeoutMs(),
                    TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException runtimeClosing) {
            // runtime 已收尾（sweeper 已关闭）：无需再安排，收尾兜底 flush 已覆盖
            LOGGER.debug("[Route] purpose deadline skipped (runtime closing) for '{}'", spec.pattern());
        }
    }

    @Override
    public RouteMetrics metrics() {
        return new RouteMetrics(
                generations.generation(),
                claims.size(),
                claims.pendingCount(),
                ops.acceptedCount(),
                ops.rejectedCount(),
                io.rejectedCount(),
                monitor.size(),
                capture.size(),
                capture.droppedCount(),
                dispatcher.dispatchCount(),
                dispatcher.hitCount(),
                retiredByPurpose.get(),
                unconfirmedRetirements.get(),
                Map.copyOf(armedDurationsMs));
    }
}
