package com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import net.serenitybdd.core.Serenity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Real API Monitor - 实时监控API响应（企业级解决方案）
 *
 * 功能：
 * 1. 实时监控API请求和响应
 * 2. 记录API调用历史（包括真实的响应状态码、响应时间等）
 * 3. 实时验证API响应是否符合预期（状态码、响应时间、响应内容等）
 * 4. 支持按URL、方法等条件过滤API调用记录
 * 5. 不修改API请求和响应，只进行监控
 * 6. 支持指定时间后自动停止监控
 * 7. 支持检测到目标API后自动停止监控
 * 8. 支持多种监控模式配置
 * 9. 所有监控结果（成功或失败）都会自动记录到Serenity报告
 *
 * 详细使用方式请参考: RealApiMonitor_Usage.md
 *
 * 简单示例：
 *   RealApiMonitor.with(context)
 *       .monitorApi(".*auth/login.*", 200)
 *       .build();
 *   RealApiMonitor.logApiMonitoringResult();
 */
public class RealApiMonitor {

    private static final Logger logger = LoggerFactory.getLogger(RealApiMonitor.class);

    // 存储所有API调用记录
    private static final List<ApiCallRecord> apiCallHistory = new CopyOnWriteArrayList<>();

    // 存储已注册的监听器（针对BrowserContext）
    private static final Map<BrowserContext, Set<ResponseListener>> contextListeners = new HashMap<>();

    // 标记Context的监听是否已停止（用于Playwright无法移除监听器的情况）
    private static final Map<BrowserContext, Boolean> contextMonitoringStopped = new HashMap<>();

    // 标记Page的监听是否已停止（用于Playwright无法移除监听器的情况）
    private static final Map<Page, Boolean> pageMonitoringStopped = new HashMap<>();

    // 存储API期望（URL模式 -> API期望对象）
    private static final Map<String, ApiExpectation> apiExpectations = new HashMap<>();

    // 存储API监控警告信息（用于在主线程中记录到Serenity报告）
    private static final List<String> apiMonitorWarnings = new CopyOnWriteArrayList<>();

    // 监控失败的AssertionError（后台线程设置，testFinished中检查）
    private static volatile AssertionError monitoringFailure = null;

    // 目标Host过滤（只监控指定host的API）
    private static volatile String targetHost = null;

    // 是否启用实时验证


    // ==================== 自动断言 ====================

    /**
     * 检查并抛出监控失败异常（供testFinished调用）
     */
    public static void checkAndThrowMonitoringFailure() {
        if (monitoringFailure != null) {
            AssertionError error = monitoringFailure;
            monitoringFailure = null;
            logApiMonitoringResult();
            throw error;
        }
    }

    /**
     * 重置监控失败标志
     */
    public static void resetMonitoringFailure() {
        monitoringFailure = null;
    }

    // ==================== 目标Host过滤 ====================

    /**
     * 设置目标Host（只监控指定host的API请求）
     * @param host 目标host（如 "www.example.com" 或 "api.example.com"）
     */
    public static void setTargetHost(String host) {
        targetHost = host;
        logger.info("Target host filter set to: {}", host);
    }

