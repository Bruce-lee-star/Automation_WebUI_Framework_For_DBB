package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import java.util.*;

/**
 * 路由规则数据模型 — 统一承载 MONITOR / MODIFY / MOCK 三种类型的配置。
 *
 * <p>校验规则：
 * <ul>
 *   <li>{@code urlPattern} 不允许为 blank（空/纯空格字符串）</li>
 *   <li>{@code mockStatus} 必须是合法 HTTP 状态码（100 ≤ status < 600）</li>
 *   <li>{@code expectedStatus} 必须是合法 HTTP 状态码（100 ≤ status < 600）</li>
 * </ul>
 *
 * <p>请求条件匹配（新增）：
 * <ul>
 *   <li>{@code resourceTypes} — 资源类型过滤（xhr/fetch/script/...）默认不限制</li>
 *   <li>{@code matchHeaders} — 请求头精确匹配</li>
 *   <li>{@code matchQuery} — Query 参数精确匹配</li>
 *   <li>{@code matchBodyRegex} — 请求体正则匹配</li>
 *   <li>{@code matchContentType} — Content-Type 包含匹配</li>
 *   <li>{@code matchReferrer / matchOrigin} — 来源匹配</li>
 *   <li>{@code matchFrameUrl / onlyMainFrame} — Frame 匹配</li>
 * </ul>
 */
public class RouteRule {

    private String urlPattern;
    private RouteHandleType type = RouteHandleType.MONITOR;

    // Mock
    private String mockBody;
    private int mockStatus = 200;
    private Map<String, String> mockHeaders;
    /** Mock 响应批量字段替换：JSONPath → 替换值。
     *  支持通配符 [*]（如 $.users[*].name → newName 将所有元素的 name 替换） */
    private Map<String, String> mockReplaceFields;

    // Modify
    private Map<String, String> addHeaders;
    private Map<String, String> replaceBodyPairs;
    private String method;

    // Monitor + 断言
    private boolean record = true;
    private Integer expectedStatus;  // 期望的 HTTP 状态码
    private Map<String, Object> jsonPathAssertions;  // JSONPath 断言

    // Monitor 自动停止控制
    private long timeoutMs = 0;          // 超时（毫秒），0 = 永不超时
    private int minMatches = 1;          // 最小匹配次数，满足后触发 auto-stop
    private boolean autoStopOnMatch = true;   // 目标匹配后是否自动停止（MONITOR 默认停止；MOCK/MODIFY 由 DSL 覆盖为 false）

    // ═══════════════════════════════════════════════════════════
    // 请求条件匹配（新增）
    // ═══════════════════════════════════════════════════════════

    /** 允许的资源类型，逗号分隔（如 "xhr,fetch"）。null/空 = 不限制。 */
    private String resourceTypes;

    /** HTTP Method 匹配（如 "GET","POST"）。null = 不限制。 */
    private String matchMethod;

    /** 请求头精确匹配。所有 key-value 必须完全匹配。 */
    private Map<String, String> matchHeaders;

    /** Query 参数精确匹配。所有 key-value 必须完全匹配。 */
    private Map<String, String> matchQuery;

    /** 请求体正则匹配。null = 不检查。 */
    private String matchBodyRegex;

    /** Content-Type 包含匹配（如 "json" 匹配 "application/json"）。null = 不检查。 */
    private String matchContentType;

    /** Referrer 包含匹配。null = 不检查。 */
    private String matchReferrer;

    /** Origin 包含匹配。null = 不检查。 */
    private String matchOrigin;

    /** Frame URL 包含匹配。null = 不检查。 */
    private String matchFrameUrl;

    /** 是否只拦截主 Frame 请求（跳过 iframe/worker）。默认 true。 */
    private boolean onlyMainFrame = true;

    /** 是否仅拦截 API 调用（xhr/fetch + 跳过 navigation）。默认 false（匹配所有请求类型）。 */
    private boolean onlyApiCall = false;

    // ─── Getters ────────────────────────────────────────────────

    public String getUrlPattern() {
        return urlPattern;
    }

    public RouteHandleType getType() {
        return type;
    }

    public String getMockBody() {
        return mockBody;
    }

    public int getMockStatus() {
        return mockStatus;
    }

    public Map<String, String> getMockHeaders() {
        return mockHeaders;
    }

    /**
     * 获取 Mock 响应批量字段替换映射（JSONPath → 值）。
     * 支持通配符 [*] 批量替换 List 中所有元素的字段。
     */
    public Map<String, String> getMockReplaceFields() {
        return mockReplaceFields;
    }

    public Map<String, String> getAddHeaders() {
        return addHeaders;
    }

    public Map<String, String> getReplaceBodyPairs() {
        return replaceBodyPairs;
    }

    public String getMethod() {
        return method;
    }

