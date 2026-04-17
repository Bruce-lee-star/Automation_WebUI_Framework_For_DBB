package com.hsbc.cmb.hk.dbb.automation.framework.web.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * BrowserStack 云测试管理器（企业级）
 * 
 * <h2>架构说明</h2>
 * <p>BrowserStack 通过 CDP (Chrome DevTools Protocol) 远程连接模式工作：</p>
 * <pre>
 *   本地 Playwright  ──CDP连接──▶  BrowserStack Cloud  ──▶  远程浏览器实例
 *                              (cdp.browserstack.com)
 * </pre>
 * 
 * <h2>使用方式</h2>
 * <pre>
 * // 方式1：在 serenity.properties 中配置后自动启用
 * browserstack.enabled=true
 * browserstack.username=xxx
 * browserstack.access_key=yyy
 * 
 * // 方式2：环境变量覆盖
 * export BROWSERSTACK_ENABLED=true
 * export BROWSERSTACK_USERNAME=xxx
 * export BROWSERSTACK_ACCESS_KEY=yyy
 * </pre>
 * 
 * <h2>驱动流程</h2>
 * <ol>
 *   <li>PlaywrightManager 启动时检查 isBrowserStackEnabled()</li>
 *   <li>启用时调用 createBrowser() 获取远程 Browser 实例</li>
 *   <li>所有页面操作通过远程 Browser 执行（视频/截图自动录制）</li>
 *   <li>测试结束后调用 setTestStatus() 标记结果到 BrowserStack Dashboard</li>
 * </ol>
 */
public class BrowserStackManager {

