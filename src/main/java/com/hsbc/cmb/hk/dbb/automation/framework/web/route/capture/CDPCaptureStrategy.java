 package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CDP 旁路采集策略 — 通过 CDP Network 域纯监听，零阻塞。
 *
 * <p>订阅事件：
 * <ul>
 *   <li>{@code Network.requestWillBeSent} — 拿到请求（含 modify 后真实体）</li>
 *   <li>{@code Network.responseReceived} — 拿到 status/headers（不含 body）</li>
 *   <li>{@code Network.loadingFinished} — 发布轻量 {@code BODY_READY} 信号，body 由 {@link BodyReader} 在专用线程池按需异步读取</li>
 * </ul>
 *
 * <p>响应体由 {@link BodyReader} 在 bodyFetchPool 线程按需惰性读取（{@code Network.getResponseBody} 本地 IPC），
 * 绝不在 CDP 事件线程内重入。
 */
public class CDPCaptureStrategy implements CaptureStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(CDPCaptureStrategy.class);

    private CDPSession cdpSession;
    private Page page;
    private CaptureRingBuffer ringBuffer;
    private volatile boolean running;
    private final Map<String, Map<String, String>> extraRequestHeaders = new ConcurrentHashMap<>();
    /** 诊断：各 CDP 事件名触发计数 */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> eventCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private void countEvent(String name) {
        eventCounts.merge(name, 1, Integer::sum);
    }


    @Override
    public void start(Page page, CaptureRingBuffer ringBuffer) {
        if (running) {
            LOGGER.debug("[CDPCaptureStrategy] Already running, skipping start");
            return;
        }

        try {
            this.page = page;
            this.ringBuffer = ringBuffer;
            this.cdpSession = page.context().newCDPSession(page);
            // 启用 Network 域（只监听，不拦截）；能力探测失败时由上层统一降级。
            cdpSession.send("Network.enable");
        } catch (Exception e) {
            LOGGER.warn("[CDPCaptureStrategy] CDP initialization failed: {}. "
                    + "Falling back to Playwright event capture.", e.getMessage());
            this.running = false;
            if (this.cdpSession != null) {
                try { this.cdpSession.detach(); } catch (Exception ignored) { }
                this.cdpSession = null;
            }
            return;
        }

        // ── 订阅 CDP 事件 ──
        cdpSession.on("Network.requestWillBeSentExtraInfo", event -> {
            if (!running) return;
            countEvent("requestWillBeSentExtraInfo");
            try {
                String requestId = event.get("requestId").getAsString();
                JsonObject headersObj = event.getAsJsonObject("headers");
                Map<String, String> headers = new HashMap<>();
                if (headersObj != null) {
                    headersObj.entrySet().forEach(e -> headers.put(e.getKey(), e.getValue().getAsString()));
                }
                // ⭐ 仅经 extraRequestHeaders 中转，等待 requestWillBeSent 消费合并。
                //   注意：Chromium 保证 requestWillBeSentExtraInfo 先于 requestWillBeSent 到达，
                //   因此不处理「后到」的兜底合并——若真出现（极端顺序反转），补充头会被丢弃，
                //   但可避免对已发布事件（merger 线程可能正在读取）的跨线程 HashMap 并发修改。
                extraRequestHeaders.put(requestId, headers);
            } catch (Exception e) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[CDPCaptureStrategy] Error processing requestWillBeSentExtraInfo: {}", e.getMessage());
            }
        });

        // requestWillBeSent：拿到请求真实信息（含 modify 后请求体）
        cdpSession.on("Network.requestWillBeSent", event -> {
            if (!running) return;
            countEvent("requestWillBeSent");
            try {
                String requestId = event.get("requestId").getAsString();
                JsonObject request = event.get("request").getAsJsonObject();
                String method = request.get("method").getAsString();
                String url = request.get("url").getAsString();

                // Capture 是 API 存储的唯一入口。MONITOR/MOCK/DELAY/MODIFY
                // 都保留 CDP 事件，避免跨层或异步 Delay 场景丢失真实响应。

                // 请求头
                Map<String, String> headers = new HashMap<>();
                JsonObject headersObj = request.getAsJsonObject("headers");
                if (headersObj != null) {
                    headersObj.entrySet().forEach(e ->
                            headers.put(e.getKey(), e.getValue().getAsString()));
                }
                mergeHeadersCaseInsensitive(headers, extraRequestHeaders.remove(requestId));

                // 请求体（仅 POST/PUT/PATCH 有 postData）
                byte[] reqBody = null;
                if (request.has("postData") && !request.get("postData").isJsonNull()) {
                    reqBody = request.get("postData").getAsString().getBytes(StandardCharsets.UTF_8);
                }

                // ⭐ 资源类型：CDP requestWillBeSent 的 type 字段位于【事件顶层】（不在 request 对象内）！
                //   XHR/Fetch/Document/Script/Image…。此前误读 request.type 恒为 null，
                //   导致所有 CDP 采集调用 resourceType 落为 OTHER，使 ofType(XHR/FETCH) 等
                //   类型过滤断言永远失败（example_filterByResourceType 即此场景）。
                String rawType = event.has("type") && !event.get("type").isJsonNull()
                        ? event.get("type").getAsString() : null;
                ResourceType resourceType = ResourceType.fromString(rawType);

                CaptureEvent eventData = CaptureEvent.request(
                        requestId, method, url, headers, reqBody, resourceType, CaptureEvent.Source.CDP)
                        .withContext(page.context(), page);
                ringBuffer.publish(eventData);

            } catch (Exception e) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[CDPCaptureStrategy] Error processing requestWillBeSent: {}", e.getMessage());
            }
        });

        // responseReceived：拿到 status/headers
        cdpSession.on("Network.responseReceived", event -> {
            if (!running) return;
            countEvent("responseReceived");
            try {
                String requestId = event.get("requestId").getAsString();
                JsonObject response = event.getAsJsonObject("response");
                int status = response.get("status").getAsInt();

                Map<String, String> headers = new HashMap<>();
                JsonObject headersObj = response.getAsJsonObject("headers");
                if (headersObj != null) {
                    headersObj.entrySet().forEach(e ->
                            headers.put(e.getKey(), e.getValue().getAsString()));
                }

                // 获取 Content-Type（用于 body 读取策略判断）
                String contentType = headers.get("content-type");
                if (contentType == null) contentType = headers.get("Content-Type");

                CaptureEvent eventData = CaptureEvent.responseMeta(
                        requestId, status, headers, CaptureEvent.Source.CDP);
                ringBuffer.publish(eventData.withContext(page.context(), page));

            } catch (Exception e) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[CDPCaptureStrategy] Error processing responseReceived: {}", e.getMessage());
            }
        });

        // loadingFinished：响应体已就绪，发布轻量 BODY_READY 信号。
        // ⭐ body 由 BodyReader 在专用线程池（bodyFetchPool）按需异步读取
        //   （Network.getResponseBody 本地 IPC，微秒级返回），不在 CDP 事件线程同步读取：
        //   统一经 BodyReader 惰性拉取，避免对未被采集范围覆盖的资源请求做无谓的 body 拉取。
        cdpSession.on("Network.loadingFinished", event -> {
            if (!running) return;
            countEvent("loadingFinished");
            try {
                String requestId = event.get("requestId").getAsString();
                CaptureEvent eventData = CaptureEvent.bodyReady(requestId, CaptureEvent.Source.CDP);
                ringBuffer.publish(eventData.withContext(page.context(), page));
            } catch (Exception e) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[CDPCaptureStrategy] Error processing loadingFinished: {}", e.getMessage());
            }
        });

        // loadingFailed：请求被 abort / 超时 / 网络错误。立即投喂 FAILED 事件，
        // 让 EventMerger 释放 captureInFlight，避免 awaitCompletion 被拖到 stale 超时。
        cdpSession.on("Network.loadingFailed", event -> {
            if (!running) return;
            countEvent("loadingFailed");
            try {
                String requestId = event.get("requestId").getAsString();
                extraRequestHeaders.remove(requestId);
                CaptureEvent eventData = CaptureEvent.failed(requestId, CaptureEvent.Source.CDP);
                ringBuffer.publish(eventData.withContext(page.context(), page));
            } catch (Exception e) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[CDPCaptureStrategy] Error processing loadingFailed: {}", e.getMessage());
            }
        });

        this.running = true;
        LOGGER.info("[CDPCaptureStrategy] Started CDP Network capture");
    }

    private static void mergeHeadersCaseInsensitive(Map<String, String> target,
                                                     Map<String, String> additions) {
        if (additions == null) return;
        for (Map.Entry<String, String> addition : additions.entrySet()) {
            String existingKey = null;
            for (String key : target.keySet()) {
                if (key.equalsIgnoreCase(addition.getKey())) {
                    existingKey = key;
                    break;
                }
            }
            target.put(existingKey != null ? existingKey : addition.getKey(), addition.getValue());
        }
    }

    @Override
    public void stop() {
        this.running = false;
        extraRequestHeaders.clear();
        if (cdpSession != null) {
            try {
                // ⭐ 安全清理：先检查 CDP session 是否仍然有效
                // Playwright 在 Page 关闭时会自动清理 CDP session，但 detach() 可确保立即释放
                cdpSession.detach();
            } catch (Exception e) {
                // Page 已关闭 → CDP session 已自动清理，无需处理
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[CDPCaptureStrategy] CDP session detach skipped (likely page already closed): {}",
                        e.getMessage());
            }
            cdpSession = null;
        }
        LOGGER.info("[CDPCaptureStrategy] Stopped. Event counts: {}", eventCounts);
    }

    @Override
    public String name() {
        return "CDP";
    }

    @Override
    public boolean isAvailable() {
        // CDP session 创建成功 → 可用；失败 → 需要降级
        return cdpSession != null && running;
    }

    @Override
    public boolean providesResponseBody() {
        // CDP 旁路在 Playwright route 拦截场景下，Network.responseReceived / loadingFinished
        // 不会为被 route 处理的请求触发（响应由 route 层 fulfill，绕过 CDP 正常响应流），
        // 因此事件链终态交换拿不到 status/body。此时必须由 Monitor 回退到 page.waitForResponse。
        // 故 CDP 旁路当前不具备可供事件链接管的 body 能力。
        return false;
    }

    @Override
    public byte[] readResponseBody(String requestId) {
        // ⭐ 在 bodyFetchPool 线程调用（非 CDP 事件线程）：Network.getResponseBody 本地 IPC 读取浏览器缓存。
        //   被 route 拦截（MOCK/MODIFY）的请求不走 CDP 正常响应流，这里会失败并返回 null——符合预期。
        try {
            JsonObject cdpParams = new JsonObject();
            cdpParams.addProperty("requestId", requestId);
            JsonObject bodyResult = cdpSession.send("Network.getResponseBody", cdpParams);
            if (bodyResult != null) {
                String bodyStr = bodyResult.get("body").getAsString();
                boolean base64Encoded = bodyResult.has("base64Encoded")
                        && bodyResult.get("base64Encoded").getAsBoolean();
                byte[] decoded = base64Encoded
                        ? java.util.Base64.getDecoder().decode(bodyStr)
                        : bodyStr.getBytes(StandardCharsets.UTF_8);
                // ⭐ 响应体上限防 OOM：超大响应体（下载/大 JSON）截断后再入环缓冲与存储
                return RouteUtil.truncateBody(decoded);
            }
            return null;
        } catch (Exception e) {
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[CDPCaptureStrategy] Error reading body via CDP for reqId={}: {}", requestId, e.getMessage());
            return null;
        }
    }
}