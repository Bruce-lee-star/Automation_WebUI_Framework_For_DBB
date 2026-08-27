package com.hsbc.cmb.hk.dbb.automation.framework.api.config;

import com.hsbc.cmb.hk.dbb.automation.framework.api.utility.Constants;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Framework Configuration Manager
 * Centralizes access to all framework configuration values
 * Provides a single point of access for all configuration parameters
 */
public class FrameworkConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(FrameworkConfig.class);
    private static Config config = ConfigProvider.getConfig();

    // ========================================
    // HTTP Configuration
    // ========================================

    /**
     * Get default connection timeout in milliseconds
     * @return connection timeout (default: 15000ms)
     */
    public static int getConnectionTimeout() {
        return config.hasPath("http.connection.timeout")
            ? config.getInt("http.connection.timeout")
            : 30000;
    }


    /**
     * Get default socket timeout in milliseconds
     * @return socket timeout (default: 15000ms)
     */
    public static int getSocketTimeoutDefault() {
        return config.hasPath("http.socket.timeout.fallback")
            ? config.getInt("http.socket.timeout.fallback")
            : 15000;
    }

    /**
     * Get socket timeout in milliseconds
     * @return socket timeout (default: 30000ms)
     */
    public static int getSocketTimeout() {
        // 优先读取 ConfigKeys.HTTP_SOCKET_TIMEOUT ("http.socket.timeout")，
        // 再回退到历史 key "http.socket.timeout.value"（二者读不到再回退 fallback 字段或默认值）
        if (config.hasPath("http.socket.timeout")) {
            return config.getInt("http.socket.timeout");
        }
        if (config.hasPath("http.socket.timeout.value")) {
            return config.getInt("http.socket.timeout.value");
        }
        if (config.hasPath("http.socket.timeout.fallback")) {
            return config.getInt("http.socket.timeout.fallback");
        }
        return 30000;
    }

    /**
     * Check if SSL validation should be relaxed.
     * 注意：默认改为 false —— 安全优先；如需宽松校验请在配置中显式开启 http.ssl.relax-validation=true。
     * @return true if SSL validation should be relaxed (default: false)
     */
    public static boolean isSslRelaxValidation() {
        return config.hasPath("http.ssl.relax-validation")
            && config.getBoolean("http.ssl.relax-validation");
    }

    // ========================================
    // File Encoding
    // ========================================

    /**
     * Get default file encoding
     * @return file encoding (default: UTF-8)
     */
    public static String getFileEncoding() {
        return config.hasPath("file.encoding.default")
            ? config.getString("file.encoding.default")
            : Constants.UTF_EIGHT;
    }

    /**
     * Get payload file encoding
     * @return payload encoding (default: UTF-8)
     */
    public static String getPayloadEncoding() {
        return config.hasPath("file.encoding.payload")
            ? config.getString("file.encoding.payload")
            : Constants.UTF_EIGHT;
    }

    // ========================================
    // JSON Configuration
    // ========================================

    /**
     * Check if JSON parser should fail on unknown properties
     * @return true if should fail (default: false)
     */
    public static boolean shouldFailOnUnknownProperties() {
        return config.hasPath("json.fail-on-unknown-properties")
            ? config.getBoolean("json.fail-on-unknown-properties")
            : false;
    }

    /**
     * Check if JSON parser should accept single value as array
     * @return true if should accept (default: true)
     */
    public static boolean acceptSingleValueAsArray() {
        return config.hasPath("json.accept-single-value-as-array")
            ? config.getBoolean("json.accept-single-value-as-array")
            : true;
    }

    /**
     * Check if JSON parser should ignore null for primitives
     * @return true if should ignore (default: true)
     */
    public static boolean ignoreNullForPrimitives() {
        return config.hasPath("json.ignore-null-for-primitives")
            ? config.getBoolean("json.ignore-null-for-primitives")
            : true;
    }

    // ========================================
    // Logging Configuration
    // ========================================

    /**
     * Get root logging level
     * @return logging level (default: INFO)
     */
    public static String getLogLevel() {
        return config.hasPath("logging.root.level")
            ? config.getString("logging.root.level")
            : "INFO";
    }

    /**
     * Get console logging pattern
     * @return console pattern (default: standard pattern)
     */
    public static String getConsolePattern() {
        return config.hasPath("logging.console.pattern")
            ? config.getString("logging.console.pattern")
            : "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n";
    }

    /**
     * Check if file logging is enabled
     * @return true if file logging enabled (default: false)
     */
    public static boolean isFileLoggingEnabled() {
        return config.hasPath("logging.file.enabled")
            ? config.getBoolean("logging.file.enabled")
            : false;
    }

    /**
     * Get file logging pattern
     * @return file pattern (default: standard pattern)
     */
    public static String getFilePattern() {
        return config.hasPath("logging.file.pattern")
            ? config.getString("logging.file.pattern")
            : "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n";
    }

    // ========================================
    // API Configuration
    // ========================================

    /**
     * Get default base URI for API requests
     * @return default base URI (default: http://localhost)
     */
    public static String getDefaultBaseUri() {
        // 兼容历史 key "api.base-uri.default" 与 ConfigProvider 标准键 "api.base.uri.default"
        if (config.hasPath("api.base.uri.default")) {
            return config.getString("api.base.uri.default");
        }
        if (config.hasPath("api.base-uri.default")) {
            return config.getString("api.base-uri.default");
        }
        return "http://localhost";
    }

    // ========================================
    // Test Configuration
    // ========================================

    /**
     * Get test retry count
     * @return retry count (default: 3)
     */
    public static int getRetryCount() {
        return config.hasPath("test.retry.count")
            ? config.getInt("test.retry.count")
            : 3;
    }

    /**
     * Get test retry delay in milliseconds
     * @return retry delay (default: 1000ms)
     */
    public static int getRetryDelay() {
        return config.hasPath("test.retry.delay")
            ? config.getInt("test.retry.delay")
            : 1000;
    }

    // ========================================
    // Error Messages
    // ========================================

    /**
     * Get error message by key
     * @param key message key (e.g., "error.endpoint.null")
     * @return error message or key if not found
     */
    public static String getErrorMessage(String key) {
        String messageKey = "messages." + key;
        return config.hasPath(messageKey)
            ? config.getString(messageKey)
            : key;
    }

    /**
     * Get error message for null endpoint (GET request)
     * @return error message
     */
    public static String getEndpointNullErrorMessage(String method) {
        return getErrorMessage("error.endpoint." + method.toLowerCase() + ".null");
    }

    // ========================================
    // Serenity Configuration
    // ========================================

    /**
     * Get Serenity screenshot strategy
     * @return screenshot strategy (default: FOR_FAILURES)
     */
    public static String getSerenityTakeScreenshots() {
        return config.hasPath("serenity.take.screenshots")
            ? config.getString("serenity.take.screenshots")
            : "FOR_FAILURES";
    }

    /**
     * Get Serenity output directory
     * @return output directory path
     */
    public static String getSerenityOutputDirectory() {
        return config.hasPath("serenity.output-directory")
            ? config.getString("serenity.output-directory")
            : "target/site/serenity";
    }

    /**
     * Get Serenity history folder
     * @return history folder path
     */
    public static String getSerenityHistoryFolder() {
        return config.hasPath("serenity.history.folder")
            ? config.getString("serenity.history.folder")
            : "target/site/serenity/history";
    }

    // ========================================
    // WebDriver Configuration
    // ========================================

    /**
     * Get WebDriver implicit wait timeout
     * @return implicit wait in milliseconds (default: 15000)
     */
    public static int getWebDriverImplicitWait() {
        return config.hasPath("webdriver.timeouts.implicitlywait")
            ? config.getInt("webdriver.timeouts.implicitlywait")
            : 15000;
    }

    /**
     * Get WebDriver wait for timeout
     * @return wait for timeout in milliseconds (default: 15000)
     */
    public static int getWebDriverWaitForTimeout() {
        return config.hasPath("webdriver.timeouts.wait.for.timeout")
            ? config.getInt("webdriver.timeouts.wait.for.timeout")
            : 15000;
    }

    // ========================================
    // API Request/Response Logging
    // ========================================

    /**
     * Check if API request/response logging is enabled
     * @return true if logging is enabled (default: true)
     */
    public static boolean isApiRequestResponseLogsEnabled() {
        return config.hasPath("api.request.response.logging.enabled")
            ? config.getBoolean("api.request.response.logging.enabled")
            : true;
    }

    // ========================================
    // Utility Methods
    // ========================================

    /**
     * Get the raw Config object for advanced usage
     * @return Config instance
     */
    public static Config getConfig() {
        return config;
    }

    /**
     * Check if a configuration path exists
     * @param path configuration path
     * @return true if path exists
     */
    public static boolean hasPath(String path) {
        return config.hasPath(path);
    }

    /**
     * Get configuration value as String
     * @param path configuration path
     * @return configuration value or null if not found
     */
    public static String getString(String path) {
        return config.hasPath(path) ? config.getString(path) : null;
    }

    /**
     * Get configuration value as Integer
     * @param path configuration path
     * @return configuration value or 0 if not found
     */
    public static int getInt(String path) {
        return config.hasPath(path) ? config.getInt(path) : 0;
    }

    /**
     * Get configuration value as Boolean
     * @param path configuration path
     * @return configuration value or false if not found
     */
    public static boolean getBoolean(String path) {
        return config.hasPath(path) && config.getBoolean(path);
    }
}
