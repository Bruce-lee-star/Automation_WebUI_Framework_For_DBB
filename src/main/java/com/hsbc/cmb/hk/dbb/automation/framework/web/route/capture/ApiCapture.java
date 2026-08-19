package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
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
 *   <li>跨页面不掉链子（页面切换时自动迁移采集）</li>
 * </ul>
 */
public class ApiCapture {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiCapture.class);

    /** 全局采集引擎实例 */
    private static volatile CaptureEngine engine;

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
        // 幂等处理：如果已有引擎在运行，先停止它
        CaptureEngine old = engine;
        if (old != null) {
            if (old.isRunning()) {
                LOGGER.warn("[ApiCapture] Already started, stopping previous engine first");
                RouteEngine.setCaptureEngine(null);
                old.shutdown();
            }
            engine = null;
        }
        engine = new CaptureEngine(page);
        // 与 RouteEngine 集成：采集引擎接收 MOCK/MODIFY 路由事件
        RouteEngine.setCaptureEngine(engine);
        LOGGER.info("[ApiCapture] Started with strategy '{}'", engine.strategyName());
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
        CaptureEngine e = engine;
        if (e == null) return;
        engine = null;
        // 断开与 RouteEngine 的集成
        RouteEngine.setCaptureEngine(null);
        e.shutdown();
        LOGGER.info("[ApiCapture] Stopped");
    }

    /**
     * 是否正在采集。
     */
    public static boolean isActive() {
        return engine != null && engine.isRunning();
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

    // ═══════════════════════════════════════════════════════════
    // 查询
    // ═══════════════════════════════════════════════════════════

    /**
     * 获取所有已采集的 API 调用。
     *
     * @return 所有 API 调用（按 endpoint 分组）
     */
    public static java.util.Map<String, List<CapturedApiCall>> getAll() {
        return ApiCaptureContext.getCurrent().getAllApiCalls();
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

    /**
     * 获取采集引擎的运行时指标。
     */
    public static CaptureMetrics metrics() {
        CaptureEngine e = engine;
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

            // 1. 精确匹配（限定在当前步骤窗口内，R4）
            CapturedApiCall call = ctx.getLastApiCallSinceStepStart(urlPattern);
            if (call != null) return call;

            // 2. 通配符匹配：仅遍历当前步骤窗口内的调用（R3/R4）
            //    避免命中上一步遗留调用或错误 endpoint，消除"全局最新时间戳"误匹配。
            java.util.Map<String, List<CapturedApiCall>> all = ctx.getAllApiCalls();
            CapturedApiCall best = null;
            long bestTimestamp = 0;
            long stepStart = ctx.getStepStartTimestamp();
            for (java.util.Map.Entry<String, List<CapturedApiCall>> e : all.entrySet()) {
                if (regex.matcher(e.getKey()).matches()) {
                    List<CapturedApiCall> calls = e.getValue();
                    if (calls != null && !calls.isEmpty()) {
                        // 仅考虑本步骤窗口内的调用
                        CapturedApiCall last = calls.get(calls.size() - 1);
                        if (stepStart != 0L && last.timestamp() < stepStart) continue;
                        if (last.timestamp() > bestTimestamp) {
                            best = last;
                            bestTimestamp = last.timestamp();
                        }
                    }
                }
            }
            return best;
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