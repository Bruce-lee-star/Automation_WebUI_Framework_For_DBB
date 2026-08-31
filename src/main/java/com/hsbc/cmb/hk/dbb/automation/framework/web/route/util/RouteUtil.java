package com.hsbc.cmb.hk.dbb.automation.framework.web.route.util;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.jayway.jsonpath.JsonPath;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    // ═══════════════════════════════════════════════════════════════
    // JsonPath 编译缓存（⭐ P2-15：单一共享，替代 ModifyHandler / MonitorHandler
    // 各自维护的缓存，提升命中率、消除重复编译）
    // ═══════════════════════════════════════════════════════════════

    /** JsonPath 编译缓存，容量上限 1024（超出后淘汰约 1/4 旧条目） */
    private static final ConcurrentHashMap<String, JsonPath> JSONPATH_CACHE = new ConcurrentHashMap<>();

    /** JsonPath 缓存容量上限 */
    private static final int JSONPATH_CACHE_MAX = 1024;

    /** 响应体读取/存储上限（字节），超过则截断，防止大响应体（如下载、大 JSON）导致 OOM。
     *  可用环境变量 ROUTE_MAX_BODY_BYTES 覆盖，默认 5MB。 */
    public static final long MAX_BODY_BYTES = getEnvLong("ROUTE_MAX_BODY_BYTES", 5L * 1024 * 1024);

    /**
     * 截断响应体字节数组，防止超大响应体撑爆内存。
     * @param body 原始字节数组（可能为 null）
     * @return 不超过 {@link #MAX_BODY_BYTES} 的字节数组；未超限则原样返回
     */
    public static byte[] truncateBody(byte[] body) {
        if (body == null || body.length <= MAX_BODY_BYTES) return body;
        byte[] truncated = Arrays.copyOf(body, (int) Math.min(MAX_BODY_BYTES, Integer.MAX_VALUE));
        LOGGER.debug("[RouteUtil] Response body truncated from {} to {} bytes (ROUTE_MAX_BODY_BYTES)",
                body.length, truncated.length);
        return truncated;
    }

    /**
     * 截断响应体字符串（与 {@link #truncateBody(byte[])} 同源，供字符串路径复用）。
     */
    public static String truncateBody(String body) {
        if (body == null || body.length() <= MAX_BODY_BYTES) return body;
        String truncated = body.substring(0, (int) Math.min(MAX_BODY_BYTES, Integer.MAX_VALUE));
        LOGGER.debug("[RouteUtil] Response body truncated from {} to {} chars (ROUTE_MAX_BODY_BYTES)",
                body.length(), truncated.length());
        return truncated;
    }

    /** 从环境变量读取 long（解析失败/缺失时返回默认值）。 */
    public static long getEnvLong(String key, long defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.trim().isEmpty()) return defaultValue;
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 从环境变量读取 double（解析失败/缺失时返回默认值）。集中实现，消除各 Handler 的重复副本。 */
    public static double getEnvDouble(String key, double defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.trim().isEmpty()) return defaultValue;
        try {
            double parsed = Double.parseDouble(val.trim());
            // 防御：拒绝 NaN / ±Infinity（如误配 "NaN"/"Infinity"），回退默认值，
            // 避免污染超时/告警阈值等 double 型配置（修复 L3）
            if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private RouteUtil() {}

    /**
     * 全局统一 JsonPath 配置（⭐ 一致性修复：此前 ModifyHandler 使用自定义 Configuration，
     * 而 MonitorHandler 直接用 {@code JsonPath.compile} 默认配置，
     * 两者在缺失字段处理、异常策略上存在语义差异，会导致同一表达式在不同 Handler 下行为不一致）。
     * <p>统一采用 Jackson 提供器 + {@code Option.SUPPRESS_EXCEPTIONS}（缺失路径返回 null/空，
     * 不抛异常），保证 MOCK/MONITOR/MODIFY 三路径解析语义一致。
     */
    public static final com.jayway.jsonpath.Configuration JSONPATH_CONFIG =
            com.jayway.jsonpath.Configuration.builder()
                    .jsonProvider(new com.jayway.jsonpath.spi.json.JacksonJsonProvider())
                    .mappingProvider(new com.jayway.jsonpath.spi.mapper.JacksonMappingProvider())
                    .options(com.jayway.jsonpath.Option.SUPPRESS_EXCEPTIONS)
                    .build();

    /**
     * 编译 JsonPath 表达式（供各 Handler 复用）。
     * <p>仅做路径编译；统一解析语义（Jackson 提供器 + SUPPRESS_EXCEPTIONS）在
     * {@code .read(document, JSONPATH_CONFIG)} 时生效，故此处无需在编译期绑定配置。
     */
    public static com.jayway.jsonpath.JsonPath compileJsonPath(String expression) {
        return JsonPath.compile(expression);
    }


    /**
     * 检查请求是否匹配规则中定义的所有请求条件。
     *
     * <p>⭐ Phase 2（enterprise-api-interception-design-final.md）：匹配逻辑已统一收敛到
     * {@link ApiMatcher}，本方法作为兼容入口委托给 {@link ApiMatcher#matchesRequest(Route)}，
     * 保证 MONITOR / MODIFY / MOCK / DELAY 四种能力共用同一套匹配实现。
     *
     * @param route Playwright Route 对象
     * @param rule  路由规则
     * @return true = 匹配所有条件，应该处理；false = 不匹配，跳过
     */
    public static boolean requestMatches(Route route, RouteRule rule) {
        return new ApiMatcher(rule).matchesRequest(route);
    }

    // ═══════════════════════════════════════════════════════════════
    // 各维度匹配方法
    // ═══════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    // ⭐ Phase 2: 请求匹配逻辑已统一收敛到 ApiMatcher（见 requestMatches 委托）。
    //   原 matchResourceType / matchMethod / matchHeaders / matchQueryParams /
    //   matchContentType / matchBodyRegex / matchReferrer / matchOrigin /
    //   matchFrame / matchNavigation 十个私有方法已迁移至 ApiMatcher，此处删除避免死代码。
    // ═══════════════════════════════════════════════════════════════

    /**
     * ⭐ 统一缓存淘汰：弱一致性批量移除约 1/4 条目（与 MonitorHandler /
     * ApiCaptureContext 同源策略）。避免 entrySet().iterator().remove() 在结构变更时抛
     * IllegalStateException，也避免简单 map.clear() 使缓存命中率瞬间归零。
     * 供 RouteEngine / ApiCaptureContext / 各 Handler 的 JSONPATH_CACHE 等复用。
     */
    public static void evictOldestQuarter(Map<?, ?> map) {
        if (map == null || map.isEmpty()) return;
        int target = Math.max(1, map.size() / 4);
        int removed = 0;
        Iterator<?> it = map.keySet().iterator();
        while (it.hasNext() && removed < target) {
            Object key = it.next();
            map.remove(key);
            removed++;
        }
    }

    /**
     * 从缓存获取或编译一个 JsonPath 表达式（⭐ P2-15：单一共享入口，供 ModifyHandler /
     * MonitorHandler 复用同一份 JSONPATH_CACHE）。
     *
     * @param expression JsonPath 表达式字符串（作为缓存 key）
     * @return 编译后的 JsonPath
     */
    public static JsonPath compileJsonPathCached(String expression) {
        if (JSONPATH_CACHE.size() >= JSONPATH_CACHE_MAX) {
            evictOldestQuarter(JSONPATH_CACHE);
        }
        return JSONPATH_CACHE.computeIfAbsent(expression, RouteUtil::compileJsonPath);
    }

    /** 清空 JsonPath 缓存（供 ModifyHandler.clearJsonPathCache 等委托调用） */
    public static void clearJsonPathCache() {
        JSONPATH_CACHE.clear();
    }

    /** 获取 JsonPath 缓存条目数（用于监控） */
    public static int getJsonPathCacheSize() {
        return JSONPATH_CACHE.size();
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

    // ═══════════════════════════════════════════════════════════════
    // ⭐ S2: URL 脱敏 — 日志中隐藏 query 参数中的敏感信息
    // ═══════════════════════════════════════════════════════════════

    /** 常见敏感 query 参数名（小写） */
    private static final Set<String> SENSITIVE_QUERY_KEYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "token", "accesstoken", "access_token", "apikey", "api_key",
                    "secret", "password", "passwd", "credential", "auth",
                    "authorization", "sign", "signature", "key", "privatekey"
            )));

    /**
     * ⭐ S2: 对 URL 做脱敏处理，隐藏 query 参数名和值（仅保留 path）。
     * <p>避免 access token / API key 等敏感信息泄漏到日志/Sereinity 报告中。
     *
     * @param url 原始 URL
     * @return 脱敏后 URL，如 {@code https://host/api/users?(query stripped)}
     */
    public static String sanitizeUrl(String url) {
        if (url == null) return null;
        try {
            URI uri = new URI(url);
            String rawQuery = uri.getRawQuery();
            if (rawQuery == null || rawQuery.isEmpty()) {
                return url; // 无 query，直接返回
            }
            // 检测是否包含敏感参数
            boolean hasSensitive = false;
            for (String pair : rawQuery.split("&")) {
                String key = pair.split("=", 2)[0].toLowerCase();
                try {
                    key = URLDecoder.decode(key, StandardCharsets.UTF_8.name()).toLowerCase();
                } catch (Exception ignored) {}
                if (SENSITIVE_QUERY_KEYS.contains(key)) {
                    hasSensitive = true;
                    break;
                }
            }
            if (hasSensitive) {
                // 重建不含 query 的 URL
                return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(),
                        null, uri.getFragment()).toString();
            }
            return url;
        } catch (Exception e) {
            // ⭐ 修复 R1：解析失败时绝不出域原始 URL（可能含明文 token/key）。
            // 降级策略：用正则剥离所有 query（"?..." 及 "#..." 之后），并委托 SensitiveDataSanitizer
            // 兜底自由文本中的 Bearer/JWT/Authorization 等形态，避免敏感值泄漏到日志/报告。
            String stripped = url.replaceAll("[?#].*$", "");
            return com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SensitiveDataSanitizer
                    .sanitizeFreeText(stripped);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 页面/上下文关闭安全检查（防御已销毁页面上的路由处理导致挂起或报错）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 判断 Route 所属的 Page 是否已关闭。
     *
     * <p>用于路由处理入口/延迟回调前的短路：当 page 或 context 已关闭时，
     * 继续对 route 执行 fetch/resume 等操作会抛 {@link com.microsoft.playwright.PlaywrightException}
     * 或卡在失效连接上，污染日志并浪费（fetch 超时）时间。
     *
     * <p>读取 page 状态本身若抛异常（对象已失效），一律视为"已关闭"以安全放行。
     */
    public static boolean isPageClosed(Route route) {
        if (route == null) {
            return true;
        }
        try {
            return route.request().frame().page().isClosed();
        } catch (Exception e) {
            // 任何读取异常都视为页面已不可用，安全放行。
            return true;
        }
    }

    /**
     * 判断 Page 是否已关闭。
     *
     * <p>用于在响应体读取前检查页面状态，避免对已关闭页面执行耗时操作。
     */
    public static boolean isPageClosed(Page page) {
        if (page == null) return true;
        try {
            return page.isClosed();
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 对已关闭页面的 route 做幂等放行，避免悬挂。
     * 若 route 已处置，resume 可能抛异常，这里静默吞掉。
     */
    public static void resumeIfOpen(Route route) {
        try {
            route.resume();
        } catch (Exception ignored) {
            // route 可能已被处置或 page 已关闭，忽略。
        }
    }

    /**
     * 安全 fallback：把请求交给【下一个】匹配的 route handler。
     *
     * <p>⭐ 与 {@link #resumeIfOpen(Route)} 的关键差异：
     * <ul>
     *   <li>{@code resume()} —— <b>终结</b> Playwright 的 handler 链，请求直接放行到网络；
     *       后续注册的同 pattern handler <b>不会再被调用</b>。</li>
     *   <li>{@code fallback()} —— 语义是"本 handler 不处理，交给下一个 handler"；
     *       若已无下一个 handler，才退化为继续发往网络。</li>
     * </ul>
     *
     * <p>适用于「本 handler 已无规则可依」的场景（如规则链被 {@code clear()} 就地清空）：
     * 此时若用 resume，会连后续重新注册的同 pattern handler 一并终结，导致规则静默失效。
     *
     * <p>降级保障：fallback 在部分场景可能不被支持（如浏览器版本较旧或已无后续 handler），
     * 此时退化为 resume，<b>保证请求绝不被挂起</b>。
     */
    public static void fallbackIfOpen(Route route) {
        if (route == null) return;
        try {
            route.fallback();
        } catch (Exception e) {
            try {
                route.resume();
            } catch (Exception ignored) {
                // route 可能已被处置或 page 已关闭，忽略。
            }
        }
    }

    /**
     * 判断 Playwright 异常是否表示 route 对象已失效/销毁。
     *
     * <p>生命周期契约修复：Chromium 下 route 销毁后操作主要抛
     * {@code Connection closed} / {@code Target closed}；而 Firefox/WebKit 下 route 在
     * 请求完成或被自动清理后，对其 resume/fulfill/abort 会抛
     * {@code Object doesn't exist: route} / {@code route has been already handled}
     * 等不同文案。统一识别这些"已死"信号，避免误判为可操作对象而重复调用。
     *
     * @param e 捕获的异常（可为 null）
     * @return true 表示 route 已失效，应静默 no-op 而非再次尝试
     */
    public static boolean isRouteDeadException(Throwable e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null) {
            // 无 message 时向上回溯（Playwright 常包装一层）
            Throwable cause = e.getCause();
            if (cause != null && cause.getMessage() != null) {
                msg = cause.getMessage();
            } else {
                return false;
            }
        }
        String m = msg.toLowerCase();
        // ⭐ 修复 5.4：覆盖 Playwright 全部生命周期失效文案（Chromium / Firefox / WebKit 差异）
        return m.contains("object doesn't exist")
                || m.contains("route")
                && (m.contains("doesn't exist") || m.contains("already handled")
                    || m.contains("already disposed") || m.contains("already closed")
                    || m.contains("connection closed") || m.contains("target closed")
                    || m.contains("context closed") || m.contains("execution context"));
    }

    /**
     * 安全 resume：route 已死则静默跳过，绝不抛异常、绝不重复操作。
     *
     * <p>exactly-one 与生命周期契约的统一守门人：所有对 route.resume() 的调用
     * 都应经过本方法（或 safeFulfill/safeAbort），确保 Firefox/WebKit 下销毁的
     * route 不会被反复操作而污染日志、计数与延迟路径的落库逻辑。
     */
    public static void safeResume(Route route) {
        if (route == null) return;
        try {
            route.resume();
        } catch (Exception e) {
            if (!isRouteDeadException(e)) {
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteUtil] safeResume unexpected error (ignored): {}", e.getMessage());
            }
        }
    }

    /**
     * 安全 resume（带修改请求选项）：route 已死则静默跳过。
     * B 方案 MODIFY 用其下发改写后的请求（method/headers/postData），由浏览器发真实请求。
     */
    public static void safeResume(Route route, Route.ResumeOptions options) {
        if (route == null || options == null) return;
        try {
            route.resume(options);
        } catch (Exception e) {
            if (!isRouteDeadException(e)) {
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteUtil] safeResume(opts) unexpected error (ignored): {}", e.getMessage());
            }
        }
    }

    /**
     * 从 urlPattern 提取字面前缀（去除通配符），用于宽松匹配响应 URL。
     * 与 MonitorHandler.literalPathOf 等价，提取至此便于 Handler 间复用。
     */
    public static String literalPathOf(String urlPattern) {
        if (urlPattern == null || urlPattern.isEmpty()) return null;
        String p = urlPattern;
        while (p.startsWith("**")) p = p.substring(2);
        while (p.endsWith("**")) p = p.substring(0, p.length() - 2);
        int star = p.indexOf('*');
        if (star >= 0) p = p.substring(0, star);
        return p.isEmpty() ? null : p;
    }

    /** 安全 fulfill：route 已死则静默跳过。 */
    public static void safeFulfill(Route route, Route.FulfillOptions options) {
        if (route == null) return;
        try {
            route.fulfill(options);
        } catch (Exception e) {
            if (!isRouteDeadException(e)) {
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteUtil] safeFulfill unexpected error (ignored): {}", e.getMessage());
            }
        }
    }

    /** 安全 abort：route 已死则静默跳过。 */
    public static void safeAbort(Route route) {
        if (route == null) return;
        try {
            route.abort();
        } catch (Exception e) {
            if (!isRouteDeadException(e)) {
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[RouteUtil] safeAbort unexpected error (ignored): {}", e.getMessage());
            }
        }
    }

    /** 根据 Route 所属 BrowserContext 获取隔离的 API 捕获上下文。 */
    public static ApiCaptureContext captureContext(Route route) {
        if (route != null) {
            try {
                if (route.request() != null && route.request().frame() != null
                        && route.request().frame().page() != null) {
                    return ApiCaptureContext.forContext(route.request().frame().page().context());
                }
            } catch (Exception ignored) {
                // Page/Context 已销毁时回退共享上下文，保证异常路径仍可安全放行。
            }
        }
        return ApiCaptureContext.getCurrent();
    }
}
