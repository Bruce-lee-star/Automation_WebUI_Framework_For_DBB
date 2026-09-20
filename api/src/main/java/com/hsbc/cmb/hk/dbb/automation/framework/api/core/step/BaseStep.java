package com.hsbc.cmb.hk.dbb.automation.framework.api.core.step;

import com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest.RestJobProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.endpoint.EndpointConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.endpoint.EndpointProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.EntityBuilder;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.schema.JsonSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * BaseStep - Core entry class for API operations (Simplified Edition)
 * Provides semantic, intuitive API request and response operation interfaces based on Entity architecture
 * <p>
 * Features:
 * - Supports Rest Assured-style JSON paths (e.g., "data.id", "items[0].name")
 * - Built-in Hamcrest assertions with detailed logging
 * - Entity-based request/response handling
 * - Endpoint-based configuration support for multiple HTTP methods
 */
public class BaseStep extends RestJobProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaseStep.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    // 统一的API实体
    private final Entity apiEntity;

    /**
     * Package-private constructor - can only be called by TestServices
     * Use TestServices.initialize().baseStep() to create BaseStep instances
     *
     * @param entityName entity name to load configuration for, or null for dynamic configuration
     */
    BaseStep(String entityName) {
        super();
        if(entityName != null && !entityName.trim().isEmpty()){
            this.apiEntity = EntityBuilder.build(entityName);
        }else{
            this.apiEntity = EntityBuilder.buildNull();
        }
        this.setEntity(this.apiEntity);
    }

    /**
     * Package-private constructor with environment - can only be called by TestServices
     * Use TestServices.initialize().baseStep() to create BaseStep instances
     *
     * @param entityName entity name to load configuration for
     * @param env environment name (e.g., "dev", "test", "prod")
     */
    BaseStep(String entityName, String env) {
        super();
        if(entityName != null && !entityName.trim().isEmpty()){
            this.apiEntity = EntityBuilder.build(entityName, env);
        }else{
            this.apiEntity = EntityBuilder.buildNull();
        }
        this.setEntity(this.apiEntity);
    }



    /**
     * Verify response status code
     */
    public void verifyResponseStatusCode(int expectedStatusCode) {
        int actualStatusCode = apiEntity.getResponseCode();
        try {
            assertThat("Response status code mismatch", actualStatusCode, equalTo(expectedStatusCode));
            LOGGER.info("Status code verification passed: {} matches expected value {}", actualStatusCode, expectedStatusCode);
        } catch (AssertionError e) {
            LOGGER.error("Status code verification failed: {} does not match expected value {}", actualStatusCode, expectedStatusCode);
            throw e;
        }
    }


    /**
     * Verify JSON path field value (P-5：统一使用 Jayway JsonPath 引擎，与 {@link #verifyJsonArrayLength} 一致，
     * 消除自写 Jackson 解析器与 Jayway 两套 JSONPath 引擎并存的维护风险)。
     */
    public void verifyResponseJsonPath(String fieldPath, Object expectedValue) {
        String responseBody = apiEntity.getResponsePayload();

        if (responseBody == null || responseBody.trim().isEmpty()) {
            throw new AssertionError("Response body is null or empty");
        }

        try {
            JsonNode fieldNode = readJsonPathAsNode(responseBody, fieldPath);
            if (fieldNode == null || fieldNode.isMissingNode()) {
                throw new AssertionError("JSON path not found: " + fieldPath);
            }

            // ✅ Convert Jackson JsonNode to Java object (match expected type)
            Object actualValue = convertJsonNodeToType(fieldNode, expectedValue);

            assertThat("JSON field value mismatch for path: " + fieldPath, actualValue, equalTo(expectedValue));
            LOGGER.info("JSON field verification passed: field '{}' has value '{}'", fieldPath, actualValue);

        } catch (Exception e) {
            throw new AssertionError(String.format(
                    "JSON field verification failed: error parsing field '%s' - %s. Response body: %s",
                    fieldPath, e.getMessage(), responseBody
            ));
        }
    }


    /**
     * Verify JSON array length using Hamcrest matcher
     */
    public void verifyJsonArrayLength(String arrayPath, int expectedLength) {
        String responseBody = apiEntity.getResponsePayload();
        assertThat("Response body is empty", responseBody, notNullValue());
        
        try {
            DocumentContext documentContext = JsonPath.using(JAYWAY_CONFIG).parse(responseBody);
            int actualLength = documentContext.read(arrayPath + ".size()");
            
            // Using Hamcrest comparison matcher
            assertThat("Array length mismatch", actualLength, equalTo(expectedLength));
            LOGGER.info("JSON array length verification passed: '{}' has length {}", arrayPath, actualLength);
        } catch (Exception e) {
            throw new AssertionError("Array length verification failed: " + e.getMessage());
        }
    }


    /**
     * P-7：校验<b>整份响应体</b>符合指定 JSON Schema（响应契约守护）。
     *
     * <p>schema 为 classpath 资源路径（如 {@code schemas/demo-user.json}），支持 JSON Schema
     * draft-07 / 2019-09 / 2020-12。schema 资源缺失或不可解析属<b>配置错误</b>，会失败快
     * （{@link IllegalStateException}），不会静默通过。
     *
     * @param schemaResource schema 的 classpath 资源路径
     */
    public void verifyResponseMatchesSchema(String schemaResource) {
        JsonSchemaValidator.assertMatches(apiEntity.getResponsePayload(), schemaResource);
        LOGGER.info("Response schema verification passed: {}", schemaResource);
    }

    /**
     * P-7：校验响应体中「JSONPath 定位到的<b>子文档</b>」符合指定 JSON Schema。
     *
     * <p>典型场景：对 {@code $.data} 校验子对象契约、对 {@code $.items[0]} 校验数组元素契约。
     * 路径不存在时失败并指明路径；schema 资源缺失时失败快（配置错误）。
     *
     * @param jsonPath       子文档的 JSONPath（支持 {@code $.data.items[0]} 与 {@code data.items[0]}）
     * @param schemaResource schema 的 classpath 资源路径
     */
    public void verifyResponseJsonPathMatchesSchema(String jsonPath, String schemaResource) {
        String responseBody = apiEntity.getResponsePayload();
        if (responseBody == null || responseBody.trim().isEmpty()) {
            throw new AssertionError("Response body is null or empty - cannot validate path '"
                    + jsonPath + "' against schema: " + schemaResource);
        }
        JsonNode subNode = readJsonPathAsNode(responseBody, jsonPath);
        if (subNode == null || subNode.isMissingNode()) {
            throw new AssertionError("JSON path not found: " + jsonPath);
        }
        JsonSchemaValidator.assertNodeMatches(subNode, schemaResource);
        LOGGER.info("Response sub-document schema verification passed: path '{}' vs schema '{}'",
                jsonPath, schemaResource);
    }

    /**
     * Verify response contains specified header using Hamcrest composite matcher
     */
    public void verifyResponseHeader(String headerName, String expectedValue) {
        Map<String, String> headers = apiEntity.getResponseHeaders();
        Object actualValue = headers.get(headerName);
        
        // Using Hamcrest composite matcher
        assertThat("Response header does not exist", headers, hasKey(headerName));
        assertThat("Response header value mismatch", actualValue, equalTo(expectedValue));
        LOGGER.info("Header verification passed: {} = {}", headerName, actualValue);
    }

    /**
     * Verify response body contains specified content (substring)
     */
    public void verifyResponseBodyContains(String expectedContent) {
        String responseBody = apiEntity.getResponsePayload();

        if (responseBody == null || responseBody.trim().isEmpty()) {
            String errorMsg = "Response body is null or empty - cannot verify content presence";
            LOGGER.error(errorMsg);
            throw new AssertionError(errorMsg);
        }

        try {
            assertThat("Response body does not contain expected content: " + expectedContent,
                    responseBody, containsString(expectedContent));
            LOGGER.info("Response body content verification passed: contains '{}'", expectedContent);
        } catch (AssertionError e) {
            // 安全：仅记录响应体前 200 字符，避免敏感数据泄露到日志
            String truncated = responseBody.length() > 200 
                    ? responseBody.substring(0, 200) + "...[truncated]" : responseBody;
            LOGGER.error("Response body content verification failed: does not contain '{}'. Response (truncated): {}",
                    expectedContent, truncated);
            throw e;
        }
    }

    // ✅ P-5 Helper: 统一 JSONPath 引擎（Jayway）+ Jackson 数据结构（JacksonJsonProvider/JacksonMappingProvider），
    //    供 verifyResponseJsonPath / verifyJsonArrayLength 共用，避免自写解析器与 Jayway 两套并存。
    private static final Configuration JAYWAY_CONFIG = Configuration.builder()
            .jsonProvider(new JacksonJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .build();

    /**
     * 使用统一的 Jayway JsonPath 引擎按路径读取节点（支持 {@code $.data.id}、{@code data.items[0].name} 等）。
     * 路径不存在或非法时返回 {@code null}，由调用方抛出语义化断言失败。
     */
    private static JsonNode readJsonPathAsNode(String json, String path) {
        try {
            return JsonPath.using(JAYWAY_CONFIG).parse(json).read(path, JsonNode.class);
        } catch (InvalidPathException e) {
            // PathNotFoundException 继承自 InvalidPathException，二者一并覆盖
            return null;
        }
    }

    // ✅ Helper: Convert Jackson JsonNode to expected Java type
    private Object convertJsonNodeToType(JsonNode node, Object expectedValue) {
        if (expectedValue == null) {
            return null;
        }

        if (expectedValue instanceof String) {
            return node.asText();
        } else  {if (expectedValue instanceof Integer) {
            return node.asInt();
        } else  {if (expectedValue instanceof Boolean) {
            return node.asBoolean();
        } else  {if (expectedValue instanceof Long) {
            return node.asLong();
        } else  {if (expectedValue instanceof Double) {
            return node.asDouble();
        } else {
            // For complex objects (e.g., Maps), convert to Java object
            return OBJECT_MAPPER.convertValue(node, expectedValue.getClass());
        }} } } } 
    }

    /**
     * Load and apply endpoint configuration from config file
     * This method loads all configuration (path, headers, params, payload) for the specified endpoint and method
     *
     * @param endpointName endpoint name (e.g., "user", "pet", "order")
     * @param method HTTP method (e.g., "GET", "POST", "PUT", "DELETE")
     * @return true if configuration loaded successfully, false otherwise
     */
    public boolean loadEndpointConfig(String endpointName, String method) {
        EndpointConfig endpointConfig = EndpointProvider.getEndpoint(endpointName, method);

        if (endpointConfig == null) {
            LOGGER.warn("Endpoint configuration not found: {} {}", method, endpointName);
            return false;
        }

        LOGGER.info("Applying endpoint configuration: {} {}", method, endpointName);

        // Apply path
        if (endpointConfig.getPath() != null) {
            this.setEndpoint(endpointConfig.getPath());
        }

        // Apply headers
        if (endpointConfig.getHeaders() != null && !endpointConfig.getHeaders().isEmpty()) {
            for (Map.Entry<String, Object> entry : endpointConfig.getHeaders().entrySet()) {
                this.addRequestHeader(entry.getKey(), entry.getValue());
            }
        }

        // Apply query parameters
        if (endpointConfig.getQueryParams() != null && !endpointConfig.getQueryParams().isEmpty()) {
            for (Map.Entry<String, Object> entry : endpointConfig.getQueryParams().entrySet()) {
                this.addQueryParam(entry.getKey(), entry.getValue());
            }
        }

        // Apply path parameters
        if (endpointConfig.getPathParams() != null && !endpointConfig.getPathParams().isEmpty()) {
            for (Map.Entry<String, Object> entry : endpointConfig.getPathParams().entrySet()) {
                this.addPathParam(entry.getKey(), entry.getValue());
            }
        }

        // Apply form parameters
        if (endpointConfig.getFormParams() != null && !endpointConfig.getFormParams().isEmpty()) {
            for (Map.Entry<String, Object> entry : endpointConfig.getFormParams().entrySet()) {
                this.addFormParam(entry.getKey(), entry.getValue());
            }
        }

        // Apply cookies
        if (endpointConfig.getCookies() != null && !endpointConfig.getCookies().isEmpty()) {
            for (Map.Entry<String, Object> entry : endpointConfig.getCookies().entrySet()) {
                this.addCookie(entry.getKey(), entry.getValue());
            }
        }

        // Load payload file if specified
        if (endpointConfig.getPayloadFile() != null && !endpointConfig.getPayloadFile().isEmpty()) {
            this.loadPayload(endpointConfig.getPayloadFile());
        }

        LOGGER.info("Endpoint configuration applied successfully");
        return true;
    }

    /**
     * Check if endpoint exists in configuration
     *
     * @param endpointName endpoint name
     * @param method HTTP method
     * @return true if endpoint exists, false otherwise
     */
    public boolean hasEndpointConfig(String endpointName, String method) {
        return EndpointProvider.hasEndpoint(endpointName, method);
    }

}