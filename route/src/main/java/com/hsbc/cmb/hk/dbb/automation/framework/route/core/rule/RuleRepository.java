package com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteContextState;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle.RouteMonitorSession;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle.StoppedCapabilityManager;

/**
 * 路由规则注册与索引仓库（T2-4 拆分，自 {@code RouteEngine} 提取）。
 *
 * <p>负责将 {@link RouteRule} 注册到 Page / BrowserContext（自动升级为 context 级单原生绑定点），
 * 维护按上下文索引的规则链（{@code RouteContextState.CONTEXT_RULES_BY_CONTEXT}），
 * 并在锁外执行原生 {@code context.route()} 注册（JNI/网络 IO）与 MonitorSession 刷新。
 *
 * <p>pattern 归一化复用 {@code RouteEngine.normalizePattern}（与注册语义一致，单一实现来源）；
 * 原生 route 回调统一委派 {@code RouteEngine.dispatchRoute}；日志复用 {@code RouteEngine.LOGGER}。
 *
 * @apiNote framework-internal：框架内部类型，非公开 API。跨子包 public 可见性仅为分层迁移需要，外部不得依赖。
 */
public final class RuleRepository {

    /**
     * 注册器（内部函数式接口）。
     *
     * @apiNote framework-internal：框架内部类型，非公开 API。跨子包 public 可见性仅为分层迁移需要，外部不得依赖。
     */
    @FunctionalInterface
    public interface RouteRegistrar {
        void register(String pattern, RouteRule rule);
    }