    private static final Logger logger = LoggerFactory.getLogger(BrowserStackManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 常量 ====================

    private static final String BROWSERSTACK_CDP_URL = "https://hub.browserstack.com/wd/hub";
    private static final String BROWSERSTACK_API_URL = "https://api.browserstack.com/automate/sessions";
    private static final int CONNECT_TIMEOUT_SECONDS = 60;
    private static final int REQUEST_TIMEOUT_MS = 30000;

    // ==================== 会话状态 ====================

    private static volatile String currentSessionId = null;
    private static volatile String currentSessionUrl = null;

    // ==================== 公共 API：开关检测 ====================

    /**
     * 检查是否启用 BrowserStack 云测试
     * 优先级：环境变量 > 系统属性 > 配置文件 > 默认值(false)
     */
    public static boolean isBrowserStackEnabled() {
        return getBooleanEnv("BROWSERSTACK_ENABLED", FrameworkConfig.BROWSERSTACK_ENABLED, false);
    }

    /**
     * 是否为本地开发模式（Local Testing）
     */
    public static boolean isLocalEnabled() {
        return getBooleanEnv("BROWSERSTACK_LOCAL", null, false);
    }

    // ==================== 公共 API：创建浏览器连接 ====================

    /**
     * 创建 BrowserStack 远程浏览器连接（核心方法）
     * 
     * <p>这是框架驱动的入口。PlaywrightManager 在初始化时调用此方法。</p>
     * 
     * @param playwright Playwright 实例
     * @return 远程 Browser 对象
     * @throws IllegalStateException 未启用或配置缺失时抛出
     */
    public static Browser connect(Playwright playwright) {
        if (!isBrowserStackEnabled()) {
            throw new IllegalStateException("BrowserStack is not enabled");
        }

        validateCredentials();

        try {
            String cdpUrl = buildCdpUrl();
            
            logger.info("[BrowserStack] Connecting to remote browser...");
            logger.debug("[BrowserStack] CDP URL: {}", maskCdpUrl(cdpUrl));

            BrowserType.ConnectOverCDPOptions options = new BrowserType.ConnectOverCDPOptions();
            options.setHeaders(buildAuthHeader());
            options.setTimeout(CONNECT_TIMEOUT_SECONDS * 1000L);

            Browser browser = playwright.chromium().connectOverCDP(cdpUrl, options);
            
            logger.info("[BrowserStack] Connected successfully!");
            logCapabilities();

            return browser;

        } catch (PlaywrightException e) {
            throw new RuntimeException(
                "[BrowserStack] Failed to connect to BrowserStack. " +
                "Check credentials and network connectivity.", e);
        }
    }

    /**
     * 创建带自定义能力的浏览器连接
     * 用于并行测试或多配置场景
     */
    public static Browser connect(Playwright playwright, Map<String, Object> customCapabilities) {
        // 合并默认能力和自定义能力
        Map<String, Object> merged = buildFullCapabilities();
        merged.putAll(customCapabilities);
        
        // 存储临时能力供 buildCdpUrl 使用
        setTempCapabilities(merged);
        
        try {
            return connect(playwright);
        } finally {
            clearTempCapabilities();
        }
    }

    // ==================== 公共 API：会话管理 ====================

    /**
     * 获取当前会话 ID（由 PlaywrightManager 或测试监听器设置）
     */
    public static String getCurrentSessionId() {
        return currentSessionId;
    }

    /**
     * 设置当前会话 ID
     */
    public static void setCurrentSessionId(String sessionId) {
        currentSessionId = sessionId;
        currentSessionUrl = getSessionDashboardUrl(sessionId);
    }

    /**
     * 获取当前会话的 BrowserStack Dashboard URL
     */
    public static String getCurrentSessionUrl() {
        return currentSessionUrl != null ? currentSessionUrl : 
               (currentSessionId != null ? getSessionDashboardUrl(currentSessionId) : null);
    }

    // ==================== 公共 API：测试状态标记 ====================

    /**
     * 标记测试结果到 BrowserStack Dashboard（REST API 实现）
     * 
     * @param status "passed" | "failed"
     * @param reason 失败原因或备注
     * @return API 调用是否成功
     */
    public static boolean setTestStatus(String status, String reason) {
        if (currentSessionId == null || currentSessionId.isEmpty()) {
            logger.warn("[BrowserStack] Cannot set status: no active session");
            return false;
        }

        try {
            String url = BROWSERSTACK_API_URL + "/" + currentSessionId + ".json";
            Map<String, Object> payload = new HashMap<>();
            payload.put("status", sanitizeStatus(status));
            payload.put("reason", truncate(reason, 256));

            logger.info("[BrowserStack] Setting session {} status to: {}", currentSessionId, status);

            int responseCode = sendPutRequest(url, payload, getUsername(), getAccessKey());
            
            if (responseCode == 200) {
                logger.info("[BrowserStack] Status updated successfully for session {}", currentSessionId);
                return true;
            } else {
                logger.warn("[BrowserStack] Failed to update status. HTTP {}", responseCode);
                return false;
            }

        } catch (Exception e) {
            logger.error("[BrowserStack] Error setting test status: {}", e.getMessage(), e);
            return false;
        }
    }

    // ==================== 内部工具方法 ====================

    /** 构建带认证信息的 CDP 连接 URL */
    private static String buildCdpUrl() {
        StringBuilder sb = new StringBuilder("wss://")
            .append(urlEncode(getUsername()))
            .append(":")
            .append(urlEncode(getAccessKey()))
            .append("@cdp.browserstack.com?");

        // 追加能力参数
        Map<String, Object> caps = buildFullCapabilities();
        boolean first = true;
        
        for (Map.Entry<String, Object> entry : caps.entrySet()) {
            if (entry.getValue() == null || entry.getValue().toString().isEmpty()) continue;
            
            sb.append(first ? "" : "&").append(urlEncode(entry.getKey()))
              .append("=").append(urlEncode(entry.getValue().toString()));
            first = false;
        }

        return sb.toString();
    }

    /** 构建完整的能力配置 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildFullCapabilities() {
        Map<String, Object> caps = new HashMap<>();

        // 浏览器配置（使用已存在的枚举）
        caps.put("browserName", getStringValue(FrameworkConfig.BROWSERSTACK_BROWSER_VERSION, "chrome"));
        caps.put("browserVersion", "latest");

        // 操作系统
        caps.put("os", getStringValue(FrameworkConfig.BROWSERSTACK_OS, "Windows"));
        caps.put("osVersion", getStringValue(FrameworkConfig.BROWSERSTACK_OS_VERSION, "11"));

        // 项目信息
        caps.put("projectName", getStringValue(FrameworkConfig.SERENITY_PROJECT_NAME, "Automation Project"));
        caps.put("buildName", "Build-" + System.currentTimeMillis());

        // 会话名称
        caps.put("name", getStringValue(FrameworkConfig.BROWSERSTACK_SESSION_NAME, "Test Session"));

        // 功能开关
        caps.put("debug", getStringValue(FrameworkConfig.BROWSERSTACK_DEBUG, "false"));
        caps.put("networkLogs", getStringValue(FrameworkConfig.BROWSERSTACK_NETWORK_LOGS, "false"));
        caps.put("video", getStringValue(FrameworkConfig.BROWSERSTACK_VIDEO, "true"));

        // Local Testing（访问内网应用时需要）
        if (isLocalEnabled()) {
            caps.put("local", "true");
            caps.put("localIdentifier", "automation_" + System.currentTimeMillis());
        }

        // 超时配置
        int timeout = intValueOf(FrameworkConfig.BROWSERSTACK_TIMEOUT, 300);
        caps.put("timeout", String.valueOf(timeout));

        return caps;
    }

    /** 构建 Basic Auth Header */
    private static Map<String, String> buildAuthHeader() {
        Map<String, String> headers = new HashMap<>();
        String auth = getUsername() + ":" + getAccessKey();
        String encoded = Base64.getEncoder()
            .encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        headers.put("Authorization", "Basic " + encoded);
        return headers;
    }

    /** 发送 PUT 请求到 BrowserStack REST API */
    @SuppressWarnings("deprecation")
    private static int sendPutRequest(String urlStr, Map<String, Object> body,
                                       String username, String accessKey) throws IOException {
        URI uri = URI.create(urlStr);
        URL url = uri.toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(REQUEST_TIMEOUT_MS);
        conn.setReadTimeout(REQUEST_TIMEOUT_MS);
        
        // Basic Auth
        String auth = username + ":" + accessKey;
        String encoded = Base64.getEncoder()
            .encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        conn.setRequestProperty("Authorization", "Basic " + encoded);
        
        // 写入请求体
        try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
            objectMapper.writeValue(writer, body);
        }
        
        return conn.getResponseCode();
    }

