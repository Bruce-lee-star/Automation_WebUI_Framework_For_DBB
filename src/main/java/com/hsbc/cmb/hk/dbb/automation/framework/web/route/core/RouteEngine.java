package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.MockHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.MonitorHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
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

    /** Handler 注册表：类型 → 处理器 */
    private static final Map<RouteHandleType, RouteHandler> HANDLERS = new EnumMap<>(RouteHandleType.class);

    /** Monitor 会话注册表：RouteRule → MonitorSession */
    private static final Map<RouteRule, MonitorSession> SESSIONS = new ConcurrentHashMap<>();

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
    private static final Set<Route> DISPATCHED_ROUTES = ConcurrentHashMap.newKeySet();

    /** DISPATCHED_ROUTES 容量上限，超过后自动清空（防御性保护） */
    private static final int MAX_DISPATCHED_ROUTES = 500;

    /** 超时调度器（守护线程，避免阻塞 JVM 退出） */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "route-monitor-timeout");
                t.setDaemon(true);
                return t;
            });

    static {
        HANDLERS.put(RouteHandleType.MONITOR, MonitorHandler::handle);
        HANDLERS.put(RouteHandleType.MODIFY, ModifyHandler::handle);
        HANDLERS.put(RouteHandleType.MOCK, MockHandler::handle);
    }

    /**
     * 注册路由规则到 Page。
     */
    public static void register(Page page, List<RouteRule> rules) {
        registerInternal(page, (pattern, rule) -> {
            if (RouteRegistry.register(page, pattern)) {
                page.route(pattern, route -> dispatchRoute(route, rule));
                startMonitorSession(page, rule);
            }
        }, rules);
    }

    /**
     * 注册路由规则到 BrowserContext。
     */
    public static void register(BrowserContext context, List<RouteRule> rules) {
        registerInternal(context, (pattern, rule) -> {
            if (RouteRegistry.register(context, pattern)) {
                context.route(pattern, route -> dispatchRoute(route, rule));
                startMonitorSession(context, rule);
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
                    LOGGER.warn("[RouteEngine] Skipping rule with empty urlPattern");
                    continue;
                }
                // 归一化：补齐前后 **，与 Playwright 全 URL（含查询参数）匹配兼容
                // 例：/api/users/1 → **/api/users/1** 可匹配 http://host:port/api/users/1?page=2
                String normalized = pattern.startsWith("/") ? "**" + pattern : pattern;
                if (!normalized.endsWith("**")) {
                    normalized = TRAILING_WILDCARDS.matcher(normalized).replaceFirst("") + "**";
                }
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
     */
    private static void dispatchRoute(Route route, RouteRule rule) {
        // ═══ 防御性清理：Map 超过上限时清空（防止异常情况下无限增长）═══
        if (DISPATCHED_ROUTES.size() >= MAX_DISPATCHED_ROUTES) {
            LOGGER.warn("[RouteEngine] DISPATCHED_ROUTES reached {} entries, clearing to prevent memory leak",
                    DISPATCHED_ROUTES.size());
            DISPATCHED_ROUTES.clear();
        }

        // ═══ 防重门控：同一请求只处理一次 ═══
        if (!DISPATCHED_ROUTES.add(route)) {
            LOGGER.warn("[RouteEngine] Route already handled by another pattern, skipping '{}' for URL '{}'",
                    rule.getUrlPattern(), route.request().url());
            return;
        }

        // ═══ 请求条件匹配：根据 Rule 中配置的 ResourceType/Header/Query/Body 等过滤 ═══
        if (!RouteUtil.requestMatches(route, rule)) {
            // 不匹配此规则 → 移除防重标记，让 Playwright 继续尝试下一个 pattern
            DISPATCHED_ROUTES.remove(route);
            route.resume();
            return;
        }

        RouteHandler handler = HANDLERS.get(rule.getType());
        if (handler == null) {
            LOGGER.warn("[RouteEngine] Unknown RouteHandleType: {}, fallback to resume for pattern '{}'",
                    rule.getType(), rule.getUrlPattern());
            try {
                route.resume();
            } catch (Exception e) {
                LOGGER.error("[RouteEngine] Failed to resume route (fallback) for pattern '{}': {}",
                        rule.getUrlPattern(), e.getMessage(), e);
            }
            return;
        }

        try {
            handler.handle(route, rule);

            // MOCK/MODIFY 处理成功后触发匹配计数（支持一次性拦截 / auto-stop）
            // MONITOR 的匹配计数在 MonitorHandler 异步完成时回调，不在此处触发
            if (rule.getType() != RouteHandleType.MONITOR) {
                onMonitorMatch(rule);
            }
        } catch (Exception e) {
            LOGGER.error("[RouteEngine] Handler '{}' threw exception for pattern '{}': {}",
                    handler.getClass().getSimpleName(), rule.getUrlPattern(), e.getMessage(), e);
            try {
                route.resume();
            } catch (Exception resumeEx) {
                LOGGER.error("[RouteEngine] Failed to resume route after handler error: {}", resumeEx.getMessage());
            }
        }
    }

    /**
     * 注册器（内部函数式接口）。
     */
    @FunctionalInterface
    private interface RouteRegistrar {
        void register(String pattern, RouteRule rule);
    }

    // ─── Monitor 自动停止 ────────────────────────────────────────

    /**
     * 为路由规则创建自动停止会话（如果需要的话）。
     *
     * <p>适用范围：MONITOR / MOCK / MODIFY 三种类型。
     * <p>条件：(timeoutMs > 0 或 autoStopOnMatch == true)
     */
    private static void startMonitorSession(Object context, RouteRule rule) {
        if (rule.getTimeoutMs() <= 0 && !rule.isAutoStopOnMatch()) {
            return;  // 无限监控/拦截，无需会话
        }

        String pattern = rule.getUrlPattern();
        MonitorSession session = new MonitorSession(context, pattern, rule);
        SESSIONS.put(rule, session);

        if (rule.getTimeoutMs() > 0) {
            session.scheduleTimeout();
        }

        LOGGER.debug("[RouteEngine] MonitorSession started: pattern='{}', timeout={}ms, minMatches={}, autoStop={}",
                pattern, rule.getTimeoutMs(), rule.getMinMatches(), rule.isAutoStopOnMatch());
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
            return;
        }

        int currentCount = session.matchCount.incrementAndGet();
        LOGGER.debug("[RouteEngine] Monitor match #{}/{} for pattern '{}'",
                currentCount, rule.getMinMatches(), rule.getUrlPattern());

        if (rule.isAutoStopOnMatch() && currentCount >= rule.getMinMatches()) {
            LOGGER.info("[RouteEngine] Auto-stopping monitor (matches={}) for pattern '{}'",
                    currentCount, rule.getUrlPattern());
            session.stop();
        }
    }

    /**
     * 清理指定上下文的全部 MonitorSession（RouteRegistry.clearContext 时同步调用）。
     */
    public static void clearMonitorSessions(Object context) {
        SESSIONS.entrySet().removeIf(entry -> {
            if (entry.getValue().context == context) {
                entry.getValue().stop();
                return true;
            }
            return false;
        });
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
        for (MonitorSession session : SESSIONS.values()) {
            session.stop();
        }
        SESSIONS.clear();
        DISPATCHED_ROUTES.clear();
    }

    /**
     * 清空 Route 防重门控集合（测试结束时调用，释放已处理的 Route 引用）。
     */
    public static void clearDispatchedRoutes() {
        DISPATCHED_ROUTES.clear();
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
        ScheduledFuture<?> timeoutFuture;

        MonitorSession(Object context, String pattern, RouteRule rule) {
            this.context = context;
            this.pattern = pattern;
            this.rule = rule;
        }

        void scheduleTimeout() {
            long timeoutMs = rule.getTimeoutMs();
            this.timeoutFuture = SCHEDULER.schedule(this::onTimeout, timeoutMs, TimeUnit.MILLISECONDS);
        }

        void onTimeout() {
            if (!stopped.get()) {
                LOGGER.info("[RouteEngine] Monitor timeout ({}ms) for pattern '{}', stopping",
                        rule.getTimeoutMs(), pattern);
                stop();
            }
        }

        /**
         * 停止监控：取消超时任务、注销 Playwright 路由、移除会话。
         */
        void stop() {
            if (!stopped.compareAndSet(false, true)) {
                return;  // 已停止（CAS 防重复）
            }

            // 取消超时任务
            if (timeoutFuture != null && !timeoutFuture.isDone()) {
                timeoutFuture.cancel(false);
            }

            // 注销 Playwright 路由
            try {
                if (context instanceof Page) {
                    ((Page) context).unroute(pattern);
                } else if (context instanceof BrowserContext) {
                    ((BrowserContext) context).unroute(pattern);
                }
                RouteRegistry.unregister(context, pattern);
            } catch (Exception e) {
                LOGGER.warn("[RouteEngine] Failed to unroute pattern '{}': {}", pattern, e.getMessage());
            }

            // 移除会话
            SESSIONS.remove(rule);

            LOGGER.debug("[RouteEngine] MonitorSession stopped: pattern='{}', totalMatches={}",
                    pattern, matchCount.get());
        }
    }
}
