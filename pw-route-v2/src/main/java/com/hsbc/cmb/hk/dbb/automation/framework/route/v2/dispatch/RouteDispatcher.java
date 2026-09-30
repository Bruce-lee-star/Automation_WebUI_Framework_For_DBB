package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dispatch;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.RouteRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.claim.RouteClaim;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteDsl2;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.modify.RequestBodyModifier;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.util.ApiMatcher;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.util.FieldReplacer;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.util.MediaType;
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

        // 3) 匹配条件过滤：条件不满足 → 终结本规则的 claim 并 fallback。
        //    fallback 是驱动级 Fallback（不置 handled），Router 会把同一请求交给下一个
        //    匹配的 pattern handler（链式裁决），全部不满足时驱动自动 resume——请求永不悬挂。
        ApiMatcher matcher = matcherCache.computeIfAbsent(spec, ApiMatcher::from);
        if (!matcher.matches(route.request())) {
            runtime.claims().markTerminal(claim, false);
            RouteAction.fallback(route);
            return;
        }

        // 3.5) 能力已停止（stop* 系列）：路由仍注册但本能力跳过 → 链式裁决。
        //    在途请求不受影响——dispatch 读的是进入时的快照；stop 只作用于后续请求。
        if (spec.isStopped(spec.capability())) {
            runtime.claims().markTerminal(claim, false);
            RouteAction.fallback(route);
            return;
        }

        // 3.6) 统一观测（所有能力必经）：MONITOR 记录 + CAPTURE 采集。
        //    capture 是横切观测——mock/modify/delay/monitor 的请求都记录，不参与终结所有权、
        //    不改变请求流，与能力执行路径无共享可变状态（无竞态）。
        hitCount.incrementAndGet(); // 已通过匹配条件与停止判定 ⇒ 确为一次"命中"
        runtime.recordObservation(route.request(), spec);

        // 命中日志：每次请求真正触发能力时输出具体操作（body 不截断、敏感信息统一经 SensitiveDataSanitizer 打码）。
        LOGGER.info("[RouteV2] captured {} route for '{}'{}",
                spec.capability(), spec.pattern(),
                RouteDsl2.describeCaptured(spec, route.request().method(), route.request().url()));

        // 4) 能力裁决
        try {
            switch (spec.capability()) {
                case MONITOR:
                    dispatchMonitor(route, spec, claim, runtime);
                    break;
                case MOCK:
                    dispatchMock(route, spec, claim, runtime);
                    break;
                case MODIFY_REQUEST:
                    // method/headers/postData 全部可由 resume(options) 携带（官方 ResumeOptions 支持
                    // setMethod/setHeaders/setPostData）→ 无需 fetch+fulfill 重放（避免二次真实请求副作用）。
                    // 仅当存在 body 级修改且请求带体时交 IO 线程做内容类型感知修改，其余事件线程直接放行。
                    if (spec.bodyOps().isEmpty()) {
                        RouteAction.resume(route, spec, null);
                        runtime.claims().markTerminal(claim, true);
                    } else {
                        dispatchModifyBody(route, spec, claim, runtime);
                    }
                    break;
                case DELAY:
                    dispatchDelay(route, spec, claim, runtime);
                    break;
                default:
                    // 未知能力 → fail-open
                    runtime.claims().markTerminal(claim, false);
                    RouteAction.fallback(route);
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

    /** MONITOR：放行（fail-open，绝不影响业务）；观测记录已由 dispatch 入口统一完成。 */
    private void dispatchMonitor(Route route, ApiSpec spec, RouteClaim claim, RouteRuntime runtime) {
        RouteAction.resume(route, null);
        runtime.claims().markTerminal(claim, true);
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
                // intercept：fetch 真实响应
                Optional<APIResponse> response = runtime.ops().tryRun("route.fetch", () -> RouteAction.fetch(route));
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

    /** MODIFY_REQUEST body 级修改：IO 线程读 body → 内容类型感知修改 → resume(method/headers/postData)。 */
    private void dispatchModifyBody(Route route, ApiSpec spec, RouteClaim claim, RouteRuntime runtime) {
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
                    // 无请求体：仅 header 修改（事件线程语义等价）
                    RouteAction.resume(route, spec);
                    runtime.claims().markTerminal(claim, true);
                    return;
                }
                String contentType = route.request().headers().get("content-type");
                MediaType mediaType = MediaType.parse(contentType);
                RequestBodyModifier.ModifyResult result = RequestBodyModifier.modify(mediaType, postData, spec.bodyOps());
                for (String failure : result.failures()) {
                    LOGGER.warn("[RouteV2] modify-body op failed for url='{}': {}", route.request().url(), failure);
                }
                // fail-open：部分操作失败也使用已应用部分（或原体）resume，绝不悬挂
                RouteAction.resume(route, spec, result.body());
                runtime.claims().markTerminal(claim, true);
            } catch (Throwable t) {
                LOGGER.warn("[RouteV2] modify-body task failed for url='{}', pattern='{}': {}",
                        route.request().url(), spec.pattern(), t.toString());
                if (!claim.isTerminal()) {
                    RouteAction.fallback(route);
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
     * DELAY：事件线程返回 pending（Router.PendingHandler → 驱动挂起请求），
     * 由 RouteDelayScheduler.delay 到点后 resume（sweep 超龄兜底）。
     *
     * <p>对照 playwright-java-1.62.0 {@code Router.handle}（Router.java:75-100）：
     * handler 返回时未终结且未 fallback → 返回 {@code PendingHandler}，请求由驱动侧挂起，
     * 只能被后续异步终结（本方法的延迟任务）或 sweep 兜底放行——因此本方法
     * <b>绝不允许在调度延迟任务之后立即 resume</b>（历史缺陷：立即放行导致 delay 空操作）。
     */
    private void dispatchDelay(Route route, ApiSpec spec, RouteClaim claim, RouteRuntime runtime) {
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
            // 事件线程已返回 pending，请求由驱动挂起。唯一终结路径 = 延迟任务 resume（或 sweep 兜底）。
            // Architecture rule (LayeringArchTest): framework code must not Thread.sleep on IO threads;
            // delay is scheduled via CompletableFuture.RouteDelayScheduler.delay (daemon common pool);
            // the IO slot is released as soon as this task returns; pending 额度在 resume 后由
            // ClaimRegistry.markTerminal 释放（wasIoAwait → pendingGuard.release），sweep(35s)
            // 大于 delay 上限(30s)，窗口期无悬挂风险。
            com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec.RouteDelayScheduler
                    .delay(effectiveDelay, () -> {
                        if (claim.isTerminal()) {
                            return; // sweep already settled (fallback resumed)
                        }
                        RouteAction.resume(route, null);
                        runtime.claims().markTerminal(claim, true);
                    });
        });
        if (!submitted) {
            RouteAction.fallback(route);
            runtime.claims().markTerminal(claim, false);
        }
    }

    /** 占用挂起额度并将 claim 置为 IO_AWAIT。 */
    private boolean acquireIoSlot(RouteClaim claim, RouteRuntime runtime) {
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
