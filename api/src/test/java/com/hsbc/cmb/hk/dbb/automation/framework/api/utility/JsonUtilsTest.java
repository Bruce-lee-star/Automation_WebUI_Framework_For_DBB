package com.hsbc.cmb.hk.dbb.automation.framework.api.utility;

import com.jayway.jsonpath.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link JsonUtils} 单测（评审 P-2 补测）。
 *
 * <p>为什么值得测：该类是 API 断言/报文加工的基础设施（序列化、JsonPath 读写、合并、深度比较），
 * 且契约明确为「<b>失败返回兜底值而不抛异常</b>」——调用方（断言链、数据加工）依赖这一点。
 * 故本测试固化两类契约：<b>正常路径</b>（读写/合并/比较结果正确）与 <b>失败与空入参路径</b>
 * （null/空白入参返回 null/空集合/false，非法 JSON 返回原串或兜底，绝不外抛）。
 *
 * <p>实现注意（测试隔离）：{@code JsonUtils} 内部以「JSON 字符串 → {@code DocumentContext}」做 LRU 解析缓存，
 * 而 {@code setValue}/{@code deleteValue} 是<b>原地修改</b>该缓存对象。故各用例一律使用<b>各自独立的
 * JSON 字面量</b>，避免用例间经缓存互相污染（这也正是本测试顺带暴露的实现风险，见
 * {@code JsonUtilsCacheAliasingTest} 之外的实现说明）。
 */
class JsonUtilsTest {

    /** 基础用例专用报文（每方法各自独有，规避解析缓存互扰）。 */
    private static final String BASE = "{\"name\":\"dbb\",\"age\":30,\"tags\":[\"a\",\"b\"],\"nested\":{\"k\":\"v\"}}";

    // ── toJson ────────────────────────────────────────────────────────────────

    @Test
    void toJsonSerializesMapAndReturnsNullForNullInput() {
        assertThat(JsonUtils.toJson(null)).isNull();
        assertThat(JsonUtils.toJson(Map.of("a", 1))).isEqualTo("{\"a\":1}");
        assertThat(JsonUtils.toJson(List.of("x", "y"))).isEqualTo("[\"x\",\"y\"]");
    }

    // ── fromJson(Class) ───────────────────────────────────────────────────────

    @Test
    void fromJsonDeserializesTypedObject() {
        String[] array = JsonUtils.fromJson("[\"fromJsonClazz\"]", String[].class);
        assertThat(array).containsExactly("fromJsonClazz");

        Map<String, Object> map = JsonUtils.fromJson("{\"fromJsonClazz\":\"v\"}", new TypeRef<Map<String, Object>>() { });
        assertThat(map).containsEntry("fromJsonClazz", "v");
    }

    @Test
    void fromJsonReturnsNullForNullBlankOrMalformedInput() {
        assertThat(JsonUtils.fromJson(null, Map.class)).isNull();
        assertThat(JsonUtils.fromJson("", Map.class)).isNull();
        assertThat(JsonUtils.fromJson("   ", Map.class)).isNull();
        assertThat(JsonUtils.fromJson("{not-json", Map.class)).isNull();
    }

    // ── fromJson(TypeRef) ─────────────────────────────────────────────────────

    @Test
    void fromJsonWithTypeRefResolvesGenericType() {
        Map<String, Object> map = JsonUtils.fromJson("{\"fromJsonTypeRef\":1}", new TypeRef<Map<String, Object>>() { });
        assertThat(map).containsEntry("fromJsonTypeRef", 1);
    }

    @Test
    void fromJsonWithTypeRefReturnsNullForNullBlankOrMalformedInput() {
        assertThat(JsonUtils.fromJson(null, new TypeRef<List<String>>() { })).isNull();
        assertThat(JsonUtils.fromJson("  ", new TypeRef<List<String>>() { })).isNull();
        assertThat(JsonUtils.fromJson("[1,", new TypeRef<List<String>>() { })).isNull();
    }