    /** 注册路由规则到 Page（升级为 context 级绑定）。 */
    public static void register(Page page, List<RouteRule> rules) {
        VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER, "[RouteEngine] ── Registering {} rule(s) on Page ──", rules.size());
        //  Phase 3 统一绑定：page 规则升级为 context 级绑定（单原生绑定点 context.route）。
        //    打 scope=PAGE + pageRef=page 逻辑标签，存进同 context 存储，交由 context.route 统一分发；
        //    不再使用 page.route / PAGE_RULES / reRegisterRules 跨页迁移链路（#6 #7 根除）。
        for (RouteRule r : rules) {
            if (r != null) {
                r.setScope(RouteRuleScope.PAGE);
                r.setPageRef(page);
            }
        }
        register(page.context(), rules);
    }

    /**
     * 注册路由规则到 BrowserContext。
     */
    public static void register(BrowserContext context, List<RouteRule> rules) {
        VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER, "[RouteEngine] ── Registering {} rule(s) on BrowserContext ──", rules.size());
        registerInternal(context, (pattern, rule) -> {
            Map<String, List<RouteRule>> store = RouteContextState.CONTEXT_RULES_BY_CONTEXT.computeIfAbsent(context, k -> new ConcurrentHashMap<>());
            //  仅在锁内完成 store 的纯内存状态决策，绝不持有锁调用原生 context.route()（JNI/网络 IO）。
            //    原生注册放在锁外执行，避免高并发注册/异常时持锁做 IO 导致线程长时间阻塞甚至重入风险。
            final boolean[] needRegister = {false};
            final boolean[] needRefresh = {false};
            //  用 AtomicReference 而非泛型数组：new List[1] 会产生「未经检查的转换」警告。
            final AtomicReference<List<RouteRule>> chainRef = new AtomicReference<>();
            synchronized (store) {
                List<RouteRule> chain = store.get(pattern);
                if (chain == null) {
                    // 首次注册：建链 + 绑定闭包（闭包捕获链引用，后续追加自动可见）
                    chain = new CopyOnWriteArrayList<>();
                    store.put(pattern, chain);
                    //  仅作清理记录（RouteRegistry 不再用于优先级决策，此处保留供 clearContext 反查 pattern）
                    RouteRegistry.forceRegister(context, pattern, rule.getType());
                    needRegister[0] = true;
                }
                chain.add(rule);
                chainRef.set(chain);
                //  追加规则携带 MONITOR 能力且当前无活跃会话时，锁外刷新（「先 modify 后追加 monitor」逆序场景）
                //  与 Page 级同理：仅按能力位 isMonitorEnabled() 判定，不再混用 type 默认值。
                if (rule.isMonitorEnabled()) {
                    needRefresh[0] = true;
                }
            }
            // 锁外执行原生路由注册（JNI）与 MonitorSession 刷新，不阻塞其它线程对 store 的访问
            if (needRegister[0]) {
                try {
                    registerRouteToContext(context, pattern, chainRef.get(), rule);
                } catch (Throwable t) {
                    //  关键一致性保护：原生注册失败（page 关闭竞态 / pattern 非法等）时，
                    //    必须回滚锁内已提交的 store + RouteRegistry 写入，否则会出现
                    //    「内存认为已注册、但实际路由从未绑定」的静默失效（请求完全不被拦截且无告警）。
                    synchronized (store) {
                        store.remove(pattern);
                    }
                    RouteRegistry.unregister(context, pattern);
                    RouteEngine.LOGGER.error("[RouteEngine] Native route registration failed for pattern '{}' on "
                            + "BrowserContext — rolled back in-memory state to avoid silent mismatch: {}",
                            pattern, t.getMessage());
                }
            }
            if (needRefresh[0]) {
                //  B3：会话绑定链头（mergeSource 归属）；是否创建按整链的 MONITOR 能力判定
                List<RouteRule> chain = chainRef.get();
                boolean chainHasMonitor = false;
                for (RouteRule r : chain) {
                    //  仅按能力位判定（理由同上：type 的 MONITOR 是默认值，不代表监控能力）
                    if (r.isMonitorEnabled()) {
                        chainHasMonitor = true;
                        break;
                    }
                }
                RouteMonitorSession.refreshMonitorSession(context, pattern, chain.get(0), chainHasMonitor);
            }
        }, rules);
    }

    /**
     * 注册路由规则到上下文（自动判断 Page 或 BrowserContext，适配 DSL 层调用）。
     *
     * @param context Page 或 BrowserContext 实例
     * @param rules   路由规则列表
     * @throws IllegalArgumentException 如果 context 类型不支持
     */
    public static void register(Object context, List<RouteRule> rules) {
        if (context instanceof Page) {
            register((Page) context, rules);
        } else if (context instanceof BrowserContext) {
            register((BrowserContext) context, rules);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported context type: " + context.getClass().getName()
                            + ". Expected Page or BrowserContext.");
        }
    }

    /**
     * 内部统一注册逻辑（异常隔离：单个规则失败不影响后续规则）。
     */
    private static void registerInternal(Object context, RouteRegistrar registrar, List<RouteRule> rules) {
        for (RouteRule rule : rules) {
            try {
                String pattern = rule.getUrlPattern();
                if (pattern == null || pattern.trim().isEmpty()) {
                    RouteEngine.LOGGER.warn("[RouteEngine] Skipping rule with empty urlPattern");
                    continue;
                }
                // 归一化复用 RouteEngine.normalizePattern（与注册语义一致，单一实现来源）
                String normalized = RouteEngine.normalizePattern(pattern);
                VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                        "[RouteEngine] registerInternal: original='{}' -> normalized='{}', type={}, context={}",
                        pattern, normalized, rule.getType(), context.getClass().getSimpleName());
                registrar.register(normalized, rule);
            } catch (Exception e) {
                RouteEngine.LOGGER.error("[RouteEngine] Failed to register rule for pattern '{}': {}",
                        rule.getUrlPattern(), e.getMessage(), e);
            }
        }
    }

    /**
     * 注册 Playwright 路由到 BrowserContext（实际 route + session 创建 + 跨层级缓存）。
     */
    private static void registerRouteToContext(BrowserContext context, String pattern, List<RouteRule> chain, RouteRule rule) {
        //  B3：闭包捕获规则链引用（而非单个 rule）——同 pattern 后续追加自动可见，分发期合并
        context.route(pattern, route -> RouteEngine.dispatchRoute(route, chain));
        //  Context 级规则链已在 register(BrowserContext) 中写入 RouteContextState.CONTEXT_RULES_BY_CONTEXT，此处无需重复存储
        RouteEngine.LOGGER.debug("[RouteEngine] Context rule cached: type={}, pattern='{}'",
                rule.getType(), pattern);
        RouteMonitorSession.startMonitorSession(context, rule, pattern);
        RouteEngine.LOGGER.info("[RouteEngine] Route registered: type={}, pattern='{}', context=BrowserContext",
                rule.getType(), pattern);
        VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                "[RouteEngine]    rule detail: urlPattern='{}', type={}, delay={}ms, mockStatus={}, record={}, autoStop={}",
                rule.getUrlPattern(), rule.getType(), rule.getDelayMs(), rule.getMockStatus(),
                rule.isRecord(), rule.isAutoStopOnMatch());
    }

    // ─── 规则索引清理 / 反注册 ───────────────

    /** 注销指定 pattern 集合的 Playwright 原生路由（异常隔离）。 */
    public static void unrouteAllForContext(Object context, Set<String> patterns) {
        VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                "[RouteEngine] unrouteAllForContext: unrouting {} pattern(s) from {}",
                patterns.size(), context.getClass().getSimpleName());
        for (String pattern : patterns) {
            try {
                if (context instanceof Page) {
                    ((Page) context).unroute(pattern);
                } else if (context instanceof BrowserContext) {
                    ((BrowserContext) context).unroute(pattern);
                }
                RouteEngine.LOGGER.debug("[RouteEngine] Unrouted pattern '{}' from context: {}",
                        pattern, context.getClass().getSimpleName());
            } catch (Exception e) {
                RouteEngine.LOGGER.warn("[RouteEngine] Failed to unroute pattern '{}' from context '{}': {}",
                        pattern, context.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /**  移除指定页面的规则缓存（按 pageRef 精确移除，保留同 context 的其它页 / 全局规则）。 */
    public static void removePageRules(Page page) {
        if (page == null) return;
        //  Phase 3 统一绑定：page 规则存于 context 存储，按 pageRef 精确移除。
        Map<String, List<RouteRule>> scoped = RouteContextState.CONTEXT_RULES_BY_CONTEXT.get(page.context());
        if (scoped != null) {
            for (List<RouteRule> chain : scoped.values()) {
                if (chain == null) continue;
                chain.removeIf(r -> r != null && r.getScope() == RouteRuleScope.PAGE && r.getPageRef() == page);
            }
            // 自然清理空链（handler 命中 empty chain 分支自动 fallback 放行，与 detachChains 不 unroute 策略一致）
            scoped.values().removeIf(chain -> chain == null || chain.isEmpty());
        }
    }

    /** 统计所有 Context 级规则总数（用于日志）。 */
    public static int contextRuleCount() {
        int count = 0;
        for (Map<String, List<RouteRule>> scoped : RouteContextState.CONTEXT_RULES_BY_CONTEXT.values()) {
            count += scoped.size();
        }
        return count;
    }

    /**  移除指定 pattern 集合中的所有 context 级规则（由 {@link RouteRegistry#clearContext(Object)} 委托）。 */
    public static void removeContextRules(Object context, Set<String> patterns) {
        if (context == null) return;
        //  必须先移除 context 条目：原实现在 patterns 为空时直接 return，导致「空 Map 残留」
        //    强引用已关闭的 BrowserContext，造成泄漏（见 cleanupClosedContext 注释）。
        Map<String, List<RouteRule>> scoped = RouteContextState.CONTEXT_RULES_BY_CONTEXT.remove(context);
        if (scoped == null) return;
        // patterns 为 null/空时视为「清理该 context 全部规则」（如 context 关闭时的整体清理）：
        // 复制 keySet 后再遍历，避免并发修改异常（scoped.keySet() 是视图，遍历中 remove 会 CME）。
        Set<String> toRemove = (patterns == null || patterns.isEmpty())
                ? new HashSet<>(scoped.keySet())
                : patterns;
        for (String pattern : toRemove) {
            List<RouteRule> ownerChain = scoped.remove(pattern);
            if (ownerChain != null) {
                //  最后就地清空链内容：BrowserContext 上的路由闭包捕获的是这个 List 引用
                //    （见 detachChains 注释），不清空则 clearContext 后旧规则仍会继续生效。
                ownerChain.clear();
            }
        }
        VerboseLogging.logDebugIfVerbose(RouteEngine.LOGGER,
                "[RouteEngine] Removed {} context rules for context, remaining: {}",
                toRemove.size(), scoped.size());
    }

    /**  清理指定上下文的全部路由状态（测试/场景结束时调用，防止内存泄漏 + 跨用例污染）。 */
    public static void clearContext(Object context) {
        // 1. 先从注册表移除，并注销 Playwright 路由层（无 MonitorSession 的 MOCK/MODIFY 路由需要）
        Map<String, RouteHandleType> patterns = RouteRegistry.removeContextPatterns(context);
        if (patterns != null && !patterns.isEmpty()) {
            //  同步清除 context 级规则注册表
            removeContextRules(context, patterns.keySet());
            unrouteAllForContext(context, patterns.keySet());
        }

        // 2. 清理 MonitorSession（停止定时器 + unroute，Playwright 对已注销的 pattern 幂等）
        RouteMonitorSession.clearMonitorSessions(context);

        // 3. 清理 Route 防重门控（ 按 Context 精确清理）
        RouteEngine.clearDispatchedRoutes(context instanceof BrowserContext ? (BrowserContext) context : null);

        //  修复 C1：同步清理 per-context 的「已停止能力」标记，防强引用泄漏与跨用例残留。
        StoppedCapabilityManager.clearStoppedCapabilities(context);
    }

    /**  Context 生命周期结束（onClose）时清理规则索引与引擎合并引用。 */
    public static void cleanupClosedContext(BrowserContext context) {
        if (context == null) return;
        //  防重门控 + MonitorSession 必须【无条件】清理，且都早于下方的 scoped 判空分支：
        //    「注册过防重门控 / 纯 monitor 但没有路由规则」的 context 关闭后条目否则会永久残留（空 Map 泄漏）。
        RouteEngine.clearDispatchedRoutes(context);
        RouteMonitorSession.clearMonitorSessions(context);
        //  修复 C1：清理 per-context 的「已停止能力」标记（STOPPED_CAPS 以 BrowserContext 为强引用键）。
        StoppedCapabilityManager.clearStoppedCapabilities(context);
        Map<String, List<RouteRule>> scoped = RouteContextState.CONTEXT_RULES_BY_CONTEXT.get(context);
        if (scoped != null) {
            //  即使 scoped 为空 Map 也要走 removeContextRules：该方法无条件移除 context 条目。
            removeContextRules(context, new HashSet<>(scoped.keySet()));
        }
    }

    /**  全局清理统一路由规则存储（测试套件结束时调用，必须逐链 clear，见 detachChains 注释）。 */
    public static void clearAllUnifiedRuleStores() {
        for (Map<String, List<RouteRule>> scoped : RouteContextState.CONTEXT_RULES_BY_CONTEXT.values()) {
            detachChains(scoped);
        }
        RouteContextState.CONTEXT_RULES_BY_CONTEXT.clear();
    }

    /**  断开「已注册的 Playwright 路由闭包」与规则链的关联 —— 就地清空链内容。 */
    private static void detachChains(Map<String, List<RouteRule>> store) {
        if (store == null || store.isEmpty()) return;
        for (List<RouteRule> chain : store.values()) {
            if (chain == null) continue;
            try {
                chain.clear();
            } catch (UnsupportedOperationException e) {
                //  不可变链不支持 clear()；关键在于【绝不能让异常中断整个清理流程】：
                //    一旦抛出，store.clear() 及调用方后续索引清理会被整段跳过 → 跨用例规则残留污染。
                VerboseLogging.logWarnIfVerbose(RouteEngine.LOGGER,
                        "[RouteEngine] detachChains: immutable rule chain cannot be cleared in place; "
                                + "relying on store.clear() to drop the reference.");
            }
        }
        store.clear();
    }
}
