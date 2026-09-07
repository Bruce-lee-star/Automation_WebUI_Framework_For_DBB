package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 路由分发与防重门控（T2-4 拆分，自 {@code RouteEngine} 提取）。
 *
 * <p>承载单条请求的完整分发判定链：空链短路 → 页面已关闭短路 → 防重门控 → 请求条件匹配 →
 * MonitorSession 停止 → times 耗尽 → 同 pattern 规则链合并 → 显式停止能力注入 → 能力位选择 →
 * 同步/异步派发，以及最外层异常 force-resume 兜底与防重门控释放。
 *
 * <p><b>控制流契约（改动前必读，各 exit 分支语义不同，不可互换）</b>：
 * <ul>
 *   <li>空链 / 无适用规则 → {@code fallbackIfOpen}（<b>非</b> resume）：resume 会终结 Playwright handler 链，
 *       使后续重新注册的同 pattern handler 永不执行（g06 规则静默失效故障根因）；</li>
 *   <li>防重门控重复 → <b>直接 return 且不 resume</b>：把请求交给下一个 handler；</li>
 *   <li>条件不匹配 → {@code unmarkDispatched} + {@code resume}：放弃本次拦截，让 Playwright 继续匹配；</li>
 *   <li>session 已停止 / times 耗尽 → {@code safeResume} + {@code unmarkDispatched}：保持 handler 注册仅放行。</li>
 * </ul>
 *
 * <p>防重门控释放时机：仅<b>同步路径</b>在 finally 释放；异步路径（MOCK+DELAY、纯 DELAY）的 route 仍在 pending，
 * 由 {@code executeHandlerScheduled}/{@code executeHandler} 的 finally 负责释放，提前清除会导致重叠 pattern 二次 dispatch 失防。
 *
 * <p>Handler 执行（{@code executeHandler} 等）与能力位选择保留在 {@code RouteEngine}（包级可见）；日志复用 {@code RouteEngine.LOGGER}。
 */
final class Dispatcher {

