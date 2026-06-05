package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

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

    /** 网络延迟调度器（多线程池，支持并发请求同时延迟） */
    private static final ScheduledExecutorService DELAY_SCHEDULER =
            Executors.newScheduledThreadPool(4, r -> {
                Thread t = new Thread(r, "route-network-delay");
                t.setDaemon(true);
                return t;
            });

    static {
        HANDLERS.put(RouteHandleType.MONITOR, MonitorHandler::handle);
        HANDLERS.put(RouteHandleType.MODIFY, ModifyHandler::handle);
        HANDLERS.put(RouteHandleType.MOCK, MockHandler::handle);
        // DELAY 类型不在此注册 — 由 dispatchRoute 直接调度，无需经过 Handler 接口
    }

    /**
     * 注册路由规则到 Page。
     */
    public static void register(Page page, List<RouteRule> rules) {
        LoggingConfigUtil.logDebugIfVerbose(LOGGER, "[RouteEngine] ── Registering {} rule(s) on Page ──", rules.size());
        registerInternal(page, (pattern, rule) -> {
            RouteHandleType type = rule.getType();
            if (RouteRegistry.register(page, pattern, type)) {
                registerRouteToPage(page, pattern, rule);
            } else if (RouteRegistry.shouldOverride(page, pattern, type)) {
                // ⭐ 优先级覆盖：高优先级规则覆盖低优先级（如 MOCK 覆盖 MONITOR）
                RouteHandleType oldType = RouteRegistry.getRegisteredType(page, pattern);
                LOGGER.info("[RouteEngine] Overriding pattern '{}': {} → {} on Page", pattern, oldType, type);
                page.unroute(pattern);
                RouteRegistry.forceRegister(page, pattern, type);
                registerRouteToPage(page, pattern, rule);
            } else {
                LOGGER.debug("[RouteEngine] Skipping pattern '{}' (already registered as {}, new is {} — same or lower priority)",
                        pattern, RouteRegistry.getRegisteredType(page, pattern), type);
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
            RouteHandleType type = rule.getType();
            if (RouteRegistry.register(context, pattern, type)) {
                registerRouteToContext(context, pattern, rule);
            } else if (RouteRegistry.shouldOverride(context, pattern, type)) {
                // ⭐ 优先级覆盖：高优先级规则覆盖低优先级（如 MOCK 覆盖 MONITOR）
                RouteHandleType oldType = RouteRegistry.getRegisteredType(context, pattern);
                LOGGER.info("[RouteEngine] Overriding pattern '{}': {} → {} on BrowserContext", pattern, oldType, type);
                context.unroute(pattern);
                RouteRegistry.forceRegister(context, pattern, type);
                registerRouteToContext(context, pattern, rule);
            } else {
                LOGGER.debug("[RouteEngine] Skipping pattern '{}' (already registered as {}, new is {} — same or lower priority)",
                        pattern, RouteRegistry.getRegisteredType(context, pattern), type);
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
        // ⭐ #1 性能优化：缓存 route.request() JNI 调用，避免多次跨语言桥接
        Request req = route.request();
        String reqUrl = req.url();
        String reqMethod = req.method();
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] ═══ dispatchRoute START: method={}, url='{}', type={}, pattern='{}' ═══",
                reqMethod, reqUrl, rule.getType(), rule.getUrlPattern());

        // ═══ 防御性清理：Map 超过上限时清空（防止异常情况下无限增长）═══
        if (DISPATCHED_ROUTES.size() >= MAX_DISPATCHED_ROUTES) {
            LOGGER.warn("[RouteEngine] DISPATCHED_ROUTES reached {} entries, clearing to prevent memory leak",
                    DISPATCHED_ROUTES.size());
            DISPATCHED_ROUTES.clear();
        }

        // ═══ 防重门控：同一请求只处理一次 ═══
        if (!DISPATCHED_ROUTES.add(route)) {
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
        // 优先级：MOCK > MODIFY > DELAY > MONITOR
        long delayMs = rule.getDelayMs();
        RouteRule ctxRule = findMatchingContextRule(reqUrl);

        if (ctxRule != null && ctxRule.getType() != rule.getType()) {
            RouteHandleType ctxType = ctxRule.getType();
            RouteHandleType pageType = rule.getType();

            // ── ∀ 跨层级组合：始终合并 DELAY ──
            if (ctxType == RouteHandleType.DELAY) {
                long ctxDelay = DelayHandler.clampDelay(DelayHandler.resolveDelay(ctxRule));
                delayMs = Math.max(delayMs, ctxDelay);
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteEngine] Context DELAY merged: pageType={}, ctxDelay={}ms, effectiveDelay={}ms",
                        pageType, ctxDelay, delayMs);
            }

            // ⭐ 快照合并后的延迟（此后 delayMs 不再修改，确保 lambda 中 effectively-final）
            final long mergedDelay = delayMs;

            // ── DELAY > MONITOR：context DELAY 覆盖 page MONITOR → 纯延迟放行，不监控 ──
            if (ctxType == RouteHandleType.DELAY && pageType == RouteHandleType.MONITOR) {
                LOGGER.info("[RouteEngine] Context DELAY overrides page MONITOR: pattern='{}', url='{}', delay={}ms",
                        rule.getUrlPattern(), reqUrl, mergedDelay);
                ApiCaptureContext.getCurrent().incrementActiveRequests();
                Runnable action = () -> {
                    try {
                        route.resume();
                        LOGGER.info("[RouteEngine] Route delayed (DELAY override MONITOR): pattern='{}', url='{}', delay={}ms",
                                rule.getUrlPattern(), reqUrl, mergedDelay);
                    } catch (Exception e) {
                        LOGGER.error("[RouteEngine] Failed to resume after DELAY override for '{}': {}",
                                rule.getUrlPattern(), e.getMessage(), e);
                        try { route.resume(); } catch (Exception ignored) {}
                    } finally {
                        DISPATCHED_ROUTES.remove(route);
                        ApiCaptureContext.getCurrent().decrementActiveRequests();
                    }
                };
                if (mergedDelay > 0) {
                    DELAY_SCHEDULER.schedule(action, mergedDelay, TimeUnit.MILLISECONDS);
                } else {
                    action.run();
                }
                return;
            }

            // ── MOCK 终结：context MOCK 覆盖所有 page handler ──
            // ⭐ 当 page handler 为 DELAY 时，page DELAY 的延迟不应被 MOCK 继承，
            //    MOCK 立即返回 mock 响应（仅合并 context 级别延迟）。
            if (ctxType == RouteHandleType.MOCK) {
                long mockDelay = (pageType == RouteHandleType.DELAY) ? 0 : mergedDelay;
                LOGGER.info("[RouteEngine] Context MOCK overrides page {}: pattern='{}', url='{}'",
                        pageType, ctxRule.getUrlPattern(), reqUrl);
                if (mockDelay > 0) {
                    DELAY_SCHEDULER.schedule(
                            () -> executeHandlerScheduled(route, ctxRule, MockHandler::handle),
                            mockDelay, TimeUnit.MILLISECONDS);
                } else {
                    executeHandler(route, ctxRule, MockHandler::handle);
                }
                return;
            }

            // ── MONITOR + MODIFY：page MODIFY 执行（已存 CapturedApiCall），
            //     context MONITOR 额外记录基础捕获 ──
            if (ctxType == RouteHandleType.MONITOR && pageType == RouteHandleType.MODIFY) {
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteEngine] Context MONITOR + page MODIFY: page modify runs, context monitor records basic capture");
            }

            // ── MODIFY + MONITOR / MODIFY + DELAY：
            //    context MODIFY 应修改请求后再执行 page handler
            //    目前 page handler 执行，delay 合并（完整 MODIFY 链需后续提取修改逻辑）
            if (ctxType == RouteHandleType.MODIFY) {
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteEngine] Context MODIFY + page {}: page handler runs with merged delay, "
                        + "context modify not applied (requires handler refactor for full chaining)",
                        pageType);
            }

            // 更新 delayMs 快照（后续 DELAY/MONITOR/MOCK/MODIFY handler 执行前统一使用）
            delayMs = mergedDelay;
        }

        // ═══ DELAY 类型：使用 schedule() 延迟调度 route.resume()，不占线程 ═══
        // 必须在 HANDLERS.get() 之前检查，因为 DELAY 已从 HANDLERS 中移除
        // 注意：上面的跨层级检查已将 context MONITOR→page DELAY 转为 MonitorHandler，此处仅处理纯 DELAY
        if (rule.getType() == RouteHandleType.DELAY) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] ═══ dispatchRoute DELAY: scheduling for pattern='{}', url='{}' ═══",
                    rule.getUrlPattern(), reqUrl);
            scheduleDelay(route, rule);
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
            } finally {
                DISPATCHED_ROUTES.remove(route);
            }
            return;
        }

        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] ═══ dispatchRoute -> handler: type={}, handler={}, pattern='{}', url='{}' ═══",
                rule.getType(), handler.getClass().getSimpleName(), rule.getUrlPattern(), reqUrl);

        // ═══ 执行 page handler（带合并后的延迟，delayMs 已由上面的跨层级合并计算好） ═══
        final long effectiveDelay = delayMs;
        if (effectiveDelay > 0) {
            DELAY_SCHEDULER.schedule(
                    () -> executeHandlerScheduled(route, rule, handler),
                    effectiveDelay, TimeUnit.MILLISECONDS);
            LOGGER.debug("[RouteEngine] Handler delayed by {}ms for pattern '{}'", effectiveDelay, rule.getUrlPattern());
        } else {
            executeHandler(route, rule, handler);
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
     * @param route Playwright 路由对象
     * @param rule  路由规则（含延迟配置）
     */
    private static void scheduleDelay(Route route, RouteRule rule) {
        long delayMs = DelayHandler.clampDelay(DelayHandler.resolveDelay(rule));

        // ⭐ #1 性能优化：缓存 route.request()
        Request req = route.request();
        String url = req.url();
        String pattern = rule.getUrlPattern();

        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] scheduleDelay: pattern='{}', url='{}', delay={}ms, minDelay={}ms, maxDelay={}ms",
                pattern, url, delayMs, rule.getDelayMinMs(), rule.getDelayMaxMs());

        // ═══ 标记活动请求，使 awaitCompletion 等待 DELAY 动作完成 ═══
        // increment 在 dispatch 线程执行，decrement 在调度器回调线程执行，
        // ApiCaptureContext 使用 AtomicInteger 保证跨线程安全。
        ApiCaptureContext.getCurrent().incrementActiveRequests();

        Runnable action = () -> {
            try {
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
                ApiCaptureContext.getCurrent().decrementActiveRequests();
            }
        };

        if (delayMs > 0) {
            DELAY_SCHEDULER.schedule(action, delayMs, TimeUnit.MILLISECONDS);
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
            ApiCaptureContext.getCurrent().storeApiCall(call);
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
                    "[RouteEngine] executeHandler START: handler={}, type={}, pattern='{}', url='{}'",
                    handler.getClass().getSimpleName(), rule.getType(),
                    rule.getUrlPattern(), req.url());
            handler.handle(route, rule);

            LOGGER.info("[RouteEngine] Route matched: type={}, pattern='{}', method={}, url='{}'",
                    rule.getType(), rule.getUrlPattern(),
                    req.method(), req.url());

            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] executeHandler DONE: handler={}, type={}, pattern='{}'",
                    handler.getClass().getSimpleName(), rule.getType(), rule.getUrlPattern());

            // MOCK/MODIFY/DELAY 处理成功后触发匹配计数（支持一次性拦截 / auto-stop）
            // MONITOR 的匹配计数在 MonitorHandler 异步完成时回调，不在此处触发
            if (rule.getType() != RouteHandleType.MONITOR) {
                onMonitorMatch(rule);
            }
        } catch (RouteException.ApiAssertionException e) {
            // ⭐⭐⭐ MonitorHandler 同步断言失败 — 测试线程已被 signalFailFast() 中断
            LOGGER.error("[RouteEngine] API assertion FAILED for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage());
            // 路由已被 MonitorHandler.resume() 放行，无需额外处理
            // ApiAssertionException 不在此处继续传播（Playwright 内部捕获），
            // 但主测试线程已被 interrupt，当前阻塞的 Playwright 操作将立即失败
        } catch (Exception e) {
            LOGGER.error("[RouteEngine] Handler '{}' threw exception for pattern '{}': {}",
                    handler.getClass().getSimpleName(), rule.getUrlPattern(), e.getMessage(), e);
            try {
                route.resume();
            } catch (Exception resumeEx) {
                LOGGER.error("[RouteEngine] Failed to resume route after handler error: {}", resumeEx.getMessage());
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
        SESSIONS.put(rule, session);

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
                "[RouteEngine] clearAllMonitorSessions: stopping {} session(s), clearing {} dispatched routes, {} context rules",
                SESSIONS.size(), DISPATCHED_ROUTES.size(), CONTEXT_RULES.size());
        for (MonitorSession session : SESSIONS.values()) {
            session.stop();
        }
        SESSIONS.clear();
        DISPATCHED_ROUTES.clear();
        CONTEXT_RULES.clear();
    }

    /**
     * 清空 Route 防重门控集合（测试结束时调用，释放已处理的 Route 引用）。
     */
    public static void clearDispatchedRoutes() {
        int size = DISPATCHED_ROUTES.size();
        DISPATCHED_ROUTES.clear();
        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[RouteEngine] clearDispatchedRoutes: cleared {} entries", size);
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
    static RouteRule findMatchingContextRule(String url) {
        if (url == null || CONTEXT_RULES.isEmpty()) return null;
        for (Map.Entry<String, RouteRule> entry : CONTEXT_RULES.entrySet()) {
            String normalized = entry.getKey();
            // 提取路径子串：**/path/** → /path
            String path = extractPathFromNormalizedPattern(normalized);
            if (!path.isEmpty() && url.contains(path)) {
                return entry.getValue();
            }
        }
        return null;
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
    public static void removeContextRules(Set<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return;
        CONTEXT_RULES.keySet().removeAll(patterns);
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteEngine] Removed {} context rules, remaining: {}",
                patterns.size(), CONTEXT_RULES.size());
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

            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[RouteEngine] MonitorSession.stop() START: pattern='{}', totalMatches={}, timeoutFuture={}",
                    pattern, matchCount.get(), timeoutFuture != null && !timeoutFuture.isDone());

            // 取消超时任务
            if (timeoutFuture != null && !timeoutFuture.isDone()) {
                timeoutFuture.cancel(false);
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
}
