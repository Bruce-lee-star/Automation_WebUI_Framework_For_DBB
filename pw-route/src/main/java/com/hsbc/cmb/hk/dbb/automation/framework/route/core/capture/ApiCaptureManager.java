package com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture;

import net.thucydides.core.steps.StepEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.BrowserContext;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;

/**
 *  API 采集管理器（常驻单例）。
 *
 * <p><b>定位</b>：独立于测试断言存储（{@link ApiCaptureContext}）的「非侵入采集」通道。
 * 框架启动即常驻开启（{@link #enabled} 默认 true，可经 {@link #setApiCaptureEnabled(boolean)} 关闭），
 * 对 Mock / Modify / Delay / Monitor 各 Handler <b>零干扰、零资源竞争</b>地采集全部 API 信息，
 * 供 scenario 内独立断言使用。
 *
 * <p><b>唯一写入通道：Handler 汇聚</b>。{@link ApiCaptureContext#storeApiCall} /
 * {@link ApiCaptureContext#storeDelayMarker} 在写入测试断言存储的同时调用
 * {@link #record(CapturedApiCall)}，自动携带 delay/mock/modify 的 {@code handleType} 与
 * {@code modifyDetail}。
 *
 * <p><b>已移除（2026-09-28）</b>：原「全局 {@code page.onResponse} 兜底被动捕获」通道
 * （{@code recordPassthrough} 及其开关，配合 {@code ApiCaptureLifecycle} 的 Page 级监听器）
 * 已<b>整体删除</b>——该订阅是驱动侧 {@code Object doesn't exist: response@…} 竞态的唯一触发源，
 * 且已注册流量的响应侧观测已改走 route 通道（轮询 {@code Request#existingResponse()}），无需该通道。
 *
 * <p><b>场景隔离 + 即时清理</b>：复用 Serenity {@code StepEventBus} 探测 scenario 切换（与
 * {@code FileStoreMonitorCallback} 同一成熟路径），切换时<b>先 clear 旧存储释放内存、再换新实例</b>，
 * 保证 scenario 间互不影响；采集为<b>同步落库、不另起后台线程</b>，故「清理线程」包袱从根上消失。
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

    /** scenario 探测结果缓存节流（反射有成本，限频至 ~5 次/秒）。无 context 兜底路径使用。 */
    private volatile long lastResolve = 0L;

    /** scenario 切换探测节流（X-3 / R-3：per-context）。并行下各 context 独立节流，互不干扰，
     *  不再用单一全局 {@code lastResolve} 造成跨 context 探测被互相限频。 */
    private final Map<BrowserContext, Long> lastResolveByContext = new ConcurrentHashMap<>();

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
        if (!enabled || call == null)  {return;} 
        if (context != null) {
            ensureApiCaptureStoreForContext(context);
            contextStores.computeIfAbsent(context, k -> new ApiCaptureStore()).record(call);
            return;
        }
        ensureApiCaptureStore();
        //  捕获局部引用，避免与场景切换 swap currentStore 之间的 TOCTOU 竞态
        ApiCaptureStore store = currentStore;
        if (store != null)  {store.record(call);} 
    }

    /** 兼容无 Context 入口（写入场景默认存储）。 */
    public void record(CapturedApiCall call) {
        record(call, null);
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
                if (currentStore != null)  {currentStore.clear();} 
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
                if (s != null)  {s.clear();} 
            } else  {if (currentStore != null) {
                currentStore.clear();
            }} 
        }
    }

    /**
     * 释放指定 BrowserContext 的采集存储（context 关闭 / 并发任务结束时调用，避免跨任务残留）。
     */
    public void clearContext(BrowserContext context) {
        if (context != null) {
            contextStores.remove(context);
            lastResolveByContext.remove(context);
        }
    }

    /** 释放全部 Context 采集存储（套件级全量复位）。 */
    public void clearAllContexts() {
        contextStores.clear();
        lastResolveByContext.clear();
    }

    /** 解析当前查询应命中的存储：优先当前线程绑定 Context 的独立存储，否则回退场景默认存储。 */
    private ApiCaptureStore resolveStore() {
        BrowserContext ctx = ApiCaptureLifecycle.currentContextOrNull();
        if (ctx != null) {
            ApiCaptureStore s = contextStores.get(ctx);
            if (s != null)  {return s;} 
        }
        return currentStore;
    }

    /** 懒检测 scenario 切换：节流反射解析，切换时先 clear 旧实例再换新，保证隔离且不累积。 */
    private void ensureApiCaptureStore() {
        long now = System.currentTimeMillis();
        if (now - lastResolve < 200)  {return;} 
        lastResolve = now;
        String key = resolveScenarioKey();
        if (key == null)  {return;} 
        if (!key.equals(currentApiCaptureScenarioKey)) {
            synchronized (swapLock) {
                if (!key.equals(currentApiCaptureScenarioKey)) {
                    if (currentStore != null)  {currentStore.clear();} 
                    currentStore = new ApiCaptureStore();
                    BrowserContext ctx = ApiCaptureLifecycle.currentContextOrNull();
                    if (ctx != null)  {contextStores.remove(ctx);} 
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
        Long last = lastResolveByContext.get(context);
        if (last != null && now - last < 200)  {return;} 
        lastResolveByContext.put(context, now);
        String key = resolveScenarioKey();
        if (key == null)  {return;} 
        if (!key.equals(currentApiCaptureScenarioKey)) {
            synchronized (swapLock) {
                if (!key.equals(currentApiCaptureScenarioKey)) {
                    if (currentStore != null)  {currentStore.clear();} 
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

    /**
     * 通过 Serenity 的 StepEventBus 反射获取当前 scenario 标识（复用
     * {@code FileStoreMonitorCallback} 已验证的反射路径，规避版本差异导致的编译问题）。
     *
     * @return 清洗后的 scenario 标识；取不到时返回 null（非 Serenity 环境）
     */
    private String resolveScenarioKey() {
        try {
            //  ⚠️ 必须用 getParallelEventBus()（2026-09-26 根因修复）：并行 Cucumber 下 Serenity 把真实
            //  BaseStepListener 注册在 per-feature 的 sticky bus 上，而 per-thread 的 getEventBus()
            //  在池线程（如 mock-intercept-*）上是另一个**空 bus** → 恒取不到 scenario 名。
            StepEventBus eventBus = StepEventBus.getParallelEventBus();
            if (eventBus == null) {
                return null;
            }
            //  就绪探测必须用 public 且**不抛异常**的 isBaseStepListenerRegistered()：直接调用
            //  getBaseStepListener() 在未注册时会打印 ERROR("CurrentListener is null") +
            //  Thread.dumpStack()，在池线程上造成大量无意义堆栈刷屏（实证：1.txt:53-69）。
            if (!eventBus.isBaseStepListenerRegistered()) {
                return null;
            }
            Method m = StepEventBus.class.getDeclaredMethod("getBaseStepListener");
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
        } catch (Exception | LinkageError e) {
            // 反射读取 scenario 名失败返回 null，由调用方降级处理。
            //  ⚠️ 必须连 LinkageError 一并捕获（2026-09-26 实测回归）：isBaseStepListenerRegistered()
            //  会经 StepEventBus.currentBaseStepListener() → Agency.currentAgentSpecificListener()
            //  触到 org.openqa.selenium.WebDriver，而 route 的**测试** classpath 不含 Selenium →
            //  抛 NoClassDefFoundError（Error 而非 Exception，不会被 catch(Exception) 接住）。
            //  本方法仅为目录命名尽力而为，故任何链接期缺失都应降级为 null，绝不影响采集主链路。
            return null;
        }
    }

    private static String toSafeDirName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
    }
}
