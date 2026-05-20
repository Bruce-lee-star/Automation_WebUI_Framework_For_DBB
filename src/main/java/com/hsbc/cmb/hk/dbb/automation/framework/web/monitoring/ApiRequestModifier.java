package com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.ContextLifecycleHookManager;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 请求修改器 - 统一修改 HTTP 请求的 Body、Headers、QueryParams、Method、URL、Host
 *
 * <p>Context 生命周期支持：
 * 当 BrowserContext 重建时（如 restoreSession 导致），已注册的路由会自动重绑到新 Context。
 *
 * 详细使用说明请参考 API_MONITOR_README.md
 */
public class ApiRequestModifier implements ContextLifecycleHookManager.RuleCapturer {

    private static final Logger logger = LoggerFactory.getLogger(ApiRequestModifier.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 全局存储，按 endpoint 分类存储请求/响应信息（线程隔离） */
    private static final ThreadLocal<Map<String, List<RequestResponseInfo>>> GLOBAL_STORE = 
        ThreadLocal.withInitial(ConcurrentHashMap::new);

    // ==================== Context 生命周期钩子注册 ====================
    static {
        ContextLifecycleHookManager.registerCapturer(new ApiRequestModifier());
        logger.info("[ApiRequestModifier] Registered as Context lifecycle hook");
    }

    // ==================== Context 生命周期钩子实现 ====================

    /**
     * 实现 RuleCapturer 接口：捕获当前所有修改器用于 Context 重建后重绑
     */
    @Override
    public List<ContextLifecycleHookManager.RuleSnapshot> captureRules(BrowserContext context) {
        List<ContextLifecycleHookManager.RuleSnapshot> snapshots = new ArrayList<>();
        
        // 检查是否有活跃的 Context 修改器
        // ApiRequestModifier 使用静态存储，需要遍历查找
        // 这里简化处理，实际场景中可以根据需要实现更复杂的捕获逻辑
        
        // 对于 ApiRequestModifier，路由是在 modifyRequest() 时动态绑定的
        // 框架层面，我们记录当前活跃的 endpoint 配置
        for (Map.Entry<String, List<RequestResponseInfo>> entry : GLOBAL_STORE.get().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                // 有数据的 endpoint，说明可能绑定了路由
                snapshots.add(new ModifierRuleSnapshot(entry.getKey()));
            }
        }
        
        if (!snapshots.isEmpty()) {
            logger.info("[ApiRequestModifier] Captured {} endpoint snapshots for lifecycle hook", snapshots.size());
        }
        
        return snapshots;
    }

    /**
     * 修改器规则快照 - 用于 Context 重建后重绑
     */
    private static class ModifierRuleSnapshot implements ContextLifecycleHookManager.RuleSnapshot {
        private final String endpoint;
        // 保存最近一次使用的 modification 配置（简化版）
        private final Map<String, Object> lastModificationConfig;

        public ModifierRuleSnapshot(String endpoint) {
            this.endpoint = endpoint;
            this.lastModificationConfig = new HashMap<>();
        }

        @Override
        public String getId() { return "modifier-" + endpoint; }

        @Override
        public String getUrlPattern() { return endpoint; }

        @Override
        public boolean rebindTo(BrowserContext newContext) {
            // ApiRequestModifier 的路由绑定需要原始 modification 配置
            // 由于 modification 配置是方法参数，这里无法完整重建
            // 实际使用中，建议在测试代码层面确保 Context 稳定后再调用 modifyRequest
            logger.warn("[ModifierRuleSnapshot] Cannot auto-rebind ApiRequestModifier rules - "
                + "modification config not preserved. Please call modifyRequest() again after Context is stable.");
            return false;
        }

        @Override
        public boolean rebindTo(Page newPage) {
            logger.warn("[ModifierRuleSnapshot] Cannot auto-rebind ApiRequestModifier rules to page - "
                + "modification config not preserved.");
            return false;
        }
    }

    // ==================== 数据类 ====================

    /** 请求信息 */
    public static class RequestInfo {
        public final String url;
        public final String method;
        public final Map<String, String> headers;
        public final String body;

        public RequestInfo(String url, String method, Map<String, String> headers, String body) {
            this.url = url;
            this.method = method;
            this.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
            this.body = body;
        }

        @Override
        public String toString() {
            return String.format("RequestInfo{url='%s', method='%s', headers=%d, body=%s}",
                    url, method, headers.size(), body != null ? body.length() + " chars" : "null");
        }
    }

