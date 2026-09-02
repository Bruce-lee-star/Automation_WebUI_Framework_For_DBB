package com.hsbc.cmb.hk.dbb.automation.framework.api.utility;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.FrameworkConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON工具类 - 提供JSON序列化、反序列化和路径操作功能
 */
public class JsonUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonUtils.class);
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    // 关键修复 P3-25：JsonPath.parse 的轻量 LRU 缓存 —— 同一 json 字符串多次解析时复用 DocumentContext，
    // 减少重复 parse（特别在断言循环中常见）。最大 256 项，超过自动清理最早条目。
    private static final Map<String, DocumentContext> PARSE_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, DocumentContext>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, DocumentContext> eldest) {
                    return size() > 256;
                }
            });

    /**
     * 带缓存的 JsonPath.parse —— 同一 json 串多次解析时复用 DocumentContext，
     * 减少 JsonPath.parse 的重复解析开销（P3-25 性能优化）。
     */
    private static DocumentContext parseCached(String json) {
        DocumentContext ctx = PARSE_CACHE.get(json);
        if (ctx == null) {
            ctx = JsonPath.parse(json);
            PARSE_CACHE.put(json, ctx);
        }
        return ctx;
    }

    /**
     * Create configurable ObjectMapper based on framework configuration
     * @return configured ObjectMapper instance
     */
    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Configure JSON parsing behavior based on application.conf
        if (FrameworkConfig.shouldFailOnUnknownProperties()) {
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        } else {
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        }

        if (FrameworkConfig.acceptSingleValueAsArray()) {
            mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        }

        if (FrameworkConfig.ignoreNullForPrimitives()) {
            mapper.configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false);
        }

        return mapper;
    }

    /**
     * 对象转JSON字符串
     */
    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }

        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            LOGGER.error("对象转JSON失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * JSON字符串转对象
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            LOGGER.error("JSON转对象失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * JSON字符串转对象（复杂类型）
     */
    public static <T> T fromJson(String json, TypeRef<T> typeRef) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            // JsonPath 默认的 JsonSmartMappingProvider 不支持 TypeRef，直接用 Jackson 反序列化
            return OBJECT_MAPPER.readValue(json, OBJECT_MAPPER.getTypeFactory().constructType(typeRef.getType()));
        } catch (Exception e) {
            LOGGER.error("JSON转对象失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取JSON路径对应的值
     */
    public static Object getValue(String json, String jsonPath) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            DocumentContext documentContext = parseCached(json);
            return documentContext.read(jsonPath);
        } catch (Exception e) {
            LOGGER.error("获取JSON路径值失败: {}, 路径: {}", e.getMessage(), jsonPath);
            return null;
        }
    }

    /**
     * 获取JSON路径对应的值（指定类型）
     */
    public static <T> T getValue(String json, String jsonPath, Class<T> type) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            DocumentContext documentContext = parseCached(json);
            return documentContext.read(jsonPath, type);
        } catch (Exception e) {
            LOGGER.error("获取JSON路径值失败: {}, 路径: {}", e.getMessage(), jsonPath);
            return null;
        }
    }

    /**
     * 获取JSON路径对应的值（复杂类型）
     */
    public static <T> T getValue(String json, String jsonPath, TypeRef<T> typeRef) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            DocumentContext documentContext = parseCached(json);
            return documentContext.read(jsonPath, typeRef);
        } catch (Exception e) {
            LOGGER.error("获取JSON路径值失败: {}, 路径: {}", e.getMessage(), jsonPath);
            return null;
        }
    }

    /**
     * 设置JSON路径对应的值
     */
    public static String setValue(String json, String jsonPath, Object value) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            DocumentContext documentContext = parseCached(json);
            documentContext.set(jsonPath, value);
            return documentContext.jsonString();
        } catch (Exception e) {
            LOGGER.error("设置JSON路径值失败: {}, 路径: {}", e.getMessage(), jsonPath);
            return json; // 返回原始JSON
        }
    }

    /**
     * 删除JSON路径对应的节点
     */
    public static String deleteValue(String json, String jsonPath) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            DocumentContext documentContext = parseCached(json);
            documentContext.delete(jsonPath);
            return documentContext.jsonString();
        } catch (Exception e) {
            LOGGER.error("删除JSON路径节点失败: {}, 路径: {}", e.getMessage(), jsonPath);
            return json; // 返回原始JSON
        }
    }

    /**
     * 验证JSON格式是否正确
     */
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

    /**
     * 格式化JSON字符串
     */
    public static String formatJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            Object jsonObject = OBJECT_MAPPER.readValue(json, Object.class);
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
        } catch (JsonProcessingException e) {
            LOGGER.error("格式化JSON失败: {}", e.getMessage());
            return json; // 返回原始JSON
        }
    }

    /**
     * 合并两个JSON对象
     */
    public static String mergeJson(String json1, String json2) {
        if (json1 == null || json1.trim().isEmpty()) {
            return json2;
        }
        if (json2 == null || json2.trim().isEmpty()) {
            return json1;
        }

        try {
            Map<String, Object> map1 = fromJson(json1, new TypeRef<Map<String, Object>>() {});
            Map<String, Object> map2 = fromJson(json2, new TypeRef<Map<String, Object>>() {});

            if (map1 != null && map2 != null) {
                map1.putAll(map2);
                return toJson(map1);
            }
        } catch (Exception e) {
            LOGGER.error("合并JSON失败: {}", e.getMessage());
        }

        return json1; // 返回第一个JSON作为备选
    }

    /**
     * 深度比较两个JSON字符串是否相等
     */
    public static boolean equalsJson(String json1, String json2) {
        if (json1 == null && json2 == null) {
            return true;
        }
        if (json1 == null || json2 == null) {
            return false;
        }

        try {
            Object obj1 = OBJECT_MAPPER.readTree(json1);
            Object obj2 = OBJECT_MAPPER.readTree(json2);
            return obj1.equals(obj2);
        } catch (IOException e) {
            LOGGER.error("比较JSON失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取JSON中所有匹配路径的值
     */
    public static List<Object> getValues(String json, String jsonPath) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }

        try {
            DocumentContext documentContext = parseCached(json);
            return documentContext.read(jsonPath);
        } catch (Exception e) {
            LOGGER.error("获取JSON路径值列表失败: {}, 路径: {}", e.getMessage(), jsonPath);
            return Collections.emptyList();
        }
    }

    /**
     * 检查JSON中是否存在指定路径
     */
    public static boolean hasPath(String json, String jsonPath) {
        if (json == null || json.trim().isEmpty()) {
            return false;
        }

        try {
            DocumentContext documentContext = parseCached(json);
            Object value = documentContext.read(jsonPath);
            return value != null;
        } catch (Exception e) {
            // 路径不存在或读取失败
            return false;
        }
    }
}