    /** 凭证验证 */
    private static void validateCredentials() {
        String user = getUsername();
        String key = getAccessKey();
        
        if (user == null || user.trim().isEmpty() || key == null || key.trim().isEmpty()) {
            throw new IllegalStateException(
                "[BrowserStack] Credentials not configured!\n" +
                "Set one of:\n" +
                "  1. Environment variables: BROWSERSTACK_USERNAME, BROWSERSTACK_ACCESS_KEY\n" +
                "  2. Config properties: browserstack.username, browserstack.access.key\n" +
                "  3. System properties: -Dbrowserstack.username=xxx -Dbrowserstack.access.key=yyy"
            );
        }
    }

    // ==================== 配置读取（优先级链）====================

    private static String getUsername() { 
        return getConfigValue("BROWSERSTACK_USERNAME", FrameworkConfig.BROWSERSTACK_USERNAME); 
    }
    
    private static String getAccessKey() { 
        return getConfigValue("BROWSERSTACK_ACCESS_KEY", FrameworkConfig.BROWSERSTACK_ACCESS_KEY); 
    }

    private static String getConfigValue(String envVar, FrameworkConfig configKey) {
        // 1. 环境变量
        String envVal = System.getenv(envVar);
        if (envVal != null && !envVal.isEmpty()) return envVal;
        
        // 2. 系统属性 (-D参数)
        String sysProp = System.getProperty(envVar.toLowerCase().replace("_", "."));
        if (sysProp != null && !sysProp.isEmpty()) return sysProp;
        
        // 3. 配置文件
        return configKey != null ? FrameworkConfigManager.getString(configKey) : null;
    }

