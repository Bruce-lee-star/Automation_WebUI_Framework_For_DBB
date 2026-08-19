package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import java.util.Map;

/**
 * 采集管道内部传递的轻量事件 — 仅在工作线程中短暂存在，不跨线程保留。
 *
 * <p>所有层（CDP 旁路、Route 回调）都产出此结构，投喂给 {@link CaptureRingBuffer}。
 * 消费者（{@link EventMerger}）按 {@link #requestId} 合并多个 Phase 事件。
 *
 * <p>设计原则：
 * <ul>
 *   <li>每个事件只包含单个 Phase 的信息，绝不含完整 API 调用数据</li>
 *   <li>事件在 RingBuffer 中短暂停留（毫秒级），消费者合并后即丢弃</li>
 *   <li>body 字段仅在 MOCK_FULL/RESPONSE_BODY phase 时非 null，其余 phase 为 null</li>
 * </ul>
 */
public class CaptureEvent {

    /** 事件阶段 */
    public enum Phase {
        /** CDP Network.requestWillBeSent — 请求发出（含 modify 后真实体） */
        REQUEST,
        /** CDP Network.responseReceived — 响应元数据（status/headers，不含 body） */
        RESPONSE_META,
        /** CDP Network.loadingFinished — 响应体已就绪，可异步读取 */
        RESPONSE_BODY,
        /** Route 回调 — MOCK fulfill 参数（浏览器未发真实请求） */
        MOCK_FULL,
        /** Route 回调 — MODIFY route.fetch 的请求参数 */
        FETCH_REQUEST,
        /** Route 回调 — MODIFY route.fetch 的响应结果 */
        FETCH_RESPONSE
    }

    /** 来源 */
    public enum Source {
        CDP,          // Chromium DevTools Protocol 旁路
        PLAYWRIGHT,   // Playwright 事件（page.onRequest/onResponse）退化策略
        ROUTE_HANDLER // Route 回调（MOCK/MODIFY 特殊处理）
    }

    // ── 核心标识 ──
    public final String requestId;       // CDP requestId，跨层关联键
    public final Phase phase;
    public final Source source;
    public final long timestamp;

    // ── Request 侧 ──
    public final String method;
    public final String url;
    public final Map<String, String> reqHeaders;
    public final byte[] reqBody;         // 仅 REQUEST/MOCK_FULL/FETCH_REQUEST phase 时可能非 null

    // ── Response 侧 ──
    public final int status;
    public final Map<String, String> respHeaders;
    public final byte[] respBody;        // 仅 RESPONSE_BODY/MOCK_FULL/FETCH_RESPONSE phase 时非 null
    public final String contentType;     // 响应 Content-Type，用于 body 读取策略

    // ── 构造 ──

    private CaptureEvent(String requestId, Phase phase, Source source, long timestamp,
                         String method, String url, Map<String, String> reqHeaders, byte[] reqBody,
                         int status, Map<String, String> respHeaders, byte[] respBody,
                         String contentType) {
        this.requestId = requestId;
        this.phase = phase;
        this.source = source;
        this.timestamp = timestamp;
        this.method = method;
        this.url = url;
        this.reqHeaders = reqHeaders;
        this.reqBody = reqBody;
        this.status = status;
        this.respHeaders = respHeaders;
        this.respBody = respBody;
        this.contentType = contentType;
    }

    // ── 工厂方法（避免构造器膨胀） ──

    /** 创建 REQUEST phase 事件（CDP requestWillBeSent 或 page.onRequest） */
    public static CaptureEvent request(String requestId, String method, String url,
                                       Map<String, String> reqHeaders, byte[] reqBody,
                                       Source source) {
        return new CaptureEvent(requestId, Phase.REQUEST, source, System.currentTimeMillis(),
                method, url, reqHeaders, reqBody, 0, null, null, null);
    }

    /** 创建 RESPONSE_META phase 事件（CDP responseReceived） */
    public static CaptureEvent responseMeta(String requestId, int status,
                                            Map<String, String> respHeaders, Source source) {
        return new CaptureEvent(requestId, Phase.RESPONSE_META, source, System.currentTimeMillis(),
                null, null, null, null, status, respHeaders, null, null);
    }

    /** 创建 RESPONSE_BODY phase 事件（CDP loadingFinished 或异步 body 读取完成） */
    public static CaptureEvent responseBody(String requestId, byte[] body,
                                            String contentType, Source source) {
        return new CaptureEvent(requestId, Phase.RESPONSE_BODY, source, System.currentTimeMillis(),
                null, null, null, null, 0, null, body, contentType);
    }

    /** 创建 MOCK_FULL phase 事件（Route handler 投喂） */
    public static CaptureEvent mockFull(String requestId, String method, String url,
                                        Map<String, String> reqHeaders, byte[] reqBody,
                                        int status, Map<String, String> respHeaders, byte[] respBody) {
        return new CaptureEvent(requestId, Phase.MOCK_FULL, Source.ROUTE_HANDLER,
                System.currentTimeMillis(), method, url, reqHeaders, reqBody,
                status, respHeaders, respBody, null);
    }

    /** 创建 FETCH_REQUEST phase 事件（MODIFY route.fetch opts） */
    public static CaptureEvent fetchRequest(String requestId, String method, String url,
                                            Map<String, String> reqHeaders, byte[] reqBody) {
        return new CaptureEvent(requestId, Phase.FETCH_REQUEST, Source.ROUTE_HANDLER,
                System.currentTimeMillis(), method, url, reqHeaders, reqBody, 0, null, null, null);
    }

    /** 创建 FETCH_RESPONSE phase 事件（MODIFY route.fetch 响应） */
    public static CaptureEvent fetchResponse(String requestId, int status,
                                             Map<String, String> respHeaders, byte[] respBody) {
        return new CaptureEvent(requestId, Phase.FETCH_RESPONSE, Source.ROUTE_HANDLER,
                System.currentTimeMillis(), null, null, null, null,
                status, respHeaders, respBody, null);
    }

    @Override
    public String toString() {
        return String.format("CaptureEvent{%s|%s|%s reqId=%s %s %s → %d}",
                source, phase, requestId, method != null ? method : "",
                url != null ? url.substring(Math.max(0, url.length() - 40)) : "",
                status);
    }
}