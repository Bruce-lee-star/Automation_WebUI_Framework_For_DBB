package com.hsbc.cmb.hk.dbb.automation.tests.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * D-5：测试数据载荷——「模板 + 变体覆盖」的不可变基底 + 可链式覆盖。
 *
 * <p>背景：多场景需要变体数据时，原做法是复制整份 JSON 文件，维护成本随场景线性增长。本类把
 * {@code payload/<模板>.json} 作为基底，用 {@link #with(String, Object)} 覆盖个别字段（浅合并），
 * 从而一份模板支撑多场景变体。
 *
 * <p>线程安全：实例可变（{@code with} 链式累积），但每个用例应持有独立实例（工厂方法每次新建），
 * 跨线程共享同一实例时需自行同步。
 */
public final class Payload {

    private static final String TEMPLATE_DIR = "payload/";

    private final String templateName;
    private final JsonObject base;
    private final Map<String, Object> overrides = new LinkedHashMap<>();

    private Payload(String templateName, JsonObject base) {
        this.templateName = templateName;
        this.base = base;
    }

    /**
     * 从 classpath {@code payload/<templateName>.json} 加载模板（{@code .json} 后缀可省略）。
     *
     * @throws IllegalArgumentException 模板名为空或模板不存在
     */
    public static Payload of(String templateName) {
        if (templateName == null || templateName.isBlank()) {
            throw new IllegalArgumentException("payload templateName must not be blank");
        }
        String resource = TEMPLATE_DIR + (templateName.endsWith(".json") ? templateName : templateName + ".json");
        try (InputStream in = Payload.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Payload template not found on classpath: " + resource);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new Payload(templateName, JsonParser.parseString(json).getAsJsonObject());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load payload template: " + resource, e);
        }
    }

    /** 模板 + 批量覆盖（等价于 {@code of(name).withAll(overrides)}）。 */
    public static Payload ofTemplate(String templateName, Map<String, ?> overrides) {
        return of(templateName).withAll(overrides);
    }

    /** 覆盖单个顶层字段（null 值写入 JSON null）。 */
    public Payload with(String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("payload field key must not be blank");
        }
        overrides.put(key, value);
        return this;
    }

    /** 批量覆盖（null map 视为无操作）。 */
    public Payload withAll(Map<String, ?> values) {
        if (values != null) {
            values.forEach(this::with);
        }
        return this;
    }

    /** 应用覆盖后的 JSON 字符串（基底不变，覆盖为浅合并）。 */
    public String toJson() {
        JsonObject merged = base.deepCopy();
        overrides.forEach((k, v) -> merged.add(k, toJsonElement(v)));
        return merged.toString();
    }

    public String templateName() {
        return templateName;
    }

    private static JsonElement toJsonElement(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        return new JsonPrimitive(String.valueOf(value));
    }
}
