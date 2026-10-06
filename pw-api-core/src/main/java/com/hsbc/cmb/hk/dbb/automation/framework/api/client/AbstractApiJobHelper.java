package com.hsbc.cmb.hk.dbb.automation.framework.api.client;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * API 请求构建辅助基类（外观 / Facade）。
 * <p>Phase 5 / T5-6 体量治理：原 677 行单体类已按职责拆分为
 * {@link ApiJobRequestReader}（读取）、{@link ApiJobRequestWriter}（写入/修改）、
 * {@link ApiJobPayloadLoader}（负载）三个同包职责类。本类仅保留公开 API 契约，
 * 方法签名与行为与原实现逐字等价（委托给上述职责类），外部调用方无感知。</p>
 */
public class AbstractApiJobHelper extends ApiJob {

    /** 包可见：供同包职责类复用同一日志实例，保证可观测性不变。 */
    static final Logger LOGGER = LoggerFactory.getLogger(AbstractApiJobHelper.class);

    private final ApiJobRequestReader reader = new ApiJobRequestReader(this);
    private final ApiJobRequestWriter writer = new ApiJobRequestWriter(this);
    private final ApiJobPayloadLoader payload = new ApiJobPayloadLoader(this);

    // ========== 读取类（委托 ApiJobRequestReader） ==========
    public Map<String, Object> getRequestHeaders() { return reader.getRequestHeaders(); }
    public Object getRequestHeader(final String headerName) { return reader.getRequestHeader(headerName); }
    public Map<String, Object> getPathParams() { return reader.getPathParams(); }
    public Object getPathParam(final String paramName) { return reader.getPathParam(paramName); }
    public Map<String, Object> getQueryParams() { return reader.getQueryParams(); }
    public Object getQueryParam(final String paramName) { return reader.getQueryParam(paramName); }
    public Map<String, Object> getFormParams() { return reader.getFormParams(); }
    public Object getFormParam(final String paramName) { return reader.getFormParam(paramName); }
    public Map<String, Object> getCookies() { return reader.getCookies(); }
    public Object getCookie(final String cookieName) { return reader.getCookie(cookieName); }
    public String getRequestPayload() { return reader.getRequestPayload(); }
    public String getBaseUri() { return reader.getBaseUri(); }
    public String getBasePath() { return reader.getBasePath(); }
    public String getEndpoint() { return reader.getEndpoint(); }

    // ========== 设置 / 修改类（委托 ApiJobRequestWriter） ==========
    public void setBaseUri(String baseUri) { writer.setBaseUri(baseUri); }
    public void setBasePath(String basePath) { writer.setBasePath(basePath); }
    public void setEndpoint(String endpoint) { writer.setEndpoint(endpoint); }
    public void setRequestPayload(String payloadStr) { writer.setRequestPayload(payloadStr); }
    public void addRequestHeader(String name, Object value) { writer.addRequestHeader(name, value); }
    public void addPathParam(String name, Object value) { writer.addPathParam(name, value); }
    public void addQueryParam(String name, Object value) { writer.addQueryParam(name, value); }
    public void addFormParam(String name, Object value) { writer.addFormParam(name, value); }
    public void addCookie(String name, Object value) { writer.addCookie(name, value); }
    public void setProxy(String host, int port, String schema) { writer.setProxy(host, port, schema); }
    public void setApiRequestResponseLogsEnabled(boolean enabled) { writer.setApiRequestResponseLogsEnabled(enabled); }

    public void clearHeader() { writer.clearHeader(); }
    public void clearQueryParams() { writer.clearQueryParams(); }
    public void clearFormParams() { writer.clearFormParams(); }
    public void clearCookies() { writer.clearCookies(); }
    public void removeHeader(final String headerName) { writer.removeHeader(headerName); }
    public void removeHeaders(final List<String> headerNames) { writer.removeHeaders(headerNames); }
    public void updateHeader(final String headerName, final String headerValue) { writer.updateHeader(headerName, headerValue); }
    public void updateHeaders(final Map<String, String> headers) { writer.updateHeaders(headers); }
    public void removePathParam(final String paramName) { writer.removePathParam(paramName); }
    public void removePathParams(final List<String> paramNames) { writer.removePathParams(paramNames); }
    public void updatePathParam(final String paramName, final String paramValue) { writer.updatePathParam(paramName, paramValue); }
    public void updatePathParams(final Map<String, String> params) { writer.updatePathParams(params); }
    public void clearPathParams() { writer.clearPathParams(); }
    public void removeQueryParam(final String paramName) { writer.removeQueryParam(paramName); }
    public void removeQueryParams(final List<String> paramNames) { writer.removeQueryParams(paramNames); }
    public void updateQueryParam(final String paramName, final String paramValue) { writer.updateQueryParam(paramName, paramValue); }
    public void updateQueryParams(final Map<String, String> params) { writer.updateQueryParams(params); }
    public void removeFormParam(final String paramName) { writer.removeFormParam(paramName); }
    public void removeFormParams(final List<String> paramNames) { writer.removeFormParams(paramNames); }
    public void updateFormParam(final String paramName, final String paramValue) { writer.updateFormParam(paramName, paramValue); }
    public void updateFormParams(final Map<String, String> params) { writer.updateFormParams(params); }
    public void removeCookieParam(final String paramName) { writer.removeCookieParam(paramName); }
    public void removeCookieParams(final List<String> paramNames) { writer.removeCookieParams(paramNames); }
    public void updateCookieParam(final String paramName, final String paramValue) { writer.updateCookieParam(paramName, paramValue); }
    public void updateCookieParams(final Map<String, String> params) { writer.updateCookieParams(params); }

    // ========== 负载类（委托 ApiJobPayloadLoader） ==========
    public void loadPayload(final String fileName) { payload.loadPayload(fileName); }
    public void modifyFieldsInRequestPayload(String fieldPath, Object fieldValue) { payload.modifyFieldsInRequestPayload(fieldPath, fieldValue); }
}
