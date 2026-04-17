package com.hsbc.cmb.hk.dbb.automation.framework.api.client;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ConfigProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.api.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import com.jayway.jsonpath.*;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class AbstractApiJobHelper extends ApiJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractApiJobHelper.class);
    private static final String JSON_PATH_PREFIX = "$.";


    // ========== 新增：读取类方法（对称补充） ==========
    /**
     * 获取当前所有请求头
     * @return 不可修改的请求头Map（避免外部直接修改）
     */
    public Map<String, Object> getRequestHeaders() {
        Entity entity = this.getEntity();
        Map<String, Object> headers = new HashMap<>(entity.getRequestHeaders()); // 返回复制，避免外部修改
        LOGGER.info("Retrieved request headers: {}", headers);
        return headers;
    }

    /**
     * 获取指定名称的请求头值
     * @param headerName 头名称
     * @return 头值（null表示不存在）
     */
    public Object getRequestHeader(final String headerName) {
        Entity entity = this.getEntity();
        Object value = entity.getRequestHeaders().get(headerName);
        LOGGER.info("Retrieved header '{}': {}", headerName, value);
        return value;
    }

    /**
     * 获取当前所有路径参数
     * @return 不可修改的路径参数Map
     */
    public Map<String, Object> getPathParams() {
        Entity entity = this.getEntity();
        Map<String, Object> pathParams = new HashMap<>(entity.getPathParams());
        LOGGER.info("Retrieved path parameters: {}", pathParams);
        return pathParams;
    }

    /**
     * 获取指定名称的路径参数值
     * @param paramName 参数名称
     * @return 参数值（null表示不存在）
     */
    public Object getPathParam(final String paramName) {
        Entity entity = this.getEntity();
        Object value = entity.getPathParams().get(paramName);
        LOGGER.info("Retrieved path parameter '{}': {}", paramName, value);
        return value;
    }

    /**
     * 获取当前所有查询参数
     * @return 不可修改的查询参数Map
     */
    public Map<String, Object> getQueryParams() {
        Entity entity = this.getEntity();
        Map<String, Object> queryParams = new HashMap<>(entity.getQueryParams());
        LOGGER.info("Retrieved query parameters: {}", queryParams);
        return queryParams;
    }

    /**
     * 获取指定名称的查询参数值
     * @param paramName 参数名称
     * @return 参数值（null表示不存在）
     */
    public Object getQueryParam(final String paramName) {
        Entity entity = this.getEntity();
        Object value = entity.getQueryParams().get(paramName);
        LOGGER.info("Retrieved query parameter '{}': {}", paramName, value);
        return value;
    }

    /**
     * 获取当前所有表单参数
     * @return 不可修改的表单参数Map
     */
    public Map<String, Object> getFormParams() {
        Entity entity = this.getEntity();
        Map<String, Object> formParams = new HashMap<>(entity.getFormParams());
        LOGGER.info("Retrieved form parameters: {}", formParams);
        return formParams;
    }

    /**
     * 获取指定名称的表单参数值
     * @param paramName 参数名称
     * @return 参数值（null表示不存在）
     */
    public Object getFormParam(final String paramName) {
        Entity entity = this.getEntity();
        Object value = entity.getFormParams().get(paramName);
        LOGGER.info("Retrieved form parameter '{}': {}", paramName, value);
        return value;
    }

    /**
     * 获取当前所有Cookie参数
     * @return 不可修改的Cookie参数Map
     */
    public Map<String, Object> getCookies() {
        Entity entity = this.getEntity();
        Map<String, Object> cookies = new HashMap<>(entity.getCookies());
        LOGGER.info("Retrieved cookies: {}", cookies);
        return cookies;
    }

    /**
     * 获取指定名称的Cookie值
     * @param cookieName Cookie名称
     * @return Cookie值（null表示不存在）
     */
    public Object getCookie(final String cookieName) {
        Entity entity = this.getEntity();
        Object value = entity.getCookies().get(cookieName);
        LOGGER.info("Retrieved cookie '{}': {}", cookieName, value);
        return value;
    }

    /**
     * 获取当前请求体内容
     * @return 请求体字符串（null表示未设置）
     */
    public String getRequestPayload() {
        Entity entity = this.getEntity();
        String payload = entity.getRequestPayload();
        LOGGER.info("Retrieved request payload: {}", payload);
        return payload;
    }

    /**
     * 获取当前Entity的基础URI
     * @return 基础URI
     */
    public String getBaseUri() {
        Entity entity = this.getEntity();
        String baseUri = entity.getBaseUri();
        LOGGER.info("Retrieved base URI: {}", baseUri);
        return baseUri;
    }

    /**
     * 获取当前Entity的基础路径
     * @return 基础路径
     */
    public String getBasePath() {
        Entity entity = this.getEntity();
        String basePath = entity.getBasePath();
        LOGGER.info("Retrieved base path: {}", basePath);
        return basePath;
    }

    /**
     * 获取当前Entity的端点
     * @return 端点路径
     */
    public String getEndpoint() {
        Entity entity = this.getEntity();
        String endpoint = entity.getEndpoint();
        LOGGER.info("Retrieved endpoint: {}", endpoint);
        return endpoint;
    }

    // ========== 新增：API设置方法 ==========
    /**
     * 设置基础URI
     * @param baseUri 基础URI
     */
    public void setBaseUri(String baseUri) {
        final Entity entity = this.getEntity();
        if (entity == null) {
            LOGGER.error("Entity is null, cannot set base URI");
            return;
        }
        entity.setBaseUri(baseUri);
        LOGGER.info("Set base URI: {}", baseUri);
    }

    /**
     * 设置基础路径
     * @param basePath 基础路径
     */
    public void setBasePath(String basePath) {
        final Entity entity = this.getEntity();
        if (entity == null) {
            LOGGER.error("Entity is null, cannot set base path");
            return;
        }
        entity.setBasePath(basePath);
        LOGGER.info("Set base path: {}", basePath);
    }

    /**
     * 设置API端点
     * @param endpoint 端点路径
     */
    public void setEndpoint(String endpoint) {
        final Entity entity = this.getEntity();
        if (entity == null) {
            LOGGER.error("Entity is null, cannot set endpoint");
            return;
        }
        entity.setEndpoint(endpoint);
        LOGGER.info("Set endpoint: {}", endpoint);
    }

    /**
     * 设置请求体
     * @param payload 请求体内容
     */
    public void setRequestPayload(String payload) {
        final Entity entity = this.getEntity();
        if (entity == null) {
            LOGGER.error("Entity is null, cannot set request payload");
            return;
        }
        entity.setRequestPayload(payload);
        LOGGER.info("Set request payload: {}", payload);
    }

    /**
     * 添加请求头（不覆盖现有同名头）
     * @param name 头名称
     * @param value 头值
     */
    public void addRequestHeader(String name, Object value) {
        final Entity entity = this.getEntity();
        if (entity == null) {
            LOGGER.error("Entity is null, cannot add request header");
            return;
        }
        entity.addRequestHeader(name, value);
        LOGGER.info("Added request header: {} = {}", name, value);
    }

    /**
     * 添加路径参数（不覆盖现有同名参数）
     * @param name 参数名称
     * @param value 参数值
     */
    public void addPathParam(String name, Object value) {
        final Entity entity = this.getEntity();
        if (entity == null) {
            LOGGER.error("Entity is null, cannot add path parameter");
            return;
        }
        entity.addPathParam(name, value);
        LOGGER.info("Added path parameter: {} = {}", name, value);
    }

    /**
     * 添加查询参数（不覆盖现有同名参数）
     * @param name 参数名称
     * @param value 参数值
     */
    public void addQueryParam(String name, Object value) {
        final Entity entity = this.getEntity();
        if (entity == null) {
            LOGGER.error("Entity is null, cannot add query parameter");
            return;
        }
        entity.addQueryParam(name, value);
        LOGGER.info("Added query parameter: {} = {}", name, value);
    }

    /**
     * 添加表单参数（不覆盖现有同名参数）
     * @param name 参数名称
     * @param value 参数值
     */
    public void addFormParam(String name, Object value) {
        final Entity entity = this.getEntity();
        if (entity == null) {
            LOGGER.error("Entity is null, cannot add form parameter");
            return;
        }
        entity.addFormParam(name, value);
        LOGGER.info("Added form parameter: {} = {}", name, value);
    }

    /**
     * 添加Cookie（不覆盖现有同名Cookie）
     * @param name Cookie名称
     * @param value Cookie值
     */
    public void addCookie(String name, Object value) {
        final Entity entity = this.getEntity();
        if (entity == null) {
            LOGGER.error("Entity is null, cannot add cookie");
            return;
        }
        entity.addCookie(name, value);
        LOGGER.info("Added cookie: {} = {}", name, value);
    }

    /**
     * 设置代理配置
     * @param host 代理主机
     * @param port 代理端口
     * @param schema 代理协议（http/https）
     */
    public void setProxy(String host, int port, String schema) {
        final Entity entity = this.getEntity();
        if (entity == null) {
            LOGGER.error("Entity is null, cannot set proxy");
            return;
        }
        entity.setProxyHost(host);
        entity.setProxyPort(port);
        entity.setProxySchema(schema);
        LOGGER.info("Set proxy: {}://{}:{}", schema, host, port);
    }

    /**
     * 启用/禁用API请求响应日志
     * @param enabled 是否启用
     */
    public void setApiRequestResponseLogsEnabled(boolean enabled) {
        final Entity entity = this.getEntity();
        if (entity == null) {
            LOGGER.error("Entity is null, cannot set request response logs enabled");
            return;
        }
        entity.setApiRequestResponseLogsEnabled(enabled);
        LOGGER.info("Set API request/response logs enabled: {}", enabled);
    }

    // ========== 现有方法保持不变 ==========
    public void clearHeader() {
        final Entity entity = this.getEntity();
        final Map<String, Object> emptyRequestHeaders = new HashMap<>();
        entity.setRequestHeaders(emptyRequestHeaders);
        final String msg = String.format("Deleted all request headers, Updated request headers are %s", entity.getRequestHeaders());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void clearQueryParams() {
        final Entity entity = this.getEntity();
        final Map<String, Object> emptyQueryParams = new HashMap<>();
        entity.setQueryParams(emptyQueryParams);
        final String msg = String.format("Deleted query parameters, Updated query parameters are %s", entity.getQueryParams());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void clearFormParams() {
        final Entity entity = this.getEntity();
        final Map<String, Object> emptyFormParams = new HashMap<>();
        entity.setFormParams(emptyFormParams);
        final String msg = String.format("Deleted form parameters, Updated form parameters are %s", entity.getFormParams());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void clearCookies() {
        final Entity entity = this.getEntity();
        final Map<String, Object> emptyCookies = new HashMap<>();
        entity.setCookies(emptyCookies);
        final String msg = String.format("Deleted all cookies, Updated cookies are %s", entity.getCookies());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void removeHeader(final String headerName) {
        final Entity entity = this.getEntity();
        Map<String, Object> requestHeaders = entity.getRequestHeaders();
        requestHeaders.remove(headerName);
        entity.setRequestHeaders(requestHeaders);
        final String msg = String.format("Removed header: %s, Updated request headers are %s", headerName, entity.getRequestHeaders());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void removeHeaders(final List<String> headerNames) {
        final Entity entity = this.getEntity();
        Iterator<String> iterator = headerNames.iterator();
        final Map<String, Object> requestHeaders = entity.getRequestHeaders();
        while (iterator.hasNext()) {
            final String headerName = iterator.next();
            requestHeaders.remove(headerName);
        }
        entity.setRequestHeaders(requestHeaders);

        final String msg = String.format("Removed header(s): %s, Updated request headers are %s", headerNames, entity.getRequestHeaders());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void updateHeader(final String headerName, final String headerValue) {
        final Entity entity = this.getEntity();
        final Map<String, Object> requestHeaders = entity.getRequestHeaders();
        requestHeaders.put(headerName, headerValue);
        entity.setRequestHeaders(requestHeaders);
        final String msg = String.format("Updated value of the header: '%s': '%s',\n Updated request headers are %s",
                headerName, entity.getRequestHeaders().get(headerName), entity.getRequestHeaders());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void updateHeaders(final Map<String, String> headers) {
        final Entity entity = this.getEntity();
        final Map<String, Object> requestHeaders = entity.getRequestHeaders();

        requestHeaders.putAll(headers);
        entity.setRequestHeaders(requestHeaders);
        final String msg = String.format("Updated request headers are %s", entity.getRequestHeaders());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void removePathParam(final String paramName) {
        final Entity entity = this.getEntity();
        Map<String, Object> pathParams = entity.getPathParams();
        pathParams.remove(paramName);
        entity.setPathParams(pathParams);
        final String msg = String.format("Removed path parameter: '%s', Updated path parameter are '%s'", paramName, entity.getPathParams());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void removePathParams(final List<String> paramNames) {
        final Entity entity = this.getEntity();
        Iterator<String> iterator = paramNames.iterator();
        final Map<String, Object> pathParams = entity.getPathParams();
        while (iterator.hasNext()) {
            final String paramName = iterator.next();
            pathParams.remove(paramName);
        }
        entity.setPathParams(pathParams);
        final String msg = String.format("Removed path parameter(s): '%s', Updated path parameters are '%s'", paramNames, entity.getPathParams());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void updatePathParam(final String paramName, final String paramValue) {
        final Entity entity = this.getEntity();
        final Map<String, Object> pathParams = entity.getPathParams();

        pathParams.put(paramName, paramValue);
        entity.setPathParams(pathParams);
        final String msg = String.format("Updated path parameter: '%s' : '%s', Updated path parameters are '%s'", paramName, paramValue, entity.getPathParams());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void updatePathParams(final Map<String, String> params) {
        final Entity entity = this.getEntity();
        final Map<String, Object> pathParams = entity.getPathParams();
        pathParams.putAll(params);
        entity.setPathParams(pathParams);
        final String msg = String.format("Updated path parameters are %s", entity.getPathParams());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void clearPathParams() {
        final Entity entity = this.getEntity();
        final Map<String, Object> emptyPathParams = new HashMap<>();
        entity.setPathParams(emptyPathParams);
        final String msg = String.format("Deleted path parameters, Updated path parameters are %s", entity.getPathParams());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void removeQueryParam(final String paramName) {
        final Entity entity = this.getEntity();
        Map<String, Object> queryParams = entity.getQueryParams();
        queryParams.remove(paramName);
        entity.setQueryParams(queryParams);
        final String msg = String.format("Removed query parameter(s): '%s', Updated query parameters are '%s'", paramName, entity.getQueryParams());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void removeQueryParams(final List<String> paramNames) {
        final Entity entity = this.getEntity();
        Iterator<String> iterator = paramNames.iterator();
        final Map<String, Object> queryParams = entity.getQueryParams();
        while (iterator.hasNext()) {
            final String paramName = iterator.next();
            queryParams.remove(paramName);
        }
        entity.setQueryParams(queryParams);
        final String msg = String.format("Removed query parameter(s): '%s', Updated query parameters are '%s'", paramNames, entity.getQueryParams());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void updateQueryParam(final String paramName, final String paramValue) {
        final Entity entity = this.getEntity();
        final Map<String, Object> queryParams = entity.getQueryParams();
        queryParams.put(paramName, paramValue);
        entity.setQueryParams(queryParams);
        final String msg = String.format("Updated query parameter: '%s' : '%s', Updated query parameters are '%s'", paramName, paramValue, entity.getQueryParams());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void updateQueryParams(final Map<String, String> params) {
        final Entity entity = this.getEntity();
        final Map<String, Object> queryParams = entity.getQueryParams();
        queryParams.putAll(params);
        entity.setQueryParams(queryParams);
        final String msg = String.format("Updated query parameters are %s", entity.getQueryParams());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void removeFormParam(final String paramName) {
        final Entity entity = this.getEntity();
        Map<String, Object> formParams = entity.getFormParams();
        formParams.remove(paramName);
        entity.setFormParams(formParams);
        final String msg = String.format("Removed form parameter(s): '%s', Updated for parameters are '%s'", paramName, entity.getFormParams());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void removeFormParams(final List<String> paramNames) {
        final Entity entity = this.getEntity();
        Iterator<String> iterator = paramNames.iterator();
        final Map<String, Object> formParams = entity.getFormParams();
        while (iterator.hasNext()) {
            final String paramName = iterator.next();
            formParams.remove(paramName);
        }
        entity.setFormParams(formParams);
        final String msg = String.format("Removed form parameter(s): '%s', Updated form parameters are '%s'", paramNames, entity.getFormParams());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void updateFormParam(final String paramName, final String paramValue) {
        final Entity entity = this.getEntity();
        final Map<String, Object> formParams = entity.getFormParams();
        formParams.put(paramName, paramValue);
        entity.setFormParams(formParams);
        final String msg = String.format("Updated form parameter: '%s' : '%s', Updated form parameters are '%s'", paramName, paramValue, entity.getFormParams());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void updateFormParams(final Map<String, String> params) {
        final Entity entity = this.getEntity();
        final Map<String, Object> formParams = entity.getFormParams();
        formParams.putAll(params);
        entity.setFormParams(formParams);
        final String msg = String.format("Updated query parameters are %s", entity.getFormParams());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void removeCookieParam(final String paramName) {
        final Entity entity = this.getEntity();
        Map<String, Object> cookies = entity.getCookies();
        cookies.remove(paramName);
        entity.setCookies(cookies);
        final String msg = String.format("Removed cookie parameter: '%s', Updated cookies are '%s'",
                paramName, entity.getCookies());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void removeCookieParams(final List<String> paramNames) {
        final Entity entity = this.getEntity();
        Iterator<String> iterator = paramNames.iterator();
        final Map<String, Object> cookies = entity.getCookies();
        while (iterator.hasNext()) {
            final String paramName = iterator.next();
            cookies.remove(paramName);
        }
        entity.setCookies(cookies);
        final String msg = String.format("Removed cookie parameters: '%s', Updated cookies are '%s'",
                paramNames, entity.getCookies());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void updateCookieParam(final String paramName, final String paramValue) {
        final Entity entity = this.getEntity();
        final Map<String, Object> cookies = entity.getCookies();
        cookies.put(paramName, paramValue);
        entity.setCookies(cookies);
        final String msg = String.format("Updated cookie parameters: '%s': '%s', Now cookies are '%s'",
                paramName, paramValue, entity.getCookies());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void updateCookieParams(final Map<String, String> params) {
        final Entity entity = this.getEntity();
        final Map<String, Object> cookies = entity.getCookies();
        cookies.putAll(params);
        entity.setCookies(cookies);
        final String msg = String.format("Now cookies are '%s'", entity.getCookies());
        AbstractApiJobHelper.LOGGER.info(msg);
    }

    public void loadPayload(final String fileName) {
        // 1. First layer validation: File name not null/empty
        if (fileName == null || fileName.trim().isEmpty()) {
            LOGGER.error("Payload file name is null or empty.", new IllegalArgumentException("File name cannot be null/empty"));
            return;
        }
        String cleanFileName = fileName.trim();

        // 2. Second layer validation: Entity not null (core fix for NPE)
        Entity entity = this.getEntity();
        if (entity == null) {
            LOGGER.error("Entity object is NULL! Cannot set payload to a null Entity instance.");
            return;
        }

        // 3. Third layer validation: Get and validate payload path
        String payloadPath = ConfigProvider.getPayloadPath(cleanFileName);
        if (payloadPath == null || payloadPath.trim().isEmpty()) {
            LOGGER.error("Payload file path is empty for file: [{}]", cleanFileName);
            return;
        }
        // Standardize path (eliminate redundant .\ characters completely)
        String normalizedPath = null;
        try {
            normalizedPath = new File(payloadPath).getCanonicalPath();
        } catch (IOException e) {
            throw new RuntimeException("Failed to get canonical path for payload file: " + payloadPath, e);
        }
        File payloadFile = new File(normalizedPath);

        // 4. Fourth layer validation: File exists and is a regular file
        if (!payloadFile.exists()) {
            LOGGER.error("Payload file NOT found: [{}]", normalizedPath);
            return;
        }
        if (!payloadFile.isFile()) {
            LOGGER.error("Specified path is a directory, not a file: [{}]", normalizedPath);
            return;
        }

        // 5. Safely read file content (catch all IO exceptions)
        try {
            // Read using Java NIO (avoid unclosed streams) with configured encoding
            String encoding = FrameworkConfig.getPayloadEncoding();
            String content = new String(
                    Files.readAllBytes(payloadFile.toPath()),
                    Charset.forName(encoding)
            );
            // 6. Fifth layer validation: File content not empty
            if (content == null || content.trim().isEmpty()) {
                LOGGER.warn("Payload file [{}] is empty (path: {})", cleanFileName, normalizedPath);
                entity.setRequestPayload(""); // Prevent subsequent NPE
                return;
            }

            // 7. Parse JSON and set to Entity (final step)
            DocumentContext requestPayload = JsonPath.parse(content);
            entity.setRequestPayload(requestPayload.jsonString());
            LOGGER.info("loaded payload file successfully: [{}]\n  {}",
                    cleanFileName, content);

        } catch (IOException e) {
            String errorMsg = String.format("IO error reading payload file: [%s] (path: %s)", cleanFileName, normalizedPath);
            LOGGER.error(errorMsg, e);
            // 抛出RuntimeException，携带自定义消息和原始异常
            throw new RuntimeException(errorMsg, e);

        } catch (InvalidJsonException e) {
            String errorMsg = String.format("Invalid JSON format in payload file: [%s] (path: %s)", cleanFileName, normalizedPath);
            LOGGER.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);

        } catch (com.jayway.jsonpath.JsonPathException e) {
            String errorMsg = String.format("JSONPath parse error for payload file: [%s] (path: %s)", cleanFileName, normalizedPath);
            LOGGER.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);

        } catch (Exception e) {
            String errorMsg = String.format("Unexpected error loading payload file: [%s] (path: %s)", cleanFileName, normalizedPath);
            LOGGER.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * Modify single field in request payload (support any path format + array index)
     * @param fieldPath Full path of target field (e.g., "user.info[0].address", "$.order[1].price", "list[2].name")
     * @param fieldValue Value to set (supports all JSON types: String, Number, Boolean, Array, Object, null)
     */
    public void modifyFieldsInRequestPayload(String fieldPath, Object fieldValue) {
        try {
            String originalJson = this.getEntity().getRequestPayload();

            // 1. 解析为 DocumentContext（核心：使用 Jackson 解析器）
            DocumentContext doc = JsonPath.using(Configuration.builder()
                            .jsonProvider(new JacksonJsonProvider()) // 强制用 Jackson 解析器
                            .mappingProvider(new JacksonMappingProvider())
                            .build())
                    .parse(originalJson);

            // 2. 直接修改（无需 TypeRef）
            doc.set(fieldPath, fieldValue); // 支持任意路径：name、user.info.id、user.addresses[0].city

            // 3. 获取修改后的 JSON
            String modifiedJson = doc.jsonString();
            this.getEntity().setRequestPayload(modifiedJson);
        }catch (InvalidPathException e) {
            // 路径格式非法（如 "user.info[abc].city" 索引非数字）
            String errorMsg = String.format("Invalid field path format: [%s]", fieldPath);
            LOGGER.error("[Path Format Error] {}", errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        } catch (Exception e) {
            // 兜底异常（捕获所有未预期的错误）
            String errorMsg = String.format("Unexpected error when modifying field [%s]", fieldPath);
            LOGGER.error("[Unexpected Error] {}", errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }

}