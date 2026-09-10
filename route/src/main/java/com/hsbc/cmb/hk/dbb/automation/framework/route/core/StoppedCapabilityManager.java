package com.hsbc.cmb.hk.dbb.automation.framework.route.core;

import com.microsoft.playwright.Route;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按能力维度显式停止（monitor / modify / delay / mock / all）的状态与注入逻辑（T2-4 拆分，自 {@code RouteEngine} 提取）。
 *
 * <p>仅操作 {@code RouteContextState.STOPPED_CAPS}（按 上下文 + 归一化 pattern 索引的已停止能力集合），
 * 并联动 {@link RouteMonitorSession} 取消相关超时会话。不触碰路由注册本身（不 unroute）。
 *
 * <p>pattern 归一化与上下文解析复用 {@code RouteEngine} 的包级工具方法（{@code normalizePattern} / {@code resolveContext}），
 * 因二者亦被注册域共用，暂留 {@code RouteEngine} 作为单一实现来源；日志复用 {@code RouteEngine.LOGGER} 以保持生产溯源一致。
 */
final class StoppedCapabilityManager {

    /** 显式停止某 API 的 MONITOR 能力（delay / modify / mock 不受影响）。 */
    static void stopMonitor(Object context, String urlPattern) {
        stopCapability(context, urlPattern, RouteHandleType.MONITOR);
    }

    /** 显式停止某 API 的 MODIFY 能力（monitor / delay / mock 不受影响）。 */
    static void stopModify(Object context, String urlPattern) {
        stopCapability(context, urlPattern, RouteHandleType.MODIFY);
    }

    /** 显式停止某 API 的 DELAY 能力（monitor / modify / mock 不受影响）。 */
    static void stopDelay(Object context, String urlPattern) {
        stopCapability(context, urlPattern, RouteHandleType.DELAY);
    }

    /** 显式停止某 API 的 MOCK 能力（monitor / modify / delay 不受影响）。 */
    static void stopMock(Object context, String urlPattern) {
        stopCapability(context, urlPattern, RouteHandleType.MOCK);
    }

    /** 显式停止某 API 的【全部】能力（monitor / modify / delay / mock 一并停止，但路由仍注册）。 */
    static void stopAll(Object context, String urlPattern) {
        stopCapability(context, urlPattern, null);
    }

    static void stopCapability(Object context, String urlPattern, RouteHandleType cap) {
        Object ctx = RouteEngine.resolveContext(context);
        if (ctx == null || urlPattern == null || urlPattern.trim().isEmpty()) {
            RouteEngine.LOGGER.warn("[RouteEngine] stopCapability ignored: context={}, pattern={}",
                    context == null ? "null" : context.getClass().getSimpleName(), urlPattern);
            return;
        }
        String normalized = RouteEngine.normalizePattern(urlPattern);
        Map<String, Set<RouteHandleType>> byPattern = RouteContextState.STOPPED_CAPS
                .computeIfAbsent(ctx, k -> new ConcurrentHashMap<>());
        Set<RouteHandleType> set = byPattern.computeIfAbsent(normalized,
                k -> EnumSet.noneOf(RouteHandleType.class));
        if (cap == null) {
            set.addAll(EnumSet.allOf(RouteHandleType.class));
        } else {
            set.add(cap);
        }
        // 停止 monitor（或全停）时一并停止其 MonitorSession（取消超时 / 标记 stopped）
        if (cap == null || cap == RouteHandleType.MONITOR) {
            RouteMonitorSession.stopSessionsFor(ctx, normalized);
        }
        RouteEngine.LOGGER.info("[RouteEngine] stopCapability: {} stopped for pattern='{}' on {}",
                cap == null ? "ALL" : cap, normalized, ctx.getClass().getSimpleName());
    }

    /** 清理指定上下文的全部「已停止能力」标记（clear/clearAll 时同步调用，防跨用例残留）。 */
    static void clearStoppedCapabilities(Object context) {
        Object ctx = RouteEngine.resolveContext(context);
        if (ctx == null) return;
        RouteContextState.STOPPED_CAPS.remove(ctx);
    }

    /** 全局清理全部「已停止能力」标记。 */
    static void clearAllStoppedCapabilities() {
        RouteContextState.STOPPED_CAPS.clear();
    }

    /** 将全局已停止能力注入当前请求的有效规则（按 context + pattern 匹配）。 */
    static void applyStoppedCapabilities(RouteRule rule, Route route) {
        Object ctx = contextOf(route);
        if (ctx == null) return;
        Map<String, Set<RouteHandleType>> byPattern = RouteContextState.STOPPED_CAPS.get(ctx);
        if (byPattern == null) return;
        Set<RouteHandleType> stopped = byPattern.get(RouteEngine.normalizePattern(rule.getUrlPattern()));
        if (stopped == null || stopped.isEmpty()) return;
        for (RouteHandleType t : stopped) {
            rule.stopCapability(t);
        }
    }

    /** 取 route 所属上下文对象（与 RouteEngine.contextOf 一致的轻量解析）。 */
    private static Object contextOf(Route route) {
        if (route == null || route.request() == null) return null;
        try {
            return route.request().frame().page().context();
        } catch (Exception ignored) {
            // route 已失效，解析上下文失败返回 null 由调用方处理
            return null;
        }
    }
}
