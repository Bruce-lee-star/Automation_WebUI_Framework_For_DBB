package com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.APIResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 弱网延迟处理器 — 模拟高延迟网络环境下的 API 响应。
 *
 * <p>核心流程：
 * <ol>
 *   <li>使用 {@link Route#fetch()} 向真实服务端发起请求，获取完整响应</li>
 *   <li>按配置的延迟时长等待（支持固定延迟和随机延迟范围）</li>
 *   <li>通过 {@link Route#fulfill()} 将真实响应返回给浏览器</li>
 * </ol>
 *
 * <p>与普通 {@code .delay()} 的区别：
 * <ul>
 *   <li>普通 {@code .delay()} — 在 handler 执行<b>前</b>延迟（对 monitor/mock/modify 通用）</li>
 *   <li>DELAY 类型 — 先获取真实服务端响应，再延迟返回（模拟弱网响应延迟）</li>
 * </ul>
 *
 * <p>典型使用场景：
 * <ul>
 *   <li>模拟 3G/4G 高延迟网络</li>
 *   <li>测试前端超时处理和 loading 状态</li>
 *   <li>验证请求失败重试机制</li>
 *   <li>模拟不同地理区域的服务端延迟</li>
 * </ul>
 */
public class DelayHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DelayHandler.class);

    /** 最大延迟毫秒数（防御性上限，防止意外配置超大延迟导致测试卡死） */
    private static final long MAX_DELAY_MS = 120_000; // 2 分钟

    /**
     * 弱网延迟处理：
     * <ol>
     *   <li>通过 {@code route.fetch()} 获取服务端真实响应</li>
     *   <li>按配置延迟等待</li>
     *   <li>将真实响应通过 {@code route.fulfill()} 返回</li>
     * </ol>
     *
     * <p><b>线程安全说明：</b>此方法在 {@code DELAY_SCHEDULER} 线程池中执行，
     * {@link Thread#sleep(long)} 不会阻塞 Playwright 的 IO 事件循环。
     *
     * @param route Playwright 路由对象（请求已匹配）
     * @param rule  路由规则（含延迟配置）
     */
    public static void handle(Route route, RouteRule rule) {
        long delayMs = resolveDelay(rule);
        delayMs = clampDelay(delayMs);

        try {
            long startTime = System.currentTimeMillis();
            LOGGER.debug("[DelayHandler] Fetching real response for '{}' (delay={}ms)",
                    route.request().url(), delayMs);

            // ① 通过 Playwright 内部 HTTP 栈获取服务端真实响应
            // route.fetch() 是同步阻塞调用，必须在调度线程中执行
            APIResponse response = route.fetch();

            int statusCode = response.status();
            byte[] body = response.body();
            Map<String, String> responseHeaders = new HashMap<>();
            // response.headers() 返回 Map<String, String>，但可能包含重复 key
            // 将第一个值作为 header 值（Playwright 的 fulfill 接受 Map<String, String>）
            response.headers().forEach((key, value) -> {
                if (!responseHeaders.containsKey(key)) {
                    responseHeaders.put(key, value);
                }
            });

            // ② 延迟等待（模拟弱网响应延迟）
            if (delayMs > 0) {
                LOGGER.debug("[DelayHandler] Applying network delay {}ms for '{}'",
                        delayMs, route.request().url());
                Thread.sleep(delayMs);
            }

            // ③ 将真实响应返回给浏览器
            Route.FulfillOptions fulfillOptions = new Route.FulfillOptions()
                    .setStatus(statusCode)
                    .setHeaders(responseHeaders)
                    .setBody(new String(body, StandardCharsets.UTF_8));
            route.fulfill(fulfillOptions);

            long elapsedMs = System.currentTimeMillis() - startTime;
            LOGGER.info("[DelayHandler] Route delayed: pattern='{}', url='{}', status={}, delay={}ms, total={}ms",
                    rule.getUrlPattern(), route.request().url(), statusCode, delayMs, elapsedMs);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("[DelayHandler] Delay interrupted for '{}', resuming without delay",
                    route.request().url());
            try {
                route.resume();
            } catch (Exception ignored) {
                // 路由可能已被处理
            }
        } catch (Exception e) {
            LOGGER.error("[DelayHandler] Failed to handle delay for '{}': {}",
                    route.request().url(), e.getMessage(), e);
            try {
                route.resume();
            } catch (Exception resumeEx) {
                LOGGER.error("[DelayHandler] Failed to resume route after error: {}", resumeEx.getMessage());
            }
        }
    }

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
    private static long resolveDelay(RouteRule rule) {
        long minMs = rule.getDelayMinMs();
        long maxMs = rule.getDelayMaxMs();
        if (minMs > 0 && maxMs > minMs) {
            return ThreadLocalRandom.current().nextLong(minMs, maxMs + 1);
        }
        return rule.getDelayMs();
    }

    /**
     * 钳制延迟值到安全范围。
     *
     * @param delayMs 原始延迟毫秒数
     * @return 钳制后的安全值
     */
    private static long clampDelay(long delayMs) {
        if (delayMs < 0) {
            LOGGER.warn("[DelayHandler] Negative delay ({}ms) clamped to 0", delayMs);
            return 0;
        }
        if (delayMs > MAX_DELAY_MS) {
            LOGGER.warn("[DelayHandler] Delay {}ms exceeds max limit, clamping to {}s",
                    delayMs, MAX_DELAY_MS / 1000);
            return MAX_DELAY_MS;
        }
        return delayMs;
    }
}
