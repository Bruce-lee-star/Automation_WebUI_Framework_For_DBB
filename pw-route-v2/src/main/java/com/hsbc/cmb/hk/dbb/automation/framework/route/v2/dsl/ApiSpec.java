package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 路由规则（不可变快照）。
 *
 * <p>并发安全设计：所有字段 {@code final}，集合一律不可变拷贝，发布后任何线程读取
 * 都不需要锁。规则一经构建不可修改——「修改规则」的唯一途径是构建新规则并发布到
 * {@code GenerationRegistry}（代际切换），读侧无锁。
 *
 * <p>匹配条件（match* / only* / allowAll*）语义对齐现有 {@code RouteDsl}：
 * <ul>
 *   <li>{@code matchMethod} 精确匹配（忽略大小写）；</li>
 *   <li>{@code matchHeader} / {@code matchQuery} 精确匹配，全部条件须同时满足；</li>
 *   <li>{@code matchContentType} / {@code matchReferrer} / {@code matchOrigin} /
 *       {@code matchFrameUrl} 为包含匹配；</li>
 *   <li>{@code matchBodyRegex} 为 Java 正则，仅对 POST/PUT 请求体生效（无 body 则不匹配）；</li>
 *   <li>{@code onlyMainFrame} 默认 {@code true}（跳过 iframe/worker 请求）；</li>
 *   <li>{@code onlyApiCall(true)}：跳过导航请求；未显式设置 resourceType 时仅匹配 xhr/fetch。</li>
 * </ul>
 */
public final class ApiSpec {

    /** URL pattern（Playwright glob，如 /api/users/**）。 */
    private final String pattern;

    /** 能力。 */
    private final RouteCapability capability;

    /** 被显式停止（stop* 系列）的能力位掩码——规则仍注册，dispatch 时跳过。 */
    private final Set<RouteCapability> disabled;

    // ── MOCK 参数 ──
    private final Integer mockStatus;
    private final String mockBody;
    private final String mockContentType;
    private final Map<String, String> mockHeaders;
    /** 显式 intercept 语义：拦截真实响应（与静态 mockBody 互斥）。 */
    private final boolean mockIntercept;
    /** intercept 后的字段替换（内容类型感知路由，见 FieldReplacer）。 */
    private final Map<String, Object> mockReplacements;
    /** 条件字段修改（when...thenSet；仅 JSON 响应生效）。 */
    private final List<ConditionalField> conditionalReplacements;

    // ── MODIFY_REQUEST 参数（resume overrides）──
    private final Map<String, String> requestHeadersToSet;
    private final Set<String> requestHeadersToRemove;
    /** body 级修改操作（内容类型感知路由，见 RequestBodyModifier）。 */
    private final List<BodyOp> bodyOps;
    /** 修改请求 HTTP 方法（对齐现有 RouteDsl.modifyMethod；resume(options.setMethod) 单请求实现）。 */
    private final String modifyMethod;

    // ── DELAY 参数 ──
    private final long delayMs;
    /** 随机延迟区间（毫秒）；0=未启用随机（使用固定 delayMs）。 */
    private final long delayMinMs;
    private final long delayMaxMs;

    // ── MONITOR 参数 ──
    private final Integer expectStatus;
    private final Map<String, Object> jsonPathAssertions;
    private final String expectBodyContains;
    private final String expectBodyRegex;
    private final Map<String, String> formFieldAssertions;
    private final long monitorTimeoutMs;
    /** 是否记录请求/响应信息（对齐现有 RouteDsl.record，默认 true）。 */
    private final boolean record;
    /** 目标匹配后是否自动停止监控（对齐现有 RouteDsl.autoStopOnMatch，默认 false=持续）。 */
    private final boolean autoStopOnMatch;
    /** 最小匹配次数（与 autoStopOnMatch 配合，默认 1）。 */
    private final int minMatches;

    // ── CAPTURE 参数（横切采集：与能力正交，对 mock/modify/delay/monitor 统一生效）──
    /** 是否采集该 pattern 的 API 信息（请求/响应快照，含 dump 消费）。 */
    private final boolean capture;
    /** 是否采集响应体（默认 false；开启后经 IO 线程读取，受 captureBodyLimitBytes 截断）。 */
    private final boolean captureBody;
    /** 响应体采集截断上限（字节，默认 {@value CaptureLimits#DEFAULT_BODY_LIMIT_BYTES}）。 */
    private final int captureBodyLimitBytes;

