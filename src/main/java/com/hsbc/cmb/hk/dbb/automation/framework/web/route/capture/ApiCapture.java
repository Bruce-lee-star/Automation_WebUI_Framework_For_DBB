package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 用户态 API 采集入口 — 简单的流式调用，一行代码启动全量采集。
 *
 * <p>使用示例：
 * <pre>{@code
 * // 1. 快速启动（采集所有 API，零配置）
 * ApiCapture.start(page);
 *
 * // 2. 链式启动（保留扩展空间）
 * ApiCapture.on(page).start();
 *
 * // 3. 在测试代码中断言
 * ApiCapture.assertThat("/api/user/list").statusIs(200);
 * ApiCapture.assertThat("/api/user/detail").jsonPath("$.code", 0);
 * ApiCapture.assertThat("/api/**").statusIs(200);
 *
 * // 4. 等待特定 API
 * CapturedApiCall call = ApiCapture.waitForApi(
 *     c -> "POST".equals(c.method()) && c.isOk(), 5000);
 *
 * // 5. 获取所有采集结果
 * List<CapturedApiCall> all = ApiCapture.getAll();
 *
 * // 6. 停止采集
 * ApiCapture.stop();
 * }</pre>
 *
 * <p>设计原则：
 * <ul>
 *   <li>零配置启动：{@code ApiCapture.start(page)} 即可</li>
 *   <li>不修改现有 {@code RouteDsl} 调用方式</li>
 *   <li>断言失败时自动保留完整快照用于排查</li>
 *   <li>跨页面采集：用例内切到新 {@code Page} 实例时，再次调用
 *       {@code ApiCapture.start(newPage)} 即可迁移采集。CDP 策略每次新建
 *       session 自动绑定新页面；Playwright 退化策略会按 Page 引用变化自动为
 *       新页面重新注册监听器（旧页面监听器随其关闭被回收），因此新页面请求
 *       不会静默丢失。</li>
 * </ul>
 */
