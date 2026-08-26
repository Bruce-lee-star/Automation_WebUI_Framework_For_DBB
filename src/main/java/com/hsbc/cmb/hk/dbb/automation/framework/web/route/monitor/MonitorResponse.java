package com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor;

import java.util.Map;

/** 浏览器无关的响应快照；不跨线程持有 Playwright Response/Route 对象。 */
public record MonitorResponse(
        String url,
        int status,
        String body,
        String method,
        String requestBody,
        Map<String, String> requestHeaders,
        Map<String, String> responseHeaders,
        BodyAvailability bodyAvailability
) {
    /** 保留既有构造契约；旧调用根据 body 自动推导状态。 */
    public MonitorResponse(String url, int status, String body, String method, String requestBody,
                           Map<String, String> requestHeaders, Map<String, String> responseHeaders) {
        this(url, status, body, method, requestBody, requestHeaders, responseHeaders,
                body == null ? BodyAvailability.UNAVAILABLE
                        : body.isEmpty() ? BodyAvailability.EMPTY : BodyAvailability.AVAILABLE);
    }

    public MonitorResponse {
        requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
        responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
        bodyAvailability = bodyAvailability == null
                ? (body == null ? BodyAvailability.UNAVAILABLE
                : body.isEmpty() ? BodyAvailability.EMPTY : BodyAvailability.AVAILABLE)
                : bodyAvailability;
    }
}
