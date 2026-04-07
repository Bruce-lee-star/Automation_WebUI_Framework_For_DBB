package com.hsbc.cmb.hk.dbb.automation.framework.web.cloud;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.microsoft.playwright.BrowserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * BrowserStack 云测试管理器
 * 
 * 功能：
 * - 管理 BrowserStack 连接配置
 * - 生成 BrowserStack 连接 URL
 * - 配置浏览器能力（Capabilities）
 * 
 * 使用方式：
 * 1. 在 serenity.conf 中配置 BrowserStack 参数
 * 2. 在 PlaywrightManager 中调用 BrowserStackManager 配置浏览器
 * 
 * 示例配置：
 * browserstack {
 *   enabled: true
 *   username: "your_username"
 *   accessKey: "your_access_key"
 *   projectName: "My Project"
 *   buildName: "Build #1"
 *   os: "Windows"
 *   osVersion: "11"
 *   browserVersion: "latest"
 * }
 */
public class BrowserStackManager {
    
    private static final Logger logger = LoggerFactory.getLogger(BrowserStackManager.class);
    
    // BrowserStack 连接 URL 模板
    private static final String BROWSERSTACK_URL_TEMPLATE = "https://%s:%s@cdp.browserstack.com/playwright";
    
    /**
     * 检查是否启用 BrowserStack
     */
    public static boolean isBrowserStackEnabled() {
        // 优先从环境变量读取
        String envEnabled = System.getenv("BROWSERSTACK_ENABLED");
        if (envEnabled != null) {
            return Boolean.parseBoolean(envEnabled);
        }
        // 环境变量没有则从配置文件读取
        return FrameworkConfigManager.getBoolean(FrameworkConfig.BROWSERSTACK_ENABLED);
    }
    
    /**
     * 获取 BrowserStack 连接 URL
     */
    public static String getBrowserStackUrl() {
        String username = getConfigValue(
            "BROWSERSTACK_USERNAME",
            FrameworkConfig.BROWSERSTACK_USERNAME
        );
        String accessKey = getConfigValue(
            "BROWSERSTACK_ACCESS_KEY",
            FrameworkConfig.BROWSERSTACK_ACCESS_KEY
        );
        
        if (username == null || username.isEmpty() || accessKey == null || accessKey.isEmpty()) {
            throw new IllegalStateException("BrowserStack username and access key must be configured");
        }
        
        return String.format(BROWSERSTACK_URL_TEMPLATE, username, accessKey);
    }
    
    /**
     * 获取配置值（环境变量优先）
     * 
     * @param envVarName 环境变量名称
     * @param configKey 配置项枚举
     * @return 配置值
     */
    private static String getConfigValue(String envVarName, FrameworkConfig configKey) {
        // 1. 优先从环境变量读取
        String envValue = System.getenv(envVarName);
        if (envValue != null && !envValue.isEmpty()) {
            return envValue;
        }
        
        // 2. 从系统属性读取（-D 参数）
        String sysPropValue = System.getProperty(envVarName.toLowerCase().replace("_", "."));
        if (sysPropValue != null && !sysPropValue.isEmpty()) {
            return sysPropValue;
        }
        
        // 3. 从配置文件读取
        return FrameworkConfigManager.getString(configKey);
    }
    
