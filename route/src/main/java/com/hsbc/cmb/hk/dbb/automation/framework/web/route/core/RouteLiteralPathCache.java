package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;

import java.util.concurrent.ConcurrentHashMap;

/**
 * ⭐ Phase 5 死代码隔离载体。
 *
 * <p>原位于 {@code RouteEngine} 的带缓存版 {@code literalPathOf} + {@code LITERAL_PATH_CACHE}。
 * 经全仓搜索（route 与 test-automation 模块）：该缓存版方法<b>无任何调用点</b>
 * （活跃调用方均使用 {@link RouteUtil#literalPathOf} 的无缓存等价实现，如
 * {@code MonitorHandler} / {@code ModifyHandler}）。为消除主引擎冗余、集中管理死代码而隔离至此；
 * 保留实现以便将来若需高性能缓存路径可复用。
 *
 * @deprecated 无调用点（死代码）。活跃语义见 {@link RouteUtil#literalPathOf}。
 */
@Deprecated
public final class RouteLiteralPathCache {

    private static final ConcurrentHashMap<String, String> LITERAL_PATH_CACHE =
            new ConcurrentHashMap<>();
    private static final int LITERAL_PATH_CACHE_MAX = 256;

    private RouteLiteralPathCache() {
    }

    /**
     * 提取 urlPattern 中「去除通配符后的字面前缀」（带上限缓存）。
     * <p>当前为死代码，仅作隔离保留；活跃路径请使用 {@link RouteUtil#literalPathOf}。
     *
     * @deprecated 无调用点。
     */
    @Deprecated
    public static String literalPathOf(String urlPattern) {
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

    /** 清空字面路径缓存（供 RouteEngine.clearAllMonitorSessions 在 case 收尾时调用）。 */
    public static void clear() {
        LITERAL_PATH_CACHE.clear();
    }
}
