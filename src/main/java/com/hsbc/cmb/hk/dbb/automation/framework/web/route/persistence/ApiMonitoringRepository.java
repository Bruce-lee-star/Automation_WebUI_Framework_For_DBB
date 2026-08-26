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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** 单批最大重试次数：超过则丢弃该记录并告警（审计 P0-3：防止无限重试/内存循环） */
    private static final int MAX_ATTEMPTS = 3;

    /**
     * ⭐ 专属刷库执行器（审计 P0-0）：DB 写库是阻塞型 IO，必须独立于通用异步池（AsyncPool.POOL）。
     * 若把 flush 塞进通用池，DB 慢/连接池满时会占满 2~6 个通用线程，拖垮路由回调、超时调度与事件记录。
     * 此处单线程串行刷库，天然串行化批量 INSERT，避免并发抢连接。
     */
    private static ScheduledExecutorService DB_FLUSH_EXECUTOR;

    /** 待写库记录的内存队列（save 只入队，刷入器负责批量落库） */
    private static final ConcurrentLinkedQueue<PendingItem> PENDING = new ConcurrentLinkedQueue<>();

    /**
     * ⭐ O(1) 队列计数（审计 P0-2）：{@code ConcurrentLinkedQueue.size()} 是 O(n) 全链表遍历，
     * 在高频 save() 路径上调用会造成可观 CPU 浪费。改用 AtomicInteger 维护精确计数。
     */
    private static final AtomicInteger pendingCount = new AtomicInteger(0);

    /** 统计：累计入队 / 累计落库 / 累计失败 / 累计丢弃数，便于诊断 */
    private static final AtomicLong enqueuedCount = new AtomicLong(0);
    private static final AtomicLong flushedCount = new AtomicLong(0);
    private static final AtomicLong failedCount = new AtomicLong(0);
    private static final AtomicLong droppedCount = new AtomicLong(0);

    /** 队列项包装：携带重试次数，避免失败重试时无限循环（审计 P0-3） */
    private static final class PendingItem {
        final ApiMonitoringRecord record;
        final int attempts;
        PendingItem(ApiMonitoringRecord record, int attempts) {
            this.record = record;
            this.attempts = attempts;
        }
    }

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

        PENDING.offer(new PendingItem(record, 0));
        enqueuedCount.incrementAndGet();
        int count = pendingCount.incrementAndGet();   // O(1) 计数（审计 P0-2）

        // 定量触发：队列达到阈值立即刷，避免积压过多。
        // 提交到专属 DB 刷库执行器（审计 P0-0），不在调用方线程做 DB IO，
        // 也不占用通用异步池线程。
        if (count >= BATCH_THRESHOLD && DB_FLUSH_EXECUTOR != null
                && !DB_FLUSH_EXECUTOR.isShutdown()) {
            try {
                DB_FLUSH_EXECUTOR.submit(ApiMonitoringRepository::flushPendingNow);
            } catch (Exception ignore) {
                // 执行器已关闭，定时刷入仍会兜底
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 后台批量刷入器
    // ═══════════════════════════════════════════════════════════════

    private static void startFlusher() {
        // ⭐ 专属单线程刷库执行器（审计 P0-0）：DB IO 与路由通用异步池彻底隔离，
        // 防止 DB 慢/连接池满时占满通用池线程，拖垮路由回调与超时调度。
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "api-monitor-flusher");
            t.setDaemon(true);
            return t;
        };
        DB_FLUSH_EXECUTOR = Executors.newSingleThreadScheduledExecutor(tf);
        DB_FLUSH_EXECUTOR.scheduleWithFixedDelay(
                ApiMonitoringRepository::flushPendingNow,
                FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 立即把当前队列中的记录批量落库（定时 + 定量 + shutdown 共用入口）。
     */
    private static void flushPendingNow() {
        if (!initialized || dataSource == null) return;
        if (PENDING.isEmpty()) return;

        // 从队列取出一批（不阻塞、不超过单批上限），一次性批量 INSERT
        List<PendingItem> batch = new ArrayList<>(Math.min(pendingCount.get(), MAX_BATCH_PER_FLUSH));
        int taken = 0;
        while (taken < MAX_BATCH_PER_FLUSH) {
            PendingItem item = PENDING.poll();
            if (item == null) break;
            batch.add(item);
            taken++;
        }
        if (batch.isEmpty()) return;
        pendingCount.addAndGet(-taken);   // 取出即出队，O(1) 减计数（审计 P0-2）

        try {
            insertBatch(batch);
            flushedCount.addAndGet(batch.size());
            LOGGER.debug("[ApiMonitoringRepository] Batch flushed: {} records (pending={})",
                    batch.size(), pendingCount.get());
        } catch (Exception e) {
            failedCount.addAndGet(batch.size());
            LOGGER.warn("[ApiMonitoringRepository] Batch flush failed ({} records), will retry: {}",
                    batch.size(), e.getMessage());
            // 失败重试：递增 attempts，超过上限则丢弃并告警（审计 P0-3：防无限循环/重复落库）
            int requeued = 0;
            for (PendingItem item : batch) {
                int nextAttempts = item.attempts + 1;
                if (nextAttempts > MAX_ATTEMPTS) {
                    droppedCount.incrementAndGet();
                    LOGGER.error("[ApiMonitoringRepository] Dropped after {} failed attempts (endpoint={}, url={}): {}",
                            MAX_ATTEMPTS, item.record.endpoint(), item.record.requestUrl(), e.getMessage());
                } else {
                    PENDING.offer(item.record != null ? new PendingItem(item.record, nextAttempts) : item);
                    requeued++;
                }
            }
            pendingCount.addAndGet(requeued);   // 重新入队，计数同步回加
            // 保护：若队列因反复失败持续膨胀，丢弃最旧以限流（防止内存无限增长）
            int cap = BATCH_THRESHOLD * 20;
            while (pendingCount.get() > cap) {
                PendingItem dropped = PENDING.poll();
                if (dropped == null) break;
                pendingCount.decrementAndGet();
                droppedCount.incrementAndGet();
            }
        }
    }

    /**
     * 执行一批记录的批量 INSERT（addBatch + executeBatch）。
     * <p>⭐ 事务包裹（审计 P0-3）：单批原子提交，失败整体回滚后再重试，
     * 避免部分成功导致重复记录。
     */
    private static void insertBatch(List<PendingItem> batch) throws Exception {
        String sql = buildInsertSql();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);   // 事务包裹，单批原子性
            try {
                for (PendingItem item : batch) {
                    ApiMonitoringRecord record = item.record;
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
                conn.commit();   // 整体成功才提交
            } catch (Exception ex) {
                try { conn.rollback(); } catch (Exception rb) { /* ignore */ }
                throw ex;
            } finally {
                try { conn.setAutoCommit(true); } catch (Exception ignore) { /* ignore */ }
            }
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

    /** 当前待落库的记录数（诊断用，O(1)） */
    public static int pendingCount() {
        return pendingCount.get();
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
                enqueuedCount.get(), flushedCount.get(), failedCount.get(), droppedCount.get(), pendingCount.get());
        // 1) 先停止定时刷入，避免 shutdown 期间又触发 flush
        if (DB_FLUSH_EXECUTOR != null && !DB_FLUSH_EXECUTOR.isShutdown()) {
            DB_FLUSH_EXECUTOR.shutdown();
            try {
                // 等待进行中的 flush 完成，最多 10s
                DB_FLUSH_EXECUTOR.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                DB_FLUSH_EXECUTOR.shutdownNow();
            }
        }
        // 2) 同步 flush 剩余队列，确保不丢数据（不依赖异步执行器）
        try {
            flushPendingNow();
        } catch (Exception e) {
            LOGGER.warn("[ApiMonitoringRepository] Flush on shutdown failed: {}", e.getMessage());
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
        pendingCount.set(0);
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
