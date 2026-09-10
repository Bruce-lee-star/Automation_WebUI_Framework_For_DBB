package com.hsbc.cmb.hk.dbb.automation.tests.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.hsbc.cmb.hk.dbb.automation.tests.utils.RouteDemoCoverageApi;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import net.serenitybdd.annotations.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Route DSL 方法 100% 覆盖步骤 —— 配合增强版 demo 服务 route-demo-web（端口 8899，context-path /web）。
 *
 * <p>route-demo-service(:8888) 只能产生 fetch JSON 这一种请求形态，无法覆盖
 * xhr / image / script / iframe / 自定义头 / body 正则 / referrer 等匹配维度。
 * route-demo-web 通过 {@code coverage.html}（含隐藏 iframe）制造全部请求形态，使下列此前未覆盖的 DSL 方法可被测试：
 * <ul>
 *   <li>Modify：setRequestHeaders(Map)、modifyMethod</li>
 *   <li>Mock：mockBodyFromFile、mockBodyFromFile(map)、mockHeader、mockStatus、replaceField、replaceFields</li>
 *   <li>条件匹配：resourceType、onlyXhr、onlyFetch、onlyApi、matchHeader、matchQuery、matchBodyRegex、
 *       matchContentType、matchReferrer、matchOrigin、matchFrameUrl、onlyMainFrame、allowAllFrames、onlyApiCall、allowAllRequests</li>
 *   <li>Delay：randomDelay</li>
 *   <li>一次性：times（集中覆盖）</li>
 *   <li>Monitor：onResponse（对照覆盖）</li>
 * </ul>
 *
 * <p>前置：route-demo-web 已在 http://localhost:8899 启动（mvn -f route-demo-web/pom.xml spring-boot:run）。
 */
public class RouteDemoCoverageSteps extends RouteDemoServiceSteps {

    private static final Logger logger = LoggerFactory.getLogger(RouteDemoCoverageSteps.class);

    private static final String BASE = "http://localhost:8899/web/api";
    private static final String ORIGIN = "http://localhost:8899/web/coverage.html";
    private static final String IMG_URL = "http://localhost:8899/web/api/img";
    private static final String SCRIPT_URL = "http://localhost:8899/web/api/script.js";
    private static final String IFRAME_ECHO = "/web/api/iframe-echo";

    private static final long CAPTURE_TIMEOUT_MS = 8000L;

    // ───────────────────────── 装配 / 辅助 ─────────────────────────

    @Override
    protected void openOrigin(Page p) {
        if (p.url() != null && p.url().startsWith("http://localhost:8899")) {
            p.reload();
        } else {
            p.navigate(ORIGIN);
        }
    }

    /**
     * 每个 Scenario 后清理 —— feature 模式下 context 不重建，必须把注册在 {@code page.context()} 上的
     * 路由真正注销，否则会泄漏到下一 Scenario。{@link RouteDsl#clear(BrowserContext)} 的 key 与
     * {@code RouteDsl.on(Page)} 的注册 key 一致，能彻底 unroute + 清 MonitorSession + 清采集上下文。
     */
    @Override
    @Step
    public void cleanup() {
        try {
            RouteDsl.clear(page().context());
        } catch (Exception ignored) {
            // 某些场景未注册规则，clear 允许空操作
        }
    }

    private ApiCaptureContext ctx() {
        return ApiCaptureContext.forContext(page().context());
    }

