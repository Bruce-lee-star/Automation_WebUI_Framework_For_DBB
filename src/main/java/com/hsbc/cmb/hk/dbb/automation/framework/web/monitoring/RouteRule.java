package com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring;

import java.util.Map;

/**
 * 路由规则配置实体
 * 统一配置路由匹配、参数、响应体
 */
public class RouteRule {

    /** 匹配路径表达式，支持 glob 模式 */
    private String urlPattern;

    /** 处理类型 */
    private RouteHandleType handleType;

    /** Mock 返回的 JSON 字符串 */
    private String mockJson;

    /** Mock 返回的状态码，默认 200 */
    private int statusCode = 200;

    /** 追加/覆盖请求头 */
    private Map<String, String> appendHeaders;

    /** 自定义替换请求体内容 - 要替换的字符串 */
    private String replaceBodyKey;

    /** 自定义替换请求体内容 - 替换后的字符串 */
    private String replaceBodyValue;

    /** 自定义响应生成器，优先级高于 mockJson */
    private RouteResponseGenerator responseGenerator;

    /** HTTP 方法过滤，null 表示匹配所有方法 */
    private String method;

    /** 是否启用此规则，默认 true */
    private boolean enabled = true;

    /** 规则描述 */
    private String description;

    // ==================== Getters & Setters ====================

    public String getUrlPattern() { return urlPattern; }
    public void setUrlPattern(String urlPattern) { this.urlPattern = urlPattern; }

    public RouteHandleType getHandleType() { return handleType; }
    public void setHandleType(RouteHandleType handleType) { this.handleType = handleType; }

    public String getMockJson() { return mockJson; }
    public void setMockJson(String mockJson) { this.mockJson = mockJson; }

    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }

    public Map<String, String> getAppendHeaders() { return appendHeaders; }
    public void setAppendHeaders(Map<String, String> appendHeaders) { this.appendHeaders = appendHeaders; }

    public String getReplaceBodyKey() { return replaceBodyKey; }
    public void setReplaceBodyKey(String replaceBodyKey) { this.replaceBodyKey = replaceBodyKey; }

    public String getReplaceBodyValue() { return replaceBodyValue; }
    public void setReplaceBodyValue(String replaceBodyValue) { this.replaceBodyValue = replaceBodyValue; }

    public RouteResponseGenerator getResponseGenerator() { return responseGenerator; }
    public void setResponseGenerator(RouteResponseGenerator responseGenerator) { this.responseGenerator = responseGenerator; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    // ==================== 工厂方法 ====================

    /**
     * 创建监控规则
     */
    public static RouteRule monitor(String urlPattern) {
        RouteRule rule = new RouteRule();
        rule.setUrlPattern(urlPattern);
        rule.setHandleType(RouteHandleType.MONITOR);
        return rule;
    }

    /**
     * 创建 Mock 规则
     */
    public static RouteRule mock(String urlPattern, String mockJson) {
        RouteRule rule = new RouteRule();
        rule.setUrlPattern(urlPattern);
        rule.setHandleType(RouteHandleType.MOCK_RESPONSE);
        rule.setMockJson(mockJson);
        return rule;
    }

    /**
     * 创建修改请求规则
     */
    public static RouteRule modify(String urlPattern) {
        RouteRule rule = new RouteRule();
        rule.setUrlPattern(urlPattern);
        rule.setHandleType(RouteHandleType.MODIFY_REQUEST);
        return rule;
    }
}
