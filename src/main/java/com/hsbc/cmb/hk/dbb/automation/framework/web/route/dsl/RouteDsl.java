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
 * // Monitor
 * RouteDsl.on(page)
 *     .api("/api/users")
 *     .monitor()
 *     .expectStatus(200)
 *     .expectJsonPath("$.code", 200)
 *     .timeout(30)
 *     .done()
 *     .start();
 *
 * // Mock
 * RouteDsl.on(page)
 *     .api("/api/users/1")
 *     .mock("{\"code\":200}")
 *     .mockStatus(200)
 *     .done()
 *     .start();
 *
 * // Modify
 * RouteDsl.on(page)
 *     .api("/api/users")
 *     .modifyRequest()
 *     .modifyRequestBody("$.role", "ADMIN")
 *     .modifyMethod("PUT")
 *     .done()
 *     .start();
 *
 * // Delay (弱网模拟)
 * RouteDsl.on(page)
 *     .api("/api/**")
 *     .delay(3)          // 拦截所有 API，延迟 3 秒后放行
 *     .done()
 *     .start();
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

    // ==================== 入口点 — 仅提供三个分支方法 ====================

    /**
     * API DSL 入口 — 创建规则，提供 {@code monitor() / mock() / modifyRequest() / delay()} 四个分支。
     * <p>调用任一分支后，返回对应类型的子 DSL（不再可回退）。
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

        /**
         * 切换到 Monitor 监控模式。
         * <p>Monitor 默认在匹配后自动停止（autoStopOnMatch=true）。
         *
         * @return MonitorApiDsl — 仅可调用 Monitor 相关方法 + 公共方法
         */
        public MonitorApiDsl monitor() {
            rule.setType(RouteHandleType.MONITOR);
            return new MonitorApiDsl(parent, rule);
        }

        /**
         * 切换到 Mock 拦截并自定义响应模式。
         * <p>Mock 默认持续拦截，不自动停止（autoStopOnMatch=false）。
         *
         * @param body Mock 响应体
         * @return MockApiDsl — 仅可调用 Mock 相关方法 + 公共方法
         */
        public MockApiDsl mock(String body) {
            rule.setType(RouteHandleType.MOCK);
            rule.setMockBody(body);
            rule.setAutoStopOnMatch(false);
            return new MockApiDsl(parent, rule);
        }

        /**
         * 切换到 Modify 修改请求模式（增删改请求头/请求体/请求方法）。
         * <p>Modify 默认持续拦截，不自动停止（autoStopOnMatch=false）。
         *
         * @return ModifyApiDsl — 仅可调用 Modify 相关方法 + 公共方法
         */
        public ModifyApiDsl modifyRequest() {
            rule.setType(RouteHandleType.MODIFY);
            rule.setAutoStopOnMatch(false);
            return new ModifyApiDsl(parent, rule);
        }

        /**
         * 切换到 Delay 弱网延迟模式。
         *
         * <p>拦截匹配请求，通过 {@code route.fetch()} 获取真实服务端响应，
         * 经过指定延迟后再返回给浏览器，真实模拟高延迟/弱网环境。
         *
         * <p>与 BaseApiDsl 中其他类型的 delay 属性不同：
         * 此模式先获取真实服务端数据，再模拟网络延迟，
         * 而 delay 属性是在 handler 执行前附加的前置延迟。
         *
         * <p>Delay 默认持续拦截所有匹配请求，不自动停止（autoStopOnMatch=false）。
         *
         * @param delaySecs 延迟秒数，必须 ≥ 0
         * @return DelayApiDsl — 可调用 {@link DelayApiDsl#randomDelay(long, long)} 切换随机模式
         */
        public DelayApiDsl delay(long delaySecs) {
            rule.setType(RouteHandleType.DELAY);
            rule.setDelayMs(delaySecs * 1000);
            rule.setAutoStopOnMatch(false);
            return new DelayApiDsl(parent, rule);
        }
    }

    // ==================== 公共基类 ====================

    /**
     * 所有分支子 DSL 的公共基类。
     * <p>包含超时、条件匹配、完成注册等通用方法。
     *
     * @param <T> 子类型（用于链式调用返回正确类型）
     */
    public abstract static class BaseApiDsl<T extends BaseApiDsl<T>> {

        protected final RouteDsl parent;
        protected final RouteRule rule;

        BaseApiDsl(RouteDsl parent, RouteRule rule) {
            this.parent = parent;
            this.rule = rule;
        }

        @SuppressWarnings("unchecked")
        protected T self() {
            return (T) this;
        }

        // ===== 自动停止控制 =====

        /**
         * 设置 Monitor 超时（秒）。0 表示永不超时。
         * 超时后自动停止监控（调用 unroute 注销路由）。
         */
        public T timeout(long timeoutSecs) {
            rule.setTimeoutMs(timeoutSecs * 1000);
            return self();
        }

        /**
         * 设置最小匹配次数，满足后触发 auto-stop。
         * 默认值为 1。
         */
        public T minMatches(int minMatches) {
            rule.setMinMatches(minMatches);
            return self();
        }

        /**
         * 目标匹配后是否自动停止。
         * <ul>
         *   <li>MONITOR — 默认 {@code true}：达到 minMatches 后自动 unroute</li>
         *   <li>MOCK   — 默认 {@code false}：持续拦截直到超时或测试结束</li>
         *   <li>MODIFY — 默认 {@code false}：持续拦截直到超时或测试结束</li>
         * </ul>
         */
        public T autoStopOnMatch(boolean autoStopOnMatch) {
            rule.setAutoStopOnMatch(autoStopOnMatch);
            return self();
        }

        // ===== 请求条件匹配 =====

        /**
         * 设置匹配的 HTTP Method（如 "GET","POST"）。
         * <p>不调用则匹配所有 Method。
         */
        public T matchMethod(String method) {
            rule.setMatchMethod(method);
            return self();
        }

        /**
         * 设置允许匹配的资源类型（逗号分隔）。
         * <p>例如 {@code "xhr,fetch"} 只匹配 XHR 和 Fetch。
         * <p>不调用则继承 {@link #onlyApiCall(boolean)} 的默认行为。
         */
        public T resourceType(String types) {
            rule.setResourceTypes(types);
            return self();
        }

        /**
         * 只匹配 XHR 请求。
         */
        public T onlyXhr() {
            rule.setResourceTypes(RouteUtil.RT_XHR);
            return self();
        }

        /**
         * 只匹配 Fetch 请求。
         */
        public T onlyFetch() {
            rule.setResourceTypes(RouteUtil.RT_FETCH);
            return self();
        }

        /**
         * 添加一个请求头匹配条件（精确匹配）。
         * <p>所有添加的 header 必须同时满足。
         */
        public T matchHeader(String key, String value) {
            rule.addMatchHeader(key, value);
            return self();
        }

        /**
         * 添加一个 Query 参数匹配条件（精确匹配）。
         * <p>所有添加的 query 参数必须同时满足。
         */
        public T matchQuery(String key, String value) {
            rule.addMatchQuery(key, value);
            return self();
        }

        /**
         * 设置请求体正则表达式匹配。
         * <p>只有当 POST/PUT 请求的 body 匹配此正则时才拦截。
         *
         * @param regex Java 正则表达式
         */
        public T matchBodyRegex(String regex) {
            rule.setMatchBodyRegex(regex);
            return self();
        }

        /**
         * 设置 Content-Type 包含匹配。
         * <p>例如 {@code "json"} 可匹配 {@code "application/json"} 和
         * {@code "application/json;charset=UTF-8"}。
         */
        public T matchContentType(String contentType) {
            rule.setMatchContentType(contentType);
            return self();
        }

        /**
         * 设置 Referrer 包含匹配。
         * <p>请求的 Referrer 头必须包含指定字符串。
         */
        public T matchReferrer(String referrer) {
            rule.setMatchReferrer(referrer);
            return self();
        }

        /**
         * 设置 Origin 包含匹配。
         * <p>请求的 Origin 头必须包含指定字符串。
         */
        public T matchOrigin(String origin) {
            rule.setMatchOrigin(origin);
            return self();
        }

        /**
         * 设置 Frame URL 包含匹配。
         * <p>发起请求的 Frame URL 必须包含指定字符串。
         */
        public T matchFrameUrl(String frameUrl) {
            rule.setMatchFrameUrl(frameUrl);
            return self();
        }

        /**
         * 是否仅匹配主 Frame 请求（跳过 iframe/worker）。
         * 默认 true。
         */
        public T onlyMainFrame(boolean onlyMainFrame) {
            rule.setOnlyMainFrame(onlyMainFrame);
            return self();
        }

        /**
         * 允许匹配所有 Frame（包括 iframe/worker）。
         */
        public T allowAllFrames() {
            rule.setOnlyMainFrame(false);
            return self();
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
        public T onlyApiCall(boolean apiOnly) {
            rule.setOnlyApiCall(apiOnly);
            return self();
        }

        /**
         * 允许匹配所有类型请求（包括 navigation、静态资源、image、font 等）。
         * <p>等同于 {@code onlyApiCall(false).allowAllFrames()}。
         */
        public T allowAllRequests() {
            rule.setOnlyApiCall(false);
            rule.setOnlyMainFrame(false);
            return self();
        }

        // ===== 完成 =====

        /**
         * 完成当前 API 配置，返回父级 RouteDsl。
         *
         * <p>校验必填字段：urlPattern 必须非空。
         *
         * @return 父级 RouteDsl 实例
         * @throws IllegalArgumentException 如果 urlPattern 为空
         */
        public RouteDsl done() {
            if (rule.getUrlPattern() == null || rule.getUrlPattern().trim().isEmpty()) {
                throw new IllegalArgumentException("urlPattern cannot be blank. "
                        + "Please call api(\"pattern\") before done().");
            }
            parent.rules.add(rule);
            return parent;
        }
    }

    // ==================== Monitor 专用 DSL ====================

    /**
     * Monitor 专用 DSL — 在 BaseApiDsl 基础上提供断言方法。
     * <p>使用示例：
     * <pre>{@code
     * .api("/api/users")
     *     .monitor()
     *     .expectStatus(200)
     *     .expectJsonPath("$.code", 200)
     *     .timeout(30)
     *     .done()
     * }</pre>
     */
    public static class MonitorApiDsl extends BaseApiDsl<MonitorApiDsl> {

        MonitorApiDsl(RouteDsl parent, RouteRule rule) {
            super(parent, rule);
        }

        /**
         * 设置是否记录请求/响应信息。默认 true。
         */
        public MonitorApiDsl record(boolean enable) {
            rule.setRecord(enable);
            return this;
        }

        /**
         * 设置期望的 HTTP 状态码（Monitor 断言）。
         */
        public MonitorApiDsl expectStatus(int status) {
            rule.setExpectedStatus(status);
            return this;
        }

        /**
         * 添加 JSONPath 断言（值类型自动推断：Int/Double/Boolean/String）。
         */
        public MonitorApiDsl expectJsonPath(String jsonPath, Object expectedValue) {
            Map<String, Object> assertions = rule.getJsonPathAssertions();
            if (assertions == null) {
                assertions = new java.util.HashMap<>();
                rule.setJsonPathAssertions(assertions);
            }
            assertions.put(jsonPath, expectedValue);
            return this;
        }
    }

    // ==================== Mock 专用 DSL ====================

    /**
     * Mock 专用 DSL — 在 BaseApiDsl 基础上提供 Mock 响应配置方法。
     * <p>使用示例：
     * <pre>{@code
     * .api("/api/users/1")
     *     .mock("{\"code\":200}")
     *     .mockStatus(200)
     *     .mockHeader("Content-Type", "application/json")
     *     .mockReplaceField("$.data.name", "Mocked")
     *     .done()
     * }</pre>
     */
    public static class MockApiDsl extends BaseApiDsl<MockApiDsl> {

        MockApiDsl(RouteDsl parent, RouteRule rule) {
            super(parent, rule);
        }

        /**
         * 设置/更新 Mock 响应体。
         * <p>通常 body 已在入口 {@code api(...).mock(body)} 设置，此方法用于后续更新。
         */
        public MockApiDsl mockBody(String body) {
            rule.setMockBody(body);
            return this;
        }

        /**
         * 设置 Mock 返回的 HTTP 状态码（默认 200）。
         */
        public MockApiDsl mockStatus(int status) {
            rule.setMockStatus(status);
            return this;
        }

        /**
         * 设置 Mock 响应头。
         * <p>可多次调用以设置多个响应头。
         */
        public MockApiDsl mockHeader(String key, String value) {
            Map<String, String> headers = rule.getMockHeaders();
            if (headers == null) {
                headers = new java.util.HashMap<>();
                rule.setMockHeaders(headers);
            }
            headers.put(key, value);
            return this;
        }

        /**
         * 对 Mock 响应的 JSON body 进行批量字段替换。
         *
         * <p>支持通配符 {@code [*]} 批量替换 List 中所有元素的字段，<b>支持嵌套 List</b>。
         * 可多次调用以设置多个字段。
         *
         * <p>路径示例：
         * <pre>{@code
         * // 替换顶层字段
         * .mockReplaceField("$.status", "ok")
         *
         * // 批量替换 List 中所有元素的字段
         * .mockReplaceField("$[*].name", "NewName")
         * .mockReplaceField("$[*].active", "true")
         *
         * // 嵌套 List：替换 users 数组中每个元素的 orders 数组中的 price 字段
         * .mockReplaceField("$.users[*].orders[*].price", "99.99")
         *
         * // 子路径嵌套：替换 data.users 数组中 email 字段
         * .mockReplaceField("$.data.users[*].email", "modified@test.com")
         * }</pre>
         *
         * @param jsonPath JSONPath 表达式（支持通配符 [*]）
         * @param value    替换值（字符串形式，自动保持原字段类型）
         */
        public MockApiDsl mockReplaceField(String jsonPath, String value) {
            rule.addMockReplaceField(jsonPath, value);
            return this;
        }
    }

    // ==================== Modify 专用 DSL ====================

    /**
     * Modify 专用 DSL — 在 BaseApiDsl 基础上提供请求修改的增删改方法。
     * <p>使用示例：
     * <pre>{@code
     * .api("/api/users")
     *     .modifyRequest()
     *     .setRequestHeader("X-Custom", "v1")
     *     .removeRequestHeader("X-Obsolete")
     *     .modifyRequestBody("$.role", "ADMIN")
     *     .addRequestBodyField("$.newField", "hello")
     *     .removeRequestBodyField("$.deprecated")
     *     .modifyMethod("PUT")
     *     .done()
     * }</pre>
     */
    public static class ModifyApiDsl extends BaseApiDsl<ModifyApiDsl> {

        ModifyApiDsl(RouteDsl parent, RouteRule rule) {
            super(parent, rule);
        }

        /**
         * 设置/新增单个请求头（覆盖已有同名头）。
         */
        public ModifyApiDsl setRequestHeader(String key, String value) {
            rule.addRequestHeaderToSet(key, value);
            return this;
        }

        /**
         * 批量设置请求头。
         * <pre>{@code
         * .setRequestHeaders(Map.of("X-Custom-1", "v1", "X-Custom-2", "v2"))
         * }</pre>
         */
        public ModifyApiDsl setRequestHeaders(Map<String, String> headers) {
            rule.addRequestHeadersToSet(headers);
            return this;
        }

        /**
         * 删除指定请求头。
         * <p>多次调用可删除多个请求头。
         */
        public ModifyApiDsl removeRequestHeader(String key) {
            rule.addRequestHeaderToRemove(key);
            return this;
        }

        /**
         * 修改请求体已有字段值（JSONPath 精准替换）。
         * <p>仅修改已存在的字段，路径不存在则跳过。
         */
        public ModifyApiDsl modifyRequestBody(String jsonPath, String value) {
            rule.addRequestBodyFieldToModify(jsonPath, value);
            return this;
        }

        /**
         * 新增请求体字段（JSONPath → 值）。
         * <p>路径不存在时自动创建中间节点。
         */
        public ModifyApiDsl addRequestBodyField(String jsonPath, String value) {
            rule.addRequestBodyFieldToAdd(jsonPath, value);
            return this;
        }

        /**
         * 删除请求体指定字段。
         * <p>多次调用可删除多个字段。
         */
        public ModifyApiDsl removeRequestBodyField(String jsonPath) {
            rule.addRequestBodyFieldToRemove(jsonPath);
            return this;
        }

        /**
         * 修改请求 HTTP 方法。
         * @param method 如 "POST","PUT","PATCH","DELETE"
         */
        public ModifyApiDsl modifyMethod(String method) {
            rule.setModifyMethod(method);
            return this;
        }
    }

    // ==================== Delay 专用 DSL ====================

    /**
     * Delay 弱网延迟专用 DSL — 在 BaseApiDsl 基础上提供延迟配置方法。
     *
     * <p>DELAY 类型通过 {@code route.fetch()} 获取真实服务端响应，
     * 经配置的延迟后再通过 {@code route.fulfill()} 返回给浏览器，
     * 真实模拟高延迟/弱网环境下的 API 响应行为。
     *
     * <p>支持两种延迟模式：
     * <ul>
     *   <li><b>固定延迟</b>：每个请求都延迟相同的秒数</li>
     *   <li><b>随机延迟</b>：每次请求在 [min, max] 秒范围内随机取值，模拟不稳定弱网</li>
     * </ul>
     *
     * <p>使用示例：
     * <pre>{@code
     * // 所有 API 固定延迟 3 秒
     * .api("/api/**")
     *     .delay(3)
     *     .done()
     *
     * // 指定 API 随机延迟 1-5 秒
     * .api("/api/slow-endpoint")
     *     .delay(5)
     *     .randomDelay(1, 5) // 覆盖为随机范围 1-5 秒
     *     .matchMethod("POST")
     *     .done()
     * }</pre>
     */
    public static class DelayApiDsl extends BaseApiDsl<DelayApiDsl> {

        DelayApiDsl(RouteDsl parent, RouteRule rule) {
            super(parent, rule);
        }

        /**
         * 更新延迟时长（秒）。
         * <p>通常 delaySecs 已在入口 {@code api(...).delay(delaySecs)} 设置，此方法用于后续更新。
         *
         * @param delaySecs 延迟秒数，必须 ≥ 0
         */
        public DelayApiDsl delay(long delaySecs) {
            rule.setDelayMs(delaySecs * 1000);
            return this;
        }

        /**
         * 启用随机延迟模式，每次请求在 [minSecs, maxSecs] 秒范围内随机取值。
         *
         * <p>随机延迟可以更真实地模拟不稳定弱网环境，
         * 每次请求的实际延迟不同，有助于发现时间敏感型缺陷。
         *
         * <p>调用此方法后，{@link #delay(long)} 设置的固定值被忽略，
         * 实际延迟从随机范围中生成。
         *
         * <pre>{@code
         * .api("/api/orders")
         *     .delay(3)
         *     .randomDelay(1, 5)   // 每次请求延迟 1-5 秒不等
         *     .done()
         * }</pre>
         *
         * @param minSecs 最小延迟秒数，必须 ≥ 0
         * @param maxSecs 最大延迟秒数，必须 > minSecs
         */
        public DelayApiDsl randomDelay(long minSecs, long maxSecs) {
            rule.setDelayMinMs(minSecs * 1000);
            rule.setDelayMaxMs(maxSecs * 1000);
            return this;
        }
    }
}