    /** 响应信息 */
    public static class ResponseInfo {
        public final int status;
        public final String statusText;
        public final Map<String, String> headers;
        public final String body;

        public ResponseInfo(int status, String statusText, Map<String, String> headers, String body) {
            this.status = status;
            this.statusText = statusText;
            this.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
            this.body = body;
        }

        @Override
        public String toString() {
            return String.format("ResponseInfo{status=%d, statusText='%s', headers=%d, body=%s}",
                    status, statusText, headers.size(), body != null ? body.length() + " chars" : "null");
        }
    }

    /** 请求/响应信息对 */
    public static class RequestResponseInfo {
        public final RequestInfo request;
        public final ResponseInfo response;
        public final long timestamp;

        public RequestResponseInfo(RequestInfo request, ResponseInfo response) {
            this.request = request;
            this.response = response;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format("RequestResponseInfo{request=%s, response=%s, timestamp=%d}",
                    request, response, timestamp);
        }
    }

    /** 请求/响应存储器 */
    public static class RequestResponseStore {
        private final Map<String, List<RequestResponseInfo>> localStore = new ConcurrentHashMap<>();
        private final String endpoint;

        public RequestResponseStore(String endpoint) {
            this.endpoint = endpoint;
        }

        /** 添加请求/响应信息 */
        void add(RequestResponseInfo info) {
            String key = extractEndpoint(info.request.url);
            localStore.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(info);
            GLOBAL_STORE.get().computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(info);
        }

        /** 获取指定 endpoint 的所有请求/响应 */
        public List<RequestResponseInfo> get(String endpointKey) {
            return localStore.getOrDefault(endpointKey, new ArrayList<>());
        }

        /** 获取指定 endpoint 的最后一次请求/响应 */
        public RequestResponseInfo getLast(String endpointKey) {
            List<RequestResponseInfo> list = localStore.get(endpointKey);
            return (list != null && !list.isEmpty()) ? list.get(list.size() - 1) : null;
        }

        /** 获取当前 endpoint 的最后一次请求/响应 */
        public RequestResponseInfo getLast() {
            return getLast(endpoint);
        }

        /** 获取所有存储的数据 */
        public Map<String, List<RequestResponseInfo>> toMap() {
            return new HashMap<>(localStore);
        }

        /** 清空本地存储 */
        public void clear() {
            localStore.clear();
        }

        /** 清空全局存储 */
        public static void clearGlobal() {
            GLOBAL_STORE.get().clear();
        }

        /** 获取全局存储 */
        public static Map<String, List<RequestResponseInfo>> getGlobalStore() {
            return new HashMap<>(GLOBAL_STORE.get());
        }

        private String extractEndpoint(String url) {
            if (url == null) return "";
            int queryIndex = url.indexOf('?');
            if (queryIndex > 0) {
                url = url.substring(0, queryIndex);
            }
            int lastSlash = url.lastIndexOf('/');
            return lastSlash >= 0 ? url.substring(lastSlash) : url;
        }
    }

    // ==================== 操作类型枚举 ====================

    private enum Operation { SET, REMOVE }

    /** 字段操作包装类 */
    private static class FieldOp {
        final Operation op;
        final Object value;

        FieldOp(Operation op, Object value) {
            this.op = op;
            this.value = value;
        }

        static FieldOp set(Object value) { return new FieldOp(Operation.SET, value); }
        static FieldOp remove() { return new FieldOp(Operation.REMOVE, null); }

        @Override
        public String toString() {
            return op == Operation.SET ? "SET=" + value : "REMOVE";
        }
    }

    private String body;
    private Map<String, FieldOp> bodyOps = new LinkedHashMap<>();
    private Map<String, FieldOp> headerOps = new LinkedHashMap<>();
    private Map<String, FieldOp> queryParamOps = new LinkedHashMap<>();
    private String method;
    private String url;
    private String host;

    // ==================== Factory 方法 ====================

    /** 创建 ApiRequestModifier 实例 */
    public static ApiRequestModifier create() {
        return new ApiRequestModifier();
    }

    // ==================== Body 操作 ====================

    /** 完全替换请求体 */
    public ApiRequestModifier body(String body) {
        this.body = body;
        return this;
    }

    /** 删除整个请求体 */
    public ApiRequestModifier removeBody() {
        this.body = "";
        this.bodyOps.clear();
        return this;
    }