    static void dispatchRoute(Route route, List<RouteRule> chain) {
        //  B3 链式模型：链头 = 源规则（请求条件匹配 / MonitorSession / times 归属）；
        //    同 pattern 多条规则在分发期合并为一次性有效规则（copyForMerge，后注册覆盖），
        //    用后即弃——消除注册期 mergeFrom 的共享可变状态（详见 resolveSameLayerChain）。
        RouteRule rule = (chain == null || chain.isEmpty()) ? null : chain.get(0);
        if (rule == null) {
            VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                    "[RouteEngine] dispatchRoute SKIP: empty rule chain");
            //  修复：改用 fallback 而非 resume。
            //    resume() 会【终结】Playwright 的 handler 链并直接放行到网络；而本分支的语义
            //    是"本 handler 已无规则可依"，理应把请求交给下一个 handler。
            //    典型故障（g06）：clear() 只就地清空 chain、不解绑原生 route（刻意不 unroute
            //    以规避线程竞态），旧 handler 仍排在同 pattern 队首；若它用 resume 放行，
            //    后续重新注册的同 pattern handler 将永远得不到执行 —— 规则静默失效。
            RouteUtil.fallbackIfOpen(route);
            return;
        }
        // ═══ 统一页面/上下文关闭短路 ═══
        // page/context 已关闭后，route handler 可能仍被触发（未 unroute）。
        // 此时执行 fetch/resume/response 等操作会抛 PlaywrightException 或卡在失效连接上，
        // 导致后续测试被该请求 block 住。统一在此放行并跳过，所有 handler 类型均受益。
        if (RouteUtil.isPageClosed(route)) {
            VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                    "[RouteEngine] Page/Context already closed, resume & skip dispatch for pattern='{}'",
                    rule.getUrlPattern());
            RouteUtil.resumeIfOpen(route);
            return;
        }

        //  #1 性能优化：缓存 route.request() JNI 调用，避免多次跨语言桥接
        Request req = route.request();
        //  异步路径标记：schedule() 分支（MOCK/MODIFY/MONITOR 延迟、DELAY）的 route 生命周期
        //    由 executeHandlerScheduled/action 的 finally 负责释放防重门控。外层 finally 仅对
        //    同步路径释放，避免提前清除 pending route 的门控导致重叠 pattern 二次 dispatch 失防。
        //    （声明在 try 外，使 catch/finally 可访问；数组形式以支持 lambda 内修改）
        final boolean[] asyncHandled = {false};
        //  最外层兜底 try：覆盖 dispatchRoute 早期逻辑（规则查询、能力位合并、MOCK 短路、
        //    MODIFY fetch 前的 route.request()/incrementActiveRequests 等）。任何未预期异常都强制
        //    resume 兜底，确保 route 绝不永久挂起（详见方法末尾 catch/finally）。
        try {
        String reqUrl = req.url();
        String reqMethod = req.method();
        VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                "[RouteEngine] ═══ dispatchRoute START: method={}, url='{}', type={}, pattern='{}' ═══",
                reqMethod, reqUrl, rule.getType(), rule.getUrlPattern());

        //  Phase 3 统一绑定（单 context handler）：不存在第二个 handler，原跨层级去重（CROSS_LAYER_HANDLED_URLS）已废弃移除。

        //  防重门控按 context 分桶（逻辑收口至 RouteContextState.markDispatched）
        BrowserContext dispatchCtx = contextOf(route);
        Set<Route> bucket = RouteContextState.markDispatched(dispatchCtx);

        // ═══ 防重门控：同一请求只处理一次（按 context 隔离，context 关闭时由 clearContext 精确清理）═══
        if (bucket != null) {
            if (!bucket.add(route)) {
                RouteEngine.LOGGER.warn("[RouteEngine] Route already handled by another pattern, skipping '{}' for URL '{}'",
                        rule.getUrlPattern(), reqUrl);
                VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                        "[RouteEngine] ═══ dispatchRoute SKIPPED (duplicate): pattern='{}', url='{}' ═══",
                        rule.getUrlPattern(), reqUrl);
                return;
            }
        }

        // ═══ 请求条件匹配：根据 Rule 中配置的 ResourceType/Header/Query/Body 等过滤 ═══
        if (!RouteUtil.requestMatches(route, rule)) {
            // 不匹配此规则 → 移除防重标记，让 Playwright 继续尝试下一个 pattern
            unmarkDispatched(route);
            route.resume();
            VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                    "[RouteEngine] ═══ dispatchRoute MISMATCH (condition filter): pattern='{}', url='{}' ═══",
                    rule.getUrlPattern(), reqUrl);
            return;
        }

        // ═══ 检查 MonitorSession 是否已停止（auto-stop / 超时），停止则跳过 handler ═══
        // 不在此处调用 unroute()，避免 Playwright 线程竞态导致 "Object doesn't exist" 或 "Cannot find command to respond" 错误。
        // route handler 保持注册，但已停止的 session 仅放行请求，不处理。
        RouteMonitorSession.MonitorSession session = RouteMonitorSession.sessionForRoute(route, rule);
        if (session != null && session.stopped.get()) {
            VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                    "[RouteEngine] ═══ dispatchRoute SKIP (session stopped): pattern='{}', url='{}' ═══",
                    rule.getUrlPattern(), reqUrl);
            RouteUtil.safeResume(route);
            unmarkDispatched(route);
            return;
        }

        // ═══ times 一次性拦截已耗尽（对齐 Playwright setTimes）：仅放行，不处理 ═══
        // 与 session stopped 语义一致：route handler 保持注册，但耗尽后仅放行请求走真实网络。
        // 不调用 unroute()，避免 Playwright 线程竞态（同 session stopped 的注释）。
        if (rule.getTimes() > 0 && rule.isTimesExhausted()) {
            VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                    "[RouteEngine] ═══ dispatchRoute SKIP (times exhausted): pattern='{}', url='{}' ═══",
                    rule.getUrlPattern(), reqUrl);
            RouteUtil.safeResume(route);
            unmarkDispatched(route);
            return;
        }

        // ═══ 同 pattern 规则链解析（统一绑定模型）═══
        //  Phase 3：单一 context handler 路径（合并核心见 resolveUnified）。
        //    同 pattern 链混合 PAGE/CONTEXT scope 规则：按 request.frame().page() 精确筛选适用 PAGE 规则
        //    + 全部 CONTEXT 规则，委托 resolveUnified → mergeCrossLayer 一次性合并执行
        //    （page 特定 > context 全域、能力位 OR、MOCK 终结、DELAY 取 max）；单请求仅执行一次。
        long delayMs = rule.getDelayMs();

        //  Phase 3 统一绑定：单 context handler 路径（合并核心见 resolveUnified）。
        //    同 pattern 链混合 PAGE/CONTEXT scope 规则，按请求所属 Page 精确筛选后一次性合并执行：
        //    page 特定 > context 全域、能力位 OR、MOCK 终结、DELAY 取 max（全部由 resolveUnified → mergeCrossLayer 承载）。
        Page reqPage = RouteUnifiedResolution.currentPageOf(route);
        RouteEngine.ResolvedUnified resolved = RouteUnifiedResolution.resolveUnified(chain, reqPage);
        if (resolved == null) {
            VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                    "[RouteEngine] ═══ dispatchRoute SKIP (unified: no applicable rule for this page): pattern='{}' ═══",
                    rule.getUrlPattern());
            RouteUtil.fallbackIfOpen(route);
            return;
        }
        rule = resolved.rule;
        delayMs = resolved.delayMs;
        // ═══ 应用显式停止的能力（仅影响指定能力，不影响同 pattern 其它能力）═══
        //    stopMonitor/stopModify/stopDelay/stopMock/stopAll 写入的停止标记在此注入有效规则，
        //    由 selectCapability 及各 handler 守卫跳过对应能力。
        StoppedCapabilityManager.applyStoppedCapabilities(rule, route);
        if (rule.isCapabilityStopped(RouteHandleType.DELAY)) {
            delayMs = 0;  // 停止 DELAY：清零延迟，避免后续 handler 仍按原延迟等待
        }
        // 统一合并路径下无独立跨层延迟合并标记需求（保留供后续 DELAY 调度判定兼容），恒为 false
        boolean crossLayerDelayMerged = false;

        // ═══ 能力位管线（取代 type 单选）：MOCK 终结 → MODIFY → MONITOR → DELAY ═══
        // 能力与 type 解耦：MOCK 为唯一终结者（短路）；其余能力位（MODIFY/MONITOR/DELAY）
        // 按字段叠加，可同时存在。监控是<b>不可覆盖的基线</b>：无论是否叠加 modify/delay，
        // 最终都对真实响应断言，失败即报错。
        //  Phase 5：管线选择抽成 InterceptorChain（按 order 升序选首个 canHandle 的拦截器），
        //    实际动作（同步/异步调度、scheduleDelay、resume）仍在此统一执行，保持 asyncHandled /
        //    unmarkDispatched 控制流与既有管线严格等价。
        final long effectiveDelay = delayMs;
        //  取 final 副本供 lambda 引用（rule 在跨层合并可能被重新赋值，非 effectively final）
        final RouteRule finalRule = rule;

        RouteHandleType capability = PriorityPolicy.selectCapability(finalRule);
        if (capability == null) {
            // 5) 兜底：无能力位 → 直接放行
            RouteEngine.LOGGER.debug("[RouteEngine] No capability on rule, resume: pattern='{}'", finalRule.getUrlPattern());
            try {
                route.resume();
            } catch (Exception ignored) {
                // 已失效/已关闭：忽略
            }
            return;
        }

        // ═══ DELAY 维度记录（与其它能力并列，非互斥）═══
        //  四种能力在 ApiCaptureContext 中是<b>四个并列维度</b>：
        //   一次请求可同时被「延迟 + 修改 + 监控」，各自落一条 type 不同的快照，
        //   由 getAllByType(DELAY) / (MODIFY) / (MONITOR) / (MOCK) 分别检索。
        //   因此只要合并后的有效延迟 > 0，无论最终由哪个拦截器执行动作，都落一条 DELAY 记录。
        if (effectiveDelay > 0) {
            HandlerExecutor.storeDelayCall(route, finalRule);
        }

        if (capability != RouteHandleType.DELAY) {
            // 1/2/3) MOCK / MODIFY / MONITOR：执行对应 Handler。
            //  B 方案：MODIFY / MONITOR（含 +DELAY）一律在事件线程同步执行，延迟由 Handler 内部
            //   page.waitForResponse 的 action 把 resume 调度到延迟线程实现；彻底弃用 route.fetch。
            //   仅 MOCK+DELAY 保留调度线程 fulfill（fulfill 线程安全）。
            if (effectiveDelay > 0 && capability == RouteHandleType.MOCK) {
                asyncHandled[0] = true;
                final RouteHandler h = HandlerExecutor.resolveCapabilityHandler(capability);
                DelayScheduler.delayScheduler(route).schedule(
                        () -> HandlerExecutor.executeHandlerScheduled(route, finalRule, h),
                        effectiveDelay, TimeUnit.MILLISECONDS);
            } else {
                HandlerExecutor.executeHandler(route, finalRule, HandlerExecutor.resolveCapabilityHandler(capability), effectiveDelay);
            }
            return;
        }

        // 4) 纯 DELAY（无 modify 无 monitor）：延迟后放行（不走 Handler）
        long scheduledMs = crossLayerDelayMerged ? delayMs : 0;
        VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                "[RouteEngine] ═══ dispatchRoute DELAY: scheduling for pattern='{}', url='{}', crossLayerMerged={}, delay={}ms ═══",
                finalRule.getUrlPattern(), reqUrl, crossLayerDelayMerged, delayMs);
        asyncHandled[0] = true;
        HandlerExecutor.scheduleDelay(route, finalRule, scheduledMs);
        return;
        } catch (Exception e) {
            //  最外层兜底：dispatchRoute 早期逻辑（规则查询、能力位合并、MOCK 短路、MODIFY fetch 前的
            //    route.request()/incrementActiveRequests 等）若抛未预期异常，必须 force-resume 兜底，
            //    否则该 route 既未 fulfill 也未 resume → 请求永久挂起（浏览器转圈、测试 block）。
            //    对已 resume/fulfill 的分支，重复 resume 被 Playwright 忽略（幂等），无副作用。
            VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                    "[RouteEngine] dispatchRoute unexpected error for pattern='{}', force-resume to avoid hang: {}",
                    rule.getUrlPattern(), e.getMessage());
            try {
                route.resume();
            } catch (Exception ignored) {
                // route 已失效/页面已关闭，放行失败也无所谓（不挂起即可）
            }
        } finally {
            //  防重门控释放：仅对同步路径在此释放。异步路径（MOCK/MODIFY/MONITOR 延迟、DELAY）
            //    的 route 仍在 pending，其生命周期由 executeHandlerScheduled/action 的 finally 负责释放；
            //    若此处提前清除，会导致重叠 pattern 二次 dispatch 失去防重保护。同步路径（含兜底 resume、
            //    早期异常 force-resume）在此统一释放，避免同 pattern 后续请求被永久吞掉。
            if (!asyncHandled[0]) {
                unmarkDispatched(route);
            }
        }
    }

    /**
     * 从 route 反查其所属 BrowserContext（防重门控按 context 分桶需要）。
     * 任意一环已关闭/失效时返回 null，调用方降级为"不按 context 隔离"（单例兜底）。
     */
    private static BrowserContext contextOf(Route route) {
        try {
            if (route != null && route.request() != null
                    && route.request().frame() != null
                    && route.request().frame().page() != null) {
                return route.request().frame().page().context();
            }
        } catch (Exception ignored) {
            // Page/Context 已关闭时无法反查，返回 null 走兜底
        }
        return null;
    }

    /**
     * 防重门控释放：从所属 context 桶中移除 route（ 分桶）。
     * <p>包级可见：异步路径的 {@code executeHandler}/{@code executeHandlerScheduled} 亦需在 finally 中调用。
     */
    static void unmarkDispatched(Route route) {
        BrowserContext ctx = contextOf(route);
        if (ctx != null) {
            Set<Route> bucket = RouteContextState.DISPATCHED_ROUTES.get(ctx);
            if (bucket != null) {
                bucket.remove(route);
                if (bucket.isEmpty()) {
                    RouteContextState.DISPATCHED_ROUTES.remove(ctx);
                }
            }
        }
    }
}
