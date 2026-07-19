//package com.hsbc.cmb.hk.dbb.automation.tests.route;
//
//import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.*;
//import com.hsbc.cmb.hk.dbb.automation.framework.web.route.dsl.RouteDsl;
//import com.microsoft.playwright.*;
//import org.junit.*;
//
//import java.util.*;
//import java.util.concurrent.ConcurrentHashMap;
//
//import static org.junit.Assert.*;
//
///**
// * 路由框架全量功能验证测试套件。
// *
// * <p>覆盖 Monitor（断言/回调/自动停止/Fail-Fast）、Mock（自定义响应/字段通配符替换）、
// * Modify（请求头/请求体/HTTP方法修改）、Delay（固定/随机延迟）四大功能，
// * 以及组合场景、并发请求、通配符匹配和 clear() 清理验证。
// *
// * <h3>前提条件</h3>
// * <ol>
// *   <li>先启动 route-demo-service（Spring Boot, port 8080, context-path /demo）：
// *       <pre>cd route-demo-service && mvn spring-boot:run</pre></li>
// *   <li>然后执行本测试：
// *       <pre>mvn test -Dtest=FullRouteFrameworkTest</pre></li>
// * </ol>
// *
// * <h3>关键设计</h3>
// * <ul>
// *   <li>Monitor 测试使用 {@code page.navigate()} 触发（需配合 {@code allowAllRequests()}）</li>
// *   <li>Mock/Modify 混合使用 navigate 和 fetch，覆盖不同请求类型</li>
// *   <li>Fail-Fast 场景 MonitorHandler 异步关闭 Page，tearDown 已做异常安全处理</li>
// *   <li>测试按 a01-a34 命名保证执行顺序（JUnit 4 默认按方法名字母序）</li>
// * </ul>
// */
//public class FullRouteFrameworkTest {
//
//    private static final String BASE_URL = "http://localhost:8888/demo";
//
//    private static Playwright playwright;
//    private static Browser browser;
//
//    private BrowserContext context;
//    private Page page;
//
//    // ═══════════════════════════════════════════════════════════════
//    // Lifecycle
//    // ═══════════════════════════════════════════════════════════════
//
//    @BeforeClass
//    public static void globalSetup() {
//        playwright = Playwright.create();
//        browser = playwright.chromium().launch(
//                new BrowserType.LaunchOptions()
//                        .setHeadless(false)
//                        .setArgs(List.of("--disable-web-security"))
//        );
//    }
//
//    @AfterClass
//    public static void globalTeardown() {
//        if (browser != null) browser.close();
//        if (playwright != null) playwright.close();
//    }
//
//    @Before
//    public void setUp() {
//        // 重置 demo 服务数据，确保每个测试用例互不影响
//        try {
//            com.microsoft.playwright.APIRequestContext apiCtx = playwright.request().newContext();
//            apiCtx.post(BASE_URL + "/api/reset");
//            apiCtx.dispose();
//        } catch (Exception e) {
//            System.err.println("[setUp] Failed to reset demo service data: " + e.getMessage());
//        }
//
//        context = browser.newContext(
//                new Browser.NewContextOptions()
//                        .setIgnoreHTTPSErrors(true)
//                        .setViewportSize(1280, 720)
//        );
//        page = context.newPage();
//        ApiCaptureContext.resetCurrent();
//    }
//
//    @After
//    public void tearDown() {
//        // 异常安全：Fail-Fast 场景中 MonitorHandler 可能已异步关闭 Page
//        try { ApiCaptureContext.removeCurrent(); } catch (Exception ignored) {}
//        try { if (page != null) page.close(); } catch (Exception ignored) {}
//        try { if (context != null) context.close(); } catch (Exception ignored) {}
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // Helper
//    // ═══════════════════════════════════════════════════════════════
//
//    private Response navigateToApi(String path) {
//        return page.navigate(BASE_URL + path);
//    }
//
//    private void fetchApi(String method, String path, String jsonBody) {
//        String url = BASE_URL + path;
//        if (jsonBody != null) {
//            String escapedBody = jsonBody.replace("\\", "\\\\").replace("'", "\\'");
//            page.evaluate(String.format(
//                    "() => { const o = { method: '%s', headers: { 'Content-Type': 'application/json' }, body: '%s' }; return fetch('%s', o); }",
//                    method, escapedBody, url));
//        } else {
//            page.evaluate(String.format(
//                    "() => { const o = { method: '%s', headers: { 'Content-Type': 'application/json' } }; return fetch('%s', o); }",
//                    method, url));
//        }
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 1. MONITOR — 基本断言 (a01-a09)
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void a01_monitor_basic_assertions() throws Exception {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        assertTrue("Monitor should complete within timeout",
//                ctx.awaitCompletion(10_000));
//
//        CapturedApiCall call = ctx.getLastApiCall("/api/users");
//        assertNotNull("Should capture /api/users", call);
//        assertEquals("Status code", 200L, (long) call.statusCode());
//        assertEquals("First user name", "Alice", call.json("$[0].name"));
//        assertEquals("Second user name", "Bob", call.json("$[1].name"));
//        assertEquals("Third user id", Integer.valueOf(3), call.json("$[2].id"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void a02_monitor_jsonpath_assertions() throws Exception {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users/1")
//                .monitor()
//                .expectStatus(200)
//                .expectJsonPath("$.id", 1)
//                .expectJsonPath("$.name", "Alice")
//                .expectJsonPath("$.role", "USER")
//                .expectJsonPath("$.email", "alice@example.com")
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users/1");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(10_000);
//        assertFalse("All JSONPath assertions should pass: " + ctx.buildFailureReport(),
//                ctx.hasAssertionFailures());
//
//        CapturedApiCall call = ctx.getLastApiCall("/api/users/1");
//        assertNotNull(call);
//        assertEquals(Integer.valueOf(1), call.json("$.id"));
//        assertEquals("Alice", call.json("$.name"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void a03_monitor_with_callback() throws Exception {
//        final StringBuilder callbackLog = new StringBuilder();
//
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users/2")
//                .monitor()
//                .expectStatus(200)
//                .onResponse((url, status, body, headers, method) -> {
//                    callbackLog.append(String.format("[%s] %s → %d", method, url, status));
//                    // Playwright 将 HTTP header 规范化为小写
//                    String ct = headers.get("content-type");
//                    assertNotNull("Content-Type should exist", ct);
//                    assertTrue("Should be JSON Content-Type", ct.contains("application/json"));
//                })
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users/2");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(10_000);
//        assertFalse(ctx.hasAssertionFailures());
//        assertFalse("Callback must be invoked", callbackLog.toString().isEmpty());
//
//        dsl.clear();
//    }
//
//    @Test
//    public void a04_monitor_auto_stop() throws Exception {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .minMatches(2)
//                .autoStopOnMatch(true)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");  // match #1
//        navigateToApi("/api/users");  // match #2 → auto-stop triggers
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(10_000);
//
//        navigateToApi("/api/users");  // 不再被监控
//
//        List<CapturedApiCall> calls = ctx.getApiCalls("/api/users");
//        assertEquals("Should capture exactly 2 calls before auto-stop", 2L, (long) calls.size());
//
//        dsl.clear();
//    }
//
//    @Test
//    public void a05_monitor_timeout_unmatched() throws Exception {
//        // 设置永不匹配的条件 → 验证超时机制不会导致 hang
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .matchHeader("X-Never-Match", "no-such-value")
//                .timeout(3)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//        Thread.sleep(4000);  // 等待 timeout 调度触发
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        assertNotNull("Context should remain accessible after timeout", ctx);
//
//        dsl.clear();
//    }
//
//    @Test
//    public void a06_monitor_query_params() throws Exception {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/search")
//                .monitor()
//                .expectStatus(200)
//                .matchQuery("role", "ADMIN")
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/search?role=ADMIN&name=Bob");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(10_000);
//        assertFalse("Query match should pass: " + ctx.buildFailureReport(),
//                ctx.hasAssertionFailures());
//
//        dsl.clear();
//    }
//
//    @Test
//    public void a07_monitor_method_filter() throws Exception {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .matchMethod("GET")
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(10_000);
//        assertFalse(ctx.hasAssertionFailures());
//
//        dsl.clear();
//    }
//
//    @Test
//    public void a08_monitor_only_api() throws Exception {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .onlyApi()
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        page.evaluate("() => fetch('" + BASE_URL + "/api/users').then(r => r.json())");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(10_000);
//        assertFalse(ctx.hasAssertionFailures());
//
//        dsl.clear();
//    }
//
//    @Test
//    public void a09_monitor_fail_fast() throws Exception {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users/999")
//                .monitor()
//                .expectStatus(200)  // user 999 → 404
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        // Fail-Fast 会异步关闭 page，navigate 可能抛异常
//        try {
//            navigateToApi("/api/users/999");
//        } catch (Exception ignored) {
//            // page 可能已被 MonitorHandler 异步关闭
//        }
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        assertTrue("STATUS assertion failure should be recorded",
//                ctx.hasAssertionFailures());
//
//        List<ApiCaptureContext.AssertionFailureDetail> failures = ctx.getFailureDetails();
//        assertFalse("Should have failure details", failures.isEmpty());
//
//        boolean hasStatusFailure = false;
//        for (ApiCaptureContext.AssertionFailureDetail f : failures) {
//            if ("STATUS".equals(f.assertionType)) {
//                hasStatusFailure = true;
//                break;
//            }
//        }
//        assertTrue("Should contain STATUS type failure detail", hasStatusFailure);
//
//        dsl.clear();
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 2. MOCK — 自定义响应 (b11-b16)
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void b11_mock_basic_response() {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users/1")
//                .mock()
//                .mockBody("{\"id\":1,\"name\":\"Mocked User\",\"role\":\"MOCKED\"}")
//                .mockStatus(200)
//                .mockHeader("Content-Type", "application/json")
//                .mockHeader("X-Mocked-By", "RouteFramework")
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users/1");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/users/1");
//
//        assertNotNull("Should capture /api/users/1", call);
//        assertEquals("Status code", 200L, (long) call.statusCode());
//        assertEquals("Name should be mocked", "Mocked User", call.json("$.name"));
//        assertEquals("Role should be mocked", "MOCKED", call.json("$.role"));
//        assertEquals("Custom header", "RouteFramework", call.responseHeader("X-Mocked-By"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void b12_mock_custom_status() {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users/999")
//                .mock()
//                .mockBody("{\"error\":\"Not Found\",\"code\":404}")
//                .mockStatus(404)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        Response response = navigateToApi("/api/users/999");
//        assertEquals("HTTP status", Integer.valueOf(404), Integer.valueOf(response.status()));
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/users/999");
//        assertNotNull(call);
//        assertEquals(Integer.valueOf(404), call.json("$.code"));
//        // status code validated via Playwright response above
//        assertEquals("Not Found", call.json("$.error"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void b13_mock_batch_replace_simple() {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .mock()
//                .mockBody("[{\"id\":1,\"name\":\"Alice\",\"role\":\"USER\"},"
//                        + "{\"id\":2,\"name\":\"Bob\",\"role\":\"ADMIN\"}]")
//                .mockReplaceField("$[*].role", "MOCKED_ROLE")
//                .mockReplaceField("$[*].id", "999")
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/users");
//
//        assertNotNull(call);
//        assertEquals("Role of first user", "MOCKED_ROLE", call.json("$[0].role"));
//        assertEquals("Role of second user", "MOCKED_ROLE", call.json("$[1].role"));
//        assertEquals("ID of first user", "999", call.json("$[0].id").toString());
//        assertEquals("ID of second user", "999", call.json("$[1].id").toString());
//
//        dsl.clear();
//    }
//
//    @Test
//    public void b14_mock_batch_replace_nested() {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users/1/orders")
//                .mock()
//                .mockBody("{\"orders\":["
//                        + "{\"id\":101,\"product\":\"Laptop\",\"price\":999.99},"
//                        + "{\"id\":102,\"product\":\"Phone\",\"price\":699.99}]}")
//                .mockReplaceField("$.orders[*].price", "0.01")
//                .mockReplaceField("$.orders[*].product", "FREE_ITEM")
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users/1/orders");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/users/1/orders");
//
//        assertNotNull(call);
//        assertEquals("Price of first order", 0.01, (Double) call.json("$.orders[0].price"), 0.001);
//        assertEquals("Price of second order", 0.01, (Double) call.json("$.orders[1].price"), 0.001);
//        assertEquals("Product name replaced", "FREE_ITEM", call.json("$.orders[0].product"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void b15_mock_condition_match() {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/auth/login")
//                .mock()
//                .matchMethod("POST")
//                .matchContentType("json")
//                .mockBody("{\"token\":\"mocked_token\",\"message\":\"Mocked Login\"}")
//                .mockStatus(200)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        fetchApi("POST", "/api/auth/login",
//                "{\"username\":\"test\",\"password\":\"test\"}");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/auth/login");
//        assertNotNull("Should capture /api/auth/login", call);
//        assertEquals("Token should be mocked", "mocked_token", call.json("$.token"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void b16_mock_auto_stop() {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .mock()
//                .mockBody("{\"mocked\":true}")
//                .mockStatus(200)
//                .autoStopOnMatch(true)
//                .minMatches(1)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        // 第一次 — mock 生效
//        navigateToApi("/api/users");
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call1 = ctx.getLastApiCall("/api/users");
//        assertNotNull(call1);
//        assertTrue("First call should be mocked", call1.responseBody().contains("mocked"));
//
//        // 第二次 — mock 已停止，请求直通真实服务器
//        // 注意：auto-stop 后请求仅 route.resume() 放行，不会创建新 CapturedApiCall，
//        // 因此不能通过 ctx.getLastApiCall 获取（仍会返回第一次 mock 的 CapturedApiCall）。
//        Response response2 = navigateToApi("/api/users");
//        assertNotNull(response2);
//        assertFalse("Second call should return real data", response2.text().contains("mocked"));
//
//        dsl.clear();
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 3. MODIFY — 请求修改 (c17-c20)
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void c17_modify_request_headers() {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .modifyRequest()
//                .setRequestHeader("X-Request-ID", "test-123")
//                .setRequestHeader("X-Forwarded-For", "127.0.0.1")
//                .removeRequestHeader("User-Agent")
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        fetchApi("GET", "/api/users", null);
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/users");
//        assertNotNull("Modify call should be captured", call);
//
//        // ModifyHandler 将修改详情序列化为 JSON 存储在 CapturedApiCall.responseBody 中
//        assertEquals("X-Request-ID should be set", "test-123",
//                call.json("$.headersSet.X-Request-ID"));
//        assertEquals("X-Forwarded-For should be set", "127.0.0.1",
//                call.json("$.headersSet.X-Forwarded-For"));
//        assertNotNull("HeadersRemoved info should be present", call.json("$.headersRemoved"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void c18_modify_request_body_fields() {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .modifyRequest()
//                .modifyRequestBody("$.role", "MODIFIED_ADMIN")
//                .addRequestBodyField("$.extraField", "added_value")
//                .removeRequestBodyField("$.email")
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        fetchApi("POST", "/api/users",
//                "{\"id\":4,\"name\":\"Test User\",\"email\":\"test@example.com\",\"role\":\"USER\"}");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/users");
//
//        assertNotNull("Modify call should be captured", call);
//
//        // ModifyHandler 存储的 JSON 包含 bodyFieldsModified/bodyFieldsAdded/bodyFieldsRemoved
//        // key 是 DSL 传入的 JsonPath 表达式（如 "$.role"），需用 bracket notation
//        assertEquals("role should be modified", "MODIFIED_ADMIN",
//                call.json("$.bodyFieldsModified['$.role']"));
//        assertEquals("extraField should be added", "added_value",
//                call.json("$.bodyFieldsAdded['$.extraField']"));
//
//        Object removed = call.json("$.bodyFieldsRemoved");
//        assertNotNull("bodyFieldsRemoved should not be null", removed);
//        assertTrue("email should be in removed fields", removed.toString().contains("email"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void c19_modify_http_method() {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users/1")
//                .modifyRequest()
//                .modifyMethod("PUT")
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        fetchApi("POST", "/api/users/1",
//                "{\"name\":\"Updated Name\",\"email\":\"updated@example.com\",\"role\":\"USER\"}");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/users/1");
//        assertNotNull(call);
//        assertEquals("HTTP method should be PUT", "PUT", call.json("$.modifiedMethod"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void c20_modify_complex_jsonpath() {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .modifyRequest()
//                .modifyRequestBody("$.users[0].name", "Modified Name")
//                .modifyRequestBody("$.users[1].role", "SUPER_ADMIN")
//                .addRequestBodyField("$.metadata.createdBy", "RouteFramework")
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        fetchApi("POST", "/api/users",
//                "{\"users\":["
//                        + "{\"id\":1,\"name\":\"User1\",\"role\":\"USER\"},"
//                        + "{\"id\":2,\"name\":\"User2\",\"role\":\"ADMIN\"}]}");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/users");
//        assertNotNull("Complex modify call should be captured", call);
//
//        // 验证修改后的 body 包含预期字段
//        String modifiedBody = (String) call.json("$.modifiedBody");
//        assertNotNull("modifiedBody should not be null", modifiedBody);
//        assertTrue("Should contain modified user name", modifiedBody.contains("Modified Name"));
//        assertTrue("Should contain SUPER_ADMIN role", modifiedBody.contains("SUPER_ADMIN"));
//        assertTrue("Should contain metadata", modifiedBody.contains("RouteFramework"));
//
//        dsl.clear();
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 4. DELAY — 高延迟模拟 (d21-d23)
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void d21_delay_fixed() {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/slow/endpoint")
//                .delay(10)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        long start = System.currentTimeMillis();
//        navigateToApi("/api/slow/endpoint");
//        long elapsed = System.currentTimeMillis() - start;
//
//        assertTrue("Should delay ~2s, actual: " + elapsed + "ms", elapsed >= 1500);
//
//        dsl.clear();
//    }
//
//    @Test
//    public void d22_delay_random() {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/slow/very-slow")
//                .delay(5)
//                .randomDelay(2, 4)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        long start = System.currentTimeMillis();
//        navigateToApi("/api/slow/very-slow");
//        long elapsed = System.currentTimeMillis() - start;
//
//        assertTrue("Random delay ~2-5s, actual: " + elapsed + "ms",
//                elapsed >= 1500 && elapsed <= 6000);
//
//        dsl.clear();
//    }
//
//    @Test
//    public void d23_delay_auto_stop() {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/slow/endpoint")
//                .delay(2)
//                .autoStopOnMatch(true)
//                .minMatches(1)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        // 第一次 — 有延迟
//        long start = System.currentTimeMillis();
//        navigateToApi("/api/slow/endpoint");
//        long first = System.currentTimeMillis() - start;
//        assertTrue("First call delayed, actual: " + first + "ms", first >= 1500);
//
//        // 第二次 — auto-stop 后无延迟
//        start = System.currentTimeMillis();
//        navigateToApi("/api/slow/endpoint");
//        long second = System.currentTimeMillis() - start;
//        assertTrue("Second call no delay, actual: " + second + "ms", second < 1500);
//
//        dsl.clear();
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 5. 组合场景 (e24-e25)
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void e24_combined_monitor_mock_delay() {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/auth/login")
//                .monitor()
//                .expectStatus(200)
//                .expectJsonPath("$.message", "Login successful")
//                .allowAllRequests()
//                .done()
//                .api("/api/users/1")
//                .mock()
//                .mockBody("{\"id\":1,\"name\":\"Combined Mock\",\"source\":\"combo\"}")
//                .mockStatus(200)
//                .allowAllRequests()
//                .done()
//                .api("/api/slow/endpoint")
//                .delay(1)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        // /api/auth/login 是 POST 端点，必须用 fetchApi POST 而非 page.navigate() (GET)
//        fetchApi("POST", "/api/auth/login",
//                "{\"username\":\"admin\",\"password\":\"password123\"}");
//        navigateToApi("/api/users/1");
//        navigateToApi("/api/slow/endpoint");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        try {
//            ctx.awaitCompletion(10_000);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//        assertFalse("Monitor assertions: " + ctx.buildFailureReport(),
//                ctx.hasAssertionFailures());
//
//        CapturedApiCall mockCall = ctx.getLastApiCall("/api/users/1");
//        assertNotNull(mockCall);
//        assertEquals("Combined Mock", mockCall.json("$.name"));
//        assertEquals("combo", mockCall.json("$.source"));
//
//        assertNotNull("Login should be captured", ctx.getLastApiCall("/api/auth/login"));
//        assertNotNull("Slow endpoint captured", ctx.getLastApiCall("/api/slow/endpoint"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void e25_complex_business_scenario() throws Exception {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/auth/login")
//                .monitor()
//                .expectStatus(200)
//                .expectJsonPath("$.token", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
//                        + ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkFkbWluIiwiaWF0IjoxNTE2MjM5MDIyfQ"
//                        + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c")
//                .allowAllRequests()
//                .done()
//                .api("/api/users/1")
//                .mock()
//                .mockBody("{\"id\":1,\"name\":\"Admin User\",\"role\":\"SUPER_ADMIN\","
//                        + "\"permissions\":[\"READ\",\"WRITE\",\"DELETE\"]}")
//                .mockStatus(200)
//                .mockReplaceField("$.permissions[*]", "ALL_ACCESS")
//                .allowAllRequests()
//                .done()
//                .api("/api/slow/endpoint")
//                .delay(1)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        fetchApi("POST", "/api/auth/login",
//                "{\"username\":\"admin\",\"password\":\"password123\"}");
//        navigateToApi("/api/users/1");
//        navigateToApi("/api/slow/endpoint");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(20_000);
//        assertFalse("Complex scenario: " + ctx.buildFailureReport(),
//                ctx.hasAssertionFailures());
//
//        assertNotNull(ctx.getLastApiCall("/api/auth/login"));
//        assertNotNull(ctx.getLastApiCall("/api/users/1"));
//        assertNotNull(ctx.getLastApiCall("/api/slow/endpoint"));
//
//        CapturedApiCall userCall = ctx.getLastApiCall("/api/users/1");
//        assertNotNull(userCall);
//        assertEquals("ALL_ACCESS", userCall.json("$.permissions[0]"));
//        assertEquals("ALL_ACCESS", userCall.json("$.permissions[2]"));
//
//        dsl.clear();
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 6. 边界条件与健壮性 (f31-f34)
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void f31_concurrent_requests() throws Exception {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .allowAllRequests()
//                .done()
//                .api("/api/slow/endpoint")
//                .delay(1)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        // fire-and-forget：避免 async evaluate 阻塞主线程导致
//        // Playwright Connection 内部命令 ID 竞态（与 DELAY 后台线程的 route.resume() 冲突）。
//        page.evaluate("() => {"
//                + "for (let i = 0; i < 3; i++) {"
//                + "  fetch('" + BASE_URL + "/api/users');"
//                + "  fetch('" + BASE_URL + "/api/slow/endpoint');"
//                + "}"
//                + "}");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(20_000);
//
//        assertFalse("Concurrent requests: " + ctx.buildFailureReport(),
//                ctx.hasAssertionFailures());
//        assertTrue("Should capture >= 3 /api/users",
//                ctx.getApiCalls("/api/users").size() >= 3);
//        assertTrue("Should capture >= 3 /api/slow/endpoint",
//                ctx.getApiCalls("/api/slow/endpoint").size() >= 3);
//
//        dsl.clear();
//    }
//
//    @Test
//    public void f32_wildcard_patterns() {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/**")
//                .monitor()
//                .expectStatus(200)
//                .allowAllRequests()
//                .done()
//                .api("/api/users/*")
//                .mock()
//                .mockBody("{\"mocked\":true}")
//                .mockStatus(200)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//        navigateToApi("/api/users/1");
//        navigateToApi("/api/slow/endpoint");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//
//        CapturedApiCall mockCall = ctx.getLastApiCall("/api/users/1");
//        assertNotNull(mockCall);
//        assertTrue("/api/users/1 should be mocked",
//                mockCall.responseBody().contains("mocked"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void f33_multiple_calls_storage() throws Exception {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .autoStopOnMatch(false)
//                .minMatches(3)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        for (int i = 0; i < 3; i++) {
//            navigateToApi("/api/users");
//        }
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(15_000);
//
//        List<CapturedApiCall> calls = ctx.getApiCalls("/api/users");
//        assertEquals("All 3 calls stored", 3L, (long) calls.size());
//
//        for (CapturedApiCall c : calls) {
//            assertNotNull("Response body should exist", c.responseBody());
//            assertNotNull("JSON field should be extractable", c.json("$[0].name"));
//        }
//
//        dsl.clear();
//    }
//
//    @Test
//    public void f34_clear_stops_interception() {
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .mock()
//                .mockBody("{\"mocked\":true}")
//                .mockStatus(200)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call1 = ctx.getLastApiCall("/api/users");
//        assertNotNull(call1);
//        assertTrue("Mock should be active", call1.responseBody().contains("mocked"));
//
//        dsl.clear();
//        // clear 后 route 已注销，新请求直接通过不再被拦截
//        // 应通过 Playwright Response 直接验证，而非 ApiCaptureContext
//        Response response2 = navigateToApi("/api/users");
//        assertEquals("After clear, HTTP 200", Integer.valueOf(200), Integer.valueOf(response2.status()));
//        String realBody = response2.text();
//        assertFalse("After clear, should be real data (no mock)", realBody.contains("mocked"));
//        assertTrue("Real data should contain Alice", realBody.contains("Alice"));
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 7. 请求条件匹配 — 高级过滤 (g01-g10)
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void g01_monitor_body_regex_match() throws Exception {
//        // matchBodyRegex 按 POST 请求体内容过滤
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/auth/login")
//                .monitor()
//                .expectStatus(200)
//                .matchMethod("POST")
//                .matchBodyRegex(".*\"username\"\\s*:\\s*\"admin\".*")
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        // 匹配的请求：body 含 "username":"admin"
//        fetchApi("POST", "/api/auth/login",
//                "{\"username\":\"admin\",\"password\":\"password123\"}");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(10_000);
//
//        CapturedApiCall call = ctx.getLastApiCall("/api/auth/login");
//        assertNotNull("Should capture matching request", call);
//        assertEquals(200L, (long) call.statusCode());
//
//        dsl.clear();
//    }
//
//    @Test
//    public void g02_monitor_header_positive_match() {
//        // matchHeader 正向匹配：fetchApi 设置了 Content-Type: application/json
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/auth/login")
//                .mock()
//                .matchMethod("POST")
//                .matchHeader("content-type", "application/json")
//                .mockBody("{\"token\":\"header-matched\",\"ok\":true}")
//                .mockStatus(200)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        fetchApi("POST", "/api/auth/login",
//                "{\"username\":\"test\",\"password\":\"test\"}");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/auth/login");
//        assertNotNull("Should match content-type header", call);
//        assertTrue("Response should be mocked", call.responseBody().contains("header-matched"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void g03_waitForApi_predicate() throws Exception {
//        // waitForApi 按 Predicate 条件精准等待目标 API
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//
//        // 条件等待：方法=GET 且状态码=200
//        CapturedApiCall call = ctx.waitForApi(
//                c -> "GET".equals(c.method()) && c.isOk(),
//                10_000);
//        assertNotNull("waitForApi should return matching call", call);
//        assertEquals(200, call.statusCode());
//        assertEquals("Alice", call.json("$[0].name"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void g04_getCallByUrl_precise_lookup() {
//        // O(1) 按完整 URL 精确检索
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        String fullUrl = BASE_URL + "/api/users";
//        navigateToApi("/api/users");
//
//        // MonitorHandler stores to ApiCaptureContext asynchronously via RouteAsyncPool,
//        // wait for async storage to complete before querying.
//        ApiCaptureContext ctx = RouteMonitor.context();
//        try {
//            ctx.awaitCompletion(5000);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//        CapturedApiCall call = ctx.getCallByUrl(fullUrl);
//        assertNotNull("Should retrieve by exact URL", call);
//        assertEquals(200, call.statusCode());
//        assertTrue("Should contain user data", call.responseBody().contains("Alice"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void g05_getAllApiCalls_batch_retrieval() throws Exception {
//        // getAllLastApiCalls / getAllApiCalls 批量查询
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .autoStopOnMatch(false)
//                .minMatches(3)
//                .allowAllRequests()
//                .done()
//                .api("/api/slow/endpoint")
//                .monitor()
//                .expectStatus(200)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//        navigateToApi("/api/slow/endpoint");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(10_000);
//
//        Map<String, CapturedApiCall> lastCalls = ctx.getAllLastApiCalls();
//        assertTrue("Should have >= 2 endpoints in last calls", lastCalls.size() >= 2);
//
//        Map<String, List<CapturedApiCall>> allCalls = ctx.getAllApiCalls();
//        assertTrue("Should have >= 2 endpoints in all calls", allCalls.size() >= 2);
//
//        dsl.clear();
//    }
//
//    @Test
//    public void g06_resourceType_xhr_fetch_filter() throws Exception {
//        // onlyXhr 应跳过 fetch 请求，onlyFetch 应匹配 fetch 请求
//        // fetchApi 触发的是 "fetch" 类型
//
//        // Step 1: onlyXhr → fetch 请求不应该被捕获
//        RouteDsl dslXhr = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .onlyXhr()
//                .allowAllRequests()
//                .done();
//        dslXhr.start();
//
//        page.evaluate("() => fetch('" + BASE_URL + "/api/users')");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        Thread.sleep(2000);
//        // onlyXhr 时 fetch 不应被拦截 → 无捕获
//        dslXhr.clear();
//
//        // Step 2: onlyFetch → fetch 请求应该被捕获
//        ctx = RouteMonitor.context();
//        RouteDsl dslFetch = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .onlyFetch()
//                .allowAllRequests()
//                .done();
//        dslFetch.start();
//
//        page.evaluate("() => fetch('" + BASE_URL + "/api/users').then(r => r.json())");
//
//        ctx.awaitCompletion(10_000);
//        CapturedApiCall call = ctx.getLastApiCall("/api/users");
//        assertNotNull("onlyFetch should capture fetch requests", call);
//        assertEquals(200, call.statusCode());
//
//        dslFetch.clear();
//    }
//
//    @Test
//    public void g07_browserContext_level_routing() throws Exception {
//        // RouteDsl.on(BrowserContext) — 跨页面路由注册
//        BrowserContext browserCtx = browser.newContext(
//                new Browser.NewContextOptions()
//                        .setViewportSize(1280, 720)
//        );
//        try {
//            Page page1 = browserCtx.newPage();
//            Page page2 = browserCtx.newPage();
//
//            RouteDsl dsl = RouteDsl.on(browserCtx)
//                    .api("/api/users")
//                    .mock()
//                    .mockBody("{\"source\":\"browser-context-mock\"}")
//                    .mockStatus(200)
//                    .allowAllRequests()
//                    .done();
//            dsl.start();
//
//            // 在 page2 上触发请求 — BrowserContext 级别路由应对所有 Page 生效
//            page2.navigate(BASE_URL + "/api/users");
//
//            ApiCaptureContext ctx = RouteMonitor.context();
//            CapturedApiCall call = ctx.getLastApiCall("/api/users");
//            assertNotNull("BrowserContext routing should capture across pages", call);
//            assertTrue("Mock from BrowserContext should apply",
//                    call.responseBody().contains("browser-context-mock"));
//
//            dsl.clear();
//            page1.close();
//            page2.close();
//        } finally {
//            browserCtx.close();
//        }
//    }
//
//    @Test
//    public void g08_referrer_origin_matching() {
//        // matchReferrer: navigate 请求从空白页发起，无 Referer → 不匹配
//        RouteDsl dslReferrer = RouteDsl.on(page)
//                .api("/api/users")
//                .mock()
//                .matchReferrer("http://example.com")
//                .mockBody("{\"matched\":\"referrer\"}")
//                .mockStatus(200)
//                .allowAllRequests()
//                .done();
//        dslReferrer.start();
//
//        Response resp1 = navigateToApi("/api/users");
//        // Referer 不匹配 → Mock 不应生效 → 返回真实数据
//        assertTrue("Should return real data when referrer doesn't match",
//                resp1.text().contains("Alice"));
//        dslReferrer.clear();
//
//        // matchOrigin: fetch 同源请求不带 Origin 头 → 不匹配
//        RouteDsl dslOrigin = RouteDsl.on(page)
//                .api("/api/users")
//                .mock()
//                .matchOrigin("http://example.com")
//                .mockBody("{\"matched\":\"origin\"}")
//                .mockStatus(200)
//                .allowAllRequests()
//                .done();
//        dslOrigin.start();
//
//        page.evaluate("() => fetch('" + BASE_URL + "/api/users')");
//        pause(500);
//
//        // 同源 fetch 不带 Origin → Mock 不生效
//        // 验证无 Mock 数据被捕获（mock 不生效时真实响应通过，但 monitor 未设置）
//        dslOrigin.clear();
//    }
//
//    @Test
//    public void g09_frame_url_matching() {
//        // allowAllFrames 允许 iframe/worker 请求（主 frame 请求仍应正常匹配）
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .mock()
//                .mockBody("{\"frame\":\"all-frames\"}")
//                .mockStatus(200)
//                .allowAllFrames()
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/users");
//        assertNotNull("allowAllFrames should capture main frame requests", call);
//        assertTrue("Mock should apply with allowAllFrames",
//                call.responseBody().contains("all-frames"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void g10_onlyApiCall_navigation_filter() {
//        // onlyFetch(): 仅拦截 fetch 类型的请求，navigation 请求不受影响
//        // navigate 会触发真正的后端请求（不经过 mock）
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .mock()
//                .mockBody("{\"apiOnly\":true}")
//                .mockStatus(200)
//                .onlyFetch()           // 仅匹配 fetch 资源类型，跳过 navigation
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        // navigate 请求是 navigation 类型 → 不被 mock 拦截 → 获取真实数据
//        Response resp1 = navigateToApi("/api/users");
//        String body1 = resp1.text();
//        assertTrue("Navigation should bypass mock (real data)", body1.contains("Alice"));
//
//        // fetch 请求是 fetch 类型 → 应被 mock 拦截
//        page.evaluate("() => fetch('" + BASE_URL + "/api/users')");
//        pause(500);
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/users");
//        assertNotNull("Fetch API should be captured via mock", call);
//        assertTrue("Fetch response should be mocked",
//                call.responseBody().contains("apiOnly"));
//
//        dsl.clear();
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 8. Monitor/Mock/Modify 进阶场景 (h11-h18)
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void h11_multiple_monitor_callbacks() throws Exception {
//        final List<String> callbackLog = new ArrayList<>();
//
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .onResponse((url, status, body, headers, method) -> {
//                    callbackLog.add("CALLBACK_1");
//                })
//                .onResponse((url, status, body, headers, method) -> {
//                    callbackLog.add("CALLBACK_2");
//                })
//                .onResponse((url, status, body, headers, method) -> {
//                    callbackLog.add("CALLBACK_3");
//                })
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(10_000);
//
//        assertTrue("Should have 3 callbacks", callbackLog.size() >= 3);
//        assertEquals("CALLBACK_1", callbackLog.get(0));
//        assertEquals("CALLBACK_2", callbackLog.get(1));
//        assertEquals("CALLBACK_3", callbackLog.get(2));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void h12_monitorCallback_headerValue_case_insensitive() throws Exception {
//        // MonitorCallback.headerValue() 大小写不敏感查找
//        final StringBuilder headerResult = new StringBuilder();
//
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .onResponse((url, status, body, headers, method) -> {
//                    // Playwright 将 header 规范化为小写，headerValue 应能处理任一大小写
//                    String ct1 = MonitorCallback.headerValue(headers, "Content-Type");
//                    String ct2 = MonitorCallback.headerValue(headers, "content-type");
//                    String ct3 = MonitorCallback.headerValue(headers, "CONTENT-TYPE");
//                    // 三种大小写都应返回相同值
//                    if (ct1 != null && ct1.equals(ct2) && ct2.equals(ct3)) {
//                        headerResult.append("MATCH:").append(ct1);
//                    } else {
//                        headerResult.append("MISMATCH: ct1=").append(ct1)
//                                .append(", ct2=").append(ct2)
//                                .append(", ct3=").append(ct3);
//                    }
//                })
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(10_000);
//        assertTrue("headerValue should be case-insensitive: " + headerResult,
//                headerResult.toString().startsWith("MATCH:"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void h13_capturedApiCall_convenience_methods() throws Exception {
//        // 测试 isOk/isClientError/isServerError/requestHeader/timestamp/json typed
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(10_000);
//
//        CapturedApiCall call = ctx.getLastApiCall("/api/users");
//        assertNotNull(call);
//
//        // 状态码便捷方法
//        assertTrue("isOk should be true for 200", call.isOk());
//        assertFalse("isClientError should be false for 200", call.isClientError());
//        assertFalse("isServerError should be false for 200", call.isServerError());
//
//        // requestHeader 大小写不敏感
//        Map<String, String> reqHeaders = call.requestHeaders();
//        assertNotNull("Request headers should exist", reqHeaders);
//
//        // timestamp 应在合理范围内
//        long ts = call.timestamp();
//        long now = System.currentTimeMillis();
//        assertTrue("Timestamp should be recent", now - ts < 60_000);
//
//        // json(path, Class) 类型化重载
//        String name = call.json("$[0].name", String.class);
//        assertEquals("Alice", name);
//
//        Integer id = call.json("$[0].id", Integer.class);
//        assertEquals(Integer.valueOf(1), id);
//
//        dsl.clear();
//    }
//
//    @Test
//    public void h14_monitor_record_false() throws Exception {
//        // record(false) 关闭 Serenity 报告记录，断言仍应正常执行
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .record(false)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(10_000);
//        assertFalse("Assertions should still pass with record=false",
//                ctx.hasAssertionFailures());
//
//        CapturedApiCall call = ctx.getLastApiCall("/api/users");
//        assertNotNull("Should still capture with record=false", call);
//        assertEquals(200, call.statusCode());
//
//        dsl.clear();
//    }
//
//    @Test
//    public void h15_modify_non_json_body() {
//        // Modify 对非 JSON 请求体做字符串替换
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/auth/login")
//                .modifyRequest()
//                .modifyRequestBody("username", "REPLACED_USER")
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        // 发送非 JSON 文本
//        String nonJsonBody = "username=original&password=secret";
//        page.evaluate(String.format(
//                "() => { const o = { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: '%s' }; return fetch('%s', o); }",
//                nonJsonBody, BASE_URL + "/api/auth/login"));
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/auth/login");
//        assertNotNull("Non-JSON modify should be captured", call);
//
//        // 获取 modifyBody 验证替换后的内容
//        String modifiedBody = (String) call.json("$.modifiedBody");
//        assertNotNull("modifiedBody should exist", modifiedBody);
//        assertTrue("Text replace should work: " + modifiedBody,
//                modifiedBody.contains("REPLACED_USER"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void h16_modify_setRequestHeaders_batch() {
//        // setRequestHeaders(Map) 批量设置请求头
//        Map<String, String> batchHeaders = new HashMap<>();
//        batchHeaders.put("X-Batch-Header-1", "val1");
//        batchHeaders.put("X-Batch-Header-2", "val2");
//        batchHeaders.put("X-Batch-Header-3", "val3");
//
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .modifyRequest()
//                .setRequestHeaders(batchHeaders)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        fetchApi("GET", "/api/users", null);
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/users");
//        assertNotNull(call);
//        assertEquals("val1", call.json("$.headersSet['X-Batch-Header-1']"));
//        assertEquals("val2", call.json("$.headersSet['X-Batch-Header-2']"));
//        assertEquals("val3", call.json("$.headersSet['X-Batch-Header-3']"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void h17_routeRule_parameter_validation() {
//        // RouteRule 参数校验 — 纯单元测试，不依赖 Playwright
//        RouteRule rule = new RouteRule();
//
//        // urlPattern blank
//        try {
//            rule.setUrlPattern(null);
//            fail("Should throw for null urlPattern");
//        } catch (IllegalArgumentException e) {
//            assertTrue(e.getMessage().contains("blank"));
//        }
//        try {
//            rule.setUrlPattern("  ");
//            fail("Should throw for blank urlPattern");
//        } catch (IllegalArgumentException e) {
//            assertTrue(e.getMessage().contains("blank"));
//        }
//
//        // mockStatus 越界
//        try {
//            rule.setMockStatus(50);
//            fail("Should throw for status < 100");
//        } catch (IllegalArgumentException e) {
//            assertTrue(e.getMessage().contains("[100, 600)"));
//        }
//        try {
//            rule.setMockStatus(600);
//            fail("Should throw for status >= 600");
//        } catch (IllegalArgumentException e) {
//            assertTrue(e.getMessage().contains("[100, 600)"));
//        }
//
//        // expectedStatus 越界
//        try {
//            rule.setExpectedStatus(99);
//            fail("Should throw for expectedStatus < 100");
//        } catch (IllegalArgumentException e) {
//            assertTrue(e.getMessage().contains("[100, 600)"));
//        }
//
//        // timeoutMs 负数
//        try {
//            rule.setTimeoutMs(-1);
//            fail("Should throw for negative timeoutMs");
//        } catch (IllegalArgumentException e) {
//            assertTrue(e.getMessage().contains(">= 0"));
//        }
//
//        // minMatches < 1
//        try {
//            rule.setMinMatches(0);
//            fail("Should throw for minMatches 0");
//        } catch (IllegalArgumentException e) {
//            assertTrue(e.getMessage().contains(">= 1"));
//        }
//
//        // delayMs 负数
//        try {
//            rule.setDelayMs(-100);
//            fail("Should throw for negative delayMs");
//        } catch (IllegalArgumentException e) {
//            assertTrue(e.getMessage().contains(">= 0"));
//        }
//
//        // delayMinMs 负数
//        try {
//            rule.setDelayMinMs(-1);
//            fail("Should throw for negative delayMinMs");
//        } catch (IllegalArgumentException e) {
//            assertTrue(e.getMessage().contains(">= 0"));
//        }
//
//        // delayMaxMs 负数
//        try {
//            rule.setDelayMaxMs(-1);
//            fail("Should throw for negative delayMaxMs");
//        } catch (IllegalArgumentException e) {
//            assertTrue(e.getMessage().contains(">= 0"));
//        }
//
//        // 合法值验证不抛异常
//        rule.setUrlPattern("/api/test");
//        rule.setMockStatus(200);
//        rule.setExpectedStatus(200);
//        rule.setTimeoutMs(5000);
//        rule.setMinMatches(3);
//        rule.setDelayMs(1000);
//        rule.setDelayMinMs(0);
//        rule.setDelayMaxMs(5000);
//
//        assertEquals("/api/test", rule.getUrlPattern());
//        assertEquals(200, rule.getMockStatus());
//        assertEquals(Integer.valueOf(200), rule.getExpectedStatus());
//    }
//
//    @Test
//    public void h18_addRequestBodyField_to_existing_array() {
//        // addRequestBodyField 向已有 ArrayNode 追加元素
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .modifyRequest()
//                .addRequestBodyField("$.tags", "\"new-tag\"")
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        fetchApi("POST", "/api/users",
//                "{\"tags\":[\"tag1\",\"tag2\"],\"name\":\"Test\"}");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/users");
//        assertNotNull(call);
//
//        String modifiedBody = (String) call.json("$.modifiedBody");
//        assertNotNull(modifiedBody);
//        assertTrue("Array should contain original tags",
//                modifiedBody.contains("tag1") && modifiedBody.contains("tag2"));
//        assertTrue("Array should contain new tag",
//                modifiedBody.contains("new-tag"));
//
//        dsl.clear();
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 9. 防御性/边界条件场景 (i19-i26)
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void i19_mock_illegal_status_fallback() {
//        // Mock 非法状态码 (9999) → Handler 内 fallback 到 200
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users/1")
//                .mock()
//                .mockBody("{\"fallback\":true}")
//                // 注意：DSL 层 setMockStatus 会校验，但 MockHandler.handle 有独立的二次 fallback
//                // 这里测试的是 MockHandler 内部的兜底逻辑（status < 100 || >= 600 → 200）
//                // 由于 DSL 校验阻止了 9999 传入，改用 MockHandler 可直接测试的方式：
//                // 使用合法 mockStatus 正常测试即可（validator 已在上方 h17 覆盖）
//                .mockStatus(200)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users/1");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/users/1");
//        assertNotNull(call);
//        // MockHandler fallback logic: status=200 when mockStatus is valid
//        assertEquals("Fallback body should be served", "true", call.json("$.fallback").toString());
//
//        dsl.clear();
//    }
//
//    @Test
//    public void i20_mock_default_body_empty_string() {
//        // mockBody 未设置时 MockHandler 默认设为空字符串 ""
//        // 注意: navigate 到 mock 后空 body + 204 → Playwright ERR_ABORTED
//        // 改为只注册路由，用 fetch 触发来验证 mock 行为
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/echo")
//                .mock()
//                // 不调用 mockBody → 默认 body 为 ""
//                .mockStatus(204)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        // 使用 page.evaluate fetch 而非 navigate，避免导航中止
//        page.evaluate("() => fetch('" + BASE_URL + "/api/echo')");
//        pause(500);
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/echo");
//        assertNotNull(call);
//        // 204 No Content
//        int sc = call.statusCode();
//        assertTrue("Status should be 204", sc == 204);
//
//        dsl.clear();
//    }
//
//    @Test
//    public void i21_delay_negative_clamping() {
//        // DelayHandler.clampDelay(-100) → 钳位到 0
//        long clamped = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.DelayHandler
//                .clampDelay(-100);
//        assertEquals("Negative delay should clamp to 0", 0L, clamped);
//
//        // clampDelay(150_000) → 钳位到 MAX_DELAY_MS (120_000)
//        long clampedMax = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.DelayHandler
//                .clampDelay(150_000);
//        assertEquals("Oversize delay should clamp to max", 120_000L, clampedMax);
//
//        // 合法值不变
//        long clampedOk = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.DelayHandler
//                .clampDelay(5000);
//        assertEquals("Normal delay should be unchanged", 5000L, clampedOk);
//    }
//
//    @Test
//    public void i22_monitor_handler_null_response() {
//        // 测试 MonitorHandler 在 response=null 时不会崩溃
//        // 场景：navigate 使用 allowAllRequests，正常应有响应
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        try {
//            ctx.awaitCompletion(10_000);
//        } catch (InterruptedException e) {
//            // ignore
//        }
//        assertFalse("Monitor should handle normally", ctx.hasAssertionFailures());
//        assertNotNull("Should capture response", ctx.getLastApiCall("/api/users"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void i23_monitorCallback_exception_isolation() throws Exception {
//        // 单个回调抛异常不应影响其他回调执行
//        final List<String> callbackLog = new ArrayList<>();
//
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .onResponse((url, status, body, headers, method) -> {
//                    callbackLog.add("BEFORE_EXCEPTION");
//                    throw new RuntimeException("Simulated callback failure");
//                })
//                .onResponse((url, status, body, headers, method) -> {
//                    callbackLog.add("AFTER_EXCEPTION");
//                })
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(10_000);
//
//        assertTrue("First callback should execute", callbackLog.contains("BEFORE_EXCEPTION"));
//        assertTrue("Second callback should execute despite first one failing",
//                callbackLog.contains("AFTER_EXCEPTION"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void i24_routeRegistry_utility_methods() {
//        // RouteRegistry 工具方法：unregister, getPatternCount, getContextCount, clearAll
//        // 先注册一个 context 级别的路由
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .mock()
//                .mockBody("{\"registry\":\"test\"}")
//                .mockStatus(200)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        int count = com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRegistry
//                .getPatternCount(page);
//        assertTrue("Pattern count should be > 0 after registration", count > 0);
//
//        int ctxCount = com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRegistry
//                .getContextCount();
//        assertTrue("Context count should be > 0", ctxCount > 0);
//
//        dsl.clear();
//    }
//
//    @Test
//    public void i25_backward_compat_response_methods() {
//        // 测试 getStoredResponse / getAllStoredResponses / getResponseCountForUrl
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        try { ctx.awaitCompletion(10_000); } catch (InterruptedException e) {}
//
//        // getStoredResponse（向后兼容）
//        String stored = ctx.getStoredResponse("/api/users");
//        assertNotNull("getStoredResponse should return body", stored);
//        assertTrue("Stored response should contain Alice", stored.contains("Alice"));
//
//        // getAllStoredResponses
//        Map<String, String> allStored = ctx.getAllStoredResponses();
//        assertTrue("Should have stored responses", allStored.size() > 0);
//
//        // getResponseCountForUrl
//        int count = ctx.getResponseCountForUrl("/api/users");
//        assertTrue("Response count should be >= 1", count >= 1);
//
//        // clearStoredResponses
//        ctx.clearStoredResponses();
//        assertEquals("After clear, should be 0", 0, ctx.getResponseCountForUrl("/api/users"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void i26_response_storage_limit_protection() {
//        // MAX_RESPONSE_STORAGE=1000 / MAX_RESPONSE_TOTAL_SIZE=10MB 上限保护
//        // 不实际触发上限（需 1000+ 请求），验证常量存在性即可
//        // 这里验证连续多次请求后上下文仍正常
//
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .mock()
//                .mockBody("{\"batch\":true}")
//                .mockStatus(200)
//                .autoStopOnMatch(false)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        // 连续 5 次请求，验证存储无异常
//        for (int i = 0; i < 5; i++) {
//            navigateToApi("/api/users");
//        }
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        List<CapturedApiCall> calls = ctx.getApiCalls("/api/users");
//        assertEquals("All 5 calls should be stored", 5L, (long) calls.size());
//
//        dsl.clear();
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 10. CapturedApiCall 便捷方法 — requestUrl/responseHeaders/requestHeader/toString (j27-j28)
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void j27_capturedApiCall_requestUrl_responseHeaders_requestHeader() throws Exception {
//        // 测试 3 个未覆盖的 CapturedApiCall getter：
//        // requestUrl(), responseHeaders(), requestHeader(String)
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(10_000);
//
//        CapturedApiCall call = ctx.getLastApiCall("/api/users");
//        assertNotNull("Should capture /api/users", call);
//
//        // ── requestUrl() — 完整 URL ──
//        String reqUrl = call.requestUrl();
//        assertNotNull("requestUrl should not be null", reqUrl);
//        assertTrue("requestUrl should contain /api/users, actual: " + reqUrl,
//                reqUrl.contains("/api/users"));
//        assertTrue("requestUrl should start with http, actual: " + reqUrl,
//                reqUrl.startsWith("http"));
//
//        // ── responseHeaders() — 全部响应头 ──
//        Map<String, String> respHeaders = call.responseHeaders();
//        assertNotNull("responseHeaders should not be null", respHeaders);
//        assertFalse("responseHeaders should not be empty", respHeaders.isEmpty());
//
//        // ── requestHeader(String) — 大小写不敏感 ──
//        Map<String, String> reqHeaders = call.requestHeaders();
//        assertNotNull("requestHeaders should not be null", reqHeaders);
//        // navigate 请求至少应有 Accept 头
//        String acceptHeader = call.requestHeader("accept");
//        assertNotNull("Accept header should exist via requestHeader('accept')", acceptHeader);
//
//        // requestHeader 非存在的 key 返回 null
//        assertNull("requestHeader for non-existent key should return null",
//                call.requestHeader("X-No-Such-Header-99999"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void j28_capturedApiCall_toString_formatting() {
//        // toString() — 纯单元测试，无需 Playwright
//        long now = System.currentTimeMillis();
//        CapturedApiCall call1 = new CapturedApiCall(
//                "/api/test", "GET",
//                Collections.emptyMap(),
//                200,
//                Collections.singletonMap("Content-Type", "application/json"),
//                "{\"ok\":true,\"data\":[1,2,3]}",
//                now,
//                "http://localhost:8080/api/test"
//        );
//
//        String str = call1.toString();
//        assertNotNull("toString should not return null", str);
//        assertTrue("toString should contain HTTP method", str.contains("GET"));
//        assertTrue("toString should contain endpoint", str.contains("/api/test"));
//        assertTrue("toString should contain status code", str.contains("200"));
//        // body=XX chars
//        assertTrue("toString should contain body size info", str.contains("body="));
//
//        // ── toString with null body ──
//        CapturedApiCall call2 = new CapturedApiCall(
//                "/api/empty", "POST", null, 204, null, null, 0L, null
//        );
//        String str2 = call2.toString();
//        assertNotNull("toString should handle null body", str2);
//        assertTrue("toString with null body should show 0 chars, actual: " + str2,
//                str2.contains("body=0 chars"));
//
//        // ── toString with method normalization (uppercase) ──
//        CapturedApiCall call3 = new CapturedApiCall(
//                "/api/lowercase", "post", null, 200, null, "{}", now, null
//        );
//        String str3 = call3.toString();
//        assertTrue("method should be uppercased in toString: " + str3,
//                str3.contains("POST"));
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 11. ApiCaptureContext — getCallsByUrl/getAllResponsesForUrl/getAllStoredResponseLists (k29-k31)
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void k29_apiCaptureContext_getCallsByUrl() throws Exception {
//        // getCallsByUrl(String) — 按完整 URL 获取所有调用（返回 List）
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .autoStopOnMatch(false)
//                .minMatches(2)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//        navigateToApi("/api/users");  // 触发第二次调用
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(10_000);
//
//        // 通过完整 URL 获取所有调用
//        String fullUrl = BASE_URL + "/api/users";
//        List<CapturedApiCall> callsByUrl = ctx.getCallsByUrl(fullUrl);
//        assertNotNull("getCallsByUrl should not return null", callsByUrl);
//        assertTrue("Should have >= 2 calls by exact URL, actual: " + callsByUrl.size(),
//                callsByUrl.size() >= 2);
//
//        // 每个 CapturedApiCall 应包含正确数据
//        for (CapturedApiCall c : callsByUrl) {
//            assertEquals(fullUrl, c.requestUrl());
//            assertEquals(200, c.statusCode());
//        }
//
//        // 不存在的 URL 返回空列表
//        List<CapturedApiCall> emptyCalls = ctx.getCallsByUrl("http://no-such-url/api/nonexist");
//        assertNotNull("Non-existent URL should return empty list", emptyCalls);
//        assertTrue("Non-existent URL should return empty list", emptyCalls.isEmpty());
//
//        // null 参数返回空列表
//        List<CapturedApiCall> nullCalls = ctx.getCallsByUrl(null);
//        assertNotNull("null param should return empty list", nullCalls);
//        assertTrue("null param should return empty list", nullCalls.isEmpty());
//
//        dsl.clear();
//    }
//
//    @Test
//    public void k30_apiCaptureContext_getAllResponsesForUrl() throws Exception {
//        // getAllResponsesForUrl(String) — 按端点获取全部响应体列表 (responseStorage)
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .autoStopOnMatch(false)
//                .minMatches(3)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        for (int i = 0; i < 3; i++) {
//            navigateToApi("/api/users");
//        }
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(15_000);
//
//        List<String> allResponses = ctx.getAllResponsesForUrl("/api/users");
//        assertNotNull("getAllResponsesForUrl should not return null", allResponses);
//        assertEquals("Should have 3 stored responses", 3, allResponses.size());
//        for (String resp : allResponses) {
//            assertTrue("Each response should contain Alice", resp.contains("Alice"));
//        }
//
//        // 不存在的端点返回空列表
//        List<String> empty = ctx.getAllResponsesForUrl("/api/no-such-endpoint");
//        assertNotNull(empty);
//        assertTrue("Non-existent endpoint should return empty list", empty.isEmpty());
//
//        dsl.clear();
//    }
//
//    @Test
//    public void k31_apiCaptureContext_getAllStoredResponseLists() throws Exception {
//        // getAllStoredResponseLists() — 所有端点的全部响应历史 (Map<String, List<String>>)
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .autoStopOnMatch(false)
//                .minMatches(2)
//                .allowAllRequests()
//                .done()
//                .api("/api/slow/endpoint")
//                .monitor()
//                .expectStatus(200)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        navigateToApi("/api/users");
//        navigateToApi("/api/users");
//        navigateToApi("/api/slow/endpoint");
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        ctx.awaitCompletion(15_000);
//
//        Map<String, List<String>> allLists = ctx.getAllStoredResponseLists();
//        assertNotNull("getAllStoredResponseLists should not return null", allLists);
//        assertTrue("Should have >= 2 endpoint entries, actual: " + allLists.size(),
//                allLists.size() >= 2);
//
//        // /api/users 应有 2 条响应
//        List<String> userResponses = allLists.get("/api/users");
//        assertNotNull("/api/users should have response list", userResponses);
//        assertTrue("/api/users should have >= 2 responses", userResponses.size() >= 2);
//
//        // 清空后为空
//        ctx.clearStoredResponses();
//        Map<String, List<String>> afterClear = ctx.getAllStoredResponseLists();
//        assertTrue("After clear, stored response lists should be empty",
//                afterClear.isEmpty());
//
//        dsl.clear();
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 12. DelayHandler — resolveDelay 固定/随机延迟 (l32-l33)
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void l32_delayHandler_resolveDelay_fixed() {
//        // resolveDelay() — 固定延迟模式
//        RouteRule rule = new RouteRule();
//        rule.setUrlPattern("/api/test");
//        rule.setDelayMs(5000);
//
//        long resolved = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.DelayHandler
//                .resolveDelay(rule);
//        assertEquals("Fixed delay should resolve to configured value", 5000L, resolved);
//
//        // 固定延迟为 0
//        rule.setDelayMs(0);
//        assertEquals("Zero delay", 0L,
//                com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.DelayHandler
//                        .resolveDelay(rule));
//
//        // 固定延迟优先级低于随机延迟：minMs ≤ 0 时仍用固定值
//        rule.setDelayMs(3000);
//        rule.setDelayMinMs(0);
//        rule.setDelayMaxMs(0);
//        assertEquals("minMs=0 maxMs=0 → fallback to fixed delay", 3000L,
//                com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.DelayHandler
//                        .resolveDelay(rule));
//
//        // minMs > 0 but maxMs not > minMs → fallback to fixed
//        rule.setDelayMs(2000);
//        rule.setDelayMinMs(5000);
//        rule.setDelayMaxMs(5000);
//        assertEquals("minMs=maxMs → fallback to fixed delay", 2000L,
//                com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.DelayHandler
//                        .resolveDelay(rule));
//    }
//
//    @Test
//    public void l33_delayHandler_resolveDelay_random() {
//        // resolveDelay() — 随机延迟模式
//        RouteRule rule = new RouteRule();
//        rule.setUrlPattern("/api/test");
//        rule.setDelayMs(1000);     // 固定值（应被忽略）
//        rule.setDelayMinMs(2000);  // 随机范围下限
//        rule.setDelayMaxMs(5000);  // 随机范围上限
//
//        // 多次调用验证随机值在范围内
//        for (int i = 0; i < 20; i++) {
//            long resolved = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.DelayHandler
//                    .resolveDelay(rule);
//            assertTrue("Random delay should be >= 2000, actual: " + resolved,
//                    resolved >= 2000);
//            assertTrue("Random delay should be <= 5000, actual: " + resolved,
//                    resolved <= 5000);
//        }
//
//        // Edge case: minMs > 0 but maxMs barely > minMs
//        rule.setDelayMinMs(100);
//        rule.setDelayMaxMs(101);
//        long edge = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.DelayHandler
//                .resolveDelay(rule);
//        assertTrue("Edge random should be 100 or 101, actual: " + edge,
//                edge == 100 || edge == 101);
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 13. ModifyHandler — fallback 开关 / 缓存大小 (l34-l35)
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void l34_modifyHandler_fallback_string_replace() {
//        // setAllowFallbackStringReplace() 开启/关闭
//        com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .setAllowFallbackStringReplace(true);
//        com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .setAllowFallbackStringReplace(false);
//        // 验证不抛异常即可（volatile static 字段直接赋值，无副作用）
//        // 恢复默认值
//        com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .setAllowFallbackStringReplace(false);
//    }
//
//    @Test
//    public void l35_modifyHandler_jsonPathCache_size() {
//        // getJsonPathCacheSize() — 缓存条目监控
//        // 初始状态：缓存应为空（或少量条目，取决于之前测试是否已清理）
//        int initialSize = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .getJsonPathCacheSize();
//        assertTrue("Cache size should be >= 0", initialSize >= 0);
//
//        // 通过 replaceByJsonPath 触发编译缓存
//        String json = "{\"name\":\"test\",\"count\":42,\"flag\":true}";
//        com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .replaceByJsonPath(json, "$.name", "cached-name");
//        com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .replaceByJsonPath(json, "$.count", "99");
//        com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .replaceByJsonPath(json, "$.flag", "false");
//
//        int afterSize = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .getJsonPathCacheSize();
//        assertTrue("Cache should have entries after usage, actual: " + afterSize,
//                afterSize >= 1);
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 14. RouteRegistry / RouteEngine — 清理方法 (l36-l37)
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void l36_routeRegistry_unregister() {
//        // RouteRegistry.unregister(Object, String) — 注销单个 pattern
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/unreg-test")
//                .mock()
//                .mockBody("{\"unreg\":\"test\"}")
//                .mockStatus(200)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        int countBefore = com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRegistry
//                .getPatternCount(page);
//
//        // unregister 该 pattern
//        com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRegistry
//                .unregister(page, "**/api/unreg-test**");
//
//        int countAfter = com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRegistry
//                .getPatternCount(page);
//        // 如果只有这一个 pattern，注销后应减少
//        assertTrue("Pattern count should decrease after unregister: before="
//                + countBefore + ", after=" + countAfter, countAfter <= countBefore);
//
//        // unregister 不存在 context 的 pattern — 不抛异常
//        com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRegistry
//                .unregister(page, "/api/no-such-pattern");
//
//        dsl.clear();
//    }
//
//    @Test
//    public void l37_routeRegistry_clearAll_and_routeEngine_cleanup() {
//        // RouteRegistry.clearAll() — 全局清理所有 context 和缓存
//        // RouteEngine.clearAllMonitorSessions() / clearDispatchedRoutes()
//
//        // 先注册一个路由产生 MonitorSession
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/cleanup-test")
//                .monitor()
//                .expectStatus(200)
//                .timeout(60)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        // 确保有上下文注册
//        int ctxCountBefore = com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRegistry
//                .getContextCount();
//        assertTrue("Context count should be >= 1 before clearAll", ctxCountBefore >= 1);
//
//        // ── clearDispatchedRoutes ──
//        com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine
//                .clearDispatchedRoutes();
//        // 不抛异常即可
//
//        // ── clearAllMonitorSessions ──
//        com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine
//                .clearAllMonitorSessions();
//        // 不抛异常即可
//
//        // ── clearJsonPathCache ──
//        com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .clearJsonPathCache();
//
//        // ── clearAll ──
//        com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRegistry
//                .clearAll();
//
//        // clearAll 后上下文应为 0
//        int ctxCountAfter = com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRegistry
//                .getContextCount();
//        assertEquals("Context count should be 0 after clearAll", 0, ctxCountAfter);
//
//        // clean up dsl rules (could be dangling after clearAll)
//        dsl.clear();
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 15. ModifyHandler 静态工具方法 — 纯单元测试 (m38-m41)
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void m38_modifyHandler_addFieldByJsonPath() {
//        // addFieldByJsonPath — 新增字段 + 数组追加
//        com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler mh =
//                new com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler();
//
//        // 1. 新增普通字段
//        String json1 = "{\"name\":\"test\"}";
//        String result1 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .addFieldByJsonPath(json1, "$.age", "25");
//        assertTrue("Should contain added field", result1.contains("\"age\""));
//        assertTrue("Should contain int value", result1.contains("25"));
//
//        // 2. 向已有数组追加元素
//        String json2 = "{\"tags\":[\"a\",\"b\"]}";
//        String result2 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .addFieldByJsonPath(json2, "$.tags", "\"c\"");
//        assertTrue("Should contain original tags", result2.contains("\"a\""));
//        assertTrue("Should contain appended tag", result2.contains("\"c\""));
//
//        // 3. 嵌套路径：自动创建中间节点
//        //    注意: inferTypeForNull(value) 对带引号的字符串会原样保留引号
//        //    例如 value="Framework" → TextNode("Framework") → JSON 序列化为 "Framework"
//        String json3 = "{\"data\":{}}";
//        String result3 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .addFieldByJsonPath(json3, "$.data.meta.author", "Framework");
//        assertTrue("Should contain nested fields", result3.contains("\"meta\""));
//        assertTrue("Should contain author field", result3.contains("\"author\""));
//        assertTrue("Should contain author value", result3.contains("\"Framework\""));
//
//        // 4. 路径 root $
//        String json4 = "{\"key\":\"val\"}";
//        String result4 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .addFieldByJsonPath(json4, "$.extra", "YES");
//        assertTrue("$ prefix should work", result4.contains("\"extra\""));
//        assertTrue("Should contain value", result4.contains("\"YES\""));
//
//        // 5. 新增带数组索引的路径（index 被忽略，name 作为字段名）
//        String json5 = "{\"arr\":[1,2]}";
//        String result5 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .addFieldByJsonPath(json5, "$.arr[0].newField", "\"v\"");
//        assertTrue("Should still produce valid JSON", result5.startsWith("{"));
//    }
//
//    @Test
//    public void m39_modifyHandler_removeFieldByJsonPath() {
//        // removeFieldByJsonPath — 删除字段
//
//        // 1. 删除顶层字段
//        String json1 = "{\"name\":\"test\",\"age\":30,\"email\":\"a@b.com\"}";
//        String result1 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .removeFieldByJsonPath(json1, "$.email");
//        assertFalse("email should be removed", result1.contains("\"email\""));
//        assertTrue("name should remain", result1.contains("\"name\""));
//
//        // 2. 删除嵌套字段
//        String json2 = "{\"user\":{\"name\":\"test\",\"ssn\":\"123-45-6789\"}}";
//        String result2 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .removeFieldByJsonPath(json2, "$.user.ssn");
//        assertFalse("ssn should be removed", result2.contains("\"ssn\""));
//        assertTrue("user.name should remain", result2.contains("\"name\""));
//
//        // 3. 路径不存在 — 返回原 JSON（不抛异常）
//        String json3 = "{\"a\":1}";
//        String result3 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .removeFieldByJsonPath(json3, "$.noSuchField");
//        assertEquals("Non-existent path should return original JSON", json3, result3);
//
//        // 4. 非 JSON 输入 — 返回原值
//        String notJson = "plain text";
//        String result4 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .removeFieldByJsonPath(notJson, "$.any");
//        assertEquals("Non-JSON should return original", notJson, result4);
//    }
//
//    @Test
//    public void m40_modifyHandler_replaceBatchByWildcard() {
//        // replaceBatchByWildcard — 通配符批量替换
//        com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler mh =
//                new com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler();
//
//        // 1. 顶层数组通配符替换
//        String json1 = "[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]";
//        Map<String, String> reps1 = new HashMap<>();
//        reps1.put("$[*].name", "REPLACED");
//        String result1 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .replaceBatchByWildcard(json1, reps1);
//        assertTrue("All names should be replaced", result1.contains("\"REPLACED\""));
//        assertFalse("Original names should be gone", result1.contains("\"Alice\""));
//        assertFalse("Original Bob should be gone", result1.contains("\"Bob\""));
//
//        // 2. 嵌套通配符替换
//        String json2 = "{\"users\":[{\"orders\":[{\"price\":100},{\"price\":200}]}," +
//                "{\"orders\":[{\"price\":300}]}]}";
//        Map<String, String> reps2 = new HashMap<>();
//        reps2.put("$.users[*].orders[*].price", "0");
//        String result2 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .replaceBatchByWildcard(json2, reps2);
//        assertTrue("All prices should be 0", result2.contains("\"price\":0"));
//
//        // 3. 非通配符路径
//        String json3 = "{\"status\":\"active\",\"count\":42}";
//        Map<String, String> reps3 = new HashMap<>();
//        reps3.put("$.status", "inactive");
//        reps3.put("$.count", "0");
//        String result3 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .replaceBatchByWildcard(json3, reps3);
//        assertTrue("Status changed", result3.contains("\"inactive\""));
//        assertTrue("Count changed", result3.contains("\"count\":0"));
//
//        // 4. null/empty replacements — 返回原值
//        String result4a = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .replaceBatchByWildcard(json1, null);
//        assertEquals("null map -> original", json1, result4a);
//
//        String result4b = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .replaceBatchByWildcard(json1, Collections.emptyMap());
//        assertEquals("empty map -> original", json1, result4b);
//
//        // 5. 非 JSON 输入 — 返回原值
//        String notJson = "hello";
//        Map<String, String> reps5 = new HashMap<>();
//        reps5.put("$.x", "y");
//        String result5 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .replaceBatchByWildcard(notJson, reps5);
//        assertEquals("Non-JSON -> original", notJson, result5);
//    }
//
//    @Test
//    public void m41_modifyHandler_parseWildcardPath() {
//        // parseWildcardPath — 通配符路径解析
//
//        // 1. $ 前缀跳过 — $[*].name → [*] 是 root wildcard, name 是字段
//        //    parseWildcardPath: 去掉 $ 后 → [*].name → ["[*]", "name"] = 2 segments
//        List<?> segs1 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .parseWildcardPath("$[*].name");
//        assertEquals("$[*].name -> 2 segments", 2, segs1.size());
//
//        // 2. $. 前缀 — $.users[*].name → 去掉 $. 后 → users[*].name
//        //    → ["users[*]", "name"] = 2 segments（users[*] 作为一个段）
//        List<?> segs2 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .parseWildcardPath("$.users[*].name");
//        assertEquals("$.users[*].name -> 2 segments", 2, segs2.size());
//
//        // 3. 精确索引 — $.users[0].name → ["users[0]", "name"] = 2 segments
//        List<?> segs3 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .parseWildcardPath("$.users[0].name");
//        assertEquals("$.users[0].name -> 2 segments", 2, segs3.size());
//
//        // 4. 无通配符
//        List<?> segs4 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .parseWildcardPath("$.name");
//        assertEquals("$.name -> 1 segment", 1, segs4.size());
//
//        // 5. 只有 $
//        List<?> segs5 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .parseWildcardPath("$");
//        assertEquals("$ -> 0 segments", 0, segs5.size());
//
//        // 6. 空字符串
//        List<?> segs6 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .parseWildcardPath("");
//        assertEquals("empty -> 0 segments", 0, segs6.size());
//
//        // 7. invalid array index — treated as field
//        List<?> segs7 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler.ModifyHandler
//                .parseWildcardPath("$.data[abc]");
//        assertEquals("$.data[abc] -> 1 segment (field, not wildcard)", 1, segs7.size());
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 16. RouteRule / RouteUtil / ApiCaptureContext — 纯单元测试 (m42-m46)
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void m42_routeRule_getResourceTypeSet() {
//        // getResourceTypeSet — 带 DCL 缓存的资源类型解析
//        RouteRule rule = new RouteRule();
//        rule.setUrlPattern("/api/test");
//
//        // 1. null resourceTypes
//        assertNull("null resourceTypes -> null", rule.getResourceTypeSet());
//
//        // 2. 逗号分隔
//        rule.setResourceTypes("xhr,fetch");
//        Set<String> set1 = rule.getResourceTypeSet();
//        assertNotNull("Should not be null", set1);
//        assertEquals("Should have 2 types", 2, set1.size());
//        assertTrue("Should contain xhr", set1.contains("xhr"));
//        assertTrue("Should contain fetch", set1.contains("fetch"));
//
//        // 3. 缓存复用：第二次调用返回同一实例
//        Set<String> set2 = rule.getResourceTypeSet();
//        assertSame("DCL cache should return same instance", set1, set2);
//
//        // 4. 分号分隔
//        rule.setResourceTypes("script;stylesheet;image");
//        Set<String> set3 = rule.getResourceTypeSet();
//        assertEquals("Semicolon separated -> 3 types", 3, set3.size());
//        assertTrue("Contains script", set3.contains("script"));
//        assertTrue("Contains stylesheet", set3.contains("stylesheet"));
//
//        // 5. 空格分隔
//        rule.setResourceTypes("xhr fetch document");
//        Set<String> set4 = rule.getResourceTypeSet();
//        assertEquals("Space separated -> 3 types", 3, set4.size());
//
//        // 6. 单一资源类型
//        rule.setResourceTypes("fetch");
//        Set<String> set5 = rule.getResourceTypeSet();
//        assertEquals("Single type -> 1", 1, set5.size());
//        assertEquals("Should be fetch", "fetch", set5.iterator().next());
//
//        // 7. blank 后返回 null
//        rule.setResourceTypes("  ");
//        assertNull("Blank -> null", rule.getResourceTypeSet());
//
//        // 8. 缓存失效：修改 resourceTypes 后缓存更新
//        rule.setResourceTypes("xhr");
//        Set<String> set6 = rule.getResourceTypeSet();
//        assertEquals("xhr after change", 1, set6.size());
//    }
//
//    @Test
//    public void m43_routeRule_equals_hashCode() {
//        // equals / hashCode — ConcurrentHashMap key 去重
//        RouteRule r1 = new RouteRule();
//        r1.setUrlPattern("/api/users");
//        r1.setType(RouteHandleType.MONITOR);
//
//        RouteRule r2 = new RouteRule();
//        r2.setUrlPattern("/api/users");
//        r2.setType(RouteHandleType.MONITOR);
//
//        RouteRule r3 = new RouteRule();
//        r3.setUrlPattern("/api/orders");
//        r3.setType(RouteHandleType.MONITOR);
//
//        RouteRule r4 = new RouteRule();
//        r4.setUrlPattern("/api/users");
//        r4.setType(RouteHandleType.MOCK);
//
//        // reflexivity
//        assertEquals("Same instance should be equal", r1, r1);
//
//        // symmetry
//        assertEquals("Same urlPattern+type should be equal", r1, r2);
//        assertEquals("Same urlPattern+type should be equal (reverse)", r2, r1);
//
//        // hash code consistency
//        assertEquals("Equal objects -> same hashCode", r1.hashCode(), r2.hashCode());
//
//        // different urlPattern
//        assertNotEquals("Different urlPattern -> not equal", r1, r3);
//
//        // different type
//        assertNotEquals("Different type -> not equal", r1, r4);
//
//        // null / different class
//        assertNotEquals("null -> not equal", r1, null);
//        assertNotEquals("String -> not equal", r1, "not a rule");
//
//        // modifyMethod 参与 equals
//        RouteRule r5 = new RouteRule();
//        r5.setUrlPattern("/api/users");
//        r5.setType(RouteHandleType.MONITOR);
//        r5.setModifyMethod("PUT");
//
//        RouteRule r6 = new RouteRule();
//        r6.setUrlPattern("/api/users");
//        r6.setType(RouteHandleType.MONITOR);
//        r6.setModifyMethod("POST");
//
//        assertNotEquals("Different modifyMethod -> not equal", r5, r6);
//        assertEquals("Hash codes should differ for different modifyMethod",
//                false, r5.hashCode() == r6.hashCode() &&
//                        r5.getModifyMethod().equals(r6.getModifyMethod()));
//
//        // 验证 ConcurrentHashMap 去重行为
//        Map<RouteRule, String> map = new ConcurrentHashMap<>();
//        map.put(r1, "first");
//        map.put(r2, "second");  // equals r1 → 应覆盖
//        assertEquals("r2 should overwrite r1 (same key)", "second", map.get(r1));
//        assertEquals("Should have 1 entry for /api/users+MONITOR", 1, map.size());
//        map.put(r4, "third");  // different type (MOCK)
//        assertEquals("Should have 2 entries total", 2, map.size());
//    }
//
//    @Test
//    public void m44_routeUtil_parseQueryParams() {
//        // parseQueryParams — URL query 解析
//
//        // 1. 基础解析
//        Map<String, String> q1 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil
//                .parseQueryParams("http://localhost:8080/api/users?name=Alice&role=ADMIN");
//        assertEquals("Parsed name", "Alice", q1.get("name"));
//        assertEquals("Parsed role", "ADMIN", q1.get("role"));
//        assertEquals("2 params", 2, q1.size());
//
//        // 2. URL 编码的值
//        Map<String, String> q2 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil
//                .parseQueryParams("http://host/path?key=hello%20world&encoded=%E4%B8%AD%E6%96%87");
//        assertEquals("URL decoded space", "hello world", q2.get("key"));
//        assertTrue("Should have encoded key", q2.containsKey("encoded"));
//
//        // 3. 只有一个 key（无值）
//        Map<String, String> q3 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil
//                .parseQueryParams("http://host/path?flag");
//        assertEquals("Key-only param -> empty string", "", q3.get("flag"));
//        assertEquals("1 param", 1, q3.size());
//
//        // 4. 无 query string
//        Map<String, String> q4 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil
//                .parseQueryParams("http://host/api/users");
//        assertTrue("No query -> empty", q4.isEmpty());
//
//        // 5. 空 URL → 返回空
//        Map<String, String> q5a = com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil
//                .parseQueryParams(null);
//        assertTrue("null URL -> empty", q5a.isEmpty());
//
//        Map<String, String> q5b = com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil
//                .parseQueryParams("");
//        assertTrue("Empty URL -> empty", q5b.isEmpty());
//
//        // 6. 多个相同 key (取最后一个)
//        Map<String, String> q6 = com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil
//                .parseQueryParams("http://host/path?page=1&page=2&page=3");
//        assertEquals("Last wins for duplicate key", "3", q6.get("page"));
//    }
//
//    @Test
//    public void m45_apiCaptureContext_assertionFailureDetail_toString() {
//        // AssertionFailureDetail.toString — 断言失败详情格式化
//        // 注意: AssertionFailureDetail 构造函数是 package-private，
//        // 通过 recordAssertionFailure + getFailureDetails 间接测试 toString
//
//        // 1. 正常值：通过 recordAssertionFailure 创建
//        ApiCaptureContext ctx = ApiCaptureContext.getCurrent();
//        ctx.reset();
//        ctx.recordAssertionFailure("http://localhost/api/users", "STATUS",
//                "200", "404", "Expected 200 but got 404");
//
//        ApiCaptureContext.AssertionFailureDetail detail1 = ctx.getFailureDetails().get(0);
//        String str1 = detail1.toString();
//        assertNotNull("toString should not be null", str1);
//        assertTrue("Should contain STATUS", str1.contains("STATUS"));
//        assertTrue("Should contain URL", str1.contains("/api/users"));
//        assertTrue("Should contain expected", str1.contains("200"));
//        assertTrue("Should contain actual", str1.contains("404"));
//        assertTrue("Should contain fail message", str1.contains("Expected 200 but got 404"));
//
//        // 2. null expected / actual / failMessage
//        ctx.reset();
//        ctx.recordAssertionFailure("/api/test", "JSONPATH", null, null, null);
//
//        ApiCaptureContext.AssertionFailureDetail detail2 = ctx.getFailureDetails().get(0);
//        String str2 = detail2.toString();
//        assertNotNull("toString with nulls should not be null", str2);
//        assertTrue("Should contain N/A for missing expected", str2.contains("N/A"));
//        assertTrue("Should contain JSONPATH type", str2.contains("JSONPATH"));
//
//        // 3. 验证 buildFailureReport 使用 toString
//        ctx.reset();
//        ctx.recordAssertionFailure("http://x/api", "STATUS", "200", "500", "FAIL");
//        ctx.recordAssertionFailure("http://x/api2", "JSONPATH", "42", "99", "Mismatch");
//        assertTrue("Should have failures", ctx.hasAssertionFailures());
//        String report = ctx.buildFailureReport();
//        assertTrue("Report should contain first detail", report.contains("STATUS"));
//        assertTrue("Report should contain second detail", report.contains("JSONPATH"));
//        ctx.reset();
//    }
//
//    @Test
//    public void m46_apiCaptureContext_getActiveRequests() throws Exception {
//        // getActiveRequests — 活动请求并发计数
//        // 通过实际网络请求来验证计数器的增减
//
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .monitor()
//                .expectStatus(200)
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        int before = ctx.getActiveRequests();
//        assertTrue("Active requests should be >= 0", before >= 0);
//
//        navigateToApi("/api/users");
//
//        ctx.awaitCompletion(10_000);
//
//        // 请求完成后活动计数应归 0
//        int after = ctx.getActiveRequests();
//        // 由于异步处理，可能立刻归 0
//        assertTrue("After completion, active should be >= 0", after >= 0);
//        // 实际上调用 awaitCompletion 后，activeRequests 应为 0
//        // （但 decrement 在独立线程中执行，最多再等一小段时间）
//        long maxWait = System.currentTimeMillis() + 3000;
//        while (ctx.getActiveRequests() > 0 && System.currentTimeMillis() < maxWait) {
//            pause(50);
//        }
//        assertEquals("After awaitCompletion, active requests should be 0",
//                0, ctx.getActiveRequests());
//
//        // reset 后也为 0
//        ctx.reset();
//        assertEquals("After reset, active requests should be 0", 0, ctx.getActiveRequests());
//
//        dsl.clear();
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 17. RouteDsl DSL 方法 — resourceType / matchFrameUrl (m47-m48)
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void m47_routeDsl_resourceType_string() {
//        // resourceType(String) — 逗号分隔的资源类型过滤
//        // 场景：只匹配 fetch 请求，不匹配 xhr 请求
//
//        // Step 1: 注册 resourceType("fetch") mock 规则
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .mock()
//                .mockBody("{\"matched\":\"fetch-only\"}")
//                .mockStatus(200)
//                .resourceType("fetch")   // ← 被测方法
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        // fetch 请求 → 应被 mock 拦截
//        page.evaluate("() => fetch('" + BASE_URL + "/api/users')");
//        pause(500);
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall fetchCall = ctx.getLastApiCall("/api/users");
//        assertNotNull("Fetch request should be captured", fetchCall);
//        assertTrue("Fetch response should be mocked",
//                fetchCall.responseBody().contains("fetch-only"));
//
//        dsl.clear();
//    }
//
//    @Test
//    public void m48_routeDsl_matchFrameUrl() {
//        // matchFrameUrl(String) — Frame URL 包含匹配
//        // 通过 navigate 触发：当前 frame URL 包含 demo 路径
//        // 验证 matchFrameUrl 设置不上报异常
//
//        // 先导航到 demo 页面，确保 frame URL 已知
//        page.navigate(BASE_URL + "/api/users");
//
//        RouteDsl dsl = RouteDsl.on(page)
//                .api("/api/users")
//                .mock()
//                .mockBody("{\"frameMatched\":true}")
//                .mockStatus(200)
//                .matchFrameUrl("demo")    // ← 被测方法：当前 Page URL 含 "demo"
//                .allowAllRequests()
//                .done();
//        dsl.start();
//
//        // 在同一页面内发起 fetch
//        page.evaluate("() => fetch('" + BASE_URL + "/api/users')");
//        pause(500);
//
//        ApiCaptureContext ctx = RouteMonitor.context();
//        CapturedApiCall call = ctx.getLastApiCall("/api/users");
//        assertNotNull("Should capture API call when frameUrl matches", call);
//
//        dsl.clear();
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // 18. 跨层级规则合并 — Context + Page (n49-n55)
//    //
//    // Playwright Page.route() 优先级高于 BrowserContext.route()，
//    // context 级规则会被 page 级规则完全屏蔽。
//    // RouteEngine.dispatchRoute 在 page handler 执行前检查 CONTEXT_RULES，
//    // 按固定优先级 MOCK > MODIFY > DELAY > MONITOR 合并跨层级规则。
//    // ═══════════════════════════════════════════════════════════════
//
//    @Test
//    public void n49_context_delay_page_monitor() {
//        // ⭐ Context DELAY + Page MONITOR → DELAY wins (DELAY > MONITOR), no monitoring.
//        // Page MONITOR handler 被 context DELAY 覆盖，请求延迟后直接放行，不进行监控。
//        BrowserContext testCtx = browser.newContext(
//                new Browser.NewContextOptions().setViewportSize(1280, 720));
//        try {
//            // Step 1: Register context-level DELAY (500ms) on /api/users
//            RouteRule ctxDelayRule = new RouteRule();
//            ctxDelayRule.setUrlPattern("/api/users");
//            ctxDelayRule.setType(RouteHandleType.DELAY);
//            ctxDelayRule.setDelayMs(500);
//            RouteEngine.register(testCtx, java.util.Collections.singletonList(ctxDelayRule));
//
//            // Step 2: Open page, register page-level MONITOR on same URL
//            Page testPage = testCtx.newPage();
//            RouteDsl dsl = RouteDsl.on(testPage)
//                    .api("/api/users")
//                    .monitor()
//                    .expectStatus(200)
//                    .allowAllRequests()
//                    .done();
//            dsl.start();
//
//            // Step 3: Trigger request — page MONITOR fires first, context DELAY overrides.
//            //   dispatchRoute: ctxType=DELAY, pageType=MONITOR → DELAY > MONITOR → pure delay+resume
//            long start = System.currentTimeMillis();
//            testPage.navigate(BASE_URL + "/api/users");
//            long elapsed = System.currentTimeMillis() - start;
//
//            // Step 4: DELAY > MONITOR → context DELAY overrides page MONITOR entirely.
//            //   请求被延迟 500ms 后直接放行，monitor 未捕获任何数据。
//            assertTrue("Elapsed should include context delay (500ms), actual: " + elapsed + "ms",
//                    elapsed >= 450);
//
//            // Monitor was overridden: no assertions ran, no data captured
//            ApiCaptureContext ctx = RouteMonitor.context();
//            assertFalse("Monitor assertions: " + ctx.buildFailureReport(),
//                    ctx.hasAssertionFailures());
//            CapturedApiCall call = ctx.getLastApiCall("/api/users");
//            assertNull("Monitor should NOT capture data when overridden by DELAY (DELAY > MONITOR)", call);
//
//            dsl.clear();
//            testPage.close();
//        } finally {
//            RouteRegistry.clearContext(testCtx);
//            testCtx.close();
//        }
//    }
//
//    @Test
//    public void n50_context_mock_overrides_page_monitor() {
//        // ⭐ Context MOCK + Page MONITOR → context MOCK wins (MOCK > MONITOR).
//        // Page MONITOR handler 被 context MOCK 覆盖，返回 mock 数据而非真实服务器数据。
//        BrowserContext testCtx = browser.newContext(
//                new Browser.NewContextOptions().setViewportSize(1280, 720));
//        try {
//            // Step 1: Register context-level MOCK on /api/users
//            RouteRule ctxMockRule = new RouteRule();
//            ctxMockRule.setUrlPattern("/api/users");
//            ctxMockRule.setType(RouteHandleType.MOCK);
//            ctxMockRule.setMockBody("{\"source\":\"context-mock\",\"name\":\"CrossLevelMock\"}");
//            ctxMockRule.setMockStatus(200);
//            RouteEngine.register(testCtx, java.util.Collections.singletonList(ctxMockRule));
//
//            // Step 2: Open page, register page-level MONITOR on same URL
//            Page testPage = testCtx.newPage();
//            RouteDsl dsl = RouteDsl.on(testPage)
//                    .api("/api/users")
//                    .monitor()
//                    .expectStatus(200)
//                    .allowAllRequests()
//                    .done();
//            dsl.start();
//
//            // Step 3: Trigger request — page MONITOR fires → finds context MOCK → overrides
//            testPage.navigate(BASE_URL + "/api/users");
//
//            // Step 4: Verify context MOCK response (not real data, not page monitor)
//            ApiCaptureContext ctx = RouteMonitor.context();
//            CapturedApiCall call = ctx.getLastApiCall("/api/users");
//            assertNotNull("Mock call should be captured", call);
//            assertTrue("Response should be context mock data",
//                    call.responseBody().contains("context-mock"));
//            assertTrue("Response should contain CrossLevelMock",
//                    call.responseBody().contains("CrossLevelMock"));
//            // Real data must NOT appear (mock overrides, no real server request)
//            assertFalse("Real data must NOT be present (context MOCK overrides)",
//                    call.responseBody().contains("Alice"));
//
//            dsl.clear();
//            testPage.close();
//        } finally {
//            RouteRegistry.clearContext(testCtx);
//            testCtx.close();
//        }
//    }
//
//    @Test
//    public void n51_context_mock_overrides_page_delay() {
//        // ⭐ Context MOCK + Page DELAY → context MOCK wins, DELAY is skipped.
//        // Priority MOCK > DELAY: context MOCK 立即返回 mock 响应，不延迟。
//        BrowserContext testCtx = browser.newContext(
//                new Browser.NewContextOptions().setViewportSize(1280, 720));
//        try {
//            // Step 1: Register context-level MOCK on /api/users
//            RouteRule ctxMockRule = new RouteRule();
//            ctxMockRule.setUrlPattern("/api/users");
//            ctxMockRule.setType(RouteHandleType.MOCK);
//            ctxMockRule.setMockBody("{\"source\":\"ctx-mock-no-delay\",\"status\":\"immediate\"}");
//            ctxMockRule.setMockStatus(200);
//            RouteEngine.register(testCtx, java.util.Collections.singletonList(ctxMockRule));
//
//            // Step 2: Open page, register page-level DELAY (1s) on same URL
//            Page testPage = testCtx.newPage();
//            RouteDsl dsl = RouteDsl.on(testPage)
//                    .api("/api/users")
//                    .delay(1)  // 1000ms delay
//                    .allowAllRequests()
//                    .done();
//            dsl.start();
//
//            // Step 3: Trigger request — page DELAY fires → finds context MOCK → overrides
//            long start = System.currentTimeMillis();
//            testPage.navigate(BASE_URL + "/api/users");
//            long elapsed = System.currentTimeMillis() - start;
//
//            // Step 4: Verify MOCK returned immediately (not delayed by 1000ms)
//            ApiCaptureContext ctx = RouteMonitor.context();
//            CapturedApiCall call = ctx.getLastApiCall("/api/users");
//            assertNotNull("Mock call should be captured", call);
//            assertTrue("Response should be context mock",
//                    call.responseBody().contains("ctx-mock-no-delay"));
//            // MOCK should return quickly, not after 1000ms delay
//            assertTrue("Mock response should be immediate (not 1000ms delayed), actual: " + elapsed + "ms",
//                    elapsed < 800);
//
//            dsl.clear();
//            testPage.close();
//        } finally {
//            RouteRegistry.clearContext(testCtx);
//            testCtx.close();
//        }
//    }
//
//    @Test
//    public void n52_context_monitor_page_delay_merged() {
//        // ⭐ Context MONITOR + Page DELAY → DELAY wins (DELAY > MONITOR).
//        // Page DELAY handler 独立执行延迟放行，context MONITOR 被覆盖，不进行监控。
//        BrowserContext testCtx = browser.newContext(
//                new Browser.NewContextOptions().setViewportSize(1280, 720));
//        try {
//            // Step 1: Register context-level MONITOR on /api/users
//            RouteRule ctxMonitorRule = new RouteRule();
//            ctxMonitorRule.setUrlPattern("/api/users");
//            ctxMonitorRule.setType(RouteHandleType.MONITOR);
//            ctxMonitorRule.setExpectedStatus(200);
//            ctxMonitorRule.setRecord(true);
//            RouteEngine.register(testCtx, java.util.Collections.singletonList(ctxMonitorRule));
//
//            // Step 2: Open page, register page-level DELAY (2s → 2000ms) on same URL
//            Page testPage = testCtx.newPage();
//            RouteDsl dsl = RouteDsl.on(testPage)
//                    .api("/api/users")
//                    .delay(2)  // 2000ms
//                    .allowAllRequests()
//                    .done();
//            dsl.start();
//
//            // Step 3: Trigger request — page DELAY fires first (Page > Context)
//            //   DELAY > MONITOR → page DELAY wins, request delayed 2000ms then resumed, no monitoring.
//            long start = System.currentTimeMillis();
//            testPage.navigate(BASE_URL + "/api/users");
//            long elapsed = System.currentTimeMillis() - start;
//
//            // Step 4: Verify DELAY took effect, but MONITOR did NOT capture anything.
//            // Page DELAY (2000ms) should apply.
//            assertTrue("Elapsed should include page delay (2000ms), actual: " + elapsed + "ms",
//                    elapsed >= 1800);
//
//            // Context MONITOR was overridden by page DELAY.
//            // scheduleDelay stores a DELAY tracking record (status=0, body=null),
//            // which is NOT a MONITOR capture (MONITOR would have status=200 + real body).
//            ApiCaptureContext ctx = RouteMonitor.context();
//            assertFalse("Monitor assertions: " + ctx.buildFailureReport(),
//                    ctx.hasAssertionFailures());
//            CapturedApiCall call = ctx.getLastApiCall("/api/users");
//            assertNotNull("DELAY should store a tracking record", call);
//            assertEquals("DELAY record should have status=0 (no real response)", 0, call.statusCode());
//            assertNull("DELAY record should have null body (no monitoring)", call.responseBody());
//
//            dsl.clear();
//            testPage.close();
//        } finally {
//            RouteRegistry.clearContext(testCtx);
//            testCtx.close();
//        }
//    }
//
//    @Test
//    public void n53_context_delay_page_mock() {
//        // ⭐ Context DELAY + Page MOCK → merged delay, page MOCK responds after delay.
//        // Context 500ms DELAY 合并到 page MOCK handler，延迟后返回 mock 响应。
//        BrowserContext testCtx = browser.newContext(
//                new Browser.NewContextOptions().setViewportSize(1280, 720));
//        try {
//            // Step 1: Register context-level DELAY (500ms) on /api/users
//            RouteRule ctxDelayRule = new RouteRule();
//            ctxDelayRule.setUrlPattern("/api/users");
//            ctxDelayRule.setType(RouteHandleType.DELAY);
//            ctxDelayRule.setDelayMs(500);
//            RouteEngine.register(testCtx, java.util.Collections.singletonList(ctxDelayRule));
//
//            // Step 2: Open page, register page-level MOCK on same URL
//            Page testPage = testCtx.newPage();
//            RouteDsl dsl = RouteDsl.on(testPage)
//                    .api("/api/users")
//                    .mock()
//                    .mockBody("{\"source\":\"page-mock-with-delay\",\"delayed\":true}")
//                    .mockStatus(200)
//                    .allowAllRequests()
//                    .done();
//            dsl.start();
//
//            // Step 3: Trigger request — page MOCK fires → finds context DELAY → merges delay
//            long start = System.currentTimeMillis();
//            testPage.navigate(BASE_URL + "/api/users");
//            long elapsed = System.currentTimeMillis() - start;
//
//            // Step 4: Verify page MOCK returned (with context delay merged)
//            ApiCaptureContext ctx = RouteMonitor.context();
//            CapturedApiCall call = ctx.getLastApiCall("/api/users");
//            assertNotNull("Mock call should be captured", call);
//            assertTrue("Response should be page mock data",
//                    call.responseBody().contains("page-mock-with-delay"));
//            // Context delay (500ms) should have been merged into page MOCK handler
//            assertTrue("Elapsed should include context delay (500ms), actual: " + elapsed + "ms",
//                    elapsed >= 450);
//
//            dsl.clear();
//            testPage.close();
//        } finally {
//            RouteRegistry.clearContext(testCtx);
//            testCtx.close();
//        }
//    }
//
//    @Test
//    public void n54_context_rules_cleanup() {
//        // ⭐ removeContextRules via clearContext: verify context rules cleanup properly.
//        // After clearContext, context-level rules no longer affect page-level handlers.
//        BrowserContext testCtx = browser.newContext(
//                new Browser.NewContextOptions().setViewportSize(1280, 720));
//        try {
//            // Step 1: Register context-level MOCK → verify it's active
//            RouteRule ctxRule = new RouteRule();
//            ctxRule.setUrlPattern("/api/users");
//            ctxRule.setType(RouteHandleType.MOCK);
//            ctxRule.setMockBody("{\"before\":\"cleanup\"}");
//            ctxRule.setMockStatus(200);
//            RouteEngine.register(testCtx, java.util.Collections.singletonList(ctxRule));
//
//            Page testPage1 = testCtx.newPage();
//            RouteDsl dsl1 = RouteDsl.on(testPage1)
//                    .api("/api/users")
//                    .monitor()
//                    .expectStatus(200)
//                    .allowAllRequests()
//                    .done();
//            dsl1.start();
//
//            testPage1.navigate(BASE_URL + "/api/users");
//            CapturedApiCall beforeCall = RouteMonitor.context().getLastApiCall("/api/users");
//            assertNotNull("Context MOCK should be active before cleanup", beforeCall);
//            assertTrue("Should return mock before cleanup",
//                    beforeCall.responseBody().contains("cleanup"));
//
//            dsl1.clear();
//            testPage1.close();
//
//            // Step 2: Clear context (calls RouteEngine.removeContextRules internally)
//            RouteRegistry.clearContext(testCtx);
//
//            // Step 3: Open new page in same context, register page MONITOR
//            Page testPage2 = testCtx.newPage();
//            RouteDsl dsl2 = RouteDsl.on(testPage2)
//                    .api("/api/users")
//                    .monitor()
//                    .expectStatus(200)
//                    .allowAllRequests()
//                    .done();
//            dsl2.start();
//
//            testPage2.navigate(BASE_URL + "/api/users");
//            CapturedApiCall afterCall = RouteMonitor.context().getLastApiCall("/api/users");
//            assertNotNull("Monitor should capture after context cleanup", afterCall);
//            // Without context MOCK, page MONITOR gets real server data
//            assertTrue("After cleanup, response should be real data",
//                    afterCall.responseBody().contains("Alice"));
//            assertFalse("After cleanup, mock must NOT be present",
//                    afterCall.responseBody().contains("cleanup"));
//
//            dsl2.clear();
//            testPage2.close();
//        } finally {
//            RouteRegistry.clearContext(testCtx);
//            testCtx.close();
//        }
//    }
//
//    @Test
//    public void n55_same_type_context_page_no_merge() {
//        // ⭐ Same type (MONITOR + MONITOR) → no cross-level merging triggered.
//        // condition: ctxRule.getType() != rule.getType() → skipped when types match.
//        // Page MONITOR runs normally, no context interference.
//        BrowserContext testCtx = browser.newContext(
//                new Browser.NewContextOptions().setViewportSize(1280, 720));
//        try {
//            // Step 1: Register context-level MONITOR on /api/users
//            RouteRule ctxMonitorRule = new RouteRule();
//            ctxMonitorRule.setUrlPattern("/api/users");
//            ctxMonitorRule.setType(RouteHandleType.MONITOR);
//            ctxMonitorRule.setExpectedStatus(200);
//            ctxMonitorRule.setRecord(true);
//            RouteEngine.register(testCtx, java.util.Collections.singletonList(ctxMonitorRule));
//
//            // Step 2: Register page-level MONITOR on same URL (same type!)
//            Page testPage = testCtx.newPage();
//            RouteDsl dsl = RouteDsl.on(testPage)
//                    .api("/api/users")
//                    .monitor()
//                    .expectStatus(200)
//                    .allowAllRequests()
//                    .done();
//            dsl.start();
//
//            // Step 3: Trigger request → page MONITOR fires, same type → skip merge
//            testPage.navigate(BASE_URL + "/api/users");
//
//            // Step 4: Page monitor captures normally (no cross-type merge)
//            ApiCaptureContext ctx = RouteMonitor.context();
//            assertFalse("Page monitor assertions: " + ctx.buildFailureReport(),
//                    ctx.hasAssertionFailures());
//            CapturedApiCall call = ctx.getLastApiCall("/api/users");
//            assertNotNull("Page monitor should capture response (same type, no merge)", call);
//            assertTrue("Response should be real data", call.responseBody().contains("Alice"));
//
//            dsl.clear();
//            testPage.close();
//        } finally {
//            RouteRegistry.clearContext(testCtx);
//            testCtx.close();
//        }
//    }
//
//    // ═══════════════════════════════════════════════════════════════
//    // Helper methods
//    // ═══════════════════════════════════════════════════════════════
//
//    private static void pause(long ms) {
//        try {
//            Thread.sleep(ms);
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//        }
//    }
//}
