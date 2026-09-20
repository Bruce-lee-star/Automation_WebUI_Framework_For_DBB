package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import java.util.List;

/**
 * 单条 API 监控失败记录（跨模块安全的数据载体，避免 reporting 直接依赖 route）。
 *
 * <p>字段已在 route 侧写出器统一脱敏（URL / body 经 {@code SensitiveDataSanitizer} 收口），
 * 可直接在 HTML 报告中展示，无需 reporting 模块再处理敏感信息。
 */
public class MonitorFailureItem {

    private final String owner;
    private final String feature;
    private final String pattern;
    private final String status;
    private final String method;
    private final String requestUrl;
    private final String reason;
    private final List<String> scenarios;
    private final String requestBody;
    private final String responseBody;

    public MonitorFailureItem(String owner, String feature, String pattern, String status,
                             String method, String requestUrl, String reason,
                             List<String> scenarios, String requestBody, String responseBody) {
        this.owner = owner;
        this.feature = feature;
        this.pattern = pattern;
        this.status = status;
        this.method = method;
        this.requestUrl = requestUrl;
        this.reason = reason;
        // 防御性拷贝为不可变集合，避免返回内部可变引用（EI_EXPOSE_REP2）
        this.scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        this.requestBody = requestBody;
        this.responseBody = responseBody;
    }

    public String getOwner() {
        return owner;
    }

    public String getFeature() {
        return feature;
    }

    public String getPattern() {
        return pattern;
    }

    public String getStatus() {
        return status;
    }

    public String getMethod() {
        return method;
    }

    public String getRequestUrl() {
        return requestUrl;
    }

    public String getReason() {
        return reason;
    }

    public List<String> getScenarios() {
        return scenarios;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
