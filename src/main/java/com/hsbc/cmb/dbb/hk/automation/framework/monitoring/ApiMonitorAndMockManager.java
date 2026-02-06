package com.hsbc.cmb.dbb.hk.automation.framework.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
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
 * 3. 修改请求信息（如headers、params、body）
 * 4. 记录API调用历史
 * 5. 动态响应生成
 */
public class ApiMonitorAndMockManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ApiMonitorAndMockManager.class);
    private static final Config config = ConfigFactory.load();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // 存储已注册的 Mock 规则
    private static final Map<String, MockRule> mockRules = new ConcurrentHashMap<>();
    // 存储API调用历史
    private static final List<ApiCallRecord> apiCallHistory = new CopyOnWriteArrayList<>();
    // 是否全局启用 Mock
    private static boolean globalMockEnabled = false;
    
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
        private RequestModifier requestModifier; // 请求修改器
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
        public RequestModifier getRequestModifier() { return requestModifier; }
        public ResponseGenerator getResponseGenerator() { return responseGenerator; }
    }
    
    /**
     * 请求修改器接口
     */
    @FunctionalInterface
    public interface RequestModifier {
        void modify(Request request);
    }
    
    /**
     * 响应生成器接口
     */
    @FunctionalInterface
    public interface ResponseGenerator {
        String generate(Request request, Map<String, Object> context);
    }
    
    /**
     * 初始化API监控和mock管理器
     */
    public static void initialize() {
        logger.info("Initializing ApiMonitorAndMockManager...");
        
        // 读取全局Mock开关
        globalMockEnabled = config.hasPath("api.monitor.enabled") 
            && config.getBoolean("api.monitor.enabled");
        
        // 从配置文件加载Mock规则
        if (config.hasPath("api.mock.rules")) {
            logger.info("Loading mock rules from configuration...");
            // 这里可以扩展从配置文件加载规则
        }
        
        logger.info("ApiMonitorAndMockManager initialized. Global mock enabled: {}", globalMockEnabled);
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
     * 全局启用所有Mock
     */
    public static void enableGlobalMock() {
        globalMockEnabled = true;
        logger.info("Global mock enabled");
    }
    
    /**
     * 全局禁用所有Mock
     */
    public static void disableGlobalMock() {
        globalMockEnabled = false;
        logger.info("Global mock disabled");
    }
    
    // 存储context或page引用，确保mock规则应用到同一个实例
private static Page currentPage;
private static BrowserContext currentContext;
    
/**
 * 为Page应用所有Mock规则
 */
public static void applyMocks(Page page) {
    if (!globalMockEnabled) {
        logger.debug("Global mock is disabled, skipping mock application");
        return;
    }
    
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
    if (!globalMockEnabled) {
        logger.debug("Global mock is disabled, skipping mock application");
        return;
    }
    
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
        context.route(rule.getUrlPattern(), route -> {
            handleMockRoute(route, rule);
        });
        
        logger.info("Applied mock rule '{}' to context for pattern: {}", rule.getName(), rule.getUrlPattern());
    }
    
    /**
     * 处理Mock路由
     */
    private static void handleMockRoute(Route route, MockRule rule) {
        try {
            // 记录请求信息
            recordApiCall(route, rule, null);
            
            logger.info("Intercepted request: {} {} - Applying mock rule: {}", 
                route.request().method(), route.request().url(), rule.getName());
            
            // 检查HTTP方法是否匹配
            if (!Pattern.matches(rule.getMethod(), route.request().method())) {
                route.resume();
                return;
            }
            
            // 修改请求（如果需要）
            if (rule.requestModifier != null) {
                rule.requestModifier.modify(route.request());
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
                    route.request().url(), rule.getStatusCode());
            } else {
                logger.warn("No mock data available for rule: {}, resuming original request", rule.getName());
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
    
    /**
     * 修改请求（添加header）
     */
    public static void modifyRequestAddHeader(String name, String urlPattern, String headerKey, String headerValue) {
        MockRule rule = new MockRule(name, urlPattern)
            .requestModifier(request -> {
                // Playwright的Request接口没有header方法用于修改，使用headers方法
                Map<String, String> headers = request.headers();
                headers.put(headerKey, headerValue);
            });
        registerMockRule(rule);
    }
}