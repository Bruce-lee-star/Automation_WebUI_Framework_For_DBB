package com.hsbc.cmb.hk.dbb.automation.framework.api.config;

import com.hsbc.cmb.hk.dbb.automation.framework.api.utility.Constants;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigKey;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * API/HTTP 侧配置管理（WEB-P1-7 收敛后更名：原名 {@code FrameworkConfig} 与
 * {@code com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig}（现 {@code WebFrameworkConfig}）同名冲突，
 * 现统一为 {@code ApiFrameworkConfig}；Web 侧对应为 {@code WebFrameworkConfig}）。
 *
 * <p>本类专管 <b>API/HTTP</b> 侧配置（连接超时、socket 超时、SSL 校验、payload 路径等），
 * 服务 {@code framework.api.*}。所有配置项均以共享 {@link ConfigKey} 三元组
 * （key / 默认 / 描述）声明，确保与 Web 侧口径一致、可被统一审计。
 *
 * <p>解析仍走 Typesafe Config（{@link #config()}），仅当键缺失时回退 {@link ConfigKey#defaultValue()}，
 * 行为与旧实现逐字等价；词面 key 经 {@link ConfigKey} 集中登记，避免散落字符串。
 *
 * <p>⚠️ api 与 web 是两个独立模块边界，二者刻意不合并（合并会迫使一方依赖另一方）。
 * 若在同一类中同时用到两侧配置，请使用全限定名或 static import 别名以规避同名冲突。
 */
public class ApiFrameworkConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiFrameworkConfig.class);

    // ========================================
    // 共享 ConfigKey 元数据（key / 默认 / 描述 三元组）
    // ========================================

    /** HTTP 连接超时（毫秒）。 */
    public static final ConfigKey HTTP_CONNECTION_TIMEOUT =
            new ConfigKey("http.connection.timeout", "30000", "HTTP 连接超时（毫秒）");

    /** HTTP socket 超时回退值（毫秒）。 */
    public static final ConfigKey HTTP_SOCKET_TIMEOUT_FALLBACK =
            new ConfigKey("http.socket.timeout.fallback", "15000", "HTTP socket 超时回退值（毫秒）");

    /** HTTP socket 超时（毫秒），主键（兼容历史键 http.socket.timeout.value / .fallback）。 */
    public static final ConfigKey HTTP_SOCKET_TIMEOUT =
            new ConfigKey("http.socket.timeout", "30000", "HTTP socket 超时（毫秒）");

    /** 是否放宽 SSL 校验（默认 false，安全优先）。 */
    public static final ConfigKey HTTP_SSL_RELAX_VALIDATION =
            new ConfigKey("http.ssl.relax-validation", "false", "是否放宽 SSL 校验（默认 false，安全优先）");

    /** 默认文件编码。 */
    public static final ConfigKey FILE_ENCODING_DEFAULT =
            new ConfigKey("file.encoding.default", Constants.UTF_EIGHT, "默认文件编码");

    /** payload 文件编码。 */
    public static final ConfigKey FILE_ENCODING_PAYLOAD =
            new ConfigKey("file.encoding.payload", Constants.UTF_EIGHT, "payload 文件编码");

    /** JSON 解析遇未知属性是否失败。 */
    public static final ConfigKey JSON_FAIL_ON_UNKNOWN_PROPERTIES =
            new ConfigKey("json.fail-on-unknown-properties", "false", "JSON 解析遇未知属性是否失败");

    /** JSON 单值是否接受为数组。 */
    public static final ConfigKey JSON_ACCEPT_SINGLE_VALUE_AS_ARRAY =
            new ConfigKey("json.accept-single-value-as-array", "true", "JSON 单值是否接受为数组");

    /** JSON 原始类型是否忽略 null。 */
    public static final ConfigKey JSON_IGNORE_NULL_FOR_PRIMITIVES =
            new ConfigKey("json.ignore-null-for-primitives", "true", "JSON 原始类型是否忽略 null");

    /** 根日志级别。 */
    public static final ConfigKey LOGGING_ROOT_LEVEL =
            new ConfigKey("logging.root.level", "INFO", "根日志级别");

    /** 控制台日志格式。 */
    public static final ConfigKey LOGGING_CONSOLE_PATTERN =
            new ConfigKey("logging.console.pattern",
                    "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n", "控制台日志格式");

    /** 是否启用文件日志。 */
    public static final ConfigKey LOGGING_FILE_ENABLED =
            new ConfigKey("logging.file.enabled", "false", "是否启用文件日志");

    /** 文件日志格式。 */
    public static final ConfigKey LOGGING_FILE_PATTERN =
            new ConfigKey("logging.file.pattern",
                    "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n", "文件日志格式");

    /** API 默认 base URI（主键，兼容历史键 api.base-uri.default）。 */
    public static final ConfigKey API_BASE_URI_DEFAULT =
            new ConfigKey("api.base.uri.default", "http://localhost", "API 默认 base URI");

    /** 测试重试次数。 */
    public static final ConfigKey TEST_RETRY_COUNT =
            new ConfigKey("test.retry.count", "3", "测试重试次数");

    /** 测试重试延迟（毫秒）。 */
    public static final ConfigKey TEST_RETRY_DELAY =
            new ConfigKey("test.retry.delay", "1000", "测试重试延迟（毫秒）");

    /** Serenity 截图策略（Selenium 遗留，已废弃）。 */
    @Deprecated
    public static final ConfigKey SERENITY_TAKE_SCREENSHOTS =
            new ConfigKey("serenity.take.screenshots", "FOR_FAILURES", "Serenity 截图策略（已废弃）");

    /** Serenity 输出目录。 */
    public static final ConfigKey SERENITY_OUTPUT_DIRECTORY =
            new ConfigKey("serenity.output-directory", "target/site/serenity", "Serenity 输出目录");

    /** Serenity 历史目录。 */
    public static final ConfigKey SERENITY_HISTORY_FOLDER =
            new ConfigKey("serenity.history.folder", "target/site/serenity/history", "Serenity 历史目录");

    /** WebDriver 隐式等待（Selenium 遗留，已废弃）。 */
    @Deprecated
    public static final ConfigKey WEBDRIVER_IMPLICIT_WAIT =
            new ConfigKey("webdriver.timeouts.implicitlywait", "15000", "WebDriver 隐式等待（已废弃）");

    /** WebDriver 等待超时（Selenium 遗留，已废弃）。 */
    @Deprecated
    public static final ConfigKey WEBDRIVER_WAIT_FOR_TIMEOUT =
            new ConfigKey("webdriver.timeouts.wait.for.timeout", "15000", "WebDriver 等待超时（已废弃）");

    /** API 请求/响应日志是否启用。 */
    public static final ConfigKey API_REQUEST_RESPONSE_LOGS_ENABLED =
            new ConfigKey("api.request.response.logging.enabled", "true", "API 请求/响应日志是否启用");

    /**
     *  修复 A3：原实现在【类加载时】把配置缓存成静态快照
     * （{@code private static Config config = ConfigProvider.getConfig();}）。
     * 一旦 ConfigProvider 在类初始化之后为不同 entity / 测试阶段返回新的 Config 实例，
     * 本类会一直读取那份陈旧快照，导致多实体配置被静默忽略。
     * <p>ConfigProvider 内部自身已有缓存（仅当 config 为 null/empty 时才重载），
     * 因此改为实时读取不会引入额外 IO 开销，只多一次引用读取。
     */
    private static Config config() {
        return ConfigProvider.getConfig();
    }

    // ========================================
    // HTTP Configuration
    // ========================================

    /**
     * 获取默认连接超时（毫秒）。
     * @return 连接超时（默认：30000ms）
     */
    public static int getConnectionTimeout() {
        return config().hasPath(HTTP_CONNECTION_TIMEOUT.key())
            ? config().getInt(HTTP_CONNECTION_TIMEOUT.key())
            : Integer.parseInt(HTTP_CONNECTION_TIMEOUT.defaultValue());
    }

    /**
     * 获取默认 socket 超时（毫秒）。
     * @return socket 超时（默认：15000ms）
     */
    public static int getSocketTimeoutDefault() {
        return config().hasPath(HTTP_SOCKET_TIMEOUT_FALLBACK.key())
            ? config().getInt(HTTP_SOCKET_TIMEOUT_FALLBACK.key())
            : Integer.parseInt(HTTP_SOCKET_TIMEOUT_FALLBACK.defaultValue());
    }

    /**
     * 获取 socket 超时（毫秒）。
     * <p>优先读取 {@link ConfigKey} {@code http.socket.timeout}，
     * 再回退到历史键 {@code http.socket.timeout.value} / {@code http.socket.timeout.fallback}，
     * 三者皆无则回退默认值。
     * @return socket 超时（默认：30000ms）
     */
    public static int getSocketTimeout() {
        if (config().hasPath(HTTP_SOCKET_TIMEOUT.key())) {
            return config().getInt(HTTP_SOCKET_TIMEOUT.key());
        }
        if (config().hasPath("http.socket.timeout.value")) {
            return config().getInt("http.socket.timeout.value");
        }
        if (config().hasPath(HTTP_SOCKET_TIMEOUT_FALLBACK.key())) {
            return config().getInt(HTTP_SOCKET_TIMEOUT_FALLBACK.key());
        }
        return Integer.parseInt(HTTP_SOCKET_TIMEOUT.defaultValue());
    }

    /**
     * 是否放宽 SSL 校验。
     * <p>注意：默认 false —— 安全优先；如需宽松校验请在配置中显式开启
     * {@code http.ssl.relax-validation=true}。键缺失时按 false 处理。
     * @return true 表示放宽校验（默认 false）
     */
    public static boolean isSslRelaxValidation() {
        return config().hasPath(HTTP_SSL_RELAX_VALIDATION.key())
            && config().getBoolean(HTTP_SSL_RELAX_VALIDATION.key());
    }

    // ========================================
    // File Encoding
    // ========================================

    /**
     * 获取默认文件编码。
     * @return 文件编码（默认：UTF-8）
     */
    public static String getFileEncoding() {
        return config().hasPath(FILE_ENCODING_DEFAULT.key())
            ? config().getString(FILE_ENCODING_DEFAULT.key())
            : FILE_ENCODING_DEFAULT.defaultValue();
    }

    /**
     * 获取 payload 文件编码。
     * @return payload 编码（默认：UTF-8）
     */
    public static String getPayloadEncoding() {
        return config().hasPath(FILE_ENCODING_PAYLOAD.key())
            ? config().getString(FILE_ENCODING_PAYLOAD.key())
            : FILE_ENCODING_PAYLOAD.defaultValue();
    }

    // ========================================
    // JSON Configuration
    // ========================================

    /**
     * JSON 解析遇未知属性是否失败。
     * @return true 表示失败（默认 false）
     */
    public static boolean shouldFailOnUnknownProperties() {
        return config().hasPath(JSON_FAIL_ON_UNKNOWN_PROPERTIES.key())
            ? config().getBoolean(JSON_FAIL_ON_UNKNOWN_PROPERTIES.key())
            : Boolean.parseBoolean(JSON_FAIL_ON_UNKNOWN_PROPERTIES.defaultValue());
    }

    /**
     * JSON 单值是否接受为数组。
     * @return true 表示接受（默认 true）
     */
    public static boolean acceptSingleValueAsArray() {
        return config().hasPath(JSON_ACCEPT_SINGLE_VALUE_AS_ARRAY.key())
            ? config().getBoolean(JSON_ACCEPT_SINGLE_VALUE_AS_ARRAY.key())
            : Boolean.parseBoolean(JSON_ACCEPT_SINGLE_VALUE_AS_ARRAY.defaultValue());
    }

    /**
     * JSON 原始类型是否忽略 null。
     * @return true 表示忽略（默认 true）
     */
    public static boolean ignoreNullForPrimitives() {
        return config().hasPath(JSON_IGNORE_NULL_FOR_PRIMITIVES.key())
            ? config().getBoolean(JSON_IGNORE_NULL_FOR_PRIMITIVES.key())
            : Boolean.parseBoolean(JSON_IGNORE_NULL_FOR_PRIMITIVES.defaultValue());
    }

    // ========================================
    // Logging Configuration
    // ========================================

    /**
     * 获取根日志级别。
     * @return 日志级别（默认：INFO）
     */
    public static String getLogLevel() {
        return config().hasPath(LOGGING_ROOT_LEVEL.key())
            ? config().getString(LOGGING_ROOT_LEVEL.key())
            : LOGGING_ROOT_LEVEL.defaultValue();
    }

    /**
     * 获取控制台日志格式。
     * @return 控制台格式（默认标准格式）
     */
    public static String getConsolePattern() {
        return config().hasPath(LOGGING_CONSOLE_PATTERN.key())
            ? config().getString(LOGGING_CONSOLE_PATTERN.key())
            : LOGGING_CONSOLE_PATTERN.defaultValue();
    }

    /**
     * 是否启用文件日志。
     * @return true 表示启用（默认 false）
     */
    public static boolean isFileLoggingEnabled() {
        return config().hasPath(LOGGING_FILE_ENABLED.key())
            && config().getBoolean(LOGGING_FILE_ENABLED.key());
    }

    /**
     * 获取文件日志格式。
     * @return 文件格式（默认标准格式）
     */
    public static String getFilePattern() {
        return config().hasPath(LOGGING_FILE_PATTERN.key())
            ? config().getString(LOGGING_FILE_PATTERN.key())
            : LOGGING_FILE_PATTERN.defaultValue();
    }

    // ========================================
    // API Configuration
    // ========================================

    /**
     * 获取默认 base URI。
     * <p>兼容历史键 {@code api.base-uri.default} 与标准键 {@code api.base.uri.default}。
     * @return 默认 base URI（默认：http://localhost）
     */
    public static String getDefaultBaseUri() {
        if (config().hasPath(API_BASE_URI_DEFAULT.key())) {
            return config().getString(API_BASE_URI_DEFAULT.key());
        }
        if (config().hasPath("api.base-uri.default")) {
            return config().getString("api.base-uri.default");
        }
        return API_BASE_URI_DEFAULT.defaultValue();
    }

    // ========================================
    // Test Configuration
    // ========================================

    /**
     * 获取测试重试次数。
     * @return 重试次数（默认：3）
     */
    public static int getRetryCount() {
        return config().hasPath(TEST_RETRY_COUNT.key())
            ? config().getInt(TEST_RETRY_COUNT.key())
            : Integer.parseInt(TEST_RETRY_COUNT.defaultValue());
    }

    /**
     * 获取测试重试延迟（毫秒）。
     * @return 重试延迟（默认：1000ms）
     */
    public static int getRetryDelay() {
        return config().hasPath(TEST_RETRY_DELAY.key())
            ? config().getInt(TEST_RETRY_DELAY.key())
            : Integer.parseInt(TEST_RETRY_DELAY.defaultValue());
    }

    // ========================================
    // Error Messages
    // ========================================

    /**
     * 按 key 获取错误消息。
     * @param key 消息 key（如 "error.endpoint.null"）
     * @return 错误消息，未找到则返回 key 本身
     */
    public static String getErrorMessage(String key) {
        String messageKey = "messages." + key;
        return config().hasPath(messageKey)
            ? config().getString(messageKey)
            : key;
    }

    /**
     * 获取某 HTTP 方法下 null endpoint 的错误消息。
     * @param method HTTP 方法名
     * @return 错误消息
     */
    public static String getEndpointNullErrorMessage(String method) {
        return getErrorMessage("error.endpoint." + method.toLowerCase() + ".null");
    }

    // ========================================
    // Serenity Configuration
    // ========================================

    /**
     * 获取 Serenity 截图策略。
     * @return 截图策略（默认：FOR_FAILURES）
     * @deprecated Selenium 遗留配置项，全项目（含 src/test）无调用点。
     *             保留仅为兼容潜在外部引用，新代码请勿使用，后续大版本可安全移除。
     */
    @Deprecated
    public static String getSerenityTakeScreenshots() {
        return config().hasPath(SERENITY_TAKE_SCREENSHOTS.key())
            ? config().getString(SERENITY_TAKE_SCREENSHOTS.key())
            : SERENITY_TAKE_SCREENSHOTS.defaultValue();
    }

    /**
     * 获取 Serenity 输出目录。
     * @return 输出目录路径
     */
    public static String getSerenityOutputDirectory() {
        return config().hasPath(SERENITY_OUTPUT_DIRECTORY.key())
            ? config().getString(SERENITY_OUTPUT_DIRECTORY.key())
            : SERENITY_OUTPUT_DIRECTORY.defaultValue();
    }

    /**
     * 获取 Serenity 历史目录。
     * @return 历史目录路径
     */
    public static String getSerenityHistoryFolder() {
        return config().hasPath(SERENITY_HISTORY_FOLDER.key())
            ? config().getString(SERENITY_HISTORY_FOLDER.key())
            : SERENITY_HISTORY_FOLDER.defaultValue();
    }

    // ========================================
    // WebDriver Configuration
    // ========================================

    /**
     * 获取 WebDriver 隐式等待超时。
     * @return 隐式等待（默认：15000）
     * @deprecated Selenium 遗留配置项，全项目（含 src/test）无调用点。
     *             保留仅为兼容潜在外部引用，新代码请勿使用，后续大版本可安全移除。
     */
    @Deprecated
    public static int getWebDriverImplicitWait() {
        return config().hasPath(WEBDRIVER_IMPLICIT_WAIT.key())
            ? config().getInt(WEBDRIVER_IMPLICIT_WAIT.key())
            : Integer.parseInt(WEBDRIVER_IMPLICIT_WAIT.defaultValue());
    }

    /**
     * 获取 WebDriver wait-for 超时。
     * @return 等待超时（默认：15000）
     * @deprecated Selenium 遗留配置项，全项目（含 src/test）无调用点。
     *             保留仅为兼容潜在外部引用，新代码请勿使用，后续大版本可安全移除。
     */
    @Deprecated
    public static int getWebDriverWaitForTimeout() {
        return config().hasPath(WEBDRIVER_WAIT_FOR_TIMEOUT.key())
            ? config().getInt(WEBDRIVER_WAIT_FOR_TIMEOUT.key())
            : Integer.parseInt(WEBDRIVER_WAIT_FOR_TIMEOUT.defaultValue());
    }

    // ========================================
    // API Request/Response Logging
    // ========================================

    /**
     * API 请求/响应日志是否启用。
     * @return true 表示启用（默认 true）
     */
    public static boolean isApiRequestResponseLogsEnabled() {
        return config().hasPath(API_REQUEST_RESPONSE_LOGS_ENABLED.key())
            && config().getBoolean(API_REQUEST_RESPONSE_LOGS_ENABLED.key());
    }

    // ========================================
    // Utility Methods（任意 key 透传，无 ConfigKey 绑定）
    // ========================================

    /**
     * 获取原始 Config 对象供高级使用。
     * @return Config 实例
     */
    public static Config getConfig() {
        return config();
    }

    /**
     * 判断某配置路径是否存在。
     * @param path 配置路径
     * @return true 表示存在
     */
    public static boolean hasPath(String path) {
        return config().hasPath(path);
    }

    /**
     * 按路径获取字符串值。
     * @param path 配置路径
     * @return 配置值，未找到返回 null
     */
    public static String getString(String path) {
        return config().hasPath(path) ? config().getString(path) : null;
    }

    /**
     * 按路径获取整数值。
     * @param path 配置路径
     * @return 配置值，未找到返回 0
     */
    public static int getInt(String path) {
        return config().hasPath(path) ? config().getInt(path) : 0;
    }

    /**
     * 按路径获取布尔值。
     * @param path 配置路径
     * @return 配置值，未找到返回 false
     */
    public static boolean getBoolean(String path) {
        return config().hasPath(path) && config().getBoolean(path);
    }
}
