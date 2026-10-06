package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteEvidenceRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.exec.RouteIoExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.route.util.MediaType;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * API 信息采集器 —— CAPTURE 能力（横切观测）的记录与快照汇聚。
 *
 * <p>与 {@link MonitorSink}（断言导向）不同，本类是<b>信息导向</b>：采集规则（{@code capture()}
 * 修饰）与 mock/modify/delay/monitor 能力<b>正交共存</b>——同一请求同时被能力处理与被采集，
 * 两条路径无共享可变状态：
 * <ul>
 *   <li>请求侧 {@link #recordRequest} 在 route 事件线程（dispatch 入口，所有能力必经）调用，
 *       只入队（O(1)），不阻塞；</li>
 *   <li>响应侧 {@link #onResponseForSpec} 在响应轮询线程调用（由 {@code RouteRuntimeImpl} 轮询
 *       {@code Request#existingResponse()} 命中后按 spec 精确投递）：弹出 pending 快照 →
 *       volatile 写响应状态 → <b>就地</b>快照响应体（{@code Response.body()} 是协议调用，必须趁句柄
 *       存活时读；交给 IO 线程排队会因句柄被回收而永远采不到 body）→ 定案入队；</li>
 *   <li>{@link #dump} 在业务线程调用（消费式快照；含 pending 超时定案）。</li>
 * </ul>
 *
 * <p><b>不订阅 Playwright response 事件</b>：与 {@link MonitorSink} 同源——该订阅会令驱动在
 * {@code BrowserContextImpl.handleEvent} 解析已回收的 {@code response@} 句柄时抛
 * {@code Object doesn't exist}，并沿共享连接污染在途调用。改为路由通道按 spec 精确配对后，
 * 该事件根本不产生，竞态从根上消除（且响应配对不再依赖 URL 匹配，更精确）。
 *
 * <p>并发安全：线程之间只经 {@link ConcurrentLinkedQueue} 与 {@code volatile} 字段交换；
 * 入队（事件/IO 线程）与消费（业务线程）无锁。队列上限（{@link CaptureLimits#DEFAULT_MAX_CAPTURED}）
 * 由原子计数约束，超限丢弃新记录并计数（fail-open，防内存失控）。
 *
 * <p>防泄漏：等不到响应的请求在 {@link #dump} 时按超时窗口（默认 30s，复用
 * {@code ApiSpec.monitorTimeoutMs}）定案为 {@code timedOut} 快照并移出 pending。
 */
public final class CaptureSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureSink.class);

    private final int maxCaptured;
    private final long defaultTimeoutMs;
    private final RouteIoExecutor io;

    /** 已定案快照队列（业务线程 dump 消费）。 */
    private final ConcurrentLinkedQueue<CapturedApiCall> captured = new ConcurrentLinkedQueue<>();
    /** pattern → 未收到响应的请求快照（响应配对索引）。 */
    private final ConcurrentMap<String, ConcurrentLinkedQueue<CapturedExchange>> pendingByPattern =
            new ConcurrentHashMap<>();

    /** 队列上限计数（入队自增 / dump 消费递减；近似约束，防内存失控）。 */
    private final AtomicInteger count = new AtomicInteger();
    /** 超限丢弃数（可观测）。 */
    private final AtomicLong dropped = new AtomicLong();
    /** 超时定案数（可观测）。 */
    private final AtomicLong timedOutCount = new AtomicLong();
    /** 响应体读取失败数（可观测；失败仍保留核心快照）。 */
    private final AtomicLong bodyReadFailures = new AtomicLong();

    public CaptureSink(RouteIoExecutor io, int maxCaptured, long defaultTimeoutMs) {
        this.io = io;
        this.maxCaptured = maxCaptured;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    /**
     * 记录一次待采集请求（route 事件线程；capture 规则才记录）。
     *
     * @return true 表示已进入「等待响应」索引——调用方应据此启动响应轮询
     */
    public boolean recordRequest(Request request, ApiSpec spec) {
        if (!spec.captureEnabled()) {
            return false;
        }
        CapturedExchange snapshot = CapturedExchange.ofRequest(request, spec);
        pendingByPattern.computeIfAbsent(spec.pattern(), k -> new ConcurrentLinkedQueue<>()).add(snapshot);
        return true;
    }

    /**
     * 路由通道响应定案（按 spec 精确配对）——取代原 {@code context.onResponse} 事件订阅。
     *
     * <p>与请求侧 {@link #recordRequest} 使用同一 {@link ApiSpec} 实例，配对关系由调用方
     * （{@code RouteRuntimeImpl} 的响应轮询）保证，故<b>不再做 URL 匹配</b>。
     * 响应体采集（{@code captureBody}）交 IO 线程读取（截断 + 入队）；未开启则直接入队。
     *
     * @param spec     该请求在 dispatch 时命中的规则（与 recordRequest 同一实例）
     * @param response 已到达的真实响应（本地持有）；null 安全（忽略）
     */
    public void onResponseForSpec(ApiSpec spec, Response response) {
        if (spec == null || response == null || !spec.captureEnabled()) {
            return;
        }
        ConcurrentLinkedQueue<CapturedExchange> pending = pendingByPattern.get(spec.pattern());
        if (pending == null) {
            return;
        }
        CapturedExchange snapshot = pending.poll();
        if (snapshot == null) {
            return; // 无配对请求快照（响应早于采集规则注册 / 已 dump 定案）→ 忽略
        }
        snapshot.markResponse(response.status(), response.headers());
        if (spec.captureBodyEnabled()) {
            captureBodyNow(snapshot, spec, response);
        } else {
            enqueue(snapshot, null, false);
        }
    }

    /**
     * <b>就地</b>读取并快照响应体（响应轮询线程；<b>不再</b>交 IO 线程排队）。
     *
     * <p><b>为什么必须就地读（2026-09-28 修正）</b>：{@code Response.body()} 是协议调用，需要驱动侧
     * {@code response@<guid>} 句柄存活；旧实现把它丢给 IO 线程排队执行，实测在 IO 池繁忙（mock fetch 等）
     * 时 body 读晚数秒 ⇒ 句柄已被回收 ⇒ {@code Object doesn't exist: response@…} ⇒ 采集到的 body 恒为 null
     * （业务基于 body 的断言随之失真）。故改为"趁句柄存活时快照字节"，编码/截断仍在本次调用内完成
     * （纯 CPU，微秒级）。</p>
     *
     * <p>读取失败保持 fail-open：保留核心快照（body=null）并计数，绝不抛异常、绝不判场景失败。</p>
     */
    private void captureBodyNow(CapturedExchange snapshot, ApiSpec spec, Response response) {
        String contentType;
        byte[] bytes;
        try {
            contentType = response.headers().get("content-type");
            bytes = response.body();
        } catch (Throwable t) {
            bodyReadFailures.incrementAndGet();
            LOGGER.warn("[Route] capture body unavailable (driver handle reclaimed / page closed) "
                            + "pattern='{}' url='{}': {}",
                    spec.pattern(), snapshot.url(), t.toString());
            enqueue(snapshot, null, false);
            return;
        }
        try {
            int limit = spec.captureBodyLimitBytes();
            boolean truncated = bytes.length > limit;
            String body = new String(truncated ? Arrays.copyOf(bytes, limit) : bytes,
                    MediaType.parse(contentType).charset());
            enqueue(snapshot, body, truncated);
        } catch (Throwable t) {
            // 编码/截断失败（理论上不会）：同样 fail-open，保留核心快照
            bodyReadFailures.incrementAndGet();
            enqueue(snapshot, null, false);
        }
    }

    /** 定案入队（队列满则丢弃并计数）；定案即打一行日志（请求/响应头 + 体 + 耗时，头已脱敏、体已截断）。 */
    private void enqueue(CapturedExchange snapshot, String body, boolean truncated) {
        if (count.get() >= maxCaptured) {
            dropped.incrementAndGet();
            return;
        }
        CapturedApiCall call = toDto(snapshot, body, truncated);
        captured.add(call);
        // 非消费式历史（peek 用）：与消费式队列并行维护，保证"用例收尾复核真实流量"不会被
        // 框架自身的断言结算（走 dump ⇒ 抽干 captured）抢走。上限同 maxCaptured，超出丢最旧。
        history.add(call);
        while (history.size() > maxCaptured) {
            history.poll();
        }
        count.incrementAndGet();
        // 展示是纯旁路：任何异常都吞掉（含非 JSON 体、脱敏/格式化失败），绝不影响采集与主流程
        try {
            LOGGER.info("[Route] captured api {}", call.detail());
        } catch (RuntimeException e) {
            LOGGER.debug("[Route] capture log skipped: {}", e.toString());
        }
        // 捕获结果 → Serenity 报告：报告要能回答"采到了什么、成功没"。**纯采集规则没有响应侧期望**，
        // 不进入 MonitorSink 的观测队列，其结果只能从这里出来，故不能省。
        // 明细复用上面同一份脱敏渲染（头值打码、体已脱敏+截断、URL 由 CapturedApiCall 脱敏）。
        try {
            RouteEvidenceRegistry.record("CAPTURE", call.url(), call.detailWith(
                    "result    : CAPTURED (no assertion; 'response' section is what the browser actually received)"));
        } catch (Throwable t) {
            LOGGER.debug("[Route] capture evidence report skipped (non-fatal): {}", t.toString());
        }
    }

    /** 非消费式历史快照（{@link #peek()} 的数据源）：只增（按 {@code maxCaptured} 限量），不受 dump 影响。 */
    private final ConcurrentLinkedQueue<CapturedApiCall> history = new ConcurrentLinkedQueue<>();

    private static CapturedApiCall toDto(CapturedExchange snapshot, String body, boolean truncated) {
        return new CapturedApiCall(
                snapshot.pattern(), snapshot.method(), snapshot.url(),
                snapshot.requestHeaders(), snapshot.postDataPreview(),
                snapshot.responseStatus(), snapshot.responseHeaders(),
                body, truncated, snapshot.durationMs(), snapshot.responseTimedOut(),
                snapshot.recordedAtMillis());
    }

    /**
     * 取走全部已定案快照（消费式；幂等——第二次调用返回空列表）。
     *
     * <p>执行两件事：
     * <ol>
     *   <li><b>超时定案</b>：对仍在等待响应、且超过超时窗口的请求定案为 {@code timedOut}
     *       快照（{@code markTimedOut} + 入队 + 移出 pending），防止 pending 无限堆积；</li>
     *   <li><b>消费快照队列</b>：把已定案快照一次性返回并清空。</li>
     * </ol>
     *
     * @return 已定案快照（按定案顺序）；无记录时为空列表（永不返回 null）
     */
    public List<CapturedApiCall> dump() {
        sweepPendingTimeouts();
        List<CapturedApiCall> result = new ArrayList<>();
        CapturedApiCall call;
        while ((call = captured.poll()) != null) {
            count.decrementAndGet();
            result.add(call);
        }
        return result;
    }

    /**
     * 查看全部已定案快照（<b>非消费式</b>；幂等——重复调用返回同一批，直到被 {@link #dump()} 取走）。
     *
     * <p>数据源是<b>只增历史</b>（{@link #history}），而非消费式队列 {@code captured}：框架自身的断言结算
     * （走 {@link #dump()} ⇒ 抽干 captured）不会再让"用例收尾复核浏览器实际收到了什么"变成不可见 ——
     * 这正是本方法存在的理由（仅 peek 无法解决：共享队列被 dump 抽干后，谁都读不到）。清理仍以 dump 为准。</p>
     *
     * @return 已定案快照（按定案顺序）；无记录时为空列表（永不返回 null）
     */
    public List<CapturedApiCall> peek() {
        sweepPendingTimeouts();
        return new ArrayList<>(history);
    }

    /** 超时扫描（dump 内调用；弱一致遍历可接受）。 */
    private void sweepPendingTimeouts() {
        long now = System.nanoTime();
        for (ConcurrentLinkedQueue<CapturedExchange> pending : pendingByPattern.values()) {
            for (CapturedExchange snapshot : pending) {
                if (snapshot.responseStatus() != null || snapshot.responseTimedOut()) {
                    continue;
                }
                // 超时窗口恒定用本 sink 的默认值（横切语义：不受 monitor 断言超时影响）
                if (now - snapshot.recordedAtNanos() > defaultTimeoutMs * 1_000_000L) {
                    snapshot.markTimedOut();
                    timedOutCount.incrementAndGet();
                    pending.remove(snapshot);
                    enqueue(snapshot, null, false);
                }
            }
        }
    }

    /** 当前已定案未消费的快照数（可观测；消费后归零）。 */
    public int size() {
        return captured.size();
    }

    /** 超限丢弃总数（可观测）。 */
    public long droppedCount() {
        return dropped.get();
    }

    /** 超时定案总数（可观测）。 */
    public long timedOutCount() {
        return timedOutCount.get();
    }

    /** 响应体读取失败总数（可观测）。 */
    public long bodyReadFailureCount() {
        return bodyReadFailures.get();
    }
}
