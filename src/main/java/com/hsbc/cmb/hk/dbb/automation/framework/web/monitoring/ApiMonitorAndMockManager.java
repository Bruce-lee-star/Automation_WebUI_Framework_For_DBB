package com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
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
 *   mockApi(context, "/api/users", 200, "{\"status\":\"success\"}");
 *   mockSuccess(context, "/api/users", "{\"status\":\"success\"}");
 *   mockError(context, "/api/users", 500, "{\"status\":\"error\"}");
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
     * mockApi(page, "/api/users", 200, "{\"status\":\"success\"}");
     * mockApi(page, ".*api/products.*", 201, "{\"status\":\"created\"}");
     */
    public static void mockApi(Page page, String urlPattern, int statusCode, String responseData) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Mocking API: {} (Status: {}) ==========", pattern, statusCode);
        logger.info("Original URL pattern: '{}' -> Converted to: '{}'", urlPattern, pattern);

        MockRule rule = new MockRule("mock-" + pattern, pattern)
            .statusCode(statusCode)
            .mockDataJson(responseData);
        registerMockRule(rule);
        applyMocks(page);
        recordMockConfiguration();

        logger.info("✅ Mock API configured successfully!");
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
     * mockApi(context, "/api/users", 200, "{\"status\":\"success\"}");
     */
    public static void mockApi(BrowserContext context, String urlPattern, int statusCode, String responseData) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Mocking API: {} (Status: {}) ==========", pattern, statusCode);

        MockRule rule = new MockRule("mock-" + pattern, pattern)
            .statusCode(statusCode)
            .mockDataJson(responseData);
        registerMockRule(rule);
        applyMocks(context);
        recordMockConfiguration();

        logger.info("✅ Mock API configured successfully!");
    }

    /**
     * 【简化】Mock成功响应（默认状态码200）
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param responseData Mock响应数据
     *
     * 示例：
     * mockSuccess(page, "/api/users", "{\"status\":\"success\"}");
     */
    public static void mockSuccess(Page page, String urlPattern, String responseData) {
        mockApi(page, urlPattern, 200, responseData);
    }

    /**
     * 【简化】Mock成功响应（默认状态码200）
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param responseData Mock响应数据
     *
     * 示例：
     * mockSuccess(context, "/api/users", "{\"status\":\"success\"}");
     */
    public static void mockSuccess(BrowserContext context, String urlPattern, String responseData) {
        mockApi(context, urlPattern, 200, responseData);
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
     * mockError(page, "/api/users", 500, "{\"status\":\"error\"}");
     * mockError(page, "/api/users", 404, "{\"status\":\"not found\"}");
     */
    public static void mockError(Page page, String urlPattern, int statusCode, String errorData) {
        mockApi(page, urlPattern, statusCode, errorData);
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
     * mockError(context, "/api/users", 500, "{\"status\":\"error\"}");
     */
    public static void mockError(BrowserContext context, String urlPattern, int statusCode, String errorData) {
        mockApi(context, urlPattern, statusCode, errorData);
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

        logger.info("✅ Mock API with timeout configured successfully!");
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

        logger.info("✅ Mock API with timeout configured successfully!");
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

        logger.info("✅ Mock API with dynamic response configured successfully!");
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

        logger.info("✅ Mock API with dynamic response configured successfully!");
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

        logger.info("✅ Request header modifier configured successfully!");
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

        logger.info("✅ Request header modifier configured successfully!");
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

        logger.info("✅ Request body modifier configured successfully!");
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

        logger.info("✅ Request body modifier configured successfully!");
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

        logger.info("✅ Request query param modifier configured successfully!");
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

        logger.info("✅ Request query param modifier configured successfully!");
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

        logger.info("✅ Request method modifier configured successfully!");
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

        logger.info("✅ Request method modifier configured successfully!");
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

        logger.info("✅ Request interceptor configured successfully!");
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

        logger.info("✅ Request interceptor configured successfully!");
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
            logger.info("✅ Recorded mock configuration to Serenity report");
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
            logger.info("✅ Recorded API call history to Serenity report");
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
    public static void mockSuccess(String name, String urlPattern, String responseData) {
        registerMock(name, urlPattern, responseData);
    }

    /**
     * Mock一个失败的响应
     * @param name 规则名称
     * @param urlPattern URL 匹配模式
     * @param errorData 完整的错误响应数据（由调用者提供）
     */
    public static void mockError(String name, String urlPattern, String errorData) {
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

            logger.info("✅ Mock configuration built successfully!");
        }
    }
}