package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * API 监控上下文 — 线程隔离的断言失败标记 + 详细失败信息 + 响应捕获存储。
 *
 * <p>每个测试线程拥有独立的上下文，MonitorHandler 在异步线程中
 * 标记断言失败，PlaywrightListener 在测试结束时（主线程）检查并处理。
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
 * @see RouteEngine#getCurrentApiMonitorContext()
 */
public class ApiMonitorContext {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiMonitorContext.class);

    /**
     * 每个测试线程独立的上下文实例。
     */
    private static final ThreadLocal<ApiMonitorContext> INSTANCE =
            ThreadLocal.withInitial(ApiMonitorContext::new);

    /**
     * 获取当前线程的 API 监控上下文。
     */
    public static ApiMonitorContext getCurrent() {
        return INSTANCE.get();
    }

    /**
     * 重置当前线程的 API 监控上下文（测试开始时调用）。
     */
    public static void resetCurrent() {
        ApiMonitorContext ctx = INSTANCE.get();
        if (ctx != null) {
            ctx.reset();
        }
    }

    /**
     * 移除当前线程的 API 监控上下文（测试结束时调用）。
     */
    public static void removeCurrent() {
        INSTANCE.remove();
    }

    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final AtomicBoolean hasAssertionFailures = new AtomicBoolean(false);

    /** 等待锁：decrement → 0 时通知 awaitCompletion 的调用方 */
    private final Object completionLock = new Object();

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
            return String.format("  [%s] %s: expected='%s', actual='%s'%s",
                    assertionType, url,
                    expectedValue != null ? expectedValue : "N/A",
                    actualValue != null ? actualValue : "N/A",
                    failMessage != null ? " (" + failMessage + ")" : "");
        }
    }

    public void incrementActiveRequests() {
        activeRequests.incrementAndGet();
    }

    /**
     * 递减活动请求计数。当计数归零时通知所有等待 {@link #awaitCompletion} 的线程。
     */
    public void decrementActiveRequests() {
        int remaining = activeRequests.decrementAndGet();
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
     * 生成易读的断言失败报告
     */
    public String buildFailureReport() {
        List<AssertionFailureDetail> details = getFailureDetails();
        if (details.isEmpty()) return "No assertion failures recorded.";
        StringBuilder sb = new StringBuilder();
        sb.append("=== API Assertion Failures (").append(details.size()).append(") ===\n");
        for (AssertionFailureDetail d : details) {
            sb.append(d.toString()).append("\n");
        }
        return sb.toString();
    }

    public void reset() {
        activeRequests.set(0);
        hasAssertionFailures.set(false);
        failureDetails.clear();
        responseStorage.clear();
        apiCallsPerUrl.clear();
        totalResponseSize.set(0L);
        synchronized (completionLock) {
            completionLock.notifyAll();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CapturedApiCall 存储（推荐）
    // ═══════════════════════════════════════════════════════════

    /**
     * 存储一次完整的 API 调用快照。
     */
    public void storeApiCall(CapturedApiCall call) {
        if (call == null || call.endpoint() == null) return;
        apiCallsPerUrl.computeIfAbsent(call.endpoint(), k ->
                java.util.Collections.synchronizedList(new ArrayList<>())
        ).add(call);
    }

    /**
     * 获取指定端点的所有 API 调用快照（按调用顺序）。
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
        List<CapturedApiCall> list = apiCallsPerUrl.get(endpoint);
        if (list == null || list.isEmpty()) return Collections.emptyList();
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    /**
     * 获取指定端点的最近一次 API 调用快照。
     *
     * @param endpoint 请求端点（路径+查询，不含 host，如 /api/users/1）
     * @return 捕获的快照，未找到返回 null
     */
    public CapturedApiCall getLastApiCall(String endpoint) {
        List<CapturedApiCall> list = apiCallsPerUrl.get(endpoint);
        if (list == null || list.isEmpty()) return null;
        synchronized (list) {
            return list.get(list.size() - 1);
        }
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

        // 数量上限检查
        int total = getTotalResponseCount();
        if (total >= MAX_RESPONSE_STORAGE) {
            LOGGER.warn("[ApiMonitorContext] Response count limit reached ({} >= {}). "
                            + "Subsequent responses will NOT be stored. Consider calling reset() between tests.",
                    total, MAX_RESPONSE_STORAGE);
            return;  // 直接拒绝存储
        }

        // 体积上限检查
        long currentSize = totalResponseSize.get();
        if (currentSize >= MAX_RESPONSE_TOTAL_SIZE) {
            LOGGER.warn("[ApiMonitorContext] Response total size limit reached ({} >= {}). "
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
