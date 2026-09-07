package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Route;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Route 引擎的跨 Context 活跃状态收口（T2-4 剩余项）。
 *
 * <p>原散落在 {@code RouteEngine} 的 4 张 static 可变 Map 统一收口于此，消除静态状态散落、
 * 为后续（依赖 T2-1 第 5 步的模块拆分后）绑定 {@code TestContext} 生命周期做准备。
 * 本类当前仍持有 static 状态（与迁移前语义完全一致），仅做<b>位置收口，行为零变更</b>。
 *
 * <p>其中 {@link #DISPATCHED_ROUTES} 的写入 + 容量防御已收口为 {@link #markDispatched(BrowserContext)}，
 * 其余 Map 以包级可见字段形式集中托管，{@code RouteEngine} 经 {@code RouteContextState.xxx} 委托访问。
 */
final class RouteContextState {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteContextState.class);

    /** Context 隔离规则快照（值类型为规则链）；旧全局表仅用于无 Context 的兼容路径。 */
    static final Map<BrowserContext, Map<String, List<RouteRule>>> CONTEXT_RULES_BY_CONTEXT =
            new ConcurrentHashMap<>();

    /**
     * Route 防重门控分桶（按 BrowserContext 隔离）。
     * <p>key = 发生过分发的 BrowserContext（弱语义由调用方 closeContext 时 remove 保证），
     * value = 该 context 的空集合占位（仅用于标记「此 context 已分发」，不缓存具体 Route 引用）。
     * 每次测试结束经 {@link #clearDispatchedRoutes()} / {@link #clearDispatchedRoutes(BrowserContext)} 清空。
     */
    static final Map<BrowserContext, Set<Route>> DISPATCHED_ROUTES = new ConcurrentHashMap<>();

    /** DISPATCHED_ROUTES 单 context 容量上限，超过后自动清空该桶（防御性保护）。 */
    static final int MAX_DISPATCHED_ROUTES_PER_CONTEXT = 500;

    /** 已停止的能力标记（ctx -> pattern -> 类型）。 */
    static final Map<Object, Map<String, Set<RouteHandleType>>> STOPPED_CAPS = new ConcurrentHashMap<>();

    /** 每 context 的引擎实例（值类型 PerContextEngine 已提取为顶层类，故本 Map 可由本类集中持有）。 */
    static final Map<BrowserContext, PerContextEngine> CONTEXT_ENGINES = new ConcurrentHashMap<>();

    private RouteContextState() {
    }

    /**
     * 标记指定 context 已发生分发（防重门控分桶），并返回该 context 的桶。
     *
     * <p>行为与原 {@code RouteEngine.dispatchRoute} 内联逻辑完全一致：非 null context 时
     * {@code computeIfAbsent} 取得/创建空桶；桶达容量上限则清空（防御性）。
     * 注意：桶内容恒为空占位集合（仅标记 context 维度发生过分发），不缓存具体 Route 引用。
     *
     * @param ctx 发生分发的 BrowserContext；为 null 时返回 null（不记录）
     * @return 该 context 的桶，或 null（ctx 为 null）
     */
    static Set<Route> markDispatched(BrowserContext ctx) {
        if (ctx == null) return null;
        Set<Route> bucket = DISPATCHED_ROUTES.computeIfAbsent(ctx, k -> ConcurrentHashMap.newKeySet());
        // ═══ 防御性清理：单 context 桶超过上限时清空（防止异常情况下无限增长）═══
        if (bucket.size() >= MAX_DISPATCHED_ROUTES_PER_CONTEXT) {
            LOGGER.warn("[RouteContextState] DISPATCHED_ROUTES bucket reached {} entries for context, "
                    + "clearing to prevent memory leak", bucket.size());
            bucket.clear();
        }
        return bucket;
    }
}
