package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec.RouteIoExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.util.MediaType;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 观测汇聚器 —— MONITOR 能力的记录与响应断言。
 *
 * <p><b>响应侧获取方式：路由通道（绝不订阅 response 事件）</b>。
 * Playwright 客户端在 {@code BrowserContextImpl.handleEvent} 的 {@code "response"} 分支会为每个事件
 * 句柄调用 {@code Connection.getExistingObject(guid)}；一旦服务端已回收该 {@code response@} 句柄即抛
 * {@code PlaywrightException: Object doesn't exist}。该异常发生在业务 lambda <b>之前</b>、无法被回调内
 * try/catch 拦截，且因 {@code Connection} 为共享单连接，会以「此刻正在等结果的任意调用线程」为宿主抛出
 * （本仓库实测：会话校验导航被炸 → 误判会话失效 → 每轮全量重登）。故本类<b>不订阅</b>该事件：
 * 由 {@code RouteRuntimeImpl} 在 route 分发入口拿到 {@link Request} 后，于调度线程轮询
 * {@link Request#existingResponse()}（请求本地字段、零协议往返、不触碰对象表），命中即以
 * {@link #onResponseForSpec(ApiSpec, Response)} 按 <b>spec 精确配对</b>投递。
 *
 * <p>并发模型：
 * <ul>
 *   <li>{@code recordRequest} 在 route 事件线程调用（入队，不阻塞），返回是否已进入等待响应索引；</li>
 *   <li>{@code onResponseForSpec} 在调度线程调用（volatile 更新 + 入队，不阻塞）；
 *       若规则配置了 JSONPath 断言，则响应体读取（{@code Response.body()} 是同步阻塞调用）
 *       与 JSONPath 断言一律交 IO 线程执行——保持调度线程零阻塞契约；</li>
 *   <li>{@code drain} 在业务线程调用（快照消费）。</li>
 * </ul>
 * 线程之间只经 {@link ConcurrentLinkedQueue} 与 {@code volatile} 字段交换，无共享可变状态。
 *
 * <p>防泄漏：等不到响应的请求在 {@code drain} 时按 monitorTimeoutMs 标记超时并移出 pending 索引。
 */
public final class MonitorSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorSink.class);

    private final ConcurrentLinkedQueue<CapturedExchange> exchanges = new ConcurrentLinkedQueue<>();
    /** 已定案且断言失败的交换（上报队列；事件线程/IO 线程入队，业务线程 drain 消费）。 */
    private final ConcurrentLinkedQueue<CapturedExchange> settledFailures = new ConcurrentLinkedQueue<>();
    /** pattern → 未收到响应的请求队列（响应断言索引，同一对象也存在于 exchanges）。 */
    private final ConcurrentMap<String, ConcurrentLinkedQueue<CapturedExchange>> pendingByPattern = new ConcurrentHashMap<>();
    private final RouteIoExecutor io;
    /** body 断言因 IO 队列满被丢弃的次数（fail-open 可观测指标）。 */
    private final AtomicLong rejectedBodyAssertions = new AtomicLong(0);
    /** per-spec 命中计数（autoStopOnMatch + minMatches 对齐：达到后停止记录/断言）。 */
    private final ConcurrentMap<ApiSpec, AtomicInteger> hitCounts = new ConcurrentHashMap<>();

    public MonitorSink(RouteIoExecutor io) {
        this.io = io;
    }

    /**
     * 记录一次被观测请求（route 分发入口调用，非阻塞）。
     *
     * <p><b>只记录「有响应侧期望」的 MONITOR 规则</b>（有 expectStatus 或 body 断言）。其余一律不记录：
     * MOCK / MODIFY / DELAY 能力（以及无任何断言的 MONITOR 规则）既无断言可做，也不该进入观测队列 ——
     * 它们永远等不到响应配对（{@link #onResponseForSpec} 对非 MONITOR 能力直接返回），却会被
     * {@link #drainSettledFailures()} 的超时扫描判成「监控超时」并<b>归因到场景失败</b>。
     *
     * <p>实测误报（2026-09-28）：MOCK 规则 {@code profile/list} 命中后，只要步骤继续存活超过
     * {@code monitorTimeoutMs}（默认 30s），就稳定产出
     * {@code [timeout] POST …/profile/list (pattern=profile/list, expect=null, timeoutMs=30000)}，
     * 把一个完全正常的 mock 请求变成场景失败。另外该队列在生产路径无消费者（{@code drain()} 仅测试调用），
     * 无条件记录还会随流量无界增长。
     *
     * @return true 表示该请求已进入「等待响应」索引——调用方应据此启动响应轮询；
     *         false 表示无需响应（未开启记录 / 非 MONITOR 能力 / 无任何响应侧期望 / auto-stop 已达标）
     */
    public boolean recordRequest(Request request, ApiSpec spec) {
        if (!spec.recordEnabled()) {
            return false; // record(false)：不记录请求/响应信息（对齐现有 RouteDsl.record）
        }
        // 能力与期望双重闸门：只有「MONITOR + 至少一条响应侧期望」才值得观测（见方法注释）
        if (spec.capability() != RouteCapability.MONITOR
                || (spec.expectStatus() == null && !spec.hasBodyAssertions())) {
            return false;
        }
        if (spec.autoStopOnMatch()) {
            AtomicInteger counter = hitCounts.computeIfAbsent(spec, k -> new AtomicInteger());
            if (counter.incrementAndGet() > spec.minMatches()) {
                // 已达最小匹配次数：停止后续记录与断言（对齐老版 auto-stop 的近似语义；
                // 老版为 unroute，V2 为跳过处理——路由仍注册，重新命中不影响）
                return false;
            }
        }
        CapturedExchange exchange = CapturedExchange.ofRequest(request, spec);
        exchanges.add(exchange);
        pendingByPattern.computeIfAbsent(spec.pattern(), k -> new ConcurrentLinkedQueue<>()).add(exchange);
        return true;
    }

    /**
     * 路由通道响应定案（按 spec 精确配对）——取代原 {@code context.onResponse} 事件订阅。
     *
     * <p><b>调用方</b>：{@code RouteRuntimeImpl} 在 route 分发入口拿到 {@link Request} 后，
     * 于调度线程轮询 {@link Request#existingResponse()}（请求本地字段、零协议往返）命中即投递。
     * 与请求侧 {@link #recordRequest} 使用同一 {@link ApiSpec} 实例，配对关系由调用方保证，
     * 故这里<b>不再做 URL 匹配</b>——也就<b>不再需要</b> Playwright 响应事件订阅
     * （订阅是驱动侧 {@code Object doesn't exist: response@…} 竞态的唯一触发源，见类注释）。
     *
     * <p>断言策略：
     * <ul>
     *   <li>无 body 断言 → 调度线程直接 volatile 写 status 断言（零阻塞）；</li>
     *   <li>有 body 断言（jsonPath / bodyContains / bodyRegex / formField）→ 先写 status，
     *       再把「读 body + 内容类型感知断言（{@link PayloadAssertor}）」投递 IO 线程；
     *       队列满时丢弃断言但保留 status（fail-open，绝不阻塞调度线程）。</li>
     * </ul>
     *
     * @param spec     该请求在 dispatch 时命中的规则（与 recordRequest 同一实例）
     * @param response 已到达的真实响应（本地持有）；null 安全（忽略）
     */
    public void onResponseForSpec(ApiSpec spec, Response response) {
        if (spec == null || response == null) {
            return;
        }
        if (spec.capability() != RouteCapability.MONITOR
                || (spec.expectStatus() == null && !spec.hasBodyAssertions())) {
            return;
        }
        ConcurrentLinkedQueue<CapturedExchange> pending = pendingByPattern.get(spec.pattern());
        if (pending == null) {
            return;
        }
        CapturedExchange exchange = pending.poll();
        if (exchange == null) {
            return; // 无配对请求快照（已被 drain 定案 / auto-stop 未记录）→ 忽略
        }
        exchange.markResponse(response.status(), response.headers());
        if (spec.hasBodyAssertions()) {
            submitBodyAssertion(exchange, spec, response);
        } else if (Boolean.FALSE.equals(exchange.assertionPassed())) {
            // 无 body 断言：响应到达即定案；断言失败立即结算上报（成功项不结算，观测完成）
            settleFailure(exchange);
        }
    }

    /** 投递 body 断言到 IO 线程（响应轮询线程不读 body，不解析 content-type）。 */
    private void submitBodyAssertion(CapturedExchange exchange, ApiSpec spec, Response response) {
        boolean submitted = io.trySubmit("monitor-assert:" + spec.pattern(), () -> {
            if (exchange.responseTimedOut()) {
                return; // 已被 drain 标记超时，断言结果无意义
            }
            try {
                String contentType = response.headers().get("content-type");
                Charset charset = MediaType.parse(contentType).charset();
                byte[] rawBody = response.body();
                String body = new String(rawBody, charset);
                List<String> failures = new ArrayList<>(PayloadAssertor.assertAll(spec, body, contentType));
                if (contentType == null && !failures.isEmpty()) {
                    // content-type 缺失时补「响应形态」诊断：一次运行即可区分
                    // 「服务端确实没发 Content-Type」与「空体/异常响应（如会话失效的空 200）」。
                    // 只上报 header 名与字节数，绝不上报 header 值/响应体（防 session/凭据泄露）。
                    failures.add("response diagnosis: headerNames=" + headerNames(response)
                            + ", bodyBytes=" + rawBody.length);
                }
                exchange.markBodyAssertionFailures(failures);
                // body 断言（含 status 重算）定案：失败立即结算上报
                if (Boolean.FALSE.equals(exchange.assertionPassed())) {
                    settleFailure(exchange);
                }
            } catch (Exception e) {
                // 读 body / 解析失败：记为失败明细（不允许异常逃逸 IO 线程包装）
                exchange.markBodyAssertionFailures(List.of("body-read-error: " + e.getMessage()));
                settleFailure(exchange);
            }
        });
        if (!submitted) {
            rejectedBodyAssertions.incrementAndGet();
            LOGGER.warn("[RouteV2] monitor body assertion dropped (IO queue full), pattern='{}'",
                    spec.pattern());
        }
    }

    /**
     * 响应 header <b>名</b>清单（小写、去重、字典序）；<b>只暴露名字，不暴露值</b>——
     * 用于 content-type 缺失时的形态诊断（如仅含 date/content-length 说明服务端确实没发该头）。
     * 读取失败时返回哨兵值，不影响失败明细上报。
     */
    private static List<String> headerNames(Response response) {
        try {
            Set<String> names = new TreeSet<>();
            for (String name : response.headers().keySet()) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
            return new ArrayList<>(names);
        } catch (Exception e) {
            return List.of("<header-read-failed>");
        }
    }

    /**
     * 消费全部观测记录（含未收到响应的超时标记）。
     *
     * <p>超时定案与 {@link #drainSettledFailures()} 走同一结算路径
     * （{@code settleFailure} + CAS 去重）：先 drain() 再 drainSettledFailures() 也不会
     * 漏掉超时失败（drain 内 markTimedOut 后即结算入队）。
     *
     * @return 从最早到最新的全部快照
     */
    public List<CapturedExchange> drain() {
        List<CapturedExchange> result = new ArrayList<>();
        long now = System.nanoTime();
        CapturedExchange exchange;
        while ((exchange = exchanges.poll()) != null) {
            if (exchange.responseStatus() == null && !exchange.responseTimedOut()) {
                long timeoutMs = exchange.monitorTimeoutMs();
                if (timeoutMs != 0 && now - requestTimeNanos(exchange) > timeoutMs * 1_000_000L) {
                    exchange.markTimedOut();
                    settleFailure(exchange);
                    removeFromPending(exchange);
                }
            }
            result.add(exchange);
        }
        return result;
    }

    /** 当前在途观测数（含 pending）。 */
    public int size() {
        return exchanges.size();
    }

    /** 因 IO 队列满被丢弃的 body 断言数。 */
    public long rejectedBodyAssertions() {
        return rejectedBodyAssertions.get();
    }

    /**
     * 结算一条断言失败的交换（幂等：每条失败只入队一次）。
     *
     * <p>调用方：响应轮询线程（无 body 断言定案）、IO 线程（body 断言定案）、
     * {@link #drainSettledFailures()}（超时定案）。CAS 保证多路径并发下不重复上报。
     */
    private void settleFailure(CapturedExchange exchange) {
        if (exchange.tryMarkSettled()) {
            settledFailures.add(exchange);
        }
    }

    /**
     * 取走全部已定案且断言失败的交换（消费式；幂等——第二次调用返回空列表）。
     *
     * <p>执行两件事：
     * <ol>
     *   <li><b>超时扫描</b>：对仍在等待响应、且超过 monitor 超时窗口的请求做超时定案
     *       （{@code markTimedOut} + 结算），保证"请求发出但迟迟无响应"也会被上报；</li>
     *   <li><b>消费失败队列</b>：把已结算的失败交换一次性返回并清空。</li>
     * </ol>
     *
     * <p>线程安全：遍历 {@link ConcurrentLinkedQueue} 弱一致（超时定案可能滞后一拍，可接受）；
     * 消费队列无锁。未定案的请求（有期望但响应未到、未超时）保留在观测队列继续等待，
     * 不会被本方法误取，符合 timeout 窗口语义。
     *
     * @return 已定案失败交换快照（按结算顺序）；无失败时为空列表（永不返回 null）
     */
    public List<CapturedExchange> drainSettledFailures() {
        long now = System.nanoTime();
        for (CapturedExchange exchange : exchanges) {
            if (exchange.responseStatus() == null && !exchange.responseTimedOut()) {
                long timeoutMs = exchange.monitorTimeoutMs();
                if (timeoutMs != 0 && now - exchange.recordedAtNanos() > timeoutMs * 1_000_000L) {
                    exchange.markTimedOut();
                    settleFailure(exchange);
                    removeFromPending(exchange);
                }
            }
        }
        List<CapturedExchange> result = new ArrayList<>();
        CapturedExchange exchange;
        while ((exchange = settledFailures.poll()) != null) {
            result.add(exchange);
        }
        return result;
    }

    /** 当前已结算未消费的失败数（可观测性；消费后归零）。 */
    public int settledFailureCount() {
        return settledFailures.size();
    }

    private long requestTimeNanos(CapturedExchange exchange) {
        return exchange.recordedAtNanos();
    }

    private void removeFromPending(CapturedExchange exchange) {
        ConcurrentLinkedQueue<CapturedExchange> pending = pendingByPattern.get(exchange.pattern());
        if (pending != null) {
            pending.remove(exchange);
        }
    }
}
