package com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.client;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.client.ApiContextScope;
import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.core.entity.ApiRequestEntity;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.HttpHeader;
import com.microsoft.playwright.options.RequestOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * PlaywrightApiClient - 基于 Playwright {@link APIRequestContext} 的 HTTP 执行器
 * <p>
 * 职责单一：把 {@link ApiRequestEntity} 中的请求描述转换为 Playwright 调用，并把响应写回 entity。
 * 不负责断言、不负责配置加载。
 * <p>
 * 企业级能力：
 * <ul>
 *   <li><b>重试</b>：由可配置的 {@link RetryStrategy} 驱动（默认 5xx + 传输异常重试，4xx 不重试）。</li>
 *   <li><b>拦截器</b>：{@link ApiInterceptor} 链，发送前后统一处理签名/脱敏/traceId 等。</li>
 *   <li><b>多值头 / Set-Cookie</b>：使用 {@code response.headersArray()} 精确解析。</li>
 *   <li><b>脱敏</b>：日志/报告对 Authorization/Cookie 等敏感头掩码。</li>
 *   <li><b>资源释放</b>：读取响应体后即 {@code dispose()} 原生响应，避免底层资源泄漏。</li>
 *   <li><b>Content-Type 自动补</b>：JSON body 且未显式设置时自动补 {@code application/json}。</li>
 *   <li><b>multipart 文件上传</b>：通过 {@code multipartParams} 支持文件字段。</li>
 * </ul>
 */
