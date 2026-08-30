package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.BrowserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Map;
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

    // ═══ 路由类型优先级：用于判断是否允许新规则覆盖旧规则 ═══
    // ⭐ Phase 1（设计文档《企业级 API 拦截框架设计》）：优先级定义已收敛至
    //    RouteHandleType 枚举（唯一来源，数值越小越先执行/优先级越高）。
    //    原先在此处的 switch 定义（MOCK=4>MODIFY=3>DELAY=2>MONITOR=1）与
    //    RouteEngine#dispatchRoute 的 if-else 分支（MOCK>MODIFY>MONITOR>DELAY）
    //    在 DELAY / MONITOR 的相对顺序上互相矛盾，已删除，改由 shouldOverride 直接引用枚举。

    /**
     * Key: ContextKey（WeakReference 包装的 Page/BrowserContext），
     * Value: 该上下文已注册的 pattern → RouteHandleType 映射。
     *
     * <p>ContextKey 使用身份哈希 + WeakReference，确保：
     * <ol>
     *   <li>两个不同的 ContextKey 包裹同一个 Page 实例时 equals() 返回 true</li>
     *   <li>Page 对象被外部释放后，StrongKey 不会阻止 GC</li>
     * </ol>
     */
    private static final ConcurrentHashMap<ContextKey, Map<String, RouteHandleType>> CONTEXT_PATTERNS = new ConcurrentHashMap<>();

    /**
     * 按 Page 上下文注册 pattern。
     *
     * @param page    Page 实例
     * @param pattern URL pattern（如 "/api/**"）
     * @param type    路由处理类型
     * @return true=首次注册，false=已存在（去重跳过）
     */
    public static boolean register(Page page, String pattern, RouteHandleType type) {
        return registerInternal(page, pattern, type);
    }

    /**
     * 内部统一注册逻辑。
     *
     * <p>每次注册前检查是否需要清理死条目（基于阈值触发）。
     */
    private static boolean registerInternal(Object context, String pattern, RouteHandleType type) {
        ContextKey key = new ContextKey(context);
        Map<String, RouteHandleType> patterns = CONTEXT_PATTERNS.computeIfAbsent(
                key, k -> new ConcurrentHashMap<>());
        RouteHandleType existing = patterns.putIfAbsent(pattern, type);
        if (existing != null) {
            LOGGER.debug("[RouteRegistry] Pattern already registered in this context: {} -> {} (existing type={}, new type={})",
                    context.getClass().getSimpleName(), pattern, existing, type);
            return false;
        }
        return true;
    }

    /**
     * 注销当前上下文中单个 pattern（业务按需注销时调用）。
     *
     * @param context Page 或 BrowserContext 实例
     * @param pattern 要注销的 URL pattern
     */
    public static void unregister(Object context, String pattern) {
        Map<String, RouteHandleType> patterns = CONTEXT_PATTERNS.get(new ContextKey(context));
        if (patterns != null) {
            patterns.remove(pattern);
            LOGGER.debug("[RouteRegistry] Unregistered pattern from context: {} -> {}",
                    context.getClass().getSimpleName(), pattern);
        }
    }

    /**
     * 强制覆盖注册 — 先移除旧 pattern，再注册新 pattern（总是返回 true）。
     *
     * <p>用于高优先级规则（如 MOCK）覆盖低优先级规则（如 MONITOR）的场景。
     * 解决同一上下文同一 pattern 被监控注册后，Mock 无法覆盖的问题。
     *
     * @param context Page 或 BrowserContext 实例
     * @param pattern URL pattern（已归一化）
     * @param type    新路由处理类型
     * @return 始终返回 true
     */
    public static boolean forceRegister(Object context, String pattern, RouteHandleType type) {
        ContextKey key = new ContextKey(context);
        Map<String, RouteHandleType> patterns = CONTEXT_PATTERNS.computeIfAbsent(
                key, k -> new ConcurrentHashMap<>());
        RouteHandleType oldType = patterns.put(pattern, type);
        if (oldType != null) {
            LOGGER.info("[RouteRegistry] Force-override registered pattern: {} -> {} ({} → {})",
                    context.getClass().getSimpleName(), pattern, oldType, type);
        }
        return true;
    }

    /**
     * 从注册表移除指定上下文的全部 pattern，并返回被移除的 pattern → 类型映射。
     * <p>供 {@link RouteEngine#clearContext(Object)} 内聚清理逻辑时调用，避免 RouteRegistry
     * 反向依赖 RouteEngine 的清理方法（打破双向依赖）。
     *
     * @param context Page 或 BrowserContext 实例
     * @return 被移除的 pattern → 类型映射；若该上下文无注册则返回 null
     */
    public static Map<String, RouteHandleType> removeContextPatterns(Object context) {
        return CONTEXT_PATTERNS.remove(new ContextKey(context));
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

        // ⭐ 清理逻辑统一内聚到 RouteEngine.clearContext，RouteRegistry 只负责登记/反查
        //   （打破 RouteRegistry ↔ RouteEngine 双向依赖）
        RouteEngine.clearContext(context);

        LOGGER.debug("[RouteRegistry] Cleared context: {}", context.getClass().getSimpleName());
    }

    /**
     * 全局清理所有上下文的所有 pattern + JSONPath 缓存（测试套件结束时调用）。
     */
    public static void clearAll() {
        // ⭐ 修复 R7：clearAll 阶段对仍存活的 context 调用原生 unrouteAll 兜底，
        // 防止后续 Playwright 原生 route handler 因只清静态 Map 而未解绑，
        // 在 Context 再次启用时残留旧 handler 造成请求被错误拦截。
        for (Map.Entry<ContextKey, Map<String, RouteHandleType>> entry : CONTEXT_PATTERNS.entrySet()) {
            Object ctx = entry.getKey().get();
            if (ctx != null && !entry.getValue().isEmpty()) {
                try {
                    if (ctx instanceof Page) {
                        // ⭐ Page 已关闭时 unrouteAll 会抛 "Cannot find object to call ..."：
                        //   弱引用仍可达但底层对象已销毁，直接跳过（pattern 随后由 CONTEXT_PATTERNS.clear() 清除）
                        if (RouteUtil.isPageClosed((Page) ctx)) continue;
                        ((Page) ctx).unrouteAll();
                    } else if (ctx instanceof BrowserContext) {
                        ((BrowserContext) ctx).unrouteAll();
                    }
                } catch (Exception e) {
                    // ⭐ 已销毁对象的 unrouteAll 失败属清理期正常竞态（Context 关闭顺序不确定），
                    //   降级为 debug，避免污染正常测试日志；其余异常仍以 WARN 暴露。
                    if (isDestroyedObjectError(e)) {
                        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                                "[RouteRegistry] clearAll: skip unrouteAll, {} already destroyed: {}",
                                ctx.getClass().getSimpleName(), e.getMessage());
                    } else {
                        LOGGER.warn("[RouteRegistry] clearAll: failed to unrouteAll for {}: {}",
                                ctx.getClass().getSimpleName(), e.getMessage());
                    }
                }
            }
        }
        CONTEXT_PATTERNS.clear();
        RouteEngine.clearAllMonitorSessions();
        RouteEngine.clearAllUnifiedRuleStores();
        ModifyHandler.clearJsonPathCache();
        LOGGER.debug("[RouteRegistry] Cleared all patterns and caches for all contexts");
    }

    /**
     * 判断异常是否为「Playwright 底层对象已销毁」信号。
     *
     * <p>清理期 Context/Page 的关闭顺序不确定，对已销毁对象调用原生 API 必然失败。
     * 这类失败是无害的（目标状态已达成），不应以 WARN 干扰正常日志。
     */
    private static boolean isDestroyedObjectError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String m = msg.toLowerCase(java.util.Locale.ROOT);
        return m.contains("object doesn't exist")
                || m.contains("cannot find object")
                || m.contains("target closed")
                || m.contains("has been closed")
                || m.contains("browser has been closed");
    }

    /**
     * 获取指定上下文的已注册 pattern 数量（用于测试/监控）。
     */
    public static int getPatternCount(Object context) {
        Map<String, RouteHandleType> patterns = CONTEXT_PATTERNS.get(new ContextKey(context));
        return patterns != null ? patterns.size() : 0;
    }

    /**
     * 获取全局上下文数量（用于测试/监控）。
     */
    public static int getContextCount() {
        return CONTEXT_PATTERNS.size();
    }

    // ─── ContextKey（WeakReference 包装器）─────────────────────────

    /**
     * 上下文的弱引用包装键 — 防止静态 Map 阻止 Page/BrowserContext 被 GC。
     *
     * <p>关键设计：
     * <ul>
     *   <li>{@link #equals(Object)} 基于包裹对象的身份（==），保证同一实例的两个 ContextKey 匹配</li>
     *   <li>{@link #hashCode()} 使用 {@link System#identityHashCode(Object)}，不因 WeakReference 释放而改变</li>
     * </ul>
     */
    /**
     * 将 clearContext 的 Object 参数还原为 BrowserContext（注册时传入的即 BrowserContext 实例）。
     * 非 BrowserContext 时返回 null（调用方降级为不精确清理防重桶）。
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
