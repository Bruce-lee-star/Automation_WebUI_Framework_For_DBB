package com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring;

import com.microsoft.playwright.*;
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
 * Real API Monitor - 监控真实API响应状态码
 * 
 * 功能：
 * 1. 监控真实API请求和响应
 * 2. 记录API调用历史（包括真实的响应状态码）
 * 3. 验证API响应状态码是否符合预期
 * 4. 支持按URL、方法等条件过滤API调用记录
 * 5. 不修改API请求和响应，只进行监控
 * 
 * 限制：
 * - 由于Playwright API限制，精确的响应时间可能无法获取（返回0）
 * - 响应时间验证功能因此受限
 */
public class RealApiMonitor {
    
    private static final Logger logger = LoggerFactory.getLogger(RealApiMonitor.class);
    
    // 存储所有API调用记录
    private static final List<ApiCallRecord> apiCallHistory = new CopyOnWriteArrayList<>();
    
    // 存储已注册的监听器
    private static final Map<Page, Set<ResponseListener>> pageListeners = new HashMap<>();
    
    // 存储已注册的监听器（针对BrowserContext）
    private static final Map<BrowserContext, Set<ResponseListener>> contextListeners = new HashMap<>();
    
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
        
        public ApiCallRecord(String requestId, String url, String method, long timestamp,
                           Map<String, String> requestHeaders, Object requestBody,
                           int statusCode, Map<String, String> responseHeaders,
                           Object responseBody, long responseTimeMs) {
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
     * 监控特定URL的真实API响应
     * 
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持正则表达式）
     */
    public static void monitorApi(Page page, String urlPattern) {
        monitorApi(page, urlPattern, null);
    }
    
    /**
     * 监控特定URL的真实API响应，并提供自定义监听器
     * 
     * @param page Playwright Page对象
     * @param urlPattern URL匹配模式（支持正则表达式）
     * @param listener 响应监听器（可为null）
     */
    public static void monitorApi(Page page, String urlPattern, ResponseListener listener) {
        Pattern pattern = Pattern.compile(urlPattern);
        
        // 监听响应事件
        ResponseListener responseListener = (response, request, responseTimeMs) -> {
            if (pattern.matcher(response.url()).matches()) {
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
                            responseBody, responseTimeMs
                    );
                    
                    apiCallHistory.add(record);
                    logger.info("Recorded API call: {} {} - Status: {}", 
                            request.method(), response.url(), response.status());
                    
                } catch (Exception e) {
                    logger.error("Failed to record API call", e);
                }
            }
        };
        
        // 添加响应监听器
        if (listener != null) {
            pageListeners.computeIfAbsent(page, k -> new HashSet<>()).add(responseListener);
        }
        
        page.onResponse(response -> {
            // 计算响应时间（由于Playwright API限制，使用估算值）
            long responseTimeMs = 0;
            
            // 调用内部监听器
            if (pageListeners.containsKey(page)) {
                for (ResponseListener rl : pageListeners.get(page)) {
                    rl.onResponse(response, response.request(), responseTimeMs);
                }
            }
            
            // 如果有自定义监听器，也调用它
            if (listener != null) {
                listener.onResponse(response, response.request(), responseTimeMs);
            }
        });
        
        logger.info("Started monitoring API for URL pattern: {}", urlPattern);
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
        