    private CapturedApiCall waitForCapturedByType(String urlContains, RouteHandleType type) {
        long deadline = System.currentTimeMillis() + CAPTURE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            for (CapturedApiCall c : ctx().getAllByType(type)) {
                String url = c.requestUrl() != null ? c.requestUrl() : "";
                if (url.contains(urlContains)) return c;
            }
            sleep(100);
        }
        return null;
    }

    private int countByType(String urlContains, RouteHandleType type) {
        int n = 0;
        for (CapturedApiCall c : ctx().getAllByType(type)) {
            String url = c.requestUrl() != null ? c.requestUrl() : "";
            if (url.contains(urlContains)) n++;
        }
        return n;
    }

    private void assertNeverCaptured(String urlContains, RouteHandleType type, String reason) {
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            if (countByType(urlContains, type) > 0) fail(reason);
            sleep(100);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 向 /web/api/echo 发起请求并解析回显 JSON {status, body, headers}。 */
    private JsonNode probe(String method, Map<String, String> headers, String body, String referrer) {
        String json = RouteDemoCoverageApi.request(page(), BASE + "/echo", method, headers, body, referrer);
        return assertJson(json);
    }

    private JsonNode bodyOf(JsonNode probe) {
        return assertJson(probe.get("body").asText());
    }

    // ───────────────────────── Modify（未覆盖部分） ─────────────────────────

    @Step
    public void modifySetRequestHeadersMap() {
        RouteDsl.on(page()).api("/web/api/echo").modifyRequest()
                .setRequestHeaders(Map.of("X-Cov", "V1", "X-Cov2", "V2")).done().start();
        openOrigin(page());
        JsonNode p = probe("POST", Map.of("Content-Type", "application/json"), "{\"k\":1}", null);
        // request() 返回包装 {status,body,headers}：body 即 echo 回显 JSON，其中 headers 为服务端收到的请求头（已转小写）
        JsonNode echo = bodyOf(p);
        JsonNode h = echo.get("headers");
        assertEquals("V1", h.get("x-cov").asText());
        assertEquals("V2", h.get("x-cov2").asText());
    }

    @Step
    public void modifyMethodChangesOutgoing() {
        RouteDsl.on(page()).api("/web/api/echo").modifyRequest().modifyMethod("PUT").done().start();
        openOrigin(page());
        JsonNode p = probe("GET", null, null, null);
        // method 在 echo 回显 JSON 内（包装的 body 字段），不在包装层
        assertEquals("PUT", bodyOf(p).get("method").asText());
    }

    // ───────────────────────── Mock（未覆盖部分） ─────────────────────────

    @Step
    public void mockBodyFromFileCoverage() {
        RouteDsl.on(page()).api("/web/api/echo").mock()
                .mockBodyFromFile("mocks/coverage/users.json").done().start();
        openOrigin(page());
        String raw = RouteDemoCoverageApi.request(page(), BASE + "/echo", "GET", null, null, null);
        // request() 返回包装 {status,body,headers}，body 才是 mock 文件内容
        JsonNode n = bodyOf(assertJson(raw));
        assertEquals("MockedFromFile", n.get("name").asText());
    }

    @Step
    public void mockBodyFromFileWithOverrides() {
        RouteDsl.on(page()).api("/web/api/echo").mock()
                .mockBodyFromFile("mocks/coverage/users.json", Map.of("$.name", "Overridden")).done().start();
        openOrigin(page());
        String raw = RouteDemoCoverageApi.request(page(), BASE + "/echo", "GET", null, null, null);
        JsonNode n = bodyOf(assertJson(raw));
        assertEquals("Overridden", n.get("name").asText());
    }

    @Step
    public void mockHeaderCoverage() {
        RouteDsl.on(page()).api("/web/api/echo").mock().mockBody("{}")
                .mockHeader("X-Demo", "yes").done().start();
        openOrigin(page());
        JsonNode p = probe("GET", null, null, null);
        assertEquals("yes", p.get("headers").get("x-demo").asText());
    }

    @Step
    public void mockStatusCoverage() {
        RouteDsl.on(page()).api("/web/api/echo").mock().mockStatus(201).mockBody("{\"ok\":true}").done().start();
        openOrigin(page());
        JsonNode p = probe("GET", null, null, null);
        assertEquals(201, p.get("status").asInt());
    }

    @Step
    public void replaceFieldCoverage() {
        RouteDsl.on(page()).api("/web/api/echo").mock().mockBody("{\"a\":1,\"b\":2}")
                .replaceField("$.a", 9).done().start();
        openOrigin(page());
        JsonNode p = probe("GET", null, null, null);
        assertEquals(9, bodyOf(p).get("a").asInt());
    }

    @Step
    public void replaceFieldsCoverage() {
        RouteDsl.on(page()).api("/web/api/echo").mock().mockBody("{\"a\":1,\"b\":2}")
                .replaceFields(Map.of("$.a", 3, "$.b", 4)).done().start();
        openOrigin(page());
        JsonNode p = probe("GET", null, null, null);
        JsonNode b = bodyOf(p);
        assertEquals(3, b.get("a").asInt());
        assertEquals(4, b.get("b").asInt());
    }

    // ───────────────────────── Delay ─────────────────────────

    @Step
    public void randomDelayCoverage() {
        RouteDsl.on(page()).api("/web/api/echo").delay(3).randomDelay(1, 2).done().start();
        openOrigin(page());
        long start = System.currentTimeMillis();
        RouteDemoCoverageApi.request(page(), BASE + "/echo", "GET", null, null, null);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("randomDelay(1,2) 应 >= 900ms，实际=" + elapsed, elapsed >= 900);
        assertTrue("randomDelay(1,2) 应 <= 3200ms，实际=" + elapsed, elapsed <= 3200);
    }

    // ───────────────────────── 一次性 times（集中覆盖） ─────────────────────────

    @Step
    public void timesOneShotCoverage() {
        RouteDsl.on(page()).api("/web/api/echo").mock().mockBody("ONCE").times(1).done().start();
        openOrigin(page());
        String first = RouteDemoCoverageApi.request(page(), BASE + "/echo", "GET", null, null, null);
        assertTrue(first.contains("ONCE"));
        String second = RouteDemoCoverageApi.request(page(), BASE + "/echo", "GET", null, null, null);
        assertFalse("第 2 次应放行真实请求（不经过 mock）", second.contains("ONCE"));
    }

    // ───────────────────────── 条件匹配（全 16 个） ─────────────────────────

    @Step
    public void condMatchMethod() {
        RouteDsl.on(page()).api("/web/api/echo").mock().mockBody("MATCHED").matchMethod("POST").done().start();
        openOrigin(page());
        String post = RouteDemoCoverageApi.request(page(), BASE + "/echo", "POST",
                Map.of("Content-Type", "application/json"), "{}", null);
        assertTrue(post.contains("MATCHED"));
        String get = RouteDemoCoverageApi.request(page(), BASE + "/echo", "GET", null, null, null);
        assertFalse(get.contains("MATCHED"));
    }

    @Step
    public void condResourceType() {
        RouteDsl.on(page()).api("/web/api/img").mock().mockBody("IMG_MOCK").resourceType("image").done().start();
        openOrigin(page());
        RouteDemoCoverageApi.loadImage(page(), IMG_URL);
        assertNotNull("resourceType(image) 应拦截图片请求", waitForCapturedByType("/web/api/img", RouteHandleType.MOCK));
    }

    @Step
    public void condResourceTypeScript() {
        RouteDsl.on(page()).api("/web/api/script.js").mock().mockBody("SCRIPT_MOCK").resourceType("script").done().start();
        openOrigin(page());
        RouteDemoCoverageApi.loadScript(page(), SCRIPT_URL);
        assertNotNull("resourceType(script) 应拦截脚本请求", waitForCapturedByType("/web/api/script.js", RouteHandleType.MOCK));
    }

    @Step
    public void condOnlyXhr() {
        RouteDsl.on(page()).api("/web/api/echo").mock().mockBody("XHR_OK").onlyXhr().done().start();
        openOrigin(page());
        // mockBody 是纯字符串（非 JSON），不能再用 bodyOf 解析；直接从包装的 body 字段取原始串
        String xhr = RouteDemoCoverageApi.xhr(page(), BASE + "/echo", "GET", null, null);
        assertEquals("XHR_OK", assertJson(xhr).get("body").asText());
        String fetch = RouteDemoCoverageApi.request(page(), BASE + "/echo", "GET", null, null, null);
        assertFalse("onlyXhr 不应匹配 fetch", fetch.contains("XHR_OK"));
    }

    @Step
    public void condOnlyFetch() {
        RouteDsl.on(page()).api("/web/api/echo").mock().mockBody("FETCH_OK").onlyFetch().done().start();
        openOrigin(page());
        String fetch = RouteDemoCoverageApi.request(page(), BASE + "/echo", "GET", null, null, null);
        assertTrue(fetch.contains("FETCH_OK"));
        String xhr = RouteDemoCoverageApi.xhr(page(), BASE + "/echo", "GET", null, null);
        // xhr 不应命中 onlyFetch → 走真实 echo，body 为 echo JSON，必不含 "FETCH_OK"
        assertFalse("onlyFetch 不应匹配 xhr", assertJson(xhr).get("body").asText().contains("FETCH_OK"));
    }

    @Step
    public void condOnlyApi() {
        RouteDsl.on(page()).api("/web/api/echo").mock().mockBody("API_OK").onlyApi().done().start();
        openOrigin(page());
        assertTrue(RouteDemoCoverageApi.request(page(), BASE + "/echo", "GET", null, null, null).contains("API_OK"));
        assertTrue(RouteDemoCoverageApi.xhr(page(), BASE + "/echo", "GET", null, null).contains("API_OK"));
    }

    @Step
    public void condMatchHeader() {
        // 注意：Playwright 把请求头名统一小写，故匹配 key 必须用 "x-match"
        RouteDsl.on(page()).api("/web/api/echo").mock().mockBody("HDR_OK").matchHeader("x-match", "yes").done().start();
        openOrigin(page());
        String with = RouteDemoCoverageApi.request(page(), BASE + "/echo", "GET",
                Map.of("x-match", "yes"), null, null);
        assertTrue(with.contains("HDR_OK"));
        String without = RouteDemoCoverageApi.request(page(), BASE + "/echo", "GET", null, null, null);
        assertFalse(without.contains("HDR_OK"));
    }

    @Step
    public void condMatchQuery() {
        RouteDsl.on(page()).api("/web/api/echo").mock().mockBody("Q_OK").matchQuery("role", "ADMIN").done().start();
        openOrigin(page());
        String with = RouteDemoCoverageApi.request(page(), BASE + "/echo?role=ADMIN", "GET", null, null, null);
        assertTrue(with.contains("Q_OK"));
        String without = RouteDemoCoverageApi.request(page(), BASE + "/echo?role=USER", "GET", null, null, null);
        assertFalse(without.contains("Q_OK"));
    }

    @Step
    public void condMatchBodyRegex() {
        // ApiMatcher 用 matcher(body).matches()（全字符串匹配），故正则须用 .*ADMIN.*
        RouteDsl.on(page()).api("/web/api/echo").mock().mockBody("BODY_OK").matchBodyRegex(".*ADMIN.*").done().start();
        openOrigin(page());
        String with = RouteDemoCoverageApi.request(page(), BASE + "/echo", "POST",
                Map.of("Content-Type", "application/json"), "{\"role\":\"ADMIN\"}", null);
        assertTrue(with.contains("BODY_OK"));
        String without = RouteDemoCoverageApi.request(page(), BASE + "/echo", "POST",
                Map.of("Content-Type", "application/json"), "{\"role\":\"USER\"}", null);
        assertFalse(without.contains("BODY_OK"));
    }

    @Step
    public void condMatchContentType() {
        RouteDsl.on(page()).api("/web/api/echo").mock().mockBody("CT_OK").matchContentType("json").done().start();
        openOrigin(page());
        String post = RouteDemoCoverageApi.request(page(), BASE + "/echo", "POST",
                Map.of("Content-Type", "application/json"), "{}", null);
        assertTrue(post.contains("CT_OK"));
        String get = RouteDemoCoverageApi.request(page(), BASE + "/echo", "GET", null, null, null);
        assertFalse(get.contains("CT_OK"));
    }

    @Step
    public void condMatchReferrer() {
        // 同源 fetch 浏览器按文档 URL 发 referer（http://localhost:8899/web/coverage.html）。
        // 注：本环境 Chromium 不会为同源 fetch 应用 fetch 选项的自定义 referrer，故用文档 URL 中
        // 确定存在的片段 "coverage.html" 验证 matchReferrer 对 referer 子串匹配。
        RouteDsl.on(page()).api("/web/api/echo").mock().mockBody("REF_OK").matchReferrer("coverage.html").done().start();
        openOrigin(page());
        String with = RouteDemoCoverageApi.request(page(), BASE + "/echo", "GET", null, null, null);
        assertTrue(with.contains("REF_OK"));
        // 反向：不存在于 referer 的片段不应命中
        RouteDsl.clear(page().context());
        RouteDsl.on(page()).api("/web/api/echo").mock().mockBody("REF_OK2").matchReferrer("zzz-no-such-ref").done().start();
        String nope = RouteDemoCoverageApi.request(page(), BASE + "/echo", "GET", null, null, null);
        assertFalse(nope.contains("REF_OK2"));
    }

    @Step
    public void condMatchOrigin() {
        // 同源 GET 浏览器不发 Origin 头，故打跨域地址（route-demo-service :8888），
        // 浏览器才会带 Origin: http://localhost:8899，才能验证 matchOrigin。
        // 注意：api() 的 pattern 会被归一化为 **/<pattern>，故此处传 "localhost:8888/..."（不带 http://），
        // 否则 **/http://... 无法匹配 http://...。mock 响应已带 CORS 头，跨域 fetch 不被拦截。
        String crossOriginUrl = "http://localhost:8888/demo/api/users";
        String crossOriginPattern = "localhost:8888/demo/api/users";
        RouteDsl.on(page()).api(crossOriginPattern).mock().mockBody("ORG_OK").matchOrigin("localhost:8899").done().start();
        openOrigin(page());
        String r = RouteDemoCoverageApi.request(page(), crossOriginUrl, "GET", null, null, null);
        assertTrue(r.contains("ORG_OK"));
    }

    @Step
    public void condMatchFrameUrl() {
        // allowAllFrames()：onlyMainFrame 默认 true 会先挡掉 iframe 请求，必须放开才能匹配 frame url
        RouteDsl.on(page()).api(IFRAME_ECHO).mock().mockBody("FRAME_OK").matchFrameUrl("frame.html").allowAllFrames().done().start();
        openOrigin(page());
        assertNotNull("matchFrameUrl(frame.html) 应命中 iframe 内请求",
                waitForCapturedByType(IFRAME_ECHO, RouteHandleType.MOCK));
    }

    @Step
    public void condOnlyMainFrame() {
        RouteDsl.on(page()).api(IFRAME_ECHO).mock().mockBody("X").onlyMainFrame(true).done().start();
        openOrigin(page());
        // iframe 内的请求不是主 frame → 应被排除，不应产生 MOCK 记录
        sleep(2500);
        assertEquals("onlyMainFrame 不应匹配 iframe 请求",
                0, countByType(IFRAME_ECHO, RouteHandleType.MOCK));
    }

    @Step
    public void condAllowAllFrames() {
        RouteDsl.on(page()).api(IFRAME_ECHO).mock().mockBody("Y").allowAllFrames().done().start();
        openOrigin(page());
        assertNotNull("allowAllFrames 应命中 iframe 内请求",
                waitForCapturedByType(IFRAME_ECHO, RouteHandleType.MOCK));
    }

    @Step
    public void condOnlyApiCall() {
        RouteDsl.on(page()).api("/web/api/echo").mock().mockBody("API_ONLY").onlyApiCall(true).done().start();
        openOrigin(page());
        String r = RouteDemoCoverageApi.request(page(), BASE + "/echo", "GET", null, null, null);
        assertTrue(r.contains("API_ONLY"));
    }

    @Step
    public void condAllowAllRequests() {
        RouteDsl.on(page()).api("/web/api/img").mock().mockBody("ALL").allowAllRequests().done().start();
        openOrigin(page());
        RouteDemoCoverageApi.loadImage(page(), IMG_URL);
        assertNotNull("allowAllRequests 应匹配 image 资源类型",
                waitForCapturedByType("/web/api/img", RouteHandleType.MOCK));
    }

    // ───────────────────────── Monitor 响应体主线程可读（替代 onResponse 桥接） ─────────────────────────

    @Step
    public void monitorResponseBodyReadable() {
        RouteDsl.on(page()).api("/web/api/echo").monitor()
                .record(true).minMatches(1).autoStopOnMatch(false).timeout(10)
                .done().start();
        openOrigin(page());
        RouteDemoCoverageApi.request(page(), BASE + "/echo", "GET", null, null, null);
        assertNotNull("monitor 应采集到 /web/api/echo",
                waitForCapturedByType("/web/api/echo", RouteHandleType.MONITOR));
        // 主线程直接读取已同步存储的响应体（替代原 onResponse + setShared/awaitShared 桥接）
        List<String> bodies = ApiCaptureContext.getCurrent().getAllResponsesForUrl("/web/api/echo");
        assertNotNull("monitor 应已捕获 /web/api/echo 的响应体", bodies);
        assertFalse("monitor 捕获的响应体不应为空", bodies.isEmpty());
    }

    // ───────────────────────── 按能力维度显式停止（monitor / modify / delay / mock / all）─────────────────────────
    // 验证「停止某能力只影响该能力，同 API 的其余能力不受影响」；stopApi 则停止全部能力（路由仍注册，走真实后端）。

    private static final String CAP_API = "/web/api/echo";

    /** 向 /web/api/echo 发 GET，返回包装 JSON {status, body, headers}。 */
    private JsonNode echoProbe() {
        String raw = RouteDemoCoverageApi.request(page(), BASE + CAP_API, "GET", null, null, null);
        return assertJson(raw);
    }

    /** 从 echo 回显 JSON 取「服务端实际收到的请求头」（body.headers，键已小写）。 */
    private String echoRequestHeader(JsonNode probe, String name) {
        JsonNode h = bodyOf(probe).get("headers");
        return (h != null && h.has(name)) ? h.get(name).asText() : null;
    }

    private int echoMonitorCount() {
        return countByType(CAP_API, RouteHandleType.MONITOR);
    }

    @Step
    public void capabilityStopMonitorLeavesModifyAndDelay() {
        RouteDsl.on(page()).api(CAP_API).monitor().record(true).expectStatus(200).minMatches(1)
                .autoStopOnMatch(false).timeout(10).done().start();
        RouteDsl.on(page()).api(CAP_API).modifyRequest().setRequestHeader("X-Cap", "ALIVE").done().start();
        RouteDsl.on(page()).api(CAP_API).delay(2).done().start();
        openOrigin(page());

        // 第一次请求：三项能力都应生效
        long t1 = System.currentTimeMillis();
        JsonNode p1 = echoProbe();
        long e1 = System.currentTimeMillis() - t1;
        assertEquals("首次请求 modify 应已改写请求头", "ALIVE", echoRequestHeader(p1, "x-cap"));
        assertTrue("首次请求 delay(2) 应 >=1900ms，实际=" + e1, e1 >= 1900);
        assertNotNull("首次请求 monitor 应已采集", waitForCapturedByType(CAP_API, RouteHandleType.MONITOR));
        int monAfterFirst = echoMonitorCount();
        assertTrue("首次请求 monitor 计数应 >=1", monAfterFirst >= 1);

        // 仅停止 monitor
        RouteDsl.stopMonitor(page(), CAP_API);

        // 第二次请求：monitor 不应再采集，modify + delay 仍生效
        long t2 = System.currentTimeMillis();
        JsonNode p2 = echoProbe();
        long e2 = System.currentTimeMillis() - t2;
        assertEquals("停止 monitor 后 modify 仍应生效", "ALIVE", echoRequestHeader(p2, "x-cap"));
        assertTrue("停止 monitor 后 delay 仍应 >=1900ms，实际=" + e2, e2 >= 1900);
        assertEquals("停止 monitor 后不应再产生新的 monitor 记录", monAfterFirst, echoMonitorCount());
    }

    @Step
    public void capabilityStopModifyLeavesMonitorAndDelay() {
        RouteDsl.on(page()).api(CAP_API).monitor().record(true).expectStatus(200).minMatches(1)
                .autoStopOnMatch(false).timeout(10).done().start();
        RouteDsl.on(page()).api(CAP_API).modifyRequest().setRequestHeader("X-Cap", "ALIVE").done().start();
        RouteDsl.on(page()).api(CAP_API).delay(2).done().start();
        openOrigin(page());

        JsonNode p1 = echoProbe();
        assertEquals("首次请求 modify 应已改写请求头", "ALIVE", echoRequestHeader(p1, "x-cap"));
        assertNotNull("首次请求 monitor 应已采集", waitForCapturedByType(CAP_API, RouteHandleType.MONITOR));
        int monBefore = echoMonitorCount();

        // 仅停止 modify
        RouteDsl.stopModify(page(), CAP_API);

        long t2 = System.currentTimeMillis();
        JsonNode p2 = echoProbe();
        long e2 = System.currentTimeMillis() - t2;
        assertNull("停止 modify 后请求头不应再带 X-Cap", echoRequestHeader(p2, "x-cap"));
        assertTrue("停止 modify 后 delay 仍应 >=1900ms，实际=" + e2, e2 >= 1900);
        assertTrue("停止 modify 后 monitor 仍应采集（计数增加）", echoMonitorCount() > monBefore);
    }

    @Step
    public void capabilityStopDelayLeavesMonitorAndModify() {
        RouteDsl.on(page()).api(CAP_API).monitor().record(true).expectStatus(200).minMatches(1)
                .autoStopOnMatch(false).timeout(10).done().start();
        RouteDsl.on(page()).api(CAP_API).modifyRequest().setRequestHeader("X-Cap", "ALIVE").done().start();
        RouteDsl.on(page()).api(CAP_API).delay(2).done().start();
        openOrigin(page());

        long t1 = System.currentTimeMillis();
        JsonNode p1 = echoProbe();
        long e1 = System.currentTimeMillis() - t1;
        assertTrue("首次请求 delay(2) 应 >=1900ms，实际=" + e1, e1 >= 1900);
        assertEquals("首次请求 modify 应已改写请求头", "ALIVE", echoRequestHeader(p1, "x-cap"));
        assertNotNull("首次请求 monitor 应已采集", waitForCapturedByType(CAP_API, RouteHandleType.MONITOR));
        int monBefore = echoMonitorCount();

        // 仅停止 delay
        RouteDsl.stopDelay(page(), CAP_API);

        long t2 = System.currentTimeMillis();
        JsonNode p2 = echoProbe();
        long e2 = System.currentTimeMillis() - t2;
        assertTrue("停止 delay 后应明显变快（<1000ms），实际=" + e2, e2 < 1000);
        assertEquals("停止 delay 后 modify 仍应生效", "ALIVE", echoRequestHeader(p2, "x-cap"));
        assertTrue("停止 delay 后 monitor 仍应采集（计数增加）", echoMonitorCount() > monBefore);
    }

    @Step
    public void capabilityStopMockFallsBackToReal() {
        RouteDsl.on(page()).api(CAP_API).mock().mockBody("MOCKED_STOP").done().start();
        openOrigin(page());

        String first = RouteDemoCoverageApi.request(page(), BASE + CAP_API, "GET", null, null, null);
        assertTrue("首次请求应命中 mock", first.contains("MOCKED_STOP"));

        // 仅停止 mock
        RouteDsl.stopMock(page(), CAP_API);

        String second = RouteDemoCoverageApi.request(page(), BASE + CAP_API, "GET", null, null, null);
        assertFalse("停止 mock 后不应再返回 mock 内容", second.contains("MOCKED_STOP"));
        assertTrue("停止 mock 后应走真实 echo（含 status 字段）", second.contains("\"status\""));
    }

    @Step
    public void capabilityStopAllStopsEverything() {
        RouteDsl.on(page()).api(CAP_API).monitor().record(true).expectStatus(200).minMatches(1)
                .autoStopOnMatch(false).timeout(10).done().start();
        RouteDsl.on(page()).api(CAP_API).modifyRequest().setRequestHeader("X-Cap", "ALIVE").done().start();
        RouteDsl.on(page()).api(CAP_API).delay(2).done().start();
        openOrigin(page());

        // 先确认三项都生效
        long t1 = System.currentTimeMillis();
        JsonNode p1 = echoProbe();
        long e1 = System.currentTimeMillis() - t1;
        assertTrue("停止前 delay 应 >=1900ms，实际=" + e1, e1 >= 1900);
        assertEquals("停止前 modify 应已改写请求头", "ALIVE", echoRequestHeader(p1, "x-cap"));
        assertNotNull("停止前 monitor 应已采集", waitForCapturedByType(CAP_API, RouteHandleType.MONITOR));
        int monBefore = echoMonitorCount();

        // 停止全部能力（路由仍注册）
        RouteDsl.stopApi(page(), CAP_API);

        long t2 = System.currentTimeMillis();
        String raw2 = RouteDemoCoverageApi.request(page(), BASE + CAP_API, "GET", null, null, null);
        long e2 = System.currentTimeMillis() - t2;
        assertTrue("stopApi 后不应再有 delay（<1000ms），实际=" + e2, e2 < 1000);
        assertTrue("stopApi 后应为 passthrough 到真实 echo（含 status 字段）", raw2.contains("\"status\""));
        assertNull("stopApi 后 modify 不应生效", echoRequestHeader(assertJson(raw2), "x-cap"));
        assertEquals("stopApi 后 monitor 不应再采集", monBefore, echoMonitorCount());
    }
}
