package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import java.util.Map;

/**
 * 路由规则数据模型 — 统一承载 MONITOR / MODIFY / MOCK 三种类型的配置。
 *
 * <p>校验规则：
 * <ul>
 *   <li>{@code urlPattern} 不允许为 blank（空/纯空格字符串）</li>
 *   <li>{@code mockStatus} 必须是合法 HTTP 状态码（100 ≤ status < 600）</li>
 *   <li>{@code expectedStatus} 必须是合法 HTTP 状态码（100 ≤ status < 600）</li>
 * </ul>
 */
public class RouteRule {

    private String urlPattern;
    private RouteHandleType type = RouteHandleType.MONITOR;

    // Mock
    private String mockBody;
    private int mockStatus = 200;
    private Map<String, String> mockHeaders;

    // Modify
    private Map<String, String> addHeaders;
    private String replaceBodyKey;
    private String replaceBodyValue;
    private String method;

    // Monitor + 断言
    private boolean record = true;
    private Integer expectedStatus;  // 期望的 HTTP 状态码
    private Map<String, Object> jsonPathAssertions;  // JSONPath 断言

    // Monitor 自动停止控制
    private long timeoutMs = 0;          // 超时（毫秒），0 = 永不超时
    private int minMatches = 1;          // 最小匹配次数，满足后触发 auto-stop
    private boolean autoStopOnMatch = true;  // 目标匹配后是否自动停止监控

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

    public Map<String, String> getAddHeaders() {
        return addHeaders;
    }

    public String getReplaceBodyKey() {
        return replaceBodyKey;
    }

    public String getReplaceBodyValue() {
        return replaceBodyValue;
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

    public void setAddHeaders(Map<String, String> addHeaders) {
        this.addHeaders = addHeaders;
    }

    public void setReplaceBodyKey(String replaceBodyKey) {
        this.replaceBodyKey = replaceBodyKey;
    }

    public void setReplaceBodyValue(String replaceBodyValue) {
        this.replaceBodyValue = replaceBodyValue;
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
}
