package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.BrowserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /** Key: Page/BrowserContext, Value: 该上下文已注册的 pattern 集合 */
    private static final ConcurrentHashMap<Object, Set<String>> CONTEXT_PATTERNS = new ConcurrentHashMap<>();

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
     * 内部统一注册逻辑
     */
    private static boolean registerInternal(Object context, String pattern) {
        Set<String> patterns = CONTEXT_PATTERNS.computeIfAbsent(
                context, k -> ConcurrentHashMap.newKeySet());
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
        Set<String> patterns = CONTEXT_PATTERNS.get(context);
        if (patterns != null) {
            patterns.remove(pattern);
            LOGGER.debug("[RouteRegistry] Unregistered pattern from context: {} -> {}",
                    context.getClass().getSimpleName(), pattern);
        }
    }

    /**
     * 清理指定上下文的全部 pattern（测试结束时调用，防止内存泄漏）。
     *
     * @param context Page 或 BrowserContext 实例
     */
    public static void clearContext(Object context) {
        CONTEXT_PATTERNS.remove(context);
        RouteEngine.clearMonitorSessions(context);
        LOGGER.debug("[RouteRegistry] Cleared all patterns for context: {}",
                context.getClass().getSimpleName());
    }

    /**
     * 全局清理所有上下文的所有 pattern（测试套件结束时调用）。
     */
    public static void clearAll() {
        CONTEXT_PATTERNS.clear();
        RouteEngine.clearAllMonitorSessions();
        LOGGER.debug("[RouteRegistry] Cleared all patterns for all contexts");
    }

    /**
     * 获取指定上下文的已注册 pattern 数量（用于测试/监控）。
     */
    public static int getPatternCount(Object context) {
        Set<String> patterns = CONTEXT_PATTERNS.get(context);
        return patterns != null ? patterns.size() : 0;
    }

    /**
     * 获取全局上下文数量（用于测试/监控）。
     */
    public static int getContextCount() {
        return CONTEXT_PATTERNS.size();
    }
}
