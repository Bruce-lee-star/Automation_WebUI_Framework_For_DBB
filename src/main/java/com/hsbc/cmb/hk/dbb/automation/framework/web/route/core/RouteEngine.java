package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.MockHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.MonitorHandler;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** Handler 注册表：类型 → 处理器 */
    private static final Map<RouteHandleType, RouteHandler> HANDLERS = new EnumMap<>(RouteHandleType.class);

    /** Monitor 会话注册表：RouteRule → MonitorSession */
    private static final Map<RouteRule, MonitorSession> SESSIONS = new ConcurrentHashMap<>();

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
                startMonitorSession(page, pattern, rule);
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
                startMonitorSession(context, pattern, rule);
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
                registrar.register(pattern, rule);
            } catch (Exception e) {
                LOGGER.error("[RouteEngine] Failed to register rule for pattern '{}': {}",
                        rule.getUrlPattern(), e.getMessage(), e);
            }
        }
    }

    /**
     * 路由分发 — 根据规则类型调用对应 Handler。
     */
    private static void dispatchRoute(Route route, RouteRule rule) {
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
     * 为 MONITOR 规则创建自动停止会话（如果需要的话）。
     *
     * <p>条件：rule.type == MONITOR 且 (timeoutMs > 0 或 autoStopOnMatch == true)
     */
    private static void startMonitorSession(Object context, String pattern, RouteRule rule) {
        if (rule.getType() != RouteHandleType.MONITOR) {
            return;
        }
        if (rule.getTimeoutMs() <= 0 && !rule.isAutoStopOnMatch()) {
            return;  // 无限监控，无需会话
        }

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
     * 全局清理所有 MonitorSession。
     */
    public static void clearAllMonitorSessions() {
        for (MonitorSession session : SESSIONS.values()) {
            session.stop();
        }
        SESSIONS.clear();
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
