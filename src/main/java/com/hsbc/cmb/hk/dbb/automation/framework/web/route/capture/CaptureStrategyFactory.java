package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import com.microsoft.playwright.Page;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 采集策略工厂 — 根据浏览器内核显式分发采集方案。
 *
 * <p>分发矩阵（确定性，不依赖异常降级）：
 * <ul>
 *   <li><b>chromium</b> → {@link CDPCaptureStrategy}（CDP Network 域旁路监听，可读取响应体，
 *       能看到 route.fetch 修改后的请求体，保真度最高）</li>
 *   <li><b>firefox</b> → {@link PlaywrightEventCaptureStrategy}（CDP 在 Firefox 上 Network 域行为不一致、
 *       响应体可能读不到，故用跨浏览器原生 page.onRequest/onResponse 事件）</li>
 *   <li><b>webkit</b>  → {@link PlaywrightEventCaptureStrategy}（同上，WebKit 的 CDP 不可靠）</li>
 *   <li><b>未知/获取失败</b> → {@link PlaywrightEventCaptureStrategy}（安全默认，绝不让采集静默缺失）</li>
 * </ul>
 *
 */
public final class CaptureStrategyFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureStrategyFactory.class);

    private CaptureStrategyFactory() {}

    /**
     * 依据浏览器内核创建采集策略。
     *
     * @param page Playwright Page 实例（用于探测所属浏览器内核）
     * @return 选定的采集策略（调用方负责 start/stop 生命周期）
     */
    public static CaptureStrategy create(Page page) {
        // ── 按浏览器内核分发 ──
        String browser = browserName(page);
        if ("chromium".equalsIgnoreCase(browser)) {
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[CaptureStrategyFactory] Chromium detected → CDPCaptureStrategy");
            return new CDPCaptureStrategy();
        }

        // firefox / webkit / 未知 → Playwright 事件（跨浏览器可靠）
        LOGGER.info("[CaptureStrategyFactory] Browser '{}' detected → PlaywrightEventCaptureStrategy "
                + "(CDP unreliable on non-Chromium, using cross-browser page events)",
                browser);
        return new PlaywrightEventCaptureStrategy();
    }

    /** 取浏览器内核名（chromium/firefox/webkit），取不到时回退空串（按非 Chromium 处理） */
    private static String browserName(Page page) {
        try {
            if (page != null && page.context() != null
                    && page.context().browser() != null
                    && page.context().browser().browserType() != null) {
                return page.context().browser().browserType().name();
            }
        } catch (Exception ignore) {
            // 探测失败绝不应阻断策略选择
        }
        return "";
    }
}
