package com.hsbc.cmb.hk.dbb.automation.framework.web.route.dsl;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.BrowserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 路由 DSL（领域特定语言）— 流式 API 构建路由规则。
 *
 * <p>核心改进：RouteDsl 持有上下文引用，{@link #start()} 无需重复传入 Page/Context。
 *
 * <p>使用示例：
 * <pre>{@code
 * RouteDsl.on(page)
 *     .api("/api/users")
 *     .monitor()
 *     .expectStatus(200)
 *     .done()
 *     .start();  // 无需再传 page
 * }</pre>
 */
public class RouteDsl {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteDsl.class);

    /** 绑定的上下文（Page 或 BrowserContext） */
    private final Object context;

    /** 规则列表（使用 CopyOnWriteArrayList 支持并发修改，防止内存泄漏） */
    private final List<RouteRule> rules = new CopyOnWriteArrayList<>();

    private RouteDsl(Object context) {
        this.context = context;
    }

    /**
     * 创建绑定到 Page 的 DSL 实例。
     */
    public static RouteDsl on(Page page) {
        return new RouteDsl(page);
    }

    /**
     * 创建绑定到 BrowserContext 的 DSL 实例。
     */
    public static RouteDsl on(BrowserContext context) {
        return new RouteDsl(context);
    }

    /**
     * 开始配置一个 API 规则。
     *
     * @param urlPattern URL pattern（如 "/api/users/**"）
     * @return ApiDsl 流式构建器
     */
    public ApiDsl api(String urlPattern) {
        return new ApiDsl(this, urlPattern);
    }

    /**
     * 启动路由注册 — 无需再传入上下文（使用构造时的绑定）。
     */
    public void start() {
        if (rules.isEmpty()) {
            LOGGER.debug("[RouteDsl] No rules to register, skipping start()");
            return;
        }
        RouteEngine.register(context, rules);
    }

    /**
     * 获取绑定的上下文。
     */
    public Object getContext() {
        return context;
    }

    /**
     * 注销所有已注册的 pattern 并清空规则（测试结束时调用）。
     */
    public void clear() {
        RouteRegistry.clearContext(context);
        rules.clear();
    }

    // ==================== 内部类 ====================

    /**
     * API DSL — 提供 Monitor / Mock / Modify 的链式配置方法。
     */
    public static class ApiDsl {

        private final RouteDsl parent;
        private final RouteRule rule;

        ApiDsl(RouteDsl parent, String urlPattern) {
            this.parent = parent;
            this.rule = new RouteRule();
            this.rule.setUrlPattern(urlPattern);
            this.rule.setType(RouteHandleType.MONITOR);
        }

        // ===== 监控 =====
        public ApiDsl monitor() {
            rule.setType(RouteHandleType.MONITOR);
            return this;
        }

        public ApiDsl record(boolean enable) {
            rule.setRecord(enable);
            return this;
        }

        /**
         * 设置 Monitor 超时（毫秒）。0 表示永不超时。
         * 超时后自动停止监控（调用 unroute 注销路由）。
         */
        public ApiDsl timeout(long timeoutMs) {
            rule.setTimeoutMs(timeoutMs);
            return this;
        }

        /**
         * 设置最小匹配次数，满足后触发 auto-stop。
         * 默认值为 1。
         */
        public ApiDsl minMatches(int minMatches) {
            rule.setMinMatches(minMatches);
            return this;
        }

        /**
         * 目标匹配后是否自动停止。
         * <ul>
         *   <li>MONITOR — 默认 {@code true}：达到 minMatches 后自动 unroute</li>
         *   <li>MOCK   — 默认 {@code false}：持续拦截直到超时或测试结束</li>
         *   <li>MODIFY — 默认 {@code false}：持续拦截直到超时或测试结束</li>
         * </ul>
         */
        public ApiDsl autoStopOnMatch(boolean autoStopOnMatch) {
            rule.setAutoStopOnMatch(autoStopOnMatch);
            return this;
        }

        // ===== 断言 =====
        public ApiDsl expectStatus(int status) {
            rule.setExpectedStatus(status);
            return this;
        }

        public ApiDsl expectJsonPath(String jsonPath, Object expectedValue) {
            Map<String, Object> assertions = rule.getJsonPathAssertions();
            if (assertions == null) {
                assertions = new java.util.HashMap<>();
                rule.setJsonPathAssertions(assertions);
            }
            assertions.put(jsonPath, expectedValue);
            return this;
        }

        // ===== Mock =====
        public ApiDsl mock(String body) {
            rule.setType(RouteHandleType.MOCK);
            rule.setMockBody(body);
            rule.setAutoStopOnMatch(false);   // Mock 默认持续拦截，不自动停止
            return this;
        }

        public ApiDsl mockStatus(int status) {
            rule.setMockStatus(status);
            return this;
        }

        public ApiDsl mockHeader(String key, String value) {
            Map<String, String> headers = rule.getMockHeaders();
            if (headers == null) {
                headers = new java.util.HashMap<>();
                rule.setMockHeaders(headers);
            }
            headers.put(key, value);
            return this;
        }

        // ===== Modify =====
        public ApiDsl modify() {
            rule.setType(RouteHandleType.MODIFY);
            rule.setAutoStopOnMatch(false);   // Modify 默认持续拦截，不自动停止
            return this;
        }

        public ApiDsl addHeader(String key, String value) {
            Map<String, String> headers = rule.getAddHeaders();
            if (headers == null) {
                headers = new java.util.HashMap<>();
                rule.setAddHeaders(headers);
            }
            headers.put(key, value);
            return this;
        }

        public ApiDsl replaceBody(String key, String value) {
            rule.addReplaceBodyPair(key, value);
            return this;
        }

        public ApiDsl method(String method) {
            rule.setMethod(method);
            return this;
        }

        // ================================================================
        // 请求条件匹配 DSL（新增）
        // ================================================================

        /**
         * 设置匹配的 HTTP Method（如 "GET","POST"）。
         * <p>不调用则匹配所有 Method。
         */
        public ApiDsl matchMethod(String method) {
            rule.setMatchMethod(method);
            return this;
        }

        /**
         * 设置允许匹配的资源类型（逗号分隔）。
         * <p>例如 {@code "xhr,fetch"} 只匹配 XHR 和 Fetch。
         * <p>不调用则继承 {@link #onlyApiCall(boolean)} 的默认行为。
         */
        public ApiDsl resourceType(String types) {
            rule.setResourceTypes(types);
            return this;
        }

        /**
         * 只匹配 XHR 请求。
         */
        public ApiDsl onlyXhr() {
            rule.setResourceTypes(RouteUtil.RT_XHR);
            return this;
        }

        /**
         * 只匹配 Fetch 请求。
         */
        public ApiDsl onlyFetch() {
            rule.setResourceTypes(RouteUtil.RT_FETCH);
            return this;
        }

        /**
         * 添加一个请求头匹配条件（精确匹配）。
         * <p>所有添加的 header 必须同时满足。
         */
        public ApiDsl matchHeader(String key, String value) {
            rule.addMatchHeader(key, value);
            return this;
        }

        /**
         * 添加一个 Query 参数匹配条件（精确匹配）。
         * <p>所有添加的 query 参数必须同时满足。
         */
        public ApiDsl matchQuery(String key, String value) {
            rule.addMatchQuery(key, value);
            return this;
        }

        /**
         * 设置请求体正则表达式匹配。
         * <p>只有当 POST/PUT 请求的 body 匹配此正则时才拦截。
         *
         * @param regex Java 正则表达式
         */
        public ApiDsl matchBodyRegex(String regex) {
            rule.setMatchBodyRegex(regex);
            return this;
        }

        /**
         * 设置 Content-Type 包含匹配。
         * <p>例如 {@code "json"} 可匹配 {@code "application/json"} 和
         * {@code "application/json;charset=UTF-8"}。
         */
        public ApiDsl matchContentType(String contentType) {
            rule.setMatchContentType(contentType);
            return this;
        }

        /**
         * 设置 Referrer 包含匹配。
         * <p>请求的 Referrer 头必须包含指定字符串。
         */
        public ApiDsl matchReferrer(String referrer) {
            rule.setMatchReferrer(referrer);
            return this;
        }

        /**
         * 设置 Origin 包含匹配。
         * <p>请求的 Origin 头必须包含指定字符串。
         */
        public ApiDsl matchOrigin(String origin) {
            rule.setMatchOrigin(origin);
            return this;
        }

        /**
         * 设置 Frame URL 包含匹配。
         * <p>发起请求的 Frame URL 必须包含指定字符串。
         */
        public ApiDsl matchFrameUrl(String frameUrl) {
            rule.setMatchFrameUrl(frameUrl);
            return this;
        }

        /**
         * 是否仅匹配主 Frame 请求（跳过 iframe/worker）。
         * 默认 true。
         */
        public ApiDsl onlyMainFrame(boolean onlyMainFrame) {
            rule.setOnlyMainFrame(onlyMainFrame);
            return this;
        }

        /**
         * 允许匹配所有 Frame（包括 iframe/worker）。
         */
        public ApiDsl allowAllFrames() {
            rule.setOnlyMainFrame(false);
            return this;
        }

        /**
         * 是否仅匹配 API 调用（自动跳过 navigation 和静态资源）。
         * <p>设为 true 时：
         * <ul>
         *   <li>跳过 isNavigationRequest=true 的请求</li>
         *   <li>如果没有显式设置 resourceType，则只匹配 xhr/fetch</li>
         * </ul>
         * 默认 false（匹配所有请求类型）。
         */
        public ApiDsl onlyApiCall(boolean apiOnly) {
            rule.setOnlyApiCall(apiOnly);
            return this;
        }

        /**
         * 允许匹配所有类型请求（包括 navigation、静态资源、image、font 等）。
         * <p>等同于 {@code onlyApiCall(false).allowAllFrames()}。
         */
        public ApiDsl allowAllRequests() {
            rule.setOnlyApiCall(false);
            rule.setOnlyMainFrame(false);
            return this;
        }

        /**
         * 完成当前 API 配置，返回父级 RouteDsl。
         *
         * <p>校验必填字段：urlPattern 必须非空。
         *
         * @return 父级 RouteDsl 实例
         * @throws IllegalStateException 如果 urlPattern 为空
         */
        public RouteDsl done() {
            if (rule.getUrlPattern() == null || rule.getUrlPattern().trim().isEmpty()) {
                throw new IllegalStateException("[RouteDsl] urlPattern cannot be blank. "
                        + "Please call api(\"pattern\") before done().");
            }
            parent.rules.add(rule);
            return parent;
        }
    }
}
