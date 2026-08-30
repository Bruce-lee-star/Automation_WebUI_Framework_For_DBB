package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.DelayHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.MockHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.MonitorHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SensitiveDataSanitizer;
import com.hsbc.cmb.hk.dbb.automation.framework.common.async.AsyncPool;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.concurrent.ScheduledThreadPoolExecutor;

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

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteEngine.class);

    /** 预编译 Pattern — 归一化 URL 路径末尾的通配符 */
    private static final Pattern TRAILING_WILDCARDS = Pattern.compile("\\*+$");



    /**
     * Monitor 会话注册表：稳定的 scope 身份 + 注册 pattern → 会话。
     * 不以可由 DSL 合并修改的 RouteRule 作为 Map key，避免 hash/equals 漂移导致会话无法查询或清理。
     */
    private static final Map<MonitorSessionKey, MonitorSession> SESSIONS = new ConcurrentHashMap<>();

    /**
     * ⭐ Context 级路由规则注册表：normalizedPattern → 规则链 List&lt;RouteRule&gt;。
     *
     * <p>解决 Playwright Page route 优先级高于 BrowserContext route 导致
     * context 级规则被 page 级规则完全屏蔽的问题。
     *
     * <p>当 dispatchRoute 处理 page 级请求时，额外检查此注册表，
     * 按固定优先级合并 context + page 规则：
     * <ol>
     *   <li><b>MOCK</b> — 终结请求，若任一 level 有 MOCK 则覆盖其他</li>
     *   <li><b>MODIFY</b> — 修改请求，先于 MONITOR/DELAY 执行</li>
     *   <li><b>MONITOR</b> — 监控响应，可与其他类型共存</li>
     *   <li><b>DELAY</b> — 延迟请求，始终合并到其余类型</li>
     * </ol>
     *
     * <p>B3 链式模型：同 pattern 多次注册追加到链尾（后注册优先），
     * 分发时对链执行分发期合并（copyForMerge + 依次 mergeFrom），
     * 语义与就地 mergeFrom 完全一致但无共享可变状态。
     */
    /**
     * ⭐ 性能优化：context 规则的预提取 path 子串缓存。
     * key = 归一化 pattern，value = 预先提取的 path（见 extractPathFromNormalizedPattern）。
     * 避免在 dispatchRoute 跨层合并的高频路径中每次请求都 substring 分配新 String。
     */
    private static final Map<String, String> CONTEXT_RULE_PATHS = new ConcurrentHashMap<>();
    /** 按 path literal 前缀索引 Context 规则；通配前缀规则进入 fallback。 */
    private static final Map<String, Set<String>> CONTEXT_RULE_KEYS_BY_PREFIX = new ConcurrentHashMap<>();
    private static final Set<String> CONTEXT_RULE_FALLBACK_KEYS = ConcurrentHashMap.newKeySet();
    /** Context 隔离规则快照（值类型为规则链）；旧全局表仅用于无 Context 的兼容路径。 */
    private static final Map<BrowserContext, Map<String, List<RouteRule>>> CONTEXT_RULES_BY_CONTEXT =
            new ConcurrentHashMap<>();

    /**
     * Route 防重门控 — 当同一请求匹配多个重叠 pattern 时，
     * 只有第一个 handler 处理，后续 handler 静默跳过。
     *
     * <p>场景：page.route("/api/**", h1) + page.route("/api/user", h2)
     * 请求 /api/user 同时命中两个 pattern，h1 先调用 route.resume()，
     * h2 再尝试操作会导致 PlaywrightException: Route is already handled。
     * 此集合用 add 保证只有首个 handler 执行。
     *
     * <p>每次测试结束通过 {@link #clearDispatchedRoutes()} 清空。
     * 同时设置容量上限，防止异常情况下（未调用 clear 的场景）无限增长。
     */
    /**
     * ⭐ 修复 P0-1：防重门控按 BrowserContext 分桶，消除并行测试下全局清空误杀其它 Context 的问题。
     * key = BrowserContext（弱语义由调用方 closeContext 时 remove 保证），value = 该 context 内已处理的 Route 集合。
     * 串行场景行为不变；真并行（多 Context 同时 active）下，clearContext(A) 不再影响 B。
     */
    private static final Map<BrowserContext, Set<Route>> DISPATCHED_ROUTES = new ConcurrentHashMap<>();

    /** DISPATCHED_ROUTES 单 context 容量上限，超过后自动清空该桶（防御性保护） */
    private static final int MAX_DISPATCHED_ROUTES_PER_CONTEXT = 500;










    /** 超时调度器（守护线程，避免阻塞 JVM 退出） */
    /** 超时检查调度委托通用异步池（AsyncPool.schedule），无独立调度器 */

    /** 网络延迟调度器（多线程池，支持并发请求同时延迟） */
    private static volatile ScheduledExecutorService DELAY_SCHEDULER =
            newDelayScheduler();

    // ⭐ 修复 P0-3：注册 JVM 关闭钩子，确保进程退出时优雅关闭调度器（DELAY_SCHEDULER 等），
    // 避免并行测试或浏览器崩溃场景下线程池任务永久挂起。shutdown() 内部由 scheduledShutdown CAS 保护，重复调用安全。
    static {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(RouteEngine::shutdown, "route-engine-shutdown"));
        } catch (Exception ignored) {
            // 某些受限环境（如部分应用服务器）不允许注册关闭钩子，忽略即可
        }
    }

    private static ScheduledExecutorService newDelayScheduler() {
        return Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "route-network-delay");
            t.setDaemon(true);
            return t;
        });
    }

    // ═══════════════════════════════════════════════════════════
    // Context 级引擎注册表（内联自 ContextRouteEngine / ContextRouteEngineManager）
    // ═══════════════════════════════════════════════════════════

    /** Context 级路由引擎状态。 */
    private enum EngineState { RUNNING, CLOSING, CLOSED }

    /** 每个 BrowserContext 独立的引擎实例：持有 per-Context 延迟调度器。 */
    private static final class PerContextEngine {
        final BrowserContext context;
        final String contextId;
        final ScheduledThreadPoolExecutor delayScheduler;
        volatile EngineState state = EngineState.RUNNING;

        PerContextEngine(BrowserContext context) {
            this.context = context;
            this.contextId = Integer.toHexString(System.identityHashCode(context));
            this.delayScheduler = AsyncPool.newContextScheduler(contextId, 2);
        }

        ScheduledExecutorService delayScheduler() {
            if (state != EngineState.RUNNING) {
                throw new IllegalStateException("Context route engine is not running");
            }
            return delayScheduler;
        }

        /** 优雅关闭：不中断在途 DELAY 任务（修复 P0-6），让其 sleep 结束自然 resume。 */
        void close() {
            if (state != EngineState.RUNNING) return;
            state = EngineState.CLOSING;
            delayScheduler.shutdown();
            try {
                if (!delayScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    delayScheduler.shutdownNow();
                    delayScheduler.awaitTermination(1, TimeUnit.SECONDS);
                }
            } catch (InterruptedException interrupted) {
                delayScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                AsyncPool.removeContextScheduler(contextId);
                state = EngineState.CLOSED;
            }
        }
    }

    private static final Map<BrowserContext, PerContextEngine> CONTEXT_ENGINES = new ConcurrentHashMap<>();

    public static PerContextEngine startContextEngine(BrowserContext context) {
        if (context == null) throw new IllegalArgumentException("BrowserContext must not be null");
        return CONTEXT_ENGINES.compute(context, (ignored, existing) ->
                existing == null || existing.state == EngineState.CLOSED
                        ? new PerContextEngine(context) : existing);
    }

    private static PerContextEngine getContextEngine(BrowserContext context) {
        return context == null ? null : CONTEXT_ENGINES.get(context);
    }

    private static PerContextEngine getOrStartContextEngine(BrowserContext context) {
        PerContextEngine engine = getContextEngine(context);
        return engine != null && engine.state == EngineState.RUNNING ? engine : startContextEngine(context);
    }

    /** 停止并关闭指定 context 的引擎，清理其规则索引与合并引用。 */
    public static void stopContextEngine(BrowserContext context) {
        if (context == null) return;
        PerContextEngine engine = CONTEXT_ENGINES.remove(context);
        if (engine != null) {
            engine.close();
            cleanupClosedContext(context);
        }
    }

    /** 停止全部 context 引擎（测试套件 teardown 用）。 */
    public static void stopAllContextEngines() {
        for (BrowserContext context : new ArrayList<>(CONTEXT_ENGINES.keySet())) {
            stopContextEngine(context);
        }
    }

    private static ScheduledExecutorService delayScheduler(Route route) {
        try {
            if (route != null && route.request() != null && route.request().frame() != null
                    && route.request().frame().page() != null) {
                BrowserContext context = route.request().frame().page().context();
                PerContextEngine contextEngine = getOrStartContextEngine(context);
                if (contextEngine.state == EngineState.RUNNING) return contextEngine.delayScheduler();
            }
        } catch (Exception ignored) {
            // Page/Context 已销毁时回退兼容调度器。
        }
        return delayScheduler();
    }

    /**
     * 将动作延迟到「延迟线程」执行（delayMs<=0 则立即执行）。
     * B 方案核心：观测统一在 Playwright 事件线程（page.waitForResponse 的 action 回调内）发起，
     * 实际 resume 经本方法调度到延迟线程，避免事件线程被长时间阻塞、也避免调度线程直接驱动 waitForResponse 的竞态。
     */
    public static void scheduleDeferred(Route route, long delayMs, Runnable action) {
        if (action == null) return;
        if (delayMs > 0) {
            delayScheduler(route).schedule(action, delayMs, TimeUnit.MILLISECONDS);
        } else {
            action.run();
        }
    }

    private static ScheduledExecutorService delayScheduler() {
        ScheduledExecutorService current = DELAY_SCHEDULER;
        if (current == null || current.isShutdown() || current.isTerminated()) {
            synchronized (RouteEngine.class) {
                current = DELAY_SCHEDULER;
                if (current == null || current.isShutdown() || current.isTerminated()) {
                    DELAY_SCHEDULER = current = newDelayScheduler();
                    scheduledShutdown.set(false);
                }
            }
        }
        return current;
    }

    /** 标记调度器是否已关闭 */
    private static final AtomicBoolean scheduledShutdown = new AtomicBoolean(false);

    /**
     * ⭐ 优雅关闭所有调度器线程池（JVM 退出前调用）。
     *
     * <p>关闭顺序：
     * <ol>
     *   <li>清除所有 MonitorSession（停止超时任务）</li>
     *   <li>清理 RouteRegistry 全局注册表 + clearAllMonitorSessions</li>
     *   <li>清空 DISPATCHED_ROUTES / CONTEXT_RULES</li>
     *   <li>关闭 DELAY_SCHEDULER（先中断，再等待未完成任务 2 秒）</li>
     *   <li>关闭 SCHEDULER（先中断，再等待未完成任务 2 秒）</li>
     * </ol>
     */
    public static void shutdown() {
        if (!scheduledShutdown.compareAndSet(false, true)) return;

        LOGGER.info("[RouteEngine] Shutting down schedulers...");

        // ⭐ 清理所有上下文路由注册表（含 Playwright 层的 unroute）
        RouteRegistry.clearAll();
        clearAllMonitorSessions();
        DISPATCHED_ROUTES.clear();
        CONTEXT_RULE_PATHS.clear();
        CONTEXT_RULE_KEYS_BY_PREFIX.clear();
        CONTEXT_RULE_FALLBACK_KEYS.clear();

        // 关闭网络延迟调度器
        DELAY_SCHEDULER.shutdownNow();
        try {
            if (!DELAY_SCHEDULER.awaitTermination(2, TimeUnit.SECONDS)) {
                LOGGER.warn("[RouteEngine] DELAY_SCHEDULER did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 超时调度已委托通用异步池 AsyncPool，由其在 JVM 关闭钩子/显式 shutdown 时统一关闭
        LOGGER.info("[RouteEngine] All schedulers shut down");
    }

    /**
     * 注册路由规则到 Page。
     */
    public static void register(Page page, List<RouteRule> rules) {
        LoggingConfigUtil.logDebugIfVerbose(LOGGER, "[RouteEngine] ── Registering {} rule(s) on Page ──", rules.size());

        // ⭐ Phase 3 统一绑定：page 规则升级为 context 级绑定（单原生绑定点 context.route）。
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
        LoggingConfigUtil.logDebugIfVerbose(LOGGER, "[RouteEngine] ── Registering {} rule(s) on BrowserContext ──", rules.size());
        registerInternal(context, (pattern, rule) -> {
            Map<String, List<RouteRule>> store = CONTEXT_RULES_BY_CONTEXT.computeIfAbsent(context, k -> new ConcurrentHashMap<>());
            // ⭐ 仅在锁内完成 store 的纯内存状态决策，绝不持有锁调用原生 context.route()（JNI/网络 IO）。
            //    原生注册放在锁外执行，避免高并发注册/异常时持锁做 IO 导致线程长时间阻塞甚至重入风险。
            final boolean[] needRegister = {false};
            final boolean[] needRefresh = {false};
            // ⭐ 用 AtomicReference 而非泛型数组：new List[1] 会产生「未经检查的转换」警告。
            final AtomicReference<List<RouteRule>> chainRef = new AtomicReference<>();
            synchronized (store) {
                List<RouteRule> chain = store.get(pattern);
                if (chain == null) {
                    // 首次注册：建链 + 绑定闭包（闭包捕获链引用，后续追加自动可见）
                    chain = new CopyOnWriteArrayList<>();
                    store.put(pattern, chain);
                    // ⭐ 仅作清理记录（RouteRegistry 不再用于优先级决策，此处保留供 clearContext 反查 pattern）
                    RouteRegistry.forceRegister(context, pattern, rule.getType());
                    needRegister[0] = true;
                }
                chain.add(rule);
                chainRef.set(chain);
                // ⭐ 追加规则携带 MONITOR 能力且当前无活跃会话时，锁外刷新（「先 modify 后追加 monitor」逆序场景）
                // ⭐ 与 Page 级同理：仅按能力位 isMonitorEnabled() 判定，不再混用 type 默认值。
                if (rule.isMonitorEnabled()) {
                    needRefresh[0] = true;
                }
            }
            // 锁外执行原生路由注册（JNI）与 MonitorSession 刷新，不阻塞其它线程对 store 的访问
            if (needRegister[0]) {
                try {
                    registerRouteToContext(context, pattern, chainRef.get(), rule);
                } catch (Throwable t) {
                    // ⭐ 关键一致性保护：原生注册失败（page 关闭竞态 / pattern 非法等）时，
                    //    必须回滚锁内已提交的 store + RouteRegistry 写入，否则会出现
                    //    「内存认为已注册、但实际路由从未绑定」的静默失效（请求完全不被拦截且无告警）。
                    synchronized (store) {
                        store.remove(pattern);
                    }
                    RouteRegistry.unregister(context, pattern);
                    LOGGER.error("[RouteEngine] Native route registration failed for pattern '{}' on "
                            + "BrowserContext — rolled back in-memory state to avoid silent mismatch: {}",
                            pattern, t.getMessage());
                }
            }
            if (needRefresh[0]) {
                // ⭐ B3：会话绑定链头（mergeSource 归属）；是否创建按整链的 MONITOR 能力判定
                List<RouteRule> chain = chainRef.get();
                boolean chainHasMonitor = false;
                for (RouteRule r : chain) {
                    // ⭐ 仅按能力位判定（理由同上：type 的 MONITOR 是默认值，不代表监控能力）
                    if (r.isMonitorEnabled()) {
                        chainHasMonitor = true;
                        break;
                    }
                }
                refreshMonitorSession(context, pattern, chain.get(0), chainHasMonitor);
            }
        }, rules);
    }

    /**
     * 注册 Playwright 路由到 BrowserContext（实际 route + session 创建 + 跨层级缓存）。
     */
    private static void registerRouteToContext(BrowserContext context, String pattern, List<RouteRule> chain, RouteRule rule) {
        // ⭐ B3：闭包捕获规则链引用（而非单个 rule）——同 pattern 后续追加自动可见，分发期合并
        context.route(pattern, route -> dispatchRoute(route, chain));
        // ⭐ Context 级规则链已在 register(BrowserContext) 中写入 CONTEXT_RULES_BY_CONTEXT，此处无需重复存储
        // ⭐ 性能优化：预提取 path 子串并缓存（避免高频匹配时重复 substring）
        CONTEXT_RULE_PATHS.put(pattern, extractPathFromNormalizedPattern(pattern));
        indexContextRule(pattern, CONTEXT_RULE_PATHS.get(pattern));
        LOGGER.debug("[RouteEngine] Context rule cached: type={}, pattern='{}'",
                rule.getType(), pattern);
        startMonitorSession(context, rule, pattern);
        LOGGER.info("[RouteEngine] Route registered: type={}, pattern='{}', context=BrowserContext",
                rule.getType(), pattern);
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine]    rule detail: urlPattern='{}', type={}, delay={}ms, mockStatus={}, record={}, autoStop={}",
                rule.getUrlPattern(), rule.getType(), rule.getDelayMs(), rule.getMockStatus(),
                rule.isRecord(), rule.isAutoStopOnMatch());
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
                    LOGGER.warn("[RouteEngine] Skipping rule with empty urlPattern");
                    continue;
                }
                // 归一化：补齐前后 **，与 Playwright 全 URL（含查询参数）匹配兼容
                // 例：/api/users/1 → **/api/users/1** 可匹配 http://host:port/api/users/1?page=2
                //     auth/assert  → **/auth/assert**  可匹配 http://host:port/portalserver/auth/assert
                // Playwright page.route() glob 匹配完整 URL 字符串，必须用 **/ 前缀覆盖 scheme+host 部分
                String normalized = pattern;
                // ① 前缀：如果没有 ** 开头（已有通配前缀则不动），补齐 **/ 以匹配任何 URL 前缀
                if (!normalized.startsWith("**")) {
                    normalized = normalized.startsWith("/") ? "**" + normalized : "**/" + normalized;
                }
                // ② 后缀：补齐 ** 以匹配查询参数
                if (!normalized.endsWith("**")) {
                    normalized = TRAILING_WILDCARDS.matcher(normalized).replaceFirst("") + "**";
                }
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteEngine] registerInternal: original='{}' -> normalized='{}', type={}, context={}",
                        pattern, normalized, rule.getType(), context.getClass().getSimpleName());
                registrar.register(normalized, rule);
            } catch (Exception e) {
                LOGGER.error("[RouteEngine] Failed to register rule for pattern '{}': {}",
                        rule.getUrlPattern(), e.getMessage(), e);
            }
        }
    }

    /**
     * ⭐ B3 链式模型：将规则链合并为<b>一次性有效规则</b>（分发期合并，后注册优先覆盖）。
     *
     * <p>对齐跨层合并的 copyForMerge 模式——<b>绝不就地修改</b>闭包/注册表持有的原始规则：
     * <ul>
     *   <li>链头为基础 copyForMerge（快照其全部能力位与请求条件字段）；</li>
     *   <li>依次 mergeFrom 后续规则（后注册覆盖先注册：MODIFY putAll、DELAY 取 max、MONITOR 基线不可关）；</li>
     *   <li>链上任一规则为 MOCK → 有效规则 type 提升为 MOCK（终结，短路）；</li>
     *   <li>mergeSource 指向链头——session 查询 / times 递减 / 跨层 identity 始终作用于源规则。</li>
     * </ul>
     *
     * <p>size &lt;= 1 时直接返回链头自身（零拷贝，独立规则不受影响）。
     *
     * @param chain 规则链（CONTEXT_RULES_BY_CONTEXT 中 pattern 对应的 List）
     * @return 有效规则；链为空返回 null
     */
    private static RouteRule resolveChain(List<RouteRule> chain) {
        if (chain == null || chain.isEmpty()) return null;
        RouteRule head = chain.get(0);
        if (chain.size() == 1) return head;
        RouteRule effective = head.copyForMerge();
        for (int i = 1; i < chain.size(); i++) {
            RouteRule r = chain.get(i);
            if (r == null) continue;
            effective.mergeFrom(r);
            // MOCK 终结提升（对齐跨层规则）：链上任一规则为 MOCK → 有效规则 type=MOCK
            if (r.getType() == RouteHandleType.MOCK) {
                effective.setType(RouteHandleType.MOCK);
            }
        }
        effective.setMergeSource(head);
        return effective;
    }

    /**
     * ⭐ Phase 5 准备：跨层合并的结果载体（纯数据，无可变状态）。
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

    /**
     * ⭐ Phase 5 准备：跨层（Page + Context）合并纯函数 —— 无 Playwright 依赖，可纯单测。
     *
     * <p>与 {@code dispatchRoute} 内联跨层合并（原 777–826 行）严格等价，作为统一绑定模型的合并核心。
     * 输入已「同层合并」的 page 有效规则与 context 规则链，输出一次性有效规则 + 合并后 DELAY + 是否发生跨层合并。
     *
     * <p>合并语义（对齐 {@code ROUTE_SCOPE_AND_PRIORITY.md} §2 / §5）：
     * <ul>
     *   <li>DELAY 取 max；ctx 层 delay 始终保留，page 层 delay 仅当 ctx 为终结者（MOCK）时丢弃；</li>
     *   <li>能力位跨层 OR 叠加（MODIFY/MONITOR/DELAY 共存），用 {@code copyForMerge()} 构造一次性拷贝，
     *       绝不就地修改输入（否则跨请求行为漂移 + 集合字段无限累积）；</li>
     *   <li>MOCK 为唯一终结者：任一层为 MOCK → 有效规则 type=MOCK（短路）；否则保持 page 主 type。</li>
     * </ul>
     *
     * <p>注：pageEffective 若已是同层合并拷贝，此处仍 {@code copyForMerge()} 一次（一次性的无害额外拷贝），
     * 换取与原始内联逻辑严格等价、且输入永不被改动的不变量。
     *
     * @param pageEffective 已同层合并的 page 有效规则（不会就地修改）
     * @param ctxChain      context 规则链（非空；内部 {@link #resolveChain} 合并），不会就地修改
     * @return 跨层合并结果
     */
    public static CrossLayerMergeResult mergeCrossLayer(RouteRule pageEffective, List<RouteRule> ctxChain) {
        RouteRule ctxEffective = resolveChain(ctxChain);
        RouteHandleType pageType = pageEffective.getType();
        RouteHandleType ctxType = ctxEffective.getType();

        long ctxDelay = DelayHandler.clampDelay(DelayHandler.resolveDelay(ctxEffective));
        boolean ctxTerminates = (ctxType == RouteHandleType.MOCK);
        long pageDelay = ctxTerminates ? 0 : DelayHandler.clampDelay(DelayHandler.resolveDelay(pageEffective));
        long delayMs = Math.max(pageDelay, ctxDelay);

        // ⭐ 关键：用 copyForMerge() 构造一次性有效规则，绝不就地修改 pageEffective / ctxEffective
        RouteRule effective = pageEffective.copyForMerge();
        effective.mergeFrom(ctxEffective);   // 仅叠加能力位（MODIFY/MONITOR/DELAY 共存）
        // ⭐ 修复（统一绑定「page 特定 > context 全域」）：MOCK 响应体由 mock 提供方决定，
        //    page 为 MOCK → page 的 status/body 胜出；否则若 ctx 为 MOCK → ctx 的 status/body 胜出；
        //    其余类型无响应体，沿用 mergeFrom 的能力位 OR 结果。避免 ctx 的 mockStatus/body 覆盖 page 特定响应。
        RouteRule mockProvider = (pageType == RouteHandleType.MOCK) ? pageEffective
                : (ctxType == RouteHandleType.MOCK) ? ctxEffective : null;
        if (mockProvider != null) {
            effective.setMockStatus(mockProvider.getMockStatus());
            effective.setMockBody(mockProvider.getMockBody());
        }
        if (pageType == RouteHandleType.MOCK || ctxType == RouteHandleType.MOCK) {
            effective.setType(RouteHandleType.MOCK);
        } else {
            effective.setType(pageType);
        }
        return new CrossLayerMergeResult(effective, delayMs, true);
    }

    /**
     * ⭐ Phase 3 统一绑定模型：单 context handler 下的「按页筛选 + 跨层合并」纯函数（无 Playwright 依赖，可纯单测）。
     *
     * <p>给定一个 pattern 对应的<b>混合 scope 规则链</b>（同一条链里既有 {@code scope=PAGE} 也有 {@code scope=CONTEXT} 的规则）
     * 与请求所属 Page，产出一次性有效规则：
     * <ul>
     *   <li>仅收集 {@code scope==PAGE && pageRef == reqPage} 的规则构成 page 有效链（保住 popup/iframe 专属隔离）；</li>
     *   <li>仅收集 {@code scope==CONTEXT} 的规则构成 context 有效链（作用于同 context 所有页面）；</li>
     *   <li>两条链都非空 → 委托 {@link #mergeCrossLayer}（page 特定 &gt; context 全域、能力位 OR、MOCK 终结、DELAY 取 max）；</li>
     *   <li>仅一条链非空 → 该链 {@link #resolveChain} 即可；</li>
     *   <li>两条链都空 → 返回 null（本页无适用规则，交由后续 handler / fallback 放行）。</li>
     * </ul>
     *
     * <p>注意：{@code reqPage} 为 {@code null} 时（frame/page 不可得），page 级规则一律不命中（它们要求精确的 pageRef 身份匹配），
     * 仅 CONTEXT 规则生效——这是合理降级。
     *
     * @param chain   同 pattern 的规则链（CONTEXT_RULES_BY_CONTEXT 中 pattern 对应的 List）
     * @param reqPage 请求所属 Page（来自 {@code route.request().frame().page()}）；可为 null
     * @return 统一解析结果；无任何适用规则时返回 null
     */
    public static ResolvedUnified resolveUnified(List<RouteRule> chain, Object reqPage) {
        if (chain == null || chain.isEmpty()) return null;
        List<RouteRule> pageChain = new java.util.ArrayList<>();
        List<RouteRule> ctxChain = new java.util.ArrayList<>();
        for (RouteRule r : chain) {
            if (r == null) continue;
            if (r.getScope() == RouteRuleScope.PAGE) {
                Object pr = r.getPageRef();
                // ⭐ 身份匹配（==）：page 级规则只作用于其注册时所绑定的那个 Page，
                //    多页面（弹窗/iframe）场景下不会串到其它页；reqPage 为 null 时一律不命中。
                if (pr != null && pr == reqPage) {
                    pageChain.add(r);
                }
            } else {
                ctxChain.add(r);
            }
        }
        if (pageChain.isEmpty() && ctxChain.isEmpty()) return null;

        if (!pageChain.isEmpty() && !ctxChain.isEmpty()) {
            CrossLayerMergeResult m = mergeCrossLayer(resolveChain(pageChain), ctxChain);
            return new ResolvedUnified(m.rule, m.delayMs);
        } else if (!pageChain.isEmpty()) {
            RouteRule eff = resolveChain(pageChain);
            return new ResolvedUnified(eff, eff.getDelayMs());
        } else {
            RouteRule eff = resolveChain(ctxChain);
            return new ResolvedUnified(eff, eff.getDelayMs());
        }
    }

    /**
     * ⭐ Phase 3：统一解析结果载体（纯数据，无可变状态）。
     */
    public static final class ResolvedUnified {
        public final RouteRule rule;
        public final long delayMs;

        public ResolvedUnified(RouteRule rule, long delayMs) {
            this.rule = rule;
            this.delayMs = delayMs;
        }
    }

    /**
     * ⭐ Phase 3：从 Route 反查请求所属 Page（统一绑定模型下 dispatch 按页筛选的关键）。
     * 任一环节不可达时返回 null（交由 resolveUnified 的降级语义处理）。
     */
    private static Page currentPageOf(Route route) {
        try {
            if (route != null && route.request() != null
                    && route.request().frame() != null
                    && route.request().frame().page() != null) {
                return route.request().frame().page();
            }
        } catch (Exception ignored) {
            // Page/Context 已关闭时无法反查，返回 null 走兜底
        }
        return null;
    }

    /**
     * 路由分发 — 根据规则类型调用对应 Handler。
     *
     * <p>防重门控：同一 Route 对象被多个重叠 pattern 匹配时，
     * 仅第一个到达的 handler 执行，后续 handler 静默跳过（避免 "Route is already handled" 异常）。
     * <p>每次 handler 执行完成后立即 remove，避免阻塞同一 pattern 的后续请求。
     */
    private static void dispatchRoute(Route route, List<RouteRule> chain) {
        // ⭐ B3 链式模型：链头 = 源规则（请求条件匹配 / MonitorSession / times 归属）；
        //    同 pattern 多条规则在分发期合并为一次性有效规则（copyForMerge，后注册覆盖），
        //    用后即弃——消除注册期 mergeFrom 的共享可变状态（详见 resolveSameLayerChain）。
        RouteRule rule = (chain == null || chain.isEmpty()) ? null : chain.get(0);
        if (rule == null) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] dispatchRoute SKIP: empty rule chain");
            // ⭐ 修复：改用 fallback 而非 resume。
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
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] Page/Context already closed, resume & skip dispatch for pattern='{}'",
                    rule.getUrlPattern());
            RouteUtil.resumeIfOpen(route);
            return;
        }

        // ⭐ #1 性能优化：缓存 route.request() JNI 调用，避免多次跨语言桥接
        Request req = route.request();
        // ⭐ 异步路径标记：schedule() 分支（MOCK/MODIFY/MONITOR 延迟、DELAY）的 route 生命周期
        //    由 executeHandlerScheduled/action 的 finally 负责释放防重门控。外层 finally 仅对
        //    同步路径释放，避免提前清除 pending route 的门控导致重叠 pattern 二次 dispatch 失防。
        //    （声明在 try 外，使 catch/finally 可访问；数组形式以支持 lambda 内修改）
        final boolean[] asyncHandled = {false};
        // ⭐ 最外层兜底 try：覆盖 dispatchRoute 早期逻辑（规则查询、能力位合并、MOCK 短路、
        //    MODIFY fetch 前的 route.request()/incrementActiveRequests 等）。任何未预期异常都强制
        //    resume 兜底，确保 route 绝不永久挂起（详见方法末尾 catch/finally）。
        try {
        String reqUrl = req.url();
        String reqMethod = req.method();
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] ═══ dispatchRoute START: method={}, url='{}', type={}, pattern='{}' ═══",
                reqMethod, reqUrl, rule.getType(), rule.getUrlPattern());

        // ⭐ Phase 3 统一绑定（单 context handler）：不存在第二个 handler，原跨层级去重（CROSS_LAYER_HANDLED_URLS）已废弃移除。

        // ⭐ 修复 P0-1：防重门控按 context 分桶
        BrowserContext dispatchCtx = contextOf(route);
        Set<Route> bucket = dispatchCtx != null ? DISPATCHED_ROUTES.computeIfAbsent(dispatchCtx, k -> ConcurrentHashMap.newKeySet()) : null;

        // ═══ 防御性清理：单 context 桶超过上限时清空（防止异常情况下无限增长）═══
        if (bucket != null && bucket.size() >= MAX_DISPATCHED_ROUTES_PER_CONTEXT) {
            LOGGER.warn("[RouteEngine] DISPATCHED_ROUTES bucket reached {} entries for context, clearing to prevent memory leak",
                    bucket.size());
            bucket.clear();
        }

        // ═══ 防重门控：同一请求只处理一次（按 context 隔离，context 关闭时由 clearContext 精确清理）═══
        if (bucket != null) {
            if (!bucket.add(route)) {
                LOGGER.warn("[RouteEngine] Route already handled by another pattern, skipping '{}' for URL '{}'",
                        rule.getUrlPattern(), reqUrl);
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
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
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] ═══ dispatchRoute MISMATCH (condition filter): pattern='{}', url='{}' ═══",
                    rule.getUrlPattern(), reqUrl);
            return;
        }

        // ═══ 检查 MonitorSession 是否已停止（auto-stop / 超时），停止则跳过 handler ═══
        // 不在此处调用 unroute()，避免 Playwright 线程竞态导致 "Object doesn't exist" 或 "Cannot find command to respond" 错误。
        // route handler 保持注册，但已停止的 session 仅放行请求，不处理。
        MonitorSession session = sessionForRoute(route, rule);
        if (session != null && session.stopped.get()) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
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
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] ═══ dispatchRoute SKIP (times exhausted): pattern='{}', url='{}' ═══",
                    rule.getUrlPattern(), reqUrl);
            RouteUtil.safeResume(route);
            unmarkDispatched(route);
            return;
        }

        // ═══ 同 pattern 规则链解析（统一绑定模型）═══
        // ⭐ Phase 3：单一 context handler 路径（合并核心见 resolveUnified）。
        //    同 pattern 链混合 PAGE/CONTEXT scope 规则：按 request.frame().page() 精确筛选适用 PAGE 规则
        //    + 全部 CONTEXT 规则，委托 resolveUnified → mergeCrossLayer 一次性合并执行
        //    （page 特定 > context 全域、能力位 OR、MOCK 终结、DELAY 取 max）；单请求仅执行一次。
        long delayMs = rule.getDelayMs();

        // ⭐ Phase 3 统一绑定：单 context handler 路径（合并核心见 resolveUnified）。
        //    同 pattern 链混合 PAGE/CONTEXT scope 规则，按请求所属 Page 精确筛选后一次性合并执行：
        //    page 特定 > context 全域、能力位 OR、MOCK 终结、DELAY 取 max（全部由 resolveUnified → mergeCrossLayer 承载）。
        Page reqPage = currentPageOf(route);
        ResolvedUnified resolved = resolveUnified(chain, reqPage);
        if (resolved == null) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] ═══ dispatchRoute SKIP (unified: no applicable rule for this page): pattern='{}' ═══",
                    rule.getUrlPattern());
            RouteUtil.fallbackIfOpen(route);
            return;
        }
        rule = resolved.rule;
        delayMs = resolved.delayMs;
        // 统一合并路径下无独立跨层延迟合并标记需求（保留供后续 DELAY 调度判定兼容），恒为 false
        boolean crossLayerDelayMerged = false;

        // ═══ 能力位管线（取代 type 单选）：MOCK 终结 → MODIFY → MONITOR → DELAY ═══
        // 能力与 type 解耦：MOCK 为唯一终结者（短路）；其余能力位（MODIFY/MONITOR/DELAY）
        // 按字段叠加，可同时存在。监控是<b>不可覆盖的基线</b>：无论是否叠加 modify/delay，
        // 最终都对真实响应断言，失败即报错。
        // ⭐ Phase 5：管线选择抽成 InterceptorChain（按 order 升序选首个 canHandle 的拦截器），
        //    实际动作（同步/异步调度、scheduleDelay、resume）仍在此统一执行，保持 asyncHandled /
        //    unmarkDispatched 控制流与既有管线严格等价。
        final long effectiveDelay = delayMs;
        // ⭐ 取 final 副本供 lambda 引用（rule 在跨层合并可能被重新赋值，非 effectively final）
        final RouteRule finalRule = rule;

        RouteHandleType capability = selectCapability(finalRule);
        if (capability == null) {
            // 5) 兜底：无能力位 → 直接放行
            LOGGER.debug("[RouteEngine] No capability on rule, resume: pattern='{}'", finalRule.getUrlPattern());
            try {
                route.resume();
            } catch (Exception ignored) {
                // 已失效/已关闭：忽略
            }
            return;
        }

        // ═══ DELAY 维度记录（与其它能力并列，非互斥）═══
        // ⭐ 四种能力在 ApiCaptureContext 中是<b>四个并列维度</b>：
        //   一次请求可同时被「延迟 + 修改 + 监控」，各自落一条 type 不同的快照，
        //   由 getAllByType(DELAY) / (MODIFY) / (MONITOR) / (MOCK) 分别检索。
        //   因此只要合并后的有效延迟 > 0，无论最终由哪个拦截器执行动作，都落一条 DELAY 记录。
        if (effectiveDelay > 0) {
            storeDelayCall(route, finalRule);
        }

        if (capability != RouteHandleType.DELAY) {
            // 1/2/3) MOCK / MODIFY / MONITOR：执行对应 Handler。
            // ⭐ B 方案：MODIFY / MONITOR（含 +DELAY）一律在事件线程同步执行，延迟由 Handler 内部
            //   page.waitForResponse 的 action 把 resume 调度到延迟线程实现；彻底弃用 route.fetch。
            //   仅 MOCK+DELAY 保留调度线程 fulfill（fulfill 线程安全）。
            if (effectiveDelay > 0 && capability == RouteHandleType.MOCK) {
                asyncHandled[0] = true;
                final RouteHandler h = resolveCapabilityHandler(capability);
                delayScheduler(route).schedule(
                        () -> executeHandlerScheduled(route, finalRule, h),
                        effectiveDelay, TimeUnit.MILLISECONDS);
            } else {
                executeHandler(route, finalRule, resolveCapabilityHandler(capability), effectiveDelay);
            }
            return;
        }

        // 4) 纯 DELAY（无 modify 无 monitor）：延迟后放行（不走 Handler）
        long scheduledMs = crossLayerDelayMerged ? delayMs : 0;
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] ═══ dispatchRoute DELAY: scheduling for pattern='{}', url='{}', crossLayerMerged={}, delay={}ms ═══",
                finalRule.getUrlPattern(), reqUrl, crossLayerDelayMerged, delayMs);
        asyncHandled[0] = true;
        scheduleDelay(route, finalRule, scheduledMs);
        return;
        } catch (Exception e) {
            // ⭐ 最外层兜底：dispatchRoute 早期逻辑（规则查询、能力位合并、MOCK 短路、MODIFY fetch 前的
            //    route.request()/incrementActiveRequests 等）若抛未预期异常，必须 force-resume 兜底，
            //    否则该 route 既未 fulfill 也未 resume → 请求永久挂起（浏览器转圈、测试 block）。
            //    对已 resume/fulfill 的分支，重复 resume 被 Playwright 忽略（幂等），无副作用。
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] dispatchRoute unexpected error for pattern='{}', force-resume to avoid hang: {}",
                    rule.getUrlPattern(), e.getMessage());
            try {
                route.resume();
            } catch (Exception ignored) {
                // route 已失效/页面已关闭，放行失败也无所谓（不挂起即可）
            }
        } finally {
            // ⭐ 防重门控释放：仅对同步路径在此释放。异步路径（MOCK/MODIFY/MONITOR 延迟、DELAY）
            //    的 route 仍在 pending，其生命周期由 executeHandlerScheduled/action 的 finally 负责释放；
            //    若此处提前清除，会导致重叠 pattern 二次 dispatch 失去防重保护。同步路径（含兜底 resume、
            //    早期异常 force-resume）在此统一释放，避免同 pattern 后续请求被永久吞掉。
            if (!asyncHandled[0]) {
                unmarkDispatched(route);
            }
        }
    }

    /**
     * ⭐ 能力位选择（取代 InterceptorChain 责任链抽象）：按 {@link RouteHandleType#getPriority()}
     * 顺序选出首个命中的能力位。等价语义：MOCK 终结短路 → MODIFY → DELAY → MONITOR。
     *
     * @param rule 已跨层/同层合并后的 finalRule
     * @return 命中的能力类型；无任何能力位命中时返回 null（由调用方 resume 放行）
     */
    public static RouteHandleType selectCapability(RouteRule rule) {
        if (rule.getType() == RouteHandleType.MOCK) {
            return RouteHandleType.MOCK;
        }
        if (hasModifyCapability(rule)) {
            return RouteHandleType.MODIFY;
        }
        if (rule.getType() == RouteHandleType.DELAY || rule.getDelayMs() > 0) {
            return RouteHandleType.DELAY;
        }
        if (rule.isMonitorEnabled()) {
            return RouteHandleType.MONITOR;
        }
        return null;
    }

    /** MODIFY 能力位判定：存在任意请求头/体改写项或改方法。 */
    private static boolean hasModifyCapability(RouteRule rule) {
        return (rule.getRequestHeadersToSet() != null && !rule.getRequestHeadersToSet().isEmpty())
                || (rule.getRequestHeadersToRemove() != null && !rule.getRequestHeadersToRemove().isEmpty())
                || (rule.getRequestBodyFieldsToModify() != null && !rule.getRequestBodyFieldsToModify().isEmpty())
                || (rule.getRequestBodyFieldsToAdd() != null && !rule.getRequestBodyFieldsToAdd().isEmpty())
                || (rule.getRequestBodyFieldsToRemove() != null && !rule.getRequestBodyFieldsToRemove().isEmpty())
                || rule.getModifyMethod() != null;
    }

    /** 把选中的能力类型解析为实际执行的 Handler（DELAY 不走 Handler，返回 null）。 */
    private static RouteHandler resolveCapabilityHandler(RouteHandleType capability) {
        switch (capability) {
            case MOCK:    return MockHandler::handle;
            case MODIFY:  return ModifyHandler::handle;
            case MONITOR: return MonitorHandler::handle;
            default:      return null;
        }
    }

    /** times 一次性拦截（对齐 Playwright setTimes）：成功处理后递减源规则计数。 */
    private static void decrementTimes(RouteRule rule) {
        RouteRule sourceRule = rule.getMergeSource();
        if (sourceRule.getTimes() > 0) {
            sourceRule.decrementTimes();
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] times decremented: pattern='{}', exhausted={}",
                    sourceRule.getUrlPattern(), sourceRule.isTimesExhausted());
        }
    }

    /**
     * DELAY 延迟调度 — 使用 {@link ScheduledExecutorService#schedule} 在延迟后放行请求。
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
    private static void scheduleDelay(Route route, RouteRule rule, long preComputedDelayMs) {
        // ⭐ 跨层级合并后的延迟值（>0）= 已取 max，直接信任使用；
        //    否则从 rule 重新计算（支持随机延迟范围 randomDelay）
        long delayMs = preComputedDelayMs > 0
                ? DelayHandler.clampDelay(preComputedDelayMs)
                : DelayHandler.clampDelay(DelayHandler.resolveDelay(rule));

        // ⭐ #1 性能优化：缓存 route.request()
        String pattern = rule.getUrlPattern();

        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] scheduleDelay: pattern='{}', url='{}', delay={}ms, minDelay={}ms, maxDelay={}ms, mergedInput={}ms",
                pattern, route.request().url(), delayMs, rule.getDelayMinMs(), rule.getDelayMaxMs(), preComputedDelayMs);

        // ⭐ DELAY 拦截期间递增 activeRequests，使 awaitCompletion 能等待延迟请求放行 +
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
        MonitorSession session = sessionForRoute(route, rule);
        if (session != null && session.stopped.get()) {
            RouteUtil.safeResume(route);
            return;
        }

        if (rule.isMonitorEnabled()) {
            // ⭐ B 方案：事件线程同步观测（page.waitForResponse），resume 经其 action 回调调度到延迟线程
            //    （见 MonitorHandler.handle），彻底弃用 route.fetch。
            //    onMonitorMatch 由 handle → assertAndRecord 内部处理；times / dispatched 门控在此清理。
            try {
                MonitorHandler.handle(route, rule, delayMs);
            } catch (Exception e) {
                LOGGER.error("[RouteEngine] DELAY+MONITOR observe failed for '{}': {}", pattern, e.getMessage(), e);
                RouteUtil.safeResume(route);
            } finally {
                decrementTimes(rule);
                unmarkDispatched(route);
                captureContext.decrementActiveRequests();
            }
        } else {
            // 纯 DELAY（无 modify 无 monitor）：延迟后放行（调度线程，非阻塞），保持原语义。
            if (delayMs > 0) {
                final long d = delayMs;
                delayScheduler(route).schedule(() -> {
                    try {
                        if (!RouteUtil.isPageClosed(route)) RouteUtil.safeResume(route);
                    } finally {
                        onMonitorMatch(rule);
                        decrementTimes(rule);
                        unmarkDispatched(route);
                        captureContext.decrementActiveRequests();
                    }
                }, d, TimeUnit.MILLISECONDS);
                return; // 清理交由调度线程
            }
            RouteUtil.safeResume(route);
            onMonitorMatch(rule);
            decrementTimes(rule);
            unmarkDispatched(route);
            captureContext.decrementActiveRequests();
        }
    }

    /**
     * 将纯 DELAY 调用存入 ApiCaptureContext，使其像 MONITOR/MOCK 一样可被查询。
     *
     * <p>DELAY 仅延迟放行请求（不修改响应），因此存储的信息以请求元数据为主，
     * 不包含响应体（resume 异步，响应尚未返回）。满足 assertNotNull 等基础断言。
     *
     * <p>⭐ 与其它能力的记录是<b>并列维度</b>：叠加 MONITOR 时，真实响应由
     * {@code MonitorHandler.handle(route, rule, delayMs)} 另落一条 type=MONITOR 的完整快照，
     * 本条 type=DELAY 依然保留 —— {@code getAllByType(DELAY)} 才能反映
     * 「哪些请求被延迟过」，不被 MONITOR 覆盖。
     */
    private static void storeDelayCall(Route route, RouteRule rule) {
        try {
            com.microsoft.playwright.Request req = route.request();
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
                // ⭐ 存入 DELAY 专用索引，不进主快照存储：
                //   本记录无响应体，若混入主存储会让 getLastApiCall / waitForApi 命中空快照。
                ctx.storeDelayMarker(call);
            }
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] Stored DELAY marker to ApiCaptureContext: pattern='{}', method={}",
                    rule.getUrlPattern(), req.method());
        } catch (Exception e) {
            LOGGER.debug("[RouteEngine] Failed to store DELAY call to ApiCaptureContext: {}", e.getMessage());
        }
    }

    /**
     * 注册器（内部函数式接口）。
     */
    @FunctionalInterface
    private interface RouteRegistrar {
        void register(String pattern, RouteRule rule);
    }

    /**
     * 在调度线程池中执行 Handler（用于 DELAY 类型和延迟场景）。
     *
     * <p>与 {@link #executeHandler} 相比增加了会话状态检查，
     * 在延迟等待期间会话可能已被 auto-stop / 超时导致停止。
     */
    private static void executeHandlerScheduled(Route route, RouteRule rule, RouteHandler handler) {
        try {
            // 延迟期间页面/上下文可能已被关闭，抵达时直接放行，避免对已销毁页面执行 handler 导致挂起/报错
            if (RouteUtil.isPageClosed(route)) {
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteEngine] Page already closed during scheduled delay, skip handler for '{}'",
                        rule.getUrlPattern());
                RouteUtil.resumeIfOpen(route);
                return;
            }
            // 检查会话是否已被停止（auto-stop / 超时）
            MonitorSession session = sessionForRoute(route, rule);
            if (session != null && session.stopped.get()) {
                LOGGER.debug("[RouteEngine] Session stopped during delay, skipping handler for '{}'",
                        rule.getUrlPattern());
                RouteUtil.safeResume(route);
                return;
            }
            executeHandler(route, rule, handler, 0);
        } catch (Exception e) {
            LOGGER.error("[RouteEngine] Scheduled handler failed for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage(), e);
            RouteUtil.safeResume(route);
        }
    }

    /**
     * 执行 Handler，统一异常处理和日志。
     */
    private static void executeHandler(Route route, RouteRule rule, RouteHandler handler, long delayMs) {
        // ⭐ #1 性能优化：缓存 route.request()，避免 executeHandler 内重复 JNI 调用
        Request req = route.request();
        try {
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[RouteEngine] executeHandler START: type={}, pattern='{}', url='{}'",
                    rule.getType(), rule.getUrlPattern(), SensitiveDataSanitizer.sanitizeUrl(req.url()));
            handler.handle(route, rule, delayMs);

            LOGGER.info("[RouteEngine] Route matched: type={}, pattern='{}', method={}, url='{}'",
                    rule.getType(), rule.getUrlPattern(),
                    req.method(), SensitiveDataSanitizer.sanitizeUrl(req.url()));

            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] executeHandler DONE: type={}, pattern='{}'",
                    rule.getType(), rule.getUrlPattern());

            // MOCK/MODIFY/DELAY 处理成功后触发匹配计数（支持一次性拦截 / auto-stop）
            // ⭐ 计数归属必须按「Handler 内部是否已计数」判定，不能按 type：
            //    · MonitorHandler.assertAndRecord 内部会调 onMonitorMatch；
            //    · ModifyHandler 仅在 rule.isMonitorEnabled() 时才走 assertAndRecord（叠加监控），
            //      因此纯 MODIFY（未叠加监控）必须在此计数，否则配了 timeout 的 modify 规则
            //      会话永不被满足 → 误报监控超时；
            //    · MOCK 即使叠加了监控也走 MockHandler（不发真实请求、不做响应断言），
            //      Handler 内部不计数，故 MOCK 一律在此计数。
            boolean countedInsideHandler = rule.getType() != RouteHandleType.MOCK
                    && rule.isMonitorEnabled();
            if (!countedInsideHandler) {
                onMonitorMatch(rule);
            }

            // ═══ times 一次性拦截（对齐 Playwright setTimes）：成功处理后递减 ═══
            // ⭐ B3：rule 可能是分发期合并的临时拷贝（copyForMerge 不复制 times），
            //    times 必须作用于源规则（getMergeSource()：链头或独立规则自身）。
            RouteRule sourceRule = rule.getMergeSource();
            if (sourceRule.getTimes() > 0) {
                sourceRule.decrementTimes();
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteEngine] times decremented: type={}, pattern='{}', exhausted={}",
                        sourceRule.getType(), sourceRule.getUrlPattern(), sourceRule.isTimesExhausted());
            }

            // ═══ 采集管道钩子已移除 ═══
            // MOCK 由 MockHandler、MODIFY 由 ModifyHandler 在拿到响应后同步 storeApiCall；
            // MONITOR/DELAY 走各自路径。原 feedCaptureEvent 的每个分支都会与 Handler 的
            // 同步存储重复落库（MODIFY 分支还会因缺少 FETCH_RESPONSE 生产者而制造永不闭合的
            // 孤儿 slot，虚占 captureInFlight 拖慢 awaitCompletion），故整体删除。
        } catch (RouteException.ApiAssertionException e) {
            // ⭐⭐⭐ MonitorHandler 同步断言失败 — 已由 signalFailFast() 置失败标志（非中断线程），
            LOGGER.error("[RouteEngine] API assertion FAILED for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage());
            // 路由已被 MonitorHandler.resume() 放行，无需额外处理
            // ApiAssertionException 不在此处继续传播（Playwright 内部捕获），
            // 主测试线程不会被 interrupt，仅标志置位，当前阻塞的 Playwright 操作照常完成
        } catch (Exception e) {
            LOGGER.error("[RouteEngine] Handler type={} threw exception for pattern '{}': {}",
                    rule.getType(), rule.getUrlPattern(), e.getMessage(), e);
            try {
                route.resume();
            } catch (Exception resumeEx) {
                // resume 失败（route 已失效/页面已关闭）：兜底 abort，
                // 确保请求绝对不会永久悬停导致"浏览器打开但无动作"的挂起。
                try {
                    route.abort();
                } catch (Exception abortEx) {
                    LOGGER.error("[RouteEngine] Failed to resume AND abort route after handler error: {}",
                            abortEx.getMessage());
                }
            }
        } finally {
            // ═══ 防重门控释放：handler 完成后立即 remove，允许同一 pattern 后续请求正常处理 ═══
            unmarkDispatched(route);
        }
    }

    // ─── Monitor 自动停止 ────────────────────────────────────────

    /**
     * 为路由规则创建自动停止会话（如果需要的话）。
     *
     * <p>适用范围：MONITOR / MOCK / MODIFY 三种类型。
     * <p>条件：(timeoutMs > 0 或 autoStopOnMatch == true)
     *
     * @param context          Page 或 BrowserContext 实例
     * @param rule             路由规则
     * @param normalizedPattern 注册时使用的归一化 pattern（用于后续 unroute）
     */
    private static void startMonitorSession(Object context, RouteRule rule, String normalizedPattern) {
        if (rule.getTimeoutMs() <= 0 && !rule.isAutoStopOnMatch()) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] No MonitorSession needed for pattern='{}' (no timeout, no autoStop)",
                    normalizedPattern);
            return;  // 无限监控/拦截，无需会话
        }

        MonitorSession session = new MonitorSession(context, normalizedPattern, rule);
        MonitorSessionKey key = new MonitorSessionKey(context, normalizedPattern);
        // scope+pattern 相同且会话活跃时复用；已停止会话原子替换，避免重注册永久复用 stopped session。
        AtomicBoolean installed = new AtomicBoolean();
        SESSIONS.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.stopped.get()) return existing;
            installed.set(true);
            return session;
        });
        if (!installed.get()) {
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[RouteEngine] MonitorSession already exists for pattern='{}', reusing",
                    normalizedPattern);
            return;
        }

        if (rule.getTimeoutMs() > 0) {
            session.scheduleTimeout();
        }

        LOGGER.debug("[RouteEngine] MonitorSession started: pattern='{}', timeout={}ms, minMatches={}, autoStop={}",
                normalizedPattern, rule.getTimeoutMs(), rule.getMinMatches(), rule.isAutoStopOnMatch());
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] MonitorSession created: id={}, context={}, total sessions={}",
                System.identityHashCode(session), context.getClass().getSimpleName(), SESSIONS.size());
    }

    /**
     * ⭐ 合并后刷新 MonitorSession：链上任一规则叠加监控能力位后，
     * 若当前无活跃 session（例如「先 modify 后追加 monitor」的逆向注册顺序），则启动一个。
     * 若 session 已存在（常见「先 monitor 后追加 modify」顺序），则无需重建——分发期
     * mergeFrom 读取链上各规则，能力自动生效。
     *
     * @param ctx          context / page 对象
     * @param pattern      归一化 pattern
     * @param sessionOwner 会话绑定规则（链头；分发期 mergeSource 指向它，sessionForRule 可命中）
     * @param needsSession 链上是否携带 MONITOR 能力（由调用方按整链判定）
     */
    private static void refreshMonitorSession(Object ctx, String pattern, RouteRule sessionOwner, boolean needsSession) {
        // ⭐ B3：needSession 由调用方按「链上任一规则携带 MONITOR 能力」判定；
        //    session 统一绑定链头（分发期 mergeSource 指向链头，sessionForRule 可命中）。
        if (!needsSession) return;
        MonitorSession session = SESSIONS.get(new MonitorSessionKey(ctx, pattern));
        if (session == null || session.stopped.get()) {
            startMonitorSession(ctx, sessionOwner, pattern);
        }
    }

    /**
     * MonitorHandler 每次匹配完成时回调。
     * 递增计数并检查 auto-stop / minMatches 条件。
     *
     * @param rule 路由规则
     */
    public static void onMonitorMatch(RouteRule rule) {
        MonitorSession session = sessionForRule(rule);
        if (session == null || session.stopped.get()) {
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[RouteEngine] onMonitorMatch SKIP: session={} for pattern='{}'",
                    session == null ? "null" : "stopped", rule.getUrlPattern());
            return;
        }

        int currentCount = session.matchCount.incrementAndGet();
        LOGGER.debug("[RouteEngine] Monitor match #{}/{} for pattern '{}'",
                currentCount, rule.getMinMatches(), rule.getUrlPattern());

        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] onMonitorMatch: count={}/{}, autoStop={}, pattern='{}'",
                currentCount, rule.getMinMatches(), rule.isAutoStopOnMatch(), rule.getUrlPattern());

        if (rule.isAutoStopOnMatch() && currentCount >= rule.getMinMatches()) {
            LOGGER.info("[RouteEngine] Auto-stopping monitor (matches={}) for pattern '{}'",
                    currentCount, rule.getUrlPattern());
            stopMonitorSession(session, currentCount);
        }
    }

    /**
     * 通过规则对象身份查找会话，避免 RouteRule 的可变 equals/hashCode 参与运行时定位。
     * 一个规则实例在同一作用域只会有一个会话；跨作用域复用时由 route 版本优先定位。
     */
    private static MonitorSession sessionForRule(RouteRule rule) {
        if (rule == null) return null;
        // ⭐ B3：分发期合并拷贝经 getMergeSource() 解引用到链头（session.rule 绑定链头）
        RouteRule source = rule.getMergeSource();
        for (MonitorSession session : SESSIONS.values()) {
            if (session.rule == source) return session;
        }
        return null;
    }

    /** 按 Route 所属 Page/Context 优先定位会话，防止跨作用域复用规则时误命中。 */
    private static MonitorSession sessionForRoute(Route route, RouteRule rule) {
        if (route == null || rule == null) return sessionForRule(rule);
        // ⭐ B3：分发期合并拷贝解引用到源规则（链头）
        RouteRule source = rule.getMergeSource();
        Page page = null;
        try {
            page = route.request().frame().page();
        } catch (Exception ignored) {
            return sessionForRule(source);
        }
        MonitorSession contextSession = null;
        for (MonitorSession session : SESSIONS.values()) {
            if (session.rule != source) continue;
            if (session.context == page) return session;
            try {
                if (session.context == page.context()) contextSession = session;
            } catch (Exception ignored) {
                // 页面关闭竞态下继续回退至规则身份查询。
            }
        }
        return contextSession != null ? contextSession : sessionForRule(source);
    }

    private static void stopMonitorSession(MonitorSession session, int totalMatches) {
        session.stop();
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] MonitorSession stopped: pattern='{}', totalMatches={}",
                session.pattern, totalMatches);
    }

    /**
     * 清理指定上下文的全部 MonitorSession（RouteRegistry.clearContext 时同步调用）。
     */
    public static void clearMonitorSessions(Object context) {
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] clearMonitorSessions for context: {} (total sessions before: {})",
                context.getClass().getSimpleName(), SESSIONS.size());
        SESSIONS.entrySet().removeIf(entry -> {
            if (entry.getValue().context == context) {
                entry.getValue().stop();
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[RouteEngine] Session removed: pattern='{}'", entry.getValue().pattern);
                return true;
            }
            return false;
        });
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] clearMonitorSessions done, remaining sessions: {}", SESSIONS.size());
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
    static void unrouteAllForContext(Object context, Set<String> patterns) {
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] unrouteAllForContext: unrouting {} pattern(s) from {}",
                patterns.size(), context.getClass().getSimpleName());
        for (String pattern : patterns) {
            try {
                if (context instanceof Page) {
                    ((Page) context).unroute(pattern);
                } else if (context instanceof BrowserContext) {
                    ((BrowserContext) context).unroute(pattern);
                }
                LOGGER.debug("[RouteEngine] Unrouted pattern '{}' from context: {}",
                        pattern, context.getClass().getSimpleName());
            } catch (Exception e) {
                LOGGER.warn("[RouteEngine] Failed to unroute pattern '{}' from context '{}': {}",
                        pattern, context.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /**
     * 全局清理所有 MonitorSession。
     */
    public static void clearAllMonitorSessions() {
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] clearAllMonitorSessions: stopping {} session(s), clearing {} dispatched routes, {} context rules",
                SESSIONS.size(), DISPATCHED_ROUTES.size(), contextRuleCount());
        for (MonitorSession session : SESSIONS.values()) {
            session.stop();
        }
        SESSIONS.clear();
        DISPATCHED_ROUTES.clear();
        CONTEXT_RULE_PATHS.clear();   // ⭐ 补清 context path 索引，防 case 间残留
        LITERAL_PATH_CACHE.clear();   // ⭐ 补清 literalPath 缓存，防 case 间残留（urlPattern 极少，重建代价可忽略）
    }

    /**
     * ⭐ 修复 P0-1：按 Context 精确清理防重门控（Context 关闭时调用，不影响其它 Context）。
     * 仅移除该 context 桶，避免并行测试下全局清空误杀其它 Context。
     */
    public static void clearDispatchedRoutes(BrowserContext context) {
        if (context == null) return;
        DISPATCHED_ROUTES.remove(context);
        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[RouteEngine] clearDispatchedRoutes(context): cleared bucket for context {}",
                System.identityHashCode(context));
    }

    /**
     * 清空 Route 防重门控集合 + 跨层去重集合（全量，shutdown / clearAll / resetAll 时调用，释放已处理的 Route 引用）。
     */
    public static void clearDispatchedRoutes() {
        int dispatchedSize = DISPATCHED_ROUTES.size();
        DISPATCHED_ROUTES.clear();
        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[RouteEngine] clearDispatchedRoutes: cleared {} dispatched entries",
                dispatchedSize);
    }

    /**
     * ⭐ 移除指定页面的规则缓存（测试结束时调用，防止跨测试用例污染）。
     *
     * @param page 要移除规则缓存的页面
     */
    public static void removePageRules(Page page) {
        if (page == null) return;
        // ⭐ Phase 3 统一绑定：page 规则存于 context 存储，按 pageRef 精确移除，保留同 context 的其它页 / 全局规则。
        Map<String, List<RouteRule>> scoped = CONTEXT_RULES_BY_CONTEXT.get(page.context());
        if (scoped != null) {
            for (List<RouteRule> chain : scoped.values()) {
                if (chain == null) continue;
                chain.removeIf(r -> r != null && r.getScope() == RouteRuleScope.PAGE && r.getPageRef() == page);
            }
            // 自然清理空链（handler 命中 empty chain 分支自动 fallback 放行，与 detachChains 不 unroute 策略一致）
            scoped.values().removeIf(chain -> chain == null || chain.isEmpty());
        }
    }

    // ─── Context 级规则跨层级合并 ────────────────────────────

    /**
     * ⭐ 在 CONTEXT_RULES 中查找与给定 URL 匹配的 context 级规则。
     *
     * <p>使用 glob 匹配（与 Playwright 注册时一样），normalized pattern 如
     * {@code ** /api/users/**} 被转换为子串匹配：提取 {@code /api/users}，
     * 检查 URL 是否包含该路径。
     *
     * @param url 请求 URL
     * @return 匹配的 context 规则，未找到则返回 null
     */
    /**
     * 提取 urlPattern 中「去除通配符后的字面前缀」。
     * <p>例：{@code /api/users} → {@code /api/users}；{@code /api/users/*} → {@code /api/users/}；
     *     {@code /api/**} → {@code /api/}。返回 null 表示无有效字面前缀。
     */
    // ⭐ 性能优化：urlPattern → literalPath 静态缓存。
    //   literalPathOf 在跨层合并 / resolveUnified 等高频路径中被反复调用，
    //   重复做 startsWith/endsWith/indexOf/substring 字符串计算。规则数少且相对稳定，
    //   用有上限的 ConcurrentHashMap 缓存结果，消除重复分配。
    private static final java.util.concurrent.ConcurrentHashMap<String, String> LITERAL_PATH_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int LITERAL_PATH_CACHE_MAX = 256;

    private static String literalPathOf(String urlPattern) {
        if (urlPattern == null || urlPattern.isEmpty()) return null;
        // 命中缓存直接返回（缓存 value 为 null 表示无有效字面前缀，用空串占位）
        String cached = LITERAL_PATH_CACHE.get(urlPattern);
        if (cached != null) return cached.isEmpty() ? null : cached;

        String p = urlPattern;
        // 去掉首尾 **（如果存在）
        while (p.startsWith("**")) p = p.substring(2);
        while (p.endsWith("**")) p = p.substring(0, p.length() - 2);
        // 截断到第一个 * 之前（保留前面的字面路径）
        int star = p.indexOf('*');
        if (star >= 0) p = p.substring(0, star);

        String result = p.isEmpty() ? "" : p;
        // ⭐ 修复 P4：原实现在 size() 达到上限后【既不淘汰也不新增】，缓存从此彻底停止工作：
        //    新 urlPattern 永远进不了缓存，之后每次调用都重做字符串运算，而 size() 恒等于 MAX。
        //    命中率不是"缓慢下降"，而是"归零"——只在规则数增长超过上限时才暴露。
        //    改为复用 RouteUtil 的统一淘汰策略：满则先批量淘汰约 1/4 再写入，缓存持续有效。
        if (LITERAL_PATH_CACHE.size() >= LITERAL_PATH_CACHE_MAX) {
            RouteUtil.evictOldestQuarter(LITERAL_PATH_CACHE);
        }
        LITERAL_PATH_CACHE.putIfAbsent(urlPattern, result);
        return p.isEmpty() ? null : p;
    }

    /** 统计所有 Context 级规则总数（用于日志）。 */
    private static int contextRuleCount() {
        int count = 0;
        for (Map<String, List<RouteRule>> scoped : CONTEXT_RULES_BY_CONTEXT.values()) {
            count += scoped.size();
        }
        return count;
    }

    /**
     * 从 route 反查其所属 BrowserContext（修复 P0-1：防重门控按 context 分桶需要）。
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

    /** 防重门控释放：从所属 context 桶中移除 route（修复 P0-1 分桶） */
    private static void unmarkDispatched(Route route) {
        BrowserContext ctx = contextOf(route);
        if (ctx != null) {
            Set<Route> bucket = DISPATCHED_ROUTES.get(ctx);
            if (bucket != null) {
                bucket.remove(route);
                if (bucket.isEmpty()) {
                    DISPATCHED_ROUTES.remove(ctx);
                }
            }
        }
    }

    private static void indexContextRule(String pattern, String path) {
        String prefix = literalPrefix(path);
        if (prefix == null || prefix.isEmpty()) {
            CONTEXT_RULE_FALLBACK_KEYS.add(pattern);
            return;
        }
        CONTEXT_RULE_KEYS_BY_PREFIX.computeIfAbsent(prefix,
                ignored -> ConcurrentHashMap.newKeySet()).add(pattern);
    }

    private static String literalPrefix(String path) {
        if (path == null || path.isEmpty()) return null;
        int wildcard = path.indexOf('*');
        String prefix = wildcard >= 0 ? path.substring(0, wildcard) : path;
        int slash = prefix.lastIndexOf('/');
        return slash > 0 ? prefix.substring(0, slash + 1) : prefix;
    }

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
            int idx = url.indexOf("//");
            if (idx >= 0) {
                int slash = url.indexOf('/', idx + 2);
                return slash >= 0 ? url.substring(slash) : "/";
            }
            return url;
        }
    }

    /**
     * 从 Playwright glob 归一化后的 pattern 中提取路径子串。
     * <p>例：{@code ** /api/users/**} → {@code /api/users}
     */
    private static String extractPathFromNormalizedPattern(String normalized) {
        String path = normalized;
        if (path.startsWith("**")) {
            path = path.substring(2);
        }
        if (path.endsWith("**")) {
            path = path.substring(0, path.length() - 2);
        }
        // 清理尾部斜杠
        while (path.endsWith("/") && !path.equals("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * ⭐ 移除指定 pattern 集合中的所有 context 级规则。
     * <p>由 {@link RouteRegistry#clearContext(Object)} 在清理上下文时调用。
     *
     * @param patterns 要移除的 normalized pattern 集合
     */
    public static void removeContextRules(Object context, Set<String> patterns) {
        if (context == null || patterns == null || patterns.isEmpty()) return;
        Map<String, List<RouteRule>> scoped = CONTEXT_RULES_BY_CONTEXT.remove(context);
        if (scoped == null) return;
        for (String pattern : patterns) {
            List<RouteRule> ownerChain = scoped.remove(pattern);
            if (ownerChain != null) {
                CONTEXT_RULE_PATHS.remove(pattern);
                String path = extractPathFromNormalizedPattern(pattern);
                String prefix = literalPrefix(path);
                if (prefix != null) {
                    Set<String> indexed = CONTEXT_RULE_KEYS_BY_PREFIX.get(prefix);
                    if (indexed != null) {
                        indexed.remove(pattern);
                        if (indexed.isEmpty()) CONTEXT_RULE_KEYS_BY_PREFIX.remove(prefix, indexed);
                    }
                }
                CONTEXT_RULE_FALLBACK_KEYS.remove(pattern);
                // ⭐ 最后就地清空链内容：BrowserContext 上的路由闭包捕获的是这个 List 引用
                //    （见 detachChains 注释），不清空则 clearContext 后旧规则仍会继续生效。
                //    必须放在上面所有「== ownerChain」身份比较之后，避免影响判定。
                ownerChain.clear();
            }
        }
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] Removed {} context rules for context, remaining: {}",
                patterns.size(), scoped.size());
    }

    /**
     * ⭐ 清理指定上下文的全部路由状态（测试/场景结束时调用，防止内存泄漏 + 跨用例污染）。
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
        // 1. 先从注册表移除，并注销 Playwright 路由层（无 MonitorSession 的 MOCK/MODIFY 路由需要）
        Map<String, RouteHandleType> patterns = RouteRegistry.removeContextPatterns(context);
        if (patterns != null && !patterns.isEmpty()) {
            // ⭐ 同步清除 context 级规则注册表
            removeContextRules(context, patterns.keySet());
            unrouteAllForContext(context, patterns.keySet());
        }

        // 2. 清理 MonitorSession（停止定时器 + unroute，Playwright 对已注销的 pattern 幂等）
        clearMonitorSessions(context);

        // 3. 清理 Route 防重门控 + 跨层去重集合（⭐ 修复 P0-1：按 Context 精确清理，
        //    仅移除当前 context 的桶，避免并行测试下全局清空误杀其它 Context 的防重门控）
        clearDispatchedRoutes(context instanceof BrowserContext ? (BrowserContext) context : null);
    }

    /**
     * ⭐ 断开「已注册的 Playwright 路由闭包」与规则链的关联 —— 就地清空链内容。
     *
     * <p><b>为什么仅移除 Map 条目不够</b>：注册时执行的是
     * {@code page.route(pattern, route -> dispatchRoute(route, chain))}，
     * 闭包<b>直接捕获 chain 这个 List 对象引用</b>，而框架刻意不调用 {@code page.unroute()}
     * （规避 Playwright 线程竞态，见 MonitorSession.stop 注释）。
     * 因此清理时只做 {@code CONTEXT_RULES_BY_CONTEXT.remove/clear} 的话，原生路由仍然挂在 context 上、
     * 闭包持有的 chain 仍然非空 → {@code dispatchRoute} 取 {@code chain.get(0)} 继续按旧规则
     * mock/modify/断言，跨用例污染，且旧 RouteRule 无法被 GC。
     *
     * <p>就地 {@code chain.clear()} 后，闭包再被触发时 {@code dispatchRoute} 命中
     * 「empty rule chain」分支。该分支现使用 {@code RouteUtil.fallbackIfOpen}
     * （而非 {@code resumeIfOpen}）：因为 resume 会终结 Playwright 的 handler 链，
     * 使后续重新注册的同 pattern handler 永不执行；fallback 才符合"本 handler 不处理、
     * 交给下一个"的语义，且在没有下一个 handler 时自动退化为放行。
     */
    private static void detachChains(Map<String, List<RouteRule>> store) {
        if (store == null || store.isEmpty()) return;
        for (List<RouteRule> chain : store.values()) {
            if (chain == null) continue;
            try {
                chain.clear();
            } catch (UnsupportedOperationException e) {
                // ⭐ 不可变链（如 Collections.singletonList / Arrays.asList 注入的定长 List）
                //    不支持 clear()，直接抛 UnsupportedOperationException。
                //    关键在于【绝不能让异常中断整个清理流程】：一旦这里抛出，
                //    下方的 store.clear() 以及调用方（clearAllUnifiedRuleStores）后续的
                //    CONTEXT_RULES_BY_CONTEXT / CONTEXT_RULE_KEYS_BY_PREFIX 等索引清理
                //    会被整段跳过 → 跨用例规则残留污染，危害远大于"这条链没清干净"。
                //    此类链已被路由闭包捕获、无法就地清空，只能靠 store.clear() 断开引用，
                //    由 GC 回收；闭包再次触发时 chain 非空会走旧规则，属注入方的责任边界。
                LoggingConfigUtil.logWarnIfVerbose(LOGGER,
                        "[RouteEngine] detachChains: immutable rule chain cannot be cleared in place; "
                                + "relying on store.clear() to drop the reference.");
            }
        }
        store.clear();
    }

    /**
     * ⭐ Context 生命周期结束（onClose）时清理规则索引与引擎合并引用。
     */
    public static void cleanupClosedContext(BrowserContext context) {
        if (context == null) return;
        Map<String, List<RouteRule>> scoped = CONTEXT_RULES_BY_CONTEXT.get(context);
        if (scoped != null && !scoped.isEmpty()) {
            removeContextRules(context, new HashSet<>(scoped.keySet()));
            clearMonitorSessions(context);
        }
    }

    /**
     * ⭐ 全局清理统一路由规则存储 {@link #CONTEXT_RULES_BY_CONTEXT}（测试套件结束时调用）。
     *
     * <p>统一绑定模型下 page 与 context 规则共存于同一 context 存储，故全局 teardown 仅需清理此处。
     * 必须逐链 clear（见 {@link #detachChains} 注释）：否则 BrowserContext 上仍挂着的路由闭包
     * 继续持有非空 chain，clearAllRules() 后旧规则照样生效、跨用例污染。
     */
    public static void clearAllUnifiedRuleStores() {
        for (Map<String, List<RouteRule>> scoped : CONTEXT_RULES_BY_CONTEXT.values()) {
            detachChains(scoped);
        }
        CONTEXT_RULES_BY_CONTEXT.clear();
        CONTEXT_RULE_KEYS_BY_PREFIX.clear();
        CONTEXT_RULE_FALLBACK_KEYS.clear();
    }

    // ─── MonitorSession（内部类）───────────────────────────────────

    /**
     * 会话稳定键：scope 按对象身份隔离，pattern 使用注册后的不可变字符串。
     * 不能使用 RouteRule，因为其可被 mergeFrom 原地更新。
     */
    private static final class MonitorSessionKey {
        private final Object scope;
        private final String pattern;
        private final int hashCode;

        private MonitorSessionKey(Object scope, String pattern) {
            this.scope = scope;
            this.pattern = pattern;
            this.hashCode = 31 * System.identityHashCode(scope) + pattern.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            return this == other || (other instanceof MonitorSessionKey key
                    && scope == key.scope && pattern.equals(key.pattern));
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    // ─── MonitorSession（内部类）───────────────────────────────────

    /**
     * Monitor 自动停止会话 — 管理超时调度和匹配计数。
     *
     * <p>生命周期：
     * <ol>
     *   <li>{@link #startMonitorSession} 创建</li>
     *   <li>{@link #onMonitorMatch} 递增计数 → 满足条件则 {@link #stop()}</li>
     *   <li>超时 → 自动 {@link #stop()}</li>
     *   <li>测试结束 → {@link #clearMonitorSessions} / {@link #clearAllMonitorSessions}</li>
     * </ol>
     */
    private static class MonitorSession {
        final Object context;
        final String pattern;
        final RouteRule rule;
        final AtomicInteger matchCount = new AtomicInteger(0);
        final AtomicBoolean stopped = new AtomicBoolean(false);
        // ⭐ 用 AtomicReference 持有超时任务：scheduleTimeout（SCHEDULER 线程写）与 stop（dispatch 线程读）跨线程，
        //    普通字段存在可见性风险，极端下 stop() 可能读到 stale null 而漏取消。AtomicReference 提供 happens-before 保证。
        final AtomicReference<ScheduledFuture<?>> timeoutFutureRef = new AtomicReference<>();

        MonitorSession(Object context, String pattern, RouteRule rule) {
            this.context = context;
            this.pattern = pattern;
            this.rule = rule;
        }

        void scheduleTimeout() {
            long timeoutMs = rule.getTimeoutMs();
            timeoutFutureRef.set(AsyncPool.schedule(this::onTimeout, timeoutMs));
        }

        void onTimeout() {
            if (!stopped.get()) {
                LOGGER.info("[RouteEngine] Monitor timeout ({}ms) for pattern '{}', stopping",
                        rule.getTimeoutMs(), pattern);
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteEngine] MonitorSession timeout triggered: pattern='{}', elapsed={}ms, matches={}",
                        pattern, rule.getTimeoutMs(), matchCount.get());
                stop();
            }
        }

        /**
         * 停止监控：取消超时任务、标记会话为已停止。
         *
         * <p><b>关键设计</b>：不调用 {@code page.unroute()} 注销路由，
         * 因为 auto-stop / 超时触发时调用 {@code unroute()} 会产生 Playwright 线程竞态，
         * 导致 {@code "Object doesn't exist: request@..."} 或 {@code "Cannot find command to respond"} 错误。
         *
         * <p>Route handler 保持注册，后续匹配请求在 {@link #dispatchRoute} 中
         * 检测到 {@code session.stopped == true} 后直接 {@code resume} 放行，不产生额外开销。
         * 真正的 unroute 发生在 {@link #clearMonitorSessions} / {@link #unrouteAllForContext} 中。
         *
         * <p>pattern 存储的是注册时使用的归一化 pattern。
         */
        void stop() {
            if (!stopped.compareAndSet(false, true)) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[RouteEngine] MonitorSession.stop() already stopped for pattern='{}'", pattern);
                return;  // 已停止（CAS 防重复）
            }

            ScheduledFuture<?> tfLog = timeoutFutureRef.get();
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] MonitorSession.stop() START: pattern='{}', totalMatches={}, timeoutFuture={}",
                    pattern, matchCount.get(), tfLog != null && !tfLog.isDone());

            // 取消超时任务
            ScheduledFuture<?> tf = timeoutFutureRef.get();
            if (tf != null && !tf.isDone()) {
                tf.cancel(false);
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteEngine] MonitorSession.stop() timeout future cancelled for pattern='{}'", pattern);
            }

            // 不调用 SESSIONS.remove(rule)，保留已停止的 session，
            // 使得 dispatchRoute 可检测 stopped 状态并跳过后续请求。
            // 也不调用 page.unroute()，避免 Playwright 线程竞态。

            LOGGER.debug("[RouteEngine] MonitorSession stopped: pattern='{}', totalMatches={}",
                    pattern, matchCount.get());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 采集管道钩子
    // ═══════════════════════════════════════════════════════════════













}
