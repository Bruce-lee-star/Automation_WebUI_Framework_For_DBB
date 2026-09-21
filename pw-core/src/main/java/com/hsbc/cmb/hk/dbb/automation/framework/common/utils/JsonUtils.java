package com.hsbc.cmb.hk.dbb.automation.framework.common.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON 基础工具（core 地基版，ARCH-1 修复 2026-09-20）。
 *
 * <p><b>背景</b>：原 {@code framework.api.utility.JsonUtils} 被 web 层的 {@code NLSUtils} 单点依赖，
 * 导致 web 编译期耦合 api 整模块（连带 RestAssured 等），违背「api 与 web 同级、都只依赖 core」的
 * 分层意图。现把 JSON 基础能力下沉到 core（地基包），web 改依赖本类，web→api 的唯一编译边随之消失，
 * api 与 web 真正成为同级模块。
 *
 * <p><b>约束</b>：本类仅依赖 {@code jackson-databind}（core 已显式声明），不引入 api.config / json-path
 * 等上层依赖，保持 core 为零上层依赖。ObjectMapper 采用宽松配置
 * （{@code FAIL_ON_UNKNOWN_PROPERTIES=false}），与 nls 等配置文件的解析语义一致。
 */
public final class JsonUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonUtils.class);
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        mapper.configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false);
        return mapper;
    }

    private JsonUtils() {
    }

    /** 序列化对象为 JSON 字符串；入参为 null 时返回 null。 */
    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            LOGGER.error("Failed to convert object to JSON: {}", e.getMessage(), e);
            return null;
        }
    }

    /** 反序列化 JSON 为指定类型；空串/异常时返回 null。 */
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            LOGGER.error("Failed to convert JSON to object: {}", e.getMessage(), e);
            return null;
        }
    }

    /** 反序列化 JSON 为泛型类型（基于 {@link TypeReference}）；空串/异常时返回 null。 */
    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, OBJECT_MAPPER.getTypeFactory().constructType(typeRef.getType()));
        } catch (Exception e) {
            LOGGER.error("Failed to convert JSON to object: {}", e.getMessage(), e);
            return null;
        }
    }

    /** 是否为合法 JSON。 */
    public static boolean isValidJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return false;
        }
        try {
            OBJECT_MAPPER.readTree(json);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /** 格式化 JSON（美化输出）；非法 JSON 原样返回。 */
    public static String formatJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            Object jsonObject = OBJECT_MAPPER.readValue(json, Object.class);
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
        } catch (JsonProcessingException e) {
            return json;
        }
    }
}
