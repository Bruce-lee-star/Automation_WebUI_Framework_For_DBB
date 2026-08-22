package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>响应体由 CDP/Playwright 策略在事件处理时直接读取（CDP Network.getResponseBody / Playwright response.body()），
 *       无需异步惰性读取</li>
 *   <li>定期清理超时未完成的 stale slot</li>
 * </ul>
 */
public class EventMerger implements Runnable {
    private static final int MAX_CAPTURE_BODY_BYTES = 5 * 1024 * 1024;

    private static final Logger LOGGER = LoggerFactory.getLogger(EventMerger.class);

    /** Slot 超时时间（毫秒），超过此时间未完成的 slot 被视为 stale。
     *  调小以便在 awaitCompletion(10s) 内清理偶发事件丢失导致的不完整 slot，释放 captureInFlight 计数。 */
    static final long SLOT_TIMEOUT_MS = 3_000;

    /** 清理间隔（毫秒） */
    private static final long CLEANUP_INTERVAL_MS = 1_000;

    private final CaptureRingBuffer ringBuffer;
    private final CaptureThreadPool threadPool;
    private final Map<String, MergingSlot> slots = new ConcurrentHashMap<>();
    private final AtomicLong completedCalls = new AtomicLong(0);
    private final AtomicLong failedMerges = new AtomicLong(0);
    private final AtomicLong staleSlots = new AtomicLong(0);

    private volatile boolean running;
    /** ⭐ 定时清理任务的引用，用于 stop() 时取消 */
    private ScheduledFuture<?> cleanupFuture;
    /** 合并线程引用，用于 stop() 时中断并带超时 join，避免线程泄漏 */
    private final AtomicReference<Thread> mergerThread = new AtomicReference<>();

    /**
     * @param ringBuffer 事件缓冲区
     * @param threadPool 专用线程池（用于定时清理）
     */
    public EventMerger(CaptureRingBuffer ringBuffer, CaptureThreadPool threadPool) {
        this.ringBuffer = ringBuffer;
        this.threadPool = threadPool;
    }

    @Override
    public void run() {
        this.running = true;
        mergerThread.set(Thread.currentThread());
        LOGGER.info("[EventMerger] Started merger loop");

        // ⭐ 启动定时清理并保存引用，用于 stop() 时取消
        cleanupFuture = threadPool.scheduleCleanup(this::cleanupStaleSlots, CLEANUP_INTERVAL_MS);

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
        // ⭐ 取消定时清理任务，防止泄漏
        ScheduledFuture<?> f = this.cleanupFuture;
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
        Thread t = mergerThread.get();
        if (t == null || Thread.currentThread() == t) return true;
        try {
            t.join(Math.max(1L, timeoutMs));
            return !t.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ── 指标 ──

    public long completedCalls() { return completedCalls.get(); }
    public long failedMerges() { return failedMerges.get(); }
    public long staleSlots() { return staleSlots.get(); }

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
            coveredEndpoint = RouteEngine.resolveEndpointIfCovered(event.url);
            // 没有注册任何规则时保留兼容行为：允许独立采集器记录 API；
            // 一旦存在规则，则严格过滤未覆盖的请求。
            if (coveredEndpoint == null && RouteEngine.hasCaptureRules()) {
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[EventMerger] Skip uncovered API (no matching rule): url='{}'", event.url);
                return;
            }
        }
        MergingSlot slot = slots.computeIfAbsent(event.requestId, k -> new MergingSlot(event.requestId));
        slot.lastActivity = System.currentTimeMillis();

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
                break;
            case RESPONSE_BODY:
                slot.bodyReady = true;
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

    /** 判断 slot 是否完整 */
    private boolean isSlotComplete(MergingSlot slot) {
        if (slot.mockFull != null) return true;
        if (slot.fetchRequest != null && slot.fetchResponse != null) return true;
        if (slot.failed) return true;
        return slot.request != null && slot.responseMeta != null && slot.bodyReady;
    }

    /** 是否是需要采集的 API 类请求（XHR/Fetch/API 投喂/OTHER 兜底），排除页面资源类请求 */
    private boolean isApiRequest(CaptureEvent event) {
        ResourceType rt = event.resourceType;
        if (rt == null) return true;   // 未知类型 → 保守保留
        switch (rt) {
            case XHR:
            case FETCH:
            case API:
            case OTHER:
                return true;
            default:
                // DOCUMENT / SCRIPT / STYLESHEET / IMAGE / FONT / MEDIA / WEBSOCKET / MANIFEST
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
                        && RouteEngine.isSyncStoredRuleForUrl(call.requestUrl())) {
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
            // ⭐ body 已由策略（CDP/Playwright）在事件处理时直接读取，无需异步 re-fetch
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
        try {
            long now = System.currentTimeMillis();
            slots.values().removeIf(slot -> {
                if (!slot.completed && (now - slot.lastActivity) > SLOT_TIMEOUT_MS) {
                    staleSlots.incrementAndGet();
                    LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                            "[EventMerger] Cleaned stale slot reqId={}, phase={}",
                            slot.requestId, describeSlotPhase(slot));
                    // ⭐ 超时 slot 同样是一次"在途请求结束"，递减采集在途计数，避免残留导致 awaitCompletion 被拖到超时
                    if (slot.inFlightCounted.compareAndSet(true, false)) {
                        decrement(slot);
                    }
                    return true;
                }
                return false;
            });
        } catch (Exception e) {
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[EventMerger] Error in cleanup: {}", e.getMessage());
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
        CaptureEvent request;
        CaptureEvent responseMeta;
        boolean bodyReady;
        /** ⭐ 策略在事件处理时直接读取的响应体数据（CDP Network.getResponseBody / Playwright response.body()） */
        byte[] responseBody;
        long originalResponseBodyBytes;
        CaptureEvent mockFull;
        CaptureEvent fetchRequest;
        CaptureEvent fetchResponse;
        /** 异常终止（abort/超时/网络错误）标记，仅释放计数不存储调用 */
        boolean failed;
        /** +1 时锁定归属 Context，避免 -1 时重新解析到其它/已关闭实例 */
        ApiCaptureContext owner;
        long lastActivity;
        boolean completed;
        /** 标记该 slot 是否已计入 captureInFlight，避免重复 +1 / 漏 -1 */
        final java.util.concurrent.atomic.AtomicBoolean inFlightCounted =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        /** ⭐ 解析出的 endpoint（命中的 urlPattern）。REQUEST 阶段一次遍历算出，buildApiCall 复用，避免重复遍历规则集。 */
        String endpoint;

        MergingSlot(String requestId) {
            this.requestId = requestId;
            this.lastActivity = System.currentTimeMillis();
        }
    }
}