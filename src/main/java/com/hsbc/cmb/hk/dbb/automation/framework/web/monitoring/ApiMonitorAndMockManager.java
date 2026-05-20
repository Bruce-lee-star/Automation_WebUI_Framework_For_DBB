package com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.ContextLifecycleHookManager;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import net.serenitybdd.core.Serenity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Iterator;

/**
 * API Monitor and Mock Manager - 企业级API Mock工具
 *
 * <p>核心能力：
 * <ul>
 *   <li><b>Mock</b> - 拦截请求并返回自定义响应（完全替换）</li>
 *   <li><b>Monitor</b> - 配合 RealApiMonitor 记录所有API调用历史，支持Serenity报告集成</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li>ThreadLocal 实例管理 → 多线程并行测试互不干扰</li>
 *   <li>Route 去重绑定 → 防止同一URL被重复拦截</li>
 *   <li>统一路由入口 → 一个 route handler 统一处理 Mock / Pass-through</li>
 *   <li>异常安全 → 所有 route 处理均有 fallback 到 resume()</li>
 *   <li>Context 生命周期钩子 → Context 重建后自动重绑规则</li>
 * </ul>
 *
 * <p>推荐用法：
 * <pre>{@code
 * // 1. 使用 RealApiMonitor 监控目标 API，获取真实响应
 * RealApiMonitor.monitor(context).forUrl("/api/user/info**").build();
 * // 页面加载后，获取记录的响应信息...
 * ApiCallRecord record = RealApiMonitor.getRecordedResponse("api/user/info");
 *
 * // 2. 修改响应内容后，使用 Mock 返回
 * String modifiedResponse = modifyResponse(record.getResponseBody());
 * ApiMonitorAndMockManager.mock(context)
 *     .forUrl("api/user/info")
 *     .withResponse(modifiedResponse)
 *     .build();
 *
 * // 3. 刷新页面，Mock 生效
 * page.reload();
 *
 * // 4. 清理（测试结束后务必调用）
 * ApiMonitorAndMockManager.cleanup(context);
 * }</pre>
 */
public class ApiMonitorAndMockManager implements ContextLifecycleHookManager.RuleCapturer {

    // ==================== 常量 ====================

    private static final Logger logger = LoggerFactory.getLogger(ApiMonitorAndMockManager.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** API调用历史最大容量，防止内存泄漏 */
    private static final int MAX_API_HISTORY_SIZE = 1000;
    /** 默认 HTTP 成功状态码 */
    private static final int DEFAULT_SUCCESS_STATUS = 200;
    /** 默认超时状态码 */
    private static final int DEFAULT_TIMEOUT_STATUS = 408;
    /** 字符编码（使用 StandardCharsets 规范常量） */
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    // ==================== Context 生命周期钩子注册 ====================
    // 使用特殊的代理实例，其 captureRules 会从当前线程获取数据
    static {
        ContextLifecycleHookManager.registerCapturer(new ContextLifecycleProxy());
        logger.info("[ApiMonitorAndMockManager] Registered as Context lifecycle hook");
    }

    /**
     * 生命周期钩子代理 — 解决单例注册与 ThreadLocal 多实例的冲突
     * 每次 captureRules/rebind 时都从当前线程获取最新的实例数据
     */
    private static class ContextLifecycleProxy implements ContextLifecycleHookManager.RuleCapturer {
        @Override
        public List<ContextLifecycleHookManager.RuleSnapshot> captureRules(BrowserContext context) {
            // 直接从当前线程获取真实业务实例，而非空对象
            return ApiMonitorAndMockManager.getInstance().captureRules(context);
        }
    }

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
        try {
            ApiMonitorAndMockManager inst = INSTANCE.get();
            inst.clearAll();
        } finally {
            // 必须先获取实例清理数据，再 remove ThreadLocal
            // 使用 try-finally 确保即使 clearAll 抛异常也能清理 ThreadLocal
            INSTANCE.remove();
        }
    }

    public static void cleanup(Page page) {
        try { if (page != null) page.unrouteAll(); } catch (Exception ignored) {}
        cleanup();
    }

    public static void cleanup(BrowserContext context) {
        try { if (context != null) context.unrouteAll(); } catch (Exception ignored) {}
        cleanup();
    }

    // ==================== Context 生命周期钩子实现 ====================

