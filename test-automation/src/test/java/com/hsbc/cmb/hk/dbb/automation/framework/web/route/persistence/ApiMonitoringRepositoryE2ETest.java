package com.hsbc.cmb.hk.dbb.automation.framework.web.route.persistence;

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
import static org.junit.Assert.assertTrue;

/**
 * T0-4 补全收尾：Monitor DB 持久化端到端验证（确认非死代码）。
 *
 * <p>用 H2 内存库驱动真实 JDBC 写入链路 {@code init → save → flush → 落库}，
 * 证明 {@link ApiMonitoringRepository} 的批量写路径真实可用，而非永不生效代码。
 * 白盒测试（同包）以调用包级私有 {@link #reset()} 做用例间静态状态隔离。
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
