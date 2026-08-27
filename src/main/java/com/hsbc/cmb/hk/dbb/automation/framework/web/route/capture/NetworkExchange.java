package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor.BodyAvailability;

import java.util.Map;

/**
 * 单次网络请求的不可变终态快照。
 *
 * <p>仅用于采集管道的旁路诊断和后续 Monitor 迁移；当前不会替代
 * {@code CapturedApiCall}，也不会触发断言、回调或持久化。</p>
 */
public record NetworkExchange(
        String requestId,
        CaptureEvent.Source source,
        int browserContextIdentity,
        int pageIdentity,
        String method,
        String url,
        ResourceType resourceType,
        Map<String, String> requestHeaders,
        byte[] requestBody,
        Integer status,
        Map<String, String> responseHeaders,
        byte[] responseBody,
        String contentType,
        BodyAvailability bodyAvailability,
        Failure failure,
        long requestTimestamp,
        long terminalTimestamp
) {
    public NetworkExchange {
        requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
        requestBody = requestBody == null ? null : requestBody.clone();
        responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
        responseBody = responseBody == null ? null : responseBody.clone();
        bodyAvailability = bodyAvailability == null ? BodyAvailability.UNAVAILABLE : bodyAvailability;
    }

    /** 终态失败类型；不以异常跨线程传递。 */
    public record Failure(FailureKind kind, String reason) {
        public Failure {
            kind = kind == null ? FailureKind.UNKNOWN : kind;
            reason = reason == null ? "" : reason;
        }
    }

    public enum FailureKind {
        NETWORK_FAILED,
        CORRELATION_TIMEOUT,
        BODY_UNAVAILABLE,
        UNKNOWN
    }

    @Override
    public byte[] requestBody() {
        return requestBody == null ? null : requestBody.clone();
    }

    @Override
    public byte[] responseBody() {
        return responseBody == null ? null : responseBody.clone();
    }
}
