package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import net.thucydides.core.steps.StepEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.BrowserContext;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 *  API 采集管理器（常驻单例）。
 *
 * <p><b>定位</b>：独立于测试断言存储（{@link ApiCaptureContext}）的「非侵入采集」通道。
 * 框架启动即常驻开启（{@link #enabled} 默认 true，可经 {@link #setApiCaptureEnabled(boolean)} 关闭），
 * 对 Mock / Modify / Delay / Monitor 各 Handler <b>零干扰、零资源竞争</b>地采集全部 API 信息，
 * 供 scenario 内独立断言使用。
 *
 * <p><b>双通道汇聚</b>：
 * <ul>
 *   <li>① Handler 汇聚：{@link ApiCaptureContext#storeApiCall} / {@link ApiCaptureContext#storeDelayMarker}
 *       在写入测试断言存储的同时，调用 {@link #record(CapturedApiCall)} —— 自动携带 delay/mock/modify 的
 *       {@code handleType} 与 {@code modifyDetail}；</li>
 *   <li>② 全局 onResponse 兜底：{@link #recordPassthrough} 由 {@code ApiCaptureLifecycle} 在 Page 启动时
 *       注册的 Playwright 原生 {@code page.onResponse} 监听器调用，捕获<b>未注册</b>流量（非侵入，不与 Handler 抢占）。</li>
 * </ul>
 *
 * <p><b>场景隔离 + 即时清理</b>：复用 Serenity {@code StepEventBus} 探测 scenario 切换（与
 * {@code FileStoreMonitorCallback} 同一成熟路径），切换时<b>先 clear 旧存储释放内存、再换新实例</b>，
 * 保证 scenario 间互不影响；采集为<b>同步落库、不另起后台线程</b>，故「清理线程」包袱从根上消失，
 * 页面关闭由 Playwright 自动解绑 {@code onResponse} 监听器。
 */
public final class ApiCaptureManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiCaptureManager.class);

    private static final ApiCaptureManager INSTANCE = new ApiCaptureManager();

    /** 框架启动即常驻 API 采集；设为 false 可整体关闭。 */
    private volatile boolean enabled = true;

    /** 当前 scenario 的采集存储（场景切换时整体替换）。 */
    private volatile ApiCaptureStore currentStore = new ApiCaptureStore();

    /** 当前 scenario 标识（用于检测切换）。 */
    private volatile String currentApiCaptureScenarioKey = null;

    /** 场景切换锁。 */
    private final Object swapLock = new Object();

    /** scenario 探测结果缓存节流（反射有成本，限频至 ~5 次/秒）。 */
    private volatile long lastResolve = 0L;

    /**
     *  并发隔离采集存储：每个 {@link BrowserContext} 独立一份（弱 key，context GC 后自动回收；
     *  {@code synchronizedMap} 保证迭代与 {@code computeIfAbsent} 原子）。
     * <p>共享 Browser + 并发 Context 下，多个 worker 任务经各自 Context 路由到此 Map，
     * 不再把调用快照写入同一全局 store，根除跨任务污染（G3）。无 Context 绑定的兜底路径仍走
     * {@link #currentStore}（场景级默认存储）。
     */
    private final Map<BrowserContext, ApiCaptureStore> contextStores =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ApiCaptureManager() {
    }

    public static ApiCaptureManager getInstance() {
        return INSTANCE;
    }

    /** 整体开启 / 关闭 API 采集（默认开启）。 */
    public static void setApiCaptureEnabled(boolean on) {
        INSTANCE.enabled = on;
        LOGGER.info("[ApiCapture] capture {}", on ? "ENABLED" : "DISABLED");
    }

    public static boolean isEnabled() {
        return INSTANCE.enabled;
    }

    // ═══════════════════════════════════════════════════════════
    // 写入通道
    // ═══════════════════════════════════════════════════════════

    /**
     * 主写入入口（Handler 汇聚通道，带 Context 归属）。delay/mock/modify/monitor 全部经此进入采集存储。
     * <p>{@code context != null}（并发隔离路径）：路由到该 Context 独立存储，多任务不再写入同一全局 store（G3）。
     */
    public void record(CapturedApiCall call, BrowserContext context) {
        if (!enabled || call == null) return;
        if (context != null) {
            ensureApiCaptureStoreForContext(context);
            contextStores.computeIfAbsent(context, k -> new ApiCaptureStore()).record(call);
            return;
        }
        ensureApiCaptureStore();
        //  捕获局部引用，避免与场景切换 swap currentStore 之间的 TOCTOU 竞态
        ApiCaptureStore store = currentStore;
        if (store != null) store.record(call);
    }

    /** 兼容无 Context 兜底入口（SHARED / onResponse 兜底通道）。 */
    public void record(CapturedApiCall call) {
        record(call, null);
    }

    /**
     * 全局 onResponse 兜底通道：捕获未注册流量。
     * 仅记录元数据（不读取响应体），保持与 Handler 的非侵入、零竞争特性。
     * <p>兼容旧签名：无 Context（兜底写入场景默认存储）。并发场景下应改用带 {@code context} 的重载以避免跨任务污染。
     */
    public void recordPassthrough(String url, int status, String method,
                                  Map<String, String> requestHeaders,
                                  Map<String, String> responseHeaders) {
        recordPassthrough(url, status, method, requestHeaders, responseHeaders, null);
    }

    /**
     * 带 Context 归属的 onResponse 兜底通道（并发隔离路径）：未注册流量按 Context 路由到独立存储（G3）。
     */
    public void recordPassthrough(String url, int status, String method,
                                  Map<String, String> requestHeaders,
                                  Map<String, String> responseHeaders,
                                  BrowserContext context) {
        if (!enabled || url == null) return;
        String endpoint = toEndpoint(url);
        CapturedApiCall call = new CapturedApiCall.Builder()
                .endpoint(endpoint)
                .method(method)
                .requestUrl(url)
                .requestHeaders(requestHeaders)
                .responseHeaders(responseHeaders)
                .statusCode(status)
                .responseBody(null)
                .timestamp(System.currentTimeMillis())
                .fromMock(false)
                .captureSource("ON_RESPONSE")
                .handleType(RouteHandleType.MONITOR)
                .build();
        record(call, context);
    }

    // ═══════════════════════════════════════════════════════════
    // 场景隔离 + 即时清理
    // ═══════════════════════════════════════════════════════════

    /**
     * 场景起步显式钩子：立即清空并换新存储（供框架在 scenario 起止调用；非 Serenity 环境亦可用）。
     */
    public void beginApiCapture() {
        synchronized (swapLock) {
            BrowserContext ctx = ApiCaptureLifecycle.currentContextOrNull();
            if (ctx != null) {
                contextStores.remove(ctx);
                contextStores.put(ctx, new ApiCaptureStore());
            } else {
                if (currentStore != null) currentStore.clear();
                currentStore = new ApiCaptureStore();
            }
            currentApiCaptureScenarioKey = null;
        }
    }

    /** 场景结束显式钩子：清空当前存储，即时释放内存。 */
    public void endApiCapture() {
        synchronized (swapLock) {
            BrowserContext ctx = ApiCaptureLifecycle.currentContextOrNull();
            if (ctx != null) {
                ApiCaptureStore s = contextStores.get(ctx);
                if (s != null) s.clear();
            } else if (currentStore != null) {
                currentStore.clear();
            }
        }
    }

    /**
     * 释放指定 BrowserContext 的采集存储（context 关闭 / 并发任务结束时调用，避免跨任务残留）。
     */
    public void clearContext(BrowserContext context) {
        if (context != null) contextStores.remove(context);
    }

    /** 释放全部 Context 采集存储（套件级全量复位）。 */
    public void clearAllContexts() {
        contextStores.clear();
    }

    /** 解析当前查询应命中的存储：优先当前线程绑定 Context 的独立存储，否则回退场景默认存储。 */
    private ApiCaptureStore resolveStore() {
        BrowserContext ctx = ApiCaptureLifecycle.currentContextOrNull();
        if (ctx != null) {
            ApiCaptureStore s = contextStores.get(ctx);
            if (s != null) return s;
        }
        return currentStore;
    }

    /** 懒检测 scenario 切换：节流反射解析，切换时先 clear 旧实例再换新，保证隔离且不累积。 */
    private void ensureApiCaptureStore() {
        long now = System.currentTimeMillis();
        if (now - lastResolve < 200) return;
        lastResolve = now;
        String key = resolveScenarioKey();
        if (key == null) return;
        if (!key.equals(currentApiCaptureScenarioKey)) {
            synchronized (swapLock) {
                if (!key.equals(currentApiCaptureScenarioKey)) {
                    if (currentStore != null) currentStore.clear();
                    currentStore = new ApiCaptureStore();
                    BrowserContext ctx = ApiCaptureLifecycle.currentContextOrNull();
                    if (ctx != null) contextStores.remove(ctx);
                    currentApiCaptureScenarioKey = key;
                    LOGGER.debug("[ApiCapture] scenario switched -> '{}', store reset", key);
                }
            }
        }
    }

    /**
     *  并发隔离路径的场景切换探测：逻辑同 {@link #ensureApiCaptureStore}，但切换时额外 drop 该 Context 的
     *  独立存储（保持每 scenario 独立、不跨场景累积），避免 feature 模式共享 Context 下串扰。
     */
    private void ensureApiCaptureStoreForContext(BrowserContext context) {
        long now = System.currentTimeMillis();
        if (now - lastResolve < 200) return;
        lastResolve = now;
        String key = resolveScenarioKey();
        if (key == null) return;
        if (!key.equals(currentApiCaptureScenarioKey)) {
            synchronized (swapLock) {
                if (!key.equals(currentApiCaptureScenarioKey)) {
                    if (currentStore != null) currentStore.clear();
                    currentStore = new ApiCaptureStore();
                    contextStores.remove(context);
                    currentApiCaptureScenarioKey = key;
                    LOGGER.debug("[ApiCapture] scenario switched -> '{}', context store reset", key);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 查询（委托当前场景存储，供 scenario 内断言）
    // ═══════════════════════════════════════════════════════════

    public ApiCaptureStore getStore() {
        return resolveStore();
    }

    public List<CapturedApiCall> getApiCalls(String endpoint) {
        return resolveStore().getApiCalls(endpoint);
    }

    public CapturedApiCall getLastApiCall(String endpoint) {
        return resolveStore().getLastApiCall(endpoint);
    }

    public List<CapturedApiCall> getAllByType(RouteHandleType type) {
        return resolveStore().getAllByType(type);
    }

    public Map<RouteHandleType, List<CapturedApiCall>> getAllGroupedByType() {
        return resolveStore().getAllGroupedByType();
    }

    public int getTotalResponseCount() {
        return resolveStore().getTotalResponseCount();
    }

    // ═══════════════════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════════════════

    /** 从完整 URL 提取端点（路径+查询，不含 host）。 */
    private static String toEndpoint(String url) {
        if (url == null) return null;
        int idx = url.indexOf("://");
        String rest = idx >= 0 ? url.substring(idx + 3) : url;
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(slash) : rest;
    }

    /**
     * 通过 Serenity 的 StepEventBus 反射获取当前 scenario 标识（复用
     * {@code FileStoreMonitorCallback} 已验证的反射路径，规避版本差异导致的编译问题）。
     *
     * @return 清洗后的 scenario 标识；取不到时返回 null（非 Serenity 环境）
     */
    private String resolveScenarioKey() {
        try {
            StepEventBus eventBus = StepEventBus.getEventBus();
            if (eventBus == null) {
                return null;
            }
            Method m = StepEventBus.class.getDeclaredMethod("currentBaseStepListener");
            m.setAccessible(true);
            Object listener = m.invoke(eventBus);
            if (listener == null) {
                return null;
            }
            Method getOutcome = listener.getClass().getMethod("getCurrentTestOutcome");
            Object outcome = getOutcome.invoke(listener);
            if (outcome == null) {
                return null;
            }
            String name = (String) outcome.getClass().getMethod("getName").invoke(outcome);
            if (name == null || name.isEmpty()) {
                return null;
            }
            return "scenario-" + toSafeDirName(name);
        } catch (Exception e) {
            // 反射读取 scenario 名失败返回 null，由调用方降级处理
            return null;
        }
    }

    private static String toSafeDirName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
    }
}
