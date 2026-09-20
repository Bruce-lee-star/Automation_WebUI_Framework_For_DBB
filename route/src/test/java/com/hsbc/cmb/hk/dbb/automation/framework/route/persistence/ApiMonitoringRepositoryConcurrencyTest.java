package com.hsbc.cmb.hk.dbb.automation.framework.route.persistence;

import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.MonitorDataLossReporter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApiMonitoringRepository} 真实并发写库验证（H2 内存库，真实批量 INSERT）。
 *
 * <p><b>为什么需要它</b>：{@code save()} 是高频并发入队（{@code ConcurrentLinkedQueue} + O(1) 计数），
 * 真正落库由单线程批量刷入器（{@code flushPendingNow()} → {@code addBatch()+executeBatch()}）完成。
 * 该「多生产者 / 单消费者」路径的正确性无法靠单元断言覆盖，必须用真实 H2 在并发下验证。
 *
 * <p><b>设计内背压</b>：{@code PENDING_HARD_CAP = BATCH_THRESHOLD * 20 = 1000}。当内存队列超过硬上限，
 * 生产路径会丢弃最旧记录并经由 {@link MonitorDataLossReporter} 记录「数据丢失」遥测（本监控特性的核心观测项）。
 * 因此本测试分两例：
 * <ul>
 *   <li>{@link #concurrentSavesWithinCapAreLossless()}：负载落在信封内（&lt; 1000），断言<b>零丢失</b>
 *       （全落库、H2 行数一致、无数据丢失遥测）；</li>
 *   <li>{@link #concurrentSavesBeyondCapRecordDataLoss()}：负载超出背压上限，断言<b>记账完整</b>
 *       （落库数 + 数据丢失数 == 总写入数，无凭空丢失）且<b>数据丢失遥测被正确触发</b>。</li>
 * </ul>
 *
 * <p><b>真实数据</b>：记录内容取自 route-demo-service 的真实端点契约（{@code /demo/api/v1/resource/...}），
 * 经真实 {@code ApiMonitoringRepository} + 真实 HikariCP + 真实 Flyway 建表写入 H2，非内存桩。
 */
public class ApiMonitoringRepositoryConcurrencyTest {

    private static final String JDBC_URL = "jdbc:h2:mem:monitorconcurrency;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASS = "";

    @BeforeEach
    public void setUp() throws Exception {
        // 复位进程级状态后初始化真实 H2 连接池 + Flyway 建表
        ApiMonitoringRepository.reset();
        ApiMonitoringRepository.init(JDBC_URL, USER, PASS, "H2", 8);
        // DB_CLOSE_DELAY=-1 的内存库跨用例持久化，且 Flyway baseline 不清数据，故显式清空表，避免用例间污染
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM route_monitor_record");
        }
        MonitorDataLossReporter.instance().reset();
    }

    @AfterEach
    public void tearDown() {
        ApiMonitoringRepository.reset();
        MonitorDataLossReporter.instance().reset();
    }

    /** 信封内并发写库：负载（800）&lt; PENDING_HARD_CAP(1000)，必须零丢失。 */
    @Test
    public void concurrentSavesWithinCapAreLossless() throws Exception {
        final int threadCount = 16;
        final int perThread = 50;          // 16 * 50 = 800 < 1000 硬上限
        final int total = threadCount * perThread;

        int flushed = runConcurrentSaves(threadCount, perThread, total);
        assertEquals(total, flushed, "信封内并发写库应全部落库，零丢失");
        assertEquals(0L, MonitorDataLossReporter.instance().lossByCategory()
                .getOrDefault("route_monitor_record", 0L), "信封内不应有数据丢失遥测");
        assertFalse(MonitorDataLossReporter.instance().hasLoss(), "信封内不应触发数据丢失");

        long rows = countRows();
        assertEquals(total, rows, "H2 表 route_monitor_record 行数应等于总写入数");
    }

    /** 超出背压上限的并发写库：断言记账完整（落库 + 丢失 == 总数）且数据丢失遥测被正确触发。 */
    @Test
    public void concurrentSavesBeyondCapRecordDataLoss() throws Exception {
        final int threadCount = 16;
        final int perThread = 100;         // 16 * 100 = 1600 > 1000 硬上限，触发背压丢弃
        final int total = threadCount * perThread;

        int flushed = runConcurrentSaves(threadCount, perThread, total);

        long loss = MonitorDataLossReporter.instance().lossByCategory()
                .getOrDefault("route_monitor_record", 0L);
        // 记账完整性：落库数 + 数据丢失数 == 总写入数（无凭空丢失、无卡在队列）
        assertEquals(total, flushed + loss, "落库数 + 数据丢失数应等于总写入数");
        assertTrue(loss > 0, "超出背压上限应触发数据丢失遥测");
        assertTrue(MonitorDataLossReporter.instance().hasLoss(), "应标记存在数据丢失");

        long rows = countRows();
        assertEquals(flushed, rows, "H2 行数应等于实际落库数");
    }

    /**
     * 真正并发执行 {@code save()}：16 线程同时发令入队，再等待单消费者刷库器清空队列。
     *
     * @return 落库记录数（{@link ApiMonitoringRepository#flushedCount()}）
     */
    private int runConcurrentSaves(int threadCount, int perThread, int total) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);   // 统一发令，制造真实并发竞争
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger failedSaves = new AtomicInteger(0);

        List<Runnable> tasks = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            tasks.add(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        int idx = threadId * perThread + i;
                        ApiMonitoringRecord rec = ApiMonitoringRecord.builder()
                                .endpoint("/demo/api/v1/resource/" + (idx % 7))
                                .requestUrl("http://demo-host:8888/demo/api/v1/resource/" + idx)
                                .method("GET")
                                .statusCode(200)
                                .requestHeaders(Map.of("Accept", "application/json"))
                                .responseHeaders(Map.of("Content-Type", "application/json"))
                                .responseBody("{\"id\":" + idx + "}")
                                .capturedAt(System.currentTimeMillis())
                                .testRunId("concurrency-run")
                                .build();
                        ApiMonitoringRepository.save(rec);
                    }
                } catch (Exception e) {
                    failedSaves.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        for (Runnable r : tasks) {
            pool.submit(r);
        }

        start.countDown();   // 所有线程同时开始 save()
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发写库未在限定时间内完成");
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池未能及时终止");
        assertEquals(0, failedSaves.get(), "save() 不应抛异常");

        // 等待单消费者刷库器（2000ms 周期）把并发入队的记录全部落库；
        // 不依赖 shutdown() 的强制 flush，避开「关闭连接池」与「批量 INSERT」的竞态。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (ApiMonitoringRepository.pendingCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(0, ApiMonitoringRepository.pendingCount(), "刷库器应清空待落库队列");
        assertEquals(0, ApiMonitoringRepository.failedCount(), "不应有刷库失败（仅背压丢弃计入数据丢失）");

        // 关闭连接池（此时队列已空，不丢数据）
        ApiMonitoringRepository.shutdown();
        return (int) ApiMonitoringRepository.flushedCount();
    }

    private long countRows() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, USER, PASS);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM route_monitor_record")) {
            assertTrue(rs.next(), "应能查询 route_monitor_record 行数");
            return rs.getLong(1);
        }
    }
}
