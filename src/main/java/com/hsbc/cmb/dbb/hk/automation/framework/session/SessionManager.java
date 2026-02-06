package com.hsbc.cmb.dbb.hk.automation.framework.session;

import com.hsbc.cmb.dbb.hk.automation.framework.config.FrameworkConfig;
import com.hsbc.cmb.dbb.hk.automation.framework.config.FrameworkConfigManager;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import com.hsbc.cmb.dbb.hk.automation.framework.lifecycle.PlaywrightManager;
import com.hsbc.cmb.dbb.hk.automation.framework.utils.LoggingConfigUtil;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
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
 * Session Key Formats:
 * - Recommended: env_username (e.g., O88_SIT1_AABBCCDD, O63_SIT1_AABBCCDD)
 * - Legacy: username only (e.g., AABBCCDD)
 *
 * Using env+username allows same username to have different sessions in different environments
 *
 * Session Configuration (in serenity.conf):
 * - session.timeout: Session timeout in minutes (default: 30)
 *   Example: session.timeout=60
 *
 * Session Storage: target/.sessions/
 */
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    // Session storage directory
    private static final String SESSION_DIR = "target/.sessions";

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
     * Save user session to file
     * Uses env+username as session key (recommended)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @param homeUrl Home page URL after successful login
     */
    public static void saveSession(String env, String username, String homeUrl) {
        String sessionKey = generateSessionKey(env, username);
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

            // Save to file
            Path sessionDir = Paths.get(SESSION_DIR);
            if (!Files.exists(sessionDir)) {
                Files.createDirectories(sessionDir);
            }

            String sessionFile = sessionDir.resolve(sessionKey + ".session").toString();
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(sessionFile))) {
                oos.writeObject(session);
            }

            LoggingConfigUtil.logInfoIfVerbose(logger, "✅ Session saved for session key: {} to {}", sessionKey, sessionFile);
        } catch (Exception e) {
            logger.error("Failed to save session for session key: {}", sessionKey, e);
            throw new RuntimeException("Failed to save session", e);
        }
    }

    /**
     * Save user session to file (legacy - uses username only as key)
     * Backward compatible version for existing code
     *
     * @param username Username
     * @param homeUrl Home page URL after successful login
     */
    public static void saveSession(String username, String homeUrl) {
        try {
            UserSession session = activeSessions.get(username);
            if (session == null) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "No active session found for user: {}", username);
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

            // Save to file
            Path sessionDir = Paths.get(SESSION_DIR);
            if (!Files.exists(sessionDir)) {
                Files.createDirectories(sessionDir);
            }

            String sessionFile = sessionDir.resolve(username + ".session").toString();
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(sessionFile))) {
                oos.writeObject(session);
            }

            LoggingConfigUtil.logInfoIfVerbose(logger, "✅ Session saved for user: {} to {}", username, sessionFile);
        } catch (Exception e) {
            logger.error("Failed to save session for user: {}", username, e);
            throw new RuntimeException("Failed to save session", e);
        }
    }
    
    /**
     * Load user session
     * Uses env+username as session key (recommended)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @return User session, return null if not exists
     */
    public static UserSession loadSession(String env, String username) {
        String sessionKey = generateSessionKey(env, username);
        try {
            Path sessionFile = Paths.get(SESSION_DIR, sessionKey + ".session");

            if (!Files.exists(sessionFile)) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "No saved session found for session key: {}", sessionKey);
                return null;
            }

            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(sessionFile.toString()))) {
                UserSession session = (UserSession) ois.readObject();

                // Add session to active sessions
                activeSessions.put(sessionKey, session);

                LoggingConfigUtil.logInfoIfVerbose(logger, "✅ Session loaded for session key: {}", sessionKey);
                return session;
            }
        } catch (Exception e) {
            logger.error("Failed to load session for session key: {}", sessionKey, e);
            return null;
        }
    }

    /**
     * Load user session (legacy - uses username only as key)
     * Backward compatible version for existing code
     *
     * @param username Username
     * @return User session, return null if not exists
     */
    public static UserSession loadSession(String username) {
        try {
            Path sessionFile = Paths.get(SESSION_DIR, username + ".session");

            if (!Files.exists(sessionFile)) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "No saved session found for user: {}", username);
                return null;
            }

            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(sessionFile.toString()))) {
                UserSession session = (UserSession) ois.readObject();

                // Add session to active sessions
                activeSessions.put(username, session);

                LoggingConfigUtil.logInfoIfVerbose(logger, "✅ Session loaded for user: {}", username);
                return session;
            }
        } catch (Exception e) {
            logger.error("Failed to load session for user: {}", username, e);
            return null;
        }
    }
    
    /**
     * Restore session to current context
     * Uses env+username as session key (recommended)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @return Return true if successfully restored, otherwise return false
     */
    public static boolean restoreSession(String env, String username) {
        String sessionKey = generateSessionKey(env, username);
        try {
            UserSession session = loadSession(env, username);
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

            // Restore localStorage if needed
            // context.addInitScript(...); can use script to set localStorage

            // Update access time to extend session validity
            session.updateAccessTime();

            LoggingConfigUtil.logInfoIfVerbose(logger, "✅ Session restored for session key: {}", sessionKey);
            return true;
        } catch (Exception e) {
            logger.error("Failed to restore session for session key: {}", sessionKey, e);
            return false;
        }
    }

    /**
     * Restore session to current context (legacy - uses username only as key)
     * Backward compatible version for existing code
     *
     * @param username Username
     * @return Return true if successfully restored, otherwise return false
     */
    public static boolean restoreSession(String username) {
        try {
            UserSession session = loadSession(username);
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

            // Restore localStorage if needed
            // context.addInitScript(...); can use script to set localStorage

            // Update access time to extend session validity
            session.updateAccessTime();

            LoggingConfigUtil.logInfoIfVerbose(logger, "✅ Session restored for user: {}", username);
            return true;
        } catch (Exception e) {
            logger.error("Failed to restore session for user: {}", username, e);
            return false;
        }
    }
    
    /**
     * Create or get user session
     * Uses env+username as session key (recommended)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @return User session
     */
    public static UserSession getOrCreateSession(String env, String username) {
        String sessionKey = generateSessionKey(env, username);
        UserSession session = activeSessions.get(sessionKey);
        if (session == null) {
            session = new UserSession(sessionKey);
            activeSessions.put(sessionKey, session);
        }
        return session;
    }

    /**
     * Create or get user session (legacy - uses username only as key)
     * Backward compatible version for existing code
     *
     * @param username Username
     * @return User session
     */
    public static UserSession getOrCreateSession(String username) {
        UserSession session = activeSessions.get(username);
        if (session == null) {
            session = new UserSession(username);
            activeSessions.put(username, session);
        }
        return session;
    }

    /**
     * Mark user as logged in
     * Uses env+username as session key (recommended)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     */
    public static void markUserLoggedIn(String env, String username) {
        String sessionKey = generateSessionKey(env, username);
        UserSession session = getOrCreateSession(env, username);
        session.setLoggedIn(true);
        LoggingConfigUtil.logInfoIfVerbose(logger, "✅ User marked as logged in: {}", sessionKey);
    }

    /**
     * Mark user as logged in (legacy - uses username only as key)
     * Backward compatible version for existing code
     *
     * @param username Username
     */
    public static void markUserLoggedIn(String username) {
        UserSession session = getOrCreateSession(username);
        session.setLoggedIn(true);
        LoggingConfigUtil.logInfoIfVerbose(logger, "✅ User marked as logged in: {}", username);
    }
    
    /**
     * Clear user session
     * Uses env+username as session key (recommended)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     */
    public static void clearSession(String env, String username) {
        String sessionKey = generateSessionKey(env, username);
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
     * Clear user session (legacy - uses username only as key)
     * Backward compatible version for existing code
     *
     * @param username Username
     */
    public static void clearSession(String username) {
        try {
            activeSessions.remove(username);

            Path sessionFile = Paths.get(SESSION_DIR, username + ".session");
            if (Files.exists(sessionFile)) {
                Files.delete(sessionFile);
                LoggingConfigUtil.logInfoIfVerbose(logger, "🗑️ Session cleared for user: {}", username);
            }
        } catch (Exception e) {
            logger.error("Failed to clear session for user: {}", username, e);
        }
    }

    /**
     * Check if user is logged in (via session file)
     * Uses env+username as session key (recommended)
     * Checks both session existence and expiration
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @return Return true if user is logged in and session is not expired
     */
    public static boolean isUserLoggedIn(String env, String username) {
        String sessionKey = generateSessionKey(env, username);
        Path sessionFile = Paths.get(SESSION_DIR, sessionKey + ".session");
        if (!Files.exists(sessionFile)) {
            return false;
        }

        UserSession session = loadSession(env, username);
        if (session == null || !session.isLoggedIn()) {
            return false;
        }

        // Check if session is expired
        if (session.isExpired(SESSION_TIMEOUT_MINUTES)) {
            LoggingConfigUtil.logInfoIfVerbose(logger,
                "Session expired for session key: {} (last accessed: {} minutes ago)",
                sessionKey, SESSION_TIMEOUT_MINUTES);
            // Clear expired session
            clearSession(env, username);
            return false;
        }

        return true;
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
        Path sessionFile = Paths.get(SESSION_DIR, username + ".session");
        if (!Files.exists(sessionFile)) {
            return false;
        }

        UserSession session = loadSession(username);
        if (session == null || !session.isLoggedIn()) {
            return false;
        }

        // Check if session is expired
        if (session.isExpired(SESSION_TIMEOUT_MINUTES)) {
            LoggingConfigUtil.logInfoIfVerbose(logger,
                "Session expired for user: {} (last accessed: {} minutes ago)",
                username, SESSION_TIMEOUT_MINUTES);
            // Clear expired session
            clearSession(username);
            return false;
        }

        return true;
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
     * Generate session key from environment and username
     * Session key format: env_username (e.g., O88_SIT1_AABBCCDD)
     *
     * @param env Environment identifier (e.g., O88_SIT1, O63_SIT1)
     * @param username Username
     * @return Session key
     */
    private static String generateSessionKey(String env, String username) {
        return env + "_" + username;
    }
}
