package com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mock 响应 Handler — 拦截请求，直接返回自定义响应。
 *
 * <p>核心改进：
 * <ul>
 *   <li>mockBody 为 null 时设置为默认空字符串 ""，避免 Playwright 空指针</li>
 *   <li>mockStatus 合法性校验（100 ≤ status < 600），非法时 fallback 到 200</li>
 *   <li>route.fulfill() 包裹 try-catch，避免单请求失败导致整个路由崩溃</li>
 * </ul>
 */
public class MockHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MockHandler.class);

    public static void handle(Route route, RouteRule rule) {
        // ── 1. 状态码校验与 fallback ──────────────────────────────
        int status = rule.getMockStatus();
        if (status < 100 || status >= 600) {
            LOGGER.warn("[MockHandler] Invalid mock status: {} for pattern '{}', using 200 instead",
                    status, rule.getUrlPattern());
            status = 200;
        }

        // ── 2. 响应体默认值处理 ────────────────────────────────────
        String body = rule.getMockBody();
        if (body == null) {
            LOGGER.debug("[MockHandler] mockBody is null for pattern '{}', using empty string",
                    rule.getUrlPattern());
            body = "";
        }

        // ── 3. 构建响应选项 ───────────────────────────────────────
        Route.FulfillOptions opts = new Route.FulfillOptions()
                .setStatus(status)
                .setBody(body);

        // ── 4. 附带自定义响应头 ────────────────────────────────────
        if (rule.getMockHeaders() != null) {
            opts.setHeaders(new java.util.HashMap<>(rule.getMockHeaders()));
        }

        // ── 5. 返回 Mock 响应（异常安全）───────────────────────────
        try {
            route.fulfill(opts);
            LOGGER.debug("[MockHandler] Fulfilled: pattern='{}', status={}, bodyLength={}",
                    rule.getUrlPattern(), status, body.length());
        } catch (PlaywrightException e) {
            LOGGER.error("[MockHandler] Failed to fulfill route for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage(), e);
        }
    }
}
