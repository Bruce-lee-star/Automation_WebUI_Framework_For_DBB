package com.hsbc.cmb.hk.dbb.automation.framework.route.dsl;

import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;
import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CapturedApiCall;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Route V2 DSL —— 流式构建路由规则并注册到 per-context 运行时。
 *
 * <p>与现有 {@code RouteDsl} 的差异（V2 并发安全契约）：
 * <ul>
 *   <li>{@code register()} 返回 {@link AutoCloseable} 句柄（对应 Playwright 原生
 *       {@code route()} 返回的句柄），可精确注销，不再需要全局 unroute 守护线程；</li>
 *   <li>同 pattern 后注册覆盖先注册（replace 语义），由不可变代际快照原子切换，无规则链合并竞态；
 *       匹配条件不满足的请求走驱动级 fallback，自动落到下一个匹配 pattern 的规则（链式裁决）；</li>
 *   <li>注册动作本身只做内存操作 + 原生 route() 绑定，注册/注销不触发全局状态清理。</li>
 * </ul>
 *
 * <p>示例：
 * <pre>{@code
 * // MONITOR（fail-open，绝不影响请求）
 * AutoCloseable m = RouteDsl.on(context)
 *     .api("/api/users/**").monitor().expectStatus(200).expectJsonPath("$.code", 0).register();
 *
 * // MOCK 静态伪造 + 匹配条件
 * AutoCloseable mock = RouteDsl.on(context)
 *     .api("/api/login").onlyApi().matchMethod("POST")
 *     .mock().status(200).body("{\"token\":\"t\"}").contentType("application/json").register();
 *
 * // MODIFY_REQUEST
 * RouteDsl.on(context)
 *     .api("/api/user").modifyRequest().setRequestHeader("X-Env", "mock").register();
 *
 * // DELAY（IO 线程延迟放行，事件线程不阻塞）
 * RouteDsl.on(context)
 *     .api("/api/pay/**").delay(3000).register();
 *
 * // 精确注销
 * m.close();
 * }</pre>
 *
 * <p><b>feature 模式语义（V2-2 明确）</b>：web 层在每个 scenario 的 {@code testFinished} 对 Context 调
 * {@code clearContext}，而 V2 的该挂点会<b>关闭整个 per-context 运行时</b> —— 故<b>默认 route 规则
 * 不跨 scenario 保留</b>（每个 scenario 起点 runtime 被重建、规则为空）。需要"保留 Context 与 runtime、
 * 只确定性清空规则"时用 {@link #clearRules(BrowserContext)}；只想注销自己注册的几条规则，持有
 * {@code register()} 返回的 {@link AutoCloseable} 句柄调 {@code close()} 即可（两条路径都不触碰
 * Context 生命周期）。
 */
