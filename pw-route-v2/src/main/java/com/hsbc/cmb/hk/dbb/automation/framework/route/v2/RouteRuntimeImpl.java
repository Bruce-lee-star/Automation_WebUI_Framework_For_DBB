package com.hsbc.cmb.hk.dbb.automation.framework.route.v2;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteV2AssertionFailure;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding.GenerationRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding.PatternBinder;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.diag.HangWatchdog;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding.RouteBinderRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding.RuleGeneration;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.claim.ClaimRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.claim.PendingGuard;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dispatch.RouteDispatcher;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec.BoundedOps;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec.RouteIoExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.monitor.CaptureSink;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.monitor.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.monitor.CapturedExchange;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.monitor.MonitorSink;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
 * 创建，{@link RouteEngine2} 不再直接 new；可经 SPI/factory 替换为测试替身（真多态）。
 *
 * <p>线程清单：事件线程（Playwright 回调）、IO 线程池、巡检线程（daemon）。
 */
public final class RouteRuntimeImpl implements RouteRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteRuntimeImpl.class);

    private final BrowserContext context;
    private final RouteV2Config config;
    private final GenerationRegistry generations = new GenerationRegistry();
    private final PendingGuard pendingGuard;
    private final ClaimRegistry claims;
    private final BoundedOps ops;
    private final RouteIoExecutor io;
    private final MonitorSink monitor;
    private final CaptureSink capture;
    private final RouteDispatcher dispatcher = new RouteDispatcher();
    private final ConcurrentMap<String, PatternBinder> binders = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** runtime 是否已降级（注册曾失败）；context 复用方据此丢弃该 Context。 */
    private final AtomicBoolean degraded = new AtomicBoolean(false);
    private final ScheduledExecutorService sweeper;
    private final HangWatchdog hangWatchdog;
    private final ConcurrentMap<BrowserContext, RouteRuntime> ownerRegistry;
    private final AtomicInteger contextSeq = new AtomicInteger(0);

    private RouteRuntimeImpl(BrowserContext context, RouteV2Config config,
                             ConcurrentMap<BrowserContext, RouteRuntime> ownerRegistry) {
        this.context = context;
        this.config = config;
        this.ownerRegistry = ownerRegistry;
        this.pendingGuard = new PendingGuard(config.maxPending());
        this.claims = new ClaimRegistry(pendingGuard, Duration.ofSeconds(config.ioAwaitTimeoutSeconds()));
        this.ops = new BoundedOps(config.opsMaxConcurrent(), Duration.ofMillis(500));
        String tag = String.valueOf(contextSeq.incrementAndGet());
        this.io = new RouteIoExecutor(tag, config.ioThreads(), config.ioQueueCapacity());
        this.monitor = new MonitorSink(io);
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
        context.onClose(ignored -> close());
        // 刻意【不订阅】context.onResponse：该订阅是驱动侧 "Object doesn't exist: response@…" 竞态的
        // 唯一触发源——Playwright 在 BrowserContextImpl.handleEvent 的 "response" 分支会为每个事件句柄
        // 调用 Connection.getExistingObject(guid)，句柄一旦被服务端回收即抛异常；异常发生在业务 lambda
        // 之前无法被拦截，且因 Connection 为共享单连接，会以「此刻在等结果的任意调用线程」为宿主抛出
        // （实测：会话校验导航被炸 → 误判会话失效 → 删缓存 → 每轮全量重登）。
        // 响应侧观测改走 route 通道：recordObservation 持有 Request，轮询 existingResponse()（本地字段）。
        LOGGER.info("[RouteV2] runtime installed for context @{} (maxPending={}, ioThreads={})",
                System.identityHashCode(context), config.maxPending(), config.ioThreads());
        // 临时诊断看门狗（与 web HangWatchdog 同源，待去除）：按本 runtime 生命周期武装
        this.hangWatchdog = new HangWatchdog("ctx@" + System.identityHashCode(context));
        this.hangWatchdog.arm();
        LOGGER.info("[RouteV2] HangWatchdog armed (diag, temporary) for context @{}", System.identityHashCode(context));
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
     * <p><b>线程模型</b>：经 {@link CompletableFuture#delayedExecutor}（daemon 公共池）递归调度，
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
        CompletableFuture.delayedExecutor(RESPONSE_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .execute(() -> scheduleResponsePoll(request, spec, monitorPending, capturePending, deadlineNs));
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
                LOGGER.warn("[RouteV2] monitor onResponse failed for pattern='{}': {}", spec.pattern(), t.toString());
            }
        }
        if (capturePending) {
            try {
                capture.onResponseForSpec(spec, response);
            } catch (Throwable t) {
                LOGGER.warn("[RouteV2] capture onResponse failed for pattern='{}': {}", spec.pattern(), t.toString());
            }
        }
    }

    /** 安装运行时（静态工厂，由 RouteRuntimeFactory / DefaultRouteRuntimeFactory 调用）。 */
    static RouteRuntime install(BrowserContext context, RouteV2Config config,
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

    /** 标记降级（注册失败触发）；仅本实现内部调用。 */
    void markDegraded() {
        if (degraded.compareAndSet(false, true)) {
            LOGGER.warn("[RouteV2] runtime degraded for context @{} (registration failed; avoid reusing this context)",
                    System.identityHashCode(context));
        }
    }

    @Override
    public void recordObservation(Request request, ApiSpec spec) {
        boolean monitorPending = monitor.recordRequest(request, spec);
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
    public List<RouteV2AssertionFailure> drainSettledAssertionFailures() {
        List<RouteV2AssertionFailure> result = new ArrayList<>();
        for (CapturedExchange e : monitor.drainSettledFailures()) {
            result.add(new RouteV2AssertionFailure(
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
            LOGGER.debug("[RouteV2] registered pattern='{}' capability={}", spec.pattern(), spec.capability());
            return () -> {
                if (binders.remove(spec.pattern(), binder)) {
                    binder.close();
                }
            };
        } catch (RuntimeException e) {
            markDegraded();
            throw e;
        }
    }

    @Override
    public boolean stop(RouteCapability capability, String pattern) {
        if (closed.get()) {
            return false;
        }
        // 读-改-写必须原子：CAS 失败即重读最新代重试，保证并发 stop/register 线性化
        while (true) {
            RuleGeneration gen = generations.snapshot();
            ApiSpec current = gen.specFor(pattern);
            if (current == null || current.isStopped(capability)) {
                return false;
            }
            ApiSpec next = current.withStopped(capability);
            RuleGeneration nextGen = RuleGeneration.next(gen, gen.mergeInto(pattern, next));
            if (generations.compareAndSet(gen, nextGen)) {
                LOGGER.debug("[RouteV2] stopped capability={} for pattern='{}' (gen {})",
                        capability, pattern, nextGen.generation());
                return true;
            }
            // 其它线程已发布新代 → 重试
        }
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
                LOGGER.trace("[RouteV2] fallback on closed context ignored: {}", ignored.toString());
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
            LOGGER.warn("[RouteV2] sweep failed: {}", e.toString());
        }
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
        LOGGER.info("[RouteV2] closing runtime for context @{}", System.identityHashCode(context));
        sweeper.shutdownNow();
        io.close();
        claims.closeAll();
        for (PatternBinder binder : binders.values()) {
            binder.close();
        }
        binders.clear();
        if (ownerRegistry != null) {
            ownerRegistry.remove(context, this);
        }
    }

    @Override
    public RouteV2Metrics metrics() {
        return new RouteV2Metrics(
                generations.generation(),
                claims.size(),
                claims.pendingCount(),
                ops.acceptedCount(),
                ops.rejectedCount(),
                io.rejectedCount(),
                monitor.size(),
                capture.size(),
                capture.droppedCount());
    }
}