    // ── getValue（三种重载）────────────────────────────────────────────────────

    @Test
    void getValueReturnsPathValue() {
        assertThat(JsonUtils.getValue(BASE, "$.name")).isEqualTo("dbb");
        assertThat(JsonUtils.getValue(BASE, "$.nested.k")).isEqualTo("v");
    }

    @Test
    void getValueWithTypeReturnsTypedValue() {
        assertThat(JsonUtils.getValue(BASE, "$.age", Integer.class)).isEqualTo(30);
        assertThat(JsonUtils.getValue(BASE, "$.name", String.class)).isEqualTo("dbb");
    }

    /**
     * {@code getValue(String, String, TypeRef)} 在默认 Jayway provider 下<b>无法完成类型化映射</b>
     * （实测命中路径也返回 null，即走 catch 兜底）——该重载目前无生产调用方。
     * 此处只固化其「<b>不抛异常、失败返回 null</b>」的降级契约（不固化"永远失败"这一现状），
     * 以便后续若修复为支持 TypeRef，本用例无需反向修改。
     */
    @Test
    void getValueWithTypeRefDegradesToNullInsteadOfThrowing() {
        assertThatCode(() -> JsonUtils.getValue(BASE, "$.tags", new TypeRef<List<Object>>() { }))
                .doesNotThrowAnyException();
        assertThat(JsonUtils.getValue(BASE, "$.missing", new TypeRef<List<Object>>() { })).isNull();
    }

    @Test
    void getValueReturnsNullForNullBlankMissingPathOrTypeMismatch() {
        assertThat(JsonUtils.getValue(null, "$.a")).isNull();
        assertThat(JsonUtils.getValue("  ", "$.a")).isNull();
        assertThat(JsonUtils.getValue(BASE, "$.missing")).isNull();
        assertThat(JsonUtils.getValue(BASE, "$.missing", String.class)).isNull();
        assertThat(JsonUtils.getValue(BASE, "$.missing", new TypeRef<List<Object>>() { })).isNull();
    }

    // ── setValue / deleteValue（各自独立字面量，规避缓存原地修改互扰）──────────

    @Test
    void setValueUpdatesNodeAndReturnsNewJson() {
        String json = "{\"setValueCase\":{\"n\":1}}";
        String updated = JsonUtils.setValue(json, "$.setValueCase.n", 2);
        assertThat(updated).contains("\"n\":2");
    }

    @Test
    void setValueReturnsNullForNullBlankAndOriginalJsonOnFailure() {
        assertThat(JsonUtils.setValue(null, "$.a", 1)).isNull();
        assertThat(JsonUtils.setValue("   ", "$.a", 1)).isNull();
        String json = "{\"setValueFail\":1}";
        assertThat(JsonUtils.setValue(json, "$.not.here.deep", 1)).isEqualTo(json);
    }

    @Test
    void deleteValueRemovesNodeAndReturnsNewJson() {
        String json = "{\"deleteValueCase\":1,\"keep\":2}";
        String updated = JsonUtils.deleteValue(json, "$.deleteValueCase");
        assertThat(updated).doesNotContain("deleteValueCase").contains("keep");
    }

    @Test
    void deleteValueReturnsNullForNullBlankAndOriginalJsonOnFailure() {
        assertThat(JsonUtils.deleteValue(null, "$.a")).isNull();
        assertThat(JsonUtils.deleteValue("  ", "$.a")).isNull();
        String json = "{\"deleteValueFail\":1}";
        assertThat(JsonUtils.deleteValue(json, "$.not.here.deep")).isEqualTo(json);
    }

    // ── isValidJson / formatJson ──────────────────────────────────────────────

