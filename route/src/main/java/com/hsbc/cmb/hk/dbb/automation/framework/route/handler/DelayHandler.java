package com.hsbc.cmb.hk.dbb.automation.framework.route.handler;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.RouteDelay;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.RouteRule;

/**
 * 延迟处理器（已废弃）。
 *
 * <p>延迟计算逻辑已上提至 {@code route.core.RouteDelay}（T1-4：消除 core → handler 依赖）。
 * 本类仅保留为兼容旧调用方（如演示步骤）的薄委托层，实际计算委托给 {@link RouteDelay}。
 *
 * @deprecated 请直接使用 {@link RouteDelay}
 */
@Deprecated
public class DelayHandler {

    /** @see RouteDelay#resolveDelay(RouteRule) */
    public static long resolveDelay(RouteRule rule) {
        return RouteDelay.resolveDelay(rule);
    }

    /** @see RouteDelay#clampDelay(long) */
    public static long clampDelay(long delayMs) {
        return RouteDelay.clampDelay(delayMs);
    }
}
