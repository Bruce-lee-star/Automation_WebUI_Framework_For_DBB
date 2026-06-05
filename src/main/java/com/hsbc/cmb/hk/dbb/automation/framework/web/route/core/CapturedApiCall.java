package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 一次完整的 API 调用快照 — 封装请求/响应的核心信息。
 *
 * <p>由 {@link MonitorHandler} 在捕获响应时创建，存入 {@link ApiCaptureContext}。
 * 测试代码通过 {@code ctx.getApiCalls(endpoint)} 获取。
 *
 * <pre>{@code
 * CapturedApiCall call = ctx.getLastApiCall("/api/login");
 * int status = call.statusCode();
 * String token = call.responseHeader("Authorization");
 * Object userId = call.json("$.data.userId");
 * }</pre>
 */
public class CapturedApiCall {
    private static final Logger LOGGER = LoggerFactory.getLogger(CapturedApiCall.class);

    // ── 请求信息 ──
    private final String endpoint;   // urlPattern（即 api() 中配置的 endpoint），用作存储/查询 key
    private final String method;
    private final Map<String, String> requestHeaders;
    private final String requestUrl;  // 实际请求的完整 URL（用于毫秒级精确检索）

    // ── 响应信息 ──
    private final int statusCode;
    private final Map<String, String> responseHeaders;
    private final String responseBody;

    // ── 时间信息 ──
    private final long timestamp;

    // ═══════════════════════════════════════════════════════════════
    // ⭐ 性能优化：懒缓存 JsonPath DocumentContext（避免重复解析 JSON）
    // ═══════════════════════════════════════════════════════════════
    private transient volatile DocumentContext cachedDocContext;

    /**
     * @param endpoint        urlPattern（即 {@code RouteDsl.api(endpoint)} 中配置的字符串），
     *                        用作存储/查询的 key
     * @param method          HTTP 方法
     * @param requestHeaders  请求头
     * @param statusCode      HTTP 状态码
     * @param responseHeaders 响应头
     * @param responseBody    响应体
     * @param timestamp       捕获时间戳
     * @deprecated 推荐使用带 {@code requestUrl} 参数的构造器，以启用毫秒级 URL 精确检索
     */
    @Deprecated
    public CapturedApiCall(String endpoint, String method, Map<String, String> requestHeaders,
                    int statusCode, Map<String, String> responseHeaders,
                    String responseBody, long timestamp) {
        this(endpoint, method, requestHeaders, statusCode, responseHeaders,
                responseBody, timestamp, null);
    }

    /**
     * @param endpoint        urlPattern（即 {@code RouteDsl.api(endpoint)} 中配置的字符串），
     *                        用作存储/查询的 key
     * @param method          HTTP 方法
     * @param requestHeaders  请求头
     * @param statusCode      HTTP 状态码
     * @param responseHeaders 响应头
     * @param responseBody    响应体
     * @param timestamp       捕获时间戳
     * @param requestUrl      实际请求的完整 URL（用于毫秒级精确检索，可为 null）
     */
    public CapturedApiCall(String endpoint, String method, Map<String, String> requestHeaders,
                    int statusCode, Map<String, String> responseHeaders,
                    String responseBody, long timestamp, String requestUrl) {
        this.endpoint = endpoint;
        this.requestUrl = requestUrl;
        this.method = (method != null) ? method.toUpperCase() : "UNKNOWN";
        this.requestHeaders = requestHeaders != null
                ? Collections.unmodifiableMap(new HashMap<>(requestHeaders))
                : Collections.emptyMap();
        this.statusCode = statusCode;
        this.responseHeaders = responseHeaders != null
                ? Collections.unmodifiableMap(new HashMap<>(responseHeaders))
                : Collections.emptyMap();
        this.responseBody = responseBody;
        this.timestamp = timestamp;
    }

    // ═══════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════

    /** 请求端点（即 {@code api(endpoint)} 传入的 urlPattern），即存储和查询所用的 key */
    public String endpoint() { return endpoint; }

    /** 实际请求的完整 URL（如 {@code http://host:port/api/users/1}），可能为 null */
    public String requestUrl() { return requestUrl; }

    /** HTTP 方法（大写） */
    public String method() { return method; }

    /** 请求头（不可变） */
    public Map<String, String> requestHeaders() { return requestHeaders; }

