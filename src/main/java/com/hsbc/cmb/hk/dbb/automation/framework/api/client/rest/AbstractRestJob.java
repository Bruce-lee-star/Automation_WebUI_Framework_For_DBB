package com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ConfigProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.api.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import com.hsbc.cmb.hk.dbb.automation.framework.api.domain.enums.ConfigKeys;
import com.typesafe.config.Config;
import net.serenitybdd.rest.SerenityRest;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.config.SSLConfig;
import io.restassured.http.Headers;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public abstract class AbstractRestJob {

    private static final Logger logger = LoggerFactory.getLogger(AbstractRestJob.class);

    private static RestAssuredConfig restAssuredConfig;

    private ValidatableResponse validatableResponse;

    static {
        initializeRestAssuredConfig();
        applySslPolicy();
    }

    /**
     * 应用 SSL/TLS 证书校验策略（安全基线）。
     * <p>
     * 默认【严格校验】（RestAssured 原生默认行为：校验证书链 + 主机名）。
     * 仅当配置显式声明 {@code http.ssl.relax-validation=true} 时才放宽。
     * <p>
     * 原实现无条件调用 {@code SerenityRest.useRelaxedHTTPSValidation()}，对所有经
     * SerenityRest/RestAssured 发出的 API 调用永久关闭证书校验（MITM 风险），
     * 并令 {@link FrameworkConfig#isSslRelaxValidation()}（默认 false）形同虚设。
     * <p>
     * 说明：RestAssured 5.x 未提供 {@code useStrictHTTPSValidation()}，恢复严格校验的
     * 正确方式是显式装配一个默认 {@link SSLConfig} 并写回 Serenity 的 default config。
     */
    private static void applySslPolicy() {
        boolean relax = FrameworkConfig.isSslRelaxValidation();
        SSLConfig sslConfig = relax
                ? SSLConfig.sslConfig().relaxedHTTPSValidation()
                : SSLConfig.sslConfig();
        SerenityRest.setDefaultConfig(SerenityRest.getDefaultConfig().sslConfig(sslConfig));

        if (relax) {
            logger.warn("SSL/TLS certificate validation is RELAXED (http.ssl.relax-validation=true). "
                    + "Certificate verification is disabled and API traffic is exposed to MITM. "
                    + "Intended for test environments only - never enable for production-like runs.");
        }
    }

    public abstract void perform(Entity entity);

    public static RestAssuredConfig getRestAssuredConfig() {
        return restAssuredConfig;
    }

    public static void setRestAssuredConfig(RestAssuredConfig restAssuredConfig) {
        AbstractRestJob.restAssuredConfig = restAssuredConfig;
    }

    public ValidatableResponse getValidatableResponse() {
        return validatableResponse;
    }

    public void setValidatableResponse(ValidatableResponse validatableResponse) {
        this.validatableResponse = validatableResponse;
    }

    /**
     * 剥离服务器返回的 HTML 包裹标签（如 &lt;html&gt;&lt;body&gt;...&lt;/body&gt;&lt;/html&gt;）
     * 提取 body 中间的实际内容（纯 JSON / XML / 文本等）
     */
    protected static String stripHtmlWrapper(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            return rawBody;
        }
        String trimmed = rawBody.trim();
        // 纯 JSON，无需处理
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        // 提取 <body>...</body> 中间内容
        int bodyStart = trimmed.indexOf("<body>");
        int bodyEnd = trimmed.indexOf("</body>");
        if (bodyStart != -1 && bodyEnd != -1) {
            return trimmed.substring(bodyStart + 6, bodyEnd).trim();
        }
        return trimmed;
    }

    // ==================== Rest*Job 模板方法（消除 5 份近拷贝） ====================

    /**
     * ⭐ 修复 P2-24：RestGetJob / RestPostJob / RestPutJob / RestPatchJob / RestDeleteJob
     * 五个实现类原先<b>逐字相同</b>，唯一差异是调用哪个 HTTP 方法（get/post/put/patch/delete）。
     * 每处约 40 行重复代码意味着任何一处修复（如新增 header 处理、代理逻辑）都要同步改 5 遍，
     * 漏改即产生行为不一致。这里上提为模板方法，子类只负责提供 HTTP 动作。
     *
     * @param entity  请求实体
     * @param invoker 具体 HTTP 动作，如 {@code spec -> spec.when().get(endpoint).then()}
     */
    protected void execute(Entity entity,
                           Function<RequestSpecification, ValidatableResponse> invoker) {
        RequestSpecification requestSpecification = buildRequestSpecification(entity);
        ValidatableResponse response = invoker.apply(requestSpecification);

        if (entity.isApiRequestResponseLogsEnabled()) {
            response.log().all();
        }

        applyResponse(entity, response);
    }

    /** 构建请求规格（base 信息 → 参数 → body → 代理 → 请求日志）。 */
    protected RequestSpecification buildRequestSpecification(Entity entity) {
        final RequestSpecification requestSpecification = SerenityRest.given()
                .baseUri(entity.getBaseUri())
                .basePath(entity.getBasePath())
                .config(AbstractRestJob.getRestAssuredConfig())
                .headers(entity.getRequestHeaders())
                .pathParams(entity.getPathParams())
                .queryParams(entity.getQueryParams())
                .formParams(entity.getFormParams())
                .cookies(entity.getCookies());

        if (StringUtils.isNotBlank(entity.getRequestPayload())) {
            requestSpecification.body(entity.getRequestPayload());
        }

        if (StringUtils.isNotBlank(entity.getProxyHost())) {
            requestSpecification.proxy(entity.getProxyHost(), entity.getProxyPort(), entity.getProxySchema());
        }

        if (entity.isApiRequestResponseLogsEnabled()) {
            requestSpecification.log().all();
        }

        return requestSpecification;
    }

    /** 将响应回写到 Entity（状态码、cookie、body、headers）。 */
    protected void applyResponse(Entity entity, ValidatableResponse response) {
        this.setValidatableResponse(response);
        entity.setResponseCode(response.extract().statusCode());
        entity.setResponseCookies(response.extract().response().cookies());
        entity.setResponsePayload(stripHtmlWrapper(response.extract().response().body().asString()));
        Headers headers = response.extract().response().headers();
        if (headers != null) {
            Map<String, String> responseHeader = new HashMap<>();
            headers.forEach(it -> responseHeader.put(it.getName(), it.getValue()));
            entity.setResponseHeaders(responseHeader);
        }
    }

    private static void initializeRestAssuredConfig() {
        final Config config = ConfigProvider.getConfig();
        int httpConnectTimeout;
        int httpSocketTimeout;

        // Priority: System Property > FrameworkConfig > Default value
        Optional<String> opt = Optional.ofNullable(System.getProperty(ConfigKeys.HTTP_CONNECTION_TIMEOUT.toString()));
        httpConnectTimeout = opt
            .map(Integer::parseInt)
            .orElse(FrameworkConfig.getConnectionTimeout());

        opt = Optional.ofNullable(System.getProperty(ConfigKeys.HTTP_SOCKET_TIMEOUT.toString()));
        httpSocketTimeout = opt
            .map(Integer::parseInt)
            .orElse(FrameworkConfig.getSocketTimeout());

        final RestAssuredConfig restAssuredConfig = RestAssuredConfig.config()
                .httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", httpConnectTimeout)
                        .setParam("http.socket.timeout", httpSocketTimeout));
        setRestAssuredConfig(restAssuredConfig);
    }
}
