package com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Route;
import net.serenitybdd.core.Serenity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * API Monitor and Mock Manager - 企业级API监控、Mock和响应修改工具
 *
 * <p>核心能力：
 * <ul>
 *   <li><b>Mock</b> - 拦截请求并返回自定义响应（完全替换）</li>
 *   <li><b>Intercept</b> - 拦截真实API响应，修改字段后返回给前端（部分篡改）</li>
 *   <li><b>Monitor</b> - 记录所有API调用历史，支持Serenity报告集成</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li>ThreadLocal 实例管理 → 多线程并行测试互不干扰</li>
 *   <li>Route 去重绑定 → 防止同一URL被重复拦截</li>
 *   <li>统一路由入口 → 一个 route handler 统一处理 Mock / Intercept / Pass-through</li>
 *   <li>异常安全 → 所有 route 处理均有 fallback 到 resume()</li>
 * </ul>
 *
 * <p>推荐用法：
 * <pre>{@code
 * // 1. Mock 完全替换响应
 * ApiMonitorAndMockManager.mock(context)
 *     .forUrl("/api/users")
 *     .withStatus(200)
 *     .withResponse("{\"status\":\"success\"}")
 *     .build();
 *
 * // 2. Intercept 拦截并修改真实响应
 * ApiMonitorAndMockManager.intercept(context)
 *     .forUrl("/api/last")
 *     .modify("$.securityCode", "123456")
 *     .build();
 *
 * // 3. 清理（测试结束后务必调用）
 * ApiMonitorAndMockManager.cleanup(context);
 * }</pre>
 */
public class ApiMonitorAndMockManager {

    // ==================== 常量 ====================

