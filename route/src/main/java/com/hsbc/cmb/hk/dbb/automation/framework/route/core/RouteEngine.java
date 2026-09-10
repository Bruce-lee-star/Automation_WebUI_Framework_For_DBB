package com.hsbc.cmb.hk.dbb.automation.framework.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.route.util.RouteUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * 路由引擎 — 统一注册入口，按类型分发到对应 Handler。
 *
 * <p>核心设计：
 * <ul>
 *   <li>遍历规则时隔离异常，单个规则失败不影响后续规则注册</li>
 *   <li>Handler 执行异常被捕获，避免单个请求失败导致整个路由崩溃</li>
 *   <li>{@code register(Object, List)} 接收 Page 或 BrowserContext，适配 DSL 层</li>
 * </ul>
 */
public class RouteEngine {

    static final Logger LOGGER = LoggerFactory.getLogger(RouteEngine.class);

    /** 预编译 Pattern — 归一化 URL 路径末尾的通配符 */
    private static final Pattern TRAILING_WILDCARDS = Pattern.compile("\\*+$");

    //  注册 JVM 关闭钩子，确保进程退出时优雅关闭调度器（DELAY_SCHEDULER 等），
    // 避免并行测试或浏览器崩溃场景下线程池任务永久挂起。shutdown() 内部由 scheduledShutdown CAS 保护，重复调用安全。
    static {
        com.hsbc.cmb.hk.dbb.automation.framework.common.ShutdownCoordinator.register(
                com.hsbc.cmb.hk.dbb.automation.framework.common.ShutdownCoordinator.ORDER_ROUTE_ENGINE,
                "route-engine", RouteEngine::shutdown);
    }

    // ─── Context 级引擎（实现下沉至 RouteLifecycleOwner）─────────────

    public static PerContextEngine startContextEngine(BrowserContext context) {
        return RouteLifecycleOwner.startContextEngine(context);
    }

    private static PerContextEngine getContextEngine(BrowserContext context) {
        return RouteLifecycleOwner.getContextEngine(context);
    }

    private static PerContextEngine getOrStartContextEngine(BrowserContext context) {
        return RouteLifecycleOwner.getOrStartContextEngine(context);
    }

    /** 停止并关闭指定 context 的引擎，清理其规则索引与合并引用。 */
    public static void stopContextEngine(BrowserContext context) {
        if (context == null) return;
        RouteLifecycleOwner.stopContextEngine(context);
        cleanupClosedContext(context);
    }

    /** 停止全部 context 引擎（测试套件 teardown 用）。 */
    public static void stopAllContextEngines() {
        for (BrowserContext context : new ArrayList<>(RouteContextState.CONTEXT_ENGINES.keySet())) {
            stopContextEngine(context);
        }
    }

    /**
     * 将动作延迟到「延迟线程」执行（delayMs<=0 则立即执行）。
     * B 方案核心：观测统一在 Playwright 事件线程（page.waitForResponse 的 action 回调内）发起，
     * 实际 resume 经本方法调度到延迟线程，避免事件线程被长时间阻塞、也避免调度线程直接驱动 waitForResponse 的竞态。
     */
    public static void scheduleDeferred(Route route, long delayMs, Runnable action) {
        DelayScheduler.scheduleDeferred(route, delayMs, action);
    }

    /**  优雅关闭所有调度器线程池（JVM 退出前调用）。实现下沉至 {@link DelayScheduler}。 */
    public static void shutdown() {
        DelayScheduler.shutdown();
    }

    // ─── 路由注册（实现下沉至 RuleRepository，此处仅保留门面）─────────
    // 实现已下沉至 RuleRepository（T2-4 拆分），此处仅保留对外 API 门面。

    public static void register(Page page, List<RouteRule> rules) {
        RuleRepository.register(page, rules);
    }

    public static void register(BrowserContext context, List<RouteRule> rules) {
        RuleRepository.register(context, rules);
    }

    public static void register(Object context, List<RouteRule> rules) {
        RuleRepository.register(context, rules);
    }

    /**
     *  Phase 5 准备：跨层合并的结果载体（纯数据，无可变状态）。
     * <p>对外公共契约类型，保留在 {@code RouteEngine}；构造逻辑见 {@link RouteUnifiedResolution}。
     */
    public static final class CrossLayerMergeResult {
        public final RouteRule rule;
        public final long delayMs;
        public final boolean delayMerged;

