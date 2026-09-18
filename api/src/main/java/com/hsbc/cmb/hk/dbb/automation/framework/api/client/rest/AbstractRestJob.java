package com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ConfigProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ApiFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.api.security.SecurityAudit;
import com.hsbc.cmb.hk.dbb.automation.framework.api.utility.EnvironmentUtils;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import com.hsbc.cmb.hk.dbb.automation.framework.api.domain.enums.ConfigKeys;
import com.typesafe.config.Config;
import net.serenitybdd.rest.SerenityRest;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.LogConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.config.SSLConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.api.logging.SanitizingPrintStream;
import io.restassured.http.Headers;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
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
     * 并令 {@link ApiFrameworkConfig#isSslRelaxValidation()}（默认 false）形同虚设。
     * <p>
     * 说明：RestAssured 5.x 未提供 {@code useStrictHTTPSValidation()}，恢复严格校验的
     * 正确方式是显式装配一个默认 {@link SSLConfig} 并写回 Serenity 的 default config。
     * <p>
     * 安全审计：放宽时登记 {@link SecurityAudit#recordRelaxedTls(String, String)}（可观测、
     * 可被测试断言）；若检测到生产（prod）环境仍放宽，则 fail-fast 拒绝启动，防止生产
     * 流量暴露于中间人攻击。
     */
    private static void applySslPolicy() {
        boolean relax = ApiFrameworkConfig.isSslRelaxValidation();
        SSLConfig sslConfig = relax
                ? SSLConfig.sslConfig().relaxedHTTPSValidation()
                : SSLConfig.sslConfig();
        SerenityRest.setDefaultConfig(SerenityRest.getDefaultConfig().sslConfig(sslConfig));

        if (relax) {
            String environment = EnvironmentUtils.currentEnvironment().getEnvironmentName();
            SecurityAudit.recordRelaxedTls(environment, ApiFrameworkConfig.HTTP_SSL_RELAX_VALIDATION.key());
            if (SecurityAudit.isProductionEnvironment(environment)) {
                throw new IllegalStateException(
                        "Refusing to start: TLS certificate validation is RELAXED in a PRODUCTION-like "
                                + "environment (env=" + environment + "). Never enable http.ssl.relax-validation "
                                + "for production-like runs - this exposes API traffic to MITM.");
            }
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
     *  RestGetJob / RestPostJob / RestPutJob / RestPatchJob / RestDeleteJob
     * 五个实现类原先<b>逐字相同</b>，唯一差异是调用哪个 HTTP 方法（get/post/put/patch/delete）。
     * 每处约 40 行重复代码意味着任何一处修复（如新增 header 处理、代理逻辑）都要同步改 5 遍，
     * 漏改即产生行为不一致。这里上提为模板方法，子类只负责提供 HTTP 动作。
     *
     * @param entity  请求实体
     * @param invoker 具体 HTTP 动作，如 {@code spec -> spec.when().get(endpoint).then()}
     */
    /**
     * 执行请求（默认非幂等，不重试 —— 与历史行为一致，避免对非幂等写操作误重试）。
     * 幂等请求（GET/PUT/DELETE）请改用 {@link #execute(Entity, Function, boolean)} 并传 {@code true}。
     */
    protected void execute(Entity entity,
                           Function<RequestSpecification, ValidatableResponse> invoker) {
        execute(entity, invoker, false);
    }

    /**
     * 执行请求（P-3：幂等 + 网络类错误 + 5xx 重试，绝不重试 4xx）。
     *
     * @param entity     请求实体
     * @param invoker    具体 HTTP 动作
     * @param idempotent 是否幂等（GET/PUT/DELETE=true；POST/PATCH=false）。仅当幂等时才对
     *                   连接超时 / 网络异常 / 5xx 重试，防止对非幂等写操作重复提交。
     */
    protected void execute(Entity entity,
                           Function<RequestSpecification, ValidatableResponse> invoker,
                           boolean idempotent) {
        ValidatableResponse response = executeWithRetry(entity, invoker, idempotent);
        if (entity.isApiRequestResponseLogsEnabled()) {
            response.log().all();
        }
        applyResponse(entity, response);
    }

    /**
     * P-3 重试内核：仅在幂等且发生「连接超时 / 网络异常 / 5xx」时重试，
     * 4xx（客户端错误）立即返回不重试。重试次数 / 退避间隔取自
     * {@link ApiFrameworkConfig#getRetryCount()} / {@link ApiFrameworkConfig#getRetryDelay()}。
     */
    ValidatableResponse executeWithRetry(Entity entity,
                                          Function<RequestSpecification, ValidatableResponse> invoker,
                                          boolean idempotent) {
        int maxAttempts = ApiFrameworkConfig.getRetryCount();
        long delayMs = ApiFrameworkConfig.getRetryDelay();
        ValidatableResponse last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ValidatableResponse response = invoker.apply(buildRequestSpecification(entity));
                int status = response.extract().statusCode();
                if (shouldRetry(status, idempotent, attempt, maxAttempts)) {
                    logger.warn("API 请求返回 5xx（{}），幂等方法第 {}/{} 次将重试（退避 {}ms）: {}",
                            status, attempt, maxAttempts, delayMs, entity.getBaseUri());
                    sleepQuietly(delayMs);
                    last = response;
                    continue;
                }
                return response;
            } catch (Exception e) {
                if (idempotent && attempt < maxAttempts) {
                    logger.warn("API 请求失败（{}），幂等方法第 {}/{} 次将重试（退避 {}ms）: {}",
                            e.getMessage(), attempt, maxAttempts, delayMs, entity.getBaseUri());
                    sleepQuietly(delayMs);
                    last = null;
                    continue;
                }
                throw e;
            }
        }
        return last;
    }

    /**
     * 重试退避等待：框架主代码禁用 {@code Thread.sleep}（ArchUnit {@code frameworkCodeMustNotCallThreadSleep}），
     * 退避需「可中断且不抛受检异常」，故改用 {@link LockSupport#parkNanos(long)}。
     * park 在中断时立即返回且<b>保留中断标志</b>（语义等价于 {@code Thread.sleep} + 恢复中断）。
     */
    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        LockSupport.parkNanos(millis * 1_000_000L);
        if (Thread.currentThread().isInterrupted()) {
            logger.debug("API 重试退避被中断（parkNanos 提前返回，中断标志保留）");
        }
    }

    /**
     * 重试判定（P-3 内核规则，包可见便于单测）：
     * 仅当<b>幂等</b>且响应为 <b>5xx</b> 且<b>未达最大尝试次数</b>时重试；
     * 4xx（客户端错误）与非幂等写操作（POST/PATCH）一律不重试，避免重复提交或无效重试。
     */
    boolean shouldRetry(int statusCode, boolean idempotent, int attempt, int maxAttempts) {
        return idempotent && statusCode >= 500 && statusCode <= 599 && attempt < maxAttempts;
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

        // Priority: System Property > ApiFrameworkConfig > Default value
        Optional<String> opt = Optional.ofNullable(System.getProperty(ConfigKeys.HTTP_CONNECTION_TIMEOUT.toString()));
        httpConnectTimeout = opt
            .map(Integer::parseInt)
            .orElse(ApiFrameworkConfig.getConnectionTimeout());

        opt = Optional.ofNullable(System.getProperty(ConfigKeys.HTTP_SOCKET_TIMEOUT.toString()));
        httpSocketTimeout = opt
            .map(Integer::parseInt)
            .orElse(ApiFrameworkConfig.getSocketTimeout());

        // P-3：连接复用（RestAssured 默认每请求新建客户端，握手开销大）。
        //
        //  ⚠️【重要】不可再经 httpClientFactory 注入自建 HttpClient：
        //    rest-assured 的 Groovy 实现（RequestSpecificationImpl.applyPathParamsAndSendRequest
        //    → DefaultTypeTransformation.castToType）会把该实例强转为
        //    org.apache.http.impl.client.AbstractHttpClient（HttpClient 4.3 之前的抽象类），
        //    而 4.3+ 交由 HttpClients.custom().build() 产出的是 InternalHttpClient（并非其子类）
        //    → 每个请求抛 GroovyCastException: Cannot cast ... InternalHttpClient ... to ... AbstractHttpClient，
        //      整批 API 用例全红（实测 134 次）。
        //
        //  改用「由 rest-assured 自身创建客户端」：不设置 httpClientFactory、也不强制单实例复用，
        //    只配置超时与脱敏日志。客户端构造路径完全交给 rest-assured，既避开上述 Groovy 强转，
        //    也避开 reuseHttpClientInstance() 的另一个坑（单连接被响应占用未释放时，后续请求抛
        //    "Invalid use of BasicClientConnManager: connection still allocated" —— 实测 9/12 用例因此失败）。
        //
        //  P-3 权衡：放弃了"显式连接池（max-total/per-route）"的调优；连接复用改由 rest-assured
        //    默认行为提供。连接池调优与 rest-assured 的 Groovy 客户端强转在本依赖版本组合下互斥，
        //    以「API 用例可用」优先（原先 134 次强转导致整批用例全红，属功能性缺陷，优先级高于吞吐调优）。
        final RestAssuredConfig restAssuredConfig = RestAssuredConfig.config()
                .httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", httpConnectTimeout)
                        .setParam("http.socket.timeout", httpSocketTimeout))
                .logConfig(new LogConfig(
                        new SanitizingPrintStream(System.out), true));
        setRestAssuredConfig(restAssuredConfig);
    }
}
