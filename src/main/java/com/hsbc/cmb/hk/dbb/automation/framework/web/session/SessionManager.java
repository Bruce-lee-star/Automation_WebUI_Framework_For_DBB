package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import com.microsoft.playwright.BrowserContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

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

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionManager.class);

    // Session storage directory
    private static final String SESSION_DIR = "target/.sessions";

    // Session timeout in minutes — read from FrameworkConfig (default: 5)
    private static final long SESSION_TIMEOUT_MINUTES =
            FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_NO_LOGIN_SESSION_TIMEOUT);

    // ==================== Feature 级别 Session 缓存 ====================
    // 用于支持 serenity.playwright.restart.browser.for.each=feature 配置
    // 确保同一个 Feature 中只恢复一次 Session，避免重复重建 Context

    // 记录当前 Feature 已恢复的 Session Key
    private static final ThreadLocal<String> currentFeatureSessionKey = new ThreadLocal<>();

    // 标记当前 Feature 是否已经恢复了 Session
    private static final ThreadLocal<Boolean> featureSessionRestored = ThreadLocal.withInitial(() -> false);

    // 记录当前 Feature 已恢复的 Session 的 homeUrl
    private static final ThreadLocal<String> currentFeatureHomeUrl = new ThreadLocal<>();

    /**
     * 检查当前 Feature 是否有任何 Session 被恢复/保存过。
     * <p>用于 Feature 模式下判断业务层是否使用了 SessionManager：
     * 若未曾使用，cleanupForScenario 应销毁 Context 而非保留 Cookie。
     */
    public static boolean isAnyFeatureSessionRestored() {
        return Boolean.TRUE.equals(featureSessionRestored.get());
    }

    /**
     * 标记 Feature 级别 Session 已恢复
     * <p>
     * 当第一个 Scenario 成功恢复 Session 后，调用此方法标记
     * 后续同一个 Feature 中的 Scenario 会直接复用，不再重建 Context
     *
     * @param sessionKey Session 标识
     * @param homeUrl 首页 URL
     */
    public static void markFeatureSessionRestored(String sessionKey, String homeUrl) {
        currentFeatureSessionKey.set(sessionKey);
        featureSessionRestored.set(true);
        currentFeatureHomeUrl.set(homeUrl);
        LoggingConfigUtil.logInfoIfVerbose(LOGGER,
            "Feature-level session marked as restored: {} (homeUrl: {})", sessionKey, homeUrl);
    }

    /**
     * 检查当前 Feature 是否已恢复指定的 Session
     * <p>
     * 用于避免在同一个 Feature 中重复恢复 Session 导致 Context 重建
     *
     * @param sessionKey Session 标识
     * @return true 表示当前 Feature 已恢复该 Session，可以直接复用
     */
    public static boolean isFeatureSessionRestored(String sessionKey) {
        Boolean restored = featureSessionRestored.get();
        String currentKey = currentFeatureSessionKey.get();

        if (restored != null && restored && sessionKey.equals(currentKey)) {
            LoggingConfigUtil.logInfoIfVerbose(LOGGER,
                "Feature-level session already restored for: {}, skipping restore", sessionKey);
            return true;
        }
        return false;
    }

    /**
     * 获取当前 Feature 已恢复 Session 的 homeUrl
     *
     * @return homeUrl，如果未恢复则返回 null
     */
    public static String getFeatureHomeUrl() {
        return currentFeatureHomeUrl.get();
    }

    /**
     * 【简化API】获取 homeUrl（自动处理 Feature 缓存和 meta 文件读取）
     * <p>
     * 封装了 homeUrl 的获取逻辑：
     * 1. 优先从 Feature 级别缓存读取（同一个 Feature 中已恢复的 Session）
     * 2. 如果缓存未命中，从 meta 文件读取
     * <p>
     * 业务层只需调用此方法，无需关心 homeUrl 的来源
     *
     * @param sessionKey Session 标识
     * @return homeUrl，如果不存在则返回 null
     */
    public static String getHomeUrl(String sessionKey) {
        // 优先从 Feature 级别缓存读取
        String homeUrl = getFeatureHomeUrl();
        if (homeUrl != null && !homeUrl.isEmpty()) {
            LoggingConfigUtil.logInfoIfVerbose(LOGGER,
                "HomeUrl loaded from Feature cache: {}", homeUrl);
            return homeUrl;
        }

        // 缓存未命中，从 meta 文件读取
        homeUrl = loadHomeUrl(sessionKey);
        if (homeUrl != null && !homeUrl.isEmpty()) {
            LoggingConfigUtil.logInfoIfVerbose(LOGGER,
                "HomeUrl loaded from meta file: {}", homeUrl);
        }

        return homeUrl;
    }

    /**
     * 重置 Feature 级别 Session 状态
     * <p>
     * 在 Feature 结束时调用，清理 ThreadLocal 变量
     */
    public static void resetFeatureSession() {
        LoggingConfigUtil.logInfoIfVerbose(LOGGER, "Resetting feature-level session state");
        currentFeatureSessionKey.remove();
        featureSessionRestored.remove();
        currentFeatureHomeUrl.remove();
    }

    /**
     * 【新】检查 Session 是否存在且有效
     * <p>
     * 框架层自动调用此方法检查 session 状态
     *
     * @param sessionKey Session 标识（如 "O63_SIT1_WP7UAT2_2"）
     * @return true 表示 session 文件存在且未过期，false 表示需要登录
     */
    private static boolean hasSession(String sessionKey) {
        Path sessionPath = getSessionPath(sessionKey);
        Path metaPath = getMetaPath(sessionKey);
        
        if (!Files.exists(sessionPath) || !Files.exists(metaPath)) {
            LoggingConfigUtil.logInfoIfVerbose(LOGGER, "Session file not found: {}", sessionKey);
            return false;
        }

        // 检查过期时间
        if (isSessionExpired(sessionKey)) {
            LoggingConfigUtil.logInfoIfVerbose(LOGGER, "Session expired for: {}", sessionKey);
            // 清除过期的 session
            try {
                Files.delete(sessionPath);
                Files.delete(metaPath);
            } catch (Exception e) {
                LOGGER.warn("Failed to delete expired session: {}", sessionKey, e);
            }
            return false;
        }

        LoggingConfigUtil.logInfoIfVerbose(LOGGER, "Valid session found for: {}", sessionKey);
        return true;
    }

    /**
     * 【新】准备 Session（框架层自动处理）
     * <p>
     * 此方法是框架层入口，负责：
     * 1. 检查 session 文件是否存在且未过期
     * 2. 如果存在，读取 homeUrl 并通过 PlaywrightManager 设置 storageStatePath
     * 3. 框架会自动延迟Context/Page创建（避免不必要的创建和销毁）
     * 4. 如果不存在或过期，等待业务层登录后调用 saveCurrentSession()
     * <p>
     * 自定义配置机制：
     * - storageStatePath 是用户自定义配置，优先级高于框架默认配置
     * - 通过 customContextOptionsFlag 标志控制是否应用自定义配置
     * - 业务层通过 PlaywrightManager.customOptions().setXXX() 设置自定义配置
     * - 框架在 createContext() 时检查标志并应用自定义配置
     * <p>
     * 职责划分：
     * - 业务层：只传递 session key，执行登录逻辑
     * - 框架层：检查 session、设置 storageStatePath（自定义配置）、保存 session
     *
     * @param sessionKey Session 标识（如 "O63_SIT1_WP7UAT2_2"）
     * @return true 表示 session 已准备好，false 表示需要登录
     */
    public static boolean restoreSession(String sessionKey) {
        String restartStrategy = PlaywrightManager.config().getRestartStrategy();
        
        if ("feature".equalsIgnoreCase(restartStrategy)) {
            // Feature 模式：检查 Feature 级别缓存
            if (isFeatureSessionRestored(sessionKey)) {
                String homeUrl = getFeatureHomeUrl();
                LoggingConfigUtil.logInfoIfVerbose(LOGGER,
                    "Feature-level session cache hit: {} (homeUrl: {})", sessionKey, homeUrl);
                return true;
            }
        } else {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "Scenario mode: skipping feature-level cache for {}", sessionKey);
        }

        if (hasSession(sessionKey)) {
            String homeUrl = loadHomeUrl(sessionKey);

            if (homeUrl != null && !homeUrl.isEmpty()) {
                Path sessionPath = getSessionPath(sessionKey);

                if ("scenario".equalsIgnoreCase(restartStrategy)) {
                    // ⭐ Scenario 模式：setStorageStatePath → customContextOptionsFlag=true
                    // → 下一个 getContext() 创建全新 Context 并加载缓存的 storageState
                    // 每个 Scenario 独立 Context = 每个 Scenario 一个窗口（预期行为）
                    PlaywrightManager.customOptions().setStorageStatePath(sessionPath);
                } else {
                    // Feature 模式
                    if (PlaywrightManager.hasContext()) {
                        // Context 已存在（前一个 Scenario 的登录态仍在）
                        // 当前是不同的登录用户 → 清除当前 Context 的 cookies → 返回 false 触发登录流程
                        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                                "Feature mode: Context already exists with different session, clearing cookies and returning false to trigger login for {}", sessionKey);
                        BrowserContext context = PlaywrightManager.getContext();
                        context.clearCookies();
                        return false;
                    }
                    // 首个 Scenario 或 Context 还未创建 → setStorageStatePath，后续 createContext 时应用
                    PlaywrightManager.customOptions().setStorageStatePath(sessionPath);
                }

                // 标记 Feature 级别 Session 已恢复
                if ("feature".equalsIgnoreCase(restartStrategy)) {
                    markFeatureSessionRestored(sessionKey, homeUrl);
                }

                LoggingConfigUtil.logInfoIfVerbose(LOGGER,
                    "Session prepared for: {} (custom storageStatePath: {})", sessionKey, sessionPath);
                return true;
            } else {
                LoggingConfigUtil.logWarnIfVerbose(LOGGER,
                    "Session file exists but no homeUrl found: {}", sessionKey);
                return false;
            }
        } else {
            LoggingConfigUtil.logInfoIfVerbose(LOGGER,
                "No valid session for: {}, waiting for login", sessionKey);
            return false;
        }
    }

    /**
     * 【新】保存当前 Context 的 Session
     * <p>
     * 此方法由业务层在登录成功后调用，框架会自动：
     * 1. 保存 Playwright storageState 到文件
     * 2. 保存元数据（homeUrl + timestamp）
     * <p>
     * 使用示例：
     * <pre>
     * // 登录成功后，业务层调用
     * SessionManager.saveCurrentSession("O63_SIT1_WP7UAT2_2", homeUrl);
     * </pre>
     *
     * @param sessionKey Session 标识（如 "O63_SIT1_WP7UAT2_2"）
     * @param homeUrl 登录成功后的首页 URL
     */
    public static void saveSession(String sessionKey, String homeUrl) {
        try {
            Path sessionPath = getSessionPath(sessionKey);

            // 确保目录存在
            if (!Files.exists(sessionPath.getParent())) {
                Files.createDirectories(sessionPath.getParent());
            }

            LoggingConfigUtil.logInfoIfVerbose(LOGGER,
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

            // 【关键】标记 Feature 级别 Session 已保存（后续 Scenario 直接复用）
            markFeatureSessionRestored(sessionKey, homeUrl);

            LoggingConfigUtil.logInfoIfVerbose(LOGGER,
                "Session saved successfully: {} -> {}", sessionKey, sessionPath);
        } catch (Exception e) {
            LOGGER.error("Failed to save session for: {}", sessionKey, e);
            throw new RuntimeException("Failed to save session", e);
        }
    }

    /**
     * 【新】清除指定的 Session
     * <p>
     * 此方法用于清除指定用户的 session，包括：
     * 1. 删除 session storageState 文件
     * 2. 删除 session 元数据文件
     * <p>
     * 使用场景：
     * - 用户登出时清除 session
     * - 需要强制重新登录时清除 session
     * - 测试清理时清除 session
     * <p>
     * 使用示例：
     * <pre>
     * // 登出时清除 session
     * SessionManager.clearSession("O63_SIT1_WP7UAT2_2");
     * </pre>
     *
     * @param sessionKey Session 标识（如 "O63_SIT1_WP7UAT2_2"）
     * @return true 表示清除成功，false 表示 session 不存在或清除失败
     */
    public static boolean clearSession(String sessionKey) {
        try {
            Path sessionPath = getSessionPath(sessionKey);
            Path metaPath = getMetaPath(sessionKey);
            
            boolean sessionDeleted = false;
            boolean metaDeleted = false;
            
            // 删除 session 文件
            if (Files.exists(sessionPath)) {
                Files.delete(sessionPath);
                sessionDeleted = true;
                LoggingConfigUtil.logInfoIfVerbose(LOGGER, 
                    "Session file deleted: {}", sessionPath);
            }
            
            // 删除 meta 文件
            if (Files.exists(metaPath)) {
                Files.delete(metaPath);
                metaDeleted = true;
                LoggingConfigUtil.logInfoIfVerbose(LOGGER, 
                    "Meta file deleted: {}", metaPath);
            }
            
            // 只要有一个文件被删除就返回 true
            boolean cleared = sessionDeleted || metaDeleted;
            
            if (cleared) {
                LOGGER.info("Session cleared successfully: {}", sessionKey);
            } else {
                LoggingConfigUtil.logInfoIfVerbose(LOGGER, 
                    "No session found to clear: {}", sessionKey);
            }
            
            return cleared;
        } catch (Exception e) {
            LOGGER.error("Failed to clear session for: {}", sessionKey, e);
            return false;
        }
    }

    /**
     * 【新】清除所有 Session
     * <p>
     * 此方法用于清除所有用户的 session，适用于：
     * - 测试环境清理
     * - 批量登出
     * <p>
     * 使用示例：
     * <pre>
     * // 清理所有 session
     * int count = SessionManager.clearAllSessions();
     * System.out.println("Cleared " + count + " sessions");
     * </pre>
     *
     * @return 清除的 session 数量
     */
    public static int clearAllSessions() {
        try {
            Path sessionDir = Paths.get(SESSION_DIR);
            if (!Files.exists(sessionDir)) {
                return 0;
            }

            // 单次遍历：收集基础名并删除文件
            Set<String> sessionNames = new HashSet<>();
            try (var stream = Files.list(sessionDir)) {
                stream.forEach(path -> {
                    String name = path.getFileName().toString();
                    if (name.endsWith(".json") || name.endsWith(".meta")) {
                        int dotIdx = name.lastIndexOf('.');
                        sessionNames.add(dotIdx > 0 ? name.substring(0, dotIdx) : name);
                        try {
                            Files.delete(path);
                        } catch (Exception e) {
                            LOGGER.warn("Failed to delete: {}", path, e);
                        }
                    }
                });
            }

            LOGGER.info("Cleared {} session(s)", sessionNames.size());
            return sessionNames.size();
        } catch (Exception e) {
            LOGGER.error("Failed to clear all sessions", e);
            return 0;
        }
    }

    /**
     * 【新】加载 HomeUrl
     * <p>
     * 从 meta 文件加载 homeUrl
     *
     * @param sessionKey Session 标识
     * @return homeUrl，如果不存在返回 null
     */
    public static String loadHomeUrl(String sessionKey) {
        Path metaPath = getMetaPath(sessionKey);
        if (!Files.exists(metaPath)) {
            return null;
        }

        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(metaPath, StandardCharsets.UTF_8)) {
            props.load(reader);
            return props.getProperty("homeUrl");
        } catch (Exception e) {
            LOGGER.warn("Failed to load homeUrl for: {}", sessionKey, e);
            return null;
        }
    }

    /**
     * 【新】检查 Session 是否过期
     */
    private static boolean isSessionExpired(String sessionKey) {
        Path metaPath = getMetaPath(sessionKey);
        if (!Files.exists(metaPath)) {
            return true;
        }

        Properties props = new Properties();
        try {
            try (var reader = Files.newBufferedReader(metaPath, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            String lastAccessTimeStr = props.getProperty("lastAccessTime");
            if (lastAccessTimeStr == null || lastAccessTimeStr.isEmpty()) {
                return true;
            }

            long lastAccessTime = Long.parseLong(lastAccessTimeStr);
            long currentTime = System.currentTimeMillis();
            long elapsedMinutes = (currentTime - lastAccessTime) / (60 * 1000);

            return elapsedMinutes > SESSION_TIMEOUT_MINUTES;
        } catch (Exception e) {
            LOGGER.warn("Failed to check session expiration for: {}", sessionKey, e);
            return true;
        }
    }

    /**
     * 【新】获取 Session 文件路径
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
                try (var reader = Files.newBufferedReader(metaPath, StandardCharsets.UTF_8)) {
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
            try (var writer = Files.newBufferedWriter(metaPath, StandardCharsets.UTF_8)) {
                props.store(writer, "Session Meta Data");
            }

            LoggingConfigUtil.logDebugIfVerbose(LOGGER, "Meta saved: {} (homeUrl: {})", sessionKey, homeUrl);
        } catch (Exception e) {
            LOGGER.warn("Failed to save meta for: {}", sessionKey, e);
        }
    }
}

