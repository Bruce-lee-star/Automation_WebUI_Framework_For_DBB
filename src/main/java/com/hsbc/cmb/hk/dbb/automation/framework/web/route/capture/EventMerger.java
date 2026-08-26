package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor.BodyAvailability;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 事件合并器 — 按 requestId 将多个 Phase 事件合并为一个 {@link CapturedApiCall}。
 *
 * <p>核心职责：
 * <ul>
 *   <li>从 {@link CaptureRingBuffer} 拉取事件，放入 {@link MergingSlot} 按 requestId 分组</li>
 *   <li>当 slot 完整（REQUEST + RESPONSE_META + RESPONSE_BODY / MOCK_FULL / FETCH_REQUEST+FETCH_RESPONSE）时，
 *       合成 {@link CapturedApiCall} 存入 {@link ApiCaptureContext}</li>
 *   <li>响应体由 {@link BodyReader} 按需惰性异步读取（bodyFetchPool 线程，绝不重入浏览器事件线程）：
 *       策略只发布轻量 BODY_READY 信号，命中采集范围后由 BodyReader 拉取并投喂 RESPONSE_BODY 闭合 slot</li>
 *   <li>定期清理超时未完成的 stale slot</li>
 * </ul>
 */
public class EventMerger implements Runnable {
    /** 单响应 body 采集/持久化的统一上限（MonitorResultRecorder 亦引用此常量，勿复制）。 */
    public static final int MAX_CAPTURE_BODY_BYTES = 5 * 1024 * 1024;

    private static final Logger LOGGER = LoggerFactory.getLogger(EventMerger.class);

    /** Slot 超时时间（毫秒），超过此时间未完成的 slot 被视为 stale。
     *  调小以便在 awaitCompletion(10s) 内清理偶发事件丢失导致的不完整 slot，释放 captureInFlight 计数。 */
    static final long SLOT_TIMEOUT_MS = 3_000;
    /** ⭐ P1-5: Hard 超时 = Soft × 3，真正销毁 slot 的最后防线（防内存泄漏）。不配置化、不复用 RouteRule.timeout。 */
    private static final long SLOT_HARD_TIMEOUT_MS = SLOT_TIMEOUT_MS * 3;

    /** 清理间隔（毫秒） */
    private static final long CLEANUP_INTERVAL_MS = 1_000;

    /** ⭐ 异步 body 拉取在途的放宽超时（毫秒）：BODY_READY 已到但 BodyReader 尚未投喂完成时，
     *   该 slot 不受 SLOT_TIMEOUT_MS 清理约束，避免拉取进行中被误清。 */
    private static final long BODY_FETCH_TIMEOUT_MS = 15_000;

    /** ⭐ Bug A 兜底延迟（毫秒）：RESPONSE_META 后若迟迟未收到 BODY_READY（CDP loadingFinished
     *   对慢响应体可能延迟甚至缺失），在此时长后主动触发 body 拉取，避免 slot 永不闭合。 */
    private static final long BODY_FALLBACK_DELAY_MS = 300;

    private final CaptureRingBuffer ringBuffer;
    private final CaptureThreadPool threadPool;
    /** ⭐ 按需异步 body 读取器（策略只发 BODY_READY 信号，由它拉取并投喂 RESPONSE_BODY）。 */
    private final BodyReader bodyReader;
    private final Map<String, MergingSlot> slots = new ConcurrentHashMap<>();
    private final AtomicLong completedCalls = new AtomicLong(0);
    private final AtomicLong failedMerges = new AtomicLong(0);
    private final AtomicLong staleSlots = new AtomicLong(0);
    /** ⭐ P1-5: Soft 超时计数 —— 仅标记 body 不可用、slot 保留，给迟到事件补全机会。 */
    private final AtomicLong softTimeouts = new AtomicLong(0);
    /** ⭐ P1-5: Hard 超时计数 —— slot 真正销毁、释放计数（泄露/事件丢失信号）。 */
    private final AtomicLong hardTimeouts = new AtomicLong(0);
    /** 仅供迁移诊断读取：有界、不可变终态，不触发任何 Monitor 副作用。 */
    private static final int MAX_RECENT_EXCHANGES = 256;
    private final Object exchangeLock = new Object();
    private final Deque<NetworkExchange> recentExchanges = new ArrayDeque<>(MAX_RECENT_EXCHANGES);

