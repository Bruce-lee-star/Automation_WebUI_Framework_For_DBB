package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 视口与滚动操作工厂（WEB-P1-2 Phase 4）：从 {@link BasePage} 下沉 {@code setViewportSize}
 * 与 {@code scroll*} 系列。
 *
 * <p>与 {@link LocatorFactory}/{@link CookieManager}/{@link PageFrameShadow} 同源模式：
 * 静态工具类、接收 {@code BasePage bp}、经 bp 的公开 API（{@code locatorInternal}/
 * {@code getPage}/{@code ensurePageValid}）访问状态，日志路由回 {@code BasePage.class}
 * 保持生产溯源一致。
 *
 * <p>全部方法均为 framework-internal。
 */
public final class PageViewport {

    private static final Logger log = LoggerFactory.getLogger(BasePage.class);

    private PageViewport() {
    }

    public static void scrollTo(BasePage bp, String selector, int x, int y) {
        bp.locatorInternal(selector).evaluate("el => el.scrollTo(" + x + "," + y + ")");
    }

    public static void scrollBy(BasePage bp, String selector, int x, int y) {
        bp.locatorInternal(selector).evaluate("el => el.scrollBy(" + x + "," + y + ")");
    }

    public static void scrollToTopOf(BasePage bp, String selector) {
        bp.locatorInternal(selector).evaluate("el => el.scrollTop = 0");
    }

    public static void scrollToBottomOf(BasePage bp, String selector) {
        bp.locatorInternal(selector).evaluate("el => el.scrollTop = el.scrollHeight");
    }

    public static void setViewportSize(BasePage bp, int width, int height) {
        bp.ensurePageValid();
        bp.getPage().setViewportSize(width, height);
    }
}
