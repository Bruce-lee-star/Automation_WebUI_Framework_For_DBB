package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * API 捕获上下文 — 统一管理所有被路由拦截的 API 调用（Monitor / Mock / Modify）。
 *
 * <p>不同于仅限 Monitor 的旧设计，本类面向所有 Route 类型的 API 调用：
 * <ul>
 *   <li><b>Monitor</b> — 监控真实 API 调用，记录请求/响应快照，支持断言</li>
 *   <li><b>Mock</b> — Mock 响应，记录被拦截的请求信息和返回的 Mock 数据</li>
 *   <li><b>Modify</b> — 修改响应，后续可扩展记录修改后的数据</li>
 * </ul>
 *
 * <p><b>⭐ 共享实例设计</b>：使用静态单例（而非 ThreadLocal），
 * 确保 Handler（Playwright 事件线程）和 PlaywrightListener（主测试线程）
 * 操作的是同一个上下文实例。所有字段均使用线程安全数据结构：
 * {@link AtomicInteger}、{@link AtomicBoolean}、
 * {@link ConcurrentHashMap}、{@code synchronized}。
 *
 * <p>两种存储：
 * <ul>
 *   <li><b>CapturedApiCall 存储</b>（推荐） — 完整的请求/响应快照，包含
 *       URL、method、statusCode、requestHeaders、responseHeaders、body</li>
 *   <li><b>Response body 存储</b>（向后兼容） — 仅存储 body 字符串</li>
 * </ul>
 *
 * <p>推荐用法：
 * <pre>{@code
 * CapturedApiCall call = ctx.getLastApiCall("/api/login");
 * int status = call.statusCode();
 * String token = call.responseHeader("Authorization");
 * Object id = call.json("$.data.userId");
 * }</pre>
 *
 * @see RouteEngine
 */
