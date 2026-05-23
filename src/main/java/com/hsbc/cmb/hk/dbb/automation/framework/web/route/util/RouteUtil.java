package com.hsbc.cmb.hk.dbb.automation.framework.web.route.util;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 路由匹配工具类 — 根据 Request 属性判断是否匹配 RouteRule 中定义的请求条件。
 *
 * <p>支持的匹配维度：
 * <ul>
 *   <li>Resource Type（xhr / fetch / script / stylesheet / image / font / media / document）</li>
 *   <li>HTTP Method</li>
 *   <li>Request Headers（精确匹配）</li>
 *   <li>Query Parameters（精确匹配）</li>
 *   <li>Request Body Regex</li>
 *   <li>Content-Type</li>
 *   <li>Referrer / Origin</li>
 *   <li>Frame URL / 主 Frame 限定</li>
 * </ul>
 *
 * <p>匹配失败 → 返回（Playwright 会自动调用下一个匹配 pattern 的 handler）。
 * <p>匹配成功 → 由 RouteEngine 分发到 Handler 处理。
 */
public final class RouteUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteUtil.class);

    // ═══════════════════════════════════════════════════════════════
    // Playwright Resource Type 常量（替代魔法字符串，与 Playwright resourceType() 返回值对应）
    // ═══════════════════════════════════════════════════════════════

    public static final String RT_XHR = "xhr";
    public static final String RT_FETCH = "fetch";
    public static final String RT_SCRIPT = "script";
    public static final String RT_STYLESHEET = "stylesheet";
    public static final String RT_IMAGE = "image";
    public static final String RT_FONT = "font";
    public static final String RT_MEDIA = "media";
    public static final String RT_DOCUMENT = "document";
    public static final String RT_WEBSOCKET = "websocket";
    public static final String RT_MANIFEST = "manifest";
    public static final String RT_OTHER = "other";

    /** 默认 API 资源类型（不拦截静态资源） */
    private static final Set<String> DEFAULT_API_TYPES = new HashSet<>(
            Arrays.asList(RT_XHR, RT_FETCH));

    /** 所有合法资源类型名称 */
    private static final Set<String> VALID_RESOURCE_TYPES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(RT_XHR, RT_FETCH, RT_SCRIPT, RT_STYLESHEET,
                    RT_IMAGE, RT_FONT, RT_MEDIA, RT_DOCUMENT,
                    RT_WEBSOCKET, RT_MANIFEST, RT_OTHER)));

    // ═══════════════════════════════════════════════════════════════
    // Regex 缓存（避免高并发下重复编译 Pattern）
    // ═══════════════════════════════════════════════════════════════

    /** 编译后 Pattern 缓存，容量上限 200（超出后清空重建，防御无限增长） */
    private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    /** Pattern 缓存容量上限 */
    private static final int PATTERN_CACHE_MAX = 200;

    private RouteUtil() {}

    /**
     * 检查请求是否匹配规则中定义的所有请求条件。
     *
     * @param route Playwright Route 对象
     * @param rule  路由规则
     * @return true = 匹配所有条件，应该处理；false = 不匹配，跳过
     */
    public static boolean requestMatches(Route route, RouteRule rule) {
        Request req = route.request();

        try {
            // ── 1. Resource Type ────────────────────────────────────
            if (!matchResourceType(req, rule)) return false;

            // ── 2. HTTP Method ──────────────────────────────────────
            if (!matchMethod(req, rule)) return false;

            // ── 3. Request Headers ──────────────────────────────────
            if (!matchHeaders(req, rule)) return false;

            // ── 4. Query Parameters ────────────────────────────────
            if (!matchQueryParams(req, rule)) return false;

            // ── 5. Content-Type ────────────────────────────────────
            if (!matchContentType(req, rule)) return false;

            // ── 6. Body Regex ──────────────────────────────────────
            if (!matchBodyRegex(req, rule)) return false;

            // ── 7. Referrer ─────────────────────────────────────────
            if (!matchReferrer(req, rule)) return false;

            // ── 8. Origin ───────────────────────────────────────────
            if (!matchOrigin(req, rule)) return false;

            // ── 9. Frame ────────────────────────────────────────────
            if (!matchFrame(req, rule)) return false;

            // ── 10. Navigation ──────────────────────────────────────
            if (!matchNavigation(req, rule)) return false;

            return true;
        } catch (Exception e) {
            LOGGER.warn("[RouteUtil] Error during request matching for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage());
            return false;  // 异常时保守跳过，避免误匹配
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 各维度匹配方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resource Type 匹配。
     * <p>使用 {@link #RT_XHR} 等常量代替魔法字符串。
     * <p>规则：如果 rule 未设置 resourceTypes 且 onlyApiCall=true，
     * 则只匹配 xhr/fetch 类型。如果 rule 显式设置了 resourceTypes，按设置匹配。
     */
    private static boolean matchResourceType(Request req, RouteRule rule) {
        Set<String> allowedTypes = rule.getResourceTypeSet();

        // 配置了 resourceTypes 时检查是否全部合法（仅日志警告，不阻断）
        if (allowedTypes != null && !allowedTypes.isEmpty()) {
            for (String t : allowedTypes) {
                if (!VALID_RESOURCE_TYPES.contains(t)) {
                    LOGGER.warn("[RouteUtil] Unknown resource type '{}' in rule, valid types: {}",
                            t, VALID_RESOURCE_TYPES);
                }
            }
        }

        // 未配置任何资源类型过滤 + 默认 API only → 只匹配 xhr/fetch
        if (allowedTypes == null && rule.isOnlyApiCall()) {
            allowedTypes = DEFAULT_API_TYPES;
        }

        // 未配置且不限制 → 匹配所有类型
        if (allowedTypes == null || allowedTypes.isEmpty()) {
            return true;
        }

        String actualType = req.resourceType() != null
                ? req.resourceType().toLowerCase() : "";
        boolean match = allowedTypes.contains(actualType);
        if (!match) {
            LOGGER.debug("[RouteUtil] Resource type mismatch: expected={}, actual={}, url={}",
                    allowedTypes, actualType, req.url());
        }
        return match;
    }

    /**
     * HTTP Method 匹配。
     */
    private static boolean matchMethod(Request req, RouteRule rule) {
        String expectedMethod = rule.getMatchMethod();
        if (expectedMethod == null || expectedMethod.trim().isEmpty()) {
            return true;  // 未设置则不限制
        }
        boolean match = expectedMethod.equalsIgnoreCase(req.method());
        if (!match) {
            LOGGER.debug("[RouteUtil] Method mismatch: expected={}, actual={}, url={}",
                    expectedMethod, req.method(), req.url());
        }
        return match;
    }

    /**
     * Header 精确匹配。所有 rule.matchHeaders 中的 key-value 必须完全匹配。
     */
    private static boolean matchHeaders(Request req, RouteRule rule) {
        Map<String, String> requiredHeaders = rule.getMatchHeaders();
        if (requiredHeaders == null || requiredHeaders.isEmpty()) {
            return true;
        }
        Map<String, String> actualHeaders = req.headers();
        for (Map.Entry<String, String> entry : requiredHeaders.entrySet()) {
            String key = entry.getKey();
            String expected = entry.getValue();
            String actual = actualHeaders.getOrDefault(key, "");
            if (!expected.equals(actual)) {
                LOGGER.debug("[RouteUtil] Header mismatch: {} expected='{}', actual='{}', url={}",
                        key, expected, actual, req.url());
                return false;
            }
        }
        return true;
    }

    /**
     * Query Parameter 精确匹配。
     */
    private static boolean matchQueryParams(Request req, RouteRule rule) {
        Map<String, String> requiredQuery = rule.getMatchQuery();
        if (requiredQuery == null || requiredQuery.isEmpty()) {
            return true;
        }
        Map<String, String> actualQuery = parseQueryParams(req.url());
        for (Map.Entry<String, String> entry : requiredQuery.entrySet()) {
            String key = entry.getKey();
            String expected = entry.getValue();
            String actual = actualQuery.getOrDefault(key, "");
            if (!expected.equals(actual)) {
                LOGGER.debug("[RouteUtil] Query mismatch: {} expected='{}', actual='{}', url={}",
                        key, expected, actual, req.url());
                return false;
            }
        }
        return true;
    }

    /**
     * Content-Type 匹配（包含匹配，非精确匹配）。
     * <p>如 matchContentType="json" 可匹配 "application/json" 和 "application/json;charset=UTF-8"。
     */
    private static boolean matchContentType(Request req, RouteRule rule) {
        String expectedCt = rule.getMatchContentType();
        if (expectedCt == null || expectedCt.trim().isEmpty()) {
            return true;
        }
        Map<String, String> headers = req.headers();
        String actualCt = "";
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("content-type".equalsIgnoreCase(entry.getKey())) {
                actualCt = entry.getValue().toLowerCase();
                break;
            }
        }
        boolean match = actualCt.contains(expectedCt.toLowerCase());
        if (!match) {
            LOGGER.debug("[RouteUtil] Content-Type mismatch: expected contains='{}', actual='{}', url={}",
                    expectedCt, actualCt, req.url());
        }
        return match;
    }

    /**
     * Request Body Regex 匹配。
     * <p>使用预编译 Pattern 缓存，避免高并发下重复编译开销。
     */
    private static boolean matchBodyRegex(Request req, RouteRule rule) {
        String regex = rule.getMatchBodyRegex();
        if (regex == null || regex.trim().isEmpty()) {
            return true;
        }
        byte[] postData = req.postDataBuffer();
        if (postData == null || postData.length == 0) {
            return false;
        }
        try {
            String body = new String(postData, StandardCharsets.UTF_8);
            Pattern pattern = getOrCompilePattern(regex);
            boolean match = pattern.matcher(body).matches();
            if (!match) {
                LOGGER.debug("[RouteUtil] Body regex mismatch: pattern='{}', url={}", regex, req.url());
            }
            return match;
        } catch (Exception e) {
            LOGGER.warn("[RouteUtil] Body regex error: pattern='{}', error={}", regex, e.getMessage());
            return false;
        }
    }

    /**
     * 从缓存获取或编译一个正则表达式 Pattern。
     * <p>缓存超出上限时重建，防止 OOM。
     *
     * @param regex 正则表达式字符串
     * @return 编译后的 Pattern
     * @throws PatternSyntaxException 正则语法错误
     */
    private static Pattern getOrCompilePattern(String regex) {
        // 缓存溢出 → 清空重建（防御性保护）
        if (PATTERN_CACHE.size() >= PATTERN_CACHE_MAX) {
            LOGGER.debug("[RouteUtil] Pattern cache reached max ({}), clearing", PATTERN_CACHE_MAX);
            PATTERN_CACHE.clear();
        }
        return PATTERN_CACHE.computeIfAbsent(regex, Pattern::compile);
    }

    /**
     * Referrer 包含匹配。
     */
    private static boolean matchReferrer(Request req, RouteRule rule) {
        String expected = rule.getMatchReferrer();
        if (expected == null || expected.trim().isEmpty()) {
            return true;
        }
        Map<String, String> headers = req.headers();
        String actual = headers.getOrDefault("referer", "");
        boolean match = actual.contains(expected);
        if (!match) {
            LOGGER.debug("[RouteUtil] Referrer mismatch: expected contains='{}', actual='{}', url={}",
                    expected, actual, req.url());
        }
        return match;
    }

    /**
     * Origin 包含匹配。
     */
    private static boolean matchOrigin(Request req, RouteRule rule) {
        String expected = rule.getMatchOrigin();
        if (expected == null || expected.trim().isEmpty()) {
            return true;
        }
        Map<String, String> headers = req.headers();
        String actual = headers.getOrDefault("origin", "");
        boolean match = actual.contains(expected);
        if (!match) {
            LOGGER.debug("[RouteUtil] Origin mismatch: expected contains='{}', actual='{}', url={}",
                    expected, actual, req.url());
        }
        return match;
    }

    /**
     * Frame 匹配。
     * <p>如果 onlyMainFrame=true（默认），只匹配主 Frame 的请求，忽略 iframe/worker。
     * <p>如果设置了 matchFrameUrl，则 Frame URL 必须包含该值。
     */
    private static boolean matchFrame(Request req, RouteRule rule) {
        // 主 Frame 限定
        if (rule.isOnlyMainFrame() && req.frame() != null) {
            boolean isMainFrame = req.frame().parentFrame() == null;
            if (!isMainFrame) {
                LOGGER.debug("[RouteUtil] Frame mismatch: not main frame, url={}", req.url());
                return false;
            }
        }

        // Frame URL 包含匹配
        String expectedFrameUrl = rule.getMatchFrameUrl();
        if (expectedFrameUrl != null && !expectedFrameUrl.trim().isEmpty()) {
            if (req.frame() == null) {
                return false;
            }
            String actualFrameUrl = req.frame().url();
            boolean match = actualFrameUrl.contains(expectedFrameUrl);
            if (!match) {
                LOGGER.debug("[RouteUtil] Frame URL mismatch: expected contains='{}', actual='{}', req={}",
                        expectedFrameUrl, actualFrameUrl, req.url());
                return false;
            }
        }

        return true;
    }

    /**
     * Navigation 匹配。
     * <p>如果 onlyApiCall=true，跳过 isNavigationRequest 为 true 的请求（页面跳转）。
     */
    private static boolean matchNavigation(Request req, RouteRule rule) {
        if (rule.isOnlyApiCall() && req.isNavigationRequest()) {
            LOGGER.debug("[RouteUtil] Navigation request skipped (onlyApiCall=true): url={}", req.url());
            return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    // Query 解析工具
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从 URL 解析 query parameters。
     *
     * @param url 完整 URL
     * @return 不可变的 query map
     */
    public static Map<String, String> parseQueryParams(String url) {
        Map<String, String> query = new LinkedHashMap<>();
        if (url == null) return query;
        try {
            URI uri = new URI(url);
            String queryStr = uri.getRawQuery();
            if (queryStr == null || queryStr.isEmpty()) return query;
            for (String pair : queryStr.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8.name());
                    String value = URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name());
                    query.put(key, value);
                } else if (kv.length == 1 && !kv[0].isEmpty()) {
                    query.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8.name()), "");
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[RouteUtil] Failed to parse query params from URL: {}", url);
        }
        return query;
    }
}
