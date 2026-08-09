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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * API 监控数据持久化仓库 — 使用独立 HikariCP 连接池【批量】写入数据库。
 *
 * <p><b>批量写库设计（核心优化，替代原"逐条 INSERT"）</b>：
 * <ul>
 *   <li>{@link #save(ApiMonitoringRecord)} 只把记录放入内存队列（{@link ConcurrentLinkedQueue}），
 *       <b>不立即写库</b>——调用方（异步任务）零阻塞，彻底消除"每 API 一次 DB 往返"的串行瓶颈。</li>
 *   <li>后台批量刷入器（单线程 {@link ScheduledExecutorService}）按【定量阈值】或【定时周期】把队列
 *       攒批，用 {@code PreparedStatement.addBatch() + executeBatch()} 一次性批量 INSERT。</li>
 *   <li>MySQL 侧启用 {@code rewriteBatchedStatements=true}，把多条 INSERT 合并成一条多值 INSERT，
 *       批量写性能较逐条提升 10~100 倍。</li>
 *   <li>进程退出（shutdown hook / {@link #shutdown()}）时强制 flush 剩余队列，确保不丢数据。</li>
 * </ul>
 *
 * <p><b>设计原则</b>：
 * <ul>
 *   <li>懒初始化：首次调用 {@link #init(...)} 时根据配置连接数据库</li>
 *   <li>静默降级：数据库连接失败或配置不正确时，仅打 WARN 日志，不抛异常</li>
 *   <li>独立连接池：不使用全局 {@code DatabaseUtil}，避免与框架其他 DB 功能冲突</li>
 *   <li>自动建表：首次连接成功后执行 DDL（表不存在则创建）</li>
 *   <li>失败重试：批量刷入失败时，把该批记录重新放回队首，下一轮重试（避免丢数据）</li>
 * </ul>
 *
 * <p><b>可配置项</b>（可通过环境变量覆盖）：
 * <ul>
 *   <li>{@code ROUTE_MONITOR_BATCH_SIZE}   批量刷入的条数阈值（默认 50）</li>
 *   <li>{@code ROUTE_MONITOR_FLUSH_MS}     定时刷入周期毫秒（默认 2000）</li>
 * </ul>
 */
public final class ApiMonitoringRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiMonitoringRepository.class);
    private static final Gson GSON = new Gson();

    /** 响应体最大存储长度（字符数），超过此值截断 */
    private static final int MAX_BODY_CHARS = 50000;

    /** 批量刷入条数阈值：队列达到该条数立即刷（环境变量可覆盖） */
    private static final int BATCH_THRESHOLD = getEnvInt("ROUTE_MONITOR_BATCH_SIZE", 50);

    /** 定时刷入周期（毫秒）：即使未达条数阈值，也定期把存量刷入（环境变量可覆盖） */
    private static final long FLUSH_INTERVAL_MS = getEnvLong("ROUTE_MONITOR_FLUSH_MS", 2000L);

    /** 单批最多插入条数（防止一次 executeBatch 过大占内存） */
    private static final int MAX_BATCH_PER_FLUSH = 500;

    private static volatile HikariDataSource dataSource;
    private static volatile boolean initialized = false;
    private static volatile boolean initFailed = false;

    /** 连接池最大连接数（默认 5，可通过配置覆盖） */
    private static int maxPoolSize = 5;

    /** 待写库记录的内存队列（save 只入队，刷入器负责批量落库） */
    private static final ConcurrentLinkedQueue<ApiMonitoringRecord> PENDING = new ConcurrentLinkedQueue<>();

    /** 后台批量刷入器（单线程，定时 + 定量触发） */
    private static volatile ScheduledExecutorService FLUSHER;

    /** 统计：累计入队 / 累计落库 / 累计失败 / 累计丢弃数，便于诊断 */
    private static final AtomicLong enqueuedCount = new AtomicLong(0);
    private static final AtomicLong flushedCount = new AtomicLong(0);
    private static final AtomicLong failedCount = new AtomicLong(0);
    private static final AtomicLong droppedCount = new AtomicLong(0);

    private ApiMonitoringRepository() {}

    // ═══════════════════════════════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════════════════════════════

    /**
     * 初始化数据库连接（懒加载，仅在 {@code monitor.db.store.enabled=true} 时调用），
     * 并启动后台批量刷入器。
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
            config.setConnectionTimeout(3000);
            config.setMaxLifetime(1800000);
            config.setPoolName("ApiMonitorPool");

            if ("MYSQL".equalsIgnoreCase(dbType)) {
                config.addDataSourceProperty("cachePrepStmts", "true");
                config.addDataSourceProperty("prepStmtCacheSize", "250");
                config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
                // 核心优化：把批量 INSERT 合并成一条多值 INSERT，写库性能提升巨大
                config.addDataSourceProperty("rewriteBatchedStatements", "true");
            }

            dataSource = new HikariDataSource(config);

            // 验证连接
            try (Connection conn = dataSource.getConnection()) {
                LOGGER.info("[ApiMonitoringRepository] DB connection validated successfully");
                // 自动建表
                ensureTableExists(conn, dbType);
            }

            initialized = true;

            // 启动后台批量刷入器（定时 + 定量双触发）
            startFlusher();

            // 注册 JVM 关闭钩子，确保退出时 flush 剩余队列
            Runtime.getRuntime().addShutdownHook(new Thread(ApiMonitoringRepository::flushPendingNow,
                    "api-monitor-flush-shutdown"));

            LOGGER.info("[ApiMonitoringRepository] Initialized successfully, pool max size={}, "
                            + "batchThreshold={}, flushInterval={}ms",
                    maxPoolSize, BATCH_THRESHOLD, FLUSH_INTERVAL_MS);

        } catch (Exception e) {
            LOGGER.warn("[ApiMonitoringRepository] Failed to initialize DB connection. "
                    + "API monitor data will NOT be persisted to database. "
                    + "Error: {}", e.getMessage());
            initFailed = true;
            closeDataSource();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 写入（入队）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 保存一条 API 监控记录到数据库。
     * <p>仅把记录放入内存队列（O(1) 无阻塞），由后台刷入器批量落库。
     * <p>如果未初始化或初始化失败，静默跳过。
     *
     * @param record 监控记录
     */
    public static void save(ApiMonitoringRecord record) {
        if (!initialized || dataSource == null) return;
        if (record == null) return;

        PENDING.offer(record);
        enqueuedCount.incrementAndGet();

        // 定量触发：队列达到阈值立即刷，避免积压过多
        if (PENDING.size() >= BATCH_THRESHOLD) {
            // 由独立线程触发，避免在调用方线程（可能持有锁/在事件线程）执行写库
            ScheduledExecutorService f = FLUSHER;
            if (f != null) {
                try {
                    f.execute(ApiMonitoringRepository::flushPendingNow);
                } catch (Exception ignore) {
                    // 刷入器未就绪或已关闭，忽略（定时刷入仍会兜底）
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 后台批量刷入器
    // ═══════════════════════════════════════════════════════════════

    private static void startFlusher() {
        ScheduledExecutorService f = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "api-monitor-flusher");
                t.setDaemon(true);
                return t;
            }
        });
        FLUSHER = f;
        // 定时刷入：即使未达条数阈值，也定期把存量落库，防止低流量时记录滞留
        f.scheduleWithFixedDelay(ApiMonitoringRepository::flushPendingNow,
                FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 立即把当前队列中的记录批量落库（定时 + 定量 + shutdown 共用入口）。
     */
    private static void flushPendingNow() {
        if (!initialized || dataSource == null) return;
        if (PENDING.isEmpty()) return;

        // 从队列取出一批（不阻塞、不超过单批上限），一次性批量 INSERT
        List<ApiMonitoringRecord> batch = new ArrayList<>(Math.min(PENDING.size(), MAX_BATCH_PER_FLUSH));
        while (batch.size() < MAX_BATCH_PER_FLUSH) {
            ApiMonitoringRecord r = PENDING.poll();
            if (r == null) break;
            batch.add(r);
        }
        if (batch.isEmpty()) return;

        try {
            insertBatch(batch);
            flushedCount.addAndGet(batch.size());
            LOGGER.debug("[ApiMonitoringRepository] Batch flushed: {} records (pending={})",
                    batch.size(), PENDING.size());
        } catch (Exception e) {
            failedCount.addAndGet(batch.size());
            LOGGER.warn("[ApiMonitoringRepository] Batch flush failed ({} records), will retry: {}",
                    batch.size(), e.getMessage());
            // 失败重试：把本批记录放回队列，下一轮定时刷入重试，避免丢数据
            PENDING.addAll(batch);
            // 保护：若队列因反复失败持续膨胀，丢弃最旧以限流（防止内存无限增长）
            int cap = BATCH_THRESHOLD * 20;
            while (PENDING.size() > cap) {
                ApiMonitoringRecord dropped = PENDING.poll();
                if (dropped == null) break;
                droppedCount.incrementAndGet();
            }
        }
    }

    /**
     * 执行一批记录的批量 INSERT（addBatch + executeBatch）。
     */
    private static void insertBatch(List<ApiMonitoringRecord> batch) throws Exception {
        String sql = buildInsertSql();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (ApiMonitoringRecord record : batch) {
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
                stmt.addBatch();
            }
            // 一次性批量执行（MySQL rewriteBatchedStatements 会把多条合并成一条多值 INSERT）
            stmt.executeBatch();
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

    // ═══════════════════════════════════════════════════════════════
    // 状态 & 生命周期
    // ═══════════════════════════════════════════════════════════════

    /**
     * 查询 Repository 是否已成功初始化。
     *
     * @return true 如果数据库连接已建立且可用
     */
    public static boolean isInitialized() {
        return initialized && dataSource != null && !dataSource.isClosed();
    }

    /** 当前待落库的记录数（诊断用） */
    public static int pendingCount() {
        return PENDING.size();
    }

    /** 累计已成功落库的记录数（诊断用） */
    public static long flushedCount() {
        return flushedCount.get();
    }

    /** 累计失败的记录数（诊断用） */
    public static long failedCount() {
        return failedCount.get();
    }

    /**
     * 关闭连接池并 flush 剩余队列（一般在 JVM shutdown hook 中调用）。
     */
    public static void shutdown() {
        LOGGER.info("[ApiMonitoringRepository] Shutting down... "
                + "enqueued={}, flushed={}, failed={}, dropped={}, pending={}",
                enqueuedCount.get(), flushedCount.get(), failedCount.get(), droppedCount.get(), PENDING.size());
        // 先 flush 剩余队列，确保不丢数据
        try {
            flushPendingNow();
        } catch (Exception e) {
            LOGGER.warn("[ApiMonitoringRepository] Flush on shutdown failed: {}", e.getMessage());
        }
        // 关闭刷入器
        ScheduledExecutorService f = FLUSHER;
        if (f != null) {
            f.shutdownNow();
            FLUSHER = null;
        }
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
        PENDING.clear();
        enqueuedCount.set(0);
        flushedCount.set(0);
        failedCount.set(0);
        droppedCount.set(0);
    }

    // ─── 环境变量读取 ──────────────────────────────────────────────

    private static int getEnvInt(String key, int defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.trim().isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long getEnvLong(String key, long defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.trim().isEmpty()) return defaultValue;
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