    private volatile boolean running;
    /** ⭐ 定时清理任务的引用（AtomicReference 保证 merger 线程写 / stop 线程读的可见性），用于 stop() 时取消 */
    private final AtomicReference<ScheduledFuture<?>> cleanupFutureRef = new AtomicReference<>();
    /** 合并线程引用，用于 stop() 时中断并带超时 join，避免线程泄漏 */
    private final AtomicReference<Thread> mergerThread = new AtomicReference<>();
    /** 合并线程真正完成排空并退出的信号，正常关闭不依赖固定等待。 */
    private final CountDownLatch stopped = new CountDownLatch(1);

    /**
     * @param ringBuffer 事件缓冲区
     * @param threadPool 专用线程池（用于定时清理）
     */
    public EventMerger(CaptureRingBuffer ringBuffer, CaptureThreadPool threadPool) {
        this.ringBuffer = ringBuffer;
        this.threadPool = threadPool;
        this.bodyReader = new BodyReader(ringBuffer, threadPool);
    }

    /** ⭐ 绑定当前策略（CaptureEngine 在策略选定/降级完成后调用），供 BodyReader 按需读取响应体。 */
    public void bindBodyReader(CaptureStrategy strategy) {
        bodyReader.bind(strategy);
    }

    @Override
    public void run() {
        this.running = true;
        mergerThread.set(Thread.currentThread());
        // ⭐ P1: 注册为 RingBuffer 消费者——publish 时立即 unpark 唤醒，
        //   消除固定轮询（poll(500)）带来的消费延迟，缩小"事件已到但未合并"的竞态窗口。
        ringBuffer.setConsumer(Thread.currentThread());
        LOGGER.info("[EventMerger] Started merger loop");

        // ⭐ 启动定时清理并保存引用，用于 stop() 时取消
        cleanupFutureRef.set(threadPool.scheduleCleanup(this::cleanupStaleSlots, CLEANUP_INTERVAL_MS));

        try {
            while (running && !Thread.interrupted()) {
                try {
                    CaptureEvent event = ringBuffer.poll(500);
                    if (event != null) {
                        merge(event);
                    }
                } catch (Exception e) {
                    LOGGER.error("[EventMerger] Error in merger loop: {}", e.getMessage(), e);
                }
            }
        } finally {
            // ⭐ 线程退出时排空缓冲区，消费所有剩余事件
            LOGGER.info("[EventMerger] Merger loop exiting, draining remaining events...");
            drainRemaining();
            LOGGER.info("[EventMerger] Merger loop exited, processed {} calls, {} failures, {} stale",
                    completedCalls.get(), failedMerges.get(), staleSlots.get());
            stopped.countDown();
        }
    }

    /** 排空剩余事件并合并 */
    private void drainRemaining() {
        java.util.List<CaptureEvent> remaining = ringBuffer.drain();
        if (!remaining.isEmpty()) {
            LOGGER.info("[EventMerger] Draining {} remaining events", remaining.size());
            for (CaptureEvent event : remaining) {
                try {
                    merge(event);
                } catch (Exception e) {
                    LOGGER.error("[EventMerger] Error draining event: {}", e.getMessage());
                }
            }
        }
        // ⭐ 排空后残留的未完整 slot：其 captureInFlight 计数需释放，
        // 否则 merger 停止后计数永久残留，导致后续 awaitCompletion 一直等到超时。
        if (!slots.isEmpty()) {
            for (MergingSlot slot : slots.values()) {
                if (slot.inFlightCounted.compareAndSet(true, false)) {
                    decrement(slot);
                }
            }
            slots.clear();
        }
    }

