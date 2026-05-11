package com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import net.serenitybdd.core.Serenity;
import net.thucydides.core.steps.StepEventBus;
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

    // ==================== 线程安全重构（支持并行测试）====================
    // ⭐ 所有核心存储改为 ThreadLocal，彻底隔离并行场景/多 scenario 的数据
    // 原因：static 全局变量会导致 A 场景的 API 被 B 场景捕获、历史记录互相污染、报告错乱

    // 存储 API 调用记录（每线程独立）
    private static final ThreadLocal<List<ApiCallRecord>> apiCallHistory = ThreadLocal.withInitial(CopyOnWriteArrayList::new);

    // 存储 API 期望（每线程独立）
    private static final ThreadLocal<Map<String, ApiExpectation>> apiExpectations = ThreadLocal.withInitial(ConcurrentHashMap::new);

    // 监控状态（以 BrowserContext/Page 实例为 key → 天然隔离不同线程的 context/page）
    private static final Map<BrowserContext, Boolean> contextMonitoringStopped = new ConcurrentHashMap<>();
    private static final Map<Page, Boolean> pageMonitoringStopped = new ConcurrentHashMap<>();

    // 已注册的监听器句柄（用于真正移除，防止泄漏）
    private static final Map<BrowserContext, java.util.function.Consumer<Response>> contextListeners = new ConcurrentHashMap<>();
    private static final Map<Page, java.util.function.Consumer<Response>> pageListeners = new ConcurrentHashMap<>();

    // 记录标志 - 防止重复记录（每线程独立）
    private static final ThreadLocal<Boolean> hasLoggedToSerenity = ThreadLocal.withInitial(() -> false);

    // 目标 Host 过滤（全局配置，通常不变；如需每线程不同可改为 ThreadLocal）
    private static volatile String targetHost = null;

    // 监控失败异常（每线程独立）
    private static final ThreadLocal<AssertionError> monitoringFailure = ThreadLocal.withInitial(() -> null);

    // 目标 API 匹配计数（用于自动停止）（每线程独立）
    private static final ThreadLocal<AtomicInteger> matchedTargetApiCount = ThreadLocal.withInitial(AtomicInteger::new);

    // 是否已捕获到所有目标 API（每线程独立）
    private static final ThreadLocal<Boolean> allTargetApisCaptured = ThreadLocal.withInitial(() -> false);

    // ⭐ 每个目标API模式的匹配次数（用于 minMatches 功能）
    private static final ThreadLocal<Map<String, AtomicInteger>> patternMatchCounts = ThreadLocal.withInitial(ConcurrentHashMap::new);
    
    // ⭐ 当前配置的 minMatches 值（每线程独立）
    private static final ThreadLocal<Integer> configuredMinMatches = ThreadLocal.withInitial(() -> 1);

    // ⭐ 是否自动停止监控（每线程独立，默认 true — 没配置时默认自动停止）
    private static final ThreadLocal<Boolean> configuredAutoStopOnMatch = ThreadLocal.withInitial(() -> true);

    // ⭐ 当前配置的超时时间（每线程独立，单位秒，0=不自动超时）
    private static final ThreadLocal<Integer> configuredTimeout = ThreadLocal.withInitial(() -> 60);

    // ⭐ 跨线程报告缓存（保持 static volatile：异步线程 → 主线程传递数据）
    // 解决 ApiMonitor-AsyncStop 线程无 Serenity 上下文导致 CurrentListener is null 的问题
    private static volatile boolean reportPending = false;
    private static volatile String pendingReportTitle = null;
    private static volatile String pendingReportContent = null;

    // ⭐ 跨线程 API 活动时间戳（volatile 全局变量，解决 ApiMonitor-AutoStop daemon 线程无法访问
    //   主线程 ThreadLocal<apiCallHistory> 的问题。每次 recordApiCall 成功后更新此时间戳，
    //   AutoStop timer 通过此时间戳判断是否有新 API 活动）
    private static volatile long lastApiActivityTime = 0;

    // 目标 API 的 URL 模式列表（每线程独立）
    private static final ThreadLocal<List<String>> targetApiPatterns = ThreadLocal.withInitial(CopyOnWriteArrayList::new);

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
        
        // 回调支持：捕获到目标 API 时执行自定义操作
        private java.util.function.Consumer<ApiCallRecord> onMatchCallback;
        private boolean stopOnFirstMatch = false;
        
        // ⭐ 每个目标API的最小匹配次数（默认1，即每个pattern至少命中1次就停止）
        // 解决场景：同一API连续请求多次（如轮询/刷新），需要等待N次后才停止
        private int minMatches = 1;

        /**
         * ⭐ 是否在所有目标API都达到 minMatches 后自动停止监控（默认 true）
         * 
         * 当 true（默认）：匹配到目标 API 且满足 minCounts → 自动 stopMonitoring()
         * 当 false：匹配到后仅记录，不自动停止，直到 timeout 时间到才停止
         * 
         * 适用场景：
         * - true（默认）：等一个/几个 API 就够了（如登录配置加载完就停止）
         * - false：分页、轮询、连续请求等场景，需要持续捕获同一种 API 的多次调用
         *
         * 示例：
         * // 分页场景：持续捕获 /api/list?page=* 直到超时
         * RealApiMonitor.monitor(context)
         *     .api("/api/list")
         *     .autoStopOnMatch(false)   // 不自动停止，按 timeout 控制
         *     .timeout(60)
         *     .start();
         */
        private boolean autoStopOnMatch = true;
        
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
         * 设置超时时间（秒），0 或负数表示无限等待（不自动停止）
         */
        public MonitorBuilder timeout(int seconds) {
            this.timeoutSeconds = seconds;
            return this;
        }
        
        /**
         * 捕获到目标 API 后执行自定义回调操作（非阻塞）
         * 
         * 示例：
         * RealApiMonitor.monitor(context)
         *     .api("/api/payment")
         *     .then(record -> {
         *         System.out.println("Payment API: " + record.getResponseBody());  // String 类型，直接用
         *     })
         *     .timeout(30)
         *     .start();
         */
        public MonitorBuilder then(java.util.function.Consumer<ApiCallRecord> callback) {
            this.onMatchCallback = callback;
            return this;
        }
        
        /**
         * 首次匹配到任一目标 API 后自动停止监控
         * 配合 .then() 使用，适合"等一个 API 就够了"的场景
         */
        public MonitorBuilder stopOnFirstMatch() {
            this.stopOnFirstMatch = true;
            return this;
        }
        
        /**
         * ⭐ 设置每个目标API的最小匹配次数（默认1）
         * 
         * 解决场景：同一API连续请求多次（如轮询、token刷新、分页）
         * 默认行为：每个pattern命中1次后，所有pattern都满足时自动停止
         * 设置minMatches(3)后：每个pattern需命中3次才触发自动停止
         *
         * @param n 每个目标API最少需要被捕获的次数
         * @return this构建器实例
         *
         * 示例：
         * // logon/config 会被请求3次，等待全部捕获后才停止
         * RealApiMonitor.monitor(context)
         *     .api("logon/config")
         *     .minMatches(3)      // 等待该API被捕获3次
         *     .timeout(60)
         *     .start();
         */
        public MonitorBuilder minMatches(int n) {
            this.minMatches = Math.max(1, n); // 至少为1
            return this;
        }

        /**
         * ⭐ 设置是否在所有目标 API 达到 minMatches 后自动停止监控（默认 true）
         *
         * 当 true（默认）：匹配到目标 API 且满足 minMatches → 自动调用 stopMonitoring()
         * 当 false：匹配到后仅记录，不自动停止，持续监听直到 timeout 超时才停止
         *
         * 适用场景：
         * - true（默认）：登录配置加载、单次 API 验证等"等到了就停"的场景
         * - false：分页请求、轮询刷新、连续点击等需要持续捕获同一种 API 多次调用的场景
         *
         * @param autoStop true=自动停止（默认），false=不自动停止，按超时控制
         * @return this构建器实例
         *
         * 示例：
         * // 分页：持续捕获所有分页请求直到60s超时
         * RealApiMonitor.monitor(context)
         *     .api("/api/data/list")
         *     .autoStopOnMatch(false)
         *     .timeout(60)
         *     .start();
         */
        public MonitorBuilder autoStopOnMatch(boolean autoStop) {
            this.autoStopOnMatch = autoStop;
            return this;
        }
        
        /**
         * 启动后阻塞等待直到任一目标 API 被捕获
         * 
         * @param timeoutSeconds 最大等待时间（秒）
         * @return 捕获到的第一条匹配记录，超时返回 null
         * 
         * 示例：
         * ApiCallRecord record = RealApiMonitor.monitor(context)
         *     .api("/api/payment")
         *     .timeout(60)
         *     .start()
         *     .waitForResponse(30);  // 最多等30秒
         */
        public ApiCallRecord waitForResponse(int timeoutSeconds) {
            // 先确保 start 已被调用（启动监听）
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
            
            // ⭐ 修复6：超时增强诊断
            List<ApiCallRecord> captured = apiCallHistory.get();
            logger.warn("waitForResponse TIMEOUT after {}s. Target patterns: {}. Captured {}/{} APIs:", 
                timeoutSeconds, apis.keySet(), captured.size(), captured.size());
            if (!captured.isEmpty()) {
                for (int i = 0; i < Math.min(captured.size(), 10); i++) {
                    ApiCallRecord r = captured.get(i);
                    logger.warn("  [{}] {} {} (status={})", i+1, r.getMethod(), simplifyUrl(r.getUrl()), r.getStatusCode());
                }
            }
            return null;
        }
        
        /**
         * 异步启动监控（非阻塞）
         */
        public void start() {
            logger.info("========== Starting async API monitoring ({} target APIs, timeout: {}s, callback: {}, minMatches: {}, autoStopOnMatch: {}) ==========", 
                apis.size(), timeoutSeconds, onMatchCallback != null ? "YES" : "NO", minMatches, autoStopOnMatch);
            
            // ⭐ 关键顺序：先停掉旧监听器（在 clearHistory 清掉引用之前）
            if (context != null) {
                markExistingListenerStopped(context);
            } else if (page != null) {
                markExistingListenerStopped(page);
            }
            
            clearHistory();
            clearExpectations();
            
            // 保存目标 API 模式（用于后续过滤）
            targetApiPatterns.get().clear();
            matchedTargetApiCount.get().set(0);
            allTargetApisCaptured.set(false);
            
            // ⭐ 保存 minMatches 配置 + 初始化每模式计数器 + autoStopOnMatch + timeout
            configuredMinMatches.set(minMatches);
            configuredAutoStopOnMatch.set(autoStopOnMatch);
            configuredTimeout.set(timeoutSeconds);
            patternMatchCounts.get().clear();
            for (String pattern : apis.keySet()) {
                patternMatchCounts.get().put(pattern, new AtomicInteger(0));
            }
            
            // 设置期望
            for (Map.Entry<String, Integer> entry : apis.entrySet()) {
                String urlPattern = entry.getKey();
                String regex = toRegex(urlPattern);
                targetApiPatterns.get().add(urlPattern); // 保存原始模式
                if (entry.getValue() > 0) {
                    apiExpectations.get().put(regex, ApiExpectation.forEndpoint(regex).statusCode(entry.getValue()));
                }
                logger.info("  - Target API: {} -> Expected Status: {}", urlPattern, entry.getValue() > 0 ? entry.getValue() : "any");
            }
            
            // 启动监听
            startListening();
            
            // 启动自动停止定时器（timeout > 0 时才启动）
            if (timeoutSeconds > 0) {
                startAutoStopTimer();
            } else {
                logger.info("No timeout set (timeout=0), monitoring until manually stopped");
            }
        }
        
        private void startListening() {
            if (context != null) {
                // 先移除旧的监听器，防止多次 start 导致监听器累积泄漏
                removeExistingListener(context);
                
                contextMonitoringStopped.put(context, false);
                
                // ⭐ 默认严格模式：配置了 .api(pattern) 时只记录匹配目标模式的响应
                // 没配目标 API 时（apis 为空）才记录全部（兼容调试场景）
                final boolean hasTargetPatterns = !apis.isEmpty();
                
                java.util.function.Consumer<Response> handler = response -> {
                    if (contextMonitoringStopped.getOrDefault(context, false)) return;
                    if (!matchesTargetHost(response.url())) return;
                    if (isStaticResource(response.url())) return;
                    
                    // 默认严格模式：有目标模式时，不匹配的 API 静默忽略
                    if (hasTargetPatterns && !isTargetPatternMatch(response.url())) {
                        return;
                    }
                    
                    recordApiCall(response, response.request());
                    
                    // 实时验证（状态码校验）
                    validateRealTime(response);
                    
                    // 执行自定义回调：目标 API 匹配后立即回调
                    invokeCallbackIfMatched(response);
                };
                contextListeners.put(context, handler);
                context.onResponse(handler);
            } else if (page != null) {
                // 先移除旧的监听器
                removeExistingListener(page);
                
                pageMonitoringStopped.put(page, false);
                
                final boolean hasTargetPatterns = !apis.isEmpty();
                
                java.util.function.Consumer<Response> handler = response -> {
                    if (pageMonitoringStopped.getOrDefault(page, false)) return;
                    if (!matchesTargetHost(response.url())) return;
                    if (isStaticResource(response.url())) return;
                    
                    // 默认严格模式：有目标模式时，不匹配的 API 静默忽略
                    if (hasTargetPatterns && !isTargetPatternMatch(response.url())) {
                        return;
                    }
                    
                    recordApiCall(response, response.request());
                    
                    // 实时验证（状态码校验）
                    validateRealTime(response);
                    
                    // 执行自定义回调
                    invokeCallbackIfMatched(response);
                };
                pageListeners.put(page, handler);
                page.onResponse(handler);
            }
        }
        
        /**
         * 检查 URL 是否匹配任一目标 API 模式
         */
        private boolean isTargetPatternMatch(String url) {
            List<String> patterns = targetApiPatterns.get();
            if (patterns.isEmpty()) return true; // 没有配置目标模式时放行所有
            for (String pattern : patterns) {
                if (url.contains(pattern) || url.matches(toRegex(pattern))) {
                    return true;
                }
            }
            return false;
        }
        
        /**
         * 检查当前响应是否匹配目标API模式，如果是则执行回调
         */
        private void invokeCallbackIfMatched(Response response) {
            if (onMatchCallback == null || targetApiPatterns.get().isEmpty()) return;
            
            String url = response.url();
            for (String pattern : targetApiPatterns.get()) {
                if (url.contains(pattern) || url.matches(toRegex(pattern))) {
                    // ⭐ 按当前 response URL 反查对应记录（避免取到并发场景下的错误全局最后一条）
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
                    break; // 只匹配第一个命中的 pattern
                }
            }
        }

        /**
         * 按 URL 精确查找对应的 API 记录
         * 用于在回调/验证中获取当前 response 对应的正确记录，
         * 避免使用 getLastApiCall() 在高并发下取错
         */
        private static ApiCallRecord findRecordByUrl(String url) {
            List<ApiCallRecord> history = apiCallHistory.get();
            // 从后往前找，优先返回最近的匹配记录（同 URL 可能有多次调用）
            for (int i = history.size() - 1; i >= 0; i--) {
                ApiCallRecord record = history.get(i);
                if (record.getUrl().equals(url)) {
                    return record;
                }
            }
            return null;
        }

        /**
         * ⭐ 修复2：真正移除已存在的旧监听器（不再只是标记停止）
         * Playwright 支持 context.offResponse(handler) / page.offResponse(handler)
         * 彻底移除回调引用，防止内存泄漏和回调堆积
         */
        private void removeExistingListener(BrowserContext ctx) {
            if (ctx != null && contextListeners.containsKey(ctx)) {
                try {
                    java.util.function.Consumer<Response> oldHandler = contextListeners.remove(ctx);
                    if (oldHandler != null) {
                        ctx.offResponse(oldHandler);  // ⭐ 真正从 Playwright 移除
                        logger.debug("Truly removed stale listener for context via offResponse()");
                    }
                } catch (Exception e) {
                    // offResponse 可能因 context 已关闭而失败，降级为标记停止
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
                        p.offResponse(oldHandler);  // ⭐ 真正从 Playwright 移除
                        logger.debug("Truly removed stale listener for page via offResponse()");
                    }
                } catch (Exception e) {
                    logger.debug("offResponse failed for page (may be closed), fallback to mark-stopped: {}", e.getMessage());
                    pageMonitoringStopped.put(p, true);
                    pageListeners.remove(p);
                }
            }
        }

        /**
         * 仅标记旧监听器为停止状态（不清理引用）
         * 用于 start() 在 clearHistory() 之前调用，确保旧回调不再处理数据
         */
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
        
        /**
         * ⭐ Auto-stop 检查（无额外线程）
         * 
         * 不再使用 new Thread() 创建 daemon 线程轮询。
         * 监控本身通过 Playwright onResponse 回调异步驱动，
         * 超时判断直接嵌入 recordApiCall() 中：每次 API 进来时，
         * 检查距上次活动的间隔是否超过 timeoutSeconds。
         * 
         * 优点：
         * - 零额外线程开销，无 ThreadLocal 隔离问题
         * - 与 onResponse 回调同线程，天然共享 apiCallHistory 等状态
         * - 更精确：只在有 API 活动时才触发超时检查
         */
        private void startAutoStopTimer() {
            // ⭐ 无操作：auto-stop 逻辑已内联到 recordApiCall() 的 checkAutoStopTimeout() 中
            // 仅在此记录配置，供 recordApiCall 读取
            logger.info("Auto-stop: will check in-record timeout={}s (inline, no extra thread)", timeoutSeconds);
        }
        
        private void validateRealTime(Response response) {
            String url = response.url();
            for (Map.Entry<String, ApiExpectation> entry : apiExpectations.get().entrySet()) {
                // ⭐ 只用正则匹配（toRegex 已正确转义特殊字符），不再依赖不可靠的 contains 兜底
                if (url.matches(entry.getKey())) {
                    // 按当前 response URL 反查对应记录，避免并发场景下取错全局最后一条
                    ApiCallRecord record = findRecordByUrl(url);
                    if (record == null) {
                        record = getLastApiCall(); // 兜底：反查失败时退回取最后一条
                    }
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

    // ==================== 核心方法 2: getLast() / getAll() - 获取记录 ====================
    
    /**
     * 获取指定 API 的最后一条记录（非阻塞）
     * 
     * @param urlPattern URL 匹配模式
     * @return API 调用记录，没有则返回 null
     */
    public static ApiCallRecord getLast(String urlPattern) {
        String regex = toRegex(urlPattern);
        List<ApiCallRecord> history = apiCallHistory.get();
        for (int i = history.size() - 1; i >= 0; i--) {
            ApiCallRecord record = history.get(i);
            if (record.getUrl().matches(regex) || record.getUrl().contains(urlPattern)) {
                return record;
            }
        }
        return null;
    }

    /**
     * 获取指定 API 的所有匹配记录（非阻塞）
     * 
     * 适用场景：
     * - 分页请求：同一接口被调用多次（page=1, page=2, ...）
     * - 重复请求：同一操作触发多次相同 API
     * - 历史回溯：需要查看某 API 的完整调用序列
     * 
     * @param urlPattern URL 匹配模式
     * @return 所有匹配记录的列表（按时间顺序，空的则返回空列表而非 null）
     * 
     * 示例：
     * // 分页场景：获取所有 /api/list?page=* 的调用
     * List<ApiCallRecord> pages = RealApiMonitor.getAll("/api/list");
     * for (ApiCallRecord page : pages) {
     *     String body = page.getResponseBody();
     *     // 处理每一页的数据...
     * }
     * 
     * // 查看某 API 被调用了几次
     * int callCount = RealApiMonitor.getAll("/api/refresh").size();
     */
    public static List<ApiCallRecord> getAll(String urlPattern) {
        String regex = toRegex(urlPattern);
        List<ApiCallRecord> history = apiCallHistory.get();
        List<ApiCallRecord> result = new ArrayList<>();
        for (ApiCallRecord record : history) {
            if (record.getUrl().matches(regex) || record.getUrl().contains(urlPattern)) {
                result.add(record);
            }
        }
        return result;
    }

    /**
     * 获取指定 API 的所有匹配记录数（便捷方法，非阻塞）
     * 
     * @param urlPattern URL 匹配模式
     * @return 匹配记录的数量
     * 
     * 示例：
     * // 刷新 token 接口是否被调用过
     * if (RealApiMonitor.getMatchCount("/api/auth/refresh") > 0) {
     *     // 说明发生了 token 刷新
     * }
     */
    public static int getMatchCount(String urlPattern) {
        return getAll(urlPattern).size();
    }

    /**
     * 获取指定 API 的所有响应体（非阻塞便捷方法）
     * 
     * @param urlPattern URL 匹配模式
     * @return 响应体字符串列表
     * 
     * 示例：
     * // 分页：获取每页数据
     * List<String> pageBodies = RealApiMonitor.getAllBodies("/api/data/list");
     * pageBodies.forEach(body -> System.out.println(body));
     */
    public static List<String> getAllBodies(String urlPattern) {
        List<ApiCallRecord> records = getAll(urlPattern);
        List<String> bodies = new ArrayList<>(records.size());
        for (ApiCallRecord record : records) {
            bodies.add(record.getResponseBody());  // responseBody 已是 String
        }
        return bodies;
    }

    /**
     * 等待目标 API 被捕获（阻塞式，解决 getLast/getLastBody 返回 null 的竞态问题）
     * 
     * @param urlPattern URL 匹配模式
     * @param timeoutSeconds 最大等待时间（秒）
     * @return 捕获到的 API 记录，超时返回 null
     * 
     * 示例：
     * ApiCallRecord record = RealApiMonitor.waitForApi("/api/payment", 30);
     * if (record != null) {
     *     String body = record.getResponseBody();
     * }
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
                Thread.sleep(200); // 200ms 轮询间隔，避免 CPU 占用过高
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("waitForApi interrupted while waiting for: {}", urlPattern);
                return null;
            }
        }
        
        // ⭐ 修复6：超时增强诊断 — 输出已捕获的 API 列表，方便排查"为什么没抓到"
        List<ApiCallRecord> captured = apiCallHistory.get();
        logger.warn("waitForApi TIMEOUT after {}s for pattern='{}'. Captured {}/{} APIs:", 
            timeoutSeconds, urlPattern, captured.size(), captured.size());
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
     * 等待目标 API 并返回响应体（便捷方法）
     */
    public static String waitForApiBody(String urlPattern, int timeoutSeconds) {
        ApiCallRecord record = waitForApi(urlPattern, timeoutSeconds);
        if (record == null) return null;
        return record.getResponseBody();
    }

    // ==================== 核心方法 5: JSON 快速取值 + 等待字段（企业级便捷操作）====================

    /**
     * 内部辅助：判断 JsonPath 返回值是否为"空值"
     * 覆盖场景：Java null、JSON null（字符串 "null"）、空字符串、空集合
     * 注意：Integer(0)、Boolean(false)、Double(0.0) 等合法值不会被误判
     */
    private static boolean isJsonValueEmpty(Object value) {
        if (value == null) return true;
        if (value instanceof String) {
            String s = ((String) value).trim();
            return s.isEmpty() || "null".equalsIgnoreCase(s);
        }
        if (value instanceof Collection) return ((Collection<?>) value).isEmpty();
        return false;
    }

    /**
     * 内部安全类型转换：将 JsonPath 返回的 Object 转为目标类型
     */
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
                // 单个元素包装成 List
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
     * 从指定 API 响应中快速提取 JSON 字段值（泛型基础版）
     *
     * @param urlPattern URL 匹配模式
     * @param jsonPath JsonPath 表达式，例如：
     *   - "$.data.token"        → 取 data 对象的 token 字段（String）
     *   - "$.data.user.name"    → 嵌套对象字段（String）
     *   - "$[0].id"             → 数组第一个元素的 id（Integer）
     *   - "$.items[*].price"    → 数组所有元素的价格（List<Integer>）
     * @return 字段值，未找到或路径不存在返回 null
     *
     * 示例：
     * Object token = RealApiMonitor.getJsonValue("/api/login", "$.data.token");
     * Object userId = RealApiMonitor.getJsonValue("/api/user", "$.data.id");
     *
     * ⚠️ 注意：JsonPath 返回类型取决于 JSON 内容：
     *   - 字符串 → String
     *   - 整数   → Integer（JSON 中无长整区分）
     *   - 小数   → Double / BigDecimal
     *   - 布尔   → Boolean
     *   - 数组   → List<...>
     *   - 对象   → LinkedHashMap
     *   推荐使用下方的类型安全便捷方法（getJsonString / getJsonInt 等）
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
            // 跳过二进制标记前缀
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

    // ==================== 类型安全便捷方法 ====================

    /** 提取 String 类型字段 */
    public static String getJsonString(String urlPattern, String jsonPath) {
        return safeCast(getJsonValue(urlPattern, jsonPath), String.class, urlPattern, jsonPath);
    }

    /** 提取 Integer 类型字段 */
    public static Integer getJsonInt(String urlPattern, String jsonPath) {
        return safeCast(getJsonValue(urlPattern, jsonPath), Integer.class, urlPattern, jsonPath);
    }

    /** 提取 Long 类型字段（自动从 Integer/Double 转换） */
    public static Long getJsonLong(String urlPattern, String jsonPath) {
        return safeCast(getJsonValue(urlPattern, jsonPath), Long.class, urlPattern, jsonPath);
    }

    /** 提取 Double 类型字段 */
    public static Double getJsonDouble(String urlPattern, String jsonPath) {
        return safeCast(getJsonValue(urlPattern, jsonPath), Double.class, urlPattern, jsonPath);
    }

    /** 提取 Boolean 类型字段 */
    public static Boolean getJsonBoolean(String urlPattern, String jsonPath) {
        return safeCast(getJsonValue(urlPattern, jsonPath), Boolean.class, urlPattern, jsonPath);
    }

    /** 提取 List 类型字段（数组） */
    @SuppressWarnings("unchecked")
    public static <E> List<E> getJsonList(String urlPattern, String jsonPath) {
        Object raw = getJsonValue(urlPattern, jsonPath);
        if (isJsonValueEmpty(raw)) return null;
        if (raw instanceof List) return (List<E>) raw;
        // JsonPath 的 [*] 操作可能返回非标准 List 实现，尝试反射兼容
        try {
            // json-path 2.x 内部使用 net.minidev.json.JSONArray，但该类可能不在 classpath
            // 通过 Iterable 接口兜底转换，不依赖具体实现类
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

    /** 提取 Map 类型字段（嵌套对象） */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getJsonObject(String urlPattern, String jsonPath) {
        Object raw = getJsonValue(urlPattern, jsonPath);
        if (isJsonValueEmpty(raw)) return null;
        if (raw instanceof Map) return (Map<String, Object>) raw;
        logger.warn("getJsonObject: expected Map but got {} for pattern='{}', jsonPath='{}'",
            raw.getClass().getSimpleName(), urlPattern, jsonPath);
        return null;
    }

    // ==================== 非阻塞便捷方法（条件性 API 场景） ====================
    
    /**
     * 检查指定 API 是否已被捕获（非阻塞，立即返回）
     * 
     * 适用场景：某些 API 只在特定条件下才会被请求，
     * 用阻塞的 waitForApi 等待不合适（可能永远不来导致超时浪费）
     * 
     * @param urlPattern URL 匹配模式
     * @return true 表示至少有一条匹配记录
     * 
     * 示例：
     * // 条件性检查：操作后判断是否触发了某 API
     * page.click("#maybe-trigger-api-btn");
     * if (RealApiMonitor.hasApiCaptured("/api/conditional")) {
     *     // 触发了，处理响应
     *     String body = RealApiMonitor.getLastBody("/api/conditional");
     * } else {
     *     // 没触发，走其他逻辑
     * }
     */
    public static boolean hasApiCaptured(String urlPattern) {
        return getLast(urlPattern) != null;
    }

    /**
     * 从所有匹配的 API 记录中提取同一 JSON 字段（非阻塞，支持分页/多次请求场景）
     * 
     * 适用场景：
     * - 分页请求：每次返回的数据结构相同，需要从每页中提取某个字段
     * - 重复请求：同一接口多次调用，收集所有响应中的某个值
     * - 列表聚合：统计多次 API 返回值的集合
     * 
     * @param urlPattern URL 匹配模式
     * @param jsonPath JsonPath 表达式
     * @return 每条匹配记录中该字段值的列表（提取失败的记录对应位置为 null）
     * 
     * 示例：
     * // 分页：获取每页的总数
     * List<Object> totalCounts = RealApiMonitor.getAllJsonValues("/api/list", "$.totalCount");
     * int sum = 0;
     * for (Object val : totalCounts) {
     *     if (val instanceof Number) sum += ((Number) val).intValue();
     * }
     * 
     * // 获取每次刷新 token 后的新 token 值
     * List<String> tokens = RealApiMonitor.getAllJsonStrings("/api/auth/refresh", "$.data.token");
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> getAllJsonValues(String urlPattern, String jsonPath) {
        List<ApiCallRecord> records = getAll(urlPattern);
        List<T> values = new ArrayList<>(records.size());
        for (ApiCallRecord record : records) {
            try {
                String body = record.getResponseBody();  // responseBody 已是 String
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
     * 类型安全版：从所有匹配记录中提取 String 字段
     * 
     * 示例：
     * // 收集所有分页请求中的订单号
     * List<String> orderIds = RealApiMonitor.getAllJsonStrings("/api/orders", "$.data.orderId");
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
     * 类型安全版：从所有匹配记录中提取 Integer 字段
     * 
     * 示例：
     * // 统计每页数据量
     * List<Integer> pageSizeList = RealApiMonitor.getAllJsonInts("/api/list", "$.data.pageSize");
     */
    public static List<Integer> getAllJsonInts(String urlPattern, String jsonPath) {
        List<Object> rawList = getAllJsonValues(urlPattern, jsonPath);
        List<Integer> result = new ArrayList<>(rawList.size());
        for (Object raw : rawList) {
            result.add(safeCast(raw, Integer.class, urlPattern, jsonPath));
        }
        return result;
    }

    /** 从所有匹配记录中提取 Long 字段 */
    public static List<Long> getAllJsonLongs(String urlPattern, String jsonPath) {
        List<Object> rawList = getAllJsonValues(urlPattern, jsonPath);
        List<Long> result = new ArrayList<>(rawList.size());
        for (Object raw : rawList) {
            result.add(safeCast(raw, Long.class, urlPattern, jsonPath));
        }
        return result;
    }

    /** 从所有匹配记录中提取 Double 字段 */
    public static List<Double> getAllJsonDoubles(String urlPattern, String jsonPath) {
        List<Object> rawList = getAllJsonValues(urlPattern, jsonPath);
        List<Double> result = new ArrayList<>(rawList.size());
        for (Object raw : rawList) {
            result.add(safeCast(raw, Double.class, urlPattern, jsonPath));
        }
        return result;
    }

    /** 从所有匹配记录中提取 Boolean 字段 */
    public static List<Boolean> getAllJsonBooleans(String urlPattern, String jsonPath) {
        List<Object> rawList = getAllJsonValues(urlPattern, jsonPath);
        List<Boolean> result = new ArrayList<>(rawList.size());
        for (Object raw : rawList) {
            result.add(safeCast(raw, Boolean.class, urlPattern, jsonPath));
        }
        return result;
    }
    
    /**
     * 等待目标 API 响应中的指定字段有值（阻塞式轮询）
     *
     * 解决场景：点击按钮 → 触发 API → 需要等 API 返回特定字段值后继续操作
     *
     * ⚠️ 空值判断已修正：Integer(0)、Boolean(false)、Double(0.0) 等合法值不再被误判为空
     *     真正判定为"空"的只有：null、JSON null、空字符串、空集合
     *
     * @param urlPattern URL 匹配模式
     * @param jsonPath JsonPath 表达式
     * @param timeoutSeconds 最大等待时间（秒）
     * @return 字段值，超时返回 null
     *
     * 示例：
     * // 等待支付接口返回 orderId（String）
     * String orderId = RealApiMonitor.waitForJsonValue("/api/payment", "$.data.orderId", 30);
     * // 等待状态码（Integer），即使值为 0 也能正确返回
     * Integer status = RealApiMonitor.waitForJsonValue("/api/status", "$.code", 10);
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
     * 类型安全版：等待指定类型的 JSON 字段值
     *
     * 示例：
     * String name = RealApiMonitor.waitForJsonString("/api/user", "$.data.name", 30);
     * Integer count = RealApiMonitor.waitForJsonInt("/api/list", "$.totalCount", 15);
     * Boolean success = RealApiMonitor.waitForJsonBoolean("/api/submit", "$.success", 10);
     */
    public static String waitForJsonString(String urlPattern, String jsonPath, int timeoutSeconds) {
        return safeCast(waitForJsonValue(urlPattern, jsonPath, timeoutSeconds), String.class, urlPattern, jsonPath);
    }

    public static Integer waitForJsonInt(String urlPattern, String jsonPath, int timeoutSeconds) {
        return safeCast(waitForJsonValue(urlPattern, jsonPath, timeoutSeconds), Integer.class, urlPattern, jsonPath);
    }

    public static Long waitForJsonLong(String urlPattern, String jsonPath, int timeoutSeconds) {
        return safeCast(waitForJsonValue(urlPattern, jsonPath, timeoutSeconds), Long.class, urlPattern, jsonPath);
    }

    public static Double waitForJsonDouble(String urlPattern, String jsonPath, int timeoutSeconds) {
        return safeCast(waitForJsonValue(urlPattern, jsonPath, timeoutSeconds), Double.class, urlPattern, jsonPath);
    }

    public static Boolean waitForJsonBoolean(String urlPattern, String jsonPath, int timeoutSeconds) {
        return safeCast(waitForJsonValue(urlPattern, jsonPath, timeoutSeconds), Boolean.class, urlPattern, jsonPath);
    }

    /**
     * 等待目标 API 响应中的指定字段匹配期望值（字符串比较）
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
     * 等待目标 API 响应中的指定字段匹配期望值（泛型对象 equals 比较）
     * 支持数字、布尔等类型的精确匹配，无需转字符串
     *
     * 示例：
     * RealApiMonitor.waitForJsonValueEquals("/api/status", "$.code", 200, 10);
     * RealApiMonitor.waitForJsonValueEquals("/api/submit", "$.success", true, 10);
     * RealApiMonitor.waitForJsonValueEquals("/api/user", "$.name", "John", 10);
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

    // ==================== 核心方法 3: getLastBody() - 获取响应体 ====================
    
    /**
     * 获取指定 API 的最后响应体
     * 
     * @param urlPattern URL 匹配模式
     * @return 响应体字符串，没有则返回 null
     */
    public static String getLastBody(String urlPattern) {
        ApiCallRecord record = getLast(urlPattern);
        if (record == null) {
            logger.debug("getLastBody returns null - no matching record found for: {}", urlPattern);
            return null;
        }
        String body = record.getResponseBody();
        if (body == null) {
            logger.debug("getLastBody returns null - response body is empty/unreadable for: {}", urlPattern);
            // 尝试从原始 Response 对象重新读取（如果仍有效）
            body = attemptRereadBody(record);
        }
        return body;
    }

    /**
     * 尝试重新读取 Response Body（处理延迟读取失效的场景）
     */
    private static String attemptRereadBody(ApiCallRecord record) {
        try {
            if (record.response != null && !record.bodyRead) {
                String text = record.response.text();
                logger.info("Successfully re-read response body on retry for: {}", record.getUrl());
                return text;
            }
        } catch (Exception e) {
            logger.debug("Failed to re-read response body: {}", e.getMessage());
        }
        return null;
    }
    
    // ==================== 核心方法 4: getHistory() - 获取所有记录 ====================
    
    /**
     * 获取所有 API 历史记录
     * 
     * @return 不可修改的 API 记录列表
     */
    public static List<ApiCallRecord> getHistory() {
        return Collections.unmodifiableList(apiCallHistory.get());
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 清空历史记录
     * 注意：不清除 contextListeners/pageListeners 引用，防止正在运行的监听器丢失 stopped 标志
     * 监听器引用的完全清理由 stopMonitoring() / resetForNextScenario() 负责
     */
    public static void clearHistory() {
        apiCallHistory.get().clear();
        hasLoggedToSerenity.set(false);
        // 注意：不清除 contextListeners/pageListeners 引用（由 stop/resetForNextScenario 负责）
        // 不清除 contextMonitoringStopped/pageMonitoringStopped（保留 stopped 标志防止旧监听器空转）
        // 重置目标 API 追踪状态
        matchedTargetApiCount.get().set(0);
        allTargetApisCaptured.set(false);
        targetApiPatterns.get().clear();
        // ⭐ 清理 minMatches + autoStopOnMatch + timeout 相关
        patternMatchCounts.get().clear();
        configuredMinMatches.set(1);           // 重置默认值
        configuredAutoStopOnMatch.set(true);   // 重置默认值
        configuredTimeout.set(60);             // 重置默认值
        // ⭐ 重置跨线程 API 活动时间戳
        lastApiActivityTime = 0;
    }
    
    /**
     * 清空期望
     */
    public static void clearExpectations() {
        apiExpectations.get().clear();
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
     * 停止所有监控
     * 
     * 此方法可能从两种上下文调用：
     * 1. 主线程（测试线程）→ 正常写 Serenity 报告
     * 2. 异步线程（ApiMonitor-AsyncStop）→ 仅标记停止 + 缓存报告，由主线程稍后 flush
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
        
        // 判断当前是否在主线程（有 Serenity 上下文）
        boolean isMainThread = isStepEventBusAvailable();
        
        if (isMainThread) {
            // 主线程：直接写报告
            logResults();
            logger.info("All monitoring stopped & results logged to Serenity (by {})", caller);
        } else {
            // 异步线程：缓存报告数据，标记待写入，由 PlaywrightListener.flushPendingApiMonitorReport() 在主线程回调中刷新
            cacheReportForLaterWrite();
            logger.info("All monitoring stopped (by {}), report cached for main-thread flush", caller);
        }
    }
    
    /**
     * 检查 StepEventBus 是否可用（当前线程是否在 Serenity 测试上下文中）
     */
    private static boolean isStepEventBusAvailable() {
        try {
            StepEventBus eventBus = StepEventBus.getEventBus();
            return eventBus != null
                && eventBus.getBaseStepListener() != null;
        } catch (Exception e) {
            // CurrentListener is null 或其他异常都视为不可用
            logger.debug("StepEventBus not available in current thread: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取当前配置的 auto-stop 超时时间（秒）
     * 供 recordApiCall() 内联超时检查使用
     */
    private static int getEffectiveTimeout() {
        return configuredTimeout.get();
    }

    /**
     * 缓存报告数据供主线程稍后写入（解决异步线程无 Serenity 上下文的问题）
     * 在 stopMonitoring 的异步调用路径中使用
     */
    private static void cacheReportForLaterWrite() {
        if (apiCallHistory.get().isEmpty() || reportPending) return;  // 已有待处理的则跳过
        
        try {
            // 构建报告内容
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
     * 由 PlaywrightListener 主线程回调调用：刷新待写入的报告到 Serenity
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
            // 再次确认在主线程中
            if (!isStepEventBusAvailable()) {
                // 仍然不在主线程，重新缓存（极端情况）
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

    /** 构建报告内容数组 [title, json] */
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
                    if (url.contains(pattern) || url.matches(toRegex(pattern))) {
                        recordsToReport.add(record);
                        break;
                    }
                }
            }
        } else {
            recordsToReport = new ArrayList<>(apiCallHistory.get());  // ⭐ 修复1：ThreadLocal 必须用 .get() 取值，否则把 ThreadLocal 对象当 List 传 → ClassCastException
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
            ? "API Monitor Summary (" + recordsToReport.size() + " calls)"
            : "Target API Monitor (" + recordsToReport.size() + "/" + patterns.size() + ")";
        
        return new String[]{title, json.toString()};
    }

    /**
     * 记录汇总到 Serenity 报告（只报告目标 API）
     * 复用 buildReportContent() 避免代码重复
     */
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
                apiCallHistory.get().size());  // 用总数量（buildReportContent 内部已过滤）
            Serenity.recordReportData()
                .withTitle(report[0])
                .andContents(report[1]);
            
            logger.info("Successfully recorded to Serenity report: {}", report[0]);
        } catch (Exception e) {
            logger.debug("Failed to log summary: {}", e.getMessage());
        }
    }
    
    /**
     * 记录结果到 Serenity 报告
     * 在 stopMonitoring() 时立即调用（而非测试结束），确保结果出现在正确的 step
     */
    public static void logResults() {
        if (apiCallHistory.get().isEmpty() || hasLoggedToSerenity.get()) {
            return;
        }
        logSummaryToSerenityReport();
    }
    
    /**
     * 清除状态，允许同一测试中多次 start/stop 循环
     * 在 scenario 结束时调用（由 PlaywrightListener.testFinished 自动调用），完全清理所有监听器引用
     */
    public static void resetForNextScenario() {
        // ⭐ 修复2：真正从 Playwright 移除所有已注册的监听器（不只是清 map）
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
        // 清理待写入报告缓存
        reportPending = false;
        pendingReportTitle = null;
        pendingReportContent = null;
        // 完全清理所有状态（包括监听器引用）
        contextMonitoringStopped.clear();
        pageMonitoringStopped.clear();
        contextListeners.clear();
        pageListeners.clear();
        
        // ⭐ 修复3：ThreadLocal remove() — 防止线程池复用时旧数据污染新用例
        // set(false)/set(0) 只改值不释放对象，线程复用时旧值仍在
        apiCallHistory.remove();
        apiExpectations.remove();
        monitoringFailure.remove();
        matchedTargetApiCount.remove();
        allTargetApisCaptured.remove();
        targetApiPatterns.remove();
        // ⭐ 清理 minMatches + timeout 相关 ThreadLocal
        patternMatchCounts.remove();
        configuredMinMatches.remove();
        configuredAutoStopOnMatch.remove();
        configuredTimeout.remove();
    }

    /**
     * ⭐ 修复6：终极清场方法 — 强制停止监控 + 完全清理所有状态
     * 用于：用例崩溃、超时、手动停止等异常场景后的紧急恢复
     * 在 PlaywrightListener.testSuiteFinished() 中调用
     */
    public static void forceCleanAll() {
        logger.info("=== forceCleanAll: Emergency cleanup of ALL RealApiMonitor state ===");
        try {
            // 1. 停止所有监控
            stopMonitoring();
        } catch (Exception e) {
            logger.debug("forceCleanAll: stopMonitoring failed (may already be stopped)", e);
        }
        try {
            // 2. 完全重置 scenario 级别状态
            resetForNextScenario();
        } catch (Exception e) {
            logger.debug("forceCleanAll: resetForNextScenario failed", e);
        }
        try {
            // 3. 清除全局配置
            clearTargetHost();
        } catch (Exception e) {
            logger.debug("forceCleanAll: clearTargetHost failed", e);
        }
        logger.info("=== forceCleanAll: Complete ===");
    }
    
    // ==================== 内部方法 ====================
    
    private static void recordApiCall(Response response, Request request) {
        try {
            // ⭐ 修复5：空保护 — Playwright 在某些跳转/异常响应下 response/request/url 可能返回 null
            if (response == null || request == null || response.url() == null) {
                logger.debug("[API] Skipping null response/request/url (may be redirect or abnormal response)");
                return;
            }
            // 先记录到历史（确保数据在停止监控前就已保存）
            String requestId = UUID.randomUUID().toString();
            
            // ⭐ 关键修复：在 onResponse 回调中立即同步读取 response body
            // Playwright 的 Response 流只在回调触发后短期有效，必须在此刻读取
            // 使用多策略：text() 优先（文本），body() 兜底（二进制/base64）
            // ⭐ 修复：处理重定向响应无 body 的情况（Playwright 会抛出 "Response body is unavailable for redirect responses"）
            String capturedBody = null;
            boolean bodyCaptured = false;
            boolean isRedirectResponse = response.status() == 301 || response.status() == 302 
                || response.status() == 303 || response.status() == 307 || response.status() == 308;
            
            try {
                capturedBody = response.text();
                bodyCaptured = true;
                logger.debug("[API] Response body captured via text() for: {} (length={})", 
                    response.url(), capturedBody != null ? capturedBody.length() : "null");
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                boolean isRedirectError = errorMsg != null && (
                    errorMsg.contains("redirect") || 
                    errorMsg.contains("unavailable for redirect") ||
                    errorMsg.contains("Body is unavailable")
                );
                
                if (isRedirectError || isRedirectResponse) {
                    // 重定向响应本来就没有 body，这是正常行为，不算错误
                    logger.debug("[API] Redirect response (status={}) has no body for: {} - this is normal", 
                        response.status(), response.url());
                    bodyCaptured = false; // 明确标记为未捕获，但不算错误
                } else {
                    logger.debug("[API] response.text() failed for {}: {}, trying body()...", 
                        response.url(), e.getMessage());
                    // text() 失败的常见原因：二进制响应、编码问题、流已消耗
                    // 尝试用 body() 获取原始字节再转 base64
                    try {
                        byte[] rawBytes = response.body();
                        if (rawBytes != null && rawBytes.length > 0) {
                            // 检查是否为可读文本（非纯二进制）
                            String rawText = new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8);
                            // 如果 UTF-8 解码后没有大量乱码控制字符，视为文本返回
                            if (isReadableText(rawText)) {
                                capturedBody = rawText;
                                bodyCaptured = true;
                                logger.debug("[API] Response body captured via UTF-8 decode for: {} (length={})", 
                                    response.url(), capturedBody.length());
                            } else {
                                // 纯二进制内容，存储 base64 + 元信息
                                capturedBody = "[BINARY_DATA base64=" + java.util.Base64.getEncoder().encodeToString(rawBytes) 
                                    + " size=" + rawBytes.length + " contentType=" + getContentType(response) + "]";
                                bodyCaptured = true;
                                logger.info("[API] Binary response body captured for: {} (size={}bytes, type={})", 
                                    response.url(), rawBytes.length, getContentType(response));
                            }
                        }
                    } catch (Exception e2) {
                        logger.debug("[API] Both text() and body() failed for {}: text_err={}, body_err={}", 
                            response.url(), e.getMessage(), e2.getMessage());
                    }
                }
            }
            
            final boolean captureSuccess = bodyCaptured;
            
            ApiCallRecord record = new ApiCallRecord(
                requestId, response.url(), request.method(), System.currentTimeMillis(),
                null, null, response.status(), null, capturedBody, false
            );
            
            // ⭐ 关键：立即标记 body 已读取，防止 getResponseBody() 懒加载重复尝试 response.text()
            // （Playwright 响应流只能读一次，第二次调用会抛异常或返回空）
            record.markBodyRead();
            
            if (!captureSuccess) {
                logger.warn("[API] ⚠ Response body NOT captured for {} (status={}) — stream may be closed or empty", 
                    response.url(), response.status());
            }
            record.setResponse(response);
            record.setRequest(request);
            apiCallHistory.get().add(record);

            // ⭐ 更新跨线程 API 活动时间戳（供 auto-stop 超时检查使用）
            long now = System.currentTimeMillis();
            long prevActivity = lastApiActivityTime;
            lastApiActivityTime = now;

            logger.debug("[API] {} {} - {}", request.method(), response.url(), response.status());

            // ⭐ 内联 auto-stop 超时检查（无额外线程，复用 onResponse 回调线程）
            // 检测：距上次 API 活动是否已超过配置的 timeoutSeconds（说明中间有空闲期）
            if (prevActivity > 0 && configuredAutoStopOnMatch.get()) {
                long idleSeconds = (now - prevActivity) / 1000;
                int effectiveTimeout = getEffectiveTimeout();
                if (effectiveTimeout > 0 && idleSeconds >= effectiveTimeout) {
                    logger.warn("AUTO-STOP: No API calls for {}s (idle), auto-stopping. Total APIs: {}", 
                        idleSeconds, apiCallHistory.get().size());
                    stopMonitoring();
                    return; // 已停止，后续逻辑跳过
                }
            }

            // 再检查是否已捕获所有目标 API（如果配置了目标 API）
            List<String> patterns = targetApiPatterns.get();
            if (!patterns.isEmpty() && !allTargetApisCaptured.get()) {
                String url = response.url();
                for (String pattern : patterns) {
                    if (url.contains(pattern) || url.matches(toRegex(pattern))) {
                        int totalMatched = matchedTargetApiCount.get().incrementAndGet();
                        
                        // ⭐ minMatches 逻辑：按模式独立计数
                        AtomicInteger patternCount = patternMatchCounts.get().get(pattern);
                        int thisPatternCount = (patternCount != null) ? patternCount.incrementAndGet() : 1;
                        
                        int requiredMin = configuredMinMatches.get();
                        logger.info("[Target API #{} | {}#{}] {} {} - {}", 
                                totalMatched, pattern, thisPatternCount, request.method(), url, response.status());

                        // 检查是否每个目标模式都达到了 minMatches 次
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
                            
                            // ⭐ 只有 autoStopOnMatch=true 时才自动停止
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
        
        // 已经是正则（包含显式正则语法）
        if (pattern.contains(".*") || pattern.contains("\\d") || pattern.contains("?") || pattern.contains("+")) {
            return pattern;
        }
        
        // 转换为正则：使用 Pattern.quote 确保特殊字符被正确转义
        // 例如 "/api/user.info" → ".*/api/user.info.*" （点号被当作字面量匹配）
        String normalized = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        return ".*" + Pattern.quote(normalized) + ".*";
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
        
        // ⭐ 去掉查询参数和片段后再判断扩展名（fix: clear.png?org_id=xxx 不会被漏过）
        String pathOnly = url;
        int queryIndex = url.indexOf('?');
        if (queryIndex > 0) {
            pathOnly = url.substring(0, queryIndex);
        }
        int fragmentIndex = pathOnly.indexOf('#');
        if (fragmentIndex > 0) {
            pathOnly = pathOnly.substring(0, fragmentIndex);
        }
        
        String lower = pathOnly.toLowerCase();

        // ⭐ 修复4：只有路径片段包含 API 关键字才放行（避免 static/xxxapi.js 误判）
        // 覆盖：/api/, /rest/, /service/ 以及路径开头的情况
        if (lower.contains("/api/") || lower.contains("/rest/") || lower.contains("/service/")
                || lower.startsWith("api/") || lower.startsWith("rest/") || lower.startsWith("service/")) {
            return false;
        }

        // 后缀过滤（基于去掉查询参数后的 path）
        String[] exts = {".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico",
                         ".woff", ".woff2", ".ttf", ".eot", ".map", ".html"};
        for (String e : exts) {
            if (lower.endsWith(e)) return true;
        }
        return false;
    }
    
    /**
     * 判断字节数组解码后是否为可读文本（非乱码二进制）
     */
    private static boolean isReadableText(String text) {
        if (text == null || text.isEmpty()) return true;  // 空视为文本
        int controlCount = 0;
        int total = Math.min(text.length(), 1024);  // 只检查前1024字符
        for (int i = 0; i < total; i++) {
            char c = text.charAt(i);
            // 统计不可打印控制字符（排除常见空白字符）
            if (c < 32 && c != '\t' && c != '\n' && c != '\r') {
                controlCount++;
            }
        }
        // 如果控制字符占比超过15%，认为是二进制数据
        return (controlCount * 100.0 / total) < 15;
    }

    /** 从 Response 中提取 Content-Type */
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
        private String responseBody;  // response.text() 返回 String，二进制标记为 "[BINARY_DATA...]"
        private final boolean isMocked;
        
        // 延迟读取支持
        private Response response;
        private Request request;
        private volatile boolean bodyRead = false;
        
        // ⭐ 修复5：bodyRead/getResponseBody 竞态锁
        // 并行场景下多线程可能同时调用 getResponseBody()，导致 Playwright 响应流重复读取异常
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
        
        public void setResponse(Response response) {
            this.response = response;
        }
        
        public void setRequest(Request request) {
            this.request = request;
        }
        
        /**
         * 标记 response body 已读取（由 recordApiCall 在回调中立即调用）
         * 防止 getResponseBody() 懒加载重复尝试 response.text() 导致异常
         * ⭐ 修复5：加锁防止竞态条件
         */
        public void markBodyRead() {
            synchronized (bodyLock) {
                this.bodyRead = true;
            }
        }
        
        public String getUrl() { return url; }
        public String getMethod() { return method; }
        public long getTimestamp() { return timestamp; }
        public int getStatusCode() { return statusCode; }
        
        /** ⭐ 修复5：加锁防止并行场景下多线程同时读取 Playwright 响应流 */
        public String getResponseBody() {
            synchronized (bodyLock) {
                // ⭐ 如果回调中已经读取过（无论成功与否），直接返回结果
                if (bodyRead) {
                    return responseBody;
                }
                // 首次调用（未在回调中捕获到的情况）
                if (response != null) {
                    try {
                        responseBody = response.text();
                    } catch (Exception e) {
                        logger.debug("Cannot read response body (stream likely consumed): {}", e.getMessage());
                    }
                }
                bodyRead = true;
                return responseBody;
            }
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
