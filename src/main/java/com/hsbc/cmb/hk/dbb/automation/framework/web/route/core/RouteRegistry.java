package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.BrowserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 路由注册表 — 按上下文（Page/BrowserContext）隔离存储，避免跨上下文路由冲突。
 *
 * <p>设计要点：
 * <ul>
 *   <li>每个上下文拥有独立的 pattern 集合，互不干扰</li>
 *   <li>支持细粒度的单个 pattern 注销和整上下文清理</li>
 *   <li>使用 {@link ConcurrentHashMap#newKeySet()} 保证线程安全</li>
 *   <li>测试结束时调用 {@link #clearContext(Object)} 防止内存泄漏</li>
 *   <li>{@link ContextKey} 内部使用 {@link WeakReference}，Page 被 GC 后不阻止回收</li>
 * </ul>
 *
 * <p>返回值语义：
 * <ul>
 *   <li>{@code true}  — 首次注册，该 pattern 此前未在此上下文注册过</li>
 *   <li>{@code false} — 已存在，此上下文已注册过该 pattern（去重跳过）</li>
 * </ul>
 */
public class RouteRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteRegistry.class);

    /**
     * Key: ContextKey（WeakReference 包装的 Page/BrowserContext），
     * Value: 该上下文已注册的 pattern 集合。
     *
     * <p>ContextKey 使用身份哈希 + WeakReference，确保：
     * <ol>
     *   <li>两个不同的 ContextKey 包裹同一个 Page 实例时 equals() 返回 true</li>
     *   <li>Page 对象被外部释放后，StrongKey 不会阻止 GC</li>
     *   <li>死条目由 {@link #purgeDeadEntries()} 定期清理</li>
     * </ol>
     */
    private static final ConcurrentHashMap<ContextKey, Set<String>> CONTEXT_PATTERNS = new ConcurrentHashMap<>();

    /** 触发死条目清理的阈值（CONTEXT_PATTERNS size 超过此值后触发 purgeDeadEntries） */
    private static final int PURGE_THRESHOLD = 50;

    /**
     * 按 Page 上下文注册 pattern。
     *
     * @param page    Page 实例
     * @param pattern URL pattern（如 "/api/**"）
     * @return true=首次注册，false=已存在（去重跳过）
     */
    public static boolean register(Page page, String pattern) {
        return registerInternal(page, pattern);
    }

    /**
     * 按 BrowserContext 上下文注册 pattern。
     *
     * @param context BrowserContext 实例
     * @param pattern URL pattern（如 "/api/**"）
     * @return true=首次注册，false=已存在（去重跳过）
     */
    public static boolean register(BrowserContext context, String pattern) {
        return registerInternal(context, pattern);
    }

    /**
     * 内部统一注册逻辑。
     *
     * <p>每次注册前检查是否需要清理死条目（基于阈值触发）。
     */
    private static boolean registerInternal(Object context, String pattern) {
        // 防御性清理死条目（GC 回收的 Page/Context）
        if (CONTEXT_PATTERNS.size() > PURGE_THRESHOLD) {
            purgeDeadEntries();
        }

        ContextKey key = new ContextKey(context);
        Set<String> patterns = CONTEXT_PATTERNS.computeIfAbsent(
                key, k -> ConcurrentHashMap.newKeySet());
        boolean isNew = patterns.add(pattern);
        if (!isNew) {
            LOGGER.debug("[RouteRegistry] Pattern already registered in this context: {} -> {}",
                    context.getClass().getSimpleName(), pattern);
        }
        return isNew;
    }

    /**
     * 注销当前上下文中单个 pattern（业务按需注销时调用）。
     *
     * @param context Page 或 BrowserContext 实例
     * @param pattern 要注销的 URL pattern
     */
    public static void unregister(Object context, String pattern) {
        Set<String> patterns = CONTEXT_PATTERNS.get(new ContextKey(context));
        if (patterns != null) {
            patterns.remove(pattern);
            LOGGER.debug("[RouteRegistry] Unregistered pattern from context: {} -> {}",
                    context.getClass().getSimpleName(), pattern);
        }
    }

    /**
     * 清理指定上下文的全部 pattern（测试结束时调用，防止内存泄漏 + 跨用例污染）。
     *
     * <p>三步清理（避免双重 unroute）：
     * <ol>
     *   <li>从注册表移除该上下文的所有 pattern 并注销 Playwright 路由层</li>
     *   <li>清理 MonitorSession（内部会停止定时器，但不重复 unroute）</li>
     *   <li>清理 Route 防重门控</li>
     * </ol>
     *
     * <p>注意：{@link RouteEngine#clearMonitorSessions(Object)} 内部会调用
     * {@link MonitorSession#stop()} → {@code unroute() + RouteRegistry.unregister()}，
     * 但此时 CONTEXT_PATTERNS 条目已被移除，unregister 不会重复操作，
     * Playwright 对已注销的 pattern 再次 unroute 也仅输出 debug 日志（幂等）。
     *
     * <p>任意一步失败不影响后续步骤（异常隔离）。
     *
     * @param context Page 或 BrowserContext 实例
     */
    public static void clearContext(Object context) {
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[RouteRegistry] clearContext() START for: {} (total contexts before: {})",
                context.getClass().getSimpleName(), CONTEXT_PATTERNS.size());

        // 1. 先从注册表移除，并注销 Playwright 路由层（无 MonitorSession 的 MOCK/MODIFY 路由需要）
        Set<String> patterns = CONTEXT_PATTERNS.remove(new ContextKey(context));
        if (patterns != null && !patterns.isEmpty()) {
            RouteEngine.unrouteAllForContext(context, patterns);
        }

        // 2. 清理 MonitorSession（停止定时器 + unroute，Playwright 对已注销的 pattern 幂等）
        RouteEngine.clearMonitorSessions(context);

        // 3. 清理 Route 防重门控（本测试上下文所有请求均已处理完毕，安全清空）
        RouteEngine.clearDispatchedRoutes();

        LOGGER.debug("[RouteRegistry] Cleared {} patterns for context: {}",
                patterns != null ? patterns.size() : 0,
                context.getClass().getSimpleName());
    }

    /**
     * 全局清理所有上下文的所有 pattern + JSONPath 缓存（测试套件结束时调用）。
     */
    public static void clearAll() {
        CONTEXT_PATTERNS.clear();
        RouteEngine.clearAllMonitorSessions();
        ModifyHandler.clearJsonPathCache();
        LOGGER.debug("[RouteRegistry] Cleared all patterns and caches for all contexts");
    }

    /**
     * 获取指定上下文的已注册 pattern 数量（用于测试/监控）。
     */
    public static int getPatternCount(Object context) {
        Set<String> patterns = CONTEXT_PATTERNS.get(new ContextKey(context));
        return patterns != null ? patterns.size() : 0;
    }

    /**
     * 获取全局上下文数量（用于测试/监控）。
     */
    public static int getContextCount() {
        purgeDeadEntries();
        return CONTEXT_PATTERNS.size();
    }

    /**
     * 清理死条目 — 移除 {@link ContextKey} 中已被 GC 回收的上下文条目。
     *
     * <p>由以下场景触发：
     * <ul>
     *   <li>{@link #registerInternal(Object, String)} 发现 Map size > {@link #PURGE_THRESHOLD}</li>
     *   <li>{@link #getContextCount()} 被调用时</li>
     *   <li>外部按需调用（如测试套件结束时）</li>
     * </ul>
     *
     * <p>使用 {@link Iterator#remove()} 安全遍历，避免 {@code ConcurrentModificationException}。
     */
    static void purgeDeadEntries() {
        int removed = 0;
        Iterator<ContextKey> it = CONTEXT_PATTERNS.keySet().iterator();
        while (it.hasNext()) {
            ContextKey key = it.next();
            if (key.isDead()) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.debug("[RouteRegistry] Purged {} dead context entries (GC-reclaimed)", removed);
        }
    }

    // ─── ContextKey（WeakReference 包装器）─────────────────────────

    /**
     * 上下文的弱引用包装键 — 防止静态 Map 阻止 Page/BrowserContext 被 GC。
     *
     * <p>关键设计：
     * <ul>
     *   <li>{@link #equals(Object)} 基于包裹对象的身份（==），保证同一实例的两个 ContextKey 匹配</li>
     *   <li>{@link #hashCode()} 使用 {@link System#identityHashCode(Object)}，不因 WeakReference 释放而改变</li>
     *   <li>{@link #isDead()} 返回 true 表示包裹对象已被 GC 回收</li>
     * </ul>
     */
    private static final class ContextKey {
        private final int identityHash;
        private final WeakReference<Object> ref;

        ContextKey(Object context) {
            this.identityHash = System.identityHashCode(context);
            this.ref = new WeakReference<>(context);
        }

        /**
         * 获取包裹的原始对象（可能为 null，如果已被 GC）。
         */
        Object get() {
            return ref.get();
        }

        /**
         * 该键对应的上下文是否已被 GC 回收。
         */
        boolean isDead() {
            return ref.get() == null;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if (!(o instanceof ContextKey)) return false;
            ContextKey that = (ContextKey) o;
            Object a = this.ref.get();
            Object b = that.ref.get();
            // 任一侧已被 GC → 不相等（死条目不参与匹配）
            return a != null && b != null && a == b;
        }

        @Override
        public int hashCode() {
            return identityHash;
        }
    }
}
