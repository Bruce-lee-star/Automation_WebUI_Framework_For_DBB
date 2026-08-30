package com.hsbc.cmb.hk.dbb.automation.framework.api.config;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import com.hsbc.cmb.hk.dbb.automation.framework.api.utility.ApiLogSanitizer;
import com.hsbc.cmb.hk.dbb.automation.framework.api.utility.Constants;
import com.hsbc.cmb.hk.dbb.automation.framework.api.utility.EnvironmentUtils;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration Provider Utility (With Configurable Paths)
 * Supports configurable base paths, config directory, and payload directory
 */
public class ConfigProvider {
    // Initialize logger FIRST (critical for early logging)
    public static final Logger LOGGER = LoggerFactory.getLogger(ConfigProvider.class);

    // Default configuration constants (fallback values)
    private static final String DEFAULT_BASE_PATH = "./src/test/resources/";
    private static final String DEFAULT_CONFIG_DIR = "config/";
    private static final String DEFAULT_PAYLOAD_DIR = "payload/";
    private static final String DEFAULT_CONFIG_FILE_NAME = "application.conf";
    private static final String DEFAULT_PROPERTIES_FILE_NAME = "application.properties";
    private static final String CONFIG_FILE_EXTENSIONS[] = {".conf", ".properties"};
    private static final String HEADERS_NODE = "headers";

    // Configurable paths (loaded from application.conf)
    // ⭐ 修复 P2-22：这些字段虽主要由静态块初始化，但会被并行线程读取；
    //    加 volatile 保证写入对所有线程立即可见，避免读到 null / 默认值。
    private static volatile String basePath;
    private static volatile String configDir;
    private static volatile String payloadDir;

    /**
     * 全局配置快照（进程级共享可变状态）。
     * <p>
     * ⭐ 修复 P2-22：原为非 volatile 静态可变字段，且 {@link #getConfig()} 以无锁的
     * check-then-act 方式读写它——与 {@code synchronized} 的 {@link #config(Entity)}
     * 之间没有 happens-before 关系，并发下会重复加载、互相覆盖，或读到过期值。
     * <p>
     * ⚠️ <b>设计局限（超出本次改动范围，需架构级重构）</b>：本字段是进程级共享状态，
     * 并行 scenario 下不同 entity 调用 {@code config(entity)} 会<b>互相覆盖</b>彼此的全局配置。
     * 调用方应优先使用 {@code config(entity)} 的<b>返回值</b>，而非 {@link #getConfig()}，
     * 以规避跨 scenario 配置串扰。
     */
    private static volatile Config config;

    // Static block: Load framework paths from application.conf
    static {
        loadFrameworkPaths();
        LOGGER.info("=== ConfigProvider Initializing ===");
        LOGGER.info("Framework Base Path: {}", basePath);
        LOGGER.info("Config Directory: {}", getConfigBasePath());
        LOGGER.info("Payload Directory: {}", getPayloadBasePath());
        LOGGER.info("Default config file path: {}", getConfigBasePath() + DEFAULT_CONFIG_FILE_NAME);
    }

