package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Session Manager - Manage user login state, supports skip login functionality
 * <p>
 * 新版特性：
 * - 框架层自动处理 session 管理逻辑
 * - 业务层只需传递 session key
 * - 自动处理 session 过期检查
 * - 简化的 API 设计
 * <p>
 * Session Key 格式（推荐）：env_username（如 O63_SIT1_WP7UAT2_2）
 */
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    // Session storage directory
    private static final String SESSION_DIR = "target/.sessions";

    // Session timeout in minutes (read from FrameworkConfig)
    private static final long SESSION_TIMEOUT_MINUTES = 60; // 默认60分钟过期
    
    // ThreadLocal: 标记当前 session 是否已准备好
    private static final ThreadLocal<Boolean> sessionPrepared = ThreadLocal.withInitial(() -> false);

    /**
     * 【超级简单 API】尝试恢复 Session
     * <p>
     * 此方法自动处理所有逻辑：
     * - 检查 session 文件是否存在
     * - 检查 session 是否过期
     * - 如果有效，自动设置 storageStatePath
     * - 如果无效或不存在，返回 false
     * <p>
     * 用户只需要：
     * <pre>
     * if (!SessionManager.restoreSession("O63_SIT1_WP7UAT2_2")) {
     *     // 执行登录
     *     SessionManager.saveSession("O63_SIT1_WP7UAT2_2", homeUrl);
     * }
     * </pre>
     *
     * @param sessionKey Session 标识（如 "O63_SIT1_WP7UAT2_2"）
     * @return true 表示 session 恢复成功，false 表示需要登录
     */
    public static boolean restoreSession(String sessionKey) {
        logger.info("Attempting to restore session: {}", sessionKey);
        
        if (hasSession(sessionKey)) {
            // Session 有效，读取 homeUrl
            String homeUrl = loadHomeUrl(sessionKey);

            if (homeUrl != null && !homeUrl.isEmpty()) {
                // 设置 storageStatePath（自动使用默认路径）
                PlaywrightManager.setStorageStatePath(sessionKey);
                
                sessionPrepared.set(true);
                
                logger.info("Session restored successfully: {}", sessionKey);
                return true;
            } else {
                logger.warn("Session file exists but no homeUrl found: {}", sessionKey);
                return false;
            }
        } else {
            logger.info("No valid session found for: {}, need to login", sessionKey);
            return false;
        }
    }
    
    /**
     * 【超级简单 API】保存 Session
     * <p>
     * 此方法自动：
     * - 保存 Playwright storageState
     * - 保存元数据（homeUrl + timestamp）
     * <p>
     * 使用示例：
     * <pre>
     * // 登录成功后调用
     * SessionManager.saveSession("O63_SIT1_WP7UAT2_2", homeUrl);
     * </pre>
     *
     * @param sessionKey Session 标识（如 "O63_SIT1_WP7UAT2_2"）
     * @param homeUrl 登录成功后的首页 URL
     */
    public static void saveSession(String sessionKey, String homeUrl) {
        logger.info("Saving session: {} (homeUrl: {})", sessionKey, homeUrl);
        saveCurrentSession(sessionKey, homeUrl);
        sessionPrepared.set(true);
    }

    /**
     * 检查 Session 是否存在且有效
     * <p>
     * 内部方法，由 restoreSession 调用
     *
     * @param sessionKey Session 标识（如 "O63_SIT1_WP7UAT2_2"）
     * @return true 表示 session 文件存在且未过期，false 表示需要登录
     */
    private static boolean hasSession(String sessionKey) {
        Path sessionPath = getSessionPath(sessionKey);
        Path metaPath = getMetaPath(sessionKey);
        
        if (!Files.exists(sessionPath) || !Files.exists(metaPath)) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Session file not found: {}", sessionKey);
            return false;
        }

        // 检查过期时间
        if (isSessionExpired(sessionKey)) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Session expired for: {}", sessionKey);
            // 清除过期的 session
            try {
                Files.delete(sessionPath);
                Files.delete(metaPath);
            } catch (Exception e) {
                logger.warn("Failed to delete expired session: {}", sessionKey, e);
            }
            return false;
        }

        LoggingConfigUtil.logInfoIfVerbose(logger, "Valid session found for: {}", sessionKey);
        return true;
    }

    /**
     * 准备 Session
     * <p>
     * 内部方法，由 restoreSession 调用
     *
     * @param sessionKey Session 标识（如 "O63_SIT1_WP7UAT2_2"）
     * @return true 表示 session 已准备好，false 表示需要登录
     */
    private static boolean prepareSession(String sessionKey) {
        if (hasSession(sessionKey)) {
            // Session 有效，读取 homeUrl
            String homeUrl = loadHomeUrl(sessionKey);

            if (homeUrl != null && !homeUrl.isEmpty()) {
                // 设置 storageStatePath（用户自定义配置，优先级高于框架默认配置）
                Path sessionPath = getSessionPath(sessionKey);
                PlaywrightManager.setStorageStatePath(sessionPath);

                LoggingConfigUtil.logInfoIfVerbose(logger,
                    "Session prepared for: {} (custom storageStatePath: {})", sessionKey, sessionPath);
                return true;
            } else {
                LoggingConfigUtil.logWarnIfVerbose(logger,
                    "Session file exists but no homeUrl found: {}", sessionKey);
                return false;
            }
        } else {
            LoggingConfigUtil.logInfoIfVerbose(logger,
                "No valid session for: {}, waiting for login", sessionKey);
            return false;
        }
    }

    /**
     * 保存当前 Context 的 Session
     * <p>
     * 内部方法，由 saveSession 调用
     *
     * @param sessionKey Session 标识（如 "O63_SIT1_WP7UAT2_2"）
     * @param homeUrl 登录成功后的首页 URL
     */
    private static void saveCurrentSession(String sessionKey, String homeUrl) {
        try {
            Path sessionPath = getSessionPath(sessionKey);
            
            // 确保目录存在
            if (!Files.exists(sessionPath.getParent())) {
                Files.createDirectories(sessionPath.getParent());
            }

            LoggingConfigUtil.logInfoIfVerbose(logger, 
                "Saving session for: {} (homeUrl: {})", sessionKey, homeUrl);

            // 获取当前 context
            BrowserContext context = PlaywrightManager.getContext();
            
            if (context == null) {
                throw new IllegalStateException("No context available for saving session");
            }

            // 使用 Playwright API 保存 storageState
            context.storageState(new BrowserContext.StorageStateOptions().setPath(sessionPath));

            // 保存元数据（homeUrl + timestamp）
            saveMeta(sessionKey, homeUrl);

            LoggingConfigUtil.logInfoIfVerbose(logger, 
                "Session saved successfully: {} -> {}", sessionKey, sessionPath);
        } catch (Exception e) {
            logger.error("Failed to save session for: {}", sessionKey, e);
            throw new RuntimeException("Failed to save session", e);
        }
    }

    /**
     * 加载 HomeUrl
     * <p>
     * 从 meta 文件加载 homeUrl
     *
     * @param sessionKey Session 标识
     * @return homeUrl，如果不存在返回 null
     */
    public static String loadHomeUrl(String sessionKey) {
        try {
            Path metaPath = getMetaPath(sessionKey);
            if (!Files.exists(metaPath)) {
                return null;
            }

            Properties props = new Properties();
            try (var reader = Files.newBufferedReader(metaPath, java.nio.charset.StandardCharsets.UTF_8)) {
                props.load(reader);
            }

            return props.getProperty("homeUrl");
        } catch (Exception e) {
            logger.warn("Failed to load homeUrl for: {}", sessionKey, e);
            return null;
        }
    }

    /**
     * 【新】检查 Session 是否过期
     * <p>
     * 检查 session 的 lastAccessTime 是否超时
     *
     * @param sessionKey Session 标识
     * @return true 表示已过期，false 表示未过期
     */
    private static boolean isSessionExpired(String sessionKey) {
        try {
            Path metaPath = getMetaPath(sessionKey);
            if (!Files.exists(metaPath)) {
                // 没有 meta 文件，认为过期
                return true;
            }

            Properties props = new Properties();
            try (var reader = Files.newBufferedReader(metaPath, java.nio.charset.StandardCharsets.UTF_8)) {
                props.load(reader);
            }

            String lastAccessTimeStr = props.getProperty("lastAccessTime");
            if (lastAccessTimeStr == null || lastAccessTimeStr.isEmpty()) {
                // 没有时间戳，认为过期
                return true;
            }

            long lastAccessTime = Long.parseLong(lastAccessTimeStr);
            long currentTime = System.currentTimeMillis();
            long elapsedMinutes = (currentTime - lastAccessTime) / (60 * 1000);
            
            return elapsedMinutes > SESSION_TIMEOUT_MINUTES;
        } catch (Exception e) {
            logger.warn("Failed to check session expiration for: {}", sessionKey, e);
            return true; // 出错时认为过期
        }
    }

    /**
     * 【新】获取 Session 文件路径
     * <p>
     * 使用 target/.sessions 目录
     *
     * @param sessionKey Session 标识
     * @return Session 文件路径
     */
    private static Path getSessionPath(String sessionKey) {
        return Paths.get(SESSION_DIR, sessionKey + ".json");
    }

    /**
     * 【新】获取 Meta 文件路径
     * <p>
     * 使用 target/.sessions 目录
     *
     * @param sessionKey Session 标识
     * @return Meta 文件路径
     */
    private static Path getMetaPath(String sessionKey) {
        return Paths.get(SESSION_DIR, sessionKey + ".meta");
    }

    /**
     * 【新】保存元数据
     * <p>
     * 保存 homeUrl 和 timestamp 到 meta 文件
     *
     * @param sessionKey Session 标识
     * @param homeUrl 首页 URL
     */
    private static void saveMeta(String sessionKey, String homeUrl) {
        try {
            Path metaPath = getMetaPath(sessionKey);
            Properties props = new Properties();

            // 如果文件已存在，先读取现有数据
            if (Files.exists(metaPath)) {
                try (var reader = Files.newBufferedReader(metaPath, java.nio.charset.StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
            }

            // 更新 homeUrl
            if (homeUrl != null && !homeUrl.isEmpty()) {
                props.setProperty("homeUrl", homeUrl);
            }

            // 更新时间戳
            props.setProperty("lastAccessTime", String.valueOf(System.currentTimeMillis()));

            // 写入文件
            try (var writer = Files.newBufferedWriter(metaPath, java.nio.charset.StandardCharsets.UTF_8)) {
                props.store(writer, "Session Meta Data");
            }

            LoggingConfigUtil.logDebugIfVerbose(logger, "Meta saved: {} (homeUrl: {})", sessionKey, homeUrl);
        } catch (Exception e) {
            logger.warn("Failed to save meta for: {}", sessionKey, e);
        }
    }
}
