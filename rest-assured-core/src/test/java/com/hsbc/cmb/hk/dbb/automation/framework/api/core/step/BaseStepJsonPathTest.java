package com.hsbc.cmb.hk.dbb.automation.framework.api.core.step;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P-5：验证 {@code verifyResponseJsonPath} / {@code verifyJsonArrayLength} 统一使用 Jayway JSONPath 引擎后，
 * 嵌套字段、数组下标、类型转换与失败语义均保持一致（替代原自写 Jackson 解析器）。
 */
class BaseStepJsonPathTest {

    private BaseStep stepWith(String json) {
        BaseStep step = new BaseStep(null);
        step.getEntity().setResponsePayload(json);
        return step;
    }

    @Test
    void verifyResponseJsonPath_matchesNestedField() {
        BaseStep step = stepWith("{\"data\":{\"id\":\"123\",\"active\":true}}");

        assertThatCode(() -> step.verifyResponseJsonPath("$.data.id", "123")).doesNotThrowAnyException();
        // 兼容不带 $ 前缀的历史写法
        assertThatCode(() -> step.verifyResponseJsonPath("data.id", "123")).doesNotThrowAnyException();
        assertThatCode(() -> step.verifyResponseJsonPath("$.data.active", true)).doesNotThrowAnyException();
    }

    @Test
    void verifyResponseJsonPath_supportsArrayIndex() {
        BaseStep step = stepWith("{\"items\":[{\"name\":\"a\"},{\"name\":\"b\"}]}");

        assertThatCode(() -> step.verifyResponseJsonPath("$.items[1].name", "b")).doesNotThrowAnyException();
    }

    @Test
    void verifyResponseJsonPath_mismatch_failsWithAssertionError() {
        BaseStep step = stepWith("{\"data\":{\"id\":\"123\"}}");

        assertThatThrownBy(() -> step.verifyResponseJsonPath("$.data.id", "999"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void verifyResponseJsonPath_missingPath_failsWithAssertionError() {
        BaseStep step = stepWith("{\"data\":{\"id\":\"123\"}}");

        assertThatThrownBy(() -> step.verifyResponseJsonPath("$.data.nope", "x"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("JSON path not found");
    }

    @Test
    void verifyJsonArrayLength_viaSameJaywayEngine() {
        BaseStep step = stepWith("{\"items\":[1,2,3]}");

        assertThatCode(() -> step.verifyJsonArrayLength("$.items", 3)).doesNotThrowAnyException();
        assertThatThrownBy(() -> step.verifyJsonArrayLength("$.items", 2))
                .isInstanceOf(AssertionError.class);
    }
}
