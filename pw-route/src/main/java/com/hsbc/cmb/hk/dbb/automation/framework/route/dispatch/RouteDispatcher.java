package com.hsbc.cmb.hk.dbb.automation.framework.route.dispatch;

import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.route.claim.RouteClaim;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.hsbc.cmb.hk.dbb.automation.framework.route.modify.RequestBodyModifier;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.util.ApiMatcher;
import com.hsbc.cmb.hk.dbb.automation.framework.route.util.FieldReplacer;
import com.hsbc.cmb.hk.dbb.automation.framework.route.util.MediaType;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 分发裁决器 —— 事件线程（Playwright 回调线程）的唯一入口，全程无阻塞。
 *
 * <p>执行契约（对齐 playwright-java-1.62.0 的 PendingHandler 机制）：
 * <ul>
 *   <li><b>事件线程绝不阻塞</b>：resume/fulfill 是异步命令（sendMessageAsync）；
 *       fetch/sleep 一律交 IO 线程，事件线程返回 pending（驱动返回 PendingHandler，请求挂起）；</li>
 *   <li><b>单所有权</b>：{@link RouteClaim} CAS 保证每个 Route 恰好一个终结者；</li>
 *   <li><b>匹配条件链式裁决</b>：规则匹配条件（method/resourceType/header/query/body…）不满足时
 *       {@code fallback}（驱动级 Fallback，不置 handled），Router 自动把请求交给下一个匹配
 *       pattern 的 handler；全部不满足时驱动自动 resume——请求永不悬挂；</li>
 *   <li><b>fail-open</b>：预算耗尽 / 队列满 / 规则退役 / 异常 → fallback 放行；</li>
 *   <li><b>额度</b>：延迟终结（DELAY / MOCK intercept）必须占 PendingGuard 额度，
 *       超限立即 fallback，防页面批量请求假死。</li>
 * </ul>
 */
