package com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.core.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.client.AbstractPlaywrightApiHelper;
import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.client.ApiContextScope;
import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.client.PlaywrightApiClient;
import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.client.PlaywrightApiHttpMethod;
import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.core.entity.ApiRequestEntity;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.core.Serenity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * PlaywrightApiStep - Playwright 接口自动化的核心步骤类（Serenity 集成）
 * <p>
 * 镜像 RestAssured 版 {@code BaseStep}，但底层走 Playwright {@code APIRequestContext}。
 * 所有对外步骤方法标注 {@link Step}，会自动出现在 Serenity 的 Living Documentation 报告中，
 * 与 UI 步骤共用同一份 {@code target/site/serenity} HTML 报告。
 */
public class PlaywrightApiStep extends AbstractPlaywrightApiHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaywrightApiStep.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 是否在 Serenity 报告中附加请求/响应原文（默认开启，便于排错） */
    private static boolean recordReportDataEnabled = true;

    /** Serenity 报告中响应体的最大长度，超出截断，防止报告膨胀 */
    private static final int MAX_REPORT_BODY = 4000;

    /** 本步骤所属的场景作用域（持有 APIRequestContext + 可能的独立 Playwright 实例）。
     *  显式持有而非依赖 ThreadLocal，使 {@link #close()} 在任意线程调用都能精准释放。 */
    private final ApiContextScope scope;

    public static void setRecordReportDataEnabled(boolean enabled) {
        recordReportDataEnabled = enabled;
    }

    /**
     * 包级构造：只能通过 {@link PlaywrightApiTestServices} 创建
     */
    PlaywrightApiStep(ApiRequestEntity entity, ApiContextScope scope) {
        this.setApiRequestEntity(entity);
        this.scope = scope;
    }

    // ============ 发送请求（@Step → Serenity 报告） ============

    @Step("Send GET request")
    public void sendGet() {
        request(PlaywrightApiHttpMethod.GET);
    }

    @Step("Send GET request to {0}")
    public void sendGet(String endpoint) {
        setEndpoint(endpoint);
        request(PlaywrightApiHttpMethod.GET);
    }

    @Step("Send POST request")
    public void sendPost() {
        request(PlaywrightApiHttpMethod.POST);
    }

    @Step("Send POST request to {0}")
    public void sendPost(String endpoint) {
        setEndpoint(endpoint);
        request(PlaywrightApiHttpMethod.POST);
    }

    @Step("Send PUT request")
    public void sendPut() {
        request(PlaywrightApiHttpMethod.PUT);
    }

    @Step("Send PUT request to {0}")
    public void sendPut(String endpoint) {
        setEndpoint(endpoint);
        request(PlaywrightApiHttpMethod.PUT);
    }

    @Step("Send PATCH request")
    public void sendPatch() {
        request(PlaywrightApiHttpMethod.PATCH);
    }

    @Step("Send PATCH request to {0}")
    public void sendPatch(String endpoint) {
        setEndpoint(endpoint);
        request(PlaywrightApiHttpMethod.PATCH);
    }

    @Step("Send DELETE request")
    public void sendDelete() {
        request(PlaywrightApiHttpMethod.DELETE);
    }

    @Step("Send DELETE request to {0}")
    public void sendDelete(String endpoint) {
        setEndpoint(endpoint);
        request(PlaywrightApiHttpMethod.DELETE);
    }

    @Step("Send {0} request to {1}")
    public void send(String method, String endpoint) {
        setEndpoint(endpoint);
        request(PlaywrightApiHttpMethod.valueOf(method.toUpperCase()));
    }

    @Step("Send {0} request")
    public void send(String method) {
        request(PlaywrightApiHttpMethod.valueOf(method.toUpperCase()));
    }

    private void request(PlaywrightApiHttpMethod method) {
        PlaywrightApiClient.execute(this.getApiRequestEntity(), method);
        recordToSerenity(method);
    }

    // ============ 断言（@Step → Serenity 报告） ============

    @Step("Verify response status code is {0}")
    public void verifyStatusCode(int expectedStatusCode) {
        int actual = getApiRequestEntity().getResponseCode();
        try {
            assertThat("Response status code mismatch", actual, equalTo(expectedStatusCode));
            LOGGER.info("Status code verified: {} == {}", actual, expectedStatusCode);
        } catch (AssertionError e) {
            LOGGER.error("Status code mismatch: {} != {}", actual, expectedStatusCode);
            throw e;
        }
    }

    @Step("Verify response json path {0} equals {1}")
    public void verifyResponseJsonPath(String fieldPath, Object expectedValue) {
        Object actual;
        try {
            actual = readJsonPath(fieldPath);
        } catch (IllegalStateException e) {
            throw new AssertionError("JSON path not found or extraction failed: " + fieldPath
                    + " -> " + e.getMessage());
        }
        Object coerced = coerceToExpected(actual, expectedValue);
        assertThat("JSON field mismatch for path: " + fieldPath, coerced, equalTo(expectedValue));
        LOGGER.info("JSON verified: {} = {}", fieldPath, coerced);
    }

    @Step("Verify response array length of {0} is {1}")
    public void verifyJsonArrayLength(String arrayPath, int expectedLength) {
        String body = getApiRequestEntity().getResponsePayload();
        assertThat("Response body is empty", body, notNullValue());
        try {
            DocumentContext ctx = JsonPath.parse(body);
            int actual = ctx.read(arrayPath + ".size()");
            assertThat("Array length mismatch", actual, equalTo(expectedLength));
            LOGGER.info("Array length verified: {} has length {}", arrayPath, actual);
        } catch (Exception e) {
            throw new AssertionError("Array length verification failed: " + e.getMessage());
        }
    }

    @Step("Verify response header {0} equals {1}")
    public void verifyResponseHeader(String headerName, String expectedValue) {
        Map<String, String> headers = getApiRequestEntity().getResponseHeaders();
        String actual = findHeaderCaseInsensitive(headers, headerName);
        assertThat("Response header missing: " + headerName, actual, notNullValue());
        assertThat("Response header value mismatch", actual, equalTo(expectedValue));
        LOGGER.info("Header verified: {} = {}", headerName, actual);
    }

    @Step("Verify response header {0} contains {1}")
    public void verifyResponseHeaderContains(String headerName, String expectedValue) {
        Map<String, String> headers = getApiRequestEntity().getResponseHeaders();
        String actual = findHeaderCaseInsensitive(headers, headerName);
        assertThat("Response header missing: " + headerName, actual, notNullValue());
        assertThat("Response header value mismatch", actual, containsString(expectedValue));
        LOGGER.info("Header contains verified: {} ~ {}", headerName, expectedValue);
    }

    @Step("Verify response time is less than {0} ms")
    public void verifyResponseTimeLessThan(long maxMillis) {
        long actual = getApiRequestEntity().getResponseTimeMs();
        assertThat("Response time exceeds limit", actual, lessThan(maxMillis));
        LOGGER.info("Response time verified: {} ms < {} ms", actual, maxMillis);
    }

    private static String findHeaderCaseInsensitive(Map<String, String> headers, String name) {
        if (headers == null) return null;
        // 先精确匹配，再忽略大小写匹配（Playwright 会把响应头名转小写）
        if (headers.containsKey(name)) {
            return headers.get(name);
        }
        String lower = name.toLowerCase();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().toLowerCase().equals(lower)) {
                return e.getValue();
            }
        }
        return null;
    }

    @Step("Verify response body contains {0}")
    public void verifyResponseBodyContains(String expectedContent) {
        String body = getApiRequestEntity().getResponsePayload();
        if (body == null || body.trim().isEmpty()) {
            throw new AssertionError("Response body is null or empty");
        }
        try {
            assertThat("Response body does not contain: " + expectedContent, body, containsString(expectedContent));
            LOGGER.info("Body contains verified: '{}'", expectedContent);
        } catch (AssertionError e) {
            String truncated = body.length() > 200 ? body.substring(0, 200) + "...[truncated]" : body;
            LOGGER.error("Body contains failed. Body(truncated): {}", truncated);
            throw e;
        }
    }

    // ============ 响应取值（接口串联 / 关联），统一走 JsonPath ============

    @Step("Extract value from response json path {0}")
    public String extractJsonPath(String fieldPath) {
        Object value = readJsonPath(fieldPath);
        if (value == null) {
            return null;
        }
        // Map/List 输出为 JSON 文本，标量输出为字符串
        if (value instanceof Map || value instanceof List) {
            try {
                return OBJECT_MAPPER.writeValueAsString(value);
            } catch (Exception ignore) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    /**
     * 从响应体按 JSON path 提取值并转换为目标类型，用于接口串联（如把 token 传入下一请求）。
     * 例：{@code api.addRequestHeader("Authorization", "Bearer " + api.extractJsonPath("access_token"));}
     * 支持 JsonPath 全部语法，包括 {@code data.items[?(@.id==1)].name} 等过滤表达式。
     *
     * @param fieldPath JSON path
     * @param type      目标类型（String/Integer/Boolean/Long/Double 或 POJO 类）
     * @param <T>       目标类型
     * @return 提取并转换后的值
     */
    @Step("Extract typed value from response json path {0}")
    public <T> T extractJsonPath(String fieldPath, Class<T> type) {
        return JsonPath.parse(getApiRequestEntity().getResponsePayload()).read(fieldPath, type);
    }

    @Step("Extract int from response json path {0}")
    public int extractJsonPathAsInt(String fieldPath) {
        return extractJsonPath(fieldPath, Integer.class);
    }

    @Step("Extract boolean from response json path {0}")
    public boolean extractJsonPathAsBoolean(String fieldPath) {
        return extractJsonPath(fieldPath, Boolean.class);
    }

    /**
     * 将响应体反序列化为 POJO（类型安全校验），例如 {@code Order o = api.asPojo(Order.class);}
     *
     * @param type 目标 POJO 类型
     * @param <T>  目标类型
     * @return 反序列化后的对象
     */
    public <T> T asPojo(Class<T> type) {
        try {
            return OBJECT_MAPPER.readValue(getApiRequestEntity().getResponsePayload(), type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize response to " + type.getName(), e);
        }
    }

    private Object readJsonPath(String fieldPath) {
        String body = getApiRequestEntity().getResponsePayload();
        if (body == null || body.trim().isEmpty()) {
            throw new IllegalStateException("Response body is null or empty, cannot extract from: " + fieldPath);
        }
        try {
            return JsonPath.parse(body).read(fieldPath);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to extract JSON path '" + fieldPath + "': " + e.getMessage(), e);
        }
    }

    private Object coerceToExpected(Object actual, Object expected) {
        if (expected == null) {
            return actual;
        }
        if (expected instanceof String) {
            return actual == null ? null : String.valueOf(actual);
        }
        if (expected instanceof Integer) {
            return actual instanceof Number ? ((Number) actual).intValue()
                    : (actual == null ? null : Integer.parseInt(String.valueOf(actual)));
        }
        if (expected instanceof Boolean) {
            return actual instanceof Boolean ? actual : Boolean.parseBoolean(String.valueOf(actual));
        }
        if (expected instanceof Long) {
            return actual instanceof Number ? ((Number) actual).longValue()
                    : Long.parseLong(String.valueOf(actual));
        }
        if (expected instanceof Double) {
            return actual instanceof Number ? ((Number) actual).doubleValue()
                    : Double.parseDouble(String.valueOf(actual));
        }
        return actual;
    }

    /**
     * 释放本场景的 API 请求上下文与（自建的）Playwright 实例。
     * 直接处置本步骤持有的作用域实例，因此即使在另一线程（如并行钩子）调用也安全，
     * 不会误清其它线程的作用域。建议在 {@code @After}/@AfterScenario 调用
     * （等价于 {@code PlaywrightApiTestServices.cleanup()}）。
     */
    public void close() {
        if (scope != null) {
            scope.dispose();
            ApiContextScope.clearIfCurrent(scope);
        }
    }

    // ============ Serenity 报告附加数据 ============

    private void recordToSerenity(PlaywrightApiHttpMethod method) {
        if (!recordReportDataEnabled) {
            return;
        }
        try {
            ApiRequestEntity entity = getApiRequestEntity();
            // 请求信息：敏感头掩码，避免凭据落入报告
            String requestInfo = method + " " + entity.getEndpoint() + "\n"
                    + PlaywrightApiClient.maskSensitiveForReport(entity);
            Serenity.recordReportData()
                    .withTitle("API Request [" + method + "]")
                    .andContents(requestInfo);

            // 响应体：先对敏感字段掩码，再截断超长内容，避免 token 等凭据落入报告，也防报告膨胀
            String body = entity.getResponsePayload();
            String maskedBody = body != null ? PlaywrightApiClient.maskSensitiveBody(body) : "";
            String safeBody = maskedBody.length() > MAX_REPORT_BODY
                    ? maskedBody.substring(0, MAX_REPORT_BODY) + "...[truncated]"
                    : maskedBody;
            Serenity.recordReportData()
                    .withTitle("API Response [" + method + "] -> " + entity.getResponseCode()
                            + " (" + entity.getResponseTimeMs() + " ms)")
                    .andContents(safeBody);
        } catch (Throwable t) {
            LOGGER.debug("Failed to record API data into Serenity report (non-fatal): {}", t.getMessage());
        }
    }
}
