package com.hsbc.cmb.hk.dbb.automation.framework.web.route.persistence;

import com.google.gson.Gson;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Map;

/**
 * API 监控数据持久化仓库 — 使用独立 HikariCP 连接池写入数据库。
 *
 * <p><b>设计原则</b>：
 * <ul>
 *   <li>懒初始化：首次调用 {@link #save(ApiMonitoringRecord)} 时根据配置连接数据库</li>
 *   <li>静默降级：数据库连接失败或配置不正确时，仅打 WARN 日志，不抛异常</li>
 *   <li>独立连接池：不使用全局 {@code DatabaseUtil}，避免与框架其他 DB 功能冲突</li>
 *   <li>自动建表：首次连接成功后执行 DDL（表不存在则创建）</li>
 * </ul>
 *
 * <p><b>线程安全</b>：使用双重检查锁定 + volatile 保证初始化线程安全。
 */
public final class ApiMonitoringRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiMonitoringRepository.class);
    private static final Gson GSON = new Gson();

    /** 响应体最大存储长度（字符数），超过此值截断 */
    private static final int MAX_BODY_CHARS = 50000;

    private static volatile HikariDataSource dataSource;
    private static volatile boolean initialized = false;
    private static volatile boolean initFailed = false;

    /** 连接池最大连接数（默认 5，可通过配置覆盖） */
    private static int maxPoolSize = 5;

    private ApiMonitoringRepository() {}

    // ═══════════════════════════════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════════════════════════════

    /**
     * 初始化数据库连接（懒加载，仅在 {@code monitor.db.store.enabled=true} 时调用）。
     *
     * @param dbUrl      JDBC URL
     * @param dbUser     用户名
     * @param dbPassword 密码
     * @param dbType     数据库类型（MYSQL / POSTGRESQL）
     * @param poolMaxSize 连接池最大连接数
     */
    public static synchronized void init(String dbUrl, String dbUser, String dbPassword,
                                          String dbType, int poolMaxSize) {
        if (initialized) return;
        if (initFailed) return; // 已失败过，不再重试
        if (dbUrl == null || dbUrl.trim().isEmpty()) {
            LOGGER.warn("[ApiMonitoringRepository] DB URL is empty, cannot initialize. "
                    + "Set monitor.db.url in serenity.properties.");
            initFailed = true;
            return;
        }

        maxPoolSize = poolMaxSize;

        try {
            LOGGER.info("[ApiMonitoringRepository] Initializing DB connection: type={}, url={}, user={}",
                    dbType, maskUrl(dbUrl), dbUser);

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(dbUrl);
            config.setUsername(dbUser);
            config.setPassword(dbPassword);
            config.setDriverClassName(driverClass(dbType));
            config.setMaximumPoolSize(maxPoolSize);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(10000);
            config.setMaxLifetime(1800000);
            config.setPoolName("ApiMonitorPool");

            if ("MYSQL".equalsIgnoreCase(dbType)) {
                config.addDataSourceProperty("cachePrepStmts", "true");
                config.addDataSourceProperty("prepStmtCacheSize", "250");
                config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            }

            dataSource = new HikariDataSource(config);

            // 验证连接
            try (Connection conn = dataSource.getConnection()) {
                LOGGER.info("[ApiMonitoringRepository] DB connection validated successfully");
                // 自动建表
                ensureTableExists(conn, dbType);
            }

            initialized = true;
            LOGGER.info("[ApiMonitoringRepository] Initialized successfully, pool max size={}", maxPoolSize);

        } catch (Exception e) {
            LOGGER.warn("[ApiMonitoringRepository] Failed to initialize DB connection. "
                    + "API monitor data will NOT be persisted to database. "
                    + "Error: {}", e.getMessage());
            initFailed = true;
            closeDataSource();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 写入
    // ═══════════════════════════════════════════════════════════════

    /**
     * 保存一条 API 监控记录到数据库。
     * <p>如果未初始化或初始化失败，静默跳过。
     *
     * @param record 监控记录
     */
    public static void save(ApiMonitoringRecord record) {
        if (!initialized || dataSource == null) return;
        if (record == null) return;

        String sql = buildInsertSql();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, truncate(record.endpoint(), 500));
            stmt.setString(2, truncate(record.requestUrl(), 2000));
            stmt.setString(3, record.method());
            stmt.setInt(4, record.statusCode());
            stmt.setString(5, toJson(record.requestHeaders()));
            stmt.setString(6, toJson(record.responseHeaders()));
            stmt.setString(7, record.safeResponseBody(MAX_BODY_CHARS));
            stmt.setInt(8, record.bodyLength());
            stmt.setTimestamp(9, new Timestamp(record.capturedAt()));
            stmt.setString(10, truncate(record.testRunId(), 100));
            stmt.setBoolean(11, record.isOk());

            stmt.executeUpdate();
            LOGGER.debug("[ApiMonitoringRepository] Saved record: {} {} → {}",
                    record.method(), record.endpoint(), record.statusCode());

        } catch (Exception e) {
            LOGGER.warn("[ApiMonitoringRepository] Failed to save record [{} {} → {}]: {}",
                    record.method(), record.endpoint(), record.statusCode(), e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Internal
    // ═══════════════════════════════════════════════════════════════

    private static String buildInsertSql() {
        return "INSERT INTO route_monitor_record "
                + "(endpoint, request_url, method, status_code, req_headers, res_headers, "
                + "res_body, body_length, captured_at, test_run_id, assertion_ok) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    }

    /** 自动建表（表不存在则创建） */
    private static void ensureTableExists(Connection conn, String dbType) throws Exception {
        String ddl = buildDdl(dbType);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            LOGGER.info("[ApiMonitoringRepository] Table 'route_monitor_record' verified/created");
        }
    }

    private static String buildDdl(String dbType) {
        if ("POSTGRESQL".equalsIgnoreCase(dbType)) {
            return "CREATE TABLE IF NOT EXISTS route_monitor_record (\n"
                    + "    id           BIGSERIAL PRIMARY KEY,\n"
                    + "    endpoint     VARCHAR(500)  NOT NULL,\n"
                    + "    request_url  VARCHAR(2000),\n"
                    + "    method       VARCHAR(10)   NOT NULL,\n"
                    + "    status_code  INT           NOT NULL,\n"
                    + "    req_headers  TEXT,\n"
                    + "    res_headers  TEXT,\n"
                    + "    res_body     TEXT,\n"
                    + "    body_length  INT,\n"
                    + "    captured_at  TIMESTAMP(3)  NOT NULL,\n"
                    + "    test_run_id  VARCHAR(100),\n"
                    + "    assertion_ok BOOLEAN,\n"
                    + "    created_at   TIMESTAMP DEFAULT NOW()\n"
                    + ")";
        }
        // MySQL (default)
        return "CREATE TABLE IF NOT EXISTS route_monitor_record (\n"
                + "    id           BIGINT AUTO_INCREMENT PRIMARY KEY,\n"
                + "    endpoint     VARCHAR(500)  NOT NULL        COMMENT 'api() 配置的 urlPattern',\n"
                + "    request_url  VARCHAR(2000)                 COMMENT '实际请求完整 URL',\n"
                + "    method       VARCHAR(10)   NOT NULL        COMMENT 'HTTP 方法',\n"
                + "    status_code  INT           NOT NULL        COMMENT 'HTTP 状态码',\n"
                + "    req_headers  TEXT                          COMMENT '请求头 (JSON)',\n"
                + "    res_headers  TEXT                          COMMENT '响应头 (JSON)',\n"
                + "    res_body     MEDIUMTEXT                    COMMENT '响应体',\n"
                + "    body_length  INT                           COMMENT '响应体长度 (bytes)',\n"
                + "    captured_at  DATETIME(3)   NOT NULL        COMMENT '捕获时间戳',\n"
                + "    test_run_id  VARCHAR(100)                  COMMENT '测试运行 ID',\n"
                + "    assertion_ok BOOLEAN                       COMMENT '断言是否通过',\n"
                + "    created_at   DATETIME      DEFAULT NOW()   COMMENT '记录创建时间',\n"
                + "    INDEX idx_endpoint   (endpoint),\n"
                + "    INDEX idx_captured   (captured_at),\n"
                + "    INDEX idx_test_run   (test_run_id)\n"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    }

    private static String driverClass(String dbType) {
        if ("POSTGRESQL".equalsIgnoreCase(dbType)) return "org.postgresql.Driver";
        return "com.mysql.cj.jdbc.Driver"; // MYSQL
    }

    private static String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) return null;
        try {
            return GSON.toJson(map);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }

    private static String maskUrl(String url) {
        if (url == null) return null;
        return url.replaceAll("password=[^&;]*", "password=******");
    }

    private static void closeDataSource() {
        if (dataSource != null) {
            try {
                dataSource.close();
            } catch (Exception e) {
                LOGGER.debug("[ApiMonitoringRepository] Error closing datasource: {}", e.getMessage());
            }
            dataSource = null;
        }
    }

    /**
     * 查询 Repository 是否已成功初始化。
     *
     * @return true 如果数据库连接已建立且可用
     */
    public static boolean isInitialized() {
        return initialized && dataSource != null && !dataSource.isClosed();
    }

    /**
     * 关闭连接池（一般在 JVM shutdown hook 中调用）。
     */
    public static void shutdown() {
        LOGGER.info("[ApiMonitoringRepository] Shutting down...");
        initialized = false;
        closeDataSource();
    }

    /**
     * 重置状态（主要用于测试）。
     */
    static synchronized void reset() {
        shutdown();
        initialized = false;
        initFailed = false;
    }
}
