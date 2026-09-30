package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dispatch;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.RouteRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.claim.RouteClaim;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteDsl2;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.modify.RequestBodyModifier;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.util.ApiMatcher;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.util.FieldReplacer;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.util.MediaType;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
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

        LOGGER.info("[RouteV2] captured {} route for '{}'{}",
                spec.capability(), spec.pattern(),
                RouteDsl2.describeCaptured(spec, route.request().method(), route.request().url()));

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
            LOGGER.warn("[RouteV2] dispatch failed for url='{}', pattern='{}': {}",
                    route.request().url(), pattern, t.toString());
            try {
                RouteAction.fallback(route);
            } catch (Exception ignored) {
                LOGGER.trace("[RouteV2] fallback after dispatch error ignored: {}", ignored.toString());
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
        if (hasMock) {
            dispatchMock(route, spec, claim, runtime);
        } else if (spec.hasModifyFields() || modifiedBody != null) {
            // MODIFY 改了 headers/method/body → 经 ResumeOptions 上路（无修改则等价放行）。
            RouteAction.resume(route, spec, modifiedBody);
            runtime.claims().markTerminal(claim, true);
        } else {
            // 纯放行（DELAY/MONITOR/无修改）：与原 MONITOR/DELAY 行为一致 = route.resume() 无参。
            RouteAction.resume(route);
            runtime.claims().markTerminal(claim, true);
        }
    }

    /** MOCK：静态伪造直接 fulfill；intercept / 字段替换交 IO 线程。 */
    private void dispatchMock(Route route, ApiSpec spec, RouteClaim claim, RouteRuntime runtime) {
        boolean hasStaticBody = spec.mockStatus() != null || spec.mockBody() != null
                || spec.mockContentType() != null || !spec.mockHeaders().isEmpty();
        if (hasStaticBody && spec.mockReplacements().isEmpty()
                && spec.conditionalReplacements().isEmpty()) {
            RouteAction.fulfill(route, spec);
            runtime.claims().markTerminal(claim, true);
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
                } finally {
                    // 响应体已消费（fulfill + 读 body/status/headers），立即释放句柄，
                    // 避免 body buffer 滞留至 GC/context 关闭（对原 route.fetch 与 replay 路径同时生效）
                    try {
                        apiResponse.dispose();
                    } catch (Exception ignore) {
                        LOGGER.debug("[RouteV2] apiResponse.dispose ignored: {}", ignore.toString());
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[RouteV2] mock task failed for url='{}', pattern='{}': {}",
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
                    LOGGER.warn("[RouteV2] modify-body op failed for url='{}': {}", route.request().url(), failure);
                }
                // fail-open：部分操作失败也使用已应用部分（或原体）继续链，绝不悬挂
                terminal.accept(result.body());
            } catch (Throwable t) {
                LOGGER.warn("[RouteV2] modify-body task failed for url='{}', pattern='{}': {}",
                        route.request().url(), spec.pattern(), t.toString());
                if (!claim.isTerminal()) {
                    try {
                        RouteAction.fallback(route);
                    } catch (Exception ignored) {
                        LOGGER.trace("[RouteV2] fallback ignored: {}", ignored.toString());
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
            LOGGER.warn("[RouteV2] mock field replacement failed, pattern='{}': {}", pattern, failure);
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
            com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec.RouteDelayScheduler
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
