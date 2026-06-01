package com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 高延迟处理器 — 模拟高延迟网络环境下的 API 请求。
 *
 * <p>核心流程（不使用 {@code route.fetch()}，避免 DNS 解析问题；
 * 不使用 {@code Thread.sleep()}，避免占用线程资源）：
 * <ol>
 *   <li>解析延迟值（固定 / 随机范围）并钳位到安全范围</li>
 *   <li>由 {@code RouteEngine} 使用 {@code ScheduledExecutorService.schedule()}
 *       在延迟后调用 {@code route.resume()} 放行原始请求</li>
 * </ol>
 *
 * <p>典型使用场景：
 * <ul>
 *   <li>模拟 3G/4G 高延迟网络</li>
 *   <li>测试前端超时处理和 loading 状态</li>
 *   <li>验证请求失败重试机制</li>
 * </ul>
 */
public class DelayHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DelayHandler.class);

    /** 最大延迟毫秒数（防御性上限，防止意外配置超大延迟导致测试卡死） */
    private static final long MAX_DELAY_MS = 120_000; // 2 分钟

    /**
     * 解析实际延迟值，支持固定延迟和随机延迟范围两种模式。
     *
     * <p>优先级：
     * <ol>
     *   <li>如果 delayMinMs > 0 且 delayMaxMs > delayMinMs → 随机区间 [min, max]</li>
     *   <li>否则使用固定延迟 delayMs</li>
     * </ol>
     *
     * @param rule 路由规则
     * @return 实际延迟毫秒数
     */
    public static long resolveDelay(RouteRule rule) {
        long minMs = rule.getDelayMinMs();
        long maxMs = rule.getDelayMaxMs();
        if (minMs > 0 && maxMs > minMs) {
            long randomDelay = ThreadLocalRandom.current().nextLong(minMs, maxMs + 1);
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[DelayHandler] Random delay selected: {}ms (range=[{}ms, {}ms]) for pattern='{}'",
                    randomDelay, minMs, maxMs, rule.getUrlPattern());
            return randomDelay;
        }
        long fixedDelay = rule.getDelayMs();
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[DelayHandler] Fixed delay: {}ms for pattern='{}'", fixedDelay, rule.getUrlPattern());
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
            LOGGER.warn("[DelayHandler] Negative delay ({}ms) clamped to 0", delayMs);
            return 0;
        }
        if (delayMs > MAX_DELAY_MS) {
            LOGGER.warn("[DelayHandler] Delay {}ms exceeds max limit, clamping to {}s",
                    delayMs, MAX_DELAY_MS / 1000);
            return MAX_DELAY_MS;
        }
        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[DelayHandler] Delay clamped: {}ms -> {}ms", delayMs, delayMs);
        return delayMs;
    }
}
