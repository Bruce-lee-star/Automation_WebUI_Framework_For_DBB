package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Playwright 事件退化策略 — 通过 page.onRequest/onResponse 采集。
 *
 * <p>作为 CDP 旁路的统一退化方案，适用于：
 * <ul>
 *   <li>非 Chromium 浏览器（Firefox、WebKit）</li>
 *   <li>CDP Session 创建失败时的兜底</li>
 * </ul>
 *
 * <p>注意：page.onRequest 拿到的是修改前快照（modify 场景下不准确），
 * 但这是非 Chromium 下的最佳选择。
 */
public class PlaywrightEventCaptureStrategy implements CaptureStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaywrightEventCaptureStrategy.class);

    private Page page;
    private volatile boolean running;
    /** 标记是否已注册监听器，防止重复注册导致的泄漏 */
    private boolean listenersRegistered;

    @Override
    public void start(Page page, CaptureRingBuffer ringBuffer) {
        if (running) {
            LOGGER.debug("[PlaywrightEventCaptureStrategy] Already running, skipping start");
            return;
        }

        this.page = page;

        // ⭐ 防泄漏：监听器仅注册一次，后续 start/stop 只控制 running 标志位
        // Playwright Java API 不提供移除单个监听器的方法，重复注册会导致泄漏
        if (!listenersRegistered) {
            // ── 订阅 Playwright 事件 ──

            // page.onRequest：拿到请求信息（注意：modify 场景下是修改前快照）
            page.onRequest(request -> {
                if (!running) return;
                try {
                    String requestId = syntheticRequestId(request);
                    String method = request.method();
                    String url = request.url();

                    // 请求头
                    Map<String, String> headers = new HashMap<>(request.headers());

                    // 请求体（仅 POST/PUT/PATCH）
                    byte[] reqBody = null;
                    String postData = request.postData();
                    if (postData != null) {
                        reqBody = postData.getBytes(StandardCharsets.UTF_8);
                    }

                    CaptureEvent event = CaptureEvent.request(
                            requestId, method, url, headers, reqBody, CaptureEvent.Source.PLAYWRIGHT);
                    ringBuffer.publish(event);

                } catch (Exception e) {
                    LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                            "[PlaywrightEventCaptureStrategy] Error processing onRequest: {}", e.getMessage());
                }
            });

            // page.onResponse：拿到 status/headers，body 在响应事件中直接读取
            page.onResponse(response -> {
                if (!running) return;
                try {
                    Request request = response.request();
                    String requestId = syntheticRequestId(request);
                    int status = response.status();

                    Map<String, String> headers = new HashMap<>(response.headers());

                    String contentType = headers.get("content-type");
                    if (contentType == null) contentType = headers.get("Content-Type");

                    // 投喂 RESPONSE_META
                    CaptureEvent metaEvent = CaptureEvent.responseMeta(
                            requestId, status, headers, CaptureEvent.Source.PLAYWRIGHT);
                    ringBuffer.publish(metaEvent);

                    // ⭐ 直接读取 body 并投喂，避免异步 re-fetch 导致的不准确和循环风险
                    byte[] body = null;
                    try {
                        body = response.body();
                    } catch (Exception e) {
                        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                                "[PlaywrightEventCaptureStrategy] Error reading body: {}", e.getMessage());
                    }
                    CaptureEvent bodyEvent = CaptureEvent.responseBody(
                            requestId, body, contentType, CaptureEvent.Source.PLAYWRIGHT);
                    ringBuffer.publish(bodyEvent);

                } catch (Exception e) {
                    LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                            "[PlaywrightEventCaptureStrategy] Error processing onResponse: {}", e.getMessage());
                }
            });

            this.listenersRegistered = true;
        }

        this.running = true;
        LOGGER.info("[PlaywrightEventCaptureStrategy] Started Playwright event capture");
    }

    @Override
    public void stop() {
        this.running = false;
        // ⭐ Playwright Java API 不提供移除单个监听器的方法
        // 所以监听器只能注册一次（由 listenersRegistered 控制），stop() 仅停止事件处理
        // 当 Page 被关闭时，监听器会被自动清理，不会泄漏
        LOGGER.info("[PlaywrightEventCaptureStrategy] Stopped");
    }

    @Override
    public String name() {
        return "Playwright";
    }

    // ── 内部 ──

    /**
     * 生成合成 requestId。
     *
     * <p>Playwright 事件不提供 CDP 的 requestId，因此用 URL + 时间戳 + 随机数合成。
     * 注意：合成的 requestId 无法跨层关联（失去 CDP 天然关联能力），
     * 但 Playwright 事件是退化策略，采集精度略低于 CDP 是可以接受的。
     */
    private static String syntheticRequestId(Request request) {
        return "pw-" + UUID.randomUUID().toString().substring(0, 8)
                + "-" + Integer.toHexString(request.url().hashCode());
    }
}