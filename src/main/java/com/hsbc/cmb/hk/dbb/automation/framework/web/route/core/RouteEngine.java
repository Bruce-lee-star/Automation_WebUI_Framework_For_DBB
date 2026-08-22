package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture.CaptureEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture.CaptureEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.MockHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.MonitorHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.DelayHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 路由引擎 — 统一注册入口，按类型分发到对应 Handler。
 *
 * <p>核心设计：
 * <ul>
 *   <li>使用 {@link EnumMap} 维护 Handler 映射，新增 Handler 无需修改 switch 分支</li>
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
     * ⭐ 引擎规则引用存储：context/page 对象 → (归一化 pattern → 被闭包捕获的 RouteRule 引用)。
     *
     * <p>用于同 pattern 多次注册时的<b>能力位合并</b>：取出已注册 rule 引用，就地
     * {@link RouteRule#mergeFrom(RouteRule)}，闭包自动看到更新后的内容（无需 unroute 重注册）。
     *
     * <p>合并语义（详见 {@link RouteRule#mergeFrom}）：
     * MONITOR 基线不可被关、MODIFY 字段 putAll、DELAY 取 max、MOCK 终结（仅可覆盖非 MOCK，不可被降级）。
     */
    private static final Map<Object, Map<String, RouteRule>> ENGINE_RULE_STORE = new ConcurrentHashMap<>();

    /** Handler 注册表：类型 → 处理器 */
    private static final Map<RouteHandleType, RouteHandler> HANDLERS = new EnumMap<>(RouteHandleType.class);

    /** Monitor 会话注册表：RouteRule → MonitorSession */
    private static final Map<RouteRule, MonitorSession> SESSIONS = new ConcurrentHashMap<>();

    /**
     * ⭐ Context 级路由规则注册表：normalizedPattern → RouteRule。
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
     */
    private static final Map<String, RouteRule> CONTEXT_RULES = new ConcurrentHashMap<>();

    /** API capture 的可选全局 URL 范围；为空时不限制。 */
    private static volatile String captureBaseUrl;
    private static volatile String captureBasePath;

    /** 配置 capture 只处理指定 BASE_URL 下的请求。传 null/blank 表示取消限制。 */
    public static void setCaptureBaseUrl(String baseUrl) {
        captureBaseUrl = normalizeCaptureBaseUrl(baseUrl);
    }

    /** 配置 capture 只处理指定 BASE_PATH 下的请求。传 null/blank 表示取消限制。 */
    public static void setCaptureBasePath(String basePath) {
        captureBasePath = normalizeCaptureBasePath(basePath);
    }

    /** 清除 capture 的 BASE_URL/BASE_PATH 限制。 */
    public static void clearCaptureUrlScope() {
        captureBaseUrl = null;
        captureBasePath = null;
    }

    /**
     * ⭐ 性能优化：context 规则的预提取 path 子串缓存。
     * key = 归一化 pattern，value = 预先提取的 path（见 extractPathFromNormalizedPattern）。
     * 避免在 dispatchRoute 跨层合并的高频路径中每次请求都 substring 分配新 String。
     */
    private static final Map<String, String> CONTEXT_RULE_PATHS = new ConcurrentHashMap<>();
    /** 按 path literal 前缀索引 Context 规则；通配前缀规则进入 fallback。 */
    private static final Map<String, Set<String>> CONTEXT_RULE_KEYS_BY_PREFIX = new ConcurrentHashMap<>();
    private static final Set<String> CONTEXT_RULE_FALLBACK_KEYS = ConcurrentHashMap.newKeySet();
    /** Context 隔离规则快照；旧全局表仅用于无 Context 的兼容路径。 */
    private static final Map<BrowserContext, Map<String, RouteRule>> CONTEXT_RULES_BY_CONTEXT =
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
    private static final Map<Route, Long> DISPATCHED_ROUTES = new ConcurrentHashMap<>();

    /** DISPATCHED_ROUTES 容量上限，超过后自动清空（防御性保护） */
    private static final int MAX_DISPATCHED_ROUTES = 500;

    /**
     * DISPATCHED_ROUTES 条目 TTL：超过该时长的 Route 引用视为已结束并自动过期释放。
     * 防止异常场景（未调用 clearDispatchedRoutes）下强引用常驻导致的内存泄漏。
     */
    private static final long DISPATCHED_ROUTES_TTL_MS = 60_000L;

    /**
     * 跨层级去重 — Page route 已通过 cross-layer merge 合并处理的请求 URL。
     *
     * <p>Page 和 Context 的 Playwright route 收到的是<b>不同的 Route 对象</b>，
     * 因此 {@link #DISPATCHED_ROUTES}（基于 Route 引用）无法阻止 context handler
     * 重复处理已被 page handler cross-layer merge 拦截的请求。
     *
     * <p>此集合记录 page handler cross-layer merge 过程中已处理的请求 URL，
     * context handler 到达时先检查此集合，命中则跳过（直接 resume）。
     * 使用 {@code remove()} 原子获取并清除，避免 URL 临时碰撞导致误删。
     */
    private static final Map<String, Long> CROSS_LAYER_HANDLED_URLS = new ConcurrentHashMap<>();
    private static final long CROSS_LAYER_DEDUP_TTL_MS = 5_000L;
    private static final int MAX_CROSS_LAYER_DEDUP_ENTRIES = 2_048;

    /**
     * ⭐ 页面级规则注册表：Page → List&lt;RouteRule&gt;
     *
     * <p>存储所有在 Page 级别注册的路由规则，用于在页面切换（新页面创建/旧页面关闭）时
     * 自动重新注册规则到新页面，确保 API 监控在跨页面场景下不丢失。
     *
     * <p>使用 WeakReference 包装的 Page 键，避免阻止 Page 被 GC。
     */
    private static final Map<PageRef, List<RouteRule>> PAGE_RULES = new ConcurrentHashMap<>();

    /**
     * ⭐ 页面级规则的弱引用键 — 允许 Page 在不使用时被 GC 回收。
     */
    private static final class PageRef {
        private final int identityHash;
        private final java.lang.ref.WeakReference<Page> ref;

        PageRef(Page page) {
            this.identityHash = System.identityHashCode(page);
            this.ref = new java.lang.ref.WeakReference<>(page);
        }

        Page get() { return ref.get(); }

        boolean isDead() { return ref.get() == null; }

        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if (!(o instanceof PageRef)) return false;
            PageRef that = (PageRef) o;
            Page a = this.ref.get();
            Page b = that.ref.get();
            return a != null && b != null && a == b;
        }

        @Override
        public int hashCode() {
            return identityHash;
        }
    }

    /** 超时调度器（守护线程，避免阻塞 JVM 退出） */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "route-monitor-timeout");
                t.setDaemon(true);
                return t;
            });

    /** 网络延迟调度器（多线程池，支持并发请求同时延迟） */
    private static volatile ScheduledExecutorService DELAY_SCHEDULER =
            newDelayScheduler();

    private static ScheduledExecutorService newDelayScheduler() {
        return Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "route-network-delay");
            t.setDaemon(true);
            return t;
        });
    }

    private static ScheduledExecutorService delayScheduler(Route route) {
        try {
            if (route != null && route.request() != null && route.request().frame() != null
                    && route.request().frame().page() != null) {
                BrowserContext context = route.request().frame().page().context();
                ContextRouteEngine contextEngine = ContextRouteEngineManager.getOrStart(context);
                if (contextEngine.isRunning()) return contextEngine.delayScheduler();
            }
        } catch (Exception ignored) {
            // Page/Context 已销毁时回退兼容调度器。
        }
        return delayScheduler();
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

    /** 采集引擎实例按 Page 隔离（由 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture.ApiCapture} 设置） */
    private static final java.util.concurrent.ConcurrentHashMap<Page, CaptureEngine> PAGE_CAPTURE_ENGINES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 绑定指定 Page 的采集引擎；用于多 Page 并行采集。 */
    public static void bindCaptureEngine(Page page, CaptureEngine engine) {
        if (page == null) return;
        if (engine == null) PAGE_CAPTURE_ENGINES.remove(page);
        else PAGE_CAPTURE_ENGINES.put(page, engine);
    }

    /** 解绑指定 Page 的采集引擎。 */
    public static void unbindCaptureEngine(Page page) {
        if (page != null) PAGE_CAPTURE_ENGINES.remove(page);
    }

    /**
     * 设置采集引擎实例（供采集管道接收 MOCK/MODIFY 路由事件）。
     *
     * <p>由 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture.ApiCapture#start(Page)}
     * 在启动时调用。执行 handler 后若 CapturedApiCall 不为 null 则投喂到管道。
     *
     * @param engine 采集引擎实例，null 表示取消关联
     */
    /**
     * 兼容性保留：采集管道已按 Page 隔离（{@link #PAGE_CAPTURE_ENGINES}），
     * 全局引擎引用已移除以避免跨 Page 串线，此方法不再执行任何副作用。
     *
     * @deprecated 全局引擎已废弃，事件路由依赖 {@link #bindCaptureEngine(Page, CaptureEngine)}。
     */
    @Deprecated
    public static void setCaptureEngine(CaptureEngine engine) {
        // no-op：采集按 Page 隔离，不再维护全局回退引擎
    }

    /** 标记调度器是否已关闭 */
    private static final AtomicBoolean scheduledShutdown = new AtomicBoolean(false);

    static {
        HANDLERS.put(RouteHandleType.MONITOR, MonitorHandler::handle);
        HANDLERS.put(RouteHandleType.MODIFY, ModifyHandler::handle);
        HANDLERS.put(RouteHandleType.MOCK, MockHandler::handle);
        // DELAY 类型不在此注册 — 由 dispatchRoute 直接调度，无需经过 Handler 接口
    }

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

        // ⭐ 停止采集引擎（释放 CDP session 和线程池）
        com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture.ApiCapture.stop();

        // ⭐ 清理所有上下文路由注册表（含 Playwright 层的 unroute）
        RouteRegistry.clearAll();
        clearAllMonitorSessions();
        DISPATCHED_ROUTES.clear();
        CONTEXT_RULES.clear();
        CONTEXT_RULE_PATHS.clear();
        CONTEXT_RULE_KEYS_BY_PREFIX.clear();
        CONTEXT_RULE_FALLBACK_KEYS.clear();
        CROSS_LAYER_HANDLED_URLS.clear();
        PAGE_RULES.clear();

        // 关闭网络延迟调度器
        DELAY_SCHEDULER.shutdownNow();
        try {
            if (!DELAY_SCHEDULER.awaitTermination(2, TimeUnit.SECONDS)) {
                LOGGER.warn("[RouteEngine] DELAY_SCHEDULER did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 关闭超时调度器
        SCHEDULER.shutdownNow();
        try {
            if (!SCHEDULER.awaitTermination(2, TimeUnit.SECONDS)) {
                LOGGER.warn("[RouteEngine] SCHEDULER did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        LOGGER.info("[RouteEngine] All schedulers shut down");
    }

    /**
     * 注册路由规则到 Page。
     */
    public static void register(Page page, List<RouteRule> rules) {
        LoggingConfigUtil.logDebugIfVerbose(LOGGER, "[RouteEngine] ── Registering {} rule(s) on Page ──", rules.size());

        // ⭐ 存储页面级规则，供切换新页面时重新注册
        PAGE_RULES.put(new PageRef(page), new java.util.ArrayList<>(rules));

        registerInternal(page, (pattern, rule) -> {
            Map<String, RouteRule> store = ENGINE_RULE_STORE.computeIfAbsent(page, k -> new ConcurrentHashMap<>());
            // ⭐ 仅在锁内完成 store 的纯内存状态决策，绝不持有锁调用原生 page.route()（JNI/网络 IO）。
            //    原生注册放在锁外执行，避免高并发注册/异常时持锁做 IO 导致线程长时间阻塞甚至重入风险。
            final boolean[] needRegister = {false};
            final boolean[] needRefresh = {false};
            final RouteRule[] mergedRule = {null};
            synchronized (store) {
                RouteRule existing = store.get(pattern);
                if (existing == null) {
                    // 首次注册：存入引用 + 绑定闭包
                    store.put(pattern, rule);
                    // ⭐ 仅作清理记录（RouteRegistry 不再用于优先级决策，此处保留供 clearContext 反查 pattern）
                    RouteRegistry.forceRegister(page, pattern, rule.getType());
                    needRegister[0] = true;
                } else if (existing.getType() == RouteHandleType.MOCK && rule.getType() != RouteHandleType.MOCK) {
                    // ⭐ 已有 MOCK 是终结型，不可被非 MOCK 降级 → 跳过
                    LOGGER.debug("[RouteEngine] Skipping non-MOCK pattern '{}' on Page (existing MOCK is terminal)", pattern);
                } else {
                    // ⭐ 能力位合并：MONITOR 基线不可关、MODIFY 字段 putAll、DELAY 取 max、MOCK 可覆盖非 MOCK
                    existing.mergeFrom(rule);
                    // ⭐ MOCK 是唯一终结者：无论注册先后顺序，只要本次是 MOCK，就把 type 提升为 MOCK
                    if (rule.getType() == RouteHandleType.MOCK && existing.getType() != RouteHandleType.MOCK) {
                        existing.setType(RouteHandleType.MOCK);
                        LOGGER.info("[RouteEngine] Type upgraded to MOCK (terminal) for pattern '{}' on Page", pattern);
                    }
                    // ⭐ 标记需在锁外刷新 MonitorSession（写全局 SESSIONS + 调度超时任务，不应持 store 锁）
                    needRefresh[0] = true;
                    mergedRule[0] = existing;
                }
            }
            // 锁外执行原生路由注册（JNI）与 MonitorSession 刷新，不阻塞其它线程对 store 的访问
            if (needRegister[0]) {
                try {
                    registerRouteToPage(page, pattern, rule);
                } catch (Throwable t) {
                    // ⭐ 关键一致性保护：原生注册失败时回滚锁内已提交的 store + RouteRegistry，
                    //    避免「内存认为已注册、但实际路由从未绑定」的静默失效（请求不被拦截且无告警）。
                    synchronized (store) {
                        store.remove(pattern);
                    }
                    RouteRegistry.unregister(page, pattern);
                    LOGGER.error("[RouteEngine] Native route registration failed for pattern '{}' on Page "
                            + "— rolled back in-memory state to avoid silent mismatch: {}",
                            pattern, t.getMessage());
                }
            }
            if (needRefresh[0]) {
                refreshMonitorSession(page, pattern, mergedRule[0]);
            }
        }, rules);
    }

    /**
     * 注册 Playwright 路由到 Page（实际 route + session 创建）。
     */
    private static void registerRouteToPage(Page page, String pattern, RouteRule rule) {
        page.route(pattern, route -> dispatchRoute(route, rule));
        startMonitorSession(page, rule, pattern);
        LOGGER.info("[RouteEngine] Route registered: type={}, pattern='{}', context=Page",
                rule.getType(), pattern);
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine]    rule detail: urlPattern='{}', type={}, delay={}ms, mockStatus={}, record={}, autoStop={}",
                rule.getUrlPattern(), rule.getType(), rule.getDelayMs(), rule.getMockStatus(),
                rule.isRecord(), rule.isAutoStopOnMatch());
    }

    /**
     * 注册路由规则到 BrowserContext。
     */
    public static void register(BrowserContext context, List<RouteRule> rules) {
        LoggingConfigUtil.logDebugIfVerbose(LOGGER, "[RouteEngine] ── Registering {} rule(s) on BrowserContext ──", rules.size());
        registerInternal(context, (pattern, rule) -> {
            Map<String, RouteRule> store = ENGINE_RULE_STORE.computeIfAbsent(context, k -> new ConcurrentHashMap<>());
            // ⭐ 仅在锁内完成 store 的纯内存状态决策，绝不持有锁调用原生 context.route()（JNI/网络 IO）。
            //    原生注册放在锁外执行，避免高并发注册/异常时持锁做 IO 导致线程长时间阻塞甚至重入风险。
            final boolean[] needRegister = {false};
            final boolean[] needRefresh = {false};
            final RouteRule[] mergedRule = {null};
            synchronized (store) {
                RouteRule existing = store.get(pattern);
                if (existing == null) {
                    // 首次注册：存入引用 + 绑定闭包
                    store.put(pattern, rule);
                    // ⭐ 仅作清理记录（RouteRegistry 不再用于优先级决策，此处保留供 clearContext 反查 pattern）
                    RouteRegistry.forceRegister(context, pattern, rule.getType());
                    needRegister[0] = true;
                } else if (existing.getType() == RouteHandleType.MOCK && rule.getType() != RouteHandleType.MOCK) {
                    // ⭐ 已有 MOCK 是终结型，不可被非 MOCK 降级 → 跳过
                    LOGGER.debug("[RouteEngine] Skipping non-MOCK pattern '{}' on BrowserContext (existing MOCK is terminal)", pattern);
                } else {
                    // ⭐ 能力位合并：MONITOR 基线不可关、MODIFY 字段 putAll、DELAY 取 max、MOCK 可覆盖非 MOCK
                    existing.mergeFrom(rule);
                    // ⭐ MOCK 是唯一终结者：无论注册先后顺序，只要本次是 MOCK，就把 type 提升为 MOCK
                    if (rule.getType() == RouteHandleType.MOCK && existing.getType() != RouteHandleType.MOCK) {
                        existing.setType(RouteHandleType.MOCK);
                        LOGGER.info("[RouteEngine] Type upgraded to MOCK (terminal) for pattern '{}' on BrowserContext", pattern);
                    }
                    // ⭐ 标记需在锁外刷新 MonitorSession（写全局 SESSIONS + 调度超时任务，不应持 store 锁）
                    needRefresh[0] = true;
                    mergedRule[0] = existing;
                }
            }
            // 锁外执行原生路由注册（JNI）与 MonitorSession 刷新，不阻塞其它线程对 store 的访问
            if (needRegister[0]) {
                try {
                    registerRouteToContext(context, pattern, rule);
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
                refreshMonitorSession(context, pattern, mergedRule[0]);
            }
        }, rules);
    }

    /**
     * 注册 Playwright 路由到 BrowserContext（实际 route + session 创建 + 跨层级缓存）。
     */
    private static void registerRouteToContext(BrowserContext context, String pattern, RouteRule rule) {
        context.route(pattern, route -> dispatchRoute(route, rule));
        // ⭐ Context 级规则入注册表，供 page 级 handler 跨层级合并
        CONTEXT_RULES.put(pattern, rule);
        CONTEXT_RULES_BY_CONTEXT.computeIfAbsent(context, ignored -> new ConcurrentHashMap<>()).put(pattern, rule);
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
     * 路由分发 — 根据规则类型调用对应 Handler。
     *
     * <p>防重门控：同一 Route 对象被多个重叠 pattern 匹配时，
     * 仅第一个到达的 handler 执行，后续 handler 静默跳过（避免 "Route is already handled" 异常）。
     * <p>每次 handler 执行完成后立即 remove，避免阻塞同一 pattern 的后续请求。
     */
    private static void dispatchRoute(Route route, RouteRule rule) {
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

        // ═══ 跨层级去重：若 page handler 已通过 cross-layer merge 处理了此 URL，
        //     context handler 不应重复拦截（Page/Context Route 对象不同，DISPATCHED_ROUTES 无法去重）═══
        purgeExpiredCrossLayerEntries();
        String crossLayerKey = crossLayerKey(route, reqUrl);
        Long handledAt = CROSS_LAYER_HANDLED_URLS.remove(crossLayerKey);
        if (handledAt != null && System.currentTimeMillis() - handledAt <= CROSS_LAYER_DEDUP_TTL_MS) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] ═══ dispatchRoute SKIP (cross-layer dedup): URL already handled by page-level merge, " +
                    "pattern='{}', url='{}' ═══", rule.getUrlPattern(), reqUrl);
            try { route.resume(); } catch (Exception ignored) {}
            return;
        }

        // ═══ 防御性清理：Map 超过上限时清空（防止异常情况下无限增长）═══
        if (DISPATCHED_ROUTES.size() >= MAX_DISPATCHED_ROUTES) {
            LOGGER.warn("[RouteEngine] DISPATCHED_ROUTES reached {} entries, clearing to prevent memory leak",
                    DISPATCHED_ROUTES.size());
            DISPATCHED_ROUTES.clear();
        } else if (!DISPATCHED_ROUTES.isEmpty()) {
            // ⭐ 惰性 TTL 过期：避免异常场景（未调用 clearDispatchedRoutes）下
            //    Route 强引用长期常驻造成内存泄漏。每次 dispatch 顺带清理过期条目。
            long now = System.currentTimeMillis();
            DISPATCHED_ROUTES.values().removeIf(ts -> now - ts > DISPATCHED_ROUTES_TTL_MS);
        }

        // ═══ 防重门控：同一请求只处理一次（带 TTL，自动过期释放引用）═══
        if (DISPATCHED_ROUTES.putIfAbsent(route, System.currentTimeMillis()) != null) {
            LOGGER.warn("[RouteEngine] Route already handled by another pattern, skipping '{}' for URL '{}'",
                    rule.getUrlPattern(), reqUrl);
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] ═══ dispatchRoute SKIPPED (duplicate): pattern='{}', url='{}' ═══",
                    rule.getUrlPattern(), reqUrl);
            return;
        }

        // ═══ 请求条件匹配：根据 Rule 中配置的 ResourceType/Header/Query/Body 等过滤 ═══
        if (!RouteUtil.requestMatches(route, rule)) {
            // 不匹配此规则 → 移除防重标记，让 Playwright 继续尝试下一个 pattern
            DISPATCHED_ROUTES.remove(route);
            route.resume();
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] ═══ dispatchRoute MISMATCH (condition filter): pattern='{}', url='{}' ═══",
                    rule.getUrlPattern(), reqUrl);
            return;
        }

        // ═══ 检查 MonitorSession 是否已停止（auto-stop / 超时），停止则跳过 handler ═══
        // 不在此处调用 unroute()，避免 Playwright 线程竞态导致 "Object doesn't exist" 或 "Cannot find command to respond" 错误。
        // route handler 保持注册，但已停止的 session 仅放行请求，不处理。
        MonitorSession session = SESSIONS.get(rule);
        if (session != null && session.stopped.get()) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] ═══ dispatchRoute SKIP (session stopped): pattern='{}', url='{}' ═══",
                    rule.getUrlPattern(), reqUrl);
            try { route.resume(); } catch (Exception ignored) {}
            DISPATCHED_ROUTES.remove(route);
            return;
        }

        // ═══ 跨层级规则合并（Context + Page） ═══
        // ⭐ 必须在 DELAY 分支之前检查：context MOCK 会覆盖 page handler
        // Playwright Page.route() 优先级高于 BrowserContext.route()，
        // context 级规则会被 page 级规则完全屏蔽。在此查找并合并。
        //
        // 主优先级（决定最终行为）：MOCK > MODIFY > MONITOR > DELAY
        // 层级优先级（同类型时）：Page > Context（page 配置胜出）
        // DELAY 始终合并取最大值；
        // 同 API 统一在 page handler 内合并执行，context handler 被 CROSS_LAYER_HANDLED_URLS 去重；
        // 不同 API 各自负责，互不干扰。
        long delayMs = rule.getDelayMs();

        // ⭐ 跨层合并是否真正发生（非 ctxRule != null，因为 session 可能已停止）
        boolean crossLayerDelayMerged = false;

        // ⭐ 仅 page handler 做跨层合并，context handler 跳过（避免在 CONTEXT_RULES 中自引用）。
        //    注意：用「实例同一性（==）」而非 equals 判断，因为新模型下同 pattern 的
        //    page 规则与 context 规则 urlPattern 相同、equals 相等，若用 equals 会把
        //    page 规则误判为 context 规则而跳过跨层合并（n50 回归）。
        //    ⭐ 性能优化：把「判断 rule 是否即 context 规则」与「查找匹配 ctxRule」合并为<b>一次遍历</b>，
        //    消除原先 isContextRule 全量扫描 + findMatchingContextRule 全量扫描的两段 O(n)。
        RouteRule ctxRule = null;
        boolean isContextRule = false;
        // ⭐ 企业级「精确优先」：同时命中多个 context 规则时，取<b>最精确</b>（字面路径最长）的一条，
        //    而非第一个。例：ctx 层有 /api/users 与 /api/users/1，URL=/api/users/1 → 取 /api/users/1。
        int bestCtxPathLen = -1;
        for (String pattern : contextCandidatePatterns(reqUrl, contextRulesFor(route))) {
            RouteRule cr = contextRulesFor(route).get(pattern);
            if (cr == null) continue;
            // 若当前 handler 的 rule 就是 context 规则本身 → 它是 context handler，跳过跨层合并
            if (cr == rule) {
                isContextRule = true;
                break;
            }
            // 记录匹配 reqUrl 且字面路径最长的 context 规则（复用 CONTEXT_RULE_PATHS 预缓存 path）
            String path = CONTEXT_RULE_PATHS.get(pattern);
            if (path != null && !path.isEmpty() && reqUrl.contains(path) && path.length() > bestCtxPathLen) {
                bestCtxPathLen = path.length();
                ctxRule = cr;
            }
        }
        if (isContextRule) ctxRule = null;

        if (ctxRule != null) {
            // ⭐ 若 context 规则的 MonitorSession 已停止（超时/auto-stop），
            //    则忽略跨层合并 — 已停止的规则不应影响 page handler 行为
            MonitorSession ctxSession = SESSIONS.get(ctxRule);
            if (ctxSession != null && ctxSession.stopped.get()) {
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteEngine] Context rule session stopped, skip cross-layer merge: ctxPattern='{}', url='{}'",
                        ctxRule.getUrlPattern(), reqUrl);
                // fall through — ctxRule 视为不存在，直接走 page handler
            } else {
                // ⭐ 标记此 URL — context handler 到达时必须跳过（所有类型均适用，含相同类型）
                if (CROSS_LAYER_HANDLED_URLS.size() >= MAX_CROSS_LAYER_DEDUP_ENTRIES) {
                    CROSS_LAYER_HANDLED_URLS.clear();
                }
                CROSS_LAYER_HANDLED_URLS.put(crossLayerKey(route, reqUrl), System.currentTimeMillis());

                RouteHandleType pageType = rule.getType();
                RouteHandleType ctxType = ctxRule.getType();

                // ── 延迟合并（n51 / n53）：ctx 层 delay 始终保留，page 层 delay 仅当
                //    page 类型在跨层合并后「存活」（优先级不低于 ctx）时保留 ──
                // • ctx 层的 delay 是「全局延迟配置」，无条件合并；
                // • page 层的 delay 是局部延迟，仅当 ctx 类型优先级 ≤ page 类型优先级时保留
                //   （即 ctx 未覆盖 page 的延迟语义）；
                // • 二者取 max。MOCK 作为唯一终结者会覆盖 DELAY（page 被 MOCK 终结则 pageDelay 失效）。
                // 例：n51 page=DELAY + ctx=MOCK → ctx 优先级更高，page 被终结 → pageDelay=0 → MOCK 立即返回；
                //     n53 ctx=DELAY(500) + page=MOCK → page 优先级≥ctx，pageDelay=0，ctxDelay=500 → 延迟 500ms。
                long ctxDelay = DelayHandler.clampDelay(DelayHandler.resolveDelay(ctxRule));
                boolean pageSurvives = priorityOf(ctxType) <= priorityOf(pageType);
                long pageDelay = pageSurvives ? DelayHandler.clampDelay(DelayHandler.resolveDelay(rule)) : 0;
                delayMs = Math.max(pageDelay, ctxDelay);
                crossLayerDelayMerged = true;
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteEngine] Cross-layer DELAY merged: pageType={}, ctxType={}, pageDelay={}ms, ctxDelay={}ms, effectiveDelay={}ms",
                        pageType, ctxType, pageDelay, ctxDelay, delayMs);

                // ═══ 能力位合并（OR）：把 context 规则的能力位合并进<b>一份临时拷贝</b> ═══
                //    ⭐ 关键：用 copyForMerge() 构造一次性有效规则，绝不就地修改
                //    ENGINE_RULE_STORE/闭包持有的原 page rule —— 否则会跨请求行为漂移 +
                //    集合字段（jsonPathAssertions / requestHeadersToSet）无限累积。
                //    MONITOR 基线不可关、MODIFY 字段 putAll、DELAY 已在上文取 max、
                //    MOCK 可终结（覆盖非 MOCK）。
                //    能力与 type 解耦：除「MOCK 作为唯一终结者」外，type 不等于行为单选；
                //    具体行为由下方能力位管线按字段判定。跨层 type 提升规则：
                //      • 任一层为 MOCK → 有效规则 type=MOCK（MOCK 终结，短路）；
                //      • 否则保持 page 层自身 type（MONITOR/MODIFY/DELAY 能力位叠加）。
                RouteRule effectiveRule = rule.copyForMerge();
                effectiveRule.mergeFrom(ctxRule);
                if (pageType == RouteHandleType.MOCK || ctxType == RouteHandleType.MOCK) {
                    effectiveRule.setType(RouteHandleType.MOCK);
                } else {
                    effectiveRule.setType(pageType); // 保持 page 主 type，能力位 OR
                }
                // 有效规则替换原 rule，后续主管线用 effectiveRule 执行（含跨层合并的全部能力位）
                rule = effectiveRule;
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteEngine] Cross-layer capability merged: ctxPattern='{}', pagePattern='{}', effectiveDelay={}ms",
                        ctxRule.getUrlPattern(), effectiveRule.getUrlPattern(), delayMs);
            }
        }

        // ═══ 能力位管线（取代 type 单选）：MOCK 终结 → MODIFY → MONITOR → DELAY ═══
        // 能力与 type 解耦：MOCK 为唯一终结者（短路）；其余能力位（MODIFY/MONITOR/DELAY）
        // 按字段叠加，可同时存在。监控是<b>不可覆盖的基线</b>：无论是否叠加 modify/delay，
        // 最终都对真实响应断言，失败即报错。
        final long effectiveDelay = delayMs;
        // ⭐ 取 final 副本供 lambda 引用（rule 在跨层合并可能被重新赋值，非 effectively final）
        final RouteRule finalRule = rule;

        // 1) MOCK 终结：直接 fulfill 假响应，不发真实请求（监控/修改/delay 均无意义）
        if (finalRule.getType() == RouteHandleType.MOCK) {
            if (effectiveDelay > 0) {
                asyncHandled[0] = true;
                delayScheduler(route).schedule(
                        () -> executeHandlerScheduled(route, finalRule, MockHandler::handle),
                        effectiveDelay, TimeUnit.MILLISECONDS);
            } else {
                executeHandler(route, finalRule, MockHandler::handle);
            }
            return;
        }

        // 2) MODIFY：改请求后 route.resume(opts) 放行（不改响应），内部读真实响应并叠加监控断言
        boolean hasModify = (finalRule.getRequestHeadersToSet() != null && !finalRule.getRequestHeadersToSet().isEmpty())
                || (finalRule.getRequestHeadersToRemove() != null && !finalRule.getRequestHeadersToRemove().isEmpty())
                || (finalRule.getRequestBodyFieldsToModify() != null && !finalRule.getRequestBodyFieldsToModify().isEmpty())
                || (finalRule.getRequestBodyFieldsToAdd() != null && !finalRule.getRequestBodyFieldsToAdd().isEmpty())
                || (finalRule.getRequestBodyFieldsToRemove() != null && !finalRule.getRequestBodyFieldsToRemove().isEmpty())
                || finalRule.getModifyMethod() != null;
        if (hasModify) {
            if (effectiveDelay > 0) {
                asyncHandled[0] = true;
                delayScheduler(route).schedule(
                        () -> executeHandlerScheduled(route, finalRule, ModifyHandler::handle),
                        effectiveDelay, TimeUnit.MILLISECONDS);
            } else {
                executeHandler(route, finalRule, ModifyHandler::handle);
            }
            return;
        }

        // 3) MONITOR（基线）：delay 后 resume + 对真实响应断言；监控失败即报错
        if (finalRule.isMonitorEnabled()) {
            if (effectiveDelay > 0) {
                asyncHandled[0] = true;
                delayScheduler(route).schedule(
                        () -> executeHandlerScheduled(route, finalRule, MonitorHandler::handle),
                        effectiveDelay, TimeUnit.MILLISECONDS);
            } else {
                executeHandler(route, finalRule, MonitorHandler::handle);
            }
            return;
        }

        // 4) 纯 DELAY（无 modify 无 monitor）：延迟后放行
        if (finalRule.getType() == RouteHandleType.DELAY || effectiveDelay > 0) {
            long scheduledMs = crossLayerDelayMerged ? delayMs : 0;
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] ═══ dispatchRoute DELAY: scheduling for pattern='{}', url='{}', crossLayerMerged={}, delay={}ms ═══",
                    finalRule.getUrlPattern(), reqUrl, crossLayerDelayMerged, delayMs);
            asyncHandled[0] = true;
            scheduleDelay(route, finalRule, scheduledMs);
            return;
        }

        // 5) 兜底：无能力位 → 直接放行
        LOGGER.debug("[RouteEngine] No capability on rule, resume: pattern='{}'", finalRule.getUrlPattern());
        try {
            route.resume();
        } catch (Exception ignored) {
            // 已失效/已关闭：忽略
        }
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
                DISPATCHED_ROUTES.remove(route);
            }
        }
    }

    /**
     * 跨层类型优先级（仅用于判断 page 层 delay 是否「存活」）：MOCK > MODIFY > MONITOR > DELAY。
     * 注意：此优先级不再用于「类型单选」（能力位管线已解耦 type），仅决定 page 的局部
     * delay 是否被更高优先级的 ctx 规则终结。
     */
    private static int priorityOf(RouteHandleType t) {
        switch (t) {
            case MOCK:   return 3;
            case MODIFY: return 2;
            case MONITOR:return 1;
            case DELAY:  return 0;
            default:     return 0;
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
        Request req = route.request();
        String url = req.url();
        String pattern = rule.getUrlPattern();

        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] scheduleDelay: pattern='{}', url='{}', delay={}ms, minDelay={}ms, maxDelay={}ms, mergedInput={}ms",
                pattern, url, delayMs, rule.getDelayMinMs(), rule.getDelayMaxMs(), preComputedDelayMs);

        // ⭐ DELAY 拦截期间递增 activeRequests，使 awaitCompletion 能等待延迟请求放行 +
        //    后续真实响应被捕获，避免并发场景（f31）在延迟窗口内误判「全部完成」。
        //    resume 后真实请求由 CDP/Playwright 捕获管道接管（captureInFlight 覆盖），
        //    此处 activeRequests 覆盖整个延迟窗口直至 storeDelayCall 落库。
        ApiCaptureContext captureContext = RouteUtil.captureContext(route);
        captureContext.incrementActiveRequests();

        Runnable action = () -> {
            try {
                // 延迟期间页面/上下文可能已被关闭，抵达时直接放行，避免对已销毁页面操作报错
                if (RouteUtil.isPageClosed(route)) {
                    LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                            "[RouteEngine] Page already closed during delay, skip resume for '{}'", pattern);
                    RouteUtil.resumeIfOpen(route);
                    DISPATCHED_ROUTES.remove(route);
                    return;
                }
                // 检查会话是否已被停止（auto-stop / 超时）
                MonitorSession session = SESSIONS.get(rule);
                if (session != null && session.stopped.get()) {
                    LOGGER.debug("[RouteEngine] Session stopped during delay, skipping for '{}'", pattern);
                    try { route.resume(); } catch (Exception ignored) {}
                    return;
                }

                route.resume();
                LOGGER.info("[RouteEngine] Route delayed: pattern='{}', url='{}', delay={}ms",
                        pattern, url, delayMs);

                // 将 DELAY 调用存入 ApiCaptureContext，与 MONITOR/MOCK 统一可查询
                storeDelayCall(route, rule);

                onMonitorMatch(rule);
            } catch (Exception e) {
                LOGGER.error("[RouteEngine] Failed to continue route after delay for '{}': {}",
                        pattern, e.getMessage(), e);
                try { route.resume(); } catch (Exception ignored) {}
            } finally {
                DISPATCHED_ROUTES.remove(route);
                // ⭐ 延迟窗口结束（resume + storeDelayCall 落库后）递减在途计数
                captureContext.decrementActiveRequests();
            }
        };

        if (delayMs > 0) {
            delayScheduler(route).schedule(action, delayMs, TimeUnit.MILLISECONDS);
            LOGGER.debug("[RouteEngine] Delay scheduled: pattern='{}', url='{}', delay={}ms",
                    pattern, url, delayMs);
        } else {
            action.run();
        }
    }

    /**
     * 将 DELAY 调用存入 ApiCaptureContext，使其像 MONITOR/MOCK 一样可被查询。
     *
     * <p>DELAY 仅延迟放行请求（不修改响应），因此存储的信息以请求元数据为主，
     * 不包含响应体（resume 异步，响应尚未返回）。满足 assertNotNull 等基础断言。
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
                    req.url()  // 实际请求 URL，用于毫秒级精确检索
            );
            ApiCaptureContext ctx = RouteUtil.captureContext(route);
            if (ctx != null) {
                ctx.storeApiCall(call);
            }
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] Stored DELAY call to ApiCaptureContext: pattern='{}', method={}",
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
            MonitorSession session = SESSIONS.get(rule);
            if (session != null && session.stopped.get()) {
                LOGGER.debug("[RouteEngine] Session stopped during delay, skipping handler for '{}'",
                        rule.getUrlPattern());
                try { route.resume(); } catch (Exception ignored) {}
                return;
            }
            executeHandler(route, rule, handler);
        } catch (Exception e) {
            LOGGER.error("[RouteEngine] Scheduled handler failed for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage(), e);
            try { route.resume(); } catch (Exception ignored) {}
        }
    }

    /**
     * 执行 Handler，统一异常处理和日志。
     */
    private static void executeHandler(Route route, RouteRule rule, RouteHandler handler) {
        // ⭐ #1 性能优化：缓存 route.request()，避免 executeHandler 内重复 JNI 调用
        Request req = route.request();
        try {
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[RouteEngine] executeHandler START: type={}, pattern='{}', url='{}'",
                    rule.getType(), rule.getUrlPattern(), req.url());
            handler.handle(route, rule);

            LOGGER.info("[RouteEngine] Route matched: type={}, pattern='{}', method={}, url='{}'",
                    rule.getType(), rule.getUrlPattern(),
                    req.method(), req.url());

            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] executeHandler DONE: type={}, pattern='{}'",
                    rule.getType(), rule.getUrlPattern());

            // MOCK/MODIFY/DELAY 处理成功后触发匹配计数（支持一次性拦截 / auto-stop）
            // MONITOR 的匹配计数在 MonitorHandler 异步完成时回调，不在此处触发
            if (rule.getType() != RouteHandleType.MONITOR) {
                onMonitorMatch(rule);
            }

            // ═══ 采集管道钩子：MOCK/MODIFY 处理完成后投喂事件 ═══
            feedCaptureEvent(route, rule, req);
        } catch (RouteException.ApiAssertionException e) {
            // ⭐⭐⭐ MonitorHandler 同步断言失败 — 测试线程已被 signalFailFast() 中断
            LOGGER.error("[RouteEngine] API assertion FAILED for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage());
            // 路由已被 MonitorHandler.resume() 放行，无需额外处理
            // ApiAssertionException 不在此处继续传播（Playwright 内部捕获），
            // 但主测试线程已被 interrupt，当前阻塞的 Playwright 操作将立即失败
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
            DISPATCHED_ROUTES.remove(route);
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
        // ⭐ 用 putIfAbsent 去重：并发 register 同一 rule 时（不同 context 各自持 store 锁，但都写全局 SESSIONS），
        //    防止 TOCTOU 导致重复创建 session —— 重复创建会让先建的 session 的 timeoutFuture 永不取消
        //    （仅最后一个被 stop），并造成 matchCount 计数分散、超时任务泄漏。已存在则复用，不重复 scheduleTimeout。
        MonitorSession existing = SESSIONS.putIfAbsent(rule, session);
        if (existing != null) {
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[RouteEngine] MonitorSession already exists for rule (pattern='{}'), reusing",
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
     * ⭐ 合并后刷新 MonitorSession：规则经 {@link RouteRule#mergeFrom} 叠加监控能力位后，
     * 若当前无活跃 session（例如「先 modify 后追加 monitor」的逆向注册顺序），则启动一个。
     * 若 session 已存在（常见「先 monitor 后追加 modify」顺序），则无需重建——闭包持有的 rule
     * 对象已被就地合并，session 读取的是同一对象，配置自动生效。
     *
     * @param ctx      context / page 对象
     * @param pattern  归一化 pattern
     * @param rule     合并后的规则
     */
    private static void refreshMonitorSession(Object ctx, String pattern, RouteRule rule) {
        if (!rule.isMonitorEnabled()) return;
        MonitorSession session = SESSIONS.get(rule);
        if (session == null || session.stopped.get()) {
            startMonitorSession(ctx, rule, pattern);
        }
    }

    /**
     * MonitorHandler 每次匹配完成时回调。
     * 递增计数并检查 auto-stop / minMatches 条件。
     *
     * @param rule 路由规则
     */
    public static void onMonitorMatch(RouteRule rule) {
        MonitorSession session = SESSIONS.get(rule);
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
                "[RouteEngine] clearAllMonitorSessions: stopping {} session(s), clearing {} dispatched routes, {} context rules, {} cross-layer handled urls",
                SESSIONS.size(), DISPATCHED_ROUTES.size(), CONTEXT_RULES.size(), CROSS_LAYER_HANDLED_URLS.size());
        for (MonitorSession session : SESSIONS.values()) {
            session.stop();
        }
        SESSIONS.clear();
        DISPATCHED_ROUTES.clear();
        CONTEXT_RULES.clear();
        CONTEXT_RULE_PATHS.clear();   // ⭐ 补清 context path 索引，防 case 间残留
        PAGE_RULES.clear();           // ⭐ 补清 page 级规则，防 case 间残留
        LITERAL_PATH_CACHE.clear();   // ⭐ 补清 literalPath 缓存，防 case 间残留（urlPattern 极少，重建代价可忽略）
        CROSS_LAYER_HANDLED_URLS.clear();
    }

    /**
     * 清空 Route 防重门控集合 + 跨层去重集合（测试结束时调用，释放已处理的 Route 引用）。
     */
    public static void clearDispatchedRoutes() {
        int dispatchedSize = DISPATCHED_ROUTES.size();
        int crossLayerSize = CROSS_LAYER_HANDLED_URLS.size();
        DISPATCHED_ROUTES.clear();
        CROSS_LAYER_HANDLED_URLS.clear();
        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[RouteEngine] clearDispatchedRoutes: cleared {} dispatched + {} cross-layer entries",
                dispatchedSize, crossLayerSize);
    }

    // ═══════════════════════════════════════════════════════════════
    // ⭐ 页面级规则重新注册（支持切换新页面时路由不丢失）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 将规则列表注册到指定的 Page。
     * <p>复用 {@link #register(Page, List)} 的完整注册逻辑（含优先级覆盖、同类型重注册等）。
     */
    private static void registerRulesToPage(Page page, List<RouteRule> rules) {
        if (page == null || rules == null || rules.isEmpty()) return;
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] registerRulesToPage: registering {} rule(s) on new page",
                rules.size());
        register(page, rules);
    }

    /**
     * ⭐ 跨页面重新注册路由规则 — 将旧页面注册的所有规则重新注册到新页面。
     *
     * <p>当测试切换新页面时（如 {@code BasePage.waitForNewPage()} 或弹出新 Tab），
     * 旧页面上的路由规则不会自动迁移到新页面。此方法查找旧页面的规则并在新页面上重新注册，
     * 确保 API 监控、Mock、Modify 等规则在新页面继续生效。
     *
     * <p>查找规则匹配规则：遍历 {@link #PAGE_RULES} 注册表，查找与 {@code oldPage} 关联的规则。
     * 若找到且新页面非空，则在新页面上重新注册。
     *
     * @param oldPage 旧页面（可能已关闭），用于查找其关联的规则
     * @param newPage 新页面，规则将注册到此页面上
     */
    public static void reRegisterRules(Page oldPage, Page newPage) {
        if (oldPage == null || newPage == null) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] reRegisterRules: skipped (oldPage={}, newPage={})",
                    oldPage, newPage);
            return;
        }
        if (oldPage == newPage) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] reRegisterRules: oldPage == newPage, no need to re-register");
            return;
        }

        PageRef key = new PageRef(oldPage);
        List<RouteRule> rules = PAGE_RULES.get(key);
        if (rules == null || rules.isEmpty()) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] reRegisterRules: no rules found for old page, skipped");
            return;
        }

        LOGGER.info("[RouteEngine] reRegisterRules: re-registering {} rule(s) from old page to new page",
                rules.size());
        registerRulesToPage(newPage, rules);

        // ⭐ 企业级生命周期：旧页面已销毁（或即将销毁），及时清理其静态路由状态，
        //   避免 PAGE_RULES / ENGINE_RULE_STORE 残留导致跨页面污染。
        //   规则已迁移到 newPage，此时移除 oldPage 的引用是安全的（不影响新页面）。
        //   RouteRegistry.clearContext(oldPage) 内部会注销 Playwright 路由、stop MonitorSession、
        //   清理 CONTEXT_RULES 与 DISPATCHED_ROUTES 等，做到及时清理。
        PAGE_RULES.remove(new PageRef(oldPage));
        ENGINE_RULE_STORE.remove(oldPage);
        RouteRegistry.clearContext(oldPage);
        purgeDeadPageRules();
        LOGGER.info("[RouteEngine] reRegisterRules: cleaned up old page state (PAGE_RULES left={})",
                PAGE_RULES.size());
    }

    /**
     * ⭐ 移除指定页面的规则缓存（测试结束时调用，防止跨测试用例污染）。
     *
     * @param page 要移除规则缓存的页面
     */
    public static void removePageRules(Page page) {
        if (page == null) return;
        PAGE_RULES.remove(new PageRef(page));
        ENGINE_RULE_STORE.remove(page);
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] removePageRules: removed rules for page, remaining entries: {}",
                PAGE_RULES.size());
    }

    /**
     * ⭐ 清理已失效的页面级规则引用（Page 被 GC 回收后清理）。
     */
    public static void purgeDeadPageRules() {
        int removed = 0;
        java.util.Iterator<Map.Entry<PageRef, List<RouteRule>>> it = PAGE_RULES.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getKey().isDead()) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.debug("[RouteEngine] Purged {} dead page rule entries", removed);
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
    // ─── 真实 URL → 已注册 urlPattern 映射（capture 目录存储端点解析） ────

    /**
     * ⭐ 将真实请求 URL 解析为「已注册规则中最具体的 urlPattern」，作为 capture 目录写入 CapturedApiCall 的 endpoint。
     *
     * <p>背景：capture 目录（CDP 旁路 + EventMerger）捕获的是真实网络流量，其 URL 含 host 前缀
     * （如 {@code http://localhost:8888/demo/api/users}），而 DSL/测试以 urlPattern（如 {@code /api/users}）
     * 作为查询 key。若直接用真实 path 存储，测试将无法通过 urlPattern 查询到数据。
     *
     * <p>因此：遍历所有已注册规则（CONTEXT + PAGE 两级），用「去除通配符后的字面路径」做 URL 子串匹配，
     * 返回<b>最长（最具体）</b>的匹配 urlPattern。无匹配时返回真实 URL 的 path。
     *
     * @param url 真实请求 URL（含 host）
     * @return 最具体的已注册 urlPattern，否则返回 URL 的 path
     */
    public static String resolveEndpointForUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        String best = null;
        int bestLen = -1;

        // CONTEXT 级规则
        for (Map.Entry<String, RouteRule> e : CONTEXT_RULES.entrySet()) {
            String p = e.getValue().getUrlPattern();
            String lit = literalPathOf(p);
            if (lit != null && url.contains(lit) && lit.length() > bestLen) {
                best = p;
                bestLen = lit.length();
            }
        }
        // PAGE 级规则
        for (Map.Entry<PageRef, List<RouteRule>> e : PAGE_RULES.entrySet()) {
            for (RouteRule r : e.getValue()) {
                String p = r.getUrlPattern();
                String lit = literalPathOf(p);
                if (lit != null && url.contains(lit) && lit.length() > bestLen) {
                    best = p;
                    bestLen = lit.length();
                }
            }
        }
        return best != null ? best : extractPathFromUrl(url);
    }

    /**
     * 提取 urlPattern 中「去除通配符后的字面前缀」。
     * <p>例：{@code /api/users} → {@code /api/users}；{@code /api/users/*} → {@code /api/users/}；
     *     {@code /api/**} → {@code /api/}。返回 null 表示无有效字面前缀。
     */
    // ⭐ 性能优化：urlPattern → literalPath 静态缓存。
    //   resolveEndpointForUrl / isSyncStoredRuleForUrl / resolveEndpointIfCovered 等在
    //   每个请求 / 每次 API 合并时高频调用 literalPathOf，重复做 startsWith/endsWith/indexOf/substring
    //   字符串计算。规则数少且相对稳定，用有上限的 ConcurrentHashMap 缓存结果，消除重复分配。
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
        if (LITERAL_PATH_CACHE.size() < LITERAL_PATH_CACHE_MAX) {
            LITERAL_PATH_CACHE.putIfAbsent(urlPattern, result);
        }
        return p.isEmpty() ? null : p;
    }

    /**
     * ⭐ 判断给定 URL 是否命中某个<b>会由 Handler 同步 storeApiCall 的规则</b>（MOCK / MONITOR）。
     *
     * <p>用途：MOCK 由 MockHandler、MONITOR 由 MonitorHandler 通过 {@code storeApiCall} <b>同步</b>写入
     * ApiCaptureContext（唯一存储，覆盖所有 Page 且即时可查）。CDP 旁路若再捕获这些请求并异步写出，
     * 会与同步存储<b>重复</b>（如 i26 期望 5 条实际 9 条）。因此 CDP 捕获前调用此方法，
     * 命中则跳过捕获，避免重复存储。
     *
     * @param url 真实请求 URL（含 host）
     * @return true = 命中 MOCK 或 MONITOR 规则（由 Handler 同步存储）
     */
    public static boolean isSyncStoredRuleForUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        boolean hasSyncStored = false;   // 命中「无 DELAY 的 MOCK/MONITOR」→ 由 Handler 同步存储
        // CONTEXT 级
        for (Map.Entry<String, RouteRule> e : CONTEXT_RULES.entrySet()) {
            RouteRule r = e.getValue();
            if (!urlCoveredByRule(url, r)) continue;
            // ⭐ 命中任一 DELAY 规则 → CDP 需捕获（Handler 无法在 DELAY 异步线程同步 storeApiCall，
            //    req.response() 异步失效，需 CDP 写入 ApiCaptureContext 供「DELAY 后 Monitor 从 ApiContext 断言」）。
            if (ruleHasDelay(r)) return false;
            if (r.getType() == RouteHandleType.MOCK || r.getType() == RouteHandleType.MONITOR) {
                hasSyncStored = true;
            }
        }
        // PAGE 级
        for (Map.Entry<PageRef, List<RouteRule>> e : PAGE_RULES.entrySet()) {
            for (RouteRule r : e.getValue()) {
                if (!urlCoveredByRule(url, r)) continue;
                if (ruleHasDelay(r)) return false;
                if (r.getType() == RouteHandleType.MOCK || r.getType() == RouteHandleType.MONITOR) {
                    hasSyncStored = true;
                }
            }
        }
        return hasSyncStored;
    }

    /** 判断 URL 是否命中任意带 DELAY 的规则。 */
    public static boolean hasDelayRuleForUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        for (RouteRule rule : CONTEXT_RULES.values()) {
            if (urlCoveredByRule(url, rule) && ruleHasDelay(rule)) return true;
        }
        for (List<RouteRule> rules : PAGE_RULES.values()) {
            for (RouteRule rule : rules) {
                if (urlCoveredByRule(url, rule) && ruleHasDelay(rule)) return true;
            }
        }
        return false;
    }

    /** 规则是否携带 DELAY（固定/随机延迟）。 */
    private static boolean ruleHasDelay(RouteRule rule) {
        return rule != null && (rule.getDelayMs() > 0
                || rule.getDelayMinMs() > 0 || rule.getDelayMaxMs() > 0);
    }

    /**
     * ⭐ 判断给定 URL 是否命中<b>任意</b>已注册规则（MOCK / MODIFY / DELAY / MONITOR，CONTEXT + PAGE 两级）。
     *
     * <p>用途：capture 目录（CDP 旁路 + EventMerger）采集的是真实网络流量，会把前端持续产生的
     * <b>无规则匹配的 API</b>（如高频 health check、动态新接口）也捕获。若全量写入 ApiCaptureContext，
     * 会导致内存无限增长、{@code getAllApiCalls} 膨胀。因此写入前调用此方法：
     * <b>只有命中已注册规则的 API 才存储</b>，无规则的 health check / 新 API 不写。
     *
     * <p>判断基于「URL 子串命中任意规则的字面路径」（与 {@link #resolveEndpointForUrl} 的映射一致），
     * 同时校验规则的请求条件（resourceType/method/headers 等），确保与 {@code requestMatches} 语义一致。
     *
     * @param url     真实请求 URL（含 host）
     * @param method  请求方法（可空）
     * @return true = 命中任意已注册规则（应存储）；false = 无规则匹配（不存储）
     */
    /**
     * ⭐ 判断给定 URL 是否命中任意已注册规则，并返回命中的 urlPattern（endpoint）。
     *
     * <p>返回 {@code null} 表示<b>不命中任何规则</b>（capture 目录应跳过，不存储）。
     * 返回非 null 表示命中，返回值即该 URL 应使用的 endpoint（最精确匹配的 urlPattern）。
     *
     * <p>⭐ 性能优化：此方法<b>一次遍历</b>同时完成「是否覆盖」判断与「endpoint 解析」，
     *   capture 目录（EventMerger）无需再单独调用 {@link #resolveEndpointForUrl} 重复遍历，
     *   消除了每请求对同一份规则集的双重 O(n) 遍历。
     *
     * @param url 真实请求 URL（含 host）
     * @return 命中的 urlPattern；无规则命中时返回 {@code null}
     */
    /** 是否存在可用于 API 覆盖过滤的上下文或页面规则。 */
    public static boolean hasCaptureRules() {
        return !CONTEXT_RULES.isEmpty() || !PAGE_RULES.isEmpty();
    }

    public static String resolveEndpointIfCovered(String url) {
        return resolveEndpointIfCovered(url, null);
    }

    /** 按请求所属 Page 解析 endpoint；传入 Page 可避免扫描其它 Page 规则。 */
    public static String resolveEndpointIfCovered(String url, Page page) {
        if (url == null || url.isEmpty()) return null;
        if (!matchesCaptureUrlScope(url)) return null;
        if (CONTEXT_RULES.isEmpty() && PAGE_RULES.isEmpty()) return null;
        String best = null;
        int bestLen = -1;
        // CONTEXT 级（最精确优先）
        for (Map.Entry<String, RouteRule> e : CONTEXT_RULES.entrySet()) {
            RouteRule r = e.getValue();
            String lit = literalPathOf(r.getUrlPattern());
            if (lit != null && url.contains(lit) && lit.length() > bestLen) {
                best = r.getUrlPattern();
                bestLen = lit.length();
            }
        }
        // PAGE 级
        for (Map.Entry<PageRef, List<RouteRule>> entry : PAGE_RULES.entrySet()) {
            if (page != null && entry.getKey().get() != page) continue;
            if (page == null && entry.getKey().isDead()) continue;
            for (RouteRule r : entry.getValue()) {
                String lit = literalPathOf(r.getUrlPattern());
                if (lit != null && url.contains(lit) && lit.length() > bestLen) {
                    best = r.getUrlPattern();
                    bestLen = lit.length();
                }
            }
        }
        return best;
    }

    /** 单个规则是否覆盖给定 URL（字面路径子串命中）。 */
    private static boolean urlCoveredByRule(String url, RouteRule rule) {
        if (rule == null) return false;
        String lit = literalPathOf(rule.getUrlPattern());
        return lit != null && url.contains(lit);
    }

    private static boolean matchesCaptureUrlScope(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String baseUrl = captureBaseUrl;
            if (baseUrl != null) {
                java.net.URI base = java.net.URI.create(baseUrl);
                if (!java.util.Objects.equals(base.getScheme(), uri.getScheme())
                        || !java.util.Objects.equals(base.getHost(), uri.getHost())
                        || effectivePort(base) != effectivePort(uri)) {
                    return false;
                }
            }
            String basePath = captureBasePath;
            if (basePath != null) {
                String path = uri.getPath();
                if (path == null || !(path.equals(basePath) || path.startsWith(basePath + "/"))) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return captureBaseUrl == null && captureBasePath == null;
        }
    }

    private static int effectivePort(java.net.URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String normalizeCaptureBaseUrl(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static String normalizeCaptureBasePath(String value) {
        if (value == null || value.trim().isEmpty() || "/".equals(value.trim())) return null;
        String normalized = value.trim();
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static String crossLayerKey(Route route, String url) {
        try {
            if (route != null && route.request() != null
                    && route.request().frame() != null
                    && route.request().frame().page() != null) {
                BrowserContext context = route.request().frame().page().context();
                return System.identityHashCode(context) + "|" + url;
            }
        } catch (Exception ignored) {
            // Page/Context 已关闭时回退：用 route 自身 identityHash 作 key，避免所有异常路径共用
            // 同一个 "legacy|url" 造成跨 route / 跨 case 的串扰（相同 URL 短时间互相 skip）。
            // route 对象本身在 dispatchRoute 生命周期内仍可达，取其 identityHash 即可保证 key 唯一性。
        }
        return "route_" + System.identityHashCode(route) + "|" + url;
    }

    private static void purgeExpiredCrossLayerEntries() {
        long cutoff = System.currentTimeMillis() - CROSS_LAYER_DEDUP_TTL_MS;
        CROSS_LAYER_HANDLED_URLS.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private static Map<String, RouteRule> contextRulesFor(Route route) {
        try {
            if (route != null && route.request() != null && route.request().frame() != null
                    && route.request().frame().page() != null) {
                Map<String, RouteRule> scoped = CONTEXT_RULES_BY_CONTEXT.get(
                        route.request().frame().page().context());
                if (scoped != null) return scoped;
            }
        } catch (Exception ignored) {
            // Page/Context 已关闭，使用兼容全局规则。
        }
        return CONTEXT_RULES;
    }

    private static Set<String> contextCandidatePatterns(String url, Map<String, RouteRule> rules) {
        Set<String> candidates = new HashSet<>(CONTEXT_RULE_FALLBACK_KEYS);
        if (rules != CONTEXT_RULES) {
            candidates.clear();
            for (String pattern : rules.keySet()) {
                String path = CONTEXT_RULE_PATHS.get(pattern);
                if (path == null || path.isEmpty() || url.contains(path)) candidates.add(pattern);
            }
        }
        String path = extractPathFromUrl(url);
        if (path == null) return candidates;
        // 只沿当前 URL 的路径边界查找索引，不再扫描全部 prefix key。
        int boundary = path.length();
        while (boundary > 0) {
            Set<String> indexed = CONTEXT_RULE_KEYS_BY_PREFIX.get(path.substring(0, boundary));
            if (indexed != null) candidates.addAll(indexed);
            boundary = path.lastIndexOf('/', boundary - 1);
        }
        return candidates;
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
        Map<String, RouteRule> scoped = CONTEXT_RULES_BY_CONTEXT.remove(context);
        if (scoped == null) return;
        for (String pattern : patterns) {
            RouteRule ownerRule = scoped.remove(pattern);
            if (ownerRule != null) {
                // 仅当全局兼容索引仍指向本 Context 的规则时才删除，避免误删其它 Context。
                CONTEXT_RULES.remove(pattern, ownerRule);
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
            }
        }
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] Removed {} context rules for context, remaining: {}",
                patterns.size(), CONTEXT_RULES.size());
    }

    /** 兼容旧调用：仅用于无 Context 归属信息的全局清理场景。 */
    public static void removeContextRules(Set<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return;
        CONTEXT_RULES.keySet().removeAll(patterns);
        CONTEXT_RULE_PATHS.keySet().removeAll(patterns);
        CONTEXT_RULE_KEYS_BY_PREFIX.values().forEach(keys -> keys.removeAll(patterns));
        CONTEXT_RULE_FALLBACK_KEYS.removeAll(patterns);
    }

    /**
     * ⭐ 清理指定上下文在 {@link #ENGINE_RULE_STORE} 中的合并引用（clearContext 时调用），
     * 避免跨场景残留导致下次注册时错误地 mergeFrom 旧规则。
     *
     * @param context Page 或 BrowserContext 实例
     */
    public static void removeEngineRuleStore(Object context) {
        ENGINE_RULE_STORE.remove(context);
    }

    /** ⭐ 全局清理 {@link #ENGINE_RULE_STORE}（测试套件结束时调用）。 */
    public static void clearAllEngineRuleStores() {
        ENGINE_RULE_STORE.clear();
    }

    // ─── MonitorSession（内部类）───────────────────────────────────

    /**
     * ⭐ 停止匹配指定 pattern 的所有旧 MonitorSession。
     *
     * <p>遍历 SESSIONS 查找 MonitorSession.pattern 匹配的旧 session 并停止。
     * 解决同类型重注册时 SESSIONS.get(newRule) 因 modifyMethod 差异导致
     * RouteRule.equals() 不匹配而无法找到旧 session 的问题。
     *
     * @param normalizedPattern 注册时使用的归一化 pattern
     */
    private static void stopOldSessionsForPattern(String normalizedPattern) {
        SESSIONS.entrySet().removeIf(entry -> {
            if (normalizedPattern.equals(entry.getValue().pattern)) {
                entry.getValue().stop();
                LOGGER.debug("[RouteEngine] Stopped old session for pattern '{}'", normalizedPattern);
                return true;
            }
            return false;
        });
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
            timeoutFutureRef.set(SCHEDULER.schedule(this::onTimeout, timeoutMs, TimeUnit.MILLISECONDS));
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

    /**
     * 将 MOCK/MODIFY 路由事件投喂到采集管道。
     *
     * <p>在 {@link #executeHandler} 中 handler 完成后调用。
     * 采集引擎的 CDP 旁路已覆盖普通 API 请求，
     * 此方法仅补充 MOCK（CDP 看不到响应）和 MODIFY（route.fetch 独立请求）的事件。
     */
    private static void feedCaptureEvent(Route route, RouteRule rule, Request req) {
        CaptureEngine engine = null;
        try {
            if (req != null && req.frame() != null && req.frame().page() != null) {
                engine = PAGE_CAPTURE_ENGINES.get(req.frame().page());
            }
        } catch (Exception ignored) {
            // Page 已销毁或请求归属不可用时，不得回退到其它 Page 的全局引擎。
        }
        if (engine == null || !engine.isRunning()) return;

        try {
            // 使用 URL + method + 时间戳 合成 requestId，避免 req.toString() 非唯一问题
            String requestId = "route-" + req.url() + "-" + req.method() + "-" + System.nanoTime();
            CaptureEvent event;

            switch (rule.getType()) {
                case MOCK:
                    // ⭐ MOCK 存储由 MockHandler 同步 storeApiCall（route.fulfill 不发真实网络请求，
                    //    且测试常无 awaitCompletion 直接查询，异步投喂存在写出竞态）。
                    //    此处不再投喂 mockFull，避免与 Handler 同步存储重复。
                    break;

                case MODIFY:
                    // MODIFY 的请求体由 ModifyHandler 修改后通过 route.fetch() 发送，
                    // CDP 旁路会捕获该 fetch 子请求 → 投喂 FETCH_REQUEST 标记
                    event = CaptureEvent.fetchRequest(
                            requestId,
                            req.method(),
                            req.url(),
                            req.headers(),
                            req.postData() != null ? req.postData().getBytes(java.nio.charset.StandardCharsets.UTF_8) : null
                    );
                    engine.feedRouteEvent(event);
                    break;

                default:
                    // MONITOR/DELAY 不在此处理
                    break;
            }
        } catch (Exception e) {
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[RouteEngine] feedCaptureEvent error: {}", e.getMessage());
        }
    }
}
