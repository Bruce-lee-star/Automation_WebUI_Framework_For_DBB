package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

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

    private static final Logger LOGGER = LoggerFactory.getLogger(EventMerger.class);

    /** Slot 超时时间（毫秒），超过此时间未完成的 slot 被视为 stale */
    private static final long SLOT_TIMEOUT_MS = 30_000;

    /** 清理间隔（毫秒） */
    private static final long CLEANUP_INTERVAL_MS = 5_000;

    private final CaptureRingBuffer ringBuffer;
    private final CaptureThreadPool threadPool;
    private final Map<String, MergingSlot> slots = new ConcurrentHashMap<>();
    private final AtomicLong completedCalls = new AtomicLong(0);
    private final AtomicLong failedMerges = new AtomicLong(0);
    private final AtomicLong staleSlots = new AtomicLong(0);

    private volatile boolean running;
    /** ⭐ 定时清理任务的引用，用于 stop() 时取消 */
    private ScheduledFuture<?> cleanupFuture;

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
    }

    /** 停止合并器 */
    public void stop() {
        this.running = false;
        // ⭐ 取消定时清理任务，防止泄漏
        ScheduledFuture<?> f = this.cleanupFuture;
        if (f != null && !f.isCancelled() && !f.isDone()) {
            f.cancel(false);
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
        MergingSlot slot = slots.computeIfAbsent(event.requestId, k -> new MergingSlot(event.requestId));
        slot.lastActivity = System.currentTimeMillis();

        switch (event.phase) {
            case REQUEST:
                slot.request = event;
                break;
            case RESPONSE_META:
                slot.responseMeta = event;
                break;
            case RESPONSE_BODY:
                slot.bodyReady = true;
                slot.responseBody = event.respBody;  // ⭐ 存储 body 数据，供 buildApiCall 使用
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
        return slot.request != null && slot.responseMeta != null && slot.bodyReady;
    }

    /**
     * 将完整的 slot 合成为 CapturedApiCall 存入 ApiCaptureContext。
     */
    private void completeSlot(MergingSlot slot) {
        try {
            CapturedApiCall call = buildApiCall(slot);
            if (call != null) {
                ApiCaptureContext.getCurrent().storeApiCall(call);
                completedCalls.incrementAndGet();
            }
        } catch (Exception e) {
            LOGGER.error("[EventMerger] Failed to complete slot reqId={}: {}", slot.requestId, e.getMessage());
            failedMerges.incrementAndGet();
        }
    }

    /**
     * 从 MergingSlot 构建 CapturedApiCall。
     */
    private CapturedApiCall buildApiCall(MergingSlot slot) {
        // ── MOCK：从 mockFull 构建 ──
        if (slot.mockFull != null) {
            CaptureEvent e = slot.mockFull;
            // 从 URL 中提取 endpoint（去掉 host）
            String endpoint = extractEndpoint(e.url);
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
                    .build();
        }

        // ── MODIFY：从 fetchRequest + fetchResponse 构建 ──
        if (slot.fetchRequest != null && slot.fetchResponse != null) {
            CaptureEvent req = slot.fetchRequest;
            CaptureEvent resp = slot.fetchResponse;
            String endpoint = extractEndpoint(req.url);
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
                    .build();
        }

        // ── 普通请求：从 request + responseMeta 构建（body 已直接由策略采集） ──
        if (slot.request != null && slot.responseMeta != null) {
            CaptureEvent req = slot.request;
            CaptureEvent resp = slot.responseMeta;
            String endpoint = extractEndpoint(req.url);
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
        CaptureEvent mockFull;
        CaptureEvent fetchRequest;
        CaptureEvent fetchResponse;
        long lastActivity;
        boolean completed;

        MergingSlot(String requestId) {
            this.requestId = requestId;
            this.lastActivity = System.currentTimeMillis();
        }
    }
}