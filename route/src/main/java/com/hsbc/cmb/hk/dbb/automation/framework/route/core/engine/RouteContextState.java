package com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Route;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle.PerContextEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRule;

/**
 * Route 引擎的跨 Context 活跃状态收口（T2-4 剩余项）。
 *
 * <p>原散落在 {@code RouteEngine} 的 4 张 static 可变 Map 统一收口于此，消除静态状态散落、
 * 为后续（依赖 T2-1 第 5 步的模块拆分后）绑定 {@code TestContext} 生命周期做准备。
 * 本类当前仍持有 static 状态（与迁移前语义完全一致），仅做<b>位置收口，行为零变更</b>。
 *
 * <p>其中 {@link #DISPATCHED_ROUTES} 的写入 + 容量防御已收口为 {@link #markDispatched(BrowserContext)}，
 * 其余 Map 以包级可见字段形式集中托管，{@code RouteEngine} 经 {@code RouteContextState.xxx} 委托访问。
 *
 * @apiNote framework-internal：框架内部类型，非公开 API。跨子包 public 可见性仅为分层迁移需要，外部不得依赖。
 */
public final class RouteContextState {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteContextState.class);

    /** Context 隔离规则快照（值类型为规则链）；旧全局表仅用于无 Context 的兼容路径。 */
    public static final Map<BrowserContext, Map<String, List<RouteRule>>> CONTEXT_RULES_BY_CONTEXT =
            new ConcurrentHashMap<>();

    /**
     * Route 防重门控分桶（按 BrowserContext 隔离）。
     * <p>key = 发生过分发的 BrowserContext（弱语义由调用方 closeContext 时 remove 保证），
     * value = 该 context 的空集合占位（仅用于标记「此 context 已分发」，不缓存具体 Route 引用）。
     * 每次测试结束经 {@link #clearDispatchedRoutes()} / {@link #clearDispatchedRoutes(BrowserContext)} 清空。
     */
    public static final Map<BrowserContext, Set<Route>> DISPATCHED_ROUTES = new ConcurrentHashMap<>();

    /** DISPATCHED_ROUTES 单 context 容量上限，超过后自动清空该桶（防御性保护）。 */
    public static final int MAX_DISPATCHED_ROUTES_PER_CONTEXT = 500;

    /** 已停止的能力标记（ctx -> pattern -> 类型）。 */
    public static final Map<Object, Map<String, Set<RouteHandleType>>> STOPPED_CAPS = new ConcurrentHashMap<>();

    /** 每 context 的引擎实例（值类型 PerContextEngine 已提取为顶层类，故本 Map 可由本类集中持有）。 */
    public static final Map<BrowserContext, PerContextEngine> CONTEXT_ENGINES = new ConcurrentHashMap<>();

    /**
     * per-context 在途异步任务登记表（2026-09-17 评审新增）。
     *
     * <p><b>为什么放在 core</b>：上下文关闭时需要即时取消「属于该 context 的在途任务」（典型场景：
     * Monitor 的 body 读取重试链 —— 用例跑完后残链仍在续投并让等待方耗尽预算，表现为"卡 timeout"）。
     * 但 {@code route.core.*} 依 ArchUnit 规则<b>不得</b>依赖 {@code route.handler.*}，故登记表下沉到本类
     * （core 的跨 Context 状态收口点），由 handler 侧登记、由 {@link RouteEngine#stopContextEngine} 统一取消。
     *
     * <p>条目在任务完成（正常/异常/取消）时自动注销；集合空即移除 context 键，避免长期占用。
     */
    private static final Map<BrowserContext, Set<java.util.concurrent.CompletableFuture<?>>> PENDING_TASKS =
            new ConcurrentHashMap<>();

    /**
     * 登记一条「属于某 context 的在途异步任务」；任务完成（正常/异常/取消）时自动注销。
     *
     * @param context 任务所属上下文；{@code null} 表示无归属（不登记）
     * @param task    可取消的异步任务（如 {@code CompletableFuture}）
     */
    public static void registerPendingTask(BrowserContext context, java.util.concurrent.CompletableFuture<?> task) {
        if (context == null || task == null) {
            return;
        }
        PENDING_TASKS.computeIfAbsent(context, k -> ConcurrentHashMap.newKeySet()).add(task);
        task.whenComplete((value, error) -> PENDING_TASKS.computeIfPresent(context, (k, set) -> {
            set.remove(task);
            return set.isEmpty() ? null : set;
        }));
    }

    /**
     * 取消指定上下文的全部在途异步任务（上下文生命周期收口时调用）。
     *
     * @param context 目标上下文；{@code null} 时 no-op
     * @return 实际被取消的任务数
     */
    public static int cancelPendingTasksFor(BrowserContext context) {
        if (context == null) {
            return 0;
        }
        Set<java.util.concurrent.CompletableFuture<?>> pending = PENDING_TASKS.remove(context);
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        int cancelled = 0;
        for (java.util.concurrent.CompletableFuture<?> task : pending) {
            if (task.cancel(true)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    /** 取消全部上下文在途任务（JVM 收尾用）。 */
    public static int cancelAllPendingTasks() {
        int total = 0;
        for (BrowserContext context : new java.util.ArrayList<>(PENDING_TASKS.keySet())) {
            total += cancelPendingTasksFor(context);
        }
        return total;
    }

    /**
     * 强键注册表归零守卫（C-11）：将指定 context 从所有以 {@code BrowserContext} 为强引用键的状态表中移除，
     * 并取消其全部在途异步任务，确保上下文关闭后强键表不残留该 context 的强引用
     * （防已关闭 context 被长期持有导致内存泄漏与跨用例串扰）。
     *
     * <p>幂等（重复调用无副作用），供 {@link #cleanupClosedContext(BrowserContext)} 在上下文收口末段调用，
     * 作为「强键表归零」的权威兜底：无论各子清理路径（路由层 / 引擎层 / MonitorSession）是否各自执行，
     * 此处统一清零，杜绝新增强键表时遗漏清理导致泄漏的回归。
     *
     * @param context 目标上下文；{@code null} 时 no-op
     */
    public static void removeContextFromAllRegistries(BrowserContext context) {
        if (context == null) {
            return;
        }
        CONTEXT_RULES_BY_CONTEXT.remove(context);
        DISPATCHED_ROUTES.remove(context);
        STOPPED_CAPS.remove(context);
        CONTEXT_ENGINES.remove(context);
        cancelPendingTasksFor(context);
    }

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
