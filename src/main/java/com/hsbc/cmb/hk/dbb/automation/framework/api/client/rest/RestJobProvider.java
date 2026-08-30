package com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest;

import com.hsbc.cmb.hk.dbb.automation.framework.api.client.AbstractApiJobHelper;
import com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest.impl.*;
import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ConfigProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.api.domain.enums.ConfigKeys;
import io.restassured.response.ValidatableResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class RestJobProvider extends AbstractApiJobHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(RestJobProvider.class);

    /**
     * Send GET request with full URL (auto-parses baseUri, endpoint, and query params)
     * @param fullUrl Complete URL including protocol, domain, path, and query parameters
     *                Example: "https://api.example.com/users?status=active&page=1"
     */
    public void getFullUrl(String fullUrl) {
        parseAndSetUrl(fullUrl);
        this.setRestJob(new RestGetJob());
        this.getRestJob().perform(this.getEntity());
    }

    /**
     * Send POST request with full URL (auto-parses baseUri, endpoint, and query params)
     * @param fullUrl Complete URL including protocol, domain, path, and query parameters
     *                Example: "https://api.example.com/users?returnFields=id,name"
     */
    public void postFullUrl(String fullUrl) {
        parseAndSetUrl(fullUrl);
        this.setRestJob(new RestPostJob());
        this.getRestJob().perform(this.getEntity());
    }

    /**
     * Send PUT request with full URL (auto-parses baseUri, endpoint, and query params)
     * @param fullUrl Complete URL including protocol, domain, path, and query parameters
     */
    public void putFullUrl(String fullUrl) {
        parseAndSetUrl(fullUrl);
        this.setRestJob(new RestPutJob());
        this.getRestJob().perform(this.getEntity());
    }

    /**
     * Send PATCH request with full URL (auto-parses baseUri, endpoint, and query params)
     * @param fullUrl Complete URL including protocol, domain, path, and query parameters
     */
    public void patchFullUrl(String fullUrl) {
        parseAndSetUrl(fullUrl);
        this.setRestJob(new RestPatchJob());
        this.getRestJob().perform(this.getEntity());
    }

    /**
     * Send DELETE request with full URL (auto-parses baseUri, endpoint, and query params)
     * @param fullUrl Complete URL including protocol, domain, path, and query parameters
     */
    public void deleteFullUrl(String fullUrl) {
        parseAndSetUrl(fullUrl);
        this.setRestJob(new RestDeleteJob());
        this.getRestJob().perform(this.getEntity());
    }

    /**
     * Parse full URL and set baseUri, endpoint, and query parameters to Entity
     * @param fullUrl Complete URL including protocol, domain, path, and query parameters
     */
    private void parseAndSetUrl(String fullUrl) {
        try {
            URI uri = new URI(fullUrl);
            
            // Extract and set baseUri (scheme + authority)
            String baseUri = new URI(uri.getScheme(), uri.getAuthority(), null, null, null).toString();
            this.setBaseUri(baseUri);
            
            // Extract and set endpoint (path)
            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                this.setEndpoint("/");
            } else {
                this.setEndpoint(path);
            }
            
            // Parse and add query parameters
            String query = uri.getQuery();
            if (query != null && !query.isEmpty()) {
                String[] pairs = query.split("&");
                for (String pair : pairs) {
                    String[] keyValue = pair.split("=", 2);
                    if (keyValue.length == 2) {
                        String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
                        String value = URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
                        this.addQueryParam(key, value);
                        LOGGER.debug("Added query param: {} = {}", key, value);
                    }
                }
            }
            
            LOGGER.info("Parsed URL - BaseUri: {}, Endpoint: {}, Query params count: {}", 
                baseUri, path, query != null ? query.split("&").length : 0);
            
        } catch (Exception e) {
            String errorMsg = String.format("Failed to parse URL: %s", fullUrl);
            LOGGER.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }

    public void postPayload() {
        this.setRestJob(new RestPostJob());
        this.getRestJob().perform(this.getEntity());
    }

    public void getResource() {
        this.setRestJob(new RestGetJob());
        this.getRestJob().perform(this.getEntity());
    }

    public void putPayload() {
        this.setRestJob(new RestPutJob());
        this.getRestJob().perform(this.getEntity());
    }

    public void patchPayload() {
        this.setRestJob(new RestPatchJob());
        this.getRestJob().perform(this.getEntity());
    }

    public void deleteResource() {
        this.setRestJob(new RestDeleteJob());
        this.getRestJob().perform(this.getEntity());
    }

    public String getResponseJson() {
        return this.getEntity().getResponsePayload();
    }

    public ValidatableResponse getValidatableResponse() {
        Object raw = this.getEntity().getValidatableResponse();
        if (raw == null) {
            return null;
        }
        if (raw instanceof ValidatableResponse) {
            return (ValidatableResponse) raw;
        }
        LOGGER.warn("Entity.validatableResponse is not a ValidatableResponse instance, actual type: {}",
                raw.getClass().getName());
        return null;
    }

    public int getResponseCode() {
        return this.getEntity().getResponseCode();
    }

    public String getRequestBody() {
        return this.getEntity().getRequestPayload();
    }

    public void setRequestBody(final String requestBody){
        this.getEntity().setRequestPayload(requestBody);
    }

    /**
     * @deprecated ⭐ 修复 P3-31：命名与全框架不一致（其余各处均为 {@code setEndpoint}，
     *             见 {@code Entity} / {@code AbstractApiJobHelper} / {@code BaseStep}）。
     *             本类继承自 {@link AbstractApiJobHelper}，后者的 {@code setEndpoint(String)}
     *             已提供同名字段的能力，两者重复；且经全仓库检索，本方法<b>无任何调用点</b>。
     *             请改用 {@code setEndpoint(...)}。本方法仅为兼容保留，后续版本将移除。
     *             （保留原有"自动补前导 /"的行为，避免兼容期语义变化。）
     */
    @Deprecated
    public void setEndPoint(final String endPoint){
        setEndpoint(endPoint != null && endPoint.startsWith("/") ? endPoint : "/" + endPoint);
    }

    /**
     * @deprecated ⭐ 修复 P3-31：命名不一致（应为 {@code getEndpoint}），且无调用点。
     *             请改用继承自 {@link AbstractApiJobHelper#getEndpoint()} 的同名方法。
     */
    @Deprecated
    public String getEndPoint(){
        return getEndpoint();
    }

    /**
     * ⭐ 修复 P2-24：原实现查到配置值后<b>只打印一条 warn 就返回</b>，是彻底的 no-op ——
     * 调用方会误以为 basePath 已切换，实际 Entity 上的值毫无变化，属于典型的"静默失败"，
     * 排查成本极高。现改为真正写入 Entity，使方法名与行为一致。
     */
    public void switchBasePath(final String key){
        final String basePath = ConfigProvider.getConfig(ConfigKeys.API_BASE_PATH.toString()).getString(key);
        if (basePath == null || basePath.isEmpty()) {
            LOGGER.warn("switchBasePath({}) — key not found under {}", key, ConfigKeys.API_BASE_PATH);
            return;
        }
        this.getEntity().setBasePath(basePath);
        LOGGER.info("switchBasePath({}) applied -> {}", key, basePath);
    }

    /**
     * ⭐ 修复 P2-24：同 {@link #switchBasePath(String)}，原为 no-op，现真正写入 Entity。
     */
    public void switchBaseUri(final String key){
        final String baseUri = ConfigProvider.getConfig(ConfigKeys.API_BASE_URI.toString()).getString(key);
        if (baseUri == null || baseUri.isEmpty()) {
            LOGGER.warn("switchBaseUri({}) — key not found under {}", key, ConfigKeys.API_BASE_URI);
            return;
        }
        this.getEntity().setBaseUri(baseUri);
        LOGGER.info("switchBaseUri({}) applied -> {}", key, baseUri);
    }

}
