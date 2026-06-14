package com.hsbc.cmb.hk.dbb.automation.framework.web.route.persistence;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * API 监控记录实体 — 对应数据库表 {@code route_monitor_record}。
 *
 * <p>由 {@link DatabaseStoreMonitorCallback} 在捕获 API 响应后构建，
 * 交由 {@link ApiMonitoringRepository} 持久化到数据库。
 */
public class ApiMonitoringRecord {

    private final String endpoint;
    private final String requestUrl;
    private final String method;
    private final int statusCode;
    private final Map<String, String> requestHeaders;
    private final Map<String, String> responseHeaders;
    private final String responseBody;
    private final int bodyLength;
    private final long capturedAt;
    private final String testRunId;

    private ApiMonitoringRecord(Builder builder) {
        this.endpoint = builder.endpoint;
        this.requestUrl = builder.requestUrl;
        this.method = builder.method;
        this.statusCode = builder.statusCode;
        this.requestHeaders = builder.requestHeaders;
        this.responseHeaders = builder.responseHeaders;
        this.responseBody = builder.responseBody;
        this.bodyLength = bodyLength(this.responseBody);
        this.capturedAt = builder.capturedAt;
        this.testRunId = builder.testRunId;
    }

    // ── Getters ──

    public String endpoint() { return endpoint; }
    public String requestUrl() { return requestUrl; }
    public String method() { return method; }
    public int statusCode() { return statusCode; }
    public Map<String, String> requestHeaders() { return requestHeaders; }
    public Map<String, String> responseHeaders() { return responseHeaders; }
    public String responseBody() { return responseBody; }
    public int bodyLength() { return bodyLength; }
    public long capturedAt() { return capturedAt; }
    public String testRunId() { return testRunId; }
    public boolean isOk() { return statusCode >= 200 && statusCode < 300; }

    /** 安全截断响应体（限制最大存储长度） */
    public String safeResponseBody(int maxChars) {
        if (responseBody == null) return null;
        return responseBody.length() > maxChars ? responseBody.substring(0, maxChars) : responseBody;
    }

    private static int bodyLength(String body) {
        return body != null ? body.length() : 0;
    }

    // ── Builder ──

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String endpoint;
        private String requestUrl;
        private String method;
        private int statusCode;
        private Map<String, String> requestHeaders;
        private Map<String, String> responseHeaders;
        private String responseBody;
        private long capturedAt;
        private String testRunId;

        public Builder endpoint(String endpoint) { this.endpoint = endpoint; return this; }
        public Builder requestUrl(String requestUrl) { this.requestUrl = requestUrl; return this; }
        public Builder method(String method) { this.method = method; return this; }
        public Builder statusCode(int statusCode) { this.statusCode = statusCode; return this; }
        public Builder requestHeaders(Map<String, String> requestHeaders) { this.requestHeaders = requestHeaders; return this; }
        public Builder responseHeaders(Map<String, String> responseHeaders) { this.responseHeaders = responseHeaders; return this; }
        public Builder responseBody(String responseBody) { this.responseBody = responseBody; return this; }
        public Builder capturedAt(long capturedAt) { this.capturedAt = capturedAt; return this; }
        public Builder testRunId(String testRunId) { this.testRunId = testRunId; return this; }

        public ApiMonitoringRecord build() {
            return new ApiMonitoringRecord(this);
        }
    }

    @Override
    public String toString() {
        return String.format("ApiMonitoringRecord{%s %s → %d, body=%d chars}",
                method, endpoint, statusCode, bodyLength);
    }
}