    /**
     * 从URL中提取host
     * @param url 完整URL
     * @return host部分
     */
    public static String extractHost(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            java.net.URL urlObj = new java.net.URL(url);
            return urlObj.getHost();
        } catch (Exception e) {
            // 如果解析失败，尝试简单提取
            int start = url.indexOf("://");
            if (start > 0) {
                String afterProtocol = url.substring(start + 3);
                int end = afterProtocol.indexOf("/");
                return end > 0 ? afterProtocol.substring(0, end) : afterProtocol;
            }
            return null;
        }
    }

    /**
     * 检查URL是否匹配目标host
     * @param url 待检查的URL
     * @return true表示匹配（应该监控），false表示不匹配（应该跳过）
     */
    private static boolean matchesTargetHost(String url) {
        if (targetHost == null || targetHost.isEmpty()) {
            return true; // 未设置目标host，监控所有请求
        }
        String urlHost = extractHost(url);
        return targetHost.equals(urlHost);
    }

    /**
     * 清除目标Host过滤
     */
    public static void clearTargetHost() {
        targetHost = null;
        logger.info("Target host filter cleared");
    }

    // ==================== 简化API（最常用） ====================

    /**
     * 【推荐】使用Builder模式配置API监控
     * @param context Playwright BrowserContext对象
     * @return ApiMonitorBuilder对象，用于链式调用
     */
    public static ApiMonitorBuilder with(BrowserContext context) {
        return new ApiMonitorBuilder(context);
    }

    /**
     * 【推荐】使用Builder模式配置API监控（Page版本）
     * @param page Playwright Page对象
     * @return ApiMonitorBuilder对象，用于链式调用
     */
    public static ApiMonitorBuilder with(Page page) {
        return new ApiMonitorBuilder(page);
    }

    /**
     * 【简化】监控单个API并实时验证
     * @param context Playwright BrowserContext对象
     * @param endpoint API endpoint
     * @param expectedStatusCode 期望的状态码
     */
    public static void monitorAndVerify(BrowserContext context, String endpoint, int expectedStatusCode) {
        logger.info("========== Starting API monitoring with real-time verification ==========");
        logger.info("Monitoring API: {} (Expected Status: {})", endpoint, expectedStatusCode);
        logger.info("Monitoring will stop automatically after detecting the first matching API");
        clearHistory();
        clearApiExpectations();
        expectApiStatus(endpoint, expectedStatusCode);
        monitorApi(context, endpoint);
        // 自动停止监控：检测到第一个匹配的API后停止
        stopMonitoringAfterApi(context, endpoint, 1);
    }

    /**
     * 【简化】监控多个API并实时验证 - 批量设置
     * @param context Playwright BrowserContext对象
     * @param expectations API期望映射（URL模式 -> 期望状态码）
     */
    public static void monitorMultiple(BrowserContext context, Map<String, Integer> expectations) {
        logger.info("========== Starting multiple APIs monitoring with real-time verification ==========");
        logger.info("Monitoring {} APIs with verification", expectations.size());
        // 转换普通URL为正则表达式
        Map<String, Integer> convertedExpectations = new HashMap<>();
        for (Map.Entry<String, Integer> entry : expectations.entrySet()) {
            String pattern = toRegexPattern(entry.getKey());
            convertedExpectations.put(pattern, entry.getValue());
            logger.info("  - API: {} (Expected Status: {})", pattern, entry.getValue());
        }
        clearHistory();
        clearApiExpectations();
        expectMultipleApiStatus(convertedExpectations);
        monitorAllApi(context);
    }

    /**
     * 【灵活】只监控API，不自动验证
     * @param context Playwright BrowserContext对象
     * @param endpoint API endpoint
     */
    public static void startMonitoring(BrowserContext context, String endpoint) {
        logger.info("========== Starting API monitoring (without automatic verification) ==========");
        logger.info("Monitoring API: {}", endpoint);
        clearHistory();
        monitorApi(context, endpoint);
    }

    /**
     * 【灵活】监控所有API响应
     * @param context Playwright BrowserContext对象
     */
    public static void startMonitoringAll(BrowserContext context) {
        logger.info("========== Starting full API monitoring (all APIs) ==========");
        clearHistory();
        monitorAllApi(context);
    }

    /**
     * 【高级】监控单个API并进行多维度实时验证
     * @param context Playwright BrowserContext对象
     * @param expectation API期望对象
     */
    public static void monitorWithExpectation(BrowserContext context, ApiExpectation expectation) {
        logger.info("========== Starting API monitoring with multi-dimension verification ==========");
        logger.info("Monitoring API: {} with expectation: {}", expectation.getEndpoint(), expectation.getDescription());
        clearHistory();
        clearApiExpectations();
        RealApiMonitor.apiExpectations.put(expectation.getEndpoint(), expectation);
        monitorApi(context, expectation.getEndpoint());
        // 自动记录到Serenity报告
        recordMonitoredApiTargets();
    }

    /**
     * 【高级】监控多个API并进行多维度实时验证
     * @param context Playwright BrowserContext对象
     * @param expectations API期望对象列表
     */
    public static void monitorWithExpectations(BrowserContext context, List<ApiExpectation> expectations) {
        logger.info("========== Starting multiple APIs monitoring with multi-dimension verification ==========");
        logger.info("Monitoring {} APIs with verification", expectations.size());
        clearHistory();
        clearApiExpectations();
        for (ApiExpectation expectation : expectations) {
            logger.info("  - {} : {}", expectation.getEndpoint(), expectation.getDescription());
            RealApiMonitor.apiExpectations.put(expectation.getEndpoint(), expectation);
        }
        if (expectations.size() == 1) {
            monitorApi(context, expectations.get(0).getEndpoint());
        } else {
            monitorAllApi(context);
        }
        // 自动记录到Serenity报告
        recordMonitoredApiTargets();
    }

    /**
     * 将普通URL模式转换为正则表达式
     * @param urlPattern URL模式（普通URL或正则表达式）
     * @return 正则表达式模式
     */
    private static String toRegexPattern(String urlPattern) {
        if (urlPattern == null || urlPattern.isEmpty()) {
            return ".*";
        }

        // 检查是否已经是正则表达式（包含常见的正则元字符）
        boolean isRegex = urlPattern.contains(".*") || urlPattern.contains("\\d")
                       || urlPattern.contains("?") || urlPattern.contains("+")
                       || urlPattern.contains("\\w") || urlPattern.contains("\\s");

        if (isRegex) {
            return urlPattern; // 已经是正则表达式，直接返回
        }

        // 如果以 / 开头，去掉开头的 /，然后添加 .* 前后缀
        // 例如：/api/users -> .*api/users.*
        String normalized = urlPattern;
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        return ".*" + normalized + ".*";
    }

    /**
     * 判断URL是否是静态资源
     *
     * 静态资源包括：
     * - JS 文件：*.js
     * - CSS 文件：*.css
     * - 图片：*.png, *.jpg, *.jpeg, *.gif, *.svg, *.ico
     * - 字体：*.woff, *.woff2, *.ttf, *.eot
     * - 其他：*.html, *.map, *.json (部分), *.woff2
     *
     * 注意：
     * - 包含 /api/ 或 /rest/ 的URL不会被识别为静态资源
     * - 动态生成的资源（如带查询参数的）不会被排除
     *
     * @param url URL字符串
     * @return true表示是静态资源，false表示不是静态资源
     */
    private static boolean isStaticResource(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }

        // 转换为小写进行匹配
        String lowerUrl = url.toLowerCase();

        // 如果包含 /api/ 或 /rest/ 或 /services/，则认为不是静态资源
        if (lowerUrl.contains("/api/") || lowerUrl.contains("/rest/") || lowerUrl.contains("/services/")) {
            return false;
        }

        // 静态资源文件扩展名
        String[] staticExtensions = {
            // JavaScript 文件
            ".js",

            // CSS 文件
            ".css",

            // 图片文件
            ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".bmp", ".webp",

            // 字体文件
            ".woff", ".woff2", ".ttf", ".eot", ".otf",

            // 其他静态资源
            ".map", ".html", ".htm"
        };

        // 检查是否以静态资源扩展名结尾
        for (String ext : staticExtensions) {
            if (lowerUrl.endsWith(ext)) {
                return true;
            }
        }

        // 检查是否包含常见的静态资源路径
        String[] staticPaths = {
            "/static/", "/assets/", "/fonts/", "/images/", "/css/", "/js/", "/styles/",
            "/node_modules/", "/vendor/", "/lib/"
        };

        for (String path : staticPaths) {
            if (lowerUrl.contains(path)) {
                return true;
            }
        }

        return false;
    }
    
    /**
     * API调用记录
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
        private final Object responseBody;
        private final boolean isMocked;

        public ApiCallRecord(String requestId, String url, String method, long timestamp,
                           Map<String, String> requestHeaders, Object requestBody,
                           int statusCode, Map<String, String> responseHeaders,
                           Object responseBody, boolean isMocked) {
            this.requestId = requestId;
            this.url = url;
            this.method = method;
            this.timestamp = timestamp;
            this.requestHeaders = requestHeaders;
            this.requestBody = requestBody;
            this.statusCode = statusCode;
            this.responseHeaders = responseHeaders;
            this.responseBody = responseBody;
            this.isMocked = isMocked;
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
        public boolean isMocked() { return isMocked; }
        
        @Override
        public String toString() {
            return String.format("ApiCallRecord{url='%s', method='%s', statusCode=%d}",
                    url, method, statusCode);
        }
    }
    
    /**
     * 响应监听器接口
     */
    @FunctionalInterface
    public interface ResponseListener {
        void onResponse(Response response, Request request);
    }

    // ==================== 数据类（用于返回值） ====================

    /** API监控存储器 */
    public static class ApiMonitorStore {
        private static final Map<String, List<ApiCallRecord>> GLOBAL_STORE = new ConcurrentHashMap<>();
        private final Map<String, List<ApiCallRecord>> localStore = new ConcurrentHashMap<>();
        private final String endpoint;

        public ApiMonitorStore(String endpoint) {
            this.endpoint = endpoint;
        }

        /** 添加记录 */
        void add(ApiCallRecord record) {
            String key = extractEndpoint(record.getUrl());
            localStore.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(record);
            GLOBAL_STORE.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(record);
        }

        /** 获取指定endpoint的所有记录 */
        public List<ApiCallRecord> getByEndpoint(String endpointKey) {
            return localStore.getOrDefault(endpointKey, new ArrayList<>());
        }

        /** 获取指定endpoint的最后一条记录 */
        public ApiCallRecord getLast(String endpointKey) {
            List<ApiCallRecord> list = localStore.get(endpointKey);
            return (list != null && !list.isEmpty()) ? list.get(list.size() - 1) : null;
        }

        /** 获取当前endpoint的最后一条记录 */
        public ApiCallRecord getLast() {
            return getLast(endpoint);
        }

        /** 获取所有记录 */
        public List<ApiCallRecord> getAll() {
            List<ApiCallRecord> all = new ArrayList<>();
            localStore.values().forEach(all::addAll);
            return all;
        }

        /** 获取所有数据Map */
        public Map<String, List<ApiCallRecord>> toMap() {
            return new HashMap<>(localStore);
        }

        /** 清空本地存储 */
        public void clear() {
            localStore.clear();
        }

        /** 清空全局存储 */
        public static void clearGlobal() {
            GLOBAL_STORE.clear();
        }

        /** 获取全局存储 */
        public static Map<String, List<ApiCallRecord>> getGlobalStore() {
            return new HashMap<>(GLOBAL_STORE);
        }

        private String extractEndpoint(String url) {
            if (url == null) return "";
            int queryIndex = url.indexOf('?');
            if (queryIndex > 0) {
                url = url.substring(0, queryIndex);
            }
            int lastSlash = url.lastIndexOf('/');
            return lastSlash >= 0 ? url.substring(lastSlash) : url;
        }
    }

    /** 格式化Headers为JSON字符串 */
    private static String formatHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "{}";
        }
        try {
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            com.google.gson.JsonObject json = new com.google.gson.JsonObject();
            headers.forEach(json::addProperty);
            return gson.toJson(json);
        } catch (Exception e) {
            return headers.toString();
        }
    }
    
    /**
     * 监控特定URL的真实API响应（针对BrowserContext）
     *
     * @param context Playwright BrowserContext对象
     * @param endpoint API endpoint（如 "/api/users"）
     */
    public static void monitorApi(BrowserContext context, String endpoint) {
        monitorApi(context, endpoint, null);
    }

    /**
     * 监控特定URL的真实API响应，并提供自定义监听器（针对BrowserContext）
     *
     * @param context Playwright BrowserContext对象
     * @param endpoint API endpoint（如 "/api/users"）
     * @param listener 响应监听器（可为null）
     */
    public static void monitorApi(BrowserContext context, String endpoint, ResponseListener listener) {
        // 使用 endpoint 匹配
        final String patternOrEndpoint = endpoint;

        logger.info(" Setting up API monitor for: {} (endpoint) on BrowserContext", endpoint);
        // 用于统计响应数量
        final int[] responseCount = {0};

        // 保存监听器引用（先初始化set）
        Set<ResponseListener> listeners = contextListeners.computeIfAbsent(context, k -> new HashSet<>());

        // 重置停止标志位（允许重新开始监控）
        contextMonitoringStopped.put(context, false);

        // 添加响应监听器
        ResponseListener responseListener = (response, request) -> {
            responseCount[0]++;

            // 检查是否匹配目标host
            if (!matchesTargetHost(response.url())) {
                LoggingConfigUtil.logDebugIfVerbose(logger, "🚫 Skipping non-target host: {} (expected: {})",
                    response.url(), targetHost);
                return;
            }

            boolean matches = endpoint.isEmpty() || response.url().contains(endpoint);

            // 如果是静态资源且启用了排除，则跳过
            if (matches && isStaticResource(response.url())) {
                LoggingConfigUtil.logDebugIfVerbose(logger, "🚫 Skipping static resource: {}", response.url());
                return;
            }

            LoggingConfigUtil.logDebugIfVerbose(logger, "🔍 Checking URL: {} matches endpoint: {} = {} (Total responses: {})",
                    response.url(), endpoint, matches, responseCount[0]);

            if (matches) {
                try {
                    String requestId = UUID.randomUUID().toString();
                    Map<String, String> requestHeaders = new HashMap<>(request.headers());
                    Object requestBody = request.postData();

                    Map<String, String> responseHeaders = new HashMap<>(response.headers());
                    Object responseBody = null;

                    // 尝试获取响应体
                    try {
                        responseBody = response.text();
                    } catch (Exception e) {
                        logger.debug("Failed to get response body for: {}", response.url());
                    }

                    ApiCallRecord record = new ApiCallRecord(
                            requestId, response.url(), request.method(), System.currentTimeMillis(),
                            requestHeaders, requestBody, response.status(), responseHeaders,
                            responseBody, false
                    );

                    apiCallHistory.add(record);
                    notifyStores(record);
                    logger.info("========================================");
                    logger.info("[API RESPONSE] {}", response.url());
                    logger.info("========================================");
                    // Request 信息
                    logger.info("[Request Info]");
                    logger.info("   URL: {}", request.url());
                    logger.info("   Method: {}", request.method());
                    logger.info("   Headers: {}", formatHeaders(requestHeaders));
                    if (requestBody != null) {
                        logger.info("   Body: {}", requestBody);
                    }
                    // Response 信息
                    logger.info("[Response Info]");
                    logger.info("   Status: {} {}", response.status(), response.statusText());
                    logger.info("   Headers: {}", formatHeaders(responseHeaders));
                    logger.info("   Body: {}", responseBody != null ? responseBody : "[Cannot read body]");
                    logger.info("========================================");
                    logger.info(" Recorded API call: {} {} - Status: {}",
                            request.method(), response.url(), response.status());

                    // 实时验证：立即检查API响应
                    validateRealTimeApi(record);

                } catch (Exception e) {
                    logger.error("Failed to record API call", e);
                }
            }
        };

        // 添加监听器到set
        listeners.add(responseListener);
        if (listener != null) {
            listeners.add(listener);
        }

        logger.info(" Registering onResponse listener on BrowserContext, listeners for this context: {}", listeners.size());

        // 使用局部变量避免闭包问题
        final Set<ResponseListener> currentListeners = listeners;

        context.onResponse(response -> {
            // 检查是否有超时失败（超时未捕获API）- 立即抛出
            if (monitoringFailure != null) {
                String errorMsg = monitoringFailure.getMessage();
                monitoringFailure = null;
                // 在主线程重新创建异常，这样堆栈跟踪会指向正确的测试代码位置
                throw new AssertionError(errorMsg);
            }

            // 检查监控是否已停止
            if (contextMonitoringStopped.getOrDefault(context, false)) {
                return;
            }

            LoggingConfigUtil.logDebugIfVerbose(logger, " onResponse event fired! URL: {}, Status: {}", response.url(), response.status());

            // 调用内部监听器
            for (ResponseListener rl : currentListeners) {
                try {
                    rl.onResponse(response, response.request());
                } catch (AssertionError e) {
                    // AssertionError 直接传播，让测试立即失败
                    throw e;
                } catch (Exception e) {
                    logger.error("Error executing response listener", e);
                }
            }
        });

        logger.info(" API monitoring started successfully for endpoint: {} on BrowserContext", endpoint);
    }

    /**
     * 监控所有API响应（1分钟无新API调用自动停止）
     * 注意：此方法立即返回，需要手动调用stopMonitoring或在tearDown中停止
     *
     * @param context Playwright BrowserContext对象
     */
    public static void monitorAllApi(BrowserContext context) {
        monitorApi(context, "");
        startAutoStopTimer(context, 60);
    }

    /**
     * 监控所有API响应，指定超时时间
     * 注意：此方法立即返回，需要手动调用stopMonitoring或在tearDown中停止
     *
     * @param context Playwright BrowserContext对象
     * @param timeoutSeconds 无新API调用后自动停止的秒数
     */
    public static void monitorAllApi(BrowserContext context, int timeoutSeconds) {
        monitorApi(context, "");
        startAutoStopTimer(context, timeoutSeconds);
    }

    /**
     * 启动自动停止定时器（无新API调用后自动停止监控）
     *
     * @param context Playwright BrowserContext对象
     * @param timeoutSeconds 超时秒数
     */
    private static void startAutoStopTimer(BrowserContext context, int timeoutSeconds) {
        Thread autoStopThread = new Thread(() -> {
            int lastHistorySize = apiCallHistory.size();
            long lastUpdateTime = System.currentTimeMillis();

            while (!contextMonitoringStopped.getOrDefault(context, false)) {
                try {
                    Thread.sleep(1000); // 每秒检查一次

                    int currentSize = apiCallHistory.size();
                    if (currentSize != lastHistorySize) {
                        // 有新的API调用，更新时间和计数
                        lastHistorySize = currentSize;
                        lastUpdateTime = System.currentTimeMillis();
                        logger.debug("API activity detected, resetting auto-stop timer. Total APIs: {}", currentSize);
                    } else {
                        // 没有新的API调用，检查是否超时
                        long elapsed = (System.currentTimeMillis() - lastUpdateTime) / 1000;
                        if (elapsed >= timeoutSeconds) {
                            logger.info("No API activity for {} seconds, auto-stopping monitoring. Total APIs captured: {}", 
                                timeoutSeconds, currentSize);
                            stopMonitoring(context);
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    logger.debug("Auto-stop timer interrupted");
                    break;
                }
            }
            logger.debug("Auto-stop timer thread ended");
        }, "ApiMonitor-AutoStop-" + context.hashCode());
        
        autoStopThread.setDaemon(true);
        autoStopThread.start();
        logger.info("Auto-stop timer started: will stop monitoring after {} seconds of inactivity", timeoutSeconds);
    }

    /**
     * 监控特定URL的真实API响应（针对Page）
     *
     * @param page Playwright Page对象
     * @param endpoint API endpoint（如 "/api/users"）
     */
    public static void monitorApi(Page page, String endpoint) {
        monitorApi(page, endpoint, null);
    }

    /**
     * 监控特定URL的真实API响应（针对Page），并提供自定义监听器
     *
     * @param page Playwright Page对象
     * @param endpoint API endpoint（如 "/api/users"）
     * @param listener 响应监听器（可为null）
     */
    public static void monitorApi(Page page, String endpoint, ResponseListener listener) {
        logger.info(" Setting up API monitor for endpoint: {} on Page", endpoint);
        // 用于统计响应数量
        final int[] responseCount = {0};

        // 重置停止标志位（允许重新开始监控）
        pageMonitoringStopped.put(page, false);

        // 添加响应监听器
        ResponseListener responseListener = (response, request) -> {
            responseCount[0]++;

            // 检查是否匹配目标host
            if (!matchesTargetHost(response.url())) {
                LoggingConfigUtil.logDebugIfVerbose(logger, "🚫 Skipping non-target host: {} (expected: {})",
                    response.url(), targetHost);
                return;
            }

            boolean matches = endpoint.isEmpty() || response.url().contains(endpoint);

            // 如果是静态资源且启用了排除，则跳过
            if (matches && isStaticResource(response.url())) {
                LoggingConfigUtil.logDebugIfVerbose(logger, "🚫 Skipping static resource: {}", response.url());
                return;
            }

            LoggingConfigUtil.logDebugIfVerbose(logger, "🔍 Checking URL: {} matches endpoint: {} = {} (Total responses: {})",
                    response.url(), endpoint, matches, responseCount[0]);

            if (matches) {
                try {
                    String requestId = UUID.randomUUID().toString();
                    Map<String, String> requestHeaders = new HashMap<>(request.headers());
                    Object requestBody = request.postData();

                    Map<String, String> responseHeaders = new HashMap<>(response.headers());
                    Object responseBody = null;

                    // 尝试获取响应体
                    try {
                        responseBody = response.text();
                    } catch (Exception e) {
                        logger.debug("Failed to get response body for: {}", response.url());
                    }

                    ApiCallRecord record = new ApiCallRecord(
                            requestId, response.url(), request.method(), System.currentTimeMillis(),
                            requestHeaders, requestBody, response.status(), responseHeaders,
                            responseBody, false
                    );

                    apiCallHistory.add(record);
                    notifyStores(record);
                    logger.info("========================================");
                    logger.info("[API RESPONSE] {}", response.url());
                    logger.info("========================================");
                    // Request 信息
                    logger.info("[Request Info]");
                    logger.info("   URL: {}", request.url());
                    logger.info("   Method: {}", request.method());
                    logger.info("   Headers: {}", formatHeaders(requestHeaders));
                    if (requestBody != null) {
                        logger.info("   Body: {}", requestBody);
                    }
                    // Response 信息
                    logger.info("[Response Info]");
                    logger.info("   Status: {} {}", response.status(), response.statusText());
                    logger.info("   Headers: {}", formatHeaders(responseHeaders));
                    logger.info("   Body: {}", responseBody != null ? responseBody : "[Cannot read body]");
                    logger.info("========================================");
                    logger.info(" Recorded API call: {} {} - Status: {}",
                            request.method(), response.url(), response.status());

                    // 实时验证：立即检查API响应
                    validateRealTimeApi(record);

                } catch (Exception e) {
                    logger.error("Failed to record API call", e);
                }
            }
        };

        page.onResponse(response -> {
            // 检查是否有超时失败（超时未捕获API）- 立即抛出
            if (monitoringFailure != null) {
                String errorMsg = monitoringFailure.getMessage();
                monitoringFailure = null;
                // 在主线程重新创建异常，这样堆栈跟踪会指向正确的测试代码位置
                throw new AssertionError(errorMsg);
            }

            // 检查监控是否已停止
            if (pageMonitoringStopped.getOrDefault(page, false)) {
                return;
            }

            LoggingConfigUtil.logDebugIfVerbose(logger, " onResponse event fired! URL: {}, Status: {}", response.url(), response.status());
            // 使用Playwright API获取真实的响应时间
            LoggingConfigUtil.logDebugIfVerbose(logger, " onResponse event fired! URL: {}, Status: {}", response.url(), response.status());

            // 调用监听器
            if (listener != null) {
                try {
                    listener.onResponse(response, response.request());
                } catch (AssertionError e) {
                    // AssertionError 直接传播，让测试立即失败
                    throw e;
                } catch (Exception e) {
                    logger.error("Error executing response listener", e);
                }
            }
        });

        logger.info(" API monitoring started successfully for endpoint: {} on Page", endpoint);
    }

    /**
     * 监控所有API响应（针对Page，1分钟无新API调用自动停止）
     * 注意：此方法立即返回，需要手动调用stopMonitoring或在tearDown中停止
     *
     * @param page Playwright Page对象
     */
    public static void monitorAllApi(Page page) {
        monitorApi(page, "");
        startAutoStopTimerForPage(page, 60);
    }

    /**
     * 监控所有API响应（针对Page，指定超时时间）
     * 注意：此方法立即返回，需要手动调用stopMonitoring或在tearDown中停止
     *
     * @param page Playwright Page对象
     * @param timeoutSeconds 无新API调用后自动停止的秒数
     */
    public static void monitorAllApi(Page page, int timeoutSeconds) {
        monitorApi(page, "");
        startAutoStopTimerForPage(page, timeoutSeconds);
    }

    /**
     * 启动自动停止定时器（针对Page，无新API调用后自动停止监控）
     *
     * @param page Playwright Page对象
     * @param timeoutSeconds 超时秒数
     */
    private static void startAutoStopTimerForPage(Page page, int timeoutSeconds) {
        Thread autoStopThread = new Thread(() -> {
            int lastHistorySize = apiCallHistory.size();
            long lastUpdateTime = System.currentTimeMillis();

            while (!pageMonitoringStopped.getOrDefault(page, false)) {
                try {
                    Thread.sleep(1000); // 每秒检查一次

                    int currentSize = apiCallHistory.size();
                    if (currentSize != lastHistorySize) {
                        // 有新的API调用，更新时间和计数
                        lastHistorySize = currentSize;
                        lastUpdateTime = System.currentTimeMillis();
                        logger.debug("API activity detected, resetting auto-stop timer. Total APIs: {}", currentSize);
                    } else {
                        // 没有新的API调用，检查是否超时
                        long elapsed = (System.currentTimeMillis() - lastUpdateTime) / 1000;
                        if (elapsed >= timeoutSeconds) {
                            logger.info("No API activity for {} seconds, auto-stopping monitoring. Total APIs captured: {}", 
                                timeoutSeconds, currentSize);
                            stopMonitoring(page);
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    logger.debug("Auto-stop timer interrupted");
                    break;
                }
            }
            logger.debug("Auto-stop timer thread ended");
        }, "ApiMonitor-AutoStop-Page-" + page.hashCode());
        
        autoStopThread.setDaemon(true);
        autoStopThread.start();
        logger.info("Auto-stop timer started: will stop monitoring after {} seconds of inactivity", timeoutSeconds);
    }
    
    /**
     * 获取所有API调用记录
     * 
     * @return API调用历史记录列表
     */
    public static List<ApiCallRecord> getApiHistory() {
        return Collections.unmodifiableList(apiCallHistory);
    }
    
    /**
     * 获取特定URL的API调用记录
     * 
     * @param endpoint API endpoint（如 "/api/users"）
     * @return 匹配的API调用记录列表
     */
    public static List<ApiCallRecord> getApiHistoryByUrl(String endpoint) {
        return apiCallHistory.stream()
                .filter(record -> record.getUrl().contains(endpoint))
                .collect(Collectors.toList());
    }
    
    /**
     * 获取特定HTTP方法的API调用记录
     * 
     * @param method HTTP方法（GET、POST等）
     * @return 匹配的API调用记录列表
     */
    public static List<ApiCallRecord> getApiHistoryByMethod(String method) {
        return apiCallHistory.stream()
                .filter(record -> record.getMethod().equalsIgnoreCase(method))
                .collect(Collectors.toList());
    }
    
    /**
     * 获取特定状态码的API调用记录
     * 
     * @param statusCode HTTP状态码
     * @return 匹配的API调用记录列表
     */
    public static List<ApiCallRecord> getApiHistoryByStatusCode(int statusCode) {
        return apiCallHistory.stream()
                .filter(record -> record.getStatusCode() == statusCode)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取最后一次API调用记录
     * 
     * @return 最后一次API调用记录，如果没有则返回null
     */
    public static ApiCallRecord getLastApiCall() {
        if (apiCallHistory.isEmpty()) {
            return null;
        }
        return apiCallHistory.get(apiCallHistory.size() - 1);
    }
    
    /**
     * 获取特定URL的最后一次API调用记录
     *
     * @param endpoint API endpoint（如 "/api/users"）
     * @return 最后一次匹配的API调用记录，如果没有则返回null
     */
    public static ApiCallRecord getLastApiCallByUrl(String endpoint) {
        List<ApiCallRecord> calls = getApiHistoryByUrl(endpoint);
        if (calls.isEmpty()) {
            return null;
        }
        return calls.get(calls.size() - 1);
    }


    /**
     * 清除所有API调用记录
     */
    public static void clearHistory() {
        apiCallHistory.clear();
        apiMonitorWarnings.clear();
        logger.info("API call history and warnings cleared");
    }

    /**
     * 停止监控并清理监听器（停止指定Context的所有监控）
     *
     * @param context Playwright BrowserContext对象
     */
    public static void stopMonitoring(BrowserContext context) {
        contextListeners.remove(context);
        contextMonitoringStopped.put(context, true);
        logger.info("Stopped monitoring and removed listeners for context");
    }

    /**
     * 停止Page的监控
     *
     * @param page Playwright Page对象
     */
    public static void stopMonitoring(Page page) {
        pageMonitoringStopped.put(page, true);
        logger.info("Stopped monitoring for page");
    }

    /**
     * 在指定秒数后停止监控
     * @param context Playwright BrowserContext对象
     * @param seconds 秒数
     */
    public static void stopMonitoringAfterSeconds(BrowserContext context, int seconds) {
        stopMonitoringAfterSeconds(context, seconds, null);
    }

    /**
     * 在指定秒数后停止监控，并验证是否捕获到目标API
     * @param context Playwright BrowserContext对象
     * @param seconds 秒数
     * @param endpoint 目标API的URL模式，如果为null则只按时间停止
     */
    public static void stopMonitoringAfterSeconds(BrowserContext context, int seconds, String endpoint) {
        if (endpoint != null) {
            logger.info("Scheduled to stop monitoring after {} seconds (must capture API matching: {})", seconds, endpoint);

            new Thread(() -> {
                try {
                    Thread.sleep(seconds * 1000L);

                    // 停止监控
                    stopMonitoring(context);

                    // 检查是否捕获到目标API
                    boolean found = apiCallHistory.stream()
                            .anyMatch(record -> record.getUrl().contains(endpoint));

                    if (found) {
                        logger.info(" Time's up! Found target API matching: {}", endpoint);
                        // 记录到Serenity报告：成功捕获API
                        String successMsg = String.format(
                            " API Monitoring SUCCESS<br>" +
                            "Target API captured within %d seconds<br>" +
                            "Expected endpoint: %s<br>" +
                            "Total APIs captured: %d",
                            seconds, endpoint, apiCallHistory.size()
                        );
                        apiMonitorWarnings.add(successMsg);
                        logger.info(successMsg.replace("<br>", "\n"));
                        // 注意：不在后台线程中记录到Serenity报告
                    } else {
                        String warningMsg = String.format(
                            " API Monitoring FAILED<br>" +
                            "Target API not detected within %d seconds!<br>" +
                            "Expected endpoint: %s<br>" +
                            "Total APIs captured: %d",
                            seconds, endpoint, apiCallHistory.size()
                        );
                        logger.warn(" API Monitor Warning: {}", warningMsg.replace("<br>", "\n"));
                        apiMonitorWarnings.add(warningMsg);
                        // 注意：不在后台线程中记录到Serenity报告
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Stop monitoring interrupted", e);
                } catch (Exception e) {
                    logger.error("Error stopping monitoring", e);
                }
            }, "StopMonitor-Timer").start();
        } else {
            logger.info("Scheduled to stop monitoring after {} seconds", seconds);

            // 记录监控开始时的API数量和期望的API模式
            final int initialApiCount = apiCallHistory.size();
            final List<String> expectationPatterns = new ArrayList<>(apiExpectations.keySet());

            new Thread(() -> {
                try {
                    Thread.sleep(seconds * 1000L);
                    logger.info(" Time's up! Stopping monitoring after {} seconds...", seconds);

                    // 停止监控
                    stopMonitoring(context);

                        // 如果有配置的期望API，检查是否捕获到
                    if (!expectationPatterns.isEmpty()) {
                        boolean foundTargetApi = false;
                        for (String pattern : expectationPatterns) {
                            for (int i = initialApiCount; i < apiCallHistory.size(); i++) {
                                ApiCallRecord record = apiCallHistory.get(i);
                                if (record.getUrl().contains(pattern)) {
                                    foundTargetApi = true;
                                    logger.info(" Found expected API matching: {}", pattern);
                                    break;
                                }
                            }
                            if (foundTargetApi) break;
                        }

                        if (foundTargetApi) {
                            // 记录到Serenity报告：成功捕获API
                            String successMsg = String.format(
                                " API Monitoring SUCCESS<br>" +
                                "Expected APIs captured within %d seconds<br>" +
                                "Expected endpoints: %s<br>" +
                                "Initial API count: %d<br>" +
                                "Final API count: %d<br>" +
                                "New APIs captured: %d",
                                seconds, expectationPatterns, initialApiCount, apiCallHistory.size(),
                                apiCallHistory.size() - initialApiCount
                            );
                            apiMonitorWarnings.add(successMsg);
                            logger.info(successMsg.replace("<br>", "\n"));
                            // 注意：不在后台线程中记录到Serenity报告
                        } else {
                            // 记录到Serenity报告：未捕获到API
                            String warningMsg = String.format(
                                " API Monitoring FAILED<br>" +
                                "No expected API captured within %d seconds!<br>" +
                                "Expected patterns: %s<br>" +
                                "Initial API count: %d<br>" +
                                "Final API count: %d<br>" +
                                "New APIs captured: %d",
                                seconds, expectationPatterns, initialApiCount, apiCallHistory.size(),
                                apiCallHistory.size() - initialApiCount
                            );
                            logger.warn(" API Monitor Warning: {}", warningMsg.replace("<br>", "\n"));
                            apiMonitorWarnings.add(warningMsg);
                            // 注意：不在后台线程中记录到Serenity报告
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Stop monitoring interrupted", e);
                } catch (Exception e) {
                    logger.error("Error stopping monitoring", e);
                }
            }, "StopMonitor-Timer").start();
        }
    }

    /**
     * 检测到指定API后停止监控
     * @param context Playwright BrowserContext对象
     * @param endpoint API endpoint
     * @param expectedCount 期望检测到的API调用次数
     */
    public static void stopMonitoringAfterApi(BrowserContext context, String endpoint, int expectedCount) {
        stopMonitoringAfterApi(context, endpoint, expectedCount, 0);
    }

    /**
     * 检测到指定API后停止监控，支持超时验证
     * @param context Playwright BrowserContext对象
     * @param endpoint API endpoint
     * @param expectedCount 期望检测到的API调用次数
     * @param timeoutSeconds 超时秒数（0表示不设置超时）
     */
    public static void stopMonitoringAfterApi(BrowserContext context, String endpoint, int expectedCount, int timeoutSeconds) {
        logger.info("Will stop monitoring after detecting {} API(s) matching endpoint: {}", expectedCount, endpoint);
        if (timeoutSeconds > 0) {
            logger.info("Timeout set: {} seconds. Will throw error if API not detected within timeout.", timeoutSeconds);
        }

        // 记录该模式的初始调用次数
        final int[] initialCount = {0};
        final boolean[] shouldStop = {false};
        final boolean[] detectedWithinTimeout = {false};

        // 添加一个新的监听器来检测目标API
        ResponseListener stopListener = new ResponseListener() {
            private volatile int detectedCount = 0;

            @Override
            public void onResponse(Response response, Request request) {
                if (shouldStop[0]) {
                    return;
                }

                boolean matches = response.url().contains(endpoint);
                if (matches) {
                    synchronized (initialCount) {
                        detectedCount++;
                        initialCount[0]++;
                        logger.info(" Detected target API #{}: {} - Status: {}",
                                detectedCount, response.url(), response.status());

                        if (detectedCount >= expectedCount) {
                            shouldStop[0] = true;
                            detectedWithinTimeout[0] = true;
                            logger.info(" Target API detected {} times, stopping monitoring...", detectedCount);
                            stopMonitoring(context);
                        }
                    }
                }
            }
        };

        // 添加检测监听器
        Set<ResponseListener> listeners = contextListeners.computeIfAbsent(context, k -> new HashSet<>());
        listeners.add(stopListener);

        // 如果设置了超时，启动定时器
        if (timeoutSeconds > 0) {
            new Thread(() -> {
                try {
                    Thread.sleep(timeoutSeconds * 1000L);

                    // 停止监控
                    if (!shouldStop[0]) {
                        stopMonitoring(context);
                    }

                    // 检查是否在超时内检测到API
                    if (!detectedWithinTimeout[0]) {
                        shouldStop[0] = true;
                        // 记录到Serenity报告：超时未检测到API
                        String warningMsg = String.format(
                            " API Monitoring FAILED<br>" +
                            "Target API not detected within %d seconds!<br>" +
                            "Expected endpoint: %s<br>" +
                            "Expected count: %d<br>" +
                            "Total APIs captured: %d",
                            timeoutSeconds, endpoint, expectedCount, apiCallHistory.size()
                        );
                        logger.warn(" API Monitor Warning: {}", warningMsg.replace("<br>", "\n"));
                        apiMonitorWarnings.add(warningMsg);
                        
                        // 设置失败异常，主线程下一个操作时会自动检查并抛出
                        String errorMsg = String.format(
                            "API Monitoring Failed!\n" +
                            "Target API not detected within %d seconds.\n" +
                            "Expected pattern: %s\n" +
                            "Expected count: %d",
                            timeoutSeconds, endpoint, expectedCount
                        );
                        monitoringFailure = new AssertionError(errorMsg);
                    } else {
                        // 记录到Serenity报告：成功检测到API
                        String successMsg = String.format(
                            " API Monitoring SUCCESS<br>" +
                            "Target API detected within %d seconds<br>" +
                            "Expected pattern: %s<br>" +
                            "Expected count: %d<br>" +
                            "Total APIs captured: %d",
                            timeoutSeconds, endpoint, expectedCount, apiCallHistory.size()
                        );
                        apiMonitorWarnings.add(successMsg);
                        logger.info(successMsg.replace("<br>", "\n"));
                        // 注意：不在后台线程中记录到Serenity报告
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.debug("Timeout check interrupted");
                }
            }, "API-Timeout-Checker").start();
        }
    }

    /**
     * 停止所有监控（企业级功能）
     */
    public static void stopAllMonitoring() {
        logger.info("========== Stopping all monitoring ==========");
        
        // 停止所有context的监听
        for (BrowserContext ctx : new ArrayList<>(contextListeners.keySet())) {
            stopMonitoring(ctx);
        }

        // 清空所有监听器映射
        contextListeners.clear();
        logger.info(" All monitoring stopped");
    }
    
    /**
     * 打印所有捕获到的API（仅用于调试）
     */
    private static void printAllCapturedApis() {
        logger.info("========== All Captured APIs ==========");
        logger.info("Total APIs captured: {}", apiCallHistory.size());

        if (apiCallHistory.isEmpty()) {
            logger.info("No API calls captured.");
        } else {
            for (int i = 0; i < apiCallHistory.size(); i++) {
                ApiCallRecord record = apiCallHistory.get(i);
                logger.info("#{} [{}] {} - Status: {}",
                        i + 1, record.getMethod(), record.getUrl(), record.getStatusCode());
            }
        }
        logger.info("========================================");
    }

    /**
     * 【推荐】记录API监控结果到Serenity报告
     * 建议在测试步骤结束时调用此方法
     */
    public static void logApiMonitoringResult() {
        logApiMonitoringResult(false);
    }

    /**
     * 【推荐】断言API监控结果，如果失败则抛出异常
     * 适用于测试exception场景
     * @throws AssertionError 如果API监控失败
     */
    public static void assertThatApiMonitoring() {
        logApiMonitoringResult(true);
    }

    /**
     * 记录API监控结果到Serenity报告（内部方法）
     * 
     * @param throwOnFailure 如果为true，当API监控失败时抛出AssertionError
     */
    private static void logApiMonitoringResult(boolean throwOnFailure) {
        logger.info("========== API Monitoring Result ==========");

        if (apiExpectations.isEmpty()) {
            logger.info("No API expectations configured, skipping validation");
            return;
        }

        // 检查是否捕获到了期望的API
        boolean foundExpected = false;
        for (String pattern : apiExpectations.keySet()) {
            Pattern regex = Pattern.compile(pattern);
            if (apiCallHistory.stream().anyMatch(record -> regex.matcher(record.getUrl()).find())) {
                foundExpected = true;
                logger.info(" Found expected API matching pattern: {}", pattern);
                break;
            }
        }

        // 生成结果消息
        String resultMsg;
        if (foundExpected) {
            // 成功捕获期望API
            resultMsg = String.format(
                " API Monitoring SUCCESS<br>" +
                "Expected APIs were captured<br>" +
                "Expected patterns: %s<br>" +
                "Total APIs captured: %d",
                apiExpectations.keySet(), apiCallHistory.size()
            );
            apiMonitorWarnings.add(resultMsg);
            logger.info(" API Monitoring SUCCESS - Expected APIs captured");
        } else {
            // 未捕获期望API - 检查是否已有失败消息（避免重复记录）
            boolean alreadyHasFailure = apiMonitorWarnings.stream()
                    .anyMatch(w -> w.contains(" API Monitoring FAILED"));
            
            if (!alreadyHasFailure) {
                resultMsg = String.format(
                    " API Monitoring FAILED<br>" +
                    "No expected API captured<br>" +
                    "Expected patterns: %s<br>" +
                    "Total APIs captured: %d",
                    apiExpectations.keySet(), apiCallHistory.size()
                );
                logger.warn(" API Monitor Warning: {}", resultMsg.replace("<br>", "\n"));
                apiMonitorWarnings.add(resultMsg);
            }
        }

        logger.info("==============================================");

        // 记录到Serenity报告
        recordApiMonitorWarnings();

        // 如果需要抛出异常且API监控失败
        if (throwOnFailure && !foundExpected) {
            String errorMsg = String.format(
                "API Monitoring Assertion Failed!%n" +
                "Expected API(s) were not captured within the specified time.%n" +
                "Expected patterns: %s%n" +
                "Total APIs captured: %d%n" +
                "Captured APIs: %s",
                apiExpectations.keySet(),
                apiCallHistory.size(),
                apiCallHistory.stream()
                    .map(r -> r.getMethod() + " " + r.getUrl())
                    .collect(Collectors.toList())
            );
            assertThat(errorMsg, foundExpected, equalTo(true));
        }
    }

    /**
     * 打印API调用历史摘要
     */
    private static void printApiHistorySummary() {
        logger.info("=== API Call History Summary ===");
        logger.info("Total API calls: {}", apiCallHistory.size());
        
        // 按URL分组统计
        Map<String, Long> urlCount = apiCallHistory.stream()
                .collect(Collectors.groupingBy(
                        record -> record.getUrl(),
                        Collectors.counting()
                ));
        
        // 按状态码分组统计
        Map<Integer, Long> statusCount = apiCallHistory.stream()
                .collect(Collectors.groupingBy(
                        ApiCallRecord::getStatusCode,
                        Collectors.counting()
                ));
        
        logger.info("Calls by URL:");
        urlCount.forEach((url, count) -> 
                logger.info("  {} - {} calls", url, count));
        
        logger.info("Calls by status code:");
        statusCount.forEach((status, count) ->
                logger.info("  {} - {} calls", status, count));
    }

    // ==================== 实时API验证功能 ====================

    /**
     * 设置API期望状态码（简单版本）
     * API响应时会自动验证状态码
     *
     * @param endpoint API endpoint（如 "/api/users"）
     * @param expectedStatusCode 期望的状态码
     */
    public static void expectApiStatus(String endpoint, int expectedStatusCode) {
        apiExpectations.put(endpoint, ApiExpectation.forEndpoint(endpoint).statusCode(expectedStatusCode));
        logger.info("Added API expectation: {} -> {}", endpoint, expectedStatusCode);
    }

    /**
     * 批量设置API期望状态码（简单版本）
     *
     * @param expectations endpoint -> 期望状态码的映射
     */
    public static void expectMultipleApiStatus(Map<String, Integer> expectations) {
        for (Map.Entry<String, Integer> entry : expectations.entrySet()) {
            apiExpectations.put(entry.getKey(), ApiExpectation.forEndpoint(entry.getKey()).statusCode(entry.getValue()));
        }
        logger.info("Added {} API expectations", expectations.size());
    }

    /**
     * 设置API期望（高级版本，支持多维度验证）
     *
     * @param expectation API期望对象
     */
    public static void expectApi(ApiExpectation expectation) {
        apiExpectations.put(expectation.getEndpoint(), expectation);
        logger.info("Added API expectation: {} -> {}", expectation.getEndpoint(), expectation.getDescription());
    }

    /**
     * 清除所有API期望
     */
    public static void clearApiExpectations() {
        apiExpectations.clear();
        logger.info("Cleared all API expectations");
    }

    /**
     * 实时验证API响应
     * 当API响应时，检查是否有匹配的期望，如果有则验证
     *
     * @param record API调用记录
     */
    private static void validateRealTimeApi(ApiCallRecord record) {
        if (apiExpectations.isEmpty()) {
            return; // 没有设置期望，跳过验证
        }

        // 检查是否有匹配的期望
        for (Map.Entry<String, ApiExpectation> entry : apiExpectations.entrySet()) {
            String endpoint = entry.getKey();
            ApiExpectation expectation = entry.getValue();

            // 检查URL是否匹配 endpoint
            if (record.getUrl().contains(endpoint)) {
                // 找到匹配的期望，进行多维度验证
                expectation.validate(record);
                // 找到匹配后立即返回
                return;
            }
        }
    }
    
    /**
     * 获取所有已设置的API期望
     *
     * @return API期望映射
     */
    public static Map<String, ApiExpectation> getApiExpectations() {
        return new HashMap<>(apiExpectations);
    }

    // ==================== Serenity报告集成方法 ====================

    /**
     * 在Serenity报告中记录监控的API目标信息
     * 这些信息会自动显示在测试报告中
     */
    private static void recordMonitoredApiTargets() {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"title\": \"API Monitor Configuration\",\n");
            json.append("  \"totalTargetApis\": ").append(apiExpectations.isEmpty() ? 0 : apiExpectations.size()).append(",\n");

            if (apiExpectations.isEmpty()) {
                json.append("  \"monitoringMode\": \"All APIs (no specific targets)\"\n");
            } else {
                json.append("  \"targets\": [\n");
                int index = 1;
                for (Map.Entry<String, ApiExpectation> entry : apiExpectations.entrySet()) {
                    ApiExpectation expectation = entry.getValue();
                    json.append("    {\n");
                    json.append("      \"#\": ").append(index++).append(",\n");
                    json.append("      \"endpoint\": \"").append(escapeJson(expectation.getEndpoint())).append("\",\n");
                    json.append("      \"expectedStatusCode\": ").append(expectation.expectedStatusCode != null ? expectation.expectedStatusCode : "\"Any\"").append(",\n");
                    json.append("      \"responseValidation\": \"").append(escapeJson(expectation.getDescription())).append("\"\n");
                    json.append("    }").append(index <= apiExpectations.size() ? "," : "").append("\n");
                }
                json.append("  ]\n");
            }
            json.append("}\n");

            Serenity.recordReportData().withTitle("API Monitor Configuration").andContents(json.toString());
            logger.info(" Recorded API monitoring configuration to Serenity report");
        } catch (Exception e) {
            logger.warn("Failed to record API targets to Serenity report", e);
        }
    }

    /**
     * 转义JSON特殊字符
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 右填充字符串到指定长度
     */
    private static String padRight(String s, int length) {
        if (s == null) s = "";
        if (s.length() >= length) return s.substring(0, length - 3) + "...";
        return String.format("%-" + length + "s", s);
    }

    /**
     * 在Serenity报告中记录API监控警告
     */
    private static void recordApiMonitorWarnings() {
        try {
            // 统计成功和失败数量
            long successCount = apiMonitorWarnings.stream().filter(w -> w.contains(" API Monitoring SUCCESS")).count();
            long failCount = apiMonitorWarnings.stream().filter(w -> w.contains(" API Monitoring FAILED")).count();
            int totalApiCalls = apiCallHistory.size();
            int expectedApiCount = apiExpectations.size();

            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"title\": \"API Monitor Results\",\n");
            json.append("  \"summary\": {\n");
            json.append("    \"expectedApis\": ").append(expectedApiCount).append(",\n");
            json.append("    \"totalApiCalls\": ").append(totalApiCalls).append(",\n");
            json.append("    \"successCount\": ").append(successCount).append(",\n");
            json.append("    \"failCount\": ").append(failCount).append("\n");
            json.append("  },\n");

            // 监控结果详情
            json.append("  \"monitoringResults\": [\n");
            for (int i = 0; i < apiMonitorWarnings.size(); i++) {
                String msg = apiMonitorWarnings.get(i);
                boolean isSuccess = msg.contains(" API Monitoring SUCCESS");
                boolean isFailure = msg.contains(" API Monitoring FAILED");
                String status = isSuccess ? "SUCCESS" : (isFailure ? "FAILED" : "WARNING");

                json.append("    {\n");
                json.append("      \"#\": ").append(i + 1).append(",\n");
                json.append("      \"status\": \"").append(status).append("\",\n");
                json.append("      \"message\": \"").append(escapeJson(msg.replace("<br>", " | "))).append("\"\n");
                json.append("    }").append(i < apiMonitorWarnings.size() - 1 ? "," : "").append("\n");
            }
            json.append("  ],\n");

            // 捕获的API详情
            json.append("  \"capturedApiCalls\": [\n");
            if (!apiCallHistory.isEmpty()) {
                for (int i = 0; i < apiCallHistory.size(); i++) {
                    ApiCallRecord record = apiCallHistory.get(i);

                    // 检查是否匹配期望的API
                    boolean matched = false;
                    for (String pattern : apiExpectations.keySet()) {
                        try {
                            if (Pattern.matches(pattern, record.getUrl())) {
                                matched = true;
                                break;
                            }
                        } catch (Exception e) {
                            // ignore
                        }
                    }

                    json.append("    {\n");
                    json.append("      \"#\": ").append(i + 1).append(",\n");
                    json.append("      \"method\": \"").append(record.getMethod()).append("\",\n");
                    json.append("      \"url\": \"").append(escapeJson(record.getUrl())).append("\",\n");
                    json.append("      \"statusCode\": ").append(record.getStatusCode()).append(",\n");
                    json.append("      \"matched\": ").append(matched).append("\n");
                    json.append("    }").append(i < apiCallHistory.size() - 1 ? "," : "").append("\n");
                }
            }
            json.append("  ]\n");
            json.append("}\n");

            Serenity.recordReportData().withTitle("API Monitor Results").andContents(json.toString());
            logger.info(" Recorded API monitor results to Serenity report");

            // 清空警告列表
            apiMonitorWarnings.clear();
        } catch (Exception e) {
            logger.warn("Failed to record API monitor results to Serenity report", e);
        }
    }

    /**
     * 在Serenity报告中记录API调用摘要
     */
    private static void recordApiCallSummary() {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"title\": \"Captured API Calls Summary\",\n");
            json.append("  \"totalApiCalls\": ").append(apiCallHistory.size()).append(",\n");

            if (apiCallHistory.isEmpty()) {
                json.append("  \"message\": \"No API calls captured yet\"\n");
            } else {
                json.append("  \"apiCalls\": [\n");
                for (int i = 0; i < apiCallHistory.size(); i++) {
                    ApiCallRecord record = apiCallHistory.get(i);
                    json.append("    {\n");
                    json.append("      \"#\": ").append(i + 1).append(",\n");
                    json.append("      \"type\": \"").append(record.isMocked() ? "Mock" : "Real").append("\",\n");
                    json.append("      \"url\": \"").append(escapeJson(record.getUrl())).append("\",\n");
                    json.append("      \"method\": \"").append(record.getMethod()).append("\",\n");
                    json.append("      \"statusCode\": ").append(record.getStatusCode()).append("\n");
                    json.append("    }").append(i < apiCallHistory.size() - 1 ? "," : "").append("\n");
                }
                json.append("  ]\n");
            }
            json.append("}\n");

            Serenity.recordReportData().withTitle("API Call Summary").andContents(json.toString());
            logger.info(" Recorded API call summary to Serenity report");
        } catch (Exception e) {
            logger.warn("Failed to record API call summary to Serenity report", e);
        }
    }

    /**
     * 在Serenity报告中记录API验证结果
     */
    private static void recordApiValidationResults() {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"title\": \"API Monitoring Validation Results\",\n");

            if (apiExpectations.isEmpty()) {
                json.append("  \"monitoringMode\": \"All APIs (no specific targets)\"\n");
            } else {
                json.append("  \"targetApis\": [\n");
                int index = 0;
                for (Map.Entry<String, ApiExpectation> entry : apiExpectations.entrySet()) {
                    ApiExpectation expectation = entry.getValue();
                    boolean found = false;
                    ApiCallRecord matchedRecord = null;

                    for (ApiCallRecord record : apiCallHistory) {
                        try {
                            Pattern pattern = Pattern.compile(entry.getKey());
                            if (pattern.matcher(record.getUrl()).matches()) {
                                found = true;
                                matchedRecord = record;
                                break;
                            }
                        } catch (Exception e) {
                            // ignore
                        }
                    }

                    json.append("    {\n");
                    json.append("      \"#\": ").append(++index).append(",\n");
                    json.append("      \"endpoint\": \"").append(escapeJson(expectation.getEndpoint())).append("\",\n");
                    json.append("      \"expectation\": \"").append(escapeJson(expectation.getDescription())).append("\",\n");
                    json.append("      \"status\": \"").append(found ? "MATCHED" : "NOT_MATCHED").append("\",\n");

                    if (found && matchedRecord != null) {
                        json.append("      \"matched\": {\n");
                        json.append("        \"type\": \"").append(matchedRecord.isMocked() ? "Mock" : "Real").append("\",\n");
                        json.append("        \"actualUrl\": \"").append(escapeJson(matchedRecord.getUrl())).append("\",\n");
                        json.append("        \"statusCode\": ").append(matchedRecord.getStatusCode()).append("\n");
                        json.append("      }\n");
                    } else {
                        json.append("      \"matched\": null\n");
                    }
                    json.append("    }").append(index < apiExpectations.size() ? "," : "").append("\n");
                }
                json.append("  ]\n");
            }
            json.append("}\n");

            Serenity.recordReportData().withTitle("API Validation Results").andContents(json.toString());
            logger.info(" Recorded API validation results to Serenity report");
        } catch (Exception e) {
            logger.warn("Failed to record API validation results to Serenity report", e);
        }
    }

    /**
     * 在Serenity报告中显示当前监控的API目标信息
     * 这些信息会自动记录到测试报告中
     */
    public static void logMonitoredApiTargets() {
        logger.info("========== Monitored API Targets ==========");
        
        if (apiExpectations.isEmpty()) {
            logger.info("No specific API targets configured (monitoring all APIs)");
        } else {
            logger.info("Total API targets configured: {}", apiExpectations.size());
            for (Map.Entry<String, ApiExpectation> entry : apiExpectations.entrySet()) {
                ApiExpectation expectation = entry.getValue();
                logger.info("   Target API: {} - {}", expectation.getEndpoint(), expectation.getDescription());
            }
        }

        logger.info("===========================================");
        
        // 自动记录到Serenity报告
        recordMonitoredApiTargets();
    }

    /**
     * 在Serenity报告中显示捕获到的API调用摘要
     */
    public static void logApiCallSummary() {
        logger.info("========== Captured API Calls Summary ==========");
        logger.info("Total API calls captured: {}", apiCallHistory.size());
        
        if (apiCallHistory.isEmpty()) {
            logger.info("No API calls captured yet");
        } else {
            // 按URL分组统计
            Map<String, Long> urlCount = apiCallHistory.stream()
                    .collect(Collectors.groupingBy(
                            ApiCallRecord::getUrl,
                            Collectors.counting()
                    ));
            
            logger.info("API calls by URL:");
            urlCount.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(entry -> logger.info("  - {}: {} time(s)", entry.getKey(), entry.getValue()));
            
            // 按状态码分组统计
            Map<Integer, Long> statusCount = apiCallHistory.stream()
                    .collect(Collectors.groupingBy(
                            ApiCallRecord::getStatusCode,
                            Collectors.counting()
                    ));
            
            logger.info("API calls by status code:");
            statusCount.entrySet().forEach(entry -> 
                    logger.info("  - {}: {} time(s)", entry.getKey(), entry.getValue())
            );
        }
        
        logger.info("===============================================");
        
        // 自动记录到Serenity报告
        recordApiCallSummary();
    }

    /**
     * 在Serenity报告中显示详细的API调用记录
     */
    public static void logDetailedApiCalls() {
        logger.info("========== Detailed API Call Records ==========");
        logger.info("Total API calls: {}", apiCallHistory.size());
        
        if (apiCallHistory.isEmpty()) {
            logger.info("No API calls recorded");
        } else {
            for (int i = 0; i < apiCallHistory.size(); i++) {
                ApiCallRecord record = apiCallHistory.get(i);
                logger.info("#{} {} {} - Status: {}",
                        i + 1, record.getMethod(), record.getUrl(), 
                        record.getStatusCode());
            }
        }
        
        logger.info("===============================================");
    }

    /**
     * 在Serenity报告中显示API验证结果
     * 显示目标API vs 实际捕获的API
     */
    public static void logApiValidationResults() {
        logger.info("========== API Monitoring Validation Results ==========");
        
        // 显示目标API
        logger.info(" Target APIs configured:");
        if (apiExpectations.isEmpty()) {
            logger.info("  - No specific targets (monitoring all APIs)");
        } else {
            for (Map.Entry<String, ApiExpectation> entry : apiExpectations.entrySet()) {
                ApiExpectation expectation = entry.getValue();
                logger.info("  - Endpoint: {} | Expectation: {}",
                        expectation.getEndpoint(), expectation.getDescription());
            }
        }
        
        // 显示实际捕获的API
        logger.info(" Actual APIs captured:");
        if (apiCallHistory.isEmpty()) {
            logger.info("  - No API calls captured yet");
        } else {
            Set<String> capturedUrls = new HashSet<>();
            for (ApiCallRecord record : apiCallHistory) {
                capturedUrls.add(record.getUrl());
            }
            
            for (String url : capturedUrls) {
                long count = apiCallHistory.stream()
                        .filter(r -> r.getUrl().equals(url))
                        .count();
                ApiCallRecord sample = apiCallHistory.stream()
                        .filter(r -> r.getUrl().equals(url))
                        .findFirst()
                        .orElse(null);
                if (sample != null) {
                    logger.info("  - URL: {} | Count: {} | Last Status: {}",
                            url, count, sample.getStatusCode());
                }
            }
        }
        
        // 验证目标API是否被捕获
        if (!apiExpectations.isEmpty() && !apiCallHistory.isEmpty()) {
            logger.info(" Validation Results:");
            for (Map.Entry<String, ApiExpectation> entry : apiExpectations.entrySet()) {
                String pattern = entry.getKey();
                ApiExpectation expectation = entry.getValue();
                
                boolean found = false;
                for (ApiCallRecord record : apiCallHistory) {
                    try {
                        Pattern p = Pattern.compile(pattern);
                        if (p.matcher(record.getUrl()).matches()) {
                            found = true;
                            logger.info("   Target matched: {} -> Captured: {} {} - Status: {}",
                                    expectation.getEndpoint(),
                                    record.getMethod(), record.getUrl(), record.getStatusCode());
                            break;
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }

                if (!found) {
                    logger.warn("  ⚠ Target NOT matched: {} - No matching API calls found",
                            expectation.getEndpoint());
                }
            }
        }
        
        logger.info("======================================================");
        
        // 自动记录到Serenity报告
        recordApiValidationResults();
    }

    // ==================== API Monitor Builder ====================

    /**
     * API监控构建器 - 使用Builder模式配置API监控
     */
    public static class ApiMonitorBuilder {
        private final BrowserContext context;
        private final Page page;
        private final Map<String, ApiExpectation> apiExpectations = new HashMap<>();
        private boolean autoClearHistory = true;
        private Integer stopAfterSeconds = null;  // 在指定秒数后停止
        private Map<String, Integer> stopAfterApiMap = new HashMap<>();  // 检测到指定API后停止 (endpoint -> expectedCount)
        private Map<String, Integer> stopAfterApiTimeoutMap = new HashMap<>();  // API超时设置 (endpoint -> timeoutSeconds)
        private boolean excludeStaticResources = false;  // 是否排除静态资源

        private ApiMonitorBuilder(BrowserContext context) {
            this.context = context;
            this.page = null;
        }

        private ApiMonitorBuilder(Page page) {
            this.page = page;
            this.context = null;
        }

        /**
         * 添加要监控的API及其期望状态码（简单版本）
         *
         * @param endpoint API endpoint（通过 request.url().contains() 判断）
         * @param expectedStatusCode 期望的状态码
         * @return this构建器实例
         */
        public ApiMonitorBuilder monitorApi(String endpoint, int expectedStatusCode) {
            apiExpectations.put(endpoint, ApiExpectation.forEndpoint(endpoint).statusCode(expectedStatusCode));
            return this;
        }

        /**
         * 添加要监控的API及其完整期望（高级版本）
         *
         * @param expectation API期望对象
         * @return this构建器实例
         */
        public ApiMonitorBuilder expectApi(ApiExpectation expectation) {
            apiExpectations.put(expectation.getEndpoint(), expectation);
            return this;
        }

        /**
         * 批量添加要监控的API（简单版本，仅状态码）
         *
         * @param expectations API期望映射
         * @return this构建器实例
         */
        public ApiMonitorBuilder monitorApis(Map<String, Integer> expectations) {
            for (Map.Entry<String, Integer> entry : expectations.entrySet()) {
                apiExpectations.put(entry.getKey(), ApiExpectation.forEndpoint(entry.getKey()).statusCode(entry.getValue()));
            }
            return this;
        }

        /**
         * 是否自动清空历史记录（默认true）
         *
         * @param autoClear true表示自动清空，false表示不清空
         * @return this构建器实例
         */
        public ApiMonitorBuilder autoClearHistory(boolean autoClear) {
            this.autoClearHistory = autoClear;
            return this;
        }

        /**
         * 是否排除静态资源（JS、CSS、图片等）
         * @param exclude true表示排除静态资源
         * @return this构建器实例
         */
        public ApiMonitorBuilder excludeStaticResources(boolean exclude) {
            this.excludeStaticResources = exclude;
            return this;
        }

        /**
         * 在指定秒数后停止监控
         * @param seconds 秒数
         * @return this构建器实例
         */
        public ApiMonitorBuilder stopAfterSeconds(int seconds) {
            this.stopAfterSeconds = seconds;
            return this;
        }

        /**
         * 检测到指定API后停止监控
         * @param endpoint URL匹配模式
         * @param expectedCount 期望检测到的API调用次数
         * @return this构建器实例
         */
        public ApiMonitorBuilder stopAfterApi(String endpoint, int expectedCount) {
            stopAfterApiMap.put(endpoint, expectedCount);
            return this;
        }

        /**
         * 检测到指定API后停止监控，支持超时验证
         * @param endpoint URL匹配模式
         * @param expectedCount 期望检测到的API调用次数
         * @param timeoutSeconds 超时秒数（0表示不设置超时）
         * @return this构建器实例
         */
        public ApiMonitorBuilder stopAfterApi(String endpoint, int expectedCount, int timeoutSeconds) {
            stopAfterApiMap.put(endpoint, expectedCount);
            stopAfterApiTimeoutMap.put(endpoint, timeoutSeconds);
            return this;
        }

        /**
         * 构建并启动监控
         */
        public void build() {
            logger.info("========== Building API Monitor ==========");
            logger.info("Total APIs to monitor: {}", apiExpectations.size());
            if (excludeStaticResources) {
                logger.info("Static resources (JS, CSS, images, etc.) will be excluded from monitoring");
            }
            
            for (Map.Entry<String, ApiExpectation> entry : apiExpectations.entrySet()) {
                logger.info("  - {} -> {}", entry.getKey(), entry.getValue().getDescription());
            }

            // 配置自动停止监控（企业级功能）
            if (stopAfterSeconds != null) {
                logger.info("⏱ Will auto-stop monitoring after {} seconds", stopAfterSeconds);

                // 记录监控开始时的API数量
                final int initialApiCount = apiCallHistory.size();
                final List<String> targetPatterns = new ArrayList<>(apiExpectations.keySet());

                new Thread(() -> {
                    try {
                        Thread.sleep(stopAfterSeconds * 1000L);
                        logger.info(" Time's up! Auto-stopping monitoring after {} seconds...", stopAfterSeconds);

                        // 停止监控
                        if (context != null) {
                            RealApiMonitor.stopMonitoring(context);
                        } else if (page != null) {
                            RealApiMonitor.stopMonitoring(page.context());
                        }

                        // 检查是否捕获到任何目标API
                        boolean foundTargetApi = false;
                        for (String pattern : targetPatterns) {
                            Pattern regex = Pattern.compile(pattern);
                            for (int i = initialApiCount; i < apiCallHistory.size(); i++) {
                                ApiCallRecord record = apiCallHistory.get(i);
                                if (regex.matcher(record.getUrl()).matches()) {
                                    foundTargetApi = true;
                                    logger.info(" Found expected API matching: {}", pattern);
                                    break;
                                }
                            }
                            if (foundTargetApi) break;
                        }

                        // 记录到Serenity报告（无论成功或失败）
                        if (!targetPatterns.isEmpty()) {
                            if (foundTargetApi) {
                                // 成功捕获API
                                String successMsg = String.format(
                                    " API Monitoring SUCCESS<br>" +
                                    "Expected APIs captured within %d seconds<br>" +
                                    "Expected patterns: %s<br>" +
                                    "Initial API count: %d<br>" +
                                    "Final API count: %d<br>" +
                                    "New APIs captured: %d",
                                    stopAfterSeconds, targetPatterns, initialApiCount, apiCallHistory.size(),
                                    apiCallHistory.size() - initialApiCount
                                );
                                apiMonitorWarnings.add(successMsg);
                                logger.info(successMsg.replace("<br>", "\n"));
                            } else {
                                // 未捕获到API - 设置失败标志，主线程操作时会立即检查并抛出异常
                                String warningMsg = String.format(
                                    " API Monitoring FAILED<br>" +
                                    "No expected API captured within %d seconds!<br>" +
                                    "Expected patterns: %s<br>" +
                                    "Initial API count: %d<br>" +
                                    "Final API count: %d<br>" +
                                    "New APIs captured: %d",
                                    stopAfterSeconds, targetPatterns, initialApiCount, apiCallHistory.size(),
                                    apiCallHistory.size() - initialApiCount
                                );
                                logger.warn(" API Monitor Warning: {}", warningMsg.replace("<br>", "\n"));
                                apiMonitorWarnings.add(warningMsg);

                                // 设置失败异常，主线程下一个操作时会自动检查并抛出
                                String errorMsg = String.format(
                                    "API Monitoring Failed!\n" +
                                    "Expected API(s) were not captured within %d seconds.\n" +
                                    "Expected patterns: %s",
                                    stopAfterSeconds, targetPatterns
                                );
                                monitoringFailure = new AssertionError(errorMsg);
                            }
                            // 注意：不在后台线程中记录到Serenity报告，因为后台线程没有测试上下文
                            // 用户需要在主线程中调用 logApiMonitoringResult() 来记录结果
                        }

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.warn("Auto-stop interrupted", e);
                    } catch (Exception e) {
                        logger.error("Error auto-stopping monitoring", e);
                    }
                }, "AutoStopMonitor-Timer").start();
            }

            if (!stopAfterApiMap.isEmpty()) {
                logger.info(" Will stop monitoring after detecting target APIs:");
                stopAfterApiMap.forEach((urlPattern, count) -> {
                    Integer timeout = stopAfterApiTimeoutMap.get(urlPattern);
                    if (timeout != null && timeout > 0) {
                        logger.info("  - {} after {} time(s) (timeout: {}s)", urlPattern, count, timeout);
                    } else {
                        logger.info("  - {} after {} time(s)", urlPattern, count);
                    }
                });
                BrowserContext ctx = context != null ? context : (page != null ? page.context() : null);
                if (ctx != null) {
                    for (Map.Entry<String, Integer> entry : stopAfterApiMap.entrySet()) {
                        Integer timeout = stopAfterApiTimeoutMap.get(entry.getKey());
                        if (timeout != null) {
                            RealApiMonitor.stopMonitoringAfterApi(ctx, entry.getKey(), entry.getValue(), timeout);
                        } else {
                            RealApiMonitor.stopMonitoringAfterApi(ctx, entry.getKey(), entry.getValue());
                        }
                    }
                }
            }

            if (autoClearHistory) {
                RealApiMonitor.clearHistory();
            }

            RealApiMonitor.clearApiExpectations();

            if (!apiExpectations.isEmpty()) {
                // 直接将ApiExpectation对象添加到RealApiMonitor的期望映射中
                for (Map.Entry<String, ApiExpectation> entry : apiExpectations.entrySet()) {
                    RealApiMonitor.apiExpectations.put(entry.getKey(), entry.getValue());
                }
            }

            if (apiExpectations.size() == 1) {
                // 只有一个API，使用特定模式监控
                String pattern = apiExpectations.keySet().iterator().next();
                if (context != null) {
                    RealApiMonitor.monitorApi(context, pattern);
                } else if (page != null) {
                    RealApiMonitor.monitorApi(page, pattern);
                }
            } else {
                // 多个API，监控所有API
                if (context != null) {
                    RealApiMonitor.monitorAllApi(context);
                } else if (page != null) {
                    RealApiMonitor.monitorAllApi(page);
                }
            }

            logger.info(" API Monitor built successfully!");

            // 自动记录到Serenity报告
            RealApiMonitor.recordMonitoredApiTargets();
            
            // 自动记录监控结果到Serenity报告
            RealApiMonitor.logApiMonitoringResult();
        }

        /**
         * 构建并启动监控，返回存储对象
         *
         * @return ApiMonitorStore 存储请求/响应信息
         */
        public ApiMonitorStore buildWithStore() {
            String primaryEndpoint = apiExpectations.isEmpty() ? "" : apiExpectations.keySet().iterator().next();
            ApiMonitorStore store = new ApiMonitorStore(primaryEndpoint);

            // 注册存储回调
            RealApiMonitor.addStoreCallback(store);

            // 调用原有的build逻辑
            build();

            return store;
        }
    }

    // 存储回调列表
    private static final List<ApiMonitorStore> activeStores = new CopyOnWriteArrayList<>();

    /**
     * 添加存储回调
     */
    private static void addStoreCallback(ApiMonitorStore store) {
        activeStores.add(store);
    }

    /**
     * 移除存储回调
     */
    public static void removeStoreCallback(ApiMonitorStore store) {
        activeStores.remove(store);
    }

    // 在记录API调用时通知所有存储
    private static void notifyStores(ApiCallRecord record) {
        for (ApiMonitorStore store : activeStores) {
            try {
                store.add(record);
            } catch (Exception e) {
                logger.error("Error adding record to store", e);
            }
        }
    }

    // ==================== API Expectation ====================

    /**
     * API期望类 - 支持多维度验证
     */
    public static class ApiExpectation {
        private final String endpoint;
        private Integer expectedStatusCode;
        private String expectedResponseBodyContent;  // 部分匹配
        private String expectedResponseBodyExact;     // 完全匹配
        private String expectedResponseBodyRegex;     // 正则匹配
        private String expectedResponseHeaderName;
        private String expectedResponseHeaderValue;
        private String customDescription;  // 自定义描述

        // JSON Path验证
        private Map<String, Object> jsonPathEqualsMap = new HashMap<>();  // JSON path精确匹配
        private Map<String, String> jsonPathContainsMap = new HashMap<>();  // JSON path包含
        private Map<String, String> jsonPathMatchesMap = new HashMap<>();  // JSON path正则匹配
        private Map<String, Integer> jsonPathIntEqualsMap = new HashMap<>();  // JSON path整数比较
        private Map<String, Boolean> jsonPathBooleanEqualsMap = new HashMap<>();  // JSON path布尔值比较

        private ApiExpectation(String endpoint) {
            this.endpoint = endpoint;
        }

        /**
         * 创建API期望对象
         *
         * @param urlPattern URL匹配模式（支持普通URL如 "/api/xxx" 或正则如 ".*api/users.*"）
         *                普通URL会自动转换为正则表达式
         * @return ApiExpectation对象
         */
        public static ApiExpectation forUrl(String urlPattern) {
            // 自动将普通URL转换为正则表达式
            String pattern = RealApiMonitor.toRegexPattern(urlPattern);
            return new ApiExpectation(pattern);
        }

        /**
         * 创建API期望对象（使用 endpoint，通过 request.url().contains() 匹配）
         *
         * @param endpoint API endpoint（如 "/api/users"）
         * @return ApiExpectation对象
         */
        public static ApiExpectation forEndpoint(String endpoint) {
            return new ApiExpectation(endpoint);
        }

        /**
         * 设置期望的状态码
         *
         * @param statusCode 期望的状态码
         * @return this
         */
        public ApiExpectation statusCode(int statusCode) {
            this.expectedStatusCode = statusCode;
            return this;
        }
        
        /**
         * 设置期望的自定义描述
         *
         * @param description 自定义描述
         * @return this
         */
        public ApiExpectation description(String description) {
            this.customDescription = description;
            return this;
        }

        /**
         * 设置期望的响应体包含内容
         *
         * @param content 期望包含的内容
         * @return this
         */
        public ApiExpectation responseBodyContains(String content) {
            this.expectedResponseBodyContent = content;
            return this;
        }

        /**
         * 设置期望的响应头
         *
         * @param headerName 响应头名称
         * @param headerValue 期望的响应头值（支持部分匹配）
         * @return this
         */
        public ApiExpectation responseHeaderContains(String headerName, String headerValue) {
            this.expectedResponseHeaderName = headerName;
            this.expectedResponseHeaderValue = headerValue;
            return this;
        }

        /**
         * 设置期望的完整响应体（完全匹配）
         *
         * @param expectedBody 期望的完整响应体内容
         * @return this
         *
         * 示例：
         * ApiExpectation.forUrl(".*auth/login.*")
         *     .responseBodyEquals("{\"status\":\"success\",\"token\":\"abc123\"}");
         */
        public ApiExpectation responseBodyEquals(String expectedBody) {
            this.expectedResponseBodyExact = expectedBody;
            return this;
        }

        /**
         * 设置期望的响应体正则表达式（正则匹配）
         * @param regex 正则表达式
         * @return this
         */
        public ApiExpectation responseBodyMatches(String regex) {
            this.expectedResponseBodyRegex = regex;
            return this;
        }

        /**
         * 设置JSON Path精确匹配
         * @param jsonPath JSON Path表达式
         * @param expectedValue 期望的值
         * @return this
         */
        public ApiExpectation jsonPathEquals(String jsonPath, Object expectedValue) {
            this.jsonPathEqualsMap.put(jsonPath, expectedValue);
            return this;
        }

        /**
         * 设置JSON Path包含匹配
         * @param jsonPath JSON Path表达式
         * @param expectedContent 期望包含的内容
         * @return this
         */
        public ApiExpectation jsonPathContains(String jsonPath, String expectedContent) {
            this.jsonPathContainsMap.put(jsonPath, expectedContent);
            return this;
        }

        /**
         * 设置JSON Path正则表达式匹配
         * @param jsonPath JSON Path表达式
         * @param regex 期望的正则表达式
         * @return this
         */
        public ApiExpectation jsonPathMatches(String jsonPath, String regex) {
            this.jsonPathMatchesMap.put(jsonPath, regex);
            return this;
        }

        /**
         * 设置JSON Path整数精确匹配
         * @param jsonPath JSON Path表达式
         * @param expectedValue 期望的整数值
         * @return this
         */
        public ApiExpectation jsonPathIntEquals(String jsonPath, int expectedValue) {
            this.jsonPathIntEqualsMap.put(jsonPath, expectedValue);
            return this;
        }

        /**
         * 设置JSON Path布尔值精确匹配
         * @param jsonPath JSON Path表达式
         * @param expectedValue 期望的布尔值
         * @return this
         */
        public ApiExpectation jsonPathBooleanEquals(String jsonPath, boolean expectedValue) {
            this.jsonPathBooleanEqualsMap.put(jsonPath, expectedValue);
            return this;
        }

        /**
         * 获取URL模式/endpoint
         */
        public String getEndpoint() {
            return endpoint;
        }

        /**
         * 获取期望描述
         */
        public String getDescription() {
            StringBuilder desc = new StringBuilder();
            
            // 如果有自定义描述，添加到开头
            if (customDescription != null && !customDescription.isEmpty()) {
                desc.append(customDescription);
            }
            
            if (expectedStatusCode != null) {
                if (desc.length() > 0) desc.append(", ");
                desc.append("Status=").append(expectedStatusCode);
            }
            if (expectedResponseBodyContent != null) {
                if (desc.length() > 0) desc.append(", ");
                desc.append("Body contains '").append(expectedResponseBodyContent).append("'");
            }
            if (expectedResponseBodyExact != null) {
                if (desc.length() > 0) desc.append(", ");
                desc.append("Body equals '").append(expectedResponseBodyExact).append("'");
            }
            if (expectedResponseBodyRegex != null) {
                if (desc.length() > 0) desc.append(", ");
                desc.append("Body matches '").append(expectedResponseBodyRegex).append("'");
            }
            if (expectedResponseHeaderName != null) {
                if (desc.length() > 0) desc.append(", ");
                desc.append("Header[").append(expectedResponseHeaderName).append("] contains '").append(expectedResponseHeaderValue).append("'");
            }
            if (!jsonPathEqualsMap.isEmpty()) {
                if (desc.length() > 0) desc.append(", ");
                desc.append("JSON Path=").append(jsonPathEqualsMap.size()).append(" checks");
            }
            if (!jsonPathContainsMap.isEmpty()) {
                if (desc.length() > 0) desc.append(", ");
                desc.append("JSON Path Contains=").append(jsonPathContainsMap.size()).append(" checks");
            }
            if (!jsonPathMatchesMap.isEmpty()) {
                if (desc.length() > 0) desc.append(", ");
                desc.append("JSON Path Matches=").append(jsonPathMatchesMap.size()).append(" checks");
            }
            if (!jsonPathIntEqualsMap.isEmpty()) {
                if (desc.length() > 0) desc.append(", ");
                desc.append("JSON Path Int=").append(jsonPathIntEqualsMap.size()).append(" checks");
            }
            if (!jsonPathBooleanEqualsMap.isEmpty()) {
                if (desc.length() > 0) desc.append(", ");
                desc.append("JSON Path Boolean=").append(jsonPathBooleanEqualsMap.size()).append(" checks");
            }
            return desc.length() > 0 ? desc.toString() : "No validation";
        }

        /**
         * 验证API调用记录
         *
         * @param record API调用记录
         * @throws AssertionError 如果验证失败
         */
        public void validate(ApiCallRecord record) {
            List<String> failures = new ArrayList<>();

            // 验证状态码
            if (expectedStatusCode != null) {
                try {
                    assertThat("Status Code Mismatch for " + record.getUrl(), 
                               record.getStatusCode(), equalTo(expectedStatusCode));
                } catch (AssertionError e) {
                    failures.add(e.getMessage());
                }
            }

            // 验证响应体内容
            if (expectedResponseBodyContent != null) {
                Object responseBodyObj = record.getResponseBody();
                if (responseBodyObj == null) {
                    failures.add(String.format(
                        "Response Body Does Not Contain: Response body is null, Expected to contain '%s'",
                        expectedResponseBodyContent
                    ));
                } else {
                    String responseBody = String.valueOf(responseBodyObj);
                    if (!responseBody.contains(expectedResponseBodyContent)) {
                        failures.add(String.format(
                            "Response Body Does Not Contain: Expected '%s' in response",
                            expectedResponseBodyContent
                        ));
                    }
                }
            }

            // 验证完整响应体（完全匹配）
            if (expectedResponseBodyExact != null) {
                Object responseBodyObj = record.getResponseBody();
                if (responseBodyObj == null) {
                    failures.add(String.format(
                        "Response Body Mismatch (Exact Match):%nExpected: %s%nActual: [null]",
                        expectedResponseBodyExact
                    ));
                } else {
                    String responseBody = String.valueOf(responseBodyObj);
                    if (!responseBody.equals(expectedResponseBodyExact)) {
                        failures.add(String.format(
                            "Response Body Mismatch (Exact Match):%nExpected: %s%nActual: %s",
                            expectedResponseBodyExact,
                            responseBody
                        ));
                    }
                }
            }

            // 验证响应体正则匹配
            if (expectedResponseBodyRegex != null) {
                Object responseBodyObj = record.getResponseBody();
                if (responseBodyObj == null) {
                    failures.add(String.format(
                        "Response Body Does Not Match Pattern: Expected pattern '%s'%nActual: [null]",
                        expectedResponseBodyRegex
                    ));
                } else {
                    String responseBody = String.valueOf(responseBodyObj);
                    if (!Pattern.matches(expectedResponseBodyRegex, responseBody)) {
                        failures.add(String.format(
                            "Response Body Does Not Match Pattern: Expected pattern '%s'%nActual: %s",
                            expectedResponseBodyRegex,
                            responseBody
                        ));
                    }
                }
            }

            // 验证响应头
            if (expectedResponseHeaderName != null) {
                String actualHeaderValue = record.getResponseHeaders().get(expectedResponseHeaderName);
                if (actualHeaderValue == null || !actualHeaderValue.contains(expectedResponseHeaderValue)) {
                    failures.add(String.format(
                        "Response Header Mismatch: Expected '%s' to contain '%s', Actual '%s'",
                        expectedResponseHeaderName, expectedResponseHeaderValue, actualHeaderValue
                    ));
                }
            }

            // 验证JSON Path精确匹配
            if (!jsonPathEqualsMap.isEmpty()) {
                Object responseBodyObj = record.getResponseBody();
                if (responseBodyObj == null) {
                    failures.add("JSON Path Validation Failed: Response body is null, cannot validate JSON Path");
                } else {
                    String responseBody = String.valueOf(responseBodyObj);
                    try {
                        for (Map.Entry<String, Object> entry : jsonPathEqualsMap.entrySet()) {
                            String jsonPath = entry.getKey();
                            Object expectedValue = entry.getValue();

                            try {
                                Object actualValue = JsonPath.parse(responseBody).read(jsonPath);
                                if (!expectedValue.equals(actualValue)) {
                                    failures.add(String.format(
                                        "JSON Path Mismatch: Path '%s' Expected '%s', Actual '%s'",
                                        jsonPath, expectedValue, actualValue
                                    ));
                                }
                            } catch (PathNotFoundException e) {
                                failures.add(String.format(
                                    "JSON Path Not Found: Path '%s' does not exist in response",
                                    jsonPath
                                ));
                            }
                        }
                    } catch (Exception e) {
                        failures.add(String.format(
                            "JSON Parse Error: Failed to parse response as JSON: %s",
                            e.getMessage()
                        ));
                    }
                }
            }

            // 验证JSON Path包含匹配
            if (!jsonPathContainsMap.isEmpty()) {
                Object responseBodyObj = record.getResponseBody();
                if (responseBodyObj == null) {
                    failures.add("JSON Path Validation Failed: Response body is null, cannot validate JSON Path");
                } else {
                    String responseBody = String.valueOf(responseBodyObj);
                    try {
                        for (Map.Entry<String, String> entry : jsonPathContainsMap.entrySet()) {
                            String jsonPath = entry.getKey();
                            String expectedContent = entry.getValue();

                            try {
                                Object actualValue = JsonPath.parse(responseBody).read(jsonPath);
                                String actualStr = String.valueOf(actualValue);
                                if (actualStr == null || !actualStr.contains(expectedContent)) {
                                    failures.add(String.format(
                                        "JSON Path Does Not Contain: Path '%s' Expected to contain '%s', Actual '%s'",
                                        jsonPath, expectedContent, actualStr
                                    ));
                                }
                            } catch (PathNotFoundException e) {
                                failures.add(String.format(
                                    "JSON Path Not Found: Path '%s' does not exist in response",
                                    jsonPath
                                ));
                            }
                        }
                    } catch (Exception e) {
                        failures.add(String.format(
                            "JSON Parse Error: Failed to parse response as JSON: %s",
                            e.getMessage()
                        ));
                    }
                }
            }

            // 验证JSON Path正则匹配
            if (!jsonPathMatchesMap.isEmpty()) {
                Object responseBodyObj = record.getResponseBody();
                if (responseBodyObj == null) {
                    failures.add("JSON Path Validation Failed: Response body is null, cannot validate JSON Path");
                } else {
                    String responseBody = String.valueOf(responseBodyObj);
                    try {
                        for (Map.Entry<String, String> entry : jsonPathMatchesMap.entrySet()) {
                            String jsonPath = entry.getKey();
                            String regex = entry.getValue();

                            try {
                                Object actualValue = JsonPath.parse(responseBody).read(jsonPath);
                                String actualStr = String.valueOf(actualValue);
                                if (actualStr == null || !Pattern.matches(regex, actualStr)) {
                                    failures.add(String.format(
                                        "JSON Path Does Not Match Pattern: Path '%s' Expected pattern '%s', Actual '%s'",
                                        jsonPath, regex, actualStr
                                    ));
                                }
                            } catch (PathNotFoundException e) {
                                failures.add(String.format(
                                    "JSON Path Not Found: Path '%s' does not exist in response",
                                    jsonPath
                                ));
                            }
                        }
                    } catch (Exception e) {
                        failures.add(String.format(
                            "JSON Parse Error: Failed to parse response as JSON: %s",
                            e.getMessage()
                        ));
                    }
                }
            }

            // 验证JSON Path整数匹配
            if (!jsonPathIntEqualsMap.isEmpty()) {
                Object responseBodyObj = record.getResponseBody();
                if (responseBodyObj == null) {
                    failures.add("JSON Path Validation Failed: Response body is null, cannot validate JSON Path");
                } else {
                    String responseBody = String.valueOf(responseBodyObj);
                    try {
                        for (Map.Entry<String, Integer> entry : jsonPathIntEqualsMap.entrySet()) {
                            String jsonPath = entry.getKey();
                            Integer expectedValue = entry.getValue();

                            try {
                                Object actualValue = JsonPath.parse(responseBody).read(jsonPath);
                                Integer actualInt = null;
                                if (actualValue instanceof Integer) {
                                    actualInt = (Integer) actualValue;
                                } else if (actualValue instanceof Long) {
                                    actualInt = ((Long) actualValue).intValue();
                                } else if (actualValue instanceof String) {
                                    try {
                                        actualInt = Integer.parseInt((String) actualValue);
                                    } catch (NumberFormatException ignored) {}
                                }

                                if (actualInt == null || !actualInt.equals(expectedValue)) {
                                    failures.add(String.format(
                                        "JSON Path Integer Mismatch: Path '%s' Expected %d, Actual %s",
                                        jsonPath, expectedValue, actualValue
                                    ));
                                }
                            } catch (PathNotFoundException e) {
                                failures.add(String.format(
                                    "JSON Path Not Found: Path '%s' does not exist in response",
                                    jsonPath
                                ));
                            }
                        }
                    } catch (Exception e) {
                        failures.add(String.format(
                            "JSON Parse Error: Failed to parse response as JSON: %s",
                            e.getMessage()
                        ));
                    }
                }
            }

            // 验证JSON Path布尔值匹配
            if (!jsonPathBooleanEqualsMap.isEmpty()) {
                Object responseBodyObj = record.getResponseBody();
                if (responseBodyObj == null) {
                    failures.add("JSON Path Validation Failed: Response body is null, cannot validate JSON Path");
                } else {
                    String responseBody = String.valueOf(responseBodyObj);
                    try {
                        for (Map.Entry<String, Boolean> entry : jsonPathBooleanEqualsMap.entrySet()) {
                            String jsonPath = entry.getKey();
                            Boolean expectedValue = entry.getValue();

                            try {
                                Object actualValue = JsonPath.parse(responseBody).read(jsonPath);
                                Boolean actualBoolean = null;
                                if (actualValue instanceof Boolean) {
                                    actualBoolean = (Boolean) actualValue;
                                } else if (actualValue instanceof String) {
                                    String str = ((String) actualValue).toLowerCase();
                                    if ("true".equals(str) || "false".equals(str)) {
                                        actualBoolean = Boolean.parseBoolean(str);
                                    }
                                }

                                if (actualBoolean == null || !actualBoolean.equals(expectedValue)) {
                                    failures.add(String.format(
                                        "JSON Path Boolean Mismatch: Path '%s' Expected %s, Actual %s",
                                        jsonPath, expectedValue, actualValue
                                    ));
                                }
                            } catch (PathNotFoundException e) {
                                failures.add(String.format(
                                    "JSON Path Not Found: Path '%s' does not exist in response",
                                    jsonPath
                                ));
                            }
                        }
                    } catch (Exception e) {
                        failures.add(String.format(
                            "JSON Parse Error: Failed to parse response as JSON: %s",
                            e.getMessage()
                        ));
                    }
                }
            }

            // 如果有失败项，抛出异常
            if (!failures.isEmpty()) {
                String errorMsg = String.format(
                    "Real-time API Validation Failed%n" +
                    "URL: %s%n" +
                    "Method: %s%n" +
                    "%s%n" +
                    "Response Body: %s",
                    record.getUrl(),
                    record.getMethod(),
                    String.join("%n", failures),
                    String.valueOf(record.getResponseBody())
                );
                throw new AssertionError(errorMsg);
            }

            // 验证通过
            logger.info(" API monitoring PASSED! URL: {}, Method: {}, Status: {} - ({})",
                    record.getUrl(),
                    record.getMethod(),
                    record.getStatusCode(),
                    getDescription());
        }
    }
}