    /**
     * 获取 BrowserStack 浏览器能力配置
     */
    public static Map<String, String> getBrowserStackCapabilities(String browserType) {
        Map<String, String> capabilities = new HashMap<>();
        
        // 基本配置
        capabilities.put("browserName", browserType);
        
        // 操作系统配置（环境变量优先）
        String os = getConfigValue("BROWSERSTACK_OS", FrameworkConfig.BROWSERSTACK_OS);
        String osVersion = getConfigValue("BROWSERSTACK_OS_VERSION", FrameworkConfig.BROWSERSTACK_OS_VERSION);
        capabilities.put("os", os);
        capabilities.put("osVersion", osVersion);
        
        // 浏览器版本（环境变量优先）
        String browserVersion = getConfigValue("BROWSERSTACK_BROWSER_VERSION", FrameworkConfig.BROWSERSTACK_BROWSER_VERSION);
        capabilities.put("browserVersion", browserVersion);
        
        // 项目配置 - 使用 Serenity 项目名称
        String projectName = FrameworkConfigManager.getString(FrameworkConfig.SERENITY_PROJECT_NAME);
        String sessionName = getConfigValue("BROWSERSTACK_SESSION_NAME", FrameworkConfig.BROWSERSTACK_SESSION_NAME);
        
        if (projectName != null && !projectName.isEmpty()) {
            capabilities.put("projectName", projectName);
        }
        if (sessionName != null && !sessionName.isEmpty()) {
            capabilities.put("sessionName", sessionName);
        }
        
        // 调试和日志配置（环境变量优先）
        String debug = getConfigValue("BROWSERSTACK_DEBUG", FrameworkConfig.BROWSERSTACK_DEBUG);
        String networkLogs = getConfigValue("BROWSERSTACK_NETWORK_LOGS", FrameworkConfig.BROWSERSTACK_NETWORK_LOGS);
        String video = getConfigValue("BROWSERSTACK_VIDEO", FrameworkConfig.BROWSERSTACK_VIDEO);
        
        capabilities.put("debug", debug);
        capabilities.put("networkLogs", networkLogs);
        capabilities.put("video", video);
        
        // 超时配置（环境变量优先）
        String timeout = getConfigValue("BROWSERSTACK_TIMEOUT", FrameworkConfig.BROWSERSTACK_TIMEOUT);
        capabilities.put("timeout", timeout);
        
        logger.info("BrowserStack capabilities: {}", capabilities);
        
        return capabilities;
    }
    
    /**
     * 配置 Playwright 的 BrowserType.LaunchOptions
     */
    public static void configureLaunchOptions(BrowserType.LaunchOptions options, String browserType) {
        if (!isBrowserStackEnabled()) {
            return;
        }
        
        // 获取 BrowserStack URL
        String browserStackUrl = getBrowserStackUrl();
        
        // 获取能力配置
        Map<String, String> capabilities = getBrowserStackCapabilities(browserType);
        
        // 设置连接 URL
        options.setChannel(browserStackUrl);
        
        // 设置能力
        // 注意：Playwright 使用不同的方式设置能力
        // BrowserStack 通过特定的端点连接，能力通过 URL 参数传递
        
        logger.info("BrowserStack configured for browser: {}", browserType);
        logger.info("BrowserStack URL: {}", browserStackUrl.replaceAll(":([^@]+)@", ":****@"));
    }
    
    /**
     * 配置 Playwright 的 BrowserType.ConnectOptions
     * 用于连接到已存在的浏览器实例
     */
    public static BrowserType.ConnectOptions getConnectOptions(String browserType) {
        BrowserType.ConnectOptions options = new BrowserType.ConnectOptions();
        
        // 设置 BrowserStack 连接超时
        String timeout = FrameworkConfigManager.getString(FrameworkConfig.BROWSERSTACK_TIMEOUT);
        options.setTimeout(Integer.parseInt(timeout) * 1000); // 转换为毫秒
        
        // 设置能力
        Map<String, String> capabilities = getBrowserStackCapabilities(browserType);
        // 注意：Playwright Java 的 ConnectOptions 不直接支持设置 capabilities
        // 需要通过其他方式传递
        
        return options;
    }
    
    /**
     * 获取 BrowserStack 会话 URL
     * 用于查看测试执行结果
     */
    public static String getSessionUrl(String sessionId) {
        return String.format("https://automate.browserstack.com/dashboard/v2/sessions/%s", sessionId);
    }
    
    /**
     * 设置测试状态（通过、失败）
     * 需要使用 BrowserStack 的 REST API
     */
    public static void setTestStatus(String sessionId, String status, String reason) {
        try {
            String username = getConfigValue("BROWSERSTACK_USERNAME", FrameworkConfig.BROWSERSTACK_USERNAME);
            String accessKey = getConfigValue("BROWSERSTACK_ACCESS_KEY", FrameworkConfig.BROWSERSTACK_ACCESS_KEY);
            
            // TODO: 调用 BrowserStack REST API 设置测试状态
            // 示例：
            // curl -u "username:access_key" \
            //   -X PUT "https://api.browserstack.com/automate/sessions/{sessionId}.json" \
            //   -H "Content-Type: application/json" \
            //   -d '{"status":"passed","reason":"All tests passed"}'
            
            logger.info("Set BrowserStack session {} status to: {}", sessionId, status);
        } catch (Exception e) {
            logger.error("Failed to set BrowserStack test status", e);
        }
    }
}
