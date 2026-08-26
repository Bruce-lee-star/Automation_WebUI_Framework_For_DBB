package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import com.microsoft.playwright.Page;

/**
 * 采集策略接口 — 根据浏览器类型选择不同的采集方式。
 *
 * <p>实现类：
 * <ul>
 *   <li>{@link CDPCaptureStrategy} — Chromium 专用，通过 CDP Network 域旁路监听</li>
 *   <li>{@link PlaywrightEventCaptureStrategy} — 所有浏览器通用，退化到 Playwright 事件</li>
 * </ul>
 *
 * <p>策略选择由 {@link CaptureEngine} 自动判断，用户无需干预。
 */
public interface CaptureStrategy {

    /**
     * 在指定页面上启动采集。
     *
     * <p>订阅浏览器事件，产出 {@link CaptureEvent} 投喂到 {@link CaptureRingBuffer}。
     *
     * @param page        Playwright Page 实例
     * @param ringBuffer  事件缓冲区
     */
    void start(Page page, CaptureRingBuffer ringBuffer);

    /**
     * 停止采集，清理事件订阅。
     */
    void stop();

    /**
     * 策略名称（用于日志和指标标识）。
     */
    String name();

    /**
     * 判断当前策略是否可用。
     *
     * @return true=可用，false=不可用（如 CDP 在非 Chromium 浏览器上）
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 当前策略是否能为事件链提供响应体（body）。
     *
     * <p>默认 false；仅 CDP 旁路具备 body 能力。Playwright 事件策略不提供 body，
     * 此时 Monitor 必须回退到既有 {@code page.waitForResponse} 同步路径。
     */
    default boolean providesResponseBody() {
        return false;
    }

    /**
     * 读取指定 requestId 的响应体，由 {@link BodyReader} 在专用线程池（bodyFetchPool）中调用。
     *
     * <p>绝不在浏览器事件回调线程中执行——实现可安全调用
     * {@code CDP Network.getResponseBody} 或 {@code Response.body()}。
     * 读取失败或请求不可用时返回 null（调用方会投喂 null 的 RESPONSE_BODY 事件闭合 slot）。
     *
     * @param requestId 本策略发布的请求关联键
     * @return 响应体字节；失败/不可用返回 null
     */
    default byte[] readResponseBody(String requestId) {
        return null;
    }
}