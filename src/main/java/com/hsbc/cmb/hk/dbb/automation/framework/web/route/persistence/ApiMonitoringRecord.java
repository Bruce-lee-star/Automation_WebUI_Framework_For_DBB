package com.hsbc.cmb.hk.dbb.automation.framework.web.route.persistence;

import java.time.LocalDateTime;
import java.util.Map;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SensitiveDataSanitizer;

/**
 * API 监控记录实体 — 对应数据库表 {@code route_monitor_record}。
 *
 * <p>由 {@link DatabaseStoreMonitorCallback} 在捕获 API 响应后构建，
 * 交由 {@link ApiMonitoringRepository} 持久化到数据库。
 *
 * <p>⭐ 合规：{@link Builder#build()} 在唯一入口对 headers 与 responseBody 做敏感数据脱敏，
 * 确保所有落库/落盘路径均不泄露 Authorization / Cookie / password / token 等凭证与 PII。
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
    private final int attempts;

    private ApiMonitoringRecord(Builder builder) {
        this.endpoint = builder.endpoint;
        // ⭐ P0 修复：requestUrl 此前未脱敏，导致 query 中的 access_token / id_token /
        //    apikey / signature 等凭据以明文入库。URL 与 header/body 同等敏感，必须一并脱敏。
        this.requestUrl = SensitiveDataSanitizer.sanitizeUrl(builder.requestUrl);
        this.method = builder.method;
        this.statusCode = builder.statusCode;
        // ⭐ 统一脱敏入口（审计 P0-合规 / P1-4）：Header 与 Body 在构建时即脱敏
        this.requestHeaders = SensitiveDataSanitizer.sanitizeHeaders(builder.requestHeaders);
        this.responseHeaders = SensitiveDataSanitizer.sanitizeHeaders(builder.responseHeaders);
        this.responseBody = SensitiveDataSanitizer.sanitizeBody(builder.responseBody);
        this.bodyLength = bodyLength(this.responseBody);
        this.capturedAt = builder.capturedAt;
        this.testRunId = builder.testRunId;
        this.attempts = builder.attempts;
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
    public int attempts() { return attempts; }
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
        private int attempts;

        public Builder endpoint(String endpoint) { this.endpoint = endpoint; return this; }
        public Builder requestUrl(String requestUrl) { this.requestUrl = requestUrl; return this; }
        public Builder method(String method) { this.method = method; return this; }
        public Builder statusCode(int statusCode) { this.statusCode = statusCode; return this; }
        public Builder requestHeaders(Map<String, String> requestHeaders) { this.requestHeaders = requestHeaders; return this; }
        public Builder responseHeaders(Map<String, String> responseHeaders) { this.responseHeaders = responseHeaders; return this; }
        public Builder responseBody(String responseBody) { this.responseBody = responseBody; return this; }
        public Builder capturedAt(long capturedAt) { this.capturedAt = capturedAt; return this; }
        public Builder testRunId(String testRunId) { this.testRunId = testRunId; return this; }
        public Builder attempts(int attempts) { this.attempts = attempts; return this; }

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
