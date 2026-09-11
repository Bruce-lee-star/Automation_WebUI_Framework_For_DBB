package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.MonitorConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;

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
 * <p><b>前置</b>：route-demo-service 已启动（{@code mvn -o -f route-demo-service/pom.xml spring-boot:run}）。
 *
 * <p><b>压测档位</b>（越大越好，故取大档）：
 * <ul>
 *   <li>{@link #CONCURRENCY} = 100 并发（页面内 {@code Promise.all} 真并发）；</li>
 *   <li>场景一：100 × 256KB = 25MB（守门之下，全部应被捕获）；</li>
 *   <li>场景二：60 × 1MB = 60MB（越过 50MB 响应总量守门，验证守门触发后<b>响应本身不受影响</b>）。</li>
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

    /** 并发请求数（页面内真并发）。 */
    private static final int CONCURRENCY = 100;

    /** 场景一单请求响应体大小（KB）→ 总 25MB。 */
    private static final int PAYLOAD_KB_SMALL = 256;

    /** 场景二单请求响应体大小（KB）与请求数 → 总 60MB，越过 50MB 守门。 */
    private static final int PAYLOAD_KB_LARGE = 1024;
    private static final int LARGE_COUNT = 60;

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
        //  外部依赖前置：压测数据源 route-demo-service 需先启动。
        //  服务不可达时"跳过"而非"失败"，避免把整条护盾拖红（CI 未起服务属环境问题，非框架缺陷）。
        Assumptions.assumeTrue(isDemoServiceUp(),
                "route-demo-service 未启动（" + ORIGIN_URL + "），跳过 Route 性能压测");
        pw = Playwright.create();
        browser = pw.chromium().launch();
    }

    /** 探测 demo-service 是否可达（短超时，避免 CI 长时间阻塞）。 */
    private static boolean isDemoServiceUp() {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(ORIGIN_URL).openConnection();
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
        if (browser != null) browser.close();
        if (pw != null) pw.close();
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
     * 场景一：100 并发 × 256KB（25MB），全部应 200 且响应体长度正确，输出 P50/P95/吞吐。
     */
    @Test
    public void monitor_highConcurrency_largePayload() {
        RouteDsl.on(page).api(PATTERN).monitor().record(true).done().start();

        BurstResult r = burst(page, BASE + "/api/perf/large-json?sizeKb=" + PAYLOAD_KB_SMALL,
                CONCURRENCY, true);

        int captured = ApiCaptureContext.getCurrent().getTotalResponseCount();
        LOGGER.info("[ROUTE-PERF] scenario1 monitor: n={}, payloadKB={}, ok={}/nonOk={}, "
                        + "totalMs={}, p50Ms={}, p95Ms={}, maxMs={}, throughput={} req/s, bytes={}, captured={}",
                CONCURRENCY, PAYLOAD_KB_SMALL, r.okCount, r.nonOkCount,
                fmt(r.totalMs), fmt(r.p50), fmt(r.p95), fmt(r.max),
                fmt(throughputPerSec(r)), r.totalBytes, captured);

        assertTrue(captured > 0, "规则应真实拦截并采集到请求（否则后续性能数据无意义）");

        assertEquals(CONCURRENCY, r.okCount, "100 并发大报文下不应有失败请求");
        assertEquals(0, r.nonOkCount, "不应出现非 200 响应");
        assertTrue(r.totalBytes >= (long) CONCURRENCY * PAYLOAD_KB_SMALL * 1024, "每个响应体应接近设定大小");
    }

    /**
     * 场景二：60 × 1MB = 60MB，越过响应总量守门（默认 50MB）。
     * 验证：<b>守门触发后响应本身仍必须完整返回</b>（守门只丢弃"捕获"，不得破坏请求）。
     */
    @Test
    public void monitor_beyondOomGuard_responsesStillIntact() {
        RouteDsl.on(page).api(PATTERN).monitor().record(true).done().start();

        long guardMb = MonitorConfig.getLong(MonitorConfig.API_CAPTURE_MAX_RESPONSE_SIZE_MB);
        BurstResult r = burst(page, BASE + "/api/perf/large-json?sizeKb=" + PAYLOAD_KB_LARGE,
                LARGE_COUNT, false);

        LOGGER.info("[ROUTE-PERF] scenario2 oom-guard: n={}, payloadKB={}, guardMB={}, ok={}/nonOk={}, "
                        + "totalMs={}, p50Ms={}, p95Ms={}, maxMs={}",
                LARGE_COUNT, PAYLOAD_KB_LARGE, guardMb, r.okCount, r.nonOkCount,
                fmt(r.totalMs), fmt(r.p50), fmt(r.p95), fmt(r.max));

        assertEquals(LARGE_COUNT, r.okCount, "越过 OOM 守门后响应本身仍须全部成功");
        assertEquals(0, r.nonOkCount, "守门不得把请求打失败");
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
        Map<String, Object> res = (Map<String, Object>) page.evaluate(MODIFY_PROBE_SCRIPT, args);

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

        Map<String, Object> res = (Map<String, Object>) p.evaluate(BURST_SCRIPT, args);
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