    /**
     * Load framework paths from application.conf
     * Reads paths.base-path, paths.config-dir, and paths.payload-dir
     * Falls back to default values if not configured
     */
    private static void loadFrameworkPaths() {
        try {
            // Try to load application.conf for path configuration
            Config appConfig = loadConfigFile(DEFAULT_CONFIG_FILE_NAME, false);

            if (appConfig.hasPath("paths.base-path")) {
                basePath = appConfig.getString("paths.base-path");
                // Normalize path: ensure it ends with separator
                if (!basePath.endsWith(File.separator) && !basePath.endsWith("/")) {
                    basePath = basePath + File.separator;
                }
            } else {
                basePath = DEFAULT_BASE_PATH;
            }

            if (appConfig.hasPath("paths.config-dir")) {
                configDir = appConfig.getString("paths.config-dir");
                // Normalize path: remove leading/trailing separators
                configDir = configDir.replaceAll("^[\\\\/]+|[\\\\/]+$", "");
            } else {
                configDir = DEFAULT_CONFIG_DIR;
            }

            if (appConfig.hasPath("paths.payload-dir")) {
                payloadDir = appConfig.getString("paths.payload-dir");
                // Normalize path: remove leading/trailing separators
                payloadDir = payloadDir.replaceAll("^[\\\\/]+|[\\\\/]+$", "");
            } else {
                payloadDir = DEFAULT_PAYLOAD_DIR;
            }

            LOGGER.info("Framework paths loaded from application.conf successfully");

        } catch (Exception e) {
            LOGGER.warn("Failed to load framework paths from application.conf, using defaults", e);
            // Use default paths
            basePath = DEFAULT_BASE_PATH;
            configDir = DEFAULT_CONFIG_DIR;
            payloadDir = DEFAULT_PAYLOAD_DIR;
        }
    }

    /**
     * Get base path for all resources
     * Can be configured in application.conf under paths.base-path
     *
     * @return base path (with trailing separator)
     */
    public static String getBasePath() {
        return basePath;
    }

    /**
     * Get configuration directory path (full path)
     * Combines base path and config directory
     *
     * @return full config directory path
     */
    public static String getConfigBasePath() {
        return basePath + configDir + File.separator;
    }

    /**
     * Get payload directory path (full path)
     * Combines base path and payload directory
     *
     * @return full payload directory path
     */
    public static String getPayloadBasePath() {
        return basePath + payloadDir + File.separator;
    }

    /**
     * Load and merge configurations (thread-safe)
     *
     * @param entity Entity to load configuration for. If null or entity name is empty,
     *               returns default configuration without throwing exception.
     * @return merged configuration
     */
    public static Config config(Entity entity) {
        return config(entity, null);
    }

    /**
     * Load and merge configurations with an explicit environment override (thread-safe).
     * <p>
     * ⭐ 修复 P2-21：提供显式 env 入参，替代原先"调用方先
     * {@code System.setProperty(ENV, env)} 再调用本方法"的写法——后者污染 JVM 全局状态，
     * 并行 scenario 下线程 A 设置的环境会被线程 B 读到，造成配置串扰。
     *
     * @param entity      Entity to load configuration for; may be null or empty-named
     * @param envOverride 环境覆盖值；null/空表示回退到 {@code EnvironmentUtils} 解析出的环境
     * @return merged configuration
     */
    public static synchronized Config config(Entity entity, String envOverride) {
        // Early validation log
        LOGGER.info("=== Starting config loading for entity ===");
        LOGGER.info("Entity name (raw): {}", entity != null ? entity.getEntityName() : "NULL");

        // Allow null entity for dynamic configuration
        if (entity == null || entity.getEntityName() == null || entity.getEntityName().trim().isEmpty()) {
            LOGGER.info("Entity is null or entity name is empty, loading default configuration only");
            try {
                // Load only default configuration
                Config defaultConfig = loadConfigFile(DEFAULT_CONFIG_FILE_NAME, false);
                config = defaultConfig.resolve();
                LOGGER.info("Default configuration loaded successfully");
                return config;
            } catch (Exception e) {
                LOGGER.warn("Failed to load default configuration, returning empty config", e);
                return ConfigFactory.empty();
            }
        }

        String entityName = entity.getEntityName().trim();
        LOGGER.info("Processing entity: {}", entityName);

        try {
            // 1. Load default configuration
            Config defaultConfig = loadConfigFile(DEFAULT_CONFIG_FILE_NAME, false);
            LOGGER.info("[1/4] Default config loaded successfully, headers key set: {}",
                    defaultConfig.hasPath(HEADERS_NODE) ? defaultConfig.getConfig(HEADERS_NODE).root().keySet() : "empty");

            // 2. Load entity-specific configuration (try both .conf and .properties)
            Config entityConfig = loadEntityConfig(entityName);
            LOGGER.info("[2/4] Entity({}) config loaded successfully, headers key set: {}",
                    entityName, entityConfig.hasPath(HEADERS_NODE) ? entityConfig.getConfig(HEADERS_NODE).root().keySet() : "empty");

            // 3. Basic merge: Entity config overrides default config
            Config baseCombinedConfig = entityConfig.withFallback(defaultConfig);

            // 4. Merge environment-specific configuration (all nodes, not just headers)
            Config finalConfig = mergeEnvConfig(baseCombinedConfig, envOverride);

            // 5. Assign to global config
            config = finalConfig.resolve();
            // ⭐ 修复 P2-26：root().unwrapped() 会展开全部 header 值（含 Authorization、
            //    Cookie、会话 token），原先直接以 INFO 打印即构成凭证泄露。改为输出脱敏结果。
            LOGGER.info("[4/4] Global config assignment completed, final headers: {}",
                    describeHeadersForLog(config));

            return config;

        } catch (Exception e) {
            LOGGER.error("Failed to load configuration for entity: {}", entityName, e);
            throw new IllegalArgumentException("Configuration load failed: " + e.getMessage(), e);
        }
    }