        public CrossLayerMergeResult(RouteRule rule, long delayMs, boolean delayMerged) {
            this.rule = rule;
            this.delayMs = delayMs;
            this.delayMerged = delayMerged;
        }
    }

    /**  Phase 5：实现下沉到 {@link RouteUnifiedResolution#mergeCrossLayer}（API 签名冻结，调用方零改动）。 */
    public static CrossLayerMergeResult mergeCrossLayer(RouteRule pageEffective, List<RouteRule> ctxChain) {
        return RouteUnifiedResolution.mergeCrossLayer(pageEffective, ctxChain);
    }

    /**
     *  Phase 3：统一解析结果载体（纯数据，无可变状态）。
     * <p>对外公共契约类型，保留在 {@code RouteEngine}；构造逻辑见 {@link RouteUnifiedResolution}。
     */
    public static final class ResolvedUnified {
        public final RouteRule rule;
        public final long delayMs;

        public ResolvedUnified(RouteRule rule, long delayMs) {
            this.rule = rule;
            this.delayMs = delayMs;
        }
    }

    /**  Phase 5：实现下沉到 {@link RouteUnifiedResolution#resolveUnified}（API 签名冻结，调用方零改动）。 */
    public static ResolvedUnified resolveUnified(List<RouteRule> chain, Object reqPage) {
        return RouteUnifiedResolution.resolveUnified(chain, reqPage);
    }

    /**
     * 路由分发 — 根据规则类型调用对应 Handler。
     *
     * <p>防重门控：同一 Route 对象被多个重叠 pattern 匹配时，
     * 仅第一个到达的 handler 执行，后续 handler 静默跳过（避免 "Route is already handled" 异常）。
     * <p>每次 handler 执行完成后立即 remove，避免阻塞同一 pattern 的后续请求。
     */
    /** 路由分发入口 —— 实现已下沉至 {@link Dispatcher}（T2-4 拆分），此处仅保留委派。 */
    static void dispatchRoute(Route route, List<RouteRule> chain) {
        Dispatcher.dispatchRoute(route, chain);
    }

    /**
     * 能力位选择（优先级裁决单一实现，已下沉至 {@link PriorityPolicy}）。
     * 保留 public 门面以兼容外部调用方与契约测试
     * （RouteCapabilityContractTest / RoutePriorityContractTest / RouteSameApiMultiRuleMergeTest）。
     */
    public static RouteHandleType selectCapability(RouteRule rule) {
        return PriorityPolicy.selectCapability(rule);
    }


    /**
     * 对指定上下文注销所有已注册 pattern 的 Playwright 路由。
     *
     * <p>用于 {@link RouteRegistry#clearContext(Object)} 中解决 MOCK/MODIFY
     * 无 MonitorSession 时路由无法解绑的问题。
     *
     * <p>单个 pattern 的 unroute 失败不影响后续 pattern（异常隔离）。
     *
     * @param context  Page 或 BrowserContext 实例
     * @param patterns 要注销的 URL pattern 集合
     */


    // ─── 按能力维度显式停止（实现下沉至 StoppedCapabilityManager，此处仅保留门面）───

    public static void stopMonitor(Object context, String urlPattern) {
        StoppedCapabilityManager.stopMonitor(context, urlPattern);
    }

    public static void stopModify(Object context, String urlPattern) {
        StoppedCapabilityManager.stopModify(context, urlPattern);
    }

    public static void stopDelay(Object context, String urlPattern) {
        StoppedCapabilityManager.stopDelay(context, urlPattern);
    }

    public static void stopMock(Object context, String urlPattern) {
        StoppedCapabilityManager.stopMock(context, urlPattern);
    }

    public static void stopAll(Object context, String urlPattern) {
        StoppedCapabilityManager.stopAll(context, urlPattern);
    }

    public static void clearStoppedCapabilities(Object context) {
        StoppedCapabilityManager.clearStoppedCapabilities(context);
    }

    public static void clearAllStoppedCapabilities() {
        StoppedCapabilityManager.clearAllStoppedCapabilities();
    }