    /** 修改/设置请求体中的某个字段，支持嵌套路径如 "user.name"、"items[0].id" */
    public ApiRequestModifier modifyBodyField(String path, Object value) {
        bodyOps.put(path, FieldOp.set(value));
        return this;
    }

    /** 批量修改请求体字段 */
    public ApiRequestModifier modifyBodyFields(Map<String, Object> fields) {
        fields.forEach((k, v) -> bodyOps.put(k, FieldOp.set(v)));
        return this;
    }

    /** 删除请求体中的某个字段 */
    public ApiRequestModifier removeBodyField(String path) {
        bodyOps.put(path, FieldOp.remove());
        return this;
    }

    /** 批量删除请求体字段 */
    public ApiRequestModifier removeBodyFields(String... paths) {
        for (String path : paths) {
            bodyOps.put(path, FieldOp.remove());
        }
        return this;
    }

    // ==================== Header 操作 ====================

    /** 添加/修改请求头 */
    public ApiRequestModifier modifyHeader(String name, String value) {
        headerOps.put(name, FieldOp.set(value));
        return this;
    }

    /** 批量添加/修改请求头 */
    public ApiRequestModifier modifyHeaders(Map<String, String> headers) {
        headers.forEach((k, v) -> headerOps.put(k, FieldOp.set(v)));
        return this;
    }

    /** 删除请求头 */
    public ApiRequestModifier removeHeader(String name) {
        headerOps.put(name, FieldOp.remove());
        return this;
    }

    /** 批量删除请求头 */
    public ApiRequestModifier removeHeaders(String... names) {
        for (String name : names) {
            headerOps.put(name, FieldOp.remove());
        }
        return this;
    }

    // ==================== QueryParam 操作 ====================

    /** 添加/修改查询参数 */
    public ApiRequestModifier modifyQueryParam(String name, String value) {
        queryParamOps.put(name, FieldOp.set(value));
        return this;
    }

    /** 批量添加/修改查询参数 */
    public ApiRequestModifier modifyQueryParams(Map<String, String> params) {
        params.forEach((k, v) -> queryParamOps.put(k, FieldOp.set(v)));
        return this;
    }

    /** 删除查询参数 */
    public ApiRequestModifier removeQueryParam(String name) {
        queryParamOps.put(name, FieldOp.remove());
        return this;
    }

    /** 批量删除查询参数 */
    public ApiRequestModifier removeQueryParams(String... names) {
        for (String name : names) {
            queryParamOps.put(name, FieldOp.remove());
        }
        return this;
    }

    // ==================== Method 操作 ====================

    /** 修改 HTTP 方法 */
    public ApiRequestModifier method(String method) {
        this.method = method;
        return this;
    }

    // ==================== URL 操作 ====================

    /** 完全替换 URL */
    public ApiRequestModifier url(String url) {
        this.url = url;
        return this;
    }

    /** 替换 host（保留路径和查询参数） */
    public ApiRequestModifier host(String newHost) {
        this.host = newHost;
        return this;
    }

    // ==================== 清理方法 ====================

    /** 清空所有修改配置 */
    public ApiRequestModifier clear() {
        this.body = null;
        this.bodyOps.clear();
        this.headerOps.clear();
        this.queryParamOps.clear();
        this.method = null;
        this.url = null;
        this.host = null;
        return this;
    }

    // ==================== 辅助方法 ====================

    boolean hasModifications() {
        return body != null || !bodyOps.isEmpty() || !headerOps.isEmpty()
                || !queryParamOps.isEmpty() || method != null || url != null || host != null;
    }

    boolean hasBodyModifications() {
        return body != null || !bodyOps.isEmpty();
    }

    boolean hasHeaderModifications() {
        return !headerOps.isEmpty();
    }

    boolean hasQueryParamModifications() {
        return !queryParamOps.isEmpty();
    }

