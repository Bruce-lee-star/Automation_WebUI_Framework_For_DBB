package com.hsbc.cmb.hk.dbb.automation.framework.route.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 *  API 采集存储（场景作用域）。
 *
 * <p>承载「API 采集」捕获的全部 API 调用快照，独立于 {@link ApiCaptureContext} 的测试断言存储，
 * 因此与 Mock / Modify / Delay / Monitor 各 Handler <b>互不抢占资源</b>。
 *
 * <p><b>采集双通道</b>：
 * <ul>
 *   <li>① Handler 汇聚通道：各 Route Handler 经 {@code ApiCaptureContext.storeApiCall/storeDelayMarker}
 *       统一写入，携带完整 {@code handleType}（MOCK / MODIFY / DELAY / MONITOR）与 {@code modifyDetail}；</li>
 *   <li>② 全局 onResponse 兜底通道：捕获<b>未注册</b>流量（Playwright 原生非侵入事件流，不影响请求生命周期）。</li>
 * </ul>
 *
 * <p><b>去重合并</b>：同一请求 URL 上，若 Handler 已记录「更丰富」的调用（delay/mock/modify），
 * 则 onResponse 的 MONITOR 兜底记录被跳过，确保 delay/mock/modify 信息不被纯观测记录覆盖、也不重复。
 *
 * <p><b>容量保护</b>：每个端点的记录数受 {@link #MAX_CALLS_PER_ENDPOINT} 上限约束，防止长场景内存膨胀。
 */
public class ApiCaptureStore {

    private static final int MAX_CALLS_PER_ENDPOINT = 200;

    /** 按端点（不含 host）索引的全部快照。 */
    private final Map<String, List<CapturedApiCall>> byEndpoint = new ConcurrentHashMap<>();
    /** 按完整请求 URL 索引（用于 onResponse 兜底去重）。 */
    private final Map<String, List<CapturedApiCall>> byUrl = new ConcurrentHashMap<>();

    /**
     * 记录一次采集到的 API 调用。
     *
     * @param call 调用快照（已携带正确的 {@code handleType}）
     */
    public void record(CapturedApiCall call) {
        if (call == null) return;
        String endpoint = call.endpoint();
        if (endpoint == null) return;
        String url = call.requestUrl();

        //  去重：onResponse 兜底（MONITOR）若遇到 Handler 已记录的「更丰富」调用则跳过，
        //   避免 delay/mock/modify 信息被纯观测记录覆盖或重复。
        if (url != null && call.handleType() == RouteHandleType.MONITOR) {
            List<CapturedApiCall> existing = byUrl.get(url);
            if (existing != null && !existing.isEmpty()) {
                CapturedApiCall last = existing.get(existing.size() - 1);
                if (last.handleType() != RouteHandleType.MONITOR) {
                    return;
                }
            }
        }

        List<CapturedApiCall> epList = byEndpoint.computeIfAbsent(endpoint,
                k -> new CopyOnWriteArrayList<>());
        if (epList.size() >= MAX_CALLS_PER_ENDPOINT) {
            epList.remove(0);
        }
        epList.add(call);

        if (url != null) {
            List<CapturedApiCall> urlList = byUrl.computeIfAbsent(url,
                    k -> new CopyOnWriteArrayList<>());
            if (urlList.size() >= MAX_CALLS_PER_ENDPOINT) {
                urlList.remove(0);
            }
            urlList.add(call);
        }
    }

    /** 获取指定端点的全部快照（按调用顺序）。 */
    public List<CapturedApiCall> getApiCalls(String endpoint) {
        List<CapturedApiCall> list = byEndpoint.get(endpoint);
        return list != null ? new ArrayList<>(list) : Collections.emptyList();
    }

    /** 获取指定端点的最近一次快照；未找到返回 null。 */
    public CapturedApiCall getLastApiCall(String endpoint) {
        List<CapturedApiCall> list = byEndpoint.get(endpoint);
        return (list != null && !list.isEmpty()) ? list.get(list.size() - 1) : null;
    }

    /** 按请求 URL 精确获取最近一次调用。 */
    public CapturedApiCall getCallByUrl(String url) {
        if (url == null) return null;
        List<CapturedApiCall> list = byUrl.get(url);
        return (list != null && !list.isEmpty()) ? list.get(list.size() - 1) : null;
    }

    /** 按请求 URL 获取全部历史。 */
    public List<CapturedApiCall> getCallsByUrl(String url) {
        if (url == null) return Collections.emptyList();
        List<CapturedApiCall> list = byUrl.get(url);
        return list != null ? new ArrayList<>(list) : Collections.emptyList();
    }

    /** 按能力类型（MOCK / MODIFY / DELAY / MONITOR）获取全部快照（按时间升序）。 */
    public List<CapturedApiCall> getAllByType(RouteHandleType type) {
        if (type == null) return Collections.emptyList();
        List<CapturedApiCall> result = new ArrayList<>();
        for (List<CapturedApiCall> list : byEndpoint.values()) {
            for (CapturedApiCall c : list) {
                if (c != null && c.handleType() == type) result.add(c);
            }
        }
        result.sort(Comparator.comparingLong(CapturedApiCall::timestamp));
        return result;
    }

    /** 按能力类型分组获取全部快照。 */
    public Map<RouteHandleType, List<CapturedApiCall>> getAllGroupedByType() {
        Map<RouteHandleType, List<CapturedApiCall>> grouped = new EnumMap<>(RouteHandleType.class);
        for (RouteHandleType t : RouteHandleType.values()) {
            grouped.put(t, new ArrayList<>());
        }
        for (List<CapturedApiCall> list : byEndpoint.values()) {
            for (CapturedApiCall c : list) {
                if (c != null) grouped.get(c.handleType()).add(c);
            }
        }
        for (List<CapturedApiCall> l : grouped.values()) {
            l.sort(Comparator.comparingLong(CapturedApiCall::timestamp));
        }
        return grouped;
    }

    /** 获取全部端点的全部快照。 */
    public Map<String, List<CapturedApiCall>> getAllApiCalls() {
        Map<String, List<CapturedApiCall>> result = new java.util.HashMap<>();
        for (Map.Entry<String, List<CapturedApiCall>> e : byEndpoint.entrySet()) {
            result.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return result;
    }

    /** 已捕获响应总数。 */
    public int getTotalResponseCount() {
        int total = 0;
        for (List<CapturedApiCall> list : byEndpoint.values()) {
            for (CapturedApiCall c : list) {
                if (c != null && c.responseBody() != null) total++;
            }
        }
        return total;
    }

    /** 即时清空全部数据（场景切换 / 资源回收时调用，释放内存）。 */
    public void clear() {
        byEndpoint.clear();
        byUrl.clear();
    }
}
