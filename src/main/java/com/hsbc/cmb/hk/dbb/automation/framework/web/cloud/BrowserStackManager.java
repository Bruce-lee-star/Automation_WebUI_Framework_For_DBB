package com.hsbc.cmb.hk.dbb.automation.framework.web.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.ProxyConfigResolver;
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
 * <p>BrowserStack 通过统一的 wss endpoint 支持所有浏览器引擎：</p>
 * <pre>
 *   本地 Playwright  ──wss连接──▶  BrowserStack Cloud  ──▶  远程浏览器实例
 *                              (cdp.browserstack.com)
 *   - Chromium (Chrome/Edge)  → 使用 connectOverCDP()
 *   - Firefox / WebKit         → 使用 browserType.connect() (Playwright 自有协议)
 * </pre>
 * 
 * <h2>使用方式</h2>
 * <pre>
 * // 方式1：在 serenity.properties 中配置后自动启用
 * browserstack.enabled=true
 * browserstack.username=xxx
 * browserstack.access_key=yyy
 * browserstack.browserName=chrome    // chrome / firefox / webkit / edge
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
 *   <li>启用时调用 connect() 获取远程 Browser 实例</li>
 *   <li>所有页面操作通过远程 Browser 执行（视频/截图自动录制）</li>
 *   <li>测试结束后调用 setTestStatus() 标记结果到 BrowserStack Dashboard</li>
 * </ol>
 */
public class BrowserStackManager {

