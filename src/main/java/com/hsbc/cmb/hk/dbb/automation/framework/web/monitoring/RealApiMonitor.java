package com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.ContextLifecycleHookManager;
import net.serenitybdd.core.Serenity;
import net.thucydides.core.steps.StepEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.net.URL;
import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Real API Monitor - 生产级 API 调用拦截与监控工具
 *
 * <p>功能特性：
 * <ul>
 *   <li>实时拦截 BrowserContext/Page 级别所有 HTTP 请求与响应</li>
 *   <li>支持指定 URL 部分字符串精确匹配</li>
 *   <li>自动提取 JSON 响应体，支持 JsonPath 深度提取</li>
 *   <li>支持超时自动停止、minMatches 次数控制</li>
 *   <li>支持自定义回调函数 onMatch</li>
 *   <li>线程安全：每个线程独立状态，支持 Serenity 多线程并行场景</li>
 *   <li>Context/Page 生命周期自动管理：重建后监听器自动重绑</li>
 *   <li>【新架构】集成 RouteAsyncPool，支持异步日志和断言</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 基础用法
 * RealApiMonitor.monitor(page)
 *     .api("/api/user/info")
 *     .api("/api/orders", 200)
 *     .timeout(30)
 *     .start();
 *
 * // 等待并获取结果
 * ApiCallRecord record = RealApiMonitor.waitForApi("/api/user/info", 10);
 * String name = RealApiMonitor.getJsonString("/api/user/info", "$.name");
 *
 * // 停止监控
 * RealApiMonitor.stopMonitoring();
 * }</pre>
 */
public class RealApiMonitor implements ContextLifecycleHookManager.RuleCapturer {

    private static final Logger logger = LoggerFactory.getLogger(RealApiMonitor.class);