    // ── 匹配条件 ──
    private final String matchMethod;
    private final Set<String> resourceTypes;
    private final Map<String, String> matchHeaders;
    private final Map<String, String> matchQueryParams;
    private final String matchBodyRegex;
    private final String matchContentType;
    private final String matchReferrer;
    private final String matchOrigin;
    private final String matchFrameUrl;
    private final boolean onlyMainFrame;
    private final boolean onlyApiCall;

    // ── 公共参数 ──
    /** 最多处理次数（委托 Playwright 原生 RouteOptions.times，用尽自动注销，见 Router.RouteInfo）。 */
    private final Integer times;

    private ApiSpec(Builder b) {
        this.pattern = b.pattern;
        this.capability = b.capability;
        this.disabled = Collections.unmodifiableSet(new LinkedHashSet<>(b.disabled));
        this.mockStatus = b.mockStatus;
        this.mockBody = b.mockBody;
        this.mockContentType = b.mockContentType;
        this.mockHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(b.mockHeaders));
        this.mockIntercept = b.mockIntercept;
        this.mockReplacements = Collections.unmodifiableMap(new LinkedHashMap<>(b.mockReplacements));
        this.conditionalReplacements = Collections.unmodifiableList(new ArrayList<>(b.conditionalReplacements));
        this.requestHeadersToSet = Collections.unmodifiableMap(new LinkedHashMap<>(b.requestHeadersToSet));
        this.requestHeadersToRemove = Collections.unmodifiableSet(new LinkedHashSet<>(b.requestHeadersToRemove));
        this.bodyOps = Collections.unmodifiableList(new ArrayList<>(b.bodyOps));
        this.modifyMethod = b.modifyMethod;
        this.delayMs = b.delayMs;
        this.delayMinMs = b.delayMinMs;
        this.delayMaxMs = b.delayMaxMs;
        this.expectStatus = b.expectStatus;
        this.jsonPathAssertions = Collections.unmodifiableMap(new LinkedHashMap<>(b.jsonPathAssertions));
        this.expectBodyContains = b.expectBodyContains;
        this.expectBodyRegex = b.expectBodyRegex;
        this.formFieldAssertions = Collections.unmodifiableMap(new LinkedHashMap<>(b.formFieldAssertions));
        this.monitorTimeoutMs = b.monitorTimeoutMs;
        this.record = b.record;
        this.autoStopOnMatch = b.autoStopOnMatch;
        this.minMatches = b.minMatches;
        this.capture = b.capture;
        this.captureBody = b.captureBody;
        this.captureBodyLimitBytes = b.captureBodyLimitBytes;
        this.times = b.times;
        this.matchMethod = b.matchMethod;
        if (b.resourceTypes == null) {
            this.resourceTypes = null;
        } else {
            this.resourceTypes = Collections.unmodifiableSet(new LinkedHashSet<>(b.resourceTypes));
        }
        this.matchHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(b.matchHeaders));
        this.matchQueryParams = Collections.unmodifiableMap(new LinkedHashMap<>(b.matchQueryParams));
        this.matchBodyRegex = b.matchBodyRegex;
        this.matchContentType = b.matchContentType;
        this.matchReferrer = b.matchReferrer;
        this.matchOrigin = b.matchOrigin;
        this.matchFrameUrl = b.matchFrameUrl;
        this.onlyMainFrame = b.onlyMainFrame;
        this.onlyApiCall = b.onlyApiCall;
    }

    /**
     * 全字段复制构造（stop 系列专用）：保留原规则全部行为参数，仅替换能力位掩码。
     * 规则不可变——「修改」的唯一途径是产出新实例并发布到代际注册表。
     */
    private ApiSpec(ApiSpec from, Set<RouteCapability> disabled) {
        this.pattern = from.pattern;
        this.capability = from.capability;
        this.disabled = Collections.unmodifiableSet(new LinkedHashSet<>(disabled));
        this.mockStatus = from.mockStatus;
        this.mockBody = from.mockBody;
        this.mockContentType = from.mockContentType;
        this.mockHeaders = from.mockHeaders;
        this.mockIntercept = from.mockIntercept;
        this.mockReplacements = from.mockReplacements;
        this.conditionalReplacements = from.conditionalReplacements;
        this.requestHeadersToSet = from.requestHeadersToSet;
        this.requestHeadersToRemove = from.requestHeadersToRemove;
        this.bodyOps = from.bodyOps;
        this.modifyMethod = from.modifyMethod;
        this.delayMs = from.delayMs;
        this.delayMinMs = from.delayMinMs;
        this.delayMaxMs = from.delayMaxMs;
        this.expectStatus = from.expectStatus;
        this.jsonPathAssertions = from.jsonPathAssertions;
        this.expectBodyContains = from.expectBodyContains;
        this.expectBodyRegex = from.expectBodyRegex;
        this.formFieldAssertions = from.formFieldAssertions;
        this.monitorTimeoutMs = from.monitorTimeoutMs;
        this.record = from.record;
        this.autoStopOnMatch = from.autoStopOnMatch;
        this.minMatches = from.minMatches;
        this.capture = from.capture;
        this.captureBody = from.captureBody;
        this.captureBodyLimitBytes = from.captureBodyLimitBytes;
        this.times = from.times;
        this.matchMethod = from.matchMethod;
        if (from.resourceTypes == null) {
            this.resourceTypes = null;
        } else {
            this.resourceTypes = Collections.unmodifiableSet(new LinkedHashSet<>(from.resourceTypes));
        }
        this.matchHeaders = from.matchHeaders;
        this.matchQueryParams = from.matchQueryParams;
        this.matchBodyRegex = from.matchBodyRegex;
        this.matchContentType = from.matchContentType;
        this.matchReferrer = from.matchReferrer;
        this.matchOrigin = from.matchOrigin;
        this.matchFrameUrl = from.matchFrameUrl;
        this.onlyMainFrame = from.onlyMainFrame;
        this.onlyApiCall = from.onlyApiCall;
    }

    /**
     * 标记某能力为已停止（返回新实例；原实例不受影响）。
     * 已停止时返回 this（幂等）。
     */
    public ApiSpec withStopped(RouteCapability capability) {
        if (disabled.contains(capability)) {
            return this;
        }
        Set<RouteCapability> next = new LinkedHashSet<>(disabled);
        next.add(capability);
        return new ApiSpec(this, next);
    }

    /** 该能力是否已被显式停止（stop* 系列）。 */
    public boolean isStopped(RouteCapability capability) {
        return disabled.contains(capability);
    }

    public String pattern() {
        return pattern;
    }

    public RouteCapability capability() {
        return capability;
    }

    public Integer mockStatus() {
        return mockStatus;
    }

    public String mockBody() {
        return mockBody;
    }

    public String mockContentType() {
        return mockContentType;
    }

    public Map<String, String> mockHeaders() {
        return mockHeaders;
    }

    public boolean mockIntercept() {
        return mockIntercept;
    }

    public Map<String, Object> mockReplacements() {
        return mockReplacements;
    }

    public List<BodyOp> bodyOps() {
        return bodyOps;
    }

    public String modifyMethod() {
        return modifyMethod;
    }

    public long delayMinMs() {
        return delayMinMs;
    }

    public long delayMaxMs() {
        return delayMaxMs;
    }

    public boolean recordEnabled() {
        return record;
    }

    public boolean captureEnabled() {
        return capture;
    }

    public boolean captureBodyEnabled() {
        return captureBody;
    }

    public int captureBodyLimitBytes() {
        return captureBodyLimitBytes;
    }

    public boolean autoStopOnMatch() {
        return autoStopOnMatch;
    }

    public int minMatches() {
        return minMatches;
    }

    public List<ConditionalField> conditionalReplacements() {
        return conditionalReplacements;
    }

    public Map<String, String> requestHeadersToSet() {
        return requestHeadersToSet;
    }

    public Set<String> requestHeadersToRemove() {
        return requestHeadersToRemove;
    }

    public long delayMs() {
        return delayMs;
    }

    public Integer expectStatus() {
        return expectStatus;
    }

    public Map<String, Object> jsonPathAssertions() {
        return jsonPathAssertions;
    }

    public String expectBodyContains() {
        return expectBodyContains;
    }

    public String expectBodyRegex() {
        return expectBodyRegex;
    }

    public Map<String, String> formFieldAssertions() {
        return formFieldAssertions;
    }

    /** 是否配置了任何 body 级断言（决定响应是否需读 body）。 */
    public boolean hasBodyAssertions() {
        return !jsonPathAssertions.isEmpty() || expectBodyContains != null
                || expectBodyRegex != null || !formFieldAssertions.isEmpty();
    }

    public long monitorTimeoutMs() {
        return monitorTimeoutMs;
    }

    public Integer times() {
        return times;
    }

    public String matchMethod() {
        return matchMethod;
    }

    public Set<String> resourceTypes() {
        return resourceTypes;
    }

    public Map<String, String> matchHeaders() {
        return matchHeaders;
    }

    public Map<String, String> matchQueryParams() {
        return matchQueryParams;
    }

    public String matchBodyRegex() {
        return matchBodyRegex;
    }

    public String matchContentType() {
        return matchContentType;
    }

    public String matchReferrer() {
        return matchReferrer;
    }

    public String matchOrigin() {
        return matchOrigin;
    }

    public String matchFrameUrl() {
        return matchFrameUrl;
    }

    public boolean onlyMainFrame() {
        return onlyMainFrame;
    }

    public boolean onlyApiCall() {
        return onlyApiCall;
    }

    public static Builder builder(String pattern, RouteCapability capability) {
        return new Builder(pattern, capability);
    }

    /** 流式构建器。所有参数方法均返回本构建器，规则构建完成后由 {@link #build()} 冻结。 */
    public static final class Builder {
        private final String pattern;
        private final RouteCapability capability;
        private final Set<RouteCapability> disabled = new LinkedHashSet<>();
        private Integer mockStatus;
        private String mockBody;
        private String mockContentType;
        private final Map<String, String> mockHeaders = new LinkedHashMap<>();
        private boolean mockIntercept;
        private final Map<String, Object> mockReplacements = new LinkedHashMap<>();
        private final List<ConditionalField> conditionalReplacements = new ArrayList<>();
        private final Map<String, String> requestHeadersToSet = new LinkedHashMap<>();
        private final Set<String> requestHeadersToRemove = new LinkedHashSet<>();
        private final List<BodyOp> bodyOps = new ArrayList<>();
        private String modifyMethod;
        private long delayMs;
        private long delayMinMs;
        private long delayMaxMs;
        private Integer expectStatus;
        private final Map<String, Object> jsonPathAssertions = new LinkedHashMap<>();
        private String expectBodyContains;
        private String expectBodyRegex;
        private final Map<String, String> formFieldAssertions = new LinkedHashMap<>();
        private long monitorTimeoutMs = 30_000L;
        private boolean record = true;
        private boolean autoStopOnMatch;
        private int minMatches = 1;
        private boolean capture;
        private boolean captureBody;
        /** 默认响应体截断上限（64 KiB，与 CaptureLimits.DEFAULT_BODY_LIMIT_BYTES 对齐）。 */
        private int captureBodyLimitBytes = 64 * 1024;
        private Integer times;
        private String matchMethod;
        private Set<String> resourceTypes;
        private final Map<String, String> matchHeaders = new LinkedHashMap<>();
        private final Map<String, String> matchQueryParams = new LinkedHashMap<>();
        private String matchBodyRegex;
        private String matchContentType;
        private String matchReferrer;
        private String matchOrigin;
        private String matchFrameUrl;
        private boolean onlyMainFrame = true;
        private boolean onlyApiCall;

        private Builder(String pattern, RouteCapability capability) {
            if (pattern == null || pattern.isEmpty()) {
                throw new IllegalArgumentException("pattern must not be empty");
            }
            this.pattern = pattern;
            this.capability = capability;
        }

        // ── MOCK ──
        public Builder mockStatus(int status) {
            this.mockStatus = status;
            return this;
        }

        public Builder mockBody(String body) {
            this.mockBody = body;
            return this;
        }

        public Builder mockContentType(String contentType) {
            this.mockContentType = contentType;
            return this;
        }

        public Builder mockHeader(String name, String value) {
            this.mockHeaders.put(name, value);
            return this;
        }

        /** 显式拦截真实响应（与静态 mockBody 互斥，互斥在 build 时校验）。 */
        public Builder mockIntercept(boolean intercept) {
            this.mockIntercept = intercept;
            return this;
        }

        /** 添加 intercept 后的字段替换（内容类型感知：JSONPath / XPath / 表单键 / 字面串）。 */
        public Builder mockReplacement(String path, Object value) {
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("replacement path must not be empty");
            }
            this.mockReplacements.put(path, value);
            return this;
        }

        public Builder mockReplacements(Map<String, Object> replacements) {
            if (replacements != null) {
                this.mockReplacements.putAll(replacements);
            }
            return this;
        }

        /** 添加条件字段修改（when...thenSet；仅 JSON 响应生效）。 */
        public Builder conditionalReplacement(ConditionalField field) {
            this.conditionalReplacements.add(field);
            return this;
        }

        // ── MODIFY_REQUEST ──
        public Builder setRequestHeader(String name, String value) {
            this.requestHeadersToSet.put(name, value);
            return this;
        }

        public Builder removeRequestHeader(String name) {
            this.requestHeadersToRemove.add(name);
            return this;
        }

        /** 修改请求 HTTP 方法（resume(options.setMethod) 单请求实现，对齐官方 ResumeOptions 能力）。 */
        public Builder modifyMethod(String method) {
            if (method == null || method.isBlank()) {
                throw new IllegalArgumentException("method must not be blank");
            }
            this.modifyMethod = method;
            return this;
        }

        // ── MODIFY_REQUEST body 级修改 ──
        public Builder bodySet(String path, Object value) {
            this.bodyOps.add(new BodyOp(BodyOp.BodyOpType.SET, path, value));
            return this;
        }

        public Builder bodyAdd(String path, Object value) {
            this.bodyOps.add(new BodyOp(BodyOp.BodyOpType.ADD, path, value));
            return this;
        }

        public Builder bodyRemove(String path) {
            this.bodyOps.add(new BodyOp(BodyOp.BodyOpType.REMOVE, path, null));
            return this;
        }

        // ── DELAY ──
        public Builder delayMs(long delayMs) {
            if (delayMs < 0) {
                throw new IllegalArgumentException("delayMs must be >= 0");
            }
            this.delayMs = delayMs;
            return this;
        }

        /** 启用随机延迟区间（毫秒）；实际延迟在 [min, max] 内随机（对齐现有 RouteDsl.randomDelay）。 */
        public Builder randomDelayMs(long minMs, long maxMs) {
            // 钳制非法配置（与 RouteDispatcher.dispatchDelay 运行期兜底一致）：
            // 构造期拦截会使运行期钳制成为死代码；负值与反转区间一律钳制，避免负延迟/立即放行。
            long min = Math.max(0, minMs);
            long max = Math.max(min, maxMs);
            this.delayMinMs = min;
            this.delayMaxMs = max;
            return this;
        }

        // ── MONITOR ──
        public Builder expectStatus(int status) {
            this.expectStatus = status;
            return this;
        }

        public Builder expectJsonPath(String jsonPath, Object expectedValue) {
            if (jsonPath == null || jsonPath.isEmpty()) {
                throw new IllegalArgumentException("jsonPath must not be empty");
            }
            this.jsonPathAssertions.put(jsonPath, expectedValue);
            return this;
        }

        public Builder expectBodyContains(String substring) {
            if (substring == null || substring.isEmpty()) {
                throw new IllegalArgumentException("substring must not be empty");
            }
            this.expectBodyContains = substring;
            return this;
        }

        public Builder expectBodyRegex(String regex) {
            if (regex == null || regex.isEmpty()) {
                throw new IllegalArgumentException("regex must not be empty");
            }
            this.expectBodyRegex = regex;
            return this;
        }

        public Builder expectFormField(String key, String value) {
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException("form field key must not be empty");
            }
            this.formFieldAssertions.put(key, value);
            return this;
        }

        public Builder monitorTimeoutMs(long timeoutMs) {
            if (timeoutMs < 0) {
                throw new IllegalArgumentException("timeoutMs must be >= 0 (0=never timeout, 对齐现有 RouteDsl.timeout(0) 语义)");
            }
            this.monitorTimeoutMs = timeoutMs;
            return this;
        }

        /** 是否记录请求/响应信息（对齐现有 RouteDsl.record，默认 true）。 */
        public Builder record(boolean enable) {
            this.record = enable;
            return this;
        }

        /** 目标匹配后是否自动停止监控（对齐现有 RouteDsl.autoStopOnMatch，默认 false=持续）。 */
        public Builder autoStopOnMatch(boolean autoStop) {
            this.autoStopOnMatch = autoStop;
            return this;
        }

        /** 最小匹配次数（与 autoStopOnMatch 配合；达到后停止断言，默认 1）。 */
        public Builder minMatches(int minMatches) {
            if (minMatches <= 0) {
                throw new IllegalArgumentException("minMatches must be > 0");
            }
            this.minMatches = minMatches;
            return this;
        }

        // ── CAPTURE（横切采集，与能力正交）──
        /** 开启该 pattern 的 API 信息采集（请求/响应快照；dumpCaptured 消费）。 */
        public Builder capture(boolean enable) {
            this.capture = enable;
            return this;
        }

        /** 是否采集响应体（默认 false；开启后经 IO 线程读取，按 captureBodyLimitBytes 截断）。 */
        public Builder captureBody(boolean enable) {
            this.captureBody = enable;
            return this;
        }

        /** 响应体采集截断上限（字节）。 */
        public Builder captureBodyLimitBytes(int limit) {
            if (limit <= 0) {
                throw new IllegalArgumentException("captureBodyLimitBytes must be > 0");
            }
            this.captureBodyLimitBytes = limit;
            return this;
        }

        // ── 匹配条件 ──
        public Builder matchMethod(String method) {
            this.matchMethod = method;
            return this;
        }

        public Builder resourceTypes(String types) {
            if (types == null || types.trim().isEmpty()) {
                throw new IllegalArgumentException("types must not be empty");
            }
            LinkedHashSet<String> set = new LinkedHashSet<>();
            for (String t : types.split(",")) {
                String trimmed = t.trim();
                if (!trimmed.isEmpty()) {
                    set.add(trimmed);
                }
            }
            this.resourceTypes = set;
            return this;
        }

        /** 仅匹配 XHR 请求。 */
        public Builder onlyXhr() {
            return resourceTypes("xhr");
        }

        /** 仅匹配 Fetch 请求。 */
        public Builder onlyFetch() {
            return resourceTypes("fetch");
        }

        /** 仅匹配 API 调用（xhr + fetch）。 */
        public Builder onlyApi() {
            return resourceTypes("xhr,fetch");
        }

        public Builder matchHeader(String key, String value) {
            this.matchHeaders.put(key, value);
            return this;
        }

        public Builder matchQuery(String key, String value) {
            this.matchQueryParams.put(key, value);
            return this;
        }

        public Builder matchBodyRegex(String regex) {
            this.matchBodyRegex = regex;
            return this;
        }

        public Builder matchContentType(String contentType) {
            this.matchContentType = contentType;
            return this;
        }

        public Builder matchReferrer(String referrer) {
            this.matchReferrer = referrer;
            return this;
        }

        public Builder matchOrigin(String origin) {
            this.matchOrigin = origin;
            return this;
        }

        public Builder matchFrameUrl(String frameUrl) {
            this.matchFrameUrl = frameUrl;
            return this;
        }

        public Builder onlyMainFrame(boolean onlyMainFrame) {
            this.onlyMainFrame = onlyMainFrame;
            return this;
        }

        /** 允许匹配所有 Frame（包括 iframe/worker）。 */
        public Builder allowAllFrames() {
            this.onlyMainFrame = false;
            return this;
        }

        public Builder onlyApiCall(boolean apiOnly) {
            this.onlyApiCall = apiOnly;
            return this;
        }

        /** 允许匹配所有类型请求（含导航、静态资源、iframe）。 */
        public Builder allowAllRequests() {
            this.onlyApiCall = false;
            this.onlyMainFrame = false;
            return this;
        }

        // ── 公共 ──
        public Builder times(int times) {
            if (times < 0) {
                throw new IllegalArgumentException("times must be >= 0 (0=unlimited, 对齐现有 RouteDsl.times(0) 语义)");
            }
            this.times = times == 0 ? null : times; // null=unlimited（PatternBinder 据此不传 RouteOptions.times）
            return this;
        }

        public ApiSpec build() {
            if (mockIntercept && (mockBody != null || mockStatus != null || !mockHeaders.isEmpty())) {
                throw new IllegalArgumentException("interceptResponse() conflicts with static mock body/status/headers; "
                        + "use mockBodyFromFile() + replaceField() for static-body field replacement");
            }
            return new ApiSpec(this);
        }
    }
}
