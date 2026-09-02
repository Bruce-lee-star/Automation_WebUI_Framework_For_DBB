package com.hsbc.cmb.hk.dbb.automation.framework.api.client;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import com.hsbc.cmb.hk.dbb.automation.framework.api.utility.ApiLogSanitizer;

import java.util.HashMap;
import java.util.Map;

/**
 * 请求读取职责：承载 {@link AbstractApiJobHelper} 的全部 get* 读取方法。
 * <p>Phase 5 / T5-6 保行为拆分产物——逻辑与原类逐字等价，仅通过 {@code owner} 访问 Entity，
 * 日志前缀沿用 {@link AbstractApiJobHelper#LOGGER} 以保证可观测性不变。</p>
 */
final class ApiJobRequestReader {

    private final ApiJob owner;

    ApiJobRequestReader(ApiJob owner) {
        this.owner = owner;
    }

    /** 获取当前所有请求头（返回复制，避免外部修改） */
    public Map<String, Object> getRequestHeaders() {
        Entity entity = owner.getEntity();
        Map<String, Object> headers = new HashMap<>(entity.getRequestHeaders());
        AbstractApiJobHelper.LOGGER.info("Retrieved request headers: {}", ApiLogSanitizer.toLogString(headers));
        return headers;
    }

    /** 获取指定名称的请求头值 */
    public Object getRequestHeader(final String headerName) {
        Entity entity = owner.getEntity();
        Object value = entity.getRequestHeaders().get(headerName);
        AbstractApiJobHelper.LOGGER.info("Retrieved header '{}': {}", headerName, ApiLogSanitizer.valueForLog(headerName, value));
        return value;
    }

    /** 获取当前所有路径参数（返回复制） */
    public Map<String, Object> getPathParams() {
        Entity entity = owner.getEntity();
        Map<String, Object> pathParams = new HashMap<>(entity.getPathParams());
        AbstractApiJobHelper.LOGGER.info("Retrieved path parameters: {}", ApiLogSanitizer.toLogString(pathParams));
        return pathParams;
    }

    /** 获取指定名称的路径参数值 */
    public Object getPathParam(final String paramName) {
        Entity entity = owner.getEntity();
        Object value = entity.getPathParams().get(paramName);
        AbstractApiJobHelper.LOGGER.info("Retrieved path parameter '{}': {}", paramName, ApiLogSanitizer.valueForLog(paramName, value));
        return value;
    }

    /** 获取当前所有查询参数（返回复制） */
    public Map<String, Object> getQueryParams() {
        Entity entity = owner.getEntity();
        Map<String, Object> queryParams = new HashMap<>(entity.getQueryParams());
        AbstractApiJobHelper.LOGGER.info("Retrieved query parameters: {}", ApiLogSanitizer.toLogString(queryParams));
        return queryParams;
    }

    /** 获取指定名称的查询参数值 */
    public Object getQueryParam(final String paramName) {
        Entity entity = owner.getEntity();
        Object value = entity.getQueryParams().get(paramName);
        AbstractApiJobHelper.LOGGER.info("Retrieved query parameter '{}': {}", paramName, ApiLogSanitizer.valueForLog(paramName, value));
        return value;
    }

    /** 获取当前所有表单参数（返回复制） */
    public Map<String, Object> getFormParams() {
        Entity entity = owner.getEntity();
        Map<String, Object> formParams = new HashMap<>(entity.getFormParams());
        AbstractApiJobHelper.LOGGER.info("Retrieved form parameters: {}", ApiLogSanitizer.toLogString(formParams));
        return formParams;
    }

    /** 获取指定名称的表单参数值 */
    public Object getFormParam(final String paramName) {
        Entity entity = owner.getEntity();
        Object value = entity.getFormParams().get(paramName);
        AbstractApiJobHelper.LOGGER.info("Retrieved form parameter '{}': {}", paramName, ApiLogSanitizer.valueForLog(paramName, value));
        return value;
    }

    /** 获取当前所有 Cookie 参数（返回复制） */
    public Map<String, Object> getCookies() {
        Entity entity = owner.getEntity();
        Map<String, Object> cookies = new HashMap<>(entity.getCookies());
        AbstractApiJobHelper.LOGGER.info("Retrieved cookies: {}", cookies);
        return cookies;
    }

    /** 获取指定名称的 Cookie 值 */
    public Object getCookie(final String cookieName) {
        Entity entity = owner.getEntity();
        Object value = entity.getCookies().get(cookieName);
        AbstractApiJobHelper.LOGGER.info("Retrieved cookie '{}': {}", cookieName, ApiLogSanitizer.valueForLog(cookieName, value));
        return value;
    }

    /** 获取当前请求体内容 */
    public String getRequestPayload() {
        Entity entity = owner.getEntity();
        String payload = entity.getRequestPayload();
        AbstractApiJobHelper.LOGGER.info("Retrieved request payload: {}", ApiLogSanitizer.bodyForLog(payload));
        return payload;
    }

    /** 获取当前 Entity 的基础 URI */
    public String getBaseUri() {
        Entity entity = owner.getEntity();
        String baseUri = entity.getBaseUri();
        AbstractApiJobHelper.LOGGER.info("Retrieved base URI: {}", baseUri);
        return baseUri;
    }

    /** 获取当前 Entity 的基础路径 */
    public String getBasePath() {
        Entity entity = owner.getEntity();
        String basePath = entity.getBasePath();
        AbstractApiJobHelper.LOGGER.info("Retrieved base path: {}", basePath);
        return basePath;
    }

    /** 获取当前 Entity 的端点 */
    public String getEndpoint() {
        Entity entity = owner.getEntity();
        String endpoint = entity.getEndpoint();
        AbstractApiJobHelper.LOGGER.info("Retrieved endpoint: {}", endpoint);
        return endpoint;
    }
}