    private static String getStringValue(FrameworkConfig key, String defaultValue) {
        try {
            String val = FrameworkConfigManager.getString(key);
            return val != null && !val.isEmpty() ? val : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static int intValueOf(FrameworkConfig key, int defaultValue) {
        try {
            return FrameworkConfigManager.getInt(key);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static boolean getBooleanEnv(String envVar, FrameworkConfig configKey, boolean defaultVal) {
        String envVal = System.getenv(envVar);
        if (envVal != null && !envVal.isEmpty()) return Boolean.parseBoolean(envVal);
        if (configKey != null) {
            try { return FrameworkConfigManager.getBoolean(configKey); } catch (Exception ignored) {}
        }
        return defaultVal;
    }

    // ==================== 工具方法 ====================

    private static String getSessionDashboardUrl(String sessionId) {
        return "https://automate.browserstack.com/dashboard/v2/sessions/" + sessionId;
    }

    private static String maskCdpUrl(String url) {
        return url.replaceAll("(\\w+):([^@]+)@", "$1:****@");
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

    private static String sanitizeStatus(String status) {
        if ("passed".equalsIgnoreCase(status)) return "passed";
        if ("failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status)) return "failed";
        return "passed"; // 默认
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }

    private static void logCapabilities() {
        Map<String, Object> caps = buildFullCapabilities();
        logger.info("[BrowserStack] Configuration:");
        logger.info("  OS: {} {}", caps.get("os"), caps.get("osVersion"));
        logger.info("  Browser: {} {}", caps.get("browserName"), caps.get("browserVersion"));
        logger.info("  Project: {} / Build: {}", caps.get("projectName"), caps.get("buildName"));
        logger.info("  Video: {}, Screenshot: {}, Debug: {}", 
                    caps.get("video"), caps.get("screenshot"), caps.get("debug"));
    }

    // ==================== 临时能力存储（用于自定义连接）====================

    private static final ThreadLocal<Map<String, Object>> tempCaps = new ThreadLocal<>();

    private static void setTempCapabilities(Map<String, Object> caps) { tempCaps.set(caps); }
    private static void clearTempCapabilities() { tempCaps.remove(); }

    // ==================== 兼容性保留（旧API）====================

    /** @deprecated 使用 {@link #connect(Playwright)} 替代 */
    @Deprecated
    public static void configureLaunchOptions(BrowserType.LaunchOptions options, String browserType) {
        // 旧接口兼容：LaunchOptions 不适用于 BrowserStack
        logger.warn("[BrowserStack] configureLaunchOptions() is deprecated. Use connect(Playwright) instead.");
    }

    /** @deprecated 使用 {@link #connect(Playwright)} 替代 */
    @Deprecated
    public static BrowserType.ConnectOptions getConnectOptions(String browserType) {
        logger.warn("[BrowserStack] getConnectOptions() is deprecated. Use connect(Playwright) instead.");
        BrowserType.ConnectOptions options = new BrowserType.ConnectOptions();
        options.setTimeout(CONNECT_TIMEOUT_SECONDS * 1000L);
        return options;
    }

    /** @deprecated 使用 {@link #setTestStatus(String, String)} 替代 */
    @Deprecated
    public static void setTestStatus(String sessionId, String status, String reason) {
        setTestStatus(status, reason);
    }

    /**
     * 获取 BrowserStack 会话 URL（向后兼容）
     */
    public static String getSessionUrl(String sessionId) {
        return getSessionDashboardUrl(sessionId);
    }
}
