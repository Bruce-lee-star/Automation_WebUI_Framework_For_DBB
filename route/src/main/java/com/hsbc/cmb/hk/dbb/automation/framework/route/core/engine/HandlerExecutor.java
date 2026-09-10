package com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;
import com.hsbc.cmb.hk.dbb.automation.framework.route.util.RouteUtil;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;

import java.util.concurrent.TimeUnit;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandlerRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle.RouteMonitorSession;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRule;

/**
 * Handler 执行与延迟调度（T2-4 拆分，自 {@code RouteEngine} 提取）。
 *
 * <p>承载「能力位 → Handler 解析 → 同步/调度执行」的收尾链路：
 * {@code resolveCapabilityHandler}（能力位→Handler 映射）、{@code scheduleDelay}（纯 DELAY 延迟放行）、
 * {@code storeDelayCall}（DELAY 维度快照）、{@code executeHandlerScheduled}（延迟到期后执行）、
 * {@code executeHandler}（统一异常处理与 times 递减）。
 *
 * <p><b>关键契约</b>：
 * <ul>
 *   <li><b>times 归属源规则</b>：分发期合并出的 rule 是临时拷贝（copyForMerge 不复制 times），
 *       times 必须作用于 {@code rule.getMergeSource()}（链头或独立规则自身）；</li>
 *   <li><b>计数归属按 Handler 内部是否已计数判定</b>（不能按 type）：MonitorHandler 内部会计数；
 *       ModifyHandler 仅叠加监控时才计数；MOCK 一律在此计数，否则配了 timeout 的规则会话永不满足；</li>
 *   <li><b>延迟窗口内递增 activeRequests</b>，使 awaitCompletion 能等待延迟请求放行 + 响应落库，
 *       避免并发场景（f31）在延迟窗口内误判「全部完成」；</li>
 *   <li><b>防重门控释放</b>统一委托 {@code Dispatcher.unmarkDispatched}（异步路径在此释放）。</li>
 * </ul>
 *
 * <p>不使用 {@code Thread.sleep()}（占用调度线程）与 {@code route.fetch()}（发起新 HTTP、可能 DNS 失败），
 * 改用 {@code schedule() + resume()} 完全复用浏览器网络栈。日志复用 {@code RouteEngine.LOGGER}。
 *
 * @apiNote framework-internal：框架内部类型，非公开 API。跨子包 public 可见性仅为分层迁移需要，外部不得依赖。
 */
public final class HandlerExecutor {

    static RouteHandler resolveCapabilityHandler(RouteHandleType capability) {
        return RouteHandlerRegistry.resolve(capability);
    }

