package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.MonitorConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureManager;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.hsbc.cmb.hk.dbb.automation.framework.route.util.PlaywrightSafeOps;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Route 独立性能 / 压力测试（真浏览器 + 真实拦截）。
 *
 * <p>目标：量化 Route 拦截链路（Monitor 采集：fetch → 捕获 → 落库）的开销与稳定性，
 * 并暴露<b>时序竞争</b>与<b>资源外泄</b>问题。数据源为 route-demo-service
 * （{@code http://localhost:8888/demo}）的 {@code /api/perf/*} 端点。
 *
 * <p><b>启用</b>：本套件为<b>显式 opt-in</b>，默认跳过（性能压测不门禁日常护盾）——
 * 需 {@code -Droute.perf.enabled=true} <b>且</b> route-demo-service 已启动
 * （{@code mvn -o -f route-demo-service/pom.xml spring-boot:run}）时才运行。
 *
 * <p><b>压测档位</b>（真并发，验证框架在拦截/改写/采集三路径下的稳定性与开销）：
 * <ul>
 *   <li>拦截/改写链路在 50 并发真并发下稳定（{@code modify} 场景）；</li>
 *   <li>monitor 采集同样验证 50 并发稳定：框架已将 {@code res.body()}（CDP 往返）收敛到独立的
 *       小并发池（{@code monitor.body.read.concurrency}，默认 2），避免高并发下打爆单 CDP 连接；</li>
 *   <li>OOM 守门场景自适应越过 50MB 守门，验证守门触发后<b>响应本身不受影响</b>。</li>
 * </ul>
 * <ul>
 *   <li>{@link #CONCURRENCY} = 50 并发（场景一，与 modify 同档）；</li>
 *   <li>场景一：50 × 256KB = 12.5MB（守门之下，全部应被捕获）；</li>
 *   <li>场景二：自适应越过响应总量守门（默认 50MB）—— 按守门值倒推报文/数量，验证守门触发后响应仍完整。</li>
 * </ul>
 *
 * <p><b>为什么必须先导航再注册规则</b>：页面需处于 demo-service 源（localhost:8888）下，
 * fetch 才是同源、可读响应体；导航本身不计入压测量，故在注册规则之前完成。
 */
public class RoutePerformanceStressTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoutePerformanceStressTest.class);

    private static final String BASE = "http://localhost:8888/demo";
    private static final String ORIGIN_URL = BASE + "/api/perf/headers";
    private static final String PATTERN = "/demo/api/perf/**";

    /** 场景一并发：与 modify 同档 50。monitor 的 body 读取已收敛到独立小并发池，不再打爆单 CDP 连接。 */
    private static final int CONCURRENCY = 50;

    /** 场景一单请求响应体大小（KB）。 */
    private static final int PAYLOAD_KB_SMALL = 256;

    /**
     * 并发抓取脚本：在页面上下文内并发 fetch，返回总耗时与每请求的 {status, len, ms}。
     * <p>{@code readBody=false} 时只读状态码（大报文场景避免浏览器侧额外内存开销）。
     */
    private static final String BURST_SCRIPT =
            "async (a) => {\n"
            + "  const t0 = performance.now();\n"
            + "  const tasks = [];\n"
            + "  for (let i = 0; i < a.n; i++) {\n"
            + "    const ts = performance.now();\n"
            + "    tasks.push(\n"
            + "      fetch(a.url + (a.url.indexOf('?') >= 0 ? '&' : '?') + 'i=' + i + '&t=' + Date.now())\n"
            + "        .then(async r => {\n"
            + "          const len = a.readBody ? (await r.text()).length : -1;\n"
            + "          return {s: r.status, len: len, ms: performance.now() - ts};\n"
            + "        })\n"
            + "        .catch(e => ({s: -1, len: -1, ms: performance.now() - ts, err: String(e)}))\n"
            + "    );\n"
            + "  }\n"
            + "  const results = await Promise.all(tasks);\n"
            + "  return {totalMs: performance.now() - t0, results: results};\n"
            + "}\n";

    /**
     * MODIFY 并发探针脚本：并发 fetch 并返回每请求状态码与<b>响应体原文</b>，
     * 用于校验「改写后的请求头是否真的到达服务端」。
     */
    private static final String MODIFY_PROBE_SCRIPT =
            "async (a) => {\n"
            + "  const t0 = performance.now();\n"
            + "  const tasks = [];\n"
            + "  for (let i = 0; i < a.n; i++) {\n"
            + "    tasks.push(\n"
            + "      fetch(a.url + (a.url.indexOf('?') >= 0 ? '&' : '?') + 'i=' + i)\n"
            + "        .then(async r => ({s: r.status, body: await r.text()}))\n"
            + "        .catch(e => ({s: -1, body: 'ERR:' + e}))\n"
            + "    );\n"
            + "  }\n"
            + "  const results = await Promise.all(tasks);\n"
            + "  return {totalMs: performance.now() - t0, results: results};\n"
            + "}\n";

    private static Playwright pw;
    private static Browser browser;

    private BrowserContext context;
    private Page page;

    @BeforeAll
    public static void launch() {
        //  性能压测为「显式 opt-in」：默认跳过，避免把标准护盾绑定在「live 服务 + 高负载机器」上。
        //  本套件驱动 50~100 并发真并发 fetch，Playwright 驱动层在重负载下会偶发 response@/route@ 对象
        //  GC 竞态（"Object doesn't exist" / "Cannot find parent object request@ … to create route@"），
        //  其结果只在线性受控环境（专用 CI）才有意义，不应门禁日常构建。
        //  显式启用：mvn ... -Droute.perf.enabled=true（且 route-demo-service 需已启动）。
        Assumptions.assumeTrue(Boolean.getBoolean("route.perf.enabled"),
                "性能压测默认跳过；以 -Droute.perf.enabled=true 显式启用（需先启动 route-demo-service）");
        //  外部依赖前置：压测数据源 route-demo-service 需先启动（未启动亦跳过，避免环境问题拖红护盾）。
        Assumptions.assumeTrue(isDemoServiceUp(),
                "route-demo-service 未启动（" + ORIGIN_URL + "），跳过 Route 性能压测");
        pw = Playwright.create();
        browser = pw.chromium().launch();
        //  高 churn 护盾：本套件 100 并发真压测会令浏览器侧 response@ 对象被快速 GC，
        //  若开启全局 onResponse 兜底被动捕获，Playwright 在事件分发层解析失效 response@ 时会抛
        //  "Object doesn't exist" 并污染同连接在途的 page.evaluate（压测脚本）。关闭被动捕获从根上消除该
        //  race；已注册流量仍由 MonitorHandler 的 waitForResponse 通道独立采集，本套件断言不受影响。
        ApiCaptureManager.setPassthroughEnabled(false);
    }

    /** 探测 demo-service 是否可达（短超时，避免 CI 长时间阻塞）。 */
    private static boolean isDemoServiceUp() {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) java.net.URI.create(ORIGIN_URL).toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return code < 500;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @AfterAll
    public static void shutdown() {
        //  try/finally 保证：无论路径如何，被动捕获开关必复位、浏览器资源必释放——避免连接损坏时
        //  browser.close() 抛 "Cannot find command to respond" 阻断 pw.close() 导致 Playwright 进程泄漏。
        try {
            //  复位被动捕获开关，避免影响同 JVM 内其它测试（surefire 默认并行 fork 隔离，此处仍显式复位）
            ApiCaptureManager.setPassthroughEnabled(true);
            //  显式清空各 Context 采集存储，即时释放内存（兜底于 WeakHashMap 自动回收之外）
            ApiCaptureManager.getInstance().clearAllContexts();
        } finally {
            silentClose("browser", browser);
            silentClose("playwright", pw);
            browser = null;
            pw = null;
        }
    }

    /** 静默关闭单个可关闭资源；关闭异常（连接已损坏等）记日志但不外抛，保证后续资源仍被释放。 */
    private static void silentClose(String what, AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable t) {
            LOGGER.warn("[ROUTE-PERF] teardown: {} close failed (resource still released): {}", what, t.toString());
        }
    }

    @BeforeEach
    public void setup() {
        context = browser.newContext();
        page = context.newPage();
        // 先进入 demo-service 源（同源才能读响应体），该导航不计入压测量
        page.navigate(ORIGIN_URL);
    }

    @AfterEach
    public void teardown() {
        try {
            if (context != null) RouteDsl.clear(context);
        } catch (Exception ignore) {
            // 清理失败不应影响其它用例
        }
        try {
            if (context != null) context.close();
        } catch (Exception ignore) {
            // 同上
        }
    }

    /**
     * 场景一：20 并发 × 256KB（5MB），全部应 200 且响应体长度正确，输出 P50/P95/吞吐。
     */
    @Test
    public void monitor_highConcurrency_largePayload() {
        RouteDsl.on(page).api(PATTERN).monitor().record(true).done().start();

        BurstResult r = burst(page, BASE + "/api/perf/large-json?sizeKb=" + PAYLOAD_KB_SMALL,
                CONCURRENCY, true);

        // 捕获是异步的（观测/落库在框架线程），需等待其落库后再断言，否则读到 0（测试竞态，非框架问题）
        awaitCapturesSettled(8000);
        int captured = ApiCaptureContext.getCurrent().getTotalResponseCount();
        LOGGER.info("[ROUTE-PERF] scenario1 monitor: n={}, payloadKB={}, ok={}/nonOk={}, "
                        + "totalMs={}, p50Ms={}, p95Ms={}, maxMs={}, throughput={} req/s, bytes={}, captured={}",
                CONCURRENCY, PAYLOAD_KB_SMALL, r.okCount, r.nonOkCount,
                fmt(r.totalMs), fmt(r.p50), fmt(r.p95), fmt(r.max),
                fmt(throughputPerSec(r)), r.totalBytes, captured);

        assertTrue(captured > 0, "规则应真实拦截并采集到请求（否则后续性能数据无意义）");

        assertEquals(CONCURRENCY, r.okCount, "50 并发大报文下不应有失败请求");
        assertEquals(0, r.nonOkCount, "不应出现非 200 响应");
        assertTrue(r.totalBytes >= (long) CONCURRENCY * PAYLOAD_KB_SMALL * 1024, "每个响应体应接近设定大小");
    }

    /**
     * 场景二：越过响应总量守门（{@code api.capture.max.response.size.mb}，默认 50MB）。
     * <b>自适应选档</b>：守门为静态常量（类加载时读一次），故按守门值倒推报文与数量——
     * 守门小（如测试注入 2MB）则用 256KB 小报文低并发快速越过；守门大（默认 50MB）则用 4.5MB
     * （&lt;5MB 不被截断）大报文以少量请求越过，避免堆叠成高并发打爆单 CDP 连接。
     *
     * <p>验证：<b>守门触发后响应本身仍必须完整返回</b>（守门只丢弃"捕获"存储，不得破坏请求）。
     * 断言同时校验①全部 200（响应完整）②{@code captured < sent}（守门确实丢弃了超出配额的捕获）。
     */
    @Test
    public void monitor_beyondOomGuard_responsesStillIntact() {
        RouteDsl.on(page).api(PATTERN).monitor().record(true).done().start();

        long guardMb = MonitorConfig.getLong(MonitorConfig.API_CAPTURE_MAX_RESPONSE_SIZE_MB);
        // 小守门 → 小报文快跑；大守门 → 非截断大报文(4.5MB)以少量请求越过。
        int payloadKb = guardMb <= 5L ? 256 : 4608;
        // 越过守门至少 1MB，并留 2 个余量；并发封顶 30 避免连接饱和。
        int count = (int) Math.ceil((guardMb + 1L) * 1024.0 / payloadKb) + 2;
        count = Math.min(count, 30);

        BurstResult r = burst(page, BASE + "/api/perf/large-json?sizeKb=" + payloadKb, count, false);

        awaitCapturesSettled(8000); // 等待异步捕获/守门判定落库
        int captured = ApiCaptureContext.getCurrent().getTotalResponseCount();
        LOGGER.info("[ROUTE-PERF] scenario2 oom-guard: n={}, payloadKB={}, guardMB={}, ok={}/nonOk={}, "
                        + "captured={}, totalMs={}",
                count, payloadKb, guardMb, r.okCount, r.nonOkCount, captured, fmt(r.totalMs));

        assertEquals(count, r.okCount, "越过 OOM 守门后响应本身仍须全部成功");
        assertEquals(0, r.nonOkCount, "守门不得把请求打失败");
        assertTrue(captured > 0, "至少有部分响应被捕获");
        assertTrue(captured < count, "守门应已触发并丢弃超出配额的捕获（验证守门生效）");
    }

    /**
     * 泄漏检查：压测 + 正常收尾后，ApiCaptureContext 的 Context 计数必须回到基线。
     * 若关闭流程有遗漏（BY_CONTEXT / contextStores 残留），计数会单调增长。
     */
    @Test
    public void noLeak_contextCountReturnsToBaseline() {
        int baseline = ApiCaptureContext.contextCount();

        for (int round = 0; round < 3; round++) {
            RouteDsl.on(page).api(PATTERN).monitor().record(true).done().start();
            burst(page, BASE + "/api/perf/large-json?sizeKb=64", 30, false);
            RouteDsl.clear(context);
            ApiCaptureContext.removeContext(context);
        }

        int after = ApiCaptureContext.contextCount();
        LOGGER.info("[ROUTE-PERF] leak-check: contextCount baseline={}, after3Rounds={}", baseline, after);

        assertTrue(after <= baseline + 1, "压测 3 轮后 Context 计数不应增长（疑似 BY_CONTEXT/contextStores 残留泄漏）");
    }

    /**
     * MODIFY 并发探针：50 并发改写请求头，校验 <b>每个请求都被改写且无失败/挂起</b>。
     *
     * <p>这是并发下最可能出现<b>时序竞争</b>的路径：MODIFY 经 {@code page.waitForResponse}
     * 观测真实响应，而并发请求共享同一 literal-path 谓词 —— 若观测错配到「别人的响应」或超时，
     * 就会表现为改写丢失 / 请求挂起。本探针把该风险显式暴露为可断言的指标。
     */
    @Test
    public void modify_highConcurrency_headerRewriteStillApplied() {
        final int n = 50;
        RouteDsl.on(page).api(PATTERN).modifyRequest()
                .setRequestHeaders(Map.of("X-Perf", "STRESS")).done().start();

        Map<String, Object> args = new HashMap<>();
        args.put("url", BASE + "/api/perf/headers");
        args.put("n", n);

        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) PlaywrightSafeOps.safeEvaluate(page, MODIFY_PROBE_SCRIPT, args);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) res.get("results");

        int ok = 0;
        int rewritten = 0;
        for (Map<String, Object> m : results) {
            int status = ((Number) m.get("s")).intValue();
            String body = String.valueOf(m.get("body"));
            if (status == 200) {
                ok++;
                // 头名服务端已转小写，头值保留原大小写 → 统一忽略大小写比对
                String lower = body.toLowerCase();
                if (lower.contains("x-perf") && lower.contains("stress")) {
                    rewritten++;
                }
            } else {
                LOGGER.warn("[ROUTE-PERF] modify probe non-200: status={}, body={}", status, body);
            }
        }

        LOGGER.info("[ROUTE-PERF] scenario3 modify: n={}, ok={}, headerRewritten={}, totalMs={}",
                n, ok, rewritten, fmt(((Number) res.get("totalMs")).doubleValue()));

        assertEquals(n, ok, "MODIFY 并发下不应有请求失败或挂起");
        assertEquals(n, rewritten, "MODIFY 并发下每个请求都应被改写（waitForResponse 观测错配会在此暴露）");
    }

    // ═══════════════════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════════════════

    /** 在页面上下文内并发抓取，解析为 {@link BurstResult}。 */
    @SuppressWarnings("unchecked")
    private static BurstResult burst(Page p, String url, int n, boolean readBody) {
        Map<String, Object> args = new HashMap<>();
        args.put("url", url);
        args.put("n", n);
        args.put("readBody", readBody);

        Map<String, Object> res = (Map<String, Object>) PlaywrightSafeOps.safeEvaluate(p, BURST_SCRIPT, args);
        double totalMs = ((Number) res.get("totalMs")).doubleValue();
        List<Map<String, Object>> results = (List<Map<String, Object>>) res.get("results");

        List<Double> times = new ArrayList<>();
        int ok = 0;
        int nonOk = 0;
        long bytes = 0L;
        for (Map<String, Object> m : results) {
            int status = ((Number) m.get("s")).intValue();
            if (status == 200) {
                ok++;
            } else {
                nonOk++;
                LOGGER.warn("[ROUTE-PERF] non-200 response: status={}, err={}", status, m.get("err"));
            }
            long len = ((Number) m.get("len")).longValue();
            if (len > 0) bytes += len;
            times.add(((Number) m.get("ms")).doubleValue());
        }
        Collections.sort(times);
        return new BurstResult(totalMs, ok, nonOk, bytes,
                percentile(times, 50), percentile(times, 95),
                times.isEmpty() ? 0d : times.get(times.size() - 1));
    }

    private static double percentile(List<Double> sorted, int pct) {
        if (sorted.isEmpty()) return 0d;
        int idx = (int) Math.ceil(sorted.size() * pct / 100.0) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }

    private static double throughputPerSec(BurstResult r) {
        return r.totalMs <= 0 ? 0d : r.okCount / (r.totalMs / 1000d);
    }

    /**
     * 等待框架异步捕获落库：采集/守门判定在框架线程完成，burst 返回时可能仍在飞行。
     * 轮询 {@code getTotalResponseCount()}，直到连续 300ms 不再增长或超时，避免读到 0 的测试竞态。
     */
    private static void awaitCapturesSettled(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int last = -1;
        long stableFor = 0;
        while (System.currentTimeMillis() < deadline) {
            int now = ApiCaptureContext.getCurrent().getTotalResponseCount();
            if (now == last) {
                stableFor += 20;
                if (stableFor >= 300) {
                    return;
                }
            } else {
                stableFor = 0;
            }
            last = now;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String fmt(double d) {
        return String.format("%.1f", d);
    }

    /** 一轮压测的聚合结果。 */
    private static final class BurstResult {
        final double totalMs;
        final int okCount;
        final int nonOkCount;
        final long totalBytes;
        final double p50;
        final double p95;
        final double max;

        BurstResult(double totalMs, int okCount, int nonOkCount, long totalBytes,
                    double p50, double p95, double max) {
            this.totalMs = totalMs;
            this.okCount = okCount;
            this.nonOkCount = nonOkCount;
            this.totalBytes = totalBytes;
            this.p50 = p50;
            this.p95 = p95;
            this.max = max;
        }
    }
}
