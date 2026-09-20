package com.example.demo;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.MonitorConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureManager;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.hsbc.cmb.hk.dbb.automation.framework.route.persistence.ApiMonitoringRepository;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 框架总体能力端到端验证（真实浏览器 + RouteDSL + 配置驱动 DB 写库 + 连接释放检测）。
 *
 * <p>本测试完整跑通用户诉求的链路：
 * <ol>
 *   <li><b>RouteDSL 声明式监控 + mock</b>：用 {@link RouteDsl} 在真实 Playwright 页面上注册
 *       MONITOR 规则（监控 /demo/api/users）与 MOCK 规则（拦截 /demo/api/mock-me 并返回假响应）；</li>
 *   <li><b>配置文件驱动数据库写库</b>：写库配置全部写在 {@code src/test/resources/serenity.properties}
 *       （{@code monitor.db.*} / {@code monitor.test.run.id}），由 {@code ConfigSource} 经 {@code ConfigResolver}
 *       SPI 按 Serenity 配置源语义读取 —— 用例代码不写任何 {@code System.setProperty}；框架在抓到响应时
 *       自动经 {@code MonitorHandler → DatabaseStoreMonitorCallback} 落 MySQL；</li>
 *   <li><b>真实数据</b>：经真实 chromium 浏览器驱动真实 HTTP 请求到 demo 后端，监控到的状态码 / 响应体
 *       源自真实端点契约，非内存桩；</li>
 *   <li><b>连接释放检测（重点）</b>：写库完成后，连接应归还连接池（全部空闲、无活跃泄漏）；
 *       框架 {@link ApiMonitoringRepository#shutdown()} 关闭数据源后，MySQL 侧该应用连接应全部断开。</li>
 * </ol>
 *
 * <p>前置：本机 MySQL 可达 {@code localhost:3306}，账号 root/root（见 serenity.properties），
 * 且已装 Playwright chromium 浏览器。目标库 {@code route_monitor} 由本测试 {@code CREATE DATABASE IF NOT EXISTS}
 * 自动创建，表由框架 Flyway 迁移自动建立。
 */
public class RouteDemoBrowserMonitoringIntegrationTest {

    /**
     * 测试侧用于建库 / processlist 探查的「无默认库」连接（凭据与 serenity.properties 的
     * monitor.db.* 一致）。框架写库自身的连接池由框架按配置自建，与此无关。
     */
    private static final String SIDE_URL =
            "jdbc:mysql://localhost:3306/?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&user=root&password=root";
    private static final String USER = "root";
    private static final String PASS = "root";

    /** 本次运行 ID（取自 serenity.properties 的 monitor.test.run.id）。 */
    private static String RUN_ID;

    /** 连接池上限（取自 serenity.properties 的 monitor.db.pool.max.size），用于连接数断言上界。 */
    private static int POOL_MAX;

    /** 收尾是否清理本 run 记录：默认 false＝保留（便于在 MySQL 中直观查看）；-Dmonitor.test.cleanup=true 时清理。 */
    private static final boolean CLEANUP_ROWS = Boolean.getBoolean("monitor.test.cleanup");

    private static ConfigurableApplicationContext ctx;
    private static int port;

    /**
     * 基线连接数：{@code ApiMonitoringRepository.init} 之前 route_monitor 上的历史残留连接数
     * （含此前 JVM 被强杀、连接池未正常关闭而遗留的连接）。用差值判定本次池行为，
     * 规避残留连接对绝对计数的干扰。
     */
    private static int baselineConns;

    @BeforeAll
    public static void setup() throws Exception {
        // 1) 写库配置全部来自 serenity.properties（见 src/test/resources/serenity.properties），
        //    由 test 期 ConfigResolver SPI（TestSerenityConfigResolver）按 Serenity 配置源语义读取；
        //    用例代码不再写任何 System.setProperty。此处仅经 MonitorConfig 读回同一配置用于预热/断言。
        RUN_ID = MonitorConfig.getString(MonitorConfig.MONITOR_TEST_RUN_ID);
        POOL_MAX = MonitorConfig.getInt(MonitorConfig.MONITOR_DB_POOL_MAX_SIZE, 5);

        // 2) 确保目标库存在（建表交给框架内置 Flyway 迁移；JDBC URL 已指向 route_monitor）。
        createDatabaseIfNotExists();

        // 3) 复位真实仓库，清掉历史连接（确保从干净态开始）。
        ApiMonitoringRepository.shutdown();

        // 3.05) 记录基线连接数（历史残留，与本轮池无关），供后续以差值判定本次池的连接行为。
        baselineConns = appConnectionCount();

        // 3.0) 主动初始化仓库（参数全部取自 serenity.properties，经 MonitorConfig 读回）：同步执行框架
        //      内置 Flyway 迁移建表，确保表在首次监控落库前已存在，避免"首次 onResponse 在路由线程懒触发
        //      init，主线程查询早于建表完成"的竞态。实际写入仍由框架 MonitorHandler →
        //      DatabaseStoreMonitorCallback 自动完成（受同一 config 开关控制）。
        ApiMonitoringRepository.init(
                MonitorConfig.getString(MonitorConfig.MONITOR_DB_URL),
                MonitorConfig.getString(MonitorConfig.MONITOR_DB_USER),
                MonitorConfig.getString(MonitorConfig.MONITOR_DB_PASSWORD),
                MonitorConfig.getString(MonitorConfig.MONITOR_DB_TYPE),
                POOL_MAX);

        // 3.2) 清空本 run 的历史记录（run id 固定在配置文件），使落库计数只反映本次运行。
        deleteRunRows();

        // 3.1) 关闭全局被动捕获（onResponse 兜底）。
        //     高 churn 下浏览器侧 response@ 对象会被快速 GC，被动捕获在事件分发层解析失效
        //     response@ 时抛 "Object doesn't exist" 并污染同连接在途的 page.evaluate；关闭后，
        //     已注册流量仍由 MonitorHandler 的 waitForResponse 通道（→ DB 回调）独立采集，不受影响。
        ApiCaptureManager.setPassthroughEnabled(false);

        // 4) 启动 demo 后端（随机端口，context-path=/demo）。
        ctx = SpringApplication.run(DemoApplication.class, "--server.port=0");
        port = Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    public static void teardown() throws Exception {
        if (CLEANUP_ROWS) {
            // 可选清理本 run 记录（-Dmonitor.test.cleanup=true）。
            try (Connection c = DriverManager.getConnection(SIDE_URL, USER, PASS);
                 Statement s = c.createStatement()) {
                s.execute("DELETE FROM route_monitor.route_monitor_record WHERE test_run_id = '" + RUN_ID + "'");
            } catch (Exception ignored) {
                // 清理失败不影响测试结论，仅记日志级（此处静默）。
            }
        } else {
            // 默认保留：方便在 MySQL 中直观查看本次落库数据。
            System.out.println("[E2E-VERIFY] 已保留本次落库记录（test_run_id=" + RUN_ID
                    + "）；可在 MySQL 执行 SELECT * FROM route_monitor_record; 查看；"
                    + "如需自动清理请加 -Dmonitor.test.cleanup=true");
        }
        if (ctx != null) {
            ctx.close();
        }
        // 复位被动捕获开关，避免影响同 JVM 内其它测试。
        ApiCaptureManager.setPassthroughEnabled(true);
        ApiMonitoringRepository.shutdown();
    }

    @Test
    public void routeDslMonitorAndMockWriteToMysqlAndReleaseConnections() throws Exception {
        Playwright playwright = Playwright.create();
        Browser browser = playwright.chromium().launch();
        BrowserContext context = browser.newContext();
        Page page = context.newPage();
        try {
            // 用 RouteDSL 声明式定义监控 + mock 规则（框架总体能力，无需手动注册回调）。
            RouteDsl.on(page)
                    .api("/demo/api/users").monitor().expectStatus(200).done()
                    .api("/demo/api/mock-me").mock().mockBody("{\"mocked\":true}").mockStatus(200).done()
                    .start();

            String base = "http://localhost:" + port;
            // 用 page.navigate（返回 Response）驱动请求：导航式请求与框架路由 handler 同步完成、
            // 天然串行，避免 page.evaluate(fetch) 与路由拦截并发时的 route@/request@/response@ 失效竞态。
            final int N = 5;
            for (int i = 0; i < N; i++) {
                // /demo/api/users 被 RouteDSL 监控：框架自动捕获并经 DB 回调落 MySQL。
                // 每次导航后停顿：让框架异步捕获（读响应体 + 落库回调）完成，避免快速连发时
                // 浏览器取消上一条在途 body 读而导致丢采；唯一 query 亦使每次为独立请求。
                navigateSafe(page, base + "/demo/api/users?seq=" + i);
                Thread.sleep(700);
            }
            // /demo/api/mock-me 被 RouteDSL mock：导航到该端点直接读取被 fulfill 的响应体。
            String mockBody = navigateAndReadText(page, base + "/demo/api/mock-me");
            assertEquals("{\"mocked\":true}", mockBody, "RouteDSL mock 应返回自定义响应体");
            System.out.println("[E2E-VERIFY] RouteDSL mock 命中，浏览器收到自定义响应体: " + mockBody);

            // 等待后台批量刷库（阈值 50 / 周期 2s），轮询 MySQL 直至本 run 记录落地。
            long rows = awaitRows(N, 15, TimeUnit.SECONDS);
            assertTrue(rows >= N,
                    "真实监控数据应已落 MySQL，期望 >= " + N + " 行，实际 " + rows);
            System.out.println("[E2E-VERIFY] 真实监控数据已落 MySQL: test_run_id=" + RUN_ID + ", rows=" + rows);
            printPersistedRows();

            // ── 连接释放验证（重点）──
            // 写库完成后，连接应归还连接池（全部空闲），而非被业务长期占用 → 无活跃泄漏。
            dumpProcessList("pre-release");
            int poolConns = appConnectionCount() - baselineConns;
            int activeConns = activeAppConnectionCount();
            assertTrue(poolConns >= 1 && poolConns <= POOL_MAX,
                    "连接池应保持 minIdle..maxPoolSize 空闲连接，实际池内 " + poolConns
                            + "（基线残留 " + baselineConns + "）");
            assertEquals(0, activeConns,
                    "写库后不应有活跃(泄漏)连接 — 框架应及时归还连接，实际活跃 " + activeConns);
            System.out.println("[E2E-VERIFY] 写库后连接已即时归还连接池: 本次池内连接=" + poolConns
                    + " (minIdle..maxPoolSize=" + POOL_MAX + "), 活跃(泄漏)连接=" + activeConns
                    + ", 基线残留=" + baselineConns);

            // 框架显式关闭数据源 → MySQL 侧该应用连接应全部断开。
            // HikariCP 关闭连接与 MySQL 侧 processlist 回收存在毫秒级延迟，故轮询等待归零（而非瞬时断言）。
            dumpProcessList("before-shutdown");
            ApiMonitoringRepository.shutdown();
            int afterShutdown = awaitAppConnectionCount(baselineConns, 10, TimeUnit.SECONDS);
            dumpProcessList("after-shutdown");
            assertEquals(baselineConns, afterShutdown,
                    "shutdown 后本次池连接应全部关闭、连接数回落到基线 " + baselineConns + "，实际 " + afterShutdown);
            System.out.println("[E2E-VERIFY] ApiMonitoringRepository.shutdown() 后 MySQL 侧连接数回落到基线="
                    + afterShutdown + "（本次池连接已全部关闭）");
        } finally {
            // 先显式清理路由规则，避免残留拦截；随后静默关闭各层资源。
            // 关闭带 route 处理器的页面/上下文时，浏览器侧 route@ 对象可能已被 GC，
            // Playwright 在清理期解析失效对象会抛 "Object doesn't exist" —— 属清理期噪音，
            // 资源仍会被释放（最终 playwright.close() 兜底回收进程），故静默忽略。
            try {
                if (context != null) {
                    RouteDsl.clear(context);
                }
            } catch (Exception ignore) {
                // 清理失败不影响资源释放
            }
            silentClose(page);
            silentClose(context);
            silentClose(browser);
            silentClose(playwright);
        }
    }

    /** 静默关闭单个可关闭资源；关闭异常（连接已损坏/对象已 GC 等）记日志但不外抛，保证后续资源仍被释放。 */
    private static void silentClose(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Throwable t) {
            // 清理期噪音（如 route@/response@ 已被 GC 的 Playwright 对象），忽略
        }
    }

    /**
     * 确保目标库存在（仅建库；建表由框架内置 Flyway 迁移完成）。
     * 连接池 URL 已指向 route_monitor，库不存在则首连失败，故测试侧先兜底建库。
     */
    private static void createDatabaseIfNotExists() throws Exception {
        try (Connection c = DriverManager.getConnection(SIDE_URL, USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS route_monitor");
        }
    }

    /** 容错导航：并发路由下 navigate 亦可能命中瞬态对象失效竞态，重试即重新导航（等价多一次监控捕获）。 */
    private static void navigateSafe(Page page, String url) throws InterruptedException {
        PlaywrightException last = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            try {
                page.navigate(url);
                return;
            } catch (PlaywrightException e) {
                if (isTransientRouteRace(e)) {
                    last = e;
                    Thread.sleep(150);
                    continue;
                }
                throw e;
            }
        }
        throw last;
    }

    /** 容错导航并读取响应体（mock 响应体校验用）；失效竞态时重新导航取全新 Response 再读。 */
    private static String navigateAndReadText(Page page, String url) throws InterruptedException {
        PlaywrightException last = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            Response resp = page.navigate(url);
            try {
                return resp == null ? null : resp.text();
            } catch (PlaywrightException e) {
                if (isTransientRouteRace(e)) {
                    last = e;
                    Thread.sleep(150);
                    continue;
                }
                throw e;
            }
        }
        throw last;
    }

    /**
     * 判定是否为 Playwright 并发路由拦截下的瞬态"对象失效"竞态。
     * 浏览器侧 route@/request@/response@ 对象可能被快速 GC，事件分发层解析失效对象时抛多种变体：
     * "Object doesn't exist: route@…"、"Cannot find parent object request@… to create route@…" 等。
     * 均与本次请求语义无关，重试（重新发起请求）即可成功。
     */
    private static boolean isTransientRouteRace(PlaywrightException e) {
        String m = e.getMessage();
        if (m == null) {
            return false;
        }
        return m.contains("Object doesn't exist")
                || m.contains("Cannot find parent object")
                || m.contains("route@")
                || m.contains("request@")
                || m.contains("response@");
    }

    /** 轮询 MySQL，直到本 run 记录数达到 minRows 或超时。 */
    private long awaitRows(int minRows, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            long c = countRows();
            if (c >= minRows) {
                return c;
            }
            Thread.sleep(300);
        }
        return countRows();
    }

    /** 打印本 run 在 MySQL 中已落库的记录明细（直观证据，避免手工查库）。 */
    private void printPersistedRows() throws Exception {
        System.out.println("[E2E-VERIFY] MySQL route_monitor_record 本次记录明细：");
        try (Connection c = DriverManager.getConnection(SIDE_URL, USER, PASS);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id, endpoint, method, status_code, body_length, test_run_id"
                             + " FROM route_monitor.route_monitor_record WHERE test_run_id = '" + RUN_ID + "' ORDER BY id")) {
            while (rs.next()) {
                System.out.printf("[E2E-VERIFY]   #%d endpoint=%s method=%s status=%d bodyLen=%d run=%s%n",
                        rs.getLong("id"), rs.getString("endpoint"), rs.getString("method"),
                        rs.getInt("status_code"), rs.getInt("body_length"), rs.getString("test_run_id"));
            }
        }
    }

    /** 清空本 run 的历史记录（run id 固定于 serenity.properties），保证落库计数只反映本次运行。 */
    private static void deleteRunRows() throws Exception {
        try (Connection c = DriverManager.getConnection(SIDE_URL, USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM route_monitor.route_monitor_record WHERE test_run_id = '" + RUN_ID + "'");
        }
    }

    private long countRows() throws Exception {
        try (Connection c = DriverManager.getConnection(SIDE_URL, USER, PASS);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM route_monitor.route_monitor_record WHERE test_run_id = '" + RUN_ID + "'")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** 连接池在 route_monitor 上的连接总数（排除本测试侧的连接自身）。 */
    private static int appConnectionCount() throws Exception {
        try (Connection c = DriverManager.getConnection(SIDE_URL, USER, PASS);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.processlist "
                             + "WHERE DB = 'route_monitor' AND ID != CONNECTION_ID()")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** 诊断：转储 route_monitor 上的连接明细（来源排查用）。 */
    private static void dumpProcessList(String tag) throws Exception {
        try (Connection c = DriverManager.getConnection(SIDE_URL, USER, PASS);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT ID, USER, HOST, DB, COMMAND, TIME, STATE, INFO"
                             + " FROM information_schema.processlist WHERE DB = 'route_monitor'")) {
            System.out.println("[E2E-VERIFY] processlist(" + tag + ") DB=route_monitor 连接：");
            while (rs.next()) {
                System.out.printf("[E2E-VERIFY]   id=%d user=%s host=%s command=%s time=%d state=%s info=%s%n",
                        rs.getLong("ID"), rs.getString("USER"), rs.getString("HOST"),
                        rs.getString("COMMAND"), rs.getLong("TIME"), rs.getString("STATE"), rs.getString("INFO"));
            }
        }
    }

    /** 轮询等待 route_monitor 上的连接数达到期望值（消除"关闭→MySQL 侧回收"的观测延迟）。 */
    private static int awaitAppConnectionCount(int expected, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        int last = appConnectionCount();
        while (last != expected && System.nanoTime() < deadline) {
            Thread.sleep(150);
            last = appConnectionCount();
        }
        return last;
    }

    /** 连接池在 route_monitor 上处于活跃（非 Sleep）状态的连接数 —— 用于检测连接泄漏。 */
    private static int activeAppConnectionCount() throws Exception {
        try (Connection c = DriverManager.getConnection(SIDE_URL, USER, PASS);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.processlist "
                             + "WHERE DB = 'route_monitor' AND ID != CONNECTION_ID() "
                             + "AND COMMAND NOT IN ('Sleep','Daemon')")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
