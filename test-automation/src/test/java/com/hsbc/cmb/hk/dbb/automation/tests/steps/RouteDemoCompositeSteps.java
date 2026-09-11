package com.hsbc.cmb.hk.dbb.automation.tests.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.microsoft.playwright.BrowserContext;
import net.serenitybdd.annotations.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Route 复合场景集成测试步骤 —— 覆盖四种能力在同一 API 上的<b>叠加与优先级</b>，
 * 以及 Page / Context 跨层合并语义。
 *
 * <p>继承 {@link RouteDemoServiceSteps} 复用其 Page 装配与 fetch 封装，
 * 只新增复合场景特有的规则组合与断言。
 *
 * <p><b>核心语义（与 route-priority-and-context-design.md 一致）</b>：
 * <ul>
 *   <li>MOCK 是唯一 terminal：一旦命中立即短路，不发真实请求，MODIFY / MONITOR 均不执行。</li>
 *   <li>MODIFY 只改<b>请求</b>（header / body / method），响应原样透传，不做任何篡改。</li>
 *   <li>DELAY 只延迟放行，与内容无关，可与 MOCK / MODIFY 任意叠加。</li>
 *   <li>MONITOR 是<b>叠加的观察维度</b>：与 MODIFY / DELAY 并存时仍采集真实响应；
 *       仅当 MOCK 短路时失效（无真实响应可观察）。</li>
 *   <li>四种能力在 {@link ApiCaptureContext} 中是<b>并列维度</b>，一次请求可同时产生多条记录。</li>
 * </ul>
 *
 * <p><b>跨层四条铁律</b>：MOCK 终结 + Page 胜出；MODIFY putAll；DELAY 取 max；MONITOR 能力位 OR。
 *
 * <p>前置：route-demo-service 已在 http://localhost:8888 启动。
 */
public class RouteDemoCompositeSteps extends RouteDemoServiceSteps {

    private static final Logger logger = LoggerFactory.getLogger(RouteDemoCompositeSteps.class);

    /** 采集等待上限（毫秒）；需覆盖 DELAY 场景的延迟时间。 */
    private static final long CAPTURE_TIMEOUT_MS = 8000L;

    // ───────────────────────── 断言辅助 ─────────────────────────

    private ApiCaptureContext ctx() {
        return ApiCaptureContext.forContext(page().context());
    }