    /**
     * 实现 RuleCapturer 接口：捕获当前所有规则用于 Context 重建后重绑
     */
    @Override
    public List<ContextLifecycleHookManager.RuleSnapshot> captureRules(BrowserContext context) {
        // 获取当前线程实例
        ApiMonitorAndMockManager inst = getInstance();
        
        List<ContextLifecycleHookManager.RuleSnapshot> snapshots = new ArrayList<>();
        
        // 捕获 Mock 规则
        for (MockRule rule : inst.mockRules.values()) {
            snapshots.add(new MockRuleSnapshot(rule));
        }
        
        if (!snapshots.isEmpty()) {
            logger.info("[ApiMonitorAndMockManager] Captured {} mock rules for context lifecycle hook",
                snapshots.size());
        }
        
        return snapshots;
    }

    /**
     * Mock 规则快照 - 用于 Context 重建后重绑
     */
    private static class MockRuleSnapshot implements ContextLifecycleHookManager.RuleSnapshot {
        private final String name;
        private final String urlPattern;
        private final String urlContains;
        private final String method;
        private final String mockDataPath;
        private final String mockDataJson;
        private final int statusCode;
        private final Map<String, String> headers;
        private final long delayMs;
        private final boolean enabled;  // 保存原始 enabled 状态

        public MockRuleSnapshot(MockRule rule) {
            this.name = rule.getName();
            this.urlPattern = rule.getUrlPattern();
            this.urlContains = rule.getUrlContains();
            this.method = rule.getMethod();
            this.mockDataPath = rule.getMockDataPath();
            this.mockDataJson = rule.getMockDataJson();
            this.statusCode = rule.getStatusCode();
            this.headers = rule.getHeaders();
            this.delayMs = rule.getDelayMs();
            this.enabled = rule.isEnabled();
        }

        @Override
        public String getId() { return "mock-" + name; }

        @Override
        public String getUrlPattern() { return urlPattern; }

        @Override
        public boolean rebindTo(BrowserContext newContext) {
            try {
                logger.debug("[MockRuleSnapshot] Rebinding mock rule: {} -> {}", name, urlPattern);
                
                // 生成唯一的规则名（避免同名规则覆盖）
                String uniqueName = name + "-" + Integer.toHexString(urlPattern.hashCode());
                
                // 重新创建 MockRule，恢复原始 enabled 状态
                MockRule rule = new MockRule(uniqueName, urlPattern)
                        .statusCode(statusCode)
                        .mockDataJson(mockDataJson)
                        .method(method)
                        .delay(delayMs)
                        .enabled(enabled);
                
                if (urlContains != null) {
                    rule.endpoint(urlContains);
                }
                
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    rule.header(header.getKey(), header.getValue());
                }
                
                // 获取当前实例并注册到新的 Context
                ApiMonitorAndMockManager inst = getInstance();
                // 移除旧 pattern 的绑定
                inst.unbindPattern(urlPattern);
                // 恢复原始 target 设置（不覆盖其他规则的上下文）
                inst.restoreTargetContext(newContext);
                inst.mockRules.put(rule.getName(), rule);
                inst.applyRoutes();
                
                logger.info("[MockRuleSnapshot] Successfully rebound mock rule: {}", uniqueName);
                return true;
            } catch (Exception e) {
                logger.warn("[MockRuleSnapshot] Failed to rebind mock rule {}: {}", name, e.getMessage(), e);
                return false;
            }
        }