    /**
     * Load entity-specific configuration, trying both .conf and .properties formats
     * Configuration file naming: {entityName}.conf or {entityName}.properties
     *
     * @param entityName entity name
     * @return loaded configuration
     */
    private static Config loadEntityConfig(String entityName) {
        // Try each file extension: .conf first, then .properties
        for (String extension : CONFIG_FILE_EXTENSIONS) {
            String entityConfigName = entityName + extension;
            File entityConfigFile = new File(getConfigBasePath() + entityConfigName);
            LOGGER.info("Trying entity config file: {} (exists: {})",
                    entityConfigFile.getAbsolutePath(), entityConfigFile.exists());

            if (entityConfigFile.exists()) {
                LOGGER.info("Found entity config file: {}", entityConfigName);
                return loadConfigFile(entityConfigName, true);
            }
        }

        // If no entity config file found, return empty config
        LOGGER.warn("No entity config file found for entity: {}, trying extensions: {}",
                entityName, String.join(", ", CONFIG_FILE_EXTENSIONS));
        return ConfigFactory.empty();
    }

    // ===== Remaining methods remain unchanged (loadConfigFile, mergeEnvHeadersConfig, getConfig, getPayloadPath) =====
    private static Config loadConfigFile(String fileName, boolean throwIfNotFound) {
        String filePath = getConfigBasePath() + fileName;
        File configFile = new File(filePath);
        LOGGER.debug("Attempting to load config file: {}", filePath); // Debug level for file loading

        if (configFile.exists()) {
            LOGGER.info("Loading config from file path: {}", filePath);
            return ConfigFactory.parseFile(configFile);
        }

        URL classpathUrl = ConfigProvider.class.getClassLoader().getResource(configDir + "/" + fileName);
        if (classpathUrl != null) {
            LOGGER.info("Loading config from classpath: {}/{}", configDir, fileName);
            return ConfigFactory.load(configDir + "/" + fileName);
        }

        Config classpathRootConfig = ConfigFactory.load(fileName);
        if (!classpathRootConfig.isEmpty()) {
            LOGGER.info("Loading config from classpath root: {}", fileName);
            return classpathRootConfig;
        }

        if (throwIfNotFound) {
            String errorMsg = "Configuration file does not exist: " + fileName;
            LOGGER.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        LOGGER.warn("Config file not found, returning empty config: {}", fileName);
        return ConfigFactory.empty();
    }

    /**
     * Merge environment-specific configuration from config file
     * Environment configuration can override ANY configuration node, not just headers
     *
     * Uses Serenity's SystemEnvironmentVariables to read environment configuration
     *
     * Example configuration structure:
     * ```
     * http.connection.timeout="30000"
     * headers.Accept="application/json"
     *
     * dev {
     *     http.connection.timeout="5000"
     *     headers.Authorization="Bearer token"
     * }
     * ```
     *
     * When env="dev", both timeout and headers.Authorization will be overridden
     *
     * @param baseCombinedConfig base configuration (default + entity)
     * @return merged configuration with environment overrides
     */
    private static Config mergeEnvConfig(Config baseCombinedConfig) {
        return mergeEnvConfig(baseCombinedConfig, null);
    }

    /**
     * 合并环境专属配置。
     *
     * @param baseCombinedConfig 基础配置（default + entity）
     * @param envOverride        显式环境覆盖值；null/空时回退到 EnvironmentUtils 解析结果
     * @return 合并后的配置
     */
    private static Config mergeEnvConfig(Config baseCombinedConfig, String envOverride) {
        // Use EnvironmentUtils (replacement for deprecated SystemEnvironmentVariables) to get environment configuration
        // ⭐ 修复 P2-21：优先采用显式传入的 env，避免依赖 JVM 全局 System property
        String activeEnv = (envOverride != null && !envOverride.trim().isEmpty())
                ? envOverride.trim()
                : EnvironmentUtils.currentEnvironment().getProperty(Constants.ENV);
        LOGGER.info("Active environment: {}", activeEnv);

        // If no environment specified, return base config
        if (activeEnv == null || activeEnv.trim().isEmpty()) {
            LOGGER.info("No environment parameter specified, using basic merged config");
            return baseCombinedConfig;
        }
        activeEnv = activeEnv.trim();
        LOGGER.info("Currently active environment: {}", activeEnv);

        // If environment node does not exist in config, return base config
        if (!baseCombinedConfig.hasPath(activeEnv)) {
            LOGGER.warn("{} environment node does not exist in config, skipping environment merge", activeEnv);
            return baseCombinedConfig;
        }

        Config envConfig = baseCombinedConfig.getConfig(activeEnv);
        LOGGER.info("Found environment({}) configuration in config file", activeEnv);

        // Merge ALL configuration from environment node (not just headers)
        // Environment config has higher priority and will override base config
        Config finalConfig = envConfig.withFallback(baseCombinedConfig);

        // Log all overridden keys
        LOGGER.info("Environment({}) configuration keys: {}", activeEnv, envConfig.root().keySet());
        for (String key : envConfig.root().keySet()) {
            LOGGER.info("Environment({}) overrides configuration node: {}",
                    activeEnv, key);
        }

        // Remove the environment node itself (it's already merged)
        ConfigObject finalConfigObj = finalConfig.root().withoutKey(activeEnv);
        finalConfig = ConfigFactory.parseMap(finalConfigObj.unwrapped());

        LOGGER.info("Environment configuration merged successfully");
        return finalConfig;
    }

    /**
     * 以脱敏形式描述配置中的 headers 节点，仅供日志使用。
     * <p>
     * ⭐ 修复 P2-26：{@code root().unwrapped()} 会展开全部 header 值，直接打印会泄露
     * Authorization / Cookie / 会话 token，故统一经 {@link ApiLogSanitizer} 脱敏。
     */
    private static String describeHeadersForLog(Config cfg) {
        if (cfg == null || !cfg.hasPath(HEADERS_NODE)) {
            return "empty";
        }
        Map<String, Object> raw = new HashMap<>();
        cfg.getConfig(HEADERS_NODE).root().unwrapped()
                .forEach((k, v) -> raw.put(String.valueOf(k), v));
        return ApiLogSanitizer.toLogString(raw);
    }

    public static Config getConfig() {
        // ⭐ 修复 P2-22：原实现是无锁的 check-then-act（判空后写全局字段），与 synchronized 的
        //    config(Entity) 并发时会重复加载并互相覆盖，且非 volatile 读取可能拿到过期值。
        //    改为 volatile + 双重检查锁定：已初始化时完全无锁（热路径），仅在缺失时进入同步块。
        //    注：原实现在此处每次调用都打一条 debug 日志，本方法属热路径，故移除该日志。
        Config snapshot = config;
        if (snapshot == null || snapshot.isEmpty()) {
            synchronized (ConfigProvider.class) {
                snapshot = config;
                if (snapshot == null || snapshot.isEmpty()) {
                    LOGGER.warn("Global config not initialized, attempting to load from default config");
                    try {
                        snapshot = loadConfigFile(DEFAULT_CONFIG_FILE_NAME, false).resolve();
                        config = snapshot;
                    } catch (Exception e) {
                        LOGGER.error("Failed to load default config as fallback", e);
                        return ConfigFactory.empty();
                    }
                }
            }
        }
        return snapshot;
    }

    public static Config getConfig(String key) {
        Config globalConfig = getConfig();
        LOGGER.debug("Retrieving sub-config for key: {} (exists: {})", key, globalConfig.hasPath(key));
        return globalConfig.hasPath(key) ? globalConfig.getConfig(key) : ConfigFactory.empty();
    }

    public static String getPayloadPath(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            LOGGER.error("Payload file name is null or empty! Cannot retrieve file path.");
            return "";
        }
        String cleanFileName = fileName.trim();
        LOGGER.debug("Start resolving payload file path for: [{}]", cleanFileName);

        // 核心优化：使用 getCanonicalPath() 消除 .\ 冗余符，使用可配置的payload目录
        File baseDir = new File(getPayloadBasePath());
        String normalizedBasePath;
        try {
            normalizedBasePath = baseDir.getCanonicalPath(); // 规范路径（无 .\ / ..\ ）
        } catch (IOException e) {
            LOGGER.error("Failed to get canonical path for payload dir: [{}]", getPayloadBasePath(), e);
            normalizedBasePath = baseDir.getAbsolutePath(); // 兜底
        }

        // 拼接规范路径 - 使用配置的payload目录
        String localPayloadPath = normalizedBasePath + File.separator + cleanFileName;
        File localPayloadFile = new File(localPayloadPath);

        if (localPayloadFile.exists() && localPayloadFile.isFile()) {
            try {
                String canonicalPath = localPayloadFile.getCanonicalPath(); // 最终规范路径
                LOGGER.info("Payload file found in local path: [{}]", canonicalPath);
                return canonicalPath;
            } catch (IOException e) {
                LOGGER.error("Failed to get canonical path for: [{}]", localPayloadPath, e);
                return localPayloadPath; // 兜底
            }
        }
        LOGGER.debug("Payload file NOT found in local path: [{}]", localPayloadPath);

        // 类路径加载逻辑（不变，仅优化日志）
        String classpathResource = "payload/" + cleanFileName;
        URL classpathUrl = null;
        try {
            classpathUrl = ConfigProvider.class.getClassLoader().getResource(classpathResource);
            if (classpathUrl == null) {
                classpathUrl = ClassLoader.getSystemResource(classpathResource);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load payload file from classpath [{}] (classloader error)", classpathResource, e);
        }

        if (classpathUrl != null) {
            try {
                String decodedPath = URLDecoder.decode(classpathUrl.getPath(), StandardCharsets.UTF_8.name());
                String canonicalClasspath = new File(decodedPath).getCanonicalPath();
                LOGGER.info("Payload file found in classpath: [{}] (decoded from: {})", canonicalClasspath, classpathUrl.getPath());
                return canonicalClasspath;
            } catch (Exception e) {
                LOGGER.error("Failed to decode/normalize classpath URL [{}]", classpathUrl.getPath(), e);
                LOGGER.warn("Using raw classpath URL path (decoding failed): [{}]", classpathUrl.getPath());
                return classpathUrl.getPath();
            }
        }

        LOGGER.warn("Payload file [{}] NOT found! Checked paths: \n" +
                        "  - Local path: {}\n" +
                        "  - Classpath resource: {}",
                cleanFileName, localPayloadPath, classpathResource);
        return "";
    }
}