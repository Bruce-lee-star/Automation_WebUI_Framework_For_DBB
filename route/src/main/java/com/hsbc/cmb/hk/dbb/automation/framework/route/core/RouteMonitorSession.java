package com.hsbc.cmb.hk.dbb.automation.framework.route.core;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.hsbc.cmb.hk.dbb.automation.framework.common.async.AsyncPool;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;

/**
 *  Phase 5 拆分：Monitor 会话域（自包含）。
 *
 * <p>持有 {@code SESSIONS} 注册表与 {@link MonitorSessionKey} / {@link MonitorSession} 类型，
 * 以及会话生命周期方法（start / refresh / onMatch / sessionFor* / stop / clear*）。
 * 是 Monitor 状态的<strong>单一所有者</strong>，与原 {@code RouteEngine} 解耦，
 * 后者通过本类的 package-private 门面方法访问，公共 API 契约不变。
 */
public final class RouteMonitorSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteMonitorSession.class);

    /** Monitor 会话注册表：稳定的 scope 身份 + 注册 pattern → 会话。 */
    private static final Map<MonitorSessionKey, MonitorSession> SESSIONS = new ConcurrentHashMap<>();

    private RouteMonitorSession() {
        // 纯工具类，禁止实例化
    }

    // ─── 类型 ─────────────────────────────────────────────────

    /** 会话稳定键：scope 按对象身份隔离，pattern 使用注册后的不可变字符串。 */
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

    /** Monitor 自动停止会话 — 管理超时调度和匹配计数。 */
    public static class MonitorSession {
        final Object context;
        final String pattern;
        final RouteRule rule;
        final AtomicInteger matchCount = new AtomicInteger(0);
        final AtomicBoolean stopped = new AtomicBoolean(false);
        //  AtomicReference 持有超时任务：scheduleTimeout（SCHEDULER 线程写）与 stop（dispatch 线程读）跨线程，
        //    普通字段存在可见性风险，AtomicReference 提供 happens-before 保证。
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
                LOGGER.info("[RouteMonitorSession] Monitor timeout ({}ms) for pattern '{}', stopping",
                        rule.getTimeoutMs(), pattern);
                VerboseLogging.logDebugIfVerbose(LOGGER,
                        "[RouteMonitorSession] MonitorSession timeout triggered: pattern='{}', elapsed={}ms, matches={}",
                        pattern, rule.getTimeoutMs(), matchCount.get());
                stop();
            }
        }

        /**
         * 停止监控：取消超时任务、标记会话为已停止。
         *
         * <p><b>关键设计</b>：不调用 {@code page.unroute()} 注销路由，避免 auto-stop / 超时触发时的
         * Playwright 线程竞态。Route handler 保持注册，后续匹配请求检测到 {@code stopped == true}
         * 后直接 resume 放行。真正的 unroute 发生在 clearMonitorSessions / unrouteAllForContext 中。
         */
        void stop() {
            if (!stopped.compareAndSet(false, true)) {
                VerboseLogging.logTraceIfVerbose(LOGGER,
                        "[RouteMonitorSession] MonitorSession.stop() already stopped for pattern='{}'", pattern);
                return;  // 已停止（CAS 防重复）
            }

            ScheduledFuture<?> tfLog = timeoutFutureRef.get();
            VerboseLogging.logDebugIfVerbose(LOGGER,
                    "[RouteMonitorSession] MonitorSession.stop() START: pattern='{}', totalMatches={}, timeoutFuture={}",
                    pattern, matchCount.get(), tfLog != null && !tfLog.isDone());

            ScheduledFuture<?> tf = timeoutFutureRef.get();
            if (tf != null && !tf.isDone()) {
                tf.cancel(false);
                VerboseLogging.logDebugIfVerbose(LOGGER,
                        "[RouteMonitorSession] MonitorSession.stop() timeout future cancelled for pattern='{}'", pattern);
            }

            LOGGER.debug("[RouteMonitorSession] MonitorSession stopped: pattern='{}', totalMatches={}",
                    pattern, matchCount.get());
        }
    }

    // ─── 生命周期方法 ─────────────────────────────────────────

    /**
     * 启动 Monitor 会话（按需：无超时且无 autoStop 时不创建）。
     */
    static void startMonitorSession(Object context, RouteRule rule, String normalizedPattern) {
        if (rule.getTimeoutMs() <= 0 && !rule.isAutoStopOnMatch()) {
            VerboseLogging.logDebugIfVerbose(LOGGER,
                    "[RouteMonitorSession] No MonitorSession needed for pattern='{}' (no timeout, no autoStop)",
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
        //  防御性：将实际生效的会话（新建或复用的活跃会话）引用挂到链头原始规则，
        //   供 sessionForRule/sessionForRoute 走 O(1) 定位，避免依赖 session.rule == mergeSource 的身份相等假设。
        rule.setMonitorSessionRef(SESSIONS.get(key));
        if (!installed.get()) {
            VerboseLogging.logTraceIfVerbose(LOGGER,
                    "[RouteMonitorSession] MonitorSession already exists for pattern='{}', reusing",
                    normalizedPattern);
            return;
        }

        if (rule.getTimeoutMs() > 0) {
            session.scheduleTimeout();
        }

        LOGGER.debug("[RouteMonitorSession] MonitorSession started: pattern='{}', timeout={}ms, minMatches={}, autoStop={}",
                normalizedPattern, rule.getTimeoutMs(), rule.getMinMatches(), rule.isAutoStopOnMatch());
        VerboseLogging.logDebugIfVerbose(LOGGER,
                "[RouteMonitorSession] MonitorSession created: id={}, context={}, total sessions={}",
                System.identityHashCode(session), context.getClass().getSimpleName(), SESSIONS.size());
    }

    /**
     *  合并后刷新 MonitorSession：链上任一规则叠加监控能力位后，
     * 若当前无活跃 session（例如「先 modify 后追加 monitor」的逆向注册顺序），则启动一个。
     */
    static void refreshMonitorSession(Object ctx, String pattern, RouteRule sessionOwner, boolean needsSession) {
        if (!needsSession) return;
        MonitorSession session = SESSIONS.get(new MonitorSessionKey(ctx, pattern));
        if (session == null || session.stopped.get()) {
            startMonitorSession(ctx, sessionOwner, pattern);
        }
    }

    /** MonitorHandler 每次匹配完成时回调：递增计数并检查 auto-stop / minMatches 条件。 */
    static void onMonitorMatch(RouteRule rule) {
        MonitorSession session = sessionForRule(rule);
        if (session == null || session.stopped.get()) {
            VerboseLogging.logTraceIfVerbose(LOGGER,
                    "[RouteMonitorSession] onMonitorMatch SKIP: session={} for pattern='{}'",
                    session == null ? "null" : "stopped", rule.getUrlPattern());
            return;
        }

        int currentCount = session.matchCount.incrementAndGet();
        LOGGER.debug("[RouteMonitorSession] Monitor match #{}/{} for pattern '{}'",
                currentCount, rule.getMinMatches(), rule.getUrlPattern());

        VerboseLogging.logDebugIfVerbose(LOGGER,
                "[RouteMonitorSession] onMonitorMatch: count={}/{}, autoStop={}, pattern='{}'",
                currentCount, rule.getMinMatches(), rule.isAutoStopOnMatch(), rule.getUrlPattern());

        if (rule.isAutoStopOnMatch() && currentCount >= rule.getMinMatches()) {
            LOGGER.info("[RouteMonitorSession] Auto-stopping monitor (matches={}) for pattern '{}'",
                    currentCount, rule.getUrlPattern());
            stopMonitorSession(session, currentCount);
        }
    }

    /** 通过规则对象身份查找会话，避免 RouteRule 的可变 equals/hashCode 参与运行时定位。 */
    private static MonitorSession sessionForRule(RouteRule rule) {
        if (rule == null) return null;
        //  分发期合并拷贝经 getMergeSource() 解引用到链头（session.rule 绑定链头）
        RouteRule source = rule.getMergeSource();
        //  防御性快路径：链头已持有会话引用则 O(1) 返回，避免全表遍历与身份相等脆弱假设
        MonitorSession ref = (MonitorSession) source.getMonitorSessionRef();
        if (ref != null) return ref;
        for (MonitorSession session : SESSIONS.values()) {
            if (session.rule == source) return session;
        }
        return null;
    }

    /** 按 Route 所属 Page/Context 优先定位会话，防止跨作用域复用规则时误命中。 */
    static MonitorSession sessionForRoute(Route route, RouteRule rule) {
        if (route == null || rule == null) return sessionForRule(rule);
        //  分发期合并拷贝解引用到源规则（链头）
        RouteRule source = rule.getMergeSource();
        Page page = null;
        try {
            page = route.request().frame().page();
        } catch (Exception ignored) {
            // 页面关闭竞态：回退到纯规则身份查询（无法定位 page/context）
            MonitorSession ref = (MonitorSession) source.getMonitorSessionRef();
            return ref != null ? ref : sessionForRule(source);
        }
        //  防御性快路径：链头已持有会话引用且上下文一致 → O(1) 返回。
        //   多 context 复用同一规则实例时，ref 可能指向最后注册的 session，故必须校验 context 一致，否则退回遍历。
        MonitorSession ref = (MonitorSession) source.getMonitorSessionRef();
        if (ref != null && ref.context == page.context()) return ref;
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
        VerboseLogging.logDebugIfVerbose(LOGGER,
                "[RouteMonitorSession] MonitorSession stopped: pattern='{}', totalMatches={}",
                session.pattern, totalMatches);
    }

    /** 清理指定上下文的全部 MonitorSession（RouteRegistry.clearContext 时同步调用）。 */
    static void clearMonitorSessions(Object context) {
        VerboseLogging.logDebugIfVerbose(LOGGER,
                "[RouteMonitorSession] clearMonitorSessions for context: {} (total sessions before: {})",
                context.getClass().getSimpleName(), SESSIONS.size());
        SESSIONS.entrySet().removeIf(entry -> {
            if (entry.getValue().context == context) {
                entry.getValue().stop();
                VerboseLogging.logTraceIfVerbose(LOGGER,
                        "[RouteMonitorSession] Session removed: pattern='{}'", entry.getValue().pattern);
                return true;
            }
            return false;
        });
        VerboseLogging.logDebugIfVerbose(LOGGER,
                "[RouteMonitorSession] clearMonitorSessions done, remaining sessions: {}", SESSIONS.size());
    }

    /** 全局清理所有 MonitorSession（被 RouteEngine.clearAllMonitorSessions 委托调用）。 */
    static void clearAll() {
        for (MonitorSession session : SESSIONS.values()) {
            session.stop();
        }
        SESSIONS.clear();
    }

    /** 显式停止某 (context + pattern) 的 MonitorSession（被 RouteEngine.stopCapability 委托调用）。 */
    static void stopSessionsFor(Object ctx, String normalized) {
        SESSIONS.entrySet().removeIf(entry -> {
            boolean match = entry.getKey().scope == ctx
                    && entry.getKey().pattern.equals(normalized);
            if (match) {
                entry.getValue().stop();
            }
            return match;
        });
    }

    /** 当前活跃 MonitorSession 数量（供 RouteEngine.clearAllMonitorSessions 日志使用）。 */
    static int sessionCount() {
        return SESSIONS.size();
    }
}
