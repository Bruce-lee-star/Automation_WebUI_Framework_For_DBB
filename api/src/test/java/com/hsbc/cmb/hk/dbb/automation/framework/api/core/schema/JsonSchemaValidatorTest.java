package com.hsbc.cmb.hk.dbb.automation.framework.api.core.schema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P-7：JSON Schema 响应契约校验的行为契约测试。
 *
 * <p>覆盖：合法通过 / 缺必填 / 枚举违约 / 额外字段 / 非 JSON 体 / 空体 /
 * **schema 资源缺失失败快（不得静默通过）** / **失败信息不回显响应体（防绕过出口脱敏）**。
 */
class JsonSchemaValidatorTest {

    private static final String SCHEMA = "schemas/demo-user.json";

    private static final String VALID_BODY =
            "{\"id\":\"u-1\",\"name\":\"alice\",\"status\":\"ACTIVE\",\"age\":30}";

    @Test
    void validBody_passes() {
        assertThatCode(() -> JsonSchemaValidator.assertMatches(VALID_BODY, SCHEMA))
                .doesNotThrowAnyException();
    }

    /** 可选字段缺省不违约（只校验 required）。 */
    @Test
    void optionalFieldAbsent_passes() {
        assertThatCode(() -> JsonSchemaValidator.assertMatches(
                "{\"id\":\"u-1\",\"name\":\"alice\",\"status\":\"LOCKED\"}", SCHEMA))
                .doesNotThrowAnyException();
    }

    @Test
    void missingRequiredField_failsWithPointer() {
        assertThatThrownBy(() -> JsonSchemaValidator.assertMatches(
                "{\"name\":\"alice\",\"status\":\"ACTIVE\"}", SCHEMA))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("does not match JSON schema")
                // 断言语言无关的 messageKey（库的消息文本随 JVM 默认 locale 变化，不作为契约）
                .hasMessageContaining("[required]");
    }

    @Test
    void enumViolation_fails() {
        assertThatThrownBy(() -> JsonSchemaValidator.assertMatches(
                "{\"id\":\"u-1\",\"name\":\"alice\",\"status\":\"UNKNOWN\"}", SCHEMA))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("[enum]");
    }

    @Test
    void typeViolation_fails() {
        assertThatThrownBy(() -> JsonSchemaValidator.assertMatches(
                "{\"id\":\"u-1\",\"name\":\"alice\",\"status\":\"ACTIVE\",\"age\":\"thirty\"}", SCHEMA))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("[type]");
    }

    /** additionalProperties:false → 后端新增未申报字段即违约（契约漂移立刻可见）。 */
    @Test
    void additionalProperty_fails() {
        assertThatThrownBy(() -> JsonSchemaValidator.assertMatches(
                "{\"id\":\"u-1\",\"name\":\"alice\",\"status\":\"ACTIVE\",\"unexpected\":1}", SCHEMA))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("[additionalProperties]");
    }

    @Test
    void blankBody_fails() {
        assertThatThrownBy(() -> JsonSchemaValidator.assertMatches("   ", SCHEMA))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("null or empty");
    }

    @Test
    void malformedJson_fails() {
        assertThatThrownBy(() -> JsonSchemaValidator.assertMatches("{not-json", SCHEMA))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("not valid JSON");
    }

    /**
     * 关键：schema 资源缺失属**配置错误**，必须失败快——若静默通过，「忘了放 schema 文件」
     * 会退化成永真断言（比没有校验更危险）。
     */
    @Test
    void missingSchemaResource_failsFast_withIllegalState() {
        assertThatThrownBy(() -> JsonSchemaValidator.assertMatches(VALID_BODY, "schemas/does-not-exist.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found on classpath");
    }

    @Test
    void blankSchemaResource_failsFast() {
        assertThatThrownBy(() -> JsonSchemaValidator.assertMatches(VALID_BODY, "  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be blank");
    }

    /** 安全：失败信息只给「位置 + 规则」，绝不回显响应体内容（响应可能含凭据/个人数据）。 */
    @Test
    void failureMessage_doesNotEchoResponseBody() {
        String secret = "hunter2-super-secret";
        String body = "{\"id\":\"u-1\",\"name\":\"" + secret + "\",\"status\":\"ACTIVE\",\"extra\":\"x\"}";

        assertThatThrownBy(() -> JsonSchemaValidator.assertMatches(body, SCHEMA))
                .isInstanceOf(AssertionError.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .as("失败信息不得回显响应体（否则绕过出口脱敏）")
                        .doesNotContain(secret));
    }

    /** schema 编译结果按资源路径缓存：重复校验结果稳定（且不重复解析）。 */
    @Test
    void schemaIsCached_acrossCalls() {
        JsonSchemaValidator.preload(SCHEMA);
        assertThatCode(() -> JsonSchemaValidator.assertMatches(VALID_BODY, SCHEMA))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> JsonSchemaValidator.assertMatches("{}", SCHEMA))
                .isInstanceOf(AssertionError.class);
    }
}