    /** 轮询等待「指定类型」的采集记录出现（四种能力各自落库，按类型精确等待）。 */
    private CapturedApiCall waitForCapturedByType(String urlContains, RouteHandleType type) {
        long deadline = System.currentTimeMillis() + CAPTURE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            for (CapturedApiCall c : ctx().getAllByType(type)) {
                String url = c.requestUrl() != null ? c.requestUrl() : "";
                if (url.contains(urlContains)) return c;
            }
            sleepQuietly(100);
        }
        return null;
    }

    /** 统计某类型下命中 URL 的记录数（用于断言「不应存在」的场景）。 */
    private int countByType(String urlContains, RouteHandleType type) {
        int n = 0;
        for (CapturedApiCall c : ctx().getAllByType(type)) {
            String url = c.requestUrl() != null ? c.requestUrl() : "";
            if (url.contains(urlContains)) n++;
        }
        return n;
    }

    /** 断言某类型在等待窗口内<b>始终没有</b>记录（MOCK 短路等"不应发生"的语义）。 */
    private void assertNeverCaptured(String urlContains, RouteHandleType type, String reason) {
        long deadline = System.currentTimeMillis() + 1200;
        while (System.currentTimeMillis() < deadline) {
            if (countByType(urlContains, type) > 0) {
                fail(reason + " —— 但采集到 " + type + " 记录: " + urlContains);
            }
            sleepQuietly(100);
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private JsonNode json(String body) {
        return assertJson(body);
    }

    // ═════════════════════ A. 单层复合（同 pattern 多能力）═════════════════════

    /**
     * MODIFY + MONITOR 叠加：请求改写后转发，MONITOR 对<b>真实响应</b>继续采集。
     *
     * <p>期望：服务端收到 role=SUPER；采集上下文同时存在 MODIFY 与 MONITOR 两条记录，
     * 且 MONITOR 记录到的响应体就是改写后请求所拿回的真实响应。
     */
    @Step
    public void modifyPlusMonitorBothRecorded() {
        resetDemoData();
        RouteDsl.on(page())
                .api("/demo/api/users")
                .modifyRequest()
                    .modifyRequestBody("$.role", "SUPER")
                .done()
                .api("/demo/api/users")
                .monitor()
                    .record(true)
                    .minMatches(1)
                    .autoStopOnMatch(false)
                    .timeout(10)
                .done()
                .start();

        openOrigin(page());

        String resp = post("/users", "{\"name\":\"NewGuy\",\"email\":\"new@e.com\",\"role\":\"USER\"}");
        assertEquals(
                "SUPER",  json(resp).at("/role").asText(), "MODIFY 应把 role 改为 SUPER 后转发给服务端");

        CapturedApiCall modify = waitForCapturedByType("/demo/api/users", RouteHandleType.MODIFY);
        assertNotNull( modify, "应存在 MODIFY 类型的采集记录");
        CapturedApiCall monitor = waitForCapturedByType("/demo/api/users", RouteHandleType.MONITOR);
        assertNotNull( monitor, "MONITOR 应叠加在 MODIFY 之上，采集到真实响应");

        assertNotNull( monitor.responseBody(), "MONITOR 记录应含响应体");
        assertTrue(
                monitor.responseBody().contains("SUPER"), "MONITOR 采集到的是改写请求后拿回的真实响应（role=SUPER）");

        // MODIFY 记录应带修改详情，说明"改了什么"
        assertNotNull( modify.modifyDetail(), "MODIFY 记录应带 modifyDetail");
        assertTrue(
                modify.modifyDetail().contains("bodyFieldsModified"), "modifyDetail 应记录被改写的字段");
    }

    /**
     * MODIFY + DELAY 叠加：先延迟，再改写请求转发。
     *
     * <p>期望：总耗时 ≥ 延迟量（DELAY 先计时），且改写仍然生效。
     */
    @Step
    public void modifyPlusDelayBothApplied() {
        resetDemoData();
        RouteDsl.on(page())
                .api("/demo/api/users")
                .modifyRequest()
                    .modifyRequestBody("$.role", "DELAYED_SUPER")
                .done()
                .api("/demo/api/users")
                .delay(1)
                .done()
                .start();

        openOrigin(page());

        long start = System.currentTimeMillis();
        String resp = post("/users", "{\"name\":\"SlowGuy\",\"email\":\"slow@e.com\",\"role\":\"USER\"}");
        long elapsed = System.currentTimeMillis() - start;

        assertEquals( "DELAYED_SUPER",  json(resp).at("/role").asText(), "DELAY 后 MODIFY 仍应生效");
        assertTrue( elapsed >= 700, "DELAY 应先计时再放行（实测 " + elapsed + "ms，应 ≥ 700ms）");
    }

    /**
     * MODIFY + DELAY + MONITOR 三者叠加：一次请求应同时产生三条并列记录。
     *
     * <p>这是「四种能力是并列维度而非互斥枚举」契约的最强验证：
     * 用 {@code getAllGroupedByType()} 一次性核对 DELAY / MODIFY / MONITOR 三类都存在。
     */
    @Step
    public void modifyDelayMonitorTripleComposite() {
        resetDemoData();
        RouteDsl.on(page())
                .api("/demo/api/users")
                .modifyRequest()
                    .modifyRequestBody("$.role", "TRIPLE")
                .done()
                .api("/demo/api/users")
                .delay(1)
                .done()
                .api("/demo/api/users")
                .monitor()
                    .record(true)
                    .minMatches(1)
                    .autoStopOnMatch(false)
                    .timeout(10)
                .done()
                .start();

        openOrigin(page());

        String resp = post("/users", "{\"name\":\"Triple\",\"email\":\"t@e.com\",\"role\":\"USER\"}");
        assertEquals( "TRIPLE",  json(resp).at("/role").asText(), "三者叠加时 MODIFY 仍应生效");

        // 等待 MONITOR 落库（延迟后才采集）
        assertNotNull(
                waitForCapturedByType("/demo/api/users", RouteHandleType.MONITOR), "MONITOR 应在延迟结束后采集到真实响应");

        Map<RouteHandleType, List<CapturedApiCall>> grouped = ctx().getAllGroupedByType();
        assertEquals( 4,  grouped.size(), "分组应恒含四个 key");
        assertFalse( grouped.get(RouteHandleType.DELAY).isEmpty(), "DELAY 记录应存在（请求被延迟过）");
        assertFalse( grouped.get(RouteHandleType.MODIFY).isEmpty(), "MODIFY 记录应存在（请求被改写过）");
        assertFalse( grouped.get(RouteHandleType.MONITOR).isEmpty(), "MONITOR 记录应存在（真实响应被观察）");
        assertTrue( grouped.get(RouteHandleType.MOCK).isEmpty(), "本次未启用 MOCK，不应有 MOCK 记录");
        logger.info("[DIAG] triple composite grouped sizes: DELAY={}, MODIFY={}, MONITOR={}, MOCK={}",
                grouped.get(RouteHandleType.DELAY).size(),
                grouped.get(RouteHandleType.MODIFY).size(),
                grouped.get(RouteHandleType.MONITOR).size(),
                grouped.get(RouteHandleType.MOCK).size());
    }

    /**
     * MOCK + DELAY 叠加：DELAY 先计时，随后 MOCK 短路（真实请求始终不发）。
     *
     * <p>期望：耗时 ≥ 延迟量，但响应内容是 mock —— 证明延迟没有把请求"放行"到真实网络。
     */
    @Step
    public void mockPlusDelayDelayedThenShortCircuit() {
        resetDemoData();
        RouteDsl.on(page())
                .api("/demo/api/users")
                .delay(1)
                .done()
                .api("/demo/api/users")
                .mock()
                    .mockBody("{\"users\":[{\"name\":\"DelayedMock\"}]}")
                .done()
                .start();

        openOrigin(page());

        long start = System.currentTimeMillis();
        String body = get("/users");
        long elapsed = System.currentTimeMillis() - start;

        assertTrue( elapsed >= 700, "DELAY 应先计时（实测 " + elapsed + "ms，应 ≥ 700ms）");
        assertTrue( body.contains("DelayedMock"), "MOCK 应短路，返回 mock 响应");
        assertFalse( body.contains("Alice"), "MOCK 短路后不应出现真实数据");

        CapturedApiCall mock = waitForCapturedByType("/demo/api/users", RouteHandleType.MOCK);
        assertNotNull( mock, "MOCK 应落库 type=MOCK 的记录");
        assertTrue( mock.fromMock(), "MOCK 记录应标记 fromMock");
        assertNeverCaptured("/demo/api/users", RouteHandleType.MONITOR,
                "MOCK 短路时不产生真实响应，MONITOR 不应有记录");
    }

    /**
     * MOCK + MODIFY 叠加：MOCK 短路，MODIFY 不执行（设计文档 §4.5）。
     *
     * <p>期望：响应是 mock；且不产生 MODIFY 记录（该能力被短路吞掉，非静默丢失语义）。
     */
    @Step
    public void mockPlusModifyMockShortCircuitsModify() {
        resetDemoData();
        RouteDsl.on(page())
                .api("/demo/api/users")
                .modifyRequest()
                    .modifyRequestBody("$.role", "SHOULD_NOT_APPLY")
                .done()
                .api("/demo/api/users")
                .mock()
                    .mockBody("{\"users\":[{\"name\":\"MockWins\"}]}")
                .done()
                .start();

        openOrigin(page());

        String body = post("/users", "{\"name\":\"X\",\"email\":\"x@e.com\",\"role\":\"USER\"}");
        assertTrue( body.contains("MockWins"), "MOCK 应终结一切，返回 mock 响应");
        assertFalse( body.contains("SHOULD_NOT_APPLY"), "MODIFY 被 MOCK 短路，响应不应含改写值");

        assertNotNull( waitForCapturedByType("/demo/api/users", RouteHandleType.MOCK), "MOCK 应落库");
        assertNeverCaptured("/demo/api/users", RouteHandleType.MODIFY,
                "MOCK 短路后 MODIFY 不执行，不应产生 MODIFY 记录");
    }

    // ═════════════════════ B. 跨层复合（Page vs Context）═════════════════════

    /** Context MONITOR + Page MOCK：MOCK 终结，MONITOR 无真实响应可观察。 */
    @Step
    public void contextMonitorPlusPageMock() {
        resetDemoData();
        BrowserContext ctxObj = page().context();

        RouteDsl.on(ctxObj)
                .api("/demo/api/users")
                .monitor()
                    .record(true)
                    .minMatches(1)
                    .autoStopOnMatch(false)
                    .timeout(10)
                .done()
                .start();
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .mockBody("{\"users\":[{\"name\":\"PageMockWins\"}]}")
                .done()
                .start();

        openOrigin(page());

        String body = get("/users");
        assertTrue( body.contains("PageMockWins"), "Page 级 MOCK 应跨层终结 Context 级规则");

        assertNotNull( waitForCapturedByType("/demo/api/users", RouteHandleType.MOCK), "MOCK 应落库");
        assertNeverCaptured("/demo/api/users", RouteHandleType.MONITOR,
                "MOCK 终结后无真实响应，Context 级 MONITOR 不应采集到记录");
    }

    /**
     * Context DELAY(1s) + Page DELAY(2s)：跨层取 <b>max = 2s</b>（非 sum = 3s）。
     *
     * <p>断言区间 [1700, 3200) 同时排除了 "取 min(1s)" 与 "取 sum(3s)" 两种错误实现。
     */
    @Step
    public void contextDelayPlusPageDelayTakesMax() {
        resetDemoData();
        BrowserContext ctxObj = page().context();

        RouteDsl.on(ctxObj)
                .api("/demo/api/slow/endpoint")
                .delay(1)
                .done()
                .start();
        RouteDsl.on(page())
                .api("/demo/api/slow/endpoint")
                .delay(2)
                .done()
                .start();

        openOrigin(page());

        long start = System.currentTimeMillis();
        String body = get("/slow/endpoint");
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(
                elapsed >= 1700, "跨层 DELAY 应取 max(page=2s, ctx=1s)=2s，而非 min 的 1s（实测 " + elapsed + "ms）");
        assertTrue(
                elapsed < 3200, "跨层 DELAY 应取 max 而非 sum，不应达到 3s（实测 " + elapsed + "ms）");
        assertTrue(body.contains("slow endpoint"));
    }

    /**
     * Context MODIFY(header) + Page MODIFY(body)：跨层 <b>putAll</b> 合并，两者都生效。
     *
     * <p>通过 MODIFY 记录的 {@code modifyDetail} 同时校验 headersSet 与 bodyFieldsModified。
     */
    @Step
    public void contextModifyPlusPageModifyMerged() {
        resetDemoData();
        BrowserContext ctxObj = page().context();

        RouteDsl.on(ctxObj)
                .api("/demo/api/users")
                .modifyRequest()
                    .setRequestHeader("X-Ctx-Level", "CTX")
                .done()
                .start();
        RouteDsl.on(page())
                .api("/demo/api/users")
                .modifyRequest()
                    .setRequestHeader("X-Page-Level", "PAGE")
                    .modifyRequestBody("$.role", "MERGED")
                .done()
                .start();

        openOrigin(page());

        String resp = post("/users", "{\"name\":\"Merged\",\"email\":\"m@e.com\",\"role\":\"USER\"}");
        assertEquals( "MERGED",  json(resp).at("/role").asText(), "Page 级 body 改写应生效");

        CapturedApiCall modify = waitForCapturedByType("/demo/api/users", RouteHandleType.MODIFY);
        assertNotNull( modify, "应存在跨层合并后的 MODIFY 记录");
        String detail = modify.modifyDetail();
        assertNotNull( detail, "MODIFY 记录应带 modifyDetail");
        assertTrue(
                detail.contains("X-Ctx-Level"), "跨层 MODIFY 应 putAll：保留 Context 级 header（X-Ctx-Level）");
        assertTrue(
                detail.contains("X-Page-Level"), "跨层 MODIFY 应 putAll：保留 Page 级 header（X-Page-Level）");
        assertTrue(
                detail.contains("bodyFieldsModified"), "跨层 MODIFY 应保留 Page 级 body 改写");
    }

    /** Context MODIFY + Page MOCK：MOCK 终结，Context 级改写不生效。 */
    @Step
    public void contextModifyPlusPageMockShortCircuits() {
        resetDemoData();
        BrowserContext ctxObj = page().context();

        RouteDsl.on(ctxObj)
                .api("/demo/api/users")
                .modifyRequest()
                    .modifyRequestBody("$.role", "CTX_SHOULD_NOT_APPLY")
                .done()
                .start();
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .mockBody("{\"users\":[{\"name\":\"PageMockOverCtxModify\"}]}")
                .done()
                .start();

        openOrigin(page());

        String body = post("/users", "{\"name\":\"Y\",\"email\":\"y@e.com\",\"role\":\"USER\"}");
        assertTrue( body.contains("PageMockOverCtxModify"), "Page 级 MOCK 应终结 Context 级 MODIFY");
        assertFalse(body.contains("CTX_SHOULD_NOT_APPLY"));
        assertNeverCaptured("/demo/api/users", RouteHandleType.MODIFY,
                "MOCK 终结后 Context 级 MODIFY 不执行");
        // Context 级规则须显式清理，避免泄漏到后续 scenario
        RouteDsl.clear(ctxObj);
    }

    // ═════════════════════ C. DSL 方法覆盖 ══════════════════════

    /**
     * MODIFY 全部请求改写方法：setRequestHeader / removeRequestHeader /
     * modifyRequestBody / addRequestBodyField / removeRequestBodyField。
     *
     * <p>MODIFY 只改请求、不改响应：响应体即服务端对<b>改写后请求</b>的真实回应。
     */
    @Step
    public void modifyAllRequestMethods() {
        resetDemoData();
        RouteDsl.on(page())
                .api("/demo/api/users")
                .modifyRequest()
                    .setRequestHeader("X-Demo-Trace", "TRACE-1")
                    .removeRequestHeader("X-Demo-Remove")
                    .modifyRequestBody("$.role", "FULL_MODIFY")
                    //  新增字段用服务端 User 模型忽略的标量：
                    //   若新增 orders 这类 List 字段并传 "[]" 字符串，服务端反序列化类型不匹配会返回 400
                    .addRequestBodyField("$.nickname", "NICK")
                    .removeRequestBodyField("$.email")
                .done()
                .start();

        openOrigin(page());

        String resp = post("/users", "{\"name\":\"FullModify\",\"email\":\"drop@e.com\",\"role\":\"USER\"}");
        JsonNode node = json(resp);
        assertEquals( "FULL_MODIFY",  node.at("/role").asText(), "modifyRequestBody 应生效");
        assertTrue(
                node.at("/email").isMissingNode() || node.at("/email").isNull(), "removeRequestBodyField 应生效（email 被删除）");

        CapturedApiCall modify = waitForCapturedByType("/demo/api/users", RouteHandleType.MODIFY);
        assertNotNull(modify);
        String detail = modify.modifyDetail();
        assertNotNull(detail);
        assertTrue( detail.contains("X-Demo-Trace"), "modifyDetail 应记录 setRequestHeader");
        assertTrue( detail.contains("X-Demo-Remove"), "modifyDetail 应记录 removeRequestHeader");
        assertTrue(
                detail.contains("nickname"), "modifyDetail 应记录 addRequestBodyField（新增字段已写入请求体）");
        assertTrue( detail.contains("bodyFieldsRemoved"), "modifyDetail 应记录 removeRequestBodyField");
        assertTrue(
                detail.contains("FULL_MODIFY"), "modifyDetail 的 modifiedBody 应含改写后的 role");
    }

    /**
     * {@code times(N)} 一次性拦截：前 N 次命中规则，之后放行走真实网络。
     */
    @Step
    public void mockTimesOneShot() {
        resetDemoData();
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .mockBody("{\"users\":[{\"name\":\"OnceMock\"}]}")
                    .times(1)
                .done()
                .start();

        openOrigin(page());

        String first = get("/users");
        assertTrue( first.contains("OnceMock"), "第 1 次应命中 mock（times=1）");

        String second = get("/users");
        assertTrue( second.contains("Alice"), "第 2 次应耗尽 times 并走真实网络");
        assertFalse( second.contains("OnceMock"), "第 2 次不应再返回 mock");
    }

    /** 请求条件匹配：matchMethod + matchQuery 只命中符合条件的请求。 */
    @Step
    public void requestConditionMatching() {
        resetDemoData();
        RouteDsl.on(page())
                .api("/demo/api/search")
                .mock()
                    .mockBody("[{\"name\":\"MatchedAdmin\"}]")
                    .matchMethod("GET")
                    .matchQuery("role", "ADMIN")
                .done()
                .start();

        openOrigin(page());

        String matched = get("/search?role=ADMIN");
        assertTrue( matched.contains("MatchedAdmin"), "role=ADMIN 应命中条件 mock");

        String unmatched = get("/search?role=USER");
        assertFalse( unmatched.contains("MatchedAdmin"), "role=USER 不满足条件，不应命中 mock");
        assertTrue(
                unmatched.contains("Alice"), "不满足条件时应走真实后端（含 Bob 等 ADMIN/USER 数据）");
    }

    // ═════════════════════ E. 各 Handler 落库契约 ══════════════════════
    //
    // 逐个验证「单一能力」下对应 Handler 是否都正确落库，
    // 确保任意一条落库路径（含 MOCK 的 interceptResponse 分支）都不会静默失效。

    /** MOCK：纯 Mock 落库 type=MOCK、fromMock=true，且响应体是注入的假数据。 */
    @Step
    public void mockHandlerPersistsContract() {
        resetDemoData();
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .mockBody("{\"users\":[{\"name\":\"ContractMock\"}]}")
                .done()
                .start();

        openOrigin(page());
        assertTrue(get("/users").contains("ContractMock"));

        CapturedApiCall mock = waitForCapturedByType("/demo/api/users", RouteHandleType.MOCK);
        assertNotNull( mock, "MockHandler 应落库 type=MOCK 的记录");
        assertTrue( mock.fromMock(), "MOCK 记录 fromMock 应为 true");
        assertNotNull( mock.responseBody(), "MOCK 记录应含注入的响应体");
        assertTrue(mock.responseBody().contains("ContractMock"));
        assertEquals( RouteHandleType.MOCK,  mock.handleType(), "MOCK 记录的 handleType 应为 MOCK");
        // 单一能力下不应产生其它类型的记录
        assertEquals(0, countByType("/demo/api/users", RouteHandleType.MONITOR));
        assertEquals(0, countByType("/demo/api/users", RouteHandleType.MODIFY));
        assertEquals(0, countByType("/demo/api/users", RouteHandleType.DELAY));
    }

    /** MOCK + interceptResponse：改写真实响应后仍落库，且内容是改写后的结果。 */
    @Step
    public void mockInterceptHandlerPersistsContract() {
        resetDemoData();
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .interceptResponse()
                    .mockReplaceField("$.users[0].name", "CONTRACT_RENAMED")
                .done()
                .start();

        openOrigin(page());
        String body = get("/users");
        // demo 的 /api/users 返回裸数组（无 users 包装层）
        assertEquals("CONTRACT_RENAMED", json(body).at("/0/name").asText());

        CapturedApiCall mock = waitForCapturedByType("/demo/api/users", RouteHandleType.MOCK);
        assertNotNull( mock, "interceptResponse 分支也必须落库（否则该调用完全不可见）");
        assertNotNull(mock.responseBody());
        assertTrue( mock.responseBody().contains("CONTRACT_RENAMED"), "落库的应是改写后的响应");
    }

    /** MODIFY：落库 type=MODIFY，响应是服务端对改写后请求的真实回应，且带 modifyDetail。 */
    @Step
    public void modifyHandlerPersistsContract() {
        resetDemoData();
        RouteDsl.on(page())
                .api("/demo/api/users")
                .modifyRequest()
                    .modifyRequestBody("$.role", "CONTRACT_ROLE")
                .done()
                .start();

        openOrigin(page());
        assertEquals("CONTRACT_ROLE",
                json(post("/users", "{\"name\":\"C\",\"email\":\"c@e.com\",\"role\":\"USER\"}")).at("/role").asText());

        CapturedApiCall modify = waitForCapturedByType("/demo/api/users", RouteHandleType.MODIFY);
        assertNotNull( modify, "ModifyHandler 应落库 type=MODIFY 的记录");
        assertNotNull( modify.responseBody(), "MODIFY 记录应含真实响应体（服务端对改写后请求的回应）");
        assertTrue(modify.responseBody().contains("CONTRACT_ROLE"));
        assertNotNull( modify.modifyDetail(), "MODIFY 记录应带 modifyDetail");
        assertTrue(
                modify.modifyDetail().contains("CONTRACT_ROLE"), "modifyDetail 应记录被改写的字段");
    }

    /** MONITOR：落库 type=MONITOR，含完整真实响应。 */
    @Step
    public void monitorHandlerPersistsContract() {
        resetDemoData();
        RouteDsl.on(page())
                .api("/demo/api/users")
                .monitor()
                    .record(true)
                    .minMatches(1)
                    .autoStopOnMatch(false)
                    .timeout(10)
                .done()
                .start();

        openOrigin(page());
        get("/users");

        CapturedApiCall monitor = waitForCapturedByType("/demo/api/users", RouteHandleType.MONITOR);
        assertNotNull( monitor, "MonitorHandler 应落库 type=MONITOR 的记录");
        assertNotNull( monitor.responseBody(), "MONITOR 记录应含真实响应体");
        assertTrue( monitor.responseBody().contains("Alice"), "MONITOR 应采集到真实后端数据");
        assertEquals(200, monitor.statusCode());
        assertFalse( monitor.fromMock(), "MONITOR 记录不应被标记为 mock");
    }

    /**
     * DELAY：落库为<b>维度标记</b>，只可通过按类型查询获得，
     * 且<b>不污染</b>按 endpoint 的通用查询（这是框架侧契约，不依赖调用方过滤）。
     */
    @Step
    public void delayHandlerPersistsContract() {
        resetDemoData();
        RouteDsl.on(page())
                .api("/demo/api/slow/endpoint")
                .delay(1)
                .done()
                .start();

        openOrigin(page());
        assertTrue(get("/slow/endpoint").contains("slow endpoint"));

        List<CapturedApiCall> delays =
                ctx().getApiCallsByType("/demo/api/slow/endpoint", RouteHandleType.DELAY);
        assertFalse( delays.isEmpty(), "DELAY 标记应可通过按类型查询获得");

        //  关键：通用查询不应返回无响应体的 DELAY 占位
        CapturedApiCall general = ctx().getLastApiCall("/demo/api/slow/endpoint");
        if (general != null) {
            assertFalse( general.isDelayMarker(), "通用查询不应返回 DELAY 标记（无响应体）");
        }
        for (CapturedApiCall c : ctx().getApiCalls("/demo/api/slow/endpoint")) {
            assertFalse( c.isDelayMarker(), "getApiCalls 结果不应混入 DELAY 标记");
        }
    }

    // ═════════════════════ D. 清理与隔离 ══════════════════════

    /**
     * 清理验证：清空规则后 pattern 表归零、采集上下文重置、请求恢复真实后端。
     *
     * <p>这是「scenario 间不互相污染」的守门用例：前面所有 scenario 若泄漏规则或采集状态，
     * 本用例会在此暴露。
     */
    @Step
    public void cleanupResetsRouteAndCaptureState() {
        resetDemoData();
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .mockBody("{\"users\":[{\"name\":\"ToBeCleared\"}]}")
                .done()
                .start();

        openOrigin(page());
        assertTrue( get("/users").contains("ToBeCleared"), "清理前应命中 mock");

        // 清理：页面级规则 + 采集上下文
        RouteDsl.clear(page());
        ApiCaptureContext.removeContext(page().context());

        assertEquals( 0,  RouteRegistry.getPatternCount(page()), "清理后该 Page 的 pattern 表应归零");

        // 清理后的采集上下文应为空（removeContext 会丢弃旧实例，取到的是全新实例）
        ApiCaptureContext fresh = ApiCaptureContext.forContext(page().context());
        assertTrue( fresh.getAllApiCalls().isEmpty(), "清理后的采集上下文不应残留记录");

        openOrigin(page());
        String after = get("/users");
        assertTrue( after.contains("Alice"), "清理后应恢复真实后端");
        assertFalse( after.contains("ToBeCleared"), "清理后不应再命中 mock");

        //  线程资源：延迟调度 / body 读取重试均为<b>固定规模</b>的共享线程池，
        //   不应随 scenario 反复创建而无限增长（否则长套件会线程泄漏）。
        // 注：调度线程是<b>懒创建</b>的（任务提交时才起），因此「0 个」同样代表无残留 ——
        //     这里只校验上界，即线程数不随 scenario 累积。
        int delayThreads = countThreads("route-network-delay");
        assertTrue(
                delayThreads <= 8, "延迟调度线程池规模应固定（实测 " + delayThreads + "，不应超过 8）");
        int bodyRetryThreads = countThreads("monitor-body-retry");
        assertTrue(
                bodyRetryThreads <= 1, "body 读取重试线程池应至多 1 个（实测 " + bodyRetryThreads + "）");
        logger.info("[DIAG] thread usage: route-network-delay={}, monitor-body-retry={}",
                delayThreads, bodyRetryThreads);
    }

    /** 统计指定名称前缀的存活线程数（用于检测线程池是否随用例泄漏）。 */
    private static int countThreads(String namePrefix) {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName() != null && t.getName().startsWith(namePrefix)) n++;
        }
        return n;
    }

    // ═════════════════════ F. Monitor 响应体主线程可读 ══════════════════════
    //
    // 验证核心链路：Monitor 捕获的响应体已同步存储，主线程可直接读取（无需 onResponse 回调桥接）。

    /**
     * Monitor 捕获 /demo/api/users 响应后，主线程可直接读取响应体并提取业务字段，
     * 验证响应数据对主线程可见。
     */
    @Step
    public void monitorResponseBodyReadableMainThread() {
        resetDemoData();

        RouteDsl.on(page())
                .api("/demo/api/users")
                .monitor()
                    .record(true)
                    .minMatches(1)
                    .autoStopOnMatch(false)
                    .timeout(10)
                .done()
                .start();

        openOrigin(page());
        get("/users");   // 触发请求 → monitor 捕获

        //  主线程直接读取已同步存储的响应体（替代原 onResponse + setShared/awaitShared 桥接）
        List<String> bodies = ApiCaptureContext.getCurrent().getAllResponsesForUrl("/demo/api/users");
        assertNotNull( bodies, "monitor 应已捕获 /demo/api/users 的响应体");
        assertFalse( bodies.isEmpty(), "monitor 捕获的响应体不应为空");
        assertTrue( bodies.get(bodies.size() - 1).contains("Alice"), "响应体应包含业务字段 Alice");
    }
}
