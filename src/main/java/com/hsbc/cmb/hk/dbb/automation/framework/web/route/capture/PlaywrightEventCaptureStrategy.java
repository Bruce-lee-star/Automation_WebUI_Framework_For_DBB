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
import java.util.IdentityHashMap;

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
    /** 当前绑定监听器的 Page 实例（用于跨页面判断是否需要重新注册） */
    private Page registeredPage;
    private CaptureRingBuffer ringBuffer;
    private volatile boolean running;
    /** 标记是否已注册监听器，防止重复注册导致的泄漏 */
    private boolean listenersRegistered;
    /**
     * ⭐ Request → requestId 关联缓存（identity 语义，同一 Request 实例的 onRequest/onResponse 共享同一 id）。
     * <p>Playwright 的 {@code onRequest} 与 {@code onResponse(response.request())} 对同一请求返回<b>同一 Request 实例</b>，
     * 因此用 identity 缓存能让请求与响应对齐（否则随机 UUID 无法关联 → 合并器无法写出完整调用）。
     * <p>容量上限保护：超出后整体清空（仅影响极少数超长会话的精确关联，可接受）。
     */
    private static final int MAX_ID_CACHE = 4096;
    private final Map<Request, String> requestIdCache = new IdentityHashMap<>();

    @Override
    public void start(Page page, CaptureRingBuffer ringBuffer) {
        if (running && page == this.page) {
            LOGGER.debug("[PlaywrightEventCaptureStrategy] Already running on same page, skipping start");
            return;
        }

        this.page = page;
        this.ringBuffer = ringBuffer;

        // ⭐ 防泄漏：监听器仅注册一次，后续 start/stop 只控制 running 标志位
        // Playwright Java API 不提供移除单个监听器的方法，重复注册会导致泄漏。
        //
        // ⭐ 跨页面修复：当传入的 Page 与已注册的不同（如用例内切到新 Page 实例），
        // 旧 Page 的监听器会随旧 Page 关闭被 Playwright 自动回收，但新 Page 必须重新
        // 注册监听器，否则新页面请求会静默采不到。这里按 page 引用变化重新绑定。
        if (!listenersRegistered || registeredPage != page) {
            registerListeners(page, ringBuffer);
            this.registeredPage = page;
            this.listenersRegistered = true;
        }

        this.running = true;
        LOGGER.info("[PlaywrightEventCaptureStrategy] Started Playwright event capture on page={}",
                page != null ? page.url() : "null");
    }

    /**
     * 在指定 Page 上注册 onRequest/onResponse 监听器。
     *
     * <p>监听器内部始终以 {@code running} 标志位 + 当前 {@link #page} 守卫，
     * 仅当事件来自当前活动页面且采集处于运行态时才投喂，避免旧页面残留回调
     * 干扰新页面采集。
     */
    private void registerListeners(Page page, CaptureRingBuffer ringBuffer) {
        // ── 订阅 Playwright 事件 ──

        // page.onRequest：拿到请求信息（注意：modify 场景下是修改前快照）
        page.onRequest(request -> {
            if (!running || page != this.page) return;
            try {
                String requestId = allocRequestId(request);
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

                // 资源类型：Playwright 提供（xhr/fetch/document/script/image…），统一解析为枚举
                String rawType = request.resourceType();
                ResourceType resourceType = ResourceType.fromString(rawType);

                CaptureEvent event = CaptureEvent.request(
                        requestId, method, url, headers, reqBody, resourceType, CaptureEvent.Source.PLAYWRIGHT);
                ringBuffer.publish(event);

            } catch (Exception e) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[PlaywrightEventCaptureStrategy] Error processing onRequest: {}", e.getMessage());
            }
        });

        // page.onResponse：拿到 status/headers，body 在响应事件中直接读取
        page.onResponse(response -> {
            if (!running || page != this.page) return;
            try {
                Request request = response.request();
                String requestId = allocRequestId(request);
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
     * 分配合成 requestId。
     *
     * <p>Playwright 事件不提供 CDP 的 requestId，但同一请求的 {@code onRequest} 与
     * {@code onResponse(response.request())} 返回<b>同一 Request 实例</b>。因此用 identity 缓存
     * 为每个 Request 分配一次 id，后续响应事件复用同一 id，使合并器能把请求与响应关联成一个调用。
     *
     * <p>容量上限保护：超出 {@link #MAX_ID_CACHE} 后整体清空（仅影响超长会话的极端情况）。
     */
    private String allocRequestId(Request request) {
        synchronized (requestIdCache) {
            String existing = requestIdCache.get(request);
            if (existing != null) {
                return existing;
            }
            if (requestIdCache.size() >= MAX_ID_CACHE) {
                requestIdCache.clear();
            }
            String id = "pw-" + UUID.randomUUID().toString().substring(0, 8)
                    + "-" + Integer.toHexString(request.url().hashCode());
            requestIdCache.put(request, id);
            return id;
        }
    }
}