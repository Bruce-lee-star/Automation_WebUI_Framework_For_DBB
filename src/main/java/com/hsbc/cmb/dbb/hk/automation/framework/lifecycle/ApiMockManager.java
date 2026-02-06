package com.hsbc.cmb.dbb.hk.automation.framework.lifecycle;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * API Mock Manager - 管理 API 请求的拦截和 Mock
 * 用于在 Web 自动化测试中 mock 不稳定的接口
 * 
 * 使用场景：
 * 1. 某些后端接口不稳定，经常报错
 * 2. 需要模拟特定的响应数据进行测试
 * 3. 需要测试异常场景（500错误、超时等）
 * 
 * 使用示例：
 * <pre>
 * // 在 CommonSteps 或测试步骤中：
 * ApiMockManager.enableMock("getUserInfo");
 * ApiMockManager.mockApi("/api/user/info", "{\"status\": \"success\", \"data\": {...}}");
 * </pre>
 */
public class ApiMockManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ApiMockManager.class);
    private static final Config config = ConfigFactory.load();
    
    // 存储已注册的 Mock 规则
    private static final Map<String, MockRule> mockRules = new HashMap<>();
    
    // 是否全局启用 Mock
    private static boolean globalMockEnabled = false;
    
    /**
     * Mock 规则
     */
    public static class MockRule {
        private String name;
        private String urlPattern;
        private String method; // GET, POST, etc.
        private String mockDataPath; // JSON 文件路径
        private String mockDataJson; // 直接提供 JSON 字符串
        private int statusCode;
        private Map<String, String> headers;
        private long delayMs; // 模拟延迟
        private boolean enabled;
        
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
        
        // Getters
        public String getName() { return name; }
        public String getUrlPattern() { return urlPattern; }
        public String getMethod() { return method; }
        public String getMockDataPath() { return mockDataPath; }
        public String getMockDataJson() { return mockDataJson; }
        public int getStatusCode() { return statusCode; }
        public Map<String, String> getHeaders() { return headers; }
        public long getDelayMs() { return delayMs; }
        public boolean isEnabled() { return enabled; }
    }
    
    /**
     * 初始化 Mock 管理器
     * 从配置文件加载预定义的 Mock 规则
     */
    public static void initialize() {
        logger.info("Initializing ApiMockManager...");
        
        // 读取全局 Mock 开关
        globalMockEnabled = config.hasPath("api.mock.enabled") 
            && config.getBoolean("api.mock.enabled");
        
        // 从配置文件加载 Mock 规则
        if (config.hasPath("api.mock.rules")) {
            // 这里可以扩展从配置文件加载规则
            logger.info("Loading mock rules from configuration...");
        }
        
        logger.info("ApiMockManager initialized. Global mock enabled: {}", globalMockEnabled);
    }
    
    /**
     * 注册 Mock 规则
     */
    public static void registerMockRule(MockRule rule) {
        mockRules.put(rule.getName(), rule);
        logger.info("Registered mock rule: {} for URL pattern: {}", rule.getName(), rule.getUrlPattern());
    }
    
    /**
     * 快速注册简单的 Mock 规则
     * 
     * @param name 规则名称
     * @param urlPattern URL 匹配模式（支持正则）
     * @param mockDataJson Mock 响应数据（JSON 字符串）
     */
    public static void registerMock(String name, String urlPattern, String mockDataJson) {
        MockRule rule = new MockRule(name, urlPattern)
            .mockDataJson(mockDataJson);
        registerMockRule(rule);
    }
    
    /**
     * 快速注册从文件读取的 Mock 规则
     */
    public static void registerMockFromFile(String name, String urlPattern, String jsonFilePath) {
        MockRule rule = new MockRule(name, urlPattern)
            .mockDataPath(jsonFilePath);
        registerMockRule(rule);
    }
    
    /**
     * 启用指定的 Mock 规则
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
     * 禁用指定的 Mock 规则
     */
    public static void disableMock(String mockName) {
        MockRule rule = mockRules.get(mockName);
        if (rule != null) {
            rule.enabled(false);
            logger.info("Disabled mock rule: {}", mockName);
        }
    }
    
    /**
     * 全局启用所有 Mock
     */
    public static void enableGlobalMock() {
        globalMockEnabled = true;
        logger.info("Global mock enabled");
    }
    
    /**
     * 全局禁用所有 Mock
     */
    public static void disableGlobalMock() {
        globalMockEnabled = false;
        logger.info("Global mock disabled");
    }
    
    /**
     * 为 Page 应用所有 Mock 规则
     */
    public static void applyMocks(Page page) {
        if (!globalMockEnabled) {
            logger.debug("Global mock is disabled, skipping mock application");
            return;
        }
        
        logger.info("Applying {} mock rules to page", mockRules.size());
        
        for (MockRule rule : mockRules.values()) {
            if (rule.isEnabled()) {
                applyMockRule(page, rule);
            }
        }
    }
    
    /**
     * 为 BrowserContext 应用所有 Mock 规则
     */
    public static void applyMocks(BrowserContext context) {
        if (!globalMockEnabled) {
            logger.debug("Global mock is disabled, skipping mock application");
            return;
        }
        
        logger.info("Applying {} mock rules to context", mockRules.size());
        
        for (MockRule rule : mockRules.values()) {
            if (rule.isEnabled()) {
                applyMockRule(context, rule);
            }
        }
    }
    
    /**
     * 应用单个 Mock 规则到 Page
     */
    private static void applyMockRule(Page page, MockRule rule) {
        Pattern urlPattern = Pattern.compile(rule.getUrlPattern());

        page.route(urlPattern.asPredicate(), route -> {  // Use pattern predicate instead
            handleMockRoute(route, rule);
        });

        logger.info("Applied mock rule '{}' to page for pattern: {}", rule.getName(), rule.getUrlPattern());
    }
    
    /**
     * 应用单个 Mock 规则到 BrowserContext
     */
    private static void applyMockRule(BrowserContext context, MockRule rule) {
        Pattern urlPattern = Pattern.compile(rule.getUrlPattern());

        context.route(rule.getUrlPattern(), route -> {
            handleMockRoute(route, rule);
        });

        logger.info("Applied mock rule '{}' to context for pattern: {}", rule.getName(), rule.getUrlPattern());
    }
    
    /**
     * 处理 Mock 路由
     */
    private static void handleMockRoute(Route route, MockRule rule) {
        try {
            logger.info("Intercepted request: {} {} - Applying mock rule: {}", 
                route.request().method(), route.request().url(), rule.getName());
            
            // 检查 HTTP 方法是否匹配
            if (!Pattern.matches(rule.getMethod(), route.request().method())) {
                route.resume();
                return;
            }
            
            // 模拟延迟
            if (rule.getDelayMs() > 0) {
                try {
                    Thread.sleep(rule.getDelayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            // 获取 Mock 数据
            String mockData = getMockData(rule);
            
            if (mockData != null) {
                // 返回 Mock 响应
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
     * 获取 Mock 数据
     */
    private static String getMockData(MockRule rule) {
        // 优先使用直接提供的 JSON
        if (rule.getMockDataJson() != null && !rule.getMockDataJson().isEmpty()) {
            return rule.getMockDataJson();
        }
        
        // 从文件读取
        if (rule.getMockDataPath() != null && !rule.getMockDataPath().isEmpty()) {
            try {
                return new String(Files.readAllBytes(Paths.get(rule.getMockDataPath())));
            } catch (Exception e) {
                logger.error("Failed to read mock data from file: " + rule.getMockDataPath(), e);
            }
        }
        
        return null;
    }
    
    /**
     * 清除所有 Mock 规则
     */
    public static void clearAllMocks() {
        mockRules.clear();
        logger.info("All mock rules cleared");
    }
    
    /**
     * 移除指定的 Mock 规则
     */
    public static void removeMock(String mockName) {
        mockRules.remove(mockName);
        logger.info("Removed mock rule: {}", mockName);
    }
    
    /**
     * 获取所有 Mock 规则
     */
    public static Map<String, MockRule> getAllMockRules() {
        return new HashMap<>(mockRules);
    }
    
    // ==================== 预定义的常用 Mock 方法 ====================

    /**
     * Mock 一个成功的响应
     * @param name 规则名称
     * @param urlPattern URL 匹配模式
     * @param responseData 完整的响应数据（由调用者提供）
     */
    public static void mockSuccess(String name, String urlPattern, String responseData) {
        registerMock(name, urlPattern, responseData);
    }

    /**
     * Mock 一个失败的响应
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
     * Mock 超时（长延迟）
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
}
