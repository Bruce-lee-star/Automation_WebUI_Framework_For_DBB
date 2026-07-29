package com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.core.entity;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.api.domain.enums.APIResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ApiRequestEntity - 请求/响应数据载体（Playwright API 版）
 * <p>
 * 与 RestAssured 版 {@code Entity} 等价，但<b>不依赖 RestAssured</b>，
 * 响应数据来自 Playwright 的 {@link APIResponse}。其余字段与 Entity 保持一致，
 * 以便上层步骤/POM 风格调用无缝迁移。
 * <p>
 * 传输无关的配置组件（ConfigProvider / EndpointProvider / HeadersAssemblers）可被本类直接复用。
 */
public class ApiRequestEntity {

    private static final Logger log = LoggerFactory.getLogger(ApiRequestEntity.class);

    private String entityName;
    private String baseUri;
    private String basePath = "";
    private String endpoint = "";

    private Map<String, Object> requestHeaders = new HashMap<>();
    private Map<String, Object> pathParams = new HashMap<>();
    private Map<String, Object> queryParams = new HashMap<>();
    private Map<String, Object> formParams = new HashMap<>();
    private Map<String, Object> cookies = new HashMap<>();

    private String requestPayload;
    private String responsePayload;
    private int responseCode;
    private Map<String, String> responseHeaders;
    private Map<String, String> responseCookies;
    /** 本次请求耗时（毫秒），由执行器写入，用于响应时间断言与报告 */
    private long responseTimeMs;

    private String proxyHost;
    private int proxyPort;
    private String proxySchema;

    /** multipart 表单参数（值可为 String 或 java.nio.file.Path 文件）；与 formParams 互斥 */
    private Map<String, Object> multipartParams = new HashMap<>();

    /** 单请求超时覆盖（毫秒）；为 null 时回退到 FrameworkConfig 的连接超时 */
    private Long requestTimeout;

    private boolean apiRequestResponseLogsEnabled;

    public ApiRequestEntity() {
        // 默认 baseUri 取自配置（与 RestAssured 版 Entity 一致）
        this.baseUri = APIResources.BASE_URI.toString();
        this.initializeApiRequestResponseLogging();
    }

    private void initializeApiRequestResponseLogging() {
        this.setApiRequestResponseLogsEnabled(FrameworkConfig.isApiRequestResponseLogsEnabled());
    }

    // ============ entityName（优先级：手动 > 系统属性 > 环境变量） ============
    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public String getEntityName() {
        if (this.entityName != null && !this.entityName.isEmpty()) {
            return this.entityName.toLowerCase(Locale.ENGLISH);
        }
        return "";
    }

    // ============ baseUri / basePath / endpoint ============
    public String getBaseUri() {
        return baseUri;
    }

    public void setBaseUri(String baseUri) {
        this.baseUri = baseUri;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    // ============ requestHeaders ============
    public Map<String, Object> getRequestHeaders() {
        return requestHeaders;
    }

    public void setRequestHeaders(Map<String, Object> requestHeaders) {
        this.requestHeaders = requestHeaders != null ? requestHeaders : new HashMap<>();
    }

    public void addRequestHeader(String name, Object value) {
        this.requestHeaders.put(name, value);
    }

    public void removeRequestHeader(String name) {
        this.requestHeaders.remove(name);
    }

    // ============ pathParams ============
    public Map<String, Object> getPathParams() {
        return pathParams;
    }

    public void setPathParams(Map<String, Object> pathParams) {
        this.pathParams = pathParams != null ? pathParams : new HashMap<>();
    }

    public void addPathParam(String name, Object value) {
        this.pathParams.put(name, value);
    }

    public void removePathParam(String name) {
        this.pathParams.remove(name);
    }

    // ============ queryParams ============
    public Map<String, Object> getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(Map<String, Object> queryParams) {
        this.queryParams = queryParams != null ? queryParams : new HashMap<>();
    }

    public void addQueryParam(String name, Object value) {
        this.queryParams.put(name, value);
    }

    public void removeQueryParam(String name) {
        this.queryParams.remove(name);
    }

    // ============ formParams ============
    public Map<String, Object> getFormParams() {
        return formParams;
    }

    public void setFormParams(Map<String, Object> formParams) {
        this.formParams = formParams != null ? formParams : new HashMap<>();
    }

    public void addFormParam(String name, Object value) {
        this.formParams.put(name, value);
    }

    public void removeFormParam(String name) {
        this.formParams.remove(name);
    }

    // ============ cookies ============
    public Map<String, Object> getCookies() {
        return cookies;
    }

    public void setCookies(Map<String, Object> cookies) {
        this.cookies = cookies != null ? cookies : new HashMap<>();
    }

    public void addCookie(String name, Object value) {
        this.cookies.put(name, value);
    }

    public void removeCookie(String name) {
        this.cookies.remove(name);
    }

    // ============ request / response payload ============
    public String getRequestPayload() {
        return requestPayload;
    }

    public void setRequestPayload(String requestPayload) {
        this.requestPayload = requestPayload;
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    public void setResponsePayload(String responsePayload) {
        this.responsePayload = responsePayload;
    }

    public int getResponseCode() {
        return responseCode;
    }

    public void setResponseCode(int responseCode) {
        this.responseCode = responseCode;
    }

    public Map<String, String> getResponseHeaders() {
        return responseHeaders;
    }

    public void setResponseHeaders(Map<String, String> responseHeaders) {
        this.responseHeaders = responseHeaders;
    }

    public Map<String, String> getResponseCookies() {
        return responseCookies;
    }

    public void setResponseCookies(Map<String, String> responseCookies) {
        this.responseCookies = responseCookies;
    }

    public long getResponseTimeMs() {
        return responseTimeMs;
    }

    public void setResponseTimeMs(long responseTimeMs) {
        this.responseTimeMs = responseTimeMs;
    }

    // ============ proxy ============
    public String getProxyHost() {
        return proxyHost;
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
    }

    public String getProxySchema() {
        return proxySchema;
    }

    public void setProxySchema(String proxySchema) {
        this.proxySchema = proxySchema;
    }

    // ============ multipart ============
    public Map<String, Object> getMultipartParams() {
        return multipartParams;
    }

    public void setMultipartParams(Map<String, Object> multipartParams) {
        this.multipartParams = multipartParams != null ? multipartParams : new HashMap<>();
    }

    public void addMultipartParam(String name, Object value) {
        this.multipartParams.put(name, value);
    }

    public void addMultipartFile(String name, java.nio.file.Path file) {
        this.multipartParams.put(name, file);
    }

    public void removeMultipartParam(String name) {
        this.multipartParams.remove(name);
    }

    // ============ per-request timeout ============
    public Long getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Long requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    // ============ logging flag ============
    public boolean isApiRequestResponseLogsEnabled() {
        return apiRequestResponseLogsEnabled;
    }

    public void setApiRequestResponseLogsEnabled(boolean apiRequestResponseLogsEnabled) {
        this.apiRequestResponseLogsEnabled = apiRequestResponseLogsEnabled;
    }
}