    /** 停止合并器 */
    public void stop() {
        this.running = false;
        // ⭐ 取消定时清理任务，防止泄漏（AtomicReference 保证跨线程可见）
        ScheduledFuture<?> f = cleanupFutureRef.get();
        if (f != null && !f.isCancelled() && !f.isDone()) {
            f.cancel(false);
        }
        // ⭐ 中断合并线程，使其在 poll(500) 返回后尽快退出，避免 stop 后线程残留
        Thread t = mergerThread.get();
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * 带超时的停止：中断并等待线程退出，防止关闭流程挂起或线程泄漏。
     *
     * @param timeoutMs 最大等待毫秒数
     * @return 线程是否在超时前退出
     */
    public boolean stop(long timeoutMs) {
        stop();
        if (Thread.currentThread() == mergerThread.get()) return true;
        try {
            return stopped.await(Math.max(1L, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ── 指标 ──

    public long completedCalls() { return completedCalls.get(); }
    public long failedMerges() { return failedMerges.get(); }
    public long staleSlots() { return staleSlots.get(); }
    public long softTimeouts() { return softTimeouts.get(); }
    public long hardTimeouts() { return hardTimeouts.get(); }

    /** 返回最近的不可变终态快照副本；仅用于迁移诊断。 */
    public List<NetworkExchange> recentExchanges() {
        synchronized (exchangeLock) {
            return List.copyOf(recentExchanges);
        }
    }

    /**
     * 阻塞等待匹配 URL 的终态交换（事件链接管用）。
     *
     * <p>接管发生在响应完成后，交换通常已写入有界缓存。此处使用条件等待
     * （wait/notify）而非忙轮询：recordExchange 写入终态缓存后 notifyAll
     * 精确唤醒，等待线程处于 parked 状态不消耗 CPU。
     *
     * <p>命中即返回，超时返回 null。调用方（MonitorHandler）必须处理 null 回退。
     */
    public NetworkExchange waitForExchange(String requestUrl, long timeoutMs) {
        if (requestUrl == null || timeoutMs <= 0) return null;
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (exchangeLock) {
            NetworkExchange hit = findRecentByUrlLocked(requestUrl);
            if (hit != null) return hit;
            while (System.currentTimeMillis() < deadline) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return null;
                try {
                    exchangeLock.wait(remaining);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                hit = findRecentByUrlLocked(requestUrl);
                if (hit != null) return hit;
            }
            return null;
        }
    }

    /** 必须在持有 exchangeLock 时调用：从最新向旧查找匹配 URL 的终态交换。 */
    private NetworkExchange findRecentByUrlLocked(String requestUrl) {
        NetworkExchange found = null;
        for (NetworkExchange ex : recentExchanges) {
            String u = ex.url();
            if (u != null && (u.equals(requestUrl) || u.contains(requestUrl) || requestUrl.contains(u))) {
                found = ex;
            }
        }
        return found;
    }

    /** 基于有界终态缓存计算的旁路迁移诊断指标。 */
    public NetworkExchangeMetrics exchangeMetrics() {
        return exchangeMetrics(null);
    }

    /**
     * 旁路迁移诊断指标；可传入 {@link NetworkEventCorrelator} 以暴露其驱逐 / 缺失完成计数。
     */
    public NetworkExchangeMetrics exchangeMetrics(NetworkEventCorrelator correlator) {
        long terminal = 0;
        long networkFailures = 0;
        long correlationTimeouts = 0;
        long available = 0;
        long empty = 0;
        long notRequested = 0;
        long unavailable = 0;
        synchronized (exchangeLock) {
            for (NetworkExchange exchange : recentExchanges) {
                terminal++;
                if (exchange.failure() != null) {
                    if (exchange.failure().kind() == NetworkExchange.FailureKind.NETWORK_FAILED) networkFailures++;
                    if (exchange.failure().kind() == NetworkExchange.FailureKind.CORRELATION_TIMEOUT) correlationTimeouts++;
                }
                switch (exchange.bodyAvailability()) {
                    case AVAILABLE -> available++;
                    case EMPTY -> empty++;
                    case NOT_REQUESTED -> notRequested++;
                    case UNAVAILABLE -> unavailable++;
                }
            }
        }
        // ⭐ P2: 暴露 NetworkEventCorrelator 的驱逐 / 缺失完成计数，提升旁路可观测性
        long evicted = 0;
        long missingFinish = 0;
        if (correlator != null) {
            evicted = correlator.evictedCount();
            missingFinish = correlator.missingFinishCount();
        }
        return new NetworkExchangeMetrics(terminal, networkFailures, correlationTimeouts,
                available, empty, notRequested, unavailable, evicted, missingFinish);
    }

    // ── 合并逻辑 ──

    /**
     * 将单个事件合并到对应的 MergingSlot 中。
     * 如果 slot 已完整，则合成 CapturedApiCall 并清理 slot。
     */
    private void merge(CaptureEvent event) {
        // ⭐ 过滤页面资源类请求（document/script/style/image/font/media/websocket/manifest）：
        //   capture 目录只采集「API 调用」，页面资源请求不应写入 CapturedApiCall，
        //   也不应计入 captureInFlight —— 否则 page.navigate 加载页面时，
        //   这些资源请求的 slot 若未完整完成，会残留计数导致 awaitCompletion 一直超时。
        // ⭐ 只存「命中已注册规则」的 API（health check / 动态新接口不存）。
        //    ⭐ 性能优化：resolveEndpointIfCovered 一次遍历同时完成「是否覆盖」与「endpoint 解析」，
        //    endpoint 存于局部变量，供下方 switch REQUEST 分支缓存到 slot，buildApiCall 复用。
        //    每请求只对规则集遍历<b>一次</b>（原先「过滤 + buildApiCall」各遍历一次 → 双重遍历）。
        String coveredEndpoint = null;
        if (event.phase == CaptureEvent.Phase.REQUEST || event.phase == CaptureEvent.Phase.FETCH_REQUEST) {
            if (!isApiRequest(event)) {
                return;
            }
            coveredEndpoint = RouteEngine.resolveEndpointIfCovered(event.url, event.page);
            // 没有当前 Page/Context 规则时保留兼容行为；存在规则才过滤未覆盖请求。
            if (coveredEndpoint == null && RouteEngine.hasCaptureRules(event.page)) {
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[EventMerger] Skip uncovered API (no matching rule): url='{}'", event.url);
                return;
            }
        }
        // ⭐ BODY_READY 是轻量信号：仅当该请求已被登记为待采集（REQUEST 已到达且未被过滤）时才触发
        //   按需 body 拉取；否则直接忽略，不创建孤儿 slot。
        if (event.phase == CaptureEvent.Phase.BODY_READY) {
            MergingSlot existing = slots.get(event.requestId);
            if (existing == null || existing.request == null || existing.completed) {
                return;
            }
        }
        MergingSlot slot = slots.computeIfAbsent(event.requestId, k -> new MergingSlot(event.requestId));

        switch (event.phase) {
            case REQUEST:
                slot.request = event;
                // ⭐ 缓存 endpoint（已在上方一次遍历算出，直接存入，buildApiCall 复用）
                if (slot.endpoint == null && coveredEndpoint != null) {
                    slot.endpoint = coveredEndpoint;
                }
                break;
            case RESPONSE_META:
                slot.responseMeta = event;
                // ⭐ Bug A 兜底：CDP Network.loadingFinished（BODY_READY 信号）对慢响应体可能延迟甚至缺失
                //   （诊断：eventCounts 只有 requestWillBeSent/responseReceived，无 loadingFinished，
                //   slot 因缺 body 信号被 stale 清理，completed=0）。在 RESPONSE_META 后延迟触发一次检查：
                //   若届时 body 信号仍未到，直接触发 BodyReader 拉取（其内部带重试），保证 slot 能闭合入库存档。
                scheduleBodyFallback(event.requestId, event.source);
                break;
            case BODY_READY:
                // ⭐ 响应体已就绪：标记异步拉取在途（放宽 stale 清理），并在 bodyFetchPool 线程按需读取。
                //   BodyReader 无论成败都会投喂 RESPONSE_BODY 闭合 slot，因此不会拖到 stale 超时。
                slot.bodyFetchPending = true;
                slot.bodyFetchRequestedAt = System.currentTimeMillis();
                // ⭐ 与 RESPONSE_META 延迟兜底互斥：仅当尚未被兜底/前序信号触发时才发起拉取，避免重复拉取
                if (slot.bodyFetchTriggered.compareAndSet(false, true)) {
                    bodyReader.requestBody(event.requestId, event.source);
                }
                break;
            case RESPONSE_BODY:
                slot.bodyReady = true;
                slot.bodyFetchPending = false;
                slot.originalResponseBodyBytes = event.respBody != null ? event.respBody.length : 0;
                slot.responseBody = limitBody(event.respBody);  // 限制单响应内存占用
                break;
            case MOCK_FULL:
                slot.mockFull = event;
                slot.completed = true;
                break;
            case FETCH_REQUEST:
                slot.fetchRequest = event;
                break;
            case FETCH_RESPONSE:
                slot.fetchResponse = event;
                slot.completed = true;
                break;
            case FAILED:
                // 请求被 abort / 超时 / 网络错误：立即结束 slot，仅释放计数，不存储伪造调用。
                slot.failed = true;
                slot.completed = true;
                break;
        }

        // ⭐ lastActivity 作为 volatile 写屏障放在所有字段写入之后：
        //   cleanup 线程（slots.removeIf）读 lastActivity 时能同时看到 request/responseMeta/
        //   bodyReady 等写入，避免读到旧值把在途 slot 提前清理。
        slot.lastActivity = System.currentTimeMillis();

        // ⭐ REQUEST / FETCH_REQUEST 代表一个"在途请求"已开始（将在响应到达后合并写出）。
        // 计入 captureInFlight，使 awaitCompletion 也能覆盖纯采集管道在途请求。
        if ((event.phase == CaptureEvent.Phase.REQUEST || event.phase == CaptureEvent.Phase.FETCH_REQUEST)
                && slot.inFlightCounted.compareAndSet(false, true)) {
            slot.owner = contextFor(event);
            slot.owner.incrementCaptureInFlight();
        }

        // 检查是否完整
        if (isSlotComplete(slot)) {
            slot.completed = true;
            completeSlot(slot);
            slots.remove(slot.requestId);
        }
    }

    /**
     * ⭐ Bug A 兜底：CDP Network.loadingFinished（BODY_READY 信号）可能延迟甚至缺失。
     * RESPONSE_META 后延迟触发一次检查：若届时该 slot 仍缺 body 信号（未 bodyReady 且未在拉取），
     * 直接发起 body 拉取（BodyReader 内部带重试），避免 slot 因缺失 BODY_READY 永远无法闭合而被 stale 清理。
     * 注意：任务运行于 cleanupPool 线程，只通过 volatile 字段 + AtomicBoolean CAS 与 merger 线程协作。
     */
    private void scheduleBodyFallback(String requestId, CaptureEvent.Source source) {
        try {
            threadPool.scheduleOnce(() -> {
                MergingSlot s = slots.get(requestId);
                if (s == null || s.completed || s.request == null) return;
                // 正常信号已到（bodyReady）或已在拉取中（bodyFetchPending）→ 无需兜底
                if (s.bodyReady || s.bodyFetchPending) return;
                s.bodyFetchPending = true;
                s.bodyFetchRequestedAt = System.currentTimeMillis();
                // CAS 互斥：若 BODY_READY 恰在兜底执行时到达，由 CAS 保证只发起一次拉取
                if (s.bodyFetchTriggered.compareAndSet(false, true)) {
                    LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                            "[EventMerger] Body fallback triggered for reqId={} (BODY_READY missing)", requestId);
                    bodyReader.requestBody(requestId, source);
                }
            }, BODY_FALLBACK_DELAY_MS);
        } catch (Exception e) {
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[EventMerger] Failed to schedule body fallback reqId={}: {}", requestId, e.getMessage());
        }
    }

    /** 判断 slot 是否完整 */
    private boolean isSlotComplete(MergingSlot slot) {
        if (slot.mockFull != null) return true;
        if (slot.fetchRequest != null && slot.fetchResponse != null) return true;
        if (slot.failed) return true;
        return slot.request != null && slot.responseMeta != null && slot.bodyReady;
    }

    /** 是否是需要采集的 API 类请求（XHR/Fetch/API 投喂/页面导航/DOCUMENT/OTHER 兜底），排除纯页面资源类请求 */
    private boolean isApiRequest(CaptureEvent event) {
        ResourceType rt = event.resourceType;
        if (rt == null) return true;   // 未知类型 → 保守保留
        switch (rt) {
            case XHR:
            case FETCH:
            case API:
            case DOCUMENT:
            case OTHER:
                return true;
            default:
                // SCRIPT / STYLESHEET / IMAGE / FONT / MEDIA / WEBSOCKET / MANIFEST 等纯资源 → 排除
                return false;
        }
    }

    /**
     * 将完整的 slot 合成为 CapturedApiCall 存入 ApiCaptureContext。
     */
    private static byte[] limitBody(byte[] body) {
        if (body == null || body.length <= MAX_CAPTURE_BODY_BYTES) return body;
        byte[] limited = new byte[MAX_CAPTURE_BODY_BYTES];
        System.arraycopy(body, 0, limited, 0, MAX_CAPTURE_BODY_BYTES);
        return limited;
    }

    private ApiCaptureContext slotContext(MergingSlot slot) {
        if (slot.owner != null) return slot.owner;
        CaptureEvent event = slot.request != null ? slot.request
                : slot.fetchRequest != null ? slot.fetchRequest
                : slot.mockFull;
        return contextFor(event);
    }

    /** 释放 slot 的采集在途计数，优先使用 +1 时锁定的 owner，避免错减到其它/已关闭实例 */
    private void decrement(MergingSlot slot) {
        ApiCaptureContext ctx = slot.owner != null ? slot.owner : slotContext(slot);
        ctx.decrementCaptureInFlight();
    }

    private ApiCaptureContext contextFor(CaptureEvent event) {
        return event != null && event.browserContext != null
                ? ApiCaptureContext.forContext(event.browserContext)
                : ApiCaptureContext.getCurrent();
    }

    private void completeSlot(MergingSlot slot) {
        try {
            recordExchange(slot, slot.failed
                    ? new NetworkExchange.Failure(NetworkExchange.FailureKind.NETWORK_FAILED, "network failed")
                    : null);
            // 异常终止（abort/超时/网络错误）只释放计数，不存储伪造调用。
            if (slot.failed) {
                return;
            }
            CapturedApiCall call = buildApiCall(slot);
            if (call != null) {
                // ⭐ 避免与 RouteHandler 同步存储重复：若该 URL 命中 MOCK/MONITOR（无 DELAY）
                // 规则，Handler 已同步 storeApiCall，此处 CDP 旁路跳过存储（仍释放计数）。
                if (!"MOCK".equalsIgnoreCase(call.captureSource())
                        && !"ROUTE".equalsIgnoreCase(call.captureSource())
                        && RouteEngine.isSyncStoredRuleForUrl(call.requestUrl(),
                        slot.request != null ? slot.request.page : null)) {
                    LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                            "[EventMerger] Skip CDP duplicate store for sync-stored rule url='{}'",
                            call.requestUrl());
                    return;
                }
                if (slot.originalResponseBodyBytes > MAX_CAPTURE_BODY_BYTES) {
                    call.markBodyTruncated(slot.originalResponseBodyBytes);
                }
                slotContext(slot).storeApiCall(call);
                completedCalls.incrementAndGet();
            }
        } catch (Exception e) {
            LOGGER.error("[EventMerger] Failed to complete slot reqId={}: {}", slot.requestId, e.getMessage());
            failedMerges.incrementAndGet();
        } finally {
            // ⭐ slot 结束（无论成功/失败）递减采集在途计数
            if (slot.inFlightCounted.compareAndSet(true, false)) {
                decrement(slot);
            }
        }
    }

    /**
     * 从 MergingSlot 构建 CapturedApiCall。
     */
    private CapturedApiCall buildApiCall(MergingSlot slot) {
        // ── MOCK：从 mockFull 构建 ──
        if (slot.mockFull != null) {
            CaptureEvent e = slot.mockFull;
            // 从 URL 中提取 endpoint（优先复用 REQUEST 阶段缓存的 endpoint，映射到已注册 urlPattern）
            String endpoint = slot.endpoint != null ? slot.endpoint : RouteEngine.resolveEndpointForUrl(e.url);
            return new CapturedApiCall.Builder()
                    .endpoint(endpoint)
                    .method(e.method != null ? e.method : "UNKNOWN")
                    .requestUrl(e.url)
                    .requestHeaders(e.reqHeaders)
                    .requestBody(e.reqBody != null ? new String(e.reqBody, StandardCharsets.UTF_8) : null)
                    .statusCode(e.status)
                    .responseHeaders(e.respHeaders)
                    .responseBody(e.respBody != null ? new String(e.respBody, StandardCharsets.UTF_8) : null)
                    .timestamp(e.timestamp)
                    .fromMock(true)
                    .captureSource("MOCK")
                    .resourceType(e.resourceType)
                    .build();
        }

        // ── MODIFY：从 fetchRequest + fetchResponse 构建 ──
        if (slot.fetchRequest != null && slot.fetchResponse != null) {
            CaptureEvent req = slot.fetchRequest;
            CaptureEvent resp = slot.fetchResponse;
            String endpoint = slot.endpoint != null ? slot.endpoint : RouteEngine.resolveEndpointForUrl(req.url);
            return new CapturedApiCall.Builder()
                    .endpoint(endpoint)
                    .method(req.method != null ? req.method : "UNKNOWN")
                    .requestUrl(req.url)
                    .requestHeaders(req.reqHeaders)
                    .requestBody(req.reqBody != null ? new String(req.reqBody, StandardCharsets.UTF_8) : null)
                    .statusCode(resp.status)
                    .responseHeaders(resp.respHeaders)
                    .responseBody(resp.respBody != null ? new String(resp.respBody, StandardCharsets.UTF_8) : null)
                    .timestamp(resp.timestamp)
                    .fromMock(false)
                    .captureSource("MODIFY")
                    .resourceType(req.resourceType)
                    .build();
        }

        // ── 普通请求：从 request + responseMeta 构建（body 已直接由策略采集） ──
        if (slot.request != null && slot.responseMeta != null) {
            CaptureEvent req = slot.request;
            CaptureEvent resp = slot.responseMeta;
            String endpoint = slot.endpoint != null ? slot.endpoint : RouteEngine.resolveEndpointForUrl(req.url);
            // ⭐ body 已由 BodyReader 在 bodyFetchPool 线程按需异步读取（策略只发 BODY_READY 信号）
            String bodyStr = slot.responseBody != null
                    ? new String(slot.responseBody, StandardCharsets.UTF_8)
                    : null;
            return new CapturedApiCall.Builder()
                    .endpoint(endpoint)
                    .method(req.method != null ? req.method : "UNKNOWN")
                    .requestUrl(req.url)
                    .requestHeaders(req.reqHeaders)
                    .requestBody(req.reqBody != null ? new String(req.reqBody, StandardCharsets.UTF_8) : null)
                    .statusCode(resp.status)
                    .responseHeaders(resp.respHeaders)
                    .responseBody(bodyStr)
                    .timestamp(resp.timestamp)
                    .fromMock(false)
                    .captureSource(slot.request.source == CaptureEvent.Source.CDP ? "CDP" : "PLAYWRIGHT")
                    .resourceType(req.resourceType)
                    .build();
        }

        return null;
    }

    /**
     * 清理超时未完成的 stale slot。
     */
    private void cleanupStaleSlots() {
        // ⭐ 守卫：stop() 已调用（或 FAILED）后，定时清理任务若已在 cleanupPool 中排队，
        //   不应继续无谓扫描（stop 只取消当前引用，已排队的任务会在 shutdown 前继续触发）。
        if (!running) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            slots.values().removeIf(slot -> {
                if (slot.completed) return false;
                long age = now - slot.lastActivity;
                // ⭐ 异步 body 拉取在途：放宽清理，等待 BodyReader 投喂 RESPONSE_BODY 闭合
                //   （BodyReader 保证无论成败都投喂，正常在 BODY_FETCH_TIMEOUT_MS 内闭合）。
                if (slot.bodyFetchPending && (now - slot.bodyFetchRequestedAt) < BODY_FETCH_TIMEOUT_MS) {
                    return false;
                }
                if (age > SLOT_HARD_TIMEOUT_MS) {
                    // ⭐ Hard 超时：真正销毁 slot（防内存泄漏的最后防线）。in-flight 已在 Soft 超时释放，
                    //   此处仅移除残留记录并暴露告警。
                    hardTimeouts.incrementAndGet();
                    staleSlots.incrementAndGet();
                    LoggingConfigUtil.logWarnIfVerbose(LOGGER,
                            "[EventMerger] Slot hard timeout, discarded reqId={}, phase={}, age={}ms",
                            slot.requestId, describeSlotPhase(slot), age);
                    recordExchange(slot, new NetworkExchange.Failure(
                            NetworkExchange.FailureKind.CORRELATION_TIMEOUT, describeSlotPhase(slot)));
                    return true;
                }
                if (age > SLOT_TIMEOUT_MS) {
                    // ⭐ Soft 超时：请求从采集管道角度已结束 → 释放 in-flight 计数（避免 awaitCompletion 被拖到 Hard），
                    //   但保留 slot 记录，给迟到事件（RESPONSE_BODY / loadingFinished）补全 body 的机会。
                    if (!slot.softTimedOut) {
                        slot.softTimedOut = true;
                        slot.bodyAvailability = BodyAvailability.UNAVAILABLE;
                        softTimeouts.incrementAndGet();
                        if (slot.inFlightCounted.compareAndSet(true, false)) {
                            decrement(slot);
                        }
                        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                                "[EventMerger] Slot soft timeout (body unavailable, kept) reqId={}, phase={}, age={}ms",
                                slot.requestId, describeSlotPhase(slot), age);
                    }
                    return false;
                }
                return false;
            });
        } catch (Exception e) {
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[EventMerger] Error in cleanup: {}", e.getMessage());
        }
    }

    /**
     * 旁路记录终态 exchange。该方法只处理已在 merger 线程内的字节快照，
     * 不访问 Page/Response/Route，也不写入 ApiCaptureContext。
     */
    private void recordExchange(MergingSlot slot, NetworkExchange.Failure failure) {
        CaptureEvent request = slot.request != null ? slot.request
                : slot.fetchRequest != null ? slot.fetchRequest : slot.mockFull;
        CaptureEvent response = slot.responseMeta != null ? slot.responseMeta
                : slot.fetchResponse != null ? slot.fetchResponse : slot.mockFull;
        if (request == null && response == null) return;

        byte[] body = slot.responseBody;
        if (body == null && slot.mockFull != null) body = limitBody(slot.mockFull.respBody);
        if (body == null && slot.fetchResponse != null) body = limitBody(slot.fetchResponse.respBody);
        BodyAvailability availability;
        if (body != null) availability = body.length == 0 ? BodyAvailability.EMPTY : BodyAvailability.AVAILABLE;
        else if (slot.bodyReady) availability = BodyAvailability.NOT_REQUESTED;
        else availability = slot.bodyAvailability;

        CaptureEvent identity = request != null ? request : response;
        NetworkExchange exchange = new NetworkExchange(slot.requestId, identity.source,
                identity.browserContext == null ? 0 : System.identityHashCode(identity.browserContext),
                identity.page == null ? 0 : System.identityHashCode(identity.page),
                request == null ? null : request.method,
                request == null ? null : request.url,
                request == null ? null : request.resourceType,
                request == null ? Map.of() : request.reqHeaders,
                request == null ? null : request.reqBody,
                response == null ? null : response.status,
                response == null ? Map.of() : response.respHeaders,
                body,
                response == null ? null : response.contentType,
                availability,
                failure,
                request == null ? 0L : request.timestamp,
                System.currentTimeMillis());
        synchronized (exchangeLock) {
            if (recentExchanges.size() >= MAX_RECENT_EXCHANGES) recentExchanges.removeFirst();
            recentExchanges.addLast(exchange);
            // 精确唤醒 waitForExchange 的条件等待者（替代忙轮询）
            exchangeLock.notifyAll();
        }
    }

    /** 描述 slot 当前阶段（用于日志） */
    private String describeSlotPhase(MergingSlot slot) {
        StringBuilder sb = new StringBuilder();
        if (slot.request != null) sb.append("REQ ");
        if (slot.responseMeta != null) sb.append("RESP_META ");
        if (slot.bodyReady) sb.append("BODY_READY ");
        if (slot.mockFull != null) sb.append("MOCK ");
        if (slot.fetchRequest != null) sb.append("FETCH_REQ ");
        if (slot.fetchResponse != null) sb.append("FETCH_RESP ");
        return sb.toString().trim();
    }

    /** 从完整 URL 中提取 endpoint（路径 + 查询，不含 host） */
    static String extractEndpoint(String url) {
        if (url == null) return "unknown";
        try {
            java.net.URI uri = java.net.URI.create(url);
            String path = uri.getPath();
            String query = uri.getQuery();
            return query != null ? path + "?" + query : path;
        } catch (Exception e) {
            return url.length() > 100 ? url.substring(0, 100) : url;
        }
    }

    // ── MergingSlot ──

    static class MergingSlot {
        final String requestId;
        /** ⭐ 以下字段被 merger 线程写、cleanup 线程读（slots.removeIf），
         *   必须 volatile 才能跨线程可见——尤其 BODY_READY→RESPONSE_BODY 异步投喂后，
         *   lastActivity 若读旧值会让在途 slot 被提前清理。 */
        volatile CaptureEvent request;
        volatile CaptureEvent responseMeta;
        volatile boolean bodyReady;
        /** ⭐ 异步 body 拉取在途标记：期间放宽 stale 清理，防止拉取进行中被误清 */
        volatile boolean bodyFetchPending;
        /** ⭐ body 拉取发起时间（ms），用于放宽窗口计算 */
        volatile long bodyFetchRequestedAt;
        /** ⭐ BodyReader 按需异步读取的响应体数据（bodyFetchPool 线程投喂） */
        byte[] responseBody;
        long originalResponseBodyBytes;
        CaptureEvent mockFull;
        CaptureEvent fetchRequest;
        CaptureEvent fetchResponse;
        /** 异常终止（abort/超时/网络错误）标记，仅释放计数不存储调用 */
        boolean failed;
        /** +1 时锁定归属 Context，避免 -1 时重新解析到其它/已关闭实例 */
        ApiCaptureContext owner;
        volatile long lastActivity;
        volatile boolean completed;
        /** 标记该 slot 是否已计入 captureInFlight，避免重复 +1 / 漏 -1 */
        final java.util.concurrent.atomic.AtomicBoolean inFlightCounted =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        /** ⭐ Bug A 唯一触发标记：BODY_READY 正常信号与 RESPONSE_META 延迟兜底
         *   只能有一个真正发起 body 拉取（compareAndSet 保证），避免重复拉取。 */
        final java.util.concurrent.atomic.AtomicBoolean bodyFetchTriggered =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        /** ⭐ 解析出的 endpoint（命中的 urlPattern）。REQUEST 阶段一次遍历算出，buildApiCall 复用，避免重复遍历规则集。 */
        String endpoint;
        /** ⭐ P1-5: Soft 超时标记 —— 已标记 body 不可用但 slot 保留，避免重复计数。 */
        volatile boolean softTimedOut;
        /** ⭐ P1-5: Soft 超时后 body 可用性（UNAVAILABLE），供 recordExchange 反映到诊断指标。 */
        volatile BodyAvailability bodyAvailability = BodyAvailability.NOT_REQUESTED;

        MergingSlot(String requestId) {
            this.requestId = requestId;
            this.lastActivity = System.currentTimeMillis();
        }
    }
}