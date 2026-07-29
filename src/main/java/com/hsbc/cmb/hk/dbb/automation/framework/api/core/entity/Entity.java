package com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity;

import com.hsbc.cmb.hk.dbb.automation.framework.api.assembler.headersImpl.HeadersAssemblers;
import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ConfigProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.api.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.api.domain.enums.APIResources;
import com.hsbc.cmb.hk.dbb.automation.framework.api.utility.Constants;
import com.typesafe.config.Config;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class Entity {
    private static final Logger log = LoggerFactory.getLogger(Entity.class);

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
    private String proxyHost;
    private int proxyPort;
    private String proxySchema;
    private boolean apiRequestResponseLogsEnabled;
    private Object validatableResponse;

    public Entity() {
        // Initialize baseUri from configuration
        this.baseUri = FrameworkConfig.getDefaultBaseUri();
        // Initialize API request/response logging from configuration
        this.initializeApiRequestResponseLogging();
    }

    /**
     * Copy constructor with configuration loading
     * 1. Copy basic properties
     * 2. Load configuration from config files
     * 3. Build headers from loaded configuration
     *
     * @param entity source entity (can be null for null entity)
     */
    public Entity(final Entity entity) {
        if (entity == null) {
            log.info("Source entity is null, creating empty entity for dynamic configuration");
            this.initializeApiRequestResponseLogging();
            return;
        }
        // 1. Copy basic properties
        this.entityName = entity.getEntityName();
        this.initializeApiRequestResponseLogging();

        // 2. Load configuration (if entity has a name, load specific config; otherwise load default config)
        try {
            // Load configuration - ConfigProvider now handles null entity gracefully
            ConfigProvider.config(entity);

            // Set baseUri and basePath from loaded config
            this.setBaseUri(APIResources.BASE_URI.toString());
            this.setBasePath(APIResources.BASE_PATH.toString());

            // 3. Build headers from loaded configuration
            this.buildHeadersMap(ConfigProvider.getConfig());
        } catch (Exception e) {
            log.warn("Failed to load configuration/build headers during copy construction", e);
        }
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
        log.debug("Set entity name: {}", entityName);
    }

    public String getEntityName() {
        // 优先级：手动设置的entityName > 系统属性 > 环境变量
        if (this.entityName != null && !this.entityName.isEmpty()) {
            return this.entityName.toLowerCase(Locale.ENGLISH);
        }
        String sysEntity = System.getProperty(Constants.ENTITY);
        if (sysEntity != null && !sysEntity.isEmpty()) {
            return sysEntity.toLowerCase(Locale.ENGLISH);
        }
        String envEntity = System.getenv(Constants.ENTITY);
        return envEntity != null ? envEntity.toLowerCase(Locale.ENGLISH) : "";
    }

    /**
     * 简化：仅调用HeadersAssemblers构建headers，不再处理具体逻辑
     */
    private void buildHeadersMap(Config config) {
        // 清空原有headers + 调用工具类构建新headers
        this.requestHeaders.clear();
        this.requestHeaders.putAll(HeadersAssemblers.buildHeadersFromConfig(config));
    }

    /**
     * 兼容原有逻辑：调用工具类处理
     */
    private void buildHeadersMap(final Entity entity) {
        this.requestHeaders.clear();
        this.requestHeaders.putAll(HeadersAssemblers.buildHeadersFromEntity(entity));
    }

    /**
     * 简化：调用工具类判断是否构建headers
     */
    private boolean isBuildHeaders() {
        return HeadersAssemblers.isBuildHeaders();
    }

    private void initializeApiRequestResponseLogging() {
        this.setApiRequestResponseLogsEnabled(FrameworkConfig.isApiRequestResponseLogsEnabled());
    }

    // ============ 原有getter/setter保持不变 ============
    public String getBaseUri() {
        return baseUri;
    }

    public void setBaseUri(String baseUri) {
        this.baseUri = baseUri;
        log.debug("Set base URI: {}", baseUri);
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
        log.debug("Set base path: {}", basePath);
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
        log.debug("Set endpoint: {}", endpoint);
    }

    public Map<String, Object> getRequestHeaders() {
        return requestHeaders;
    }

    public void setRequestHeaders(Map<String, Object> requestHeaders) {
        this.requestHeaders = requestHeaders;
        log.debug("Set request headers: {} entries", requestHeaders != null ? requestHeaders.size() : 0);
    }

    public void addRequestHeader(String name, Object value) {
        this.requestHeaders.put(name, value);
        log.debug("Add request header: {} = {}", name, value);
    }

    public void removeRequestHeader(String name) {
        this.requestHeaders.remove(name);
        log.debug("Remove request header: {}", name);
    }

    public Map<String, Object> getPathParams() {
        return pathParams;
    }

    public void setPathParams(Map<String, Object> pathParams) {
        this.pathParams = pathParams;
        log.debug("Set path parameters: {} entries", pathParams != null ? pathParams.size() : 0);
    }

    public void addPathParam(String name, Object value) {
        this.pathParams.put(name, value);
        log.debug("Add path parameter: {} = {}", name, value);
    }

    public void removePathParam(String name) {
        this.pathParams.remove(name);
        log.debug("Remove path parameter: {}", name);
    }

    public Map<String, Object> getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(Map<String, Object> queryParams) {
        this.queryParams = queryParams;
        log.debug("Set query parameters: {} entries", queryParams != null ? queryParams.size() : 0);
    }

    public void addQueryParam(String name, Object value) {
        this.queryParams.put(name, value);
        log.debug("Add query parameter: {} = {}", name, value);
    }

    public void removeQueryParam(String name) {
        this.queryParams.remove(name);
        log.debug("Remove query parameter: {}", name);
    }

    public Map<String, Object> getFormParams() {
        return formParams;
    }

    public void setFormParams(Map<String, Object> formParams) {
        this.formParams = formParams;
        log.debug("Set form parameters: {} entries", formParams != null ? formParams.size() : 0);
    }

    public void addFormParam(String name, Object value) {
        this.formParams.put(name, value);
        log.debug("Add form parameter: {} = {}", name, value);
    }

    public void removeFormParam(String name) {
        this.formParams.remove(name);
        log.debug("Remove form parameter: {}", name);
    }

    public Map<String, Object> getCookies() {
        return cookies;
    }

    public void setCookies(Map<String, Object> cookies) {
        this.cookies = cookies;
        log.debug("Set cookies: {} entries", cookies != null ? cookies.size() : 0);
    }

    public void addCookie(String name, Object value) {
        this.cookies.put(name, value);
        log.debug("Add cookie: {} = {}", name, value);
    }

    public void removeCookie(String name) {
        this.cookies.remove(name);
        log.debug("Remove cookie: {}", name);
    }

    public String getRequestPayload() {
        return requestPayload;
    }

    public void setRequestPayload(String requestPayload) {
        this.requestPayload = requestPayload;
        log.debug("Set request payload: {} characters", requestPayload != null ? requestPayload.length() : 0);
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    public void setResponsePayload(String responsePayload) {
        this.responsePayload = responsePayload;
        log.debug("Set response payload: {} characters", responsePayload != null ? responsePayload.length() : 0);
    }

    public int getResponseCode() {
        return responseCode;
    }

    public void setResponseCode(int responseCode) {
        this.responseCode = responseCode;
        log.debug("Set response code: {}", responseCode);
    }

    public Map<String, String> getResponseHeaders() {
        return responseHeaders;
    }

    public void setResponseHeaders(Map<String, String> responseHeaders) {
        this.responseHeaders = responseHeaders;
        log.debug("Set response headers: {} entries", responseHeaders != null ? responseHeaders.size() : 0);
    }

    public void addResponseHeader(String name, String value) {
        if (this.responseHeaders == null) {
            this.responseHeaders = new HashMap<>();
        }
        this.responseHeaders.put(name, value);
        log.debug("Add response header: {} = {}", name, value);
    }

    public Map<String, String> getResponseCookies() {
        return responseCookies;
    }

    public void setResponseCookies(Map<String, String> responseCookies) {
        this.responseCookies = responseCookies;
        log.debug("Set response cookies: {} entries", responseCookies != null ? responseCookies.size() : 0);
    }

    public void addResponseCookie(String name, String value) {
        if (this.responseCookies == null) {
            this.responseCookies = new HashMap<>();
        }
        this.responseCookies.put(name, value);
        log.debug("Add response cookie: {} = {}", name, value);
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost;
        log.debug("Set proxy host: {}", proxyHost);
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
        log.debug("Set proxy port: {}", proxyPort);
    }

    public String getProxySchema() {
        return proxySchema;
    }

    public void setProxySchema(String proxySchema) {
        this.proxySchema = proxySchema;
        log.debug("Set proxy schema: {}", proxySchema);
    }

    public boolean isApiRequestResponseLogsEnabled() {
        return apiRequestResponseLogsEnabled;
    }

    public void setApiRequestResponseLogsEnabled(boolean apiRequestResponseLogsEnabled) {
        this.apiRequestResponseLogsEnabled = apiRequestResponseLogsEnabled;
        log.debug("Set API request/response logs enabled: {}", apiRequestResponseLogsEnabled);
    }

    public Object getValidatableResponse() {
        return validatableResponse;
    }

    public void setValidatableResponse(Object validatableResponse) {
        this.validatableResponse = validatableResponse;
        log.debug("Set validatable response");
    }

}