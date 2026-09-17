package com.hsbc.cmb.hk.dbb.automation.framework.api.core.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * P-7：JSON Schema 响应契约校验器 —— 把「后端接口契约」变成可自动发现的断言。
 *
 * <p><b>要解决的问题</b>：此前框架只能逐字段断言（{@code verifyResponseJsonPath} 等），
 * 后端一旦新增必填字段、改类型、收紧枚举，用例不会失败——契约漂移只能在联调时被人肉发现。
 * 引入 JSON Schema 校验后，响应结构与 schema 的任何偏离都会在用例内立即失败。
 *
 * <p><b>能力</b>：符合 JSON Schema draft-07 / 2019-09 / 2020-12（默认 2020-12，schema 内声明
 * {@code $schema} 时以声明为准）；支持整份响应体校验，也支持对 JSONPath 定位到的<b>子文档</b>校验
 * （如对数组元素逐项校验 item 契约）。
 *
 * <p><b>失败语义</b>：
 * <ul>
 *   <li>响应体不符合 schema → {@link AssertionError}（契约违约，用例失败）；</li>
 *   <li>响应体为空 / 非法 JSON → {@link AssertionError}；</li>
 *   <li>schema 资源缺失或不可解析 → {@link IllegalStateException}（<b>配置错误必须失败快</b>，
 *       绝不静默通过——否则「忘了放 schema 文件」会变成永真断言）；</li>
 * </ul>
 *
 * <p><b>安全</b>：失败信息只包含违规<b>位置与规则</b>，<b>不回显响应体</b>——响应可能含凭据/个人数据，
 * 回显会绕过出口脱敏（见 doc05 P-1 的同类教训）。
 *
 * <p><b>性能</b>：schema 按资源路径缓存已编译实例（{@link ConcurrentHashMap}），重复断言不重复解析。
 */
public final class JsonSchemaValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonSchemaValidator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认按 draft 2020-12 解释；schema 内声明 {@code $schema} 时以声明为准。 */
    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /** 已编译 schema 缓存（classpath 资源路径 → 编译结果）。 */
    private static final Map<String, JsonSchema> SCHEMA_CACHE = new ConcurrentHashMap<>();

    /** 失败报告最多列出的违规条数（避免大响应用例输出巨量噪声）。 */
    private static final int MAX_REPORTED_VIOLATIONS = 20;

    private JsonSchemaValidator() {
    }

    /**
     * 校验 JSON 文本是否符合 classpath 上的 JSON Schema。
     *
     * @param jsonBody       响应体 JSON 文本
     * @param schemaResource schema 的 classpath 资源路径（如 {@code schemas/route-demo-user.json}）
     * @throws AssertionError        响应体为空/非法 JSON/不符合 schema
     * @throws IllegalStateException schema 资源缺失或不可解析（配置错误）
     */
    public static void assertMatches(String jsonBody, String schemaResource) {
        if (schemaResource == null || schemaResource.isBlank()) {
            throw new IllegalStateException("JSON schema resource path must not be blank");
        }
        if (jsonBody == null || jsonBody.isBlank()) {
            throw new AssertionError(
                    "Response body is null or empty - cannot validate against schema: " + schemaResource);
        }
        JsonNode body;
        try {
            body = MAPPER.readTree(jsonBody);
        } catch (IOException e) {
            throw new AssertionError(
                    "Response body is not valid JSON (schema=" + schemaResource + "): " + e.getMessage());
        }
        assertNodeMatches(body, schemaResource);
    }

    /**
     * 校验任意 JSON 节点（供「对 JSONPath 子文档校验」复用）。
     *
     * @param node           待校验节点
     * @param schemaResource schema 的 classpath 资源路径
     * @throws AssertionError        节点不符合 schema
     * @throws IllegalStateException schema 资源缺失或不可解析
     */
    public static void assertNodeMatches(JsonNode node, String schemaResource) {
        JsonSchema schema = schemaFor(schemaResource);
        Set<ValidationMessage> violations;
        try {
            violations = schema.validate(node);
        } catch (RuntimeException e) {
            throw new AssertionError(
                    "JSON schema validation error (schema=" + schemaResource + "): " + e.getMessage());
        }
        if (!violations.isEmpty()) {
            throw new AssertionError(buildFailureMessage(schemaResource, violations));
        }
        LOGGER.info("Response schema verification passed: {}", schemaResource);
    }

    /**
     * 预加载 schema（可选）：把「schema 文件缺失/写错」的配置错误提前到用例准备阶段暴露，
     * 而不是等到首次断言时才失败。
     */
    public static void preload(String schemaResource) {
        schemaFor(schemaResource);
    }

    private static JsonSchema schemaFor(String schemaResource) {
        if (schemaResource == null || schemaResource.isBlank()) {
            throw new IllegalStateException("JSON schema resource path must not be blank");
        }
        return SCHEMA_CACHE.computeIfAbsent(schemaResource, JsonSchemaValidator::compile);
    }

    private static JsonSchema compile(String schemaResource) {
        JsonNode schemaNode = readSchemaResource(schemaResource);
        try {
            return FACTORY.getSchema(schemaNode);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Invalid JSON schema '" + schemaResource + "': " + e.getMessage(), e);
        }
    }

    private static JsonNode readSchemaResource(String schemaResource) {
        String path = schemaResource.startsWith("/") ? schemaResource.substring(1) : schemaResource;
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = JsonSchemaValidator.class.getClassLoader();
        }
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(
                        "JSON schema resource not found on classpath: " + schemaResource
                                + "（请确认文件位于 src/main/resources 或 src/test/resources 下）");
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read JSON schema resource: " + schemaResource, e);
        }
    }

    /**
     * 构造失败信息：仅列「违规位置 + 违反的规则」，<b>不回显响应体</b>（避免绕过出口脱敏）。
     *
     * <p>输出形如 {@code - [required] $: ...}：{@code [messageKey]} 是<b>语言无关</b>的稳定键
     * （如 {@code required}/{@code enum}/{@code type}），断言与排障都不受 JVM 默认语言影响；
     * 库的消息文本随 locale 变化，故不作为契约。
     */
    private static String buildFailureMessage(String schemaResource, Set<ValidationMessage> violations) {
        String details = violations.stream()
                .limit(MAX_REPORTED_VIOLATIONS)
                .map(JsonSchemaValidator::describe)
                .collect(Collectors.joining("\n"));
        String omitted = violations.size() > MAX_REPORTED_VIOLATIONS
                ? "\n  ... 其余 " + (violations.size() - MAX_REPORTED_VIOLATIONS) + " 条违规已省略"
                : "";
        return "Response does not match JSON schema '" + schemaResource + "' ("
                + violations.size() + " violation(s)):\n" + details + omitted;
    }

    private static String describe(ValidationMessage message) {
        String path = String.valueOf(message.getInstanceLocation());
        String text = message.getMessage();
        // 多数校验器的 message 已以路径开头（如 "$: ..."），避免重复拼接
        String prefix = text.startsWith(path) ? "" : path + " ";
        return "  - [" + message.getMessageKey() + "] " + prefix + text;
    }
}
