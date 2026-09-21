package com.hsbc.cmb.hk.dbb.automation.framework.api.utility;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ApiFrameworkConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.TypeRef;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
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

    // 关键JsonPath.parse 的轻量 LRU 缓存 —— 同一 json 字符串多次解析时复用 DocumentContext，
    // 减少重复 parse（特别在断言循环中常见）。最大 256 项，超过自动清理最早条目。
    private static final Map<String, DocumentContext> PARSE_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, DocumentContext>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, DocumentContext> eldest) {
                    return size() > 256;
                }
            });

    /**
     * 支持 {@link TypeRef} 类型化读取的 JsonPath 配置。
     *
     * <p>Jayway 默认的 {@code JsonSmartMappingProvider} <b>不支持</b> {@code TypeRef} 映射
     * （{@code read(path, typeRef)} 会抛异常，被调用方 catch 后返回 null —— 使该重载形同虚设）。
     * 故此处显式改用 Jackson 的 json/mapping provider，与 {@code fromJson(json, TypeRef)} 走同一套类型系统，
     * 保证「泛型读取」在两条 API 上口径一致。
     */
    private static final Configuration TYPE_REF_CONFIG = Configuration.builder()
            .jsonProvider(new JacksonJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .build();

    /**
     * 带缓存的 JsonPath.parse —— 同一 json 串多次解析时复用 DocumentContext，
     * 减少 JsonPath.parse 的重复解析开销（P3-25 性能优化）。
     *
     * <p><b>只读契约</b>：缓存实例是共享的，任何<b>会改写</b>文档的操作
     * （{@link #setValue}/{@link #deleteValue}）<b>不得</b>使用本方法取上下文，
     * 否则会把改写结果泄漏给后续读取同一 json 串的调用方（缓存污染）。变更类操作一律走
     * {@code JsonPath.parse(json)} 得到独占实例。
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
        if (ApiFrameworkConfig.shouldFailOnUnknownProperties()) {
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        } else {
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        }

        if (ApiFrameworkConfig.acceptSingleValueAsArray()) {
            mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        }

        if (ApiFrameworkConfig.ignoreNullForPrimitives()) {
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
            LOGGER.error("Failed to convert object to JSON: {}", e.getMessage(), e);
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
            LOGGER.error("Failed to convert JSON to object: {}", e.getMessage(), e);
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
            LOGGER.error("Failed to convert JSON to object: {}", e.getMessage(), e);
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
            LOGGER.error("Failed to get JSON path value: {}, path: {}", e.getMessage(), jsonPath);
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
            LOGGER.error("Failed to get JSON path value: {}, path: {}", e.getMessage(), jsonPath);
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
            // TypeRef 必须走 Jackson 映射 provider（见 TYPE_REF_CONFIG）：Jayway 默认 JsonSmart 不支持类型化映射
            return JsonPath.using(TYPE_REF_CONFIG).parse(json).read(jsonPath, typeRef);
        } catch (Exception e) {
            LOGGER.error("Failed to get JSON path value: {}, path: {}", e.getMessage(), jsonPath);
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
            // 变更类操作使用独占实例（勿走 parseCached，否则会把改写结果污染给后续读取同一 json 串的调用方）
            DocumentContext documentContext = JsonPath.parse(json);
            documentContext.set(jsonPath, value);
            return documentContext.jsonString();
        } catch (Exception e) {
            LOGGER.error("Failed to set JSON path value: {}, path: {}", e.getMessage(), jsonPath);
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
            // 变更类操作使用独占实例（勿走 parseCached，同上：避免污染共享缓存实例）
            DocumentContext documentContext = JsonPath.parse(json);
            documentContext.delete(jsonPath);
            return documentContext.jsonString();
        } catch (Exception e) {
            LOGGER.error("Failed to delete JSON path node: {}, path: {}", e.getMessage(), jsonPath);
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
            LOGGER.error("Failed to format JSON: {}", e.getMessage());
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
            LOGGER.error("Failed to merge JSON: {}", e.getMessage());
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
            LOGGER.error("Failed to compare JSON: {}", e.getMessage());
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
            LOGGER.error("Failed to get JSON path value list: {}, path: {}", e.getMessage(), jsonPath);
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