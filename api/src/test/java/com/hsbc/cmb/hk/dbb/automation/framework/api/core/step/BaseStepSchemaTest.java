package com.hsbc.cmb.hk.dbb.automation.framework.api.core.step;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P-7：{@code BaseStep} 的响应 schema 校验入口（整份响应体 / JSONPath 子文档）。
 */
class BaseStepSchemaTest {

    private static final String USER_SCHEMA = "schemas/demo-user.json";
    private static final String ITEM_SCHEMA = "schemas/order-item.json";

    private BaseStep stepWith(String json) {
        BaseStep step = new BaseStep(null);
        step.getEntity().setResponsePayload(json);
        return step;
    }

    @Test
    void verifyResponseMatchesSchema_passesForValidBody() {
        BaseStep step = stepWith("{\"id\":\"u-1\",\"name\":\"alice\",\"status\":\"ACTIVE\"}");

        assertThatCode(() -> step.verifyResponseMatchesSchema(USER_SCHEMA)).doesNotThrowAnyException();
    }

    @Test
    void verifyResponseMatchesSchema_failsOnContractViolation() {
        BaseStep step = stepWith("{\"id\":\"u-1\",\"name\":\"alice\"}");

        assertThatThrownBy(() -> step.verifyResponseMatchesSchema(USER_SCHEMA))
                .isInstanceOf(AssertionError.class);
    }

    /** 子文档校验：对 {@code $.items[1]} 校验元素契约（数组元素逐项校验的典型用法）。 */
    @Test
    void verifyResponseJsonPathMatchesSchema_validatesSubDocument() {
        BaseStep step = stepWith("{\"items\":[{\"sku\":\"A\",\"qty\":1},{\"sku\":\"B\",\"qty\":2}]}");

        assertThatCode(() -> step.verifyResponseJsonPathMatchesSchema("$.items[1]", ITEM_SCHEMA))
                .doesNotThrowAnyException();
    }

    @Test
    void verifyResponseJsonPathMatchesSchema_failsWhenItemViolates() {
        BaseStep step = stepWith("{\"items\":[{\"sku\":\"A\",\"qty\":0}]}");

        assertThatThrownBy(() -> step.verifyResponseJsonPathMatchesSchema("$.items[0]", ITEM_SCHEMA))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("order-item.json");
    }

    @Test
    void verifyResponseJsonPathMatchesSchema_missingPath_fails() {
        BaseStep step = stepWith("{\"items\":[]}");

        assertThatThrownBy(() -> step.verifyResponseJsonPathMatchesSchema("$.items[0]", ITEM_SCHEMA))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("JSON path not found");
    }

    @Test
    void verifyResponseJsonPathMatchesSchema_emptyBody_fails() {
        BaseStep step = stepWith("");

        assertThatThrownBy(() -> step.verifyResponseJsonPathMatchesSchema("$.items[0]", ITEM_SCHEMA))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("null or empty");
    }
}