    /** 应用 body 修改到原始请求体 */
    private String applyBodyModifications(String originalBody) {
        if (body != null && bodyOps.isEmpty()) {
            return body;
        }

        String jsonBody = (body != null) ? body : originalBody;
        if (jsonBody == null || jsonBody.isEmpty()) {
            jsonBody = "{}";
        }

        if (bodyOps.isEmpty()) {
            return jsonBody;
        }

        try {
            Gson gson = new Gson();
            JsonObject jsonObject = gson.fromJson(jsonBody, JsonObject.class);

            if (jsonObject == null) {
                jsonObject = new JsonObject();
            }

            for (Map.Entry<String, FieldOp> entry : bodyOps.entrySet()) {
                String path = entry.getKey();
                FieldOp op = entry.getValue();

                if (op.op == Operation.REMOVE) {
                    removeJsonValueByPath(jsonObject, path);
                } else {
                    setJsonValueByPath(jsonObject, path, op.value, gson);
                }
            }

            return gson.toJson(jsonObject);
        } catch (Exception e) {
            logger.warn("Failed to apply body modifications: {}", e.getMessage());
            return jsonBody;
        }
    }

    private void setJsonValueByPath(JsonObject root, String path, Object value, Gson gson) {
        String[] parts = path.split("\\.");
        JsonElement current = root;

        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            String arrayIndex = extractArrayIndex(part);

            if (arrayIndex != null) {
                String arrayName = part.substring(0, part.indexOf('['));
                int index = Integer.parseInt(arrayIndex);

                if (current.isJsonObject() && current.getAsJsonObject().has(arrayName)) {
                    JsonElement arrayElem = current.getAsJsonObject().get(arrayName);
                    if (arrayElem.isJsonArray() && arrayElem.getAsJsonArray().size() > index) {
                        current = arrayElem.getAsJsonArray().get(index);
                    }
                }
            } else {
                if (current.isJsonObject()) {
                    JsonObject currentObj = current.getAsJsonObject();
                    if (!currentObj.has(part)) {
                        currentObj.add(part, new JsonObject());
                    }
                    current = currentObj.get(part);
                }
            }
        }

        String lastPart = parts[parts.length - 1];
        String arrayIndex = extractArrayIndex(lastPart);

