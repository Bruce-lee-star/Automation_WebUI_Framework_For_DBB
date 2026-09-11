package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.route.handler.ModifyHandler;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2-7 契约/对照基准测试：固化 {@link ModifyHandler} 公开 JSON 操作 API 的<b>当前行为</b>，
 * 作为后续用 Jayway JsonPath 替代自研路径解析/写逻辑的回归网（保留旧行为作对照）。
 *
 * <p>覆盖切换 Jayway 时最易漂移的语义点：
 * <ul>
 *   <li>类型保持（原 int→int、bool→bool、null→推断、decimal→decimal）</li>
 *   <li>数组索引与数组追加</li>
 *   <li>中间节点自动创建</li>
 *   <li>通配路径解析分段</li>
 * </ul>
 *
 * <p>已知限制（已在用例中固化当前真实行为，T2-7 改写后可解除）：
 * <ul>
 *   <li>{@code replaceByJsonPath} 替换 JSON 对象值的<b>字符串化限制已于 T2-7 阶段2.1 解除</b>——
 *       {@code modifyFieldOnTree} 改为经 Jayway 带类型读取（JsonNode.class）+ Jackson 配置，
 *       原字段类型信息被保留，{@code convertToMatchingType} 的 ObjectNode 分支正确命中。</li>
 *   <li>{@code buildJsonFromFieldMap} 对中间段数组索引键（如 {@code items[0].id}）<b>不支持</b>——
 *       中间段 {@code [0]} 被当作嵌套对象路径，结果 {@code items} 为对象而非数组（非核心契约，保留）。</li>
 * </ul>
 *
 * <p>注：{@code convertToMatchingType} / {@code evalCondition} 为包级私有，本测试通过 public API 间接守护其语义。
 */
