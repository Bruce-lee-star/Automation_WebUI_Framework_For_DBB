package com.hsbc.cmb.hk.dbb.automation.framework.api.client;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import com.hsbc.cmb.hk.dbb.automation.framework.api.utility.ApiLogSanitizer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 请求写入/修改职责：承载 {@link AbstractApiJobHelper} 的全部 set/add/clear/remove/update 系列方法。
 * <p>Phase 5 / T5-6 保行为拆分产物，逻辑与原类逐字等价，仅通过 {@code owner} 访问 Entity，
 * 日志前缀沿用 {@link AbstractApiJobHelper#LOGGER}。</p>
 */
final class ApiJobRequestWriter {

    private final ApiJob owner;

    ApiJobRequestWriter(ApiJob owner) {
        this.owner = owner;
    }

    // ── 内部辅助方法：消除 get→modify→set→log 重复 ──────────────

    /** 安全获取 Entity，为 null 时记录错误并返回 false */
    private boolean entityAvailable(String action) {
        if (owner.getEntity() == null) {
            AbstractApiJobHelper.LOGGER.error("Entity is null, cannot {}", action);
            return false;
        }
        return true;
    }

    /** 通用：清空 Map 字段 */
    private void clearMapField(Consumer<Map<String, Object>> setter, String label) {
        setter.accept(new HashMap<>());
        AbstractApiJobHelper.LOGGER.info("Deleted all {}, Updated {} are empty", label, label);
    }

    /** 通用：读取-修改-回写 Map 字段 */
    private void modifyMapField(
            Supplier<Map<String, Object>> getter,
            Consumer<Map<String, Object>> setter,
            Consumer<Map<String, Object>> modifier,
            String action, String detail) {
        Map<String, Object> params = getter.get();
        modifier.accept(params);
        setter.accept(params);
        AbstractApiJobHelper.LOGGER.info("{} {} parameters, Detail: {}", action, detail, ApiLogSanitizer.toLogString(params));
    }

    // ========== API 设置方法 ==========

    public void setBaseUri(String baseUri) {
        final Entity entity = owner.getEntity();
        if (entity == null) {
            AbstractApiJobHelper.LOGGER.error("Entity is null, cannot set base URI");
            return;
        }
        entity.setBaseUri(baseUri);
        AbstractApiJobHelper.LOGGER.info("Set base URI: {}", baseUri);
    }

    public void setBasePath(String basePath) {
        final Entity entity = owner.getEntity();
        if (entity == null) {
            AbstractApiJobHelper.LOGGER.error("Entity is null, cannot set base path");
            return;
        }
        entity.setBasePath(basePath);
        AbstractApiJobHelper.LOGGER.info("Set base path: {}", basePath);
    }

    public void setEndpoint(String endpoint) {
        final Entity entity = owner.getEntity();
        if (entity == null) {
            AbstractApiJobHelper.LOGGER.error("Entity is null, cannot set endpoint");
            return;
        }
        entity.setEndpoint(endpoint);
        AbstractApiJobHelper.LOGGER.info("Set endpoint: {}", endpoint);
    }

    public void setRequestPayload(String payload) {
        final Entity entity = owner.getEntity();
        if (entity == null) {
            AbstractApiJobHelper.LOGGER.error("Entity is null, cannot set request payload");
            return;
        }
        entity.setRequestPayload(payload);
        AbstractApiJobHelper.LOGGER.info("Set request payload: {}", ApiLogSanitizer.bodyForLog(payload));
    }

    public void addRequestHeader(String name, Object value) {
        final Entity entity = owner.getEntity();
        if (entity == null) {
            AbstractApiJobHelper.LOGGER.error("Entity is null, cannot add request header");
            return;
        }
        entity.addRequestHeader(name, value);
        AbstractApiJobHelper.LOGGER.info("Added request header: {} = {}", name, ApiLogSanitizer.valueForLog(name, value));
    }

    public void addPathParam(String name, Object value) {
        final Entity entity = owner.getEntity();
        if (entity == null) {
            AbstractApiJobHelper.LOGGER.error("Entity is null, cannot add path parameter");
            return;
        }
        entity.addPathParam(name, value);
        AbstractApiJobHelper.LOGGER.info("Added path parameter: {} = {}", name, ApiLogSanitizer.valueForLog(name, value));
    }

    public void addQueryParam(String name, Object value) {
        final Entity entity = owner.getEntity();
        if (entity == null) {
            AbstractApiJobHelper.LOGGER.error("Entity is null, cannot add query parameter");
            return;
        }
        entity.addQueryParam(name, value);
        AbstractApiJobHelper.LOGGER.info("Added query parameter: {} = {}", name, ApiLogSanitizer.valueForLog(name, value));
    }

    public void addFormParam(String name, Object value) {
        final Entity entity = owner.getEntity();
        if (entity == null) {
            AbstractApiJobHelper.LOGGER.error("Entity is null, cannot add form parameter");
            return;
        }
        entity.addFormParam(name, value);
        AbstractApiJobHelper.LOGGER.info("Added form parameter: {} = {}", name, ApiLogSanitizer.valueForLog(name, value));
    }

    public void addCookie(String name, Object value) {
        final Entity entity = owner.getEntity();
        if (entity == null) {
            AbstractApiJobHelper.LOGGER.error("Entity is null, cannot add cookie");
            return;
        }
        entity.addCookie(name, value);
        AbstractApiJobHelper.LOGGER.info("Added cookie: {} = {}", name, ApiLogSanitizer.valueForLog(name, value));
    }

    public void setProxy(String host, int port, String schema) {
        final Entity entity = owner.getEntity();
        if (entity == null) {
            AbstractApiJobHelper.LOGGER.error("Entity is null, cannot set proxy");
            return;
        }
        entity.setProxyHost(host);
        entity.setProxyPort(port);
        entity.setProxySchema(schema);
        AbstractApiJobHelper.LOGGER.info("Set proxy: {}://{}:{}", schema, host, port);
    }

    public void setApiRequestResponseLogsEnabled(boolean enabled) {
        final Entity entity = owner.getEntity();
        if (entity == null) {
            AbstractApiJobHelper.LOGGER.error("Entity is null, cannot set request response logs enabled");
            return;
        }
        entity.setApiRequestResponseLogsEnabled(enabled);
        AbstractApiJobHelper.LOGGER.info("Set API request/response logs enabled: {}", enabled);
    }

    // ========== 清除 / 修改方法 ==========

    public void clearHeader() {
        Entity entity = owner.getEntity();
        if (!entityAvailable("clear headers")) return;
        clearMapField(entity::setRequestHeaders, "request headers");
    }

    public void clearQueryParams() {
        Entity entity = owner.getEntity();
        if (!entityAvailable("clear query params")) return;
        clearMapField(entity::setQueryParams, "query parameters");
    }

    public void clearFormParams() {
        Entity entity = owner.getEntity();
        if (!entityAvailable("clear form params")) return;
        clearMapField(entity::setFormParams, "form parameters");
    }

    public void clearCookies() {
        Entity entity = owner.getEntity();
        if (!entityAvailable("clear cookies")) return;
        clearMapField(entity::setCookies, "cookies");
    }

    public void removeHeader(final String headerName) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("remove header")) return;
        modifyMapField(entity::getRequestHeaders, entity::setRequestHeaders,
                m -> m.remove(headerName), "Removed", "header: " + headerName);
    }

    public void removeHeaders(final List<String> headerNames) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("remove headers")) return;
        modifyMapField(entity::getRequestHeaders, entity::setRequestHeaders,
                m -> headerNames.forEach(m::remove), "Removed", "headers: " + headerNames);
    }

    public void updateHeader(final String headerName, final String headerValue) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("update header")) return;
        modifyMapField(entity::getRequestHeaders, entity::setRequestHeaders,
                m -> m.put(headerName, headerValue), "Updated",
                String.format("header '%s'='%s'", headerName, headerValue));
    }

    public void updateHeaders(final Map<String, String> headers) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("update headers")) return;
        modifyMapField(entity::getRequestHeaders, entity::setRequestHeaders,
                m -> m.putAll(headers), "Updated", "headers batch");
    }

    public void removePathParam(final String paramName) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("remove path param")) return;
        modifyMapField(entity::getPathParams, entity::setPathParams,
                m -> m.remove(paramName), "Removed", "path param: " + paramName);
    }

    public void removePathParams(final List<String> paramNames) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("remove path params")) return;
        modifyMapField(entity::getPathParams, entity::setPathParams,
                m -> paramNames.forEach(m::remove), "Removed", "path params: " + paramNames);
    }

    public void updatePathParam(final String paramName, final String paramValue) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("update path param")) return;
        modifyMapField(entity::getPathParams, entity::setPathParams,
                m -> m.put(paramName, paramValue), "Updated",
                String.format("path param '%s'='%s'", paramName, paramValue));
    }

    public void updatePathParams(final Map<String, String> params) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("update path params")) return;
        modifyMapField(entity::getPathParams, entity::setPathParams,
                m -> m.putAll(params), "Updated", "path params batch");
    }

    public void clearPathParams() {
        Entity entity = owner.getEntity();
        if (!entityAvailable("clear path params")) return;
        clearMapField(entity::setPathParams, "path parameters");
    }

    public void removeQueryParam(final String paramName) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("remove query param")) return;
        modifyMapField(entity::getQueryParams, entity::setQueryParams,
                m -> m.remove(paramName), "Removed", "query param: " + paramName);
    }

    public void removeQueryParams(final List<String> paramNames) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("remove query params")) return;
        modifyMapField(entity::getQueryParams, entity::setQueryParams,
                m -> paramNames.forEach(m::remove), "Removed", "query params: " + paramNames);
    }

    public void updateQueryParam(final String paramName, final String paramValue) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("update query param")) return;
        modifyMapField(entity::getQueryParams, entity::setQueryParams,
                m -> m.put(paramName, paramValue), "Updated",
                String.format("query param '%s'='%s'", paramName, paramValue));
    }

    public void updateQueryParams(final Map<String, String> params) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("update query params")) return;
        modifyMapField(entity::getQueryParams, entity::setQueryParams,
                m -> m.putAll(params), "Updated", "query params batch");
    }

    public void removeFormParam(final String paramName) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("remove form param")) return;
        modifyMapField(entity::getFormParams, entity::setFormParams,
                m -> m.remove(paramName), "Removed", "form param: " + paramName);
    }

    public void removeFormParams(final List<String> paramNames) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("remove form params")) return;
        modifyMapField(entity::getFormParams, entity::setFormParams,
                m -> paramNames.forEach(m::remove), "Removed", "form params: " + paramNames);
    }

    public void updateFormParam(final String paramName, final String paramValue) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("update form param")) return;
        modifyMapField(entity::getFormParams, entity::setFormParams,
                m -> m.put(paramName, paramValue), "Updated",
                String.format("form param '%s'='%s'", paramName, paramValue));
    }

    public void updateFormParams(final Map<String, String> params) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("update form params")) return;
        modifyMapField(entity::getFormParams, entity::setFormParams,
                m -> m.putAll(params), "Updated", "form params batch");
    }

    public void removeCookieParam(final String paramName) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("remove cookie param")) return;
        modifyMapField(entity::getCookies, entity::setCookies,
                m -> m.remove(paramName), "Removed", "cookie: " + paramName);
    }

    public void removeCookieParams(final List<String> paramNames) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("remove cookie params")) return;
        modifyMapField(entity::getCookies, entity::setCookies,
                m -> paramNames.forEach(m::remove), "Removed", "cookies: " + paramNames);
    }

    public void updateCookieParam(final String paramName, final String paramValue) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("update cookie param")) return;
        modifyMapField(entity::getCookies, entity::setCookies,
                m -> m.put(paramName, paramValue), "Updated",
                String.format("cookie '%s'='%s'", paramName, paramValue));
    }

    public void updateCookieParams(final Map<String, String> params) {
        Entity entity = owner.getEntity();
        if (!entityAvailable("update cookie params")) return;
        modifyMapField(entity::getCookies, entity::setCookies,
                m -> m.putAll(params), "Updated", "cookies batch");
    }
}
