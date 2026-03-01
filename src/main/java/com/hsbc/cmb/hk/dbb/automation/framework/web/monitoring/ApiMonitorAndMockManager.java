package com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.microsoft.playwright.*;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import net.serenitybdd.core.Serenity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * API Monitor and Mock Manager - 监测API响应、mock response和修改请求信息
 *
 * 功能：
 * 1. 监测API请求和响应
 * 2. Mock API响应
 * 3. 修改请求信息（headers、query parameters、request body、method等）
 * 4. 记录API调用历史
 * 5. 动态响应生成
 *
 * 使用方式（推荐使用Builder模式）：
 *
 * 【推荐】Builder模式 - Mock响应：
 *   ApiMonitorAndMockManager.mock(context)
 *       .forUrl(".*api/users.*")
 *       .withStatus(200)
 *       .withResponse("{\"status\":\"success\"}")
 *       .build();
 *
 * 【推荐】Builder模式 - 修改请求：
 *   ApiMonitorAndMockManager.mock(context)
 *       .forUrl(".*api/users.*")
 *       .withInterceptor((route, request) -> {
 *           return new Route.ResumeOptions()
 *               .setMethod("POST")
 *               .setPostData("{\"modified\":true}")
 *               .setHeaders(Map.of("X-Custom", "value"));
 *       })
 *       .build();
 *
 * 【简化】单API Mock - 一行代码：
 *   mockDirectResponse(context, "/api/users", 200, "{\"status\":\"success\"}");
 *   mockDirectSuccess(context, "/api/users", "{\"status\":\"success\"}");
 *   mockDirectError(context, "/api/users", 500, "{\"status\":\"error\"}");
 *
 * 【简化】修改请求 - 一行代码：
 *   modifyRequestHeader(page, "/api/users", "Authorization", "Bearer token");
 *   modifyRequestBody(context, "/api/users", "{\"new\":\"body\"}");
 *   modifyRequestQueryParam(page, "/api/users", "userId", "123");
 *   modifyRequestMethod(context, "/api/users", "POST");
 *
 * 【高级】动态Mock - 基于请求生成响应：
 *   mockDynamic(context, "/api/users", (request, ctx) -> {
 *       String userId = extractUserId(request.url());
 *       return "{\"id\":" + userId + "}";
 *   });
 *
 * 【高级】自定义请求拦截器 - 完全控制请求：
 *   interceptRequest(context, "/api/users", (route, request) -> {
 *       return new Route.ContinueOptions()
 *           .setMethod("POST")
 *           .setPostData("{\"modified\":true}")
 *           .setHeaders(Map.of("X-Custom", "value"));
 *   });
 */
