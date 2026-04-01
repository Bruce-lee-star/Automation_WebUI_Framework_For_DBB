package com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
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
 * 功能：监测API请求和响应、Mock API响应、修改请求信息、记录API调用历史等
 *
 * 详细使用说明请参考：src/test/resources/API_Mock_User_Guide.md
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
        private final boolean isMocked;
        
        public ApiCallRecord(String requestId, String url, String method, long timestamp, 
                          Map<String, String> requestHeaders, Object requestBody, 
                          int statusCode, Map<String, String> responseHeaders, 
                          Object responseBody, boolean isMocked) {
            this.requestId = requestId;
            this.url = url;
            this.method = method;
            this.timestamp = timestamp;
            this.requestHeaders = requestHeaders;
            this.requestBody = requestBody;
            this.statusCode = statusCode;
            this.responseHeaders = responseHeaders;
            this.responseBody = responseBody;
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
        public boolean isMocked() { return isMocked; }
    }
    
    /**
     * Mock规则
     */
    public static class MockRule {
        private String name;
        private String urlPattern;
        private String hostUrl; // Host URL
        private String endpoint; // API endpoint
        private String method; // GET, POST, etc.
        private String mockDataPath; // JSON文件路径
        private String mockDataJson; // 直接提供JSON字符串
        private int statusCode;
        private Map<String, String> headers;
        private long delayMs; // 模拟延迟
        private boolean enabled;
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

        public MockRule responseGenerator(ResponseGenerator responseGenerator) {
            this.responseGenerator = responseGenerator;
            return this;
        }

        public MockRule hostUrl(String hostUrl) {
            this.hostUrl = hostUrl;
            return this;
        }

        public MockRule urlPattern(String urlPattern) {
            this.urlPattern = urlPattern;
            return this;
        }

        public MockRule endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        // ==================== 网络模拟方法 ====================

        public String getName() { return name; }
        public String getUrlPattern() { return urlPattern; }
        public String getHostUrl() { return hostUrl; }
        public String getEndpoint() { return endpoint; }
        public String getMethod() { return method; }
        public String getMockDataPath() { return mockDataPath; }
        public String getMockDataJson() { return mockDataJson; }
        public int getStatusCode() { return statusCode; }
        public Map<String, String> getHeaders() { return headers; }
        public long getDelayMs() { return delayMs; }
        public boolean isEnabled() { return enabled; }
        public ResponseGenerator getResponseGenerator() { return responseGenerator; }
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
        String pattern = toGlobPattern(urlPattern);
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
        String pattern = toGlobPattern(urlPattern);
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
        String pattern = toGlobPattern(urlPattern);
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
        String pattern = toGlobPattern(urlPattern);
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

    // ==================== 高级API ====================

    /**
     * 【高级】动态响应生成 - 根据请求内容生成响应
     * 适用于需要根据请求参数返回不同响应的场景
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param generator 响应生成器，接收请求和上下文，返回响应字符串
     *
     * 示例：
     * mockDynamic(page, "/api/users", (request, ctx) -> {
     *     String method = request.method();
     *     if ("POST".equals(method)) {
     *         return "{\"status\":\"created\"}";
     *     }
     *     return "{\"status\":\"success\"}";
     * });
     */
    public static void mockDynamic(Page page, String urlPattern, ResponseGenerator generator) {
        String pattern = toGlobPattern(urlPattern);
        logger.info("========== Mocking Dynamic API: {} ==========", pattern);

        MockRule rule = new MockRule("mock-dynamic-" + pattern, pattern)
            .responseGenerator(generator);
        registerMockRule(rule);
        applyMocks(page);
        recordMockConfiguration();

        logger.info(" Mock Dynamic API configured successfully!");
    }

    /**
     * 【高级】动态响应生成 - 根据请求内容生成响应
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     * @param generator 响应生成器
     *
     * 示例：
     * mockDynamic(context, "/api/users", (request, ctx) -> {
     *     return "{\"dynamic\": \"response\"}";
     * });
     */
    public static void mockDynamic(BrowserContext context, String urlPattern, ResponseGenerator generator) {
        String pattern = toGlobPattern(urlPattern);
        logger.info("========== Mocking Dynamic API: {} ==========", pattern);

        MockRule rule = new MockRule("mock-dynamic-" + pattern, pattern)
            .responseGenerator(generator);
        registerMockRule(rule);
        applyMocks(context);
        recordMockConfiguration();

        logger.info(" Mock Dynamic API configured successfully!");
    }

    // ==================== URL工具方法 ====================

    /**
     * 将普通URL模式转换为 glob 模式
     * 用于 Playwright 的 route() 方法
     *
     * @param urlPattern URL模式（如 /api/users, api/users, users）
     * @return glob 模式
     */
    private static String toGlobPattern(String urlPattern) {
        if (urlPattern == null || urlPattern.isEmpty()) {
            return "**";
        }

        // 移除开头的斜杠（如果有）
        String normalized = urlPattern;
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        // 返回 glob 模式
        return "**/" + normalized + "**";
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
    
    // ==================== 内部方法（不对外暴露） ====================

    /**
     * 【内部方法】注册Mock规则
     * 用户不需要直接调用，使用 mock() 或 mockDirectResponse() 等简化方法
     */
    private static void registerMockRule(MockRule rule) {
        mockRules.put(rule.getName(), rule);
        logger.info("Registered mock rule: {} for URL pattern: {}", rule.getName(), rule.getUrlPattern());
    }
    
    /**
     * 【内部方法】快速注册简单的Mock规则
     */
    private static void registerMock(String name, String urlPattern, String mockDataJson) {
        MockRule rule = new MockRule(name, urlPattern)
            .mockDataJson(mockDataJson);
        registerMockRule(rule);
    }
    
    /**
     * 【内部方法】快速注册从文件读取的Mock规则
     */
    private static void registerMockFromFile(String name, String urlPattern, String jsonFilePath) {
        MockRule rule = new MockRule(name, urlPattern)
            .mockDataPath(jsonFilePath);
        registerMockRule(rule);
    }
    
    /**
     * 【内部方法】启用指定的Mock规则
     */
    private static void enableMock(String mockName) {
        MockRule rule = mockRules.get(mockName);
        if (rule != null) {
            rule.enabled(true);
            logger.info("Enabled mock rule: {}", mockName);
        } else {
            logger.warn("Mock rule not found: {}", mockName);
        }
    }
    
    /**
     * 【内部方法】禁用指定的Mock规则
     */
    private static void disableMock(String mockName) {
        MockRule rule = mockRules.get(mockName);
        if (rule != null) {
            rule.enabled(false);
            logger.info("Disabled mock rule: {}", mockName);
        }
    }

    /**
     * 为Page应用所有Mock规则
     */
    private static void applyMocks(Page page) {
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
    private static void applyMocks(BrowserContext context) {
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
        String globPattern = rule.getUrlPattern();

        page.route(globPattern, route -> {
            handleMockRoute(route, rule);
        });

        logger.info("Applied mock rule '{}' to page for glob pattern: {}", rule.getName(), globPattern);
    }

    /**
     * 应用单个Mock规则到BrowserContext
     */
    private static void applyMockRule(BrowserContext context, MockRule rule) {
        String globPattern = rule.getUrlPattern();

        context.route(globPattern, route -> {
            handleMockRoute(route, rule);
        });

        logger.info("Applied mock rule '{}' to context for glob pattern: {}", rule.getName(), globPattern);
    }
    
    /**
     * 处理Mock路由
     */
    private static void handleMockRoute(Route route, MockRule rule) {
        try {
            Request request = route.request();
            String url = request.url();

            // 检查 hostUrl 和 endpoint 是否匹配
            if (rule.getHostUrl() != null && !rule.getHostUrl().isEmpty()) {
                if (!url.contains(rule.getHostUrl())) {
                    logger.debug("URL does not match hostUrl: {}, skipping", rule.getHostUrl());
                    route.resume();
                    return;
                }
            }

            if (rule.getEndpoint() != null && !rule.getEndpoint().isEmpty()) {
                if (!url.contains(rule.getEndpoint())) {
                    logger.debug("URL does not match endpoint: {}, skipping", rule.getEndpoint());
                    route.resume();
                    return;
                }
            }

            // 记录请求信息
            recordApiCall(route, rule, null);

            logger.info("Intercepted request: {} {} - Applying rule: {}",
                request.method(), url, rule.getName());

            // 检查HTTP方法是否匹配
            if (!Pattern.matches(rule.getMethod(), request.method())) {
                route.resume();
                return;
            }

            // Mock响应
            handleNormalMock(route, rule);

        } catch (Exception e) {
            logger.error("Error handling mock route for rule: " + rule.getName(), e);
            route.resume();
        }
    }

    /**
     * 处理普通Mock（包括延迟）
     */
    private static void handleNormalMock(Route route, MockRule rule) {
        Request request = route.request();

        // 获取Mock数据或生成响应
        String mockData = null;
        try {
            mockData = getMockData(rule, route);
        } catch (Exception e) {
            logger.error("Failed to get mock data for rule: {}, continuing original request", rule.getName(), e);
        }

        if (mockData != null) {
            // 准备响应选项
            Route.FulfillOptions options = new Route.FulfillOptions()
                .setStatus(rule.getStatusCode())
                .setBody(mockData);

            // 设置响应头
            if (rule.getHeaders() != null && !rule.getHeaders().isEmpty()) {
                options.setHeaders(rule.getHeaders());
            }

            // 异步处理延迟 - 不阻塞其他API请求
            if (rule.getDelayMs() > 0) {
                new Thread(() -> {
                    try {
                        logger.info("Delaying response for {} by {}ms", request.url(), rule.getDelayMs());
                        Thread.sleep(rule.getDelayMs());
                        route.fulfill(options);
                        logger.info("Mock response sent for: {} with status: {} after delay",
                            request.url(), rule.getStatusCode());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.error("Delay interrupted for: {}", request.url(), e);
                    } catch (Exception e) {
                        logger.error("Failed to send delayed response for: {}", request.url(), e);
                    }
                }).start();
            } else {
                // 无延迟，立即返回响应
                try {
                    route.fulfill(options);
                    logger.info("Mock response sent for: {} with status: {}",
                        request.url(), rule.getStatusCode());
                } catch (Exception e) {
                    logger.error("Failed to fulfill mock response for: {}", request.url(), e);
                    try {
                        route.resume();
                    } catch (Exception ex) {
                        logger.error("Failed to resume route after fulfillment error for: {}", request.url(), ex);
                    }
                }
            }
        } else {
            logger.info("No mock data available for rule: {}, continuing original request", rule.getName());
            try {
                route.resume();
            } catch (Exception e) {
                logger.error("Failed to resume route for: {}", request.url(), e);
            }
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
                requestHeaders, requestBody, statusCode, responseHeaders, responseBody, isMocked
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
     * @param urlPattern URL匹配模式
     */
    public static void stopMock(BrowserContext context, String urlPattern) {
        String globPattern = toGlobPattern(urlPattern);
        logger.info("========== Stopping Mock for URL: {} ==========", globPattern);
        try {
            context.unroute(globPattern);
            logger.info("✓ Route removed for glob pattern: {}", globPattern);
        } catch (Exception e) {
            logger.warn("Failed to remove route for glob pattern {}: {}", globPattern, e.getMessage());
        }
        logger.info("========== Mock Stopped ==========");
    }

    /**
     * 停止指定URL的Mock - 只移除特定URL的route（Page版本）
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式
     */
    public static void stopMock(Page page, String urlPattern) {
        String globPattern = toGlobPattern(urlPattern);
        logger.info("========== Stopping Mock for URL: {} ==========", globPattern);
        try {
            page.unroute(globPattern);
            logger.info("✓ Route removed for glob pattern: {}", globPattern);
        } catch (Exception e) {
            logger.warn("Failed to remove route for glob pattern {}: {}", globPattern, e.getMessage());
        }
        logger.info("========== Mock Stopped ==========");
    }

    /**
     * 【内部方法】移除指定的Mock规则
     * 用户不需要直接调用，使用 stopMock() 或 stopAllMocks()
     */
    private static void removeMock(String mockName) {
        mockRules.remove(mockName);
        logger.info("Removed mock rule: {}", mockName);
    }
    
    // ==================== 查询API（用于测试验证） ====================

    /**
     * 获取已注册的Mock规则数量
     */
    public static int getMockRuleCount() {
        return mockRules.size();
    }

    /**
     * 检查是否存在指定URL模式的Mock规则
     */
    public static boolean hasMockForUrl(String urlPattern) {
        String globPattern = toGlobPattern(urlPattern);
        return mockRules.values().stream()
                .anyMatch(rule -> rule.getUrlPattern().equals(globPattern));
    }

    /**
     * 获取所有Mock规则（用于测试验证）
     * 注意：此方法主要用于测试目的，正常业务代码不需要使用
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
                    json.append("      \"statusCode\": ").append(record.getStatusCode()).append("\n");
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
    
    // ==================== 内部预定义Mock方法（不对外暴露） ====================

    /**
     * 【内部方法】Mock一个成功的响应
     * 用户应使用 mockDirectSuccess(page, urlPattern, responseData)
     */
    private static void mockDirectSuccess(String name, String urlPattern, String responseData) {
        registerMock(name, urlPattern, responseData);
    }

    /**
     * 【内部方法】Mock一个失败的响应
     * 用户应使用 mockDirectError(page, urlPattern, statusCode, errorData)
     */
    private static void mockDirectError(String name, String urlPattern, String errorData) {
        MockRule rule = new MockRule(name, urlPattern)
            .statusCode(500)
            .mockDataJson(errorData);
        registerMockRule(rule);
    }

    /**
     * 【内部方法】Mock超时（长延迟）
     * 用户应使用 mockTimeout(page, urlPattern, timeoutMs, responseData)
     */
    private static void mockTimeout(String name, String urlPattern, long timeoutMs, String responseData) {
        MockRule rule = new MockRule(name, urlPattern)
            .delay(timeoutMs)
            .statusCode(408)
            .mockDataJson(responseData);
        registerMockRule(rule);
    }

    /**
     * 【内部方法】Mock 404 Not Found
     * 用户应使用 mockDirectError(page, urlPattern, 404, responseData)
     */
    private static void mockNotFound(String name, String urlPattern, String responseData) {
        MockRule rule = new MockRule(name, urlPattern)
            .statusCode(404)
            .mockDataJson(responseData);
        registerMockRule(rule);
    }

    /**
     * 【内部方法】动态生成响应（基于请求内容）
     * 高级用户可使用 registerDynamicMock()
     */
    private static void registerDynamicMock(String name, String urlPattern, ResponseGenerator generator) {
        MockRule rule = new MockRule(name, urlPattern)
            .responseGenerator(generator);
        registerMockRule(rule);
    }

    // ==================== Mock Builder ====================

    /**
     * Mock构建器 - 使用Builder模式配置Mock
     *
     * 使用方式：
     * 1. forEndpoint() - 指定 API endpoint，自动匹配包含该 endpoint 的请求
     * 2. forUrl() - 指定 Host URL（可选），用于精确匹配特定域名
     *
     * 示例用法（Mock 响应）：
     * ApiMonitorAndMockManager.mock(context)
     *     .forEndpoint("/api/users")
     *     .withStatus(200)
     *     .withResponse("{\"status\":\"success\"}")
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
         * 设置要Mock的URL模式（支持 glob 模式）
         * 
         * 每次调用 forUrl() 都会创建一个新的 Mock 规则，支持链式创建多个规则
         * 
         * @param urlPattern URL 模式
         * @return this构建器实例
         */
        public MockBuilder forUrl(String urlPattern) {
            // 转换为 glob 模式
            String globPattern = toGlobPattern(urlPattern);
            
            // 每次调用都创建新规则（支持链式创建多个规则）
            MockRule rule = new MockRule("mock-" + urlPattern, globPattern);
            mockRules.add(rule);
            
            return this;
        }

        /**
         * 设置要Mock的Endpoint（API的路径）
         * 
         * 每次调用 forEndpoint() 都会创建一个新的 Mock 规则，支持链式创建多个规则
         *
         * @param endpoint Endpoint（如：/api/users）
         * @return this构建器实例
         *
         * 示例：单独使用（匹配所有包含 /api/users 的请求）
         *   .forEndpoint("/api/users")
         */
        public MockBuilder forEndpoint(String endpoint) {
            // 使用 ".*" 匹配所有请求，在 handleMockRoute 中通过 request.url().contains() 判断
            MockRule rule = new MockRule("mock-" + endpoint, ".*");
            rule.endpoint(endpoint);
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
