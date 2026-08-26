package com.hsbc.cmb.hk.dbb.automation.framework.web.route.handler;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor.ApiMonitorOrchestrator;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor.MonitorFailureCollector;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.MonitorCallback;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.persistence.DatabaseStoreMonitorCallback;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.persistence.FileStoreMonitorCallback;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SerenityReporter;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.jayway.jsonpath.JsonPath;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * API 监控 Handler — 在 Playwright 事件线程中同步读取响应 body，
 * 拷贝 byte[] 后交给 RouteAsyncPool 异步执行断言和报告记录。
 *
 * <p>关键设计原则：
 * <ul>
 *   <li>response.body() 在 Playwright 事件线程同步调用（线程安全）</li>
 *   <li>byte[] 拷贝后传给异步线程，避免跨线程访问 Response 对象</li>
 *   <li>断言结果通过 {@link ApiCaptureContext} 通知测试生命周期</li>
 *   <li>失败详情（URL、类型、预期值、实际值）记录到上下文供测试结束报告</li>
 *   <li>Serenity 报告写入通过 {@link SerenityReporter} 统一处理</li>
 *   <li>route.resume() 包裹 try-catch，避免单请求失败导致整个路由崩溃</li>
 * </ul>
 */
public class MonitorHandler {

    /**
     * body 读取重试调度器：用于 {@link #readResponseBodyWithRetry} 的异步退避，
     * 避免 Thread.sleep 阻塞 route 处理线程（线程契约）。
     */
    private static final ScheduledExecutorService bodyReadScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "monitor-body-retry");
                t.setDaemon(true);
                return t;
            });

    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorHandler.class);

    /** 等待真实响应 / 兜底请求的默认超时（毫秒），可用环境变量 ROUTE_FETCH_TIMEOUT_MS 覆盖 */
    private static final double ROUTE_FETCH_TIMEOUT_MS = RouteUtil.getEnvDouble("ROUTE_FETCH_TIMEOUT_MS", 30000);

    /** 从 urlPattern 提取字面前缀（去除通配符），用于宽松匹配响应 URL。 */
    private static boolean hasDelay(RouteRule rule) {
        return rule != null && (rule.getDelayMs() > 0
                || rule.getDelayMinMs() > 0 || rule.getDelayMaxMs() > 0);
    }

    private static String literalPathOf(String urlPattern) {
        if (urlPattern == null || urlPattern.isEmpty()) return null;
        String p = urlPattern;
        while (p.startsWith("**")) p = p.substring(2);
        while (p.endsWith("**")) p = p.substring(0, p.length() - 2);
        int star = p.indexOf('*');
        if (star >= 0) p = p.substring(0, star);
        return p.isEmpty() ? null : p;
    }

    // ═══════════════════════════════════════════════════════════════
    // ⭐ 性能优化：JsonPath 编译缓存（避免每次断言都重新编译表达式）
    // ═══════════════════════════════════════════════════════════════
    private static final Map<String, JsonPath> JSONPATH_CACHE = new ConcurrentHashMap<>();
    private static final int JSONPATH_CACHE_MAX = 200;

    /**
     * 处理单个 route 的监控逻辑（带断言）。
     *
     * <p><b>⭐⭐⭐ 重要架构变更 — 同步断言 + Fail-Fast</b>：
     * <ul>
     *   <li>断言（状态码 / JSONPath）在 Playwright 事件线程上<b>同步执行</b>，
     *       不再提交到 RouteAsyncPool 异步线程</li>
     *   <li>断言失败 → 调用 {@code context.signalFailFast()} 中断主测试线程，
     *       主线程当前阻塞的 Playwright IO 操作立即感知中断，Step 即刻失败</li>
     *   <li>响应体存储、CapturedApiCall 快照、Serenity 报告记录仍提交到
     *       RouteAsyncPool 异步执行（繁重操作不阻塞事件线程）</li>
     * </ul>
     */
    public static void handle(Route route, RouteRule rule) {
        // ═══ 页面关闭检查：页面已关闭时直接放行，避免对已销毁页面操作报错 ═══
        if (RouteUtil.isPageClosed(route)) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[MonitorHandler] Page/Context already closed, resume & skip for pattern='{}'",
                    rule.getUrlPattern());
            RouteUtil.safeResume(route);
            return;
        }

        // 获取 API 监控上下文并增加活动请求计数
        ApiCaptureContext context = RouteUtil.captureContext(route);
        if (context == null) {
            LOGGER.warn("[MonitorHandler] ApiCaptureContext is null, resuming & skipping assertion for pattern='{}'",
                    rule.getUrlPattern());
            // ⭐ 必须放行请求，否则请求会永久挂起
            RouteUtil.safeResume(route);
            return;
        }

        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[MonitorHandler] ── handle() START: pattern='{}', expectStatus={}, jsonPathAssertions={} ──",
                rule.getUrlPattern(), rule.getExpectedStatus(),
                rule.getJsonPathAssertions() != null ? rule.getJsonPathAssertions().size() : 0);

        // DELAY 场景由 CDP/EventMerger 捕获真实响应；此处只放行，不再操作易失效的 Response 对象。
        if (hasDelay(rule)) {
            RouteUtil.safeResume(route);
            return;
        }

        // ⭐⭐ 用 page.waitForResponse 可靠获取真实响应（源码级确认，见 Playwright RouteImpl/RequestImpl）：
        //    • route.request().response() 是「实时 channel 调用 + 依赖对象表」，异步延迟线程里
        //      Response 对象被 GC 后从对象表移除 → "Object doesn't exist: response@..."。
        //    • page.waitForResponse(predicate, code) 基于 Playwright 自身管道的 "response" 服务端推送事件，
        //      DELAY 放行（resume）后请求继续完成必然触发该事件；返回的 Response 被 waitForResponse 强引用持有，
        //      不被 GC → 可安全读 body/status/headers。这是不依赖失效对象、不依赖 CDP 的可靠方式。
        Request req = route.request();
        com.microsoft.playwright.Frame frame = req.frame();
        Response res = null;
        if (frame != null) {
            com.microsoft.playwright.Page page = frame.page();
            if (page != null) {
                // ⭐ 超时保护：绝不传 0（Playwright 源码 TimeoutSettings.createWaitable 中 timeout==0
                //   会返回 WaitableNever 无限等待 → 死等）。ROUTE_FETCH_TIMEOUT_MS 若被设成 0/负数，
                //   强制回落到 20s 上限，保证最多阻塞 20s，绝不永久挂起。
                double wfrTimeout = Math.min(20000, ROUTE_FETCH_TIMEOUT_MS);
                if (wfrTimeout <= 0) wfrTimeout = 20000;
                // ⭐ predicate 用「URL 包含字面路径」而非精确 equals：避免响应重定向/参数规范化后
                //    predicate 永不匹配 → 每个请求白等满 20s 超时（性能问题）。
                final String lit = literalPathOf(rule.getUrlPattern());
                try {
                    com.microsoft.playwright.Page.WaitForResponseOptions wfrOpts =
                            new com.microsoft.playwright.Page.WaitForResponseOptions()
                                    .setTimeout(wfrTimeout);
                    res = page.waitForResponse(
                            r -> {
                                if (r == null || r.request() == null) return false;
                                String ru = r.request().url();
                                return ru != null && (lit != null ? ru.contains(lit) : ru.equals(req.url()));
                            },
                            wfrOpts,
                            () -> {
                                // 放行：若 route 已失效（Firefox/WebKit 下 Object doesn't exist）
                                // 则静默跳过，让 waitForResponse 自然结束，避免抛异常污染等待链路。
                                if (RouteUtil.isPageClosed(route)) return;
                                RouteUtil.safeResume(route);
                            });
                } catch (PlaywrightException e) {
                    LoggingConfigUtil.logWarnIfVerbose(LOGGER,
                            "[MonitorHandler] waitForResponse failed/expired (async/delayed context), skip assertion: pattern='{}', url='{}', error='{}'",
                            rule.getUrlPattern(), req.url(), e.getMessage());
                    // 兜底放行，避免请求永久挂起
                    RouteUtil.safeResume(route);
                    return;
                }
            }
        }
        if (res == null) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[MonitorHandler] No response available (waitForResponse) for pattern='{}', url='{}'",
                    rule.getUrlPattern(), req.url());
            // 兜底放行
            RouteUtil.safeResume(route);
            return;
        }

        // ⭐ 生命周期契约容错：route 回调中 res.body() 在并发/连续导航场景下可能偶发返回
        //    null（响应体尚未缓冲就绪），直接丢弃会导致该 call 丢失（getAllResponsesForUrl 少一条）。
        //    改为带短重试的读取（非阻塞：用 CompletableFuture.delayedExecutor 调度退避，
        //    绝不 Thread.sleep 阻塞线程），应对 body 未就绪的瞬时竞态，避免捕获计数漂移。
        byte[] bodyBytes = readResponseBodyWithRetry(res, rule, req);
        if (bodyBytes == null) {
            LOGGER.debug("[MonitorHandler] Response body unavailable after retry for {}: pattern='{}'",
                    req.url(), rule.getUrlPattern());
            LoggingConfigUtil.logWarnIfVerbose(LOGGER,
                    "[MonitorHandler] Cannot read response body after retry: pattern='{}', url='{}'",
                    rule.getUrlPattern(), req.url());
            return;
        }

        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        String url = req.url();
        int status = res.status();
        String urlPattern = rule.getUrlPattern();

        LOGGER.info("[MonitorHandler] Captured: url={}, status={}, bodyLength={}, pattern='{}'",
                RouteUtil.sanitizeUrl(url), status, body.length(), urlPattern);

        // ⭐ 复用统一的「断言 + 记录」逻辑（ModifyHandler 叠加监控时也调用此方法）
        assertAndRecord(route, rule, context, url, status, body,
                req.method(), req.postData(),                 snapshotHeadersSafely(req.headers()),
                snapshotHeadersSafely(res.headers()));
    }

    /**
     * 带短重试地读取响应体，应对 route 回调中 res.body() 偶发返回 null（响应体尚未缓冲就绪）的情况。
     *
     * <p>生命周期契约容错：连续 page.navigate 到 API 端点或高并发下，Playwright 的
     * Response 对象偶发在 body 尚未就绪时被访问，res.body() 返回 null。简单地丢弃会导致
     * CapturedApiCall 漏存（触发调用方 getAllResponsesForUrl 计数漂移）。
     *
     * <p>线程契约：退避通过 {@link CompletableFuture#delayedExecutor} 调度到独立
     * {@link #bodyReadScheduler} 线程，当前 route 处理线程在退避期间不被 {@code Thread.sleep}
     * 阻塞（绝不占用线程等待），重试到期后再读取。最后一次成功或耗尽后通过 future 返回，
     * 调用方以 {@code join()} 获取结果（join 不阻塞线程池事件循环，仅当前任务等待自身结果）。
     *
     * @param res  响应对象
     * @param rule 路由规则（仅用于日志）
     * @param req  请求对象（仅用于日志）
     * @return 响应体字节；全部重试后仍不可用则返回 null
     */
    private static byte[] readResponseBodyWithRetry(Response res, RouteRule rule, Request req) {
        final int MAX_ATTEMPTS = 3;
        final long RETRY_INTERVAL_MS = 50;
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        retryBodyOnce(res, rule, req, 1, MAX_ATTEMPTS, RETRY_INTERVAL_MS, future);
        try {
            return future.join();
        } catch (Exception e) {
            future.cancel(true);
            return null;
        }
    }

    /** 递归异步重试读取 body：每次失败/空 body 后按固定间隔提交下一次读取（非阻塞），不占用当前线程。 */
    private static void retryBodyOnce(Response res, RouteRule rule, Request req,
                                       int attempt, int maxAttempts, long intervalMs,
                                       CompletableFuture<byte[]> result) {
        try {
            byte[] body = res.body();
            if (body != null) {
                // ⭐ 响应体上限防 OOM：超大响应体截断后再向上传递（监控存储/断言）
                result.complete(RouteUtil.truncateBody(body));
                return;
            }
        } catch (Exception e) {
            // Response 已失效：重试无意义，直接完成 null 由调用方决定降级
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[MonitorHandler] res.body() threw on attempt {}/{} for {}: {}",
                    attempt, maxAttempts, req.url(), e.getMessage());
            result.complete(null);
            return;
        }
        if (attempt < maxAttempts) {
            CompletableFuture.runAsync(
                    () -> retryBodyOnce(res, rule, req, attempt + 1, maxAttempts, intervalMs, result),
                    CompletableFuture.delayedExecutor(intervalMs, TimeUnit.MILLISECONDS, bodyReadScheduler));
        } else {
            result.complete(null);
        }
    }

    /**
     * ⭐ 统一的「断言 + 记录」逻辑：供 {@link #handle(Route, RouteRule)}（纯监控）
     * 与 {@link ModifyHandler}（修改请求后叠加监控）共同复用。
     *
     * <p>行为：
     * <ul>
     *   <li>在 Playwright 事件线程上<b>同步断言</b>（状态码 / JSONPath），失败 → Fail-Fast 中断测试</li>
     *   <li>响应体存储、CapturedApiCall 快照、Serenity 报告记录走 {@link RouteAsyncPool} 异步</li>
     * </ul>
     *
     * <p>⭐ 监控是<b>不可被覆盖的基线</b>：无论是否叠加 Modify/Delay，真实响应拿回后都会在此断言健康，
     * 断言失败即报错（对应「监控到 API 失败就报错」的诉求）。
     *
     * @param route        Playwright 路由对象（用于异常日志）
     * @param rule         路由规则（含断言配置）
     * @param context      当前 ApiCaptureContext（可为 null，null 时直接跳过）
     * @param url          实际请求 URL
     * @param status       HTTP 状态码（真实响应）
     * @param body         响应体（真实响应）
     * @param method       请求方法
     * @param reqBody      请求体
     * @param reqHeaders   请求头快照（线程安全副本）
     * @param resHeaders   响应头快照（线程安全副本）
     */
    public static void assertAndRecord(Route route, RouteRule rule, ApiCaptureContext context,
                                       String url, int status, String body,
                                       String method, String reqBody,
                                       Map<String, String> reqHeaders, Map<String, String> resHeaders) {
        String urlPattern = rule.getUrlPattern();

        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[MonitorHandler] Response headers: {}", resHeaders);
        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[MonitorHandler] Response body (first 500 chars): {}",
                body != null && body.length() > 500 ? body.substring(0, 500) + "..." : body);

        // ═══════════════════════════════════════════════════════════════
        // ⭐⭐⭐ 同步断言：在 Playwright 事件线程上立即执行
        // ═══════════════════════════════════════════════════════════════
        boolean assertionsPassed = executeAssertions(rule, url, status, body, context);
        if (!assertionsPassed) {
            LoggingConfigUtil.logErrorIfVerbose(LOGGER,
                    "[MonitorHandler] ═══ ASSERTIONS FAILED: pattern='{}', url='{}' ═══", urlPattern, url);
            if (context != null) {
                // ⭐⭐⭐ Fail-Fast（仅 interrupt，不关闭 Page）：
                //   关闭 page 会导致浏览器 context 状态损坏，后续 Scenario 无法继续执行。
                //   thread.interrupt() 已足够中断主线程当前阻塞的 Playwright IO 操作，
                //   通过 checkAndFailOnApiAssertions() 在步骤结束时统一抛 AssertionError。
                context.signalFailFast();
            }
            // ⭐ 抛出 ApiAssertionException，dispatchRoute 捕获后记录
            throw new RouteException.ApiAssertionException(
                    urlPattern, "ASSERTION",
                    rule.getExpectedStatus() != null ? String.valueOf(rule.getExpectedStatus()) : "N/A",
                    String.valueOf(status));
        }

        // ═══════════════════════════════════════════════════════════════
        // ⭐ 同步存储本调用（单一来源，覆盖所有 Page）：
        //   capture 目录的 CDP 旁路仅绑定启动时传入的 Page，对测试中新创建的
        //   Page（如跨层/Context 场景）捕获不到；且异步写出存在查询竞态。
        //   故此处同步 storeApiCall（可靠、即时可查），CDP 旁路对 MONITOR 请求跳过
        //   （RouteEngine.isSyncStoredRuleForUrl），避免重复存储。
        //   本方法其余部分仅负责：断言、匹配计数、回调、报告、持久化（同步，已删 RouteAsyncPool）。
        // ═══════════════════════════════════════════════════════════════
        if (context == null) return;
        // ⭐ 只构造一次 CapturedApiCall，同时用于 storeApiCall 与（断言失败时的）MonitorFailureCollector，
        //   消除重复构造（此前两处字段完全相同地 new 了一次）。
        CapturedApiCall captured = new CapturedApiCall(
                urlPattern, method, reqHeaders, status, resHeaders, body,
                System.currentTimeMillis(), url, reqBody);
        try {
            context.storeApiCall(captured);
        } catch (Exception e) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[MonitorHandler] Failed to store monitor call: {}", e.getMessage());
        }
        context.incrementActiveRequests();

        try {
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[MonitorHandler] Record START: pattern='{}', url='{}', status={}, bodyLen={}",
                    urlPattern, url, status, body.length());

            // 监控断言失败 → 归集到失败收集器（按 owner 去重，供 CI 邮件发送）
            if (context.hasAssertionFailures()) {
                for (ApiCaptureContext.AssertionFailureDetail d : context.getFailureDetails()) {
                    if (d.url.equals(url) || d.url.equals(urlPattern)) {
                        String owner = ApiMonitorOrchestrator.getInstance().getOwner(urlPattern);
                        String reason = String.format("%s expected=%s actual=%s (%s)",
                                d.assertionType, d.expectedValue, d.actualValue, d.failMessage);
                        MonitorFailureCollector.getInstance().record(captured, urlPattern, owner, reason);
                    }
                }
            }

            // 记录到 Serenity 报告
            if (rule.isRecord()) {
                SerenityReporter.recordApiOperation("MONITOR", url,
                        String.format("Status: %d\nBody: %s", status,
                                body.length() > 2000 ? body.substring(0, 2000) + "..." : body));
            }

            // 通知 RouteEngine 完成一次匹配（触发 auto-stop / minMatches 检查）
            RouteEngine.onMonitorMatch(rule);

            // ═══════════════════════════════════════════════════════════════
            // 执行用户注册的 Monitor 响应回调
            // ═══════════════════════════════════════════════════════════════
            invokeCallbacks(rule, url, status, body, resHeaders, method);

            // ═══════════════════════════════════════════════════════════════
            // 框架内置：根据配置自动决定是否持久化到数据库
            // 无需用户在业务层手动注册 DatabaseStoreMonitorCallback
            // ═══════════════════════════════════════════════════════════════
            DatabaseStoreMonitorCallback.INSTANCE.onResponse(url, status, body, resHeaders, method);

            // ═══════════════════════════════════════════════════════════════
            // 框架内置：根据配置自动决定是否将监控数据写入文件
            // ═══════════════════════════════════════════════════════════════
            FileStoreMonitorCallback.INSTANCE.onResponse(url, urlPattern, status, body, resHeaders, method);

            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[MonitorHandler] Record DONE: pattern='{}', url='{}'", urlPattern, url);

        } catch (Exception e) {
            LOGGER.error("[MonitorHandler] Error recording monitor match: {}", e.getMessage(), e);
        } finally {
            context.decrementActiveRequests();
        }
    }

    /**
     * 执行 RouteRule 中配置的断言（状态码 + JSONPath），
     * 失败时通过 {@code context} 记录详细信息。
     *
     * @param rule    路由规则
     * @param url     请求 URL
     * @param status  HTTP 状态码
     * @param body    响应 body
     * @param context ApiCaptureContext（可为 null）
     * @return true 所有断言通过，false 有断言失败
     */
    private static boolean executeAssertions(RouteRule rule, String url, int status,
                                              String body, ApiCaptureContext context) {
        boolean allPassed = true;

        // 状态码断言
        Integer expectedStatus = rule.getExpectedStatus();
        if (expectedStatus != null) {
            boolean statusMatch = (status == expectedStatus);
            if (!statusMatch) {
                LOGGER.warn("[MonitorHandler] Status assertion failed for {}: expected={}, actual={}",
                        url, expectedStatus, status);
                if (context != null) {
                    context.recordAssertionFailure(url, "STATUS",
                            String.valueOf(expectedStatus), String.valueOf(status),
                            null);
                }
                allPassed = false;
            } else {
                LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                        "[MonitorHandler] Status assertion PASSED: {}, expected={}, actual={}",
                        url, expectedStatus, status);
            }
        }

        // JSONPath 断言（使用缓存编译）
        Map<String, Object> jsonPathAssertions = rule.getJsonPathAssertions();
        if (jsonPathAssertions != null && !jsonPathAssertions.isEmpty()) {
            for (Map.Entry<String, Object> entry : jsonPathAssertions.entrySet()) {
                String jsonPathExpr = entry.getKey();
                try {
                    // ⭐ 从缓存获取或编译 JsonPath（避免每次重新编译）
                    JsonPath compiled = getOrCompileJsonPath(jsonPathExpr);
                    Object actual = compiled.read(body);
                    boolean match = compareValues(actual, entry.getValue());
                    if (!match) {
                        String actualStr = actual != null ? actual.toString() : "null";
                        LOGGER.warn("[MonitorHandler] JSONPath assertion failed for {}: path={}, expected={}, actual={}",
                                url, jsonPathExpr, entry.getValue(), actualStr);
                        if (context != null) {
                            context.recordAssertionFailure(url, "JSONPATH",
                                    entry.getValue() != null ? entry.getValue().toString() : "null",
                                    actualStr,
                                    "path=" + jsonPathExpr);
                        }
                        allPassed = false;
                    } else {
                        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                                "[MonitorHandler] JSONPath assertion PASSED: {}, path='{}', expected='{}', actual='{}'",
                                url, jsonPathExpr, entry.getValue(), actual);
                    }
                } catch (Exception e) {
                    LOGGER.warn("[MonitorHandler] JSONPath evaluation error for {}: path={}, error={}",
                            url, jsonPathExpr, e.getMessage(), e);
                    if (context != null) {
                        context.recordAssertionFailure(url, "JSONPATH",
                                entry.getValue() != null ? entry.getValue().toString() : "null",
                                "ERROR",
                                "path=" + jsonPathExpr + ", error=" + e.getMessage());
                    }
                    allPassed = false;
                }
            }
        }

        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[MonitorHandler] executeAssertions RESULT: allPassed={}, url={}, pattern='{}'",
                allPassed, url, rule.getUrlPattern());
        return allPassed;
    }

    /**
     * 从缓存获取或编译 JsonPath 表达式（容量保护）。
     */
    private static JsonPath getOrCompileJsonPath(String expression) {
        // ⭐ 单次原子查找+编译：computeIfAbsent 仅在 miss 时编译，避免先 get 再 put 的二次查找。
        JsonPath cached = JSONPATH_CACHE.get(expression);
        if (cached != null) {
            return cached;
        }
        // 容量保护：超限时淘汰约 1/4 旧条目（复用 RouteUtil 统一实现，避免命中率瞬间归零）
        if (JSONPATH_CACHE.size() >= JSONPATH_CACHE_MAX) {
            RouteUtil.evictOldestQuarter(JSONPATH_CACHE);
        }
        return JSONPATH_CACHE.computeIfAbsent(expression, RouteUtil::compileJsonPath);
    }

    /**
     * 值比较（支持 Number 类型的松散比较，使用 epsilon 避免浮点精度问题）。
     */
    private static boolean compareValues(Object actual, Object expected) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;

        if (actual instanceof Number && expected instanceof Number) {
            double a = ((Number) actual).doubleValue();
            double e = ((Number) expected).doubleValue();
            // ⭐ 使用 epsilon 比较，避免 0.1+0.2 != 0.3 等浮点精度问题
            double epsilon = 1e-9;
            boolean match = Math.abs(a - e) < epsilon;
            LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                    "[MonitorHandler] compareValues (Number): actual={}, expected={}, match={}",
                    a, e, match);
            return match;
        }

        boolean match = actual.toString().equals(expected.toString());
        LoggingConfigUtil.logTraceIfVerbose(LOGGER,
                "[MonitorHandler] compareValues (String): actual='{}', expected='{}', match={}",
                actual.toString(), expected.toString(), match);
        return match;
    }

    /**
     * 安全快照 Playwright headers 对象（避免跨线程访问）。
     * 复制为普通 HashMap，与 Playwright 事件线程解耦。
     */
    private static Map<String, String> snapshotHeadersSafely(Map<String, String> headers) {
        if (headers == null) return null;
        try {
            return new java.util.HashMap<>(headers);
        } catch (Exception e) {
            LOGGER.warn("[MonitorHandler] Failed to snapshot headers: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 调用 RouteRule 中注册的所有 Monitor 响应回调。
     * <p>每个回调独立 try-catch，单个回调失败不影响其他回调执行。
     *
     * @param rule            路由规则
     * @param url             请求 URL
     * @param status          HTTP 状态码
     * @param body            响应体字符串
     * @param responseHeaders 响应头快照（线程安全的 Map 副本）
     * @param method          请求方法
     */
    private static void invokeCallbacks(RouteRule rule, String url, int status,
                                         String body, Map<String, String> responseHeaders,
                                         String method) {
        java.util.List<MonitorCallback> callbacks = rule.getMonitorCallbacks();
        if (callbacks == null || callbacks.isEmpty()) return;

        LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                "[MonitorHandler] Invoking {} monitor callback(s) for pattern='{}', url='{}'",
                callbacks.size(), rule.getUrlPattern(), url);

        for (int i = 0; i < callbacks.size(); i++) {
            try {
                callbacks.get(i).onResponse(url, status, body, responseHeaders, method);
            } catch (Exception e) {
                LOGGER.error("[MonitorHandler] Monitor callback #{} failed for pattern='{}', url='{}': {}",
                        i, rule.getUrlPattern(), url, e.getMessage(), e);
            }
        }
    }

}