    private static final Logger logger = LoggerFactory.getLogger(ApiMonitorAndMockManager.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ==================== ThreadLocal 实例管理 ====================
    /**
     * 线程级实例存储 — 保证多线程/并行测试时规则和历史互不污染。
     * 外部通过 static 方法调用时自动委托到当前线程实例。
     */
    private static final ThreadLocal<ApiMonitorAndMockManager> INSTANCE = ThreadLocal.withInitial(ApiMonitorAndMockManager::new);

    /** 获取当前线程实例 */
    private static ApiMonitorAndMockManager getInstance() {
        return INSTANCE.get();
    }

    /** 获取当前线程实例并初始化目标引用 */
    private static ApiMonitorAndMockManager getInstance(Page page, BrowserContext context) {
        ApiMonitorAndMockManager inst = INSTANCE.get();
        inst.targetPage = page;
        inst.targetContext = context;
        return inst;
    }

    /** 清理当前线程实例（测试结束时应调用） */
    public static void cleanup() {
        ApiMonitorAndMockManager inst = INSTANCE.get();
        inst.clearAll();
        INSTANCE.remove();
    }

    public static void cleanup(Page page) {
        try { page.unrouteAll(); } catch (Exception ignored) {}
        cleanup();
    }

    public static void cleanup(BrowserContext context) {
        try { context.unrouteAll(); } catch (Exception ignored) {}
        cleanup();
    }

    // ==================== 实例字段（非静态） ====================

    /** 当前关联的 Page */
    private Page targetPage;
    /** 当前关联的 BrowserContext */
    private BrowserContext targetContext;
    /** 已注册的 Mock 规则 */
    private final Map<String, MockRule> mockRules = new ConcurrentHashMap<>();
    /** 已注册的拦截修改规则 */
    private final List<InterceptRule> interceptRules = new CopyOnWriteArrayList<>();
    /** API调用历史 */
    private final List<ApiCallRecord> apiCallHistory = new CopyOnWriteArrayList<>();
    /** 已绑定的 URL Pattern 集合（防止重复 route 绑定） */
    private final Set<String> registeredPatterns = ConcurrentHashMap.newKeySet();

    // ==================== 内部数据类 ====================

    /**
     * API 调用记录 — 记录完整的请求/响应对信息
     */
    public static class ApiCallRecord {
        private final String requestId;
        private final String url;
        private final String method;
        private final long timestamp;
        private final Map<String, String> requestHeaders;
        private final Object requestBody;
        private final int statusCode;
        private final Map<String, String> responseHeaders;
        private final Object responseBody;  // 修复：完整记录响应体
        private final Type type;            // MOCK / INTERCEPT / REAL

        public enum Type { MOCK, INTERCEPT, REAL }

        public ApiCallRecord(String requestId, String url, String method, long timestamp,
                             Map<String, String> requestHeaders, Object requestBody,
                             int statusCode, Map<String, String> responseHeaders,
                             Object responseBody, Type type) {
            this.requestId = requestId;
            this.url = url;
            this.method = method;
            this.timestamp = timestamp;
            this.requestHeaders = requestHeaders;
            this.requestBody = requestBody;
            this.statusCode = statusCode;
            this.responseHeaders = responseHeaders;
            this.responseBody = responseBody;
            this.type = type;
        }

        public String getRequestId() { return requestId; }
        public String getUrl() { return url; }
        public String getMethod() { return method; }
        public long getTimestamp() { return timestamp; }
        public Map<String, String> getRequestHeaders() { return requestHeaders; }
        public Object getRequestBody() { return requestBody; }
        public int getStatusCode() { return statusCode; }
        public Map<String, String> getResponseHeaders() { return responseHeaders; }
        public Object getResponseBody() { return responseBody; }
        public Type getType() { return type; }
        public boolean isMocked() { return Type.MOCK == type; }
        public boolean isIntercepted() { return Type.INTERCEPT == type; }
    }

    /**
     * Mock 规则 — 定义如何拦截请求并返回自定义响应
     */
    public static class MockRule {
        private String name;
        private String urlPattern;       // glob URL模式（用于 Playwright route）
        private String urlContains;      // 二次精确过滤关键字
        private String method = ".*";    // HTTP方法匹配，默认全部
        private String mockDataPath;     // JSON 文件路径
        private String mockDataJson;     // 直接提供的JSON字符串
        private int statusCode = 200;
        private final Map<String, String> headers = new HashMap<>();
        private long delayMs;            // 模拟延迟(ms)
        private boolean enabled = true;
        private ResponseGenerator responseGenerator;

        public MockRule(String name, String urlPattern) {
            this.name = name;
            this.urlPattern = urlPattern;
            this.headers.put("Content-Type", "application/json");
        }

        // ---- Builder 链式方法 ----
        public MockRule method(String method)           { this.method = method; return this; }
        public MockRule mockDataPath(String path)        { this.mockDataPath = path; return this; }
        public MockRule mockDataJson(String json)         { this.mockDataJson = json; return this; }
        public MockRule statusCode(int code)             { this.statusCode = code; return this; }
        public MockRule header(String key, String value)  { this.headers.put(key, value); return this; }
        public MockRule delay(long ms)                    { this.delayMs = ms; return this; }
        public MockRule enabled(boolean enabled)          { this.enabled = enabled; return this; }
        public MockRule responseGenerator(ResponseGenerator g) { this.responseGenerator = g; return this; }
        public MockRule hostUrl(String hostUrl)            { this.urlContains = hostUrl; return this; }
        public MockRule urlPattern(String p)               { this.urlPattern = p; return this; }
        public MockRule endpoint(String ep)                { this.urlContains = ep; return this; }

        // ---- Getters ----
        public String getName()              { return name; }
        public String getUrlPattern()        { return urlPattern; }
        public String getUrlContains()       { return urlContains; }
        public String getMethod()            { return method; }
        public String getMockDataPath()      { return mockDataPath; }
        public String getMockDataJson()      { return mockDataJson; }
        public int getStatusCode()           { return statusCode; }
        public Map<String, String> getHeaders() { return headers; }
        public long getDelayMs()             { return delayMs; }
        public boolean isEnabled()           { return enabled; }
        public ResponseGenerator getResponseGenerator() { return responseGenerator; }
    }

    /**
     * 拦截修改规则 — 拦截真实API请求，获取响应后修改字段再返回前端
     */
    public static class InterceptRule {
        private String name;
        private String urlPattern;       // glob URL模式
        private String urlContains;      // 二次精确过滤
        private String method = ".*";
        private ResponseModifier modifier;              // 自定义修改器
        private final Map<String, Object> jsonPathMods = new HashMap<>(); // JsonPath批量修改
        private boolean enabled = true;

        public InterceptRule(String name, String urlPattern) {
            this.name = name;
            this.urlPattern = urlPattern;
        }

        void setUrlContains(String s)      { this.urlContains = s; }
        void setMethod(String m)           { this.method = m; }
        void setModifier(ResponseModifier m) { this.modifier = m; }
        void setEnabled(boolean e)         { this.enabled = e; }

        public String getName()                    { return name; }
        public String getUrlPattern()              { return urlPattern; }
        public String getUrlContains()             { return urlContains; }
        public String getMethod()                  { return method; }
        public ResponseModifier getModifier()      { return modifier; }
        public Map<String, Object> getJsonPathModifications() { return jsonPathMods; }
        public boolean isEnabled()                 { return enabled; }
    }

    // ==================== 函数式接口 ====================

    /** 动态响应生成器 — 根据请求内容生成不同响应 */
    @FunctionalInterface
    public interface ResponseGenerator {
        String generate(Request request, Map<String, Object> context);
    }

    /** 响应修改器 — 接收原始body，返回修改后的body */
    @FunctionalInterface
    public interface ResponseModifier {
        String modify(String originalBody);
    }

    // ==================== 公开工厂方法（Builder 入口） ====================

    /** 创建 Mock 配置器（Page级别） */
    public static MockBuilder mock(Page page) {
        return new MockBuilder(getInstance(page, null));
    }

    /** 创建 Mock 配置器（BrowserContext级别） */
    public static MockBuilder mock(BrowserContext context) {
        return new MockBuilder(getInstance(null, context));
    }

    /** 创建拦截修改配置器（Page级别） */
    public static InterceptBuilder intercept(Page page) {
        return new InterceptBuilder(getInstance(page, null));
    }

    /** 创建拦截修改配置器（BrowserContext级别） */
    public static InterceptBuilder intercept(BrowserContext context) {
        return new InterceptBuilder(getInstance(null, context));
    }

    // ==================== 简化快捷 API（合并 Page/Context 重载） ====================

    /**
     * 一行代码 Mock API 响应
     * <pre>{@code
     * ApiMonitorAndMockManager.mockDirectResponse(pageOrContext, "/api/users", 200, "{\"ok\":true}");
     * }</pre>
     */
    public static void mockDirectResponse(Object pageOrContext, String urlPattern,
                                          int statusCode, String responseData) {
        ApiMonitorAndMockManager inst = resolveTarget(pageOrContext);
        String glob = toGlobPattern(urlPattern);
        logger.info("[Mock] Direct: {} status={}", glob, statusCode);

        MockRule rule = new MockRule("mock-" + glob, glob)
                .statusCode(statusCode).mockDataJson(responseData);
        inst.registerMock(rule);
        inst.applyRoutes();
    }

    /** Mock 成功响应（默认200） */
    public static void mockDirectSuccess(Object pageOrContext, String urlPattern, String responseData) {
        mockDirectResponse(pageOrContext, urlPattern, 200, responseData);
    }

    /** Mock 错误响应 */
    public static void mockDirectError(Object pageOrContext, String urlPattern,
                                       int statusCode, String errorData) {
        mockDirectResponse(pageOrContext, urlPattern, statusCode, errorData);
    }

    /** Mock 超时响应 */
    public static void mockTimeout(Object pageOrContext, String urlPattern,
                                   long timeoutMs, String responseData) {
        ApiMonitorAndMockManager inst = resolveTarget(pageOrContext);
        String glob = toGlobPattern(urlPattern);
        logger.info("[Mock] Timeout: {} delay={}ms", glob, timeoutMs);

        MockRule rule = new MockRule("mock-timeout-" + glob, glob)
                .statusCode(408).delay(timeoutMs).mockDataJson(responseData);
        inst.registerMock(rule);
        inst.applyRoutes();
    }

    /** 动态响应生成 */
    public static void mockDynamic(Object pageOrContext, String urlPattern,
                                   ResponseGenerator generator) {
        ApiMonitorAndMockManager inst = resolveTarget(pageOrContext);
        String glob = toGlobPattern(urlPattern);
        MockRule rule = new MockRule("mock-dynamic-" + glob, glob).responseGenerator(generator);
        inst.registerMock(rule);
        inst.applyRoutes();
    }

    // ==================== 查询 API ====================

    public static List<ApiCallRecord> getApiCallHistory() {
        return Collections.unmodifiableList(getInstance().apiCallHistory);
    }

    public static List<ApiCallRecord> getApiCallHistoryByUrl(String urlRegex) {
        Pattern pat = Pattern.compile(urlRegex);
        return getInstance().apiCallHistory.stream()
                .filter(r -> pat.matcher(r.getUrl()).matches())
                .collect(Collectors.toList());
    }

    public static int getMockRuleCount() { return getInstance().mockRules.size(); }
    public static int getInterceptRuleCount() { return getInstance().interceptRules.size(); }
    public static boolean hasMockForUrl(String urlPattern) {
        String glob = toGlobPattern(urlPattern);
        return getInstance().mockRules.values().stream().anyMatch(r -> r.getUrlPattern().equals(glob));
    }
    public static Map<String, MockRule> getAllMockRules() {
        return new HashMap<>(getInstance().mockRules);
    }

    // ==================== 管理 / 清理 API（合并 Page/Context） ====================

    /** 停止所有路由并清除规则 */
    public static void stopAllMocks(Object pageOrContext) {
        safeUnrouteAll(pageOrContext);
        getInstance().mockRules.clear();
        logger.debug("[Cleanup] All mocks cleared");
    }

    /** 停止特定URL的路由 */
    public static void stopMock(Object pageOrContext, String urlPattern) {
        safeUnroute(pageOrContext, toGlobPattern(urlPattern));
        logger.debug("[Cleanup] Mock stopped: {}", urlPattern);
    }

    /** 仅清除规则（不unroute） */
    public static void clearAllMocks() {
        getInstance().mockRules.clear();
    }

    public static void clearAllIntercepts() {
        getInstance().interceptRules.clear();
    }

    public static void stopAllIntercepts(Object pageOrContext) {
        safeUnrouteAll(pageOrContext);
        getInstance().interceptRules.clear();
    }

    // ==================== Serenity 报告集成 ====================

    public static void recordToSerenityReport() {
        recordMockConfiguration();
        recordApiCallHistory();
    }

    @SuppressWarnings("unchecked")
    private static void recordMockConfiguration() {
        try {
            ApiMonitorAndMockManager inst = getInstance();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("title", "Mock Configuration");
            report.put("totalRules", inst.mockRules.size());
            if (!inst.mockRules.isEmpty()) {
                List<Map<String, Object>> rulesList = new ArrayList<>();
                int idx = 1;
                for (MockRule r : inst.mockRules.values()) {
                    Map<String, Object> rm = new LinkedHashMap<>();
                    rm.put("#", idx++);
                    rm.put("name", r.getName());
                    rm.put("urlPattern", r.getUrlPattern());
                    rm.put("method", r.getMethod());
                    rm.put("statusCode", r.getStatusCode());
                    rm.put("enabled", r.isEnabled());
                    rm.put("delayMs", r.getDelayMs());
                    rulesList.add(rm);
                }
                report.put("rules", rulesList);
            } else {
                report.put("message", "No mock rules configured");
            }
            Serenity.recordReportData()
                    .withTitle("Mock Configuration")
                    .andContents(OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        } catch (Exception e) {
            logger.warn("Failed to record mock config to Serenity report", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void recordApiCallHistory() {
        try {
            List<ApiCallRecord> history = getInstance().apiCallHistory;
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("title", "API Call History");
            report.put("totalApiCalls", history.size());

            if (!history.isEmpty()) {
                List<Map<String, Object>> calls = new ArrayList<>();
                for (int i = 0; i < history.size(); i++) {
                    ApiCallRecord r = history.get(i);
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("#", i + 1);
                    cm.put("type", r.getType().name());
                    cm.put("url", r.getUrl());
                    cm.put("method", r.getMethod());
                    cm.put("statusCode", r.getStatusCode());
                    calls.add(cm);
                }
                report.put("apiCalls", calls);

                long mockCnt = history.stream().filter(ApiCallRecord::isMocked).count();
                long interCnt = history.stream().filter(ApiCallRecord::isIntercepted).count();
                long realCnt = history.size() - mockCnt - interCnt;
                double total = Math.max(history.size(), 1);

                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("realApiCount", realCnt);
                summary.put("mockApiCount", mockCnt);
                summary.put("interceptApiCount", interCnt);
                summary.put("realPercentage", String.format("%.1f%%", realCnt * 100.0 / total));
                summary.put("mockPercentage", String.format("%.1f%%", mockCnt * 100.0 / total));
                summary.put("interceptPercentage", String.format("%.1f%%", interCnt * 100.0 / total));
                report.put("summary", summary);
            } else {
                report.put("message", "No API calls recorded yet");
            }

            Serenity.recordReportData()
                    .withTitle("API Call History")
                    .andContents(OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        } catch (Exception e) {
            logger.warn("Failed to record call history to Serenity report", e);
        }
    }

    // ==================== 内部核心逻辑 ====================

    /**
     * 解析 Page 或 Context 参数并设置到线程实例
     */
    private static ApiMonitorAndMockManager resolveTarget(Object pageOrContext) {
        if (pageOrContext instanceof Page) {
            return getInstance((Page) pageOrContext, null);
        } else if (pageOrContext instanceof BrowserContext) {
            return getInstance(null, (BrowserContext) pageOrContext);
        }
        throw new IllegalArgumentException(
                "Expected Page or BrowserContext, got: " +
                (pageOrContext != null ? pageOrContext.getClass().getName() : "null"));
    }

    /**
     * 安全执行 unrouteAll（忽略异常）
     */
    private static void safeUnrouteAll(Object pageOrContext) {
        try {
            if (pageOrContext instanceof Page) {
                ((Page) pageOrContext).unrouteAll();
            } else if (pageOrContext instanceof BrowserContext) {
                ((BrowserContext) pageOrContext).unrouteAll();
            }
        } catch (Exception e) {
            logger.debug("unrouteAll failed (may already be empty): {}", e.getMessage());
        }
    }

    /**
     * 安全执行单个 unroute（忽略异常）
     */
    private static void safeUnroute(Object pageOrContext, String globPattern) {
        try {
            if (pageOrContext instanceof Page) {
                ((Page) pageOrContext).unroute(globPattern);
            } else if (pageOrContext instanceof BrowserContext) {
                ((BrowserContext) pageOrContext).unroute(globPattern);
            }
        } catch (Exception e) {
            logger.debug("unroute({}) failed: {}", globPattern, e.getMessage());
        }
    }

    /**
     * 注册 Mock 规则（内部）
     */
    private void registerMock(MockRule rule) {
        mockRules.put(rule.getName(), rule);
        logger.debug("[Mock] Registered: {}", rule.getName());
    }

    /**
     * 注册拦截规则（内部）
     */
    private void registerIntercept(InterceptRule rule) {
        interceptRules.add(rule);
        logger.debug("[Intercept] Registered: {}", rule.getName());
    }

    /**
     * 清除实例内所有状态
     */
    private void clearAll() {
        mockRules.clear();
        interceptRules.clear();
        apiCallHistory.clear();
        registeredPatterns.clear();
    }

    /**
     * 将所有已注册规则应用到目标（统一路由入口）
     *
     * 核心改进：
     * - 使用 {@code registeredPatterns} 做去重，避免同一URL被多次 route() 绑定
     * - Mock 和 Intercept 共用同一个 route handler 分发逻辑
     */
    private void applyRoutes() {
        if (targetPage == null && targetContext == null) {
            logger.warn("[Route] No target Page or Context set, skipping applyRoutes");
            return;
        }

        // --- 注册 Mock 规则 ---
        for (MockRule rule : mockRules.values()) {
            if (!rule.isEnabled()) continue;
            bindRouteIfNeeded(rule.getUrlPattern(), rule);
        }

        // --- 注册 Intercept 规则 ---
        for (InterceptRule rule : interceptRules) {
            if (!rule.isEnabled()) continue;
            bindRouteIfNeeded(rule.getUrlPattern(), rule);
        }
    }

    /**
     * 去重绑定：只在 URL pattern 未注册过时才调用 route()
     */
    private void bindRouteIfNeeded(String globPattern, Object rule) {
        if (registeredPatterns.add(globPattern)) {
            // 首次绑定此 pattern
            java.util.function.Consumer<Route> handler = route -> handleUnifiedRoute(route);
            if (targetPage != null) {
                targetPage.route(globPattern, handler);
            } else if (targetContext != null) {
                targetContext.route(globPattern, handler);
            }
            logger.debug("[Route] Bound: {}", globPattern);
        } else {
            logger.debug("[Route] Already bound, skipping: {}", globPattern);
        }
    }

    /**
     * 统一路由处理器 — 所有请求经过这里分发到对应的 Mock / Intercept / Pass-through
     */
    private void handleUnifiedRoute(Route route) {
        Request req = route.request();
        String url = req.url();

        try {
            // 1. 尝试匹配 Mock 规则
            MockRule matchedMock = findMatchingMock(req);
            if (matchedMock != null) {
                handleMock(route, matchedMock);
                return;
            }

            // 2. 尝试匹配 Intercept 规则
            InterceptRule matchedIntercept = findMatchingIntercept(req);
            if (matchedIntercept != null) {
                handleIntercept(route, matchedIntercept);
                return;
            }

            // 3. 都不匹配 → 放行原始请求
            route.resume();

        } catch (Exception e) {
            logger.error("[Route] Unhandled exception for {}, falling back to resume(): {}", url, e.getMessage(), e);
            safeResume(route);
        }
    }

    /**
     * 查找匹配的 Mock 规则
     */
    private MockRule findMatchingMock(Request req) {
        String url = req.url();
        for (MockRule rule : mockRules.values()) {
            if (!rule.isEnabled()) continue;
            // URL contains 过滤
            if (rule.getUrlContains() != null && !rule.getUrlContains().isEmpty()
                    && !url.contains(rule.getUrlContains())) {
                continue;
            }
            // 方法匹配
            if (!Pattern.matches(rule.getMethod(), req.method())) continue;
            return rule;
        }
        return null;
    }

    /**
     * 查找匹配的 Intercept 规则
     */
    private InterceptRule findMatchingIntercept(Request req) {
        String url = req.url();
        for (InterceptRule rule : interceptRules) {
            if (!rule.isEnabled()) continue;
            if (rule.getUrlContains() != null && !rule.getUrlContains().isEmpty()
                    && !url.contains(rule.getUrlContains())) {
                continue;
            }
            if (!Pattern.matches(rule.getMethod(), req.method())) continue;
            return rule;
        }
        return null;
    }

    // ==================== Mock 处理 ====================

    /**
     * 处理 Mock 请求 — 返回自定义响应
     */
    private void handleMock(Route route, MockRule rule) {
        Request req = route.request();

        // 记录请求信息
        recordCall(route, rule.getStatusCode(), null, ApiCallRecord.Type.MOCK);

        // 获取 Mock 数据
        String mockBody = null;
        try {
            mockBody = getMockBody(rule, route);
        } catch (Exception e) {
            logger.warn("[Mock] Failed to get mock data for '{}', resuming: {}", rule.getName(), e.getMessage());
            safeResume(route);
            return;
        }

        if (mockBody == null) {
            safeResume(route);
            return;
        }

        Route.FulfillOptions opts = new Route.FulfillOptions()
                .setStatus(rule.getStatusCode())
                .setBody(mockBody);
        if (!rule.getHeaders().isEmpty()) {
            opts.setHeaders(rule.getHeaders());
        }

        if (rule.getDelayMs() > 0) {
            fulfillWithDelay(route, opts, rule.getDelayMs(), req.url());
        } else {
            safeFulfill(route, opts, req.url());
        }
    }

    /**
     * 获取 Mock 响应体数据
     */
    private String getMockBody(MockRule rule, Route route) throws Exception {
        if (rule.getResponseGenerator() != null) {
            return rule.getResponseGenerator().generate(route.request(), createRequestContext(route));
        }
        if (rule.getMockDataJson() != null && !rule.getMockDataJson().isEmpty()) {
            return rule.getMockDataJson();
        }
        if (rule.getMockDataPath() != null && !rule.getMockDataPath().isEmpty()) {
            return new String(Files.readAllBytes(Paths.get(rule.getMockDataPath())));
        }
        return null;
    }

    /**
     * 延迟 fulfill — 直接 fulfill（延迟功能已移除，不再使用额外线程）
     */
    private void fulfillWithDelay(Route route, Route.FulfillOptions opts, long delayMs, String url) {
        logger.info("[Mock] Delayed fulfill ({}ms) → executing directly in async callback, no extra thread", delayMs);
        safeFulfill(route, opts, url);
    }

    // ==================== Intercept 处理 ====================

    /**
     * 处理 Intercept 请求 — fetch 真实响应 → 修改 body → fulfill 返回
     *
     * 核心流程：
     * 1. URL/方法匹配（已在 handleUnifiedRoute 中完成）
     * 2. route.fetch() 发送真实请求获取响应
     * 3. 读取响应 body
     * 4. 应用修改（自定义 modifier 优先，否则 JsonPath 批量修改）
     * 5. route.fulfill() 返回修改后的响应
     * 6. 异常时 fallback 到 route.resume()
     */
    private void handleIntercept(Route route, InterceptRule rule) {
        Request req = route.request();
        String url = req.url();

        try {
            logger.debug("[Intercept] Fetching real: {} {}", req.method(), url);

            // fetch 真实响应
            APIResponse realResp = route.fetch();

            // 读取原始 body（处理编码）
            String originalBody = readResponseBody(realResp);

            // 应用修改
            String modifiedBody = applyModification(originalBody, rule);

            // 记录调用（包含完整响应体）
            recordCall(req, realResp.status(), realResp.headers(),
                    modifiedBody, ApiCallRecord.Type.INTERCEPT);

            // fulfill 返回修改后的响应
            Route.FulfillOptions opts = new Route.FulfillOptions()
                    .setStatus(realResp.status())
                    .setHeaders(realResp.headers())
                    .setBody(modifiedBody);

            route.fulfill(opts);
            logger.debug("[Intercept] Done: {} {} | orig={} mod={}", req.method(), url,
                    originalBody.length(), modifiedBody.length());

        } catch (Exception e) {
            logger.error("[Intercept] Error for rule '{}', resuming: {}", rule.getName(), e.getMessage());
            safeResume(route);
        }
    }

    /**
     * 从 Response 读取 body 字符串（处理 charset）
     */
    private static String readResponseBody(APIResponse resp) {
        byte[] bodyBytes = resp.body();
        if (bodyBytes == null || bodyBytes.length == 0) return "";

        String ct = resp.headers().get("content-type");
        if (ct != null && ct.toLowerCase().contains("charset=")) {
            int idx = ct.toLowerCase().indexOf("charset=");
            String cs = ct.substring(idx + 8).trim();
            int semi = cs.indexOf(';');
            if (semi > 0) cs = cs.substring(0, semi);
            return new String(bodyBytes, StandardCharsets.UTF_8); // 大多数情况 UTF_8 兼容
        }
        return new String(bodyBytes, StandardCharsets.UTF_8);
    }

    /**
     * 应用响应修改 — 自定义 modifier 优先，其次 JsonPath 批量修改
     */
    private static String applyModification(String body, InterceptRule rule) {
        // 1. 自定义修改器
        if (rule.getModifier() != null) {
            try {
                return rule.getModifier().modify(body);
            } catch (Exception e) {
                logger.warn("[Intercept] Custom modifier failed, returning original: {}", e.getMessage());
                return body;
            }
        }

        // 2. JsonPath 批量字段修改
        Map<String, Object> mods = rule.getJsonPathModifications();
        if (mods == null || mods.isEmpty()) return body;

        try {
            DocumentContext ctx = JsonPath.parse(body);
            for (Map.Entry<String, Object> entry : mods.entrySet()) {
                try {
                    ctx.set(entry.getKey(), entry.getValue());
                } catch (PathNotFoundException ignored) {
                    logger.trace("[Intercept] JsonPath not found: {}", entry.getKey());
                }
            }
            return OBJECT_MAPPER.writeValueAsString(ctx.json());
        } catch (Exception e) {
            logger.warn("[Intercept] JsonPath modification failed, returning original: {}", e.getMessage());
            return body;
        }
    }

    /**
     * 内部辅助：用于 InterceptBuilder 两阶段修改（先JsonPath再自定义）
     */
    static String applyModificationInternal(String body, Map<String, Object> jsonPathMods) {
        if (jsonPathMods == null || jsonPathMods.isEmpty()) return body;
        try {
            DocumentContext ctx = JsonPath.parse(body);
            jsonPathMods.forEach((p, v) -> {
                try { ctx.set(p, v); } catch (PathNotFoundException ignored) {}
            });
            return OBJECT_MAPPER.writeValueAsString(ctx.json());
        } catch (Exception e) {
            return body;
        }
    }

    // ==================== 记录与工具方法 ====================

    /**
     * 记录 Mock 类型调用（无真实响应头）
     */
    private void recordCall(Route route, int statusCode, Object responseBody, ApiCallRecord.Type type) {
        try {
            Request req = route.request();
            apiCallHistory.add(new ApiCallRecord(
                    UUID.randomUUID().toString(), req.url(), req.method(), System.currentTimeMillis(),
                    new HashMap<>(req.headers()), req.postData(),
                    statusCode, Collections.emptyMap(), responseBody, type));
        } catch (Exception e) {
            logger.warn("[Record] Failed to record call: {}", e.getMessage());
        }
    }

    /**
     * 记录 Intercept 类型调用（有完整真实响应信息）
     */
    private void recordCall(Request req, int statusCode,
                            Map<String, String> respHeaders, Object responseBody, ApiCallRecord.Type type) {
        try {
            apiCallHistory.add(new ApiCallRecord(
                    UUID.randomUUID().toString(), req.url(), req.method(), System.currentTimeMillis(),
                    new HashMap<>(req.headers()), req.postData(),
                    statusCode, respHeaders != null ? respHeaders : Collections.emptyMap(),
                    responseBody, type));
        } catch (Exception e) {
            logger.warn("[Record] Failed to record intercepted call: {}", e.getMessage());
        }
    }

    private static Map<String, Object> createRequestContext(Route route) {
        Map<String, Object> ctx = new HashMap<>();
        Request req = route.request();
        ctx.put("url", req.url());
        ctx.put("method", req.method());
        ctx.put("headers", req.headers());
        try { ctx.put("postData", req.postData() != null ? req.postData() : ""); }
        catch (Exception e) { ctx.put("postData", ""); }
        return ctx;
    }

    /**
     * 安全 fulfill（带异常兜底 resume）
     */
    private static void safeFulfill(Route route, Route.FulfillOptions opts, String url) {
        try {
            route.fulfill(opts);
        } catch (Exception e) {
            logger.warn("[Route] fulfill failed for {}, trying resume: {}", url, e.getMessage());
            safeResume(route);
        }
    }

    /**
     * 安全 resume（带异常吞没）
     */
    private static void safeResume(Route route) {
        try { route.resume(); } catch (Exception ignored) {}
    }

    // ==================== URL 工具方法 ====================

    /**
     * 将普通URL转换为 Playwright glob 模式
     *
     * @param urlPattern 如 "/api/users" 或 "api/users" 或 "*.example.com/api/**"
     * @return 如果已经是glob格式则原样返回；否则包装为 "**{pattern}**"
     */
    static String toGlobPattern(String urlPattern) {
        if (urlPattern == null || urlPattern.isEmpty()) return "**";

        // 已经是 glob 模式（含 * 或 **），直接返回
        if (urlPattern.contains("*")) return urlPattern;

        // 移除前导斜杠
        String normalized = urlPattern.startsWith("/") ? urlPattern.substring(1) : urlPattern;
        return "**/" + normalized + "**";
    }

    // ==================== Mock Builder ====================

    /**
     * Mock 构建器 — 流式配置 Mock 规则
     *
     * <pre>{@code
     * ApiMonitorAndMockManager.mock(context)
     *     .forUrl("/api/users")
     *     .withStatus(201)
     *     .withResponse("{\"id\":1}")
     *     .withHeader("X-Custom", "test")
     *     .build();
     * }</pre>
     */
    public static class MockBuilder {
        private final ApiMonitorAndMockManager instance;
        private final List<MockRule> rules = new ArrayList<>();
        private boolean autoClear = true;

        private MockBuilder(ApiMonitorAndMockManager instance) {
            this.instance = instance;
        }

        public MockBuilder forUrl(String urlPattern) {
            rules.add(new MockRule("mock-" + urlPattern, toGlobPattern(urlPattern)));
            return this;
        }

        public MockBuilder forEndpoint(String endpoint) {
            MockRule rule = new MockRule("mock-" + endpoint, ".*").endpoint(endpoint);
            rules.add(rule);
            return this;
        }

        public MockBuilder withStatus(int code)       { last().statusCode(code); return this; }
        public MockBuilder withResponse(String data)   { last().mockDataJson(data); return this; }
        public MockBuilder withDelay(long ms)          { last().delay(ms); return this; }
        public MockBuilder withHeader(String k, String v) { last().header(k, v); return this; }
        public MockBuilder withGenerator(ResponseGenerator g) { last().responseGenerator(g); return this; }
        public MockBuilder method(String m)             { last().method(m); return this; }

        public MockBuilder autoClearRules(boolean b) { this.autoClear = b; return this; }

        public void build() {
            if (autoClear) {
                instance.mockRules.clear();
                instance.registeredPatterns.clear();
            }
            for (MockRule r : rules) instance.registerMock(r);
            instance.applyRoutes();
            recordToSerenityReport();
            logger.info("[Mock] Built {} rule(s)", rules.size());
        }

        private MockRule last() {
            if (rules.isEmpty()) throw new IllegalStateException("No rule added yet");
            return rules.get(rules.size() - 1);
        }
    }

    // ==================== Intercept Builder ====================

    /**
     * 拦截构建器 — 流式配置响应拦截+修改规则
     *
     * <p>三种使用方式：
     * <ol>
     *   <li>JsonPath 字段级修改（简单场景）</li>
     *   <li>自定义修改器（复杂场景）</li>
     *   <li>两者组合（先 JsonPath 再自定义）</li>
     * </ol>
     *
     * <pre>{@code
     * // 方式1: JsonPath
     * ApiMonitorAndMockManager.intercept(context)
     *     .forUrl("/api/user/profile")
     *     .modify("$.data.role", "admin")
     *     .build();
     *
     * // 方式2: 自定义修改器
     * ApiMonitorAndMockManager.intercept(context)
     *     .forUrl("/api/payment")
     *     .thenModify(body -> { ...; return result; })
     *     .build();
     *
     * // 方式3: 组合
     * ApiMonitorAndMockManager.intercept(context)
     *     .forUrl("/api/last")
     *     .modify("$.securityCode", "123456")
     *     .thenModify(body -> extraProcessing(body))
     *     .build();
     * }</pre>
     */
    public static class InterceptBuilder {
        private final ApiMonitorAndMockManager instance;
        private final List<InterceptRule> rules = new ArrayList<>();
        private boolean autoClear = true;

        private InterceptBuilder(ApiMonitorAndMockManager instance) {
            this.instance = instance;
        }

        public InterceptBuilder forUrl(String urlPattern) {
            rules.add(new InterceptRule("intercept-" + urlPattern, toGlobPattern(urlPattern)));
            return this;
        }

        public InterceptBuilder forUrlContains(String keyword) {
            lastRule().setUrlContains(keyword);
            return this;
        }

        public InterceptBuilder method(String m) {
            lastRule().setMethod(m);
            return this;
        }

        public InterceptBuilder modify(String jsonPath, Object value) {
            lastRule().getJsonPathModifications().put(jsonPath, value);
            return this;
        }

        public InterceptBuilder modifications(Map<String, Object> map) {
            if (map != null) lastRule().getJsonPathModifications().putAll(map);
            return this;
        }

        public InterceptBuilder thenModify(ResponseModifier modifier) {
            InterceptRule last = lastRule();
            Map<String, Object> existingMods = last.getJsonPathModifications();

            if (existingMods != null && !existingMods.isEmpty()) {
                // 两阶段：先 JsonPath → 再自定义
                final ResponseModifier userModifier = modifier;
                final Map<String, Object> modsSnapshot = new HashMap<>(existingMods);
                last.setModifier(body -> {
                    String afterJsonPath = applyModificationInternal(body, modsSnapshot);
                    return userModifier.modify(afterJsonPath);
                });
                existingMods.clear(); // 避免重复应用
            } else {
                last.setModifier(modifier);
            }
            return this;
        }

        public InterceptBuilder autoClear(boolean b) { this.autoClear = b; return this; }

        public void build() {
            if (autoClear) {
                instance.interceptRules.clear();
                instance.registeredPatterns.clear();
            }
            for (InterceptRule r : rules) instance.registerIntercept(r);
            instance.applyRoutes();
            logger.info("[Intercept] Built {} rule(s)", rules.size());
        }

        private InterceptRule lastRule() {
            if (rules.isEmpty()) throw new IllegalStateException("No intercept rule added yet");
            return rules.getLast();
        }
    }
}
