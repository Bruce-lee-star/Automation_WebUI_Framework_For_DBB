package com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring;

import java.util.Map;

/**
 * 路由响应生成器接口
 * 用于自定义监控回调和动态 Mock 响应
 *
 * <p>使用示例：
 * <pre>{@code
 * // 监控回调
 * RouteResponseGenerator monitorCallback = data -> {
 *     System.out.println("API called: " + data.getUrl());
 *     System.out.println("Status: " + data.getStatus());
 * };
 *
 * // 动态 Mock
 * RouteResponseGenerator dynamicMock = data -> {
 *     if (data.getUrl().contains("user")) {
 *         return "{\"id\":1,\"name\":\"动态用户\"}";
 *     }
 *     return "{\"error\":\"not found\"}";
 * };
 * }</pre>
 */
@FunctionalInterface
public interface RouteResponseGenerator {

    /**
     * 响应数据封装
     */
    class ResponseData {
        private final String url;
        private final String method;
        private final int status;
        private final String body;
        private final Map<String, String> headers;

        public ResponseData(String url, String method, int status, String body, Map<String, String> headers) {
            this.url = url;
            this.method = method;
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        public String getUrl() { return url; }
        public String getMethod() { return method; }
        public int getStatus() { return status; }
        public String getBody() { return body; }
        public Map<String, String> getHeaders() { return headers; }

        @Override
        public String toString() {
            return String.format("ResponseData{url='%s', method='%s', status=%d, bodyLen=%d}",
                RouteUtil.simplifyUrl(url), method, status,
                body != null ? body.length() : 0);
        }
    }

    /**
     * 响应回调方法
     *
     * @param data 响应数据
     */
    void onResponse(ResponseData data);

    /**
     * 默认实现：不做任何处理
     */
    static RouteResponseGenerator noop() {
        return data -> {};
    }

    /**
     * 打印日志的回调
     */
    static RouteResponseGenerator logging() {
        return data -> {
            System.out.printf("[Route] %s %s -> %d%n", data.getMethod(), data.getUrl(), data.getStatus());
        };
    }

    /**
     * 断言响应的回调
     *
     * @param urlPattern URL 模式
     * @param expectedStatus 期望的状态码
     * @return 断言回调
     */
    static RouteResponseGenerator assertStatus(String urlPattern, int expectedStatus) {
        return data -> {
            if (data.getUrl().contains(urlPattern)) {
                if (data.getStatus() != expectedStatus) {
                    throw new AssertionError(String.format(
                        "Status code mismatch for %s: expected %d, got %d",
                        urlPattern, expectedStatus, data.getStatus()
                    ));
                }
            }
        };
    }
}
