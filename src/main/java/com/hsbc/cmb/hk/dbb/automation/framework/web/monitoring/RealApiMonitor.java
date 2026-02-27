package com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.*;
import net.serenitybdd.core.Serenity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Real API Monitor - 实时监控API响应（企业级解决方案）
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
 * 使用方式（推荐使用Builder模式）：
 *
 * 【推荐】Builder模式 - 简单验证（仅状态码）：
 *   RealApiMonitor.with(context)
 *       .monitorApi(".*auth/login.*", 200)
 *       .monitorApi(".*api/users.*", 200)
 *       .build();
 *   RealApiMonitor.logApiMonitoringResult();  // 记录结果到Serenity报告
 *
 * 【推荐】Builder模式 - 自动停止监控：
 *   RealApiMonitor.with(context)
 *       .monitorApi(".*auth/login.*", 200)
 *       .monitorApi(".*api/users.*", 200)
 *       .stopAfterSeconds(10)  // 10秒后停止
 *       .build();
 *   RealApiMonitor.logApiMonitoringResult();  // 记录结果到Serenity报告
 *
 * 【推荐】Builder模式 - 检测到API后停止（带超时验证）：
 *   RealApiMonitor.with(context)
 *       .monitorApi(".*auth/login.*", 200)
 *       .stopAfterApi(".*auth/login.*", 1, 10)  // 10秒内必须检测到1次登录API
 *       .build();
 *   RealApiMonitor.logApiMonitoringResult();  // 记录结果到Serenity报告
 *
 * 【高级】Builder模式 - 多维度验证：
 *   RealApiMonitor.with(context)
 *       .expectApi(ApiExpectation.forUrl(".*auth/login.*")
 *           .statusCode(200)
 *           .responseTimeLessThan(1000)
 *           .responseBodyContains("success"))
 *       .expectApi(ApiExpectation.forUrl(".*api/users.*")
 *           .statusCode(200)
 *           .responseTimeLessThan(500))
 *       .build();
 *   RealApiMonitor.logApiMonitoringResult();  // 记录结果到Serenity报告
 *
 * 【完整Response内容验证】：
 *   // 完全匹配
 *   RealApiMonitor.with(context)
 *       .expectApi(ApiExpectation.forUrl(".*auth/login.*")
 *           .responseBodyEquals("{\"status\":\"success\",\"token\":\"abc123\"}"))
 *       .build();
 *
 *   // 正则匹配
 *   RealApiMonitor.with(context)
 *       .expectApi(ApiExpectation.forUrl(".*auth/login.*")
 *           .responseBodyMatches(".*\"token\":\"[^\"]+\".*"))
 *       .build();
 *
 * 【简化】单API监控验证（仅状态码）：
 *   monitorAndVerify(context, ".*auth/login.*", 200);
 *
 * 【高级】单API多维度验证：
 *   monitorWithExpectation(context, ApiExpectation.forUrl(".*auth/login.*")
 *       .statusCode(200)
 *       .responseTimeLessThan(1000)
 *       .responseBodyContains("token"));
 *
 * 【灵活】只监控不验证：
 *   startMonitoring(context, ".*api/.*");
 *
 * 【停止监控】：
 *   stopMonitoring(context);  // 停止所有监控
 *   stopMonitoring(context, ".*api/.*");  // 停止指定URL模式的监控
 *   stopMonitoringAfterSeconds(context, 10);  // 10秒后停止监控
 *   stopMonitoringAfterSeconds(context, 10, ".*auth/login.*");  // 10秒内必须检测到auth/login
 *   stopMonitoringAfterApi(context, ".*auth/login.*", 1);  // 检测到1次登录API后停止
 *   stopMonitoringAfterApi(context, ".*auth/login.*", 1, 10);  // 10秒内必须检测到1次登录API
 *   RealApiMonitor.logApiMonitoringResult();  // 记录结果到Serenity报告（重要！）
 *
 * 【记录API监控结果】：
 *   logApiMonitoringResult();  // 记录API监控结果到Serenity报告（推荐，必须调用）
 *   assertThatApiMonitoring();  // 断言API监控结果，失败则抛出AssertionError（适用于exception场景）
 *
 * 【调试】：
 *   printAllCapturedApis();  // 仅用于调试，打印所有捕获的API
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

    // ==================== 简化API（最常用） ====================

    /**
     * 【推荐】使用Builder模式配置API监控
     *
     * @param context Playwright BrowserContext对象
     * @return ApiMonitorBuilder对象，用于链式调用
     *
     * 示例：
     * RealApiMonitor.with(context)
     *     .monitorApi(".*auth/login.*", 200)
     *     .monitorApi(".*api/users.*", 200)
     *     .build();
     * 
     * 示例：自动停止监控
     * RealApiMonitor.with(context)
     *     .monitorApi(".*auth/login.*", 200)
     *     .stopAfterSeconds(10)  // 10秒后自动停止
     *     .build();
     * 
     * 示例：检测到API后停止
     * RealApiMonitor.with(context)
     *     .monitorApi(".*auth/login.*", 200)
     *     .stopAfterApi(".*auth/login.*", 1)  // 检测到1次登录API后停止
     *     .build();
     *
     * 示例：检测到API后停止（带超时验证）
     * RealApiMonitor.with(context)
     *     .monitorApi(".*auth/login.*", 200)
     *     .stopAfterApi(".*auth/login.*", 1, 10)  // 10秒内必须检测到1次登录API，否则报错
     *     .build();
     */
    public static ApiMonitorBuilder with(BrowserContext context) {
        return new ApiMonitorBuilder(context);
    }

    /**
     * 【推荐】使用Builder模式配置API监控（Page版本）
     *
     * @param page Playwright Page对象
     * @return ApiMonitorBuilder对象，用于链式调用
     *
     * 示例：
     * RealApiMonitor.with(page)
     *     .monitorApi(".*auth/login.*", 200)
     *     .stopAfterSeconds(10)
     *     .build();
     */
    public static ApiMonitorBuilder with(Page page) {
        return new ApiMonitorBuilder(page);
    }

    /**
     * 【简化】监控单个API并实时验证 - 一行代码搞定！
     * 自动清空历史、启用验证、设置期望、开始监控
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL如 "/api/xxx" 或正则如 ".*api/users.*"）
     * @param expectedStatusCode 期望的状态码（如 200）
     *
     * 示例：
     * monitorAndVerify(context, ".*auth/login.*", 200);
     * monitorAndVerify(context, "/api/users", 200); // 自动转换为正则
     */
    public static void monitorAndVerify(BrowserContext context, String urlPattern, int expectedStatusCode) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Starting API monitoring with real-time verification ==========");
        logger.info("Monitoring API: {} (Expected Status: {})", pattern, expectedStatusCode);
        logger.info("Original URL pattern: '{}' -> Converted to: '{}'", urlPattern, pattern);
        logger.info("Monitoring will stop automatically after detecting the first matching API");
        clearHistory();
        clearApiExpectations();
        expectApiStatus(pattern, expectedStatusCode);
        monitorApi(context, pattern);
        // 自动停止监控：检测到第一个匹配的API后停止
        stopMonitoringAfterApi(context, urlPattern, 1);
    }

    /**
     * 【简化】监控多个API并实时验证 - 批量设置
     *
     * @param context Playwright BrowserContext对象
     * @param expectations API期望映射（URL模式 -> 期望状态码，支持普通URL或正则）
     *
     * 示例：
     * monitorMultiple(context, Map.of(
     *     ".*api/users.*", 200,
     *     ".*api/products.*", 200
     * ));
     * // 或使用普通URL
     * monitorMultiple(context, Map.of(
     *     "/api/users", 200,
     *     "/api/products", 200
     * ));
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
     * 【灵活】只监控API，不自动验证 - 灵活手动验证
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL或正则）
     *
     * 示例：
     * startMonitoring(context, ".*api/.*");
     * // ... 执行操作
     * verifyStatus(".*api/users.*", 200); // 手动验证
     */
    public static void startMonitoring(BrowserContext context, String urlPattern) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("========== Starting API monitoring (without automatic verification) ==========");
        logger.info("Monitoring API: {} (Original: '{}')", pattern, urlPattern);
        clearHistory();
        monitorApi(context, pattern);
    }

    /**
     * 【灵活】监控所有API响应
     *
     * @param context Playwright BrowserContext对象
     *
     * 示例：
     * startMonitoringAll(context);
     * // ... 执行操作
     * printAllCapturedApis(); // 查看所有捕获的API
     */
    public static void startMonitoringAll(BrowserContext context) {
        logger.info("========== Starting full API monitoring (all APIs) ==========");
        clearHistory();
        monitorAllApi(context);
    }

    /**
     * 【高级】监控单个API并进行多维度实时验证
     * 支持验证状态码、响应时间、响应内容等
     *
     * @param context Playwright BrowserContext对象
     * @param expectation API期望对象
     *
     * 示例：
     * monitorWithExpectation(context, ApiExpectation.forUrl(".*auth/login.*")
     *     .statusCode(200)
     *     .responseTimeLessThan(1000)
     *     .responseBodyContains("token"));
     */
    public static void monitorWithExpectation(BrowserContext context, ApiExpectation expectation) {
        logger.info("========== Starting API monitoring with multi-dimension verification ==========");
        logger.info("Monitoring API: {} with expectation: {}", expectation.getUrlPattern(), expectation.getDescription());
        clearHistory();
        clearApiExpectations();
        RealApiMonitor.apiExpectations.put(expectation.getUrlPattern(), expectation);
        monitorApi(context, expectation.getUrlPattern());
        // 自动记录到Serenity报告
        recordMonitoredApiTargets();
    }

    /**
     * 【高级】监控多个API并进行多维度实时验证
     *
     * @param context Playwright BrowserContext对象
     * @param expectations API期望对象列表
     *
     * 示例：
     * monitorWithExpectations(context, List.of(
     *     ApiExpectation.forUrl(".*auth/login.*").statusCode(200).responseTimeLessThan(1000),
     *     ApiExpectation.forUrl(".*api/users.*").statusCode(200).responseBodyContains("data")
     * ));
     */
    public static void monitorWithExpectations(BrowserContext context, List<ApiExpectation> expectations) {
        logger.info("========== Starting multiple APIs monitoring with multi-dimension verification ==========");
        logger.info("Monitoring {} APIs with verification", expectations.size());
        clearHistory();
        clearApiExpectations();
        for (ApiExpectation expectation : expectations) {
            logger.info("  - {} : {}", expectation.getUrlPattern(), expectation.getDescription());
            RealApiMonitor.apiExpectations.put(expectation.getUrlPattern(), expectation);
        }
        if (expectations.size() == 1) {
            monitorApi(context, expectations.get(0).getUrlPattern());
        } else {
            monitorAllApi(context);
        }
        // 自动记录到Serenity报告
        recordMonitoredApiTargets();
    }

    /**
     * 将普通URL模式转换为正则表达式
     * 如果URL已经是正则表达式（包含.*、\\d等），则原样返回
     * 否则自动添加.*前缀和后缀进行灵活匹配
     *
     * @param urlPattern URL模式（普通URL或正则表达式）
     * @return 正则表达式模式
     *
     * 示例：
     * - "/api/users" -> ".*api/users.*"
     * - "api/users" -> ".*api/users.*"
     * - ".*api/.*" -> ".*api/.*" (已经是正则，不转换)
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
        private final long responseTimeMs;
        private final boolean isMocked;

        public ApiCallRecord(String requestId, String url, String method, long timestamp,
                           Map<String, String> requestHeaders, Object requestBody,
                           int statusCode, Map<String, String> responseHeaders,
                           Object responseBody, long responseTimeMs, boolean isMocked) {
            this.requestId = requestId;
            this.url = url;
            this.method = method;
            this.timestamp = timestamp;
            this.requestHeaders = requestHeaders;
            this.requestBody = requestBody;
            this.statusCode = statusCode;
            this.responseHeaders = responseHeaders;
            this.responseBody = responseBody;
            this.responseTimeMs = responseTimeMs;
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
        public long getResponseTimeMs() { return responseTimeMs; }
        public boolean isMocked() { return isMocked; }
        
        @Override
        public String toString() {
            return String.format("ApiCallRecord{url='%s', method='%s', statusCode=%d, responseTime=%dms}",
                    url, method, statusCode, responseTimeMs);
        }
    }
    
    /**
     * 响应监听器接口
     */
    @FunctionalInterface
    public interface ResponseListener {
        void onResponse(Response response, Request request, long responseTimeMs);
    }
    
    /**
     * 监控特定URL的真实API响应（针对BrowserContext）
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持正则表达式）
     */
    public static void monitorApi(BrowserContext context, String urlPattern) {
        monitorApi(context, urlPattern, null);
    }
    
    /**
     * 监控特定URL的真实API响应，并提供自定义监听器（针对BrowserContext）
     * 
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持正则表达式）
     * @param listener 响应监听器（可为null）
     */
    public static void monitorApi(BrowserContext context, String urlPattern, ResponseListener listener) {
        Pattern pattern = Pattern.compile(urlPattern);
        logger.info("🎯 Setting up API monitor for pattern: {} on BrowserContext", urlPattern);
        // 用于统计响应数量
        final int[] responseCount = {0};

        // 保存监听器引用（先初始化set）
        Set<ResponseListener> listeners = contextListeners.computeIfAbsent(context, k -> new HashSet<>());

        // 重置停止标志位（允许重新开始监控）
        contextMonitoringStopped.put(context, false);

        // 添加响应监听器
        ResponseListener responseListener = (response, request, responseTimeMs) -> {
            responseCount[0]++;
            boolean matches = pattern.matcher(response.url()).matches();
            LoggingConfigUtil.logDebugIfVerbose(logger, "🔍 Checking URL: {} matches pattern: {} = {} (Total responses: {})",
                    response.url(), urlPattern, matches, responseCount[0]);

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
                            responseBody, responseTimeMs, false
                    );

                    apiCallHistory.add(record);
                    logger.info("✅ Recorded API call: {} {} - Status: {}",
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

        logger.info("📡 Registering onResponse listener on BrowserContext, listeners for this context: {}", listeners.size());

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

            LoggingConfigUtil.logDebugIfVerbose(logger, "📡 onResponse event fired! URL: {}, Status: {}", response.url(), response.status());
            // 使用Playwright API获取真实的响应时间
            long responseTimeMs = 0;
            try {
                responseTimeMs = (long) response.request().timing().responseEnd;
                LoggingConfigUtil.logDebugIfVerbose(logger, "📊 Response timing for {}: {}ms", response.url(), responseTimeMs);
            } catch (Exception e) {
                logger.debug("Failed to get response timing for: {}", response.url());
            }

            // 调用内部监听器
            for (ResponseListener rl : currentListeners) {
                try {
                    rl.onResponse(response, response.request(), responseTimeMs);
                } catch (AssertionError e) {
                    // AssertionError 直接传播，让测试立即失败
                    throw e;
                } catch (Exception e) {
                    logger.error("Error executing response listener", e);
                }
            }
        });

        logger.info("✅ API monitoring started successfully for pattern: {} on BrowserContext", urlPattern);
    }
    
    /**
     * 监控所有API响应
     *
     * @param context Playwright BrowserContext对象
     */
    public static void monitorAllApi(BrowserContext context) {
        monitorApi(context, ".*");
    }

    /**
     * 监控特定URL的真实API响应（针对Page）
     *
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持正则表达式）
     */
    public static void monitorApi(Page page, String urlPattern) {
        monitorApi(page, urlPattern, null);
    }

    /**
     * 监控特定URL的真实API响应（针对Page），并提供自定义监听器
     * 
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持正则表达式）
     * @param listener 响应监听器（可为null）
     */
    public static void monitorApi(Page page, String urlPattern, ResponseListener listener) {
        Pattern pattern = Pattern.compile(urlPattern);
        logger.info("🎯 Setting up API monitor for pattern: {} on Page", urlPattern);
        // 用于统计响应数量
        final int[] responseCount = {0};

        // 重置停止标志位（允许重新开始监控）
        pageMonitoringStopped.put(page, false);

        // 添加响应监听器
        ResponseListener responseListener = (response, request, responseTimeMs) -> {
            responseCount[0]++;
            boolean matches = pattern.matcher(response.url()).matches();
            LoggingConfigUtil.logDebugIfVerbose(logger, "🔍 Checking URL: {} matches pattern: {} = {} (Total responses: {})",
                    response.url(), urlPattern, matches, responseCount[0]);

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
                            responseBody, responseTimeMs, false
                    );

                    apiCallHistory.add(record);
                    logger.info("✅ Recorded API call: {} {} - Status: {}",
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

            LoggingConfigUtil.logDebugIfVerbose(logger, "📡 onResponse event fired! URL: {}, Status: {}", response.url(), response.status());
            // 使用Playwright API获取真实的响应时间
            long responseTimeMs = 0;
            try {
                responseTimeMs = (long) response.request().timing().responseEnd;
                LoggingConfigUtil.logDebugIfVerbose(logger, "📊 Response timing for {}: {}ms", response.url(), responseTimeMs);
            } catch (Exception e) {
                logger.debug("Failed to get response timing for: {}", response.url());
            }

            // 调用监听器
            if (listener != null) {
                try {
                    listener.onResponse(response, response.request(), responseTimeMs);
                } catch (AssertionError e) {
                    // AssertionError 直接传播，让测试立即失败
                    throw e;
                } catch (Exception e) {
                    logger.error("Error executing response listener", e);
                }
            }
        });

        logger.info("✅ API monitoring started successfully for pattern: {} on Page", urlPattern);
    }

    /**
     * 监控所有API响应（针对Page）
     *
     * @param page Playwright Page对象
     */
    public static void monitorAllApi(Page page) {
        monitorApi(page, ".*");
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
     * @param urlPattern URL匹配模式（支持正则表达式）
     * @return 匹配的API调用记录列表
     */
    public static List<ApiCallRecord> getApiHistoryByUrl(String urlPattern) {
        Pattern pattern = Pattern.compile(urlPattern);
        return apiCallHistory.stream()
                .filter(record -> pattern.matcher(record.getUrl()).matches())
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
     * @param urlPattern URL匹配模式（支持正则表达式）
     * @return 最后一次匹配的API调用记录，如果没有则返回null
     */
    public static ApiCallRecord getLastApiCallByUrl(String urlPattern) {
        List<ApiCallRecord> calls = getApiHistoryByUrl(urlPattern);
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
     * 停止指定URL模式的监控
     *
     * 注意：由于 ResponseListener 接口不包含 URL 模式信息，此方法会停止该 Context 的所有监控。
     * 如需特定功能，请使用 stopMonitoringAfterApi() 方法。
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式（支持普通URL或正则表达式）
     */
    public static void stopMonitoring(BrowserContext context, String urlPattern) {
        logger.info("Stopping all monitoring for context (requested pattern: {})", urlPattern);

        Set<ResponseListener> listeners = contextListeners.get(context);
        if (listeners == null || listeners.isEmpty()) {
            logger.warn("No active monitoring for context");
            return;
        }

        // 由于无法区分监听器对应的URL模式，停止该 Context 的所有监控
        contextListeners.remove(context);
        logger.info("Stopped all monitoring for context");
    }

    /**
     * 在指定秒数后停止监控（企业级功能）
     *
     * 注意：此方法只是按时间停止，不会验证是否捕获到API。
     * 如需验证是否捕获到目标API，请使用 stopMonitoringAfterSeconds(context, seconds, urlPattern)
     *
     * @param context Playwright BrowserContext对象
     * @param seconds 秒数
     *
     * 示例：
     * startMonitoring(context, ".*api/.*");
     * // ... 执行操作
     * stopMonitoringAfterSeconds(context, 10);  // 10秒后停止
     * RealApiMonitor.logApiMonitoringResult();  // 记录结果到Serenity报告
     */
    public static void stopMonitoringAfterSeconds(BrowserContext context, int seconds) {
        stopMonitoringAfterSeconds(context, seconds, null);
    }

    /**
     * 在指定秒数后停止监控，并验证是否捕获到目标API（企业级功能）
     *
     * 行为：
     * 1. 如果在指定秒数内检测到目标API，立即停止监控
     * 2. 如果在指定秒数内没有检测到目标API，记录警告信息
     *
     * 注意：需要在主线程中调用 logApiMonitoringResult() 来记录结果到Serenity报告
     *
     * @param context Playwright BrowserContext对象
     * @param seconds 秒数
     * @param urlPattern 目标API的URL模式（支持正则），如果为null则只按时间停止
     *
     * 示例：
     * startMonitoring(context, ".*api/.*");
     * // ... 执行操作
     * stopMonitoringAfterSeconds(context, 10, ".*auth/login.*");  // 10秒内必须捕获到auth/login
     * RealApiMonitor.logApiMonitoringResult();  // 记录结果到Serenity报告
     */
    public static void stopMonitoringAfterSeconds(BrowserContext context, int seconds, String urlPattern) {
        if (urlPattern != null) {
            Pattern pattern = Pattern.compile(toRegexPattern(urlPattern));
            logger.info("Scheduled to stop monitoring after {} seconds (must capture API matching: {})", seconds, urlPattern);

            new Thread(() -> {
                try {
                    Thread.sleep(seconds * 1000L);

                    // 停止监控
                    stopMonitoring(context);

                    // 检查是否捕获到目标API
                    boolean found = apiCallHistory.stream()
                            .anyMatch(record -> pattern.matcher(record.getUrl()).matches());

                    if (found) {
                        logger.info("⏰ Time's up! Found target API matching: {}", urlPattern);
                        // 记录到Serenity报告：成功捕获API
                        String successMsg = String.format(
                            "✅ API Monitoring SUCCESS<br>" +
                            "Target API captured within %d seconds<br>" +
                            "Expected pattern: %s<br>" +
                            "Total APIs captured: %d",
                            seconds, urlPattern, apiCallHistory.size()
                        );
                        apiMonitorWarnings.add(successMsg);
                        logger.info(successMsg.replace("<br>", "\n"));
                        // 注意：不在后台线程中记录到Serenity报告
                    } else {
                        String warningMsg = String.format(
                            "❌ API Monitoring FAILED<br>" +
                            "Target API not detected within %d seconds!<br>" +
                            "Expected pattern: %s<br>" +
                            "Total APIs captured: %d",
                            seconds, urlPattern, apiCallHistory.size()
                        );
                        logger.warn("⚠️ API Monitor Warning: {}", warningMsg.replace("<br>", "\n"));
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
                    logger.info("⏰ Time's up! Stopping monitoring after {} seconds...", seconds);

                    // 停止监控
                    stopMonitoring(context);

                        // 如果有配置的期望API，检查是否捕获到
                    if (!expectationPatterns.isEmpty()) {
                        boolean foundTargetApi = false;
                        for (String pattern : expectationPatterns) {
                            Pattern regex = Pattern.compile(pattern);
                            for (int i = initialApiCount; i < apiCallHistory.size(); i++) {
                                ApiCallRecord record = apiCallHistory.get(i);
                                if (regex.matcher(record.getUrl()).matches()) {
                                    foundTargetApi = true;
                                    logger.info("✅ Found expected API matching: {}", pattern);
                                    break;
                                }
                            }
                            if (foundTargetApi) break;
                        }

                        if (foundTargetApi) {
                            // 记录到Serenity报告：成功捕获API
                            String successMsg = String.format(
                                "✅ API Monitoring SUCCESS<br>" +
                                "Expected APIs captured within %d seconds<br>" +
                                "Expected patterns: %s<br>" +
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
                                "❌ API Monitoring FAILED<br>" +
                                "No expected API captured within %d seconds!<br>" +
                                "Expected patterns: %s<br>" +
                                "Initial API count: %d<br>" +
                                "Final API count: %d<br>" +
                                "New APIs captured: %d",
                                seconds, expectationPatterns, initialApiCount, apiCallHistory.size(),
                                apiCallHistory.size() - initialApiCount
                            );
                            logger.warn("⚠️ API Monitor Warning: {}", warningMsg.replace("<br>", "\n"));
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
     * 检测到指定API后停止监控（企业级功能）
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式
     * @param expectedCount 期望检测到的API调用次数
     *
     * 示例：
     * startMonitoring(context, ".*api/.*");
     * // ... 执行操作
     * stopMonitoringAfterApi(context, ".*auth/login.*", 1);  // 检测到1次登录API后停止
     */
    public static void stopMonitoringAfterApi(BrowserContext context, String urlPattern, int expectedCount) {
        stopMonitoringAfterApi(context, urlPattern, expectedCount, 0);
    }

    /**
     * 检测到指定API后停止监控，并支持超时验证（企业级功能）
     *
     * 行为：
     * 1. 如果在指定秒数内检测到目标API，立即停止监控
     * 2. 如果在指定秒数内没有检测到目标API，记录警告信息
     * 3. 如果 timeoutSeconds 为 0，则不设置超时，只检测API
     *
     * 注意：需要在主线程中调用 logApiMonitoringResult() 来记录结果到Serenity报告
     *
     * @param context Playwright BrowserContext对象
     * @param urlPattern URL匹配模式
     * @param expectedCount 期望检测到的API调用次数
     * @param timeoutSeconds 超时秒数（0表示不设置超时）
     *
     * 示例：
     * startMonitoring(context, ".*api/.*");
     * // ... 执行操作
     * // 10秒内必须检测到1次登录API
     * stopMonitoringAfterApi(context, ".*auth/login.*", 1, 10);
     * RealApiMonitor.logApiMonitoringResult();  // 记录结果到Serenity报告
     */
    public static void stopMonitoringAfterApi(BrowserContext context, String urlPattern, int expectedCount, int timeoutSeconds) {
        String pattern = toRegexPattern(urlPattern);
        logger.info("Will stop monitoring after detecting {} API(s) matching pattern: {}", expectedCount, pattern);
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
            public void onResponse(Response response, Request request, long responseTimeMs) {
                if (shouldStop[0]) {
                    return;
                }

                boolean matches = Pattern.compile(pattern).matcher(response.url()).matches();
                if (matches) {
                    synchronized (initialCount) {
                        detectedCount++;
                        initialCount[0]++;
                        logger.info("🎯 Detected target API #{}: {} - Status: {}",
                                detectedCount, response.url(), response.status());

                        if (detectedCount >= expectedCount) {
                            shouldStop[0] = true;
                            detectedWithinTimeout[0] = true;
                            logger.info("✅ Target API detected {} times, stopping monitoring...", detectedCount);
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
                            "❌ API Monitoring FAILED<br>" +
                            "Target API not detected within %d seconds!<br>" +
                            "Expected pattern: %s<br>" +
                            "Expected count: %d<br>" +
                            "Total APIs captured: %d",
                            timeoutSeconds, urlPattern, expectedCount, apiCallHistory.size()
                        );
                        logger.warn("⚠️ API Monitor Warning: {}", warningMsg.replace("<br>", "\n"));
                        apiMonitorWarnings.add(warningMsg);
                        
                        // 设置失败异常，主线程下一个操作时会自动检查并抛出
                        String errorMsg = String.format(
                            "API Monitoring Failed!\n" +
                            "Target API not detected within %d seconds.\n" +
                            "Expected pattern: %s\n" +
                            "Expected count: %d",
                            timeoutSeconds, urlPattern, expectedCount
                        );
                        monitoringFailure = new AssertionError(errorMsg);
                    } else {
                        // 记录到Serenity报告：成功检测到API
                        String successMsg = String.format(
                            "✅ API Monitoring SUCCESS<br>" +
                            "Target API detected within %d seconds<br>" +
                            "Expected pattern: %s<br>" +
                            "Expected count: %d<br>" +
                            "Total APIs captured: %d",
                            timeoutSeconds, urlPattern, expectedCount, apiCallHistory.size()
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
        logger.info("✅ All monitoring stopped");
    }
    
    /**
     * 打印所有捕获到的API（仅用于调试）
     */
    public static void printAllCapturedApis() {
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
     * 这个方法会检查是否捕获到了期望的API，并记录结果到Serenity报告
     * 
     * 建议在测试步骤结束时调用此方法，确保监控结果被记录到报告中
     * 
     * 示例：
     * RealApiMonitor.with(context)
     *     .monitorApi(".*auth/login.*", 200)
     *     .stopAfterSeconds(3)
     *     .build();
     * // ... 执行测试操作 ...
     * RealApiMonitor.logApiMonitoringResult();  // 记录结果到Serenity报告
     */
    public static void logApiMonitoringResult() {
        logApiMonitoringResult(false);
    }

    /**
     * 【推荐】断言API监控结果，如果失败则抛出异常
     * 这个方法会检查是否捕获到了期望的API，并记录结果到Serenity报告
     * 如果API监控失败（未捕获到期望API），会抛出AssertionError
     * 
     * 适用于测试exception场景，确保API在指定时间内被调用
     * 
     * 示例：
     * RealApiMonitor.with(context)
     *     .monitorApi(".*auth/login.*", 200)
     *     .stopAfterSeconds(10)
     *     .build();
     * // ... 执行测试操作 ...
     * RealApiMonitor.assertThatApiMonitoring();  // 断言API监控结果，失败则抛出异常
     * 
     * @throws AssertionError 如果API监控失败（未捕获到期望API）
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
            if (apiCallHistory.stream().anyMatch(record -> regex.matcher(record.getUrl()).matches())) {
                foundExpected = true;
                logger.info("✅ Found expected API matching pattern: {}", pattern);
                break;
            }
        }

        // 生成结果消息
        String resultMsg;
        if (foundExpected) {
            // 成功捕获期望API
            resultMsg = String.format(
                "✅ API Monitoring SUCCESS<br>" +
                "Expected APIs were captured<br>" +
                "Expected patterns: %s<br>" +
                "Total APIs captured: %d",
                apiExpectations.keySet(), apiCallHistory.size()
            );
            apiMonitorWarnings.add(resultMsg);
            logger.info("✅ API Monitoring SUCCESS - Expected APIs captured");
        } else {
            // 未捕获期望API - 检查是否已有失败消息（避免重复记录）
            boolean alreadyHasFailure = apiMonitorWarnings.stream()
                    .anyMatch(w -> w.contains("❌ API Monitoring FAILED"));
            
            if (!alreadyHasFailure) {
                resultMsg = String.format(
                    "❌ API Monitoring FAILED<br>" +
                    "No expected API captured<br>" +
                    "Expected patterns: %s<br>" +
                    "Total APIs captured: %d",
                    apiExpectations.keySet(), apiCallHistory.size()
                );
                logger.warn("⚠️ API Monitor Warning: {}", resultMsg.replace("<br>", "\n"));
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
            logger.error(errorMsg);
            throw new AssertionError(errorMsg);
        }
    }

    /**
     * 记录API监控警告到Serenity报告（公共方法）
     * 用户可以在任何时候调用此方法来记录警告
     */
    public static void recordApiMonitorWarningsToReport() {
        recordApiMonitorWarnings();
    }

    /**
     * 打印API调用历史摘要
     */
    public static void printApiHistorySummary() {
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
     * @param urlPattern URL匹配模式（支持正则表达式）
     * @param expectedStatusCode 期望的状态码
     */
    public static void expectApiStatus(String urlPattern, int expectedStatusCode) {
        apiExpectations.put(urlPattern, ApiExpectation.forUrl(urlPattern).statusCode(expectedStatusCode));
        logger.info("Added API expectation: {} -> {}", urlPattern, expectedStatusCode);
    }

    /**
     * 批量设置API期望状态码（简单版本）
     *
     * @param expectations URL模式 -> 期望状态码的映射
     */
    public static void expectMultipleApiStatus(Map<String, Integer> expectations) {
        for (Map.Entry<String, Integer> entry : expectations.entrySet()) {
            apiExpectations.put(entry.getKey(), ApiExpectation.forUrl(entry.getKey()).statusCode(entry.getValue()));
        }
        logger.info("Added {} API expectations", expectations.size());
    }

    /**
     * 设置API期望（高级版本，支持多维度验证）
     *
     * @param expectation API期望对象
     */
    public static void expectApi(ApiExpectation expectation) {
        apiExpectations.put(expectation.getUrlPattern(), expectation);
        logger.info("Added API expectation: {} -> {}", expectation.getUrlPattern(), expectation.getDescription());
    }

    /**
     * 批量设置API期望（高级版本）
     *
     * @param expectations API期望对象列表
     */
    public static void expectMultipleApi(List<ApiExpectation> expectations) {
        for (ApiExpectation expectation : expectations) {
            apiExpectations.put(expectation.getUrlPattern(), expectation);
        }
        logger.info("Added {} API expectations", expectations.size());
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
            String urlPattern = entry.getKey();
            ApiExpectation expectation = entry.getValue();

            // 检查URL是否匹配模式
            try {
                Pattern pattern = Pattern.compile(urlPattern);
                if (pattern.matcher(record.getUrl()).matches()) {
                    // 找到匹配的期望，进行多维度验证
                    expectation.validate(record);
                    // 找到匹配后立即返回
                    return;
                }
            } catch (Exception e) {
                logger.warn("Failed to match URL pattern: {}", urlPattern, e);
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
                    json.append("      \"urlPattern\": \"").append(escapeJson(expectation.getUrlPattern())).append("\",\n");
                    json.append("      \"expectedStatusCode\": ").append(expectation.expectedStatusCode != null ? expectation.expectedStatusCode : "\"Any\"").append(",\n");
                    json.append("      \"responseValidation\": \"").append(escapeJson(expectation.getDescription())).append("\"\n");
                    json.append("    }").append(index <= apiExpectations.size() ? "," : "").append("\n");
                }
                json.append("  ]\n");
            }
            json.append("}\n");

            Serenity.recordReportData().withTitle("API Monitor Configuration").andContents(json.toString());
            logger.info("✅ Recorded API monitoring configuration to Serenity report");
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
            long successCount = apiMonitorWarnings.stream().filter(w -> w.contains("✅ API Monitoring SUCCESS")).count();
            long failCount = apiMonitorWarnings.stream().filter(w -> w.contains("❌ API Monitoring FAILED")).count();
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
                boolean isSuccess = msg.contains("✅ API Monitoring SUCCESS");
                boolean isFailure = msg.contains("❌ API Monitoring FAILED");
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
                    json.append("      \"responseTimeMs\": ").append(record.getResponseTimeMs()).append(",\n");
                    json.append("      \"matched\": ").append(matched).append("\n");
                    json.append("    }").append(i < apiCallHistory.size() - 1 ? "," : "").append("\n");
                }
            }
            json.append("  ]\n");
            json.append("}\n");

            Serenity.recordReportData().withTitle("API Monitor Results").andContents(json.toString());
            logger.info("✅ Recorded API monitor results to Serenity report");

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
                    json.append("      \"statusCode\": ").append(record.getStatusCode()).append(",\n");
                    json.append("      \"responseTimeMs\": ").append(record.getResponseTimeMs()).append("\n");
                    json.append("    }").append(i < apiCallHistory.size() - 1 ? "," : "").append("\n");
                }
                json.append("  ]\n");
            }
            json.append("}\n");

            Serenity.recordReportData().withTitle("API Call Summary").andContents(json.toString());
            logger.info("✅ Recorded API call summary to Serenity report");
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
                    json.append("      \"pattern\": \"").append(escapeJson(expectation.getUrlPattern())).append("\",\n");
                    json.append("      \"expectation\": \"").append(escapeJson(expectation.getDescription())).append("\",\n");
                    json.append("      \"status\": \"").append(found ? "MATCHED" : "NOT_MATCHED").append("\",\n");

                    if (found && matchedRecord != null) {
                        json.append("      \"matched\": {\n");
                        json.append("        \"type\": \"").append(matchedRecord.isMocked() ? "Mock" : "Real").append("\",\n");
                        json.append("        \"actualUrl\": \"").append(escapeJson(matchedRecord.getUrl())).append("\",\n");
                        json.append("        \"statusCode\": ").append(matchedRecord.getStatusCode()).append(",\n");
                        json.append("        \"responseTimeMs\": ").append(matchedRecord.getResponseTimeMs()).append("\n");
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
            logger.info("✅ Recorded API validation results to Serenity report");
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
                logger.info("  📡 Target API: {} - {}", expectation.getUrlPattern(), expectation.getDescription());
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
                logger.info("#{} {} {} - Status: {} - Time: {}ms",
                        i + 1, record.getMethod(), record.getUrl(), 
                        record.getStatusCode(), record.getResponseTimeMs());
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
        logger.info("🎯 Target APIs configured:");
        if (apiExpectations.isEmpty()) {
            logger.info("  - No specific targets (monitoring all APIs)");
        } else {
            for (Map.Entry<String, ApiExpectation> entry : apiExpectations.entrySet()) {
                ApiExpectation expectation = entry.getValue();
                logger.info("  - Pattern: {} | Expectation: {}", 
                        expectation.getUrlPattern(), expectation.getDescription());
            }
        }
        
        // 显示实际捕获的API
        logger.info("📊 Actual APIs captured:");
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
                    logger.info("  - URL: {} | Count: {} | Last Status: {} | Avg Time: {}ms",
                            url, count, sample.getStatusCode(), 
                            apiCallHistory.stream()
                                    .filter(r -> r.getUrl().equals(url))
                                    .mapToLong(ApiCallRecord::getResponseTimeMs)
                                    .average()
                                    .orElse(0));
                }
            }
        }
        
        // 验证目标API是否被捕获
        if (!apiExpectations.isEmpty() && !apiCallHistory.isEmpty()) {
            logger.info("✓ Validation Results:");
            for (Map.Entry<String, ApiExpectation> entry : apiExpectations.entrySet()) {
                String pattern = entry.getKey();
                ApiExpectation expectation = entry.getValue();
                
                boolean found = false;
                for (ApiCallRecord record : apiCallHistory) {
                    try {
                        Pattern p = Pattern.compile(pattern);
                        if (p.matcher(record.getUrl()).matches()) {
                            found = true;
                            logger.info("  ✓ Target matched: {} -> Captured: {} {} - Status: {}",
                                    expectation.getUrlPattern(),
                                    record.getMethod(), record.getUrl(), record.getStatusCode());
                            break;
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
                
                if (!found) {
                    logger.warn("  ⚠ Target NOT matched: {} - No matching API calls found",
                            expectation.getUrlPattern());
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
     *
     * 示例用法（简单验证）：
     * RealApiMonitor.with(context)
     *     .monitorApi(".*auth/login.*", 200)
     *     .monitorApi(".*api/users.*", 200)
     *     .build();
     *
     * 示例用法（多维度验证）：
     * RealApiMonitor.with(context)
     *     .expectApi(ApiExpectation.forUrl(".*auth/login.*")
     *         .statusCode(200)
     *         .responseTimeLessThan(1000))
     *     .expectApi(ApiExpectation.forUrl(".*api/users.*")
     *         .statusCode(200)
     *         .responseBodyContains("data"))
     *     .build();
     */
    public static class ApiMonitorBuilder {
        private final BrowserContext context;
        private final Page page;
        private final Map<String, ApiExpectation> apiExpectations = new HashMap<>();
        private boolean autoClearHistory = true;
        private Integer stopAfterSeconds = null;  // 在指定秒数后停止
        private Map<String, Integer> stopAfterApiMap = new HashMap<>();  // 检测到指定API后停止 (URL -> expectedCount)
        private Map<String, Integer> stopAfterApiTimeoutMap = new HashMap<>();  // API超时设置 (URL -> timeoutSeconds)

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
         * @param urlPattern URL匹配模式（支持普通URL或正则）
         * @param expectedStatusCode 期望的状态码
         * @return this构建器实例
         */
        public ApiMonitorBuilder monitorApi(String urlPattern, int expectedStatusCode) {
            String pattern = toRegexPattern(urlPattern);
            apiExpectations.put(pattern, ApiExpectation.forUrl(pattern).statusCode(expectedStatusCode));
            return this;
        }

        /**
         * 添加要监控的API及其完整期望（高级版本）
         *
         * @param expectation API期望对象
         * @return this构建器实例
         */
        public ApiMonitorBuilder expectApi(ApiExpectation expectation) {
            apiExpectations.put(expectation.getUrlPattern(), expectation);
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
                String pattern = toRegexPattern(entry.getKey());
                apiExpectations.put(pattern, ApiExpectation.forUrl(pattern).statusCode(entry.getValue()));
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
         * 在指定秒数后停止监控（企业级功能）
         *
         * 注意：需要在主线程中调用 logApiMonitoringResult() 来记录结果到Serenity报告
         *
         * @param seconds 秒数
         * @return this构建器实例
         *
         * 示例：
         * RealApiMonitor.with(context)
         *     .monitorApi(".*api/.*", 200)
         *     .stopAfterSeconds(10)  // 10秒后自动停止
         *     .build();
         * RealApiMonitor.logApiMonitoringResult();  // 记录结果到Serenity报告
         */
        public ApiMonitorBuilder stopAfterSeconds(int seconds) {
            this.stopAfterSeconds = seconds;
            return this;
        }

        /**
         * 检测到指定API后停止监控（企业级功能）
         *
         * @param urlPattern URL匹配模式（支持普通URL或正则表达式）
         * @param expectedCount 期望检测到的API调用次数
         * @return this构建器实例
         *
         * 示例：
         * RealApiMonitor.with(context)
         *     .monitorApi(".*api/.*", 200)
         *     .stopAfterApi(".*auth/login.*", 1)  // 检测到1次登录API后停止
         *     .build();
         */
        public ApiMonitorBuilder stopAfterApi(String urlPattern, int expectedCount) {
            String pattern = toRegexPattern(urlPattern);
            stopAfterApiMap.put(pattern, expectedCount);
            return this;
        }

        /**
         * 检测到指定API后停止监控，并设置超时验证（企业级功能）
         *
         * 注意：需要在主线程中调用 logApiMonitoringResult() 来记录结果到Serenity报告
         *
         * 行为：
         * 1. 如果在指定秒数内检测到目标API，立即停止监控
         * 2. 如果在指定秒数内没有检测到目标API，记录警告信息
         * 3. 如果 timeoutSeconds 为 0，则不设置超时，只检测API
         *
         * @param urlPattern URL匹配模式（支持普通URL或正则）
         * @param expectedCount 期望检测到的API调用次数
         * @param timeoutSeconds 超时秒数（0表示不设置超时）
         * @return this构建器实例
         *
         * 示例：
         * RealApiMonitor.with(context)
         *     .monitorApi(".*auth/login.*", 200)
         *     .stopAfterApi(".*auth/login.*", 1, 10)  // 10秒内必须检测到1次登录API
         *     .build();
         * RealApiMonitor.logApiMonitoringResult();  // 记录结果到Serenity报告
         */
        public ApiMonitorBuilder stopAfterApi(String urlPattern, int expectedCount, int timeoutSeconds) {
            String pattern = toRegexPattern(urlPattern);
            stopAfterApiMap.put(pattern, expectedCount);
            stopAfterApiTimeoutMap.put(pattern, timeoutSeconds);
            return this;
        }

        /**
         * 构建并启动监控
         */
        public void build() {
            logger.info("========== Building API Monitor ==========");
            logger.info("Total APIs to monitor: {}", apiExpectations.size());
            
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
                        logger.info("⏰ Time's up! Auto-stopping monitoring after {} seconds...", stopAfterSeconds);

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
                                    logger.info("✅ Found expected API matching: {}", pattern);
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
                                    "✅ API Monitoring SUCCESS<br>" +
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
                                    "❌ API Monitoring FAILED<br>" +
                                    "No expected API captured within %d seconds!<br>" +
                                    "Expected patterns: %s<br>" +
                                    "Initial API count: %d<br>" +
                                    "Final API count: %d<br>" +
                                    "New APIs captured: %d",
                                    stopAfterSeconds, targetPatterns, initialApiCount, apiCallHistory.size(),
                                    apiCallHistory.size() - initialApiCount
                                );
                                logger.warn("⚠️ API Monitor Warning: {}", warningMsg.replace("<br>", "\n"));
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
                logger.info("🎯 Will stop monitoring after detecting target APIs:");
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

            logger.info("✅ API Monitor built successfully!");

            // 自动记录到Serenity报告
            RealApiMonitor.recordMonitoredApiTargets();
        }
    }

    // ==================== API Expectation ====================

    /**
     * API期望类 - 支持多维度验证
     *
     * 示例用法：
     * ApiExpectation.forUrl(".*auth/login.*")
     *     .statusCode(200)
     *     .responseTimeLessThan(1000)
     *     .responseBodyContains("token")  // 部分匹配
     *     .responseHeaderContains("Content-Type", "application/json");
     *
     * 完整response内容验证：
     * ApiExpectation.forUrl(".*auth/login.*")
     *     .responseBodyEquals("{\"status\":\"success\",\"token\":\"abc123\"}")  // 完全匹配
     *     .responseBodyMatches(".*\"token\":\"[^\"]+\".*")  // 正则匹配
     */
    public static class ApiExpectation {
        private final String urlPattern;
        private Integer expectedStatusCode;
        private Long maxResponseTime;
        private String expectedResponseBodyContent;  // 部分匹配
        private String expectedResponseBodyExact;     // 完全匹配
        private String expectedResponseBodyRegex;     // 正则匹配
        private String expectedResponseHeaderName;
        private String expectedResponseHeaderValue;

        private ApiExpectation(String urlPattern) {
            this.urlPattern = urlPattern;
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
         * 设置期望的最大响应时间
         *
         * @param maxTimeMs 最大响应时间（毫秒）
         * @return this
         */
        public ApiExpectation responseTimeLessThan(long maxTimeMs) {
            this.maxResponseTime = maxTimeMs;
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
         *
         * @param regex 正则表达式
         * @return this
         *
         * 示例：
         * ApiExpectation.forUrl(".*auth/login.*")
         *     .responseBodyMatches(".*\"token\":\"[^\"]+\".*");
         */
        public ApiExpectation responseBodyMatches(String regex) {
            this.expectedResponseBodyRegex = regex;
            return this;
        }

        /**
         * 获取URL模式
         */
        public String getUrlPattern() {
            return urlPattern;
        }

        /**
         * 获取期望描述
         */
        public String getDescription() {
            StringBuilder desc = new StringBuilder();
            if (expectedStatusCode != null) {
                desc.append("Status=").append(expectedStatusCode);
            }
            if (maxResponseTime != null) {
                if (desc.length() > 0) desc.append(", ");
                desc.append("Time<").append(maxResponseTime).append("ms");
            }
            if (expectedResponseBodyContent != null) {
                if (desc.length() > 0) desc.append(", ");
                desc.append("Body contains '").append(expectedResponseBodyContent).append("'");
            }
            if (expectedResponseBodyExact != null) {
                if (desc.length() > 0) desc.append(", ");
                desc.append("Body equals '").append(truncate(expectedResponseBodyExact, 50)).append("'");
            }
            if (expectedResponseBodyRegex != null) {
                if (desc.length() > 0) desc.append(", ");
                desc.append("Body matches '").append(expectedResponseBodyRegex).append("'");
            }
            if (expectedResponseHeaderName != null) {
                if (desc.length() > 0) desc.append(", ");
                desc.append("Header[").append(expectedResponseHeaderName).append("] contains '").append(expectedResponseHeaderValue).append("'");
            }
            return desc.length() > 0 ? desc.toString() : "No validation";
        }

        /**
         * 截断字符串
         */
        private String truncate(String str, int maxLength) {
            if (str == null) return null;
            return str.length() > maxLength ? str.substring(0, maxLength) + "..." : str;
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
            if (expectedStatusCode != null && record.getStatusCode() != expectedStatusCode) {
                failures.add(String.format(
                    "Status Code Mismatch: Expected %d, Actual %d",
                    expectedStatusCode, record.getStatusCode()
                ));
            }

            // 验证响应时间
            if (maxResponseTime != null && record.getResponseTimeMs() > maxResponseTime) {
                failures.add(String.format(
                    "Response Time Exceeded: Expected <%dms, Actual %dms",
                    maxResponseTime, record.getResponseTimeMs()
                ));
            }

            // 验证响应体内容
            if (expectedResponseBodyContent != null) {
                String responseBody = String.valueOf(record.getResponseBody());
                if (responseBody == null || !responseBody.contains(expectedResponseBodyContent)) {
                    failures.add(String.format(
                        "Response Body Does Not Contain: Expected '%s' in response",
                        expectedResponseBodyContent
                    ));
                }
            }

            // 验证完整响应体（完全匹配）
            if (expectedResponseBodyExact != null) {
                String responseBody = String.valueOf(record.getResponseBody());
                if (responseBody == null || !responseBody.equals(expectedResponseBodyExact)) {
                    failures.add(String.format(
                        "Response Body Mismatch (Exact Match):%nExpected: %s%nActual: %s",
                        expectedResponseBodyExact,
                        responseBody
                    ));
                }
            }

            // 验证响应体正则匹配
            if (expectedResponseBodyRegex != null) {
                String responseBody = String.valueOf(record.getResponseBody());
                if (responseBody == null || !Pattern.matches(expectedResponseBodyRegex, responseBody)) {
                    failures.add(String.format(
                        "Response Body Does Not Match Pattern: Expected pattern '%s'%nActual: %s",
                        expectedResponseBodyRegex,
                        responseBody
                    ));
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
                logger.error(errorMsg);
                throw new AssertionError(errorMsg);
            }

            // 验证通过
            logger.info("✅ API monitoring PASSED! URL: {}, Method: {}, Status: {}, Time: {}ms - ({})",
                    record.getUrl(),
                    record.getMethod(),
                    record.getStatusCode(),
                    record.getResponseTimeMs(),
                    getDescription());
        }
    }
}
