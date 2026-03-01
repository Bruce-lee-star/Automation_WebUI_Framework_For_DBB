package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Session Manager - Manage user login state, supports skip login functionality
 *
 * Features:
 * 1. Save and restore Cookies
 * 2. Save and restore LocalStorage
 * 3. Support multi-user session management across different environments
 * 4. File persistence support
 * 5. Method overloading for backward compatibility
 * 6. Configurable session timeout
 *
 * Session Key Formats (method overloading):
 * - Full: env_username_browser (e.g., O88_SIT1_AABBCCDD_chromium)
 * - Recommended: env_username (e.g., O88_SIT1_AABBCCDD)
 * - Legacy: username only (e.g., AABBCCDD)
 *
 * Using env+username allows same username to have different sessions in different environments
 * Using browser type allows same username to have different sessions across browsers
 *
 * Session Configuration (in serenity.conf):
 * - playwright.no.login.session.timeout.minutes: Session timeout in minutes (default: 5)
 *   Example: playwright.no.login.session.timeout.minutes=60
 *
 * Session Storage: target/.sessions/
 *
 * 使用方式（按简洁程度排序）：
 *
 * 【超简】智能登录（推荐） - 自动推断环境：
 *   SessionManager.smartLogin(username, homeUrl, () -> loginPage.login());
 *   SessionManager.smartLogin(username, () -> loginPage.login()); // 无homeUrl
 *
 * 【超简】智能恢复 - 自动推断环境：
 *   if (SessionManager.smartRestore(username)) { 跳过登录  }
 *   SessionManager.smartSave(username, homeUrl);
 *   SessionManager.smartClear(username);
 *
 * 【推荐】保存Session - 一行代码：
 *   saveSession(username, "https://example.com/home");
 *   saveSession(env, username, "https://example.com/home");
 *
 * 【推荐】尝试恢复Session - 自动跳过登录（如果可用）：
 *   if (tryRestoreAndSkipLogin(username, loginRunnable)) {  跳过 }
 *
 * 【推荐】完整流程：恢复或登录并保存：
 *   restoreOrLogin(username, homeUrl, () -> loginPage.login());
 *
 * 【简化】检查是否可跳过登录：
 *   if (canSkipLogin(username)) { 可以跳过登录 }
 *
 * 【传统】分步操作（向后兼容）：
 *   if (restoreSession(username)) {  已恢复  }
 *   markUserLoggedIn(username);
 *   saveSession(username, homeUrl);
 */
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    // Session storage directory
    private static final String SESSION_DIR = "target/.sessions";

    // Gson instance for JSON serialization (UTF-8 encoding)
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .setDateFormat("yyyy-MM-dd HH:mm:ss")
            .create();

    // Session timeout in minutes (read from FrameworkConfig)
    private static final long SESSION_TIMEOUT_MINUTES;

    static {
        SESSION_TIMEOUT_MINUTES = FrameworkConfigManager.getLong(FrameworkConfig.PLAYWRIGHT_NO_LOGIN_SESSION_TIMEOUT);
        LoggingConfigUtil.logInfoIfVerbose(logger, "Session timeout configured: {} minutes", SESSION_TIMEOUT_MINUTES);
    }

    // Active user sessions in memory
    private static final Map<String, UserSession> activeSessions = new HashMap<>();
    
    /**
     * User Session class - stores user login state
     */
    public static class UserSession implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String username;
        private final List<Map<String, Object>> cookies;
        private final Map<String, String> localStorage;
        private boolean isLoggedIn;
        private String homeUrl;
        private long lastAccessTime;

        public UserSession(String username) {
            this.username = username;
            this.cookies = new ArrayList<>();
            this.localStorage = new HashMap<>();
            this.isLoggedIn = false;
            this.homeUrl = "";
            this.lastAccessTime = System.currentTimeMillis();
        }

        // Getters and Setters
        public String getUsername() { return username; }
        public List<Map<String, Object>> getCookies() { return cookies; }
        public Map<String, String> getLocalStorage() { return localStorage; }
        public boolean isLoggedIn() { return isLoggedIn; }
        public void setLoggedIn(boolean loggedIn) { this.isLoggedIn = loggedIn; }
        public String getHomeUrl() { return homeUrl; }
        public void setHomeUrl(String url) { this.homeUrl = url; }
        public long getLastAccessTime() { return lastAccessTime; }
        public void updateAccessTime() { this.lastAccessTime = System.currentTimeMillis(); }
        public void addCookie(Map<String, Object> cookie) { this.cookies.add(cookie); }
        public void setLocalStorageItem(String key, String value) { this.localStorage.put(key, value); }

        /**
         * Check if session is expired
         * @param timeoutMinutes Timeout in minutes
         * @return true if expired, false otherwise
         */
        public boolean isExpired(long timeoutMinutes) {
            long currentTime = System.currentTimeMillis();
            long elapsedTime = currentTime - lastAccessTime;
            long timeoutMillis = timeoutMinutes * 60 * 1000;
            return elapsedTime > timeoutMillis;
        }
    }
    
    /**
     * Save user session to file with explicit browser type
     * Uses env+username+browser as session key (full format)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @param browser Browser type (e.g., chromium, firefox)
     * @param homeUrl Home page URL after successful login
     */
    public static void saveSession(String env, String username, String browser, String homeUrl) {
        String sessionKey = generateSessionKey(env, username, browser);
        try {
            UserSession session = activeSessions.get(sessionKey);
            if (session == null) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "No active session found for session key: {}", sessionKey);
                return;
            }

            // Set home URL and update access time
            session.setHomeUrl(homeUrl);
            session.updateAccessTime();

            // Get cookies from current context
            BrowserContext context = PlaywrightManager.getContext();
            List<Cookie> cookies = context.cookies();

            // Convert to serializable Map
            for (Cookie cookie : cookies) {
                Map<String, Object> cookieMap = new HashMap<>();
                cookieMap.put("name", cookie.name);
                cookieMap.put("value", cookie.value);
                cookieMap.put("domain", cookie.domain);
                cookieMap.put("path", cookie.path);
                cookieMap.put("expires", cookie.expires);
                cookieMap.put("httpOnly", cookie.httpOnly);
                cookieMap.put("secure", cookie.secure);
                cookieMap.put("sameSite", cookie.sameSite);
                session.addCookie(cookieMap);
            }

            // Save localStorage
            try {
                Page page = PlaywrightManager.getPage();
                if (page != null) {
                    Object localStorageObj = page.evaluate("() => { const data = {}; for (let i = 0; i < localStorage.length; i++) { const key = localStorage.key(i); data[key] = localStorage.getItem(key); } return data; }");
                    @SuppressWarnings("unchecked")
                    Map<String, String> localStorageData = (Map<String, String>) localStorageObj;
                    for (Map.Entry<String, String> entry : localStorageData.entrySet()) {
                        session.setLocalStorageItem(entry.getKey(), entry.getValue());
                    }
                    LoggingConfigUtil.logInfoIfVerbose(logger, " Saved {} localStorage items for session key: {}", localStorageData.size(), sessionKey);
                }
            } catch (Exception e) {
                logger.warn("Failed to save localStorage for session key: {} (continuing without localStorage)", sessionKey, e);
            }

            // Save to file (JSON format with UTF-8 encoding)
            Path sessionDir = Paths.get(SESSION_DIR);
            if (!Files.exists(sessionDir)) {
                Files.createDirectories(sessionDir);
            }

            String sessionFile = sessionDir.resolve(sessionKey + ".session").toString();
            try (Writer writer = new OutputStreamWriter(
                    new BufferedOutputStream(new FileOutputStream(sessionFile), 8192),
                    StandardCharsets.UTF_8)) {
                GSON.toJson(session, writer);
            }

            LoggingConfigUtil.logInfoIfVerbose(logger, " Session saved for session key: {} to {}", sessionKey, sessionFile);
        } catch (Exception e) {
            logger.error("Failed to save session for session key: {}", sessionKey, e);
            throw new RuntimeException("Failed to save session", e);
        }
    }

    /**
     * Save user session to file
     * Uses env+username as session key (auto-detect browser)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @param homeUrl Home page URL after successful login
     */
    public static void saveSession(String env, String username, String homeUrl) {
        saveSession(env, username, null, homeUrl);
    }

    /**
     * Save user session to file (legacy - uses username only as key)
     * Backward compatible version for existing code
     *
     * @param username Username
     * @param homeUrl Home page URL after successful login
     */
    public static void saveSession(String username, String homeUrl) {
        saveSession(null, username, null, homeUrl);
    }

    /**
     * Load user session with explicit browser type
     * Uses env+username+browser as session key (full format)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @param browser Browser type (e.g., chromium, firefox)
     * @return User session, return null if not exists
     */
    public static UserSession loadSession(String env, String username, String browser) {
        String sessionKey = generateSessionKey(env, username, browser);
        try {
            Path sessionFile = Paths.get(SESSION_DIR, sessionKey + ".session");

            if (!Files.exists(sessionFile)) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "No saved session found for session key: {}", sessionKey);
                return null;
            }

            try (Reader reader = new InputStreamReader(
                    new BufferedInputStream(new FileInputStream(sessionFile.toString()), 8192),
                    StandardCharsets.UTF_8)) {
                UserSession session = GSON.fromJson(reader, UserSession.class);

                // Add session to active sessions
                activeSessions.put(sessionKey, session);

                LoggingConfigUtil.logInfoIfVerbose(logger, " Session loaded for session key: {}", sessionKey);
                return session;
            }
        } catch (Exception e) {
            logger.error("Failed to load session for session key: {}", sessionKey, e);
            return null;
        }
    }

    /**
     * Load user session
     * Uses env+username as session key (auto-detect browser)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @return User session, return null if not exists
     */
    public static UserSession loadSession(String env, String username) {
        return loadSession(env, username, null);
    }

    /**
     * Load user session (legacy - uses username only as key)
     * Backward compatible version for existing code
     *
     * @param username Username
     * @return User session, return null if not exists
     */
    public static UserSession loadSession(String username) {
        return loadSession(null, username, null);
    }
    
    /**
     * Restore session to current context with explicit browser type
     * Uses env+username+browser as session key (full format)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @param browser Browser type (e.g., chromium, firefox)
     * @return Return true if successfully restored, otherwise return false
     */
    public static boolean restoreSession(String env, String username, String browser) {
        String sessionKey = generateSessionKey(env, username, browser);
        try {
            UserSession session = loadSession(env, username, browser);
            if (session == null || !session.isLoggedIn()) {
                return false;
            }

            BrowserContext context = PlaywrightManager.getContext();

            // Restore cookies
            List<Map<String, Object>> cookies = session.getCookies();
            for (Map<String, Object> cookieMap : cookies) {
                context.addCookies(List.of(new Cookie(
                    (String) cookieMap.get("name"),
                    (String) cookieMap.get("value")
                ).setDomain((String) cookieMap.get("domain"))
                 .setPath((String) cookieMap.get("path"))
                 .setExpires((Double) cookieMap.get("expires"))
                 .setHttpOnly((Boolean) cookieMap.get("httpOnly"))
                 .setSecure((Boolean) cookieMap.get("secure"))
                 .setSameSite(SameSiteAttribute.valueOf(
                     cookieMap.get("sameSite").toString()
                 ))
                ));
            }

            // Restore localStorage
            Map<String, String> localStorage = session.getLocalStorage();
            if (localStorage != null && !localStorage.isEmpty()) {
                try {
                    // Create script to set localStorage items
                    StringBuilder scriptBuilder = new StringBuilder();
                    scriptBuilder.append("(() => {");
                    // Clear existing localStorage
                    scriptBuilder.append("localStorage.clear();");
                    // Set new localStorage items
                    for (Map.Entry<String, String> entry : localStorage.entrySet()) {
                        String key = escapeJsString(entry.getKey());
                        String value = escapeJsString(entry.getValue());
                        scriptBuilder.append(String.format("localStorage.setItem('%s', '%s');", key, value));
                    }
                    scriptBuilder.append("})();");

                    // Execute the script in the context
                    context.addInitScript(scriptBuilder.toString());

                    LoggingConfigUtil.logInfoIfVerbose(logger, " Restored {} localStorage items for session key: {}", localStorage.size(), sessionKey);
                } catch (Exception e) {
                    logger.warn("Failed to restore localStorage for session key: {} (continuing without localStorage)", sessionKey, e);
                }
            }

            // Update access time to extend session validity
            session.updateAccessTime();

            // Persist updated session to file to extend timeout
            persistSessionToFile(session, env, username, browser);

            LoggingConfigUtil.logInfoIfVerbose(logger, " Session restored and updated for session key: {}", sessionKey);
            return true;
        } catch (Exception e) {
            logger.error("Failed to restore session for session key: {}", sessionKey, e);
            return false;
        }
    }

    /**
     * Restore session to current context
     * Uses env+username as session key (auto-detect browser)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @return Return true if successfully restored, otherwise return false
     */
    public static boolean restoreSession(String env, String username) {
        return restoreSession(env, username, null);
    }

    /**
     * Restore session to current context (legacy - uses username only as key)
     * Backward compatible version for existing code
     *
     * @param username Username
     * @return Return true if successfully restored, otherwise return false
     */
    public static boolean restoreSession(String username) {
        return restoreSession(null, username, null);
    }
    
    /**
     * Create or get user session with explicit browser type
     * Uses env+username+browser as session key (full format)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @param browser Browser type (e.g., chromium, firefox)
     * @return User session
     */
    public static UserSession getOrCreateSession(String env, String username, String browser) {
        String sessionKey = generateSessionKey(env, username, browser);
        UserSession session = activeSessions.get(sessionKey);
        if (session == null) {
            session = new UserSession(sessionKey);
            activeSessions.put(sessionKey, session);
        }
        return session;
    }

    /**
     * Create or get user session
     * Uses env+username as session key (auto-detect browser)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @return User session
     */
    public static UserSession getOrCreateSession(String env, String username) {
        return getOrCreateSession(env, username, null);
    }

    /**
     * Create or get user session (legacy - uses username only as key)
     * Backward compatible version for existing code
     *
     * @param username Username
     * @return User session
     */
    public static UserSession getOrCreateSession(String username) {
        return getOrCreateSession(null, username, null);
    }

    /**
     * Mark user as logged in with explicit browser type
     * Uses env+username+browser as session key (full format)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @param browser Browser type (e.g., chromium, firefox)
     */
    public static void markUserLoggedIn(String env, String username, String browser) {
        String sessionKey = generateSessionKey(env, username, browser);
        UserSession session = getOrCreateSession(env, username, browser);
        session.setLoggedIn(true);
        LoggingConfigUtil.logInfoIfVerbose(logger, " User marked as logged in: {}", sessionKey);
    }

    /**
     * Mark user as logged in
     * Uses env+username as session key (auto-detect browser)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     */
    public static void markUserLoggedIn(String env, String username) {
        markUserLoggedIn(env, username, null);
    }

    /**
     * Mark user as logged in (legacy - uses username only as key)
     * Backward compatible version for existing code
     *
     * @param username Username
     */
    public static void markUserLoggedIn(String username) {
        markUserLoggedIn(null, username, null);
    }
    
    /**
     * Clear user session with explicit browser type
     * Uses env+username+browser as session key (full format)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @param browser Browser type (e.g., chromium, firefox)
     */
    public static void clearSession(String env, String username, String browser) {
        String sessionKey = generateSessionKey(env, username, browser);
        try {
            activeSessions.remove(sessionKey);

            Path sessionFile = Paths.get(SESSION_DIR, sessionKey + ".session");
            if (Files.exists(sessionFile)) {
                Files.delete(sessionFile);
                LoggingConfigUtil.logInfoIfVerbose(logger, "🗑️ Session cleared for session key: {}", sessionKey);
            }
        } catch (Exception e) {
            logger.error("Failed to clear session for session key: {}", sessionKey, e);
        }
    }

    /**
     * Clear user session
     * Uses env+username as session key (auto-detect browser)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     */
    public static void clearSession(String env, String username) {
        clearSession(env, username, null);
    }

    /**
     * Clear user session (legacy - uses username only as key)
     * Backward compatible version for existing code
     *
     * @param username Username
     */
    public static void clearSession(String username) {
        clearSession(null, username, null);
    }

    /**
     * Check if user is logged in with explicit browser type
     * Uses env+username+browser as session key (full format)
     * Checks both session existence and expiration
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @param browser Browser type (e.g., chromium, firefox)
     * @return Return true if user is logged in and session is not expired
     */
    public static boolean isUserLoggedIn(String env, String username, String browser) {
        String sessionKey = generateSessionKey(env, username, browser);
        Path sessionFile = Paths.get(SESSION_DIR, sessionKey + ".session");
        if (!Files.exists(sessionFile)) {
            return false;
        }

        UserSession session = loadSession(env, username, browser);
        if (session == null || !session.isLoggedIn()) {
            return false;
        }

        // Check if session is expired
        if (session.isExpired(SESSION_TIMEOUT_MINUTES)) {
            LoggingConfigUtil.logInfoIfVerbose(logger,
                "Session expired for session key: {} (last accessed: {} minutes ago)",
                sessionKey, SESSION_TIMEOUT_MINUTES);
            // Clear expired session
            clearSession(env, username, browser);
            return false;
        }

        return true;
    }

    /**
     * Check if user is logged in (via session file)
     * Uses env+username as session key (auto-detect browser)
     * Checks both session existence and expiration
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @return Return true if user is logged in and session is not expired
     */
    public static boolean isUserLoggedIn(String env, String username) {
        return isUserLoggedIn(env, username, null);
    }

    /**
     * Check if user is logged in (legacy - uses username only as key)
     * Backward compatible version for existing code
     * Checks both session existence and expiration
     *
     * @param username Username
     * @return Return true if user is logged in and session is not expired
     */
    public static boolean isUserLoggedIn(String username) {
        return isUserLoggedIn(null, username, null);
    }
    
    /**
     * Clear all sessions
     */
    public static void clearAllSessions() {
        try {
            activeSessions.clear();
            Path sessionDir = Paths.get(SESSION_DIR);
            if (Files.exists(sessionDir)) {
                Files.walk(sessionDir)
                    .filter(path -> !Files.isDirectory(path))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            logger.error("Failed to delete session file: {}", path, e);
                        }
                    });
            }
            LoggingConfigUtil.logInfoIfVerbose(logger, "🗑️ All sessions cleared");
        } catch (Exception e) {
            logger.error("Failed to clear all sessions", e);
        }
    }

    /**
     * 持久化session到文件（更新时间戳）
     * 用于在恢复session时更新访问时间，延长session有效期
     *
     * @param session User session
     * @param env Environment identifier
     * @param username Username
     * @param browser Browser type
     */
    private static void persistSessionToFile(UserSession session, String env, String username, String browser) {
        try {
            // Update access time
            session.updateAccessTime();

            // Save to file
            Path sessionDir = Paths.get(SESSION_DIR);
            if (!Files.exists(sessionDir)) {
                Files.createDirectories(sessionDir);
            }

            String sessionKey = generateSessionKey(env, username, browser);
            String sessionFile = sessionDir.resolve(sessionKey + ".session").toString();

            try (Writer writer = new OutputStreamWriter(
                    new BufferedOutputStream(new FileOutputStream(sessionFile), 8192),
                    StandardCharsets.UTF_8)) {
                GSON.toJson(session, writer);
            }

            LoggingConfigUtil.logDebugIfVerbose(logger, " Session persisted and updated: {}", sessionKey);
        } catch (Exception e) {
            logger.warn("Failed to persist session update (session will still be restored)", e);
        }
    }

    // ==================== 超级简化API（最常用，智能推断） ====================

    /**
     * 【超简】智能登录管理 - 一行代码搞定！
     * 自动处理所有逻辑：检查session、恢复session、执行登录、保存session
     *
     * 特性：
     * - 自动推断环境（从BDDUtils获取）
     * - 自动推断浏览器类型
     * - 自动处理session过期
     * - 自动更新session时间戳
     *
     * @param username Username
     * @param homeUrl 登录成功后的首页URL（可为null）
     * @param loginOperation 登录操作（如果session不可用，会执行此操作）
     * @return true表示使用了已有session，false表示执行了登录
     *
     * 示例：
     * SessionManager.smartLogin(username, homeUrl, () -> {
     *     loginPage.login(username, password);
     * });
     */
    public static boolean smartLogin(String username, String homeUrl, Runnable loginOperation) {
        // 自动推断环境（如果配置了）
        String env = inferEnvironment();
        return restoreOrLogin(env, username, homeUrl, loginOperation);
    }

    /**
     * 【超简】智能登录管理（无homeUrl版本）
     *
     * @param username Username
     * @param loginOperation 登录操作
     * @return true表示使用了已有session，false表示执行了登录
     *
     * 示例：
     * SessionManager.smartLogin(username, () -> {
     *     loginPage.login(username, password);
     * });
     */
    public static boolean smartLogin(String username, Runnable loginOperation) {
        return smartLogin(username, null, loginOperation);
    }

    /**
     * 【超简】智能恢复Session - 一行代码！
     * 自动推断环境和浏览器类型
     *
     * @param username Username
     * @return true表示session已恢复，false表示需要登录
     *
     * 示例：
     * if (SessionManager.smartRestore(username)) {
     *     // Session已恢复，跳过登录
     * } else {
     *     // 需要登录
     * }
     */
    public static boolean smartRestore(String username) {
        String env = inferEnvironment();
        return restoreSession(env, username);
    }

    /**
     * 【超简】智能保存Session - 一行代码！
     * 自动推断环境和浏览器类型
     *
     * @param username Username
     * @param homeUrl 首页URL
     *
     * 示例：
     * SessionManager.smartSave(username, homeUrl);
     */
    public static void smartSave(String username, String homeUrl) {
        String env = inferEnvironment();
        saveSessionAfterLogin(env, username, homeUrl);
    }

    /**
     * 【超简】智能清除Session - 一行代码！
     * 自动推断环境和浏览器类型
     *
     * @param username Username
     *
     * 示例：
     * SessionManager.smartClear(username);
     */
    public static void smartClear(String username) {
        String env = inferEnvironment();
        clearSession(env, username);
    }

    /**
     * 【超简】智能检查是否可跳过登录 - 一行代码！
     * 自动推断环境和浏览器类型
     *
     * @param username Username
     * @return true表示可以跳过登录
     *
     * 示例：
     * if (SessionManager.smartCanSkip(username)) {
     *     // 跳过登录
     * }
     */
    public static boolean smartCanSkip(String username) {
        String env = inferEnvironment();
        return canSkipLogin(env, username);
    }

    /**
     * 自动推断环境
     * 尝试从BDDUtils或系统属性获取环境
     */
    private static String inferEnvironment() {
        // 尝试从BDDUtils获取（如果可用）
        try {
            Object envObj = Class.forName("com.hsbc.cmb.hk.dbb.automation.tests.utils.BDDUtils")
                .getMethod("getEnvironment")
                .invoke(null);
            if (envObj instanceof String) {
                return (String) envObj;
            }
        } catch (Exception e) {
            // 忽略，使用null
        }

        // 尝试从系统属性获取
        String env = System.getProperty("playwright.environment");
        if (env != null && !env.isEmpty()) {
            return env;
        }

        return null; // 使用默认环境
    }

    // ==================== 简化API（最常用） ====================

    /**
     * 【简化】检查是否可以跳过登录
     * 检查session是否存在且未过期
     *
     * @param username Username
     * @return true表示可以跳过登录，false表示需要登录
     *
     * 示例：
     * if (canSkipLogin(username)) {
     *     // 跳过登录，直接使用已有session
     * } else {
     *     // 执行登录流程
     * }
     */
    public static boolean canSkipLogin(String username) {
        return isUserLoggedIn(username);
    }

    /**
     * 【简化】检查是否可以跳过登录（带环境）
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @return true表示可以跳过登录，false表示需要登录
     *
     * 示例：
     * if (canSkipLogin("O88_SIT1", username)) {
     *     // 跳过登录
     * }
     */
    public static boolean canSkipLogin(String env, String username) {
        return isUserLoggedIn(env, username);
    }

    /**
     * 【推荐】尝试恢复Session并跳过登录（如果可用）
     * 这是最常用的方法：自动处理session恢复和登录流程
     *
     * 注意：当session成功恢复并跳过登录时，会自动更新session的时间戳，延长session有效期
     *
     * @param username Username
     * @param loginOperation 登录操作（如果session不可用，会执行此操作）
     * @return true表示session已恢复并跳过登录，false表示执行了登录操作
     *
     * 示例：
     * if (SessionManager.tryRestoreAndSkipLogin(username, () -> {
     *     loginSteps.login(username, password);
     * })) {
     *     // Session已恢复，跳过登录
     *     // 同时session时间戳已更新，有效期延长
     * } else {
     *     // 执行了登录操作
     * }
     */
    public static boolean tryRestoreAndSkipLogin(String username, Runnable loginOperation) {
        if (restoreSession(username)) {
            LoggingConfigUtil.logInfoIfVerbose(logger, " Session restored for {}, skipping login", username);
            return true; // Session已恢复，跳过登录，时间戳已更新
        }

        // Session不可用，执行登录
        LoggingConfigUtil.logInfoIfVerbose(logger, "No valid session for {}, executing login", username);
        if (loginOperation != null) {
            try {
                loginOperation.run();
            } catch (Exception e) {
                logger.error("Login operation failed for user: {}", username, e);
                throw new RuntimeException("Login failed", e);
            }
        }
        return false; // 执行了登录操作
    }

    /**
     * 【推荐】尝试恢复Session并跳过登录（如果可用，带环境）
     *
     * 注意：当session成功恢复并跳过登录时，会自动更新session的时间戳，延长session有效期
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @param loginOperation 登录操作（如果session不可用，会执行此操作）
     * @return true表示session已恢复并跳过登录，false表示执行了登录操作
     *
     * 示例：
     * if (SessionManager.tryRestoreAndSkipLogin("O88_SIT1", username, () -> {
     *     loginSteps.login(username, password);
     * })) {
     *     // Session已恢复，跳过登录
     *     // 同时session时间戳已更新，有效期延长
     * }
     */
    public static boolean tryRestoreAndSkipLogin(String env, String username, Runnable loginOperation) {
        if (restoreSession(env, username)) {
            LoggingConfigUtil.logInfoIfVerbose(logger, " Session restored for {}/{}, skipping login", env, username);
            return true; // Session已恢复，跳过登录，时间戳已更新
        }

        // Session不可用，执行登录
        LoggingConfigUtil.logInfoIfVerbose(logger, "No valid session for {}/{}, executing login", env, username);
        if (loginOperation != null) {
            try {
                loginOperation.run();
            } catch (Exception e) {
                logger.error("Login operation failed for user: {}/{}", env, username, e);
                throw new RuntimeException("Login failed", e);
            }
        }
        return false; // 执行了登录操作
    }

    /**
     * 【简化】登录后保存Session - 一行代码
     * 自动标记用户已登录并保存session
     *
     * @param username Username
     * @param homeUrl 登录成功后的首页URL
     *
     * 示例：
     * loginSteps.login(username, password);
     * saveSessionAfterLogin(username, "https://example.com/home");
     */
    public static void saveSessionAfterLogin(String username, String homeUrl) {
        markUserLoggedIn(username);
        saveSession(username, homeUrl);
        LoggingConfigUtil.logInfoIfVerbose(logger, " Session saved after login for user: {}", username);
    }

    /**
     * 【简化】登录后保存Session - 一行代码（带环境）
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @param homeUrl 登录成功后的首页URL
     *
     * 示例：
     * loginSteps.login(username, password);
     * saveSessionAfterLogin("O88_SIT1", username, "https://example.com/home");
     */
    public static void saveSessionAfterLogin(String env, String username, String homeUrl) {
        markUserLoggedIn(env, username);
        saveSession(env, username, homeUrl);
        LoggingConfigUtil.logInfoIfVerbose(logger, " Session saved after login for user: {}/{}", env, username);
    }

    /**
     * 【简化】完整流程：尝试恢复或登录并保存
     * 这是一个便捷方法，封装了整个登录流程
     *
     * @param username Username
     * @param homeUrl 登录成功后的首页URL
     * @param loginOperation 登录操作（如果session不可用，会执行此操作）
     * @return true表示使用已有session，false表示执行了登录
     *
     * 示例：
     * SessionManager.restoreOrLogin(username, "https://example.com/home", () -> {
     *     loginSteps.login(username, password);
     * });
     */
    public static boolean restoreOrLogin(String username, String homeUrl, Runnable loginOperation) {
        boolean skippedLogin = tryRestoreAndSkipLogin(username, loginOperation);
        if (!skippedLogin && homeUrl != null) {
            // 执行了登录，保存session
            saveSessionAfterLogin(username, homeUrl);
        }
        return skippedLogin;
    }

    /**
     * 【简化】完整流程：尝试恢复或登录并保存（带环境）
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @param homeUrl 登录成功后的首页URL
     * @param loginOperation 登录操作（如果session不可用，会执行此操作）
     * @return true表示使用已有session，false表示执行了登录
     *
     * 示例：
     * SessionManager.restoreOrLogin("O88_SIT1", username, "https://example.com/home", () -> {
     *     loginSteps.login(username, password);
     * });
     */
    public static boolean restoreOrLogin(String env, String username, String homeUrl, Runnable loginOperation) {
        boolean skippedLogin = tryRestoreAndSkipLogin(env, username, loginOperation);
        if (!skippedLogin && homeUrl != null) {
            // 执行了登录，保存session
            saveSessionAfterLogin(env, username, homeUrl);
        }
        return skippedLogin;
    }

    /**
     * Generate session key from environment, username and browser type
     * Session key formats (supports multiple parameter combinations):
     * - env_username_browser (full format, e.g., O88_SIT1_AABBCCDD_chromium)
     * - env_username (recommended, e.g., O88_SIT1_AABBCCDD)
     * - username (legacy, auto env and browser, e.g., AABBCCDD)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1), can be null
     * @param username Username
     * @param browser Browser type (e.g., chromium, firefox), can be null
     * @return Session key
     */
    private static String generateSessionKey(String env, String username, String browser) {
        // 检查是否包含浏览器类型
        // 如果调用时明确传递了browser参数，则包含浏览器类型
        // 否则使用默认的env_username格式（推荐格式）
        boolean includeBrowser = (browser != null);

        // 构建session key
        if (env != null) {
            if (includeBrowser) {
                // 包含浏览器类型：env_username_browser
                return env + "_" + username + "_" + browser;
            } else {
                // 不包含浏览器类型：env_username（推荐格式）
                return env + "_" + username;
            }
        } else {
            // 不包含环境：username（legacy格式）
            return username;
        }
    }

    /**
     * Generate session key from environment and username (auto-detect browser)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1), can be null
     * @param username Username
     * @return Session key
     */
    private static String generateSessionKey(String env, String username) {
        return generateSessionKey(env, username, null);
    }

    /**
     * Generate session key from username only (legacy - auto env and browser)
     *
     * @param username Username
     * @return Session key
     */
    private static String generateSessionKey(String username) {
        return generateSessionKey(null, username, null);
    }

    /**
     * Escape JavaScript string to prevent syntax errors and XSS
     *
     * @param str String to escape
     * @return Escaped string
     */
    private static String escapeJsString(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\")
                  .replace("'", "\\'")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