    /** times 一次性拦截（对齐 Playwright setTimes）：成功处理后递减源规则计数。 */
    private static void decrementTimes(RouteRule rule) {
        RouteRule sourceRule = rule.getMergeSource();
        if (sourceRule.getTimes() > 0) {
            sourceRule.decrementTimes();
            VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                    "[RouteEngine] times decremented: pattern='{}', exhausted={}",
                    sourceRule.getUrlPattern(), sourceRule.isTimesExhausted());
        }
    }

    /**
     * DELAY 延迟调度 — 使用 {@code ScheduledExecutorService#schedule} 在延迟后放行请求。
     *
     * <p>不使用 {@code Thread.sleep()}：sleep 会占用调度线程整个延迟期间，
     * 而 {@code schedule()} 只在到期时执行回调，线程在延迟期间可复用处理其他请求。
     *
     * <p>不使用 {@code route.fetch()}：fetch 发起新的 HTTP 请求，可能因 DNS 解析失败。
     * 改用 {@code route.resume()} 放行原始请求，完全复用浏览器网络栈。
     *
     * @param route              Playwright 路由对象
     * @param rule               路由规则（含延迟配置）
     * @param preComputedDelayMs 跨层级合并后的延迟值。>0 表示已由跨层合并计算出最终值（取 max），
     *                           此时跳过 resolveDelay 的随机/固定计算，直接使用该值。
     *                           非跨层场景传入 0，让 resolveDelay 自行处理随机延迟范围。
     */
    static void scheduleDelay(Route route, RouteRule rule, long preComputedDelayMs) {
        //  跨层级合并后的延迟值（>0）= 已取 max，直接信任使用；
        //    否则从 rule 重新计算（支持随机延迟范围 randomDelay）
        long delayMs = preComputedDelayMs > 0
                ? RouteDelay.clampDelay(preComputedDelayMs)
                : RouteDelay.clampDelay(RouteDelay.resolveDelay(rule));

        //  #1 性能优化：缓存 route.request()
        String pattern = rule.getUrlPattern();

        VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                "[RouteEngine] scheduleDelay: pattern='{}', url='{}', delay={}ms, minDelay={}ms, maxDelay={}ms, mergedInput={}ms",
                pattern, route.request().url(), delayMs, rule.getDelayMinMs(), rule.getDelayMaxMs(), preComputedDelayMs);

        //  DELAY 拦截期间递增 activeRequests，使 awaitCompletion 能等待延迟请求放行 +
        //    后续真实响应被捕获，避免并发场景（f31）在延迟窗口内误判「全部完成」。
        //    此处 activeRequests 覆盖整个延迟窗口，直至路由放行、快照落库后递减。
        ApiCaptureContext captureContext = RouteUtil.captureContext(route);
        captureContext.incrementActiveRequests();

        // exactly-one 契约：延迟回调只会执行一次 resume/fulfill，防止 Firefox/WebKit 下
        // route 已销毁导致 resume 抛 "Object doesn't exist" 后又被 catch 二次 resume（0次或2次）。
        if (RouteUtil.isPageClosed(route)) {
            RouteUtil.safeResume(route);
            return;
        }
        // 检查会话是否已被停止（auto-stop / 超时）
        RouteMonitorSession.MonitorSession session = RouteMonitorSession.sessionForRoute(route, rule);
        if (session != null && session.stopped.get()) {
            RouteUtil.safeResume(route);
            return;
        }

        if (rule.isMonitorEnabled() && !rule.isCapabilityStopped(RouteHandleType.MONITOR)) {
            //  B 方案：事件线程同步观测（page.waitForResponse），resume 经其 action 回调调度到延迟线程
            //    （见 MonitorHandler.handle），彻底弃用 route.fetch。
            //    onMonitorMatch 由 handle → assertAndRecord 内部处理；times / dispatched 门控在此清理。
            try {
                RouteHandler monitorHandler = RouteHandlerRegistry.resolve(RouteHandleType.MONITOR);
                if (monitorHandler != null) {
                    monitorHandler.handle(route, rule, delayMs);
                }
            } catch (Exception e) {
                RouteEngine.LOGGER.error("[RouteEngine] DELAY+MONITOR observe failed for '{}': {}", pattern, e.getMessage(), e);
                RouteUtil.safeResume(route);
            } finally {
                decrementTimes(rule);
                Dispatcher.unmarkDispatched(route);
                captureContext.decrementActiveRequests();
            }
        } else {
            // 纯 DELAY（无 modify 无 monitor）：延迟后放行（调度线程，非阻塞），保持原语义。
            if (delayMs > 0) {
                final long d = delayMs;
                DelayScheduler.delayScheduler(route).schedule(() -> {
                    try {
                        if (!RouteUtil.isPageClosed(route)) RouteUtil.safeResume(route);
                    } finally {
                        RouteMonitorSession.onMonitorMatch(rule);
                        decrementTimes(rule);
                        Dispatcher.unmarkDispatched(route);
                        captureContext.decrementActiveRequests();
                    }
                }, d, TimeUnit.MILLISECONDS);
                return; // 清理交由调度线程
            }
            RouteUtil.safeResume(route);
            RouteMonitorSession.onMonitorMatch(rule);
            decrementTimes(rule);
            Dispatcher.unmarkDispatched(route);
            captureContext.decrementActiveRequests();
        }
    }

    /**
     * 将纯 DELAY 调用存入 ApiCaptureContext，使其像 MONITOR/MOCK 一样可被查询。
     *
     * <p>DELAY 仅延迟放行请求（不修改响应），因此存储的信息以请求元数据为主，
     * 不包含响应体（resume 异步，响应尚未返回）。满足 assertNotNull 等基础断言。
     *
     * <p> 与其它能力的记录是<b>并列维度</b>：叠加 MONITOR 时，真实响应由
     * {@code MonitorHandler.handle(route, rule, delayMs)} 另落一条 type=MONITOR 的完整快照，
     * 本条 type=DELAY 依然保留 —— {@code getAllByType(DELAY)} 才能反映
     * 「哪些请求被延迟过」，不被 MONITOR 覆盖。
     */
    static void storeDelayCall(Route route, RouteRule rule) {
        try {
            Request req = route.request();
            CapturedApiCall call = new CapturedApiCall(
                    rule.getUrlPattern(),
                    req.method(),
                    null,   // 请求头快照（简化处理）
                    0,      // 状态码未知（resume 异步）
                    null,   // 响应头未知
                    null,   // 响应体未知（resume 异步，不阻塞等待）
                    System.currentTimeMillis(),
                    req.url(),  // 实际请求 URL，用于毫秒级精确检索
                    req.postData(),
                    RouteHandleType.DELAY
            );
            ApiCaptureContext ctx = RouteUtil.captureContext(route);
            if (ctx != null) {
                //  存入 DELAY 专用索引，不进主快照存储：
                //   本记录无响应体，若混入主存储会让 getLastApiCall / waitForApi 命中空快照。
                ctx.storeDelayMarker(call);
            }
            VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                    "[RouteEngine] Stored DELAY marker to ApiCaptureContext: pattern='{}', method={}",
                    rule.getUrlPattern(), req.method());
        } catch (Exception e) {
            RouteEngine.LOGGER.debug("[RouteEngine] Failed to store DELAY call to ApiCaptureContext: {}", e.getMessage());
        }
    }

    /**
     * 在调度线程池中执行 Handler（用于 DELAY 类型和延迟场景）。
     *
     * <p>与 {@link #executeHandler} 相比增加了会话状态检查，
     * 在延迟等待期间会话可能已被 auto-stop / 超时导致停止。
     */
    static void executeHandlerScheduled(Route route, RouteRule rule, RouteHandler handler) {
        try {
            // 延迟期间页面/上下文可能已被关闭，抵达时直接放行，避免对已销毁页面执行 handler 导致挂起/报错
            if (RouteUtil.isPageClosed(route)) {
                VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                        "[RouteEngine] Page already closed during scheduled delay, skip handler for '{}'",
                        rule.getUrlPattern());
                RouteUtil.resumeIfOpen(route);
                return;
            }
            // 检查会话是否已被停止（auto-stop / 超时）
            RouteMonitorSession.MonitorSession session = RouteMonitorSession.sessionForRoute(route, rule);
            if (session != null && session.stopped.get()) {
                RouteEngine.LOGGER.debug("[RouteEngine] Session stopped during delay, skipping handler for '{}'",
                        rule.getUrlPattern());
                RouteUtil.safeResume(route);
                return;
            }
            executeHandler(route, rule, handler, 0);
        } catch (Exception e) {
            RouteEngine.LOGGER.error("[RouteEngine] Scheduled handler failed for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage(), e);
            RouteUtil.safeResume(route);
        }
    }

    /**
     * 执行 Handler，统一异常处理和日志。
     */
    static void executeHandler(Route route, RouteRule rule, RouteHandler handler, long delayMs) {
        //  #1 性能优化：缓存 route.request()，避免 executeHandler 内重复 JNI 调用
        Request req = route.request();
        try {
            VerboseLogging.logTraceIfVerbose(RouteEngine.LOGGER,
                    "[RouteEngine] executeHandler START: type={}, pattern='{}', url='{}'",
                    rule.getType(), rule.getUrlPattern(), SensitiveDataSanitizer.sanitizeUrl(req.url()));
            handler.handle(route, rule, delayMs);

            RouteEngine.LOGGER.info("[RouteEngine] Route matched: type={}, pattern='{}', method={}, url='{}'",
                    rule.getType(), rule.getUrlPattern(),
                    req.method(), SensitiveDataSanitizer.sanitizeUrl(req.url()));

            VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                    "[RouteEngine] executeHandler DONE: type={}, pattern='{}'",
                    rule.getType(), rule.getUrlPattern());

            // MOCK/MODIFY/DELAY 处理成功后触发匹配计数（支持一次性拦截 / auto-stop）
            //  计数归属必须按「Handler 内部是否已计数」判定，不能按 type：
            //    · MonitorHandler.assertAndRecord 内部会调 onMonitorMatch；
            //    · ModifyHandler 仅在 rule.isMonitorEnabled() 时才走 assertAndRecord（叠加监控），
            //      因此纯 MODIFY（未叠加监控）必须在此计数，否则配了 timeout 的 modify 规则
            //      会话永不被满足 → 误报监控超时；
            //    · MOCK 即使叠加了监控也走 MockHandler（不发真实请求、不做响应断言），
            //      Handler 内部不计数，故 MOCK 一律在此计数。
            boolean countedInsideHandler = rule.getType() != RouteHandleType.MOCK
                    && rule.isMonitorEnabled();
            if (!countedInsideHandler) {
                RouteMonitorSession.onMonitorMatch(rule);
            }

            // ═══ times 一次性拦截（对齐 Playwright setTimes）：成功处理后递减 ═══
            //  B3：rule 可能是分发期合并的临时拷贝（copyForMerge 不复制 times），
            //    times 必须作用于源规则（getMergeSource()：链头或独立规则自身）。
            RouteRule sourceRule = rule.getMergeSource();
            if (sourceRule.getTimes() > 0) {
                sourceRule.decrementTimes();
                VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                        "[RouteEngine] times decremented: type={}, pattern='{}', exhausted={}",
                        sourceRule.getType(), sourceRule.getUrlPattern(), sourceRule.isTimesExhausted());
            }

            // ═══ 采集管道钩子已移除 ═══
            // MOCK 由 MockHandler、MODIFY 由 ModifyHandler 在拿到响应后同步 storeApiCall；
            // MONITOR/DELAY 走各自路径。原 feedCaptureEvent 的每个分支都会与 Handler 的
            // 同步存储重复落库（MODIFY 分支还会因缺少 FETCH_RESPONSE 生产者而制造永不闭合的
            // 孤儿 slot，虚占 captureInFlight 拖慢 awaitCompletion），故整体删除。
        } catch (RouteException.ApiAssertionException e) {
            //  MonitorHandler 同步断言失败 — 已由 signalFailFast() 置失败标志（非中断线程），
            RouteEngine.LOGGER.error("[RouteEngine] API assertion FAILED for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage());
            // 路由已被 MonitorHandler.resume() 放行，无需额外处理
            // ApiAssertionException 不在此处继续传播（Playwright 内部捕获），
            // 主测试线程不会被 interrupt，仅标志置位，当前阻塞的 Playwright 操作照常完成
        } catch (Exception e) {
            RouteEngine.LOGGER.error("[RouteEngine] Handler type={} threw exception for pattern '{}': {}",
                    rule.getType(), rule.getUrlPattern(), e.getMessage(), e);
            try {
                route.resume();
            } catch (Exception resumeEx) {
                // resume 失败（route 已失效/页面已关闭）：兜底 abort，
                // 确保请求绝对不会永久悬停导致"浏览器打开但无动作"的挂起。
                try {
                    route.abort();
                } catch (Exception abortEx) {
                    RouteEngine.LOGGER.error("[RouteEngine] Failed to resume AND abort route after handler error: {}",
                            abortEx.getMessage());
                }
            }
        } finally {
            // ═══ 防重门控释放：handler 完成后立即 remove，允许同一 pattern 后续请求正常处理 ═══
            Dispatcher.unmarkDispatched(route);
        }
    }
}
