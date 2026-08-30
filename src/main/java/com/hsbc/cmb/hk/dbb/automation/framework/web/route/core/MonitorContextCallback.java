package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import java.util.Map;

/**
 * Monitor 响应回调（携带 {@link ApiCaptureContext} 重载）。
 *
 * <p>与 {@link MonitorCallback} 的区别：本接口把当前 {@link ApiCaptureContext} 也传入回调，
 * 使回调能经 {@code context.setShared(key, value)} 把数据回传到主线程
 * （主线程再用 {@code ctx.awaitShared(key, timeout)} 读取），从而让 onResponse 真正
 * 「影响」主线程变量 / 分支判断。
 *
 * <p>回调在 RouteAsyncPool 的「Monitor 回调专用串行线程」执行，不阻塞 Playwright 事件线程。
 * 在其中设置的框架级全局状态（如 {@code NLSUtils.setLanguage}）因已全局化而对主线程可见，
 * 无需额外桥接。
 *
 * <p><b>与 MonitorCallback 的关系</b>：两者并存，由 DSL 的 {@code onResponse(...)} 重载按
 * 回调参数个数（5 参 / 6 参）自动选择；本接口的 6 参 lambda 不会再被降级为 5 参委托，
 * 从而能真正拿到 {@code ApiCaptureContext}。
 */
@FunctionalInterface
public interface MonitorContextCallback {

    /**
     * 当 Monitor 捕获到匹配请求且断言通过时调用。
     *
     * <p><b>注意</b>：{@code responseHeaders} 中的 key 均为 Playwright 规范化后的
     * 小写形式（如 {@code content-type}）。若需按原始大小写查找，请用
     * {@link MonitorCallback#headerValue(Map, String)} 工具方法。
     *
     * @param url             请求 URL
     * @param status          HTTP 状态码
     * @param body            响应体字符串
     * @param responseHeaders 响应头快照（不可变副本，线程安全；key 为小写）
     * @param method          请求方法（GET/POST/PUT/DELETE...）
     * @param context         当前 API 捕获上下文（回调线程与主线程共享同一实例）
     */
    void onResponse(String url, int status, String body,
                   Map<String, String> responseHeaders, String method,
                   ApiCaptureContext context);
}
