package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture.CaptureEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.MockHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.MonitorHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.DelayHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SensitiveDataSanitizer;
import com.hsbc.cmb.hk.dbb.automation.framework.common.async.AsyncPool;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
     * ⭐ 引擎规则引用存储：context/page 对象 → (归一化 pattern → 规则链 List&lt;RouteRule&gt;)。
     *
     * <p>B3 链式模型（对齐 Playwright Router.RouteInfo）：同 pattern 多次注册不再就地
     * {@link RouteRule#mergeFrom} 共享可变对象，而是追加到链尾（后注册优先）。
     * 分发时对链执行<b>分发期合并</b>（copyForMerge + 依次 mergeFrom）生成一次性有效规则，
     * 用后即弃——消除「注册期落库」导致的状态污染、equals/hashCode 漂移与集合无限累积。
     *
     * <p>合并语义（详见 {@link RouteRule#mergeFrom}）：
     * MONITOR 基线不可被关、MODIFY 字段 putAll、DELAY 取 max、MOCK 终结（仅可覆盖非 MOCK，不可被降级）。
     */
    private static final Map<PageRef, Map<String, List<RouteRule>>> ENGINE_RULE_STORE = new ConcurrentHashMap<>();

    /** Handler 注册表：类型 → 处理器 */
    private static final Map<RouteHandleType, RouteHandler> HANDLERS = new EnumMap<>(RouteHandleType.class);

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

    /** DISPATCHED_ROUTES 路由放入时间戳（用于 TTL 惰性过期，与分桶 Map 解耦） */
    private static final Map<Route, Long> DISPATCHED_ROUTE_TS = new ConcurrentHashMap<>();

    /** DISPATCHED_ROUTES 单 context 容量上限，超过后自动清空该桶（防御性保护） */
    private static final int MAX_DISPATCHED_ROUTES_PER_CONTEXT = 500;

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
    /**
     * ⭐ 修复 1.3：跨层去重 TTL。原 5s 短于可配置的 DELAY 延迟（可配 10s+），
     * 导致 context handler 在 5s 后误判"未处理"重复处理同一请求。改为默认 30s，且可由
     * 环境变量 ROUTE_CROSS_LAYER_DEDUP_TTL_MS 覆盖，确保不小于最大 DELAY 延迟。
     */
    private static final long CROSS_LAYER_DEDUP_TTL_MS =
            RouteUtil.getEnvLong("ROUTE_CROSS_LAYER_DEDUP_TTL_MS", 30_000L);
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
        DISPATCHED_ROUTE_TS.clear();
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

        // 超时调度已委托通用异步池 AsyncPool，由其在 JVM 关闭钩子/显式 shutdown 时统一关闭
        LOGGER.info("[RouteEngine] All schedulers shut down");
    }

    /**
     * 以只读快照查询响应事件对应的 Monitor 规则。优先 Page 级规则，再查 Context 级规则。
     * 不向事件处理链暴露可变 RouteRule，未命中或规则非 Monitor 时返回 null。
     */
    public static com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor.MonitorRuleSnapshot
    findMonitorRuleSnapshot(Page page, String url, String method) {
        if (page == null || url == null) return null;
        RouteRule pageRule = findMatchingRule(ENGINE_RULE_STORE.get(page), url, method);
        if (pageRule != null && pageRule.isMonitorEnabled() && pageRule.getType() != RouteHandleType.MOCK) {
            return com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor.MonitorRuleSnapshot.from(pageRule);
        }
        BrowserContext context;
        try {
            context = page.context();
        } catch (Exception ignored) {
            return null;
        }
        RouteRule contextRule = findMatchingRule(CONTEXT_RULES_BY_CONTEXT.get(context), url, method);
        if (contextRule != null && contextRule.isMonitorEnabled() && contextRule.getType() != RouteHandleType.MOCK) {
            return com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor.MonitorRuleSnapshot.from(contextRule);
        }
        return null;
    }

    private static RouteRule findMatchingRule(Map<String, List<RouteRule>> rules, String url, String method) {
        if (rules == null || rules.isEmpty()) return null;
        List<RouteRule> bestChain = null;
        int bestSpecificity = -1;
        // ⭐ B3：Map 值类型为规则链（List<RouteRule>），glob 以链头 pattern 匹配（链头 = 源规则，
        //    条件字段归属），命中后返回该链的分发期合并有效规则（只读快照，绝不暴露可变链）。
        for (Map.Entry<String, List<RouteRule>> e : rules.entrySet()) {
            List<RouteRule> chain = e.getValue();
            if (chain == null || chain.isEmpty()) continue;
            RouteRule head = chain.get(0);
            if (head == null || head.getUrlPattern() == null || !globMatches(head.getUrlPattern(), url)) continue;
            String matchMethod = head.getMatchMethod();
            if (matchMethod != null && method != null && !matchMethod.equalsIgnoreCase(method)) continue;
            int specificity = head.getUrlPattern().replace("*", "").length();
            if (specificity > bestSpecificity) {
                bestSpecificity = specificity;
                bestChain = chain;
            }
        }
        if (bestChain == null) return null;
        return resolveChain(bestChain);
    }

    private static boolean globMatches(String pattern, String value) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^?]*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.append('$').toString()).matcher(value).matches();
    }

    /**
     * 注册路由规则到 Page。
     */
    public static void register(Page page, List<RouteRule> rules) {
        LoggingConfigUtil.logDebugIfVerbose(LOGGER, "[RouteEngine] ── Registering {} rule(s) on Page ──", rules.size());

        // ⭐ 存储页面级规则，供切换新页面时重新注册
        PAGE_RULES.put(new PageRef(page), new java.util.ArrayList<>(rules));

        registerInternal(page, (pattern, rule) -> {
            Map<String, List<RouteRule>> store = ENGINE_RULE_STORE.computeIfAbsent(new PageRef(page), k -> new ConcurrentHashMap<>());
            // ⭐ 仅在锁内完成 store 的纯内存状态决策，绝不持有锁调用原生 page.route()（JNI/网络 IO）。
            //    原生注册放在锁外执行，避免高并发注册/异常时持锁做 IO 导致线程长时间阻塞甚至重入风险。
            final boolean[] needRegister = {false};
            final boolean[] needRefresh = {false};
            // ⭐ 用 AtomicReference 而非泛型数组：new List[1] 会产生「未经检查的转换」警告
            //    （泛型数组在 Java 中无法安全创建）。此处仅作锁内→锁外的单值传递，无并发需求。
            final AtomicReference<List<RouteRule>> chainRef = new AtomicReference<>();
            synchronized (store) {
                List<RouteRule> chain = store.get(pattern);
                if (chain == null) {
                    // 首次注册：建链 + 绑定闭包（闭包捕获链引用，后续追加自动可见）
                    chain = new CopyOnWriteArrayList<>();
                    store.put(pattern, chain);
                    // ⭐ 仅作清理记录（RouteRegistry 不再用于优先级决策，此处保留供 clearContext 反查 pattern）
                    RouteRegistry.forceRegister(page, pattern, rule.getType());
                    needRegister[0] = true;
                }
                chain.add(rule);
                chainRef.set(chain);
                // ⭐ 追加规则携带 MONITOR 能力时，锁外刷新（「先 modify 后追加 monitor」逆序场景）
                // ⭐ 判定只看能力位 isMonitorEnabled()，不再看 type：type 的 MONITOR 是构造器默认值，
                //    对 modify/delay 规则同样成立，用它判定会给纯 modify/delay 规则创建永不被
                //    计数的多余 MonitorSession（配了 timeout 时更会误报超时失败）。
                if (rule.isMonitorEnabled()) {
                    needRefresh[0] = true;
                }
            }
            // 锁外执行原生路由注册（JNI）与 MonitorSession 刷新，不阻塞其它线程对 store 的访问
            if (needRegister[0]) {
                try {
                    registerRouteToPage(page, pattern, chainRef.get(), rule);
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
                refreshMonitorSession(page, pattern, chain.get(0), chainHasMonitor);
            }
        }, rules);
    }

    /**
     * 注册 Playwright 路由到 Page（实际 route + session 创建）。
     */
    private static void registerRouteToPage(Page page, String pattern, List<RouteRule> chain, RouteRule rule) {
        // ⭐ B3：闭包捕获规则链引用（而非单个 rule）——同 pattern 后续追加自动可见，分发期合并
        page.route(pattern, route -> dispatchRoute(route, chain));
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
     * @param chain 规则链（ENGINE_RULE_STORE / CONTEXT_RULES 中 pattern 对应的 List）
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
            RouteUtil.resumeIfOpen(route);
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

        // ═══ 跨层级去重：若 page handler 已通过 cross-layer merge 处理了此 URL，
        //     context handler 不应重复拦截（Page/Context Route 对象不同，DISPATCHED_ROUTES 无法去重）═══
        purgeExpiredCrossLayerEntries();
        String crossLayerKey = crossLayerKey(route, reqUrl);
        Long handledAt = CROSS_LAYER_HANDLED_URLS.remove(crossLayerKey);
        if (handledAt != null && System.currentTimeMillis() - handledAt <= CROSS_LAYER_DEDUP_TTL_MS) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] ═══ dispatchRoute SKIP (cross-layer dedup): URL already handled by page-level merge, " +
                    "pattern='{}', url='{}' ═══", rule.getUrlPattern(), reqUrl);
            RouteUtil.safeResume(route);
            return;
        }

        // ⭐ 修复 P0-1：防重门控按 context 分桶
        BrowserContext dispatchCtx = contextOf(route);
        Set<Route> bucket = dispatchCtx != null ? DISPATCHED_ROUTES.computeIfAbsent(dispatchCtx, k -> ConcurrentHashMap.newKeySet()) : null;

        // ═══ 防御性清理：单 context 桶超过上限时清空（防止异常情况下无限增长）═══
        if (bucket != null && bucket.size() >= MAX_DISPATCHED_ROUTES_PER_CONTEXT) {
            LOGGER.warn("[RouteEngine] DISPATCHED_ROUTES bucket reached {} entries for context, clearing to prevent memory leak",
                    bucket.size());
            bucket.clear();
        } else if (bucket != null && !bucket.isEmpty()) {
            // ⭐ 惰性 TTL 过期：避免异常场景（未调用 clearDispatchedRoutes）下
            //    Route 强引用长期常驻造成内存泄漏。每次 dispatch 顺带清理过期条目。
            long now = System.currentTimeMillis();
            bucket.removeIf(r -> now - DISPATCHED_ROUTES_TTL_MS > 0 && DISPATCHED_ROUTE_TS.getOrDefault(r, 0L) < now - DISPATCHED_ROUTES_TTL_MS);
        }

        // ═══ 防重门控：同一请求只处理一次（按 context 隔离，TTL 自动过期释放引用）═══
        if (bucket != null) {
            if (!bucket.add(route)) {
                LOGGER.warn("[RouteEngine] Route already handled by another pattern, skipping '{}' for URL '{}'",
                        rule.getUrlPattern(), reqUrl);
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteEngine] ═══ dispatchRoute SKIPPED (duplicate): pattern='{}', url='{}' ═══",
                        rule.getUrlPattern(), reqUrl);
                return;
            }
            DISPATCHED_ROUTE_TS.put(route, System.currentTimeMillis());
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

        // ═══ B3 同层链合并：同 pattern 多条规则 → 分发期合并为一次性有效规则 ═══
        // 完全复用跨层合并的 copyForMerge 模式（绝不就地修改 ENGINE_RULE_STORE/闭包持有的
        // 原始规则）：链头为基础，依次 mergeFrom（后注册覆盖），MOCK 终结提升。
        // mergeSource 指向链头——session 查询 / times 递减 / 跨层 identity 始终作用于源规则。
        if (chain.size() > 1) {
            RouteRule effective = resolveChain(chain);
            delayMs = effective.getDelayMs(); // 链合并后 DELAY 取 max，同步局部变量
            rule = effective;
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] Same-layer chain merged: {} rule(s) on pattern='{}', effectiveType={}, effectiveDelay={}ms",
                    chain.size(), effective.getUrlPattern(), effective.getType(), delayMs);
        }

        // ⭐ 跨层合并是否真正发生（非 ctxRule != null，因为 session 可能已停止）
        boolean crossLayerDelayMerged = false;

        // ⭐ 仅 page handler 做跨层合并，context handler 跳过（避免在 CONTEXT_RULES 中自引用）。
        //    注意：用「实例同一性（==）」而非 equals 判断，因为新模型下同 pattern 的
        //    page 规则与 context 规则 urlPattern 相同、equals 相等，若用 equals 会把
        //    page 规则误判为 context 规则而跳过跨层合并（n50 回归）。
        //    ⭐ 性能优化：把「判断 rule 是否即 context 规则」与「查找匹配 ctxRule」合并为<b>一次遍历</b>，
        //    消除原先 isContextRule 全量扫描 + findMatchingContextRule 全量扫描的两段 O(n)。
        RouteRule ctxRule = null;
        List<RouteRule> ctxChain = null;
        boolean isContextRule = false;
        // ⭐ 企业级「精确优先」：同时命中多个 context 规则时，取<b>最精确</b>（字面路径最长）的一条，
        //    而非第一个。例：ctx 层有 /api/users 与 /api/users/1，URL=/api/users/1 → 取 /api/users/1。
        //    ⭐ B3：ctx 每条 pattern 对应规则链（List<RouteRule>），identity 判断需遍历链（用 == 而非 equals，
        //    同 pattern 规则 equals 相等会把 page 规则误判为 context 规则）。
        int bestCtxPathLen = -1;
        Map<String, List<RouteRule>> ctxRulesMap = contextRulesFor(route);
        for (String pattern : contextCandidatePatterns(reqUrl, ctxRulesMap)) {
            List<RouteRule> crList = ctxRulesMap.get(pattern);
            if (crList == null || crList.isEmpty()) continue;
            // 若当前 handler 的链头 rule 就是 context 链中的规则 → 它是 context handler，跳过跨层合并
            for (RouteRule cr : crList) {
                if (cr == rule) {
                    isContextRule = true;
                    break;
                }
            }
            if (isContextRule) break;
            // 记录匹配 reqUrl 且字面路径最长的 context 链（复用 CONTEXT_RULE_PATHS 预缓存 path）
            String path = CONTEXT_RULE_PATHS.get(pattern);
            if (path != null && !path.isEmpty() && reqUrl.contains(path) && path.length() > bestCtxPathLen) {
                bestCtxPathLen = path.length();
                ctxRule = crList.get(0); // 链头：条件/session/times 归属（与 page 链语义一致）
                ctxChain = crList;       // 整链：跨层合并时对 ctx 层做分发期合并
            }
        }
        if (isContextRule) ctxChain = null;

        if (ctxChain != null) {
            // ⭐ 若 context 规则的 MonitorSession 已停止（超时/auto-stop），
            //    则忽略跨层合并 — 已停止的规则不应影响 page handler 行为
            MonitorSession ctxSession = sessionForRoute(route, ctxRule);
            if (ctxSession != null && ctxSession.stopped.get()) {
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteEngine] Context rule session stopped, skip cross-layer merge: ctxPattern='{}', url='{}'",
                        ctxRule.getUrlPattern(), reqUrl);
                // fall through — ctxChain 视为不存在，直接走 page handler
            } else {
                // ⭐ 标记此 URL — context handler 到达时必须跳过（所有类型均适用，含相同类型）
                if (CROSS_LAYER_HANDLED_URLS.size() >= MAX_CROSS_LAYER_DEDUP_ENTRIES) {
                    CROSS_LAYER_HANDLED_URLS.clear();
                }
                CROSS_LAYER_HANDLED_URLS.put(crossLayerKey(route, reqUrl), System.currentTimeMillis());

                // ⭐ B3：ctx 层链合并为一次性有效规则（与 page 层 resolveChain 完全一致的模型）
                RouteRule ctxRuleEffective = resolveChain(ctxChain);
                RouteHandleType pageType = rule.getType();
                RouteHandleType ctxType = ctxRuleEffective.getType();

                // ── 延迟合并（n51 / n53 / contextMonitorAndPageDelay）：ctx 层 delay 始终保留；
                //    page 层 delay 仅当 ctx 为「终结者（MOCK）」时丢弃 ──
                // • ctx 层的 delay 是「全局延迟配置」，无条件合并；
                // • DELAY 与 type 正交：只要 ctx 规则不终结响应（非 MOCK，即 MONITOR/MODIFY 放行真实响应），
                //   page 层 delay 必须对真实响应生效；仅当 ctx=MOCK（直接 fulfill 假响应，无真实响应可延迟）
                //   时才丢弃 page 层 delay。原逻辑按「type 优先级」判定（MONITOR=1 > DELAY=0）会错误丢弃
                //   page 层 delay，导致 contextMonitorAndPageDelay 用例中 page delay 完全不生效。
                // • 二者取 max。
                // 例：n51 page=DELAY + ctx=MOCK → ctx 终结 → pageDelay=0 → MOCK 立即返回；
                //     n53 ctx=DELAY(500) + page=MOCK → ctx 非 MOCK 但 page 为 MOCK 终结，
                //          pageDelay=0，ctxDelay=500 → 延迟 500ms；
                //     contextMonitorAndPageDelay ctx=MONITOR + page=DELAY → ctx 非 MOCK → pageDelay 保留。
                long ctxDelay = DelayHandler.clampDelay(DelayHandler.resolveDelay(ctxRuleEffective));
                boolean ctxTerminates = (ctxType == RouteHandleType.MOCK);
                long pageDelay = ctxTerminates ? 0 : DelayHandler.clampDelay(DelayHandler.resolveDelay(rule));
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
                //    ⭐ B3：page 层 rule 若已是同层链合并拷贝（chain.size()>1）则直接复用，
                //    仅当为原始链头（独立规则）时才 copyForMerge，避免双重拷贝。
                RouteRule effectiveRule = chain.size() > 1 ? rule : rule.copyForMerge();
                effectiveRule.mergeFrom(ctxRuleEffective);
                if (pageType == RouteHandleType.MOCK || ctxType == RouteHandleType.MOCK) {
                    effectiveRule.setType(RouteHandleType.MOCK);
                } else {
                    effectiveRule.setType(pageType); // 保持 page 主 type，能力位 OR
                }
                // 有效规则替换原 rule，后续主管线用 effectiveRule 执行（含跨层合并的全部能力位）
                rule = effectiveRule;
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteEngine] Cross-layer capability merged: ctxPattern='{}', pagePattern='{}', effectiveDelay={}ms",
                        ctxRuleEffective.getUrlPattern(), effectiveRule.getUrlPattern(), delayMs);
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
                unmarkDispatched(route);
            }
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

        // exactly-one 契约：延迟回调只会执行一次 resume/fulfill，防止 Firefox/WebKit 下
        // route 已销毁导致 resume 抛 "Object doesn't exist" 后又被 catch 二次 resume（0次或2次）。
        AtomicBoolean resumed = new AtomicBoolean(false);
        Runnable action = () -> {
            try {
                // 延迟期间页面/上下文可能已被关闭，抵达时直接放行，避免对已销毁页面操作报错
                if (RouteUtil.isPageClosed(route)) {
                    LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                            "[RouteEngine] Page already closed during delay, skip resume for '{}'", pattern);
                    RouteUtil.safeResume(route);
                    return;
                }
                // 检查会话是否已被停止（auto-stop / 超时）
                MonitorSession session = sessionForRoute(route, rule);
                if (session != null && session.stopped.get()) {
                    LOGGER.debug("[RouteEngine] Session stopped during delay, skipping for '{}'", pattern);
                    RouteUtil.safeResume(route);
                    return;
                }

                // 无论后续落库是否成功，route 此刻必须被放行且仅一次
                RouteUtil.safeResume(route);
                if (!resumed.compareAndSet(false, true)) {
                    LOGGER.warn("[RouteEngine] DELAY resume invoked more than once for '{}' (guarded)", pattern);
                    return;
                }
                LOGGER.info("[RouteEngine] Route delayed: pattern='{}', url='{}', delay={}ms",
                        pattern, url, delayMs);

                // 将 DELAY 调用存入 ApiCaptureContext，与 MONITOR/MOCK 统一可查询
                storeDelayCall(route, rule);

                onMonitorMatch(rule);

                // ═══ times 一次性拦截（对齐 Playwright setTimes）：DELAY 成功放行后递减 ═══
                // ⭐ B3：times 作用于源规则（分发期合并拷贝不携带 times）
                RouteRule sourceRule = rule.getMergeSource();
                if (sourceRule.getTimes() > 0) {
                    sourceRule.decrementTimes();
                    LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                            "[RouteEngine] times decremented (delay): pattern='{}', exhausted={}",
                            sourceRule.getUrlPattern(), sourceRule.isTimesExhausted());
                }
            } catch (Exception e) {
                LOGGER.error("[RouteEngine] Failed to continue route after delay for '{}': {}",
                        pattern, e.getMessage(), e);
                // exactly-one：catch 中仅在尚未 resume 时补一次放行，避免 0 次或 2 次
                if (resumed.compareAndSet(false, true)) {
                    RouteUtil.safeResume(route);
                }
            } finally {
                unmarkDispatched(route);
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
            MonitorSession session = sessionForRoute(route, rule);
            if (session != null && session.stopped.get()) {
                LOGGER.debug("[RouteEngine] Session stopped during delay, skipping handler for '{}'",
                        rule.getUrlPattern());
                RouteUtil.safeResume(route);
                return;
            }
            executeHandler(route, rule, handler);
        } catch (Exception e) {
            LOGGER.error("[RouteEngine] Scheduled handler failed for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage(), e);
            RouteUtil.safeResume(route);
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
                    rule.getType(), rule.getUrlPattern(), SensitiveDataSanitizer.sanitizeUrl(req.url()));
            handler.handle(route, rule);

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
    /** 事件处理链按 Page/URL/Method 回调 Monitor 匹配；实际可变 Rule 仅在引擎内部解析。 */
    public static void onMonitorMatch(Page page, String url, String method) {
        if (page == null || url == null) return;
        RouteRule rule = findMatchingRule(ENGINE_RULE_STORE.get(page), url, method);
        if (rule == null) {
            try {
                rule = findMatchingRule(CONTEXT_RULES_BY_CONTEXT.get(page.context()), url, method);
            } catch (Exception ignored) {
                return;
            }
        }
        if (rule != null && rule.isMonitorEnabled()) onMonitorMatch(rule);
    }

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
                "[RouteEngine] clearAllMonitorSessions: stopping {} session(s), clearing {} dispatched routes, {} context rules, {} cross-layer handled urls",
                SESSIONS.size(), DISPATCHED_ROUTES.size(), contextRuleCount(), CROSS_LAYER_HANDLED_URLS.size());
        for (MonitorSession session : SESSIONS.values()) {
            session.stop();
        }
        SESSIONS.clear();
        DISPATCHED_ROUTES.clear();
        DISPATCHED_ROUTE_TS.clear();
        CONTEXT_RULE_PATHS.clear();   // ⭐ 补清 context path 索引，防 case 间残留
        PAGE_RULES.clear();           // ⭐ 补清 page 级规则，防 case 间残留
        LITERAL_PATH_CACHE.clear();   // ⭐ 补清 literalPath 缓存，防 case 间残留（urlPattern 极少，重建代价可忽略）
        CROSS_LAYER_HANDLED_URLS.clear();
    }

    /**
     * ⭐ 修复 P0-1：按 Context 精确清理防重门控（Context 关闭时调用，不影响其它 Context）。
     * 仅移除该 context 桶内的 Route 引用及其时间戳，避免并行测试下全局清空误杀其它 Context。
     */
    public static void clearDispatchedRoutes(BrowserContext context) {
        if (context == null) return;
        Set<Route> bucket = DISPATCHED_ROUTES.remove(context);
        if (bucket != null) {
            for (Route r : bucket) {
                DISPATCHED_ROUTE_TS.remove(r);
            }
        }
        // 跨层去重按 context identityHash 隔离，清理属于该 context 的条目
        CROSS_LAYER_HANDLED_URLS.entrySet().removeIf(e -> {
            String key = e.getKey();
            return key != null && key.startsWith(System.identityHashCode(context) + "|");
        });
        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[RouteEngine] clearDispatchedRoutes(context): cleared bucket for context {}",
                System.identityHashCode(context));
    }

    /**
     * 清空 Route 防重门控集合 + 跨层去重集合（全量，shutdown / clearAll / resetAll 时调用，释放已处理的 Route 引用）。
     */
    public static void clearDispatchedRoutes() {
        int dispatchedSize = DISPATCHED_ROUTES.size();
        int crossLayerSize = CROSS_LAYER_HANDLED_URLS.size();
        DISPATCHED_ROUTES.clear();
        DISPATCHED_ROUTE_TS.clear();
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
        ENGINE_RULE_STORE.remove(new PageRef(oldPage));
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
        // ⭐ 逐链 clear 而非仅移除条目：page 上的路由闭包捕获的是 chain 引用（见 detachChains）
        detachChains(ENGINE_RULE_STORE.remove(page));
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] removePageRules: removed rules for page, remaining entries: {}",
                PAGE_RULES.size());
    }

    /**
     * ⭐ 清理已失效的页面级规则引用（Page 被 GC 回收后清理）。
     */
    public static void purgeDeadPageRules() {
        int removed = 0;
        // 修复：PAGE_RULES 是 ConcurrentHashMap，entrySet 迭代器 .remove() 在结构变更时会抛
        // IllegalStateException。改为先收集 dead key，再逐个 remove(key)（原子且并发安全）。
        java.util.List<PageRef> deadKeys = new java.util.ArrayList<>();
        for (java.util.Map.Entry<PageRef, List<RouteRule>> e : PAGE_RULES.entrySet()) {
            if (e.getKey().isDead()) deadKeys.add(e.getKey());
        }
        for (PageRef key : deadKeys) {
            PAGE_RULES.remove(key);
            removed++;
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

        // CONTEXT 级规则（B3：值类型为规则链，取链头 urlPattern 即可）
        for (Map<String, List<RouteRule>> scoped : CONTEXT_RULES_BY_CONTEXT.values()) {
            for (Map.Entry<String, List<RouteRule>> e : scoped.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            String p = e.getValue().get(0).getUrlPattern();
            String lit = literalPathOf(p);
            if (lit != null && url.contains(lit) && lit.length() > bestLen) {
                best = p;
                bestLen = lit.length();
            }
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
     * ⭐ 判断给定 URL 是否命中某个<b>会由 Handler 同步 storeApiCall 的规则</b>（MOCK / MONITOR / MODIFY）。
     *
     * <p>用途：MOCK 由 MockHandler、MONITOR 由 MonitorHandler、MODIFY 由 ModifyHandler 通过
     * {@code storeApiCall} <b>同步</b>写入 ApiCaptureContext（唯一存储，覆盖所有 Page 且即时可查）。
     * CDP/事件采集旁路若再捕获这些请求并异步写出，会与同步存储<b>重复</b>（如 i26 期望 5 条实际 9 条；
     * c21 的 modifyDetail 被重复落库的 null 条阴影覆盖）。因此采集前调用此方法，命中则跳过捕获。
     *
     * <p><b>DELAY 叠加处理：</b>当同 pattern 链中同时存在 DELAY 与某个同步存储规则（MOCK/MONITOR/MODIFY，
     * 含 modify+delay 叠加）时，Handler 仍会在延迟后同步落库，故 CDP 旁路<b>跳过</b>；仅当命中
     * 「纯 DELAY」（无任何同步存储规则，Handler 不在响应期落库）时才强制 CDP 捕获，供水线后断言从
     * ApiCaptureContext 读取。
     *
     * @param url 真实请求 URL（含 host）
     * @return true = 命中 MOCK / MONITOR / MODIFY 规则（由 Handler 同步存储，旁路应跳过）
     */
    public static boolean isSyncStoredRuleForUrl(String url) {
        return isSyncStoredRuleForUrl(url, null);
    }

    /** 按请求所属 Page 检查同步存储规则，避免扫描其它 BrowserContext 的兼容全局索引。 */
    public static boolean isSyncStoredRuleForUrl(String url, Page page) {
        if (url == null || url.isEmpty()) return false;
        boolean hasSyncStored = false;   // 命中「MOCK/MONITOR/MODIFY」→ 由 Handler 同步存储
        boolean hasDelay = false;        // 仅「纯 DELAY」(无同步存储规则) 时才需 CDP 旁路捕获
        // CONTEXT 级（B3：值类型为规则链，逐链遍历全部节点）
        Map<String, List<RouteRule>> contextRules = contextRulesFor(page);
        for (List<RouteRule> chain : contextRules.values()) {
            for (RouteRule r : chain) {
                if (!urlCoveredByRule(url, r)) continue;
                if (ruleHasDelay(r)) hasDelay = true;
                // ⭐ MODIFY 必须一并计入：ModifyHandler 拿到 route.fetch() 的真实响应后
                //    同步 ctx.storeApiCall(...)，与 MOCK/MONITOR 同属「Handler 同步存储」；
                //    且 MODIFY 可与 DELAY 叠加（modify+delay overlay），其 handle 在延迟后调度，
                //    仍同步落库并携带 modifyDetail，故不应被 CDP 重复捕获覆盖。
                if (r.getType() == RouteHandleType.MOCK
                        || r.getType() == RouteHandleType.MONITOR
                        || r.getType() == RouteHandleType.MODIFY) {
                    hasSyncStored = true;
                }
            }
        }
        // PAGE 级
        for (Map.Entry<PageRef, List<RouteRule>> e : PAGE_RULES.entrySet()) {
            if (page != null && e.getKey().get() != page) continue;
            if (page == null && e.getKey().isDead()) continue;
            for (RouteRule r : e.getValue()) {
                if (!urlCoveredByRule(url, r)) continue;
                if (ruleHasDelay(r)) hasDelay = true;
                if (r.getType() == RouteHandleType.MOCK
                        || r.getType() == RouteHandleType.MONITOR
                        || r.getType() == RouteHandleType.MODIFY) {
                    hasSyncStored = true;
                }
            }
        }
        // ⭐ 修复 c21（monitor+modify+delay 同 pattern 叠加）：
        //   原逻辑「命中任一 DELAY 立即 return false 强制 CDP 捕获」会导致——即便同链已存在
        //   MODIFY/MONITOR/MOCK 的同步落库，CDP 仍对 /api/users 再写一条 modifyDetail=null 的
        //   CapturedApiCall，被 getLastApiCall 阴影覆盖，使 c21 断言 modifyDetail.headersSet 为 null。
        //   现改为：仅当「纯 DELAY」(hasDelay 且 !hasSyncStored，Handler 不在响应期同步存储) 才强制
        //   CDP 捕获供水线后断言从 ApiContext 读取；一旦同链已有 Handler 同步落库，则跳过 CDP，
        //   避免重复落库覆盖 modifyDetail。
        if (hasDelay && !hasSyncStored) {
            return false;
        }
        return hasSyncStored;
    }

    /** 判断 URL 是否命中任意带 DELAY 的规则。 */
    public static boolean hasDelayRuleForUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        for (Map<String, List<RouteRule>> scoped : CONTEXT_RULES_BY_CONTEXT.values()) {
            for (List<RouteRule> chain : scoped.values()) {
            for (RouteRule rule : chain) {
                if (urlCoveredByRule(url, rule) && ruleHasDelay(rule)) return true;
            }
            }
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
        return hasContextRules() || !PAGE_RULES.isEmpty();
    }

    /** 是否存在任意 Context 级规则。 */
    private static boolean hasContextRules() {
        for (Map<String, List<RouteRule>> scoped : CONTEXT_RULES_BY_CONTEXT.values()) {
            if (!scoped.isEmpty()) return true;
        }
        return false;
    }

    /** 统计所有 Context 级规则总数（用于日志）。 */
    private static int contextRuleCount() {
        int count = 0;
        for (Map<String, List<RouteRule>> scoped : CONTEXT_RULES_BY_CONTEXT.values()) {
            count += scoped.size();
        }
        return count;
    }

    /** 按 Page 检查规则覆盖，避免采集器因其它 Context 的规则而错误过滤本请求。 */
    public static boolean hasCaptureRules(Page page) {
        if (!contextRulesFor(page).isEmpty()) return true;
        if (page == null) return !PAGE_RULES.isEmpty();
        for (PageRef ref : PAGE_RULES.keySet()) {
            if (ref.get() == page) return true;
        }
        return false;
    }

    public static String resolveEndpointIfCovered(String url) {
        return resolveEndpointIfCovered(url, null);
    }

    /** 按请求所属 Page 解析 endpoint；传入 Page 可避免扫描其它 Page 规则。 */
    public static String resolveEndpointIfCovered(String url, Page page) {
        if (url == null || url.isEmpty()) return null;
        if (!matchesCaptureUrlScope(url)) return null;
        if (!hasContextRules() && PAGE_RULES.isEmpty()) return null;
        String best = null;
        int bestLen = -1;
        // CONTEXT 级（最精确优先）（B3：值类型为规则链，取链头 urlPattern 即可）
        if (page == null) {
            // 无 Page 调用（向后兼容旧 API）：遍历所有 Context 的规则
            for (Map<String, List<RouteRule>> scoped : CONTEXT_RULES_BY_CONTEXT.values()) {
                for (List<RouteRule> chain : scoped.values()) {
                    if (chain.isEmpty()) continue;
                    RouteRule r = chain.get(0);
                    String lit = literalPathOf(r.getUrlPattern());
                    if (lit != null && url.contains(lit) && lit.length() > bestLen) {
                        best = r.getUrlPattern();
                        bestLen = lit.length();
                    }
                }
            }
        } else {
            for (List<RouteRule> chain : contextRulesFor(page).values()) {
                if (chain.isEmpty()) continue;
                RouteRule r = chain.get(0);
                String lit = literalPathOf(r.getUrlPattern());
                if (lit != null && url.contains(lit) && lit.length() > bestLen) {
                    best = r.getUrlPattern();
                    bestLen = lit.length();
                }
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

    /** 防重门控释放：从所属 context 桶中移除 route，并清理时间戳（修复 P0-1 分桶） */
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
        DISPATCHED_ROUTE_TS.remove(route);
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

    private static Map<String, List<RouteRule>> contextRulesFor(Page page) {
        try {
            if (page != null) {
                Map<String, List<RouteRule>> scoped = CONTEXT_RULES_BY_CONTEXT.get(page.context());
                // Page 仍可用时，空 scoped 集合表示此 Context 没有规则；绝不能回退到其它 Context 的兼容索引。
                return scoped == null ? Map.of() : scoped;
            }
        } catch (Exception ignored) {
            // Page/Context 已关闭，保留无 Page 旧调用的兼容回退。
        }
        return Map.of();
    }

    private static Map<String, List<RouteRule>> contextRulesFor(Route route) {
        try {
            if (route != null && route.request() != null && route.request().frame() != null
                    && route.request().frame().page() != null) {
                Map<String, List<RouteRule>> scoped = CONTEXT_RULES_BY_CONTEXT.get(
                        route.request().frame().page().context());
                if (scoped != null) return scoped;
            }
        } catch (Exception ignored) {
            // Page/Context 已关闭，使用兼容全局规则。
        }
        return Map.of();
    }

    private static Set<String> contextCandidatePatterns(String url, Map<String, List<RouteRule>> rules) {
        Set<String> candidates = new HashSet<>(CONTEXT_RULE_FALLBACK_KEYS);
        if (!rules.isEmpty()) {
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

    /** 兼容旧调用：仅用于无 Context 归属信息的全局清理场景。 */
    public static void removeContextRules(Set<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return;
        for (Map<String, List<RouteRule>> scoped : CONTEXT_RULES_BY_CONTEXT.values()) {
            scoped.keySet().removeAll(patterns);
        }
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
        if (context instanceof PageRef) {
            detachChains(ENGINE_RULE_STORE.remove(context));
        }
        // BrowserContext 的断链已由 removeContextRules 就地 clear 完成，无需在此处理
    }

    /**
     * ⭐ 断开「已注册的 Playwright 路由闭包」与规则链的关联 —— 就地清空链内容。
     *
     * <p><b>为什么仅移除 Map 条目不够</b>：注册时执行的是
     * {@code page.route(pattern, route -> dispatchRoute(route, chain))}，
     * 闭包<b>直接捕获 chain 这个 List 对象引用</b>，而框架刻意不调用 {@code page.unroute()}
     * （规避 Playwright 线程竞态，见 MonitorSession.stop 注释）。
     * 因此清理时只做 {@code ENGINE_RULE_STORE.remove/clear} 的话，原生路由仍然挂在 page 上、
     * 闭包持有的 chain 仍然非空 → {@code dispatchRoute} 取 {@code chain.get(0)} 继续按旧规则
     * mock/modify/断言，跨用例污染，且旧 RouteRule 无法被 GC。
     *
     * <p>就地 {@code chain.clear()} 后，闭包再被触发时 {@code dispatchRoute} 命中
     * 「empty rule chain」分支直接 {@code resumeIfOpen} 放行 —— 这正是既有的兜底语义。
     */
    private static void detachChains(Map<String, List<RouteRule>> store) {
        if (store == null || store.isEmpty()) return;
        for (List<RouteRule> chain : store.values()) {
            if (chain != null) chain.clear();
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
        removeEngineRuleStore(context);
    }

    /** ⭐ 全局清理 {@link #ENGINE_RULE_STORE}（测试套件结束时调用）。 */
    public static void clearAllEngineRuleStores() {
        // ⭐ 必须逐链 clear（见 detachChains 注释）：否则 page 上仍挂着的路由闭包
        //    继续持有非空 chain，clearAllRules() 后旧规则照样生效。
        for (Map<String, List<RouteRule>> store : ENGINE_RULE_STORE.values()) {
            detachChains(store);
        }
        ENGINE_RULE_STORE.clear();
        // ⭐ 补清 context 级索引：clearAllMonitorSessions 只清了 CONTEXT_RULES / CONTEXT_RULE_PATHS，
        //    以下三个索引此前无任何全局清理出口，会跨用例累积（且强引用 BrowserContext）。
        //    context 桶已收敛至 CONTEXT_RULES_BY_CONTEXT，须逐链 clear（见 detachChains 注释），
        //    否则 BrowserContext 上挂着的路由闭包仍持有非空 chain，跨用例污染。
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

    /**
     * 获取指定 Page 已绑定的采集引擎（若有且仍在运行）。
     *
     * <p>供 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.MonitorHandler}
     * 在具备 body 能力（Chromium CDP）时做事件链接管的可选路径；调用方必须自行处理缺失与超时回退。
     */
    public static CaptureEngine getCaptureEngine(Page page) {
        if (page == null) return null;
        CaptureEngine engine = PAGE_CAPTURE_ENGINES.get(page);
        return (engine != null && engine.isRunning()) ? engine : null;
    }
}
