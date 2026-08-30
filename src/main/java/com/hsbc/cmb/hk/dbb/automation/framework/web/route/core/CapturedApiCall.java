package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SensitiveDataSanitizer;
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
    private final String requestBody; // 请求体（POST/PUT 等），可为 null

    // ── 响应信息 ──
    private final int statusCode;
    private final Map<String, String> responseHeaders;
    private final String responseBody;

    // ── 采集信息 ──
    private final boolean fromMock;       // 是否来自 Mock 拦截
    private final String captureSource;   // 采集来源：MOCK / MODIFY / DELAY / MONITOR
    /**
     * ⭐ 产生本次快照的路由能力类型（MOCK / MODIFY / DELAY / MONITOR），
     * 供 {@link ApiCaptureContext#getAllByType(RouteHandleType)} 按能力维度检索。
     *
     * <p>与 {@code captureSource} 的区别：{@code captureSource} 是自由字符串（历史字段），
     * 本字段是枚举，作为「按类型查询」的<b>唯一判据</b>。
     */
    private final RouteHandleType handleType;

    // ── 修改详情（ModifyHandler 回填：headersSet / modifiedBody / bodyFieldsModified …） ──
    private final String modifyDetail;

    // ── 时间信息 ──
    private final long timestamp;
    private boolean bodyTruncated;
    private long originalBodyBytes;

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
     */
    public CapturedApiCall(String endpoint, String method, Map<String, String> requestHeaders,
                    int statusCode, Map<String, String> responseHeaders,
                    String responseBody, long timestamp) {
        this(endpoint, method, requestHeaders, statusCode, responseHeaders,
                responseBody, timestamp, null, null);
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
        this(endpoint, method, requestHeaders, statusCode, responseHeaders,
                responseBody, timestamp, requestUrl, null);
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
     * @param requestBody     请求体（POST/PUT 等），可为 null
     */
    public CapturedApiCall(String endpoint, String method, Map<String, String> requestHeaders,
                    int statusCode, Map<String, String> responseHeaders,
                    String responseBody, long timestamp, String requestUrl, String requestBody) {
        this(endpoint, method, requestHeaders, requestUrl, requestBody,
                statusCode, responseHeaders, responseBody, timestamp,
                false, RouteHandleType.MONITOR.name(), null, RouteHandleType.MONITOR);
    }

    /**
     * ⭐ 指定能力类型的构造器。各 Handler 落库时<b>必须</b>显式传入自身类型
     * （MOCK / MODIFY / DELAY / MONITOR），否则默认按 MONITOR 归类，
     * 会导致 {@code ApiCaptureContext.getAllByType(...)} 查不到 mock / delay 的调用。
     *
     * @param handleType 产生本次快照的路由能力类型
     */
    public CapturedApiCall(String endpoint, String method, Map<String, String> requestHeaders,
                    int statusCode, Map<String, String> responseHeaders,
                    String responseBody, long timestamp, String requestUrl, String requestBody,
                    RouteHandleType handleType) {
        this(endpoint, method, requestHeaders, requestUrl, requestBody,
                statusCode, responseHeaders, responseBody, timestamp,
                handleType == RouteHandleType.MOCK,
                handleType == null ? "MONITOR" : handleType.name(),
                null, handleType == null ? RouteHandleType.MONITOR : handleType);
    }

    /**
     * 含修改详情（modifyDetail）的构造器，供 ModifyHandler / MockHandler 回填「做了什么修改」。
     *
     * @param requestBody   请求体（POST/PUT 等），可为 null
     * @param modifyDetail  修改详情 JSON（如 headersSet / modifiedBody / bodyFieldsModified …），可为 null
     */
    public CapturedApiCall(String endpoint, String method, Map<String, String> requestHeaders,
                    int statusCode, Map<String, String> responseHeaders,
                    String responseBody, long timestamp, String requestUrl,
                    String requestBody, String modifyDetail) {
        this(endpoint, method, requestHeaders, requestUrl, requestBody,
                statusCode, responseHeaders, responseBody, timestamp,
                false, RouteHandleType.MODIFY.name(), modifyDetail, RouteHandleType.MODIFY);
    }

    /**
     * 全字段构造（供 Builder 使用）。
     */
    CapturedApiCall(String endpoint, String method, Map<String, String> requestHeaders,
                    String requestUrl, String requestBody,
                    int statusCode, Map<String, String> responseHeaders,
                    String responseBody, long timestamp,
                    boolean fromMock, String captureSource, RouteHandleType handleType) {
        this(endpoint, method, requestHeaders, requestUrl, requestBody,
                statusCode, responseHeaders, responseBody, timestamp,
                fromMock, captureSource, null, handleType);
    }

    CapturedApiCall(String endpoint, String method, Map<String, String> requestHeaders,
                    String requestUrl, String requestBody,
                    int statusCode, Map<String, String> responseHeaders,
                    String responseBody, long timestamp,
                    boolean fromMock, String captureSource, String modifyDetail,
                    RouteHandleType handleType) {
        this.endpoint = endpoint;
        this.requestUrl = requestUrl;
        this.method = (method != null) ? method.toUpperCase() : "UNKNOWN";
        // ⭐ 修复 R4：在构造期即对所有出站数据做脱敏，避免明文敏感 body/header 长期驻留内存，
        // 即使后续 DTO 导出时才脱敏，内存快照（heap dump / 序列化）也不会暴露原始值。
        this.requestBody = sanitizeBody(requestBody);
        this.requestHeaders = sanitizeHeaders(requestHeaders);
        this.statusCode = statusCode;
        this.responseHeaders = sanitizeHeaders(responseHeaders);
        this.responseBody = sanitizeBody(responseBody);
        this.fromMock = fromMock;
        this.captureSource = captureSource != null ? captureSource : "UNKNOWN";
        this.modifyDetail = sanitizeBody(modifyDetail);
        this.handleType = handleType != null ? handleType : RouteHandleType.MONITOR;
        this.timestamp = timestamp;
    }

    /** ⭐ 修复 R4：对单个 body 字符串按格式脱敏（JSON/XML/form/纯文本统一收口）。 */
    private static String sanitizeBody(String body) {
        if (body == null || body.isEmpty()) return body;
        return SensitiveDataSanitizer.sanitizeBody(body);
    }

    /** ⭐ 修复 R4：对所有 header 值脱敏（Authorization/Cookie/Set-Cookie 等含凭据）。 */
    private static Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return Collections.emptyMap();
        Map<String, String> sanitized = new HashMap<>(headers.size());
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String v = e.getValue();
            sanitized.put(e.getKey(), (v == null || v.isEmpty()) ? v : SensitiveDataSanitizer.sanitizeBody(v));
        }
        return Collections.unmodifiableMap(sanitized);
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

    public boolean bodyTruncated() { return bodyTruncated; }

    public long originalBodyBytes() { return originalBodyBytes; }

    public void markBodyTruncated(long originalBytes) {
        this.bodyTruncated = true;
        this.originalBodyBytes = Math.max(originalBytes, 0L);
    }

    /** 请求体字符串（POST/PUT 等），可能为 null */
    public String requestBody() { return requestBody; }

    /** 捕获时间戳（System.currentTimeMillis()） */
    public long timestamp() { return timestamp; }

    /** 是否来自 Mock 拦截 */
    public boolean fromMock() { return fromMock; }

    /** 采集来源：MOCK / MODIFY / DELAY / MONITOR */
    public String captureSource() { return captureSource; }

    /**
     * ⭐ 产生本次快照的路由能力类型（MOCK / MODIFY / DELAY / MONITOR）。
     *
     * <p>用于 {@code ApiCaptureContext.getAllByType(type)} 按能力维度检索。
     * 同一 endpoint 可能同时产生多条不同类型的快照（如 MODIFY + MONITOR 叠加）。
     */
    public RouteHandleType handleType() { return handleType; }

    /**
     * 是否为 <b>DELAY 维度标记</b>（非完整调用快照）。
     *
     * <p>DELAY 只记录「该请求被延迟过」这一事实，且在请求放行<b>之前</b>落库，
     * 因此不含响应信息：{@code statusCode == 0} 且 {@code responseBody == null}。
     * 它与同一请求的 MODIFY / MONITOR 完整快照<b>并存</b>，用于回答「哪些请求被延迟过」。
     *
     * <p>按 endpoint 做通用查询（{@code getLastApiCall} / {@code getApiCalls}）时，
     * 若只关心带响应的快照，可用本方法过滤掉延迟标记，避免拿到空 body。
     */
    public boolean isDelayMarker() {
        return handleType == RouteHandleType.DELAY;
    }

    /**
     * Modify/Mock 等场景的「修改详情」JSON。
     * 由对应 Handler 在拦截时构建（如 headersSet / modifiedBody / bodyFieldsModified 等），
     * 用于断言「做了什么修改」。无修改时为 null。
     */
    public String modifyDetail() { return modifyDetail; }

    /**
     * 是否 XHR 类型请求。
     *
     * <p>⚠️ <b>恒为 false</b>：全局旁路采集（CDP {@code Network.requestWillBeSent}）移除后，
     * Playwright {@code Route} 不暴露 {@code ResourceType}，框架已无资源类型数据源。
     * 保留本方法仅为向后兼容，请勿依赖其返回值做断言。
     *
     * @deprecated 无数据源支撑，恒返回 false；资源类型过滤请用
     *             {@code RouteDsl.resourceType(...)} / {@code onlyApi(...)} 在注册期完成。
     */
    @Deprecated
    public boolean isXhr() {
        return false;
    }

    /**
     * 是否 Fetch 类型请求。
     *
     * @deprecated 同 {@link #isXhr()}，无数据源支撑，恒返回 false。
     */
    @Deprecated
    public boolean isFetch() {
        return false;
    }

    /**
     * 是否 API 类请求（XHR / Fetch）。
     *
     * @deprecated 同 {@link #isXhr()}，无数据源支撑，恒返回 false。
     */
    @Deprecated
    public boolean isApiType() {
        return false;
    }

    /**
     * 资源类型原始字符串。
     *
     * @deprecated 无数据源支撑，恒返回 {@code "OTHER"}。
     */
    @Deprecated
    public String resourceTypeName() {
        return "OTHER";
    }

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
        if (jsonPath == null) return null;
        // 优先从响应体解析
        if (responseBody != null) {
            try {
                return getOrParseDocument().read(jsonPath);
            } catch (Exception e) {
                LOGGER.debug("[CapturedApiCall] '{}' not found in responseBody of {}: {}",
                        jsonPath, endpoint, e.getMessage());
            }
        }
        // 回退：从 modifyDetail（修改详情 JSON）解析，满足 modify/mock 场景的修改断言
        if (modifyDetail != null) {
            try {
                return JsonPath.parse(modifyDetail).read(jsonPath);
            } catch (Exception e) {
                LOGGER.debug("[CapturedApiCall] '{}' not found in modifyDetail of {}: {}",
                        jsonPath, endpoint, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 按 JsonPath 从「修改详情」({@link #modifyDetail()}) 中提取字段值。
     * 用于断言 Modify/Mock 等场景「做了什么修改」（headersSet / modifiedBody / bodyFieldsModified …）。
     * 修改详情查询不应回退到响应体，故提供专用入口。
     *
     * @param jsonPath JsonPath 表达式
     * @return 提取的字段值，无修改详情或不匹配时返回 null
     */
    public Object modifyDetailJson(String jsonPath) {
        if (jsonPath == null || modifyDetail == null) return null;
        try {
            return JsonPath.parse(modifyDetail).read(jsonPath);
        } catch (Exception e) {
            LOGGER.debug("[CapturedApiCall] '{}' not found in modifyDetail of {}: {}",
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
        if (jsonPath == null) return null;
        if (responseBody != null) {
            try {
                return getOrParseDocument().read(jsonPath, type);
            } catch (Exception e) {
                LOGGER.debug("[CapturedApiCall] '{}' as {} not found in responseBody of {}: {}",
                        jsonPath, type.getSimpleName(), endpoint, e.getMessage());
            }
        }
        if (modifyDetail != null) {
            try {
                return JsonPath.parse(modifyDetail).read(jsonPath, type);
            } catch (Exception e) {
                LOGGER.debug("[CapturedApiCall] '{}' as {} not found in modifyDetail of {}: {}",
                        jsonPath, type.getSimpleName(), endpoint, e.getMessage());
            }
        }
        return null;
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

    // ═══════════════════════════════════════════════════════════
    // Builder
    // ═══════════════════════════════════════════════════════════

    /**
     * Builder — 用于采集管道合成 CapturedApiCall。
     *
     * <p>支持 {@code fromMock} 和 {@code captureSource} 字段，
     * 兼容现有构造器，避免破坏现有调用方。
     */
    public static class Builder {
        private String endpoint;
        private String method;
        private Map<String, String> requestHeaders;
        private String requestUrl;
        private String requestBody;
        private int statusCode;
        private Map<String, String> responseHeaders;
        private String responseBody;
        private long timestamp;
        private boolean fromMock;
        private String captureSource;
        private RouteHandleType handleType;

        public Builder endpoint(String endpoint) { this.endpoint = endpoint; return this; }
        public Builder method(String method) { this.method = method; return this; }
        public Builder requestHeaders(Map<String, String> requestHeaders) { this.requestHeaders = requestHeaders; return this; }
        public Builder requestUrl(String requestUrl) { this.requestUrl = requestUrl; return this; }
        public Builder requestBody(String requestBody) { this.requestBody = requestBody; return this; }
        public Builder statusCode(int statusCode) { this.statusCode = statusCode; return this; }
        public Builder responseHeaders(Map<String, String> responseHeaders) { this.responseHeaders = responseHeaders; return this; }
        public Builder responseBody(String responseBody) { this.responseBody = responseBody; return this; }
        public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }
        public Builder fromMock(boolean fromMock) { this.fromMock = fromMock; return this; }
        public Builder captureSource(String captureSource) { this.captureSource = captureSource; return this; }

        /** ⭐ 指定能力类型（MOCK / MODIFY / DELAY / MONITOR）；未指定时由 captureSource 推导。 */
        public Builder handleType(RouteHandleType handleType) { this.handleType = handleType; return this; }

        public CapturedApiCall build() {
            RouteHandleType type = handleType;
            if (type == null && captureSource != null) {
                try {
                    type = RouteHandleType.valueOf(captureSource.toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    type = RouteHandleType.MONITOR;
                }
            }
            return new CapturedApiCall(
                    endpoint, method, requestHeaders, requestUrl, requestBody,
                    statusCode, responseHeaders, responseBody, timestamp,
                    fromMock, captureSource, type);
        }
    }
}
