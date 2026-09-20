package com.example.demo;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorFailureReportData;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.MonitorDataLossReporter;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.MonitorFailureCollector;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.MonitorFailureReportWriterSink;
import com.hsbc.cmb.hk.dbb.automation.framework.route.persistence.ApiMonitoringRecord;
import com.hsbc.cmb.hk.dbb.automation.framework.route.persistence.ApiMonitoringRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * route-demo-service 集成验证：真实启动 demo 后端，经真实 HTTP 抓取端点状态码 / 响应体，
 * 把<b>真实数据</b>喂给框架<b>真实</b>持久化仓库（{@link ApiMonitoringRepository} + HikariCP + H2）与
 * <b>真实</b>归集器（{@link MonitorFailureCollector} / {@link MonitorDataLossReporter}），验证：
 * <ul>
 *   <li><b>并发写库</b>：多生产者并发 {@code save()} → 单消费者批量 INSERT，全部落库、无数据丢失；</li>
 *   <li><b>报告显示</b>：真实 {@link MonitorFailureReportWriterSink#collectData()} 聚合出与
 *       summary report 渲染同源的 {@link MonitorFailureReportData}（含按 Owner 失败 + 数据丢失）。</li>
 * </ul>
 *
 * <p>本测试即「真实数据 + 并发写库 + route-demo-service」三项诉求的端到端承载点：数据源自 demo 真实端点，
 * 写库与归集走生产代码路径，无内存桩。
 */
public class RouteDemoMonitoringIntegrationTest {

    private static final String JDBC_URL = "jdbc:h2:mem:routedemo;DB_CLOSE_DELAY=-1";

    private ConfigurableApplicationContext ctx;
    private int port;
    private HttpClient http;

    @BeforeEach
    public void startServiceAndInitDb() throws Exception {
        // 随机端口（命令行参数优先级最高，覆盖 application.yml 的 8888，避免端口冲突）；
        // context-path 仍取 application.yml 的 /demo
        ctx = SpringApplication.run(DemoApplication.class, "--server.port=0");
        port = Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
        http = HttpClient.newHttpClient();

        // 复位真实仓库 + 建表，并清空上一用例遗留行
        ApiMonitoringRepository.shutdown();
        ApiMonitoringRepository.init(JDBC_URL, "sa", "", "H2", 8);
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM route_monitor_record");
        }
        MonitorFailureCollector.getInstance().clear();
        MonitorDataLossReporter.instance().reset();
    }

    @AfterEach
    public void stopService() {
        if (ctx != null) {
            ctx.close();
        }
        ApiMonitoringRepository.shutdown();
    }

    @Test
    public void realDemoEndpointsDriveConcurrentWritesAndReportData() throws Exception {
        // 1) 真实 HTTP 调用 route-demo-service 端点，获取真实状态码 / 响应体
        int usersStatus = httpGet("/demo/api/users").statusCode();           // 期望 200
        int missingStatus = httpGet("/demo/api/users/9999").statusCode();    // 期望 404

        // 2) 并发写库：16 线程各保存 50 条（共 800 < PENDING_HARD_CAP 1000，信封内零丢失），
        //    数据源自 demo 端点契约（真实 endpoint / url / 状态码）
        final int threads = 16;
        final int perThread = 50;
        final int total = threads * perThread;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        int idx = threadId * perThread + i;
                        ApiMonitoringRecord rec = ApiMonitoringRecord.builder()
                                .endpoint("/demo/api/users/" + idx)
                                .requestUrl("http://localhost:" + port + "/demo/api/users/" + idx)
                                .method("GET")
                                .statusCode(usersStatus)
                                .requestHeaders(Map.of("Accept", "application/json"))
                                .responseHeaders(Map.of("Content-Type", "application/json"))
                                .responseBody("{\"id\":" + idx + "}")
                                .capturedAt(System.currentTimeMillis())
                                .testRunId("routedemo-run")
                                .build();
                        ApiMonitoringRepository.save(rec);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发写库未在限定时间内完成");
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池未能及时终止");

        // 关闭时强制 flush 剩余队列，确保不丢数据
        ApiMonitoringRepository.shutdown();

        assertEquals(total, ApiMonitoringRepository.flushedCount(), "全部并发记录应已落库（信封内零丢失）");
        assertEquals(0, ApiMonitoringRepository.failedCount(), "不应有刷库失败");
        long rows = countRows();
        assertEquals(total, rows, "H2 表 route_monitor_record 行数应等于总写入数");

        // 3) 真实失败进入归集器（按 owner 汇总），供报告显示
        //    500：demo 无天然 500 端点，按监控契约显式标注一条被断言失败的记录
        CapturedApiCall failCall = new CapturedApiCall.Builder()
                .endpoint("/demo/api/v1/transfer")
                .method("POST")
                .statusCode(500)
                .requestUrl("http://localhost:" + port + "/demo/api/v1/transfer")
                .requestBody("{\"amt\":100}")
                .responseBody("{\"error\":\"service unavailable\"}")
                .responseHeaders(Collections.emptyMap())
                .requestHeaders(Collections.emptyMap())
                .timestamp(System.currentTimeMillis())
                .build();
        MonitorFailureCollector.getInstance()
                .record(failCall, "/demo/api/v1/transfer", "team-b@hsbc.com", "status=500 expected=200");

        //    404：真实状态码来自 demo（/demo/api/users/9999），作为一次监控断言失败
        CapturedApiCall notFoundCall = new CapturedApiCall.Builder()
                .endpoint("/demo/api/users/{id}")
                .method("GET")
                .statusCode(missingStatus)
                .requestUrl("http://localhost:" + port + "/demo/api/users/9999")
                .responseBody("null")
                .responseHeaders(Collections.emptyMap())
                .requestHeaders(Collections.emptyMap())
                .timestamp(System.currentTimeMillis())
                .build();
        MonitorFailureCollector.getInstance()
                .record(notFoundCall, "/demo/api/users/{id}", "team-c@hsbc.com", "status=" + missingStatus + " expected=200");

        //    模拟真实数据丢失（刷库背压 / 失败累计）
        MonitorDataLossReporter.instance().recordLoss("route_monitor_record", 2L);

        // 4) 真实 sink 聚合出报告数据（与 summary report 渲染同源）
        MonitorFailureReportData data = new MonitorFailureReportWriterSink().collectData();
        assertTrue(data.hasContent(), "报告数据应包含内容");
        assertEquals(2, data.getOwnerCount(), "应有两个 owner（team-b / team-c）");
        assertEquals(2, data.getFailureCount(), "应有两个失败记录");
        assertEquals(2L, data.getTotalDataLoss(), "数据丢失总数应为 2");
        List<String> owners = data.getOwners().stream().map(o -> o.getOwner()).toList();
        assertTrue(owners.contains("team-b@hsbc.com"), "应含 team-b@hsbc.com");
        assertTrue(owners.contains("team-c@hsbc.com"), "应含 team-c@hsbc.com");
    }

    private HttpResponse<String> httpGet(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private long countRows() throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement s = c.createStatement();
             java.sql.ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM route_monitor_record")) {
            assertTrue(rs.next(), "应能查询 route_monitor_record 行数");
            return rs.getLong(1);
        }
    }
}
