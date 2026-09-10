package com.hsbc.cmb.hk.dbb.automation.framework.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 延迟计算工具 — 从原 {@code DelayHandler} 上提（T1-4：消除 core → handler 的 {@code DelayHandler} 依赖）。
 *
 * <p>原 {@link RouteEngine} 直接调用 {@code DelayHandler.clampDelay/resolveDelay} 导致 core 反向依赖
 * handler 包。本类把这两个纯计算逻辑收口到 core，使 {@code RouteEngine} 不再 import 任何具体 Handler。
 * 计算语义与原实现完全一致。
 */
public final class RouteDelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteDelay.class);

    /** 最大延迟毫秒数（防御性上限，防止意外配置超大延迟导致测试卡死）。 */
    private static final long MAX_DELAY_MS = 120_000; // 2 分钟

    private RouteDelay() {}

    /**
     * 解析实际延迟值，支持固定延迟和随机延迟范围两种模式。
     *
     * @param rule 路由规则
     * @return 实际延迟毫秒数
     */
    public static long resolveDelay(RouteRule rule) {
        long minMs = rule.getDelayMinMs();
        long maxMs = rule.getDelayMaxMs();
        if (minMs > 0 && maxMs > minMs) {
            long randomDelay = ThreadLocalRandom.current().nextLong(minMs, maxMs + 1);
            VerboseLogging.logDebugIfVerbose(LOGGER,
                    "[RouteDelay] Random delay selected: {}ms (range=[{}ms, {}ms]) for pattern='{}'",
                    randomDelay, minMs, maxMs, rule.getUrlPattern());
            return randomDelay;
        }
        long fixedDelay = rule.getDelayMs();
        VerboseLogging.logDebugIfVerbose(LOGGER,
                "[RouteDelay] Fixed delay: {}ms for pattern='{}'", fixedDelay, rule.getUrlPattern());
        return fixedDelay;
    }

    /**
     * 钳制延迟值到安全范围。
     *
     * @param delayMs 原始延迟毫秒数
     * @return 钳制后的安全值
     */
    public static long clampDelay(long delayMs) {
        if (delayMs < 0) {
            LOGGER.warn("[RouteDelay] Negative delay ({}ms) clamped to 0", delayMs);
            return 0;
        }
        if (delayMs > MAX_DELAY_MS) {
            LOGGER.warn("[RouteDelay] Delay {}ms exceeds max limit, clamping to {}s",
                    delayMs, MAX_DELAY_MS / 1000);
            return MAX_DELAY_MS;
        }
        VerboseLogging.logTraceIfVerbose(LOGGER,
                "[RouteDelay] Delay clamped: {}ms -> {}ms", delayMs, delayMs);
        return delayMs;
    }
}