public final class PlaywrightApiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaywrightApiClient.class);
    private static final int MAX_LOG_BODY = 2000;

    /** 敏感头名称（小写），日志/报告中掩码（包级可见，供 Helper 复用） */
    static final Set<String> SENSITIVE_HEADERS = new HashSet<>(Set.of(
            "authorization", "cookie", "set-cookie", "proxy-authorization", "x-api-key", "x-auth-token"));

    /** 敏感请求/响应体 JSON 字段名（小写），写入报告前掩码其值，避免凭据落入 Serenity 报告 */
    private static final Set<String> SENSITIVE_BODY_KEYS = new HashSet<>(Set.of(
            "password", "passwd", "pwd", "pin", "token", "access_token", "refresh_token",
            "secret", "secretkey", "secret_key", "apikey", "api_key", "authorization",
            "client_secret", "sessionid", "session_id", "credential", "otp", "cvv"));

    /** 出现在 URL query 中的敏感参数名（小写），日志打印前掩码其值，避免凭据以明文写入 INFO 日志 */
    private static final Set<String> SENSITIVE_QUERY_KEYS = new HashSet<>(Set.of(
            "password", "passwd", "pwd", "pin", "token", "access_token", "refresh_token",
            "secret", "secretkey", "secret_key", "apikey", "api_key", "authorization",
            "client_secret", "sessionid", "session_id", "credential", "otp", "cvv",
            "signature", "sig", "code", "key", "verification", "ticket"));

    /** 复用的 Jackson 实例（避免在 maskSensitiveBody 中每次新建，降低开销） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 判断头名是否为敏感头（供 Helper 日志掩码复用） */
    static boolean isSensitiveHeader(String name) {
        return name != null && SENSITIVE_HEADERS.contains(name.toLowerCase());
    }

    private static boolean isSensitiveBodyKey(String name) {
        if (name == null) return false;
        return SENSITIVE_BODY_KEYS.contains(name.toLowerCase());
    }

    /** 全局默认重试策略（基于 FrameworkConfig）；场景线程可通过自己作用域的 ApiContextScope 覆盖 */
    private static volatile RetryStrategy GLOBAL_RETRY_STRATEGY = new RetryStrategy.DefaultRetryStrategy();

    /** 全局默认拦截器链（注册一次即对所有线程生效）；场景线程还可在自己的 ApiContextScope 追加场景级拦截器 */
    private static final List<ApiInterceptor> GLOBAL_INTERCEPTORS = new CopyOnWriteArrayList<>();

    private PlaywrightApiClient() {
    }

    /**
     * 注册全局默认重试策略（对所有线程生效）。场景级覆盖请用 {@code ApiContextScope.current().setRetryStrategy(...)}。
     */
    public static void setRetryStrategy(RetryStrategy strategy) {
        if (strategy != null) {
            GLOBAL_RETRY_STRATEGY = strategy;
        }
    }

    /**
     * 注册全局默认拦截器（对所有线程生效）。场景级追加请用 {@code ApiContextScope.current().addInterceptor(...)}。
     */
    public static void addInterceptor(ApiInterceptor interceptor) {
        if (interceptor != null) {
            GLOBAL_INTERCEPTORS.add(interceptor);
        }
    }

    /**
     * 执行一次 HTTP 请求，并把响应写回 entity（含重试与耗时统计）。
     */
    public static void execute(ApiRequestEntity entity, PlaywrightApiHttpMethod method) {
        // 发送前拦截器：全局默认链 + 当前场景作用域链（并行安全：每线程独立，互不干扰）
        ApiContextScope scope = ApiContextScope.current();
        List<ApiInterceptor> scopeInterceptors = scope.getInterceptors();
        for (ApiInterceptor i : GLOBAL_INTERCEPTORS) {
            i.onRequest(entity);
        }
        for (ApiInterceptor i : scopeInterceptors) {
            i.onRequest(entity);
        }

        APIRequestContext context = PlaywrightApiClientManager.getContext(entity);
        String url = buildUrl(entity);
        Map<String, String> headers = buildHeaders(entity);
        long timeout = entity.getRequestTimeout() != null
                ? entity.getRequestTimeout()
                : (long) FrameworkConfig.getConnectionTimeout();

        if (entity.isApiRequestResponseLogsEnabled()) {
            // INFO 级别即输出完整请求交换块（头/体均经脱敏，避免凭据落入日志）
            LOGGER.info("[API] >>> {} {}", method, maskSensitiveUrl(url));
            LOGGER.info("[API] >>> headers: {}", maskHeaders(headers));
            // 仅对有请求体的方法打印 body；GET/DELETE 复用同一 entity 时不会遗留上一次 payload，避免误导
            if (method == PlaywrightApiHttpMethod.POST || method == PlaywrightApiHttpMethod.PUT || method == PlaywrightApiHttpMethod.PATCH) {
                String reqBody = entity.getRequestPayload();
                if (reqBody != null && !reqBody.isEmpty()) {
                    LOGGER.info("[API] >>> body: {}", truncate(maskSensitiveBody(reqBody)));
                }
            }
        }

        // 重试策略：优先场景作用域覆盖，否则全局默认（并行安全，互不串扰）
        RetryStrategy strategy = scope.getRetryStrategy() != null ? scope.getRetryStrategy() : GLOBAL_RETRY_STRATEGY;
        int maxAttempts = Math.max(1, strategy.maxAttempts());
        long retryDelay = strategy.delayMillis();
        APIResponse response = null;
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long start = System.currentTimeMillis();
            try {
                response = doRequest(context, method, url, headers, timeout, entity);
                entity.setResponseTimeMs(System.currentTimeMillis() - start);

                int status = response.status();
                if (strategy.shouldRetry(attempt, status, null)) {
                    LOGGER.warn("[API] {} {} -> {} (retryable, retry {}/{})",
                            method, maskSensitiveUrl(url), status, attempt, maxAttempts);
                    disposeQuietly(response);
                    response = null;
                    sleepQuietly(retryDelay);
                    continue;
                }
                populateEntity(entity, response);
                // 数据已拷贝，及时释放原生响应，避免底层资源泄漏
                disposeQuietly(response);
                response = null;

                if (entity.isApiRequestResponseLogsEnabled()) {
                    LOGGER.info("[API] <<< {} ({} ms)", status, entity.getResponseTimeMs());
                    LOGGER.info("[API] <<< headers: {}", maskHeaders(entity.getResponseHeaders()));
                    String respBody = entity.getResponsePayload();
                    if (respBody != null && !respBody.isEmpty()) {
                        LOGGER.info("[API] <<< body: {}", truncate(maskSensitiveBody(respBody)));
                    }
                }
                for (ApiInterceptor i : GLOBAL_INTERCEPTORS) {
                    i.onResponse(entity);
                }
                for (ApiInterceptor i : scopeInterceptors) {
                    i.onResponse(entity);
                }
                return;
            } catch (Exception e) {
                entity.setResponseTimeMs(System.currentTimeMillis() - start);
                lastError = new RuntimeException("Playwright API request failed: " + method + " " + url, e);
                if (strategy.shouldRetry(attempt, 0, e)) {
                    LOGGER.warn("[API] {} {} failed (attempt {}/{}): {}",
                            method, maskSensitiveUrl(url), attempt, maxAttempts, e.getMessage());
                    disposeQuietly(response);
                    response = null;
                    sleepQuietly(retryDelay);
                }
            }
        }

        // 到达此处说明已耗尽 maxAttempts 次尝试（最后一次成功分支已 return，失败分支也仅当
        // shouldRetry 为真才 continue），response 必为 null，无需再次 populate/dispose。
        LOGGER.error("[API] Request ultimately failed after {} attempts: {} {}", maxAttempts, method, maskSensitiveUrl(url));
        throw lastError != null ? lastError : new RuntimeException("Playwright API request failed: " + method + " " + url);
    }

    private static APIResponse doRequest(APIRequestContext context, PlaywrightApiHttpMethod method,
                                         String url, Map<String, String> headers, long timeout,
                                         ApiRequestEntity entity) {
        RequestOptions opts = RequestOptions.create().setTimeout((double) timeout);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            opts.setHeader(e.getKey(), e.getValue());
        }
        return switch (method) {
            case GET -> context.get(url, opts);
            case DELETE -> context.delete(url, opts);
            case POST -> {
                if (hasMultipart(entity)) {
                    opts.setMultipart(toFormData(entity.getMultipartParams()));
                } else if (hasForm(entity)) {
                    opts.setForm(toFormData(entity.getFormParams()));
                } else {
                    opts.setData(payloadOrEmpty(entity));
                }
                yield context.post(url, opts);
            }
            case PUT -> context.put(url, opts.setData(payloadOrEmpty(entity)));
            case PATCH -> context.patch(url, opts.setData(payloadOrEmpty(entity)));
        };
    }

    /**
     * 把键值对转换为 Playwright {@link FormData}：文件值（Path / FilePayload）原样传递，其余按字符串。
     */
    private static FormData toFormData(Map<String, Object> params) {
        FormData form = FormData.create();
        if (params != null) {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                Object v = e.getValue();
                if (v instanceof java.nio.file.Path) {
                    form.set(e.getKey(), (java.nio.file.Path) v);
                } else if (v instanceof FilePayload) {
                    form.set(e.getKey(), (FilePayload) v);
                } else {
                    form.set(e.getKey(), String.valueOf(v));
                }
            }
        }
        return form;
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void disposeQuietly(APIResponse response) {
        if (response == null) return;
        try {
            response.dispose();
        } catch (Throwable t) {
            LOGGER.debug("Failed to dispose discarded APIResponse: {}", t.getMessage());
        }
    }

    private static boolean hasForm(ApiRequestEntity entity) {
        return entity.getFormParams() != null && !entity.getFormParams().isEmpty();
    }

    private static boolean hasMultipart(ApiRequestEntity entity) {
        return entity.getMultipartParams() != null && !entity.getMultipartParams().isEmpty();
    }

    private static String payloadOrEmpty(ApiRequestEntity entity) {
        return entity.getRequestPayload() != null ? entity.getRequestPayload() : "";
    }

    private static void populateEntity(ApiRequestEntity entity, APIResponse response) {
        entity.setResponseCode(response.status());

        // 使用 headersArray() 精确处理多值头（尤其是多个 set-cookie）。HttpHeader 使用公共字段 name/value。
        Map<String, String> headers = new HashMap<>();
        Map<String, String> cookies = new HashMap<>();
        for (HttpHeader h : response.headersArray()) {
            String name = h.name.toLowerCase();
            headers.merge(name, h.value, (a, b) -> a + ", " + b);
            if ("set-cookie".equals(name)) {
                parseSingleSetCookie(h.value, cookies);
            }
        }
        entity.setResponseHeaders(headers);
        if (!cookies.isEmpty()) {
            entity.setResponseCookies(cookies);
        }

        try {
            entity.setResponsePayload(response.text());
        } catch (Exception e) {
            LOGGER.warn("[API] Failed to read response text: {}", e.getMessage());
            entity.setResponsePayload("");
        }
    }

    private static void parseSingleSetCookie(String setCookieValue, Map<String, String> cookies) {
        // 单个 set-cookie 形如：name=value; Path=/; HttpOnly; Secure
        int idx = setCookieValue.indexOf('=');
        if (idx > 0) {
            String name = setCookieValue.substring(0, idx).trim();
            String value = setCookieValue.substring(idx + 1).split(";", 2)[0].trim();
            cookies.put(name, value);
        }
    }

    // ============ URL / headers 构造 ============

    private static String buildUrl(ApiRequestEntity entity) {
        String base = entity.getBaseUri() == null ? "" : entity.getBaseUri();
        if (base.isBlank()) {
            throw new IllegalStateException("baseUri is not set for entity"
                    + (entity.getEntityName() != null ? " '" + entity.getEntityName() + "'" : "")
                    + "; cannot build request URL. Configure the entity's baseUri or call withBaseUri(...).");
        }
        String path = (entity.getBasePath() == null ? "" : entity.getBasePath())
                + (entity.getEndpoint() == null ? "" : entity.getEndpoint());
        path = applyPathParams(path, entity.getPathParams());

        String trimmedBase = base.replaceAll("/+$", "");
        String trimmedPath = path.replaceAll("^/+", "");
        String url = trimmedBase + "/" + trimmedPath;

        String query = buildQueryString(entity.getQueryParams());
        if (!query.isEmpty()) {
            url += "?" + query;
        }
        return url;
    }

    private static String buildQueryString(Map<String, Object> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : queryParams.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(encode(e.getKey())).append('=').append(encode(String.valueOf(e.getValue())));
        }
        return sb.toString();
    }

    /**
     * RFC 3986 编码：URLEncoder 把空格编成 '+'，不符合 query/path 规范，这里统一把 '+' 改回 '%20'。
     * 其余保留字符（字母/数字/-._~）URLEncoder 已原样保留，符合规范。
     */
    private static String encode(String value) {
        if (value == null) return "";
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    private static String applyPathParams(String path, Map<String, Object> pathParams) {
        if (pathParams == null || pathParams.isEmpty()) {
            return path;
        }
        String result = path;
        for (Map.Entry<String, Object> e : pathParams.entrySet()) {
            // 路径参数值需编码（如含 / & = 会破坏 URL），同样走 RFC 3986 编码
            result = result.replace("{" + e.getKey() + "}", encode(String.valueOf(e.getValue())));
        }
        return result;
    }

    private static Map<String, String> buildHeaders(ApiRequestEntity entity) {
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, Object> e : entity.getRequestHeaders().entrySet()) {
            headers.put(e.getKey(), String.valueOf(e.getValue()));
        }
        // JSON body 且未显式设置 Content-Type 时自动补充
        boolean hasContentType = headers.keySet().stream().anyMatch(k -> "content-type".equalsIgnoreCase(k));
        if (!hasContentType && entity.getRequestPayload() != null && !entity.getRequestPayload().isEmpty()
                && !hasForm(entity) && !hasMultipart(entity)) {
            headers.put("Content-Type", "application/json");
        }
        // 把 cookies 合成为 Cookie 请求头（Playwright 按请求粒度不直接支持 cookie，统一走头）
        if (entity.getCookies() != null && !entity.getCookies().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> c : entity.getCookies().entrySet()) {
                sb.append(c.getKey()).append('=').append(c.getValue()).append("; ");
            }
            headers.put("Cookie", sb.toString());
        }
        return headers;
    }

    // ============ 脱敏 ============

    private static String maskHeaders(Map<String, String> headers) {
        if (headers == null) return "{}";
        Map<String, String> masked = new HashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            masked.put(e.getKey(), SENSITIVE_HEADERS.contains(e.getKey().toLowerCase()) ? "***" : e.getValue());
        }
        return masked.toString();
    }

    /**
     * 对写入日志的 URL 做脱敏：保留路径，但把 query 中敏感参数（token/secret/code/...）的值掩码，
     * 避免凭据（如 {@code ?access_token=xxx}）以明文落入 INFO 日志。
     */
    private static String maskSensitiveUrl(String url) {
        if (url == null) return null;
        int q = url.indexOf('?');
        if (q < 0) return url;
        String base = url.substring(0, q);
        String query = url.substring(q + 1);
        String[] pairs = query.split("&");
        StringBuilder sb = new StringBuilder(base).append('?');
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) sb.append('&');
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            if (eq < 0) {
                sb.append(pair);
            } else {
                String k = pair.substring(0, eq);
                String v = pair.substring(eq + 1);
                sb.append(k).append('=').append(isSensitiveQueryKey(k) ? "***" : v);
            }
        }
        return sb.toString();
    }

    private static boolean isSensitiveQueryKey(String name) {
        return name != null && SENSITIVE_QUERY_KEYS.contains(name.toLowerCase());
    }

    /**
     * 日志体截断：超过 {@link #MAX_LOG_BODY} 时截取前缀并标注被截断长度，避免单条日志过大刷屏。
     * 调用方应传入已脱敏后的文本（如 {@link #maskSensitiveBody}）。
     */
    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        if (body.length() > MAX_LOG_BODY) {
            return body.substring(0, MAX_LOG_BODY) + "...[truncated " + (body.length() - MAX_LOG_BODY) + " chars]";
        }
        return body;
    }

    /**
     * 对写入 Serenity 报告/日志的文本内容做敏感字段掩码。
     * 掩码已知的敏感请求头值，并对请求体 JSON 中的敏感字段值掩码，避免凭据落入测试报告。
     */
    public static String maskSensitiveForReport(ApiRequestEntity entity) {
        Map<String, String> masked = new HashMap<>();
        for (Map.Entry<String, Object> e : entity.getRequestHeaders().entrySet()) {
            String key = e.getKey();
            masked.put(key, isSensitiveHeader(key) ? "***" : String.valueOf(e.getValue()));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Headers: ").append(masked);
        if (entity.getRequestPayload() != null) {
            sb.append("\nBody: ").append(maskSensitiveBody(entity.getRequestPayload()));
        }
        return sb.toString();
    }

    /**
     * 对请求体（假定为 JSON）中的敏感字段值做掩码，避免密码/token 等落入 Serenity 报告。
     * 非 JSON 或解析失败时保守原样返回（主要凭据已通过头掩码保护）。
     */
    public static String maskSensitiveBody(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            maskNode(root);
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return body;
        }
    }

    private static void maskNode(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.forEach(PlaywrightApiClient::maskNode);
            if (node instanceof ObjectNode) {
                ObjectNode obj = (ObjectNode) node;
                List<String> fields = new ArrayList<>();
                Iterator<String> it = obj.fieldNames();
                while (it.hasNext()) {
                    fields.add(it.next());
                }
                for (String f : fields) {
                    if (isSensitiveBodyKey(f)) {
                        obj.put(f, "***");
                    }
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                maskNode(child);
            }
        }
    }
}
