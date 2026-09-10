package com.hsbc.cmb.hk.dbb.automation.framework.route.persistence;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T0-4 补全收尾：Monitor DB 持久化端到端验证（确认非死代码）。
 *
 * <p>用 H2 内存库驱动真实 JDBC 写入链路 {@code init → save → flush → 落库}，
 * 证明 {@link ApiMonitoringRepository} 的批量写路径真实可用，而非永不生效代码。
 * 白盒测试（同包）以调用包级私有 {@link #reset()} 做用例间静态状态隔离，并直接调用包级私有
 * {@link #resolveDialect(String, String)} / {@link #dialectToMigrationFolder(String)} 验证方言路由。
 *
 * <p>ROUTE-P1-N2（含 Oracle/SQLServer 扩展）：DDL 已移出 Java，改由 Flyway 管理；本测试断言
 * {@code flyway_schema_history} 存在（证明 schema 由迁移创建）、旧库（已有表、无 flyway 历史）
 * 经 {@code baselineOnMigrate} 自动基线零丢数据，以及方言→Flyway 目录的映射覆盖 5 种库。
 */
public class ApiMonitoringRepositoryE2ETest {

    private String dbUrl;

    @Before
    public void setUp() {
        // 每个用例独立内存库，避免串扰
        dbUrl = "jdbc:h2:mem:routemonitor_e2e_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1";
        ApiMonitoringRepository.reset();
    }

    @After
    public void tearDown() {
        ApiMonitoringRepository.reset();
    }

    @Test
    public void shouldPersistMonitorRecordsEndToEnd() throws Exception {
        ApiMonitoringRepository.init(dbUrl, "sa", "", "H2", 2);
        assertTrue("Repository 应成功初始化（连接 H2 验证通过）", ApiMonitoringRepository.isInitialized());

        int n = 5;
        for (int i = 0; i < n; i++) {
            ApiMonitoringRecord rec = ApiMonitoringRecord.builder()
                    .endpoint("/api/users")
                    .requestUrl("https://api.example.com/api/users?id=" + i)
                    .method("GET")
                    .statusCode(200)
                    .requestHeaders(sampleHeaders())
                    .responseHeaders(sampleHeaders())
                    .responseBody("{\"id\":" + i + "}")
                    .capturedAt(System.currentTimeMillis())
                    .testRunId("run-e2e")
                    .build();
            ApiMonitoringRepository.save(rec);
        }

        // shutdown() 同步 flush 剩余队列（不依赖异步执行器），确保真实落库
        ApiMonitoringRepository.shutdown();

        // 1) 诊断计数证明批量 INSERT 成功执行（非死代码）
        assertEquals("应有 " + n + " 条记录成功落库", n, ApiMonitoringRepository.flushedCount());
        assertEquals(0, ApiMonitoringRepository.failedCount());

        // 2) 直接查 H2 验证行真实落库
        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM route_monitor_record")) {
            assertTrue("应能查到落库记录", rs.next());
            assertEquals(n, rs.getInt(1));
        }
    }

    @Test
    public void shouldCreateFlywayHistoryTableOnInit() throws Exception {
        ApiMonitoringRepository.init(dbUrl, "sa", "", "H2", 2);
        assertTrue("Repository 应成功初始化", ApiMonitoringRepository.isInitialized());

        // ROUTE-P1-N2：DDL 已移出 Java，改由 Flyway 管理 → flyway_schema_history 应被创建并记录 V1
        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '1'")) {
            assertTrue("flyway_schema_history 应存在且含 V1 迁移记录", rs.next());
            assertEquals(1, rs.getInt(1));
        }
        ApiMonitoringRepository.shutdown();
    }

    @Test
    public void shouldBaselineExistingPreFlywayTableWithoutDataLoss() throws Exception {
        // 模拟旧版代码已建表（无 flyway_schema_history），验证旧库可迁移、零丢数据
        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE route_monitor_record ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, endpoint VARCHAR(500) NOT NULL, "
                    + "method VARCHAR(10) NOT NULL, status_code INT NOT NULL, "
                    + "captured_at TIMESTAMP NOT NULL)");
            stmt.execute("INSERT INTO route_monitor_record (endpoint, method, status_code, captured_at) "
                    + "VALUES ('/api/legacy', 'GET', 200, NOW())");
        }

        ApiMonitoringRepository.init(dbUrl, "sa", "", "H2", 2);
        assertTrue("旧库经 baselineOnMigrate 应成功接入", ApiMonitoringRepository.isInitialized());

        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM route_monitor_record")) {
            assertTrue(rs.next());
            assertEquals("旧表数据应保留（零丢数据）", 1, rs.getInt(1));
        }
        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '1'")) {
            assertTrue("应写入 V1 基线历史", rs.next());
            assertEquals(1, rs.getInt(1));
        }
        ApiMonitoringRepository.shutdown();
    }

    @Test
    public void shouldResolveOracleAndSqlServerDialects() {
        // 显式 dbType 优先（含 MSSQL 别名归一为 SQLSERVER）
        assertEquals("ORACLE", ApiMonitoringRepository.resolveDialect("ORACLE", "jdbc:oracle:thin:@localhost:1521:x"));
        assertEquals("SQLSERVER", ApiMonitoringRepository.resolveDialect("SQLSERVER", "jdbc:sqlserver://localhost:1433;database=x"));
        assertEquals("SQLSERVER", ApiMonitoringRepository.resolveDialect("MSSQL", "jdbc:sqlserver://localhost:1433;database=x"));
        // URL 自动嗅探（dbType 留空）
        assertEquals("ORACLE", ApiMonitoringRepository.resolveDialect("", "jdbc:oracle:thin:@localhost:1521:x"));
        assertEquals("SQLSERVER", ApiMonitoringRepository.resolveDialect("", "jdbc:sqlserver://localhost:1433;database=x"));
        // 既有方言不受影响
        assertEquals("MYSQL", ApiMonitoringRepository.resolveDialect("MYSQL", "jdbc:mysql://localhost/x"));
        assertEquals("POSTGRESQL", ApiMonitoringRepository.resolveDialect("POSTGRESQL", "jdbc:postgresql://localhost/x"));
        assertEquals("H2", ApiMonitoringRepository.resolveDialect("H2", "jdbc:h2:mem:test"));
        // 未知显式类型不静默回落 MySQL（保留 fail-fast 安全网）
        assertEquals("FOO", ApiMonitoringRepository.resolveDialect("FOO", "jdbc:foo://x"));
    }

    @Test
    public void shouldMapDialectToFlywayLocationFolder() {
        assertEquals("oracle", ApiMonitoringRepository.dialectToMigrationFolder("ORACLE"));
        assertEquals("sqlserver", ApiMonitoringRepository.dialectToMigrationFolder("SQLSERVER"));
        assertEquals("mysql", ApiMonitoringRepository.dialectToMigrationFolder("MYSQL"));
        assertEquals("postgresql", ApiMonitoringRepository.dialectToMigrationFolder("POSTGRESQL"));
        assertEquals("h2", ApiMonitoringRepository.dialectToMigrationFolder("H2"));
    }

    @Test
    public void shouldSilentlySkipWhenNotInitialized() {
        // 未 init 时 save 应静默跳过（不报错、不抛异常、不积压）
        ApiMonitoringRepository.save(ApiMonitoringRecord.builder()
                .endpoint("/api/x").requestUrl("https://x/y").method("GET").statusCode(200).build());
        assertEquals(0, ApiMonitoringRepository.pendingCount());
        assertFalse(ApiMonitoringRepository.isInitialized());
    }

    private Map<String, String> sampleHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Authorization", "Bearer secret-token-should-be-redacted");
        return h;
    }
}