    /**
     * 【新架构】异步执行器 - 用于非阻塞的日志记录和断言验证
     * 避免在 Playwright route 线程中执行 IO 操作，确保 UI 不卡顿
     */
    private static final ExecutorService ASYNC_EXECUTOR = 
        new ThreadPoolExecutor(
            2, 2, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500),
            r -> {
                Thread t = new Thread(r, "real-api-monitor-async");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

    /** API调用历史最大容量，防止内存泄漏 */
    private static final int MAX_API_HISTORY_SIZE = 1000;
    /** 响应体最大读取大小（字节），超过则不读取，防止同步阻塞 */
    private static final int MAX_RESPONSE_BODY_SIZE = 512 * 1024; // 512KB

    private static final ThreadLocal<List<ApiCallRecord>> apiCallHistory = ThreadLocal.withInitial(CopyOnWriteArrayList::new);
    private static final Map<BrowserContext, Boolean> contextMonitoringStopped = new ConcurrentHashMap<>();
    private static final Map<Page, Boolean> pageMonitoringStopped = new ConcurrentHashMap<>();
    private static final Map<BrowserContext, java.util.function.Consumer<Response>> contextListeners = new ConcurrentHashMap<>();
    private static final Map<Page, java.util.function.Consumer<Response>> pageListeners = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> hasLoggedToSerenity = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<String> targetHost = ThreadLocal.withInitial(() -> null);
    private static final ThreadLocal<AssertionError> monitoringFailure = ThreadLocal.withInitial(() -> null);
    private static final ThreadLocal<AtomicInteger> matchedTargetApiCount = ThreadLocal.withInitial(AtomicInteger::new);
    private static final ThreadLocal<Boolean> allTargetApisCaptured = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Map<String, AtomicInteger>> patternMatchCounts = ThreadLocal.withInitial(ConcurrentHashMap::new);
    private static final ThreadLocal<Integer> configuredMinMatches = ThreadLocal.withInitial(() -> 1);
    private static final ThreadLocal<Boolean> configuredAutoStopOnMatch = ThreadLocal.withInitial(() -> true);
    private static final ThreadLocal<Integer> configuredTimeout = ThreadLocal.withInitial(() -> 60);
    private static volatile boolean reportPending = false;
    private static volatile String pendingReportTitle = null;
    private static volatile String pendingReportContent = null;
    private static volatile long lastApiActivityTime = 0;
    private static final ThreadLocal<List<String>> targetApiPatterns = ThreadLocal.withInitial(CopyOnWriteArrayList::new);
    private static final ThreadLocal<MonitorListenerSnapshot> cachedSnapshot = new ThreadLocal<>();

    /** API期望映射（使用原始URL部分字符串作为key） */
    private static final ThreadLocal<Map<String, ApiExpectation>> apiExpectations = ThreadLocal.withInitial(ConcurrentHashMap::new);

    static {
        ContextLifecycleHookManager.registerCapturer(new RealApiMonitor());
        logger.info("[RealApiMonitor] Registered as Context lifecycle hook");
    }

    /**
     * 捕获当前监听器配置快照，用于 Context/Page 重建后重绑
     *
     * @param context 目标 BrowserContext（当前未使用，仅满足接口签名）
     * @return 监听器快照列表，若无活跃监听器则返回空列表
     */
    @Override
    public List<ContextLifecycleHookManager.RuleSnapshot> captureRules(BrowserContext context) {
        List<ContextLifecycleHookManager.RuleSnapshot> snapshots = new ArrayList<>();
        MonitorListenerSnapshot snapshot = cachedSnapshot.get();
        if (snapshot != null) {
            snapshots.add(snapshot);
            logger.info("[RealApiMonitor] Captured monitor listener snapshot for lifecycle hook");
        }
        return snapshots;
    }

    /**
     * 监听器快照 - 持有创建时的配置，支持跨 Context/Page 生命周期重建
     *
     * <p>配置在 start() 时由业务线程创建并持有，captureRules 直接获取，
     * 避免 ThreadLocal 跨线程取值导致配置丢失的问题。
     */
    private static class MonitorListenerSnapshot implements ContextLifecycleHookManager.RuleSnapshot {
        private final List<String> patterns;
        private final int timeoutSeconds;
        private final boolean autoStopOnMatch;
        private final int minMatches;

        public MonitorListenerSnapshot(List<String> capturedPatterns,
                                     int timeout, boolean autoStop, int minMatch) {
            this.patterns = new ArrayList<>(capturedPatterns != null ? capturedPatterns : new ArrayList<>());
            this.timeoutSeconds = timeout;
            this.autoStopOnMatch = autoStop;
            this.minMatches = minMatch;
        }

        @Override
        public String getId() { return "monitor-context-listener"; }

        @Override
        public String getUrlPattern() { 
            return patterns.isEmpty() ? "" : patterns.get(0); 
        }

        @Override
        public boolean rebindTo(BrowserContext newContext) {
            try {
                logger.info("[MonitorListenerSnapshot] Rebinding monitor listener to new context (patterns={}, timeout={}s)",
                    patterns, timeoutSeconds);
                MonitorBuilder builder = new MonitorBuilder(newContext, null);
                for (String pattern : patterns) {
                    builder.api(pattern);
                }
                builder.timeout(timeoutSeconds)
                       .autoStopOnMatch(autoStopOnMatch)
                       .minMatches(minMatches)
                       .start();
                logger.info("[MonitorListenerSnapshot] Successfully rebound monitor listener");
                return true;
            } catch (Exception e) {
                logger.warn("[MonitorListenerSnapshot] Failed to rebind monitor listener: {}", e.getMessage());
                return false;
            }
        }

        @Override
        public boolean rebindTo(Page newPage) {
            try {
                logger.info("[MonitorListenerSnapshot] Rebinding monitor listener to new page (patterns={}, timeout={}s)",
                    patterns, timeoutSeconds);
                MonitorBuilder builder = new MonitorBuilder(null, newPage);
                for (String pattern : patterns) {
                    builder.api(pattern);
                }
                builder.timeout(timeoutSeconds)
                       .autoStopOnMatch(autoStopOnMatch)
                       .minMatches(minMatches)
                       .start();
                return true;
            } catch (Exception e) {
                logger.warn("[MonitorListenerSnapshot] Failed to rebind monitor listener to page: {}", e.getMessage());
                return false;
            }
        }
    }

    /**
     * 创建 BrowserContext 级别的监控构建器
     *
     * @param context 目标 BrowserContext
     * @return MonitorBuilder 实例
     */
    public static MonitorBuilder monitor(BrowserContext context) {
        return new MonitorBuilder(context, null);
    }

    /**
     * 创建 Page 级别的监控构建器
     *
     * @param page 目标 Page
     * @return MonitorBuilder 实例
     */
    public static MonitorBuilder monitor(Page page) {
        return new MonitorBuilder(null, page);
    }

    /**
     * 监控构建器 - 链式 API 配置
     */
    public static class MonitorBuilder {
        private final BrowserContext context;
        private final Page page;
        private final Map<String, Integer> apis = new LinkedHashMap<>();
        private int timeoutSeconds = 60;
        private java.util.function.Consumer<ApiCallRecord> onMatchCallback;
        private boolean stopOnFirstMatch = false;
        private int minMatches = 1;
        private boolean autoStopOnMatch = true;

        public MonitorBuilder(BrowserContext context, Page page) {
            this.context = context;
            this.page = page;
        }

        /**
         * 添加目标 API URL 部分字符串（带期望状态码）
         *
         * @param urlPart URL 部分字符串，URL 包含此字符串即匹配
         * @param expectedStatus 期望的 HTTP 状态码，0 表示任意状态
         * @return this
         */
        public MonitorBuilder api(String urlPart, int expectedStatus) {
            apis.put(urlPart, expectedStatus);
            return this;
        }

        /**
         * 添加目标 API URL 部分字符串（不校验状态码）
         *
         * @param urlPart URL 部分字符串，URL 包含此字符串即匹配
         * @return this
         */
        public MonitorBuilder api(String urlPart) {
            apis.put(urlPart, 0);
            return this;
        }

        /**
         * 设置监控超时时间
         *
         * @param seconds 超时秒数，0 表示不超时
         * @return this
         */
        public MonitorBuilder timeout(int seconds) {
            this.timeoutSeconds = seconds;
            return this;
        }

        /**
         * 设置匹配时的回调函数
         *
         * @param callback API 匹配时执行的回调
         * @return this
         */
        public MonitorBuilder then(java.util.function.Consumer<ApiCallRecord> callback) {
            this.onMatchCallback = callback;
            return this;
        }

        /**
         * 首次匹配后停止监控
         *
         * @return this
         */
        public MonitorBuilder stopOnFirstMatch() {
            this.stopOnFirstMatch = true;
            return this;
        }

        /**
         * 设置每个模式期望匹配的最小次数
         *
         * @param n 最小匹配次数
         * @return this
         */
        public MonitorBuilder minMatches(int n) {
            this.minMatches = Math.max(1, n);
            return this;
        }

        /**
         * 设置是否在满足所有条件后自动停止监控
         *
         * @param autoStop true 则自动停止
         * @return this
         */
        public MonitorBuilder autoStopOnMatch(boolean autoStop) {
            this.autoStopOnMatch = autoStop;
            return this;
        }

        /**
         * 等待任意目标 API 响应
         *
         * @param timeoutSeconds 超时秒数
         * @return 匹配的 ApiCallRecord，若超时则返回 null
         */
        public ApiCallRecord waitForResponse(int timeoutSeconds) {
            logger.info("Waiting for any target API response (timeout={}s)...", timeoutSeconds);
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            
            while (System.currentTimeMillis() < deadline) {
                for (String pattern : apis.keySet()) {
                    ApiCallRecord found = getLast(pattern);
                    if (found != null) {
                        logger.info("Target API captured via waitForResponse: {} {}", found.getMethod(), found.getUrl());
                        return found;
                    }
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("waitForResponse interrupted");
                    return null;
                }
            }
            
            List<ApiCallRecord> captured = apiCallHistory.get();
            logger.warn("waitForResponse TIMEOUT after {}s. Target patterns: {}. Captured APIs:", 
                timeoutSeconds, apis.keySet());
            if (!captured.isEmpty()) {
                for (int i = 0; i < Math.min(captured.size(), 10); i++) {
                    ApiCallRecord r = captured.get(i);
                    logger.warn("  [{}] {} {} (status={})", i+1, r.getMethod(), simplifyUrl(r.getUrl()), r.getStatusCode());
                }
            }
            return null;
        }

        /**
         * 启动异步监控
         */
        public void start() {
            logger.info("========== Starting async API monitoring ({} target APIs, timeout: {}s, callback: {}, minMatches: {}, autoStopOnMatch: {}) ==========", 
                apis.size(), timeoutSeconds, onMatchCallback != null ? "YES" : "NO", minMatches, autoStopOnMatch);
            
            if (context != null) {
                markExistingListenerStopped(context);
            } else if (page != null) {
                markExistingListenerStopped(page);
            }
            
            clearHistory();
            clearExpectations();
            
            targetApiPatterns.get().clear();
            matchedTargetApiCount.get().set(0);
            allTargetApisCaptured.set(false);
            
            configuredMinMatches.set(minMatches);
            configuredAutoStopOnMatch.set(autoStopOnMatch);
            configuredTimeout.set(timeoutSeconds);
            patternMatchCounts.get().clear();
            for (String pattern : apis.keySet()) {
                patternMatchCounts.get().put(pattern, new AtomicInteger(0));
            }
            
            for (Map.Entry<String, Integer> entry : apis.entrySet()) {
                String urlPattern = entry.getKey();
                targetApiPatterns.get().add(urlPattern);
                if (entry.getValue() > 0) {
                    apiExpectations.get().put(urlPattern, ApiExpectation.forEndpoint(urlPattern).statusCode(entry.getValue()));
                }
                logger.info("  - Target API: {} -> Expected Status: {}", urlPattern, entry.getValue() > 0 ? entry.getValue() : "any");
            }
            
            cachedSnapshot.set(new MonitorListenerSnapshot(
                targetApiPatterns.get(),
                timeoutSeconds,
                autoStopOnMatch,
                minMatches
            ));
            
            startListening();
            
            if (timeoutSeconds > 0) {
                startAutoStopTimer();
            } else {
                logger.info("No timeout set (timeout=0), monitoring until manually stopped");
            }
        }

        private void startListening() {
            if (context != null) {
                removeExistingListener(context);
                contextMonitoringStopped.put(context, false);
                final boolean hasTargetPatterns = !apis.isEmpty();
                
                java.util.function.Consumer<Response> handler = response -> {
                    // 【调试】检查是否被 stopped 标记阻止
                    if (contextMonitoringStopped.getOrDefault(context, false)) {
                        logger.debug("[RealApiMonitor] Response skipped - monitor is stopped: {}", response.url());
                        return;
                    }
                    // 【调试】检查 targetHost 过滤
                    if (!matchesTargetHost(response.url())) {
                        logger.debug("[RealApiMonitor] Response skipped - targetHost mismatch: {}", response.url());
                        return;
                    }
                    // 检查 targetPattern 匹配
                    if (hasTargetPatterns && !isTargetPatternMatch(response.url())) {
                        logger.debug("[RealApiMonitor] Response skipped - pattern mismatch: {} (patterns={})", 
                            response.url(), targetApiPatterns.get());
                        return;
                    }
                    recordApiCall(response, response.request());
                    validateRealTime(response);
                    invokeCallbackIfMatched(response);
                };
                contextListeners.put(context, handler);
                context.onResponse(handler);
            } else if (page != null) {
                removeExistingListener(page);
                pageMonitoringStopped.put(page, false);
                final boolean hasTargetPatterns = !apis.isEmpty();
                
                java.util.function.Consumer<Response> handler = response -> {
                    // 【调试】检查是否被 stopped 标记阻止
                    if (pageMonitoringStopped.getOrDefault(page, false)) {
                        logger.debug("[RealApiMonitor] Response skipped - monitor is stopped: {}", response.url());
                        return;
                    }
                    // 【调试】检查 targetHost 过滤
                    if (!matchesTargetHost(response.url())) {
                        logger.debug("[RealApiMonitor] Response skipped - targetHost mismatch: {}", response.url());
                        return;
                    }
                    // 检查 targetPattern 匹配
                    if (hasTargetPatterns && !isTargetPatternMatch(response.url())) {
                        logger.debug("[RealApiMonitor] Response skipped - pattern mismatch: {} (patterns={})", 
                            response.url(), targetApiPatterns.get());
                        return;
                    }
                    recordApiCall(response, response.request());
                    validateRealTime(response);
                    invokeCallbackIfMatched(response);
                };
                pageListeners.put(page, handler);
                page.onResponse(handler);
            }
        }

        private boolean isTargetPatternMatch(String url) {
            List<String> patterns = targetApiPatterns.get();
            if (patterns.isEmpty()) return true;
            for (String pattern : patterns) {
                if (url.contains(pattern)) {
                    return true;
                }
            }
            return false;
        }

        private void invokeCallbackIfMatched(Response response) {
            if (onMatchCallback == null || targetApiPatterns.get().isEmpty()) return;
            
            String url = response.url();
            for (String pattern : targetApiPatterns.get()) {
                if (url.contains(pattern)) {
                    ApiCallRecord matchedRecord = findRecordByUrl(url);
                    if (matchedRecord != null) {
                        try {
                            logger.info("[CALLBACK] Invoking custom callback for matched API: {} {}", 
                                matchedRecord.getMethod(), url);
                            onMatchCallback.accept(matchedRecord);
                            if (stopOnFirstMatch) {
                                logger.info("[CALLBACK] stopOnFirstMatch=true, stopping monitoring");
                                stopMonitoring();
                            }
                        } catch (Exception e) {
                            logger.error("[CALLBACK] Custom callback failed for {}: {}", url, e.getMessage(), e);
                        }
                    } else {
                        logger.warn("[CALLBACK] Response matched pattern '{}' but no record found for url: {}", pattern, url);
                    }
                    break;
                }
            }
        }

        private static ApiCallRecord findRecordByUrl(String url) {
            List<ApiCallRecord> history = apiCallHistory.get();
            for (int i = history.size() - 1; i >= 0; i--) {
                ApiCallRecord record = history.get(i);
                if (record.getUrl().equals(url)) {
                    return record;
                }
            }
            return null;
        }

        private void removeExistingListener(BrowserContext ctx) {
            if (ctx != null && contextListeners.containsKey(ctx)) {
                try {
                    java.util.function.Consumer<Response> oldHandler = contextListeners.remove(ctx);
                    if (oldHandler != null) {
                        ctx.offResponse(oldHandler);
                        logger.debug("Truly removed stale listener for context via offResponse()");
                    }
                } catch (Exception e) {
                    logger.debug("offResponse failed for context (may be closed), fallback to mark-stopped: {}", e.getMessage());
                    contextMonitoringStopped.put(ctx, true);
                    contextListeners.remove(ctx);
                }
            }
        }

        private void removeExistingListener(Page p) {
            if (p != null && pageListeners.containsKey(p)) {
                try {
                    java.util.function.Consumer<Response> oldHandler = pageListeners.remove(p);
                    if (oldHandler != null) {
                        p.offResponse(oldHandler);
                        logger.debug("Truly removed stale listener for page via offResponse()");
                    }
                } catch (Exception e) {
                    logger.debug("offResponse failed for page (may be closed), fallback to mark-stopped: {}", e.getMessage());
                    pageMonitoringStopped.put(p, true);
                    pageListeners.remove(p);
                }
            }
        }

        private static void markExistingListenerStopped(BrowserContext ctx) {
            if (ctx != null && contextListeners.containsKey(ctx)) {
                contextMonitoringStopped.put(ctx, true);
                logger.debug("Marked existing context listener as stopped (pre-clearHistory)");
            }
        }

        private static void markExistingListenerStopped(Page p) {
            if (p != null && pageListeners.containsKey(p)) {
                pageMonitoringStopped.put(p, true);
                logger.debug("Marked existing page listener as stopped (pre-clearHistory)");
            }
        }

        private void startAutoStopTimer() {
            logger.info("Auto-stop: will check in-record timeout={}s (inline, no extra thread)", timeoutSeconds);
        }

        private void validateRealTime(Response response) {
            String url = response.url();
            for (Map.Entry<String, ApiExpectation> entry : apiExpectations.get().entrySet()) {
                if (url.contains(entry.getKey())) {
                    ApiCallRecord record = findRecordByUrl(url);
                    if (record == null) {
                        record = getLastApiCall();
                    }
                    if (record != null) {
                        try {
                            entry.getValue().validate(record);
                        } catch (AssertionError e) {
                            // 保存断言错误，后续 rethrow 会让测试失败
                            monitoringFailure.set(e);
                            logger.error("API validation failed for {}: expected status={}, actual={}",
                                    url, entry.getValue().expectedStatusCode, record.getStatusCode());
                            throw e;  // 重新抛出，让测试立即失败
                        }
                    }
                    break;
                }
            }
        }
    }

    /**
     * 获取最近匹配的 API 记录
     *
     * @param urlPattern URL 部分字符串
     * @return 匹配的记录，若无则返回 null
     */
    public static ApiCallRecord getLast(String urlPattern) {
        List<ApiCallRecord> history = apiCallHistory.get();
        for (int i = history.size() - 1; i >= 0; i--) {
            ApiCallRecord record = history.get(i);
            if (record.getUrl().contains(urlPattern)) {
                return record;
            }
        }
        return null;
    }

    /**
     * 获取所有匹配的 API 记录
     *
     * @param urlPattern URL 部分字符串
     * @return 匹配的记录列表
     */
    public static List<ApiCallRecord> getAll(String urlPattern) {
        List<ApiCallRecord> history = apiCallHistory.get();
        List<ApiCallRecord> result = new ArrayList<>();
        for (ApiCallRecord record : history) {
            if (record.getUrl().contains(urlPattern)) {
                result.add(record);
            }
        }
        return result;
    }

    /**
     * 获取指定模式的匹配次数
     *
     * @param urlPattern URL 模式
     * @return 匹配次数
     */
    public static int getMatchCount(String urlPattern) {
        return getAll(urlPattern).size();
    }

    /**
     * 获取所有匹配的响应体列表
     *
     * @param urlPattern URL 模式
     * @return 响应体列表
     */
    public static List<String> getAllBodies(String urlPattern) {
        List<ApiCallRecord> records = getAll(urlPattern);
        List<String> bodies = new ArrayList<>(records.size());
        for (ApiCallRecord record : records) {
            bodies.add(record.getResponseBody());
        }
        return bodies;
    }

    /**
     * 等待指定 API 被捕获
     *
     * @param urlPattern URL 模式
     * @param timeoutSeconds 超时秒数
     * @return 匹配的记录
     */
    public static ApiCallRecord waitForApi(String urlPattern, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        logger.info("Waiting for API match: pattern={}, timeout={}s", urlPattern, timeoutSeconds);
        
        while (System.currentTimeMillis() < deadline) {
            ApiCallRecord record = getLast(urlPattern);
            if (record != null) {
                logger.info("Target API captured after {}ms: {} {}", 
                    (deadline - System.currentTimeMillis() + timeoutSeconds * 1000), 
                    record.getMethod(), record.getUrl());
                return record;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("waitForApi interrupted while waiting for: {}", urlPattern);
                return null;
            }
        }
        
        List<ApiCallRecord> captured = apiCallHistory.get();
        logger.warn("waitForApi TIMEOUT after {}s for pattern='{}'. Captured APIs:", 
            timeoutSeconds, urlPattern);
        if (!captured.isEmpty()) {
            for (int i = 0; i < Math.min(captured.size(), 10); i++) {
                ApiCallRecord r = captured.get(i);
                logger.warn("  [{}] {} {} (status={})", i+1, r.getMethod(), simplifyUrl(r.getUrl()), r.getStatusCode());
            }
            if (captured.size() > 10) {
                logger.warn("  ... and {} more", captured.size() - 10);
            }
        }
        return null;
    }

    /**
     * 等待 API 并返回响应体
     *
     * @param urlPattern URL 模式
     * @param timeoutSeconds 超时秒数
     * @return 响应体字符串
     */
    public static String waitForApiBody(String urlPattern, int timeoutSeconds) {
        ApiCallRecord record = waitForApi(urlPattern, timeoutSeconds);
        if (record == null) return null;
        return record.getResponseBody();
    }

    private static boolean isJsonValueEmpty(Object value) {
        if (value == null) return true;
        if (value instanceof String) {
            String s = ((String) value).trim();
            return s.isEmpty() || "null".equalsIgnoreCase(s);
        }
        if (value instanceof Collection) return ((Collection<?>) value).isEmpty();
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T> T safeCast(Object rawValue, Class<T> targetType, String urlPattern, String jsonPath) {
        if (isJsonValueEmpty(rawValue)) return null;
        try {
            if (targetType == String.class) {
                return targetType.cast(rawValue.toString());
            } else if (targetType == Integer.class || targetType == int.class) {
                if (rawValue instanceof Number) return targetType.cast(((Number) rawValue).intValue());
                return targetType.cast(Integer.parseInt(rawValue.toString()));
            } else if (targetType == Long.class || targetType == long.class) {
                if (rawValue instanceof Number) return targetType.cast(((Number) rawValue).longValue());
                return targetType.cast(Long.parseLong(rawValue.toString()));
            } else if (targetType == Double.class || targetType == double.class) {
                if (rawValue instanceof Number) return targetType.cast(((Number) rawValue).doubleValue());
                return targetType.cast(Double.parseDouble(rawValue.toString()));
            } else if (targetType == Boolean.class || targetType == boolean.class) {
                if (rawValue instanceof Boolean) return targetType.cast(rawValue);
                return targetType.cast(Boolean.parseBoolean(rawValue.toString()));
            } else if (targetType == List.class) {
                if (rawValue instanceof List) return targetType.cast(rawValue);
                return targetType.cast(Collections.singletonList(rawValue));
            } else if (targetType == Map.class) {
                if (rawValue instanceof Map) return targetType.cast(rawValue);
            }
            return targetType.cast(rawValue);
        } catch (ClassCastException e) {
            logger.warn("safeCast: type mismatch for pattern='{}', jsonPath='{}': expected {}, got {} ({})",
                urlPattern, jsonPath, targetType.getSimpleName(),
                rawValue.getClass().getSimpleName(), rawValue);
            return null;
        }
    }

    /**
     * 从最近匹配的 API 响应中提取 JSON 值
     *
     * @param urlPattern URL 模式
     * @param jsonPath JsonPath 表达式，如 "$.data.name"
     * @param <T> 返回值类型
     * @return 提取的值，若无匹配或解析失败则返回 null
     */
    public static <T> T getJsonValue(String urlPattern, String jsonPath) {
        ApiCallRecord record = getLast(urlPattern);
        if (record == null || record.getResponseBody() == null) {
            logger.debug("getJsonValue: no matching record or empty body for pattern='{}'", urlPattern);
            return null;
        }
        try {
            String body = record.getResponseBody();
            if (body == null) return null;
            String bodyStr = body.toString();
            if (bodyStr.startsWith("[BINARY_DATA")) {
                logger.debug("getJsonValue: response is binary data, cannot extract JSON field for '{}'", urlPattern);
                return null;
            }
            T result = JsonPath.read(bodyStr, jsonPath);
            logger.debug("getJsonValue: pattern={}, jsonPath={}, result={} (type={})",
                urlPattern, jsonPath, result, result != null ? result.getClass().getSimpleName() : "null");
            return result;
        } catch (PathNotFoundException e) {
            logger.debug("getJsonValue: jsonPath '{}' not found in response for '{}' (path does not exist)", jsonPath, urlPattern);
            return null;
        } catch (Exception e) {
            logger.warn("getJsonValue: failed to parse JSON for pattern='{}', jsonPath='{}': {}", urlPattern, jsonPath, e.getMessage());
            return null;
        }
    }

    /**
     * 从最近匹配的 API 响应中提取 String 值
     */
    public static String getJsonString(String urlPattern, String jsonPath) {
        return safeCast(getJsonValue(urlPattern, jsonPath), String.class, urlPattern, jsonPath);
    }

    /**
     * 从最近匹配的 API 响应中提取 Integer 值
     */
    public static Integer getJsonInt(String urlPattern, String jsonPath) {
        return safeCast(getJsonValue(urlPattern, jsonPath), Integer.class, urlPattern, jsonPath);
    }

    /**
     * 从最近匹配的 API 响应中提取 Long 值
     */
    public static Long getJsonLong(String urlPattern, String jsonPath) {
        return safeCast(getJsonValue(urlPattern, jsonPath), Long.class, urlPattern, jsonPath);
    }

    /**
     * 从最近匹配的 API 响应中提取 Double 值
     */
    public static Double getJsonDouble(String urlPattern, String jsonPath) {
        return safeCast(getJsonValue(urlPattern, jsonPath), Double.class, urlPattern, jsonPath);
    }

    /**
     * 从最近匹配的 API 响应中提取 Boolean 值
     */
    public static Boolean getJsonBoolean(String urlPattern, String jsonPath) {
        return safeCast(getJsonValue(urlPattern, jsonPath), Boolean.class, urlPattern, jsonPath);
    }

    /**
     * 从最近匹配的 API 响应中提取 List 值
     *
     * @param <E> 列表元素类型
     */
    @SuppressWarnings("unchecked")
    public static <E> List<E> getJsonList(String urlPattern, String jsonPath) {
        Object raw = getJsonValue(urlPattern, jsonPath);
        if (isJsonValueEmpty(raw)) return null;
        if (raw instanceof List) return (List<E>) raw;
        try {
            if (raw instanceof Iterable) {
                List<E> result = new ArrayList<>();
                for (Object item : (Iterable<?>) raw) {
                    result.add((E) item);
                }
                return result;
            }
        } catch (Exception e) {
            logger.debug("getJsonList: failed to convert Iterable to List: {}", e.getMessage());
        }
        logger.warn("getJsonList: expected List but got {} for pattern='{}', jsonPath='{}'",
            raw.getClass().getSimpleName(), urlPattern, jsonPath);
        return null;
    }

    /**
     * 从最近匹配的 API 响应中提取 Map 值
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getJsonObject(String urlPattern, String jsonPath) {
        Object raw = getJsonValue(urlPattern, jsonPath);
        if (isJsonValueEmpty(raw)) return null;
        if (raw instanceof Map) return (Map<String, Object>) raw;
        logger.warn("getJsonObject: expected Map but got {} for pattern='{}', jsonPath='{}'",
            raw.getClass().getSimpleName(), urlPattern, jsonPath);
        return null;
    }

    /**
     * 检查指定模式的 API 是否已被捕获
     *
     * @param urlPattern URL 模式
     * @return true 如果存在匹配记录
     */
    public static boolean hasApiCaptured(String urlPattern) {
        return getLast(urlPattern) != null;
    }

    /**
     * 从所有匹配的 API 响应中提取 JSON 值列表
     *
     * @param <T> 返回值类型
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> getAllJsonValues(String urlPattern, String jsonPath) {
        List<ApiCallRecord> records = getAll(urlPattern);
        List<T> values = new ArrayList<>(records.size());
        for (ApiCallRecord record : records) {
            try {
                String body = record.getResponseBody();
                if (body == null) {
                    values.add(null);
                    continue;
                }
                String bodyStr = body.toString();
                if (bodyStr.startsWith("[BINARY_DATA")) {
                    values.add(null);
                    continue;
                }
                T result = JsonPath.read(bodyStr, jsonPath);
                values.add(result);
            } catch (Exception e) {
                logger.debug("getAllJsonValues: failed to extract '{}' from record {}: {}",
                    jsonPath, record.getUrl(), e.getMessage());
                values.add(null);
            }
        }
        return values;
    }

    /**
     * 从所有匹配的 API 响应中提取 String 值列表
     */
    public static List<String> getAllJsonStrings(String urlPattern, String jsonPath) {
        List<Object> rawList = getAllJsonValues(urlPattern, jsonPath);
        List<String> result = new ArrayList<>(rawList.size());
        for (Object raw : rawList) {
            result.add(safeCast(raw, String.class, urlPattern, jsonPath));
        }
        return result;
    }

    /**
     * 从所有匹配的 API 响应中提取 Integer 值列表
     */
    public static List<Integer> getAllJsonInts(String urlPattern, String jsonPath) {
        List<Object> rawList = getAllJsonValues(urlPattern, jsonPath);
        List<Integer> result = new ArrayList<>(rawList.size());
        for (Object raw : rawList) {
            result.add(safeCast(raw, Integer.class, urlPattern, jsonPath));
        }
        return result;
    }

    /**
     * 从所有匹配的 API 响应中提取 Long 值列表
     */
    public static List<Long> getAllJsonLongs(String urlPattern, String jsonPath) {
        List<Object> rawList = getAllJsonValues(urlPattern, jsonPath);
        List<Long> result = new ArrayList<>(rawList.size());
        for (Object raw : rawList) {
            result.add(safeCast(raw, Long.class, urlPattern, jsonPath));
        }
        return result;
    }

    /**
     * 从所有匹配的 API 响应中提取 Double 值列表
     */
    public static List<Double> getAllJsonDoubles(String urlPattern, String jsonPath) {
        List<Object> rawList = getAllJsonValues(urlPattern, jsonPath);
        List<Double> result = new ArrayList<>(rawList.size());
        for (Object raw : rawList) {
            result.add(safeCast(raw, Double.class, urlPattern, jsonPath));
        }
        return result;
    }

    /**
     * 从所有匹配的 API 响应中提取 Boolean 值列表
     */
    public static List<Boolean> getAllJsonBooleans(String urlPattern, String jsonPath) {
        List<Object> rawList = getAllJsonValues(urlPattern, jsonPath);
        List<Boolean> result = new ArrayList<>(rawList.size());
        for (Object raw : rawList) {
            result.add(safeCast(raw, Boolean.class, urlPattern, jsonPath));
        }
        return result;
    }

    /**
     * 等待 JSON 值满足条件
     *
     * @param urlPattern URL 模式
     * @param jsonPath JsonPath 表达式
     * @param timeoutSeconds 超时秒数
     * @param <T> 返回值类型
     * @return 匹配的值
     */
    public static <T> T waitForJsonValue(String urlPattern, String jsonPath, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        logger.info("Waiting for JSON value: pattern='{}', jsonPath='{}', timeout={}s", urlPattern, jsonPath, timeoutSeconds);

        while (System.currentTimeMillis() < deadline) {
            T value = getJsonValue(urlPattern, jsonPath);
            if (!isJsonValueEmpty(value)) {
                logger.info("JSON value ready after {}ms: {}={} (type={})",
                    (deadline - System.currentTimeMillis() + timeoutSeconds * 1000), jsonPath, value,
                    value.getClass().getSimpleName());
                return value;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("waitForJsonValue interrupted for pattern='{}'", urlPattern);
                return null;
            }
        }

        logger.warn("waitForJsonValue TIMEOUT after {}s for pattern='{}', jsonPath='{}'. Last known APIs:",
            timeoutSeconds, urlPattern, jsonPath);
        List<ApiCallRecord> captured = apiCallHistory.get();
        for (int i = 0; i < Math.min(captured.size(), 5); i++) {
            ApiCallRecord r = captured.get(i);
            logger.warn("  [{}] {} {}", i+1, r.getMethod(), simplifyUrl(r.getUrl()));
        }
        return null;
    }

    /**
     * 等待 JSON String 值
     */
    public static String waitForJsonString(String urlPattern, String jsonPath, int timeoutSeconds) {
        return safeCast(waitForJsonValue(urlPattern, jsonPath, timeoutSeconds), String.class, urlPattern, jsonPath);
    }

    /**
     * 等待 JSON Integer 值
     */
    public static Integer waitForJsonInt(String urlPattern, String jsonPath, int timeoutSeconds) {
        return safeCast(waitForJsonValue(urlPattern, jsonPath, timeoutSeconds), Integer.class, urlPattern, jsonPath);
    }

    /**
     * 等待 JSON Long 值
     */
    public static Long waitForJsonLong(String urlPattern, String jsonPath, int timeoutSeconds) {
        return safeCast(waitForJsonValue(urlPattern, jsonPath, timeoutSeconds), Long.class, urlPattern, jsonPath);
    }

    /**
     * 等待 JSON Double 值
     */
    public static Double waitForJsonDouble(String urlPattern, String jsonPath, int timeoutSeconds) {
        return safeCast(waitForJsonValue(urlPattern, jsonPath, timeoutSeconds), Double.class, urlPattern, jsonPath);
    }

    /**
     * 等待 JSON Boolean 值
     */
    public static Boolean waitForJsonBoolean(String urlPattern, String jsonPath, int timeoutSeconds) {
        return safeCast(waitForJsonValue(urlPattern, jsonPath, timeoutSeconds), Boolean.class, urlPattern, jsonPath);
    }

    /**
     * 等待 JSON 值等于指定字符串
     *
     * @return true 如果在超时前匹配
     */
    public static boolean waitForJsonEquals(String urlPattern, String jsonPath, String expectedValue, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Object value = getJsonValue(urlPattern, jsonPath);
            if (!isJsonValueEmpty(value) && expectedValue.equals(value.toString())) {
                logger.info("waitForJsonEquals matched: {}={} (type={})", jsonPath, value,
                    value.getClass().getSimpleName());
                return true;
            }
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        logger.warn("waitForJsonEquals TIMEOUT: expected '{}' but not matched within {}s", expectedValue, timeoutSeconds);
        return false;
    }

    /**
     * 等待 JSON 值等于指定值（泛型）
     *
     * @param <T> 期望值类型
     * @return true 如果在超时前匹配
     */
    @SuppressWarnings("unchecked")
    public static <T> boolean waitForJsonValueEquals(String urlPattern, String jsonPath, T expectedValue, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        Class<?> expectedType = expectedValue != null ? expectedValue.getClass() : Object.class;
        while (System.currentTimeMillis() < deadline) {
            T value = getJsonValue(urlPattern, jsonPath);
            if (!isJsonValueEmpty(value)) {
                T typedValue = safeCast(value, (Class<T>) expectedType, urlPattern, jsonPath);
                if (typedValue != null && expectedValue.equals(typedValue)) {
                    logger.info("waitForJsonValueEquals matched: {}={} (type={})",
                        jsonPath, typedValue, expectedType.getSimpleName());
                    return true;
                }
                if (value != null && expectedValue.equals(value)) {
                    logger.info("waitForJsonValueEquals matched (raw): {}={} (type={})",
                        jsonPath, value, value.getClass().getSimpleName());
                    return true;
                }
            }
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        logger.warn("waitForJsonValueEquals TIMEOUT: expected '{}' ({}) but not matched within {}s",
            expectedValue, expectedType.getSimpleName(), timeoutSeconds);
        return false;
    }

    /**
     * 获取最近匹配的响应体（同步读取）
     * 直接从 Response 同步获取 body，不会返回 null
     *
     * @param urlPattern URL 模式
     * @return 响应体字符串，如果记录不存在或读取失败返回 null
     */
    public static String getLastBody(String urlPattern) {
        ApiCallRecord record = getLast(urlPattern);
        if (record == null) {
            logger.debug("getLastBody: no record found for pattern: {}", urlPattern);
            return null;
        }
        return record.getResponseBodySync();
    }

    /**
     * 获取当前线程的 API 调用历史
     *
     * @return 不可修改的 API 记录列表
     */
    public static List<ApiCallRecord> getHistory() {
        return Collections.unmodifiableList(apiCallHistory.get());
    }

    /**
     * 清空当前线程的 API 调用历史和配置
     */
    public static void clearHistory() {
        apiCallHistory.get().clear();
        hasLoggedToSerenity.set(false);
        matchedTargetApiCount.get().set(0);
        allTargetApisCaptured.set(false);
        targetApiPatterns.get().clear();
        patternMatchCounts.get().clear();
        configuredMinMatches.set(1);
        configuredAutoStopOnMatch.set(true);
        configuredTimeout.set(60);
        lastApiActivityTime = 0;
        cachedSnapshot.remove();
    }

    /**
     * 清空当前线程的期望配置
     */
    public static void clearExpectations() {
        apiExpectations.get().clear();
    }

    /**
     * 【新架构】异步执行任务
     * 用于非阻塞的日志记录、断言验证等操作
     *
     * @param task 要执行的任务
     */
    public static void runAsync(Runnable task) {
        if (task == null) return;
        try {
            ASYNC_EXECUTOR.execute(task);
        } catch (Exception e) {
            logger.debug("[Async] Failed to execute task: {}", e.getMessage());
        }
    }

    /**
     * 【新架构】关闭异步执行器
     * 项目结束时应调用
     */
    public static void shutdownAsyncExecutor() {
        try {
            ASYNC_EXECUTOR.shutdownNow();
            logger.debug("[Async] Executor shutdown");
        } catch (Exception e) {
            logger.debug("[Async] Error during shutdown: {}", e.getMessage());
        }
    }

    /**
     * 设置目标主机过滤器
     *
     * @param host 主机地址或完整 URL
     */
    public static void setTargetHost(String host) {
        targetHost.set(extractHost(host));
        logger.info("Target host set to: {}", targetHost.get());
    }

    /**
     * 清除目标主机过滤器（捕获所有主机）
     */
    public static void clearTargetHost() {
        targetHost.remove();
    }

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

    private static final Object stopMonitorLock = new Object();

    /**
     * 停止所有监控并输出报告
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
        
        boolean isMainThread = isStepEventBusAvailable();
        
        if (isMainThread) {
            logResults();
            logger.info("All monitoring stopped & results logged to Serenity (by {})", caller);
        } else {
            cacheReportForLaterWrite();
            logger.info("All monitoring stopped (by {}), report cached for main-thread flush", caller);
        }
    }

    private static boolean isStepEventBusAvailable() {
        try {
            StepEventBus eventBus = StepEventBus.getEventBus();
            return eventBus != null && eventBus.getBaseStepListener() != null;
        } catch (Exception e) {
            logger.debug("StepEventBus not available in current thread: {}", e.getMessage());
            return false;
        }
    }

    private static int getEffectiveTimeout() {
        return configuredTimeout.get();
    }

    private static void cacheReportForLaterWrite() {
        if (reportPending) return;

        // 先记录配置报告
        recordMonitorConfiguration();

        if (apiCallHistory.get().isEmpty()) return;

        try {
            String[] report = buildReportContent();
            if (report != null) {
                synchronized (RealApiMonitor.class) {
                    reportPending = true;
                    pendingReportTitle = report[0];
                    pendingReportContent = report[1];
                }
                logger.info("API monitor report cached for later flush: title={}", report[0]);
            }
        } catch (Exception e) {
            logger.debug("Failed to build cached report: {}", e.getMessage());
        }
    }

    /**
     * 刷新待处理的报告到 Serenity
     */
    public static void flushPendingReport() {
        if (!reportPending) return;
        
        String title, content;
        synchronized (RealApiMonitor.class) {
            if (!reportPending) return;
            title = pendingReportTitle;
            content = pendingReportContent;
            reportPending = false;
            pendingReportTitle = null;
            pendingReportContent = null;
        }
        
        if (title == null || content == null) return;
        
        try {
            if (!isStepEventBusAvailable()) {
                logger.warn("flushPendingReport called outside test context, re-caching");
                synchronized (RealApiMonitor.class) {
                    reportPending = true;
                    pendingReportTitle = title;
                    pendingReportContent = content;
                }
                return;
            }
            
            logger.info("Flushing cached API monitor report to Serenity: {}", title);
            Serenity.recordReportData()
                .withTitle(title)
                .andContents(content);
            hasLoggedToSerenity.set(true);
            logger.info("Successfully flushed API monitor report to Serenity");
        } catch (Exception e) {
            logger.error("Failed to flush cached API monitor report", e);
        }
    }

    private static String[] buildReportContent() {
        List<ApiCallRecord> history = apiCallHistory.get();
        if (history.isEmpty()) return null;

        List<ApiCallRecord> recordsToReport;
        List<String> patterns = targetApiPatterns.get();
        if (!patterns.isEmpty()) {
            recordsToReport = new ArrayList<>();
            for (ApiCallRecord record : history) {
                String url = record.getUrl();
                for (String pattern : patterns) {
                    if (url.contains(pattern)) {
                        recordsToReport.add(record);
                        break;
                    }
                }
            }
        } else {
            recordsToReport = new ArrayList<>(apiCallHistory.get());
        }
        
        if (recordsToReport.isEmpty()) return null;
        
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"summary\": {\n");
        json.append("    \"totalApiCalls\": ").append(recordsToReport.size()).append(",\n");
        json.append("    \"targetApiPatterns\": ").append(patterns).append(",\n");

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

        String title = patterns.isEmpty()
            ? "Monitored API Calls (" + recordsToReport.size() + ")"
            : "Monitored API Calls (" + recordsToReport.size() + "/" + patterns.size() + " target)";
        
        return new String[]{title, json.toString()};
    }

    /**
     * 【v3.5 新增】限制 API 调用历史大小，防止内存泄漏
     * 使用同步块保护，避免并发修改异常
     */
    private static void trimHistoryIfNeeded() {
        List<ApiCallRecord> history = apiCallHistory.get();
        int currentSize = history.size();
        
        if (currentSize > MAX_API_HISTORY_SIZE) {
            synchronized (history) {
                // 再次检查（防止并发）
                if (history.size() <= MAX_API_HISTORY_SIZE) return;
                
                int toKeepCount = MAX_API_HISTORY_SIZE;
                // 【修复】CopyOnWriteArrayList 迭代器不支持 remove()，subList().clear() 也不支持
                // 改用 ArrayList 中转 + removeAll() 方案
                List<ApiCallRecord> allRecords = new ArrayList<>(history);
                List<ApiCallRecord> toRemove = new ArrayList<>(allRecords.subList(0, allRecords.size() - toKeepCount));
                history.removeAll(toRemove);
                
                logger.debug("[History] Trimmed {} old records, kept {} latest (max={})", 
                    toRemove.size(), history.size(), MAX_API_HISTORY_SIZE);
            }
        }
    }

    private static void logSummaryToSerenityReport() {
        if (apiCallHistory.get().isEmpty() || hasLoggedToSerenity.get()) return;
        
        hasLoggedToSerenity.set(true);
        
        try {
            String[] report = buildReportContent();
            if (report == null) {
                logger.info("No target APIs captured to report to Serenity");
                return;
            }
            
            logger.info("Recording {} target API(s) to Serenity report", 
                apiCallHistory.get().size());
            Serenity.recordReportData()
                .withTitle(report[0])
                .andContents(report[1]);
            
            logger.info("Successfully recorded to Serenity report: {}", report[0]);
        } catch (Exception e) {
            logger.debug("Failed to log summary: {}", e.getMessage());
        }
    }

    /**
     * 输出监控结果到 Serenity 报告
     */
    public static void logResults() {
        if (hasLoggedToSerenity.get()) {
            return;
        }
        recordMonitorConfiguration();
        if (!apiCallHistory.get().isEmpty()) {
            logSummaryToSerenityReport();
        }
    }

    /**
     * 记录监控配置到 Serenity 报告
     */
    private static void recordMonitorConfiguration() {
        try {
            List<String> patterns = targetApiPatterns.get();
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"monitoredApis\": ");
            json.append(patterns.toString());
            json.append(",\n");
            json.append("  \"timeout\": ").append(configuredTimeout.get()).append(",\n");
            json.append("  \"minMatches\": ").append(configuredMinMatches.get()).append(",\n");
            json.append("  \"autoStopOnMatch\": ").append(configuredAutoStopOnMatch.get()).append("\n");
            json.append("}\n");

            Serenity.recordReportData()
                .withTitle("Monitor Configuration")
                .andContents(json.toString());
            logger.debug("Recorded monitor configuration to Serenity report");
        } catch (Exception e) {
            logger.debug("Failed to record monitor configuration: {}", e.getMessage());
        }
    }

    /**
     * 清理所有 ThreadLocal，防止内存泄漏
     * <p>应在测试用例结束、异常退出、Thread 复用时调用
     */
    public static void removeThreadLocals() {
        apiCallHistory.remove();
        apiExpectations.remove();
        monitoringFailure.remove();
        matchedTargetApiCount.remove();
        allTargetApisCaptured.remove();
        targetApiPatterns.remove();
        patternMatchCounts.remove();
        configuredMinMatches.remove();
        configuredAutoStopOnMatch.remove();
        configuredTimeout.remove();
        hasLoggedToSerenity.remove();
        targetHost.remove();
        cachedSnapshot.remove();
        logger.trace("All ThreadLocals removed");
    }

    /**
     * 重置状态，准备下一个 Scenario
     *
     * <p>移除所有监听器，清理 ThreadLocal 状态
     */
    public static void resetForNextScenario() {
        for (Map.Entry<BrowserContext, java.util.function.Consumer<Response>> entry : new ArrayList<>(contextListeners.entrySet())) {
            try {
                entry.getKey().offResponse(entry.getValue());
                logger.debug("resetForNextScenario: offResponse for context");
            } catch (Exception e) {
                logger.debug("resetForNextScenario: offResponse failed (context may be closed): {}", e.getMessage());
            }
        }
        for (Map.Entry<Page, java.util.function.Consumer<Response>> entry : new ArrayList<>(pageListeners.entrySet())) {
            try {
                entry.getKey().offResponse(entry.getValue());
                logger.debug("resetForNextScenario: offResponse for page");
            } catch (Exception e) {
                logger.debug("resetForNextScenario: offResponse failed (page may be closed): {}", e.getMessage());
            }
        }
        
        hasLoggedToSerenity.set(false);
        reportPending = false;
        pendingReportTitle = null;
        pendingReportContent = null;
        contextMonitoringStopped.clear();
        pageMonitoringStopped.clear();
        contextListeners.clear();
        pageListeners.clear();
        
        // 统一清理所有 ThreadLocal
        removeThreadLocals();
    }

    /**
     * 强制清理所有状态（紧急清理）
     *
     * <p>停止监控、重置场景、清除目标主机
     */
    public static void forceCleanAll() {
        logger.info("=== forceCleanAll: Emergency cleanup of ALL RealApiMonitor state ===");
        try {
            stopMonitoring();
        } catch (Exception e) {
            logger.debug("forceCleanAll: stopMonitoring failed (may already be stopped)", e);
        }
        try {
            resetForNextScenario();
        } catch (Exception e) {
            logger.debug("forceCleanAll: resetForNextScenario failed", e);
        }
        try {
            clearTargetHost();
        } catch (Exception e) {
            logger.debug("forceCleanAll: clearTargetHost failed", e);
        }
        logger.info("=== forceCleanAll: Complete ===");
    }

    private static void recordApiCall(Response response, Request request) {
        try {
            if (response == null || request == null || response.url() == null) {
                logger.debug("[API] Skipping null response/request/url (may be redirect or abnormal response)");
                return;
            }
            String requestId = UUID.randomUUID().toString();
            
            // 【v3.6 修复】不再在响应监听器中同步读取 body，改为异步读取
            // 同步读取 response.text() 会阻塞 Playwright 主线程，导致 UI 卡顿
            String capturedBody = null;
            boolean isRedirectResponse = response.status() == 301 || response.status() == 302 
                || response.status() == 303 || response.status() == 307 || response.status() == 308;
            
            // 检查 Content-Length，超过阈值则不读取
            long contentLength = -1;
            try {
                contentLength = response.headers().containsKey("content-length") 
                    ? Long.parseLong(response.headers().get("content-length")) 
                    : -1;
            } catch (Exception ignored) {}
            
            if (isRedirectResponse) {
                capturedBody = "[REDIRECT status=" + response.status() + "]";
            } else if (contentLength > MAX_RESPONSE_BODY_SIZE) {
                capturedBody = "[BODY_SKIPPED size=" + contentLength + " bytes, use getResponseBodyAsync() to read]";
                logger.debug("[API] Response body skipped (size={} > {}), use async API to read later", 
                    contentLength, MAX_RESPONSE_BODY_SIZE);
            }
            // body 将在 getResponseBody() 或 getResponseBodyAsync() 时读取
            
            ApiCallRecord record = new ApiCallRecord(
                requestId, response.url(), request.method(), System.currentTimeMillis(),
                null, null, response.status(), null, capturedBody, false
            );
            
            record.setResponse(response);
            record.setRequest(request);
            apiCallHistory.get().add(record);
            
            // 【v3.5 修复】容量限制，防止内存泄漏
            trimHistoryIfNeeded();

            long now = System.currentTimeMillis();
            long prevActivity = lastApiActivityTime;
            lastApiActivityTime = now;

            logger.debug("[API] {} {} - {}", request.method(), response.url(), response.status());

            if (prevActivity > 0 && configuredAutoStopOnMatch.get()) {
                long idleSeconds = (now - prevActivity) / 1000;
                int effectiveTimeout = getEffectiveTimeout();
                if (effectiveTimeout > 0 && idleSeconds >= effectiveTimeout) {
                    logger.warn("AUTO-STOP: No API calls for {}s (idle), auto-stopping. Total APIs: {}", 
                        idleSeconds, apiCallHistory.get().size());
                    stopMonitoring();
                    return;
                }
            }

            List<String> patterns = targetApiPatterns.get();
            if (!patterns.isEmpty() && !allTargetApisCaptured.get()) {
                String url = response.url();
                for (String pattern : patterns) {
                    if (url.contains(pattern)) {
                        int totalMatched = matchedTargetApiCount.get().incrementAndGet();
                        
                        AtomicInteger patternCount = patternMatchCounts.get().get(pattern);
                        int thisPatternCount = (patternCount != null) ? patternCount.incrementAndGet() : 1;
                        
                        int requiredMin = configuredMinMatches.get();
                        logger.info("[Target API #{} | {}#{}] {} {} - {}", 
                                totalMatched, pattern, thisPatternCount, request.method(), url, response.status());

                        boolean allSatisfied = true;
                        for (String p : patterns) {
                            AtomicInteger cnt = patternMatchCounts.get().get(p);
                            int current = (cnt != null) ? cnt.get() : 0;
                            if (current < requiredMin) {
                                allSatisfied = false;
                                break;
                            }
                        }

                        if (allSatisfied) {
                            allTargetApisCaptured.set(true);
                            logger.info("All {} target API(s) reached minMatches({}). Total captured: {}. {}",
                                    patterns.size(), requiredMin, totalMatched,
                                    configuredAutoStopOnMatch.get() ? "Stopping." : "(autoStopOnMatch=false, continuing to monitor)");
                            
                            if (configuredAutoStopOnMatch.get()) {
                                stopMonitoring();
                            }
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to record API call", e);
        }
    }

    private static String simplifyUrl(String url) {
        if (url == null) return "";
        int queryIndex = url.indexOf('?');
        if (queryIndex > 0) {
            return url.substring(0, Math.min(queryIndex, 60));
        }
        return url.length() > 60 ? url.substring(0, 60) + "..." : url;
    }

    private static boolean matchesTargetHost(String url) {
        String host = targetHost.get();
        if (host == null || host.isEmpty()) return true;
        try {
            URL urlObj = URI.create(url).toURL();
            return host.equals(urlObj.getHost());
        } catch (Exception e) {
            return url.contains(host);
        }
    }

    private static boolean isReadableText(String text) {
        if (text == null || text.isEmpty()) return true;
        int controlCount = 0;
        int total = Math.min(text.length(), 1024);
        for (int i = 0; i < total; i++) {
            char c = text.charAt(i);
            if (c < 32 && c != '\t' && c != '\n' && c != '\r') {
                controlCount++;
            }
        }
        return (controlCount * 100.0 / total) < 15;
    }

    private static String getContentType(Response response) {
        try {
            String ct = response.headers().get("content-type");
            return ct != null ? ct.split(";")[0].trim() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static ApiCallRecord getLastApiCall() {
        List<ApiCallRecord> history = apiCallHistory.get();
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    /**
     * API 调用记录
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
        private String responseBody;
        private final boolean isMocked;
        private Response response;
        private Request request;
        private volatile boolean bodyRead = false;
        private final Object bodyLock = new Object();

        public ApiCallRecord(String requestId, String url, String method, long timestamp,
                           Map<String, String> requestHeaders, Object requestBody,
                           int statusCode, Map<String, String> responseHeaders,
                           String responseBody, boolean isMocked) {
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

        public void setResponse(Response response) { this.response = response; }
        public void setRequest(Request request) { this.request = request; }

        public void markBodyRead() {
            synchronized (bodyLock) {
                this.bodyRead = true;
            }
        }

        public String getUrl() { return url; }
        public String getMethod() { return method; }
        public long getTimestamp() { return timestamp; }
        public int getStatusCode() { return statusCode; }

        /**
         * 获取响应体（惰性读取，优先返回缓存）
         * @return 已缓存的响应体，或 null（需调用 getResponseBodySync 获取）
         */
        public String getResponseBody() {
            synchronized (bodyLock) {
                if (bodyRead) {
                    return responseBody;
                }
                // 有缓存值（如 "[BODY_SKIPPED...]"），标记并返回
                if (responseBody != null) {
                    bodyRead = true;
                    return responseBody;
                }
                // 无缓存且未读取，返回 null
                return null;
            }
        }

        /**
         * 同步获取响应体（直接读取，不依赖缓存）
         * 适用于 getLastBody() 等需要确保返回 body 的场景
         * @return 响应体字符串，读取失败返回 null
         */
        public String getResponseBodySync() {
            // 优先返回缓存
            String cached = getResponseBody();
            if (cached != null) {
                return cached;
            }

            // 缓存为空，同步读取
            if (response != null) {
                try {
                    return response.text();
                } catch (Exception e) {
                    logger.debug("Failed to read response body: {}", e.getMessage());
                }
            }
            return null;
        }

        /**
         * 异步获取响应体（通过回调返回）
         * @param callback 读取完成后的回调
         */
        public void getResponseBodyAsync(java.util.function.Consumer<String> callback) {
            synchronized (bodyLock) {
                if (bodyRead && responseBody != null) {
                    callback.accept(responseBody);
                    return;
                }
                if (bodyRead) {
                    callback.accept(null);
                    return;
                }
            }

            if (response != null) {
                try {
                    String body = response.text();
                    synchronized (bodyLock) {
                        responseBody = body;
                        bodyRead = true;
                    }
                    callback.accept(body);
                } catch (Exception e) {
                    logger.debug("Failed to read response body: {}", e.getMessage());
                    synchronized (bodyLock) {
                        bodyRead = true;
                    }
                    callback.accept(null);
                }
            } else {
                callback.accept(null);
            }
        }

        public Object getRequestBody() {
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
            if (requestHeaders == null && request != null) {
                requestHeaders = new HashMap<>(request.headers());
            }
            return requestHeaders;
        }

        public Map<String, String> getResponseHeaders() {
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
     * API 期望值配置
     */
    public static class ApiExpectation {
        private final String endpoint;
        private Integer expectedStatusCode;
        private String description;

        private ApiExpectation(String endpoint) {
            this.endpoint = endpoint;
        }

        /**
         * 创建指定端点的期望配置
         */
        public static ApiExpectation forEndpoint(String endpoint) {
            return new ApiExpectation(endpoint);
        }

        /**
         * 设置期望的状态码
         */
        public ApiExpectation statusCode(int code) {
            this.expectedStatusCode = code;
            return this;
        }

        /**
         * 设置描述
         */
        public ApiExpectation description(String desc) {
            this.description = desc;
            return this;
        }

        public String getEndpoint() { return endpoint; }
        public String getDescription() { return description != null ? description : "Status=" + expectedStatusCode; }

        /**
         * 验证记录是否满足期望
         *
         * @param record API 调用记录
         * @throws AssertionError 如果不满足期望
         */
        public void validate(ApiCallRecord record) {
            if (expectedStatusCode != null) {
                assertThat("Status code mismatch for " + record.getUrl(), 
                    record.getStatusCode(), equalTo(expectedStatusCode));
            }
        }
    }
}