public class ModifyHandlerContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parsed(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    // ───────────────────────── replaceByJsonPath：类型保持 ─────────────────────────

    @Test
    public void replace_nestedInt_keepsIntType() throws Exception {
        String out = ModifyHandler.replaceByJsonPath("{\"user\":{\"age\":30}}", "$.user.age", "31");
        JsonNode root = parsed(out);
        assertEquals(31, root.path("user").path("age").asInt());
        assertTrue(root.path("user").path("age").isInt());
    }

    @Test
    public void replace_boolean_keepsBooleanType() throws Exception {
        String out = ModifyHandler.replaceByJsonPath("{\"flag\":true}", "$.flag", "false");
        JsonNode root = parsed(out);
        assertFalse(root.path("flag").asBoolean());
        assertTrue(root.path("flag").isBoolean());
    }

    @Test
    public void replace_nullExisting_infersType() throws Exception {
        // 数字
        JsonNode n = parsed(ModifyHandler.replaceByJsonPath("{\"x\":null}", "$.x", "5"));
        assertTrue(n.path("x").isInt());
        assertEquals(5, n.path("x").asInt());
        // 布尔
        JsonNode b = parsed(ModifyHandler.replaceByJsonPath("{\"x\":null}", "$.x", "true"));
        assertTrue(b.path("x").isBoolean());
        // 显式 null
        JsonNode nu = parsed(ModifyHandler.replaceByJsonPath("{\"x\":null}", "$.x", "null"));
        assertTrue(nu.path("x").isNull());
    }

    @Test
    public void replace_decimal_keepsDecimalType() throws Exception {
        String out = ModifyHandler.replaceByJsonPath("{\"price\":1.5}", "$.price", "2.5");
        JsonNode root = parsed(out);
        assertEquals(2.5, root.path("price").asDouble(), 0.0001);
        assertTrue(root.path("price").isDouble());
    }

    @Test
    public void replace_arrayIndex_targetsCorrectElement() throws Exception {
        String body = "{\"users\":[{\"name\":\"a\"},{\"name\":\"b\"}]}";
        String out = ModifyHandler.replaceByJsonPath(body, "$.users[1].name", "B");
        JsonNode root = parsed(out);
        assertEquals("B", root.path("users").get(1).path("name").asText());
        assertEquals("a", root.path("users").get(0).path("name").asText());
    }

    /**
     * T2-7 阶段2.1 已解除「对象值字符串化」限制：modifyFieldOnTree 经
     * Jayway 带类型读取（JsonNode.class）+ Jackson 配置保留原字段类型信息，
     * 新 JSON 对象值被正确写入为嵌套对象节点（原字符串化用例的反向验证）。
     * 注：replace 语义为整体替换（convertToMatchingType 对 ObjectNode 返回新对象），
     * 故原字段 a 被新对象 {"b":2} 取代，本用例仅守护「对象化」这一关键修复点。
     */
    @Test
    public void replace_jsonObjectValue_isNestedObject_afterFixed() throws Exception {
        String body = "{\"obj\":{\"a\":1}}";
        String out = ModifyHandler.replaceByJsonPath(body, "$.obj", "{\"b\":2}");
        JsonNode root = parsed(out);
        assertTrue(root.path("obj").isObject());
        assertEquals(2, root.path("obj").path("b").asInt());
    }

    @Test
    public void replace_invalidJsonBody_returnsOriginal() {
        String body = "not a json";
        String out = ModifyHandler.replaceByJsonPath(body, "$.x", "1");
        assertEquals(body, out);
    }

    // ───────────────────────── addFieldByJsonPath ─────────────────────────

    @Test
    public void add_newField_createsIntermediateNodes() throws Exception {
        String out = ModifyHandler.addFieldByJsonPath("{}", "$.data.name", "Alice");
        JsonNode root = parsed(out);
        assertEquals("Alice", root.path("data").path("name").asText());
    }

    @Test
    public void add_toExistingArray_appendsParsedObject() throws Exception {
        String body = "{\"data\":{\"items\":[{\"id\":1}]}}";
        String out = ModifyHandler.addFieldByJsonPath(body, "$.data.items", "{\"id\":2}");
        JsonNode root = parsed(out);
        JsonNode items = root.path("data").path("items");
        assertEquals(2, items.size());
        assertEquals(2, items.get(1).path("id").asInt());
    }

    @Test
    public void add_toExistingArray_appendsScalarWhenNotJson() throws Exception {
        String body = "{\"data\":{\"items\":[{\"id\":1}]}}";
        String out = ModifyHandler.addFieldByJsonPath(body, "$.data.items", "hello");
        JsonNode root = parsed(out);
        JsonNode items = root.path("data").path("items");
        assertEquals(2, items.size());
        assertEquals("hello", items.get(1).asText());
    }

    @Test
    public void add_toExistingNonArrayField_overwrites() throws Exception {
        String body = "{\"data\":{\"name\":\"old\"}}";
        String out = ModifyHandler.addFieldByJsonPath(body, "$.data.name", "\"new\"");
        JsonNode root = parsed(out);
        assertEquals("new", root.path("data").path("name").asText());
    }

    // ───────────────────────── removeFieldByJsonPath ─────────────────────────

    @Test
    public void remove_field_deletesIt() throws Exception {
        String out = ModifyHandler.removeFieldByJsonPath("{\"a\":1,\"b\":2}", "$.b");
        JsonNode root = parsed(out);
        assertFalse(root.has("b"));
        assertTrue(root.has("a"));
    }

    @Test
    public void remove_nestedField_keepsParent() throws Exception {
        String out = ModifyHandler.removeFieldByJsonPath("{\"a\":{\"b\":1,\"c\":2}}", "$.a.b");
        JsonNode root = parsed(out);
        assertFalse(root.path("a").has("b"));
        assertTrue(root.path("a").has("c"));
    }

    @Test
    public void remove_nonexistentPath_isNoOp() throws Exception {
        String body = "{\"a\":1}";
        String out = ModifyHandler.removeFieldByJsonPath(body, "$.z");
        JsonNode root = parsed(out);
        assertEquals(1, root.path("a").asInt());
    }

    // ───────────────────────── parseWildcardPath（分段对照） ─────────────────────────

    @Test
    public void parseWildcardPath_segments_count() {
        assertEquals(2, ModifyHandler.parseWildcardPath("users[*].name").size());
        assertEquals(2, ModifyHandler.parseWildcardPath("items[0].id").size());
        assertEquals(1, ModifyHandler.parseWildcardPath("field").size());
        assertEquals(0, ModifyHandler.parseWildcardPath("$").size());
        assertEquals(0, ModifyHandler.parseWildcardPath("$.").size());
    }

    // ───────────────────────── replaceBatchByWildcard（通配批量） ─────────────────────────

    @Test
    public void replaceBatch_arrayWildcard_setsAllElements() throws Exception {
        Map<String, Object> reps = new LinkedHashMap<>();
        reps.put("$.users[*].name", "x");
        String out = ModifyHandler.replaceBatchByWildcard(
                "{\"users\":[{\"name\":\"a\"},{\"name\":\"b\"}]}", reps);
        JsonNode root = parsed(out);
        JsonNode users = root.path("users");
        assertEquals("x", users.get(0).path("name").asText());
        assertEquals("x", users.get(1).path("name").asText());
    }

    @Test
    public void replaceBatch_nestedWildcard_setsAllLeaves() throws Exception {
        Map<String, Object> reps = new LinkedHashMap<>();
        reps.put("$.users[*].orders[*].price", 9);
        String out = ModifyHandler.replaceBatchByWildcard(
                "{\"users\":[{\"orders\":[{\"price\":1},{\"price\":2}]}]}", reps);
        JsonNode root = parsed(out);
        JsonNode orders = root.path("users").get(0).path("orders");
        assertEquals(9, orders.get(0).path("price").asInt());
        assertEquals(9, orders.get(1).path("price").asInt());
    }

    @Test
    public void replaceBatch_coercesStringValueToOriginalNumberType() throws Exception {
        Map<String, Object> reps = new LinkedHashMap<>();
        reps.put("$.users[*].price", "9");
        String out = ModifyHandler.replaceBatchByWildcard(
                "{\"users\":[{\"price\":1},{\"price\":2}]}", reps);
        JsonNode root = parsed(out);
        JsonNode users = root.path("users");
        assertEquals(9, users.get(0).path("price").asInt());
        assertEquals(9, users.get(1).path("price").asInt());
        assertTrue(users.get(0).path("price").isInt());
    }

    @Test
    public void replaceBatch_exactIndex_onlyThatElement() throws Exception {
        Map<String, Object> reps = new LinkedHashMap<>();
        reps.put("$.users[0].name", "x");
        String out = ModifyHandler.replaceBatchByWildcard(
                "{\"users\":[{\"name\":\"a\"},{\"name\":\"b\"}]}", reps);
        JsonNode root = parsed(out);
        JsonNode users = root.path("users");
        assertEquals("x", users.get(0).path("name").asText());
        assertEquals("b", users.get(1).path("name").asText());
    }

    @Test
    public void replaceBatch_noMatch_isNoOp() throws Exception {
        Map<String, Object> reps = new LinkedHashMap<>();
        reps.put("$.nope[*].x", "y");
        String body = "{\"users\":[{\"name\":\"a\"}]}";
        String out = ModifyHandler.replaceBatchByWildcard(body, reps);
        JsonNode root = parsed(out);
        assertEquals("a", root.path("users").get(0).path("name").asText());
    }
}