    /** HTTP 状态码 */
    public int statusCode() { return statusCode; }

    /** 响应头（不可变） */
    public Map<String, String> responseHeaders() { return responseHeaders; }

    /** 响应体字符串 */
    public String responseBody() { return responseBody; }

    /** 捕获时间戳（System.currentTimeMillis()） */
    public long timestamp() { return timestamp; }

    // ═══════════════════════════════════════════════════════════
    // 便捷查询
    // ═══════════════════════════════════════════════════════════

    /**
     * 查询单个请求头（大小写不敏感）。
     *
     * @return 头值，未找到返回 null
     */
    public String requestHeader(String name) {
        return findHeader(requestHeaders, name);
    }

    /**
     * 查询单个响应头（大小写不敏感）。
     *
     * @return 头值，未找到返回 null
     */
    public String responseHeader(String name) {
        return findHeader(responseHeaders, name);
    }

    /**
     * 按 JsonPath 从响应体中提取 JSON 字段值。
     *
     * <p><b>性能优化</b>：内部懒缓存 {@link DocumentContext}，
     * 同一 {@code CapturedApiCall} 多次调用 {@code json()} 时只解析一次 JSON。
     *
     * <pre>{@code
     * Object id = call.json("$.data.userId");
     * String name = call.json("$.data.name", String.class);
     * }</pre>
     *
     * @param jsonPath JsonPath 表达式
     * @return 提取的字段值，路径无效返回 null
     */
    public Object json(String jsonPath) {
        if (responseBody == null || jsonPath == null) return null;
        try {
            DocumentContext ctx = getOrParseDocument();
            return ctx.read(jsonPath);
        } catch (Exception e) {
            LOGGER.warn("[CapturedApiCall] Failed to extract '{}' from {}: {}",
                    jsonPath, endpoint, e.getMessage());
            return null;
        }
    }

    /**
     * 按 JsonPath 从响应体中提取 JSON 字段值（指定类型）。
     *
     * <p><b>性能优化</b>：复用懒缓存的 {@link DocumentContext}，避免重复 JSON 解析。
     *
     * @param jsonPath JsonPath 表达式
     * @param type     目标类型
     * @param <T>      泛型
     * @return 提取的字段值
     */
    @SuppressWarnings("unchecked")
    public <T> T json(String jsonPath, Class<T> type) {
        if (responseBody == null || jsonPath == null) return null;
        try {
            DocumentContext ctx = getOrParseDocument();
            return ctx.read(jsonPath, type);
        } catch (Exception e) {
            LOGGER.warn("[CapturedApiCall] Failed to extract '{}' as {} from {}: {}",
                    jsonPath, type.getSimpleName(), endpoint, e.getMessage());
            return null;
        }
    }

    /**
     * 懒缓存 — 首次调用时解析 JSON，后续复用 DocumentContext。
     * <p>使用 DCL + volatile 保证线程安全且无锁竞争。
     */
    private DocumentContext getOrParseDocument() {
        DocumentContext ctx = cachedDocContext;
        if (ctx == null) {
            synchronized (this) {
                ctx = cachedDocContext;
                if (ctx == null) {
                    ctx = JsonPath.parse(responseBody);
                    cachedDocContext = ctx;
                }
            }
        }
        return ctx;
    }

    // ═══════════════════════════════════════════════════════════
    // 判断
    // ═══════════════════════════════════════════════════════════

    /** 状态码是否为 2xx */
    public boolean isOk() {
        return statusCode >= 200 && statusCode < 300;
    }

    /** 状态码是否为 4xx */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    /** 状态码是否为 5xx */
    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }

    // ═══════════════════════════════════════════════════════════
    // Object
    // ═══════════════════════════════════════════════════════════

    @Override
    public String toString() {
        return String.format("CapturedApiCall{%s %s → %d, body=%d chars}",
                method, endpoint, statusCode,
                responseBody != null ? responseBody.length() : 0);
    }

    // ═══════════════════════════════════════════════════════════
    // internal
    // ═══════════════════════════════════════════════════════════

    private static String findHeader(Map<String, String> headers, String name) {
        if (name == null || headers == null) return null;
        // 精确匹配
        String value = headers.get(name);
        if (value != null) return value;
        // 大小写不敏感匹配
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }
}
