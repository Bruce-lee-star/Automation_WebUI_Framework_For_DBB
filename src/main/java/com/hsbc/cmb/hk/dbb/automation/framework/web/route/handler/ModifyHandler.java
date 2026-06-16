package com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SerenityReporter;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
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
import java.util.*;
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

    /** JsonPath 编译缓存：避免重复解析相同路径表达式。
     *  使用简单容量限制防止长期运行缓慢增长。 */
    private static final Map<String, JsonPath> JSONPATH_CACHE = new ConcurrentHashMap<>();

    /** JSONPath 缓存容量上限，超过后清空重建 */
    private static final int JSONPATH_CACHE_MAX_SIZE = 200;

    /** 是否在 JSON 解析失败时退化为字符串替换（False=仅处理 JSON） */
    private static volatile boolean allowFallbackStringReplace = false;

    /**
     * 设置 JSON 解析失败时是否退化为字符串替换。
     * 默认关闭，避免非预期的文本替换。
     */
    public static void setAllowFallbackStringReplace(boolean allow) {
        allowFallbackStringReplace = allow;
    }

    /**
     * ⭐ #7 伪 LRU 淘汰：从 ConcurrentHashMap 中移除约 25% 的条目。
     * <p>使用弱一致性迭代器，兼容并发环境。
     */
    private static void evictOldestQuarter(Map<?, ?> map) {
        int evictCount = Math.max(1, map.size() / 4);
        Iterator<?> it = map.keySet().iterator();
        for (int i = 0; i < evictCount && it.hasNext(); i++) {
            it.next();
            it.remove();
        }
    }

    /**
     * 清空 JSONPath 编译缓存。
     *
     * <p>建议在测试套件结束时调用，防止长期运行（如 CI 节点多天不重启）
     * 场景下缓存缓慢增长。单次测试中缓存 < 200 条目会自动清空旧条目。
     */
    public static void clearJsonPathCache() {
        int size = JSONPATH_CACHE.size();
        JSONPATH_CACHE.clear();
        if (size > 0) {
            LOGGER.debug("[ModifyHandler] JSONPath cache cleared ({} entries freed)", size);
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[ModifyHandler] JSONPath cache cleared: {} entries freed", size);
        }
    }

    /**
     * 获取 JSONPath 缓存条目数（用于监控）。
     */
    public static int getJsonPathCacheSize() {
        return JSONPATH_CACHE.size();
    }

    public static void handle(Route route, RouteRule rule) {
        Request req = route.request();
        Route.ResumeOptions opts = new Route.ResumeOptions();

        // 保存修改后的最终状态，用于在 resume 前打印完整请求
        Map<String, String> finalHeaders = null;
        String finalBody = null;
        boolean bodyModified = false;

        // ⭐ P4: 缓存 rule getter 值，日志和下游逻辑共享，避免重复调用 getter
        Map<String, String> requestHeadersToSet = rule.getRequestHeadersToSet();
        Set<String> requestHeadersToRemove = rule.getRequestHeadersToRemove();
        boolean hasHeaderModifications = (requestHeadersToSet != null && !requestHeadersToSet.isEmpty())
                || (requestHeadersToRemove != null && !requestHeadersToRemove.isEmpty());

        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[ModifyHandler] ── handle() START: pattern='{}', url='{}', method={}, "
                + "headersToSet={}, headersToRemove={}, bodyMods={}, modifyMethod={} ──",
                rule.getUrlPattern(), req.url(), req.method(),
                requestHeadersToSet != null ? requestHeadersToSet.size() : 0,
                requestHeadersToRemove != null ? requestHeadersToRemove.size() : 0,
                (rule.getRequestBodyFieldsToModify() != null ? rule.getRequestBodyFieldsToModify().size() : 0)
                    + (rule.getRequestBodyFieldsToAdd() != null ? rule.getRequestBodyFieldsToAdd().size() : 0)
                    + (rule.getRequestBodyFieldsToRemove() != null ? rule.getRequestBodyFieldsToRemove().size() : 0),
                rule.getModifyMethod());

        if (hasHeaderModifications) {
            Map<String, String> existingHeaders = Optional.ofNullable(req.headers())
                    .orElse(Collections.emptyMap());
            HashMap<String, String> newHeaders = new HashMap<>(existingHeaders);

            // 1a. 先删
            if (requestHeadersToRemove != null) {
                for (String key : requestHeadersToRemove) {
                    newHeaders.remove(key);
                    LOGGER.debug("[ModifyHandler] Header removed: '{}'", key);
                }
            }

            // 1b. 再改/增
            if (requestHeadersToSet != null) {
                newHeaders.putAll(requestHeadersToSet);
            }

            opts.setHeaders(newHeaders);
            finalHeaders = Collections.unmodifiableMap(newHeaders);
        } else {
            finalHeaders = req.headers();  // 未修改则用原始请求头
        }

        // ── 2. 修改请求体（增删改） ─────────────────────────────────
        Map<String, String> fieldsToModify = rule.getRequestBodyFieldsToModify();
        Map<String, String> fieldsToAdd = rule.getRequestBodyFieldsToAdd();
        Set<String> fieldsToRemove = rule.getRequestBodyFieldsToRemove();
        boolean hasBodyModifications = (fieldsToModify != null && !fieldsToModify.isEmpty())
                || (fieldsToAdd != null && !fieldsToAdd.isEmpty())
                || (fieldsToRemove != null && !fieldsToRemove.isEmpty());

        if (hasBodyModifications) {
            byte[] postDataBuffer = req.postDataBuffer();

            if (postDataBuffer != null && postDataBuffer.length > 0) {
                String postData = new String(postDataBuffer, StandardCharsets.UTF_8);
                boolean isJson = postData.trim().startsWith("{") || postData.trim().startsWith("[");

                if (isJson) {
                    // ⭐ #3 性能优化：ParseOnce — 一次解析，树级修改，一次序列化
                    //   避免了逐字段循环中 N 次 readTree + writeValueAsString 的 O(N×size) 开销
                    JsonNode root;
                    try {
                        root = OBJECT_MAPPER.readTree(postData);
                    } catch (JsonProcessingException e) {
                        // 解析失败：保持原始 body，跳过 JSON 树修改
                        LOGGER.warn("[ModifyHandler] JSON parse failed, body unchanged: {}", e.getMessage());
                        root = null;
                    }

                    if (root != null) {

                        boolean treeModified = false;

                        // 2a. 先修改已有字段（树级操作）
                        if (fieldsToModify != null) {
                            for (Map.Entry<String, String> entry : fieldsToModify.entrySet()) {
                                String path = entry.getKey();
                                String value = entry.getValue();
                                try {
                                    modifyFieldOnTree(root, path, value);
                                    treeModified = true;
                                    LOGGER.debug("[ModifyHandler] Body field modified: path='{}', value='{}'", path, value);
                                } catch (Exception e) {
                                    if (allowFallbackStringReplace) {
                                        LOGGER.warn("[ModifyHandler] Body modify failed ({}), falling back to string replace: path='{}'",
                                                e.getMessage(), path);
                                    } else {
                                        LOGGER.error("[ModifyHandler] Body modify failed and fallback disabled, skipping path='{}': {}",
                                                path, e.getMessage(), e);
                                    }
                                }
                            }
                        }

                        // 2b. 新增字段（树级操作）
                        if (fieldsToAdd != null) {
                            for (Map.Entry<String, String> entry : fieldsToAdd.entrySet()) {
                                String path = entry.getKey();
                                String value = entry.getValue();
                                try {
                                    addFieldOnTree(root, path, value);
                                    treeModified = true;
                                    LOGGER.debug("[ModifyHandler] Body field added: path='{}', value='{}'", path, value);
                                } catch (Exception e) {
                                    LOGGER.error("[ModifyHandler] Body field add failed: path='{}': {}", path, e.getMessage(), e);
                                }
                            }
                        }

                        // 2c. 删除字段（树级操作）
                        if (fieldsToRemove != null) {
                            for (String path : fieldsToRemove) {
                                try {
                                    removeFieldOnTree(root, path);
                                    treeModified = true;
                                    LOGGER.debug("[ModifyHandler] Body field removed: path='{}'", path);
                                } catch (Exception e) {
                                    LOGGER.error("[ModifyHandler] Body field remove failed: path='{}': {}", path, e.getMessage(), e);
                                }
                            }
                        }

                        // ⭐ 序列化一次
                        String newBody = postData;
                        if (treeModified) {
                            try {
                                newBody = OBJECT_MAPPER.writeValueAsString(root);
                            } catch (JsonProcessingException e) {
                                LOGGER.warn("[ModifyHandler] Failed to serialize modified body: {}", e.getMessage());
                            }
                        }
                        opts.setPostData(newBody);
                        finalBody = newBody;
                        bodyModified = true;
                    }  // end if (root != null)
                } else {
                    // 非 JSON：仅支持字符串替换
                    if ((fieldsToAdd != null && !fieldsToAdd.isEmpty())
                            || (fieldsToRemove != null && !fieldsToRemove.isEmpty())) {
                        LOGGER.warn("[ModifyHandler] Body add/remove operations are ignored for non-JSON content. "
                                + "Only field modifications (string replace) are supported.");
                    }
                    String newBody = postData;
                    if (fieldsToModify != null) {
                        for (Map.Entry<String, String> entry : fieldsToModify.entrySet()) {
                            newBody = newBody.replace(entry.getKey(), entry.getValue());
                            LOGGER.debug("[ModifyHandler] Non-JSON text body modified: key='{}'", entry.getKey());
                        }
                    }
                    opts.setPostData(newBody);
                    finalBody = newBody;
                    bodyModified = true;
                }
            } else {
                LOGGER.debug("[ModifyHandler] No post data or binary body, skipping body modifications");
            }
        }

        // ── 3. 修改 HTTP 方法 ────────────────────────────────────
        String finalMethod = rule.getModifyMethod() != null ? rule.getModifyMethod() : req.method();
        if (rule.getModifyMethod() != null) {
            opts.setMethod(rule.getModifyMethod());
        }

        // ── 4. 打印完整的修改后请求（便于调试和审计）─────────────────
        LoggingConfigUtil.logInfoIfVerbose(LOGGER, "[ModifyHandler] ===== Modified Request =====\n" +
                "  URL     : {}\n" +
                "  Method  : {} -> {}\n" +
                "  Pattern : {}\n" +
                "  Headers : {}\n" +
                "  Body    : {}",
                RouteUtil.sanitizeUrl(req.url()),
                req.method(), finalMethod,
                rule.getUrlPattern(),
                finalHeaders,
                finalBody != null ? finalBody : (bodyModified ? "(empty)" : "(unchanged)"));

        // ── 5. 放行请求（异常安全）─────────────────────────────────
        try {
            route.resume(opts);
            LOGGER.info("[ModifyHandler] Modified: url={}, pattern='{}', method={}, headersSet={}, headersRemoved={}, bodyModified={}, bodyAdded={}, bodyRemoved={}",
                    RouteUtil.sanitizeUrl(req.url()), rule.getUrlPattern(),
                    finalMethod,
                    requestHeadersToSet != null ? requestHeadersToSet.keySet() : "none",
                    requestHeadersToRemove != null ? requestHeadersToRemove : "none",
                    fieldsToModify != null ? fieldsToModify.keySet() : "none",
                    fieldsToAdd != null ? fieldsToAdd.keySet() : "none",
                    fieldsToRemove != null ? fieldsToRemove : "none");
            SerenityReporter.recordApiOperation("MODIFY", req.url(),
                    String.format("Pattern: %s\nMethod: %s\nHeadersSet: %s\nHeadersRemoved: %s\nBodyModified: %s\nBodyAdded: %s\nBodyRemoved: %s",
                            rule.getUrlPattern(),
                            finalMethod,
                            requestHeadersToSet != null ? requestHeadersToSet.toString() : "none",
                            requestHeadersToRemove != null ? requestHeadersToRemove.toString() : "none",
                            fieldsToModify != null ? fieldsToModify.toString() : "none",
                            fieldsToAdd != null ? fieldsToAdd.toString() : "none",
                            fieldsToRemove != null ? fieldsToRemove.toString() : "none"));

            // ── 6. 存储 Modify 调用到 ApiCaptureContext ───────────────
            try {
                // ⭐ 性能优化：用 ObjectNode 一次性构建 JSON（之前 7 次 writeValueAsString，
                //    每次都要做 Jackson 序列化）。现在只做 1 次 tree→string 序列化。
                ObjectNode detailNode = OBJECT_MAPPER.createObjectNode();
                detailNode.put("originalUrl", req.url());
                detailNode.put("modifiedMethod", finalMethod);

                if (requestHeadersToSet != null) {
                    detailNode.set("headersSet", OBJECT_MAPPER.valueToTree(requestHeadersToSet));
                } else {
                    detailNode.putNull("headersSet");
                }
                if (requestHeadersToRemove != null) {
                    detailNode.set("headersRemoved", OBJECT_MAPPER.valueToTree(requestHeadersToRemove));
                } else {
                    detailNode.putNull("headersRemoved");
                }
                if (fieldsToModify != null) {
                    detailNode.set("bodyFieldsModified", OBJECT_MAPPER.valueToTree(fieldsToModify));
                } else {
                    detailNode.putNull("bodyFieldsModified");
                }
                if (fieldsToAdd != null) {
                    detailNode.set("bodyFieldsAdded", OBJECT_MAPPER.valueToTree(fieldsToAdd));
                } else {
                    detailNode.putNull("bodyFieldsAdded");
                }
                if (fieldsToRemove != null) {
                    detailNode.set("bodyFieldsRemoved", OBJECT_MAPPER.valueToTree(fieldsToRemove));
                } else {
                    detailNode.putNull("bodyFieldsRemoved");
                }
                if (finalBody != null) {
                    detailNode.put("modifiedBody", finalBody);
                } else {
                    detailNode.putNull("modifiedBody");
                }

                String modifyDetail = OBJECT_MAPPER.writeValueAsString(detailNode);

                CapturedApiCall call = new CapturedApiCall(
                        rule.getUrlPattern(),
                        req.method(),
                        null,   // Modify 场景无请求头快照
                        0,      // resume 后无直接响应状态码
                        finalHeaders,
                        modifyDetail,
                        System.currentTimeMillis(),
                        req.url()  // 实际请求 URL，用于毫秒级精确检索
                );
                ApiCaptureContext ctx = ApiCaptureContext.getCurrent();
                if (ctx != null) {
                    ctx.storeApiCall(call);
                    LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                            "[ModifyHandler] Stored to ApiCaptureContext: endpoint='{}', method={}",
                            rule.getUrlPattern(), req.method());
                } else {
                    LOGGER.debug("[ModifyHandler] ApiCaptureContext is null, skipped store for pattern '{}'",
                            rule.getUrlPattern());
                }
            } catch (Exception e) {
                LOGGER.debug("[ModifyHandler] Failed to store modify call to ApiCaptureContext: {}", e.getMessage());
            }
        } catch (PlaywrightException e) {
            LOGGER.error("[ModifyHandler] Failed to resume route for pattern '{}': {}",
                    rule.getUrlPattern(), e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ⭐ #3 性能优化：树级修改方法（无解析/序列化，供 handle() 批量操作复用）
    // ═══════════════════════════════════════════════════════════════

    /**
     * ⭐ 树级修改：在已解析的 JsonNode 树上按 JSONPath 替换字段值（保持类型）。
     */
    private static void modifyFieldOnTree(JsonNode root, String path, String value) {
        // 类型推断
        Object existingValue;
        try {
            if (JSONPATH_CACHE.size() >= JSONPATH_CACHE_MAX_SIZE) {
                evictOldestQuarter(JSONPATH_CACHE);
            }
            JsonPath compiled = JSONPATH_CACHE.computeIfAbsent(path, p -> JsonPath.compile(p));
            existingValue = compiled.read(root, JSONPATH_CONFIG);
        } catch (Exception e) {
            existingValue = null;
        }
        Object typedValue = convertToMatchingType(value, existingValue);
        setNodeByPath(root, path, typedValue);
    }

    /**
     * ⭐ 树级新增：在已解析的 JsonNode 树上按路径新增字段。
     */
    private static void addFieldOnTree(JsonNode root, String path, String value) {
        Object typedValue = convertToMatchingType(value, null);

        String[] segments = path.split("\\.");
        JsonNode current = root;

        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if ("$".equals(segment) || segment.isEmpty()) continue;

            boolean isLast = (i == segments.length - 1);
            int bracketIdx = segment.indexOf('[');
            String fieldName;
            if (bracketIdx > 0) {
                fieldName = segment.substring(0, bracketIdx);
            } else {
                fieldName = segment;
            }
            if (fieldName.isEmpty()) continue;

            if (current instanceof ObjectNode) {
                ObjectNode obj = (ObjectNode) current;
                if (isLast) {
                    JsonNode existing = obj.get(fieldName);
                    if (existing instanceof ArrayNode) {
                        ArrayNode arr = (ArrayNode) existing;
                        try {
                            JsonNode parsed = OBJECT_MAPPER.readTree(value);
                            arr.add(parsed);
                        } catch (JsonProcessingException e) {
                            arr.add(OBJECT_MAPPER.valueToTree(typedValue));
                        }
                    } else {
                        setJsonNode(obj, fieldName, typedValue);
                    }
                } else {
                    JsonNode child = obj.get(fieldName);
                    if (child == null) {
                        child = OBJECT_MAPPER.createObjectNode();
                        obj.set(fieldName, child);
                    }
                    current = child;
                }
            }
        }
    }

    /**
     * ⭐ 树级删除：在已解析的 JsonNode 树上按路径删除字段。
     */
    private static void removeFieldOnTree(JsonNode root, String path) {
        String[] segments = path.split("\\.");
        JsonNode current = root;

        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if ("$".equals(segment) || segment.isEmpty()) continue;

            boolean isLast = (i == segments.length - 1);
            int bracketIdx = segment.indexOf('[');
            String fieldName;
            if (bracketIdx > 0) {
                fieldName = segment.substring(0, bracketIdx);
            } else {
                fieldName = segment;
            }
            if (fieldName.isEmpty()) continue;

            if (current instanceof ObjectNode) {
                ObjectNode obj = (ObjectNode) current;
                if (isLast) {
                    obj.remove(fieldName);
                } else {
                    JsonNode child = obj.get(fieldName);
                    if (child == null) return;
                    current = child;
                }
            }
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
            JsonNode root = OBJECT_MAPPER.readTree(jsonBody);
            modifyFieldOnTree(root, path, value);
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            LOGGER.warn("JSON processing failed for path={}: {}", path, e.getMessage());
            return jsonBody;
        }
    }

    /**
     * 在 JSON body 中按路径新增字段，支持向已有数组追加元素。
     *
     * <p>行为规则：
     * <ul>
     *   <li>路径末段对应的字段不存在 → 创建普通字段</li>
     *   <li>路径末段对应的字段已存在且为 {@code ArrayNode} → 追加到数组尾部</li>
     *   <li>区间节点不存在 → 自动创建 {@code ObjectNode}</li>
     * </ul>
     *
     * <p>示例：
     * <pre>{@code
     * // 向 $.data.items 数组追加一个元素
     * addFieldByJsonPath(body, "$.data.items", "{\"id\":1}")
     *
     * // 创建新字段
     * addFieldByJsonPath(body, "$.newField", "hello")
     * }</pre>
     *
     * @param jsonBody 原始 JSON body 字符串
     * @param path     JsonPath 路径（如 {@code $.newField}、{@code $.data.items}）
     * @param value    字段值（字符串形式，自动类型推断；JSON 字符串被解析为对象/数组节点）
     * @return 修改后的 JSON 字符串
     */
    public static String addFieldByJsonPath(String jsonBody, String path, String value) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(jsonBody);
            addFieldOnTree(root, path, value);
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            LOGGER.warn("JSON processing failed for addField path={}: {}", path, e.getMessage());
            return jsonBody;
        }
    }

    /**
     * 从 JSON body 中按路径删除字段。
     *
     * @param jsonBody 原始 JSON body 字符串
     * @param path     JsonPath 路径（如 {@code $.fieldName}、{@code $.nested.field}）
     * @return 修改后的 JSON 字符串
     */
    public static String removeFieldByJsonPath(String jsonBody, String path) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(jsonBody);
            removeFieldOnTree(root, path);
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            LOGGER.warn("JSON processing failed for removeField path={}: {}", path, e.getMessage());
            return jsonBody;
        }
    }

    /**
     * 将字符串值转换为与原字段类型匹配的值。
     *
     * <p>使用类型分类减少 instanceof 链长度：
     * <ul>
     *   <li>null → 智能推断</li>
     *   <li>布尔 → BooleanNode</li>
     *   <li>整数类型 → IntNode / LongNode</li>
     *   <li>浮点类型 → DecimalNode</li>
     *   <li>JSON 结构 → 字符串（避免破坏结构）</li>
     *   <li>其他 → TextNode</li>
     * </ul>
     */
    static Object convertToMatchingType(String newValue, Object existingValue) {
        // ── 原值为 null ──
        if (existingValue == null || existingValue instanceof NullNode) {
            Object result = inferTypeForNull(newValue);
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[ModifyHandler] Type convert (null): newValue='{}' -> {}",
                    newValue, result.getClass().getSimpleName());
            return result;
        }

        Class<?> type = existingValue.getClass();

        // ── 布尔 ──
        if (type == BooleanNode.class || existingValue instanceof Boolean) {
            return BooleanNode.valueOf(Boolean.parseBoolean(newValue));
        }

        // ── JSON 结构（Object/Array）→ 先尝试解析为 JSON，失败则降级为字符串 ──
        if (existingValue instanceof ObjectNode || existingValue instanceof ArrayNode) {
            try {
                JsonNode parsed = OBJECT_MAPPER.readTree(newValue);
                LOGGER.debug("[ModifyHandler] Target field is a JSON object/array, parsed as {}",
                        parsed.getNodeType());
                return parsed;
            } catch (JsonProcessingException e) {
                LOGGER.debug("[ModifyHandler] Target field is a JSON object/array, falling back to string: {}",
                        e.getMessage());
                return new TextNode(newValue);
            }
        }

        // ── 数值类型 ──
        if (existingValue instanceof JsonNode) {
            if (existingValue instanceof IntNode || existingValue instanceof ShortNode) {
                return tryParseInt(newValue);
            }
            if (existingValue instanceof LongNode || existingValue instanceof BigIntegerNode) {
                return tryParseLong(newValue);
            }
            if (existingValue instanceof FloatNode || existingValue instanceof DoubleNode
                    || existingValue instanceof DecimalNode) {
                return tryParseDecimal(newValue);
            }
        }
        // Number 类型兜底
        if (existingValue instanceof Number) {
            return parseNumberByRange((Number) existingValue, newValue);
        }

        // 默认为字符串
        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[ModifyHandler] Type convert (default text): existingType={}, newValue='{}'",
                type.getSimpleName(), newValue);
        return new TextNode(newValue);
    }

    /** 原值为 null 时的智能类型推断 */
    private static JsonNode inferTypeForNull(String newValue) {
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

    /** 尝试解析为整数，失败则返回 TextNode */
    private static JsonNode tryParseInt(String newValue) {
        try {
            return new IntNode(Integer.parseInt(newValue));
        } catch (NumberFormatException e) {
            return new TextNode(newValue);
        }
    }

    /** 尝试解析为长整数，失败则返回 TextNode */
    private static JsonNode tryParseLong(String newValue) {
        try {
            return new LongNode(Long.parseLong(newValue));
        } catch (NumberFormatException e) {
            return new TextNode(newValue);
        }
    }

    /** 尝试解析为 Decimal，失败则返回 TextNode */
    private static JsonNode tryParseDecimal(String newValue) {
        try {
            return new DecimalNode(new BigDecimal(newValue));
        } catch (NumberFormatException e) {
            return new TextNode(newValue);
        }
    }

    /** 根据 Number 范围决定整数还是 Decimal */
    private static JsonNode parseNumberByRange(Number num, String newValue) {
        long longVal = num.longValue();
        if (num.doubleValue() % 1 == 0
                && longVal <= Integer.MAX_VALUE
                && longVal >= Integer.MIN_VALUE) {
            return tryParseInt(newValue);
        }
        if (longVal > Integer.MAX_VALUE) {
            return tryParseLong(newValue);
        }
        return tryParseDecimal(newValue);
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

            // 跳过 JSONPath 根前缀 "S" 和空段
            if ("$".equals(segment) || segment.isEmpty()) {
                continue;
            }

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

    // ═══════════════════════════════════════════════════════════════
    // 通配符 [*] 批量字段替换（用于 Mock 响应 List 数据批量处理）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对 JSON body 批量执行通配符字段替换。
     *
     * <p>支持的通配符路径格式：
     * <ul>
     *   <li>{@code $.name} — 替换顶层字段</li>
     *   <li>{@code $[*].name} — 替换顶层数组中所有元素的 name 字段</li>
     *   <li>{@code $.users[*].name} — 替换 users 数组中所有元素的 name 字段</li>
     *   <li>{@code $.users[*].orders[*].price} — 嵌套 List 批量替换：每个 user 的每个 order 的 price</li>
     *   <li>{@code $.users[0].name} — 精确索引替换（非通配符）</li>
     * </ul>
     *
     * ⭐ value 为 Object 类型，支持 String、Integer、Double、Boolean、null 等。
     *
     * @param jsonBody     原始 JSON body 字符串
     * @param replacements 路径 → 值 的映射（Object 类型）
     * @return 替换后的 JSON 字符串；解析失败时返回原字符串
     */
    public static String replaceBatchByWildcard(String jsonBody, Map<String, Object> replacements) {
        if (replacements == null || replacements.isEmpty()) {
            return jsonBody;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(jsonBody);
            for (Map.Entry<String, Object> entry : replacements.entrySet()) {
                String path = entry.getKey();
                Object rawValue = entry.getValue();
                try {
                    List<PathSegment> segments = parseWildcardPath(path);
                    if (segments.isEmpty()) {
                        LOGGER.warn("[ModifyHandler] Empty path after parsing: '{}'", path);
                        continue;
                    }
                    // ⭐ 将 Object 转为 JsonNode 直接设置（不再做类型推断）
                    JsonNode typedValue = rawValueToJsonNode(rawValue);
                    Object[] typeHolder = new Object[1];
                    typeHolder[0] = typedValue;
                    int count = applyWildcardWithRawType(root, null, null, segments, 0, typedValue);
                    LOGGER.debug("[ModifyHandler] Wildcard replaced {} node(s) for path='{}', value='{}'",
                            count, path, rawValue);
                } catch (Exception e) {
                    LOGGER.warn("[ModifyHandler] Wildcard replace failed for path='{}': {}",
                            path, e.getMessage());
                }
            }
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            LOGGER.warn("[ModifyHandler] Batch wildcard replace failed: {}", e.getMessage());
            return jsonBody;
        }
    }

    /**
     * 将原始 Object 值转换为 JsonNode。
     * <p>支持 String、Number、Boolean、null、Collection（→ArrayNode）、
     * Map（→ObjectNode）、JsonNode（直接返回）。
     *
     * <p>支持空集合：{@code Collections.emptyList()} → {@code []}，
     * {@code Collections.emptyMap()} → {@code {}}。
     */
    private static JsonNode rawValueToJsonNode(Object value) {
        if (value == null) return NullNode.getInstance();
        if (value instanceof JsonNode) return (JsonNode) value;
        if (value instanceof String) return new TextNode((String) value);
        if (value instanceof Boolean) return BooleanNode.valueOf((Boolean) value);
        if (value instanceof Integer) return new IntNode((Integer) value);
        if (value instanceof Long) return new LongNode((Long) value);
        if (value instanceof Float || value instanceof Double) {
            return new DecimalNode(new BigDecimal(value.toString()));
        }
        if (value instanceof Number) {
            return new DecimalNode(new BigDecimal(value.toString()));
        }
        // ⭐ Collection → ArrayNode（支持空列表 []
        if (value instanceof Collection) {
            ArrayNode arr = OBJECT_MAPPER.createArrayNode();
            for (Object elem : (Collection<?>) value) {
                arr.add(rawValueToJsonNode(elem));
            }
            return arr;
        }
        // ⭐ Map → ObjectNode（支持空对象 {}）
        if (value instanceof Map) {
            ObjectNode obj = OBJECT_MAPPER.createObjectNode();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = entry.getKey() != null ? entry.getKey().toString() : "null";
                obj.set(key, rawValueToJsonNode(entry.getValue()));
            }
            return obj;
        }
        // ⭐ 数组 → ArrayNode
        if (value instanceof Object[]) {
            ArrayNode arr = OBJECT_MAPPER.createArrayNode();
            for (Object elem : (Object[]) value) {
                arr.add(rawValueToJsonNode(elem));
            }
            return arr;
        }
        // fallback: toString
        return new TextNode(value.toString());
    }

    /**
     * ⭐ 递归遍历 JSON 树，在通配符路径匹配的所有叶节点上直接设置 JsonNode 值（跳过类型推断）。
     */
    private static int applyWildcardWithRawType(JsonNode node, ObjectNode parent, String parentKey,
                                                 List<PathSegment> segments, int segIdx,
                                                 JsonNode typedValue) {
        if (segIdx >= segments.size() || node == null) return 0;
        PathSegment seg = segments.get(segIdx);
        boolean isLast = (segIdx == segments.size() - 1);

        if (seg.isWildcard()) {
            JsonNode arrayNode;
            if (seg.isRootWildcard()) {
                arrayNode = node;
            } else if (node instanceof ObjectNode) {
                arrayNode = ((ObjectNode) node).get(seg.fieldName);
            } else {
                return 0;
            }
            if (arrayNode instanceof ArrayNode) {
                int count = 0;
                ArrayNode arr = (ArrayNode) arrayNode;
                for (int i = 0; i < arr.size(); i++) {
                    JsonNode elem = arr.get(i);
                    if (isLast) {
                        arr.set(i, typedValue);
                        count++;
                    } else {
                        count += applyWildcardWithRawType(elem,
                                node instanceof ObjectNode ? (ObjectNode) node : parent,
                                seg.fieldName, segments, segIdx + 1, typedValue);
                    }
                }
                return count;
            }
            return 0;
        }

        // 精确导航
        JsonNode child;
        ObjectNode effectiveParent;
        String effectiveKey;
        if (node instanceof ArrayNode && seg.arrayIndex != null) {
            ArrayNode arr = (ArrayNode) node;
            if (seg.arrayIndex < 0 || seg.arrayIndex >= arr.size()) return 0;
            child = arr.get(seg.arrayIndex);
            effectiveParent = parent;
            effectiveKey = parentKey;
        } else if (node instanceof ObjectNode && seg.fieldName != null) {
            child = ((ObjectNode) node).get(seg.fieldName);
            if (child instanceof ArrayNode && seg.arrayIndex != null) {
                ArrayNode arr = (ArrayNode) child;
                if (seg.arrayIndex < 0 || seg.arrayIndex >= arr.size()) return 0;
                child = arr.get(seg.arrayIndex);
            }
            effectiveParent = (ObjectNode) node;
            effectiveKey = seg.fieldName;
        } else {
            return 0;
        }
        if (child == null) return 0;
        if (isLast) {
            if (effectiveParent != null && effectiveKey != null) {
                setJsonNode(effectiveParent, effectiveKey, typedValue);
                return 1;
            }
            return 0;
        }
        return applyWildcardWithRawType(child, effectiveParent, effectiveKey, segments, segIdx + 1, typedValue);
    }

    // ── 路径段数据结构 ────────────────────────────────────────────

    /**
     * 通配符路径解析后的一个路径段。
     * <p>例如 {@code users[*]} 解析为 fieldName="users", wildcard=true。
     */
    public static class PathSegment {
        final String fieldName;      // 字段名，root 通配符时为 null
        final boolean wildcard;      // 是否为 [*] 通配符
        final Integer arrayIndex;    // 精确数组索引，null 表示无索引或通配符

        PathSegment(String fieldName, boolean wildcard, Integer arrayIndex) {
            this.fieldName = fieldName;
            this.wildcard = wildcard;
            this.arrayIndex = arrayIndex;
        }

        boolean isWildcard() { return wildcard; }

        boolean isRootWildcard() { return fieldName == null && wildcard; }

        @Override
        public String toString() {
            if (fieldName == null && wildcard) return "[*]";
            if (wildcard) return fieldName + "[*]";
            if (arrayIndex != null) return fieldName + "[" + arrayIndex + "]";
            return fieldName != null ? fieldName : "";
        }
    }

    /**
     * 解析通配符路径为 PathSegment 列表。
     *
     * <p>解析规则：
     * <ul>
     *   <li>{@code $} 前缀被跳过</li>
     *   <li>{@code users[*]} → {fieldName="users", wildcard=true}</li>
     *   <li>{@code users[0]} → {fieldName="users", arrayIndex=0}</li>
     *   <li>{@code name} → {fieldName="name"}</li>
     *   <li>{@code [*]} 出现在段开头 → root 通配符</li>
     * </ul>
     */
    public static List<PathSegment> parseWildcardPath(String path) {
        List<PathSegment> segments = new ArrayList<>();
        // 去掉 JSONPath 根前缀
        String trimmed = path;
        if (trimmed.startsWith("$.")) {
            trimmed = trimmed.substring(2);
        } else if (trimmed.startsWith("$")) {
            trimmed = trimmed.substring(1);
        }

        if (trimmed.isEmpty()) return segments;

        String[] parts = trimmed.split("\\.");
        for (String part : parts) {
            if (part.isEmpty()) continue;

            int bracketIdx = part.indexOf('[');
            if (bracketIdx < 0) {
                // 普通字段名，无数组修饰
                segments.add(new PathSegment(part, false, null));
            } else {
                String field = part.substring(0, bracketIdx);
                String bracketContent = part.substring(bracketIdx + 1, part.indexOf(']'));
                if ("*".equals(bracketContent)) {
                    // 通配符 [*]
                    segments.add(new PathSegment(field.isEmpty() ? null : field, true, null));
                } else {
                    // 精确索引 [N]
                    try {
                        int idx = Integer.parseInt(bracketContent);
                        segments.add(new PathSegment(field.isEmpty() ? null : field, false, idx));
                    } catch (NumberFormatException e) {
                        LOGGER.debug("[ModifyHandler] Invalid array index '{}' in path '{}', treating as field",
                                bracketContent, path);
                        segments.add(new PathSegment(part, false, null));
                    }
                }
            }
        }
        return segments;
    }

    // ── 类型推断：找到第一个匹配节点的原值 ──────────────────────────

    /**
     * 递归查找第一个匹配通配符路径的节点的原始值，用于类型推断。
     */
    private static Object findFirstMatchingValue(JsonNode node, List<PathSegment> segments, int segIdx) {
        if (segIdx >= segments.size() || node == null) return null;
        PathSegment seg = segments.get(segIdx);
        boolean isLast = (segIdx == segments.size() - 1);

        if (seg.isWildcard()) {
            JsonNode arrayNode;
            if (seg.isRootWildcard()) {
                arrayNode = node;
            } else if (node instanceof ObjectNode) {
                arrayNode = ((ObjectNode) node).get(seg.fieldName);
            } else {
                return null;
            }

            if (arrayNode instanceof ArrayNode && !isLast) {
                for (JsonNode elem : (ArrayNode) arrayNode) {
                    Object found = findFirstMatchingValue(elem, segments, segIdx + 1);
                    if (found != null) return found;
                }
            }
            return null;
        }

        // 非通配符：精确导航
        JsonNode child;
        if (node instanceof ArrayNode && seg.arrayIndex != null) {
            ArrayNode arr = (ArrayNode) node;
            child = (seg.arrayIndex >= 0 && seg.arrayIndex < arr.size()) ? arr.get(seg.arrayIndex) : null;
        } else if (node instanceof ObjectNode && seg.fieldName != null) {
            child = ((ObjectNode) node).get(seg.fieldName);
            if (child instanceof ArrayNode && seg.arrayIndex != null) {
                ArrayNode arr = (ArrayNode) child;
                child = (seg.arrayIndex >= 0 && seg.arrayIndex < arr.size()) ? arr.get(seg.arrayIndex) : null;
            }
        } else {
            return null;
        }

        if (child == null) return null;
        if (isLast) return child;  // 返回原值用于类型匹配
        return findFirstMatchingValue(child, segments, segIdx + 1);
    }

    // ── 递归批量替换核心 ──────────────────────────────────────────

    /**
     * ⭐ #6 合并递归：单次遍历同时完成类型推断 + 替换。
     *
     * <p>与 {@link #applyWildcardRecursive} 的区别：
     * <ul>
     *   <li>接受原始字符串 {@code newValue} 而非预转换的 typed value</li>
     *   <li>在首次匹配叶节点时，根据现有值自动推断类型并缓存至 {@code typeHolder}</li>
     *   <li>后续匹配的叶节点复用已推断的类型，无需二次遍历</li>
     * </ul>
     *
     * @param node       当前 JSON 节点
     * @param parent     父 ObjectNode（可为 null）
     * @param parentKey  父节点中的 key
     * @param segments   路径段列表
     * @param segIdx     当前段索引
     * @param newValue   新值字符串（未类型转换）
     * @param typeHolder [0] = 已推断的类型值，null 表示尚未推断
     * @return 成功替换的节点数
     */
    private static int applyWildcardWithType(JsonNode node, ObjectNode parent, String parentKey,
                                              List<PathSegment> segments, int segIdx,
                                              String newValue, Object[] typeHolder) {
        if (segIdx >= segments.size() || node == null) return 0;
        PathSegment seg = segments.get(segIdx);
        boolean isLast = (segIdx == segments.size() - 1);

        if (seg.isWildcard()) {
            JsonNode arrayNode;
            ObjectNode arrayParent;
            String arrayKey;
            if (seg.isRootWildcard()) {
                arrayNode = node;
                arrayParent = parent;
                arrayKey = parentKey;
            } else if (node instanceof ObjectNode) {
                arrayNode = ((ObjectNode) node).get(seg.fieldName);
                arrayParent = (ObjectNode) node;
                arrayKey = seg.fieldName;
            } else {
                return 0;
            }

            if (arrayNode instanceof ArrayNode) {
                int count = 0;
                ArrayNode arr = (ArrayNode) arrayNode;
                for (int i = 0; i < arr.size(); i++) {
                    JsonNode elem = arr.get(i);
                    if (isLast) {
                        // ⭐ 叶节点：首次匹配时推断类型
                        if (typeHolder[0] == null) {
                            typeHolder[0] = convertToMatchingType(newValue, elem);
                        }
                        if (typeHolder[0] instanceof JsonNode) {
                            arr.set(i, (JsonNode) typeHolder[0]);
                            count++;
                        }
                    } else {
                        count += applyWildcardWithType(elem, arrayParent, arrayKey,
                                segments, segIdx + 1, newValue, typeHolder);
                    }
                }
                return count;
            }
            return 0;
        }

        // ── 非通配符：精确导航 ──
        JsonNode child;
        ObjectNode effectiveParent;
        String effectiveKey;

        if (node instanceof ArrayNode && seg.arrayIndex != null) {
            ArrayNode arr = (ArrayNode) node;
            if (seg.arrayIndex < 0 || seg.arrayIndex >= arr.size()) return 0;
            child = arr.get(seg.arrayIndex);
            effectiveParent = parent;
            effectiveKey = parentKey;
        } else if (node instanceof ObjectNode && seg.fieldName != null) {
            child = ((ObjectNode) node).get(seg.fieldName);
            if (child instanceof ArrayNode && seg.arrayIndex != null) {
                ArrayNode arr = (ArrayNode) child;
                if (seg.arrayIndex < 0 || seg.arrayIndex >= arr.size()) return 0;
                child = arr.get(seg.arrayIndex);
            }
            effectiveParent = (ObjectNode) node;
            effectiveKey = seg.fieldName;
        } else {
            return 0;
        }

        if (child == null) return 0;

        if (isLast) {
            // ⭐ 叶节点：首次匹配时推断类型
            if (effectiveParent != null && effectiveKey != null) {
                if (typeHolder[0] == null) {
                    typeHolder[0] = convertToMatchingType(newValue, child);
                }
                setJsonNode(effectiveParent, effectiveKey, typeHolder[0]);
                return 1;
            }
            return 0;
        }

        return applyWildcardWithType(child, effectiveParent, effectiveKey,
                segments, segIdx + 1, newValue, typeHolder);
    }

    /**
     * 递归遍历 JSON 树，在通配符路径匹配的所有叶节点上设置值。
     *
     * @param node     当前 JSON 节点
     * @param parent   当前节点的父 ObjectNode（用于最终设值）；root 层级可为 null
     * @param parentKey 当前节点在其父 ObjectNode 中的 key
     * @param segments 路径段列表
     * @param segIdx   当前处理到的段索引
     * @param value    要设置的值（已处理好类型）
     * @return 成功替换的节点数
     */
    private static int applyWildcardRecursive(JsonNode node, ObjectNode parent, String parentKey,
                                               List<PathSegment> segments, int segIdx, Object value) {
        if (segIdx >= segments.size() || node == null) return 0;
        PathSegment seg = segments.get(segIdx);
        boolean isLast = (segIdx == segments.size() - 1);

        if (seg.isWildcard()) {
            // ── 通配符：定位到数组，迭代每个元素 ──
            JsonNode arrayNode;
            ObjectNode arrayParent;
            String arrayKey;
            if (seg.isRootWildcard()) {
                arrayNode = node;
                arrayParent = parent;
                arrayKey = parentKey;
            } else if (node instanceof ObjectNode) {
                arrayNode = ((ObjectNode) node).get(seg.fieldName);
                arrayParent = (ObjectNode) node;
                arrayKey = seg.fieldName;
            } else {
                return 0;
            }

            if (arrayNode instanceof ArrayNode) {
                int count = 0;
                ArrayNode arr = (ArrayNode) arrayNode;
                for (int i = 0; i < arr.size(); i++) {
                    JsonNode elem = arr.get(i);
                    if (isLast) {
                        // 路径如 $.users[*] — 替换整个数组元素
                        if (value instanceof JsonNode) {
                            arr.set(i, (JsonNode) value);
                            count++;
                        }
                    } else {
                        // 继续递归到下一层
                        count += applyWildcardRecursive(elem, arrayParent, arrayKey, segments, segIdx + 1, value);
                    }
                }
                return count;
            }
            return 0;
        }

        // ── 非通配符：精确导航到子节点 ──
        JsonNode child;
        ObjectNode effectiveParent;
        String effectiveKey;

        if (node instanceof ArrayNode && seg.arrayIndex != null) {
            ArrayNode arr = (ArrayNode) node;
            if (seg.arrayIndex < 0 || seg.arrayIndex >= arr.size()) return 0;
            child = arr.get(seg.arrayIndex);
            effectiveParent = parent;
            effectiveKey = parentKey;
        } else if (node instanceof ObjectNode && seg.fieldName != null) {
            child = ((ObjectNode) node).get(seg.fieldName);
            if (child instanceof ArrayNode && seg.arrayIndex != null) {
                ArrayNode arr = (ArrayNode) child;
                if (seg.arrayIndex < 0 || seg.arrayIndex >= arr.size()) return 0;
                child = arr.get(seg.arrayIndex);
            }
            effectiveParent = (ObjectNode) node;
            effectiveKey = seg.fieldName;
        } else {
            return 0;
        }

        if (child == null) return 0;

        if (isLast) {
            // 到达最终字段：在 effectiveParent 上设置值
            if (effectiveParent != null && effectiveKey != null) {
                setJsonNode(effectiveParent, effectiveKey, value);
                return 1;
            }
            return 0;
        }

        return applyWildcardRecursive(child, effectiveParent, effectiveKey, segments, segIdx + 1, value);
    }

    // ═══════════════════════════════════════════════════════════════
    // 从字段 Map 构建 JSON（用于 Mock 场景未设 mockBody 但有 replaceFields）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从字段路径→值映射构建 JSON 字符串。
     *
     * <p>当 Mock 场景只设置了 {@code mockReplaceField()} 但未设置 {@code mockBody()} 时，
     * 直接用字段构建响应体，无需预先提供模板 body。
     *
     * <p>支持：
     * <ul>
     *   <li>顶层字段：{@code "flag" → false} → {@code {"flag":false}}</li>
     *   <li>嵌套字段：{@code "data.name" → "Alice"} → {@code {"data":{"name":"Alice"}}}</li>
     *   <li>带数组索引：{@code "items[0].id" → 1} → {@code {"items":[{"id":1}]}}</li>
     *   <li>多种值类型：String / Integer / Double / Boolean / null</li>
     * </ul>
     *
     * @param fields 字段路径 → 值映射
     * @return JSON 字符串
     */
    public static String buildJsonFromFieldMap(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return "{}";
        }
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String path = entry.getKey();
            JsonNode value = rawValueToJsonNode(entry.getValue());
            setNodeOnNewTree(root, path, value);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            LOGGER.warn("[ModifyHandler] Failed to serialize body from fields: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 在新建的 ObjectNode 树上按路径设置值（自动创建中间 ObjectNode）。
     * 与 {@link #setNodeByPath} 不同：路径不存在时不会跳过，而是自动创建中间节点。
     */
    private static void setNodeOnNewTree(ObjectNode root, String path, JsonNode value) {
        String[] segments = path.split("\\.");
        JsonNode current = root;

        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if ("$".equals(segment) || segment.isEmpty()) continue;

            boolean isLast = (i == segments.length - 1);
            int bracketIdx = segment.indexOf('[');
            String fieldName;
            Integer arrayIndex = null;
            if (bracketIdx > 0) {
                fieldName = segment.substring(0, bracketIdx);
                String idxStr = segment.substring(bracketIdx + 1, segment.indexOf(']'));
                try { arrayIndex = Integer.parseInt(idxStr); } catch (NumberFormatException e) { /* ignore */ }
            } else {
                fieldName = segment;
            }
            if (fieldName.isEmpty()) continue;

            if (current instanceof ObjectNode) {
                ObjectNode obj = (ObjectNode) current;
                if (isLast) {
                    if (arrayIndex != null) {
                        // 数组索引：确保存在数组并在指定位置设值
                        JsonNode existing = obj.get(fieldName);
                        ArrayNode arr;
                        if (existing instanceof ArrayNode) {
                            arr = (ArrayNode) existing;
                        } else {
                            arr = OBJECT_MAPPER.createArrayNode();
                            obj.set(fieldName, arr);
                        }
                        while (arr.size() <= arrayIndex) arr.add(NullNode.getInstance());
                        arr.set(arrayIndex, value);
                    } else {
                        setJsonNode(obj, fieldName, value);
                    }
                } else {
                    JsonNode child = obj.get(fieldName);
                    if (child instanceof ArrayNode && arrayIndex != null) {
                        ArrayNode arr = (ArrayNode) child;
                        while (arr.size() <= arrayIndex) arr.add(OBJECT_MAPPER.createObjectNode());
                        current = arr.get(arrayIndex);
                    } else if (child instanceof ObjectNode) {
                        current = child;
                    } else {
                        ObjectNode newChild = OBJECT_MAPPER.createObjectNode();
                        obj.set(fieldName, newChild);
                        current = newChild;
                    }
                }
            }
        }
    }
}