        @Override
        public boolean rebindTo(Page newPage) {
            try {
                logger.debug("[MockRuleSnapshot] Rebinding mock rule to page: {} -> {}", name, urlPattern);
                
                // 生成唯一的规则名
                String uniqueName = name + "-" + Integer.toHexString(urlPattern.hashCode());
                
                // 恢复原始 enabled 状态
                MockRule rule = new MockRule(uniqueName, urlPattern)
                        .statusCode(statusCode)
                        .mockDataJson(mockDataJson)
                        .method(method)
                        .delay(delayMs)
                        .enabled(enabled);
                
                if (urlContains != null) {
                    rule.endpoint(urlContains);
                }
                
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    rule.header(header.getKey(), header.getValue());
                }
                
                ApiMonitorAndMockManager inst = getInstance();
                // 移除旧 pattern 的绑定
                inst.unbindPattern(urlPattern);
                // 恢复原始 target 设置
                inst.restoreTargetPage(newPage);
                inst.mockRules.put(rule.getName(), rule);
                inst.applyRoutes();
                
                logger.info("[MockRuleSnapshot] Successfully rebound mock rule to page: {}", uniqueName);
                return true;
            } catch (Exception e) {
                logger.warn("[MockRuleSnapshot] Failed to rebind mock rule to page {}: {}", name, e.getMessage(), e);
                return false;
            }
        }
    }

    // ==================== 实例字段（非静态） ====================

    /** 当前关联的 Page */
    private volatile Page targetPage;
    /** 当前关联的 BrowserContext */
    private volatile BrowserContext targetContext;
    /** 已注册的 Mock 规则 */
    private final Map<String, MockRule> mockRules = new ConcurrentHashMap<>();
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
        private final Object responseBody;  // 完整记录响应体
        private final Type type;            // MOCK / REAL

        public enum Type { MOCK, REAL }

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
    }

    /**
     * Mock 规则 — 定义如何拦截请求并返回自定义响应
     */
    public static class MockRule {
        private final String name;            // 不可变，构造函数设置
        private final String urlPattern;     // 不可变，构造函数设置
        private String urlContains;           // 二次精确过滤关键字
        private String method = ".*";        // HTTP方法匹配，默认全部
        private String mockDataPath;         // JSON 文件路径
        private String mockDataJson;          // 直接提供的JSON字符串
        private int statusCode = 200;
        private final Map<String, String> headers = new HashMap<>();
        private long delayMs;                // 模拟延迟(ms)
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
        /** 设置 URL 关键字过滤（urlContains），请求 URL 必须包含此关键字才能匹配 */
        public MockRule endpoint(String ep)                { this.urlContains = ep; return this; }

        // ---- Getters ----
        public final String getName()              { return name; }
        public final String getUrlPattern()        { return urlPattern; }
        public final String getUrlContains()       { return urlContains; }
        public final String getMethod()            { return method; }
        public final String getMockDataPath()      { return mockDataPath; }
        public final String getMockDataJson()      { return mockDataJson; }
        public final int getStatusCode()           { return statusCode; }
        /** 返回可修改的副本，避免 Playwright fulfill 时抛 UnsupportedOperationException */
        public final Map<String, String> getHeaders() { return new HashMap<>(headers); }
        public final long getDelayMs()             { return delayMs; }
        public final boolean isEnabled()           { return enabled; }
        public final ResponseGenerator getResponseGenerator() { return responseGenerator; }
    }

    // ==================== 函数式接口 ====================

    /** 动态响应生成器 — 根据请求内容生成不同响应 */
    @FunctionalInterface
    public interface ResponseGenerator {
        String generate(Request request, Map<String, Object> context);
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
        mockDirectResponse(pageOrContext, urlPattern, DEFAULT_SUCCESS_STATUS, responseData);
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
                .statusCode(DEFAULT_TIMEOUT_STATUS).delay(timeoutMs).mockDataJson(responseData);
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
        return Collections.unmodifiableList(new ArrayList<>(getInstance().apiCallHistory));
    }

    public static List<ApiCallRecord> getApiCallHistoryByUrl(String urlRegex) {
        Pattern pat = Pattern.compile(urlRegex);
        return getInstance().apiCallHistory.stream()
                .filter(r -> pat.matcher(r.getUrl()).matches())
                .collect(Collectors.toList());
    }

    public static int getMockRuleCount() { return getInstance().mockRules.size(); }
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
        ApiMonitorAndMockManager inst = getInstance();
        // 解绑传入目标
        safeUnrouteAll(pageOrContext);
        // 解绑当前实例绑定的所有路由（防止残留）
        safeUnrouteAll(inst.targetPage);
        safeUnrouteAll(inst.targetContext);
        inst.mockRules.clear();
        inst.registeredPatterns.clear();
        logger.info("[Cleanup] All mocks stopped and cleared");
    }

    /** 停止特定URL的路由 */
    public static void stopMock(Object pageOrContext, String urlPattern) {
        String glob = toGlobPattern(urlPattern);
        ApiMonitorAndMockManager inst = getInstance();
        // 统一解绑传入目标（内部会处理 Page/Context 类型）
        safeUnroute(pageOrContext, glob);
        // 清理 mockRules 和 registeredPatterns
        inst.mockRules.entrySet().removeIf(e -> e.getValue().getUrlPattern().equals(glob));
        inst.registeredPatterns.remove(glob);
        logger.info("[Cleanup] Mock stopped: {} -> {}", urlPattern, glob);
    }

    /** 仅清除规则（不unroute） */
    public static void clearAllMocks() {
        ApiMonitorAndMockManager inst = getInstance();
        inst.mockRules.clear();
        inst.registeredPatterns.clear();
        logger.debug("[Cleanup] All mock rules cleared (routes still bound)");
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
                long realCnt = history.size() - mockCnt;
                double total = Math.max(history.size(), 1);

                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("realApiCount", realCnt);
                summary.put("mockApiCount", mockCnt);
                summary.put("realPercentage", String.format("%.1f%%", realCnt * 100.0 / total));
                summary.put("mockPercentage", String.format("%.1f%%", mockCnt * 100.0 / total));
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
     * @param pageOrContext Page、BrowserContext 或 null
     * @return 当前线程实例
     */
    private static ApiMonitorAndMockManager resolveTarget(Object pageOrContext) {
        if (pageOrContext instanceof Page) {
            return getInstance((Page) pageOrContext, null);
        } else if (pageOrContext instanceof BrowserContext) {
            return getInstance(null, (BrowserContext) pageOrContext);
        }
        return getInstance();
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
     * 使用规则名作为 key，使用 urlPattern hashCode 生成唯一 name 避免覆盖
     */
    private void registerMock(MockRule rule) {
        // 使用 urlPattern hashCode 生成唯一 name，避免同名规则覆盖
        String uniqueName = rule.getName() + "-" + Integer.toHexString(rule.getUrlPattern().hashCode());
        
        // 检查是否已存在同名规则
        MockRule existing = mockRules.get(uniqueName);
        if (existing != null) {
            // 移除旧规则的 pattern 绑定
            unbindPattern(existing.getUrlPattern());
            logger.warn("[Mock] Rule '{}' already exists (url={}), overwriting", 
                    uniqueName, existing.getUrlPattern());
        }
        
        // 更新 rule 的 name 为唯一名称
        MockRule uniqueRule = new MockRule(uniqueName, rule.getUrlPattern())
                .statusCode(rule.getStatusCode())
                .mockDataJson(rule.getMockDataJson())
                .mockDataPath(rule.getMockDataPath())
                .method(rule.getMethod())
                .delay(rule.getDelayMs())
                .enabled(rule.isEnabled())
                .responseGenerator(rule.getResponseGenerator());
        if (rule.getUrlContains() != null) {
            uniqueRule.endpoint(rule.getUrlContains());
        }
        for (Map.Entry<String, String> h : rule.getHeaders().entrySet()) {
            uniqueRule.header(h.getKey(), h.getValue());
        }
        
        mockRules.put(uniqueName, uniqueRule);
        logger.debug("[Mock] Registered: {} (url={})", uniqueName, rule.getUrlPattern());
    }

    /**
     * 解绑指定 pattern 的路由（不清理 mockRules）
     */
    private void unbindPattern(String globPattern) {
        registeredPatterns.remove(globPattern);
        safeUnroute(targetPage, globPattern);
        safeUnroute(targetContext, globPattern);
    }

    /**
     * 恢复 targetContext（用于 rebind，不覆盖其他规则的上下文）
     */
    private void restoreTargetContext(BrowserContext newContext) {
        if (this.targetContext != newContext) {
            // Context 改变时才解绑旧的
            if (this.targetContext != null) {
                for (String pattern : registeredPatterns) {
                    safeUnroute(this.targetContext, pattern);
                }
            }
            this.targetContext = newContext;
        }
        // 不再置空 targetPage，保持 Page 级别的 Mock
    }

    /**
     * 恢复 targetPage（用于 rebind，不覆盖其他规则的上下文）
     */
    private void restoreTargetPage(Page newPage) {
        if (this.targetPage != newPage) {
            if (this.targetPage != null) {
                for (String pattern : registeredPatterns) {
                    safeUnroute(this.targetPage, pattern);
                }
            }
            this.targetPage = newPage;
        }
        // 不再置空 targetContext，保持 Context 级别的 Mock
    }

    /**
     * 清除实例内所有状态
     */
    private void clearAll() {
        // 先解绑所有路由，防止内存泄漏
        safeUnrouteAll(targetPage);
        safeUnrouteAll(targetContext);
        mockRules.clear();
        apiCallHistory.clear();
        registeredPatterns.clear();
        targetPage = null;    // 防止内存泄漏
        targetContext = null; // 防止内存泄漏
    }

    /**
     * 限制 API 调用历史大小，防止内存泄漏
     */
    private void trimApiHistoryIfNeeded() {
        int currentSize = apiCallHistory.size();
        if (currentSize > MAX_API_HISTORY_SIZE) {
            synchronized (apiCallHistory) {
                // 再次检查
                if (apiCallHistory.size() <= MAX_API_HISTORY_SIZE) return;
                int toRemoveCount = apiCallHistory.size() - MAX_API_HISTORY_SIZE;
                // 使用迭代器安全删除，避免 CopyOnWriteArrayList subList().clear() 抛异常
                Iterator<ApiCallRecord> iterator = apiCallHistory.iterator();
                int count = 0;
                while (iterator.hasNext() && count < toRemoveCount) {
                    iterator.next();
                    iterator.remove();
                    count++;
                }
                logger.debug("[History] Trimmed {} old records, kept {} latest", 
                        count, MAX_API_HISTORY_SIZE);
            }
        }
    }

    /**
     * 将所有已注册规则应用到目标（统一路由入口）
     *
     * 核心改进：
     * - 使用 {@code registeredPatterns} 做去重，避免同一URL被多次 route() 绑定
     * - 统一路由入口处理 Mock 请求
     * - 对规则列表创建快照，避免并发修改异常
     * - 【强制清理】每次 applyRoutes 时强制清理所有已注册的路由，防止重复注册链式阻塞
     * - 只解绑 registeredPatterns 中的 pattern，不影响其他框架的路由
     */
    private void applyRoutes() {
        if (targetPage == null && targetContext == null) {
            logger.warn("[Route] No target Page or Context set, skipping applyRoutes");
            return;
        }

        // 对规则列表创建快照
        List<MockRule> rulesSnapshot;
        synchronized (mockRules) {
            rulesSnapshot = new ArrayList<>(mockRules.values());
        }

        // 【关键修复】强制清理所有已注册的路由，防止链式阻塞和白屏
        // 1. 先解绑所有 registeredPatterns 中的路由
        for (String pattern : registeredPatterns) {
            safeUnroute(targetPage, pattern);
            safeUnroute(targetContext, pattern);
        }
        registeredPatterns.clear();
        
        // 2. 额外防护：强制解绑该 endpoint 对应的所有旧路由（防止链式注册残留）
        for (MockRule rule : rulesSnapshot) {
            if (rule.isEnabled()) {
                unbindPattern(rule.getUrlPattern());
            }
        }
        
        // 3. 如果有 targetContext，额外做一次 Context 级别的清理（防止遗漏）
        if (targetContext != null) {
            try {
                // 只解绑我们注册的 pattern，使用 registeredPatterns 作为白名单
                for (String pattern : new ArrayList<>(registeredPatterns)) {
                    // 已在上面清理，无需重复
                }
            } catch (Exception e) {
                logger.debug("[Route] Context-level cleanup skipped: {}", e.getMessage());
            }
        }

        // --- 注册 Mock 规则 ---
        for (MockRule rule : rulesSnapshot) {
            if (!rule.isEnabled()) continue;
            bindRouteIfNeeded(rule.getUrlPattern());
        }
    }

    /**
     * 去重绑定：只在 URL pattern 未注册过时才调用 route()
     * 【修复】改用正则表达式替代 glob pattern，避免 Java PathMatcher 的 ** 匹配 bug
     */
    private void bindRouteIfNeeded(String globPattern) {
        if (registeredPatterns.add(globPattern)) {
            // 首次绑定此 pattern
            // 将 glob pattern 转换为正则表达式，使用 Pattern.compile() 绑定
            java.util.function.Consumer<Route> handler = route -> handleUnifiedRoute(route);
            try {
                // 将 glob **/* 转换为正则 .*/.*
                String regex = globToRegex(globPattern);
                Pattern compiledPattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
                
                if (targetPage != null) {
                    targetPage.route(compiledPattern, handler);
                } else if (targetContext != null) {
                    targetContext.route(compiledPattern, handler);
                }
                logger.info("[Route] Bound regex: {}", regex);
            } catch (Exception e) {
                logger.error("[Route] Failed to bind pattern '{}': {}", globPattern, e.getMessage());
            }
        } else {
            logger.debug("[Route] Already bound, skipping: {}", globPattern);
        }
    }
    
    /**
     * 提取纯路径关键字，剔除通配符
     */
    private static String extractEndpoint(String globPattern) {
        if (globPattern == null || globPattern.isEmpty()) return null;
        return globPattern.replaceAll("\\*+", "").trim();
    }
    
    /**
     * Glob pattern to regex conversion for URL matching
     */
    private static String globToRegex(String pattern) {
        if (pattern == null || pattern.isEmpty()) return ".*";
        if (pattern.contains(".*") || pattern.contains("\\d") || pattern.contains("?") || pattern.contains("+")) {
            return pattern;
        }
        String normalized = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        return ".*" + Pattern.quote(normalized) + ".*";
    }

    /**
     * 统一路由处理器 — 所有请求经过这里分发到对应的 Mock / Pass-through
     * 【修复】使用 Throwable 捕获所有异常，确保每个请求 100% 被处理
     * 【双重兜底】resume 失败则 abort，避免请求永久挂起导致白屏
     */
    private void handleUnifiedRoute(Route route) {
        try {
            MockRule matchedMock = findMatchingMock(route.request());
            if (matchedMock != null) {
                handleMock(route, matchedMock);
                return;
            }
            recordRealRequest(route);
            safeResume(route);

        } catch (Throwable e) {
            // 【双重兜底策略】确保每个请求 100% 被处理
            // 1. 先尝试 resume 放行请求
            try {
                safeResume(route);
            } catch (Throwable resumeEx) {
                // 2. resume 失败，强制 abort 避免请求永久挂起
                logger.error("[ROUTE FATAL] Resume failed, forcing abort for: {} (original error: {}, resume error: {})",
                    route.request().url(), e.getMessage(), resumeEx.getMessage());
                try {
                    route.abort("failedhandler");
                } catch (Throwable abortEx) {
                    // 3. abort 也失败，日志记录，请求确实被永久阻塞（此时白屏已不可避免）
                    logger.error("[ROUTE FATAL] Abort also failed, request {} is permanently blocked: {}",
                        route.request().url(), abortEx.getMessage());
                }
            }
        }
    }

    /**
     * 记录真实请求（不匹配任何 Mock 规则的请求）
     */
    private void recordRealRequest(Route route) {
        Request req = route.request();
        logger.debug("[Real] Pass-through: {} {}", req.method(), req.url());
        recordCall(route, 0, null, ApiCallRecord.Type.REAL);
    }

    /**
     * 查找匹配的 Mock 规则
     * 自动忽略查询参数，同路径不同参数全部命中
     */
    private MockRule findMatchingMock(Request req) {
        String fullUrl = req.url();
        String reqMethod = req.method();

        List<MockRule> rulesSnapshot;
        synchronized (mockRules) {
            rulesSnapshot = new ArrayList<>(mockRules.values());
        }

        for (MockRule rule : rulesSnapshot) {
            if (!rule.isEnabled()) continue;

            String rulePattern = rule.getUrlPattern();
            String regex = globToRegex(rulePattern);

            // 1. URL正则匹配（自带兼容所有?xxx参数）
            if (!Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(fullUrl).matches()) {
                continue;
            }

            // 2. HTTP方法匹配
            String ruleMethod = rule.getMethod();
            if (ruleMethod != null && !"*".equals(ruleMethod) && !".*".equals(ruleMethod)) {
                if (!Pattern.matches(ruleMethod, reqMethod)) {
                    continue;
                }
            }

            // 3. 二次自定义关键字过滤（可选）
            String urlContains = rule.getUrlContains();
            if (urlContains != null && !fullUrl.toLowerCase().contains(urlContains.toLowerCase())) {
                continue;
            }

            logger.info("[Mock] ✅ 命中接口: {} {}", reqMethod, fullUrl);
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

        // 获取 Mock 数据
        String mockBody = null;
        try {
            mockBody = getMockBody(rule, route);
        } catch (Exception e) {
            logger.warn("[Mock] Failed to get mock data for '{}', resuming: {}", rule.getName(), e.getMessage(), e);
            safeResume(route);
            return;
        }

        if (mockBody == null) {
            logger.warn("[Mock] No mock body for rule '{}', resuming original request", rule.getName());
            safeResume(route);
            return;
        }

        // 记录请求信息（包含 mock 响应体）
        recordCall(route, rule.getStatusCode(), mockBody, ApiCallRecord.Type.MOCK);

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
        // 1. 动态生成器
        if (rule.getResponseGenerator() != null) {
            try {
                Request req = route.request();
                Map<String, Object> ctx = new HashMap<>();
                ctx.put("url", req.url());
                ctx.put("method", req.method());
                ctx.put("headers", req.headers());
                try { ctx.put("postData", req.postData() != null ? req.postData() : ""); }
                catch (Exception e) { ctx.put("postData", ""); }
                return rule.getResponseGenerator().generate(req, ctx);
            } catch (Exception e) {
                logger.error("[Mock] ResponseGenerator threw exception for {}: {}", rule.getName(), e.getMessage(), e);
                throw e;
            }
        }
        // 2. 直接 JSON
        if (rule.getMockDataJson() != null && !rule.getMockDataJson().isEmpty()) {
            return rule.getMockDataJson();
        }
        // 3. 文件路径（指定 UTF-8 编码，防止中文乱码）
        if (rule.getMockDataPath() != null && !rule.getMockDataPath().isEmpty()) {
            java.nio.file.Path path = Paths.get(rule.getMockDataPath());
            try {
                if (!Files.exists(path)) {
                    throw new IllegalArgumentException("Mock data file not found: " + rule.getMockDataPath());
                }
                return new String(Files.readAllBytes(path), DEFAULT_CHARSET);
            } catch (IllegalArgumentException e) {
                throw e;  // 重新抛出业务异常
            } catch (Exception e) {
                throw new IllegalStateException("Failed to read mock data file: " + e.getMessage(), e);
            }
        }
        return null;
    }

    /**
     * 延迟 fulfill — 使用 Playwright 原生 route.wait() API
     * route.wait() 在 Playwright 中是官方支持的，不会阻塞浏览器主线程
     * 它只在路由处理的上下文中等待，是安全的
     */
    private void fulfillWithDelay(Route route, Route.FulfillOptions opts, long delayMs, String url) {
        if (delayMs <= 0) {
            safeFulfill(route, opts, url);
            return;
        }

        logger.info("[Mock] Delay {}ms for {}", delayMs, url);
        try {
            // Playwright 原生支持的等待方式，不阻塞浏览器
            route.wait(delayMs);
            safeFulfill(route, opts, url);
        } catch (Exception e) {
            logger.error("[Mock] Delay failed for {}: {}", url, e.getMessage());
            safeResume(route);
        }
    }

    // ==================== 记录与工具方法 ====================

    /**
     * 记录调用（包含完整响应体）
     */
    private void recordCall(Route route, int statusCode, Object responseBody, ApiCallRecord.Type type) {
        try {
            Request req = route.request();
            Object safePostData = null;
            try {
                safePostData = req.postData();
            } catch (Exception e) {
                logger.trace("[Record] Failed to read postData for {}: {}", req.url(), e.getMessage());
            }
            // 使用同步块保护并发写入
            synchronized (apiCallHistory) {
                apiCallHistory.add(new ApiCallRecord(
                        UUID.randomUUID().toString(), req.url(), req.method(), System.currentTimeMillis(),
                        req.headers(), safePostData,
                        statusCode, Collections.emptyMap(), responseBody, type));
                trimApiHistoryIfNeeded();
            }
        } catch (Exception e) {
            logger.warn("[Record] Failed to record call: {}", e.getMessage(), e);
        }
    }

    /**
     * 安全 fulfill（带异常兜底 resume）
     */
    private static void safeFulfill(Route route, Route.FulfillOptions opts, String url) {
        try {
            route.fulfill(opts);
        } catch (Exception e) {
            logger.error("[Route] fulfill failed for {}, falling back to resume: {}", url, e.getMessage(), e);
            // fulfill 失败时，尝试 resume 放行请求
            try {
                route.resume();
            } catch (Exception resumeEx) {
                logger.error("[Route] resume fallback also failed for {}, request may be blocked: {}", url, resumeEx.getMessage());
                try {
                    route.abort("failedhandler");
                } catch (Exception abortEx) {
                    logger.error("[Route] abort() failed for {}, request is definitely blocked: {}", url, abortEx.getMessage());
                }
            }
        }
    }

    /**
     * 安全 resume（带异常记录完整堆栈）
     */
    private static void safeResume(Route route) {
        try {
            route.resume();
        } catch (Exception e) {
            logger.error("[Route] resume() failed, request may be blocked: {}", e.getMessage(), e);
            // 尝试 abort 避免请求永久挂起
            try {
                route.abort("failedhandler");
            } catch (Exception abortEx) {
                logger.error("[Route] abort() also failed, request is definitely blocked: {}", abortEx.getMessage());
            }
        }
    }

    // ==================== URL 工具方法 ====================

    /**
     * 将普通URL转换为 Playwright glob 匹配模式
     * 
     * <p>统一匹配策略（用于 Playwright route() 方法）：
     * - 移除开头斜杠
     * - 前后加 ** 实现跨目录匹配（支持查询参数）
     * - Java/Playwright glob 中 ** 匹配任意路径（包括 /），* 不匹配 /
     * 
     * @param urlPattern 如 "/api/users" 或 "rest/account-list"
     * @return Playwright glob pattern
     */
    static String toGlobPattern(String urlPattern) {
        if (urlPattern == null || urlPattern.isEmpty()) return "**";

        // 已带通配符直接返回
        if (urlPattern.contains("*")) return urlPattern;
        
        String normalized = urlPattern.startsWith("/") ? urlPattern.substring(1) : urlPattern;
        // 后缀不加/**，用**收尾，兼容所有查询参数
        return "**/" + normalized + "**";
    }

    /**
     * 检查 URL 是否匹配 pattern（与 RealApiMonitor 保持一致）
     * 支持两种模式：
     * - 包含匹配：直接使用 contains() 检查 URL 是否包含 pattern
     * - 正则匹配：使用正则表达式匹配
     * 
     * @param url 完整 URL
     * @param pattern 匹配模式（glob 或 regex）
     * @return true 如果匹配
     */
    static boolean matchesGlob(String url, String pattern) {
        if (url == null || pattern == null) return false;
        
        // Glob 模式处理（* 匹配任意字符包括 /）
        if (pattern.contains("*")) {
            // 将 glob * 转换为正则 .* （但 / 不需要特殊处理，因为 glob 中 * 也匹配 /）
            String regex = globToRegex(pattern);
            try {
                return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(url).matches();
            } catch (Exception e) {
                logger.debug("[Pattern] Invalid glob pattern '{}': {}", pattern, e.getMessage());
                return false;
            }
        }
        
        // 纯文本模式：直接 contains 匹配
        if (url.contains(pattern)) {
            return true;
        }
        
        // 尝试正则匹配（大小写不敏感）
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(url).matches();
        } catch (Exception e) {
            logger.debug("[Pattern] Invalid regex pattern '{}': {}", pattern, e.getMessage());
            return false;
        }
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

        /**
         * 使用 endpoint 关键字匹配请求（作为 urlContains 二次过滤）
         * 注意：urlPattern 设为唯一值以支持多次 forEndpoint 调用
         * @param endpoint URL 中需包含的关键字（完整 URL 或部分路径）
         */
        public MockBuilder forEndpoint(String endpoint) {
            // 生成唯一的 glob pattern，避免 registeredPatterns 去重导致后续 forEndpoint 不生效
            // 将 endpoint 转换为合法的 glob pattern（如 "/api/users" -> "**/api/users**"）
            String uniquePattern = toGlobPattern(endpoint);
            MockRule rule = new MockRule("mock-" + endpoint, uniquePattern).endpoint(endpoint);
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
                // 先解绑所有旧路由
                safeUnrouteAll(instance.targetPage != null ? instance.targetPage : instance.targetContext);
                instance.mockRules.clear();
                instance.registeredPatterns.clear();
            }
            for (MockRule r : rules) {
                instance.registerMock(r);
            }
            instance.applyRoutes();
            recordToSerenityReport();
            logger.info("[Mock] Built {} rule(s)", rules.size());
        }

        private MockRule last() {
            if (rules.isEmpty()) throw new IllegalStateException("No rule added yet");
            return rules.get(rules.size() - 1);
        }
    }
}
