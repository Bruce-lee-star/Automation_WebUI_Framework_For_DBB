package com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import net.serenitybdd.core.Serenity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.net.URL;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Real API Monitor - 极简 API 监控工具（非阻塞）
 * 
 * 这是一个辅助工具，用于在 web 测试过程中监控 API 调用。
 * 所有方法都是非阻塞的，不会影响测试流程。
 * 
 * 详细使用说明请参考 API_MONITOR_README.md
 */
public class RealApiMonitor {

    private static final Logger logger = LoggerFactory.getLogger(RealApiMonitor.class);

    // 存储 API 调用记录
    private static final List<ApiCallRecord> apiCallHistory = new CopyOnWriteArrayList<>();
    
    // 存储 API 期望
    private static final Map<String, ApiExpectation> apiExpectations = new ConcurrentHashMap<>();
    
    // 监控状态
    private static final Map<BrowserContext, Boolean> contextMonitoringStopped = new ConcurrentHashMap<>();
    private static final Map<Page, Boolean> pageMonitoringStopped = new ConcurrentHashMap<>();
    
    // 记录标志 - 防止重复记录
    private static volatile boolean hasLoggedToSerenity = false;
    
    // 目标 Host 过滤
    private static volatile String targetHost = null;
    
    // 监控失败异常
    private static volatile AssertionError monitoringFailure = null;
    
    // 目标 API 匹配计数（用于自动停止）
    private static final AtomicInteger matchedTargetApiCount = new AtomicInteger(0);
    
    // 是否已捕获到所有目标 API
    private static volatile boolean allTargetApisCaptured = false;
    
    // 目标 API 的 URL 模式列表（用于判断是否是目标 API）
    private static final List<String> targetApiPatterns = new CopyOnWriteArrayList<>();

    // ==================== 核心方法: monitor() - 异步监控 ====================
    
    /**
     * 异步监控 API（非阻塞）
     * 
     * @param context BrowserContext
     * @return MonitorBuilder 构建器
     * 
     * 示例：
     * // 在执行操作前启动监控
     * RealApiMonitor.monitor(context)
     *     .api("/api/login", 200)
     *     .api("/api/user", 200)
     *     .timeout(15)
     *     .start();  // 异步启动，立即返回
     * 
     * // 执行操作，API 会自动被捕获
     * loginButton.click();
     * 
     * // 查询捕获的 API
     * ApiCallRecord record = RealApiMonitor.getLast("/api/login");
     */
    public static MonitorBuilder monitor(BrowserContext context) {
        return new MonitorBuilder(context, null);
    }
    
    /**
     * 异步监控 API - Page 版本（非阻塞）
     */
    public static MonitorBuilder monitor(Page page) {
        return new MonitorBuilder(null, page);
    }
    
    /**
     * 监控构建器
     */
    public static class MonitorBuilder {
        private final BrowserContext context;
        private final Page page;
        private final Map<String, Integer> apis = new LinkedHashMap<>();
        private int timeoutSeconds = 60;
        
        public MonitorBuilder(BrowserContext context, Page page) {
            this.context = context;
            this.page = page;
        }
        
        /**
         * 添加要监控的 API
         * @param urlPattern URL 匹配模式
         * @param expectedStatus 期望状态码
         */
        public MonitorBuilder api(String urlPattern, int expectedStatus) {
            apis.put(urlPattern, expectedStatus);
            return this;
        }
        
        /**
         * 添加要监控的 API（不验证状态码）
         */
        public MonitorBuilder api(String urlPattern) {
            apis.put(urlPattern, 0);
            return this;
        }
        
        /**
         * 设置超时时间（秒）
         */
        public MonitorBuilder timeout(int seconds) {
            this.timeoutSeconds = seconds;
            return this;
        }
        
        /**
         * 异步启动监控（非阻塞）
         */
        public void start() {
            logger.info("========== Starting async API monitoring ({} target APIs, timeout: {}s) ==========", 
                apis.size(), timeoutSeconds);
            
            clearHistory();
            clearExpectations();
            
            // 保存目标 API 模式（用于后续过滤）
            targetApiPatterns.clear();
            matchedTargetApiCount.set(0);
            allTargetApisCaptured = false;
            
            // 设置期望
            for (Map.Entry<String, Integer> entry : apis.entrySet()) {
                String urlPattern = entry.getKey();
                String regex = toRegex(urlPattern);
                targetApiPatterns.add(urlPattern); // 保存原始模式
                if (entry.getValue() > 0) {
                    apiExpectations.put(regex, ApiExpectation.forEndpoint(regex).statusCode(entry.getValue()));
                }
                logger.info("  - Target API: {} -> Expected Status: {}", urlPattern, entry.getValue() > 0 ? entry.getValue() : "any");
            }
            
            // 启动监听
            startListening();
            
            // 启动自动停止定时器
            startAutoStopTimer();
        }
        