public class ApiMonitorAndMockManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ApiMonitorAndMockManager.class);
    private static final Config config = ConfigFactory.load();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // 存储已注册的 Mock 规则
    private static final Map<String, MockRule> mockRules = new ConcurrentHashMap<>();
    // 存储API调用历史
    private static final List<ApiCallRecord> apiCallHistory = new CopyOnWriteArrayList<>();

    // 存储context或page引用，确保mock规则应用到同一个实例
    private static Page currentPage;
    private static BrowserContext currentContext;
    
    /**
     * API调用记录
     */
    public static class ApiCallRecord {
        private final String requestId;
        private final String url;
        private final String method;
        private final long timestamp;
        private final Map<String, String> requestHeaders;
        private final Object requestBody;
        private final int statusCode;
        private final Map<String, String> responseHeaders;
        private final Object responseBody;
        private final long responseTimeMs;
        private final boolean isMocked;
        
        public ApiCallRecord(String requestId, String url, String method, long timestamp, 
                          Map<String, String> requestHeaders, Object requestBody, 
                          int statusCode, Map<String, String> responseHeaders, 
                          Object responseBody, long responseTimeMs, boolean isMocked) {
            this.requestId = requestId;
            this.url = url;
            this.method = method;
            this.timestamp = timestamp;
            this.requestHeaders = requestHeaders;
            this.requestBody = requestBody;
            this.statusCode = statusCode;
            this.responseHeaders = responseHeaders;
            this.responseBody = responseBody;
            this.responseTimeMs = responseTimeMs;
            this.isMocked = isMocked;
        }
        
        public String getRequestId() { return requestId; }
        public String getUrl() { return url; }
        public String getMethod() { return method; }
        public long getTimestamp() { return timestamp; }
        public Map<String, String> getRequestHeaders() { return requestHeaders; }
        public Object getRequestBody() { return requestBody; }
        public int getStatusCode() { return statusCode; }
        public Map<String, String> getResponseHeaders() { return responseHeaders; }
        public Object getResponseBody() { return responseBody; }
        public long getResponseTimeMs() { return responseTimeMs; }
        public boolean isMocked() { return isMocked; }
    }
    
    /**
     * Mock规则
     */
    public static class MockRule {
        private String name;
        private String urlPattern;
        private String method; // GET, POST, etc.
        private String mockDataPath; // JSON文件路径
        private String mockDataJson; // 直接提供JSON字符串
        private int statusCode;
        private Map<String, String> headers;
        private long delayMs; // 模拟延迟
        private boolean enabled;
        private RequestModifier requestModifier; // 请求修改器（已废弃）
        private RequestInterceptor requestInterceptor; // 请求拦截器（新版）
        private ResponseGenerator responseGenerator; // 响应生成器
        
        public MockRule(String name, String urlPattern) {
            this.name = name;
            this.urlPattern = urlPattern;
            this.method = ".*"; // 默认匹配所有方法
            this.statusCode = 200;
            this.headers = new HashMap<>();
            this.headers.put("Content-Type", "application/json");
            this.delayMs = 0;
            this.enabled = true;
        }
        
        public MockRule method(String method) {
            this.method = method;
            return this;
        }
        
        public MockRule mockDataPath(String path) {
            this.mockDataPath = path;
            return this;
        }
        
        public MockRule mockDataJson(String json) {
            this.mockDataJson = json;
            return this;
        }
        
        public MockRule statusCode(int code) {
            this.statusCode = code;
            return this;
        }
        
        public MockRule header(String key, String value) {
            this.headers.put(key, value);
            return this;
        }
        
        public MockRule delay(long milliseconds) {
            this.delayMs = milliseconds;
            return this;
        }
        
        public MockRule enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        
        public MockRule requestModifier(RequestModifier requestModifier) {
            this.requestModifier = requestModifier;
            return this;
        }

        public MockRule requestInterceptor(RequestInterceptor requestInterceptor) {
            this.requestInterceptor = requestInterceptor;
            return this;
        }

        public MockRule responseGenerator(ResponseGenerator responseGenerator) {
            this.responseGenerator = responseGenerator;
            return this;
        }

        public String getName() { return name; }
        public String getUrlPattern() { return urlPattern; }
        public String getMethod() { return method; }
        public String getMockDataPath() { return mockDataPath; }
        public String getMockDataJson() { return mockDataJson; }
        public int getStatusCode() { return statusCode; }
        public Map<String, String> getHeaders() { return headers; }
        public long getDelayMs() { return delayMs; }
        public boolean isEnabled() { return enabled; }
        @Deprecated
        public RequestModifier getRequestModifier() { return requestModifier; }
        public RequestInterceptor getRequestInterceptor() { return requestInterceptor; }
        public ResponseGenerator getResponseGenerator() { return responseGenerator; }
    }
    
    /**
     * 请求修改器接口（旧版，仅用于添加header）
     * @deprecated 使用 RequestInterceptor 代替
     */
    @FunctionalInterface
    @Deprecated
    public interface RequestModifier {
        void modify(Request request);
    }

    /**
     * 请求拦截器接口（新版，支持全面的请求修改）
     */
    @FunctionalInterface
    public interface RequestInterceptor {
        /**
         * 拦截并修改请求
         * @param route 路由对象
         * @param request 原始请求对象
         * @return 修改后的继续选项，如果返回null则继续原始请求
         */
        Route.ResumeOptions intercept(Route route, Request request);
    }

    /**
     * 响应生成器接口
     */
    @FunctionalInterface
    public interface ResponseGenerator {
        String generate(Request request, Map<String, Object> context);
    }
    
    // ==================== 简化API（最常用） ====================

    /**
     * 【推荐】使用Builder模式配置Mock
     *
     * @param page Playwright Page对象
     * @return MockBuilder对象，用于链式调用
     *
     * 示例：
     * ApiMonitorAndMockManager.mock(page)
     *     .forUrl(".*api/users.*")
     *     .withStatus(200)
     *     .withResponse("{\"status\":\"success\"}")
     *     .build();
     */
    public static MockBuilder mock(Page page) {
        return new MockBuilder(page);
    }

    /**
     * 【推荐】使用Builder模式配置Mock
     *
     * @param context Playwright BrowserContext对象
     * @return MockBuilder对象，用于链式调用
     *
     * 示例：
     * ApiMonitorAndMockManager.mock(context)
     *     .forUrl(".*api/users.*")
     *     .withStatus(200)
     *     .withResponse("{\"status\":\"success\"}")
     *     .build();
     */
    public static MockBuilder mock(BrowserContext context) {
        return new MockBuilder(context);
    }

    /**
     * 【简化】Mock单个API - 一行代码搞定！
     * 立即应用Mock到Page
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持普通URL如 "/api/xxx" 或正则如 ".*api/users.*"）
     * @param statusCode 期望的状态码
     * @param responseData Mock响应数据
     *
     * 示例：
     * mockDirectResponse(page, "/api/users", 200, "{\"status\":\"success\"}");
     * mockDirectResponse(page, ".*api/products.*", 201, "{\"status\":\"created\"}");
     */
    public static void mockDirectResponse(Page page, String urlPattern, int statusCode, String responseData) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Mocking API: {} (Status: {}) ==========", pattern, statusCode);
        logger.info("Original URL pattern: '{}' -> Converted to: '{}'", urlPattern, pattern);

        MockRule rule = new MockRule("mock-" + pattern, pattern)
            .statusCode(statusCode)
            .mockDataJson(responseData);
        registerMockRule(rule);
        applyMocks(page);
        recordMockConfiguration();

        logger.info(" Mock API configured successfully!");
    }

    /**
     * 【简化】Mock单个API - 一行代码搞定！
     * 立即应用Mock到BrowserContext
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param statusCode 期望的状态码
     * @param responseData Mock响应数据
     *
     * 示例：
     * mockDirectResponse(context, "/api/users", 200, "{\"status\":\"success\"}");
     */
    public static void mockDirectResponse(BrowserContext context, String urlPattern, int statusCode, String responseData) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Mocking API: {} (Status: {}) ==========", pattern, statusCode);

        MockRule rule = new MockRule("mock-" + pattern, pattern)
            .statusCode(statusCode)
            .mockDataJson(responseData);
        registerMockRule(rule);
        applyMocks(context);
        recordMockConfiguration();

        logger.info(" Mock API configured successfully!");
    }

    /**
     * 【简化】Mock成功响应（默认状态码200）
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param responseData Mock响应数据
     *
     * 示例：
     * mockDirectSuccess(page, "/api/users", "{\"status\":\"success\"}");
     */
    public static void mockDirectSuccess(Page page, String urlPattern, String responseData) {
        mockDirectResponse(page, urlPattern, 200, responseData);
    }

    /**
     * 【简化】Mock成功响应（默认状态码200）
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param responseData Mock响应数据
     *
     * 示例：
     * mockDirectSuccess(context, "/api/users", "{\"status\":\"success\"}");
     */
    public static void mockDirectSuccess(BrowserContext context, String urlPattern, String responseData) {
        mockDirectResponse(context, urlPattern, 200, responseData);
    }

    /**
     * 【简化】Mock错误响应
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param statusCode 错误状态码（如 404, 500）
     * @param errorData 错误响应数据
     *
     * 示例：
     * mockDirectError(page, "/api/users", 500, "{\"status\":\"error\"}");
     * mockDirectError(page, "/api/users", 404, "{\"status\":\"not found\"}");
     */
    public static void mockDirectError(Page page, String urlPattern, int statusCode, String errorData) {
        mockDirectResponse(page, urlPattern, statusCode, errorData);
    }

    /**
     * 【简化】Mock错误响应
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param statusCode 错误状态码（如 404, 500）
     * @param errorData 错误响应数据
     *
     * 示例：
     * mockDirectError(context, "/api/users", 500, "{\"status\":\"error\"}");
     */
    public static void mockDirectError(BrowserContext context, String urlPattern, int statusCode, String errorData) {
        mockDirectResponse(context, urlPattern, statusCode, errorData);
    }

    /**
     * 【简化】Mock超时响应
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param timeoutMs 超时时间（毫秒）
     * @param responseData 响应数据
     *
     * 示例：
     * mockTimeout(page, "/api/users", 3000, "{\"status\":\"timeout\"}");
     */
    public static void mockTimeout(Page page, String urlPattern, long timeoutMs, String responseData) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Mocking API with timeout: {} (Timeout: {}ms) ==========", pattern, timeoutMs);

        MockRule rule = new MockRule("mock-timeout-" + pattern, pattern)
            .statusCode(408)
            .delay(timeoutMs)
            .mockDataJson(responseData);
        registerMockRule(rule);
        applyMocks(page);
        recordMockConfiguration();

        logger.info(" Mock API with timeout configured successfully!");
    }

    /**
     * 【简化】Mock超时响应
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param timeoutMs 超时时间（毫秒）
     * @param responseData 响应数据
     *
     * 示例：
     * mockTimeout(context, "/api/users", 3000, "{\"status\":\"timeout\"}");
     */
    public static void mockTimeout(BrowserContext context, String urlPattern, long timeoutMs, String responseData) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Mocking API with timeout: {} (Timeout: {}ms) ==========", pattern, timeoutMs);

        MockRule rule = new MockRule("mock-timeout-" + pattern, pattern)
            .statusCode(408)
            .delay(timeoutMs)
            .mockDataJson(responseData);
        registerMockRule(rule);
        applyMocks(context);
        recordMockConfiguration();

        logger.info(" Mock API with timeout configured successfully!");
    }

    // ==================== 捕获后Mock - 基于真实响应修改字段 ====================
    // 适用场景：【场景2】需要基于真实API响应修改特定字段
    // 优势：保留真实API的其他字段，只修改需要的字段
    // 流程：监控 → 捕获真实响应 → 修改字段 → Mock → 刷新页面
    //
    // 【对比】如果已有完整Mock数据，请使用 mockDirectResponse() 或 mockDirectSuccess()，更简单
    // - mockDirectResponse(): 直接提供完整响应，不需要监控
    // - captureAndMockField(): 监控真实API，修改特定字段

    /**
     * 【捕获后Mock】先监控获取真实响应，修改字段，然后Mock，最后刷新页面
     * 这种方法避免了Playwright route handler的单线程限制
     * 适用于【场景2】需要基于真实API响应修改特定字段
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param fieldName 要修改的字段名
     * @param fieldValue 字段值
     * @param mockStatusCode Mock的状态码（建议使用API实际返回的状态码）
     * @param waitSeconds 等待API响应的超时时间（秒）
     *
     * 示例：
     * // 监控lastLoginTime API，获取响应，修改lastLoginTime为2025年的时间戳，mock该API，刷新页面
     * captureAndMockField(page, "/rest/lastLoginTime", "lastLoginTime", 1735689600000L, 200, 30);
     *
     * 【对比】如果已有完整Mock数据，建议使用：
     * mockDirectSuccess(page, "/rest/lastLoginTime", "{\"lastLoginTime\":1735689600000}");
     */
    public static void captureAndMockField(Page page, String urlPattern, String fieldName, Object fieldValue, int mockStatusCode, int waitSeconds) {
        captureAndMockFields(page, urlPattern, Map.of(fieldName, fieldValue), mockStatusCode, waitSeconds);
    }

    /**
     * 【捕获后Mock】自动使用原始API状态码的版本
     * 监控获取真实响应，自动使用原始API的状态码，修改字段，然后Mock
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param fieldName 要修改的字段名
     * @param fieldValue 字段值
     * @param waitSeconds 等待API响应的超时时间（秒）
     *
     * 示例：
     * // 自动使用原始API的状态码
     * captureAndMockFieldWithOriginalStatus(page, "/rest/lastLoginTime", "lastLoginTime", 1735689600000L, 30);
     */
    public static void captureAndMockFieldWithOriginalStatus(Page page, String urlPattern, String fieldName, Object fieldValue, int waitSeconds) {
        captureAndMockFieldsWithOriginalStatus(page, urlPattern, Map.of(fieldName, fieldValue), waitSeconds);
    }

    /**
     * 【捕获后Mock】先监控获取真实响应，修改字段，然后Mock，最后刷新页面 - Context版本
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param fieldName 要修改的字段名
     * @param fieldValue 字段值
     * @param mockStatusCode Mock的状态码（建议使用API实际返回的状态码）
     * @param waitSeconds 等待API响应的超时时间（秒）
     */
    public static void captureAndMockField(BrowserContext context, String urlPattern, String fieldName, Object fieldValue, int mockStatusCode, int waitSeconds) {
        captureAndMockFields(context, urlPattern, Map.of(fieldName, fieldValue), mockStatusCode, waitSeconds);
    }

    /**
     * 【捕获后Mock】自动使用原始API状态码的版本 - Context版本
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param fieldName 要修改的字段名
     * @param fieldValue 字段值
     * @param waitSeconds 等待API响应的超时时间（秒）
     */
    public static void captureAndMockFieldWithOriginalStatus(BrowserContext context, String urlPattern, String fieldName, Object fieldValue, int waitSeconds) {
        captureAndMockFieldsWithOriginalStatus(context, urlPattern, Map.of(fieldName, fieldValue), waitSeconds);
    }

    /**
     * 【捕获后Mock】先监控获取真实响应，修改多个字段，然后Mock，最后刷新页面
     *
     * 工作流程：
     * 1. 设置持久化的 route 监听器，不等待，不超时
     * 2. 当 API 第一次被调用时，捕获真实响应，修改字段，返回修改后的响应
     * 3. 后续调用继续使用修改后的响应
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param fieldsToMock 要修改的字段路径和值的映射
     * @param mockStatusCode Mock的状态码
     * @param waitSeconds 等待API响应的超时时间（秒）- 已废弃参数，保留以保持兼容性
     *
     * 示例：
     * Map<String, Object> fields = new HashMap<>();
     * fields.put("lastLoginTime", 1735689600000L);
     * fields.put("status", "active");
     * captureAndMockFields(page, "/rest/config", fields, 200, 30);
     */
    public static void captureAndMockFields(Page page, String urlPattern, Map<String, Object> fieldsToMock, int mockStatusCode, int waitSeconds) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Capture And Mock: {} ==========", pattern);
        logger.info("Fields to modify: {}", fieldsToMock);
        logger.info("Mock status code: {}", mockStatusCode);
        logger.info("Note: Setting up persistent route handler - will capture and mock on first API call");

        final boolean[] capturedAndMocked = new boolean[1];

        try {
            Pattern compiledPattern = Pattern.compile(pattern);

            // 设置持久化的 route 监听器，不等待，不超时
            page.route(compiledPattern.asPredicate(), route -> {
                try {
                    Request request = route.request();

                    if (!capturedAndMocked[0]) {
                        // 第一次调用：捕获真实响应，修改字段，返回修改后的响应
                        logger.info(" First API call detected - capturing real response for: {}", request.url());

                        APIResponse originalResponse = route.fetch();
                        String originalBody = originalResponse.text();

                        logger.info(" Original response captured successfully");

                        // 修改响应字段
                        String modifiedBody = modifyJsonFields(originalBody, fieldsToMock);
                        logger.info(" Modified response with new field values");

                        // 标记已捕获并 mock
                        capturedAndMocked[0] = true;

                        // 返回修改后的响应（使用指定的状态码）
                        route.fulfill(new Route.FulfillOptions()
                            .setStatus(mockStatusCode)
                            .setHeaders(originalResponse.headers())
                            .setBody(modifiedBody));

                    } else {
                        // 后续调用：继续使用原始请求（让真正的 mock 规则处理）
                        logger.info(" Subsequent API call - resuming original request");
                        route.resume();
                    }
                } catch (Exception e) {
                    logger.error("Error handling route in capture-and-mock", e);
                    try {
                        route.resume();
                    } catch (Exception ex) {
                        logger.error("Error resuming route", ex);
                    }
                }
            });

            logger.info(" Persistent route handler set up for pattern: {}", pattern);
            logger.info(" Note: Route will remain active until explicitly removed or page closed");
            logger.info(" Capture and mock setup completed for: {}", pattern);

        } catch (Exception e) {
            logger.error("Failed to set up capture and mock for: {}", pattern, e);
        }
    }

    /**
     * 【捕获后Mock】自动使用原始API状态码的版本 - Page版本
     *
     * 工作流程：
     * 1. 设置持久化的 route 监听器，不等待，不超时
     * 2. 当 API 第一次被调用时，捕获真实响应，修改字段，返回修改后的响应
     * 3. 后续调用继续使用修改后的响应
     * 4. 【新增】如果autoStopAfterFirstCall为true，第一次调用后自动停止mock
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式
     * @param fieldsToMock 要修改的字段
     * @param waitSeconds 等待超时时间（秒）- 已废弃参数，保留以保持兼容性
     * @param autoStopAfterFirstCall 第一次调用后是否自动停止mock（默认false）
     */
    public static void captureAndMockFieldsWithOriginalStatus(Page page, String urlPattern, Map<String, Object> fieldsToMock, int waitSeconds, boolean autoStopAfterFirstCall) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Capture And Mock With Original Status: {} ==========", pattern);
        logger.info("Fields to modify: {}", fieldsToMock);
        logger.info("Auto-stop after first call: {}", autoStopAfterFirstCall);
        logger.info("Note: Setting up persistent route handler - will capture and mock on first API call");

        final boolean[] capturedAndMocked = new boolean[1];

        try {
            Pattern compiledPattern = Pattern.compile(pattern);

            // 设置持久化的 route 监听器，不等待，不超时
            page.route(compiledPattern.asPredicate(), route -> {
                try {
                    Request request = route.request();

                    if (!capturedAndMocked[0]) {
                        // 第一次调用：捕获真实响应，修改字段，返回修改后的响应
                        logger.info(" First API call detected - capturing real response for: {}", request.url());

                        APIResponse originalResponse = route.fetch();
                        String originalBody = originalResponse.text();
                        int statusCode = originalResponse.status();

                        logger.info(" Original status code: {}", statusCode);
                        logger.info(" Original response captured successfully");

                        // 修改响应字段
                        String modifiedBody = modifyJsonFields(originalBody, fieldsToMock);
                        logger.info(" Modified response with new field values");

                        // 标记已捕获并 mock
                        capturedAndMocked[0] = true;

                        // 返回修改后的响应
                        route.fulfill(new Route.FulfillOptions()
                            .setStatus(statusCode)
                            .setHeaders(originalResponse.headers())
                            .setBody(modifiedBody));

                        // 【新增】如果配置了自动停止，在第一次调用后停止mock
                        if (autoStopAfterFirstCall) {
                            logger.info(" Auto-stopping mock after first successful call for: {}", pattern);
                            try {
                                page.unroute(compiledPattern.asPredicate());
                                logger.info("✓ Route removed for pattern: {} (auto-stop)", pattern);
                            } catch (Exception ex) {
                                logger.warn("Failed to auto-remove route for pattern {}: {}", pattern, ex.getMessage());
                            }
                        }

                    } else {
                        // 后续调用：继续使用原始请求（让真正的 mock 规则处理）
                        logger.info(" Subsequent API call - resuming original request");
                        route.resume();
                    }
                } catch (Exception e) {
                    logger.error("Error handling route in capture-and-mock", e);
                    try {
                        route.resume();
                    } catch (Exception ex) {
                        logger.error("Error resuming route", ex);
                    }
                }
            });

            logger.info(" Persistent route handler set up for pattern: {}", pattern);
            logger.info(" Note: Route will remain active until explicitly removed or page closed");
            if (autoStopAfterFirstCall) {
                logger.info(" Note: Route will be auto-removed after first successful call");
            }
            logger.info(" Capture and mock with original status setup completed for: {}", pattern);

        } catch (Exception e) {
            logger.error("Failed to set up capture and mock for: {}", pattern, e);
        }
    }

    /**
     * 【捕获后Mock】自动使用原始API状态码的版本 - Page版本（默认不自动停止）
     *
     * 工作流程：
     * 1. 设置持久化的 route 监听器，不等待，不超时
     * 2. 当 API 第一次被调用时，捕获真实响应，修改字段，返回修改后的响应
     * 3. 后续调用继续使用修改后的响应
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式
     * @param fieldsToMock 要修改的字段
     * @param waitSeconds 等待超时时间（秒）- 已废弃参数，保留以保持兼容性
     */
    public static void captureAndMockFieldsWithOriginalStatus(Page page, String urlPattern, Map<String, Object> fieldsToMock, int waitSeconds) {
        captureAndMockFieldsWithOriginalStatus(page, urlPattern, fieldsToMock, waitSeconds, false);
    }

    /**
     * 【捕获后Mock】先监控获取真实响应，修改多个字段，然后Mock - Context版本
     *
     * 工作流程：
     * 1. 设置持久化的 route 监听器，不等待，不超时
     * 2. 当 API 第一次被调用时，捕获真实响应，修改字段，返回修改后的响应
     * 3. 后续调用继续使用修改后的响应
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式
     * @param fieldsToMock 要修改的字段
     * @param mockStatusCode Mock的状态码
     * @param waitSeconds 等待超时时间（秒）- 已废弃参数，保留以保持兼容性
     */
    public static void captureAndMockFields(BrowserContext context, String urlPattern, Map<String, Object> fieldsToMock, int mockStatusCode, int waitSeconds) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Capture And Mock (Context): {} ==========", pattern);
        logger.info("Fields to modify: {}", fieldsToMock);
        logger.info("Mock status code: {}", mockStatusCode);
        logger.info("Note: Setting up persistent route handler - will capture and mock on first API call");

        final boolean[] capturedAndMocked = new boolean[1];

        try {
            Pattern compiledPattern = Pattern.compile(pattern);

            // 设置持久化的 route 监听器，不等待，不超时
            context.route(compiledPattern.asPredicate(), route -> {
                try {
                    Request request = route.request();

                    if (!capturedAndMocked[0]) {
                        // 第一次调用：捕获真实响应，修改字段，返回修改后的响应
                        logger.info(" First API call detected - capturing real response for: {}", request.url());

                        APIResponse originalResponse = route.fetch();
                        String originalBody = originalResponse.text();

                        logger.info(" Original response captured successfully");

                        // 修改响应字段
                        String modifiedBody = modifyJsonFields(originalBody, fieldsToMock);
                        logger.info(" Modified response with new field values");

                        // 标记已捕获并 mock
                        capturedAndMocked[0] = true;

                        // 返回修改后的响应（使用指定的状态码）
                        route.fulfill(new Route.FulfillOptions()
                            .setStatus(mockStatusCode)
                            .setHeaders(originalResponse.headers())
                            .setBody(modifiedBody));

                    } else {
                        // 后续调用：继续使用原始请求（让真正的 mock 规则处理）
                        logger.info(" Subsequent API call - resuming original request");
                        route.resume();
                    }
                } catch (Exception e) {
                    logger.error("Error handling route in capture-and-mock (Context)", e);
                    try {
                        route.resume();
                    } catch (Exception ex) {
                        logger.error("Error resuming route", ex);
                    }
                }
            });

            logger.info(" Persistent route handler set up for pattern: {}", pattern);
            logger.info(" Note: Route will remain active until explicitly removed or context closed");
            logger.info(" Capture and mock setup completed for: {}", pattern);

        } catch (Exception e) {
            logger.error("Failed to set up capture and mock (Context) for: {}", pattern, e);
        }
    }

    /**
     * 【捕获后Mock】自动使用原始API状态码的版本 - Context版本
     *
     * 工作流程：
     * 1. 设置持久化的 route 监听器，不等待，不超时
     * 2. 当 API 第一次被调用时，捕获真实响应，修改字段，返回修改后的响应
     * 3. 后续调用继续使用修改后的响应
     * 4. 【新增】如果autoStopAfterFirstCall为true，第一次调用后自动停止mock
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式
     * @param fieldsToMock 要修改的字段
     * @param waitSeconds 等待超时时间（秒）- 已废弃参数，保留以保持兼容性
     * @param autoStopAfterFirstCall 第一次调用后是否自动停止mock（默认false）
     */
    public static void captureAndMockFieldsWithOriginalStatus(BrowserContext context, String urlPattern, Map<String, Object> fieldsToMock, int waitSeconds, boolean autoStopAfterFirstCall) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Capture And Mock With Original Status (Context): {} ==========", pattern);
        logger.info("Fields to modify: {}", fieldsToMock);
        logger.info("Auto-stop after first call: {}", autoStopAfterFirstCall);
        logger.info("Note: Setting up persistent route handler - will capture and mock on first API call");

        final boolean[] capturedAndMocked = new boolean[1];

        try {
            Pattern compiledPattern = Pattern.compile(pattern);

            // 设置持久化的 route 监听器，不等待，不超时
            context.route(compiledPattern.asPredicate(), route -> {
                try {
                    Request request = route.request();

                    if (!capturedAndMocked[0]) {
                        // 第一次调用：捕获真实响应，修改字段，返回修改后的响应
                        logger.info(" First API call detected - capturing real response for: {}", request.url());

                        APIResponse originalResponse = route.fetch();
                        String originalBody = originalResponse.text();
                        int statusCode = originalResponse.status();

                        logger.info(" Original status code: {}", statusCode);
                        logger.info(" Original response captured successfully");

                        // 修改响应字段
                        String modifiedBody = modifyJsonFields(originalBody, fieldsToMock);
                        logger.info(" Modified response with new field values");

                        // 标记已捕获并 mock
                        capturedAndMocked[0] = true;

                        // 返回修改后的响应
                        route.fulfill(new Route.FulfillOptions()
                            .setStatus(statusCode)
                            .setHeaders(originalResponse.headers())
                            .setBody(modifiedBody));

                        // 【新增】如果配置了自动停止，在第一次调用后停止mock
                        if (autoStopAfterFirstCall) {
                            logger.info(" Auto-stopping mock after first successful call for: {}", pattern);
                            try {
                                context.unroute(compiledPattern.asPredicate());
                                logger.info("✓ Route removed for pattern: {} (auto-stop)", pattern);
                            } catch (Exception ex) {
                                logger.warn("Failed to auto-remove route for pattern {}: {}", pattern, ex.getMessage());
                            }
                        }

                    } else {
                        // 后续调用：继续使用原始请求（让真正的 mock 规则处理）
                        logger.info(" Subsequent API call - resuming original request");
                        route.resume();
                    }
                } catch (Exception e) {
                    logger.error("Error handling route in capture-and-mock (Context)", e);
                    try {
                        route.resume();
                    } catch (Exception ex) {
                        logger.error("Error resuming route", ex);
                    }
                }
            });

            logger.info(" Persistent route handler set up for pattern: {}", pattern);
            logger.info(" Note: Route will remain active until explicitly removed or context closed");
            if (autoStopAfterFirstCall) {
                logger.info(" Note: Route will be auto-removed after first successful call");
            }
            logger.info(" Capture and mock with original status setup completed for: {}", pattern);

        } catch (Exception e) {
            logger.error("Failed to set up capture and mock (Context) for: {}", pattern, e);
        }
    }

    /**
     * 【捕获后Mock】自动使用原始API状态码的版本 - Context版本（默认不自动停止）
     *
     * 工作流程：
     * 1. 设置持久化的 route 监听器，不等待，不超时
     * 2. 当 API 第一次被调用时，捕获真实响应，修改字段，返回修改后的响应
     * 3. 后续调用继续使用修改后的响应
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式
     * @param fieldsToMock 要修改的字段
     * @param waitSeconds 等待超时时间（秒）- 已废弃参数，保留以保持兼容性
     */
    public static void captureAndMockFieldsWithOriginalStatus(BrowserContext context, String urlPattern, Map<String, Object> fieldsToMock, int waitSeconds) {
        captureAndMockFieldsWithOriginalStatus(context, urlPattern, fieldsToMock, waitSeconds, false);
    }

    // ==================== 辅助类和方法 ====================

    /**
     * 捕获的响应数据
     */
    public static class CapturedResponse {
        public String body;
        public int statusCode;

        public CapturedResponse(String body, int statusCode) {
            this.body = body;
            this.statusCode = statusCode;
        }
    }

    /**
     * 修改JSON中的字段（使用JsonPath）
     *
     * @param jsonString 原始JSON字符串
     * @param fieldsToModify 要修改的字段映射（key为JsonPath，value为新值）
     * @return 修改后的JSON字符串
     */
    private static String modifyJsonFields(String jsonString, Map<String, Object> fieldsToModify) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return jsonString;
        }

        try {
            // 使用ObjectMapper解析JSON
            JsonNode rootNode = objectMapper.readTree(jsonString);

            // 遍历要修改的字段
            for (Map.Entry<String, Object> entry : fieldsToModify.entrySet()) {
                String jsonPath = entry.getKey();
                Object newValue = entry.getValue();

                try {
                    // 使用JsonPath定位字段
                    Object valueAtPath = JsonPath.parse(jsonString).read(jsonPath);

                    // 简单的实现：只支持顶层字段或简单的点号分隔路径
                    // 对于更复杂的路径，需要使用JsonPath的modify功能
                    String[] pathSegments = jsonPath.split("\\.");
                    JsonNode currentNode = rootNode;

                    // 遍历到最后一个节点之前的所有节点
                    for (int i = 0; i < pathSegments.length - 1; i++) {
                        String segment = pathSegments[i];
                        if (segment.contains("[")) {
                            // 处理数组索引，如 items[0]
                            String arrayName = segment.substring(0, segment.indexOf("["));
                            int index = Integer.parseInt(segment.substring(segment.indexOf("[") + 1, segment.indexOf("]")));
                            currentNode = currentNode.path(arrayName).get(index);
                        } else {
                            currentNode = currentNode.path(segment);
                        }
                        if (currentNode.isMissingNode()) {
                            logger.warn("Path segment '{}' not found in JSON", segment);
                            break;
                        }
                    }

                    // 修改最后一个节点
                    if (currentNode.isObject() && !currentNode.isMissingNode()) {
                        String lastSegment = pathSegments[pathSegments.length - 1];
                        if (lastSegment.contains("[")) {
                            // 处理数组索引
                            String arrayName = lastSegment.substring(0, lastSegment.indexOf("["));
                            int index = Integer.parseInt(lastSegment.substring(lastSegment.indexOf("[") + 1, lastSegment.indexOf("]")));
                            JsonNode arrayNode = currentNode.path(arrayName);
                            if (!arrayNode.isMissingNode()) {
                                ObjectNode mutableNode = (ObjectNode) currentNode;
                                JsonNode newArrayNode = arrayNode;
                                // 对于数组中的对象，需要特殊处理
                                if (arrayNode.isArray() && index < arrayNode.size() && arrayNode.get(index).isObject()) {
                                    // 这里简化处理，直接跳过数组元素的修改
                                    logger.warn("Array element modification not fully supported: {}", jsonPath);
                                }
                            }
                        } else {
                            // 简单对象字段修改
                            ObjectNode mutableNode = (ObjectNode) currentNode;
                            if (newValue instanceof String) {
                                mutableNode.put(lastSegment, (String) newValue);
                            } else if (newValue instanceof Integer) {
                                mutableNode.put(lastSegment, (Integer) newValue);
                            } else if (newValue instanceof Long) {
                                mutableNode.put(lastSegment, (Long) newValue);
                            } else if (newValue instanceof Double) {
                                mutableNode.put(lastSegment, (Double) newValue);
                            } else if (newValue instanceof Boolean) {
                                mutableNode.put(lastSegment, (Boolean) newValue);
                            } else {
                                mutableNode.set(lastSegment, objectMapper.valueToTree(newValue));
                            }
                            logger.info("Modified field: {} = {}", jsonPath, newValue);
                        }
                    } else {
                        logger.warn("Cannot modify path: {} (not an object node or path not found)", jsonPath);
                    }
                } catch (PathNotFoundException e) {
                    logger.warn("JSON path '{}' not found in response, skipping modification", jsonPath);
                }
            }

            return objectMapper.writeValueAsString(rootNode);
        } catch (Exception e) {
            logger.error("Failed to modify JSON fields", e);
            return jsonString;
        }
    }



    /**
     * 【高级】动态Mock - 基于请求内容生成响应
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param generator 响应生成器
     *
     * 示例：
     * mockDynamic(page, "/api/users", (request, context) -> {
     *     String userId = extractUserId(request.url());
     *     return "{\"id\":" + userId + ",\"name\":\"User " + userId + "\"}";
     * });
     */
    public static void mockDynamic(Page page, String urlPattern, ResponseGenerator generator) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Mocking API with dynamic response: {} ==========", pattern);

        MockRule rule = new MockRule("mock-dynamic-" + pattern, pattern)
            .responseGenerator(generator);
        registerMockRule(rule);
        applyMocks(page);
        recordMockConfiguration();

        logger.info(" Mock API with dynamic response configured successfully!");
    }

    /**
     * 【高级】动态Mock - 基于请求内容生成响应
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param generator 响应生成器
     *
     * 示例：
     * mockDynamic(context, "/api/users", (request, context) -> {
     *     String userId = extractUserId(request.url());
     *     return "{\"id\":" + userId + ",\"name\":\"User " + userId + "\"}";
     * });
     */
    public static void mockDynamic(BrowserContext context, String urlPattern, ResponseGenerator generator) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Mocking API with dynamic response: {} ==========", pattern);

        MockRule rule = new MockRule("mock-dynamic-" + pattern, pattern)
            .responseGenerator(generator);
        registerMockRule(rule);
        applyMocks(context);
        recordMockConfiguration();

        logger.info(" Mock API with dynamic response configured successfully!");
    }

    /**
     * 将普通URL模式转换为正则表达式
     * 如果URL已经是正则表达式（包含.*、\\d等），则原样返回
     * 否则自动添加.*前缀和后缀进行灵活匹配
     *
     * @param urlPattern URL模式（普通URL或正则表达式）
     * @return 正则表达式模式
     *
     * 示例：
     * - "/api/users" -> ".*api/users.*"
     * - "api/users" -> ".*api/users.*"
     * - ".*api/.*" -> ".*api/.*" (已经是正则，不转换)
     */
    private static String toRegexPattern(String urlPattern) {
        if (urlPattern == null || urlPattern.isEmpty()) {
            return ".*";
        }

        // 检查是否已经是正则表达式（包含常见的正则元字符）
        boolean isRegex = urlPattern.contains(".*") || urlPattern.contains("\\d")
                       || urlPattern.contains("?") || urlPattern.contains("+")
                       || urlPattern.contains("\\w") || urlPattern.contains("\\s");

        if (isRegex) {
            return urlPattern; // 已经是正则表达式，直接返回
        }

        // 如果以 / 开头，去掉开头的 /，然后添加 .* 前后缀
        // 例如：/api/users -> .*api/users.*
        String normalized = urlPattern;
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        return ".*" + normalized + ".*";
    }

    // ==================== 请求修改API（拦截并修改请求） ====================

    /**
     * 【简化】修改请求 - 添加或替换请求头
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param headerName 头名称
     * @param headerValue 头值
     *
     * 示例：
     * modifyRequestHeader(page, "/api/users", "Authorization", "Bearer token123");
     */
    public static void modifyRequestHeader(Page page, String urlPattern, String headerName, String headerValue) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Modifying request header for: {} ==========", pattern);

        MockRule rule = new MockRule("modify-header-" + pattern, pattern)
            .requestInterceptor((route, request) -> {
                Map<String, String> headers = new HashMap<>(request.headers());
                headers.put(headerName, headerValue);
                return new Route.ResumeOptions().setHeaders(headers);
            });
        registerMockRule(rule);
        applyMocks(page);
        recordMockConfiguration();

        logger.info(" Request header modifier configured successfully!");
    }

    /**
     * 【简化】修改请求 - 添加或替换请求头
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param headerName 头名称
     * @param headerValue 头值
     *
     * 示例：
     * modifyRequestHeader(context, "/api/users", "Authorization", "Bearer token123");
     */
    public static void modifyRequestHeader(BrowserContext context, String urlPattern, String headerName, String headerValue) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Modifying request header for: {} ==========", pattern);

        MockRule rule = new MockRule("modify-header-" + pattern, pattern)
            .requestInterceptor((route, request) -> {
                Map<String, String> headers = new HashMap<>(request.headers());
                headers.put(headerName, headerValue);
                return new Route.ResumeOptions().setHeaders(headers);
            });
        registerMockRule(rule);
        applyMocks(context);
        recordMockConfiguration();

        logger.info(" Request header modifier configured successfully!");
    }

    /**
     * 【简化】修改请求 - 添加或替换请求体
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param newBody 新的请求体
     *
     * 示例：
     * modifyRequestBody(page, "/api/users", "{\"name\":\"new_name\"}");
     */
    public static void modifyRequestBody(Page page, String urlPattern, String newBody) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Modifying request body for: {} ==========", pattern);

        MockRule rule = new MockRule("modify-body-" + pattern, pattern)
            .requestInterceptor((route, request) -> {
                return new Route.ResumeOptions().setPostData(newBody);
            });
        registerMockRule(rule);
        applyMocks(page);
        recordMockConfiguration();

        logger.info(" Request body modifier configured successfully!");
    }

    /**
     * 【简化】修改请求 - 添加或替换请求体
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param newBody 新的请求体
     *
     * 示例：
     * modifyRequestBody(context, "/api/users", "{\"name\":\"new_name\"}");
     */
    public static void modifyRequestBody(BrowserContext context, String urlPattern, String newBody) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Modifying request body for: {} ==========", pattern);

        MockRule rule = new MockRule("modify-body-" + pattern, pattern)
            .requestInterceptor((route, request) -> {
                return new Route.ResumeOptions().setPostData(newBody);
            });
        registerMockRule(rule);
        applyMocks(context);
        recordMockConfiguration();

        logger.info(" Request body modifier configured successfully!");
    }

    /**
     * 【简化】修改请求 - 添加查询参数
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param paramName 参数名
     * @param paramValue 参数值
     *
     * 示例：
     * modifyRequestQueryParam(page, "/api/users", "userId", "123");
     */
    public static void modifyRequestQueryParam(Page page, String urlPattern, String paramName, String paramValue) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Modifying request query param for: {} ==========", pattern);

        MockRule rule = new MockRule("modify-query-" + pattern, pattern)
            .requestInterceptor((route, request) -> {
                String url = request.url();
                String separator = url.contains("?") ? "&" : "?";
                String newUrl = url + separator + paramName + "=" + paramValue;
                return new Route.ResumeOptions().setUrl(newUrl);
            });
        registerMockRule(rule);
        applyMocks(page);
        recordMockConfiguration();

        logger.info(" Request query param modifier configured successfully!");
    }

    /**
     * 【简化】修改请求 - 添加查询参数
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param paramName 参数名
     * @param paramValue 参数值
     *
     * 示例：
     * modifyRequestQueryParam(context, "/api/users", "userId", "123");
     */
    public static void modifyRequestQueryParam(BrowserContext context, String urlPattern, String paramName, String paramValue) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Modifying request query param for: {} ==========", pattern);

        MockRule rule = new MockRule("modify-query-" + pattern, pattern)
            .requestInterceptor((route, request) -> {
                String url = request.url();
                String separator = url.contains("?") ? "&" : "?";
                String newUrl = url + separator + paramName + "=" + paramValue;
                return new Route.ResumeOptions().setUrl(newUrl);
            });
        registerMockRule(rule);
        applyMocks(context);
        recordMockConfiguration();

        logger.info(" Request query param modifier configured successfully!");
    }

    /**
     * 【简化】修改请求 - 修改请求方法
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param newMethod 新的HTTP方法（GET, POST, PUT, DELETE等）
     *
     * 示例：
     * modifyRequestMethod(page, "/api/users", "GET");
     */
    public static void modifyRequestMethod(Page page, String urlPattern, String newMethod) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Modifying request method for: {} -> {} ==========", pattern, newMethod);

        MockRule rule = new MockRule("modify-method-" + pattern, pattern)
            .requestInterceptor((route, request) -> {
                return new Route.ResumeOptions().setMethod(newMethod);
            });
        registerMockRule(rule);
        applyMocks(page);
        recordMockConfiguration();

        logger.info(" Request method modifier configured successfully!");
    }

    /**
     * 【简化】修改请求 - 修改请求方法
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param newMethod 新的HTTP方法（GET, POST, PUT, DELETE等）
     *
     * 示例：
     * modifyRequestMethod(context, "/api/users", "GET");
     */
    public static void modifyRequestMethod(BrowserContext context, String urlPattern, String newMethod) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Modifying request method for: {} -> {} ==========", pattern, newMethod);

        MockRule rule = new MockRule("modify-method-" + pattern, pattern)
            .requestInterceptor((route, request) -> {
                return new Route.ResumeOptions().setMethod(newMethod);
            });
        registerMockRule(rule);
        applyMocks(context);
        recordMockConfiguration();

        logger.info(" Request method modifier configured successfully!");
    }

    /**
     * 【高级】自定义请求拦截器 - 完全控制请求修改
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param interceptor 请求拦截器
     *
     * 示例：
     * interceptRequest(page, "/api/users", (route, request) -> {
     *     return new Route.ResumeOptions()
     *         .setMethod("POST")
     *         .setPostData("{\"modified\":true}")
     *         .setHeaders(Map.of("X-Custom", "value"));
     * });
     */
    public static void interceptRequest(Page page, String urlPattern, RequestInterceptor interceptor) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Intercepting requests for: {} ==========", pattern);

        MockRule rule = new MockRule("intercept-" + pattern, pattern)
            .requestInterceptor(interceptor);
        registerMockRule(rule);
        applyMocks(page);
        recordMockConfiguration();

        logger.info(" Request interceptor configured successfully!");
    }

    /**
     * 【高级】自定义请求拦截器 - 完全控制请求修改
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param interceptor 请求拦截器
     *
     * 示例：
     * interceptRequest(context, "/api/users", (route, request) -> {
     *     return new Route.ResumeOptions()
     *         .setMethod("POST")
     *         .setPostData("{\"modified\":true}")
     *         .setHeaders(Map.of("X-Custom", "value"));
     * });
     */
    public static void interceptRequest(BrowserContext context, String urlPattern, RequestInterceptor interceptor) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Intercepting requests for: {} ==========", pattern);

        MockRule rule = new MockRule("intercept-" + pattern, pattern)
            .requestInterceptor(interceptor);
        registerMockRule(rule);
        applyMocks(context);
        recordMockConfiguration();

        logger.info(" Request interceptor configured successfully!");
    }

    // ==================== 传统API（向后兼容） ====================

    /**
     * 初始化API监控和mock管理器
     */
    public static void initialize() {
        logger.info("Initializing ApiMonitorAndMockManager...");

        // 从配置文件加载Mock规则
        if (config.hasPath("api.mock.rules")) {
            logger.info("Loading mock rules from configuration...");
            // 这里可以扩展从配置文件加载规则
        }

        logger.info("ApiMonitorAndMockManager initialized successfully");
    }
    
    /**
     * 注册Mock规则
     */
    public static void registerMockRule(MockRule rule) {
        mockRules.put(rule.getName(), rule);
        logger.info("Registered mock rule: {} for URL pattern: {}", rule.getName(), rule.getUrlPattern());
    }
    
    /**
     * 快速注册简单的Mock规则
     */
    public static void registerMock(String name, String urlPattern, String mockDataJson) {
        MockRule rule = new MockRule(name, urlPattern)
            .mockDataJson(mockDataJson);
        registerMockRule(rule);
    }
    
    /**
     * 快速注册从文件读取的Mock规则
     */
    public static void registerMockFromFile(String name, String urlPattern, String jsonFilePath) {
        MockRule rule = new MockRule(name, urlPattern)
            .mockDataPath(jsonFilePath);
        registerMockRule(rule);
    }
    
    /**
     * 启用指定的Mock规则
     */
    public static void enableMock(String mockName) {
        MockRule rule = mockRules.get(mockName);
        if (rule != null) {
            rule.enabled(true);
            logger.info("Enabled mock rule: {}", mockName);
        } else {
            logger.warn("Mock rule not found: {}", mockName);
        }
    }
    
    /**
     * 禁用指定的Mock规则
     */
    public static void disableMock(String mockName) {
        MockRule rule = mockRules.get(mockName);
        if (rule != null) {
            rule.enabled(false);
            logger.info("Disabled mock rule: {}", mockName);
        }
    }

    /**
     * 为Page应用所有Mock规则
     */
    public static void applyMocks(Page page) {
        logger.info("Applying {} mock rules to page", mockRules.size());

        // 存储当前page引用
        currentPage = page;
        currentContext = null;

        for (MockRule rule : mockRules.values()) {
            if (rule.isEnabled()) {
                applyMockRule(page, rule);
            }
        }
    }

    /**
     * 为BrowserContext应用所有Mock规则
     */
    public static void applyMocks(BrowserContext context) {
        logger.info("Applying {} mock rules to context", mockRules.size());

        // 存储当前context引用
        currentContext = context;
        currentPage = null;

        for (MockRule rule : mockRules.values()) {
            if (rule.isEnabled()) {
                applyMockRule(context, rule);
            }
        }
    }
    
    /**
     * 应用单个Mock规则到Page
     */
    private static void applyMockRule(Page page, MockRule rule) {
        Pattern urlPattern = Pattern.compile(rule.getUrlPattern());
        
        page.route(urlPattern.asPredicate(), route -> {  // 使用pattern predicate
            handleMockRoute(route, rule);
        });
        
        logger.info("Applied mock rule '{}' to page for pattern: {}", rule.getName(), rule.getUrlPattern());
    }
    
    /**
     * 应用单个Mock规则到BrowserContext
     */
    private static void applyMockRule(BrowserContext context, MockRule rule) {
        Pattern urlPattern = Pattern.compile(rule.getUrlPattern());
        
        context.route(urlPattern.asPredicate(), route -> {
            handleMockRoute(route, rule);
        });
        
        logger.info("Applied mock rule '{}' to context for pattern: {}", rule.getName(), rule.getUrlPattern());
    }
    
    /**
     * 处理Mock路由
     */
    private static void handleMockRoute(Route route, MockRule rule) {
        try {
            Request request = route.request();

            // 记录请求信息
            recordApiCall(route, rule, null);

            logger.info("Intercepted request: {} {} - Applying rule: {}",
                request.method(), request.url(), rule.getName());

            // 检查HTTP方法是否匹配
            if (!Pattern.matches(rule.getMethod(), request.method())) {
                route.resume();
                return;
            }

            // 处理请求拦截（新版，支持全面修改）
            if (rule.requestInterceptor != null) {
                Route.ResumeOptions continueOptions = rule.requestInterceptor.intercept(route, request);
                if (continueOptions != null) {
                    logger.info("Request modified with interceptor, continuing with modified request");
                    route.resume(continueOptions);
                    return;
                }
            }

            // 处理请求修改（旧版，仅header）
            if (rule.requestModifier != null) {
                rule.requestModifier.modify(request);
            }

            // 模拟延迟
            if (rule.getDelayMs() > 0) {
                try {
                    Thread.sleep(rule.getDelayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // 获取Mock数据或生成响应
            String mockData = getMockData(rule, route);

            if (mockData != null) {
                // 返回Mock响应
                Route.FulfillOptions options = new Route.FulfillOptions()
                    .setStatus(rule.getStatusCode())
                    .setBody(mockData);

                // 设置响应头
                if (rule.getHeaders() != null && !rule.getHeaders().isEmpty()) {
                    options.setHeaders(rule.getHeaders());
                }

                route.fulfill(options);
                logger.info("Mock response sent for: {} with status: {}",
                    request.url(), rule.getStatusCode());
            } else {
                logger.info("No mock data available for rule: {}, continuing original request", rule.getName());
                route.resume();
            }

        } catch (Exception e) {
            logger.error("Error handling mock route for rule: " + rule.getName(), e);
            route.resume();
        }
    }
    
    /**
     * 获取Mock数据
     */
    private static String getMockData(MockRule rule, Route route) throws Exception {
        // 优先使用响应生成器
        if (rule.getResponseGenerator() != null) {
            Map<String, Object> context = createRequestContext(route);
            return rule.getResponseGenerator().generate(route.request(), context);
        }
        
        // 优先使用直接提供的JSON
        if (rule.getMockDataJson() != null && !rule.getMockDataJson().isEmpty()) {
            return rule.getMockDataJson();
        }
        
        // 从文件读取
        if (rule.getMockDataPath() != null && !rule.getMockDataPath().isEmpty()) {
            return new String(Files.readAllBytes(Paths.get(rule.getMockDataPath())));
        }
        
        return null;
    }
    
    /**
     * 创建请求上下文
     */
    private static Map<String, Object> createRequestContext(Route route) {
        Map<String, Object> context = new HashMap<>();
        context.put("url", route.request().url());
        context.put("method", route.request().method());
        context.put("headers", route.request().headers());
        try {
            // Playwright的Request接口没有postDataJSON方法，使用postData
            String postData = route.request().postData();
            if (postData != null) {
                // 尝试解析为JSON
                context.put("postData", postData);
            } else {
                context.put("postData", "");
            }
        } catch (Exception e) {
            context.put("postData", "");
        }
        return context;
    }
    
    /**
     * 记录API调用
     */
    private static void recordApiCall(Route route, MockRule rule, Object originalResponse) {
        try {
            String requestId = UUID.randomUUID().toString();
            long timestamp = System.currentTimeMillis();
            int statusCode = rule != null ? rule.getStatusCode() : 0;
            boolean isMocked = rule != null;
            
            Map<String, String> requestHeaders = new HashMap<>(route.request().headers());
            Object requestBody = route.request().postData();
            
            Map<String, String> responseHeaders = new HashMap<>();
            Object responseBody = null;
            
            ApiCallRecord record = new ApiCallRecord(
                requestId, route.request().url(), route.request().method(), timestamp,
                requestHeaders, requestBody, statusCode, responseHeaders, responseBody, 0, isMocked
            );
            
            apiCallHistory.add(record);
            logger.debug("Recorded API call: {}", record.requestId);
        } catch (Exception e) {
            logger.error("Failed to record API call", e);
        }
    }
    
    /**
     * 获取API调用历史
     */
    public static List<ApiCallRecord> getApiCallHistory() {
        return Collections.unmodifiableList(apiCallHistory);
    }
    
    /**
     * 获取特定URL的API调用记录
     */
    public static List<ApiCallRecord> getApiCallHistoryByUrl(String urlPattern) {
        Pattern pattern = Pattern.compile(urlPattern);
        return apiCallHistory.stream()
            .filter(record -> pattern.matcher(record.getUrl()).matches())
            .collect(Collectors.toList());
    }
    
    /**
     * 清除所有Mock规则
     */
    public static void clearAllMocks() {
        mockRules.clear();
        logger.info("All mock rules cleared");
    }

    /**
     * 停止所有Mock - 移除所有route并清除规则（Context版本）
     * @param context Playwright BrowserContext对象
     */
    public static void stopAllMocks(BrowserContext context) {
        logger.info("========== Stopping All Mocks (Context) ==========");
        try {
            context.unrouteAll();
            logger.info("✓ All routes removed from context");
        } catch (Exception e) {
            logger.warn("Failed to remove routes from context: {}", e.getMessage());
        }
        mockRules.clear();
        logger.info("✓ All mock rules cleared");
        logger.info("========== All Mocks Stopped ==========");
    }

    /**
     * 停止所有Mock - 移除所有route并清除规则（Page版本）
     * @param page Playwright Page对象
     */
    public static void stopAllMocks(Page page) {
        logger.info("========== Stopping All Mocks (Page) ==========");
        try {
            page.unrouteAll();
            logger.info("✓ All routes removed from page");
        } catch (Exception e) {
            logger.warn("Failed to remove routes from page: {}", e.getMessage());
        }
        mockRules.clear();
        logger.info("✓ All mock rules cleared");
        logger.info("========== All Mocks Stopped ==========");
    }

    /**
     * 停止指定URL的Mock - 只移除特定URL的route（Context版本）
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     */
    public static void stopMock(BrowserContext context, String urlPattern) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Stopping Mock for URL: {} ==========", pattern);
        try {
            Pattern compiledPattern = Pattern.compile(pattern);
            context.unroute(compiledPattern.asPredicate());
            logger.info("✓ Route removed for pattern: {}", pattern);
        } catch (Exception e) {
            logger.warn("Failed to remove route for pattern {}: {}", pattern, e.getMessage());
        }
        logger.info("========== Mock Stopped ==========");
    }

    /**
     * 停止指定URL的Mock - 只移除特定URL的route（Page版本）
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     */
    public static void stopMock(Page page, String urlPattern) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Stopping Mock for URL: {} ==========", pattern);
        try {
            Pattern compiledPattern = Pattern.compile(pattern);
            page.unroute(compiledPattern.asPredicate());
            logger.info("✓ Route removed for pattern: {}", pattern);
        } catch (Exception e) {
            logger.warn("Failed to remove route for pattern {}: {}", pattern, e.getMessage());
        }
        logger.info("========== Mock Stopped ==========");
    }

    /**
     * 移除指定的Mock规则
     */
    public static void removeMock(String mockName) {
        mockRules.remove(mockName);
        logger.info("Removed mock rule: {}", mockName);
    }
    
    /**
     * 获取所有Mock规则
     */
    public static Map<String, MockRule> getAllMockRules() {
        return new HashMap<>(mockRules);
    }

    // ==================== Serenity报告集成 ====================

    /**
     * 记录Mock配置和API调用历史到Serenity报告
     */
    public static void recordToSerenityReport() {
        recordMockConfiguration();
        recordApiCallHistory();
    }

    /**
     * 记录Mock配置到Serenity报告
     */
    private static void recordMockConfiguration() {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"title\": \"Mock Configuration\",\n");
            json.append("  \"totalRules\": ").append(mockRules.size()).append(",\n");

            if (mockRules.isEmpty()) {
                json.append("  \"message\": \"No mock rules configured\"\n");
            } else {
                json.append("  \"rules\": [\n");
                int index = 1;
                for (MockRule rule : mockRules.values()) {
                    json.append("    {\n");
                    json.append("      \"#\": ").append(index++).append(",\n");
                    json.append("      \"name\": \"").append(escapeJson(rule.getName())).append("\",\n");
                    json.append("      \"urlPattern\": \"").append(escapeJson(rule.getUrlPattern())).append("\",\n");
                    json.append("      \"method\": \"").append(rule.getMethod() != null ? escapeJson(rule.getMethod()) : "ANY").append("\",\n");
                    json.append("      \"statusCode\": ").append(rule.getStatusCode()).append(",\n");
                    json.append("      \"enabled\": ").append(rule.isEnabled()).append(",\n");
                    json.append("      \"delayMs\": ").append(rule.getDelayMs()).append("\n");
                    json.append("    }").append(index <= mockRules.size() ? "," : "").append("\n");
                }
                json.append("  ]\n");
            }
            json.append("}\n");

            Serenity.recordReportData().withTitle("Mock Configuration").andContents(json.toString());
            logger.info(" Recorded mock configuration to Serenity report");
        } catch (Exception e) {
            logger.warn("Failed to record mock configuration to Serenity report", e);
        }
    }

    /**
     * 记录API调用历史到Serenity报告
     */
    private static void recordApiCallHistory() {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"title\": \"API Call History\",\n");
            json.append("  \"totalApiCalls\": ").append(apiCallHistory.size()).append(",\n");

            if (apiCallHistory.isEmpty()) {
                json.append("  \"message\": \"No API calls recorded yet\"\n");
            } else {
                json.append("  \"apiCalls\": [\n");
                for (int i = 0; i < apiCallHistory.size(); i++) {
                    ApiCallRecord record = apiCallHistory.get(i);
                    json.append("    {\n");
                    json.append("      \"#\": ").append(i + 1).append(",\n");
                    json.append("      \"type\": \"").append(record.isMocked() ? "Mock" : "Real").append("\",\n");
                    json.append("      \"url\": \"").append(escapeJson(record.getUrl())).append("\",\n");
                    json.append("      \"method\": \"").append(record.getMethod()).append("\",\n");
                    json.append("      \"statusCode\": ").append(record.getStatusCode()).append(",\n");
                    json.append("      \"responseTimeMs\": ").append(record.getResponseTimeMs()).append("\n");
                    json.append("    }").append(i < apiCallHistory.size() - 1 ? "," : "").append("\n");
                }
                json.append("  ],\n");

                // 统计信息
                long mockCount = apiCallHistory.stream().filter(ApiCallRecord::isMocked).count();
                long realCount = apiCallHistory.size() - mockCount;

                json.append("  \"summary\": {\n");
                json.append("    \"realApiCount\": ").append(realCount).append(",\n");
                json.append("    \"mockApiCount\": ").append(mockCount).append(",\n");
                json.append("    \"realApiPercentage\": \"").append(String.format("%.1f%%", realCount * 100.0 / apiCallHistory.size())).append("\",\n");
                json.append("    \"mockApiPercentage\": \"").append(String.format("%.1f%%", mockCount * 100.0 / apiCallHistory.size())).append("\"\n");
                json.append("  }\n");
            }
            json.append("}\n");

            Serenity.recordReportData().withTitle("API Call History").andContents(json.toString());
            logger.info(" Recorded API call history to Serenity report");
        } catch (Exception e) {
            logger.warn("Failed to record API call history to Serenity report", e);
        }
    }

    /**
     * 转义JSON特殊字符
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
    
    // ==================== 预定义的常用Mock方法 ====================

    /**
     * Mock一个成功的响应
     * @param name 规则名称
     * @param urlPattern URL 匹配模式
     * @param responseData 完整的响应数据（由调用者提供）
     */
    public static void mockDirectSuccess(String name, String urlPattern, String responseData) {
        registerMock(name, urlPattern, responseData);
    }

    /**
     * Mock一个失败的响应
     * @param name 规则名称
     * @param urlPattern URL 匹配模式
     * @param errorData 完整的错误响应数据（由调用者提供）
     */
    public static void mockDirectError(String name, String urlPattern, String errorData) {
        MockRule rule = new MockRule(name, urlPattern)
            .statusCode(500)
            .mockDataJson(errorData);
        registerMockRule(rule);
    }

    /**
     * Mock超时（长延迟）
     * @param name 规则名称
     * @param urlPattern URL 匹配模式
     * @param timeoutMs 超时时间（毫秒）
     * @param responseData 完整的响应数据（由调用者提供）
     */
    public static void mockTimeout(String name, String urlPattern, long timeoutMs, String responseData) {
        MockRule rule = new MockRule(name, urlPattern)
            .delay(timeoutMs)
            .statusCode(408)
            .mockDataJson(responseData);
        registerMockRule(rule);
    }

    /**
     * Mock 404 Not Found
     * @param name 规则名称
     * @param urlPattern URL 匹配模式
     * @param responseData 完整的 404 响应数据（由调用者提供）
     */
    public static void mockNotFound(String name, String urlPattern, String responseData) {
        MockRule rule = new MockRule(name, urlPattern)
            .statusCode(404)
            .mockDataJson(responseData);
        registerMockRule(rule);
    }

    /**
     * 动态生成响应（基于请求内容）
     */
    public static void registerDynamicMock(String name, String urlPattern, ResponseGenerator generator) {
        MockRule rule = new MockRule(name, urlPattern)
            .responseGenerator(generator);
        registerMockRule(rule);
    }

    // ==================== Mock Builder ====================

    /**
     * Mock构建器 - 使用Builder模式配置Mock
     *
     * 示例用法（单个Mock）：
     * ApiMonitorAndMockManager.mock(context)
     *     .forUrl(".*api/users.*")
     *     .withStatus(200)
     *     .withResponse("{\"status\":\"success\"}")
     *     .build();
     *
     * 示例用法（多个Mock）：
     * ApiMonitorAndMockManager.mock(context)
     *     .addMock(new MockRule("users", ".*api/users.*")
     *         .mockDataJson("{\"status\":\"success\"}"))
     *     .addMock(new MockRule("products", ".*api/products.*")
     *         .statusCode(201))
     *     .build();
     */
    public static class MockBuilder {
        private final Page page;
        private final BrowserContext context;
        private final List<MockRule> mockRules;
        private boolean autoClearRules = true;

        private MockBuilder(Page page) {
            this.page = page;
            this.context = null;
            this.mockRules = new ArrayList<>();
        }

        private MockBuilder(BrowserContext context) {
            this.page = null;
            this.context = context;
            this.mockRules = new ArrayList<>();
        }

        /**
         * 设置要Mock的URL模式（开始链式调用）
         *
         * @param urlPattern URL匹配模式（支持普通URL或正则）
         * @return this构建器实例
         */
        public MockBuilder forUrl(String urlPattern) {
            String pattern = toRegexPattern(urlPattern);
            MockRule rule = new MockRule("mock-" + pattern, pattern);
            mockRules.add(rule);
            return this;
        }

        /**
         * 添加Mock规则（用于链式调用）
         *
         * @param rule Mock规则对象
         * @return this构建器实例
         */
        public MockBuilder withMock(MockRule rule) {
            mockRules.add(rule);
            return this;
        }

        /**
         * 添加多个Mock规则（用于链式调用）
         *
         * @param rules Mock规则列表
         * @return this构建器实例
         */
        public MockBuilder withMocks(List<MockRule> rules) {
            mockRules.addAll(rules);
            return this;
        }

        /**
         * 添加Mock规则（别名方法）
         *
         * @param rule Mock规则对象
         * @return this构建器实例
         */
        public MockBuilder addMock(MockRule rule) {
            mockRules.add(rule);
            return this;
        }

        /**
         * 设置响应状态码（针对最后一个添加的规则）
         *
         * @param statusCode HTTP状态码
         * @return this构建器实例
         */
        public MockBuilder withStatus(int statusCode) {
            if (!mockRules.isEmpty()) {
                mockRules.get(mockRules.size() - 1).statusCode(statusCode);
            }
            return this;
        }

        /**
         * 设置响应数据（针对最后一个添加的规则）
         *
         * @param responseData 响应数据
         * @return this构建器实例
         */
        public MockBuilder withResponse(String responseData) {
            if (!mockRules.isEmpty()) {
                mockRules.get(mockRules.size() - 1).mockDataJson(responseData);
            }
            return this;
        }

        /**
         * 设置延迟（针对最后一个添加的规则）
         *
         * @param delayMs 延迟时间（毫秒）
         * @return this构建器实例
         */
        public MockBuilder withDelay(long delayMs) {
            if (!mockRules.isEmpty()) {
                mockRules.get(mockRules.size() - 1).delay(delayMs);
            }
            return this;
        }

        /**
         * 添加响应头（针对最后一个添加的规则）
         *
         * @param key 响应头名称
         * @param value 响应头值
         * @return this构建器实例
         */
        public MockBuilder withHeader(String key, String value) {
            if (!mockRules.isEmpty()) {
                mockRules.get(mockRules.size() - 1).header(key, value);
            }
            return this;
        }

        /**
         * 设置响应生成器（针对最后一个添加的规则）
         *
         * @param generator 响应生成器
         * @return this构建器实例
         */
        public MockBuilder withGenerator(ResponseGenerator generator) {
            if (!mockRules.isEmpty()) {
                mockRules.get(mockRules.size() - 1).responseGenerator(generator);
            }
            return this;
        }

        /**
         * 设置请求拦截器（针对最后一个添加的规则）
         *
         * @param interceptor 请求拦截器
         * @return this构建器实例
         */
        public MockBuilder withInterceptor(RequestInterceptor interceptor) {
            if (!mockRules.isEmpty()) {
                mockRules.get(mockRules.size() - 1).requestInterceptor(interceptor);
            }
            return this;
        }

        /**
         * 是否自动清空旧的Mock规则（默认true）
         *
         * @param autoClear true表示自动清空，false表示不清空
         * @return this构建器实例
         */
        public MockBuilder autoClearRules(boolean autoClear) {
            this.autoClearRules = autoClear;
            return this;
        }

        /**
         * 构建并应用Mock规则
         */
        public void build() {
            logger.info("========== Building Mock Configuration ==========");
            logger.info("Total mocks to configure: {}", mockRules.size());
            for (MockRule rule : mockRules) {
                logger.info("  - {} -> Status: {}, URL: {}",
                        rule.getName(), rule.getStatusCode(), rule.getUrlPattern());
            }

            if (autoClearRules) {
                clearAllMocks();
            }

            // 注册所有Mock规则
            for (MockRule rule : mockRules) {
                registerMockRule(rule);
            }

            // 应用Mock规则到Page或Context
            if (page != null) {
                applyMocks(page);
            } else if (context != null) {
                applyMocks(context);
            }

            // 记录到Serenity报告
            recordToSerenityReport();

            logger.info(" Mock configuration built successfully!");
        }
    }
}
