package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 采集引擎 — 编排整个采集管道的生命周期。
 *
 * <p>职责：
 * <ul>
 *   <li>创建并启动 {@link CaptureRingBuffer}、{@link CaptureThreadPool}、{@link EventMerger}</li>
 *   <li>自动选择采集策略（CDP → Playwright 事件退化）</li>
 *   <li>暴露健康检查和运行时指标</li>
 *   <li>与 {@link RouteEngine} 集成，接收 MOCK/MODIFY 事件</li>
 * </ul>
 *
 * <p>生命周期：init → start → (running) → stop → shutdown
 */
public class CaptureEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureEngine.class);

    /** 健康状态 */
    public enum Health {
        /** 正常运行 */
        HEALTHY,
        /** 降级运行（如 CDP 不可用，退化到 Playwright 事件） */
        DEGRADED,
        /** 消费者线程卡死（RingBuffer 堆积超过阈值） */
        STALLED,
        /** 不可恢复错误 */
        FAILED
    }

    /** RingBuffer 堆积告警阈值 */
    private static final long STALL_THRESHOLD = 5000;

    /** 关闭兜底超时；正常路径由 EventMerger 的退出信号驱动，不会固定等待。 */
    private static final long SHUTDOWN_TIMEOUT_MS =
            Long.getLong("route.capture.shutdown-timeout-ms", 1000L);

    private final CaptureRingBuffer ringBuffer;
    private final CaptureThreadPool threadPool;
    private final EventMerger merger;
    /** 由线程池管理的 merger 任务句柄，用于生命周期观测和兜底取消。 */
    private volatile Future<?> mergerFuture;
    private volatile CaptureStrategy strategy;      // 非 final：降级时替换
    private final Page page;
    private final BrowserContext browserContext;
    private volatile String strategyName;            // 非 final：降级时同步更新

    private volatile Health health = Health.HEALTHY;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 创建采集引擎并自动启动。
     *
     * @param page  Playwright Page 实例
     * @throws IllegalArgumentException 如果 page 为 null
     * @throws IllegalStateException 如果所有策略均不可用
     */
    CaptureEngine(Page page) {
        if (page == null) {
            throw new IllegalArgumentException("Page must not be null");
        }
        this.page = page;
        this.browserContext = page.context();
        this.ringBuffer = new CaptureRingBuffer();
        this.threadPool = new CaptureThreadPool();

        // 选择采集策略：优先 CDP，退化到 Playwright 事件
        this.strategy = selectStrategy(page);
        this.strategyName = strategy.name();

        // 创建 EventMerger（不再需要 page 参数；body 由 BodyReader 在 bodyFetchPool 线程按需异步读取）
        this.merger = new EventMerger(ringBuffer, threadPool);

        // 启动（异常安全：失败时立即清理已分配的资源）
        try {
            start();
        } catch (Exception e) {
            LOGGER.error("[CaptureEngine] Failed to start, shutting down. Strategy={}", strategyName, e);
            shutdown();
            throw new IllegalStateException("CaptureEngine failed to start: " + e.getMessage(), e);
        }
    }

    // ── 生命周期 ──

    private void start() {
        // 1. 启动策略（订阅浏览器事件）
        strategy.start(page, ringBuffer);

        // 2. 检查策略是否可用，不可用时降级并替换引用
        if (!strategy.isAvailable()) {
            LOGGER.warn("[CaptureEngine] Strategy '{}' not available, falling back to PlaywrightEventCaptureStrategy",
                    strategyName);
            CaptureStrategy fallback = new PlaywrightEventCaptureStrategy();
            fallback.start(page, ringBuffer);
            if (!fallback.isAvailable()) {
                LOGGER.error("[CaptureEngine] Fallback strategy also unavailable. "
                        + "Capture will be disabled.");
                fallback.stop();
                strategy.stop();
                health = Health.FAILED;
                return;
            }
            // ★ 必须替换引用，否则 stop() 不会停止 fallback（导致线程泄漏）
            // 直接修改 final 字段在 Java 中不可行，因此通过内部 setter 实现
            setStrategy(fallback);
        }

        // ⭐ 绑定 body 读取能力：策略选定/降级完成后，BodyReader 才能在 bodyFetchPool
        //   线程按需拉取响应体（策略只发布轻量 BODY_READY 信号）。
        merger.bindBodyReader(strategy);

        // 3. 启动 merger 消费者线程
        this.mergerFuture = threadPool.submitMerger(merger);

        this.running.set(true);
        LOGGER.info("[CaptureEngine] Started with strategy '{}', ringBuffer cap={}",
                strategyName, ringBuffer.capacity());

        // 记录降级警告
        if (!strategy.isAvailable() || strategy instanceof PlaywrightEventCaptureStrategy) {
            LOGGER.warn("[CaptureEngine] Running in DEGRADED mode (Playwright event capture). "
                    + "CDP was not available. Consider using Chromium for full capture fidelity.");
            health = Health.DEGRADED;
        }
    }

    /**
     * 替换策略引用（降级时替换，volatile 保证线程安全）。
     * 先停止旧策略，再替换引用，避免 stop() 时重复停止。
     */
    private void setStrategy(CaptureStrategy newStrategy) {
        CaptureStrategy old = this.strategy;
        if (old != null && old != newStrategy) {
            old.stop();
        }
        this.strategy = newStrategy;
        this.strategyName = newStrategy.name();
    }

    /**
     * 停止采集引擎。
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) return;

        // 1. 停止策略
        strategy.stop();

        // 2. 先中断 merger，仅给正常排空一个短窗口，避免每个 case 固定等待 5 秒。
        boolean mergerStopped = merger.stop(SHUTDOWN_TIMEOUT_MS);
        if (!mergerStopped) {
            Future<?> task = mergerFuture;
            if (task != null) task.cancel(true);
            LOGGER.warn("[CaptureEngine] Merger did not stop within {}ms; task cancellation requested",
                    SHUTDOWN_TIMEOUT_MS);
        }
        mergerFuture = null;

        LOGGER.info("[CaptureEngine] Stopped. Metrics: {}", metrics().toSummary());
    }

    /**
     * 关闭采集引擎并释放资源。
     */
    public void shutdown() {
        stop();
        threadPool.shutdown(SHUTDOWN_TIMEOUT_MS);
        LOGGER.info("[CaptureEngine] Shutdown complete");
    }

    // ── 指标 ──

    /**
     * 获取当前运行时指标快照。
     */
    public CaptureMetrics metrics() {
        // 检查是否 stall
        long pending = ringBuffer.pending();
        if (pending > STALL_THRESHOLD && health == Health.HEALTHY) {
            health = Health.STALLED;
            LOGGER.warn("[CaptureEngine] RingBuffer pending {} > stall threshold {}. "
                            + "Consumer may be blocked or too slow.",
                    pending, STALL_THRESHOLD);
        } else if (pending <= STALL_THRESHOLD && health == Health.STALLED) {
            health = Health.HEALTHY;
        }

        return new CaptureMetrics(
                ringBuffer.capacity(),
                pending, ringBuffer.droppedCount(),
                ringBuffer.totalPublished(),
                merger.completedCalls(), merger.failedMerges(),
                merger.staleSlots(), 0L, 0L,
                threadPool.mergerActiveCount(), threadPool.mergerQueueSize(),
                threadPool.mergerCompletedCount(),
                health
        );
    }

    /**
     * 获取健康状态。
     */
    public Health health() {
        return health;
    }

    /** 采集策略名称 */
    public String strategyName() {
        return strategyName;
    }

    /** 是否在运行 */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 当前采集策略是否能为旁路事件链提供响应体。
     *
     * <p>仅 CDP 旁路具备 body 能力（已确认能经 {@code NetworkResponse.getResponseBody} 取回 body）。
     * Playwright 事件策略不提供 body，此时 {@link MonitorHandler} 必须回退到既有
     * {@code page.waitForResponse} 同步路径，不能由事件链接管 body 读取，否则断言会退化。
     */
    public boolean providesResponseBody() {
        return running.get() && strategy != null && strategy.providesResponseBody();
    }

    /**
     * 阻塞等待匹配 URL 的事件链终态交换（旁路诊断/接管用）。
     *
     * <p>仅用于 {@link MonitorHandler} 在 {@link #providesResponseBody()} 为真时的可选接管路径；
     * 调用方必须自行处理超时与缺失回退，禁止依赖此路径保证采集成功。
     */
    public NetworkExchange waitForExchange(String requestUrl, long timeoutMs) {
        if (!running.get()) return null;
        return merger.waitForExchange(requestUrl, timeoutMs);
    }

    /**
     * 最近的事件链终态快照，仅用于迁移诊断；不代表或替代既有 API 捕获结果。
     */
    public java.util.List<NetworkExchange> recentExchanges() {
        return merger.recentExchanges();
    }

    /** 事件链终态与响应体能力诊断指标；不替代既有 CaptureMetrics。 */
    public NetworkExchangeMetrics exchangeMetrics() {
        NetworkEventCorrelator correlator = (strategy instanceof PlaywrightEventCaptureStrategy)
                ? ((PlaywrightEventCaptureStrategy) strategy).getCorrelator() : null;
        return merger.exchangeMetrics(correlator);
    }

    // ── Route 集成 ──

    /**
     * 接收来自 RouteHandler 的 MOCK/MODIFY 事件。
     *
     * <p>由 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine}
     * 在处理 MOCK/MODIFY 路由时调用，将事件投喂到 RingBuffer。
     */
    public void feedRouteEvent(CaptureEvent event) {
        if (!running.get()) return;
        ringBuffer.publish(event.withContext(browserContext, page));
    }

    public BrowserContext browserContext() {
        return browserContext;
    }

    public Page page() {
        return page;
    }

    // ── 内部 ──

    /**
     * 选择采集策略：优先 CDP，退化到 Playwright 事件。
     *
     * <p>不再用 test session 探测 CDP 可用性，而是直接创建 CDPCaptureStrategy，
     * 其 {@link CaptureStrategy#start(Page, CaptureRingBuffer)} 方法内部会捕获
     * CDP 异常并返回 false，由本方法据此降级。
     */
    private static CaptureStrategy selectStrategy(Page page) {
        // ⭐ 采集策略交由工厂按浏览器内核显式分发：
        //    chromium → CDP（保真度最高）；firefox/webkit → Playwright 事件（CDP 不可靠）；
        //    未知 → 安全默认 Playwright 事件。避免「非 Chromium 上 CDP 静默产出残缺数据」
        //    的企业级假正常风险。
        return CaptureStrategyFactory.create(page);
    }
}