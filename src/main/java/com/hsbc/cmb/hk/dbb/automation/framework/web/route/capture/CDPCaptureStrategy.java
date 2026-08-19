 package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

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

/**
 * CDP 旁路采集策略 — 通过 CDP Network 域纯监听，零阻塞。
 *
 * <p>订阅事件：
 * <ul>
 *   <li>{@code Network.requestWillBeSent} — 拿到请求（含 modify 后真实体）</li>
 *   <li>{@code Network.responseReceived} — 拿到 status/headers（不含 body）</li>
 *   <li>{@code Network.loadingFinished} — 使用 {@code Network.getResponseBody} 读取 body 并直接投喂</li>
 * </ul>
 *
 * <p>响应体在 CDP 事件线程直接读取，无需异步惰性处理，避免了重新 fetch 的循环风险。
 */
public class CDPCaptureStrategy implements CaptureStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(CDPCaptureStrategy.class);

    private CDPSession cdpSession;
    private volatile boolean running;

    @Override
    public void start(Page page, CaptureRingBuffer ringBuffer) {
        if (running) {
            LOGGER.debug("[CDPCaptureStrategy] Already running, skipping start");
            return;
        }

        try {
            this.cdpSession = page.context().newCDPSession(page);
        } catch (PlaywrightException e) {
            LOGGER.warn("[CDPCaptureStrategy] Failed to create CDP session: {}. "
                    + "Falling back to Playwright event capture.", e.getMessage());
            return;
        }

        // 启用 Network 域（只监听，不拦截）
        cdpSession.send("Network.enable");

        // ── 订阅 CDP 事件 ──

        // requestWillBeSent：拿到请求真实信息（含 modify 后请求体）
        cdpSession.on("Network.requestWillBeSent", event -> {
            if (!running) return;
            try {
                String requestId = event.get("requestId").getAsString();
                JsonObject request = event.get("request").getAsJsonObject();
                String method = request.get("method").getAsString();
                String url = request.get("url").getAsString();

                // 请求头
                Map<String, String> headers = new HashMap<>();
                JsonObject headersObj = request.getAsJsonObject("headers");
                if (headersObj != null) {
                    headersObj.entrySet().forEach(e ->
                            headers.put(e.getKey(), e.getValue().getAsString()));
                }

                // 请求体（仅 POST/PUT/PATCH 有 postData）
                byte[] reqBody = null;
                if (request.has("postData") && !request.get("postData").isJsonNull()) {
                    reqBody = request.get("postData").getAsString().getBytes(StandardCharsets.UTF_8);
                }

                CaptureEvent eventData = CaptureEvent.request(
                        requestId, method, url, headers, reqBody, CaptureEvent.Source.CDP);
                ringBuffer.publish(eventData);

            } catch (Exception e) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[CDPCaptureStrategy] Error processing requestWillBeSent: {}", e.getMessage());
            }
        });

        // responseReceived：拿到 status/headers
        cdpSession.on("Network.responseReceived", event -> {
            if (!running) return;
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
                ringBuffer.publish(eventData);

            } catch (Exception e) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[CDPCaptureStrategy] Error processing responseReceived: {}", e.getMessage());
            }
        });

        // loadingFinished：读取 body 并投喂（使用 CDP Network.getResponseBody 从缓存读取）
        // 注意：这是本地 IPC 调用，微秒级返回，不会阻塞事件线程
        cdpSession.on("Network.loadingFinished", event -> {
            if (!running) return;
            try {
                String requestId = event.get("requestId").getAsString();

                // ⭐ 使用 CDP 从浏览器缓存读取响应体，避免异步 re-fetch
                // 这比 page.request().fetch(url) 更准确（获取原始响应，而非重新请求）
                byte[] body = null;
                String contentType = null;
                try {
                    // ⭐ CDP send() 接受 JsonObject 参数
                    com.google.gson.JsonObject cdpParams = new com.google.gson.JsonObject();
                    cdpParams.addProperty("requestId", requestId);
                    JsonObject bodyResult = cdpSession.send("Network.getResponseBody", cdpParams);
                    if (bodyResult != null) {
                        String bodyStr = bodyResult.get("body").getAsString();
                        boolean base64Encoded = bodyResult.has("base64Encoded")
                                && bodyResult.get("base64Encoded").getAsBoolean();
                        // 根据 encoding 处理
                        body = base64Encoded
                                ? java.util.Base64.getDecoder().decode(bodyStr)
                                : bodyStr.getBytes(StandardCharsets.UTF_8);
                    }
                } catch (Exception e) {
                    LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                            "[CDPCaptureStrategy] Error reading body via CDP: {}", e.getMessage());
                }

                CaptureEvent eventData = CaptureEvent.responseBody(
                        requestId, body, contentType, CaptureEvent.Source.CDP);
                ringBuffer.publish(eventData);

            } catch (Exception e) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[CDPCaptureStrategy] Error processing loadingFinished: {}", e.getMessage());
            }
        });

        this.running = true;
        LOGGER.info("[CDPCaptureStrategy] Started CDP Network capture");
    }

    @Override
    public void stop() {
        this.running = false;
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
        LOGGER.info("[CDPCaptureStrategy] Stopped");
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
}