public class ApiCaptureContext {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiCaptureContext.class);

    /**
     * ⭐ 全局共享的 API 捕获上下文实例（不再使用 ThreadLocal）。
     *
     * <p>Handler（Playwright 事件线程）和 PlaywrightListener（主线程）
     * 通过此单一实例共享断言状态，保证跨线程可见性。
     * 所有可变字段均使用线程安全结构，无需额外同步。
     */
    private static final ApiCaptureContext SHARED = new ApiCaptureContext();

    /**
     * 获取全局共享的 API 捕获上下文实例。
     *
     * <p>⭐ 任意线程调用均返回同一实例，保证跨线程状态一致性。
     */
    public static ApiCaptureContext getCurrent() {
        return SHARED;
    }

    /**
     * 重置 API 捕获上下文（测试开始时调用）。
     * <p>注意：此方法会重置全局共享实例的断言和响应存储状态。
     */
    public static void resetCurrent() {
        SHARED.reset();
    }

    /**
     * 清理 API 捕获上下文（测试结束时调用）。
     * <p>⭐ 不再使用 ThreadLocal.remove，改为 reset 重置状态即可。
     */
    public static void removeCurrent() {
        SHARED.reset();
    }

    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final AtomicBoolean hasAssertionFailures = new AtomicBoolean(false);

    /** 等待锁：decrement → 0 时通知 awaitCompletion 的调用方 */
    private final Object completionLock = new Object();

    // ═══════════════════════════════════════════════════════════════
    // ⭐ 性能优化：Ant Glob Pattern 缓存（避免每次调用 Pattern.compile()）
    // ═══════════════════════════════════════════════════════════════
    private static final int MAX_PATTERN_CACHE_SIZE = 200;
    private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    // ⭐ #4 通配符模式索引：仅包含通配符的 urlPattern key，避免 fallback 时遍历全量
    private final Set<String> wildcardPatternKeys = ConcurrentHashMap.newKeySet();

    // ⭐ #5 wait/notify 锁：waitForApi 使用条件等待替代忙轮询
    private final Object apiCallLock = new Object();

    // ⭐ P3: 最近调用平铺列表 — 延迟初始化，用于 scanForMatching 快速扫描（避免 O(n) 遍历两重 Map）
    private final List<CapturedApiCall> recentCalls = new CopyOnWriteArrayList<>();
    private static final int MAX_RECENT_CALLS = 500;

    // ═══════════════════════════════════════════════════════════════
    // ⭐ 性能优化：URL 精确索引（毫秒级 O(1) 检索）
    // ═══════════════════════════════════════════════════════════════
    private final Map<String, List<CapturedApiCall>> apiCallsByUrl = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════
    // ⭐⭐⭐ Fail-Fast 机制：断言失败立即中断测试线程
    // ═══════════════════════════════════════════════════════════════

    /**
     * 当前测试的主线程引用（由 PlaywrightListener.stepStarted 设置）。
     * volatile 保证跨线程可见性。
     */
    private volatile Thread testThread;

    /**
     * 设置当前测试的主线程引用。
     * <p>由 PlaywrightListener.stepStarted() 调用，供 MonitorHandler 在断言失败时使用。
     */
    public void setTestThread(Thread thread) {
        this.testThread = thread;
    }

    /**
     * 清除测试线程引用。
     * <p>由 PlaywrightListener.stepFinished() 调用，防止悬空引用。
     */
    public void clearTestThread() {
        this.testThread = null;
    }

    /**
     * ⭐⭐⭐ 断言失败标记（非中断模式）。
     *
     * <p>MonitorHandler 在 Playwright 事件线程上同步执行断言，
     * 失败时调用此方法标记失败状态并记录详情。不再中断主测试线程，
     * 避免 {@code Thread.interrupt()} 导致后续 Playwright IO（page.waitForSelector 等）
     * 抛出异常，从而保证后续 Scenario 仍可正常执行。
     *
     * <p>断言失败由 {@link PlaywrightListener#checkAndFailOnApiAssertions()}
     * 在每个步骤结束时兜底检查并抛出 {@code AssertionError}。
     */
    public void signalFailFast() {
        hasAssertionFailures.set(true);
        if (activeRequests.get() > 0) {
            LOGGER.debug("[ApiCaptureContext] Draining activeRequests ({}) after assertion failure",
                    activeRequests.get());
            activeRequests.set(0);
            synchronized (completionLock) {
                completionLock.notifyAll();
            }
        }
    }

    /** Response 存储上限（防止内存泄漏），超过后记录 WARN 日志但继续存储 */
    private static final int MAX_RESPONSE_STORAGE = 1000;

    /** Response 总字节数上限（10MB），防止大响应（如文件下载）导致 OOM */
    private static final long MAX_RESPONSE_TOTAL_SIZE = 10 * 1024 * 1024; // 10 MB

    /** 当前已存储响应总字节数（原子操作，线程安全） */
    private final AtomicLong totalResponseSize = new AtomicLong(0L);

    /** 断言失败详情列表（线程安全） */
    private final List<AssertionFailureDetail> failureDetails =
            java.util.Collections.synchronizedList(new ArrayList<>());

    /**
     * CapturedApiCall 存储 — 完整的请求/响应快照（推荐）。
     *
     * <p>Key = 请求端点（路径+查询，不含 host），Value = 该端点被调用的所有快照（按顺序）。
     */
    private final Map<String, List<CapturedApiCall>> apiCallsPerUrl = new ConcurrentHashMap<>();

    /**
     * Response body 存储 — 向后兼容的旧存储。
     *
     * <p>Key = 请求端点（路径+查询，不含 host），Value = 该端点被调用的所有响应 body（按顺序）。
     * 同一 endpoint 分页多次调用（如 /api/users?page=1, page=2）会全部保留。
     *
     * @deprecated 推荐使用 {@link #getApiCalls(String)} 获取完整信息
     */
    @Deprecated
    private final Map<String, List<String>> responseStorage = new ConcurrentHashMap<>();

    /**
     * 断言失败详情 DTO
     */
    public static class AssertionFailureDetail {
        public final String url;
        public final String assertionType;   // "STATUS" / "JSONPATH"
        public final String expectedValue;
        public final String actualValue;
        public final String failMessage;

        AssertionFailureDetail(String url, String assertionType, String expectedValue,
                               String actualValue, String failMessage) {
            this.url = url;
            this.assertionType = assertionType;
            this.expectedValue = expectedValue;
            this.actualValue = actualValue;
            this.failMessage = failMessage;
        }

        @Override
        public String toString() {
            return String.format("  [%s] %s%s: expected='%s', actual='%s'",
                    assertionType, extractEndpoint(url),
                    failMessage != null ? " (" + failMessage + ")" : "",
                    expectedValue != null ? expectedValue : "N/A",
                    actualValue != null ? actualValue : "N/A");
        }

        /**
         * 智能缩短 URL：host 首尾保留用 {@code ...} 省略中部，路径保留首尾段。
         * <p>示例：
         * {@code https://www.qualityassurance-amh-gbb-sit.p2g.netd2.hsbc.com.hk/portalserver/.../permissionLeftMenuConfig}
         * → {@code www.qualityassu...hsbc.com.hk/portalserver/.../permissionLeftMenuConfig}
         */
        private static String extractEndpoint(String url) {
            if (url == null || url.isEmpty()) return "N/A";
            try {
                java.net.URI uri = java.net.URI.create(url);
                String host = uri.getHost();
                String path = uri.getPath();

                if (host == null) {
                    // 无 host 时直接按长度截断
                    return url.length() <= 60 ? url
                            : url.substring(0, 25) + "..." + url.substring(url.length() - 20);
                }

                String shortHost = abbreviateMiddle(host, 18, 14);
                String shortPath = abbreviatePath(path);
                return shortHost + shortPath;
            } catch (Exception e) {
                // 解析失败兜底：超长截断
                return url.length() <= 60 ? url
                        : url.substring(0, 25) + "..." + url.substring(url.length() - 20);
            }
        }

        /** 保留字符串首部 N 个字符 + ... + 尾部 M 个字符 */
        private static String abbreviateMiddle(String s, int headLen, int tailLen) {
            if (s == null || s.isEmpty()) return "";
            if (s.length() <= headLen + tailLen + 3) return s;
            return s.substring(0, headLen) + "..." + s.substring(s.length() - tailLen);
        }

        /** 路径保留首段/.../末段，且末段（endpoint 名）始终完整显示 */
        private static String abbreviatePath(String path) {
            if (path == null || path.isEmpty()) return "";
            if (path.length() <= 50) return path;

            int lastSlash = path.lastIndexOf('/');
            if (lastSlash < 0) return abbreviateMiddle(path, 25, 18);

            String endpoint = path.substring(lastSlash);       // /permissionLeftMenuConfig（完整保留）
            String prefix = path.substring(0, lastSlash);      // /portalserver/.../leftmenu

            // prefix 够短则不动
            if (prefix.length() <= 30) return prefix + endpoint;

            // 只缩写 prefix 中间部分，endpoint 原样输出
            int firstSlash = prefix.indexOf('/', 1);
            if (firstSlash < 0) {
                return abbreviateMiddle(prefix, 15, 8) + endpoint;
            }
            return prefix.substring(0, firstSlash) + "/..." + endpoint;
        }
    }

    public void incrementActiveRequests() {
        int count = activeRequests.incrementAndGet();
        if (count == 1) {
            // ⭐ 0→1 时通知 waitForActiveRequest() 的调用方（DELAY 延迟载荷场景）
            synchronized (completionLock) {
                completionLock.notifyAll();
            }
        }
        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[ApiCaptureContext] incrementActiveRequests -> {}", count);
    }

    /**
     * 递减活动请求计数。当计数归零时通知所有等待 {@link #awaitCompletion} 的线程。
     */
    public void decrementActiveRequests() {
        int remaining = activeRequests.decrementAndGet();
        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[ApiCaptureContext] decrementActiveRequests -> {}", remaining);
        if (remaining == 0) {
            synchronized (completionLock) {
                completionLock.notifyAll();
            }
        }
    }

    public int getActiveRequests() {
        return activeRequests.get();
    }

    /**
     * 阻塞等待至少一个请求被 Route 拦截过（activeRequests 从 0→1）。
     *
     * <p>典型用途：DELAY 延迟载荷的 loading UI 验证。操作触发 API 请求后，
     * 调用此方法确认 Route 已拦截请求进入延迟阻塞，然后再断言 loading 元素可见。
     *
     * <pre>{@code
     * // 确保请求已被 DELAY 拦截
     * if (!ApiCaptureContext.getCurrent().waitForActiveRequest(5000)) {
     *     throw new AssertionError("Request was not intercepted within 5s");
     * }
     * // 此时请求处于悬停状态，loading 应当已渲染
     * loadingElement.waitForVisible(10);
     * }</pre>
     *
     * @param timeoutMs 超时毫秒数（推荐 3000–5000）
     * @return true=已有请求被拦截，false=超时
     */
    public boolean waitForActiveRequest(long timeoutMs) {
        if (activeRequests.get() > 0) {
            return true;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (completionLock) {
            while (activeRequests.get() == 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    LOGGER.warn("[ApiCaptureContext] waitForActiveRequest timed out after {}ms", timeoutMs);
                    return false;
                }
                try {
                    completionLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 阻塞等待所有进行中的异步请求完成。
     *
     * <p>使用 {@code synchronized + wait/notifyAll} 替代 Thread.sleep 忙等待。
     * 线程在等待期间处于 parked 状态，不消耗 CPU。
     *
     * @param timeoutMs 超时毫秒数
     * @return true=所有请求已完成，false=超时（仍可能有请求未完成）
     * @throws InterruptedException 如果等待被中断
     */
    public boolean awaitCompletion(long timeoutMs) throws InterruptedException {
        if (activeRequests.get() == 0) {
            return true;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (completionLock) {
            while (activeRequests.get() > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                completionLock.wait(remaining);
            }
        }
        return true;
    }

    /** 标记断言失败（兼容旧调用） */
    public void setAssertionFailure() {
        hasAssertionFailures.set(true);
    }

    /**
     * 记录断言失败详细信息。
     *
     * @param url           请求 URL
     * @param assertionType 断言类型（"STATUS" 或 "JSONPATH"）
     * @param expectedValue 预期值
     * @param actualValue   实际值
     * @param failMessage   额外失败信息
     */
    public void recordAssertionFailure(String url, String assertionType,
                                       String expectedValue, String actualValue, String failMessage) {
        hasAssertionFailures.set(true);
        failureDetails.add(new AssertionFailureDetail(
                url, assertionType, expectedValue, actualValue, failMessage));
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[ApiCaptureContext] recordAssertionFailure: url={}, type={}, expected='{}', actual='{}', msg='{}'",
                url, assertionType, expectedValue, actualValue, failMessage);
    }

    public boolean hasAssertionFailures() {
        return hasAssertionFailures.get();
    }

    /**
     * 获取断言失败详情列表（不可变副本）
     */
    public List<AssertionFailureDetail> getFailureDetails() {
        synchronized (failureDetails) {
            return new ArrayList<>(failureDetails);
        }
    }

    /**
     * 生成易读的断言失败报告（含标题头，供日志使用）。
     */
    public String buildFailureReport() {
        List<AssertionFailureDetail> details = getFailureDetails();
        if (details.isEmpty()) return "No assertion failures recorded.";
        StringBuilder sb = new StringBuilder();
        sb.append("API Assertion Failures (").append(details.size()).append(")\n");
        for (AssertionFailureDetail d : details) {
            sb.append(d.toString()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 生成断言失败详情（不含标题头，供报告区块内展示）。
     * <p>标题由 {@code Serenity.recordReportData().withTitle("API Assertion Failures")} 单独提供。
     */
    public String buildFailureDetails() {
        List<AssertionFailureDetail> details = getFailureDetails();
        if (details.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (AssertionFailureDetail d : details) {
            sb.append(d.toString()).append("\n");
        }
        // 去除末尾多余的换行
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    public void reset() {
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[ApiCaptureContext] reset() — clearing activeRequests={}, failures={}, responses={}, apiCalls={}, apiCallsByUrl={}, recentCalls={}, wildcardKeys={}",
                activeRequests.get(), failureDetails.size(), getTotalResponseCount(),
                apiCallsPerUrl.size(), apiCallsByUrl.size(), recentCalls.size(), wildcardPatternKeys.size());
        activeRequests.set(0);
        hasAssertionFailures.set(false);
        failureDetails.clear();
        responseStorage.clear();
        apiCallsPerUrl.clear();
        apiCallsByUrl.clear();
        recentCalls.clear();
        wildcardPatternKeys.clear();
        totalResponseSize.set(0L);
        testThread = null;
        synchronized (completionLock) {
            completionLock.notifyAll();
        }
        // ⭐ #5：唤醒 waitForApi 等待线程（避免残留等待）
        synchronized (apiCallLock) {
            apiCallLock.notifyAll();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CapturedApiCall 存储（推荐）
    // ═══════════════════════════════════════════════════════════

    /**
     * 存储一次完整的 API 调用快照（Monitor / Mock / Modify 均可使用）。
     *
     * <p><b>性能优化</b>：同时索引到 urlPattern 和 requestUrl 两个 Map，
     * 支持 O(1) 精确 URL 检索 + Ant 通配符 fallback。
     */
    public void storeApiCall(CapturedApiCall call) {
        if (call == null || call.endpoint() == null) return;

        // ── 主索引：urlPattern → 调用列表（兼容通配符检索）──
        String endpoint = call.endpoint();
        apiCallsPerUrl.computeIfAbsent(endpoint, k ->
                java.util.Collections.synchronizedList(new ArrayList<>())
        ).add(call);

        // ⭐ #4 通配符索引：包含 * 的 pattern 注册到专用集合，加速 fallback 检索
        if (containsGlobWildcard(endpoint)) {
            wildcardPatternKeys.add(endpoint);
        }

        // ── 辅助索引：requestUrl → 调用列表（毫秒级精确检索）──
        String url = call.requestUrl();
        if (url != null) {
            apiCallsByUrl.computeIfAbsent(url, k ->
                    java.util.Collections.synchronizedList(new ArrayList<>())
            ).add(call);
        }

        // ⭐ P3: 追加到平铺最近调用列表（用于 scanForMatching 快速扫描）
        //   超限时移除最老的一半条目
        if (recentCalls.size() >= MAX_RECENT_CALLS) {
            List<CapturedApiCall> toRemove = new ArrayList<>(recentCalls.subList(0, MAX_RECENT_CALLS / 2));
            recentCalls.removeAll(toRemove);
        }
        recentCalls.add(call);

        // ⭐ #5 wait/notify：通知 waitForApi 等待线程有新调用到达
        synchronized (apiCallLock) {
            apiCallLock.notifyAll();
        }

        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[ApiCaptureContext] storeApiCall: endpoint='{}', method={}, status={}, bodyLen={}",
                endpoint, call.method(), call.statusCode(),
                call.responseBody() != null ? call.responseBody().length() : 0);
    }

    /**
     * 获取指定端点的所有 API 调用快照（按调用顺序）。
     *
     * <p>支持两种匹配策略：
     * <ol>
     *   <li><b>精确匹配</b> — 直接按存储 key 查找（O(1)）</li>
     *   <li><b>Ant 通配符匹配</b> — 若精确匹配未命中，将存储的 urlPattern
     *       key（如 {@code /api/users/*}）按 ant 风格 glob 匹配查询的 endpoint
     *       （如 {@code /api/users/1}）</li>
     * </ol>
     *
     * <pre>{@code
     * List<CapturedApiCall> calls = ctx.getApiCalls("/api/track");
     * for (CapturedApiCall c : calls) {
     *     System.out.println(c.statusCode() + " " + c.responseHeader("Content-Type"));
     * }
     * }</pre>
     *
     * @param endpoint 请求端点（路径+查询，不含 host，如 /api/users/1?page=2）
     * @return 不可变副本列表，未找到返回空列表
     */
    public List<CapturedApiCall> getApiCalls(String endpoint) {
        // 1. 精确匹配（fast path）
        List<CapturedApiCall> list = apiCallsPerUrl.get(endpoint);
        if (list != null && !list.isEmpty()) {
            synchronized (list) {
                return new ArrayList<>(list);
            }
        }

        // 2. ⭐ #4 Ant 通配符 fallback（仅遍历已知含通配符的 pattern key）
        //    当多个 pattern 同时匹配时，选择调用时间最近的那组（而非依赖 Map 迭代顺序）。
        List<CapturedApiCall> bestMatch = null;
        long bestTimestamp = 0;
        for (String storedPattern : wildcardPatternKeys) {
            if (antGlobMatch(storedPattern, endpoint)) {
                List<CapturedApiCall> matched = apiCallsPerUrl.get(storedPattern);
                if (matched != null && !matched.isEmpty()) {
                    synchronized (matched) {
                        CapturedApiCall last = matched.get(matched.size() - 1);
                        if (last.timestamp() > bestTimestamp) {
                            bestMatch = matched;
                            bestTimestamp = last.timestamp();
                        }
                    }
                }
            }
        }
        if (bestMatch != null) {
            synchronized (bestMatch) {
                return new ArrayList<>(bestMatch);
            }
        }
        return Collections.emptyList();
    }

    /**
     * 获取指定端点的最近一次 API 调用快照。
     *
     * <p>支持两种匹配策略：<b>精确匹配</b> → <b>Ant 通配符匹配</b>（见 {@link #getApiCalls}）。
     *
     * @param endpoint 请求端点（路径+查询，不含 host，如 /api/users/1）
     * @return 捕获的快照，未找到返回 null
     */
    public CapturedApiCall getLastApiCall(String endpoint) {
        // 1. 精确匹配（fast path）
        List<CapturedApiCall> list = apiCallsPerUrl.get(endpoint);
        if (list != null && !list.isEmpty()) {
            synchronized (list) {
                return list.get(list.size() - 1);
            }
        }

        // 2. ⭐ #4 Ant 通配符 fallback（仅遍历已知含通配符的 pattern key）
        //    当多个 pattern 同时匹配（如 /api/** 和 /api/users/* 都匹配 /api/users/1），
        //    选择调用时间最近的那次（而非依赖 Map 迭代顺序）。
        CapturedApiCall latest = null;
        long latestTimestamp = 0;
        for (String storedPattern : wildcardPatternKeys) {
            if (antGlobMatch(storedPattern, endpoint)) {
                List<CapturedApiCall> matched = apiCallsPerUrl.get(storedPattern);
                if (matched != null && !matched.isEmpty()) {
                    synchronized (matched) {
                        CapturedApiCall last = matched.get(matched.size() - 1);
                        if (last.timestamp() > latestTimestamp) {
                            latest = last;
                            latestTimestamp = last.timestamp();
                        }
                    }
                }
            }
        }
        return latest;
    }

    /**
     * 检查字符串是否包含 ant 通配符（{@code *} 或 {@code **}）。
     */
    private static boolean containsGlobWildcard(String s) {
        return s.indexOf('*') >= 0;
    }

    /**
     * Ant 风格 Glob 匹配 — 将存储的 urlPattern 与查询的 endpoint 进行匹配。
     *
     * <p>通配符语义：
     * <ul>
     *   <li>{@code *} — 匹配单层路径段（不含 {@code /}）</li>
     *   <li>{@code **} — 匹配任意层级的路径（含 {@code /}）</li>
     * </ul>
     *
     * <p>示例：
     * <pre>{@code
     * antGlobMatch("/api/users/*", "/api/users/1")     → true
     * antGlobMatch("/api/users/*", "/api/users/1/2")   → false
     * antGlobMatch("/api/**",     "/api/users/1")     → true
     * }</pre>
     *
     * @param storedPattern 存储的 urlPattern（可能含通配符）
     * @param endpoint      查询的真实 endpoint（不含通配符）
     * @return 是否匹配
     */
    private static boolean antGlobMatch(String storedPattern, String endpoint) {
        return antGlobToRegex(storedPattern).matcher(endpoint).matches();
    }

    /**
     * 将 ant 风格 glob 编译为 {@link Pattern}。
     *
     * <p><b>性能优化</b>：使用 {@link #PATTERN_CACHE} 缓存编译结果，
     * 上限 {@link #MAX_PATTERN_CACHE_SIZE}，避免每次调用 {@code Pattern.compile()}。
     */
    private static Pattern antGlobToRegex(String glob) {
        Pattern cached = PATTERN_CACHE.get(glob);
        if (cached != null) return cached;

        String regex = antGlobToRegexString(glob);
        Pattern compiled = Pattern.compile(regex);

        // ⭐ #7 伪 LRU：超限时移除 ~25% 条目（避免全量清空导致命中率归零）
        if (PATTERN_CACHE.size() >= MAX_PATTERN_CACHE_SIZE) {
            evictOldestQuarter(PATTERN_CACHE);
        }
        PATTERN_CACHE.put(glob, compiled);
        return compiled;
    }

    /**
     * ⭐ #7 伪 LRU 淘汰辅助：从 ConcurrentHashMap 中移除约 25% 的条目。
     * <p>使用弱一致性迭代器，适合并发场景下的近似 LRU。
     */
    private static void evictOldestQuarter(ConcurrentHashMap<?, ?> map) {
        int evictCount = Math.max(1, map.size() / 4);
        Iterator<?> it = map.keySet().iterator();
        for (int i = 0; i < evictCount && it.hasNext(); i++) {
            it.next();
            it.remove();
        }
    }

    /**
     * Ant glob → 正则字符串（不含编译，供缓存层调用）。
     */
    private static String antGlobToRegexString(String glob) {
        StringBuilder sb = new StringBuilder();
        sb.append('^');
        int len = glob.length();
        int i = 0;
        while (i < len) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < len && glob.charAt(i + 1) == '*') {
                sb.append(".*");
                i += 2;
            } else if (c == '*') {
                sb.append("[^/]*");
                i++;
            } else {
                if (c == '.' || c == '+' || c == '?' || c == '(' || c == ')'
                        || c == '[' || c == ']' || c == '{' || c == '}'
                        || c == '\\' || c == '^' || c == '$' || c == '|') {
                    sb.append('\\');
                }
                sb.append(c);
                i++;
            }
        }
        sb.append('$');
        return sb.toString();
    }

    /**
     * 获取所有端点的 API 调用快照（每个端点仅返回最近一次）。
     *
     * @return 不可变 Map 副本，key=端点, value=最近一次快照
     */
    public Map<String, CapturedApiCall> getAllLastApiCalls() {
        Map<String, CapturedApiCall> result = new java.util.HashMap<>();
        for (Map.Entry<String, List<CapturedApiCall>> e : apiCallsPerUrl.entrySet()) {
            List<CapturedApiCall> list = e.getValue();
            if (list != null && !list.isEmpty()) {
                synchronized (list) {
                    result.put(e.getKey(), list.get(list.size() - 1));
                }
            }
        }
        return result;
    }

    /**
     * 获取所有端点的全部 API 调用快照。
     *
     * @return 不可变 Map 副本，key=端点, value=全部快照列表
     */
    public Map<String, List<CapturedApiCall>> getAllApiCalls() {
        Map<String, List<CapturedApiCall>> result = new java.util.HashMap<>();
        for (Map.Entry<String, List<CapturedApiCall>> e : apiCallsPerUrl.entrySet()) {
            List<CapturedApiCall> list = e.getValue();
            if (list != null) {
                synchronized (list) {
                    result.put(e.getKey(), new ArrayList<>(list));
                }
            }
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // ⭐ 性能优化：URL 精确索引 + Predicate 条件等待
    // ═══════════════════════════════════════════════════════════

    /**
     * 按实际请求 URL 精确获取 API 调用 — O(1) 毫秒级检索。
     *
     * <pre>{@code
     * // 页面操作触发大量 API 后，按完整 URL 直接定位目标调用
     * CapturedApiCall call = ctx.getCallByUrl("http://host:port/api/users/1");
     * }</pre>
     *
     * @param requestUrl 实际请求的完整 URL
     * @return 最近一次该 URL 的调用快照，未找到返回 null
     */
    public CapturedApiCall getCallByUrl(String requestUrl) {
        if (requestUrl == null) return null;
        List<CapturedApiCall> list = apiCallsByUrl.get(requestUrl);
        if (list != null && !list.isEmpty()) {
            synchronized (list) {
                return list.get(list.size() - 1);
            }
        }
        return null;
    }

    /**
     * 按请求 URL 获取该 URL 的所有 API 调用历史。
     *
     * @param requestUrl 实际请求的完整 URL
     * @return 不可变副本列表，未找到返回空列表
     */
    public List<CapturedApiCall> getCallsByUrl(String requestUrl) {
        if (requestUrl == null) return Collections.emptyList();
        List<CapturedApiCall> list = apiCallsByUrl.get(requestUrl);
        if (list != null && !list.isEmpty()) {
            synchronized (list) {
                return new ArrayList<>(list);
            }
        }
        return Collections.emptyList();
    }

    /**
     * 条件等待 — 阻塞直到匹配 predicate 的 API 调用出现（毫秒级响应）。
     *
     * <p>不同于 {@link #awaitCompletion(long)} 等待<b>所有</b>异步请求完成，
     * 本方法按指定条件精准等待单一目标 API，一经命中立即返回，无需等待无关请求。
     *
     * <p>适用场景：页面操作触发数百个 API，仅关注其中 1 个目标请求。
     *
     * <pre>{@code
     * // 等待 POST /api/login 返回 200
     * CapturedApiCall login = ctx.waitForApi(
     *     c -> "POST".equals(c.method()) && c.endpoint().contains("login") && c.isOk(),
     *     5_000);
     * }</pre>
     *
     * @param predicate 匹配条件（在 Playwright 事件线程的存储回调中检查）
     * @param timeoutMs 超时毫秒数
     * @return 匹配的 API 调用快照，超时返回 null
     */
    public CapturedApiCall waitForApi(Predicate<CapturedApiCall> predicate, long timeoutMs) {
        if (predicate == null) return null;

        // ── Fast Path：先扫描已存储的调用 ──
        CapturedApiCall found = scanForMatching(predicate);
        if (found != null) return found;

        // ── ⭐ #5 条件等待：使用 wait/notify 替代忙轮询（Thread.sleep）──
        //    storeApiCall 在新调用存储后 notifyAll，waitForApi 被精确唤醒。
        //    超时时间超过 50ms 时使用 wait 模式；短超时保留轮询以降低切换开销。
        long deadline = System.currentTimeMillis() + timeoutMs;

        if (timeoutMs > 50) {
            // 长超时：条件等待（线程 parked，零 CPU）
            synchronized (apiCallLock) {
                while (System.currentTimeMillis() < deadline) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    try {
                        apiCallLock.wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    found = scanForMatching(predicate);
                    if (found != null) return found;
                }
            }
        } else {
            // 短超时：轻量轮询（避免 wait/notify 上下文切换开销）
            int pollInterval = 10;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(pollInterval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                found = scanForMatching(predicate);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * 遍历所有已存储的 API 调用，返回第一个匹配 predicate 的 CapturedApiCall。
     * <p>⭐ P3: 优先扫描 recentCalls 平铺列表（按时间排序，最新在前），单次 O(1) 尺寸扫描。
     * <p>仅在 recentCalls 未命中时 fallback 到 Map 遍历（兼容极边缘调用）。
     */
    private CapturedApiCall scanForMatching(Predicate<CapturedApiCall> predicate) {
        // ⭐ P3: Fast path — 从平铺列表由新到旧扫描（绝大多数命中即返回）
        for (int i = recentCalls.size() - 1; i >= 0; i--) {
            CapturedApiCall c = recentCalls.get(i);
            if (predicate.test(c)) return c;
        }
        // Fallback 扫描 Map（兼容 recentCalls 已被淘汰的边缘调用，理论上极少触发）
        for (List<CapturedApiCall> calls : apiCallsByUrl.values()) {
            if (calls != null) {
                synchronized (calls) {
                    for (int i = calls.size() - 1; i >= 0; i--) {
                        CapturedApiCall c = calls.get(i);
                        if (predicate.test(c)) return c;
                    }
                }
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════
    // Response body 存储（向后兼容）
    // ═══════════════════════════════════════════════════════════

    /**
     * 存储 API 响应体（追加到该端点的列表中，不覆盖历史调用）。
     *
     * <p>双重上限保护：
     * <ul>
     *   <li>数量上限：{@link #MAX_RESPONSE_STORAGE} 条响应</li>
     *   <li>体积上限：{@link #MAX_RESPONSE_TOTAL_SIZE} 字节（10MB）</li>
     * </ul>
     *
     * @param endpoint     请求端点（路径+查询，不含 host）
     * @param responseBody 响应体字符串
     */
    public void storeResponse(String endpoint, String responseBody) {
        if (endpoint == null || responseBody == null) {
            return;
        }

        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[ApiCaptureContext] storeResponse: endpoint='{}', bodyLen={}, totalSize={}",
                endpoint, responseBody.length(), formatBytes(totalResponseSize.get()));

        // 数量上限检查
        int total = getTotalResponseCount();
        if (total >= MAX_RESPONSE_STORAGE) {
            LOGGER.warn("[ApiCaptureContext] Response count limit reached ({} >= {}). "
                            + "Subsequent responses will NOT be stored. Consider calling reset() between tests.",
                    total, MAX_RESPONSE_STORAGE);
            return;  // 直接拒绝存储
        }

        // 体积上限检查
        long currentSize = totalResponseSize.get();
        if (currentSize >= MAX_RESPONSE_TOTAL_SIZE) {
            LOGGER.warn("[ApiCaptureContext] Response total size limit reached ({} >= {}). "
                            + "Subsequent responses will NOT be stored to prevent OOM.",
                    formatBytes(currentSize), formatBytes(MAX_RESPONSE_TOTAL_SIZE));
            return;
        }

        // 写入存储
        int bodySize = responseBody.length();
        responseStorage.computeIfAbsent(endpoint, k ->
                java.util.Collections.synchronizedList(new ArrayList<>())
        ).add(responseBody);
        totalResponseSize.addAndGet(bodySize);
    }

    /**
     * 格式化字节数为易读字符串（KB/MB）。
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * 获取已存储的 API 响应体（返回最近一次调用）。
     *
     * @param endpoint 请求端点
     * @return 最新的响应体字符串，未找到返回 null
     */
    public String getStoredResponse(String endpoint) {
        List<String> list = responseStorage.get(endpoint);
        return (list != null && !list.isEmpty()) ? list.get(list.size() - 1) : null;
    }

    /**
     * 获取指定端点所有响应（按调用顺序保留，分页场景适用）。
     *
     * @param endpoint 请求端点
     * @return 不可变副本列表，未找到返回空列表
     */
    public List<String> getAllResponsesForUrl(String endpoint) {
        List<String> list = responseStorage.get(endpoint);
        if (list == null || list.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    /**
     * 获取所有已存储的响应（仅返回每个 URL 最近一次调用）。
     *
     * @return Map 副本（不可变）
     */
    public Map<String, String> getAllStoredResponses() {
        Map<String, String> result = new java.util.HashMap<>();
        for (Map.Entry<String, List<String>> e : responseStorage.entrySet()) {
            List<String> list = e.getValue();
            if (list != null && !list.isEmpty()) {
                synchronized (list) {
                    result.put(e.getKey(), list.get(list.size() - 1));
                }
            }
        }
        return result;
    }

    /**
     * 获取所有已存储的响应（每个 URL 的全部调用历史）。
     *
     * @return Map 副本（不可变），key=URL, value=全部响应列表
     */
    public Map<String, List<String>> getAllStoredResponseLists() {
        Map<String, List<String>> result = new java.util.HashMap<>();
        for (Map.Entry<String, List<String>> e : responseStorage.entrySet()) {
            List<String> list = e.getValue();
            if (list != null) {
                synchronized (list) {
                    result.put(e.getKey(), new ArrayList<>(list));
                }
            }
        }
        return result;
    }

    /**
     * 获取已捕获的响应总数（所有 URL 的所有调用次数之和）。
     */
    public int getTotalResponseCount() {
        int total = 0;
        for (List<String> list : responseStorage.values()) {
            total += list.size();
        }
        return total;
    }

    /**
     * 获取指定端点的调用次数。
     */
    public int getResponseCountForUrl(String endpoint) {
        List<String> list = responseStorage.get(endpoint);
        return list != null ? list.size() : 0;
    }

    /**
     * 清除所有已存储的响应。
     */
    public void clearStoredResponses() {
        responseStorage.clear();
    }
}
