package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 异步响应体读取器 — 按需惰性读取，浏览器事件线程零重入。
 *
 * <p>设计（消费者按需拉取）：
 * <ul>
 *   <li>生产者（CDP/Playwright 策略）只发布轻量 {@link CaptureEvent.Phase#BODY_READY} 信号，不读 body</li>
 *   <li>{@link EventMerger} 判定请求命中采集范围后，调用 {@link #requestBody} 触发异步读取</li>
 *   <li>读取在 {@link CaptureThreadPool} 的 bodyFetchPool 线程执行，绝不在浏览器事件线程</li>
 *   <li>无论成功失败都会投喂 {@code RESPONSE_BODY}（失败投喂 null body），保证 merger slot 一定闭合</li>
 * </ul>
 */
public class BodyReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(BodyReader.class);

    private final CaptureRingBuffer ringBuffer;
    private final CaptureThreadPool threadPool;
    /** 当前采集策略（响应体读取能力的提供方）；降级后由 CaptureEngine 重新 bind */
    private volatile CaptureStrategy strategy;

    public BodyReader(CaptureRingBuffer ringBuffer, CaptureThreadPool threadPool) {
        this.ringBuffer = ringBuffer;
        this.threadPool = threadPool;
    }

    /** 绑定当前策略（CaptureEngine 在策略选定/降级完成后调用）。 */
    public void bind(CaptureStrategy strategy) {
        this.strategy = strategy;
    }

    /** ⭐ 响应体读取重试次数上限：CDP Network.getResponseBody 在 loadingFinished 未到时
     *   调用会失败/返回空（Bug A：BODY_READY 延迟或缺失，兜底拉取可能早于响应体就绪），
     *   需要退避重试，避免一次失败就永久留下空 slot。
     *   慢网络下 loadingFinished 延迟可达数秒（实测 5s+），重试总窗口需覆盖之：
     *   5 次 × 500/1000/2000/4000ms 退避 = 最长 ~7.5s。 */
    private static final int MAX_READ_ATTEMPTS = 5;
    /** ⭐ 读取重试基础间隔（毫秒），按 2x 退避（500 → 1000 → 2000 → 4000） */
    private static final long READ_RETRY_BASE_DELAY_MS = 500;

    /**
     * 触发异步读取指定请求的响应体。
     *
     * <p>读取完成后投喂 {@code RESPONSE_BODY} 事件（失败也投喂 null body），
     * 保证 EventMerger 的 slot 一定闭合，不会因 body 缺失被拖到 stale 超时。
     *
     * <p>线程契约修复：读取重试的退避不再使用 {@link Thread#sleep(long)} 阻塞 bodyFetchPool
     * 线程。原先的 sleep 会让一个请求的退避期间独占线程池线程，导致并发 body 读取被串行化，
     * 在 Firefox/WebKit（CDP 通道不同、失败重试更频繁）下极易触发读取超时、body 为 null、
     * 进而引发断言误报。现改用 {@link CompletableFuture#delayedExecutor} 异步退避，
     * 退避期间线程被释放去处理其他请求的 body 读取，重试到期后再提交下一次读取，彻底并行。
     *
     * @param requestId 请求关联键
     * @param source    事件来源（CDP / PLAYWRIGHT），透传给 RESPONSE_BODY 事件
     */
    public void requestBody(String requestId, CaptureEvent.Source source) {
        // 完全异步：提交一个"启动读取"任务，读取与退避均在异步链内完成，
        // 不阻塞 bodyFetchPool 线程等待（线程契约：避免并发 body 读取串行化丢请求）。
        threadPool.submitBodyFetch(() -> readWithRetry(requestId, source));
    }

    /**
     * ⭐ Bug A 修复：带重试异步读取响应体。
     *
     * <p>EventMerger 的延迟兜底（RESPONSE_META 后 300ms）可能早于 CDP loadingFinished
     * 触发拉取，此时 getResponseBody 会失败或返回空；因此在失败/空 body 时按 2x 退避
     * 短暂重试，等待响应体真正就绪。所有尝试均失败时投喂 null body（slot 仍以 null body 闭合，
     * 调用本身照常入库，仅 body 为 null）。
     *
     * <p>线程契约修复：退避通过 {@code CompletableFuture.delayedExecutor} 调度到独立的
     * bodyFetchScheduler 线程，当前 bodyFetchPool 线程在每次尝试后立即返回（不调用 get() 阻塞），
     * 从而支持任意并发数的 body 读取互不串行化——这是 Firefox/WebKit 下不丢请求的关键。
     */
    private void readWithRetry(String requestId, CaptureEvent.Source source) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        retryOnce(requestId, 1, READ_RETRY_BASE_DELAY_MS, future);
        future.whenComplete((body, ex) -> {
            // 无论成功/失败/超时，均投喂 RESPONSE_BODY 事件闭合 merger slot（失败投 null）
            byte[] result = (body != null) ? body : null;
            ringBuffer.publish(CaptureEvent.responseBody(requestId, result, null, source));
        });
    }

    /** 递归异步重试：每次失败/空 body 后按 2x 退避提交下一次读取，不阻塞线程。 */
    private void retryOnce(String requestId, int attempt, long delayMs,
                           CompletableFuture<byte[]> result) {
        CaptureStrategy s = strategy;
        if (s == null) {
            result.complete(null);
            return;
        }
        try {
            byte[] body = s.readResponseBody(requestId);
            if (body != null) {
                result.complete(body);
                return;
            }
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[BodyReader] Empty body for reqId={}, attempt {}/{}", requestId, attempt, MAX_READ_ATTEMPTS);
        } catch (Exception e) {
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[BodyReader] Failed to read body for reqId={}, attempt {}/{}: {}",
                    requestId, attempt, MAX_READ_ATTEMPTS, e.getMessage());
        }
        if (attempt < MAX_READ_ATTEMPTS) {
            CompletableFuture.runAsync(() -> retryOnce(requestId, attempt + 1, delayMs * 2, result),
                    CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS, threadPool.bodyFetchScheduler()));
        } else {
            result.complete(null);
        }
    }
}