        private void startListening() {
            if (context != null) {
                contextMonitoringStopped.put(context, false);
                context.onResponse(response -> {
                    if (contextMonitoringStopped.getOrDefault(context, false)) return;
                    if (!matchesTargetHost(response.url())) return;
                    if (isStaticResource(response.url())) return;
                    
                    recordApiCall(response, response.request());
                    
                    // 实时验证
                    validateRealTime(response);
                });
            } else if (page != null) {
                pageMonitoringStopped.put(page, false);
                page.onResponse(response -> {
                    if (pageMonitoringStopped.getOrDefault(page, false)) return;
                    if (!matchesTargetHost(response.url())) return;
                    if (isStaticResource(response.url())) return;
                    
                    recordApiCall(response, response.request());
                    
                    // 实时验证
                    validateRealTime(response);
                });
            }
        }
        
        private void startAutoStopTimer() {
            Thread timer = new Thread(() -> {
                int lastSize = apiCallHistory.size();
                long lastUpdate = System.currentTimeMillis();
                
                logger.info("Auto-stop timer started: timeout={}s, initial APIs={}", timeoutSeconds, lastSize);
                
                while (true) {
                    try {
                        Thread.sleep(1000);
                        
                        // 检查是否已被手动停止
                        boolean alreadyStopped = false;
                        if (context != null) {
                            alreadyStopped = contextMonitoringStopped.getOrDefault(context, false);
                        } else if (page != null) {
                            alreadyStopped = pageMonitoringStopped.getOrDefault(page, false);
                        }
                        if (alreadyStopped) {
                            logger.debug("Monitoring already stopped manually, exiting auto-stop timer");
                            break;
                        }
                        
                        int currentSize = apiCallHistory.size();
                        if (currentSize != lastSize) {
                            lastSize = currentSize;
                            lastUpdate = System.currentTimeMillis();
                            if ((lastSize % 5 == 0) || lastSize <= 5) {
                                logger.debug("API count changed: {} (elapsed: {}s since last activity)", 
                                    lastSize, (System.currentTimeMillis() - lastUpdate) / 1000);
                            }
                        } else {
                            long elapsed = (System.currentTimeMillis() - lastUpdate) / 1000;
                            long remaining = timeoutSeconds - elapsed;
                            if (remaining > 0 && remaining <= 5) {
                                logger.info("Auto-stop in {}s... (no new API calls, total: {})", remaining, currentSize);
                            }
                            if (elapsed >= timeoutSeconds) {
                                logger.warn("TIMEOUT: No API calls for {}s. Auto-stopping. Total APIs captured: {}", 
                                    timeoutSeconds, currentSize);
                                stopMonitoring();
                                break;
                            }
                        }
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }, "ApiMonitor-AutoStop");
            timer.setDaemon(true);
            timer.start();
        }
        
        private void validateRealTime(Response response) {
            String url = response.url();
            for (Map.Entry<String, ApiExpectation> entry : apiExpectations.entrySet()) {
                if (url.matches(entry.getKey()) || url.contains(entry.getKey().replaceAll("\\.\\*", ""))) {
                    ApiCallRecord record = getLastApiCall();
                    if (record != null) {
                        try {
                            entry.getValue().validate(record);
                        } catch (AssertionError e) {
                            logger.error("API validation failed: {}", e.getMessage());
                        }
                    }
                    break;
                }
            }
        }
    }

    // ==================== 核心方法 2: getLast() - 获取最后记录 ====================
    
    /**
     * 获取指定 API 的最后一条记录
     * 
     * @param urlPattern URL 匹配模式
     * @return API 调用记录，没有则返回 null
     */
    public static ApiCallRecord getLast(String urlPattern) {
        String regex = toRegex(urlPattern);
        for (int i = apiCallHistory.size() - 1; i >= 0; i--) {
            ApiCallRecord record = apiCallHistory.get(i);
            if (record.getUrl().matches(regex) || record.getUrl().contains(urlPattern)) {
                return record;
            }
        }
        return null;
    }

    // ==================== 核心方法 3: getLastBody() - 获取响应体 ====================
    
    /**
     * 获取指定 API 的最后响应体
     * 
     * @param urlPattern URL 匹配模式
     * @return 响应体字符串，没有则返回 null
     */
    public static String getLastBody(String urlPattern) {
        ApiCallRecord record = getLast(urlPattern);
        return record != null && record.getResponseBody() != null 
            ? String.valueOf(record.getResponseBody()) 
            : null;
    }
    
    // ==================== 核心方法 4: getHistory() - 获取所有记录 ====================
    
    /**
     * 获取所有 API 历史记录
     * 
     * @return 不可修改的 API 记录列表
     */
    public static List<ApiCallRecord> getHistory() {
        return Collections.unmodifiableList(apiCallHistory);
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 清空历史记录
     */
    public static void clearHistory() {
        apiCallHistory.clear();
        hasLoggedToSerenity = false;
        // 同时清除监控状态，确保新的监听器可以正常工作
        contextMonitoringStopped.clear();
        pageMonitoringStopped.clear();
        // 重置目标 API 追踪状态
        matchedTargetApiCount.set(0);
        allTargetApisCaptured = false;
        targetApiPatterns.clear();
    }
    
    /**
     * 清空期望
     */
    public static void clearExpectations() {
        apiExpectations.clear();
    }
    
    /**
     * 设置目标 Host（只监控指定 host 的 API）
     */
    public static void setTargetHost(String host) {
        targetHost = extractHost(host);
        logger.info("Target host set to: {}", targetHost);
    }
    
    /**
     * 清除目标 Host
     */
    public static void clearTargetHost() {
        targetHost = null;
    }
    
    /**
     * 从 URL 中提取 Host
     */
    private static String extractHost(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            URL urlObj = URI.create(url).toURL();
            return urlObj.getHost();
        } catch (Exception e) {
            int start = url.indexOf("://");
            if (start > 0) {
                String after = url.substring(start + 3);
                int end = after.indexOf("/");
                return end > 0 ? after.substring(0, end) : after;
            }
            return null;
        }
    }
    
    // 停止监控的锁
    private static final Object stopMonitorLock = new Object();
    
    /**
     * 停止所有监控，并立即将结果记录到当前 step
     */
    public static void stopMonitoring() {
        String caller = Thread.currentThread().getName();
        logger.debug("stopMonitoring() called by thread: {}", caller);
        
        synchronized (stopMonitorLock) {
            for (BrowserContext ctx : new ArrayList<>(contextMonitoringStopped.keySet())) {
                contextMonitoringStopped.put(ctx, true);
            }
            for (Page p : new ArrayList<>(pageMonitoringStopped.keySet())) {
                pageMonitoringStopped.put(p, true);
            }
        }
        
        // 立即记录到 Serenity 报告（出现在当前 step，而非测试结束时）
        logResults();
        logger.info("All monitoring stopped & results logged (by {})", caller);
    }
    
    /**
     * 记录汇总到 Serenity 报告（只报告目标 API）
     */
    private static void logSummaryToSerenityReport() {
        if (apiCallHistory.isEmpty() || hasLoggedToSerenity) return;
        
        hasLoggedToSerenity = true;
        
        try {
            // 过滤：只报告目标 API（如果有配置），否则报告全部
            List<ApiCallRecord> recordsToReport;
            if (!targetApiPatterns.isEmpty()) {
                recordsToReport = new ArrayList<>();
                for (ApiCallRecord record : apiCallHistory) {
                    String url = record.getUrl();
                    for (String pattern : targetApiPatterns) {
                        if (url.contains(pattern) || url.matches(toRegex(pattern))) {
                            recordsToReport.add(record);
                            break;
                        }
                    }
                }
                logger.info("Reporting {} target API(s) out of {} total to Serenity (filtered from non-target APIs)",
                    recordsToReport.size(), apiCallHistory.size());
            } else {
                recordsToReport = new ArrayList<>(apiCallHistory);
            }
            
            if (recordsToReport.isEmpty()) {
                logger.info("No target APIs captured to report to Serenity");
                return;
            }
            
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"summary\": {\n");
            json.append("    \"totalApiCalls\": ").append(recordsToReport.size()).append(",\n");
            json.append("    \"targetApiPatterns\": ").append(targetApiPatterns).append(",\n");
            
            // 按状态码统计
            Map<Integer, Long> statusCount = recordsToReport.stream()
                .collect(Collectors.groupingBy(ApiCallRecord::getStatusCode, Collectors.counting()));
            json.append("    \"statusCodes\": {\n");
            int i = 0;
            for (Map.Entry<Integer, Long> entry : statusCount.entrySet()) {
                json.append("      \"").append(entry.getKey()).append("\": ").append(entry.getValue());
                if (i < statusCount.size() - 1) json.append(",");
                json.append("\n");
                i++;
            }
            json.append("    }\n");
            json.append("  },\n");
            
            // 目标 API 列表
            json.append("  \"targetApiCalls\": [\n");
            for (int j = 0; j < recordsToReport.size(); j++) {
                ApiCallRecord record = recordsToReport.get(j);
                json.append("    {\"method\": \"").append(record.getMethod())
                    .append("\", \"url\": \"").append(escapeJson(record.getUrl()))
                    .append("\", \"status\": ").append(record.getStatusCode()).append("}");
                if (j < recordsToReport.size() - 1) json.append(",");
                json.append("\n");
            }
            json.append("  ]\n");
            json.append("}\n");
            
            String title = targetApiPatterns.isEmpty() 
                ? "API Monitor Summary (" + recordsToReport.size() + " calls)"
                : "Target API Monitor (" + recordsToReport.size() + "/" + targetApiPatterns.size() + ")";
            Serenity.recordReportData()
                .withTitle(title)
                .andContents(json.toString());
            
            logger.info("Recorded {} target API(s) to Serenity report", recordsToReport.size());
        } catch (Exception e) {
            logger.debug("Failed to log summary: {}", e.getMessage());
        }
    }
    
