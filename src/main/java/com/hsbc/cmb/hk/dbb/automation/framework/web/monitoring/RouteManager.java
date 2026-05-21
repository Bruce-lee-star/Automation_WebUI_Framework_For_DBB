package com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 路由统一注册管理器
 * 批量注册、低侵入、统一处理监控/修改请求/Mock三种场景
 */
public class RouteManager {

    private static final Logger logger = LoggerFactory.getLogger(RouteManager.class);

    /** 全局规则存储 */
    private static final Map<Object, List<RouteRule>> GLOBAL_RULES = new ConcurrentHashMap<>();

    private RouteManager() {}

    // ==================== 公开 API ====================

    /**
     * 批量注册所有路由规则到 Page
     */
    public static void registerRoutes(Page page, List<RouteRule> rules) {
        if (page == null || rules == null || rules.isEmpty()) {
            logger.warn("[RouteManager] Invalid page or rules, skipping");
            return;
        }
        GLOBAL_RULES.put(page, new ArrayList<>(rules));
        for (RouteRule rule : rules) {
            registerRoute(page, rule);
        }
        logger.info("[RouteManager] Registered {} routes to page", rules.size());
    }

    /**
     * 批量注册所有路由规则到  BrowserContext
     */
    public static void registerRoutes(BrowserContext context, List<RouteRule> rules) {
        if (context == null || rules == null || rules.isEmpty()) {
            logger.warn("[RouteManager] Invalid context or rules, skipping");
            return;
        }
        GLOBAL_RULES.put(context, new ArrayList<>(rules));
        for (RouteRule rule : rules) {
            registerRoute(context, rule);
        }
        logger.info("[RouteManager] Registered {} routes to context", rules.size());
    }

    /**
     * 注册单个路由规则到 Page
     */
    public static void registerRoute(Page page, RouteRule rule) {
        if (page == null || rule == null || !rule.isEnabled()) return;
        registerRouteInternal(page, rule);
    }

    /**
     * 注册单个路由规则到 BrowserContext
     */
    public static void registerRoute(BrowserContext context, RouteRule rule) {
        if (context == null || rule == null || !rule.isEnabled()) return;
        registerRouteInternal(context, rule);
    }

    /**
     * 清除 Page 上的所有路由规则
     */
    public static void clearAllRoutes(Page page) {
        if (page == null) return;
        GLOBAL_RULES.remove(page);
        try {
            page.unrouteAll();
            logger.info("[RouteManager] Cleared all routes from page");
        } catch (Exception e) {
            logger.debug("[RouteManager] Error clearing routes: {}", e.getMessage());
        }
    }

    /**
     * 清除 BrowserContext 上的所有路由规则
     */
    public static void clearAllRoutes(BrowserContext context) {
        if (context == null) return;
        GLOBAL_RULES.remove(context);
        try {
            context.unrouteAll();
            logger.info("[RouteManager] Cleared all routes from context");
        } catch (Exception e) {
            logger.debug("[RouteManager] Error clearing routes: {}", e.getMessage());
        }
    }

    /**
     * 获取 Page 已注册的路由规则
     */
    public static List<RouteRule> getRules(Page page) {
        return GLOBAL_RULES.getOrDefault(page, new ArrayList<>());
    }

    /**
     * 获取 BrowserContext 已注册的路由规则
     */
    public static List<RouteRule> getRules(BrowserContext context) {
        return GLOBAL_RULES.getOrDefault(context, new ArrayList<>());
    }

    // ==================== 内部实现 ====================

    private static void registerRouteInternal(Object pageOrContext, RouteRule rule) {
        Consumer<Route> handler;
        switch (rule.getHandleType()) {
            case MONITOR:
                handler = createMonitorHandler(rule);
                break;
            case MODIFY_REQUEST:
                handler = createModifyRequestHandler(rule);
                break;
            case MOCK_RESPONSE:
                handler = createMockResponseHandler(rule);
                break;
            default:
                logger.warn("[RouteManager] Unknown handle type: {}", rule.getHandleType());
                return;
        }

        if (pageOrContext instanceof Page) {
            ((Page) pageOrContext).route(rule.getUrlPattern(), handler);
        } else if (pageOrContext instanceof BrowserContext) {
            ((BrowserContext) pageOrContext).route(rule.getUrlPattern(), handler);
        }
    }

    /**
     * 监控处理器 - 先放行，后异步解析
     */
    private static Consumer<Route> createMonitorHandler(RouteRule rule) {
        return route -> {
            Request request = route.request();
            String url = request.url();
            String method = request.method();

            // 立刻放行，不卡页面
            route.resume();

            // 异步处理日志、报文解析
            RouteAsyncPool.runAsync(() -> {
                try {
                    Response response = request.response();
                    if (response == null) return;

                    int status = response.status();
                    String body = RouteUtil.cleanControlChar(response.text());

                    if (logger.isDebugEnabled()) {
                        logger.debug("[Monitor] {} {} status:{}", method, RouteUtil.simplifyUrl(url), status);
                    }

                    if (rule.getResponseGenerator() != null) {
                        try {
                            RouteResponseGenerator.ResponseData data = new RouteResponseGenerator.ResponseData(
                                url, method, status, body, request.headers()
                            );
                            rule.getResponseGenerator().onResponse(data);
                        } catch (Exception e) {
                            logger.warn("[Monitor] Callback error for {}: {}", url, e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    logger.debug("[Monitor] Error for {}: {}", url, e.getMessage());
                }
            });
        };
    }

    /**
     * 修改请求处理器 - 修改请求头/请求体后继续
     */
    private static Consumer<Route> createModifyRequestHandler(RouteRule rule) {
        return route -> {
            Request request = route.request();
            Route.ResumeOptions options = RouteUtil.buildResumeOptions(request, rule.getAppendHeaders());

            // 请求体替换
            String postData = request.postData();
            if (postData != null && rule.getReplaceBodyKey() != null && rule.getReplaceBodyValue() != null) {
                String newBody = postData.replace(rule.getReplaceBodyKey(), rule.getReplaceBodyValue());
                options.setPostData(newBody);
                logger.debug("[Modify] Body replaced for {}", RouteUtil.simplifyUrl(request.url()));
            }

            // 方法替换
            if (rule.getMethod() != null && !rule.getMethod().isEmpty()) {
                options.setMethod(rule.getMethod());
            }

            route.resume(options);
        };
    }

    /**
     * Mock 响应处理器 - 直接返回响应
     */
    private static Consumer<Route> createMockResponseHandler(RouteRule rule) {
        final String cachedMockJson = rule.getMockJson();
        final int statusCode = rule.getStatusCode() > 0 ? rule.getStatusCode() : 200;
        final Map<String, String> headers = rule.getAppendHeaders();

        return route -> {
            Route.FulfillOptions fulfillOptions = new Route.FulfillOptions()
                    .setStatus(statusCode)
                    .setBody(cachedMockJson != null ? cachedMockJson : "{}");

            if (headers != null && !headers.isEmpty()) {
                fulfillOptions.setHeaders(new java.util.HashMap<>(headers));
            } else {
                Map<String, String> defaultHeaders = new java.util.HashMap<>();
                defaultHeaders.put("Content-Type", "application/json;charset=utf-8");
                fulfillOptions.setHeaders(defaultHeaders);
            }

            RouteAsyncPool.runAsync(() -> {
                logger.debug("[Mock] {} {} -> status:{}", route.request().method(),
                    RouteUtil.simplifyUrl(route.request().url()), statusCode);
            });

            route.fulfill(fulfillOptions);
        };
    }
}
