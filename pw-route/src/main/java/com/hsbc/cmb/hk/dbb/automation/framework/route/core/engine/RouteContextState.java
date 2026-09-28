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
     * 原生路由句柄注册表（AutoCloseable）。
     *
     * <p>每 (BrowserContext, normalizedPattern) 恰好一个原生路由闭包（page 规则已升级为 context 级单绑定点），
     * 故句柄按 (context, pattern) 唯一。注册时由 {@code RuleRepository} 捕获 {@code context.route(...)}
     * 的返回值并登记；case 结束时由 {@code RuleRepository.clearContext} 经有界守护线程逐个 {@code close()}
     * 确定性注销（替代「清链但闭包仍挂 context」的技巧）。<b>不使用 {@code unrouteAll()} 兜底</b>：
     * 该收尾任务可能晚于下一个用例执行（feature 模式复用 Context），{@code unrouteAll()} 会摘掉新用例
     * 刚注册的路由，故改为仅按 pattern 精确 close，并跳过已被新用例接管的 pattern（见 {@code BoundedUnrouteTask}）。
     *
     * <p>句柄仅持有引用用于<b>归属记账与确定性关闭</b>；context 关闭路径仅清表释放引用（不 close，
     * 避免对已销毁对象发起 {@code unroute} 阻塞）。
     */
    public static final Map<BrowserContext, Map<String, AutoCloseable>> ROUTE_HANDLES = new ConcurrentHashMap<>();

    /** 登记某 context 某 pattern 的原生路由句柄（注册成功时调用，幂等覆盖）。 */
    public static void registerRouteHandle(BrowserContext context, String pattern, AutoCloseable handle) {
        if (context == null || pattern == null || handle == null) {
            return;
        }
        ROUTE_HANDLES.computeIfAbsent(context, k -> new ConcurrentHashMap<>()).put(pattern, handle);
    }

    /** 移除某 context 某 pattern 的句柄记账（注册回滚 / 精确注销时调用）。 */
    public static void removeRouteHandle(BrowserContext context, String pattern) {
        if (context == null || pattern == null) {
            return;
        }
        Map<String, AutoCloseable> m = ROUTE_HANDLES.get(context);
        if (m != null) {
            m.remove(pattern);
            if (m.isEmpty()) {
                ROUTE_HANDLES.remove(context);
            }
        }
    }

    /**
     * 取某 context 的全部句柄（只读副本，避免并发修改异常）。
     * <p>{@code null} context 返回空 Map（不抛异常）。
     */
    public static Map<String, AutoCloseable> getRouteHandles(BrowserContext context) {
        if (context == null) {
            return java.util.Collections.emptyMap();
        }
        Map<String, AutoCloseable> m = ROUTE_HANDLES.get(context);
        return m == null ? java.util.Collections.emptyMap() : new java.util.HashMap<>(m);
    }

    /** 仅清表释放引用（context 关闭路径；不 close 句柄）。 */
    public static void clearRouteHandles(BrowserContext context) {
        if (context != null) {
            ROUTE_HANDLES.remove(context);
        }
    }

    /**
     * 该 context 的指定 pattern 当前<b>是否已有登记句柄</b>（O(1)，不复制整表）。
     *
     * <p><b>用途（跨用例收尾竞态防护）</b>：上一用例的 teardown worker 可能在下一个用例开始后才执行。
     * 该 worker 只允许 close「自己 spawn 时登记的句柄」，且必须在<b>每个句柄 close 之前实时复查</b>本方法 ——
     * 若该 pattern 又出现条目，说明已被更新的用例重新注册，对其 close 会摘掉新路由（详见
     * {@code RuleRepository.BoundedUnrouteTask}）。
     *
     * @param context 目标上下文；{@code null} 返回 false
     * @param pattern 归一化后的 pattern
     * @return true 表示该 pattern 当前有登记句柄
     */
    public static boolean hasRouteHandle(BrowserContext context, String pattern) {
        if (context == null || pattern == null) {
            return false;
        }
        Map<String, AutoCloseable> m = ROUTE_HANDLES.get(context);
        return m != null && m.containsKey(pattern);
    }

    // ═══════════════════════════════════════════════════════════════
    // 「在途 unroute 收尾」登记表 + 跨用例栅栏（2026-09-26）
    // ═══════════════════════════════════════════════════════════════

    /**
     * per-context 在途 unroute 收尾登记表（<b>跨用例栅栏 + 确定性交接</b>）。
     *
     * <p><b>为什么需要</b>：teardown worker（{@code RuleRepository.BoundedUnrouteTask}）在守护线程中执行
     * 「排空在途拦截 + 逐个 close 原生路由句柄」。feature 模式下 Context 跨 scenario 复用，worker 实测常在
     * <b>下一个用例已经开始之后</b>才收工（用例1 收尾 12:34:58.531 spawn，用例2 12:34:59.24 已注册新规则，
     * worker 直至 12:35:03.996 才结束；2026-09-26 内网运行亦实测重叠 ~1.65s）。
     *
     * <p>窗口内两个线程会同时操作同一个 Playwright {@code Connection}：worker 在 drain 中
     * <b>派发页面事件</b>（日志实证：{@code [route-unroute-2076083498] PlaywrightContextManager - New page loaded: ...}），
     * 用例线程同时在导航。驱动侧对象生命周期因此错乱，实测抛
     * {@code PlaywrightException: Object doesn't exist: response@/worker@...}，
     * 进而「会话校验导航失败 → 缓存被误删 → 每轮都完整登录」。
     *
     * <p><b>2026-09-26 二次设计（为何不止是"有界等待"）</b>：实测 worker 的 drain 预算为 10s，而栅栏只等 2s
     * ⇒ 栅栏必然超时、我们付了延迟却<b>没拿到串行保证</b>。故改为 <b>先抢占、再短等</b>：
     * 下一个用例在复用 Context 之前调用 {@link UnrouteTask#requestPreempt()}（置标志 + 中断 worker），
     * worker 在 drain/每次 close 前检查标志，被中断即刻退出 ⇒ 交接是<b>确定性的</b>（毫秒级），
     * 而不是"赌超时"。
     */
    private static final Map<BrowserContext, UnrouteTask> PENDING_UNROUTE = new ConcurrentHashMap<>();

    /**
     * 一次「收尾任务」的句柄：完成信号 + 抢占能力 + worker 线程引用。
     *
     * <p>线程安全：{@code preemptRequested} 为原子标志；{@code worker} 在 worker 启动后登记
     * （登记时若已有抢占请求则<b>立即中断</b>，消除"抢占先于登记"的窗口）。
     */
    public static final class UnrouteTask {
        private final java.util.concurrent.CompletableFuture<Void> completion =
                new java.util.concurrent.CompletableFuture<>();
        private final java.util.concurrent.atomic.AtomicBoolean preemptRequested =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        private volatile Thread worker;

        /** 是否已被下一个用例要求停止（worker 应在 drain 前 / 每次 close 前检查）。 */
        public boolean isPreemptRequested() {
            return preemptRequested.get();
        }

        /** worker 启动后登记自身；若抢占请求已下达则立即自我中断。 */
        @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "EI_EXPOSE_REP2",
                justification = "Thread reference used only for interrupt coordination; no external mutable state touched")
        public void attachWorker(Thread workerThread) {
            this.worker = workerThread;
            if (preemptRequested.get()) {
                workerThread.interrupt();
            }
        }

        /** 请求 worker 立即停止触碰 Playwright 连接（幂等；worker 未登记时仅置标志，登记时补中断）。 */
        public void requestPreempt() {
            preemptRequested.set(true);
            Thread target = worker;
            if (target != null) {
                target.interrupt();
            }
        }

        /** 有界等待本任务真正结束（worker 已退出即返回 true）。 */
        public boolean await(long timeoutMs) {
            try {
                completion.get(Math.max(0L, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                return false;
            }
        }

        java.util.concurrent.CompletableFuture<Void> completion() {
            return completion;
        }
    }

    /**
     * 登记某 context 的收尾<b>开始</b>（必须在 worker 启动前调用，否则下一个用例会看不到在途收尾）。
     *
     * @param context 目标上下文；{@code null} 时返回的任务不进入登记表（仍须由调用方经 {@link #endUnroute} 完成）
     * @return 收尾任务句柄（含完成信号与抢占能力）
     */
    public static UnrouteTask beginUnroute(BrowserContext context) {
        UnrouteTask task = new UnrouteTask();
        if (context != null) {
            PENDING_UNROUTE.put(context, task);
        }
        return task;
    }

    /** 收尾<b>结束</b>（幂等）：注销登记并唤醒等待方。 */
    public static void endUnroute(BrowserContext context, UnrouteTask task) {
        if (task == null) {
            return;
        }
        if (context != null) {
            PENDING_UNROUTE.remove(context, task);
        }
        task.completion().complete(null);
    }

    /** 该 context 当前是否有在途 unroute 收尾（O(1)；栅栏的零开销快速路径）。 */
    public static boolean hasPendingUnroute(BrowserContext context) {
        return context != null && PENDING_UNROUTE.containsKey(context);
    }

    /**
     * 取该 context 当前的在途收尾任务（无则 {@code null}）。
     *
     * <p>供栅栏实现"先抢占、再短等"：拿到任务后调用 {@link UnrouteTask#requestPreempt()}。
     */
    public static UnrouteTask unrouteTaskFor(BrowserContext context) {
        return context == null ? null : PENDING_UNROUTE.get(context);
    }

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
        clearRouteHandles(context);
        cancelPendingTasksFor(context);
        //  context 已关闭：立即唤醒栅栏等待方（其等待对象已不会再有浏览器往返，无需空等满超时）。
        UnrouteTask pendingUnroute = PENDING_UNROUTE.remove(context);
        if (pendingUnroute != null) {
            pendingUnroute.completion().complete(null);
        }
        //  context 已关闭：其"无响应"标记随之失效（新 Context 是新连接，无需继承历史判定）。
        UNRESPONSIVE_CONTEXTS.remove(context);
    }

    /**
     * CT2-19：防御性清扫 —— 把「已被显式标记关闭」的 context 从全部<b>强引用键</b>状态表中移除。
     *
     * <p><b>为什么需要</b>：这些表的正常清理入口是 {@link RouteEngine#stopContextEngine}，而它是
     * <b>被动</b>调用（依赖 Playwright {@code onClose} 钩子 / 上层收尾链路）。一旦钩子注册失败
     * （见 {@code ApiMonitorOrchestrator#ensureCloseHook}）或 context 因崩溃/被杀未经正常关闭流程，
     * 强键表会残留该 context 的强引用 → context 及其全部 Page 无法 GC、跨用例串扰。
     * 本方法以「{@link #markContextClosed} 显式标记」为唯一判据做主动兜底，不会误伤仍存活的 context。
     *
     * <p>幂等；建议在套件 teardown（{@link RouteEngine#stopAllContextEngines()}）时调用。
     *
     * @return 被实际清扫的 context 数（仅统计确有残留条目的）
     */
    public static int pruneClosedContexts() {
        final java.util.Set<BrowserContext> closed;
        synchronized (CLOSED_CONTEXTS) {
            closed = new java.util.HashSet<>(CLOSED_CONTEXTS);
        }
        int pruned = 0;
        for (BrowserContext ctx : closed) {
            if (ctx == null) {
                continue;
            }
            boolean present = CONTEXT_RULES_BY_CONTEXT.containsKey(ctx)
                    || DISPATCHED_ROUTES.containsKey(ctx)
                    || STOPPED_CAPS.containsKey(ctx)
                    || CONTEXT_ENGINES.containsKey(ctx);
            if (present) {
                removeContextFromAllRegistries(ctx);
                pruned++;
            }
        }
        return pruned;
    }

    // ═══════════════════════════════════════════════════════════════
    // 「context 已关闭」标记（弱键，随 context GC 自动失效）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 已关闭的 Context 集合。
     *
     * <p><b>用途（「context 关闭 → 所有活动立即停止」）</b>：{@code MonitorHandler} 的在途观测/重试链会
     * 阻塞在 {@code page.waitForResponse}（≤20s）与 body 读（≤30s）上；仅 {@link #cancelPendingTasksFor}
     * 取消 future <b>不足以</b>让已进入阻塞的任务立即退出。故在 context 收口时登记本标记，使观测/重试链在
     * <b>每个可中断点</b>（进入前 / 每次重试前 / 读 body 前）检查并立即放弃，而不是空跑至超时。
     *
     * <p>弱键：context 被 GC 后条目自动失效，无需显式清理。
     */
    private static final Set<BrowserContext> CLOSED_CONTEXTS =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    /** 标记指定 context 已关闭（幂等；由 {@link RouteEngine#stopContextEngine} 收口调用）。 */
    public static void markContextClosed(BrowserContext context) {
        if (context != null) {
            CLOSED_CONTEXTS.add(context);
        }
    }

    /** 该 context 是否已关闭（关闭后其全部在途活动应尽快停止）。 */
    public static boolean isContextClosed(BrowserContext context) {
        return context != null && CLOSED_CONTEXTS.contains(context);
    }

    // ═══════════════════════════════════════════════════════════════
    // 「连接无响应」标记（弱键，随 context GC / 关闭自动失效）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 被判定为「浏览器/连接无响应」的 Context 集合。
     *
     * <p><b>判定来源</b>：Playwright 的协议往返中有一类<b>无客户端超时</b>的命令
     * （实测 {@code setNetworkInterceptionPatterns}：playwright 1.62
     * {@code BrowserContextImpl.updateInterceptionPatterns} 传 {@code NO_TIMEOUT}），
     * 浏览器不回 ACK 时调用方<b>永久阻塞</b>（实测：{@code main} 线程在 {@code context.route()} 上停 300s+
     * 且两次采样同帧）。框架已为该往返加有界预算（见 {@code RuleRepository.registerRouteBounded}），
     * 超时即在此登记本标记。
     *
     * <p><b>用途</b>：① 该 Context 上后续路由操作<b>立即快速失败</b>（不再各付一次超时预算）；
     * ② web 侧在下一个用例初始化时据此<b>重建 Context/浏览器</b>（见 {@code RouteLifecycle} SPI），
     * 使"连接损坏"不会级联到后续用例。
     *
     * <p>弱键；并在 {@link #removeContextFromAllRegistries}（context 关闭）时显式移除。
     */
    private static final Set<BrowserContext> UNRESPONSIVE_CONTEXTS =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    /** 标记该 context 的连接已无响应（幂等；由有界协议往返的超时路径调用）。 */
    public static void markUnresponsive(BrowserContext context) {
        if (context != null) {
            UNRESPONSIVE_CONTEXTS.add(context);
        }
    }

    /** 该 context 是否已被判定连接无响应（用于快速失败与下一个用例的浏览器重建）。 */
    public static boolean isUnresponsive(BrowserContext context) {
        return context != null && UNRESPONSIVE_CONTEXTS.contains(context);
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
        if (ctx == null)  {return null;} 
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