    /** 将全局已停止能力注入当前请求的有效规则（按 context + pattern 匹配）。实现下沉至 StoppedCapabilityManager。 */
    private static void applyStoppedCapabilities(RouteRule rule, Route route) {
        StoppedCapabilityManager.applyStoppedCapabilities(rule, route);
    }

    /** 解析上下文对象（Page → 其 BrowserContext；BrowserContext 原样返回；其它返回 null）。 */
    static Object resolveContext(Object context) {
        if (context instanceof Page) {
            try {
                return ((Page) context).context();
            } catch (Exception e) {
                // page 可能已关闭，context() 抛异常则视为无有效 BrowserContext，返回 null 由调用方处理
                return null;
            }
        }
        if (context instanceof BrowserContext) return context;
        return null;
    }

    /** 与 registerInternal 一致的 pattern 归一化（补齐 ** 前缀 / 后缀）。 */
    static String normalizePattern(String urlPattern) {
        if (urlPattern == null) return null;
        String normalized = urlPattern;
        if (!normalized.startsWith("**")) {
            normalized = normalized.startsWith("/") ? "**" + normalized : "**/" + normalized;
        }
        if (!normalized.endsWith("**")) {
            normalized = TRAILING_WILDCARDS.matcher(normalized).replaceFirst("") + "**";
        }
        return normalized;
    }

    /**
     * 全局清理所有 MonitorSession。
     */
    /**  Phase 5：实现下沉到 RouteMonitorSession.onMonitorMatch（API 签名冻结，外部调用方零改动）。 */
    public static void onMonitorMatch(RouteRule rule) {
        RouteMonitorSession.onMonitorMatch(rule);
    }

    /**  Phase 5：实现下沉到 RouteMonitorSession.clearMonitorSessions（API 签名冻结）。 */
    public static void clearMonitorSessions(Object context) {
        RouteMonitorSession.clearMonitorSessions(context);
    }

    public static void clearAllMonitorSessions() {
        VerboseLogging.logDebugIfVerbose(LOGGER,
                "[RouteEngine] clearAllMonitorSessions: stopping {} session(s), clearing {} dispatched routes, {} context rules",
                RouteMonitorSession.sessionCount(), RouteContextState.DISPATCHED_ROUTES.size(), RuleRepository.contextRuleCount());
        RouteMonitorSession.clearAll();
        RouteContextState.DISPATCHED_ROUTES.clear();
    }

    /**
     *  按 Context 精确清理防重门控（Context 关闭时调用，不影响其它 Context）。
     * 仅移除该 context 桶，避免并行测试下全局清空误杀其它 Context。
     */
    public static void clearDispatchedRoutes(BrowserContext context) {
        if (context == null) return;
        RouteContextState.DISPATCHED_ROUTES.remove(context);
        VerboseLogging.logTraceIfVerbose(LOGGER,
                "[RouteEngine] clearDispatchedRoutes(context): cleared bucket for context {}",
                System.identityHashCode(context));
    }

    /**
     * 清空 Route 防重门控集合 + 跨层去重集合（全量，shutdown / clearAll / resetAll 时调用，释放已处理的 Route 引用）。
     */
    public static void clearDispatchedRoutes() {
        int dispatchedSize = RouteContextState.DISPATCHED_ROUTES.size();
        RouteContextState.DISPATCHED_ROUTES.clear();
        VerboseLogging.logTraceIfVerbose(LOGGER,
                "[RouteEngine] clearDispatchedRoutes: cleared {} dispatched entries",
                dispatchedSize);
    }

    /**
     *  移除指定页面的规则缓存（测试结束时调用，防止跨测试用例污染）。
     *
     * @param page 要移除规则缓存的页面
     */
    public static void removePageRules(Page page) {
        RuleRepository.removePageRules(page);
    }

    // ─── Context 级规则跨层级合并 ────────────────────────────

    /**
     *  在 CONTEXT_RULES 中查找与给定 URL 匹配的 context 级规则。
     *
     * <p>使用 glob 匹配（与 Playwright 注册时一样），normalized pattern 如
     * {@code ** /api/users/**} 被转换为子串匹配：提取 {@code /api/users}，
     * 检查 URL 是否包含该路径。
     *
     * @param url 请求 URL
     * @return 匹配的 context 规则，未找到则返回 null
     */