public final class RouteDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteDispatcher.class);

    /** DELAY 上限：单请求最多延迟毫秒数（防配置失误导致长悬挂）。 */
    private static final long MAX_DELAY_MS = 30_000L;

    /** T1 度量基线：进入分发器的请求数（= 路由层被触发的次数，即"下发次数"）。 */
    private final AtomicLong dispatchCount = new AtomicLong(0);
    /** T1 度量基线：真正命中规则条件并触发能力的请求数（<= 下发次数，即"路由命中数"）。 */
    private final AtomicLong hitCount = new AtomicLong(0);

    /** spec → 预编译匹配器缓存（ApiSpec 不可变，实例即稳定 key；同一 spec 只编译一次）。 */
    private final ConcurrentMap<ApiSpec, ApiMatcher> matcherCache = new ConcurrentHashMap<>();

    public void dispatch(Route route, String pattern, RouteRuntime runtime) {
        dispatchCount.incrementAndGet();
        // 1) 唯一 claim（防重放 / 防并发终结）
        RouteClaim claim = runtime.claims().tryClaim(route);
        if (claim == null) {
            // 同一 Route 已被其它 pattern 处理或已终结 → fail-open 放行，绝不重复处理
            RouteAction.fallback(route);
            return;
        }

        // 2) 读规则快照（无锁）
        ApiSpec spec = runtime.generations().snapshot().specFor(pattern);
        if (spec == null) {
            // 规则已退役（代际切换 / 注销竞态窗口）→ fail-open
            runtime.claims().markTerminal(claim, false);
            RouteAction.fallback(route);
            return;
        }

        // 3) 匹配条件过滤：条件不满足 → 终结本规则的 claim 并 fallback（链式裁决）。
        ApiMatcher matcher = matcherCache.computeIfAbsent(spec, ApiMatcher::from);
        if (!matcher.matches(route.request())) {
            runtime.claims().markTerminal(claim, false);
            RouteAction.fallback(route);
            return;
        }

        // 4) 单规则多能力位（参照 V1 RouteHandleType / PriorityPolicy）：同 pattern 的多条规则合并为一条，
        //    在 dispatcher 内按 executionOrder 统一编排，各能力位独立可停（stop* / 退役）。
        //    各能力位"字段就绪且未停止"才视为活跃；停止位直接跳过（链式裁决交给下一个 pattern）。
        boolean hasDelay = !spec.isStopped(RouteCapability.DELAY)
                && (spec.delayMs() > 0 || spec.delayMinMs() > 0 || spec.delayMaxMs() > 0);
        boolean hasModify = !spec.isStopped(RouteCapability.MODIFY_REQUEST) && spec.hasModifyFields();
        boolean hasMock = !spec.isStopped(RouteCapability.MOCK) && spec.hasMockFields();
        boolean hasMonitor = !spec.isStopped(RouteCapability.MONITOR) && spec.hasMonitorFields();
        // CAPTURE 是横切观测，不是可停止的能力位（无 RouteCapability.CAPTURE 枚举），
        // 故以 spec.captureEnabled() 单独判定；纯采集规则（如 .monitor().capture() 不带断言）必须照常观测。
        boolean hasCapture = spec.captureEnabled();

        if (!hasDelay && !hasModify && !hasMock && !hasMonitor && !hasCapture) {
            // 无任何活跃能力位（全部停止或纯无观测）→ 链式裁决交给下一个 pattern / 真实网络
            // （对齐旧 stop 语义：已停止能力位不作用于请求；能力未停止的 MONITOR/capture 才放行）。
            runtime.claims().markTerminal(claim, false);
            RouteAction.fallback(route);
            return;
        }

        // 4.5) 统一观测（所有能力必经）：MONITOR 记录 + CAPTURE 采集。
        //      MONITOR 已停时 recordObservation 内部自动跳过其响应轮询；capture 维度不受影响。
        hitCount.incrementAndGet(); // 已通过匹配条件与停止判定 ⇒ 确为一次"命中"
        runtime.recordObservation(route.request(), spec);

        LOGGER.info("[Route] captured {} route for '{}'{}",
                spec.capability(), spec.pattern(),
                RouteDsl.describeCaptured(spec, route.request().method(), route.request().url()));

        // 6) 上报命中证据（观测旁路）。挂点选在此处：本段"所有能力必经"—— DELAY / MODIFY_REQUEST /
        //    MOCK / MONITOR / CAPTURE 全部经过，一处即覆盖四种能力。落到哪里由 SPI 实现决定
        //    （pw-web-ui 提供 Serenity 实现：入队 + 主线程 flush）；无实现即静默空操作。
        //    正文＝【完整请求信息】（方法/URL/头/体；头值脱敏、体截断）+ 规则声明；命中时刻还没有响应，
        //    响应侧与"实际做了什么"由后续「结果」证据给出（MOCK/MODIFY/DELAY 见 reportOutcome，
        //    MONITOR/CAPTURE 由各自 sink 在定案时上报）。
        recordHitEvidence(spec, route.request());

        // 5) 时序编排：DELAY(1) → MODIFY(2) → MOCK(3，终结｜否则 resume 真实网络) → MONITOR(4，叠加观察，入口已记录)。
        //    单 handler 内异步链式执行：DELAY 挂起到点后继续 MODIFY / MOCK；MOCK 命中即短路 fulfill。
        try {
            Consumer<String> terminal = (modifiedBody) ->
                    mockOrResume(route, spec, claim, runtime, hasMock, modifiedBody);
            Runnable afterDelay = () -> modifyStage(route, spec, claim, runtime, terminal);
            if (hasDelay) {
                dispatchDelayChain(route, spec, claim, runtime, afterDelay);
            } else {
                afterDelay.run();
            }
        } catch (Throwable t) {
            // 事件线程兜底：任何未预期异常都不得让请求悬挂
            LOGGER.warn("[Route] dispatch failed for url='{}', pattern='{}': {}",
                    route.request().url(), pattern, t.toString());
            try {
                RouteAction.fallback(route);
            } catch (Exception ignored) {
                LOGGER.trace("[Route] fallback after dispatch error ignored: {}", ignored.toString());
            }
            runtime.claims().markTerminal(claim, false);
        }
    }

    /**
     * 链尾：MOCK 命中 → fulfill 短路（经 {@link #dispatchMock}）；否则放行真实网络
     * （MODIFY 的 headers/method 与 modifiedBody 经此方法上路）。
     */
    private void mockOrResume(Route route, ApiSpec spec, RouteClaim claim, RouteRuntime runtime,
                              boolean hasMock, String modifiedBody) {
        // 事件线程先把请求句柄取好：resume 之后 Route 已终结，仍从句柄取"完整请求信息"最稳妥。
        Request request = route.request();
        if (hasMock) {
            dispatchMock(route, spec, claim, runtime);
        } else if (spec.hasModifyFields() || modifiedBody != null) {
            // MODIFY 改了 headers/method/body → 经 ResumeOptions 上路（无修改则等价放行）。
            RouteAction.resume(route, spec, modifiedBody);
            runtime.claims().markTerminal(claim, true);
            reportOutcome(spec.capability().name() + " RESULT", spec, request, null, null, null,
                    "resume real network; " + modifyLine(spec, modifiedBody));
        } else {
            // 纯放行（DELAY/MONITOR/无修改）：与原 MONITOR/DELAY 行为一致 = route.resume() 无参。
            RouteAction.resume(route);
            runtime.claims().markTerminal(claim, true);
            if (spec.capability() == RouteCapability.DELAY) {
                reportOutcome("DELAY RESULT", spec, request, null, null, null,
                        "delayed " + spec.delayMs() + "ms then resumed real network");
            }
        }
    }

    // ── 路由证据（命中 / 结果）──

    /** 证据正文里的体预览上限：防大夹具把报告撑爆（要完整体请用 CAPTURE 能力）。 */
    private static final int EVIDENCE_BODY_MAX = 4096;

    /** 体预览（脱敏由 {@code CapturedApiCall} 统一负责，这里只截断）。 */
    private static String preview(String body) {
        if (body == null) {
            return null;
        }
        return body.length() <= EVIDENCE_BODY_MAX ? body : body.substring(0, EVIDENCE_BODY_MAX) + "...[truncated]";
    }

    /** 规则声明行（expectStatus / timeout / delay / mock 内容…），复用 {@link RouteDsl} 的既有格式化。 */
    private static String ruleLine(ApiSpec spec, Request request) {
        return RouteDsl.describeCaptured(spec,
                request == null ? "-" : request.method(),
                request == null ? "-" : request.url()).trim();
    }

    /**
     * 命中证据上报：<b>完整请求信息</b>（方法/URL/头/体）+ 规则声明。
     *
     * <p>旁路语义：构造失败一律退化为规则声明，绝不影响派发链（观测设施不得成为故障源）。</p>
     */
    private static void recordHitEvidence(ApiSpec spec, Request request) {
        String detail;
        try {
            detail = CapturedApiCall.ofRequest(spec.pattern(), request, null, null, null, false, -1, false)
                    .detailWith("rule      : " + ruleLine(spec, request),
                            "stage     : HIT (outcome is reported by the matching 'RESULT' block)");
        } catch (Throwable t) {
            // 请求句柄不可用（如测试替身）→ 退化为纯规则声明，绝不向上抛
            try {
                detail = RouteDsl.describeCaptured(spec, request.method(), request.url());
            } catch (Throwable ignored) {
                detail = "HIT (request info unavailable)";
            }
        }
        com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteEvidenceRegistry.record(
                spec.capability().name(), request == null ? null : request.url(), detail);
    }

    /**
     * 结果证据上报：请求信息 + 规则声明 + 实际动作（+ 伪造/改写后的响应，若有）。
     *
     * <p>覆盖 MOCK / MODIFY_REQUEST / DELAY 三种"自己产出结果"的能力；MONITOR / CAPTURE 的响应侧结果
     * 由 {@code MonitorSink}/{@code CaptureSink} 在定案时上报。旁路：任何异常只记 DEBUG。</p>
     */
    private static void reportOutcome(String operation, ApiSpec spec, Request request,
                                      Integer status, Map<String, String> responseHeaders,
                                      String responseBody, String actionLine) {
        try {
            String result = status == null
                    ? "APPLIED (real response is reported by the matching MONITOR/CAPTURE block)"
                    : "FULFILLED (browser received status=" + status + ")";
            com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteEvidenceRegistry.record(
                    operation, request.url(),
                    CapturedApiCall.ofRequest(spec.pattern(), request, status, responseHeaders,
                                    preview(responseBody), false, -1, false)
                            .detailWith("rule      : " + ruleLine(spec, request),
                                    "applied   : " + actionLine,
                                    "result    : " + result));
        } catch (Throwable t) {
            LOGGER.debug("[Route] outcome evidence skipped (non-fatal): {}", t.toString());
        }
    }

    /** MODIFY 的动作说明（改了哪些头 / 方法 / 体），供结果证据的 {@code applied} 行。 */
    private static String modifyLine(ApiSpec spec, String modifiedBody) {
        StringBuilder m = new StringBuilder(96);
        if (!spec.requestHeadersToSet().isEmpty()) {
            m.append("setHeader=").append(spec.requestHeadersToSet().keySet());
        }
        if (!spec.requestHeadersToRemove().isEmpty()) {
            m.append(m.length() > 0 ? " " : "").append("removeHeader=").append(spec.requestHeadersToRemove());
        }
        if (spec.modifyMethod() != null) {
            m.append(m.length() > 0 ? " " : "").append("method=").append(spec.modifyMethod());
        }
        if (modifiedBody != null) {
            m.append(m.length() > 0 ? " " : "").append("bodyModified=true");
        }
        return m.length() == 0 ? "no modified fields" : m.toString();
    }

    /** MOCK：静态伪造直接 fulfill；intercept / 字段替换交 IO 线程。 */
    private void dispatchMock(Route route, ApiSpec spec, RouteClaim claim, RouteRuntime runtime) {
        // 事件线程取好请求句柄：IO 线程的 fulfill 分支也要用同一份"完整请求信息"上报结果
        Request request = route.request();
        boolean hasStaticBody = spec.mockStatus() != null || spec.mockBody() != null
                || spec.mockContentType() != null || !spec.mockHeaders().isEmpty();
        if (hasStaticBody && spec.mockReplacements().isEmpty()
                && spec.conditionalReplacements().isEmpty()) {
            RouteAction.fulfill(route, spec);
            runtime.claims().markTerminal(claim, true);
            reportOutcome("MOCK RESULT", spec, request, spec.mockStatus(), spec.mockHeaders(), spec.mockBody(),
                    "static fulfill (status=" + (spec.mockStatus() == null ? "default 200" : spec.mockStatus())
                            + ", body=" + (spec.mockBody() == null ? "empty"
                                    : spec.mockBody().length() + " chars") + ")");
            return;
        }
        // 需要 IO：intercept fetch（无静态体）或 字段替换（静态体 / 真实响应）
        if (!acquireIoSlot(claim, runtime)) {
            RouteAction.fallback(route);
            runtime.claims().markTerminal(claim, false);
            return;
        }
        // 事件线程（此刻 Request 句柄仍有效）：为 intercept fetch 取好请求快照（值类型、复制 headers）；
        // 取响应用 runtime 所属 Context 的 APIRequestContext —— 不依赖任何 frame/Request 句柄。
        final RouteAction.RequestSnapshot fallbackSnapshot =
                hasStaticBody ? null : RouteAction.RequestSnapshot.of(route.request());
        final APIRequestContext fallbackCtx = hasStaticBody ? null : runtime.request();
        boolean submitted = runtime.io().trySubmit("mock:" + spec.pattern(), () -> {
            if (claim.isTerminal()) {
                return;
            }
            try {
                if (hasStaticBody) {
                    // 静态体 + 字段替换：IO 线程替换后 fulfill
                    Map<String, String> headers = new LinkedHashMap<>(spec.mockHeaders());
                    if (spec.mockContentType() != null) {
                        headers.put("content-type", spec.mockContentType());
                    }
                    String body = spec.mockBody();
                    FieldReplacer.ReplaceResult replaced = FieldReplacer.replace(
                            MediaType.parse(spec.mockContentType()), body, spec.mockReplacements(),
                            spec.conditionalReplacements());
                    logReplacementFailures(spec.pattern(), replaced.failures());
                    RouteAction.fulfillWithBody(route, spec.mockStatus(), headers, replaced.body());
                    runtime.claims().markTerminal(claim, true);
                    reportOutcome("MOCK RESULT", spec, request, spec.mockStatus(), headers, replaced.body(),
                            "static body + field replacement (replacePaths=" + spec.mockReplacements().keySet()
                                    + ", failures=" + replaced.failures().size() + ")");
                    return;
                }
                // intercept：用事件线程取好的快照 + context.request() 取真实响应（不依赖 frame/Request 句柄）
                Optional<APIResponse> response = Optional.empty();
                if (fallbackCtx != null && fallbackSnapshot != null) {
                    response = runtime.ops().tryRun("route.fetch",
                            () -> RouteAction.fetch(fallbackCtx, fallbackSnapshot));
                }
                if (response.isEmpty()) {
                    // fetch 失败 / 预算耗尽 → fail-open（若尚未被巡检兜底）
                    if (!claim.isTerminal()) {
                        RouteAction.fallback(route);
                        runtime.claims().markTerminal(claim, false);
                    }
                    return;
                }
                APIResponse apiResponse = response.get();
                try {
                    if (spec.mockReplacements().isEmpty()
                            && spec.conditionalReplacements().isEmpty()) {
                        RouteAction.fulfillWithResponse(route, apiResponse);
                        runtime.claims().markTerminal(claim, true);
                        reportOutcome("MOCK RESULT", spec, request, apiResponse.status(), apiResponse.headers(), null,
                                "intercept: replay real response as-is "
                                        + "(body not read here; full body in the matching CAPTURE block)");
                        return;
                    }
                    String contentType = apiResponse.headers().get("content-type");
                    MediaType mediaType = MediaType.parse(contentType);
                    String body = new String(apiResponse.body(), mediaType.charset());
                    FieldReplacer.ReplaceResult replaced = FieldReplacer.replace(mediaType, body, spec.mockReplacements(),
                            spec.conditionalReplacements());
                    logReplacementFailures(spec.pattern(), replaced.failures());
                    RouteAction.fulfillWithBody(route, apiResponse.status(), apiResponse.headers(), replaced.body());
                    runtime.claims().markTerminal(claim, true);
                    reportOutcome("MOCK RESULT", spec, request, apiResponse.status(), apiResponse.headers(),
                            replaced.body(),
                            "intercept real response + field replacement (replacePaths="
                                    + spec.mockReplacements().keySet()
                                    + ", failures=" + replaced.failures().size() + ")");
                } finally {
                    // 响应体已消费（fulfill + 读 body/status/headers），立即释放句柄，
                    // 避免 body buffer 滞留至 GC/context 关闭（对原 route.fetch 与 replay 路径同时生效）
                    try {
                        apiResponse.dispose();
                    } catch (Exception ignore) {
                        LOGGER.debug("[Route] apiResponse.dispose ignored: {}", ignore.toString());
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[Route] mock task failed for url='{}', pattern='{}': {}",
                        route.request().url(), spec.pattern(), t.toString());
                if (!claim.isTerminal()) {
                    RouteAction.fallback(route);
                    runtime.claims().markTerminal(claim, false);
                }
            }
        });
        if (!submitted) {
            // 队列满 → 立即 fail-open
            RouteAction.fallback(route);
            runtime.claims().markTerminal(claim, false);
        }
    }

    /**
     * MODIFY 阶段（链路第 2 步）：无 body 级修改则直接入链尾（headers/method 由链尾 resume 应用）；
     * 有 body 级修改则交 IO 线程做内容类型感知修改，再入链尾。链尾 {@link #mockOrResume} 据 MOCK 是否命中
     * 决定 fulfill 短路或 resume 真实网络。
     */
    private void modifyStage(Route route, ApiSpec spec, RouteClaim claim, RouteRuntime runtime,
                             Consumer<String> terminal) {
        if (spec.bodyOps().isEmpty()) {
            // headers/method 由链尾 resume(route, spec, body) 统一应用，无需 IO
            terminal.accept(null);
            return;
        }
        if (!acquireIoSlot(claim, runtime)) {
            RouteAction.fallback(route);
            runtime.claims().markTerminal(claim, false);
            return;
        }
        boolean submitted = runtime.io().trySubmit("modify-body:" + spec.pattern(), () -> {
            if (claim.isTerminal()) {
                return;
            }
            try {
                String postData = route.request().postData();
                if (postData == null) {
                    // 无请求体：无 body 可改，仍交链尾（含可能的 MOCK 短路），等价于原 resume(route, spec)
                    terminal.accept(null);
                    return;
                }
                String contentType = null;
                Map<String, String> headers = route.request().headers();
                if (headers != null) {
                    contentType = headers.get("content-type");
                }
                MediaType mediaType = MediaType.parse(contentType);
                RequestBodyModifier.ModifyResult result = RequestBodyModifier.modify(mediaType, postData, spec.bodyOps());
                for (String failure : result.failures()) {
                    LOGGER.warn("[Route] modify-body op failed for url='{}': {}", route.request().url(), failure);
                }
                // fail-open：部分操作失败也使用已应用部分（或原体）继续链，绝不悬挂
                terminal.accept(result.body());
            } catch (Throwable t) {
                LOGGER.warn("[Route] modify-body task failed for url='{}', pattern='{}': {}",
                        route.request().url(), spec.pattern(), t.toString());
                if (!claim.isTerminal()) {
                    try {
                        RouteAction.fallback(route);
                    } catch (Exception ignored) {
                        LOGGER.trace("[Route] fallback ignored: {}", ignored.toString());
                    }
                    runtime.claims().markTerminal(claim, false);
                }
            }
        });
        if (!submitted) {
            RouteAction.fallback(route);
            runtime.claims().markTerminal(claim, false);
        }
    }

    private static void logReplacementFailures(String pattern, List<String> failures) {
        for (String failure : failures) {
            LOGGER.warn("[Route] mock field replacement failed, pattern='{}': {}", pattern, failure);
        }
    }

    /**
     * DELAY 阶段（链路第 1 步）：事件线程返回 pending（Router.PendingHandler → 驱动挂起请求），
     * 由 RouteDelayScheduler.delay 到点后执行 {@code afterDelay} 继续链路（MODIFY → MOCK / 真实网络）。
     * sweep 超龄兜底在延迟窗口内仍生效（sweep 35s > delay 上限 30s，无悬挂风险）。
     *
     * <p>对照 playwright-java-1.62.0 {@code Router.handle}：handler 返回时未终结且未 fallback →
     * 返回 {@code PendingHandler}，请求由驱动侧挂起，只能被后续异步终结（本方法的延迟回调）或 sweep 兜底放行——
     * 因此<b>绝不允许在调度延迟任务之后立即 resume</b>（历史缺陷：立即放行导致 delay 空操作）。</p>
     */
    private void dispatchDelayChain(Route route, ApiSpec spec, RouteClaim claim, RouteRuntime runtime,
                                    Runnable afterDelay) {
        if (!acquireIoSlot(claim, runtime)) {
            RouteAction.fallback(route);
            runtime.claims().markTerminal(claim, false);
            return;
        }
        long delay = spec.delayMs();
        if (spec.delayMinMs() > 0) {
            // 随机延迟模式（对齐现有 RouteDsl.randomDelay）：[min, max] 内随机。
            // 钳制下限与区间：min>max 或负值配置失误时不得产生负延迟（负延迟=立即放行=delay 失效）。
            long min = Math.max(0, spec.delayMinMs());
            long max = Math.max(min, spec.delayMaxMs());
            delay = min + (long) (Math.random() * (max - min));
        }
        final long effectiveDelay = Math.min(delay, MAX_DELAY_MS);
        boolean submitted = runtime.io().trySubmit("delay:" + spec.pattern(), () -> {
            if (claim.isTerminal()) {
                return; // sweep 已兜底（fallback 已放行）
            }
            // 事件线程已返回 pending，请求由驱动挂起。延迟到点后唯一终结路径 = 继续链路（或 sweep 兜底）。
            com.hsbc.cmb.hk.dbb.automation.framework.route.exec.RouteDelayScheduler
                    .delay(effectiveDelay, () -> {
                        if (claim.isTerminal()) {
                            return; // sweep already settled (fallback resumed)
                        }
                        afterDelay.run();
                    });
        });
        if (!submitted) {
            RouteAction.fallback(route);
            runtime.claims().markTerminal(claim, false);
        }
    }

    /**
     * 占用挂起额度并将 claim 置为 IO_AWAIT。
     * 幂等：链路中途（DELAY 已占用额度）的后续阶段（MODIFY/MOCK）复用同一额度，避免双占导致页面批量假死。
     */
    private boolean acquireIoSlot(RouteClaim claim, RouteRuntime runtime) {
        if (claim.isIoAwait()) {
            return true; // 已在挂起额度中（如 DELAY 已在链中占用）→ 复用，不重复占额
        }
        if (!runtime.pending().tryAcquire()) {
            return false;
        }
        if (!claim.toIoAwait()) {
            // 极端防御：claim 已被并发终结（事件线程独占下不应发生）→ 归还额度
            runtime.pending().release();
            return false;
        }
        return true;
    }

    /** T1：进入分发器的请求数（"下发次数"）。 */
    public long dispatchCount() {
        return dispatchCount.get();
    }

    /** T1：命中规则条件并触发能力的请求数（"路由命中数"）。 */
    public long hitCount() {
        return hitCount.get();
    }
}
