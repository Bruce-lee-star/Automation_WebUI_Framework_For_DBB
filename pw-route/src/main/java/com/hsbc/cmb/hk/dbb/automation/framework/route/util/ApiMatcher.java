package com.hsbc.cmb.hk.dbb.automation.framework.route.util;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.microsoft.playwright.Request;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 请求匹配器 —— 对 Playwright {@link Request} 施加规则匹配条件。
 *
 * <p>语义对齐现有 {@code RouteDsl}（资源类型 / frame / 导航语义）：
 * <ul>
 *   <li>{@code matchMethod}：HTTP method 精确匹配（忽略大小写）；</li>
 *   <li>{@code resourceTypes}：显式集合；未显式设置时，{@code onlyApiCall=true} 默认
 *       xhr/fetch，否则匹配任意资源类型；</li>
 *   <li>{@code onlyApiCall=true}：跳过导航请求（isNavigationRequest）；</li>
 *   <li>{@code matchHeader} / {@code matchQuery}：精确匹配，全部条件须同时满足；</li>
 *   <li>{@code matchContentType} / {@code matchReferrer} / {@code matchOrigin} /
 *       {@code matchFrameUrl}：包含匹配；</li>
 *   <li>{@code matchBodyRegex}：Java 正则，body 为 null 时不匹配；</li>
 *   <li>{@code onlyMainFrame=true}（默认）：仅匹配主 frame 发起的请求
 *       （frame 非空且无父 frame；worker/导航之外的请求被跳过）。</li>
 * </ul>
 *
 * <p>并发安全：{@link ApiMatcher} 由 {@link ApiSpec} 一次性编译（正则预编译），
 * 不可变，可安全被多事件线程并发读取。实例按 spec 缓存（{@code ConcurrentHashMap}），
 * 同一 spec 只编译一次。
 */
public final class ApiMatcher {

    private static final Set<String> API_RESOURCE_TYPES = Set.of("xhr", "fetch");

    private final String method;            // 精确（忽略大小写），null=任意
    private final Set<String> resourceTypes; // 显式集合，null=按 onlyApiCall 推导
    private final Map<String, String> headers;       // 精确
    private final Map<String, String> queryParams;   // 精确
    private final Pattern bodyRegex;                 // null=任意
    private final String contentType;                // 包含，null=任意
    private final String referrer;                   // 包含，null=任意
    private final String origin;                     // 包含，null=任意
    private final String frameUrl;                   // 包含，null=任意
    private final boolean onlyMainFrame;
    private final boolean onlyApiCall;

    private ApiMatcher(String method, Set<String> resourceTypes, Map<String, String> headers,
                       Map<String, String> queryParams, Pattern bodyRegex, String contentType,
                       String referrer, String origin, String frameUrl,
                       boolean onlyMainFrame, boolean onlyApiCall) {
        this.method = method;
        this.resourceTypes = resourceTypes;
        this.headers = headers;
        this.queryParams = queryParams;
        this.bodyRegex = bodyRegex;
        this.contentType = contentType;
        this.referrer = referrer;
        this.origin = origin;
        this.frameUrl = frameUrl;
        this.onlyMainFrame = onlyMainFrame;
        this.onlyApiCall = onlyApiCall;
    }

    /** 从规则快照编译匹配器（预编译正则，只做一次）。 */
    public static ApiMatcher from(ApiSpec spec) {
        Pattern bodyRegex = null;
        if (spec.matchBodyRegex() != null) {
            bodyRegex = Pattern.compile(spec.matchBodyRegex());
        }
        return new ApiMatcher(
                spec.matchMethod(),
                spec.resourceTypes(),
                spec.matchHeaders(),
                spec.matchQueryParams(),
                bodyRegex,
                spec.matchContentType(),
                spec.matchReferrer(),
                spec.matchOrigin(),
                spec.matchFrameUrl(),
                spec.onlyMainFrame(),
                spec.onlyApiCall());
    }

    /** 事件线程调用：判定请求是否命中规则的全部条件（无阻塞）。 */
    public boolean matches(Request request) {
        // 1) method
        if (method != null && !method.equalsIgnoreCase(request.method())) {
            return false;
        }
        // 2) 资源类型：显式集合优先；否则 onlyApiCall 默认 xhr/fetch；再否则任意
        if (resourceTypes != null) {
            String rt = request.resourceType();
            if (rt == null || !resourceTypes.contains(rt)) {
                return false;
            }
        } else if (onlyApiCall) {
            String rt = request.resourceType();
            if (rt == null || !API_RESOURCE_TYPES.contains(rt)) {
                return false;
            }
        }
        // 3) 导航请求过滤（onlyApiCall）
        if (onlyApiCall && request.isNavigationRequest()) {
            return false;
        }
        // 4) 帧过滤
        if (onlyMainFrame) {
            com.microsoft.playwright.Frame frame = request.frame();
            if (frame == null || frame.parentFrame() != null) {
                return false;
            }
        }
        if (frameUrl != null) {
            com.microsoft.playwright.Frame frame = request.frame();
            if (frame == null || !frame.url().contains(frameUrl)) {
                return false;
            }
        }
        // 5) headers（精确，全部同时满足）
        if (!headers.isEmpty()) {
            Map<String, String> actual = request.headers();
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String value = actual.get(e.getKey().toLowerCase());
                if (value == null || !value.equals(e.getValue())) {
                    return false;
                }
            }
        }
        // 6) query（精确，全部同时满足；解析请求 URL 的 query 段）
        if (!queryParams.isEmpty()) {
            Map<String, String> actual = parseQuery(request.url());
            for (Map.Entry<String, String> e : queryParams.entrySet()) {
                if (!e.getValue().equals(actual.get(e.getKey()))) {
                    return false;
                }
            }
        }
        // 7) body 正则（无 body 不匹配）
        if (bodyRegex != null) {
            String body = request.postData();
            if (body == null || !bodyRegex.matcher(body).find()) {
                return false;
            }
        }
        // 8) 包含匹配
        if (contentType != null) {
            String ct = request.headers().get("content-type");
            if (ct == null || !ct.contains(contentType)) {
                return false;
            }
        }
        if (referrer != null) {
            String ref = request.headers().get("referer");
            if (ref == null || !ref.contains(referrer)) {
                return false;
            }
        }
        if (origin != null) {
            String ori = request.headers().get("origin");
            if (ori == null || !ori.contains(origin)) {
                return false;
            }
        }
        return true;
    }

    /** 解析 URL 的 query 段为 k=v（UTF-8 解码；解码失败时保留原值）。 */
    static Map<String, String> parseQuery(String url) {
        int q = url.indexOf('?');
        if (q < 0) {
            return Map.of();
        }
        String query = url.substring(q + 1);
        int fragment = query.indexOf('#');
        if (fragment >= 0) {
            query = query.substring(0, fragment);
        }
        Map<String, String> result = new HashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                result.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
                        URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                result.put(k, v);
            }
        }
        return result;
    }
}