    /**
     * 记录结果到 Serenity 报告
     * 在 stopMonitoring() 时立即调用（而非测试结束），确保结果出现在正确的 step
     */
    public static void logResults() {
        if (apiCallHistory.isEmpty() || hasLoggedToSerenity) {
            return;
        }
        logSummaryToSerenityReport();
    }
    
    /**
     * 清除状态，允许同一测试中多次 start/stop 循环
     */
    public static void resetForNextScenario() {
        hasLoggedToSerenity = false;
    }
    
    // ==================== 内部方法 ====================
    
    private static void recordApiCall(Response response, Request request) {
        try {
            // 检查是否已捕获所有目标 API（如果配置了目标 API）
            if (!targetApiPatterns.isEmpty() && !allTargetApisCaptured) {
                String url = response.url();
                for (String pattern : targetApiPatterns) {
                    if (url.contains(pattern) || url.matches(toRegex(pattern))) {
                        int matched = matchedTargetApiCount.incrementAndGet();
                        logger.info("[Target API #{}] {} {} - {}", matched, request.method(), url, response.status());
                        
                        // 检查是否已匹配所有目标 API
                        if (matched >= targetApiPatterns.size()) {
                            allTargetApisCaptured = true;
                            logger.info("All {} target API(s) captured. Stopping monitoring.", targetApiPatterns.size());
                            stopMonitoring();
                        }
                        break;
                    }
                }
            }
            
            String requestId = UUID.randomUUID().toString();

            ApiCallRecord record = new ApiCallRecord(
                requestId, response.url(), request.method(), System.currentTimeMillis(),
                null, null, response.status(), null, null, false
            );

            // 保存原始 Response/Request 对象，支持延迟读取 headers 和 body
            record.setResponse(response);
            record.setRequest(request);

            apiCallHistory.add(record);

            logger.debug("[API] {} {} - {}", request.method(), response.url(), response.status());
        } catch (Exception e) {
            logger.error("Failed to record API call", e);
        }
    }
    
