package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
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
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
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
    // ⭐ 修复 B-1：原 BY_CONTEXT 用 BrowserContext 强引用作 key，若 removeContext/removeAllContexts
    //   遗漏调用，已关闭的 BrowserContext 会被钉在堆上（整个 JVM 生命周期）造成泄漏，且与
    //   CURRENT_CONTEXT 的 WeakReference 设计不一致。改为 WeakHashMap（弱 key），context 被 GC 后
    //   对应 entry 自动失效，避免泄漏。WeakHashMap 非并发安全，用 synchronizedMap 包装。
    private static final Map<BrowserContext, ApiCaptureContext> BY_CONTEXT =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 当前测试线程绑定的 BrowserContext。
     *
     * <p>⭐ 用 {@link WeakReference} 包装：Cucumber/JUnit 的执行线程会被线程池复用，
     * 而 {@code RouteDsl.on(...)} 只负责 bind、并不保证在用例结束时 unbind。
     * 若直接强引用 BrowserContext，线程的 ThreadLocalMap 会把已关闭的 BrowserContext
     * 钉在堆上直到该线程销毁（并行 N 线程 × 整个 JVM 生命周期），且下一个用例复用该线程时
     * 会读到上一个用例的死 context —— 既泄漏又串扰。
     */
    private static final ThreadLocal<WeakReference<BrowserContext>> CURRENT_CONTEXT = new ThreadLocal<>();

    /** 获取 BrowserContext 隔离的捕获上下文；旧 API 继续使用共享上下文。 */
    public static ApiCaptureContext forContext(BrowserContext context) {
        if (context == null) return SHARED;
        return BY_CONTEXT.computeIfAbsent(context, ignored -> new ApiCaptureContext());
    }

    /** 移除并重置指定 BrowserContext 的捕获上下文。 */
    public static void removeContext(BrowserContext context) {
        if (context == null) return;
        ApiCaptureContext removed = BY_CONTEXT.remove(context);
        if (removed != null) removed.reset();
        // ⭐ 源头解绑：调用方已宣告该 context 生命周期结束，若当前线程仍绑在它上面，
        //    立即解绑，避免后续 getCurrent() 再次把它 computeIfAbsent 复活。
        if (isCurrentContext(context)) {
            CURRENT_CONTEXT.remove();
        }
    }

    /** 当前已注册的 Context 数量。 */
    public static int contextCount() {
        return BY_CONTEXT.size();
    }

    /**
     * 移除并重置<b>所有</b> BrowserContext 的捕获上下文（套件级全量复位专用）。
     *
     * <p>⭐ 补齐泄漏出口：{@code BY_CONTEXT} 以 BrowserContext 为强引用 key，
     * 若用例只走 {@code resetAll()} 而没有逐个 {@code removeContext}，
     * 已关闭的 BrowserContext 及其全部 CapturedApiCall 快照会常驻堆内存。
     */
    public static void removeAllContexts() {
        int size = BY_CONTEXT.size();
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
        CURRENT_CONTEXT.remove();
        SHARED.reset();
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[ApiCaptureContext] removeAllContexts() — released {} per-context instance(s)", size);
    }

    /**
     * 获取当前线程绑定的 API 捕获上下文；未绑定或绑定已失效时回退全局共享实例。
     *
     * <p>⭐ 失效绑定自愈：这里刻意<b>不</b>用 {@link #forContext} 的 computeIfAbsent。
     * 一旦 {@code removeContext} 已回收该 context（用例收尾），或 BrowserContext 已被 GC，
     * 复活条目等于把上一个用例的状态带进下一个用例。此时就地 remove 掉 ThreadLocal
     * 并回退 SHARED，让线程自我修复。
     */
    public static ApiCaptureContext getCurrent() {
        BrowserContext context = currentContextOrNull();
        if (context == null) return SHARED;
        ApiCaptureContext existing = BY_CONTEXT.get(context);
        if (existing != null) return existing;
        CURRENT_CONTEXT.remove();
        return SHARED;
    }

    /** 将当前测试线程绑定到指定 BrowserContext，供旧兼容 API 正确隔离。 */
    public static void bindCurrentContext(BrowserContext context) {
        if (context == null) CURRENT_CONTEXT.remove();
        else {
            CURRENT_CONTEXT.set(new WeakReference<>(context));
            forContext(context);
        }
    }

    /** 判断当前线程是否正绑定在指定 BrowserContext 上。 */
    public static boolean isCurrentContext(BrowserContext context) {
        return context != null && currentContextOrNull() == context;
    }

    /** 清除当前测试线程的 Context 绑定，防止线程池线程污染后续测试。 */
    public static void unbindCurrentContext() {
        CURRENT_CONTEXT.remove();
    }

    /**
     * 解引用当前线程绑定的 BrowserContext；引用已被 GC 清空时顺手移除 ThreadLocal 条目。
     */
    private static BrowserContext currentContextOrNull() {
        WeakReference<BrowserContext> ref = CURRENT_CONTEXT.get();
        if (ref == null) return null;
        BrowserContext context = ref.get();
        if (context == null) {
            CURRENT_CONTEXT.remove();
        }
        return context;
    }

    /**
     * 重置 API 捕获上下文（测试开始/结束时统一调用，与线程解绑不再使用 ThreadLocal.remove）。
     * <p>注意：此方法会重置全局共享实例的断言和响应存储状态；
     * 若当前线程绑定了 BrowserContext，还会一并重置该 context 对应的 per-context 实例。
     */
    public static void resetCurrent() {
        SHARED.reset();
        // ⭐ 同时重置当前线程绑定的 per-context 实例：
        //    feature 模式下 BrowserContext 会被多个 scenario 复用，而 getCurrent() 返回的是
        //    BY_CONTEXT 中的 per-context 实例。若只重置 SHARED，上一场景累积的 captured calls
        //    与断言失败状态会随复用的 context 带入下一场景（跨场景串扰）。
        //    这里只清空实例状态、不移除条目，保持 forContext 的复用语义。
        BrowserContext bound = currentContextOrNull();
        if (bound != null) {
            ApiCaptureContext perContext = BY_CONTEXT.get(bound);
            if (perContext != null) {
                perContext.reset();
            }
        }
        // ⭐ 清理时机对齐：NLSUtils 已全局化（跨线程可见，支持 Monitor 回调影响主线程），
        // 但全局值若不在 context 生命周期边界清理，会跨用例串扰。
        // 故在每次 resetCurrent（用例 before/after、scenario 边界）时一并清理语言 ThreadLocal，
        // 确保「用例 A 的回调设的语言」不会泄漏到「用例 B 的 getLanguage」。
        com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils.reset();
    }

    // ═══════════════════════════════════════════════════════════════
    // ⭐ R4: 步骤级时间窗口 — 隔离同一 Scenario 内跨 Step 的 API 调用串扰
    // ═══════════════════════════════════════════════════════════════
    // 在 stepStarted 时记录起始时间戳，后续的 waitForApi/getLastApiCall 等
    // 可限定只匹配该时间戳之后的调用，避免命中上一步遗留的旧调用。

    /** 当前步骤起始时间戳（毫秒），0 表示未限定（匹配全部） */
    private volatile long stepStartTimestamp = 0L;

    /**
     * 标记一个新步骤的起始时间点（由 PlaywrightListener.stepStarted 调用）。
     * <p>调用后，所有 {@code *SinceStepStart} 系列查询仅匹配时间戳
     * {@code >= stepStartTimestamp} 的调用，消除跨 step 串扰。
     */
    public void markStepStart() {
        this.stepStartTimestamp = System.currentTimeMillis();
        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[ApiCaptureContext] markStepStart → stepStartTimestamp={}", stepStartTimestamp);
    }

    /**
     * 当前步骤起始时间戳。
     */
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

    private final AtomicInteger activeRequests = new AtomicInteger(0);
    /**
     * ⭐ 单调递增「曾观察到的请求数」：每次有请求进入拦截（activeRequests +1）即 +1，永不减。
     * 用于 {@link #awaitCompletion(long)} 的首活动门控，吸收「触发请求→Route 拦截」
     * 之间的时序间隙——若用 activeRequests==0 直接判定「无活动」会因并发 fire-and-forget fetch
     * 在 evaluate 返回后、route 拦截前的瞬时归零而提前返回（f31 丢并发）。单调性保证一旦有活动即永久可见，
     * 不会因请求在两次检查间瞬时完成而漏唤醒导致空等超时。
     */
    private final AtomicLong observedRequests = new AtomicLong(0);
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

    /**
     * ⭐ #6 投递式等待器注册表（点对点投递，替代"广播 notifyAll + 调用方重扫"）。
     *
     * <p>storeApiCall 在入库时直接评估已注册的谓词，命中即完成对应 future，
     * 等待方零重扫、零广播唤醒；未命中则继续等待直到超时/重置。
     * 所有操作均在持有 {@code apiCallLock} 时执行，与存储写入严格串行。
     */
    private final Map<CompletableFuture<CapturedApiCall>, Predicate<CapturedApiCall>> apiCallWaiters =
            new ConcurrentHashMap<>();

    // ⭐ P3: 最近调用平铺列表 — 用于 scanForMatching 快速扫描。
    //   改用有界 ArrayDeque + 单锁，淘汰最老元素为 O(1)；
    //   避免 CopyOnWriteArrayList 每次 add 都复制整个底层数组（500 容量时 O(500) 拷贝 + GC 压力）。
    private final java.util.ArrayDeque<CapturedApiCall> recentCalls = new java.util.ArrayDeque<>();
    private final java.util.concurrent.locks.ReentrantLock recentCallsLock =
            new java.util.concurrent.locks.ReentrantLock();
    private static final int MAX_RECENT_CALLS = 500;
    private static final int MAX_CALLS_PER_ENDPOINT = 100;
    private static final int MAX_CALLS_PER_REQUEST_URL = 100;

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
     * <p>断言失败由 {@link PlaywrightListener# ()}
     * 在每个步骤结束时兜底检查并抛出 {@code AssertionError}。
     */
    public void signalFailFast() {
        hasAssertionFailures.set(true);
        // 失败状态与请求账本分离：计数只能由取得请求所有权的路径递减，
        // 不能在此重置，否则原 finally 再递减会得到负数并破坏后续等待语义。
        synchronized (completionLock) {
            completionLock.notifyAll();
        }
    }

    /** Response 存储上限（防止内存泄漏），超过后记录 WARN 日志但继续存储 */
    private static final int MAX_RESPONSE_STORAGE = 1000;

    /**
     * Response 总字节数上限，防止大响应（如文件下载）导致 OOM。
     * <p>通过 {@link FrameworkConfig#API_CAPTURE_MAX_RESPONSE_SIZE_MB} 配置，默认 50MB。
     * <p>示例：{@code -Dapi.capture.max.response.size.mb=100}
     */
    private static final long MAX_RESPONSE_TOTAL_SIZE =
            FrameworkConfig.API_CAPTURE_MAX_RESPONSE_SIZE_MB.getLongValue() * 1024 * 1024;

    /** 当前已存储响应总字节数（原子操作，线程安全） */
    private final AtomicLong totalResponseSize = new AtomicLong(0L);

    /** 断言失败详情列表（线程安全） */
    private final List<AssertionFailureDetail> failureDetails =
            java.util.Collections.synchronizedList(new java.util.LinkedList<>());

    /**
     * CapturedApiCall 存储 — 完整的请求/响应快照（推荐）。
     *
     * <p>Key = 请求端点（路径+查询，不含 host），Value = 该端点被调用的所有快照（按顺序）。
     */
    private final Map<String, List<CapturedApiCall>> apiCallsPerUrl = new ConcurrentHashMap<>();

    /**
     * ⭐ DELAY 维度标记存储 —— <b>独立于</b> {@link #apiCallsPerUrl}。
     *
     * <p>DELAY 与其它三种能力<b>性质不同</b>：MOCK / MODIFY / MONITOR 各自都持有一次完整调用的
     * 请求/响应内容，而 DELAY 只是「该请求被延迟过」这一<b>事实标记</b>
     * （在请求放行之前落库，不含响应，{@code statusCode=0}）。
     *
     * <p>若把它混入主快照存储，按 endpoint 的通用查询
     * （{@code getLastApiCall} / {@code getApiCalls} / {@code waitForApi}）会先命中这条无响应的
     * 占位记录，调用方被迫在每一处都写「跳过 DELAY」的过滤逻辑 —— 这是典型的语义泄漏。
     *
     * <p>因此：主快照存储<b>只放完整调用</b>；DELAY 标记单独存放，
     * 仅通过 {@code getAllByType(RouteHandleType.DELAY)} 等按类型查询暴露。
     */
    private final Map<String, List<CapturedApiCall>> delayMarkersByEndpoint = new ConcurrentHashMap<>();

    /**
     * Response body 存储。
     *
     * <p>Key = 请求端点（路径+查询，不含 host），Value = 该端点被调用的所有响应 body（按顺序）。
     * 同一 endpoint 分页多次调用（如 /api/users?page=1, page=2）会全部保留。
     */
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
        observedRequests.incrementAndGet();
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
        int remaining = activeRequests.updateAndGet(current -> Math.max(0, current - 1));
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
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (completionLock) {
            // ⭐ P2-13（恢复单元测契约）：无待处理请求时立即返回 true，不阻塞。
            // 「fire-and-forget 并发 fetch 在 route 拦截前的窗口」由测试侧先行
            // waitForActiveRequest() 等待首个请求可见后再调用本方法，而非框架侧用首活动门控
            // 阻塞「无活动」场景（否则 awaitCompletion_noActiveRequests 契约被破坏、空等超时）。
            if (activeRequests.get() == 0) {
                return true;
            }
            // 排空门控：等待全部进行中请求完成（activeRequests 归零）。
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
     * ⭐ 可观测性标志：waiter 线程已进入 completionLock.wait() 内部时为 true。
     * 供测试/调试精确判断等待线程就绪，避免 signal 早于 wait 的竞态。
     */
    private volatile boolean inWaitState = false;

    /** 仅供测试观测：waiter 是否已进入 wait 状态 */
    boolean isInWaitState() {
        return inWaitState;
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
        observedRequests.set(0);
        hasAssertionFailures.set(false);
        failureDetails.clear();
        responseStorage.clear();
        // ⭐ 索引清空与 storeApiCall 的索引写持同一把 apiCallLock，串行化，
        // 避免 merger 正在写出时 reset 并发清空导致数据丢失竞态。
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
            // ⭐ #6 重置语义：所有投递式等待器立即以 null 完成（调用方返回 null），避免空等至超时
            if (!apiCallWaiters.isEmpty()) {
                for (CompletableFuture<CapturedApiCall> f : apiCallWaiters.keySet()) {
                    f.complete(null);
                }
                apiCallWaiters.clear();
            }
        }
        totalResponseSize.set(0L);
        // ⭐ R4: 测试级重置时清除步骤窗口标记
        stepStartTimestamp = 0L;
        testThread = null;
        synchronized (completionLock) {
            completionLock.notifyAll();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CapturedApiCall 存储（推荐）
    // ═══════════════════════════════════════════════════════════

    /**
     * ⭐ 存储一条 <b>DELAY 维度标记</b>（由 {@code RouteEngine} 的延迟分支调用）。
     *
     * <p>与 {@link #storeApiCall(CapturedApiCall)} 的区别：本方法写入<b>独立</b>的
     * {@link #delayMarkersByEndpoint}，不进入主快照存储，因此不会影响
     * {@code getLastApiCall} / {@code getApiCalls} / {@code waitForApi} 等通用查询 ——
     * 这些查询<b>永远只会返回带有完整请求/响应内容的调用</b>。
     *
     * <p>延迟标记仍可通过 {@link #getAllByType(RouteHandleType)} 以
     * {@code RouteHandleType.DELAY} 检索，用于回答「哪些请求被延迟过」。
     *
     * @param call 延迟标记（{@code handleType == DELAY}）
     */
    public void storeDelayMarker(CapturedApiCall call) {
        if (call == null || call.endpoint() == null) return;
        String endpoint = call.endpoint();
        synchronized (apiCallLock) {
            List<CapturedApiCall> markers = delayMarkersByEndpoint.computeIfAbsent(endpoint, k ->
                    java.util.Collections.synchronizedList(new java.util.LinkedList<>()));
            markers.add(call);
            while (markers.size() > MAX_CALLS_PER_ENDPOINT) {
                markers.remove(0);
            }
        }
        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[ApiCaptureContext] storeDelayMarker: endpoint='{}', method={}, url='{}'",
                endpoint, call.method(), RouteUtil.sanitizeUrl(call.requestUrl()));
    }

    /**
     * 存储一次完整的 API 调用快照（Monitor / Mock / Modify 均可使用）。
     *
     * <p><b>性能优化</b>：同时索引到 urlPattern 和 requestUrl 两个 Map，
     * 支持 O(1) 精确 URL 检索 + Ant 通配符 fallback。
     */
    public void storeApiCall(CapturedApiCall call) {
        if (call == null || call.endpoint() == null) return;

        String endpoint = call.endpoint();
        String url = call.requestUrl();

        // ⭐ 与 reset() 串行：索引写与 reset 的 clear 持同一把 apiCallLock，
        // 避免 merger 正在写出时 reset 并发清空导致的数据丢失竞态。
        synchronized (apiCallLock) {
            // ⭐ 修复 B-2：50MB 总体积守门必须覆盖 CapturedApiCall 完整快照。
            //   原守门只统计 responseStorage（去重 body），而 storeApiCall 把完整
            //   responseBody 存进了 apiCallsPerUrl/apiCallsByUrl/recentCalls 三处，
            //   导致"每端点一条大响应"即可绕过守门造成 OOM。此处同 addResponse 一致：
            //   超限则拒绝存储，避免双重存储放大。
            int callBodySize = call.responseBody() != null ? call.responseBody().length() : 0;
            if (totalResponseSize.get() >= MAX_RESPONSE_TOTAL_SIZE) {
                LOGGER.warn("[ApiCaptureContext] CapturedApiCall total size limit reached ({} >= {}). "
                                + "Rejecting storeApiCall for endpoint='{}' to prevent OOM.",
                        formatBytes(totalResponseSize.get()), formatBytes(MAX_RESPONSE_TOTAL_SIZE), endpoint);
                return;
            }
            totalResponseSize.addAndGet(callBodySize);

            List<CapturedApiCall> endpointCalls = apiCallsPerUrl.computeIfAbsent(endpoint, k ->
                    java.util.Collections.synchronizedList(new java.util.LinkedList<>()));
            endpointCalls.add(call);
            while (endpointCalls.size() > MAX_CALLS_PER_ENDPOINT) {
                // ⭐ 修复 H1：淘汰最老调用时必须回减总体积，否则计数器只增不减，
                //    越过 MAX_RESPONSE_TOTAL_SIZE 后所有 storeApiCall 被永久静默拒绝（监控快照全丢）。
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
                        java.util.Collections.synchronizedList(new java.util.LinkedList<>()));
                urlCalls.add(call);
                while (urlCalls.size() > MAX_CALLS_PER_REQUEST_URL) {
                    urlCalls.remove(0);
                }
            }

            // 追加到平铺最近调用列表（用于 scanForMatching 快速扫描），有界淘汰最老元素。
            recentCallsLock.lock();
            try {
                recentCalls.addLast(call);
                while (recentCalls.size() > MAX_RECENT_CALLS) {
                    recentCalls.pollFirst();
                }
            } finally {
                recentCallsLock.unlock();
            }

            // 通知 waitForApi 等待线程有新调用到达
            apiCallLock.notifyAll();
            // ⭐ #6 投递式唤醒：直接评估注册表谓词，命中即精确完成对应 future（点对点，非广播+重扫）
            deliverToWaiters(call);
        }

        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[ApiCaptureContext] storeApiCall: endpoint='{}', method={}, status={}, bodyLen={}",
                endpoint, call.method(), call.statusCode(),
                call.responseBody() != null ? call.responseBody().length() : 0);
    }

    /**
     * 更新已存储的 API 调用快照的响应体（惰性 body 读取完成后调用）。
     *
     * <p>按 requestUrl 精确查找最近一次调用，若其 responseBody 为 null 则替换为新 body。
     * 不创建新条目，避免 {@link #storeApiCall(CapturedApiCall)} 导致的重复存储。
     *
     * <p>线程安全：对列表的修改在 synchronized 块中执行。
     *
     * @param requestUrl 实际请求 URL（与 {@code CapturedApiCall.requestUrl()} 一致）
     * @param body       响应体字符串
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
                    // 创建新对象替换旧对象
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

        // 3. ⭐ 完整 URL 兜底：调用方可能传完整 URL（如 https://host/api/users/1），
        //    而存储 key 是 path-only endpoint（/api/users/1）；改用 requestUrl 精确索引匹配。
        List<CapturedApiCall> byUrl = getCallsByUrl(endpoint);
        if (!byUrl.isEmpty()) {
            return byUrl;
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
        if (latest != null) {
            return latest;
        }

        // 3. ⭐ 完整 URL 兜底：调用方可能传完整 URL（如 https://host/api/users/1），
        //    而存储 key 是 path-only endpoint（/api/users/1）；改用 requestUrl 精确索引匹配。
        return getCallByUrl(endpoint);
    }

    // ═══════════════════════════════════════════════════════════════
    // ⭐ R4: 步骤级窗口查询重载 — 只匹配 markStepStart() 之后的调用
    // ═══════════════════════════════════════════════════════════════

    /**
     * 获取当前步骤起始之后的、指定端点的所有 API 调用快照（按调用顺序）。
     *
     * <p>仅匹配 {@link #markStepStart()} 记录的时间戳之后的调用，
     * 避免命中上一步遗留的旧调用（R4 跨 step 串扰隔离）。
     *
     * @param endpoint 请求端点（路径+查询，不含 host）
     * @return 不可变副本列表，未找到返回空列表
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
     *
     * <p>仅匹配 {@link #markStepStart()} 记录的时间戳之后的调用（R4）。
     *
     * @param endpoint 请求端点（路径+查询，不含 host）
     * @return 捕获的快照，未找到返回 null
     */
    public CapturedApiCall getLastApiCallSinceStepStart(String endpoint) {
        List<CapturedApiCall> calls = getApiCallsSinceStepStart(endpoint);
        return calls.isEmpty() ? null : calls.get(calls.size() - 1);
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
            RouteUtil.evictOldestQuarter(PATTERN_CACHE);
        }
        PATTERN_CACHE.put(glob, compiled);
        return compiled;
    }

    /**
     * ⭐ #7 伪 LRU 淘汰辅助：从 ConcurrentHashMap 中移除约 25% 的条目。
     * <p>委托 {@link RouteUtil#evictOldestQuarter(Map)} 的统一实现；保留此方法以兼容
     * {@code RouteCoreEvictionTest} 对框架内部淘汰逻辑的直接验证。
     */
    private static void evictOldestQuarter(ConcurrentHashMap<?, ?> map) {
        RouteUtil.evictOldestQuarter(map);
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
    // ⭐ 按能力类型查询（MOCK / MODIFY / DELAY / MONITOR）
    // ═══════════════════════════════════════════════════════════

    /**
     * ⭐ 按路由能力类型获取全部 API 调用快照（按时间升序）。
     *
     * <p>四种能力各自独立落库，因此同一 endpoint 在复合场景下可能产生<b>多条不同类型</b>的记录：
     * <ul>
     *   <li>{@code MONITOR} — 对真实响应的观察结果（含被 MODIFY / DELAY 干预后的真实响应）</li>
     *   <li>{@code MODIFY}  — 请求被改写后拿回的真实响应（含 modifyDetail 修改详情）</li>
     *   <li>{@code DELAY}   — 纯延迟放行的请求元数据（响应未返回，status=0）</li>
     *   <li>{@code MOCK}    — fulfill 注入的假响应（含 interceptRealResponse 改写后的响应）</li>
     * </ul>
     *
     * <pre>{@code
     * List<CapturedApiCall> mocked = ctx.getAllByType(RouteHandleType.MOCK);
     * List<CapturedApiCall> monitored = ctx.getAllByType(RouteHandleType.MONITOR);
     * }</pre>
     *
     * @param type 能力类型（MOCK / MODIFY / DELAY / MONITOR）；null 返回空列表
     * @return 该类型的全部快照（不可变副本），无记录返回空列表
     */
    public List<CapturedApiCall> getAllByType(RouteHandleType type) {
        if (type == null) return Collections.emptyList();
        // ⭐ DELAY 是维度标记，存放在独立索引中（不污染主快照存储）
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

    /**
     * ⭐ 按「能力类型 + endpoint」获取指定端点的全部快照。
     *
     * <p>endpoint 匹配语义与 {@link #getApiCalls(String)} 完全一致
     * （精确匹配 → Ant 通配符 fallback → 完整 URL 兜底）。
     *
     * @param endpoint 端点（urlPattern 或完整 URL）
     * @param type     能力类型
     * @return 该端点该类型的全部快照，无记录返回空列表
     */
    public List<CapturedApiCall> getApiCallsByType(String endpoint, RouteHandleType type) {
        if (type == null) return Collections.emptyList();
        // ⭐ DELAY 走独立索引
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

    /**
     * ⭐ 按「能力类型 + endpoint」获取最近一次快照。
     *
     * @param endpoint 端点（urlPattern 或完整 URL）
     * @param type     能力类型
     * @return 最近一次该类型的快照；无记录返回 null
     */
    public CapturedApiCall getLastApiCallByType(String endpoint, RouteHandleType type) {
        List<CapturedApiCall> calls = getApiCallsByType(endpoint, type);
        return calls.isEmpty() ? null : calls.get(calls.size() - 1);
    }

    /**
     * ⭐ 按能力类型分组获取全部快照。
     *
     * <p>返回值四种 key 恒存在（无记录时为 {@code null}，与 {@link java.util.EnumMap} 语义一致），
     * 调用方无需判空 Map 本身。
     *
     * @return key=能力类型，value=该类型的全部快照（按时间升序）
     */
    public Map<RouteHandleType, List<CapturedApiCall>> getAllGroupedByType() {
        Map<RouteHandleType, List<CapturedApiCall>> grouped = new java.util.EnumMap<>(RouteHandleType.class);
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
        // ⭐ DELAY 来自独立索引
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

        // ── ⭐ #6 投递式等待：注册一次性谓词，storeApiCall 入库时直接评估并精确完成 future ──
        //    命中即返回；零重扫、零广播唤醒（替代旧的"notifyAll + 重扫"模式）。
        CompletableFuture<CapturedApiCall> waiter = registerApiCallWaiter(predicate);
        // 关闭"注册前入库 → 投递丢失"竞态：注册后立即补扫一次（单次检查，非轮询）
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

    /**
     * ⭐ #6 注册一次性投递式等待器：谓词将在后续每次入库时被直接评估，
     * 命中即完成返回的 future（点对点投递）。若调用早已入库，请先自行扫描。
     *
     * @param predicate 匹配谓词（在存储线程持有 apiCallLock 时评估，必须廉价且非阻塞）
     * @return 完成时为命中调用的 future
     */
    public CompletableFuture<CapturedApiCall> registerApiCallWaiter(Predicate<CapturedApiCall> predicate) {
        CompletableFuture<CapturedApiCall> future = new CompletableFuture<>();
        synchronized (apiCallLock) {
            apiCallWaiters.put(future, predicate);
        }
        return future;
    }

    /**
     * ⭐ #6 注销投递式等待器（幂等）：超时/中断/正常返回后必须调用，避免注册表泄漏。
     */
    public void unregisterApiCallWaiter(CompletableFuture<CapturedApiCall> waiter) {
        if (waiter == null) return;
        synchronized (apiCallLock) {
            apiCallWaiters.remove(waiter);
        }
    }

    /**
     * ⭐ #6 投递式唤醒：在 storeApiCall 持有 apiCallLock 时调用，评估所有注册谓词，
     * 命中即移除并完成对应 future。谓词必须廉价（仅字段比对），绝不可阻塞。
     */
    private void deliverToWaiters(CapturedApiCall call) {
        if (apiCallWaiters.isEmpty()) return;
        var it = apiCallWaiters.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            Predicate<CapturedApiCall> predicate = entry.getValue();
            if (predicate != null && predicate.test(call)) {
                it.remove();
                entry.getKey().complete(call);
            }
        }
    }

    /**
     * 遍历所有已存储的 API 调用，返回第一个匹配 predicate 的 CapturedApiCall。
     * <p>⭐ P3: 优先扫描 recentCalls 平铺列表（按时间排序，最新在前），单次 O(1) 尺寸扫描。
     * <p>仅在 recentCalls 未命中时 fallback 到 Map 遍历（兼容极边缘调用）。
     */
    private CapturedApiCall scanForMatching(Predicate<CapturedApiCall> predicate) {
        // ⭐ P3: Fast path — 从平铺列表由新到旧扫描（绝大多数命中即返回）。
        //   ArrayDeque 无 get(int)，用 descendingIterator 从 newest→oldest 遍历，并加锁保证一致性。
        recentCallsLock.lock();
        try {
            java.util.Iterator<CapturedApiCall> it = recentCalls.descendingIterator();
            while (it.hasNext()) {
                CapturedApiCall c = it.next();
                if (predicate.test(c)) return c;
            }
        } finally {
            recentCallsLock.unlock();
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
                java.util.Collections.synchronizedList(new java.util.LinkedList<>())
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
        if (list != null && !list.isEmpty()) {
            return list.get(list.size() - 1);
        }
        // ⭐ 向后兼容回退：响应体统一存在 CapturedApiCall 上，从最近一次调用读取。
        CapturedApiCall call = getLastApiCall(endpoint);
        return call != null ? call.responseBody() : null;
    }

    /**
     * 获取指定端点所有响应（按调用顺序保留，分页场景适用）。
     *
     * @param endpoint 请求端点
     * @return 不可变副本列表，未找到返回空列表
     */
    public List<String> getAllResponsesForUrl(String endpoint) {
        List<String> list = responseStorage.get(endpoint);
        if (list != null && !list.isEmpty()) {
            synchronized (list) {
                return new ArrayList<>(list);
            }
        }
        // ⭐ 向后兼容回退：MONITOR 存储已交给 capture（CapturedApiCall），从调用存储补齐响应体
        List<CapturedApiCall> calls = getApiCalls(endpoint);
        List<String> result = new ArrayList<>();
        if (calls != null) {
            for (CapturedApiCall c : calls) {
                if (c.responseBody() != null) {
                    result.add(c.responseBody());
                }
            }
        }
        return result;
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
        // ⭐ 向后兼容回退：responseStorage 为空时，从 CapturedApiCall 存储补齐（MONITOR 存储已交给 capture）
        if (result.isEmpty()) {
            for (Map.Entry<String, List<CapturedApiCall>> e : apiCallsPerUrl.entrySet()) {
                List<CapturedApiCall> list = e.getValue();
                if (list != null && !list.isEmpty()) {
                    CapturedApiCall last = list.get(list.size() - 1);
                    if (last.responseBody() != null) {
                        result.put(e.getKey(), last.responseBody());
                    }
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
        // ⭐ 向后兼容回退：responseStorage 为空时，从 CapturedApiCall 存储补齐（MONITOR 存储已交给 capture）
        if (result.isEmpty()) {
            for (Map.Entry<String, List<CapturedApiCall>> e : apiCallsPerUrl.entrySet()) {
                List<String> bodies = new ArrayList<>();
                for (CapturedApiCall c : e.getValue()) {
                    if (c.responseBody() != null) {
                        bodies.add(c.responseBody());
                    }
                }
                if (!bodies.isEmpty()) {
                    result.put(e.getKey(), bodies);
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
        // ⭐ 向后兼容回退：responseStorage 为空时，统计 CapturedApiCall 存储的响应体数量
        if (total == 0) {
            for (List<CapturedApiCall> calls : apiCallsPerUrl.values()) {
                for (CapturedApiCall c : calls) {
                    if (c.responseBody() != null) {
                        total++;
                    }
                }
            }
        }
        return total;
    }

    /**
     * 获取指定端点的调用次数。
     */
    public int getResponseCountForUrl(String endpoint) {
        List<String> list = responseStorage.get(endpoint);
        if (list != null && !list.isEmpty()) {
            return list.size();
        }
        // ⭐ 向后兼容回退：从 CapturedApiCall 存储读取调用次数
        List<CapturedApiCall> calls = getApiCalls(endpoint);
        return calls != null ? calls.size() : 0;
    }

    /**
     * 清除所有已存储的响应。
     */
    public void clearStoredResponses() {
        responseStorage.clear();
        // ⭐ 向后兼容：响应体统一存在 CapturedApiCall 上，
        //    清空响应存储时同步清空调用存储，保证 getResponseCountForUrl 回退后归零。
        apiCallsPerUrl.clear();
    }

    // ═══════════════════════════════════════════════════════════════
    // 原 ApiCapture 生命周期门面 / 断言逻辑（已并入本类，移除独立 ApiCapture 类）
    // ═══════════════════════════════════════════════════════════════

    // ⭐ 按 Context 隔离管理其下活动的 Page（不再维护 CaptureEngine 字段，旁路采集已移除）
    private static final ConcurrentHashMap<BrowserContext, Set<Page>> CONTEXT_PAGES =
            new ConcurrentHashMap<>();
    /** ⭐ 已注册 Context 关闭钩子的实例集合（幂等注册防重复，Playwright 无移除 listener API） */
    private static final ConcurrentHashMap<BrowserContext, Boolean> CONTEXT_CLOSE_REGISTERED =
            new ConcurrentHashMap<>();

    /** 启动 BrowserContext 级采集；已有 Page 需随后 attach。 */
    public static void start(BrowserContext context) {
        if (context == null) throw new IllegalArgumentException("BrowserContext must not be null");
        RouteEngine.startContextEngine(context);
        CONTEXT_PAGES.computeIfAbsent(context, ignored -> ConcurrentHashMap.newKeySet());
        ApiCaptureContext.bindCurrentContext(context);
        registerContextCloseHook(context);
    }

    /**
     * ⭐ 注册 Context 级关闭钩子（幂等）：中途 Context 被关闭（登录态切换重建 / 浏览器退出等）时，
     * 自动停止该 Context 下全部 Page 采集，并清理 {@link ApiCaptureContext} 与 Context 级路由引擎。
     * 无论从 {@link #start(BrowserContext)} 还是 {@link #start(Page)} 进入都只注册一次。
     */
    private static void registerContextCloseHook(BrowserContext context) {
        if (context == null) return;
        if (CONTEXT_CLOSE_REGISTERED.putIfAbsent(context, Boolean.TRUE) == null) {
            context.onClose(ignored -> stop(context));
        }
    }

    /** 将 Page 加入所属 BrowserContext 的采集会话。 */
    public static void attach(Page page) {
        if (page == null) throw new IllegalArgumentException("Page must not be null");
        BrowserContext context = page.context();
        start(context);
        start(page);
        CONTEXT_PAGES.get(context).add(page);
    }

    /** 从 Context 会话中移除 Page，不影响其它 Page。 */
    public static void detach(Page page) {
        if (page == null) return;
        BrowserContext context = null;
        try { context = page.context(); } catch (Exception ignored) { }
        if (context != null) {
            Set<Page> pages = CONTEXT_PAGES.get(context);
            if (pages != null) {
                pages.remove(page);
                if (pages.isEmpty()) CONTEXT_PAGES.remove(context, pages);
            }
        }
        stop(page);
    }

    /** 停止 Context 下全部 Page 采集。 */
    public static void stop(BrowserContext context) {
        if (context == null) return;
        // ⭐ 与 start(Page)/stop(Page)/stop() 共用 ApiCaptureContext.class 锁，防止并发修改 CONTEXT_PAGES
        synchronized (ApiCaptureContext.class) {
            Set<Page> pages = CONTEXT_PAGES.remove(context);
            if (pages != null) {
                for (Page page : new ArrayList<>(pages)) stop(page);
            }
            ApiCaptureContext.removeContext(context);
            RouteEngine.stopContextEngine(context);
            if (ApiCaptureContext.isCurrentContext(context)) {
                ApiCaptureContext.unbindCurrentContext();
            }
            // ⭐ 修复 P0-2：Context 关闭后清理幂等注册标记，避免 BrowserContext 强引用常驻 Map 导致泄漏
            CONTEXT_CLOSE_REGISTERED.remove(context);
            // ⭐ 显式清理 RouteRegistry 中该 Context 的残留条目（弱引用 ContextKey 失效后由本调用兜底清除，
            // 不依赖已移除的 purgeDeadEntries 定时扫描），避免死条目残留导致的内存泄漏。
            RouteRegistry.clearContext(context);
        }
    }

    /** 活动 Context 采集会话数，便于排查泄漏。 */
    public static int activeContextCount() {
        return CONTEXT_PAGES.size();
    }

    // ═══════════════════════════════════════════════════════════
    // 启动 / 停止
    // ═══════════════════════════════════════════════════════════

    /**
     * 快速启动 — 一行代码开启全量 API 采集。
     *
     * <p>⭐ 旁路采集已移除：本方法仅绑定当前线程到 Page 所属 Context、登记 Page 到会话、
     * 注册关闭钩子，真实的响应采集由各 Route Handler 在 route 事件线程内同步完成。
     *
     * @param page Playwright Page 实例
     */
    public static void start(Page page) {
        if (page == null) {
            throw new IllegalArgumentException("Page must not be null");
        }
        synchronized (ApiCaptureContext.class) {
            BrowserContext pageContext = page.context();
            CONTEXT_PAGES.computeIfAbsent(pageContext, ignored -> ConcurrentHashMap.newKeySet()).add(page);
            registerContextCloseHook(pageContext);
            page.onClose(ignored -> detach(page));
            // ⭐ 将调用线程绑定到该 Page 所属 BrowserContext，使 getCurrent() 指向正确的捕获上下文，
            //   消除跨用例数据串扰问题。
            ApiCaptureContext.bindCurrentContext(pageContext);
            LOGGER.info("[ApiCapture] Started for Page (activePages={})", activePageCount());
        }
    }

    /**
     * 链式启动入口（保留扩展空间）。
     *
     * <pre>{@code
     * ApiCaptureContext.on(page).start();
     * }</pre>
     */
    public static ApiCaptureStart on(Page page) {
        return new ApiCaptureStart(page);
    }

    /** 停止采集并释放资源。 */
    public static void stop() {
        synchronized (ApiCaptureContext.class) {
            for (Map.Entry<BrowserContext, Set<Page>> entry : new ArrayList<>(CONTEXT_PAGES.entrySet())) {
                for (Page page : new ArrayList<>(entry.getValue())) {
                    stop(page);
                }
            }
            LOGGER.info("[ApiCapture] Stopped all Page capture sessions");
        }
    }

    /** 停止并移除指定 Page 的采集会话，不影响同一 Context 的其它 Page。 */
    public static void stop(Page page) {
        if (page == null) return;
        synchronized (ApiCaptureContext.class) {
            releaseContextIfOrphaned(page);
            LOGGER.info("[ApiCapture] Stopped Page capture session (activePages={})", activePageCount());
        }
    }

    /**
     * ⭐ 当 Page 所属 BrowserContext 已无其它活动采集会话时，清理该 Context 的
     * 捕获上下文（BY_CONTEXT 实例）与当前线程绑定，防止跨用例数据串扰与实例泄漏。
     */
    private static void releaseContextIfOrphaned(Page page) {
        BrowserContext pageContext;
        try {
            pageContext = page.context();
        } catch (Exception e) {
            return;
        }
        if (pageContext == null) return;
        // 仍存在同 Context 的活动采集会话 → 保留 Context 级绑定
        for (Map.Entry<BrowserContext, Set<Page>> entry : CONTEXT_PAGES.entrySet()) {
            if (entry.getKey() == pageContext) continue;
            for (Page other : entry.getValue()) {
                try {
                    if (other.context() == pageContext) return;
                } catch (Exception ignored) {
                    // 其它 Page 已关闭，忽略
                }
            }
        }
        Set<Page> pages = CONTEXT_PAGES.get(pageContext);
        if (pages != null) {
            pages.remove(page);
            if (pages.isEmpty()) {
                CONTEXT_PAGES.remove(pageContext, pages);
            }
        }
        ApiCaptureContext.removeContext(pageContext);
        if (ApiCaptureContext.isCurrentContext(pageContext)) {
            ApiCaptureContext.unbindCurrentContext();
        }
    }

    /** 当前活动 Page 采集会话数。 */
    public static int activePageCount() {
        int total = 0;
        for (Set<Page> pages : CONTEXT_PAGES.values()) {
            total += pages.size();
        }
        return total;
    }

    // ═══════════════════════════════════════════════════════════
    // 断言
    // ═══════════════════════════════════════════════════════════

    /**
     * 创建断言器 — 按 URL 模式匹配已采集的 API 调用。
     *
     * <p>支持 Ant 风格通配符匹配：
     * <ul>
     *   <li>{@code /api/users/*} — 匹配单层路径</li>
     *   <li>{@code /api/**} — 匹配任意层级路径</li>
     * </ul>
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
     * <p>⭐ P0 防御：当存在多个活动 Page 时，全局上下文无法区分归属，回退到
     * {@code CURRENT_CONTEXT} 会导致跨测试数据串扰。此时立即失败
     * （fail-fast）而非静默返回错误数据，强制调用方改用 {@link #getAll(BrowserContext)}。
     *
     * @return 所有 API 调用（按 endpoint 分组）
     */
    public static Map<String, List<CapturedApiCall>> getAll() {
        // 修复 P1-12：size() 检查与 getCurrent() 必须在同一把类锁内完成，否则并发 start(page)
        // 可能使 size 从 1 变为 2 发生在检查之后，导致读到错误的跨 context 数据（TOCTOU 竞态）。
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



    /** ⭐ 按「能力类型 + endpoint」获取最近一次快照；无记录返回 null。 */
    public static CapturedApiCall getLastByType(String endpoint, RouteHandleType type) {
        return ApiCaptureContext.getCurrent().getLastApiCallByType(endpoint, type);
    }



    // ═══════════════════════════════════════════════════════════
    // 内部：链式启动
    // ═══════════════════════════════════════════════════════════

    /**
     * 链式启动辅助类。
     */
    public static class ApiCaptureStart {
        private final Page page;

        ApiCaptureStart(Page page) {
            this.page = page;
        }

        /**
         * 启动采集。
         */
        public void start() {
            ApiCaptureContext.start(page);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 内部：断言器
    // ═══════════════════════════════════════════════════════════

    /**
     * API 断言器 — 流式 API 对采集结果进行断言。
     *
     * <p>支持 status、jsonPath、bodyContains、isMock 断言。
     * 断言失败时自动保留完整快照并记录到测试报告。
     */
    public static class ApiAssertion {

        private final String urlPattern;
        private final Pattern regex;
        /** ⭐ P1: 断言首次未命中时，等待采集管道在途请求闭合的超时上限 */
        private static final long CAPTURE_AWAIT_TIMEOUT_MS = 1_500L;

        ApiAssertion(String urlPattern) {
            this.urlPattern = urlPattern;
            this.regex = globToRegex(urlPattern);
        }

        /**
         * 断言 HTTP 状态码。
         *
         * @param expectedStatus 期望的状态码
         * @return 当前断言器（链式调用）
         * @throws AssertionError 如果断言失败
         */
        public ApiAssertion statusIs(int expectedStatus) {
            CapturedApiCall call = findCall();
            if (call == null) {
                String msg = "No API call matched pattern '" + urlPattern + "'";
                recordFailure(msg);
                throw new AssertionError(msg);
            }
            if (call.statusCode() != expectedStatus) {
                String msg = String.format("Expected status %d for '%s', but got %d",
                        expectedStatus, urlPattern, call.statusCode());
                ApiCaptureContext.getCurrent().recordAssertionFailure(
                        call.requestUrl(), "STATUS",
                        String.valueOf(expectedStatus), String.valueOf(call.statusCode()),
                        "pattern=" + urlPattern);
                throw new AssertionError(msg);
            }
            return this;
        }

        /**
         * 断言 JSONPath 表达式的值。
         *
         * @param jsonPath JSONPath 表达式
         * @param expectedValue 期望值
         * @return 当前断言器（链式调用）
         * @throws AssertionError 如果断言失败
         */
        public ApiAssertion jsonPath(String jsonPath, Object expectedValue) {
            CapturedApiCall call = findCall();
            if (call == null) {
                String msg = "No API call matched pattern '" + urlPattern + "'";
                recordFailure(msg);
                throw new AssertionError(msg);
            }
            Object actual = call.json(jsonPath);
            if (actual == null || !actual.equals(expectedValue)) {
                String msg = String.format("JSONPath '%s' for '%s': expected '%s', but got '%s'",
                        jsonPath, urlPattern, expectedValue, actual);
                ApiCaptureContext.getCurrent().recordAssertionFailure(
                        call.requestUrl(), "JSONPATH",
                        String.valueOf(expectedValue), String.valueOf(actual),
                        "jsonPath=" + jsonPath + ", pattern=" + urlPattern);
                throw new AssertionError(msg);
            }
            return this;
        }

        /**
         * 断言响应体包含指定字符串。
         *
         * @param content 期望包含的字符串
         * @return 当前断言器（链式调用）
         * @throws AssertionError 如果断言失败
         */
        public ApiAssertion bodyContains(String content) {
            CapturedApiCall call = findCall();
            if (call == null) {
                String msg = "No API call matched pattern '" + urlPattern + "'";
                recordFailure(msg);
                throw new AssertionError(msg);
            }
            String body = call.responseBody();
            if (body == null || !body.contains(content)) {
                String msg = String.format("Response body for '%s' does not contain '%s'",
                        urlPattern, content);
                ApiCaptureContext.getCurrent().recordAssertionFailure(
                        call.requestUrl(), "BODY_CONTAINS",
                        content, body != null ? body.substring(0, Math.min(100, body.length())) : "null",
                        "pattern=" + urlPattern);
                throw new AssertionError(msg);
            }
            return this;
        }

        /**
         * 断言这是一个 Mock 响应。
         */
        public ApiAssertion isMock() {
            CapturedApiCall call = findCall();
            if (call == null) {
                String msg = "No API call matched pattern '" + urlPattern + "'";
                recordFailure(msg);
                throw new AssertionError(msg);
            }
            if (!call.fromMock()) {
                String msg = String.format("Expected '%s' to be a mock response, but it was not",
                        urlPattern);
                ApiCaptureContext.getCurrent().recordAssertionFailure(
                        call.requestUrl(), "IS_MOCK",
                        "true", "false", "pattern=" + urlPattern);
                throw new AssertionError(msg);
            }
            return this;
        }

        /**
         * 获取匹配的 API 调用（不断言）。
         */
        public CapturedApiCall get() {
            return findCall();
        }

        // ── 内部 ──

        private CapturedApiCall findCall() {
            ApiCaptureContext ctx = ApiCaptureContext.getCurrent();

            // 1/1b. 快路径：endpoint key + 完整 URL 索引双通道精确匹配
            CapturedApiCall call = fastExactMatch(ctx);
            if (call != null) return call;

            // ⭐ P1: 竞态兜底 — 等待采集管道在途请求闭合后再重试。
            awaitCapturePipeline(ctx);

            // ⭐ P1.2: 投递式等待 — awaitCompletion 可能在 captureInFlight==0
            //   （事件尚未到达 merger、REQUEST 还没计数）时立即返回；若此时只扫一次就放弃，
            //   调用会在随后几毫秒入库但断言已失败。先补扫一次已入库调用；仍未命中则
            //   注册一次性谓词，storeApiCall 入库时直接评估并精确完成 future。
            call = fastExactMatch(ctx);
            if (call != null) return call;

            // 2. 通配符匹配：仅遍历当前步骤窗口内的调用
            CapturedApiCall best = wildcardScan(ctx);
            if (best != null) return best;

            long stepStart = ctx.getStepStartTimestamp();
            CompletableFuture<CapturedApiCall> waiter =
                    ctx.registerApiCallWaiter(c -> matchesPattern(c, stepStart));
            // 关闭"注册前入库 → 投递丢失"竞态：调用可能在注册与评估之间已入库，
            // 注册后立即补扫一次（单次检查，非轮询），命中即返回。
            CapturedApiCall late = fastExactMatch(ctx);
            if (late == null) late = wildcardScan(ctx);
            if (late != null) {
                ctx.unregisterApiCallWaiter(waiter);
                return late;
            }
            try {
                return waiter.get(CAPTURE_AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (TimeoutException | ExecutionException e) {
                return null;
            } finally {
                ctx.unregisterApiCallWaiter(waiter);
            }
        }

        /**
         * ⭐ P1.2: 投递式匹配谓词 — 与 fastExactMatch/wildcardScan 同源：
         * endpoint key（path-only）与完整 URL 双通道 + 步骤窗口。
         */
        private boolean matchesPattern(CapturedApiCall c, long stepStart) {
            if (c == null) return false;
            if (stepStart != 0L && c.timestamp() < stepStart) return false;
            String endpoint = c.endpoint();
            String url = c.requestUrl();
            if (urlPattern.equals(endpoint) || urlPattern.equals(url)) return true;
            if (endpoint != null && regex.matcher(endpoint).matches()) return true;
            return url != null && regex.matcher(url).matches();
        }

        /** ⭐ 快路径精确匹配：endpoint key（path-only）+ 完整 URL 索引双通道（限定步骤窗口）。 */
        private CapturedApiCall fastExactMatch(ApiCaptureContext ctx) {
            // 1. 精确匹配（限定在当前步骤窗口内，R4）— 按 endpoint key（path-only）检索
            CapturedApiCall call = ctx.getLastApiCallSinceStepStart(urlPattern);
            if (call != null) return call;

            // 1b. ⭐ P2: 完整 URL 精确索引（O(1)，apiCallsByUrl）——pattern 传完整 URL 时
            //     endpoint key 无法命中（存储键为 path-only），这里补一次 URL 索引查询。
            if (!containsGlobWildcard(urlPattern)) {
                CapturedApiCall byUrl = lastSinceStepStart(ctx.getCallsByUrl(urlPattern), ctx);
                if (byUrl != null) return byUrl;
            }
            return null;
        }

        /** ⭐ 步骤 2：通配符全量扫描（遍历每个 endpoint 的全部调用而非仅最后一条）。 */
        private CapturedApiCall wildcardScan(ApiCaptureContext ctx) {
            Map<String, List<CapturedApiCall>> all = ctx.getAllApiCalls();
            CapturedApiCall best = null;
            long bestTimestamp = 0;
            long stepStart = ctx.getStepStartTimestamp();
            for (Map.Entry<String, List<CapturedApiCall>> e : all.entrySet()) {
                List<CapturedApiCall> calls = e.getValue();
                if (calls == null || calls.isEmpty()) continue;
                for (CapturedApiCall c : calls) {
                    if (c == null) continue;
                    // 仅考虑本步骤窗口内的调用
                    if (stepStart != 0L && c.timestamp() < stepStart) continue;
                    if (!regex.matcher(e.getKey()).matches()
                            && !regex.matcher(c.requestUrl()).matches()) {
                        continue;
                    }
                    if (c.timestamp() > bestTimestamp) {
                        best = c;
                        bestTimestamp = c.timestamp();
                    }
                }
            }
            return best;
        }

        /** ⭐ P2: 取列表内步骤窗口中的最近一条调用（列表按时间追加，倒序查找）。 */
        private CapturedApiCall lastSinceStepStart(List<CapturedApiCall> calls, ApiCaptureContext ctx) {
            if (calls == null || calls.isEmpty()) return null;
            long stepStart = ctx == null ? 0L : ctx.getStepStartTimestamp();
            for (int i = calls.size() - 1; i >= 0; i--) {
                CapturedApiCall c = calls.get(i);
                if (c == null) continue;
                if (stepStart != 0L && c.timestamp() < stepStart) continue;
                return c;
            }
            return null;
        }

        /** ⭐ P1: 有限等待采集管道在途请求闭合，不抛出中断异常。 */
        private void awaitCapturePipeline(ApiCaptureContext ctx) {
            if (ctx == null) return;
            try {
                ctx.awaitCompletion(CAPTURE_AWAIT_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static boolean containsGlobWildcard(String s) {
            return s != null && s.indexOf('*') >= 0;
        }

        private void recordFailure(String msg) {
            ApiCaptureContext.getCurrent().recordAssertionFailure(
                    urlPattern, "NO_MATCH", "ANY", "NONE", msg);
        }

        /**
         * 将 Ant glob 模式转换为正则。
         */
        private static Pattern globToRegex(String glob) {
            StringBuilder sb = new StringBuilder("^");
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
            return Pattern.compile(sb.toString());
        }
    }
}
