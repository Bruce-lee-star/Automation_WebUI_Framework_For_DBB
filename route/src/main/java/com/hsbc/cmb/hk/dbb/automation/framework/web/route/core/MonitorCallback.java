package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import java.util.Map;

/**
 * Monitor 响应回调契约 — 每次 Monitor 捕获到匹配请求且断言通过时触发。
 *
 * <p>本接口为<b>框架内部</b>契约：由 {@code MonitorHandler} 在断言通过后直接调用，
 * 当前实现为响应体持久化（{@code FileStoreMonitorCallback} / {@code DatabaseStoreMonitorCallback}）。
 * 用户侧不再通过 DSL 注册自定义 {@code onResponse} 回调。
 *
 * <p>回调异常会被捕获并记录日志，不会中断测试流程。
 *
 * <p><b>注意</b>：Playwright 会将 HTTP header 名称规范化为小写
 * （如 {@code content-type} 而非 {@code Content-Type}）。
 * 建议使用 {@link #headerValue(Map, String)} 进行大小写不敏感查找，
 * 而非直接调用 {@code headers.get("Content-Type")}。
 */
@FunctionalInterface
public interface MonitorCallback {

    /**
     * 当 Monitor 捕获到匹配请求且断言通过时调用。
     *
     * <p><b>注意</b>：{@code responseHeaders} 中的 key 均为 Playwright 规范化后的
     * 小写形式（如 {@code content-type}）。若需按原始大小写查找，请使用
     * {@link #headerValue(Map, String)} 工具方法。
     *
     * @param url             请求 URL
     * @param status          HTTP 状态码
     * @param body            响应体字符串
     * @param responseHeaders 响应头快照（不可变副本，线程安全；key 为小写）
     * @param method          请求方法（GET/POST/PUT/DELETE...）
     */
    void onResponse(String url, int status, String body,
                   Map<String, String> responseHeaders, String method);

    /**
     * 从 Header Map 中按名称查找值（大小写不敏感）。
     * <p>先精确匹配，失败后遍历 key 进行 {@code equalsIgnoreCase} 比较。
     *
     * <pre>{@code
     * // 以下两种写法均可，无需关心 Playwright 的大小写规范化
     * String ct1 = MonitorCallback.headerValue(headers, "Content-Type");
     * String ct2 = MonitorCallback.headerValue(headers, "content-type");
     * }</pre>
     *
     * @param headers    Header Map（可为 null）
     * @param headerName Header 名称（大小写不敏感）
     * @return Header 值，未找到或 headers 为 null 时返回 null
     */
    static String headerValue(Map<String, String> headers, String headerName) {
        if (headers == null || headerName == null) return null;
        // 先精确匹配（Playwright 规范化为小写，精确匹配即命中）
        String value = headers.get(headerName);
        if (value != null) return value;
        // 大小写不敏感回退
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(headerName)) {
                return e.getValue();
            }
        }
        return null;
    }
}
