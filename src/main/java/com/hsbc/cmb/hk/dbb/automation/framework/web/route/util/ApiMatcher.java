package com.hsbc.cmb.hk.dbb.automation.framework.web.route.util;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 统一 API 匹配器 — 封装请求匹配的全部维度，供 MONITOR / MODIFY / MOCK / DELAY 四种能力共用。
 *
 * <p>设计目标（对齐 enterprise-api-interception-design-final.md 第 5 节「匹配条件体系」）：
 * <ul>
 *   <li><b>单一实现</b>：所有能力共用同一套匹配逻辑，保证行为一致，消除各 Handler 各自维护的重复匹配代码。</li>
 *   <li><b>AND 语义</b>：同一 matcher 内多条件是 AND（URL 且 Method 且 Header...）。</li>
 *   <li><b>布尔判断</b>：匹配成功与否是布尔判断，不影响执行优先级；优先级只由 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteHandleType} 决定。</li>
 * </ul>
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
 *   <li>Navigation 限定（onlyApiCall 时跳过页面跳转）</li>
 * </ul>
 *
 * <p>线程安全：本类为不可变（immutable），所有字段在构造后不再变更，可安全地被多线程共享。
 * 内部使用的 {@link #PATTERN_CACHE} 为 {@link ConcurrentHashMap}，支持并发读写。
 */
public final class ApiMatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiMatcher.class);

    // ═══════════════════════════════════════════════════════════════
    // 常量（与 RouteUtil 保持一致，避免魔法字符串）
    // ═══════════════════════════════════════════════════════════════

    /** 默认 API 资源类型（不拦截静态资源） */
    private static final Set<String> DEFAULT_API_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(RouteUtil.RT_XHR, RouteUtil.RT_FETCH)));

    /** 所有合法资源类型名称 */
    private static final Set<String> VALID_RESOURCE_TYPES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(RouteUtil.RT_XHR, RouteUtil.RT_FETCH, RouteUtil.RT_SCRIPT, RouteUtil.RT_STYLESHEET,
                    RouteUtil.RT_IMAGE, RouteUtil.RT_FONT, RouteUtil.RT_MEDIA, RouteUtil.RT_DOCUMENT,
                    RouteUtil.RT_WEBSOCKET, RouteUtil.RT_MANIFEST, RouteUtil.RT_OTHER)));

    // ═══════════════════════════════════════════════════════════════
    // Regex 缓存（避免高并发下重复编译 Pattern）
    // ═══════════════════════════════════════════════════════════════

    /** 编译后 Pattern 缓存，容量上限 200（超出后淘汰约 1/4 旧条目，防御无限增长） */
    private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    /** Pattern 缓存容量上限 */
    private static final int PATTERN_CACHE_MAX = 200;

    // ═══════════════════════════════════════════════════════════════
    // ⭐ S1: ReDoS 防护 — 正则表达式长度上限（拒绝指数回溯恶意正则）
    // ═══════════════════════════════════════════════════════════════

    private static final int MAX_REGEX_LENGTH = 1000;
    private static final int MAX_BODY_LENGTH_FOR_REGEX = 500_000; // 500KB

    // ═══════════════════════════════════════════════════════════════
    // 匹配条件字段（不可变）
    // ═══════════════════════════════════════════════════════════════

    private final String urlPattern;
    private final Set<String> resourceTypes;
    private final String matchMethod;
    private final Map<String, String> matchHeaders;
    private final Map<String, String> matchQuery;
    private final String matchBodyRegex;
    private final String matchContentType;
    private final String matchReferrer;
    private final String matchOrigin;
    private final String matchFrameUrl;
    private final boolean onlyMainFrame;
    private final boolean onlyApiCall;

    /**
     * 从 {@link RouteRule} 构建匹配器。
     *
     * @param rule 路由规则（不可为 null）
     */
    public ApiMatcher(RouteRule rule) {
        Objects.requireNonNull(rule, "rule must not be null");
        this.urlPattern = rule.getUrlPattern();
        this.resourceTypes = rule.getResourceTypeSet();
        this.matchMethod = rule.getMatchMethod();
        this.matchHeaders = rule.getMatchHeaders();
        this.matchQuery = rule.getMatchQuery();
        this.matchBodyRegex = rule.getMatchBodyRegex();
        this.matchContentType = rule.getMatchContentType();
        this.matchReferrer = rule.getMatchReferrer();
        this.matchOrigin = rule.getMatchOrigin();
        this.matchFrameUrl = rule.getMatchFrameUrl();
        this.onlyMainFrame = rule.isOnlyMainFrame();
        this.onlyApiCall = rule.isOnlyApiCall();
    }

    /**
     * 检查请求是否匹配所有请求条件。
     *
     * @param route Playwright Route 对象
     * @return true = 匹配所有条件，应该处理；false = 不匹配，跳过
     */
    public boolean matchesRequest(Route route) {
        Request req = route.request();

        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[ApiMatcher] matchesRequest START: pattern='{}', url='{}', method={}, resourceType='{}'",
                urlPattern, RouteUtil.sanitizeUrl(req.url()), req.method(), req.resourceType());

        try {
            // ── 1. Resource Type ────────────────────────────────────
            if (!matchResourceType(req)) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[ApiMatcher] matchesRequest FAIL at ResourceType: pattern='{}'", urlPattern);
                return false;
            }

            // ── 2. HTTP Method ──────────────────────────────────────
            if (!matchMethod(req)) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[ApiMatcher] matchesRequest FAIL at Method: pattern='{}'", urlPattern);
                return false;
            }

            // ── 3. Request Headers ──────────────────────────────────
            if (!matchHeaders(req)) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[ApiMatcher] matchesRequest FAIL at Headers: pattern='{}'", urlPattern);
                return false;
            }

            // ── 4. Query Parameters ────────────────────────────────
            if (!matchQueryParams(req)) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[ApiMatcher] matchesRequest FAIL at QueryParams: pattern='{}'", urlPattern);
                return false;
            }

            // ── 5. Content-Type ────────────────────────────────────
            if (!matchContentType(req)) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[ApiMatcher] matchesRequest FAIL at ContentType: pattern='{}'", urlPattern);
                return false;
            }

            // ── 6. Body Regex ──────────────────────────────────────
            if (!matchBodyRegex(req)) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[ApiMatcher] matchesRequest FAIL at BodyRegex: pattern='{}'", urlPattern);
                return false;
            }

            // ── 7. Referrer ─────────────────────────────────────────
            if (!matchReferrer(req)) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[ApiMatcher] matchesRequest FAIL at Referrer: pattern='{}'", urlPattern);
                return false;
            }

            // ── 8. Origin ───────────────────────────────────────────
            if (!matchOrigin(req)) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[ApiMatcher] matchesRequest FAIL at Origin: pattern='{}'", urlPattern);
                return false;
            }

            // ── 9. Frame ────────────────────────────────────────────
            if (!matchFrame(req)) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[ApiMatcher] matchesRequest FAIL at Frame: pattern='{}'", urlPattern);
                return false;
            }

            // ── 10. Navigation ──────────────────────────────────────
            if (!matchNavigation(req)) {
                LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                        "[ApiMatcher] matchesRequest FAIL at Navigation: pattern='{}'", urlPattern);
                return false;
            }

            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[ApiMatcher] matchesRequest ALL PASSED: pattern='{}', url='{}'",
                    urlPattern, RouteUtil.sanitizeUrl(req.url()));
            return true;
        } catch (Exception e) {
            LOGGER.warn("[ApiMatcher] Error during request matching for pattern '{}': {}",
                    urlPattern, e.getMessage());
            return false;  // 异常时保守跳过，避免误匹配
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 各维度匹配方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resource Type 匹配。
     * <p>规则：如果未设置 resourceTypes 且 onlyApiCall=true，则只匹配 xhr/fetch 类型。
     * 如果显式设置了 resourceTypes，按设置匹配。
     */
    private boolean matchResourceType(Request req) {
        Set<String> allowedTypes = resourceTypes;

        // 配置了 resourceTypes 时检查是否全部合法（仅日志警告，不阻断）
        if (allowedTypes != null && !allowedTypes.isEmpty()) {
            for (String t : allowedTypes) {
                if (!VALID_RESOURCE_TYPES.contains(t)) {
                    LOGGER.warn("[ApiMatcher] Unknown resource type '{}' in rule, valid types: {}",
                            t, VALID_RESOURCE_TYPES);
                }
            }
        }

        // 未配置任何资源类型过滤 + 默认 API only → 只匹配 xhr/fetch
        if (allowedTypes == null && onlyApiCall) {
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
            LOGGER.debug("[ApiMatcher] Resource type mismatch: expected={}, actual={}, url={}",
                    allowedTypes, actualType, RouteUtil.sanitizeUrl(req.url()));
        }
        return match;
    }

    /**
     * HTTP Method 匹配。
     */
    private boolean matchMethod(Request req) {
        if (matchMethod == null || matchMethod.trim().isEmpty()) {
            return true;  // 未设置则不限制
        }
        boolean match = matchMethod.equalsIgnoreCase(req.method());
        if (!match) {
            LOGGER.debug("[ApiMatcher] Method mismatch: expected={}, actual={}, url={}",
                    matchMethod, req.method(), RouteUtil.sanitizeUrl(req.url()));
        }
        return match;
    }

    /**
     * Header 精确匹配。所有 matchHeaders 中的 key-value 必须完全匹配。
     */
    private boolean matchHeaders(Request req) {
        if (matchHeaders == null || matchHeaders.isEmpty()) {
            return true;
        }
        Map<String, String> actualHeaders = req.headers();
        for (Map.Entry<String, String> entry : matchHeaders.entrySet()) {
            String key = entry.getKey();
            String expected = entry.getValue();
            String actual = actualHeaders.getOrDefault(key, "");
            if (!expected.equals(actual)) {
                LOGGER.debug("[ApiMatcher] Header mismatch: {} expected='{}', actual='{}', url={}",
                        key, expected, actual, RouteUtil.sanitizeUrl(req.url()));
                return false;
            }
        }
        return true;
    }

    /**
     * Query Parameter 精确匹配。
     */
    private boolean matchQueryParams(Request req) {
        if (matchQuery == null || matchQuery.isEmpty()) {
            return true;
        }
        Map<String, String> actualQuery = RouteUtil.parseQueryParams(req.url());
        for (Map.Entry<String, String> entry : matchQuery.entrySet()) {
            String key = entry.getKey();
            String expected = entry.getValue();
            String actual = actualQuery.getOrDefault(key, "");
            if (!expected.equals(actual)) {
                LOGGER.debug("[ApiMatcher] Query mismatch: {} expected='{}', actual='{}', url={}",
                        key, expected, actual, RouteUtil.sanitizeUrl(req.url()));
                return false;
            }
        }
        return true;
    }

    /**
     * Content-Type 匹配（包含匹配，非精确匹配）。
     * <p>如 matchContentType="json" 可匹配 "application/json" 和 "application/json;charset=UTF-8"。
     */
    private boolean matchContentType(Request req) {
        if (matchContentType == null || matchContentType.trim().isEmpty()) {
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
        boolean match = actualCt.contains(matchContentType.toLowerCase());
        if (!match) {
            LOGGER.debug("[ApiMatcher] Content-Type mismatch: expected contains='{}', actual='{}', url={}",
                    matchContentType, actualCt, RouteUtil.sanitizeUrl(req.url()));
        }
        return match;
    }

    /**
     * Request Body Regex 匹配。
     * <p>使用预编译 Pattern 缓存，避免高并发下重复编译开销。
     * <p>⭐ S1: ReDoS 防护 — 正则超长拒绝编译，body 过大拒绝匹配。
     */
    private boolean matchBodyRegex(Request req) {
        if (matchBodyRegex == null || matchBodyRegex.trim().isEmpty()) {
            return true;
        }
        // ⭐ S1: ReDoS — 拒绝超长正则（指数回溯风险）
        if (matchBodyRegex.length() > MAX_REGEX_LENGTH) {
            LOGGER.warn("[ApiMatcher] Body regex too long ({} chars), rejected for ReDoS protection: pattern='{}'",
                    matchBodyRegex.length(), matchBodyRegex);
            return false;
        }
        byte[] postData = req.postDataBuffer();
        if (postData == null || postData.length == 0) {
            return false;
        }
        // ⭐ S1: ReDoS — body 过大时跳过正则匹配（防止 CPU 长时间占用）
        if (postData.length > MAX_BODY_LENGTH_FOR_REGEX) {
            LOGGER.debug("[ApiMatcher] Body too large for regex matching ({} bytes), skipping", postData.length);
            return false;
        }
        try {
            String body = new String(postData, StandardCharsets.UTF_8);
            Pattern pattern = getOrCompilePattern(matchBodyRegex);
            boolean match = pattern.matcher(body).matches();
            if (!match) {
                LOGGER.debug("[ApiMatcher] Body regex mismatch: pattern='{}', url={}",
                        matchBodyRegex, RouteUtil.sanitizeUrl(req.url()));
            }
            return match;
        } catch (Exception e) {
            LOGGER.warn("[ApiMatcher] Body regex error: pattern='{}', error={}", matchBodyRegex, e.getMessage());
            return false;
        }
    }

    /**
     * Referrer 包含匹配。
     */
    private boolean matchReferrer(Request req) {
        if (matchReferrer == null || matchReferrer.trim().isEmpty()) {
            return true;
        }
        Map<String, String> headers = req.headers();
        String actual = headers.getOrDefault("referer", "");
        boolean match = actual.contains(matchReferrer);
        if (!match) {
            LOGGER.debug("[ApiMatcher] Referrer mismatch: expected contains='{}', actual='{}', url={}",
                    matchReferrer, actual, RouteUtil.sanitizeUrl(req.url()));
        }
        return match;
    }

    /**
     * Origin 包含匹配。
     */
    private boolean matchOrigin(Request req) {
        if (matchOrigin == null || matchOrigin.trim().isEmpty()) {
            return true;
        }
        Map<String, String> headers = req.headers();
        String actual = headers.getOrDefault("origin", "");
        boolean match = actual.contains(matchOrigin);
        if (!match) {
            LOGGER.debug("[ApiMatcher] Origin mismatch: expected contains='{}', actual='{}', url={}",
                    matchOrigin, actual, RouteUtil.sanitizeUrl(req.url()));
        }
        return match;
    }

    /**
     * Frame 匹配。
     * <p>如果 onlyMainFrame=true（默认），只匹配主 Frame 的请求，忽略 iframe/worker。
     * <p>如果设置了 matchFrameUrl，则 Frame URL 必须包含该值。
     */
    private boolean matchFrame(Request req) {
        // ⭐ P1: Cache req.frame() — Playwright frame() 是跨 JNI 桥调用，有显著开销
        //   缓存后从最多 3 次 JNI 调用降为最多 1 次
        com.microsoft.playwright.Frame frame = null;
        boolean frameResolved = false;

        // 主 Frame 限定
        if (onlyMainFrame) {
            frame = req.frame();
            frameResolved = true;
            if (frame != null) {
                boolean isMainFrame = frame.parentFrame() == null;
                if (!isMainFrame) {
                    LOGGER.debug("[ApiMatcher] Frame mismatch: not main frame, url={}",
                            RouteUtil.sanitizeUrl(req.url()));
                    return false;
                }
            }
        }

        // Frame URL 包含匹配
        if (matchFrameUrl != null && !matchFrameUrl.trim().isEmpty()) {
            if (!frameResolved) {
                frame = req.frame();
            }
            if (frame == null) {
                return false;
            }
            String actualFrameUrl = frame.url();
            boolean match = actualFrameUrl.contains(matchFrameUrl);
            if (!match) {
                LOGGER.debug("[ApiMatcher] Frame URL mismatch: expected contains='{}', actual='{}', req={}",
                        matchFrameUrl, actualFrameUrl, RouteUtil.sanitizeUrl(req.url()));
                return false;
            }
        }

        return true;
    }

    /**
     * Navigation 匹配。
     * <p>如果 onlyApiCall=true，跳过 isNavigationRequest 为 true 的请求（页面跳转）。
     */
    private boolean matchNavigation(Request req) {
        if (onlyApiCall && req.isNavigationRequest()) {
            LOGGER.debug("[ApiMatcher] Navigation request skipped (onlyApiCall=true): url={}",
                    RouteUtil.sanitizeUrl(req.url()));
            return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    // Pattern 缓存工具
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从缓存获取或编译一个正则表达式 Pattern。
     * <p>超限时使用伪 LRU 淘汰 ~25% 条目（避免全量清空导致命中率归零）。
     *
     * @param regex 正则表达式字符串
     * @return 编译后的 Pattern
     * @throws PatternSyntaxException 正则语法错误
     */
    private static Pattern getOrCompilePattern(String regex) {
        // ⭐ P2: 伪 LRU 淘汰替代全量 clear()，避免缓存命中率瞬间归零
        if (PATTERN_CACHE.size() >= PATTERN_CACHE_MAX) {
            LOGGER.debug("[ApiMatcher] Pattern cache reached max ({}), evicting oldest ~25%", PATTERN_CACHE_MAX);
            RouteUtil.evictOldestQuarter(PATTERN_CACHE);
        }
        return PATTERN_CACHE.computeIfAbsent(regex, Pattern::compile);
    }

    /**
     * 清空 Pattern 缓存（供测试/资源释放使用）。
     */
    public static void clearPatternCache() {
        PATTERN_CACHE.clear();
    }

    /**
     * 获取 Pattern 缓存条目数（用于监控）。
     */
    public static int getPatternCacheSize() {
        return PATTERN_CACHE.size();
    }
}