    /**
     * 从完整 URL 提取 path（含 query），去掉 scheme + host。
     * <p>例：{@code http://host:port/demo/api/users?x=1} → {@code /demo/api/users?x=1}
     */
    private static String extractPathFromUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String path = uri.getPath();
            if (uri.getRawQuery() != null) {
                path = path + "?" + uri.getRawQuery();
            }
            return path != null ? path : url;
        } catch (Exception e) {
            // URI.create 失败（非法 URL）时的兜底路径提取，尽量保留可用 path 而非中断
            int idx = url.indexOf("//");
            if (idx >= 0) {
                int slash = url.indexOf('/', idx + 2);
                return slash >= 0 ? url.substring(slash) : "/";
            }
            return url;
        }
    }

    /**
     *  移除指定 pattern 集合中的所有 context 级规则。
     * <p>由 {@link RouteRegistry#clearContext(Object)} 在清理上下文时调用。
     *
     * @param patterns 要移除的 normalized pattern 集合
     */
    public static void removeContextRules(Object context, Set<String> patterns) {
        RuleRepository.removeContextRules(context, patterns);
    }

    /**
     *  清理指定上下文的全部路由状态（测试/场景结束时调用，防止内存泄漏 + 跨用例污染）。
     *
     * <p>统一内聚清理逻辑，供 {@link RouteRegistry#clearContext(Object)} 委托调用，
     * 打破 RouteRegistry ↔ RouteEngine 双向依赖（RouteRegistry 只负责登记/反查）。
     *
     * <p>三步清理（避免双重 unroute）：
     * <ol>
     *   <li>从 RouteRegistry 移除该上下文的全部 pattern，并注销 Playwright 路由层</li>
     *   <li>清理 MonitorSession（内部会停止定时器，但不重复 unroute）</li>
     *   <li>清理 Route 防重门控集合（按 Context 精确清理）</li>
     * </ol>
     *
     * <p>任意一步失败不影响后续步骤（异常隔离）。
     *
     * @param context Page 或 BrowserContext 实例
     */
    public static void clearContext(Object context) {
        RuleRepository.clearContext(context);
    }

    /**
     *  断开「已注册的 Playwright 路由闭包」与规则链的关联 —— 就地清空链内容。
     *
     * <p><b>为什么仅移除 Map 条目不够</b>：注册时执行的是
     * {@code page.route(pattern, route -> dispatchRoute(route, chain))}，
     * 闭包<b>直接捕获 chain 这个 List 对象引用</b>，而框架刻意不调用 {@code page.unroute()}
     * （规避 Playwright 线程竞态，见 MonitorSession.stop 注释）。
     * 因此清理时只做 {@code RouteContextState.CONTEXT_RULES_BY_CONTEXT.remove/clear} 的话，原生路由仍然挂在 context 上、
     * 闭包持有的 chain 仍然非空 → {@code dispatchRoute} 取 {@code chain.get(0)} 继续按旧规则
     * mock/modify/断言，跨用例污染，且旧 RouteRule 无法被 GC。
     *
     * <p>就地 {@code chain.clear()} 后，闭包再被触发时 {@code dispatchRoute} 命中
     * 「empty rule chain」分支。该分支现使用 {@code RouteUtil.fallbackIfOpen}
     * （而非 {@code resumeIfOpen}）：因为 resume 会终结 Playwright 的 handler 链，
     * 使后续重新注册的同 pattern handler 永不执行；fallback 才符合"本 handler 不处理、
     * 交给下一个"的语义，且在没有下一个 handler 时自动退化为放行。
     */


    /**
     *  Context 生命周期结束（onClose）时清理规则索引与引擎合并引用。
     */
    public static void cleanupClosedContext(BrowserContext context) {
        RuleRepository.cleanupClosedContext(context);
    }

    /**
     *  全局清理统一路由规则存储 {@link #RouteContextState.CONTEXT_RULES_BY_CONTEXT}（测试套件结束时调用）。
     *
     * <p>统一绑定模型下 page 与 context 规则共存于同一 context 存储，故全局 teardown 仅需清理此处。
     * 必须逐链 clear（见 {@link #detachChains} 注释）：否则 BrowserContext 上仍挂着的路由闭包
     * 继续持有非空 chain，clearAllRules() 后旧规则照样生效、跨用例污染。
     */
    public static void clearAllUnifiedRuleStores() {
        RuleRepository.clearAllUnifiedRuleStores();
    }

}