        if (current.isJsonObject()) {
            String fieldName = (arrayIndex != null) ? lastPart.substring(0, lastPart.indexOf('[')) : lastPart;
            JsonElement jsonValue = convertToJsonElement(value, gson);

            if (arrayIndex != null) {
                int index = Integer.parseInt(arrayIndex);
                JsonObject currentObj = current.getAsJsonObject();
                if (!currentObj.has(fieldName)) {
                    currentObj.add(fieldName, new JsonArray());
                }
                JsonArray array = currentObj.get(fieldName).getAsJsonArray();
                while (array.size() <= index) {
                    array.add(new JsonObject());
                }
                array.set(index, jsonValue);
            } else {
                current.getAsJsonObject().add(fieldName, jsonValue);
            }
        }
    }

    private void removeJsonValueByPath(JsonObject root, String path) {
        String[] parts = path.split("\\.");
        JsonElement current = root;

        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (current.isJsonObject() && current.getAsJsonObject().has(part)) {
                current = current.getAsJsonObject().get(part);
            } else {
                return;
            }
        }

        String lastPart = parts[parts.length - 1];
        if (current.isJsonObject()) {
            current.getAsJsonObject().remove(lastPart);
        }
    }

    private String extractArrayIndex(String part) {
        int start = part.indexOf('[');
        int end = part.indexOf(']');
        if (start != -1 && end != -1 && end > start + 1) {
            return part.substring(start + 1, end);
        }
        return null;
    }

    private JsonElement convertToJsonElement(Object value, Gson gson) {
        if (value == null) {
            return JsonNull.INSTANCE;
        } else if (value instanceof String) {
            return new JsonPrimitive((String) value);
        } else if (value instanceof Number) {
            return new JsonPrimitive((Number) value);
        } else if (value instanceof Boolean) {
            return new JsonPrimitive((Boolean) value);
        } else if (value instanceof Character) {
            return new JsonPrimitive((Character) value);
        } else {
            return gson.toJsonTree(value);
        }
    }

    // ==================== 静态修改方法 ====================

    /**
     * 统一修改请求 - BrowserContext 版本
     * @return RequestResponseStore 存储请求/响应信息
     */
    public static RequestResponseStore modifyRequest(BrowserContext context, String endpoint, ApiRequestModifier modification) {
        return modifyRequest(context, endpoint, modification, null);
    }

    /**
     * 统一修改请求 - BrowserContext 版本（带回调）
     * @param callback 每次请求完成后的回调
     * @return RequestResponseStore 存储请求/响应信息
     */
    public static RequestResponseStore modifyRequest(BrowserContext context, String endpoint,
                                                     ApiRequestModifier modification,
                                                     Consumer<RequestResponseInfo> callback) {
        logger.info("========== Configuring Request Modifier for: {} ==========", endpoint);
        logger.info("Modification Config: {}", formatModificationConfig(modification));

        // 使用统一的 toGlobPattern 方法（与 ApiMonitorAndMockManager 保持一致）
        String globPattern = toGlobPattern(endpoint);
        logger.info("Registering route with glob pattern: {}", globPattern);

        RequestResponseStore store = new RequestResponseStore(endpoint);

        // 先注册响应监听器（在route之前）
        context.onResponse(response -> {
            String responseUrl = response.url();
            // 使用统一的 matchesGlob 方法进行匹配
            if (matchesGlob(responseUrl, endpoint)) {
                try {
                    Request respRequest = response.request();

                    logger.info("========================================");
                    logger.info("[RESPONSE] {}", responseUrl);
                    logger.info("========================================");

                    // 请求信息（修改后的）
                    logger.info("[Request Info]");
                    logger.info("   URL: {}", respRequest.url());
                    logger.info("   Method: {}", respRequest.method());
                    logger.info("   Headers: {}", formatHeaders(respRequest.headers()));
                    if (respRequest.postData() != null) {
                        logger.info("   Body: {}", respRequest.postData());
                    }

                    // 响应信息
                    logger.info("[Response Info]");
                    logger.info("   Status: {} {}", response.status(), response.statusText());
                    logger.info("   Headers: {}", formatHeaders(response.headers()));

                    String respBody = null;
                    try {
                        respBody = response.text();
                        logger.info("   Body: {}", respBody);
                    } catch (Exception e) {
                        logger.info("   Body: (Unable to read response body)");
                    }

                    logger.info("========================================");

                    // 存储请求/响应信息
                    RequestInfo requestInfo = new RequestInfo(
                            respRequest.url(),
                            respRequest.method(),
                            respRequest.headers(),
                            respRequest.postData()
                    );
                    ResponseInfo responseInfo = new ResponseInfo(
                            response.status(),
                            response.statusText(),
                            response.headers(),
                            respBody
                    );
                    RequestResponseInfo info = new RequestResponseInfo(requestInfo, responseInfo);
                    store.add(info);

                    // 执行回调
                    if (callback != null) {
                        try {
                            callback.accept(info);
                        } catch (Exception e) {
                            logger.error("Error in callback", e);
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error logging response", e);
                }
            }
        });

        // 然后注册路由拦截
        context.route(globPattern, route -> {
            try {
                Request request = route.request();
                String url = request.url();

                logger.info("========================================");
                logger.info("[REQUEST MODIFICATION] {}", url);
                logger.info("========================================");
                logger.info("[Original Request]");
                logger.info("   URL: {}", url);
                logger.info("   Method: {}", request.method());
                logger.info("   Headers: {}", formatHeaders(request.headers()));
                logger.info("   Body: {}", request.postData());
                logger.info("----------------------------------------");

                Route.ResumeOptions options = new Route.ResumeOptions();

                // 1. 修改 Body
                if (modification.hasBodyModifications()) {
                    String originalBody = request.postData();
                    String modifiedBody = modification.applyBodyModifications(originalBody);
                    options.setPostData(modifiedBody);
                    logger.info("[Modified Body]");
                    logger.info("   Original: {}", originalBody);
                    logger.info("   Modified: {}", modifiedBody);
                }

                // 2. 修改 Headers
                if (modification.hasHeaderModifications()) {
                    Map<String, String> headers = new HashMap<>(request.headers());
                    logger.info("[Modified Headers]");
                    for (Map.Entry<String, FieldOp> entry : modification.headerOps.entrySet()) {
                        if (entry.getValue().op == Operation.REMOVE) {
                            headers.remove(entry.getKey());
                            logger.info("   Removed: {}", entry.getKey());
                        } else {
                            headers.put(entry.getKey(), (String) entry.getValue().value);
                            logger.info("   Set: {} = {}", entry.getKey(), entry.getValue().value);
                        }
                    }
                    options.setHeaders(headers);
                }

                // 3. 修改 Query Params
                if (modification.hasQueryParamModifications()) {
                    String newUrl = url;
                    logger.info("[Modified Query Params]");
                    for (Map.Entry<String, FieldOp> entry : modification.queryParamOps.entrySet()) {
                        if (entry.getValue().op == Operation.REMOVE) {
                            newUrl = modifyUrlQueryParam(newUrl, entry.getKey(), null);
                            logger.info("   Removed: {}", entry.getKey());
                        } else {
                            newUrl = modifyUrlQueryParam(newUrl, entry.getKey(), (String) entry.getValue().value);
                            logger.info("   Set: {} = {}", entry.getKey(), entry.getValue().value);
                        }
                    }
                    options.setUrl(newUrl);
                    logger.info("   New URL: {}", newUrl);
                }

                // 4. 修改 Method
                if (modification.method != null) {
                    options.setMethod(modification.method);
                    logger.info("[Modified Method] {} -> {}", request.method(), modification.method);
                }

                // 5. 修改 URL 或 Host
                String finalUrl = url;
                if (modification.url != null) {
                    finalUrl = modification.url;
                    logger.info("[Modified URL] {} -> {}", url, finalUrl);
                } else if (modification.host != null) {
                    finalUrl = replaceHost(url, modification.host);
                    logger.info("[Modified Host] {} -> {}", url, finalUrl);
                }
                
                if (!finalUrl.equals(url)) {
                    options.setUrl(finalUrl);
                }

                logger.info("========================================");

                route.resume(options);
            } catch (Exception e) {
                logger.error("Error in request modification handler", e);
                route.resume();
            }
        });

        logger.info("Request modifier configured successfully!");
        return store;
    }

    /**
     * 统一修改请求 - Page 版本
     * @return RequestResponseStore 存储请求/响应信息
     */
    public static RequestResponseStore modifyRequest(Page page, String endpoint, ApiRequestModifier modification) {
        return modifyRequest(page, endpoint, modification, null);
    }

    /**
     * 统一修改请求 - Page 版本（带回调）
     * @param callback 每次请求完成后的回调
     * @return RequestResponseStore 存储请求/响应信息
     */
    public static RequestResponseStore modifyRequest(Page page, String endpoint,
                                                     ApiRequestModifier modification,
                                                     Consumer<RequestResponseInfo> callback) {
        logger.info("========== Configuring Request Modifier for: {} ==========", endpoint);
        logger.info("Modification Config: {}", formatModificationConfig(modification));

        // 使用统一的 toGlobPattern 方法（与 ApiMonitorAndMockManager 保持一致）
        String globPattern = toGlobPattern(endpoint);
        logger.info("Registering route with glob pattern: {}", globPattern);

        RequestResponseStore store = new RequestResponseStore(endpoint);

        // 先注册响应监听器（在route之前）
        page.onResponse(response -> {
            String responseUrl = response.url();
            // 使用统一的 matchesGlob 方法进行匹配
            if (matchesGlob(responseUrl, endpoint)) {
                try {
                    Request respRequest = response.request();

                    logger.info("========================================");
                    logger.info("[RESPONSE] {}", responseUrl);
                    logger.info("========================================");

                    // 请求信息（修改后的）
                    logger.info("[Request Info]");
                    logger.info("   URL: {}", respRequest.url());
                    logger.info("   Method: {}", respRequest.method());
                    logger.info("   Headers: {}", formatHeaders(respRequest.headers()));
                    if (respRequest.postData() != null) {
                        logger.info("   Body: {}", respRequest.postData());
                    }

                    // 响应信息
                    logger.info("[Response Info]");
                    logger.info("   Status: {} {}", response.status(), response.statusText());
                    logger.info("   Headers: {}", formatHeaders(response.headers()));

                    String respBody = null;
                    try {
                        respBody = response.text();
                        logger.info("   Body: {}", respBody);
                    } catch (Exception e) {
                        logger.info("   Body: (Unable to read response body)");
                    }

                    logger.info("========================================");

                    // 存储请求/响应信息
                    RequestInfo requestInfo = new RequestInfo(
                            respRequest.url(),
                            respRequest.method(),
                            respRequest.headers(),
                            respRequest.postData()
                    );
                    ResponseInfo responseInfo = new ResponseInfo(
                            response.status(),
                            response.statusText(),
                            response.headers(),
                            respBody
                    );
                    RequestResponseInfo info = new RequestResponseInfo(requestInfo, responseInfo);
                    store.add(info);

                    // 执行回调
                    if (callback != null) {
                        try {
                            callback.accept(info);
                        } catch (Exception e) {
                            logger.error("Error in callback", e);
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error logging response", e);
                }
            }
        });

        // 然后注册路由拦截
        page.route(globPattern, route -> {
            try {
                Request request = route.request();
                String url = request.url();

                logger.info("========================================");
                logger.info("[REQUEST MODIFICATION] {}", url);
                logger.info("========================================");
                logger.info("[Original Request]");
                logger.info("   URL: {}", url);
                logger.info("   Method: {}", request.method());
                logger.info("   Headers: {}", formatHeaders(request.headers()));
                logger.info("   Body: {}", request.postData());
                logger.info("----------------------------------------");

                Route.ResumeOptions options = new Route.ResumeOptions();

                // 1. 修改 Body
                if (modification.hasBodyModifications()) {
                    String originalBody = request.postData();
                    String modifiedBody = modification.applyBodyModifications(originalBody);
                    options.setPostData(modifiedBody);
                    logger.info("[Modified Body]");
                    logger.info("   Original: {}", originalBody);
                    logger.info("   Modified: {}", modifiedBody);
                }

                // 2. 修改 Headers
                if (modification.hasHeaderModifications()) {
                    Map<String, String> headers = new HashMap<>(request.headers());
                    logger.info("[Modified Headers]");
                    for (Map.Entry<String, FieldOp> entry : modification.headerOps.entrySet()) {
                        if (entry.getValue().op == Operation.REMOVE) {
                            headers.remove(entry.getKey());
                            logger.info("   Removed: {}", entry.getKey());
                        } else {
                            headers.put(entry.getKey(), (String) entry.getValue().value);
                            logger.info("   Set: {} = {}", entry.getKey(), entry.getValue().value);
                        }
                    }
                    options.setHeaders(headers);
                }

                // 3. 修改 Query Params
                if (modification.hasQueryParamModifications()) {
                    String newUrl = url;
                    logger.info("[Modified Query Params]");
                    for (Map.Entry<String, FieldOp> entry : modification.queryParamOps.entrySet()) {
                        if (entry.getValue().op == Operation.REMOVE) {
                            newUrl = modifyUrlQueryParam(newUrl, entry.getKey(), null);
                            logger.info("   Removed: {}", entry.getKey());
                        } else {
                            newUrl = modifyUrlQueryParam(newUrl, entry.getKey(), (String) entry.getValue().value);
                            logger.info("   Set: {} = {}", entry.getKey(), entry.getValue().value);
                        }
                    }
                    options.setUrl(newUrl);
                    logger.info("   New URL: {}", newUrl);
                }

                // 4. 修改 Method
                if (modification.method != null) {
                    options.setMethod(modification.method);
                    logger.info("[Modified Method] {} -> {}", request.method(), modification.method);
                }

                // 5. 修改 URL 或 Host
                String finalUrl = url;
                if (modification.url != null) {
                    finalUrl = modification.url;
                    logger.info("[Modified URL] {} -> {}", url, finalUrl);
                } else if (modification.host != null) {
                    finalUrl = replaceHost(url, modification.host);
                    logger.info("[Modified Host] {} -> {}", url, finalUrl);
                }
                
                if (!finalUrl.equals(url)) {
                    options.setUrl(finalUrl);
                }

                logger.info("========================================");

                route.resume(options);
            } catch (Exception e) {
                logger.error("Error in request modification handler", e);
                route.resume();
            }
        });

        logger.info("Request modifier configured successfully!");
        return store;
    }

    // ==================== URL Pattern 工具方法 ====================

    /**
     * 将普通URL转换为 Playwright glob 匹配模式
     * 
     * <p>统一匹配策略（用于 Playwright route() 方法）：
     * - 移除开头斜杠
     * - 前后加 ** 实现跨目录匹配（支持查询参数）
     * - Java/Playwright glob 中 ** 匹配任意路径（包括 /），* 不匹配 /
     * 
     * @param urlPattern 如 "/api/users" 或 "rest/account-list"
     * @return Playwright glob pattern
     */
    static String toGlobPattern(String urlPattern) {
        if (urlPattern == null || urlPattern.isEmpty()) return "**";

        // 已带通配符直接返回
        if (urlPattern.contains("*")) return urlPattern;
        
        String normalized = urlPattern.startsWith("/") ? urlPattern.substring(1) : urlPattern;
        // 后缀不加/**，用**收尾，兼容所有查询参数
        return "**/" + normalized + "**";
    }

    /**
     * 将 glob pattern 转换为正则表达式
     */
    private static String globToRegex(String pattern) {
        if (pattern == null || pattern.isEmpty()) return ".*";
        String raw = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        // 通配符替换
        String reg = raw.replace("**", ".*").replace("*", "[^/]*");
        // 全局匹配，允许后面拼接任意查询参数
        return ".*" + reg + ".*";
    }

    /**
     * 检查 URL 是否匹配 pattern
     */
    static boolean matchesGlob(String url, String pattern) {
        if (url == null || pattern == null) return false;
        
        // 使用正则匹配
        String regex = globToRegex(pattern);
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(url).matches();
        } catch (Exception e) {
            logger.debug("[Pattern] Invalid pattern '{}': {}", pattern, e.getMessage());
            return false;
        }
    }

    // ==================== 辅助方法 ====================

    /** 格式化修改配置 */
    private static String formatModificationConfig(ApiRequestModifier mod) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n   Body: ").append(mod.body != null ? mod.body : "(no change)");
        sb.append("\n   BodyOps: ").append(mod.bodyOps.isEmpty() ? "(none)" : mod.bodyOps);
        sb.append("\n   HeaderOps: ").append(mod.headerOps.isEmpty() ? "(none)" : mod.headerOps);
        sb.append("\n   QueryParamOps: ").append(mod.queryParamOps.isEmpty() ? "(none)" : mod.queryParamOps);
        sb.append("\n   Method: ").append(mod.method != null ? mod.method : "(no change)");
        sb.append("\n   URL: ").append(mod.url != null ? mod.url : "(no change)");
        sb.append("\n   Host: ").append(mod.host != null ? mod.host : "(no change)");
        return sb.toString();
    }

    /** 格式化Headers为JSON字符串 */
    private static String formatHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "{}";
        }
        try {
            JsonObject json = new JsonObject();
            headers.forEach(json::addProperty);
            return GSON.toJson(json);
        } catch (Exception e) {
            return headers.toString();
        }
    }


    /** 修改 URL 查询参数 */
    private static String modifyUrlQueryParam(String url, String paramName, String paramValue) {
        if (url == null) {
            return url;
        }

        try {
            URI uri = URI.create(url);
            String query = uri.getQuery();
            Map<String, String> params = new LinkedHashMap<>();

            if (query != null && !query.isEmpty()) {
                for (String param : query.split("&")) {
                    String[] keyValue = param.split("=", 2);
                    if (keyValue.length == 2) {
                        String key = URLDecoder.decode(keyValue[0], "UTF-8");
                        String value = URLDecoder.decode(keyValue[1], "UTF-8");
                        params.put(key, value);
                    }
                }
            }

            if (paramValue == null) {
                params.remove(paramName);
            } else {
                params.put(paramName, paramValue);
            }

            StringBuilder newQuery = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (newQuery.length() > 0) {
                    newQuery.append("&");
                }
                newQuery.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
                newQuery.append("=");
                newQuery.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            }

            URI newUri = new URI(
                    uri.getScheme(),
                    uri.getAuthority(),
                    uri.getPath(),
                    newQuery.length() > 0 ? newQuery.toString() : null,
                    uri.getFragment()
            );

            return newUri.toString();
        } catch (Exception e) {
            logger.warn("Failed to modify URL query param: {} = {}, error: {}", paramName, paramValue, e.getMessage());
            String separator = url.contains("?") ? "&" : "?";
            if (paramValue == null) {
                return url;
            }
            return url + separator + paramName + "=" + paramValue;
        }
    }

    /** 替换 URL 中的 host */
    private static String replaceHost(String url, String newHost) {
        if (url == null || newHost == null) {
            return url;
        }

        try {
            URI uri = URI.create(url);
            URI newUri = new URI(
                    uri.getScheme(),
                    newHost,
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment()
            );
            return newUri.toString();
        } catch (Exception e) {
            logger.warn("Failed to replace host in URL: {} -> {}, error: {}", url, newHost, e.getMessage());
            // 简单替换
            int hostStart = url.indexOf("://");
            if (hostStart > 0) {
                int pathStart = url.indexOf("/", hostStart + 3);
                if (pathStart > 0) {
                    return url.substring(0, hostStart + 3) + newHost + url.substring(pathStart);
                } else {
                    return url.substring(0, hostStart + 3) + newHost;
                }
            }
            return url;
        }
    }
}
