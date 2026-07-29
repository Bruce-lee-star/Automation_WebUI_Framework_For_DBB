package com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.client;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ConfigProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.api.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.endpoint.EndpointConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.endpoint.EndpointProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.core.entity.ApiRequestEntity;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AbstractPlaywrightApiHelper - 请求构造辅助（Playwright API 版）
 * <p>
 * 镜像 RestAssured 版 {@code AbstractApiJobHelper}：提供 baseUri/endpoint/headers/params/payload
 * 的 setter 与修改方法，并复用传输无关的 {@link EndpointProvider} 与 {@link ConfigProvider}。
 */
public class AbstractPlaywrightApiHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractPlaywrightApiHelper.class);

    private ApiRequestEntity apiRequestEntity;

    public ApiRequestEntity getApiRequestEntity() {
        return apiRequestEntity;
    }

    public void setApiRequestEntity(ApiRequestEntity apiRequestEntity) {
        this.apiRequestEntity = apiRequestEntity;
    }

    // ============ 基础设置 ============

    public void setBaseUri(String baseUri) {
        requireEntity().setBaseUri(baseUri);
        LOGGER.info("Set base URI: {}", baseUri);
    }

    public void setBasePath(String basePath) {
        requireEntity().setBasePath(basePath);
        LOGGER.info("Set base path: {}", basePath);
    }

    public void setEndpoint(String endpoint) {
        requireEntity().setEndpoint(endpoint.startsWith("/") ? endpoint : "/" + endpoint);
        LOGGER.info("Set endpoint: {}", endpoint);
    }

    public void setRequestPayload(String payload) {
        requireEntity().setRequestPayload(payload);
        LOGGER.info("Set request payload ({} chars)", payload != null ? payload.length() : 0);
    }

    public void setProxy(String host, int port, String schema) {
        ApiRequestEntity entity = requireEntity();
        entity.setProxyHost(host);
        entity.setProxyPort(port);
        entity.setProxySchema(schema);
        LOGGER.info("Set proxy: {}://{}:{}", schema, host, port);
    }

    public void setApiRequestResponseLogsEnabled(boolean enabled) {
        requireEntity().setApiRequestResponseLogsEnabled(enabled);
        LOGGER.info("Set API request/response logs enabled: {}", enabled);
    }

    // ============ Headers ============

    public void addRequestHeader(String name, Object value) {
        requireEntity().addRequestHeader(name, value);
        LOGGER.info("Added request header: {} = {}", name, maskValue(name, value));
    }

    public void updateHeaders(Map<String, String> headers) {
        requireEntity().getRequestHeaders().putAll(headers);
        LOGGER.info("Updated headers batch: {}", maskHeadersMap(headers));
    }

    public void updateHeader(String name, String value) {
        requireEntity().addRequestHeader(name, value);
        LOGGER.info("Updated header: {} = {}", name, maskValue(name, value));
    }

    public void removeHeader(String name) {
        requireEntity().removeRequestHeader(name);
        LOGGER.info("Removed header: {}", name);
    }

    public void removeHeaders(List<String> names) {
        names.forEach(requireEntity()::removeRequestHeader);
        LOGGER.info("Removed headers: {}", names);
    }

    public void clearHeader() {
        requireEntity().getRequestHeaders().clear();
        LOGGER.info("Cleared request headers");
    }

    // ============ Path params ============

    public void addPathParam(String name, Object value) {
        requireEntity().addPathParam(name, value);
        LOGGER.info("Added path param: {} = {}", name, maskValue(name, value));
    }

    public void updatePathParams(Map<String, String> params) {
        requireEntity().getPathParams().putAll(params);
        LOGGER.info("Updated path params: {}", params);
    }

    public void removePathParam(String name) {
        requireEntity().removePathParam(name);
        LOGGER.info("Removed path param: {}", name);
    }

    public void clearPathParams() {
        requireEntity().getPathParams().clear();
        LOGGER.info("Cleared path params");
    }

    // ============ Query params ============

    public void addQueryParam(String name, Object value) {
        requireEntity().addQueryParam(name, value);
        LOGGER.info("Added query param: {} = {}", name, maskValue(name, value));
    }

    public void updateQueryParams(Map<String, String> params) {
        requireEntity().getQueryParams().putAll(params);
        LOGGER.info("Updated query params: {}", params);
    }

    public void removeQueryParam(String name) {
        requireEntity().removeQueryParam(name);
        LOGGER.info("Removed query param: {}", name);
    }

    public void clearQueryParams() {
        requireEntity().getQueryParams().clear();
        LOGGER.info("Cleared query params");
    }

    // ============ Form params ============

    public void addFormParam(String name, Object value) {
        requireEntity().addFormParam(name, value);
        LOGGER.info("Added form param: {} = {}", name, maskValue(name, value));
    }

    public void updateFormParams(Map<String, String> params) {
        requireEntity().getFormParams().putAll(params);
        LOGGER.info("Updated form params: {}", params);
    }

    public void removeFormParam(String name) {
        requireEntity().removeFormParam(name);
        LOGGER.info("Removed form param: {}", name);
    }

    public void clearFormParams() {
        requireEntity().getFormParams().clear();
        LOGGER.info("Cleared form params");
    }

    // ============ Multipart（文件上传） ============

    public void addMultipartParam(String name, Object value) {
        requireEntity().addMultipartParam(name, value);
        LOGGER.info("Added multipart param: {} = {}", name, maskValue(name, value));
    }

    public void addMultipartFile(String name, java.nio.file.Path file) {
        requireEntity().addMultipartFile(name, file);
        LOGGER.info("Added multipart file: {} = {}", name, file);
    }

    public void removeMultipartParam(String name) {
        requireEntity().removeMultipartParam(name);
        LOGGER.info("Removed multipart param: {}", name);
    }

    public void clearMultipartParams() {
        requireEntity().getMultipartParams().clear();
        LOGGER.info("Cleared multipart params");
    }

    // ============ Per-request timeout ============

    public void setRequestTimeout(long timeoutMillis) {
        requireEntity().setRequestTimeout(timeoutMillis);
        LOGGER.info("Set request timeout: {} ms", timeoutMillis);
    }

    // ============ Cookies ============

    public void addCookie(String name, Object value) {
        requireEntity().addCookie(name, value);
        LOGGER.info("Added cookie: {} = {}", name, maskValue(name, value));
    }

    public void updateCookieParams(Map<String, String> params) {
        requireEntity().getCookies().putAll(params);
        LOGGER.info("Updated cookies: {}", maskHeadersMap(params));
    }

    public void removeCookieParam(String name) {
        requireEntity().getCookies().remove(name);
        LOGGER.info("Removed cookie: {}", name);
    }

    public void clearCookies() {
        requireEntity().getCookies().clear();
        LOGGER.info("Cleared cookies");
    }

    // ============ Payload 文件加载 ============

    public void loadPayload(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            LOGGER.error("Payload file name is null or empty.");
            return;
        }
        ApiRequestEntity entity = requireEntity();
        String payloadPath = ConfigProvider.getPayloadPath(fileName.trim());
        if (payloadPath == null || payloadPath.trim().isEmpty()) {
            LOGGER.error("Payload file path is empty for file: [{}]", fileName);
            return;
        }
        File payloadFile = new File(payloadPath);
        if (!payloadFile.exists() || !payloadFile.isFile()) {
            LOGGER.error("Payload file NOT found: [{}]", payloadPath);
            return;
        }
        try {
            String encoding = FrameworkConfig.getPayloadEncoding();
            String content = new String(Files.readAllBytes(payloadFile.toPath()), Charset.forName(encoding));
            if (content == null || content.trim().isEmpty()) {
                entity.setRequestPayload("");
                return;
            }
            DocumentContext ctx = JsonPath.parse(content);
            entity.setRequestPayload(ctx.jsonString());
            LOGGER.info("Loaded payload file successfully: [{}]", fileName);
        } catch (IOException e) {
            throw new RuntimeException("IO error reading payload file: " + fileName, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse payload file: " + fileName, e);
        }
    }

    /**
     * 修改请求体中的字段（支持任意 JSONPath，如 "user.info[0].city"）
     */
    public void modifyFieldsInRequestPayload(String fieldPath, Object fieldValue) {
        try {
            DocumentContext doc = JsonPath.using(Configuration.builder()
                            .jsonProvider(new JacksonJsonProvider())
                            .mappingProvider(new JacksonMappingProvider())
                            .build())
                    .parse(requireEntity().getRequestPayload());
            doc.set(fieldPath, fieldValue);
            requireEntity().setRequestPayload(doc.jsonString());
            LOGGER.info("Modified payload field: {} = {}", fieldPath, fieldValue);
        } catch (Exception e) {
            throw new RuntimeException("Failed to modify field [" + fieldPath + "] in payload", e);
        }
    }

    // ============ Endpoint 配置（复用传输无关的 EndpointProvider） ============

    /**
     * 加载并应用 endpoint 配置（path/headers/params/payload）。
     *
     * @return true 配置加载成功
     */
    public boolean loadEndpointConfig(String endpointName, String method) {
        EndpointConfig endpointConfig = EndpointProvider.getEndpoint(endpointName, method);
        if (endpointConfig == null) {
            LOGGER.warn("Endpoint configuration not found: {} {}", method, endpointName);
            return false;
        }
        LOGGER.info("Applying endpoint configuration: {} {}", method, endpointName);
        ApiRequestEntity entity = requireEntity();

        if (endpointConfig.getPath() != null) {
            entity.setEndpoint(endpointConfig.getPath());
        }
        if (endpointConfig.getHeaders() != null) {
            endpointConfig.getHeaders().forEach(entity::addRequestHeader);
        }
        if (endpointConfig.getQueryParams() != null) {
            endpointConfig.getQueryParams().forEach(entity::addQueryParam);
        }
        if (endpointConfig.getPathParams() != null) {
            endpointConfig.getPathParams().forEach(entity::addPathParam);
        }
        if (endpointConfig.getFormParams() != null) {
            endpointConfig.getFormParams().forEach(entity::addFormParam);
        }
        if (endpointConfig.getCookies() != null) {
            endpointConfig.getCookies().forEach(entity::addCookie);
        }
        if (endpointConfig.getPayloadFile() != null && !endpointConfig.getPayloadFile().isEmpty()) {
            loadPayload(endpointConfig.getPayloadFile());
        }
        LOGGER.info("Endpoint configuration applied successfully");
        return true;
    }

    public boolean hasEndpointConfig(String endpointName, String method) {
        return EndpointProvider.hasEndpoint(endpointName, method);
    }

    // ============ 读取类 ============

    public String getRequestPayload() {
        return requireEntity().getRequestPayload();
    }

    public String getEndpoint() {
        return requireEntity().getEndpoint();
    }

    public String getBaseUri() {
        return requireEntity().getBaseUri();
    }

    public int getResponseCode() {
        return requireEntity().getResponseCode();
    }

    public String getResponsePayload() {
        return requireEntity().getResponsePayload();
    }

    public Map<String, String> getResponseHeaders() {
        return requireEntity().getResponseHeaders();
    }

    // ============ 内部 ============

    /**
     * 对日志中的参数/头/Cookie 值做掩码：敏感头名或值名含 token/secret/password/session/auth 等
     * 关键字时输出 "***"，避免凭据写入日志。仅影响日志输出，不改变实际请求数据。
     */
    private static String maskValue(String name, Object value) {
        if (name == null) {
            return String.valueOf(value);
        }
        String lower = name.toLowerCase();
        boolean sensitive = PlaywrightApiClient.isSensitiveHeader(name)
                || lower.contains("token") || lower.contains("secret") || lower.contains("password")
                || lower.contains("passwd") || lower.contains("pwd") || lower.contains("session")
                || lower.contains("auth") || lower.contains("credential") || lower.contains("pin")
                || lower.contains("otp") || lower.contains("apikey") || lower.contains("api_key")
                || lower.contains("cvv");
        return sensitive ? "***" : String.valueOf(value);
    }

    private static Map<String, String> maskHeadersMap(Map<String, String> headers) {
        Map<String, String> masked = new HashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            masked.put(e.getKey(), maskValue(e.getKey(), e.getValue()));
        }
        return masked;
    }

    private ApiRequestEntity requireEntity() {
        if (apiRequestEntity == null) {
            throw new IllegalStateException("ApiRequestEntity is not initialized. Use PlaywrightApiTestServices to create the step.");
        }
        return apiRequestEntity;
    }
}
