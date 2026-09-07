package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import net.thucydides.core.steps.StepEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

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
     * 主写入入口（Handler 汇聚通道）。delay/mock/modify/monitor 全部经此进入采集存储。
     */
    public void record(CapturedApiCall call) {
        if (!enabled || call == null) return;
        ensureApiCaptureStore();
        //  捕获局部引用，避免与场景切换 swap currentStore 之间的 TOCTOU 竞态
        ApiCaptureStore store = currentStore;
        if (store != null) store.record(call);
    }

    /**
     * 全局 onResponse 兜底通道：捕获未注册流量。
     * 仅记录元数据（不读取响应体），保持与 Handler 的非侵入、零竞争特性。
     */
    public void recordPassthrough(String url, int status, String method,
                                  Map<String, String> requestHeaders,
                                  Map<String, String> responseHeaders) {
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
        record(call);
    }

    // ═══════════════════════════════════════════════════════════
    // 场景隔离 + 即时清理
    // ═══════════════════════════════════════════════════════════

    /**
     * 场景起步显式钩子：立即清空并换新存储（供框架在 scenario 起止调用；非 Serenity 环境亦可用）。
     */
    public void beginApiCapture() {
        synchronized (swapLock) {
            if (currentStore != null) currentStore.clear();
            currentStore = new ApiCaptureStore();
            currentApiCaptureScenarioKey = null;
        }
    }

    /** 场景结束显式钩子：清空当前存储，即时释放内存。 */
    public void endApiCapture() {
        synchronized (swapLock) {
            if (currentStore != null) currentStore.clear();
        }
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
                    currentApiCaptureScenarioKey = key;
                    LOGGER.debug("[ApiCapture] scenario switched -> '{}', store reset", key);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 查询（委托当前场景存储，供 scenario 内断言）
    // ═══════════════════════════════════════════════════════════

    public ApiCaptureStore getStore() {
        return currentStore;
    }

    public List<CapturedApiCall> getApiCalls(String endpoint) {
        return currentStore.getApiCalls(endpoint);
    }

    public CapturedApiCall getLastApiCall(String endpoint) {
        return currentStore.getLastApiCall(endpoint);
    }

    public List<CapturedApiCall> getAllByType(RouteHandleType type) {
        return currentStore.getAllByType(type);
    }

    public Map<RouteHandleType, List<CapturedApiCall>> getAllGroupedByType() {
        return currentStore.getAllGroupedByType();
    }

    public int getTotalResponseCount() {
        return currentStore.getTotalResponseCount();
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