    private static final Logger logger = LoggerFactory.getLogger(BrowserStackManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 常量 ====================

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
     * 是否为本地开发模式（Local Testing）。
     * <p>读取 {@code browserstack.local} 配置或 {@code BROWSERSTACK_LOCAL} 环境变量。
     * <p>启用后自动设置 {@code browserstack.local.force.local=true}，
     * CDP/Playwright WebSocket 流量强制走 Local 隧道（官方原生方案，零额外组件）。
     */
    public static boolean isLocalEnabled() {
        return getBooleanEnv("BROWSERSTACK_LOCAL", FrameworkConfig.BROWSERSTACK_LOCAL, false);
    }

    // ==================== 公共 API：创建浏览器连接 ====================

    /**
     * 创建 BrowserStack 远程浏览器连接（核心方法）。
     *
     * <p>这是框架驱动的入口。PlaywrightManager 在初始化时调用此方法。</p>
     * <p>启用 Local Testing 时，会自动拉起 BrowserStack Local 隧道。</p>
     *
     * <p><b>连接协议：</b>统一使用 {@code browserType.connect()}（Playwright 自有协议），
     * 不再使用 {@code connectOverCDP()}。原因：Playwright Java 的 connectOverCDP()
     * 不通过 HTTPS_PROXY 代理 WebSocket 连接，公司网络下会导致 DNS 失败。
     * BrowserStack 的 wss://cdp.browserstack.com/playwright endpoint 同时支持两种协议，
     * connect() 底层使用 Node.js http 库，能正确读取 HTTPS_PROXY 环境变量。</p>
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

        // 启动 Local 隧道（如果启用）
        if (isLocalEnabled()) {
            boolean tunnelOk = BrowserStackLocalManager.startTunnel();
            if (!tunnelOk) {
                logger.warn("[BrowserStack] Local tunnel failed to start, proceeding without local testing");
            }
        }

        // 代理感知：公司网络下可能无法直连 cdp.browserstack.com
        logProxyStatus();

        // Local 隧道模式：CDP/WS 流量自动走 Local 隧道（官方原生方案）
        boolean localEnabled = isLocalEnabled();
        String wssEndpoint = buildWsEndpoint();

        try {
            String browserName = resolveBrowserName();

            String connectEndpoint = wssEndpoint;
            if (localEnabled) {
                logger.info("[BrowserStack] Connecting via tunnel + proxy: Playwright wss:// traffic routes through proxy to {}",
                        FrameworkConfigManager.getString(FrameworkConfig.BROWSERSTACK_CDP_ENDPOINT) != null
                                ? FrameworkConfigManager.getString(FrameworkConfig.BROWSERSTACK_CDP_ENDPOINT)
                                : "cdp.browserstack.com");
            }
            logger.info("[BrowserStack] Connecting to remote browser (browser={})...", browserName);
            logger.debug("[BrowserStack] Connect endpoint: {}", maskCdpUrl(connectEndpoint));

            BrowserType.ConnectOptions options = new BrowserType.ConnectOptions();
            options.setHeaders(buildAuthHeader());
            options.setTimeout(CONNECT_TIMEOUT_SECONDS * 1000L);

            Browser browser;
            switch (browserName.toLowerCase()) {
                case "chrome":
                case "edge":
                case "chromium":
                    browser = playwright.chromium().connect(connectEndpoint, options);
                    break;
                case "firefox":
                    browser = playwright.firefox().connect(connectEndpoint, options);
                    break;
                case "webkit":
                case "safari":
                    browser = playwright.webkit().connect(connectEndpoint, options);
                    break;
                default:
                    throw new IllegalArgumentException(
                        "[BrowserStack] Unsupported browser: " + browserName +
                        ". Supported: chrome, edge, firefox, webkit");
            }

            logger.info("[BrowserStack] Connected successfully!");
            logCapabilities();

            return browser;

        } catch (PlaywrightException e) {
            // PlaywrightException 消息可能含完整 CDP URL（wss://user:key@...），
            // 不能作为 cause 传递（SLF4J 会递归打印整个 cause 链暴露凭据）。
            // 脱敏后仅保留报错原因文本。
            String causeMsg = sanitizeMessage(e.getMessage());
            logger.debug("[BrowserStack] Connection failed: {}", causeMsg);

            String proxyHint = "";
            if (isLikelyDnsFailure(causeMsg)) {
                String endpoint = FrameworkConfigManager.getString(FrameworkConfig.BROWSERSTACK_CDP_ENDPOINT);
                if (endpoint == null || endpoint.trim().isEmpty()) {
                    endpoint = "cdp.browserstack.com";
                }
                proxyHint = " DNS resolution failed for '" + endpoint + "'. "
                        + "Playwright WebSocket connect() does NOT use HTTPS_PROXY "
                        + "(known limitation, GitHub issue #26985). "
                        + "Solutions: (1) Ensure browserstack.local=true is enabled + add hosts entry for '" + endpoint + "', "
                        + "(2) Use a system-level proxy/tunnel (e.g. proxifier), or "
                        + "(3) Set browserstack.cdp.endpoint to a domain resolvable in your network.";
            }

            throw new RuntimeException(
                "[BrowserStack] Failed to connect to BrowserStack. " +
                "Check credentials and network connectivity." +
                proxyHint +
                (causeMsg != null ? " Cause: " + causeMsg : ""));
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

    /**
     * 构建 BrowserStack 通用 wss 连接端点。
     * <p>BrowserStack 使用统一端点，根据 capabilities 中的 browserName
     * 自动选择 CDP 或 Playwright 自有协议。
     * <p>URL 格式：{@code wss://user:key@<endpoint>/playwright?caps=<json>}
     * <p>端点域名通过 {@link FrameworkConfig#BROWSERSTACK_CDP_ENDPOINT} 配置，
     * 默认 {@code cdp.browserstack.com}。
     */
    private static String buildWsEndpoint() {
        Map<String, Object> caps = buildFullCapabilities();

        String endpoint = FrameworkConfigManager.getString(FrameworkConfig.BROWSERSTACK_CDP_ENDPOINT);
        if (endpoint == null || endpoint.trim().isEmpty()) {
            endpoint = "cdp.browserstack.com";
        }

        try {
            String capsJson = objectMapper.writeValueAsString(caps);
            return "wss://" + urlEncode(getUsername()) + ":" + urlEncode(getAccessKey())
                    + "@" + endpoint.trim() + "/playwright?caps=" + urlEncode(capsJson);
        } catch (Exception e) {
            logger.error("[BrowserStack] Failed to encode capabilities", e);
            throw new RuntimeException("[BrowserStack] Failed to build connection URL", e);
        }
    }

    /** 构建完整的能力配置 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildFullCapabilities() {
        Map<String, Object> caps = new HashMap<>();

        // 浏览器配置
        caps.put("browserName", resolveBrowserName());
        caps.put("browserVersion", getStringValue(FrameworkConfig.BROWSERSTACK_BROWSER_VERSION, "latest"));

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

        // Local Testing：访问内网应用，并解决公司代理环境下 CDP 域名无法解析的问题
        // 启用 local 后自动设置 force.local=true，CDP/Playwright WebSocket 流量走 Local 隧道
        if (isLocalEnabled()) {
            caps.put("local", "true");
            caps.put("browserstack.local.force.local", "true");
            caps.put("browserstack.useWSS", "true");
            caps.put("browserstack.wsLocalSupport", "true");
            String localId = BrowserStackLocalManager.getLocalIdentifier();
            if (localId != null) {
                caps.put("localIdentifier", localId);
            }
            logger.info("[BrowserStack] Local tunnel mode: CDP traffic routes through Local tunnel (official native, no extra components)");
        }

        // 超时配置
        int timeout = intValueOf(FrameworkConfig.BROWSERSTACK_TIMEOUT, 300);
        caps.put("timeout", String.valueOf(timeout));

        return caps;
    }

    /**
     * 解析浏览器名称（从配置读取，规范化后返回）。
     * <p>BrowserStack API 接受的 browserName: chrome / firefox / webkit / edge。
     */
    private static String resolveBrowserName() {
        String raw = getStringValue(FrameworkConfig.BROWSERSTACK_BROWSER_NAME, "chrome");
        if (raw == null || raw.trim().isEmpty()) return "chrome";

        String normalized = raw.trim().toLowerCase();
        // 别名映射
        switch (normalized) {
            case "safari":
                return "webkit";
            case "msedge":
            case "microsoftedge":
                return "edge";
            default:
                return normalized;
        }
    }

    /**
     * 判断是否为 Chromium 系浏览器（使用 CDP 协议连接）。
     */
    private static boolean isChromiumBrowser(String browserName) {
        if (browserName == null) return true;
        switch (browserName.toLowerCase()) {
            case "chrome":
            case "chromium":
            case "edge":
                return true;
            case "firefox":
            case "webkit":
            case "safari":
                return false;
            default:
                logger.warn("[BrowserStack] Unknown browser '{}', defaulting to Chromium CDP mode", browserName);
                return true;
        }
    }

    /**
     * 记录当前代理状态，帮助排查公司网络下域名无法解析的问题。
     * <p><b>重要限制：</b>Playwright Java 的 {@code browserType.connect()} /
     * {@code connectOverCDP()} 不通过 {@code HTTPS_PROXY} 环境变量代理 WebSocket 连接
     * （已知限制 GitHub #26985）。
     * <p><b>推荐方案：</b>{@code browserstack.local=true} —
     * CDP 流量走 Local 隧道，自动设置 force.local，零额外组件。
     */
    private static void logProxyStatus() {
        String localProxy = ProxyConfigResolver.getHttpProxyUrlForBrowserStackLocal();
        if (localProxy != null) {
            logger.info("[BrowserStack] Local tunnel mode + proxy auto-injection: "
                    + "tunnel and Playwright WebSocket share the same proxy ({}). "
                    + "wss:// traffic will route through proxy to {}.",
                    ProxyConfigResolver.sanitizeProxyUrlForLog(localProxy),
                    FrameworkConfigManager.getString(FrameworkConfig.BROWSERSTACK_CDP_ENDPOINT) != null
                            ? FrameworkConfigManager.getString(FrameworkConfig.BROWSERSTACK_CDP_ENDPOINT)
                            : "cdp.browserstack.com");
        } else {
            logger.info("[BrowserStack] Local tunnel mode enabled. "
                    + "No proxy configured — tunnel may connect directly, "
                    + "but Playwright WebSocket to {} may fail in corporate networks. "
                    + "Configure playwright.proxy.http to route wss:// traffic through proxy.",
                    FrameworkConfigManager.getString(FrameworkConfig.BROWSERSTACK_CDP_ENDPOINT) != null
                            ? FrameworkConfigManager.getString(FrameworkConfig.BROWSERSTACK_CDP_ENDPOINT)
                            : "cdp.browserstack.com");
        }
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

    /**
     * 获取原始 AccessKey（供 BrowserStackLocalManager 包内使用）。
     */
    static String getAccessKeyRaw() {
        return getAccessKey();
    }

    /**
     * 清理 BrowserStack 资源（停止 Local 隧道等）。
     * <p>由 PlaywrightManager.cleanupAll() 调用。
     */
    public static void cleanup() {
        if (isLocalEnabled()) {
            BrowserStackLocalManager.stopTunnel();
        }
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
            try { return FrameworkConfigManager.getBoolean(configKey); } catch (Exception e) {
                logger.debug("[BrowserStack] Failed to read boolean config: {}", configKey.name());
            }
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

    /**
     * 脱敏异常/日志消息中的凭据（wss:// / https?:// 中的密码、accessKey）。
     * <p>用于防止 Playwright 内部异常消息（含完整 CDP URL）被日志打印出去。
     * <p>正则匹配 {@code scheme://user:secret@host}，将 secret 替换为 {@code ****}。
     * <p>兼容 URL 编码后的密码（如 {@code pass%40word}），因为正则用 {@code [^@]+} 匹配。
     */
    public static String sanitizeMessage(String message) {
        if (message == null || message.isEmpty()) return message;
        // 匹配 scheme://user:secret@ — secret 可含 %-encoded 字符、特殊字符
        return message.replaceAll("(wss|https?://)([^:]+):([^@]+)@", "$1$2:****@");
    }

    /**
     * 根据异常消息判断是否为 DNS/域名解析失败。
     * <p>匹配常见 DNS 相关错误关键词，用于给出代理配置建议。
     */
    private static boolean isLikelyDnsFailure(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("unknownhostexception")
                || lower.contains("namenotfound")
                || lower.contains("no such host")
                || lower.contains("dns")
                || lower.contains("resolve")
                || lower.contains("unresolved")
                || lower.contains("nodename")
                || lower.contains("getaddrinfo")
                || lower.contains("enotfound");
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

    /**
     * 获取 BrowserStack 会话 URL（向后兼容）
     */
    public static String getSessionUrl(String sessionId) {
        return getSessionDashboardUrl(sessionId);
    }
}
