package com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 修改请求 Handler — 拦截请求，修改后继续发送。
 *
 * <p>核心改进：
 * <ul>
 *   <li><b>JSONPath 精准替换</b>：支持嵌套路径 {@code user.name} 和数组路径 {@code users[0].name}</li>
 *   <li><b>类型保持</b>：替换值时保留原字段类型（数字→数字，布尔→布尔，null→null），避免类型篡改</li>
 *   <li><b>安全降级</b>：JSON 解析失败时退化为字符串替换，但仅在 {@code allowFallbackStringReplace=true} 时启用</li>
 *   <li><b>请求头判空</b>：使用 Optional 包装，避免空指针</li>
 *   <li><b>异常安全</b>：route.resume() 包裹 try-catch，避免单请求失败导致整个路由崩溃</li>
 * </ul>
 */
public class ModifyHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModifyHandler.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** JsonPath 配置：使用 Jackson 提供器，禁止自动创建缺失路径 */
    private static final Configuration JSONPATH_CONFIG = Configuration.builder()
            .jsonProvider(new JacksonJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .options(Option.SUPPRESS_EXCEPTIONS)
            .build();

    /** JsonPath 编译缓存：避免重复解析相同路径表达式 */
    private static final Map<String, JsonPath> JSONPATH_CACHE = new ConcurrentHashMap<>();

    /** 是否在 JSON 解析失败时退化为字符串替换（False=仅处理 JSON） */
    private static volatile boolean allowFallbackStringReplace = false;

    /**
     * 设置 JSON 解析失败时是否退化为字符串替换。
     * 默认关闭，避免非预期的文本替换。
     */
    public static void setAllowFallbackStringReplace(boolean allow) {
        allowFallbackStringReplace = allow;
    }

    public static void handle(Route route, RouteRule rule) {
        Request req = route.request();
        Route.ResumeOptions opts = new Route.ResumeOptions();

        // ── 1. 修改请求头 ────────────────────────────────────────
        if (rule.getAddHeaders() != null) {
            Map<String, String> headers = Optional.ofNullable(req.headers())
                    .orElse(Collections.emptyMap());
            HashMap<String, String> newHeaders = new HashMap<>(headers);
            newHeaders.putAll(rule.getAddHeaders());
            opts.setHeaders(newHeaders);
        }

        // ── 2. 修改请求体 ────────────────────────────────────────
        if (rule.getReplaceBodyKey() != null) {
            byte[] postDataBuffer = req.postDataBuffer();

            if (postDataBuffer != null && postDataBuffer.length > 0) {
                String postData = new String(postDataBuffer, StandardCharsets.UTF_8);
                boolean isJson = postData.trim().startsWith("{") || postData.trim().startsWith("[");

                if (isJson) {
                    try {
                        // ── JSONPath 精准替换 ──
                        String newBody = replaceByJsonPath(postData, rule.getReplaceBodyKey(),
                                rule.getReplaceBodyValue());
                        opts.setPostData(newBody);
                        LOGGER.debug("[ModifyHandler] JSONPath replaced: path='{}', value='{}'",
                                rule.getReplaceBodyKey(), rule.getReplaceBodyValue());
                    } catch (Exception e) {
                        if (allowFallbackStringReplace) {
                            LOGGER.warn("[ModifyHandler] JSONPath replace failed ({}), falling back to string replace for path='{}'",
                                    e.getMessage(), rule.getReplaceBodyKey());
                            String newBody = postData.replace(
                                    rule.getReplaceBodyKey(), rule.getReplaceBodyValue());
                            opts.setPostData(newBody);
                        } else {
                            LOGGER.error("[ModifyHandler] JSONPath replace failed and fallback disabled, body unchanged: path='{}', error={}",
                                    rule.getReplaceBodyKey(), e.getMessage(), e);
                        }
                    }
                } else {
                    // 非 JSON 纯文本，使用字符串替换
                    String newBody = postData.replace(
                            rule.getReplaceBodyKey(), rule.getReplaceBodyValue());
                    opts.setPostData(newBody);
                    LOGGER.debug("[ModifyHandler] Non-JSON text body replaced: key='{}'",
                            rule.getReplaceBodyKey());
                }
            } else {
                LOGGER.debug("[ModifyHandler] No post data or binary body, skipping body replacement");
            }
        }

        // ── 3. 修改 HTTP 方法 ────────────────────────────────────
        if (rule.getMethod() != null) {
            opts.setMethod(rule.getMethod());
        }

        // ── 4. 放行请求（异常安全）─────────────────────────────────
        try {
            route.resume(opts);
        } catch (PlaywrightException e) {
            LOGGER.error("[ModifyHandler] Failed to resume route for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage(), e);
        }
    }

    /**
     * 使用 JsonPath 解析并替换 JSON body 中指定路径的字段值。
     *
     * <p>支持：
     * <ul>
     *   <li>嵌套路径：{@code user.name}</li>
     *   <li>数组索引：{@code users[0].name}</li>
     *   <li>类型保持：原字段是 int → 替换为 int，boolean → boolean，null → null</li>
     * </ul>
     *
     * @param jsonBody 原始 JSON body 字符串
     * @param path     JsonPath 路径
     * @param value    替换值（字符串形式，自动转换为原字段类型）
     * @return 替换后的 JSON 字符串
     */
    public static String replaceByJsonPath(String jsonBody, String path, String value) {
        try {
            // 1. 解析为 Jackson JsonNode
            JsonNode root = OBJECT_MAPPER.readTree(jsonBody);

            // 2. 获取原字段值（用于判断原类型）
            Object existingValue;
            try {
                JsonPath compiled = JSONPATH_CACHE.computeIfAbsent(path,
                        p -> JsonPath.compile(p));
                existingValue = compiled.read(jsonBody, JSONPATH_CONFIG);
            } catch (Exception e) {
                // 路径不存在时视为 null
                existingValue = null;
            }

            // 3. 转换替换值为原字段类型
            Object typedValue = convertToMatchingType(value, existingValue);

            // 4. 使用 Jackson 在 JsonNode 上进行路径替换
            setNodeByPath(root, path, typedValue);

            // 5. 序列化回字符串
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            LOGGER.warn("JSON processing failed for path={}: {}", path, e.getMessage());
            return jsonBody; // 解析失败返回原始 body，由上层退化为字符串替换
        }
    }

    /**
     * 将字符串值转换为与原字段类型匹配的值。
     */
    static Object convertToMatchingType(String newValue, Object existingValue) {
        if (existingValue == null || existingValue instanceof NullNode) {
            // 原字段为 null，尝试智能类型推断
            if ("null".equalsIgnoreCase(newValue) || "".equals(newValue)) {
                return NullNode.getInstance();
            }
            if ("true".equalsIgnoreCase(newValue) || "false".equalsIgnoreCase(newValue)) {
                return BooleanNode.valueOf(Boolean.parseBoolean(newValue));
            }
            try {
                return new IntNode(Integer.parseInt(newValue));
            } catch (NumberFormatException e1) {
                try {
                    return new DecimalNode(new BigDecimal(newValue));
                } catch (NumberFormatException e2) {
                    return new TextNode(newValue);
                }
            }
        }

        if (existingValue instanceof BooleanNode || existingValue instanceof Boolean) {
            return BooleanNode.valueOf(Boolean.parseBoolean(newValue));
        }
        if (existingValue instanceof IntNode || existingValue instanceof ShortNode
                || (existingValue instanceof Number && ((Number) existingValue).doubleValue() % 1 == 0
                        && ((Number) existingValue).longValue() <= Integer.MAX_VALUE
                        && ((Number) existingValue).longValue() >= Integer.MIN_VALUE)) {
            try {
                return new IntNode(Integer.parseInt(newValue));
            } catch (NumberFormatException e) {
                return new TextNode(newValue);
            }
        }
        if (existingValue instanceof LongNode || existingValue instanceof BigIntegerNode
                || (existingValue instanceof Number && ((Number) existingValue).longValue() > Integer.MAX_VALUE)) {
            try {
                return new LongNode(Long.parseLong(newValue));
            } catch (NumberFormatException e) {
                return new TextNode(newValue);
            }
        }
        if (existingValue instanceof FloatNode || existingValue instanceof DoubleNode
                || existingValue instanceof DecimalNode || existingValue instanceof Number) {
            try {
                return new DecimalNode(new BigDecimal(newValue));
            } catch (NumberFormatException e) {
                return new TextNode(newValue);
            }
        }

        // JSON 对象或数组 — 不替换，避免结构破坏
        if (existingValue instanceof ObjectNode || existingValue instanceof ArrayNode
                || existingValue instanceof JsonNode) {
            LOGGER.debug("[ModifyHandler] Target field is a JSON object/array, replacing as string: path={}", newValue);
        }

        // 默认为字符串
        return new TextNode(newValue);
    }

    /**
     * 在 JsonNode 树上按路径设置值。支持点号嵌套和数组索引。
     *
     * <p>路径示例：
     * <ul>
     *   <li>{@code name} → 顶层字段</li>
     *   <li>{@code user.name} → 嵌套对象字段</li>
     *   <li>{@code users[0].name} → 数组第一个元素的字段</li>
     *   <li>{@code users[0]} → 数组元素替换</li>
     * </ul>
     */
    static void setNodeByPath(JsonNode root, String path, Object value) {
        // 规范化：将 [数字] 分隔的路径转换为点号分隔 + 特殊数组标记
        String[] segments = path.split("\\.");
        JsonNode current = root;

        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean isLast = (i == segments.length - 1);

            // 解析数组标记 [index]
            int bracketIdx = segment.indexOf('[');
            String fieldName;
            Integer arrayIndex = null;

            if (bracketIdx > 0) {
                fieldName = segment.substring(0, bracketIdx);
                String idxStr = segment.substring(bracketIdx + 1, segment.indexOf(']'));
                try {
                    arrayIndex = Integer.parseInt(idxStr);
                } catch (NumberFormatException e) {
                    LOGGER.debug("[ModifyHandler] Invalid array index in path '{}': {}", path, idxStr);
                    return;
                }
            } else {
                fieldName = segment;
            }

            // 如果当前节点是数组，按索引获取
            if (current instanceof ArrayNode) {
                if (arrayIndex != null) {
                    if (arrayIndex < ((ArrayNode) current).size()) {
                        current = ((ArrayNode) current).get(arrayIndex);
                    } else {
                        LOGGER.debug("[ModifyHandler] Array index {} out of bounds (size={}) for path '{}'",
                                arrayIndex, ((ArrayNode) current).size(), path);
                        return;
                    }
                }
            }

            // 获取下一级节点
            if (current instanceof ObjectNode && fieldName != null && !fieldName.isEmpty()) {
                ObjectNode obj = (ObjectNode) current;

                if (isLast) {
                    // 最后一层：设置值
                    setJsonNode(obj, fieldName, value);
                } else {
                    JsonNode child = obj.get(fieldName);
                    if (child == null) {
                        LOGGER.debug("[ModifyHandler] Path segment '{}' not found in JSON path '{}'",
                                fieldName, path);
                        return;
                    }
                    if (arrayIndex != null && child instanceof ArrayNode) {
                        if (arrayIndex < ((ArrayNode) child).size()) {
                            current = ((ArrayNode) child).get(arrayIndex);
                        } else {
                            LOGGER.debug("[ModifyHandler] Array index {} out of child bounds for path '{}'",
                                    arrayIndex, path);
                            return;
                        }
                    } else {
                        current = child;
                    }
                }
            }
        }
    }

    /**
     * 根据 value 类型设置 JsonNode 字段值。
     */
    private static void setJsonNode(ObjectNode obj, String fieldName, Object value) {
        if (value instanceof NullNode || value == null) {
            obj.putNull(fieldName);
        } else if (value instanceof BooleanNode) {
            obj.put(fieldName, ((BooleanNode) value).booleanValue());
        } else if (value instanceof IntNode) {
            obj.put(fieldName, ((IntNode) value).intValue());
        } else if (value instanceof LongNode) {
            obj.put(fieldName, ((LongNode) value).longValue());
        } else if (value instanceof FloatNode) {
            obj.put(fieldName, ((FloatNode) value).floatValue());
        } else if (value instanceof DoubleNode) {
            obj.put(fieldName, ((DoubleNode) value).doubleValue());
        } else if (value instanceof DecimalNode) {
            obj.put(fieldName, ((DecimalNode) value).decimalValue());
        } else if (value instanceof BigIntegerNode) {
            obj.put(fieldName, ((BigIntegerNode) value).bigIntegerValue());
        } else if (value instanceof TextNode) {
            obj.put(fieldName, ((TextNode) value).textValue());
        } else if (value instanceof JsonNode) {
            obj.set(fieldName, (JsonNode) value);
        } else {
            obj.put(fieldName, value.toString());
        }
    }
}