    /**
     * 简化 URL 用于显示
     */
    private static String simplifyUrl(String url) {
        if (url == null) return "";
        int queryIndex = url.indexOf('?');
        if (queryIndex > 0) {
            return url.substring(0, Math.min(queryIndex, 60));
        }
        return url.length() > 60 ? url.substring(0, 60) + "..." : url;
    }
    
    private static String toRegex(String pattern) {
        if (pattern == null || pattern.isEmpty()) return ".*";
        
        // 已经是正则
        if (pattern.contains(".*") || pattern.contains("\\d") || pattern.contains("?") || pattern.contains("+")) {
            return pattern;
        }
        
        // 转换为正则
        String normalized = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        return ".*" + Pattern.quote(normalized).replace("\\Q", "").replace("\\E", "") + ".*";
    }
    
    private static boolean matchesTargetHost(String url) {
        if (targetHost == null || targetHost.isEmpty()) return true;
        
        try {
            URL urlObj = URI.create(url).toURL();
            return targetHost.equals(urlObj.getHost());
        } catch (Exception e) {
            return url.contains(targetHost);
        }
    }
    
    private static boolean isStaticResource(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        
        if (lower.contains("/api/") || lower.contains("/rest/")) return false;
        
        String[] extensions = {".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", 
                               ".woff", ".woff2", ".ttf", ".eot", ".map", ".html"};
        for (String ext : extensions) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }
    
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
    