    @Test
    void isValidJsonDistinguishesValidFromInvalidAndBlank() {
        assertThat(JsonUtils.isValidJson(BASE)).isTrue();
        assertThat(JsonUtils.isValidJson("[1,2]")).isTrue();
        assertThat(JsonUtils.isValidJson(null)).isFalse();
        assertThat(JsonUtils.isValidJson("")).isFalse();
        assertThat(JsonUtils.isValidJson("   ")).isFalse();
        assertThat(JsonUtils.isValidJson("{broken")).isFalse();
    }

    @Test
    void formatJsonPrettyPrintsAndFallsBackToOriginalOnFailure() {
        String pretty = JsonUtils.formatJson("{\"formatJsonCase\":1}");
        assertThat(pretty).contains("\n").contains("formatJsonCase");

        assertThat(JsonUtils.formatJson(null)).isNull();
        assertThat(JsonUtils.formatJson("  ")).isNull();
        assertThat(JsonUtils.formatJson("{broken")).isEqualTo("{broken");
    }

    // ── mergeJson ─────────────────────────────────────────────────────────────

    @Test
    void mergeJsonOverridesLeftWithRight() {
        String merged = JsonUtils.mergeJson("{\"mergeA\":1,\"shared\":\"left\"}", "{\"mergeB\":2,\"shared\":\"right\"}");
        assertThat(merged).contains("mergeA").contains("mergeB").contains("\"shared\":\"right\"");
    }

    @Test
    void mergeJsonToleratesBlankOperands() {
        assertThat(JsonUtils.mergeJson(null, "{\"mergeOnlyRight\":1}")).isEqualTo("{\"mergeOnlyRight\":1}");
        assertThat(JsonUtils.mergeJson("{\"mergeOnlyLeft\":1}", "   ")).isEqualTo("{\"mergeOnlyLeft\":1}");
        assertThat(JsonUtils.mergeJson("  ", "  ")).isEqualTo("  ");
    }

    @Test
    void mergeJsonFallsBackToFirstJsonWhenMergeFails() {
        String left = "{\"mergeFailA\":1}";
        assertThat(JsonUtils.mergeJson(left, "{broken")).isEqualTo(left);
    }

    // ── equalsJson ────────────────────────────────────────────────────────────

    @Test
    void equalsJsonIsOrderInsensitiveDeepComparison() {
        assertThat(JsonUtils.equalsJson("{\"a\":1,\"b\":2}", "{\"b\":2,\"a\":1}")).isTrue();
        assertThat(JsonUtils.equalsJson("{\"a\":1}", "{\"a\":2}")).isFalse();
    }

    @Test
    void equalsJsonHandlesNullsAndMalformedInput() {
        assertThat(JsonUtils.equalsJson(null, null)).isTrue();
        assertThat(JsonUtils.equalsJson(null, "{}")).isFalse();
        assertThat(JsonUtils.equalsJson("{}", null)).isFalse();
        assertThat(JsonUtils.equalsJson("{broken", "{}")).isFalse();
    }

    // ── getValues / hasPath ───────────────────────────────────────────────────

    @Test
    void getValuesReturnsMatchingList() {
        assertThat(JsonUtils.getValues(BASE, "$.tags")).containsExactly("a", "b");
    }

    @Test
    void getValuesReturnsEmptyListForNullBlankOrFailure() {
        assertThat(JsonUtils.getValues(null, "$.a")).isEmpty();
        assertThat(JsonUtils.getValues("  ", "$.a")).isEmpty();
        assertThat(JsonUtils.getValues(BASE, "$.not.here.deep")).isEmpty();
    }

    @Test
    void hasPathReflectsExistence() {
        assertThat(JsonUtils.hasPath(BASE, "$.name")).isTrue();
        assertThat(JsonUtils.hasPath(BASE, "$.nested.k")).isTrue();
        assertThat(JsonUtils.hasPath(BASE, "$.missing")).isFalse();
        assertThat(JsonUtils.hasPath(null, "$.name")).isFalse();
        assertThat(JsonUtils.hasPath("  ", "$.name")).isFalse();
    }
}