public final class RouteDsl {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteDsl.class);

    private final BrowserContext context;
    private final List<ApiSpec> pending = new ArrayList<>();
    /** 本 DSL 已注册的合并句柄（实例 clear() 注销用）。 */
    private final List<AutoCloseable> registered = new ArrayList<>();

    private RouteDsl(BrowserContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** 绑定 BrowserContext。 */
    public static RouteDsl on(BrowserContext context) {
        return new RouteDsl(context);
    }

    /** 绑定 Page（自动取 page.context()）。 */
    public static RouteDsl on(Page page) {
        Objects.requireNonNull(page, "page");
        return new RouteDsl(page.context());
    }

    /** 绑定的上下文（对齐现有 RouteDsl.getContext()）。 */
    public Object getContext() {
        return context;
    }

    /**
     * 注销本 DSL 已注册的全部规则并清空 pending（对齐现有 RouteDsl.clear() 实例方法）。
     * 幂等；不影响其它 DSL 实例注册的规则。
     */
    public void clear() {
        for (AutoCloseable h : registered) {
            try {
                h.close();
            } catch (Exception e) {
                LOGGER.trace("[Route] clear() handle close ignored: {}", e.toString());
            }
        }
        registered.clear();
        pending.clear();
    }

    /** 只清理指定 BrowserContext 的 V2 运行时（对齐现有 RouteDsl.clear(BrowserContext)）。 */
    public static void clear(BrowserContext context) {
        if (context == null) {
            return;
        }
        RouteEngine.shutdown(context);
    }

    /** 只清理指定 Page 所属 context 的 V2 运行时（对齐现有 RouteDsl.clear(Page)）。 */
    public static void clear(Page page) {
        if (page == null) {
            return;
        }
        RouteEngine.shutdown(page.context());
    }
    /**
     * 只清指定 BrowserContext 的全部 V2 规则，<b>保留</b> runtime 与 Context（V2-2）。
     *
     * <p>与 {@link #clear(BrowserContext)} 的区别：{@code clear(context)} 关闭整个 V2 runtime
     * （线程池 / 注册表条目随之一并释放，下一次注册会重建）；本方法只退役规则，runtime 与 Context
     * 都保留 —— feature 模式下"复用 Context、又要规则确定性清空"时用它。</p>
     *
     * @return 本次提交退役的规则数
     */
    public static int clearRules(BrowserContext context) {
        return RouteEngine.clearRules(context);
    }

    /** 只清指定 Page 所属 context 的全部 V2 规则，保留 runtime 与 Context（V2-2）。 */
    public static int clearRules(Page page) {
        Objects.requireNonNull(page, "page");
        return RouteEngine.clearRules(page.context());
    }

    /** Object 重载（Page / BrowserContext 皆可；非两者抛 IAE，fail-fast 避免静默误用）。 */
    public static int clearRules(Object context) {
        Objects.requireNonNull(context, "context");
        if (context instanceof Page page) {
            return RouteEngine.clearRules(page.context());
        }
        if (context instanceof BrowserContext bc) {
            return RouteEngine.clearRules(bc);
        }
        throw new IllegalArgumentException("context must be Page or BrowserContext, got: "
                + context.getClass().getName());
    }

    /** 只清全部 context 的 V2 规则，保留各 runtime 与 Context（V2-2）。 */
    public static int clearRulesAll() {
        return RouteEngine.clearRulesAll();
    }

    /**
     * 全局清理全部 V2 运行时（对齐现有 RouteDsl.resetAll() 的全局语义；幂等）。
     * V2 无跨用例共享线程池与全局规则表，等价于关闭全部 per-context 运行时。
     */
    public static void resetAll() {
        RouteEngine.shutdownAll();
    }

    /** 轻量全局清理（对齐现有 RouteDsl.clearAllRules()）；V2 等价于 resetAll()。 */
    public static void clearAllRules() {
        RouteEngine.shutdownAll();
    }

    // ── CAPTURE 采集消费（P1：API 信息采集）──

    /** 取走指定 BrowserContext 已采集的 API 快照（消费式；幂等——第二次调用返回空列表）。 */
    public static List<CapturedApiCall> dumpCaptured(BrowserContext context) {
        return RouteEngine.dumpCapturedApis(context);
    }

    /** 取走指定 Page 所属 context 已采集的 API 快照（消费式；幂等）。 */
    public static List<CapturedApiCall> dumpCaptured(Page page) {
        Objects.requireNonNull(page, "page");
        return RouteEngine.dumpCapturedApis(page.context());
    }

    /** Object 重载（对齐现有 RouteDsl 的 Object context 签名）：Page / BrowserContext 皆可。 */
    public static List<CapturedApiCall> dumpCaptured(Object context) {
        Objects.requireNonNull(context, "context");
        if (context instanceof Page page) {
            return RouteEngine.dumpCapturedApis(page.context());
        }
        if (context instanceof BrowserContext bc) {
            return RouteEngine.dumpCapturedApis(bc);
        }
        throw new IllegalArgumentException("context must be Page or BrowserContext, got: "
                + context.getClass().getName());
    }

    // ── CAPTURE 采集查看（非消费式，P1：场景收尾复核真实流量）──

    /** 查看指定 BrowserContext 已采集的 API 快照（<b>非消费式</b>；幂等；清理仍用 {@link #dumpCaptured}）。 */
    public static List<CapturedApiCall> peekCaptured(BrowserContext context) {
        return RouteEngine.peekCapturedApis(context);
    }

    /** 查看指定 Page 所属 context 已采集的 API 快照（非消费式；幂等）。 */
    public static List<CapturedApiCall> peekCaptured(Page page) {
        Objects.requireNonNull(page, "page");
        return RouteEngine.peekCapturedApis(page.context());
    }

    /** Object 重载（对齐 dumpCaptured 的签名）：Page / BrowserContext 皆可。 */
    public static List<CapturedApiCall> peekCaptured(Object context) {
        Objects.requireNonNull(context, "context");
        if (context instanceof Page page) {
            return RouteEngine.peekCapturedApis(page.context());
        }
        if (context instanceof BrowserContext bc) {
            return RouteEngine.peekCapturedApis(bc);
        }
        throw new IllegalArgumentException("context must be Page or BrowserContext, got: "
                + context.getClass().getName());
    }

    // ── stop 系列（对齐现有 RouteDsl.stopX(context, urlPattern)）──
    // 只停指定 pattern 的指定能力；路由仍注册（不 unroute）；未注册/已停返回 false。
    // 在途请求（已 claim）按进入时快照完成，不被打断；重新 register 同 pattern 即恢复能力。

    public static boolean stopMock(BrowserContext context, String urlPattern) {
        return RouteEngine.stopMock(context, urlPattern);
    }

    public static boolean stopMock(Page page, String urlPattern) {
        Objects.requireNonNull(page, "page");
        return RouteEngine.stopMock(page.context(), urlPattern);
    }

    public static boolean stopMonitor(BrowserContext context, String urlPattern) {
        return RouteEngine.stopMonitor(context, urlPattern);
    }

    public static boolean stopMonitor(Page page, String urlPattern) {
        Objects.requireNonNull(page, "page");
        return RouteEngine.stopMonitor(page.context(), urlPattern);
    }

    public static boolean stopModify(BrowserContext context, String urlPattern) {
        return RouteEngine.stopModify(context, urlPattern);
    }

    public static boolean stopModify(Page page, String urlPattern) {
        Objects.requireNonNull(page, "page");
        return RouteEngine.stopModify(page.context(), urlPattern);
    }

    public static boolean stopDelay(BrowserContext context, String urlPattern) {
        return RouteEngine.stopDelay(context, urlPattern);
    }

    public static boolean stopDelay(Page page, String urlPattern) {
        Objects.requireNonNull(page, "page");
        return RouteEngine.stopDelay(page.context(), urlPattern);
    }

    public static boolean stopApi(BrowserContext context, String urlPattern) {
        return RouteEngine.stopApi(context, urlPattern);
    }

    public static boolean stopApi(Page page, String urlPattern) {
        Objects.requireNonNull(page, "page");
        return RouteEngine.stopApi(page.context(), urlPattern);
    }

    // ── Object 重载（对齐现有 RouteDsl.stopX(Object context, String pattern) 签名）──
    // 传 Page / BrowserContext 都能解析；非两者抛 IAE（fail-fast，避免静默误用）。

    public static boolean stopMonitor(Object context, String urlPattern) {
        return stopOnContext(context, urlPattern, RouteEngine::stopMonitor);
    }

    public static boolean stopModify(Object context, String urlPattern) {
        return stopOnContext(context, urlPattern, RouteEngine::stopModify);
    }

    public static boolean stopDelay(Object context, String urlPattern) {
        return stopOnContext(context, urlPattern, RouteEngine::stopDelay);
    }

    public static boolean stopMock(Object context, String urlPattern) {
        return stopOnContext(context, urlPattern, RouteEngine::stopMock);
    }

    public static boolean stopApi(Object context, String urlPattern) {
        return stopOnContext(context, urlPattern, RouteEngine::stopApi);
    }

    @FunctionalInterface
    private interface StopFn {
        boolean apply(BrowserContext context, String urlPattern);
    }

    private static boolean stopOnContext(Object context, String urlPattern, StopFn fn) {
        Objects.requireNonNull(context, "context");
        if (context instanceof Page page) {
            return fn.apply(page.context(), urlPattern);
        }
        if (context instanceof BrowserContext bc) {
            return fn.apply(bc, urlPattern);
        }
        throw new IllegalArgumentException("context must be Page or BrowserContext, got: "
                + context.getClass().getName());
    }

    /** 开始配置一条规则。 */
    public CapabilityDsl api(String urlPattern) {
        return new CapabilityDsl(urlPattern);
    }

    /**
     * 注册本 DSL 内配置的全部规则，返回合并后的注销句柄。
     *
     * <p>每个 pattern 一个独立绑定；重复调用 register() 对同 pattern 执行覆盖语义。
     * 返回句柄 close() 幂等，可安全并发调用。
     */
    public AutoCloseable register() {
        if (pending.isEmpty()) {
            throw new IllegalStateException("No rule configured; call api(pattern).xxx() first");
        }
        RouteRuntime runtime = RouteEngine.runtimeOf(context);
        List<AutoCloseable> handles = new ArrayList<>(pending.size());
        for (ApiSpec spec : pending) {
            handles.add(runtime.register(spec));
            LOGGER.info("[Route] register {} route for '{}'{}",
                    spec.capability(), spec.pattern(), describeRegister(spec));
        }
        pending.clear();
        AutoCloseable merged = () -> {
            for (AutoCloseable h : handles) {
                try {
                    h.close();
                } catch (Exception e) {
                    // 幂等注销：单个失败不影响其它句柄
                    LOGGER.trace("[Route] handle close ignored: {}", e.toString());
                }
            }
        };
        registered.add(merged);
        return merged;
    }

    /**
     * 把一条已注册规则的「具体操作」格式化为单行可读描述（INFO 日志用），
     * 让用户一眼看到 monitor 断言 / mock 内容 / modify 改了哪些值 / delay 时长。
     * 纯展示、不影响行为；body 不截断，敏感信息统一经 {@link SensitiveDataSanitizer} 打码（与全框架一致）。
     */
    private static String describeRegister(ApiSpec spec) {
        StringBuilder d = new StringBuilder();
        appendOperation(d, spec);
        return d.toString();
    }

    /**
     * 把一次命中（请求真正触发能力）格式化为单行可读描述，供 {@code RouteDispatcher} 的逐次命中日志复用，
     * 并作为路由命中证据（{@code RouteEvidenceSink}）的<b>正文</b>写入 Serenity 报告。
     *
     * <p>附带 method/url 便于追溯是哪次请求触发；<b>URL 与 body 一并脱敏</b>：URL 走
     * {@link SensitiveDataSanitizer#sanitizeUrl}（命中敏感 query 键/值即整体丢弃 query，并剥离
     * {@code user:pass@} 内嵌凭据）。此前 url 是<b>原样</b>拼接的 —— 报告标题经 sink 打了码、
     * 正文却带着完整 query（含 token/otp 的端点等于漏出），且同一字符串也进日志。
     * 本方法是该 URL 的<b>唯一出口</b>，故在此收口。</p>
     */
    public static String describeCaptured(ApiSpec spec, String method, String url) {
        StringBuilder d = new StringBuilder();
        d.append(" method=").append(method)
                .append(" url=").append(SensitiveDataSanitizer.sanitizeUrl(url));
        appendOperation(d, spec);
        return d.toString();
    }

    private static void appendOperation(StringBuilder d, ApiSpec spec) {
        switch (spec.capability()) {
            case MONITOR -> {
                if (spec.expectStatus() != null) {
                    d.append(" expectStatus=").append(spec.expectStatus());
                }
                appendMap(d, "jsonPath", spec.jsonPathAssertions());
                if (spec.expectBodyContains() != null) {
                    d.append(" bodyContains=\"").append(SensitiveDataSanitizer.sanitizeBody(spec.expectBodyContains())).append('"');
                }
                if (spec.expectBodyRegex() != null) {
                    d.append(" bodyRegex=\"").append(SensitiveDataSanitizer.sanitizeBody(spec.expectBodyRegex())).append('"');
                }
                appendMap(d, "formField", spec.formFieldAssertions());
                d.append(" timeout=").append(spec.monitorTimeoutMs() / 1000).append("s");
                if (spec.autoStopOnMatch()) {
                    d.append(" autoStop(min=").append(spec.minMatches()).append(')');
                }
            }
            case MOCK -> {
                // 未声明状态码时输出 real-response 而不是裸 "null"：.mock().interceptResponse() 这类
                // "用上游真实响应"的写法本就不声明状态码，裸 null 在报告里读起来像"状态缺失/故障"。
                d.append(" status=").append(spec.mockStatus() != null ? spec.mockStatus() : "real-response");
                d.append(" intercept=").append(spec.mockIntercept());
                if (spec.mockContentType() != null) {
                    d.append(" contentType=").append(spec.mockContentType());
                }
                if (!spec.mockHeaders().isEmpty()) {
                    d.append(" headers=").append(spec.mockHeaders().keySet());
                }
                if (spec.mockBody() == null) {
                    d.append(" body=[intercepted]");
                } else {
                    d.append(" body=").append(SensitiveDataSanitizer.sanitizeBody(spec.mockBody()));
                }
                if (!spec.mockReplacements().isEmpty()) {
                    d.append(" replacePaths=").append(spec.mockReplacements().keySet());
                }
                if (!spec.conditionalReplacements().isEmpty()) {
                    d.append(" when=").append(spec.conditionalReplacements().stream()
                            .map(c -> c.whenPath() + "->" + c.thenPath()).toList());
                }
            }
            case MODIFY_REQUEST -> {
                appendMap(d, "setHeader", spec.requestHeadersToSet());
                if (!spec.requestHeadersToRemove().isEmpty()) {
                    d.append(" removeHeader=").append(spec.requestHeadersToRemove());
                }
                if (!spec.bodyOps().isEmpty()) {
                    d.append(" bodyOps=").append(spec.bodyOps().stream()
                            .map(o -> o.type() + " " + o.path() + "="
                                    + SensitiveDataSanitizer.sanitizeBody(String.valueOf(o.value())))
                            .toList());
                }
                if (spec.modifyMethod() != null) {
                    d.append(" method=").append(spec.modifyMethod());
                }
            }
            case DELAY -> {
                if (spec.delayMinMs() > 0 && spec.delayMaxMs() > spec.delayMinMs()) {
                    d.append(" randomDelay=[").append(spec.delayMinMs() / 1000).append("s,")
                            .append(spec.delayMaxMs() / 1000).append("s]");
                } else {
                    d.append(" delay=").append(spec.delayMs() / 1000).append("s");
                }
            }
            default -> { }
        }
    }

    private static void appendMap(StringBuilder d, String label, Map<String, ?> map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        d.append(' ').append(label).append('=').append(map.entrySet().stream()
                .map(e -> e.getKey() + "=" + SensitiveDataSanitizer.sanitizeBody(String.valueOf(e.getValue())))
                .toList());
    }

    /**
     * 级联结束统一提交（对齐现有 RouteDsl 的 {@code .done() ... .start()} 语法）。
     *
     * <p>等价于 {@link #register()}：把此前所有 {@code done()} 累积的规则一次性注册，
     * 返回合并后的注销句柄（{@link AutoCloseable}）。规则生命周期随所属 Context 关闭而清理
     * （与现有 RouteDsl.start() 语义一致）；若需精确注销单条 / 整组规则，请用本句柄 {@code close()}。
     *
     * <p><b>API 契约（二进制兼容）</b>：本方法返回 {@link AutoCloseable}，与 {@link #register()}
     * 完全一致 —— 下游已编译产物（如 test-automation 的 {@code .start();} 语句及持有句柄的用法）
     * 不因签名变化而触发 {@code NoSuchMethodError}。
     *
     * <p>时序与并发：{@code done()} 只做内存累积（无注册、无跨线程可见状态）；
     * {@code start()} 复用 {@link #register()} 路径（逐条 CAS 线性化注册），
     * 与并发注册 / stop 系列无竞态。
     */
    public AutoCloseable start() {
        return register();
    }

    /** 当前已声明未提交的规则数（级联调试用）。 */
    public int pendingCount() {
        return pending.size();
    }

    /** 能力选择器：monitor / mock / modifyRequest / delay。匹配条件可在能力选择前或后配置。 */
    public final class CapabilityDsl {
        private final String urlPattern;
        private final List<java.util.function.Consumer<ApiSpec.Builder>> conditions = new ArrayList<>();

        private CapabilityDsl(String urlPattern) {
            this.urlPattern = urlPattern;
        }

        private CapabilityDsl with(java.util.function.Consumer<ApiSpec.Builder> c) {
            conditions.add(c);
            return this;
        }

        // ── 匹配条件（对齐现有 RouteDsl：api(pattern).matchMethod(...).mock()...）──

        public CapabilityDsl matchMethod(String method) {
            return with(b -> b.matchMethod(method));
        }

        public CapabilityDsl resourceType(String types) {
            return with(b -> b.resourceTypes(types));
        }

        public CapabilityDsl onlyXhr() {
            return with(ApiSpec.Builder::onlyXhr);
        }

        public CapabilityDsl onlyFetch() {
            return with(ApiSpec.Builder::onlyFetch);
        }

        public CapabilityDsl onlyApi() {
            return with(ApiSpec.Builder::onlyApi);
        }

        public CapabilityDsl matchHeader(String key, String value) {
            return with(b -> b.matchHeader(key, value));
        }

        public CapabilityDsl matchQuery(String key, String value) {
            return with(b -> b.matchQuery(key, value));
        }

        public CapabilityDsl matchBodyRegex(String regex) {
            return with(b -> b.matchBodyRegex(regex));
        }

        public CapabilityDsl matchContentType(String contentType) {
            return with(b -> b.matchContentType(contentType));
        }

        public CapabilityDsl matchReferrer(String referrer) {
            return with(b -> b.matchReferrer(referrer));
        }

        public CapabilityDsl matchOrigin(String origin) {
            return with(b -> b.matchOrigin(origin));
        }

        public CapabilityDsl matchFrameUrl(String frameUrl) {
            return with(b -> b.matchFrameUrl(frameUrl));
        }

        public CapabilityDsl onlyMainFrame(boolean onlyMainFrame) {
            return with(b -> b.onlyMainFrame(onlyMainFrame));
        }

        public CapabilityDsl allowAllFrames() {
            return with(ApiSpec.Builder::allowAllFrames);
        }

        public CapabilityDsl onlyApiCall(boolean apiOnly) {
            return with(b -> b.onlyApiCall(apiOnly));
        }

        public CapabilityDsl allowAllRequests() {
            return with(ApiSpec.Builder::allowAllRequests);
        }

        private ApiSpec.Builder newBuilder(RouteCapability capability) {
            ApiSpec.Builder b = ApiSpec.builder(urlPattern, capability);
            for (java.util.function.Consumer<ApiSpec.Builder> c : conditions) {
                c.accept(b);
            }
            return b;
        }

        public MonitorDsl monitor() {
            return new MonitorDsl(newBuilder(RouteCapability.MONITOR));
        }

        public MockDsl mock() {
            return new MockDsl(newBuilder(RouteCapability.MOCK));
        }

        public ModifyDsl modifyRequest() {
            return new ModifyDsl(newBuilder(RouteCapability.MODIFY_REQUEST));
        }

        /**
         * 延迟 N 秒后放行（对齐现有 RouteDsl 的 {@code api(p).delay(secs)} 语义，单位为秒）。
         *
         * <p>返回 {@link DelayDsl}：可链式 {@code randomDelay(min,max)} / 匹配条件 /
         * {@code timeout} / {@code minMatches} / {@code times}，最后 {@code done().start()} 统一提交。
         * 与 MOCK / MODIFY 不同，DELAY 不拦截响应（resume 放行原始请求），fail-open。
         */
        public DelayDsl delay(long delaySecs) {
            if (delaySecs < 0) {
                throw new IllegalArgumentException("delaySecs must be >= 0, got: " + delaySecs);
            }
            return new DelayDsl(newBuilder(RouteCapability.DELAY)
                    .delayMs(Math.multiplyExact(delaySecs, 1000L)));
        }

        /**
         * 结束当前段（未选能力）——对齐现有 RouteDsl 的「api(p).done()」自动启用
         * collect-only MONITOR 语义（老版：type=MONITOR 但未调 monitor() 时自动启用监控能力位）。
         * 仅累积不注册，由根 start()/register() 统一提交。
         */
        public RouteDsl done() {
            pending.add(newBuilder(RouteCapability.MONITOR).build());
            return RouteDsl.this;
        }
    }

    /**
     * 匹配条件基类 —— 对 MONITOR / MOCK / MODIFY_REQUEST 统一生效。
     *
     * <p>语义对齐现有 {@code RouteDsl}：method 精确、query/header 精确且同时满足、
     * contentType/referrer/origin/frameUrl 包含匹配、bodyRegex 为 Java 正则。
     * 条件不满足的请求不进入本规则，走 fallback 落到下一个匹配 pattern（不破坏其它规则）。
     */
    public abstract class BaseMatchDsl<T extends BaseMatchDsl<T>> {
        protected final ApiSpec.Builder b;

        protected BaseMatchDsl(ApiSpec.Builder b) {
            this.b = b;
        }

        @SuppressWarnings("unchecked")
        protected T self() {
            return (T) this;
        }

        /** 精确匹配 HTTP Method（忽略大小写，如 "GET" / "POST"）。 */
        public T matchMethod(String method) {
            b.matchMethod(method);
            return self();
        }

        /** 匹配资源类型（逗号分隔，如 "xhr,fetch"）。 */
        public T resourceType(String types) {
            b.resourceTypes(types);
            return self();
        }

        /** 仅匹配 XHR 请求。 */
        public T onlyXhr() {
            b.resourceTypes("xhr");
            return self();
        }

        /** 仅匹配 Fetch 请求。 */
        public T onlyFetch() {
            b.resourceTypes("fetch");
            return self();
        }

        /** 仅匹配 API 调用（xhr + fetch）。 */
        public T onlyApi() {
            b.resourceTypes("xhr,fetch");
            return self();
        }

        /** 添加请求头精确匹配条件（全部同时满足）。 */
        public T matchHeader(String key, String value) {
            b.matchHeader(key, value);
            return self();
        }

        /** 添加 Query 参数精确匹配条件（全部同时满足）。 */
        public T matchQuery(String key, String value) {
            b.matchQuery(key, value);
            return self();
        }

        /** 请求体 Java 正则匹配（仅对 POST/PUT 有 body 的请求生效）。 */
        public T matchBodyRegex(String regex) {
            b.matchBodyRegex(regex);
            return self();
        }

        /** Content-Type 包含匹配（如 "json" 匹配 application/json;charset=UTF-8）。 */
        public T matchContentType(String contentType) {
            b.matchContentType(contentType);
            return self();
        }

        /** Referrer 包含匹配。 */
        public T matchReferrer(String referrer) {
            b.matchReferrer(referrer);
            return self();
        }

        /** Origin 包含匹配。 */
        public T matchOrigin(String origin) {
            b.matchOrigin(origin);
            return self();
        }

        /** 发起请求的 Frame URL 包含匹配。 */
        public T matchFrameUrl(String frameUrl) {
            b.matchFrameUrl(frameUrl);
            return self();
        }

        /** 是否仅匹配主 Frame 请求（默认 true，跳过 iframe/worker）。 */
        public T onlyMainFrame(boolean onlyMainFrame) {
            b.onlyMainFrame(onlyMainFrame);
            return self();
        }

        /** 允许匹配所有 Frame（包括 iframe/worker）。 */
        public T allowAllFrames() {
            b.onlyMainFrame(false);
            return self();
        }

        /**
         * 是否仅匹配 API 调用。
         * true 时跳过导航请求；未显式设置 resourceType 则只匹配 xhr/fetch。
         */
        public T onlyApiCall(boolean apiOnly) {
            b.onlyApiCall(apiOnly);
            return self();
        }

        /** 允许匹配所有类型请求（含导航、静态资源、iframe）。 */
        public T allowAllRequests() {
            b.onlyApiCall(false);
            b.onlyMainFrame(false);
            return self();
        }

        // ── CAPTURE（横切采集，与能力正交；对 monitor/mock/modifyRequest/delay 统一生效）──

        /**
         * 开启该 pattern 的 API 信息采集（请求/响应快照；经 RouteDsl.dumpCaptured 消费）。
         *
         * <p>capture 是横切观测：不参与终结所有权、不改变请求流，与 mock/modify/delay/monitor
         * 能力共存无竞态——同一请求同时被能力处理与被采集（响应侧挂载于 context.onResponse，
         * 事件线程零阻塞，响应体读取交 IO 线程）。
         */
        public T capture() {
            b.capture(true);
            return self();
        }

        /** 是否采集响应体（默认 false；开启后经 IO 线程读取，按 captureBodyLimitBytes 截断）。 */
        public T captureBody(boolean enable) {
            b.captureBody(enable);
            return self();
        }

        /** 响应体采集截断上限（字节；默认 64 KiB）。 */
        public T captureBodyLimitBytes(int limitBytes) {
            b.captureBodyLimitBytes(limitBytes);
            return self();
        }

        /**
         * 结束当前规则段并返回级联根（对齐现有 RouteDsl 的 done() 语法）。
         *
         * <p>仅将本段规则累积到 pending（不注册、不触达运行时），由根的
         * {@code start()} 统一提交。规则校验（如 MOCK intercept 与静态 body 互斥）
         * 在 done() 即 fail-fast，早于注册暴露配置错误。
         */
        public RouteDsl done() {
            RouteDsl.this.pending.add(b.build());
            return RouteDsl.this;
        }

        // ===== 自动停止控制（对齐现有 RouteDsl BaseApiDsl）=====

        /**
         * 设置监控断言超时（秒；0=永不超时）。对齐现有 RouteDsl.timeout(long)（老版单位为秒）。
         * 对 MONITOR 生效；MOCK/MODIFY/DELAY 段接受但不改变行为（老版同）。
         */
        public T timeout(long timeoutSecs) {
            if (timeoutSecs < 0) {
                throw new IllegalArgumentException("timeout must be >= 0, got: " + timeoutSecs);
            }
            b.monitorTimeoutMs(Math.multiplyExact(timeoutSecs, 1000L));
            return self();
        }

        /** 设置最小匹配次数（配合 autoStopOnMatch；默认 1）。 */
        public T minMatches(int minMatches) {
            b.minMatches(minMatches);
            return self();
        }

        /** 目标匹配后是否自动停止监控（默认 false=持续，直到显式 stopMonitor/stopApi）。 */
        public T autoStopOnMatch(boolean autoStopOnMatch) {
            b.autoStopOnMatch(autoStopOnMatch);
            return self();
        }
    }

    /** MONITOR 配置。 */
    public final class MonitorDsl extends BaseMatchDsl<MonitorDsl> {

        private MonitorDsl(ApiSpec.Builder b) {
            super(b);
        }

        public MonitorDsl expectStatus(int status) {
            b.expectStatus(status);
            return this;
        }

        /**
         * 添加 JSONPath 断言（值类型自动推断：Number 按数值比较，其余 equals）。
         * 仅适用于 JSON 响应体；非 JSON 响应 → 明确的 not-applicable 失败明细。
         * 响应体读取与断言在 IO 线程执行，事件线程零阻塞。
         */
        public MonitorDsl expectJsonPath(String jsonPath, Object expectedValue) {
            b.expectJsonPath(jsonPath, expectedValue);
            return this;
        }

        /**
         * 任意文本响应体包含匹配（XML / HTML / 纯文本 / 表单均可用）。
         * 非文本响应（二进制等）→ 明确的 not-applicable 失败明细。
         */
        public MonitorDsl expectBodyContains(String substring) {
            b.expectBodyContains(substring);
            return this;
        }

        /** 任意文本响应体正则匹配（Java 正则，find 语义）。 */
        public MonitorDsl expectBodyRegex(String regex) {
            b.expectBodyRegex(regex);
            return this;
        }

        /**
         * 表单字段断言（application/x-www-form-urlencoded 响应）。
         * 字段值集合须包含期望值（精确匹配，允许重复键）。
         */
        public MonitorDsl expectFormField(String key, String value) {
            b.expectFormField(key, value);
            return this;
        }

        public MonitorDsl timeoutMs(long timeoutMs) {
            b.monitorTimeoutMs(timeoutMs);
            return this;
        }

        /** 是否记录请求/响应信息（对齐现有 RouteDsl.record；默认 true）。 */
        public MonitorDsl record(boolean enable) {
            b.record(enable);
            return this;
        }

        public MonitorDsl times(int times) {
            b.times(times);
            return this;
        }

        public AutoCloseable register() {
            pending.add(b.build());
            return RouteDsl.this.register();
        }
    }

    /** MOCK 配置。 */
    public final class MockDsl extends BaseMatchDsl<MockDsl> {

        private MockDsl(ApiSpec.Builder b) {
            super(b);
        }

        public MockDsl status(int status) {
            b.mockStatus(status);
            return this;
        }

        /** 对齐现有 RouteDsl.mockStatus(int)。 */
        public MockDsl mockStatus(int status) {
            return status(status);
        }

        public MockDsl body(String body) {
            b.mockBody(body);
            return this;
        }

        /** 对齐现有 RouteDsl.mockBody(String)。 */
        public MockDsl mockBody(String body) {
            return body(body);
        }

        /** 二进制 Mock 响应体（对齐现有 RouteDsl.mockBody(byte[])；优先级高于 String body）。 */
        public MockDsl mockBody(byte[] bodyBytes) {
            Objects.requireNonNull(bodyBytes, "bodyBytes");
            b.mockBody(new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8));
            return this;
        }

        /** 任意对象 Mock 响应体（对齐现有 RouteDsl.mockBody(Object)：Map/POJO 自动序列化为 JSON）。 */
        public MockDsl mockBody(Object obj) {
            Objects.requireNonNull(obj, "obj");
            if (obj instanceof byte[] bytes) {
                return mockBody(bytes);
            }
            if (obj instanceof String s) {
                return body(s);
            }
            b.mockBody(new com.google.gson.Gson().toJson(obj));
            return this;
        }

        public MockDsl contentType(String contentType) {
            b.mockContentType(contentType);
            return this;
        }

        /**
         * 从 classpath 资源读取响应体模板（注册时读取，失败快速失败）。
         * 可链式 {@link #replaceField(String, Object)} 做字段覆盖（纯静态，不访问真实服务器）。
         */
        public MockDsl mockBodyFromFile(String classpathResource) {
            Objects.requireNonNull(classpathResource, "classpathResource");
            try (java.io.InputStream in = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(classpathResource)) {
                if (in == null) {
                    throw new IllegalArgumentException("classpath resource not found: " + classpathResource);
                }
                b.mockBody(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                // 文件模板按 JSON 处理（对齐现有 RouteDsl.mockBodyFromFile 语义）；
                // 用户随后显式 contentType(...) 可覆盖（Builder 后写生效）。
                b.mockContentType("application/json");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("failed to read classpath resource: " + classpathResource, e);
            }
            return this;
        }

        /**
         * 从 classpath 资源读取 Mock 响应体并按字段映射批量替换后返回（对齐现有 RouteDsl
         * {@code mockBodyFromFile(String, Map)}；纯静态，不访问真实服务器）。
         * 等价于 {@code mockBodyFromFile(file).replaceFields(fieldOverrides)}。
         */
        public MockDsl mockBodyFromFile(String classpathResource, Map<String, Object> fieldOverrides) {
            mockBodyFromFile(classpathResource);
            if (fieldOverrides != null && !fieldOverrides.isEmpty()) {
                replaceFields(fieldOverrides);
            }
            return this;
        }

        public MockDsl header(String name, String value) {
            b.mockHeader(name, value);
            return this;
        }

        /** 对齐现有 RouteDsl.mockHeader(String, String)。 */
        public MockDsl mockHeader(String name, String value) {
            return header(name, value);
        }

        /**
         * 拦截真实响应（fetch 后按需字段替换再返回前端）。
         * 与静态 body/status/header 互斥（build 时校验）。
         */
        public MockDsl interceptResponse() {
            b.mockIntercept(true);
            return this;
        }

        /**
         * 字段替换（内容类型感知）：JSON→JSONPath、XML→XPath、表单→字段键、文本→字面串。
         * 可作用于 intercept 后的真实响应，也可作用于 mockBodyFromFile 的静态体。
         */
        public MockDsl mockReplaceField(String path, Object value) {
            b.mockReplacement(path, value);
            return this;
        }

        /** {@link #mockReplaceField(String, Object)} 别名。 */
        public MockDsl replaceField(String path, Object value) {
            return mockReplaceField(path, value);
        }

        public MockDsl replaceFields(Map<String, Object> replacements) {
            b.mockReplacements(replacements);
            return this;
        }

        /**
         * 条件字段修改（对齐现有 RouteDsl 的 {@code when(...).thenSet(...)}）：
         * 当响应中 {@code whenJsonPath} 取值满足 {@code op}/{@code expected} 时，才将
         * {@code thenSetJsonPath} 设置为新值；不满足保留原值。仅 JSON 响应生效。
         *
         * <p>支持的 op（忽略大小写）：EQUALS / NOT_EQUALS / CONTAINS / NOT_CONTAINS /
         * REGEX（全字符串 matches 语义，对齐老版）/ EXISTS / NOT_EXISTS / GT / LT / GTE / LTE。
         */
        public ConditionalWhen when(String whenJsonPath, String op, Object expected) {
            return new ConditionalWhen(whenJsonPath, op, expected);
        }

        /** {@code when(...).thenSet(...)} 中间构建器（对齐现有 RouteDsl.ConditionalWhen）。 */
        public final class ConditionalWhen {
            private final String whenJsonPath;
            private final String op;
            private final Object expected;

            private ConditionalWhen(String whenJsonPath, String op, Object expected) {
                this.whenJsonPath = whenJsonPath;
                this.op = op;
                this.expected = expected;
            }

            /** 登记条件规则并返回外层 MockDsl（可继续链式 when(...)）。 */
            public MockDsl thenSet(String thenSetJsonPath, Object setValue) {
                b.conditionalReplacement(
                        new ConditionalField(whenJsonPath, op, expected, thenSetJsonPath, setValue));
                return MockDsl.this;
            }
        }

        public MockDsl times(int times) {
            b.times(times);
            return this;
        }

        public AutoCloseable register() {
            pending.add(b.build());
            return RouteDsl.this.register();
        }
    }

    /** MODIFY_REQUEST 配置。 */
    public final class ModifyDsl extends BaseMatchDsl<ModifyDsl> {

        private ModifyDsl(ApiSpec.Builder b) {
            super(b);
        }

        public ModifyDsl setRequestHeader(String name, String value) {
            b.setRequestHeader(name, value);
            return this;
        }

        /** 批量设置请求头（对齐现有 RouteDsl.setRequestHeaders(Map)）。 */
        public ModifyDsl setRequestHeaders(Map<String, String> headers) {
            if (headers != null) {
                headers.forEach(b::setRequestHeader);
            }
            return this;
        }

        /** 修改请求 HTTP 方法（对齐现有 RouteDsl.modifyMethod；resume 不支持改 method，运行时走 fetch+fulfill）。 */
        public ModifyDsl modifyMethod(String method) {
            b.modifyMethod(method);
            return this;
        }

        public ModifyDsl removeRequestHeader(String name) {
            b.removeRequestHeader(name);
            return this;
        }

        /**
         * 请求体字段设置（JSONPath：存在更新 / 缺失按路径创建）。
         * body 级修改在 IO 线程执行，事件线程零阻塞。
         */
        public ModifyDsl modifyRequestBody(String jsonPath, Object value) {
            b.bodySet(jsonPath, value);
            return this;
        }

        /** 请求体数组追加（JSONPath）。 */
        public ModifyDsl addRequestBodyField(String jsonPath, Object value) {
            b.bodyAdd(jsonPath, value);
            return this;
        }

        /** 请求体字段删除（JSONPath）。 */
        public ModifyDsl removeRequestBodyField(String jsonPath) {
            b.bodyRemove(jsonPath);
            return this;
        }

        /** 表单字段设置（application/x-www-form-urlencoded；键不存在则追加）。 */
        public ModifyDsl modifyFormField(String key, String value) {
            b.bodySet(key, value);
            return this;
        }

        /** 表单字段追加（重复键）。 */
        public ModifyDsl addFormField(String key, String value) {
            b.bodyAdd(key, value);
            return this;
        }

        /** 表单字段删除（全部该键）。 */
        public ModifyDsl removeFormField(String key) {
            b.bodyRemove(key);
            return this;
        }

        public ModifyDsl times(int times) {
            b.times(times);
            return this;
        }

        public AutoCloseable register() {
            pending.add(b.build());
            return RouteDsl.this.register();
        }
    }

    /** DELAY 配置（对齐现有 RouteDsl.DelayApiDsl：done() 提交，randomDelay 切换随机模式）。 */
    public final class DelayDsl extends BaseMatchDsl<DelayDsl> {

        private DelayDsl(ApiSpec.Builder b) {
            super(b);
        }

        /** 更新延迟时长（秒）。 */
        public DelayDsl delay(long delaySecs) {
            if (delaySecs < 0) {
                throw new IllegalArgumentException("delaySecs must be >= 0, got: " + delaySecs);
            }
            b.delayMs(Math.multiplyExact(delaySecs, 1000L));
            return this;
        }

        /** 启用随机延迟（秒）：每次请求在 [minSecs, maxSecs] 内随机取值（覆盖固定值）。 */
        public DelayDsl randomDelay(long minSecs, long maxSecs) {
            b.randomDelayMs(Math.multiplyExact(minSecs, 1000L), Math.multiplyExact(maxSecs, 1000L));
            return this;
        }
    }
}