    private static ApiCallRecord getLastApiCall() {
        return apiCallHistory.isEmpty() ? null : apiCallHistory.get(apiCallHistory.size() - 1);
    }

    // ==================== 数据类 ====================
    
    /**
     * API 调用记录
     * 
     * 包含 API 调用的完整信息：
     * - 基本信息：URL、Method、状态码、时间戳
     * - 请求信息：请求头、请求体
     * - 响应信息：响应头、响应体（延迟读取，避免阻塞）
     * - 元数据：是否为 Mock 数据
     */
    public static class ApiCallRecord {
        private final String requestId;
        private final String url;
        private final String method;
        private final long timestamp;
        private Map<String, String> requestHeaders;
        private Object requestBody;
        private final int statusCode;
        private Map<String, String> responseHeaders;
        private Object responseBody;
        private final boolean isMocked;
        
        // 延迟读取支持
        private Response response;
        private Request request;
        private boolean bodyRead = false;
        
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
        
        public void setResponse(Response response) {
            this.response = response;
        }
        
        public void setRequest(Request request) {
            this.request = request;
        }
        
        public String getUrl() { return url; }
        public String getMethod() { return method; }
        public long getTimestamp() { return timestamp; }
        public int getStatusCode() { return statusCode; }
        
        public Object getResponseBody() {
            // 延迟读取响应体
            if (responseBody == null && response != null && !bodyRead) {
                try {
                    responseBody = response.text();
                } catch (Exception e) {
                    logger.debug("Cannot read response body: {}", e.getMessage());
                }
                bodyRead = true;
            }
            return responseBody;
        }
        
        public Object getRequestBody() {
            // 延迟读取请求体
            if (requestBody == null && request != null) {
                try {
                    requestBody = request.postData();
                } catch (Exception e) {
                    logger.debug("Cannot read request body: {}", e.getMessage());
                }
            }
            return requestBody;
        }
        
        public Map<String, String> getRequestHeaders() {
            // 延迟读取请求头
            if (requestHeaders == null && request != null) {
                requestHeaders = new HashMap<>(request.headers());
            }
            return requestHeaders;
        }
        
        public Map<String, String> getResponseHeaders() {
            // 延迟读取响应头
            if (responseHeaders == null && response != null) {
                responseHeaders = new HashMap<>(response.headers());
            }
            return responseHeaders;
        }
        
        public boolean isMocked() { return isMocked; }
        
        @Override
        public String toString() {
            return String.format("ApiCallRecord{method='%s', url='%s', status=%d}", method, url, statusCode);
        }
    }
    
    /**
     * API 期望（内部使用）
     * 用于验证捕获的 API 是否符合预期状态码
     */
    public static class ApiExpectation {
        private final String endpoint;
        private Integer expectedStatusCode;
        private String description;
        
        private ApiExpectation(String endpoint) {
            this.endpoint = endpoint;
        }
        
        public static ApiExpectation forEndpoint(String endpoint) {
            return new ApiExpectation(endpoint);
        }
        
        public ApiExpectation statusCode(int code) {
            this.expectedStatusCode = code;
            return this;
        }
        
        public ApiExpectation description(String desc) {
            this.description = desc;
            return this;
        }
        
        public String getEndpoint() { return endpoint; }
        public String getDescription() { return description != null ? description : "Status=" + expectedStatusCode; }
        
        public void validate(ApiCallRecord record) {
            if (expectedStatusCode != null) {
                assertThat("Status code mismatch for " + record.getUrl(), 
                    record.getStatusCode(), equalTo(expectedStatusCode));
            }
        }
    }
}