public class ApiCapture {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiCapture.class);

    // ⭐ 采集引擎按 Page 隔离管理（PAGE_ENGINES），不再维护全局回退引擎字段，避免跨 Page 串线
    private static final java.util.concurrent.ConcurrentHashMap<Page, CaptureEngine> PAGE_ENGINES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<BrowserContext, java.util.Set<Page>> CONTEXT_PAGES =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** ⭐ 已注册 Context 关闭钩子的实例集合（幂等注册防重复，Playwright 无移除 listener API） */
    private static final java.util.concurrent.ConcurrentHashMap<BrowserContext, Boolean> CONTEXT_CLOSE_REGISTERED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 启动 BrowserContext 级采集；已有 Page 需随后 attach。 */
    public static void start(BrowserContext context) {
        if (context == null) throw new IllegalArgumentException("BrowserContext must not be null");
        com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ContextRouteEngineManager.start(context);
        java.util.Set<Page> pages = CONTEXT_PAGES.computeIfAbsent(context,
                ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet());
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
            java.util.Set<Page> pages = CONTEXT_PAGES.get(context);
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
        // ⭐ 修复 P0-1：与 start(Page)/stop(Page)/stop() 共用 ApiCapture.class 锁，
        // 防止并发 start(Page) 向 CONTEXT_PAGES 写入的同时本方法遍历并移除同一 set，
        // 导致 ConcurrentModificationException 或对同一 Page 双 stop。
        synchronized (ApiCapture.class) {
            java.util.Set<Page> pages = CONTEXT_PAGES.remove(context);
            if (pages != null) {
                for (Page page : new java.util.ArrayList<>(pages)) stop(page);
            }
            ApiCaptureContext.removeContext(context);
            com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ContextRouteEngineManager.stop(context);
            if (ApiCaptureContext.isCurrentContext(context)) {
                ApiCaptureContext.unbindCurrentContext();
            }
            // ⭐ 修复 P0-2：Context 关闭后清理幂等注册标记，避免 BrowserContext 强引用常驻 Map 导致泄漏
            CONTEXT_CLOSE_REGISTERED.remove(context);
            // ⭐ 修复 P1-1：显式清理 RouteRegistry 中该 Context 的残留条目（含 WeakReference 失效的 ContextKey），
            // 弥补 purgeDeadEntries 仅在 register 时触发、长时不注册则死条目残留的内存泄漏。
            com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRegistry.clearContext(context);
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
     * <p>内部自动选择采集策略（CDP 旁路 > Playwright 事件退化），
     * 启动 RingBuffer 和 EventMerger 异步消费者。
     *
     * @param page Playwright Page 实例
     */
    public static void start(Page page) {
        if (page == null) {
            throw new IllegalArgumentException("Page must not be null");
        }
        synchronized (ApiCapture.class) {
            CaptureEngine existing = PAGE_ENGINES.get(page);
            if (existing != null && existing.isRunning()) {
                return;
            }
            CaptureEngine created = new CaptureEngine(page);
            PAGE_ENGINES.put(page, created);
            BrowserContext pageContext = page.context();
            CONTEXT_PAGES.computeIfAbsent(pageContext, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(page);
            // ⭐ Page 关闭 → 停止该 Page 采集；Context 关闭 → 停止该 Context 下全部采集并清理
            //   （Context 级数据/路由清理必须走 stop(context)，仅 page.onClose 的 detach 不够）。
            registerContextCloseHook(pageContext);
            page.onClose(ignored -> detach(page));
            RouteEngine.bindCaptureEngine(page, created);
            // ⭐ P0: 将调用线程绑定到该 Page 所属 BrowserContext，使 getCurrent() 与
            //   EventMerger（按 browserContext 写入 forContext 实例）指向同一捕获上下文，
            //   消除"merger 写入 BY_CONTEXT 实例、测试查询 SHARED 实例"的数据不可见问题。
            ApiCaptureContext.bindCurrentContext(pageContext);
            LOGGER.info("[ApiCapture] Started for Page with strategy '{}' (activePages={})",
                    created.strategyName(), PAGE_ENGINES.size());
        }
    }

    /**
     * 链式启动入口（保留扩展空间）。
     *
     * <pre>{@code
     * ApiCapture.on(page).start();
     * }</pre>
     */
    public static ApiCaptureStart on(Page page) {
        return new ApiCaptureStart(page);
    }

    /**
     * 停止采集并释放资源。
     */
    public static void stop() {
        synchronized (ApiCapture.class) {
            for (Page page : new java.util.ArrayList<>(PAGE_ENGINES.keySet())) {
                stop(page);
            }
            LOGGER.info("[ApiCapture] Stopped all Page capture sessions");
        }
    }

    /** 停止并移除指定 Page 的采集会话，不影响同一 Context 的其它 Page。 */
    public static void stop(Page page) {
        if (page == null) return;
        synchronized (ApiCapture.class) {
            CaptureEngine removed = PAGE_ENGINES.remove(page);
            // ⭐ 先移除引用，避免 shutdown 抛异常时静态 Map 泄漏导致后续线程/资源残留
            if (removed == null) return;
            RouteEngine.unbindCaptureEngine(page);
            try {
                removed.shutdown();
            } catch (Exception e) {
                LOGGER.warn("[ApiCapture] Error during Page capture shutdown: {}", e.getMessage());
            }
            // ⭐ P0: 若该 Page 所属 Context 下已无其它活动采集会话，清理 Context 级捕获上下文，
            //   避免 BY_CONTEXT 实例泄漏、以及 CURRENT_CONTEXT 跨用例残留导致数据串扰。
            releaseContextIfOrphaned(page);
            LOGGER.info("[ApiCapture] Stopped Page capture session (activePages={})", PAGE_ENGINES.size());
        }
    }

    /**
     * ⭐ P0: 当 Page 所属 BrowserContext 已无其它活动采集会话时，清理该 Context 的
     * 捕获上下文（BY_CONTEXT 实例）与当前线程绑定，防止跨用例数据串扰与实例泄漏。
     * 若 Context 下仍有其它 Page 在采集，则保留（多 Page 共享 Context 的场景）。
     */
    private static void releaseContextIfOrphaned(Page page) {
        BrowserContext pageContext;
        try {
            pageContext = page.context();
        } catch (Exception e) {
            // Page 已关闭：Context 级清理由 onClose → detach / stop(context) 完成
            return;
        }
        if (pageContext == null) return;
        // 仍存在同 Context 的活动采集会话 → 保留 Context 级绑定
        for (Page other : PAGE_ENGINES.keySet()) {
            if (other == page) continue;
            try {
                if (other.context() == pageContext) return;
            } catch (Exception ignored) {
                // 其它 Page 已关闭，忽略
            }
        }
        java.util.Set<Page> pages = CONTEXT_PAGES.get(pageContext);
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
        return PAGE_ENGINES.size();
    }

    /**
     * 获取指定 Page 最近的事件链终态快照，仅用于迁移诊断。
     * 不触发写入、断言、回调或持久化，也不改变既有捕获查询结果。
     */
    public static List<NetworkExchange> recentExchanges(Page page) {
        // ⭐ 按 Page 隔离获取；全局回退引擎已移除，无 Page 引擎时返回空（调用方应改用 getXxx(Page/Context) 隔离 API）
        CaptureEngine pageEngine = page == null ? null : PAGE_ENGINES.get(page);
        return pageEngine == null ? List.of() : pageEngine.recentExchanges();
    }

    /** 获取指定 Page 的事件链旁路诊断指标，不影响既有 CaptureMetrics。 */
    public static NetworkExchangeMetrics exchangeMetrics(Page page) {
        CaptureEngine pageEngine = page == null ? null : PAGE_ENGINES.get(page);
        return pageEngine == null ? new NetworkExchangeMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0)
                : pageEngine.exchangeMetrics();
    }

    /**
     * 是否正在采集（按 Page 隔离判定：任一 Page 引擎在运行即为 true）。
     */
    public static boolean isActive() {
        return PAGE_ENGINES.values().stream().anyMatch(e -> e != null && e.isRunning());
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
     * ApiCapture.assertThat("/api/user/list").statusIs(200);
     * ApiCapture.assertThat("/api/user/detail").jsonPath("$.code", 0);
     * }</pre>
     *
     * @param urlPattern URL 模式（支持 Ant glob 通配符）
     * @return 断言器
     */
    public static ApiAssertion assertThat(String urlPattern) {
        return new ApiAssertion(urlPattern);
    }

    /**
     * 等待匹配特定条件的 API 调用出现。
     *
     * <pre>{@code
     * // 等待 POST /api/login 返回 200
     * CapturedApiCall login = ApiCapture.waitForApi(
     *     c -> "POST".equals(c.method()) && c.isOk(), 5000);
     * }</pre>
     *
     * @param predicate 匹配条件
     * @param timeoutMs 超时毫秒
     * @return 匹配的 API 调用，超时返回 null
     */
    public static CapturedApiCall waitForApi(Predicate<CapturedApiCall> predicate, long timeoutMs) {
        return ApiCaptureContext.getCurrent().waitForApi(predicate, timeoutMs);
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
    public static java.util.Map<String, List<CapturedApiCall>> getAll() {
        // 修复 P1-12：size() 检查与 getCurrent() 必须在同一把类锁内完成，否则并发 start(page)
        // 可能使 size 从 1 变为 2 发生在检查之后，导致读到错误的跨 context 数据（TOCTOU 竞态）。
        synchronized (ApiCapture.class) {
            if (PAGE_ENGINES.size() > 1) {
                throw new IllegalStateException(
                        "ApiCapture.getAll() detected multiple active Pages (" + PAGE_ENGINES.size()
                                + "). Use getAll(Page) / getAll(BrowserContext) to avoid cross-test data contamination.");
            }
            return ApiCaptureContext.getCurrent().getAllApiCalls();
        }
    }

    /** Context 隔离版本：获取指定 BrowserContext 的全部 API 调用。 */
    public static java.util.Map<String, List<CapturedApiCall>> getAll(BrowserContext context) {
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

    /**
     * 获取采集引擎的运行时指标（按 Page 隔离：返回任一运行中引擎的指标）。
     */
    public static CaptureMetrics metrics() {
        CaptureEngine e = PAGE_ENGINES.values().stream()
                .filter(en -> en != null && en.isRunning())
                .findFirst().orElse(null);
        return e != null ? e.metrics() : null;
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
            ApiCapture.start(page);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 内部：断言器
    // ═══════════════════════════════════════════════════════════

    /**
     * API 断言器 — 流式 API 对采集结果进行断言。
     *
     * <p>支持 status、jsonPath 断言。
     * 断言失败时自动保留完整快照并记录到测试报告。
     */
    public static class ApiAssertion {

        private final String urlPattern;
        private final Pattern regex;
        /** 资源类型过滤（枚举集合），null 表示不过滤 */
        private Set<ResourceType> resourceTypeFilter;
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
         * 按资源类型（枚举）过滤匹配的 API 调用。类型安全，推荐用法。
         *
         * <p>采集框架会标记每个请求的资源类型（见 {@link ResourceType}），
         * 例如 {@code ResourceType.XHR}、{@code ResourceType.FETCH}。
         * 仅当匹配到的调用资源类型与给定枚举之一一致时才算命中。
         *
         * @param types 资源类型枚举（可传多个，命中任一即可），如 {@code ofType(ResourceType.XHR, ResourceType.FETCH)}
         * @return 当前断言器（链式调用）
         */
        public ApiAssertion ofType(ResourceType... types) {
            this.resourceTypeFilter = java.util.EnumSet.noneOf(ResourceType.class);
            for (ResourceType t : types) {
                if (t != null) this.resourceTypeFilter.add(t);
            }
            return this;
        }

        /**
         * 按资源类型（字符串）过滤匹配的 API 调用（便捷重载，内部解析为 {@link ResourceType}）。
         *
         * <p>支持逗号分隔的多个类型，大小写不敏感，例如 {@code ofType("XHR,FETCH")} 或 {@code ofType("xhr")}。
         * 无法识别的类型会被归一为 {@link ResourceType#OTHER}。
         *
         * @param type 资源类型字符串（如 "XHR"、"Fetch"、"document"）
         * @return 当前断言器（链式调用）
         */
        public ApiAssertion ofType(String type) {
            this.resourceTypeFilter = java.util.EnumSet.noneOf(ResourceType.class);
            if (type != null && !type.trim().isEmpty()) {
                for (String t : type.split(",")) {
                    this.resourceTypeFilter.add(ResourceType.fromString(t));
                }
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
            //   navigate() 刚返回就断言时，CDP 事件可能仍在"事件回调→RingBuffer→merger 合并"链路中；
            //   awaitCompletion 覆盖 captureInFlight（merger 消费 REQUEST 时计数、合并/超时后归零）。
            awaitCapturePipeline(ctx);

            // ⭐ P1.2: 投递式等待 — awaitCompletion 可能在 captureInFlight==0
            //   （CDP 事件尚未到达 merger、REQUEST 还没计数）时立即返回；若此时只扫一次就放弃，
            //   调用会在随后几毫秒入库但断言已失败（example_assertApiByUrl 偶发失败即此竞态）。
            //   先补扫一次已入库调用；仍未命中则注册一次性谓词，storeApiCall 入库时
            //   直接评估并精确完成 future —— 命中即返回，零重扫、零广播唤醒。
            call = fastExactMatch(ctx);
            if (call != null) return call;

            // 2. 通配符匹配：仅遍历当前步骤窗口内的调用（R3/R4）
            //    避免命中上一步遗留调用或错误 endpoint，消除"全局最新时间戳"误匹配。
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
         * endpoint key（path-only）与完整 URL 双通道 + 步骤窗口 + 资源类型过滤。
         */
        private boolean matchesPattern(CapturedApiCall c, long stepStart) {
            if (c == null) return false;
            if (stepStart != 0L && c.timestamp() < stepStart) return false;
            if (!matchType(c)) return false;
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
            if (call != null && matchType(call)) return call;

            // 1b. ⭐ P2: 完整 URL 精确索引（O(1)，apiCallsByUrl）——pattern 传完整 URL 时
            //     endpoint key 无法命中（存储键为 path-only），这里补一次 URL 索引查询。
            if (!containsGlobWildcard(urlPattern)) {
                CapturedApiCall byUrl = lastSinceStepStart(ctx.getCallsByUrl(urlPattern), ctx);
                if (byUrl != null && matchType(byUrl)) return byUrl;
            }
            return null;
        }

        /** ⭐ 步骤 2：通配符全量扫描（Bug B 修复：遍历每个 endpoint 的全部调用而非仅最后一条）。 */
        private CapturedApiCall wildcardScan(ApiCaptureContext ctx) {
            java.util.Map<String, List<CapturedApiCall>> all = ctx.getAllApiCalls();
            CapturedApiCall best = null;
            long bestTimestamp = 0;
            long stepStart = ctx.getStepStartTimestamp();
            for (java.util.Map.Entry<String, List<CapturedApiCall>> e : all.entrySet()) {
                List<CapturedApiCall> calls = e.getValue();
                if (calls == null || calls.isEmpty()) continue;
                // ⭐ Bug B 修复：遍历该 endpoint 的全部调用（而不只是最后一条）。
                //   同一 URL 可能先发 XHR 后被 DOCUMENT 导航覆盖，仅看最后一条会因
                //   matchType/regex 不命中而漏掉更早的匹配调用（example_filterByResourceType 即此场景）。
                for (CapturedApiCall c : calls) {
                    if (c == null) continue;
                    // 仅考虑本步骤窗口内的调用
                    if (stepStart != 0L && c.timestamp() < stepStart) continue;
                    // ⭐ P2: endpoint key（path-only）与完整 URL 双通道匹配——
                    //   pattern 为完整 URL（如 https://httpbin.org/**）时，仅匹配 key 必然落空。
                    if (!regex.matcher(e.getKey()).matches()
                            && !regex.matcher(c.requestUrl()).matches()) {
                        continue;
                    }
                    if (!matchType(c)) continue;
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

        /** 资源类型过滤：filter 为 null 或空集合时直接通过；否则匹配调用方的 ResourceType 是否在集合内 */
        private boolean matchType(CapturedApiCall call) {
            if (resourceTypeFilter == null || resourceTypeFilter.isEmpty()) return true;
            ResourceType actual = call.resourceType();
            if (actual == null) actual = ResourceType.OTHER;
            return resourceTypeFilter.contains(actual);
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