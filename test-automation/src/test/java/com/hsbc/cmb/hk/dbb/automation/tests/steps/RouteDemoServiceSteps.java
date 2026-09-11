package com.hsbc.cmb.hk.dbb.automation.tests.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.RouteDemoPage;
import com.hsbc.cmb.hk.dbb.automation.tests.utils.RouteDemoApi;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import net.serenitybdd.annotations.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Route Demo Service 集成测试步骤 —— 结合真实 SpringBoot demo service（route-demo-service，端口 8888，context-path /demo）。
 *
 * <p>参照 login_dbb 的设计：业务层只负责路由规则与断言，
 * 浏览器 / Context / Page 由框架 {@code @AutoBrowser} 自动托管（与框架运行时一致的装配），
 * 而非原生 {@code Playwright.create()}。
 *
 * <p><b>统一执行约定（所有 case 必须遵守）</b>：
 * <ol>
 *   <li>先注册 Route 规则（{@code RouteDsl.on(page)...start()}）</li>
 *   <li>再导航 / 刷新页面（{@link #openOrigin(Page)}）</li>
 *   <li>然后由页面 JS 上下文发起 fetch 触发规则（{@code get()/post()}）</li>
 *   <li>最后断言</li>
 * </ol>
 * 这样可保证所有请求都发生在规则生效之后，避免规则注册前的请求逃逸拦截。
 *
 * <p>覆盖场景（与原 JUnit 版 {@code RouteDemoServiceIntegrationTest} 一一对应）：
 * <ul>
 *   <li>MONITOR：采集真实 API 响应信息</li>
 *   <li>MOCK：整体替换响应</li>
 *   <li>MOCK + interceptRealResponse：取真实响应后改字段</li>
 *   <li>需求3 条件修改：满足某条件才改某字段、不影响其它数据（数组逐元素 + 数值条件）</li>
 *   <li>MODIFY：改写请求体后转发</li>
 *   <li>DELAY：高延迟生效；delay + monitor（需求2：monitor 读 body 重试上限 +delayMs）</li>
 *   <li>优先级：mock + monitor 同 pattern，monitor 在链尾采集到 mock 后的响应</li>
 *   <li>资源清理：clear 后规则失效恢复真实后端；多 context 隔离互不泄漏</li>
 * </ul>
 *
 * <p>前置：route-demo-service 已在 http://localhost:8888 启动（mvn spring-boot:run）。
 */
public class RouteDemoServiceSteps {

    private static final Logger logger = LoggerFactory.getLogger(RouteDemoServiceSteps.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "http://localhost:8888/demo/api";

    /**
     * 建立同源 origin 用的 URL。
     *
     * <p>刻意选用 {@code /api/search}：它返回 200，且 URL 不落在任何测试 pattern
     * （{@code /demo/api/users}、{@code /demo/api/slow/endpoint}）之内，
     * 因此导航请求不会被规则拦截，也就不会污染 monitor 的采集计数。
     * （若改用 /api/users/1，其 URL 会被 /demo/api/users 规则匹配，导致导航请求被误拦截 / 被计数。）
     */
    private static final String ORIGIN_URL = "http://localhost:8888/demo/api/search";

    // PageObject 字段（非静态初始化）；BasePage 构造函数不创建 Context，Context 仅在 getPage() 时由框架创建
    private final RouteDemoPage routeDemoPage = PageObjectFactory.getPage(RouteDemoPage.class);

    // 工具方法 ─────────────────────────

    /**
     * 取当前框架托管的 Page。每个 Scenario 由 @AutoBrowser 重新创建，故每次调用取最新实例。
     *
     * <p>protected：供 {@code RouteDemoCompositeSteps} 等子类复用同一套装配与辅助方法。
     */
    protected Page page() {
        return routeDemoPage.getPage();
    }

    /**
     * 在<b>规则注册之后</b>建立 / 刷新同源 origin。
     *
     * <p>Playwright 的 APIRequestContext 发出的请求不经过 page/context 的 route 拦截，
     * 框架的拦截只对「页面上下文内的 fetch / navigation」生效，
     * 因此必须先让页面处于 demo service 的同源 origin 下，再用 page.evaluate 发起 fetch。
     *
     * <p>已在目标 origin 上时执行 {@code reload()}（刷新页面，使页面级请求也经过已注册规则），
     * 否则 {@code navigate()} 建立 origin。
     */
    protected void openOrigin(Page p) {
        if (p.url() != null && p.url().startsWith("http://localhost:8888")) {
            p.reload();
        } else {
            p.navigate(ORIGIN_URL);
        }
    }

    protected String getVia(Page p, String path) {
        if (p.url() == null || !p.url().startsWith("http://localhost:8888")) {
            // 兜底：正常流程应在规则注册后已调用 openOrigin(Page)
            openOrigin(p);
        }
        String result = RouteDemoApi.getJson(p, BASE + path);
        logger.info("[DIAG] GET {} => len={} body={}", path,
                (result == null ? "null" : result.length()),
                (result == null ? "null" : result.substring(0, Math.min(300, result.length()))));
        return result;
    }

    protected String postVia(Page p, String path, String jsonBody) {
        if (p.url() == null || !p.url().startsWith("http://localhost:8888")) {
            openOrigin(p);
        }
        return RouteDemoApi.postJson(p, BASE + path, jsonBody);
    }

    protected String get(String path) {
        return getVia(page(), path);
    }

    protected String post(String path, String jsonBody) {
        return postVia(page(), path, jsonBody);
    }

    /**
     * 轮询等待采集到指定 URL 的调用记录（monitor 异步）。
     *
     * <p>无需过滤 DELAY：延迟标记存放在 {@code ApiCaptureContext} 的独立索引中，
     * 按 endpoint 的通用查询只会返回带有完整请求/响应内容的调用。
     */
    protected CapturedApiCall waitForCaptured(String urlContains) {
        ApiCaptureContext ctx = ApiCaptureContext.forContext(page().context());
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            for (List<CapturedApiCall> calls : ctx.getAllApiCalls().values()) {
                for (CapturedApiCall c : calls) {
                    String url = c.requestUrl() != null ? c.requestUrl() : "";
                    if (url.contains(urlContains)) return c;
                }
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return null;
    }

    /** 每个 Scenario 后清理：注销路由规则 + 释放 context 级采集状态。 */
    @Step
    public void cleanup() {
        try {
            // 规则由 RouteDsl.on(Page) 注册为页面级，须用 clear(Page) 才能移除页面级规则
            RouteDsl.clear(page());
        } catch (Exception ignored) {
            // 某些场景未注册规则，clear 允许空操作
        }
        ApiCaptureContext.removeContext(page().context());
    }

    // ───────────────────────── A. MONITOR 采集 ─────────────────────────

    @Step
    public void monitorCollectsRealResponse() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .monitor()
                    .record(true)
                    .expectStatus(200)
                    .minMatches(1)
                    .autoStopOnMatch(true)
                    .timeout(5)
                .done()
                .start();

        openOrigin(page()); // 规则注册后再导航

        get("/users");

        CapturedApiCall call = waitForCaptured("/demo/api/users");
        assertNotNull( call, "monitor 应将 /demo/api/users 的响应记入采集上下文");
        assertEquals(200, call.statusCode());
        String body = call.responseBody();
        assertNotNull(body);
        assertTrue( body.contains("Alice"), "采集到的响应应来自真实 demo service（含 Alice）");
    }

    // ───────────────────────── B. MOCK 整体替换 ─────────────────────────

    @Step
    public void mockReplacesWholeResponse() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .mockBody("{\"users\":[{\"name\":\"MockedUser\",\"role\":\"MOCK\"}]}")
                .done()
                .start();

        openOrigin(page());

        String body = get("/users");
        JsonNode node = assertJson(body);
        assertEquals("MockedUser", node.at("/users/0/name").asText());
        assertFalse( body.contains("Alice"), "mock 响应不应包含真实数据 Alice");
    }

    // ───────────────────────── C. MOCK + 拦截真实响应改字段 ─────────────────────────

    @Step
    public void mockInterceptRealResponseThenReplaceField() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .interceptResponse()
                    .mockReplaceField("$.users[0].name", "RENAMED")
                .done()
                .start();

        openOrigin(page());

        String body = get("/users");
        JsonNode node = assertJson(body);
        // 注意：demo 的 /api/users 返回裸 JSON 数组（无 users 包装层），索引从根开始
        assertEquals("RENAMED", node.at("/0/name").asText());
        assertEquals("Bob", node.at("/1/name").asText());
        assertEquals("Charlie", node.at("/2/name").asText());
    }

    // ───────────────────────── D1. 需求3 条件修改（数组逐元素） ─────────────────────────

    @Step
    public void conditionalModifyArrayElementByRole() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .interceptResponse()
                    .when("$[*].role", "EQUALS", "ADMIN").thenSet("$[*].vip", true)
                .done()
                .start();

        openOrigin(page());

        String body = get("/users");
        JsonNode node = assertJson(body);
        // Bob(index1) 是 ADMIN → vip=true
        assertTrue( node.at("/1/vip").asBoolean(), "ADMIN 用户(Bob)应被设置 vip=true");
        // Alice(index0)/Charlie(index2) 是 USER → 不产生 vip 或保持原值（不应为 true）
        assertFalse( node.at("/0/vip").asBoolean(false), "USER 用户(Alice)不应被设置 vip");
        assertFalse( node.at("/2/vip").asBoolean(false), "USER 用户(Charlie)不应被设置 vip");
        // 其它字段不受影响
        assertEquals("Alice", node.at("/0/name").asText());
        assertEquals("Charlie", node.at("/2/name").asText());
    }

    // ───────────────────────── D2. 需求3 条件修改（数值条件） ─────────────────────────

    @Step
    public void conditionalModifyNumericGreaterThan() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .interceptResponse()
                    .when("$[*].id", "GTE", 2).thenSet("$[*].flag", true)
                .done()
                .start();

        openOrigin(page());

        String body = get("/users");
        JsonNode node = assertJson(body);
        assertFalse( node.at("/0/flag").asBoolean(false), "id=1 不满足 >=2，不应被添加 flag");
        assertTrue( node.at("/1/flag").asBoolean(), "id=2 满足 >=2，应被添加 flag=true");
        assertTrue( node.at("/2/flag").asBoolean(), "id=3 满足 >=2，应被添加 flag=true");
    }

    // ───────────── D3. 需求3 条件修改 — 补齐 ConditionOp 全部 11 个操作符覆盖 ─────────────
    //
    // 数据基线（GET /demo/api/users）：
    //   [0] id=1  name=Alice    email=alice@example.com    role=USER
    //   [1] id=2  name=Bob      email=bob@example.com      role=ADMIN
    //   [2] id=3  name=Charlie  email=charlie@example.com  role=USER
    // 稳定性说明：demo 服务的 USERS 是 static List，POST 仅追加到末尾，
    // 不改变前三个元素的 index，因此下述 /0 /1 /2 断言不受其它场景 POST 影响。
    // 每个场景均同时覆盖「命中」与「未命中」两侧，避免恒真断言。

    @Step
    public void conditionalModifyNotEquals() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .interceptResponse()
                    .when("$[*].role", "NOT_EQUALS", "ADMIN").thenSet("$[*].vip", true)
                .done()
                .start();

        openOrigin(page());

        JsonNode node = assertJson(get("/users"));
        assertTrue( node.at("/0/vip").asBoolean(), "Alice(USER)≠ADMIN，应设置 vip=true");
        assertFalse( node.at("/1/vip").asBoolean(false), "Bob 是 ADMIN，NOT_EQUALS 不应设置 vip");
        assertTrue( node.at("/2/vip").asBoolean(), "Charlie(USER)≠ADMIN，应设置 vip=true");
    }

    @Step
    public void conditionalModifyGreaterThan() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .interceptResponse()
                    .when("$[*].id", "GT", 2).thenSet("$[*].flag", true)
                .done()
                .start();

        openOrigin(page());

        JsonNode node = assertJson(get("/users"));
        assertFalse( node.at("/0/flag").asBoolean(false), "id=1 不满足 >2");
        assertFalse( node.at("/1/flag").asBoolean(false), "id=2 边界：GT 为严格大于，2 不 > 2");
        assertTrue( node.at("/2/flag").asBoolean(), "id=3 满足 >2");
    }

    @Step
    public void conditionalModifyLessThan() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .interceptResponse()
                    .when("$[*].id", "LT", 2).thenSet("$[*].flag", true)
                .done()
                .start();

        openOrigin(page());

        JsonNode node = assertJson(get("/users"));
        assertTrue( node.at("/0/flag").asBoolean(), "id=1 满足 <2");
        assertFalse( node.at("/1/flag").asBoolean(false), "id=2 边界：LT 为严格小于，2 不 < 2");
        assertFalse( node.at("/2/flag").asBoolean(false), "id=3 不满足 <2");
    }

    @Step
    public void conditionalModifyLessThanOrEqual() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .interceptResponse()
                    .when("$[*].id", "LTE", 2).thenSet("$[*].flag", true)
                .done()
                .start();

        openOrigin(page());

        JsonNode node = assertJson(get("/users"));
        assertTrue( node.at("/0/flag").asBoolean(), "id=1 满足 <=2");
        assertTrue( node.at("/1/flag").asBoolean(), "id=2 边界：LTE 含等于，2<=2 应命中");
        assertFalse( node.at("/2/flag").asBoolean(false), "id=3 不满足 <=2");
    }

    @Step
    public void conditionalModifyContains() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .interceptResponse()
                    .when("$[*].email", "CONTAINS", "bob").thenSet("$[*].matched", true)
                .done()
                .start();

        openOrigin(page());

        JsonNode node = assertJson(get("/users"));
        assertFalse( node.at("/0/matched").asBoolean(false), "alice@example.com 不含 'bob'");
        assertTrue( node.at("/1/matched").asBoolean(), "bob@example.com 含 'bob'，应命中");
        assertFalse( node.at("/2/matched").asBoolean(false), "charlie@example.com 不含 'bob'");
    }

    @Step
    public void conditionalModifyNotContains() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .interceptResponse()
                    .when("$[*].email", "NOT_CONTAINS", "bob").thenSet("$[*].matched", true)
                .done()
                .start();

        openOrigin(page());

        JsonNode node = assertJson(get("/users"));
        assertTrue( node.at("/0/matched").asBoolean(), "alice@example.com 不含 'bob'，应命中");
        assertFalse( node.at("/1/matched").asBoolean(false), "bob@example.com 含 'bob'，NOT_CONTAINS 不应命中");
        assertTrue( node.at("/2/matched").asBoolean(), "charlie@example.com 不含 'bob'，应命中");
    }

    @Step
    public void conditionalModifyRegex() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .interceptResponse()
                    // ⚠️ REGEX 底层是 String.matches()，属【全字符串匹配】而非 find() 部分匹配，
                    //    因此表达「A 或 C 开头」必须写成 "^[AC].*"（".*" 用于消耗剩余字符）；
                    //    若写成 "^[AC]" 则永远匹配失败（"[AC]" 只消耗首字符，剩余字符无对应模式）。
                    .when("$[*].name", "REGEX", "^[AC].*").thenSet("$[*].initialGroup", true)
                .done()
                .start();

        openOrigin(page());

        JsonNode node = assertJson(get("/users"));
        assertTrue( node.at("/0/initialGroup").asBoolean(), "Alice 以 A 开头，应匹配 ^[AC].*");
        assertFalse( node.at("/1/initialGroup").asBoolean(false), "Bob 以 B 开头，不应匹配 ^[AC].*");
        assertTrue( node.at("/2/initialGroup").asBoolean(), "Charlie 以 C 开头，应匹配 ^[AC].*");
    }

    /**
     * EXISTS — 同时验证「字段存在时命中」与「字段缺失时不命中」，
     * 并顺带覆盖多条 when 链式并列（thenSet 返回外层 InterceptMockDsl 后可继续 when）。
     */
    @Step
    public void conditionalModifyExists() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .interceptResponse()
                    .when("$[*].role", "EXISTS", null).thenSet("$[*].hasRole", true)
                    .when("$[*].missingField", "EXISTS", null).thenSet("$[*].hasMissing", true)
                .done()
                .start();

        openOrigin(page());

        JsonNode node = assertJson(get("/users"));
        assertTrue( node.at("/0/hasRole").asBoolean(), "role 存在，Alice 应被设置 hasRole");
        assertTrue( node.at("/1/hasRole").asBoolean(), "role 存在，Bob 应被设置 hasRole");
        assertTrue( node.at("/2/hasRole").asBoolean(), "role 存在，Charlie 应被设置 hasRole");
        assertFalse( node.at("/0/hasMissing").asBoolean(false), "missingField 不存在，EXISTS 不应命中");
        assertFalse( node.at("/1/hasMissing").asBoolean(false), "missingField 不存在，EXISTS 不应命中");
        assertFalse( node.at("/2/hasMissing").asBoolean(false), "missingField 不存在，EXISTS 不应命中");
    }

    /**
     * NOT_EXISTS — 与 EXISTS 互为反向：字段缺失时命中，字段存在时不命中。
     */
    @Step
    public void conditionalModifyNotExists() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .interceptResponse()
                    .when("$[*].missingField", "NOT_EXISTS", null).thenSet("$[*].missingOk", true)
                    .when("$[*].role", "NOT_EXISTS", null).thenSet("$[*].roleGone", true)
                .done()
                .start();

        openOrigin(page());

        JsonNode node = assertJson(get("/users"));
        assertTrue( node.at("/0/missingOk").asBoolean(), "missingField 不存在，NOT_EXISTS 应命中");
        assertTrue( node.at("/1/missingOk").asBoolean(), "missingField 不存在，NOT_EXISTS 应命中");
        assertTrue( node.at("/2/missingOk").asBoolean(), "missingField 不存在，NOT_EXISTS 应命中");
        assertFalse( node.at("/0/roleGone").asBoolean(false), "role 存在，NOT_EXISTS 不应命中");
        assertFalse( node.at("/1/roleGone").asBoolean(false), "role 存在，NOT_EXISTS 不应命中");
        assertFalse( node.at("/2/roleGone").asBoolean(false), "role 存在，NOT_EXISTS 不应命中");
    }

    // ───────────────────────── E. MODIFY 改写请求体 ─────────────────────────

    @Step
    public void modifyRequestBodyIsForwardedToServer() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .modifyRequest()
                    .modifyRequestBody("$.role", "SUPER")
                .done()
                .start();

        openOrigin(page());

        // 原始 body 的 role=USER，框架应改为 SUPER 再转发给 demo service
        String resp = post("/users", "{\"name\":\"NewGuy\",\"email\":\"new@e.com\",\"role\":\"USER\"}");
        JsonNode node = assertJson(resp);
        assertEquals("SUPER", node.at("/role").asText());
    }

    // ───────────────────────── F1. DELAY 高延迟生效 ─────────────────────────

    @Step
    public void delayAppliesLatency() {
        RouteDsl.on(page())
                .api("/demo/api/slow/endpoint")
                .delay(1) // 延迟 1 秒
                .done()
                .start();

        // 先完成导航，确保下面的 elapsed 只计量 fetch 本身，不被页面导航耗时污染
        openOrigin(page());

        long start = System.currentTimeMillis();
        String body = get("/slow/endpoint");
        long elapsed = System.currentTimeMillis() - start;

        // /demo/api/slow/endpoint 的真实基线仅约 110ms；delay(1s) 生效则应约 1100ms+
        assertTrue( elapsed >= 700, "delay(1s) 应使响应耗时 > 700ms（真实基线约 110ms），实际=" + elapsed);
        assertTrue(body.contains("slow endpoint"));
    }

    // ───────────────────────── F2. 需求2：delay + monitor（读 body 重试上限 +delayMs） ─────────────────────────

    @Step
    public void delayPlusMonitorMonitorStillCaptures() {
        RouteDsl.on(page())
                .api("/demo/api/slow/endpoint")
                .delay(1)
                .done()
                .start();
        RouteDsl.on(page())
                .api("/demo/api/slow/endpoint")
                .monitor()
                    .record(true)
                    .minMatches(1)
                    .autoStopOnMatch(true)
                    .timeout(8)
                .done()
                .start();

        openOrigin(page());

        get("/slow/endpoint");

        CapturedApiCall call = waitForCaptured("/demo/api/slow/endpoint");
        assertNotNull( call, "delay 场景下 monitor 经扩展重试仍应采集到响应（需求2）");
        assertNotNull( call.responseBody(), "delay 场景下 monitor 读 body 重试(+delayMs)应取到响应体");
        assertTrue( call.responseBody().contains("slow endpoint"), "monitor 采集到的响应体应来自真实 slow endpoint");
    }

    // ───────────────────────── E2. 用户关注点：同 API 先 modify 后 monitor（分写）→ 两能力共存，monitor 不失联 ─────────────────────────

    @Step
    public void modifyThenMonitorSameApiBothActive() {
        String api = "/demo/api/users";
        // ① 先写 modify（写在前面）
        RouteDsl.on(page())
                .api(api)
                .modifyRequest()
                    .modifyRequestBody("$.role", "SUPER")
                .done()
                .start();
        // ② 后写 monitor（写在后面，同 pattern）——验证「先 modify 后 monitor」不会令 monitor 失联
        RouteDsl.on(page())
                .api(api)
                .monitor()
                    .record(true)
                    .expectStatus(200)
                    .minMatches(1)
                    .autoStopOnMatch(true)
                    .timeout(5)
                .done()
                .start();

        openOrigin(page());

        // POST：modify 改写请求体 role=USER → SUPER 并转发，响应回显 SUPER（证明 modify 生效）
        String resp = post("/users", "{\"name\":\"NewGuy\",\"email\":\"new@e.com\",\"role\":\"USER\"}");
        JsonNode node = assertJson(resp);
        assertEquals("SUPER", node.at("/role").asText());

        // monitor 经分发期合并后仍在同 pattern 生效：采集到响应（且反映 modify 后的请求）
        CapturedApiCall call = waitForCaptured(api);
        assertNotNull( call, "同 API 先 modify 后 monitor：monitor 不应失联，应采集到响应");
        assertEquals(200, call.statusCode());
        assertNotNull(call.responseBody());
        assertTrue( call.responseBody().contains("SUPER"), "monitor 采集到的响应应反映 modify 后的请求（含 SUPER）");
    }

    @Step
    public void monitorThenModifySameApiBothActive() {
        String api = "/demo/api/users";
        // ① 先写 monitor
        RouteDsl.on(page())
                .api(api)
                .monitor()
                    .record(true)
                    .expectStatus(200)
                    .minMatches(1)
                    .autoStopOnMatch(true)
                    .timeout(5)
                .done()
                .start();
        // ② 后写 modify（同 pattern，顺序反转）——验证写出顺序无关，monitor 仍共存
        RouteDsl.on(page())
                .api(api)
                .modifyRequest()
                    .modifyRequestBody("$.role", "SUPER")
                .done()
                .start();

        openOrigin(page());

        String resp = post("/users", "{\"name\":\"NewGuy\",\"email\":\"new@e.com\",\"role\":\"USER\"}");
        JsonNode node = assertJson(resp);
        assertEquals("SUPER", node.at("/role").asText());

        CapturedApiCall call = waitForCaptured(api);
        assertNotNull( call, "同 API 先 monitor 后 modify：monitor 应共存并采集到响应");
        assertEquals(200, call.statusCode());
        assertNotNull(call.responseBody());
        assertTrue( call.responseBody().contains("SUPER"), "monitor 采集到的响应应反映 modify 后的请求（含 SUPER）");
    }

    // ───────────────────────── G. 优先级：mock + monitor 同 pattern ─────────────────────────

    @Step
    public void priorityMockAndMonitorSeesMockedResponse() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .mockBody("{\"users\":[{\"name\":\"MockedUser\"}]}")
                .done()
                .start();
        RouteDsl.on(page())
                .api("/demo/api/users")
                .monitor()
                    .record(true)
                    .minMatches(1)
                    .autoStopOnMatch(true)
                    .timeout(5)
                .done()
                .start();

        openOrigin(page());

        String direct = get("/users");
        assertTrue(direct.contains("MockedUser"));

        CapturedApiCall call = waitForCaptured("/demo/api/users");
        assertNotNull(call);
        assertTrue( call.responseBody().contains("MockedUser"), "monitor 在链尾应采集到 mock 后的响应");
    }

    // ───────────────────────── H1. 资源清理：clear 后规则失效、恢复真实后端 ─────────────────────────

    @Step
    public void singleContextRuleDisabledAfterClear() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .mockBody("{\"users\":[{\"name\":\"MockedUser\"}]}")
                .done()
                .start();

        openOrigin(page());

        assertTrue( get("/users").contains("MockedUser"), "clear 前应命中 mock");

        // 页面级规则须用 clear(Page) 移除，否则 mock 仍然命中
        RouteDsl.clear(page());
        // clear 后刷新，验证规则已彻底失效（页面级请求也恢复真实后端）
        openOrigin(page());

        String after = get("/users");
        assertTrue( after.contains("Alice"), "clear 后应恢复真实响应（含 Alice）");
        assertFalse( after.contains("MockedUser"), "clear 后不应再返回 mock 数据");
    }

    // ───────────────────────── H2. 资源清理：多 context 隔离，互不影响 ─────────────────────────

    @Step
    public void multiContextIsolation() {
        // 第二个独立 context + page（复用框架托管的浏览器实例 PlaywrightManager.getBrowser()）
        BrowserContext ctx2 = PlaywrightManager.getBrowser().newContext();
        Page page2 = ctx2.newPage();
        try {
            RouteDsl.on(page())
                    .api("/demo/api/users")
                    .mock().mockBody("{\"users\":[{\"name\":\"C1MOCK\"}]}").done()
                    .start();
            RouteDsl.on(ctx2)
                    .api("/demo/api/users")
                    .mock().mockBody("{\"users\":[{\"name\":\"C2MOCK\"}]}").done()
                    .start();

            // 两个 context 均在规则注册后再导航
            openOrigin(page());
            openOrigin(page2);

            assertTrue( getVia(page(), "/users").contains("C1MOCK"), "context1 应命中其 mock");
            assertTrue( getVia(page2, "/users").contains("C2MOCK"), "context2 应命中其 mock");

            // 仅清理 context1，不应影响 context2
            // 主页面规则为页面级 → clear(Page)；ctx2 规则为 context 级 → clear(ctx2)
            RouteDsl.clear(page());
            ApiCaptureContext.removeContext(page().context());
            openOrigin(page()); // 清理后刷新

            String c1after = getVia(page(), "/users");
            assertTrue( c1after.contains("Alice"), "context1 清理后应恢复真实响应");
            assertTrue( getVia(page2, "/users").contains("C2MOCK"), "context2 未被清理，仍应命中 mock");
        } finally {
            RouteDsl.clear(ctx2);
            ApiCaptureContext.removeContext(ctx2);
            ctx2.close();
        }
    }

    // ───────────────────────── 断言辅助 ─────────────────────────

    protected JsonNode assertJson(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            fail("响应不是合法 JSON: " + body);
            return null;
        }
    }

    /**
     *  每个 Scenario 开头重置 demo service 的后端数据。
     *
     * <p>POST /api/users 等写操作会真实改动服务端 USERS 列表，若不重置，
     * 前一个 Scenario 写入的数据会泄漏到后续 Scenario（跨用例污染）。
     * 必须在<b>路由规则注册之前</b>调用：reset 路径本身不应被测试规则拦截。
     */
    @Step("route demo: 重置后端数据")
    public void resetDemoData() {
        try {
            post("/reset", "{}");
        } catch (Exception e) {
            logger.warn("[DIAG] reset demo data failed (ignored): {}", e.getMessage());
        }
    }
}
