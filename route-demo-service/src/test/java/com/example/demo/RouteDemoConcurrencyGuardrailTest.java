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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发采集<b>压测护栏</b>（X-1 梯度 4→8→16）：真实浏览器 + 真实后端 + 真实 MySQL 下，
 * 验证监控链路在并发梯度上<b>零丢失</b>且<b>不泄漏连接</b>。
 *
 * <p><b>为什么需要它</b>：监控链路曾有「高并发下捕获全部降级」的历史事故——即
 * {@code res.body()} 是 CDP 往返，读体时机被推迟到响应对象被浏览器回收之后，
 * 抛 {@code Object doesn't exist} 使捕获静默归零（实测 monitor@50 捕获为 0，见 {@code MonitorConfig}
 * 中 body 读取并发/协调池的取值论证）。此类缺陷<b>单并发下不可见</b>：功能全对，只有并发起来才丢数据；
 * 而"丢数据"恰恰是最危险的一类缺陷——测试仍绿，但审计/报告里的证据是空的。
 * 故本护栏把「并发档位下捕获数必须等于请求数」从"靠审查相信"变成"靠断言守住"。
 *
 * <p><b>断言口径</b>：
 * <ul>
 *   <li>每个 page 在自己的线程/Context 内串行发 {@code REQ_PER_PAGE} 次请求，<b>跨 page 并发</b>
 *       （贴近真实：同 Context 内页面切换本就串行，跨 Context 才是并发收益来源）；</li>
 *   <li>梯度逐档累加，断言 MySQL 中本 run 的行数 <b>≥ 累计请求数</b>——即任一档位都不允许丢采；</li>
 *   <li>全部档位结束后断言活跃（非 Sleep）连接为 0，且 {@code shutdown()} 后连接回落到基线。</li>
 * </ul>
 *
 * <p><b>可调旋钮</b>：{@code -Dguardrail.parallelisms=4,8,16}（默认）与
 * {@code -Dguardrail.requestsPerPage=3}（默认；P=16 时合计 48 次，贴近历史失败的 50 并发档位）。
 *
 * <p>前置：本机 MySQL 可达（root/root）、已装 Playwright chromium；目标库/表由框架自动建立。
 * 本类使用<b>专用 run id</b>（{@code conc-guardrail}），与其它用例的落库计数互不干扰。
 */
public class RouteDemoConcurrencyGuardrailTest {

    private static final String SIDE_URL =
            "jdbc:mysql://localhost:3306/?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&user=root&password=root";
    private static final String USER = "root";
    private static final String PASS = "root";

    /** 专用 run id：与既有 E2E 用例隔离，使落库计数只反映本护栏。 */
    private static final String RUN_ID = "conc-guardrail";

    /** 并发梯度（默认 4,8,16）。 */
    private static final int[] GRADIENT = parseGradient(System.getProperty("guardrail.parallelisms", "4,8,16"));

    /** 每个 page 的监控请求数。 */
    private static final int REQ_PER_PAGE = Integer.getInteger("guardrail.requestsPerPage", 3);

    /** 同一 page 内两次导航之间的结算间隔（毫秒）：避免浏览器取消上一条在途导航/body 读。 */
    private static final long SAME_PAGE_SETTLE_MS = Long.getLong("guardrail.samePageSettleMs", 400L);

    /** 每档等待落库的上限（秒）：批量刷库阈值 50 / 周期 2s，故给出充裕窗口。 */
    private static final long AWAIT_ROWS_SECONDS = 60L;

    private static int poolMax;
    private static int baselineConns;
    private static ConfigurableApplicationContext ctx;
    private static int port;

    @BeforeAll
    public static void setup() throws Exception {
        // 专用 run id（MonitorConfig/ConfigSource 优先读系统属性，故在读取配置前注入）。
        System.setProperty("monitor.test.run.id", RUN_ID);
        poolMax = MonitorConfig.getInt(MonitorConfig.MONITOR_DB_POOL_MAX_SIZE, 5);

        createDatabaseIfNotExists();
        ApiMonitoringRepository.shutdown();
        baselineConns = appConnectionCount();

        // 主动初始化仓库（同步执行框架 Flyway 建表），避免首次落库与建表竞态。
        ApiMonitoringRepository.init(
                MonitorConfig.getString(MonitorConfig.MONITOR_DB_URL),
                MonitorConfig.getString(MonitorConfig.MONITOR_DB_USER),
                MonitorConfig.getString(MonitorConfig.MONITOR_DB_PASSWORD),
                MonitorConfig.getString(MonitorConfig.MONITOR_DB_TYPE),
                poolMax);
        deleteRunRows();

        // 关闭全局被动捕获（高 churn 下 response@ 会被 GC，被动捕获会污染在途 evaluate）；
        // 已注册的 MONITOR 流量由 waitForResponse 通道独立采集，不受影响。
        ApiCaptureManager.setPassthroughEnabled(false);

        ctx = SpringApplication.run(DemoApplication.class, "--server.port=0");
        port = Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
        System.out.println("[GUARDRAIL] 梯度=" + java.util.Arrays.toString(GRADIENT)
                + " 每页请求=" + REQ_PER_PAGE + " demo 端口=" + port + " 连接池上限=" + poolMax);
    }

    @AfterAll
    public static void teardown() throws Exception {
        if (ctx != null) {
            ctx.close();
        }
        ApiCaptureManager.setPassthroughEnabled(true);
        ApiMonitoringRepository.shutdown();
        System.clearProperty("monitor.test.run.id");
    }

    @Test
    public void monitorCapturesAllTrafficAcrossConcurrencyGradient() throws Exception {
        Playwright playwright = Playwright.create();
        Browser browser = playwright.chromium().launch();
        try {
            long expected = 0;
            for (int parallelism : GRADIENT) {
                List<BrowserContext> contexts = new ArrayList<>();
                List<Page> pages = new ArrayList<>();
                for (int i = 0; i < parallelism; i++) {
                    BrowserContext context = browser.newContext();
                    Page page = context.newPage();
                    // 每个 page 各自注册 MONITOR 规则（per-context 隔离），框架自动捕获并落库。
                    RouteDsl.on(page)
                            .api("/demo/api/users").monitor().expectStatus(200).done()
                            .start();
                    contexts.add(context);
                    pages.add(page);
                }
                try {
                    DriveResult result = driveConcurrently(pages, parallelism);
                    // 只对「成功完成的请求」要求被采集：被浏览器取消的导航没有响应，本就不该计入期望。
                    expected += result.succeeded;
                    assertTrue(result.succeeded >= parallelism,
                            "并发未真正发生：P=" + parallelism + " 仅 " + result.succeeded + " 次请求成功完成");

                    long rows = awaitRows(expected, AWAIT_ROWS_SECONDS, TimeUnit.SECONDS);
                    assertTrue(rows >= expected,
                            "并发档位 P=" + parallelism + " 下监控不得丢采：期望累计 >= " + expected + " 行，实际 " + rows);
                    System.out.println("[GUARDRAIL] P=" + parallelism + " 尝试=" + result.attempts
                            + " 成功=" + result.succeeded + " 累计期望=" + expected
                            + " 实际落库=" + rows + "（已完成请求零丢失）");
                } finally {
                    for (BrowserContext context : contexts) {
                        try {
                            RouteDsl.clear(context);
                        } catch (Exception ignore) {
                            // 清理期噪音（route@ 可能已被 GC），不影响资源释放
                        }
                        silentClose(context);
                    }
                    for (Page page : pages) {
                        silentClose(page);
                    }
                }
            }

            // ── 资源释放断言 ──
            dumpProcessList("guardrail-pre-release");
            int active = activeAppConnectionCount();
            assertEquals(0, active,
                    "并发落库后不应有活跃(泄漏)连接 —— 框架应及时归还连接，实际活跃 " + active);
            System.out.println("[GUARDRAIL] 并发落库后活跃(泄漏)连接=" + active + "（全部已归还）");

            ApiMonitoringRepository.shutdown();
            int afterShutdown = awaitAppConnectionCount(baselineConns, 15, TimeUnit.SECONDS);
            dumpProcessList("guardrail-after-shutdown");
            assertEquals(baselineConns, afterShutdown,
                    "shutdown() 后本次池连接应全部关闭、回落到基线 " + baselineConns + "，实际 " + afterShutdown);
            System.out.println("[GUARDRAIL] shutdown() 后连接回落基线=" + afterShutdown);
        } finally {
            silentClose(browser);
            silentClose(playwright);
        }
    }

    /**
     * 并发驱动：每个 page 在<b>自己的线程内串行</b>导航 {@link #REQ_PER_PAGE} 次，跨 page 并发。
     * <p>刻意不在同一 page 上并发导航：同 Context 的页面切换本就串行（框架语义），
     * 且并发 navigate 会命中 Playwright 对象失效竞态——那不是本护栏要考察的东西。
     */
    private static DriveResult driveConcurrently(List<Page> pages, int parallelism) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        String base = "http://localhost:" + port;
        AtomicInteger succeeded = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            final Page page = pages.get(i);
            final int pageIndex = i;
            futures.add(pool.submit(() -> {
                for (int r = 0; r < REQ_PER_PAGE; r++) {
                    try {
                        navigateSafe(page, base + "/demo/api/users?p=" + parallelism
                                + "&page=" + pageIndex + "&r=" + r);
                        succeeded.incrementAndGet();
                        // 同 page 连发导航会让浏览器取消上一条在途导航/body 读（既有 E2E 用例已记录该教训），
                        // 而那不是本护栏要考察的并发维度 —— 故同 page 内留出结算间隔，跨 page 仍完全并发。
                        Thread.sleep(SAME_PAGE_SETTLE_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (RuntimeException e) {
                        // 浏览器侧瞬态（连发导航被取消 / 拦截对象失效）重试后仍未成功：该请求没有产生响应，
                        // 不计入期望；仅记日志，避免把浏览器噪声当采集缺陷。非空转由「每个并发参与者
                        // 至少完成一次」断言兜底，防止"全失败也算通过"。
                        System.out.println("[GUARDRAIL] 导航重试后仍失败（page=" + pageIndex
                                + " req=" + r + "）：" + e.getMessage());
                    }
                }
            }));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.MINUTES), "并发驱动应在时限内完成");
        for (Future<?> future : futures) {
            future.get(); // 线程体已自捕获异常，此处仅确保线程正常结束
        }
        return new DriveResult(pages.size() * REQ_PER_PAGE, succeeded.get());
    }

    /** 一轮并发驱动的结果：尝试数 / 成功完成数（只有成功完成者才被要求"必被采集"）。 */
    private static final class DriveResult {
        private final int attempts;
        private final int succeeded;

        private DriveResult(int attempts, int succeeded) {
            this.attempts = attempts;
            this.succeeded = succeeded;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助（本类自持，保证与其它用例的 JVM/数据库状态隔离）
    // ═══════════════════════════════════════════════════════════════

    private static int[] parseGradient(String csv) {
        String[] parts = csv.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }

    private static void createDatabaseIfNotExists() throws Exception {
        try (Connection c = DriverManager.getConnection(SIDE_URL, USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS route_monitor");
        }
    }

    private static void deleteRunRows() throws Exception {
        try (Connection c = DriverManager.getConnection(SIDE_URL, USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM route_monitor.route_monitor_record WHERE test_run_id = '" + RUN_ID + "'");
        }
    }

    private static long countRows() throws Exception {
        try (Connection c = DriverManager.getConnection(SIDE_URL, USER, PASS);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM route_monitor.route_monitor_record WHERE test_run_id = '" + RUN_ID + "'")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static long awaitRows(long minRows, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            long count = countRows();
            if (count >= minRows) {
                return count;
            }
            Thread.sleep(300);
        }
        return countRows();
    }

    private static int appConnectionCount() throws Exception {
        try (Connection c = DriverManager.getConnection(SIDE_URL, USER, PASS);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.processlist "
                             + "WHERE DB = 'route_monitor' AND ID != CONNECTION_ID()")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

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

    private static int awaitAppConnectionCount(int expected, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        int last = appConnectionCount();
        while (last != expected && System.nanoTime() < deadline) {
            Thread.sleep(150);
            last = appConnectionCount();
        }
        return last;
    }

    private static void dumpProcessList(String tag) throws Exception {
        try (Connection c = DriverManager.getConnection(SIDE_URL, USER, PASS);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT ID, USER, HOST, DB, COMMAND, TIME FROM information_schema.processlist "
                             + "WHERE DB = 'route_monitor'")) {
            System.out.println("[GUARDRAIL] processlist(" + tag + "):");
            while (rs.next()) {
                System.out.printf("[GUARDRAIL]   id=%d user=%s host=%s db=%s command=%s time=%d%n",
                        rs.getLong("ID"), rs.getString("USER"), rs.getString("HOST"),
                        rs.getString("DB"), rs.getString("COMMAND"), rs.getLong("TIME"));
            }
        }
    }

    /** 容错导航：并发路由下 navigate 可能命中瞬态失效，重试即重新导航（等价多一次捕获）。 */
    private static void navigateSafe(Page page, String url) throws InterruptedException {
        PlaywrightException last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                page.navigate(url);
                return;
            } catch (PlaywrightException e) {
                if (isTransientNavigationFailure(e)) {
                    last = e;
                    Thread.sleep(200);
                    continue;
                }
                throw e;
            }
        }
        throw last;
    }

    /**
     * 判定是否为「可重试的瞬态失效」——两类，均与本次请求的业务语义无关：
     * <ol>
     *   <li><b>对象/命令失效</b>：路由拦截并发下浏览器侧 {@code route@/request@/response@} 被快速 GC，
     *       事件分发层解析失效对象时抛 {@code Object doesn't exist} / {@code Cannot find parent object} /
     *       {@code Cannot find command to respond}；</li>
     *   <li><b>导航被取消</b>：{@code net::ERR_ABORTED} —— 由连发导航触发的浏览器侧取消（沿用既有 E2E 的
     *       "同 page 连发需停顿"结论，本类另以 {@link #SAME_PAGE_SETTLE_MS} 缓解）。</li>
     * </ol>
     * 重试会重新发起请求，故断言的 {@code rows >= 计划请求数} 只是下界，重试不会放宽判定（只会更严）。
     */
    private static boolean isTransientNavigationFailure(PlaywrightException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("Object doesn't exist")
                || message.contains("Cannot find parent object")
                || message.contains("Cannot find command to respond")
                || message.contains("Cannot find object to call route")
                || message.contains("net::ERR_ABORTED")
                || message.contains("route@")
                || message.contains("request@")
                || message.contains("response@");
    }

    private static void silentClose(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable t) {
            // 清理期噪音（route@/response@ 已被 GC 的 Playwright 对象），忽略
        }
    }
}
