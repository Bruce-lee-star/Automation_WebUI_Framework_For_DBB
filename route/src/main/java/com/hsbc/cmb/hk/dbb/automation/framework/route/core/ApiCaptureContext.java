package com.hsbc.cmb.hk.dbb.automation.framework.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.common.context.LanguageState;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CaptureContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.hsbc.cmb.hk.dbb.automation.framework.route.util.RouteUtil;
import java.util.regex.Pattern;

/**
 * API 捕获上下文 — 统一管理所有被路由拦截的 API 调用（Monitor / Mock / Modify / Delay）。
 *
 * <p>不同于仅限 Monitor 的旧设计，本类面向所有 Route 类型的 API 调用：
 * <ul>
 *   <li><b>Monitor</b> — 监控真实 API 调用，记录请求/响应快照，支持断言</li>
 *   <li><b>Mock</b> — Mock 响应，记录被拦截的请求信息和返回的 Mock 数据</li>
 *   <li><b>Modify</b> — 修改响应，记录修改后的数据</li>
 *   <li><b>Delay</b> — 延迟放行，记录「被延迟过」的事实标记</li>
 * </ul>
 *
 * <p> Phase 5 分层：本类 = 上下文实例存储 + 查询 API + 断言/生命周期转发壳；
 * <b>响应存储域</b>（apiCallsPerUrl / apiCallsByUrl / recentCalls / delayMarkersByEndpoint /
 * totalResponseSize / 等待器 / 各类上限）已抽离至 {@link ResponseStore}，本类所有存储操作
 * 委托 {@link #responseStore}，公开 API 与行为<b>零变更</b>。冗余的 {@code responseStorage}
 * （body 备份）已随抽离删除，{@code totalResponseSize} 收口为 {@link ResponseStore} 内单一权威计数，
 * 根除「跨 Map 共享计数器 + 重复存储」导致的计数器漂移 / 提前误触发 OOM 守门。
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
public class ApiCaptureContext implements CaptureContext {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiCaptureContext.class);

    /**
     *  全局共享的 API 捕获上下文实例（不再使用 ThreadLocal）。
     *
     * <p>Handler（Playwright 事件线程）和 PlaywrightListener（主测试线程）
     * 通过此单一实例共享断言状态，保证跨线程可见性。
     */
    private static final ApiCaptureContext SHARED = new ApiCaptureContext(null);

    /**
     *  每个并发 BrowserContext 持有的独立捕获上下文实例的归属标识（弱 key 索引于 {@link #BY_CONTEXT}）。
     * <p>用于把「场景级 API 采集」汇聚精确路由到各自 Context 的存储，避免共享 Browser + 并发 Context 下
     * 多任务把调用快照写入同一全局 store 造成跨任务污染（G3）。{@code null} 表示共享/兜底实例。
     */
    private final BrowserContext ownerContext;

    //  修复 B-1：原 BY_CONTEXT 用 BrowserContext 强引用作 key，改为 WeakHashMap（弱 key），
    //   context 被 GC 后对应 entry 自动失效，避免泄漏。WeakHashMap 非并发安全，用 synchronizedMap 包装。
    private static final Map<BrowserContext, ApiCaptureContext> BY_CONTEXT =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 每个 Context 一个存储实例，携带归属标识供采集汇聚路由（G3 并发隔离）。 */
    private ApiCaptureContext(BrowserContext ownerContext) {
        this.ownerContext = ownerContext;
    }

    /** 获取 BrowserContext 隔离的捕获上下文；旧 API 继续使用共享上下文。 */
    public static ApiCaptureContext forContext(BrowserContext context) {
        if (context == null) return SHARED;
        return BY_CONTEXT.computeIfAbsent(context, ignored -> new ApiCaptureContext(context));
    }

    /** 本实例归属的 BrowserContext（共享兜底实例返回 null）。供采集汇聚路由，包级可见。 */
    BrowserContext getOwnerContext() {
        return ownerContext;
    }

    /** 移除并重置指定 BrowserContext 的捕获上下文。 */
    public static void removeContext(BrowserContext context) {
        if (context == null) return;
        ApiCaptureContext removed = BY_CONTEXT.remove(context);
        if (removed != null) removed.reset();
        //  G3：同步释放该 Context 的并发隔离采集存储，避免跨任务残留。
        ApiCaptureManager.getInstance().clearContext(context);
        if (ApiCaptureLifecycle.isCurrentContext(context)) {
            ApiCaptureLifecycle.unbindCurrentContext();
        }
    }

    /** 当前已注册的 Context 数量。 */
    public static int contextCount() {
        return BY_CONTEXT.size();
    }

    /**
     * 移除并重置<b>所有</b> BrowserContext 的捕获上下文（套件级全量复位专用）。
     */
    public static void removeAllContexts() {
        int size;
        //  G3：迭代删除须在 BY_CONTEXT 监视器内整体加锁（Collections.synchronizedMap 仅保证单方法原子，
        //  不保证迭代原子），否则并发 removeContext 可能触发 ConcurrentModificationException。
        synchronized (BY_CONTEXT) {
            size = BY_CONTEXT.size();
            for (Iterator<Map.Entry<BrowserContext, ApiCaptureContext>> it = BY_CONTEXT.entrySet().iterator();
                 it.hasNext(); ) {
                Map.Entry<BrowserContext, ApiCaptureContext> entry = it.next();
                try {
                    entry.getValue().reset();
                } catch (Exception ignored) {
                    // 单个 context 重置失败不影响其余条目回收
                }
                it.remove();
            }
        }
        ApiCaptureLifecycle.unbindCurrentContext();
        SHARED.reset();
        //  G3：一并释放全部 Context 级采集存储。
        ApiCaptureManager.getInstance().clearAllContexts();
        VerboseLogging.logDebugIfVerbose(LOGGER,
                "[ApiCaptureContext] removeAllContexts() — released {} per-context instance(s)", size);
    }

    /**
     * 获取当前线程绑定的 API 捕获上下文；未绑定或绑定已失效时回退全局共享实例。
     */
    public static ApiCaptureContext getCurrent() {
        BrowserContext context = ApiCaptureLifecycle.currentContextOrNull();
        if (context == null) return SHARED;
        ApiCaptureContext existing = BY_CONTEXT.get(context);
        if (existing != null) return existing;
        ApiCaptureLifecycle.unbindCurrentContext();
        return SHARED;
    }

    /**
     * 返回全局共享上下文实例（ROUTE-P0-1 兜底解析用）。
     *
     * <p>route 事件线程在页面/上下文失效、{@code RouteUtil.captureContext(route)} 无法解析
     * per-context 实例时，经 {@code getCurrent()} 回退写入的落点。步骤结束的失败聚合检查借此
     * 兜底读取，避免断言失败标志因跨线程上下文错配而漏检。
     */
    public static ApiCaptureContext getShared() {
        return SHARED;
    }

    /** 将当前测试线程绑定到指定 BrowserContext，供旧兼容 API 正确隔离。 */
    public static void bindCurrentContext(BrowserContext context) {
        ApiCaptureLifecycle.bindCurrentContext(context);
    }

    /** 判断当前线程是否正绑定在指定 BrowserContext 上。 */
    public static boolean isCurrentContext(BrowserContext context) {
        return ApiCaptureLifecycle.isCurrentContext(context);
    }

    /** 清除当前测试线程的 Context 绑定，防止线程池线程污染后续测试。 */
    public static void unbindCurrentContext() {
        ApiCaptureLifecycle.unbindCurrentContext();
    }

    /**
     * 重置 API 捕获上下文（测试开始/结束时统一调用）。
     */
    public static void resetCurrent() {
        SHARED.reset();
        //  feature 模式下 BrowserContext 被多个 scenario 复用，getCurrent() 返回 BY_CONTEXT 中的
        //   per-context 实例；仅重置 SHARED 会把上一场景状态带入下一场景（跨场景串扰）。
        BrowserContext bound = ApiCaptureLifecycle.currentContextOrNull();
        if (bound != null) {
            ApiCaptureContext perContext = BY_CONTEXT.get(bound);
            if (perContext != null) {
                perContext.reset();
            }
        }
        //  清理时机对齐：NLSUtils 全局值若不在 context 生命周期边界清理会跨用例串扰。
        LanguageState.reset();
    }

    // ═══════════════════════════════════════════════════════════
    //  R4: 步骤级时间窗口 — 隔离同一 Scenario 内跨 Step 的 API 调用串扰
    // ═══════════════════════════════════════════════════════════

    /** 当前步骤起始时间戳（毫秒），0 表示未限定（匹配全部） */
    private volatile long stepStartTimestamp = 0L;

    /**
     * 标记一个新步骤的起始时间点（由 PlaywrightListener.stepStarted 调用）。
     */
    public void markStepStart() {
        this.stepStartTimestamp = System.currentTimeMillis();
        VerboseLogging.logDebugIfVerbose(LOGGER,
                "[ApiCaptureContext] markStepStart → stepStartTimestamp={}", stepStartTimestamp);
    }

    /** 当前步骤起始时间戳。 */
    public long getStepStartTimestamp() {
        return stepStartTimestamp;
    }

    /**
     * 判断调用是否在本步骤窗口内（{@code timestamp >= stepStartTimestamp}）。
     * <p>{@code stepStartTimestamp == 0} 时视为不限定，返回 true。
     */
    private boolean isWithinStepWindow(CapturedApiCall call) {
        long ts = stepStartTimestamp;
        return ts == 0L || call.timestamp() >= ts;
    }

    // ── 请求活动计数（与 completionLock 协作实现 awaitCompletion 门控）──
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    /**
     *  单调递增「曾观察到的请求数」：每次有请求进入拦截（activeRequests +1）即 +1，永不减。
     * 用于 {@link #awaitCompletion(long)} 的首活动门控，吸收「触发请求→Route 拦截」之间的时序间隙。
     */
    private final AtomicLong observedRequests = new AtomicLong(0);
    private final AtomicBoolean hasAssertionFailures = new AtomicBoolean(false);

    /** 等待锁：decrement → 0 时通知 awaitCompletion 的调用方 */
    private final Object completionLock = new Object();

    /** 当前测试线程（仅用于调试/诊断，防止跨线程串扰）。 */
    private volatile Thread testThread;

    //  Phase 5：响应存储域已抽离至 ResponseStore；本类仅保留实例存储壳并委托转发。
    private final ResponseStore responseStore = new ResponseStore();

    /** 断言失败详情列表（线程安全） */
    private final List<AssertionFailureDetail> failureDetails =
            Collections.synchronizedList(new java.util.LinkedList<>());

    public void incrementActiveRequests() {
        observedRequests.incrementAndGet();
        int count = activeRequests.incrementAndGet();
        if (count == 1) {
            synchronized (completionLock) {
                completionLock.notifyAll();
            }
        }
        VerboseLogging.logTraceIfVerbose(LOGGER,
                "[ApiCaptureContext] incrementActiveRequests -> {}", count);
    }

    /**
     * 递减活动请求计数。当计数归零时通知所有等待 {@link #awaitCompletion} 的线程。
     */
    public void decrementActiveRequests() {
        int remaining = activeRequests.updateAndGet(current -> Math.max(0, current - 1));
        VerboseLogging.logTraceIfVerbose(LOGGER,
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
     * <pre>{@code
     * if (!ApiCaptureContext.getCurrent().waitForActiveRequest(5000)) {
     *     throw new AssertionError("Request was not intercepted within 5s");
     * }
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
     * 阻塞等待所有进行中的异步请求完成（使用 {@code synchronized + wait/notifyAll} 替代忙等待）。
     *
     * @param timeoutMs 超时毫秒数
     * @return true=所有请求已完成，false=超时（仍可能有请求未完成）
     * @throws InterruptedException 如果等待被中断
     */
    public boolean awaitCompletion(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (completionLock) {
            if (activeRequests.get() == 0) {
                return true;
            }
            while (activeRequests.get() > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                inWaitState = true;
                try {
                    completionLock.wait(remaining);
                } finally {
                    inWaitState = false;
                }
            }
        }
        return true;
    }

    /**
     *  可观测性标志：waiter 线程已进入 completionLock.wait() 内部时为 true。
     */
    private volatile boolean inWaitState = false;

    /** 仅供测试观测：waiter 是否已进入 wait 状态 */
    boolean isInWaitState() {
        return inWaitState;
    }

    /**
     *  断言失败快速信号：当任一断言失败时，标记 completionLock 以唤醒 awaitCompletion 的等待线程，
     * 避免其在测试已失败时仍死等超时。
     */
    public void signalFailFast() {
        synchronized (completionLock) {
            completionLock.notifyAll();
        }
    }

    /** 绑定当前测试线程（防止跨线程串扰，仅用于调试/诊断）。 */
    public void setTestThread(Thread thread) {
        this.testThread = thread;
    }

    /** 清除测试线程绑定。 */
    public void clearTestThread() {
        this.testThread = null;
    }

    /** 标记断言失败（兼容旧调用） */
    public void setAssertionFailure() {
        hasAssertionFailures.set(true);
    }

    /**
     * 记录断言失败详细信息。
     */
    public void recordAssertionFailure(String url, String assertionType,
                                       String expectedValue, String actualValue, String failMessage) {
        hasAssertionFailures.set(true);
        failureDetails.add(new AssertionFailureDetail(
                url, assertionType, expectedValue, actualValue, failMessage));
        VerboseLogging.logDebugIfVerbose(LOGGER,
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
     */
    public String buildFailureDetails() {
        List<AssertionFailureDetail> details = getFailureDetails();
        if (details.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (AssertionFailureDetail d : details) {
            sb.append(d.toString()).append("\n");
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * 重置 API 捕获上下文（测试开始/结束时统一调用，与线程解绑不再使用 ThreadLocal.remove）。
     * <p> 存储域重置委托 {@link ResponseStore#reset()}（持其内 apiCallLock 与写入严格串行）。
     */
    public void reset() {
        VerboseLogging.logDebugIfVerbose(LOGGER,
                "[ApiCaptureContext] reset() — clearing activeRequests={}, failures={}, responses={}",
                activeRequests.get(), failureDetails.size(), responseStore.getTotalResponseCount());
        activeRequests.set(0);
        observedRequests.set(0);
        hasAssertionFailures.set(false);
        failureDetails.clear();
        responseStore.reset();
        //  R4: 测试级重置时清除步骤窗口标记
        stepStartTimestamp = 0L;
        testThread = null;
        synchronized (completionLock) {
            completionLock.notifyAll();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  响应存储域 — 全部委托 ResponseStore（零行为变更，公开 API 不变）
    // ═══════════════════════════════════════════════════════════

    /** 存储一条 DELAY 维度标记（由 RouteEngine 的延迟分支调用）。 */
    public void storeDelayMarker(CapturedApiCall call) {
        responseStore.storeDelayMarker(call);
        //  API 采集汇聚：DELAY 标记同步进入常驻采集存储（与各 Handler 零竞争）；
        //  携带 ownerContext 使并发任务各自隔离到本 Context 的采集存储（G3）。
        ApiCaptureManager.getInstance().record(call, ownerContext);
    }

    /** 存储一次完整的 API 调用快照（Monitor / Mock / Modify 均可使用）。 */
    public void storeApiCall(CapturedApiCall call) {
        responseStore.storeApiCall(call);
        //  API 采集汇聚：统一入口，自动携带 delay/mock/modify 的 handleType 进入常驻采集存储；
        //  携带 ownerContext 使并发任务各自隔离到本 Context 的采集存储（G3）。
        ApiCaptureManager.getInstance().record(call, ownerContext);
    }

    /**
     * 更新已存储的 API 调用快照的响应体（惰性 body 读取完成后调用）。
     *
     * @return true=更新成功，false=未找到匹配的调用或 body 已存在
     */
    public boolean updateResponseBody(String requestUrl, String body) {
        return responseStore.updateResponseBody(requestUrl, body);
    }

    /**
     * 获取指定端点的所有 API 调用快照（按调用顺序）。
     *
     * @return 不可变副本列表，未找到返回空列表
     */
    public List<CapturedApiCall> getApiCalls(String endpoint) {
        return responseStore.getApiCalls(endpoint);
    }

    /**
     * 获取指定端点的最近一次 API 调用快照。
     *
     * @return 捕获的快照，未找到返回 null
     */
    public CapturedApiCall getLastApiCall(String endpoint) {
        return responseStore.getLastApiCall(endpoint);
    }

    /**
     * 获取当前步骤起始之后的、指定端点的所有 API 调用快照（按调用顺序）。
     */
    public List<CapturedApiCall> getApiCallsSinceStepStart(String endpoint) {
        List<CapturedApiCall> all = getApiCalls(endpoint);
        if (all.isEmpty() || stepStartTimestamp == 0L) return all;
        List<CapturedApiCall> filtered = new ArrayList<>();
        for (CapturedApiCall c : all) {
            if (c.timestamp() >= stepStartTimestamp) filtered.add(c);
        }
        return filtered;
    }

    /**
     * 获取当前步骤起始之后的、指定端点的最近一次 API 调用快照。
     */
    public CapturedApiCall getLastApiCallSinceStepStart(String endpoint) {
        List<CapturedApiCall> calls = getApiCallsSinceStepStart(endpoint);
        return calls.isEmpty() ? null : calls.get(calls.size() - 1);
    }

    /** 获取所有端点的 API 调用快照（每个端点仅返回最近一次）。 */
    public Map<String, CapturedApiCall> getAllLastApiCalls() {
        return responseStore.getAllLastApiCalls();
    }

    /** 获取所有端点的全部 API 调用快照。 */
    public Map<String, List<CapturedApiCall>> getAllApiCalls() {
        return responseStore.getAllApiCalls();
    }

    /**
     *  按路由能力类型获取全部 API 调用快照（按时间升序）。
     */
    public List<CapturedApiCall> getAllByType(RouteHandleType type) {
        return responseStore.getAllByType(type);
    }

    /**
     *  按「能力类型 + endpoint」获取指定端点的全部快照。
     */
    public List<CapturedApiCall> getApiCallsByType(String endpoint, RouteHandleType type) {
        return responseStore.getApiCallsByType(endpoint, type);
    }

    /**  按「能力类型 + endpoint」获取最近一次快照；无记录返回 null。 */
    public CapturedApiCall getLastApiCallByType(String endpoint, RouteHandleType type) {
        return responseStore.getLastApiCallByType(endpoint, type);
    }

    /**  按能力类型分组获取全部快照。 */
    public Map<RouteHandleType, List<CapturedApiCall>> getAllGroupedByType() {
        return responseStore.getAllGroupedByType();
    }

    /** 按实际请求 URL 精确获取 API 调用 — O(1) 毫秒级检索。 */
    public CapturedApiCall getCallByUrl(String requestUrl) {
        return responseStore.getCallByUrl(requestUrl);
    }

    /** 按请求 URL 获取该 URL 的所有 API 调用历史。 */
    public List<CapturedApiCall> getCallsByUrl(String requestUrl) {
        return responseStore.getCallsByUrl(requestUrl);
    }

    /**
     * 条件等待 — 阻塞直到匹配 predicate 的 API 调用出现（毫秒级响应）。
     *
     * @param predicate 匹配条件（在 Playwright 事件线程的存储回调中检查）
     * @param timeoutMs 超时毫秒数
     * @return 匹配的 API 调用快照，超时返回 null
     */
    public CapturedApiCall waitForApi(Predicate<CapturedApiCall> predicate, long timeoutMs) {
        return responseStore.waitForApi(predicate, timeoutMs);
    }

    /**  注册一次性投递式等待器：谓词将在后续每次入库时被直接评估。 */
    public CompletableFuture<CapturedApiCall> registerApiCallWaiter(Predicate<CapturedApiCall> predicate) {
        return responseStore.registerApiCallWaiter(predicate);
    }

    /**  注销投递式等待器（幂等）。 */
    public void unregisterApiCallWaiter(CompletableFuture<CapturedApiCall> waiter) {
        responseStore.unregisterApiCallWaiter(waiter);
    }

    /**
     *  伪 LRU 淘汰辅助：从 ConcurrentHashMap 中移除约 25% 的条目。
     * <p>委托 {@link ResponseStore#evictOldestQuarter} 的统一实现；保留为 static 以兼容
     * {@code RouteCoreEvictionTest} 对框架内部淘汰逻辑的直接验证。
     */
    private static void evictOldestQuarter(ConcurrentHashMap<?, ?> map) {
        SHARED.responseStore.evictOldestQuarter(map);
    }

    /**
     * 获取已捕获的响应总数（所有 URL 的所有调用次数之和）。
     */
    public int getTotalResponseCount() {
        return responseStore.getTotalResponseCount();
    }

    // ═══════════════════════════════════════════════════════════
    // Response body 向后兼容查询（统一从 CapturedApiCall 读取，responseStorage 已随抽离删除）
    // ═══════════════════════════════════════════════════════════

    /** 获取已存储的 API 响应体（返回最近一次调用）。 */
    public String getStoredResponse(String endpoint) {
        CapturedApiCall call = getLastApiCall(endpoint);
        return call != null ? call.responseBody() : null;
    }

    /** 获取指定端点所有响应（按调用顺序保留，分页场景适用）。 */
    public List<String> getAllResponsesForUrl(String endpoint) {
        List<CapturedApiCall> calls = getApiCalls(endpoint);
        List<String> result = new ArrayList<>();
        for (CapturedApiCall c : calls) {
            if (c.responseBody() != null) result.add(c.responseBody());
        }
        return result;
    }

    /** 获取所有已存储的响应（仅返回每个 URL 最近一次调用）。 */
    public Map<String, String> getAllStoredResponses() {
        Map<String, String> result = new java.util.HashMap<>();
        for (Map.Entry<String, List<CapturedApiCall>> e : getAllApiCalls().entrySet()) {
            List<CapturedApiCall> list = e.getValue();
            if (list != null && !list.isEmpty()) {
                CapturedApiCall last = list.get(list.size() - 1);
                if (last.responseBody() != null) {
                    result.put(e.getKey(), last.responseBody());
                }
            }
        }
        return result;
    }

    /** 获取所有已存储的响应（每个 URL 的全部调用历史）。 */
    public Map<String, List<String>> getAllStoredResponseLists() {
        Map<String, List<String>> result = new java.util.HashMap<>();
        for (Map.Entry<String, List<CapturedApiCall>> e : getAllApiCalls().entrySet()) {
            List<String> bodies = new ArrayList<>();
            for (CapturedApiCall c : e.getValue()) {
                if (c.responseBody() != null) bodies.add(c.responseBody());
            }
            if (!bodies.isEmpty()) {
                result.put(e.getKey(), bodies);
            }
        }
        return result;
    }

    /** 获取指定端点的调用次数。 */
    public int getResponseCountForUrl(String endpoint) {
        return getApiCalls(endpoint).size();
    }

    /** 清除所有已存储的响应（含主快照存储）。 */
    public void clearStoredResponses() {
        responseStore.reset();
    }

    // ═══════════════════════════════════════════════════════════
    // Page 生命周期门面 / 当前上下文绑定 —— 已抽离至 ApiCaptureLifecycle
    // （本类仅保留同名公开转发壳，业务逻辑见 ApiCaptureLifecycle，零行为变更）
    // ═══════════════════════════════════════════════════════════

    /** 启动 BrowserContext 级采集；已有 Page 需随后 attach。 */
    public static void start(BrowserContext context) {
        ApiCaptureLifecycle.start(context);
    }

    /** 将 Page 加入所属 BrowserContext 的采集会话。 */
    public static void attach(Page page) {
        ApiCaptureLifecycle.attach(page);
    }

    /** 从 Context 会话中移除 Page，不影响其它 Page。 */
    public static void detach(Page page) {
        ApiCaptureLifecycle.detach(page);
    }

    /** 停止 Context 下全部 Page 采集。 */
    public static void stop(BrowserContext context) {
        ApiCaptureLifecycle.stop(context);
    }

    /** 活动 Context 采集会话数，便于排查泄漏。 */
    public static int activeContextCount() {
        return ApiCaptureLifecycle.activeContextCount();
    }

    /**
     * 快速启动 — 一行代码开启全量 API 采集。
     *
     * @param page Playwright Page 实例
     */
    public static void start(Page page) {
        ApiCaptureLifecycle.start(page);
    }

    /**
     * 链式启动入口（保留扩展空间）。
     *
     * <pre>{@code
     * ApiCaptureContext.on(page).start();
     * }</pre>
     */
    public static ApiCaptureStart on(Page page) {
        return ApiCaptureLifecycle.on(page);
    }

    /** 停止采集并释放资源。 */
    public static void stop() {
        ApiCaptureLifecycle.stop();
    }

    /** 停止并移除指定 Page 的采集会话，不影响同一 Context 的其它 Page。 */
    public static void stop(Page page) {
        ApiCaptureLifecycle.stop(page);
    }

    /** 当前活动 Page 采集会话数。 */
    public static int activePageCount() {
        return ApiCaptureLifecycle.activePageCount();
    }

    // ═══════════════════════════════════════════════════════════
    // 断言
    // ═══════════════════════════════════════════════════════════

    /**
     * 创建断言器 — 按 URL 模式匹配已采集的 API 调用。
     *
     * <pre>{@code
     * ApiCaptureContext.assertThat("/api/user/list").statusIs(200);
     * ApiCaptureContext.assertThat("/api/user/detail").jsonPath("$.code", 0);
     * }</pre>
     *
     * @param urlPattern URL 模式（支持 Ant glob 通配符）
     * @return 断言器
     */
    public static ApiAssertion assertThat(String urlPattern) {
        return new ApiAssertion(urlPattern);
    }

    /** Context 隔离版本：等待指定 BrowserContext 的 API 调用。 */
    public static CapturedApiCall waitForApi(BrowserContext context,
                                             Predicate<CapturedApiCall> predicate,
                                             long timeoutMs) {
        return ApiCaptureContext.forContext(context).waitForApi(predicate, timeoutMs);
    }

    // ═══════════════════════════════════════════════════════════
    // 查询
    // ═══════════════════════════════════════════════════════════

    /**
     * 获取所有已采集的 API 调用。
     *
     * <p> P0 防御：当存在多个活动 Page 时，全局上下文无法区分归属，立即失败（fail-fast）
     * 而非静默返回错误数据，强制调用方改用 {@link #getAll(BrowserContext)}。
     *
     * @return 所有 API 调用（按 endpoint 分组）
     */
    public static Map<String, List<CapturedApiCall>> getAll() {
        synchronized (ApiCaptureContext.class) {
            if (activePageCount() > 1) {
                throw new IllegalStateException(
                        "ApiCapture.getAll() detected multiple active Pages (" + activePageCount()
                                + "). Use getAll(Page) / getAll(BrowserContext) to avoid cross-test data contamination.");
            }
            return ApiCaptureContext.getCurrent().getAllApiCalls();
        }
    }

    /** Context 隔离版本：获取指定 BrowserContext 的全部 API 调用。 */
    public static Map<String, List<CapturedApiCall>> getAll(BrowserContext context) {
        return ApiCaptureContext.forContext(context).getAllApiCalls();
    }

    /**
     * 获取指定 endpoint 的最近一次 API 调用。
     *
     * @param endpoint 请求端点（路径+查询，不含 host）
     * @return 最近一次调用，未找到返回 null
     */
    public static CapturedApiCall getLast(String endpoint) {
        return ApiCaptureContext.getCurrent().getLastApiCall(endpoint);
    }

    /** Context 隔离版本：获取指定 BrowserContext 的最近一次 API 调用。 */
    public static CapturedApiCall getLast(BrowserContext context, String endpoint) {
        return ApiCaptureContext.forContext(context).getLastApiCall(endpoint);
    }

    // ═══════════════════════════════════════════════════════════
    // 按能力类型查询（MOCK / MODIFY / DELAY / MONITOR）
    // ═══════════════════════════════════════════════════════════

    /** Context 隔离版本：按能力类型获取指定 BrowserContext 的全部快照。 */
    public static List<CapturedApiCall> getAllByType(BrowserContext context, RouteHandleType type) {
        return ApiCaptureContext.forContext(context).getAllByType(type);
    }

    /**  按「能力类型 + endpoint」获取最近一次快照；无记录返回 null。 */
    public static CapturedApiCall getLastByType(String endpoint, RouteHandleType type) {
        return ApiCaptureContext.getCurrent().getLastApiCallByType(endpoint, type);
    }

    // ═══════════════════════════════════════════════════════════
    //  场景级 API 采集（Captured）
    //    框架启动即常驻开启；与各 Handler 零干扰、零资源竞争；独立于测试断言存储，
    //    可在 scenario 内独立断言（含 delay / mock / modify 全部信息）。
    // ═══════════════════════════════════════════════════════════

    /** 开启 / 关闭 API 采集（默认开启）。 */
    public static void setApiCaptureEnabled(boolean on) {
        ApiCaptureManager.setApiCaptureEnabled(on);
    }

    /** 场景起步：清空并隔离 API 采集存储（scenario 间互不影响）。 */
    public static void beginApiCapture() {
        ApiCaptureManager.getInstance().beginApiCapture();
    }

    /** 场景结束：即时清理 API 采集存储（释放内存 / 资源）。 */
    public static void endApiCapture() {
        ApiCaptureManager.getInstance().endApiCapture();
    }

    /** 获取采集到的指定端点全部 API 调用快照（按调用顺序）。 */
    public static List<CapturedApiCall> getCapturedApiCalls(String endpoint) {
        return ApiCaptureManager.getInstance().getApiCalls(endpoint);
    }

    /** 获取采集到的指定端点最近一次 API 调用快照。 */
    public static CapturedApiCall getLastCapturedApiCall(String endpoint) {
        return ApiCaptureManager.getInstance().getLastApiCall(endpoint);
    }

    /** 按完整请求 URL 精确获取采集到的最近一次 API 调用。 */
    public static CapturedApiCall getCapturedApiCallByUrl(String requestUrl) {
        return ApiCaptureManager.getInstance().getStore().getCallByUrl(requestUrl);
    }
}
