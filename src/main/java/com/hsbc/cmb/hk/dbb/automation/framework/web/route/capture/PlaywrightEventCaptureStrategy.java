package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * <p>响应体由 {@link BodyReader} 在 bodyFetchPool 线程按需异步读取（{@code Response.body()}）：
 * 绝不在 onResponse 回调内调用 body()（Firefox/WebKit 中该回调与 Playwright connection
 * dispatcher 共用调度链路，重入会抛 NetworkError/Load failed）。
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
    private final NetworkEventCorrelator correlator = new NetworkEventCorrelator();
    /**
     * ⭐ 待按需读取的响应体：requestId → Playwright {@code Response} 对象。
     * <p>onResponse 回调内暂存（不读 body），由 {@link BodyReader} 在 bodyFetchPool 线程
     * 调用 {@link #readResponseBody} 时取走并 {@code Response.body()}，读取后即移除。
     */
    private final Map<String, Response> pendingResponses = new ConcurrentHashMap<>();
    /** ⭐ pendingResponses 容量上限：超过后整体清空（防极端长会话下 Response 对象无界残留） */
    private static final int MAX_PENDING_RESPONSES = 1024;

    @Override
    public void start(Page page, CaptureRingBuffer ringBuffer) {
        if (running && page == this.page) {
            LOGGER.debug("[PlaywrightEventCaptureStrategy] Already running on same page, skipping start");
            return;
        }

        this.page = page;
        this.ringBuffer = ringBuffer;
        correlator.start();
        pendingResponses.clear();

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
                String requestId = correlator.onRequest(page, request, null);
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
                        requestId, method, url, headers, reqBody, resourceType, CaptureEvent.Source.PLAYWRIGHT)
                        .withContext(page.context(), page);
                ringBuffer.publish(event);

            } catch (Exception e) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[PlaywrightEventCaptureStrategy] Error processing onRequest: {}", e.getMessage());
            }
        });

        // page.onResponse：拿到 status/headers；body 暂存后由 BodyReader 按需异步读取
        page.onResponse(response -> {
            if (!running || page != this.page) return;
            try {
                Request request = response.request();
                String requestId = correlator.onRequest(page, request, null);
                int status = response.status();

                Map<String, String> headers = new HashMap<>(response.headers());

                String contentType = headers.get("content-type");
                if (contentType == null) contentType = headers.get("Content-Type");

                // 投喂 RESPONSE_META
                CaptureEvent metaEvent = CaptureEvent.responseMeta(
                        requestId, status, headers, CaptureEvent.Source.PLAYWRIGHT)
                        .withContext(page.context(), page);
                ringBuffer.publish(metaEvent);

                // 不在 onResponse 回调内调用 response.body()：Firefox/WebKit 中该回调
                // 与 Playwright connection dispatcher 共用调度链路，重入 body() 会导致
                // NetworkError/Load failed。改为暂存 Response 并发布轻量 BODY_READY 信号，
                // 由 BodyReader 在 bodyFetchPool 线程按需异步读取（对用户无感、统一）。
                // ⭐ 只暂存 API 类资源（XHR/Fetch）的 Response：
                //   - 页面资源请求（图片/CSS/JS…）的 BODY_READY 会被 merger 快速路径忽略，
                //     不暂存可避免长会话中大量 Playwright Response 对象残留导致的内存泄漏；
                //   - 这里不随 onRequestFinished 清理（BodyReader 在 bodyFetchPool 异步执行，
                //     几乎必然晚于该回调），只由 readResponseBody 消费时移除。
                String rawType = request.resourceType();
                if (ResourceType.fromString(rawType).isApi()) {
                    if (pendingResponses.size() >= MAX_PENDING_RESPONSES) {
                        // 修复 P1-11：原实现整体 clear() 会导致其他在途请求的 body 读取变 null（断言偶发失败）。
                        // 改为弱一致性批量淘汰约 1/4 旧条目，保留热点响应，抑制无界增长同时不丢失在途数据。
                        evictPendingQuarter();
                    }
                    pendingResponses.put(requestId, response);
                }
                CaptureEvent bodyEvent = CaptureEvent.bodyReady(requestId, CaptureEvent.Source.PLAYWRIGHT)
                        .withContext(page.context(), page);
                ringBuffer.publish(bodyEvent);
            } catch (Exception e) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[PlaywrightEventCaptureStrategy] Error processing onResponse: {}", e.getMessage());
            }
        });

        // requestFinished 仅作为终态清理。Firefox/WebKit 不在任何 Playwright 回调内读取
        // response body，避免 connection dispatcher 重入；body 由 BodyReader 在 bodyFetchPool
        // 线程异步读取（Response.body() 在非 dispatcher 线程调用是安全的）。
        page.onRequestFinished(request -> finishRequest(request, "onRequestFinished"));
        page.onRequestFailed(request -> finishRequest(request, "onRequestFailed"));
    }

    private void finishRequest(Request request, String eventName) {
        if (!running) return;
        try {
            String requestId = correlator.finish(request);
            if (requestId != null) {
                // ⭐ 不能在此清理 pendingResponses：onRequestFinished 与本回调（onResponse）
                //   在同一 dispatcher 线程顺序触发，而 BodyReader 在 bodyFetchPool 异步执行，
                //   几乎必然晚于本回调 → 若在此移除，响应体读取会返回 null 而丢失。
                //   只由 readResponseBody 消费移除 + stop()/start() 清空。
            }
        } catch (Exception e) {
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[PlaywrightEventCaptureStrategy] Error processing {}: {}",
                    eventName, e.getMessage());
        }
    }

    /** ⭐ P2: 暴露关联器的诊断计数（驱逐 / 缺失完成），供 CaptureEngine.exchangeMetrics 聚合。 */
    public NetworkEventCorrelator getCorrelator() {
        return correlator;
    }

    @Override
    public void stop() {
        this.running = false;
        correlator.stop();
        pendingResponses.clear();
        // ⭐ Playwright Java API 不提供移除单个监听器的方法
        // 所以监听器只能注册一次（由 listenersRegistered 控制），stop() 仅停止事件处理
        // 当 Page 被关闭时，监听器会被自动清理，不会泄漏
        LOGGER.info("[PlaywrightEventCaptureStrategy] Stopped");
    }

    @Override
    public String name() {
        return "Playwright";
    }

    @Override
    public byte[] readResponseBody(String requestId) {
        // ⭐ 在 bodyFetchPool 线程调用（非 onResponse dispatcher 回调），Response.body() 安全。
        //   注意：读取失败/返回 null 时【不移除】对象，交由 BodyReader.readWithRetry 重试
        //   （否则首次读取失败后对象被 remove，后续重试永远取到 null，重试形同虚设）。
        //   仅当成功读取到非 null body 才移除，避免 long-lived map 泄漏。
        Response response = pendingResponses.get(requestId);
        if (response == null) {
            return null;
        }
        try {
            byte[] body = response.body();
            if (body != null) {
                pendingResponses.remove(requestId);
            }
            // ⭐ 响应体上限防 OOM：超大响应体（下载/大 JSON）截断后再入环缓冲与存储
            return RouteUtil.truncateBody(body);
        } catch (Exception e) {
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[PlaywrightEventCaptureStrategy] Error reading response body for reqId={}: {}",
                    requestId, e.getMessage());
            return null;
        }
    }

    /** 弱一致性批量移除约 1/4 的 pendingResponses（修复 P1-11）。不保证精确，足以抑制无界增长。
     * 注意：不能用 entrySet().iterator().remove()，ConcurrentHashMap 在结构变更时会抛 IllegalStateException；
     * 改为先收集候选 key，再逐个 remove(key)（原子且并发安全）。 */
    private void evictPendingQuarter() {
        int target = Math.max(1, pendingResponses.size() / 4);
        int removed = 0;
        for (Iterator<String> it = pendingResponses.keySet().iterator(); it.hasNext() && removed < target; ) {
            String key = it.next();
            pendingResponses.remove(key);
            removed++;
        }
    }

    // 请求身份和生命周期由 NetworkEventCorrelator 统一管理。
}