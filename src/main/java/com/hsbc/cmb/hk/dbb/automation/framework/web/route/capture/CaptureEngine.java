package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private final CaptureRingBuffer ringBuffer;
    private final CaptureThreadPool threadPool;
    private final EventMerger merger;
    private volatile CaptureStrategy strategy;      // 非 final：降级时替换
    private final Page page;
    private volatile String strategyName;            // 非 final：降级时同步更新

    private volatile Health health = Health.HEALTHY;
    private volatile boolean running;

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
        this.ringBuffer = new CaptureRingBuffer();
        this.threadPool = new CaptureThreadPool();

        // 选择采集策略：优先 CDP，退化到 Playwright 事件
        this.strategy = selectStrategy(page);
        this.strategyName = strategy.name();

        // 创建 EventMerger（不再需要 page 参数，body 由策略直接读取）
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
                health = Health.FAILED;
                return;
            }
            // ★ 必须替换引用，否则 stop() 不会停止 fallback（导致线程泄漏）
            // 直接修改 final 字段在 Java 中不可行，因此通过内部 setter 实现
            setStrategy(fallback);
        }

        // 3. 启动 merger 消费者线程
        threadPool.submitMerger(merger);

        this.running = true;
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
        if (!running) return;
        this.running = false;

        // 1. 停止策略
        strategy.stop();

        // 2. 停止 merger
        merger.stop();

        LOGGER.info("[CaptureEngine] Stopped. Metrics: {}", metrics().toSummary());
    }

    /**
     * 关闭采集引擎并释放资源。
     */
    public void shutdown() {
        stop();
        threadPool.shutdown(5000);
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
        return running;
    }

    // ── Route 集成 ──

    /**
     * 接收来自 RouteHandler 的 MOCK/MODIFY 事件。
     *
     * <p>由 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine}
     * 在处理 MOCK/MODIFY 路由时调用，将事件投喂到 RingBuffer。
     */
    public void feedRouteEvent(CaptureEvent event) {
        if (!running) return;
        ringBuffer.publish(event);
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
        // 先尝试 CDP（不创建 test session 探测，避免泄漏）
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[CaptureEngine] Attempting CDPCaptureStrategy...");
        return new CDPCaptureStrategy();
    }
}