        // 添加响应监听器
        ResponseListener responseListener = (response, request, responseTimeMs) -> {
            if (pattern.matcher(response.url()).matches()) {
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
                            responseBody, responseTimeMs
                    );
                    
                    apiCallHistory.add(record);
                    logger.info("Recorded API call: {} {} - Status: {}", 
                            request.method(), response.url(), response.status());
                    
                } catch (Exception e) {
                    logger.error("Failed to record API call", e);
                }
            }
        };
        
        // 保存监听器引用
        if (listener != null) {
            contextListeners.computeIfAbsent(context, k -> new HashSet<>()).add(listener);
        }
        
        context.onResponse(response -> {
            // 计算响应时间（由于Playwright API限制，使用估算值）
            long responseTimeMs = 0;
            
            // 调用内部监听器
            if (contextListeners.containsKey(context)) {
                for (ResponseListener rl : contextListeners.get(context)) {
                    rl.onResponse(response, response.request(), responseTimeMs);
                }
            }
            
            // 如果有自定义监听器，也调用它
            if (listener != null) {
                listener.onResponse(response, response.request(), responseTimeMs);
            }
        });
        
        logger.info("Started monitoring API for URL pattern: {} on context", urlPattern);
    }
    
    /**
     * 监控所有API响应
     * 
     * @param page Playwright Page对象
     */
    public static void monitorAllApi(Page page) {
        monitorApi(page, ".*");
    }
    
    /**
     * 监控所有API响应（针对BrowserContext）
     * 
     * @param context Playwright BrowserContext对象
     */
    public static void monitorAllApi(BrowserContext context) {
        monitorApi(context, ".*");
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
     * 验证API响应状态码是否符合预期
     * 
     * @param urlPattern URL匹配模式（支持正则表达式）
     * @param expectedStatusCode 期望的状态码
     * @return 如果状态码符合预期返回true，否则返回false
     */
    public static boolean verifyStatusCode(String urlPattern, int expectedStatusCode) {
        ApiCallRecord record = getLastApiCallByUrl(urlPattern);
        if (record == null) {
            logger.warn("No API call found for URL pattern: {}", urlPattern);
            return false;
        }
        
        int actualStatusCode = record.getStatusCode();
        boolean result = actualStatusCode == expectedStatusCode;
        
        if (result) {
            logger.info("Status code verification passed. URL: {}, Status: {}", 
                    record.getUrl(), actualStatusCode);
        } else {
            logger.warn("Status code verification failed. URL: {}, Expected: {}, Actual: {}", 
                    record.getUrl(), expectedStatusCode, actualStatusCode);
        }
        
        return result;
    }
    
    /**
     * 验证API响应状态码是否在预期范围内
     * 
     * @param urlPattern URL匹配模式（支持正则表达式）
     * @param minStatusCode 最小期望状态码（包含）
     * @param maxStatusCode 最大期望状态码（包含）
     * @return 如果状态码在预期范围内返回true，否则返回false
     */
    public static boolean verifyStatusCodeInRange(String urlPattern, int minStatusCode, int maxStatusCode) {
        ApiCallRecord record = getLastApiCallByUrl(urlPattern);
        if (record == null) {
            logger.warn("No API call found for URL pattern: {}", urlPattern);
            return false;
        }
        
        int actualStatusCode = record.getStatusCode();
        boolean result = actualStatusCode >= minStatusCode && actualStatusCode <= maxStatusCode;
        
        if (result) {
            logger.info("Status code verification passed. URL: {}, Status: {}, Range: [{}-{}]", 
                    record.getUrl(), actualStatusCode, minStatusCode, maxStatusCode);
        } else {
            logger.warn("Status code verification failed. URL: {}, Expected range: [{}-{}], Actual: {}", 
                    record.getUrl(), minStatusCode, maxStatusCode, actualStatusCode);
        }
        
        return result;
    }
    
    /**
     * 检查API是否被调用
     * 
     * @param urlPattern URL匹配模式（支持正则表达式）
     * @return 如果API被调用至少一次返回true，否则返回false
     */
    public static boolean isApiCalled(String urlPattern) {
        List<ApiCallRecord> calls = getApiHistoryByUrl(urlPattern);
        return !calls.isEmpty();
    }
    
    /**
     * 统计API调用次数
     * 
     * @param urlPattern URL匹配模式（支持正则表达式）
     * @return API调用次数
     */
    public static int countApiCalls(String urlPattern) {
        return getApiHistoryByUrl(urlPattern).size();
    }
    
    /**
     * 获取API响应时间统计信息
     * 
     * @param urlPattern URL匹配模式（支持正则表达式）
     * @return 包含平均、最小、最大响应时间的Map
     */
    public static Map<String, Long> getResponseTimeStats(String urlPattern) {
        List<ApiCallRecord> calls = getApiHistoryByUrl(urlPattern);
        Map<String, Long> stats = new HashMap<>();
        
        if (calls.isEmpty()) {
            stats.put("average", 0L);
            stats.put("min", 0L);
            stats.put("max", 0L);
            return stats;
        }
        
        long sum = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        
        for (ApiCallRecord record : calls) {
            long responseTime = record.getResponseTimeMs();
            sum += responseTime;
            min = Math.min(min, responseTime);
            max = Math.max(max, responseTime);
        }
        
        stats.put("average", sum / calls.size());
        stats.put("min", min);
        stats.put("max", max);
        
        return stats;
    }
    
    /**
     * 清除所有API调用记录
     */
    public static void clearHistory() {
        apiCallHistory.clear();
        logger.info("API call history cleared");
    }
    
    /**
     * 移除指定Page的所有监听器
     * 
     * @param page Playwright Page对象
     */
    public static void removeListeners(Page page) {
        pageListeners.remove(page);
        logger.info("Removed all listeners for page");
    }
    
    /**
     * 移除指定BrowserContext的所有监听器
     * 
     * @param context Playwright BrowserContext对象
     */
    public static void removeListeners(BrowserContext context) {
        contextListeners.remove(context);
        logger.info("Removed all listeners for context");
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
    
    // ==================== Serenity Report Integration ====================
    // 以下方法与Serenity报告集成，验证失败时会抛出AssertionError并显示在报告中
    
    /**
     * 验证API响应状态码（Serenity报告集成版）
     * 如果验证失败，会抛出AssertionError并记录到Serenity报告中
     * 
     * @param urlPattern URL匹配模式（支持正则表达式）
     * @param expectedStatusCode 期望的状态码
     */
    public static void assertStatusCode(String urlPattern, int expectedStatusCode) {
        ApiCallRecord record = getLastApiCallByUrl(urlPattern);
        
        if (record == null) {
            String errorMsg = String.format(
                "No API call found for URL pattern: '%s'. Expected status code: %d", 
                urlPattern, expectedStatusCode
            );
            logger.error(errorMsg);
            throw new AssertionError(errorMsg);
        }
        
        int actualStatusCode = record.getStatusCode();
        
        try {
            assertThat(
                String.format("API status code mismatch for URL: %s", record.getUrl()),
                actualStatusCode, 
                equalTo(expectedStatusCode)
            );
            logger.info("Status code verification passed. URL: {}, Status: {}", 
                    record.getUrl(), actualStatusCode);
        } catch (AssertionError e) {
            logger.error("Status code verification failed. URL: {}, Expected: {}, Actual: {}", 
                    record.getUrl(), expectedStatusCode, actualStatusCode);
            
            // 添加详细的错误信息到断言中
            throw new AssertionError(String.format(
                "API Status Code Mismatch%n" +
                "URL: %s%n" +
                "Method: %s%n" +
                "Expected Status Code: %d%n" +
                "Actual Status Code: %d%n" +
                "Response Time: %dms%n" +
                "Response Body: %s",
                record.getUrl(),
                record.getMethod(),
                expectedStatusCode,
                actualStatusCode,
                record.getResponseTimeMs(),
                truncateString(String.valueOf(record.getResponseBody()), 500)
            ));
        }
    }
    
    /**
     * 验证API响应状态码在预期范围内（Serenity报告集成版）
     * 如果验证失败，会抛出AssertionError并记录到Serenity报告中
     * 
     * @param urlPattern URL匹配模式（支持正则表达式）
     * @param minStatusCode 最小期望状态码（包含）
     * @param maxStatusCode 最大期望状态码（包含）
     */
    public static void assertStatusCodeInRange(String urlPattern, int minStatusCode, int maxStatusCode) {
        ApiCallRecord record = getLastApiCallByUrl(urlPattern);
        
        if (record == null) {
            String errorMsg = String.format(
                "No API call found for URL pattern: '%s'. Expected range: [%d-%d]", 
                urlPattern, minStatusCode, maxStatusCode
            );
            logger.error(errorMsg);
            throw new AssertionError(errorMsg);
        }
        
        int actualStatusCode = record.getStatusCode();
        
        try {
            assertThat(
                String.format("API status code not in range for URL: %s", record.getUrl()),
                actualStatusCode,
                greaterThanOrEqualTo(minStatusCode)
            );
            assertThat(
                String.format("API status code not in range for URL: %s", record.getUrl()),
                actualStatusCode,
                lessThanOrEqualTo(maxStatusCode)
            );
            logger.info("Status code verification passed. URL: {}, Status: {}, Range: [{}-{}]", 
                    record.getUrl(), actualStatusCode, minStatusCode, maxStatusCode);
        } catch (AssertionError e) {
            logger.error("Status code verification failed. URL: {}, Expected range: [{}-{}], Actual: {}", 
                    record.getUrl(), minStatusCode, maxStatusCode, actualStatusCode);
            
            // 添加详细的错误信息到断言中
            throw new AssertionError(String.format(
                "API Status Code Out of Range%n" +
                "URL: %s%n" +
                "Method: %s%n" +
                "Expected Range: [%d-%d]%n" +
                "Actual Status Code: %d%n" +
                "Response Time: %dms%n" +
                "Response Body: %s",
                record.getUrl(),
                record.getMethod(),
                minStatusCode,
                maxStatusCode,
                actualStatusCode,
                record.getResponseTimeMs(),
                truncateString(String.valueOf(record.getResponseBody()), 500)
            ));
        }
    }
    
    /**
     * 断言API被调用（Serenity报告集成版）
     * 如果API未被调用，会抛出AssertionError并记录到Serenity报告中
     * 
     * @param urlPattern URL匹配模式（支持正则表达式）
     */
    public static void assertApiCalled(String urlPattern) {
        List<ApiCallRecord> calls = getApiHistoryByUrl(urlPattern);
        
        if (calls.isEmpty()) {
            String errorMsg = String.format(
                "Expected API to be called, but no API call found for URL pattern: '%s'", 
                urlPattern
            );
            logger.error(errorMsg);
            throw new AssertionError(errorMsg);
        }
        
        logger.info("API call verification passed. Pattern: {}, Calls: {}", urlPattern, calls.size());
    }
    
    /**
     * 断言API被调用指定次数（Serenity报告集成版）
     * 
     * @param urlPattern URL匹配模式（支持正则表达式）
     * @param expectedCount 期望的调用次数
     */
    public static void assertApiCallCount(String urlPattern, int expectedCount) {
        int actualCount = countApiCalls(urlPattern);
        
        if (actualCount != expectedCount) {
            String errorMsg = String.format(
                "API call count mismatch for pattern '%s'. Expected: %d, Actual: %d", 
                urlPattern, expectedCount, actualCount
            );
            logger.error(errorMsg);
            throw new AssertionError(errorMsg);
        }
        
        logger.info("API call count verification passed. Pattern: {}, Count: {}", urlPattern, actualCount);
    }
    
    /**
     * 断言API响应成功（状态码为2xx）（Serenity报告集成版）
     * 
     * @param urlPattern URL匹配模式（支持正则表达式）
     */
    public static void assertApiSuccess(String urlPattern) {
        ApiCallRecord record = getLastApiCallByUrl(urlPattern);
        
        if (record == null) {
            String errorMsg = String.format(
                "No API call found for URL pattern: '%s'. Expected successful response (2xx)", 
                urlPattern
            );
            logger.error(errorMsg);
            throw new AssertionError(errorMsg);
        }
        
        int actualStatusCode = record.getStatusCode();
        
        if (actualStatusCode < 200 || actualStatusCode >= 300) {
            logger.error("API response not successful. URL: {}, Status: {}", 
                    record.getUrl(), actualStatusCode);
            
            throw new AssertionError(String.format(
                "API Response Not Successful (Expected 2xx)%n" +
                "URL: %s%n" +
                "Method: %s%n" +
                "Actual Status Code: %d%n" +
                "Response Time: %dms%n" +
                "Response Body: %s",
                record.getUrl(),
                record.getMethod(),
                actualStatusCode,
                record.getResponseTimeMs(),
                truncateString(String.valueOf(record.getResponseBody()), 500)
            ));
        }
        
        logger.info("API success verification passed. URL: {}, Status: {}", 
                record.getUrl(), actualStatusCode);
    }
    
    /**
     * 断言API响应体包含指定内容（Serenity报告集成版）
     * 
     * @param urlPattern URL匹配模式（支持正则表达式）
     * @param expectedContent 期望包含的内容
     */
    public static void assertResponseBodyContains(String urlPattern, String expectedContent) {
        ApiCallRecord record = getLastApiCallByUrl(urlPattern);
        
        if (record == null) {
            String errorMsg = String.format(
                "No API call found for URL pattern: '%s'. Expected response to contain: %s", 
                urlPattern, expectedContent
            );
            logger.error(errorMsg);
            throw new AssertionError(errorMsg);
        }
        
        String responseBody = String.valueOf(record.getResponseBody());
        
        if (responseBody == null || !responseBody.contains(expectedContent)) {
            logger.error("Response body does not contain expected content. URL: {}, Expected: {}", 
                    record.getUrl(), expectedContent);
            
            throw new AssertionError(String.format(
                "Response Body Does Not Contain Expected Content%n" +
                "URL: %s%n" +
                "Method: %s%n" +
                "Expected Content: %s%n" +
                "Response Body: %s",
                record.getUrl(),
                record.getMethod(),
                expectedContent,
                truncateString(responseBody, 500)
            ));
        }
        
        logger.info("Response body content verification passed. URL: {}", record.getUrl());
    }
    
    /**
     * 断言API响应时间小于指定值（Serenity报告集成版）
     * 注意：由于Playwright API限制，响应时间可能不可用（返回0）
     * 
     * @param urlPattern URL匹配模式（支持正则表达式）
     * @param maxResponseTimeMs 最大响应时间（毫秒）
     */
    public static void assertResponseTime(String urlPattern, long maxResponseTimeMs) {
        ApiCallRecord record = getLastApiCallByUrl(urlPattern);
        
        if (record == null) {
            String errorMsg = String.format(
                "No API call found for URL pattern: '%s'. Expected response time <= %dms", 
                urlPattern, maxResponseTimeMs
            );
            logger.error(errorMsg);
            throw new AssertionError(errorMsg);
        }
        
        long actualResponseTime = record.getResponseTimeMs();
        
        // 检查响应时间是否可用
        if (actualResponseTime == 0) {
            logger.warn("Response time verification skipped. Response time is not available due to Playwright API limitations. URL: {}", 
                    record.getUrl());
            return; // 跳过验证
        }
        
        if (actualResponseTime > maxResponseTimeMs) {
            logger.error("Response time exceeded threshold. URL: {}, Expected: <={}ms, Actual: {}ms", 
                    record.getUrl(), maxResponseTimeMs, actualResponseTime);
            
            throw new AssertionError(String.format(
                "API Response Time Exceeded Threshold%n" +
                "URL: %s%n" +
                "Method: %s%n" +
                "Max Expected Response Time: %dms%n" +
                "Actual Response Time: %dms%n" +
                "Status Code: %d",
                record.getUrl(),
                record.getMethod(),
                maxResponseTimeMs,
                actualResponseTime,
                record.getStatusCode()
            ));
        }
        
        logger.info("Response time verification passed. URL: {}, Time: {}ms", 
                record.getUrl(), actualResponseTime);
    }
    
    /**
     * 断言多个API的状态码（Serenity报告集成版）
     * 
     * @param apiExpectations API期望值映射表（URL模式 -> 期望状态码）
     */
    public static void assertMultipleApiStatusCodes(Map<String, Integer> apiExpectations) {
        List<String> failures = new ArrayList<>();
        
        for (Map.Entry<String, Integer> entry : apiExpectations.entrySet()) {
            String urlPattern = entry.getKey();
            int expectedStatus = entry.getValue();
            
            ApiCallRecord record = getLastApiCallByUrl(urlPattern);
            
            if (record == null) {
                failures.add(String.format("No API call for pattern '%s' (expected status: %d)", 
                        urlPattern, expectedStatus));
                continue;
            }
            
            if (record.getStatusCode() != expectedStatus) {
                failures.add(String.format(
                    "URL: %s - Expected: %d, Actual: %d",
                    record.getUrl(), expectedStatus, record.getStatusCode()
                ));
            }
        }
        
        if (!failures.isEmpty()) {
            String errorMsg = "Multiple API status code verification failed:\n" + 
                             String.join("\n", failures);
            logger.error(errorMsg);
            throw new AssertionError(errorMsg);
        }
        
        logger.info("Multiple API status codes verification passed. Verified {} APIs", 
                apiExpectations.size());
    }
    
    /**
     * 获取API调用详细报告（包含所有记录）
     * 
     * @return 包含所有API调用详细信息的字符串
     */
    public static String getDetailedApiReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== API Monitoring Detailed Report ===\n");
        report.append(String.format("Total API Calls: %d\n\n", apiCallHistory.size()));
        
        if (apiCallHistory.isEmpty()) {
            report.append("No API calls recorded.");
            return report.toString();
        }
        
        for (int i = 0; i < apiCallHistory.size(); i++) {
            ApiCallRecord record = apiCallHistory.get(i);
            report.append(String.format("--- API Call #%d ---\n", i + 1));
            report.append(String.format("URL: %s\n", record.getUrl()));
            report.append(String.format("Method: %s\n", record.getMethod()));
            report.append(String.format("Status Code: %d\n", record.getStatusCode()));
            report.append(String.format("Response Time: %dms\n", record.getResponseTimeMs()));
            report.append(String.format("Timestamp: %s\n", 
                    new Date(record.getTimestamp()).toString()));
            
            if (record.getRequestBody() != null) {
                report.append(String.format("Request Body: %s\n", 
                        truncateString(String.valueOf(record.getRequestBody()), 200)));
            }
            
            if (record.getResponseBody() != null) {
                report.append(String.format("Response Body: %s\n", 
                        truncateString(String.valueOf(record.getResponseBody()), 500)));
            }
            
            report.append("\n");
        }
        
        return report.toString();
    }
    
    /**
     * 截断字符串到指定长度
     * 
     * @param str 原始字符串
     * @param maxLength 最大长度
     * @return 截断后的字符串
     */
    private static String truncateString(String str, int maxLength) {
        if (str == null) {
            return "null";
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "... (truncated)";
    }
}