    public boolean isRecord() {
        return record;
    }

    public Integer getExpectedStatus() {
        return expectedStatus;
    }

    public Map<String, Object> getJsonPathAssertions() {
        return jsonPathAssertions;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public int getMinMatches() {
        return minMatches;
    }

    public boolean isAutoStopOnMatch() {
        return autoStopOnMatch;
    }

    // ─── 请求条件匹配 Getters ────────────────────────────────────

    public String getResourceTypes() { return resourceTypes; }

    /** 获取解析后的资源类型集合（不可变）。 */
    public Set<String> getResourceTypeSet() {
        if (resourceTypes == null || resourceTypes.trim().isEmpty()) return null;
        String[] parts = resourceTypes.trim().toLowerCase().split("[,;\\s]+");
        Set<String> set = new LinkedHashSet<>();
        for (String p : parts) {
            if (!p.isEmpty()) set.add(p);
        }
        return set.isEmpty() ? null : Collections.unmodifiableSet(set);
    }

    public String getMatchMethod() { return matchMethod; }
    public Map<String, String> getMatchHeaders() { return matchHeaders; }
    public Map<String, String> getMatchQuery() { return matchQuery; }
    public String getMatchBodyRegex() { return matchBodyRegex; }
    public String getMatchContentType() { return matchContentType; }
    public String getMatchReferrer() { return matchReferrer; }
    public String getMatchOrigin() { return matchOrigin; }
    public String getMatchFrameUrl() { return matchFrameUrl; }
    public boolean isOnlyMainFrame() { return onlyMainFrame; }
    public boolean isOnlyApiCall() { return onlyApiCall; }

    // ─── Setters（带参数校验）────────────────────────────────────

    /**
     * 设置 URL pattern。
     *
     * @param urlPattern URL pattern，不能为 blank
     * @throws IllegalArgumentException 如果 urlPattern 为 blank
     */
    public void setUrlPattern(String urlPattern) {
        if (urlPattern == null || urlPattern.trim().isEmpty()) {
            throw new IllegalArgumentException("urlPattern cannot be blank");
        }
        this.urlPattern = urlPattern;
    }

    public void setType(RouteHandleType type) {
        this.type = type;
    }

    public void setMockBody(String mockBody) {
        this.mockBody = mockBody;
    }

    /**
     * 设置 Mock HTTP 状态码。
     *
     * @param mockStatus HTTP 状态码，必须在 [100, 600) 范围内
     * @throws IllegalArgumentException 如果状态码非法
     */
    public void setMockStatus(int mockStatus) {
        if (mockStatus < 100 || mockStatus >= 600) {
            throw new IllegalArgumentException("Invalid HTTP status: " + mockStatus + ". Must be in range [100, 600).");
        }
        this.mockStatus = mockStatus;
    }

    public void setMockHeaders(Map<String, String> mockHeaders) {
        this.mockHeaders = mockHeaders;
    }

    /**
     * 添加一个 Mock 响应字段替换（JSONPath → 值）。
     * 支持通配符 [*] 批量替换 List 中所有元素的字段，如 $.users[*].name。
     * <p>支持多次调用，添加到 Map 中。
     */
    public void addMockReplaceField(String jsonPath, String value) {
        if (mockReplaceFields == null) {
            mockReplaceFields = new LinkedHashMap<>();
        }
        mockReplaceFields.put(jsonPath, value);
    }

    public void setMockReplaceFields(Map<String, String> mockReplaceFields) {
        this.mockReplaceFields = mockReplaceFields;
    }

    public void setAddHeaders(Map<String, String> addHeaders) {
        this.addHeaders = addHeaders;
    }

    /**
     * 添加一个请求体字段替换（JSONPath → 值）。
     * <p>支持多次调用，添加到 Map 中。
     */
    public void addReplaceBodyPair(String key, String value) {
        if (replaceBodyPairs == null) {
            replaceBodyPairs = new LinkedHashMap<>();
        }
        replaceBodyPairs.put(key, value);
    }

    public void setReplaceBodyPairs(Map<String, String> replaceBodyPairs) {
        this.replaceBodyPairs = replaceBodyPairs;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public void setRecord(boolean record) {
        this.record = record;
    }

    /**
     * 设置期望的 HTTP 状态码（用于断言）。
     *
     * @param expectedStatus HTTP 状态码，必须在 [100, 600) 范围内
     * @throws IllegalArgumentException 如果状态码非法
     */
    public void setExpectedStatus(Integer expectedStatus) {
        if (expectedStatus != null && (expectedStatus < 100 || expectedStatus >= 600)) {
            throw new IllegalArgumentException("Invalid expected HTTP status: " + expectedStatus + ". Must be in range [100, 600).");
        }
        this.expectedStatus = expectedStatus;
    }

    public void setJsonPathAssertions(Map<String, Object> jsonPathAssertions) {
        this.jsonPathAssertions = jsonPathAssertions;
    }

    /**
     * 设置 Monitor 超时（毫秒）。0 表示永不超时。
     *
     * @param timeoutMs 超时毫秒数，必须 ≥ 0
     * @throws IllegalArgumentException 如果 timeoutMs < 0
     */
    public void setTimeoutMs(long timeoutMs) {
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("timeoutMs must be >= 0, got: " + timeoutMs);
        }
        this.timeoutMs = timeoutMs;
    }

    /**
     * 设置最小匹配次数（达到后触发 auto-stop）。
     *
     * @param minMatches 最小匹配次数，必须 ≥ 1
     * @throws IllegalArgumentException 如果 minMatches < 1
     */
    public void setMinMatches(int minMatches) {
        if (minMatches < 1) {
            throw new IllegalArgumentException("minMatches must be >= 1, got: " + minMatches);
        }
        this.minMatches = minMatches;
    }

    public void setAutoStopOnMatch(boolean autoStopOnMatch) {
        this.autoStopOnMatch = autoStopOnMatch;
    }

    // ─── 请求条件匹配 Setters ──────────────────────────────────

    /**
     * 设置允许匹配的资源类型（逗号分隔）。
     * <p>例如：{@code "xhr,fetch"} 只匹配 XHR 和 Fetch 请求。
     * <p>设为 null 或空字符串 = 不限制（配合 onlyApiCall 使用）。
     */
    public void setResourceTypes(String resourceTypes) {
        this.resourceTypes = resourceTypes;
    }

    /**
     * 设置 HTTP Method 匹配条件。
     * @param method 如 "GET","POST","PUT","DELETE"。null = 不限制。
     */
    public void setMatchMethod(String method) {
        this.matchMethod = method;
    }

    /**
     * 添加一个请求头匹配条件。
     * @param key   请求头名称
     * @param value 期望的值（精确匹配）
     */
    public void addMatchHeader(String key, String value) {
        if (matchHeaders == null) matchHeaders = new HashMap<>();
        matchHeaders.put(key, value);
    }

    /**
     * 添加一个 Query 参数匹配条件。
     * @param key   参数名
     * @param value 期望的值（精确匹配）
     */
    public void addMatchQuery(String key, String value) {
        if (matchQuery == null) matchQuery = new HashMap<>();
        matchQuery.put(key, value);
    }

    public void setMatchHeaders(Map<String, String> matchHeaders) {
        this.matchHeaders = matchHeaders;
    }

    public void setMatchQuery(Map<String, String> matchQuery) {
        this.matchQuery = matchQuery;
    }

    /**
     * 设置请求体正则表达式匹配。
     * @param regex 正则表达式（Java Pattern 语法）
     */
    public void setMatchBodyRegex(String regex) {
        this.matchBodyRegex = regex;
    }

    /**
     * 设置 Content-Type 包含匹配。
     * @param contentType 如 "json" 可匹配 "application/json;charset=UTF-8"
     */
    public void setMatchContentType(String contentType) {
        this.matchContentType = contentType;
    }

    /**
     * 设置 Referrer 包含匹配。
     * @param referrer Referrer URL 中必须包含的字符串
     */
    public void setMatchReferrer(String referrer) {
        this.matchReferrer = referrer;
    }

    /**
     * 设置 Origin 包含匹配。
     * @param origin Origin 中必须包含的字符串
     */
    public void setMatchOrigin(String origin) {
        this.matchOrigin = origin;
    }

    /**
     * 设置 Frame URL 包含匹配。
     * @param frameUrl Frame URL 中必须包含的字符串
     */
    public void setMatchFrameUrl(String frameUrl) {
        this.matchFrameUrl = frameUrl;
    }

    /**
     * 是否只匹配主 Frame 请求（跳过 iframe/worker）。
     * 默认 true。
     */
    public void setOnlyMainFrame(boolean onlyMainFrame) {
        this.onlyMainFrame = onlyMainFrame;
    }

    /**
     * 是否仅匹配 API 调用（遇到 navigation 请求自动跳过）。
     * 默认 false（不限制请求类型）。
     */
    public void setOnlyApiCall(boolean onlyApiCall) {
        this.onlyApiCall = onlyApiCall;
    }

    // ═══════════════════════════════════════════════════════════
    // equals / hashCode（RouteRule 作为 ConcurrentHashMap key）
    // ═══════════════════════════════════════════════════════════

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RouteRule that = (RouteRule) o;
        return Objects.equals(urlPattern, that.urlPattern)
                && type == that.type
                && Objects.equals(method, that.method);
    }

    @Override
    public int hashCode() {
        return Objects.hash(urlPattern, type, method);
    }
}
