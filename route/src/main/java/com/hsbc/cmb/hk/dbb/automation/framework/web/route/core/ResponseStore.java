package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RoutePatternCache;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 *  Phase 5 抽离：响应存储域（原 {@code ApiCaptureContext} 的 ③ 响应存储域）。
 *
 * <p>职责：承载 per-context 的全部 API 调用快照存储与查询，包括
 * {@code apiCallsPerUrl} / {@code apiCallsByUrl} / {@code recentCalls} /
 * {@code delayMarkersByEndpoint} / {@code wildcardPatternKeys} / {@code totalResponseSize}
 * 及各类上限常量、{@code apiCallLock}、投递式等待器 {@code ApiCallAwaiter}。
 *
 * <p><b>关键修复（高风险管理）</b>：
 * <ul>
 *   <li>删除冗余的 {@code responseStorage}（仅存 body 字符串）——同一 body 已由
 *       {@code CapturedApiCall} 持有，旧设计存在"主快照存储 + body 备份"两份重复存储，
 *       且 {@code totalResponseSize} 被 {@code storeApiCall} 与已废弃的 {@code storeResponse}
 *       两条写入路径共享，淘汰回减不对称会导致计数器漂移 / 提前误触发 OOM 守门。</li>
 *   <li>{@code totalResponseSize} 在本类内<b>收口为单一权威计数</b>，仅由
 *       {@link #storeApiCall(CapturedApiCall)} 一处写入并随淘汰对称回减，根除跨 Map 共享风险。</li>
 * </ul>
 *
 * <p><b>零行为变更</b>：所有公开方法语义与重构前逐字一致；{@code ApiCaptureContext}
 * 保留同名公开壳并委托本类，调用方代码与既有集成护盾无需改动。本类可纯单测（无需 Playwright）。
 */
public class ResponseStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResponseStore.class);

    /**
     * Response 总字节数上限，防止大响应（如文件下载）导致 OOM。
     * <p>通过 {@link FrameworkConfig#API_CAPTURE_MAX_RESPONSE_SIZE_MB} 配置，默认 50MB。
     */
    private static final long MAX_RESPONSE_TOTAL_SIZE =
            FrameworkConfig.API_CAPTURE_MAX_RESPONSE_SIZE_MB.getLongValue() * 1024 * 1024;

    private static final int MAX_RECENT_CALLS = 500;
    private static final int MAX_CALLS_PER_ENDPOINT = 100;
    private static final int MAX_CALLS_PER_REQUEST_URL = 100;

    /** CapturedApiCall 存储 — 完整的请求/响应快照（推荐）。Key = 请求端点，Value = 该端点被调用的所有快照。 */
    private final Map<String, List<CapturedApiCall>> apiCallsPerUrl = new ConcurrentHashMap<>();

    /** URL 精确索引（毫秒级 O(1) 检索）。 */
    private final Map<String, List<CapturedApiCall>> apiCallsByUrl = new ConcurrentHashMap<>();

    /** 最近调用平铺列表 — 用于 scanForMatching 快速扫描（有界 ArrayDeque + 单锁，淘汰最老元素 O(1)）。 */
    private final java.util.ArrayDeque<CapturedApiCall> recentCalls = new java.util.ArrayDeque<>();
    private final ReentrantLock recentCallsLock = new ReentrantLock();

    /**  DELAY 维度标记存储 —— 独立于 {@link #apiCallsPerUrl}（DELAY 只记录"被延迟过"这一事实，不含响应）。 */
    private final Map<String, List<CapturedApiCall>> delayMarkersByEndpoint = new ConcurrentHashMap<>();

    /**  通配符模式索引：仅包含通配符的 urlPattern key，避免 fallback 时遍历全量。 */
    private final Set<String> wildcardPatternKeys = ConcurrentHashMap.newKeySet();

    /** 当前已存储响应总字节数（原子操作，线程安全）。 本类内唯一权威计数。 */
    private final AtomicLong totalResponseSize = new AtomicLong(0L);

    /**  wait/notify 锁：存储写入（storeApiCall/storeDelayMarker/reset）持此锁，与重置严格串行。 */
    private final Object apiCallLock = new Object();

    /**  投递式等待器注册表（点对点投递，替代"广播 notifyAll + 调用方重扫"）。 */
    private final ApiCallAwaiter apiCallAwaiter = new ApiCallAwaiter();

    // ═══════════════════════════════════════════════════════════
    // 写入
    // ═══════════════════════════════════════════════════════════

    /**
     * 存储一次完整的 API 调用快照（Monitor / Mock / Modify 均可使用）。
     * <p>同时索引到 urlPattern 与 requestUrl 两个 Map，支持 O(1) 精确 URL 检索 + Ant 通配符 fallback。
     * <p> {@code totalResponseSize} 仅在此处累加，并在超出 {@link #MAX_CALLS_PER_ENDPOINT} 淘汰最老调用时
     * 对称回减，确保计数器与真实存储字节一致，不会误触发 OOM 守门。
     */
    public void storeApiCall(CapturedApiCall call) {
        if (call == null || call.endpoint() == null) return;

        String endpoint = call.endpoint();
        String url = call.requestUrl();

        synchronized (apiCallLock) {
            int callBodySize = call.responseBody() != null ? call.responseBody().length() : 0;
            if (totalResponseSize.get() >= MAX_RESPONSE_TOTAL_SIZE) {
                LOGGER.warn("[ResponseStore] CapturedApiCall total size limit reached ({} >= {}). "
                                + "Rejecting storeApiCall for endpoint='{}' to prevent OOM.",
                        formatBytes(totalResponseSize.get()), formatBytes(MAX_RESPONSE_TOTAL_SIZE), endpoint);
                return;
            }
            totalResponseSize.addAndGet(callBodySize);

            List<CapturedApiCall> endpointCalls = apiCallsPerUrl.computeIfAbsent(endpoint, k ->
                    Collections.synchronizedList(new java.util.LinkedList<>()));
            endpointCalls.add(call);
            while (endpointCalls.size() > MAX_CALLS_PER_ENDPOINT) {
                CapturedApiCall evicted = endpointCalls.remove(0);
                if (evicted != null) {
                    int evictedLen = evicted.responseBody() != null ? evicted.responseBody().length() : 0;
                    totalResponseSize.addAndGet(-evictedLen);
                }
            }

            if (containsGlobWildcard(endpoint)) {
                wildcardPatternKeys.add(endpoint);
            }

            if (url != null) {
                List<CapturedApiCall> urlCalls = apiCallsByUrl.computeIfAbsent(url, k ->
                        Collections.synchronizedList(new java.util.LinkedList<>()));
                urlCalls.add(call);
                while (urlCalls.size() > MAX_CALLS_PER_REQUEST_URL) {
                    urlCalls.remove(0);
                }
            }

            recentCallsLock.lock();
            try {
                recentCalls.addLast(call);
                while (recentCalls.size() > MAX_RECENT_CALLS) {
                    recentCalls.pollFirst();
                }
            } finally {
                recentCallsLock.unlock();
            }

            apiCallLock.notifyAll();
            apiCallAwaiter.deliver(call);
        }

        VerboseLogging.logTraceIfVerbose(LOGGER,
                "[ResponseStore] storeApiCall: endpoint='{}', method={}, status={}, bodyLen={}",
                endpoint, call.method(), call.statusCode(),
                call.responseBody() != null ? call.responseBody().length() : 0);
    }

    /**
     * 存储一条 DELAY 维度标记（由 RouteEngine 的延迟分支调用）。
     * <p>写入独立的 {@link #delayMarkersByEndpoint}，不影响主快照存储，
     * 仅通过 {@code getAllByType(RouteHandleType.DELAY)} 检索，用于回答"哪些请求被延迟过"。
     */
    public void storeDelayMarker(CapturedApiCall call) {
        if (call == null || call.endpoint() == null) return;
        String endpoint = call.endpoint();
        synchronized (apiCallLock) {
            List<CapturedApiCall> markers = delayMarkersByEndpoint.computeIfAbsent(endpoint, k ->
                    Collections.synchronizedList(new java.util.LinkedList<>()));
            markers.add(call);
            while (markers.size() > MAX_CALLS_PER_ENDPOINT) {
                markers.remove(0);
            }
        }
        VerboseLogging.logTraceIfVerbose(LOGGER,
                "[ResponseStore] storeDelayMarker: endpoint='{}', method={}, url='{}'",
                endpoint, call.method(), RouteUtil.sanitizeUrl(call.requestUrl()));
    }

    /**
     * 更新已存储的 API 调用快照的响应体（惰性 body 读取完成后调用）。
     * <p>按 requestUrl 精确查找最近一次调用，若其 responseBody 为 null 则替换为新 body，不创建新条目。
     *
     * @return true=更新成功，false=未找到匹配的调用或 body 已存在
     */
    public boolean updateResponseBody(String requestUrl, String body) {
        if (requestUrl == null || body == null) return false;

        List<CapturedApiCall> list = apiCallsByUrl.get(requestUrl);
        if (list == null || list.isEmpty()) return false;

        synchronized (list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                CapturedApiCall existing = list.get(i);
                if (existing.responseBody() == null) {
                    CapturedApiCall updated = new CapturedApiCall.Builder()
                            .endpoint(existing.endpoint())
                            .method(existing.method())
                            .requestUrl(existing.requestUrl())
                            .requestHeaders(existing.requestHeaders())
                            .requestBody(existing.requestBody())
                            .statusCode(existing.statusCode())
                            .responseHeaders(existing.responseHeaders())
                            .responseBody(body)
                            .timestamp(existing.timestamp())
                            .fromMock(existing.fromMock())
                            .captureSource(existing.captureSource())
                            .build();
                    list.set(i, updated);
                    return true;
                }
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════
    // 查询
    // ═══════════════════════════════════════════════════════════

    /**
     * 获取指定端点的所有 API 调用快照（按调用顺序）。
     * <p>匹配策略：精确匹配 → Ant 通配符 fallback（取时间最近的一组）→ 完整 URL 兜底。
     *
     * @return 不可变副本列表，未找到返回空列表
     */
    public List<CapturedApiCall> getApiCalls(String endpoint) {
        List<CapturedApiCall> list = apiCallsPerUrl.get(endpoint);
        if (list != null && !list.isEmpty()) {
            synchronized (list) {
                return new ArrayList<>(list);
            }
        }

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

        List<CapturedApiCall> byUrl = getCallsByUrl(endpoint);
        if (!byUrl.isEmpty()) {
            return byUrl;
        }
        return Collections.emptyList();
    }

    /**
     * 获取指定端点的最近一次 API 调用快照。匹配策略同 {@link #getApiCalls(String)}。
     *
     * @return 捕获的快照，未找到返回 null
     */
    public CapturedApiCall getLastApiCall(String endpoint) {
        List<CapturedApiCall> list = apiCallsPerUrl.get(endpoint);
        if (list != null && !list.isEmpty()) {
            synchronized (list) {
                return list.get(list.size() - 1);
            }
        }

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
        if (latest != null) {
            return latest;
        }

        return getCallByUrl(endpoint);
    }

    /** 获取所有端点的 API 调用快照（每个端点仅返回最近一次）。 */
    public Map<String, CapturedApiCall> getAllLastApiCalls() {
        Map<String, CapturedApiCall> result = new HashMap<>();
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

    /** 获取所有端点的全部 API 调用快照。 */
    public Map<String, List<CapturedApiCall>> getAllApiCalls() {
        Map<String, List<CapturedApiCall>> result = new HashMap<>();
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

    /**
     *  按路由能力类型获取全部 API 调用快照（按时间升序）。
     * <p>DELAY 是维度标记，存放在独立索引中（不污染主快照存储）。
     */
    public List<CapturedApiCall> getAllByType(RouteHandleType type) {
        if (type == null) return Collections.emptyList();
        if (type == RouteHandleType.DELAY) {
            List<CapturedApiCall> result = new ArrayList<>();
            for (List<CapturedApiCall> list : delayMarkersByEndpoint.values()) {
                if (list == null || list.isEmpty()) continue;
                synchronized (list) {
                    result.addAll(list);
                }
            }
            result.sort(java.util.Comparator.comparingLong(CapturedApiCall::timestamp));
            return result;
        }
        List<CapturedApiCall> result = new ArrayList<>();
        for (Map.Entry<String, List<CapturedApiCall>> e : apiCallsPerUrl.entrySet()) {
            List<CapturedApiCall> list = e.getValue();
            if (list == null || list.isEmpty()) continue;
            synchronized (list) {
                for (CapturedApiCall c : list) {
                    if (c != null && c.handleType() == type) result.add(c);
                }
            }
        }
        result.sort(java.util.Comparator.comparingLong(CapturedApiCall::timestamp));
        return result;
    }

    /**  按「能力类型 + endpoint」获取指定端点的全部快照。 */
    public List<CapturedApiCall> getApiCallsByType(String endpoint, RouteHandleType type) {
        if (type == null) return Collections.emptyList();
        if (type == RouteHandleType.DELAY) {
            List<CapturedApiCall> markers = delayMarkersByEndpoint.get(endpoint);
            if (markers == null || markers.isEmpty()) return Collections.emptyList();
            synchronized (markers) {
                return new ArrayList<>(markers);
            }
        }
        List<CapturedApiCall> all = getApiCalls(endpoint);
        if (all.isEmpty()) return Collections.emptyList();
        List<CapturedApiCall> filtered = new ArrayList<>();
        for (CapturedApiCall c : all) {
            if (c != null && c.handleType() == type) filtered.add(c);
        }
        return filtered;
    }

    /**  按「能力类型 + endpoint」获取最近一次快照；无记录返回 null。 */
    public CapturedApiCall getLastApiCallByType(String endpoint, RouteHandleType type) {
        List<CapturedApiCall> calls = getApiCallsByType(endpoint, type);
        return calls.isEmpty() ? null : calls.get(calls.size() - 1);
    }

    /**  按能力类型分组获取全部快照（四种 key 恒存在）。 */
    public Map<RouteHandleType, List<CapturedApiCall>> getAllGroupedByType() {
        Map<RouteHandleType, List<CapturedApiCall>> grouped = new EnumMap<>(RouteHandleType.class);
        for (RouteHandleType t : RouteHandleType.values()) {
            grouped.put(t, new ArrayList<>());
        }
        for (Map.Entry<String, List<CapturedApiCall>> e : apiCallsPerUrl.entrySet()) {
            List<CapturedApiCall> list = e.getValue();
            if (list == null || list.isEmpty()) continue;
            synchronized (list) {
                for (CapturedApiCall c : list) {
                    if (c != null) grouped.get(c.handleType()).add(c);
                }
            }
        }
        List<CapturedApiCall> delayBucket = grouped.get(RouteHandleType.DELAY);
        for (List<CapturedApiCall> list : delayMarkersByEndpoint.values()) {
            if (list == null || list.isEmpty()) continue;
            synchronized (list) {
                delayBucket.addAll(list);
            }
        }
        for (List<CapturedApiCall> l : grouped.values()) {
            l.sort(java.util.Comparator.comparingLong(CapturedApiCall::timestamp));
        }
        return grouped;
    }

    /** 按实际请求 URL 精确获取 API 调用 — O(1) 毫秒级检索。 */
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

    /** 按请求 URL 获取该 URL 的所有 API 调用历史。 */
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

    // ═══════════════════════════════════════════════════════════
    // 条件等待（投递式）
    // ═══════════════════════════════════════════════════════════

    /**
     * 条件等待 — 阻塞直到匹配 predicate 的 API 调用出现（毫秒级响应）。
     */
    public CapturedApiCall waitForApi(Predicate<CapturedApiCall> predicate, long timeoutMs) {
        if (predicate == null) return null;

        CapturedApiCall found = scanForMatching(predicate);
        if (found != null) return found;

        CompletableFuture<CapturedApiCall> waiter = registerApiCallWaiter(predicate);
        found = scanForMatching(predicate);
        if (found != null) {
            unregisterApiCallWaiter(waiter);
            return found;
        }
        try {
            return waiter.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (TimeoutException | ExecutionException e) {
            return null;
        } finally {
            unregisterApiCallWaiter(waiter);
        }
    }

    /** 注册一次性投递式等待器。 */
    public CompletableFuture<CapturedApiCall> registerApiCallWaiter(Predicate<CapturedApiCall> predicate) {
        return apiCallAwaiter.register(predicate);
    }

    /** 注销投递式等待器（幂等）。 */
    public void unregisterApiCallWaiter(CompletableFuture<CapturedApiCall> waiter) {
        apiCallAwaiter.unregister(waiter);
    }

    /** 遍历最近调用平铺列表 + URL 索引，返回第一个匹配 predicate 的 CapturedApiCall。 */
    private CapturedApiCall scanForMatching(Predicate<CapturedApiCall> predicate) {
        recentCallsLock.lock();
        try {
            Iterator<CapturedApiCall> it = recentCalls.descendingIterator();
            while (it.hasNext()) {
                CapturedApiCall c = it.next();
                if (predicate.test(c)) return c;
            }
        } finally {
            recentCallsLock.unlock();
        }
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
    // 统计 / 重置
    // ═══════════════════════════════════════════════════════════

    /** 获取已捕获的响应总数（所有 URL 的所有调用次数之和）。 */
    public int getTotalResponseCount() {
        int total = 0;
        for (List<CapturedApiCall> calls : apiCallsPerUrl.values()) {
            if (calls == null) continue;
            synchronized (calls) {
                for (CapturedApiCall c : calls) {
                    if (c != null && c.responseBody() != null) total++;
                }
            }
        }
        return total;
    }

    /** 重置全部存储（测试开始/结束时调用）。与写入持同一把 apiCallLock 串行，避免并发清空导致数据丢失。 */
    public void reset() {
        synchronized (apiCallLock) {
            apiCallsPerUrl.clear();
            apiCallsByUrl.clear();
            delayMarkersByEndpoint.clear();
            recentCallsLock.lock();
            try {
                recentCalls.clear();
            } finally {
                recentCallsLock.unlock();
            }
            wildcardPatternKeys.clear();
            apiCallLock.notifyAll();
            apiCallAwaiter.reset();
        }
        totalResponseSize.set(0L);
    }

    // ═══════════════════════════════════════════════════════════
    // 兼容 / 工具
    // ═══════════════════════════════════════════════════════════

    /** 伪 LRU 淘汰辅助：从 ConcurrentHashMap 中移除约 25% 的条目（兼容 RouteCoreEvictionTest）。 */
    void evictOldestQuarter(ConcurrentHashMap<?, ?> map) {
        RouteUtil.evictOldestQuarter(map);
    }

    /** 检查字符串是否包含 ant 通配符（{@code *} 或 {@code **}）。 */
    private static boolean containsGlobWildcard(String s) {
        return s.indexOf('*') >= 0;
    }

    /** Ant 风格 Glob 匹配 — 将存储的 urlPattern 与查询的 endpoint 进行匹配。 */
    private static boolean antGlobMatch(String storedPattern, String endpoint) {
        return RoutePatternCache.antGlobToRegex(storedPattern).matcher(endpoint).matches();
    }

    /** 格式化字节数为易读字符串（KB/MB）。 */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
