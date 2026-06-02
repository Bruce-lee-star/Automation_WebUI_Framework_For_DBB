package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

/**
 * 面向测试步骤的 Route API 捕获入口。
 *
 * <pre>{@code
 * ApiCaptureContext ctx = RouteMonitor.context();
 *
 * // 获取完整调用快照
 * CapturedApiCall call = ctx.getLastApiCall("/api/track");
 * int status = call.statusCode();
 * String token = call.responseHeader("Authorization");
 * Object id = call.json("$.data.id");
 *
 * // 获取全部调用历史
 * List<CapturedApiCall> calls = ctx.getApiCalls("/api/track");
 * String body = ctx.getStoredResponse("/api/track");  // 向后兼容
 * }</pre>
 */
public final class RouteMonitor {

    private RouteMonitor() {
    }

    /**
     * 获取当前线程的 API 捕获上下文。
     */
    public static ApiCaptureContext context() {
        return ApiCaptureContext.getCurrent();
    }
}
