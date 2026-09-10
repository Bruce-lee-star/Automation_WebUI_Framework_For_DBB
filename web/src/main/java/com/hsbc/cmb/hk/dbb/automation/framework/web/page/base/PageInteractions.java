package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 页面交互与状态操作工厂（WEB-P1-2 Phase 4b）：从 {@link BasePage} 下沉交互动作
 * （键盘/按键/弹窗/截图/脚本执行）与页面状态读取（源码/闭合/尺寸/BoundingBox/聚焦）。
 *
 * <p>与 {@link LocatorFactory}/{@link CookieManager}/{@link PageFrameShadow}/{@link PageViewport}/
 * {@link PageAccessibility} 同源模式：静态工具类、接收 {@code BasePage bp}、经 bp 公开 API
 * （{@code getPage}/{@code getCurrentFrame}/{@code getContext}/{@code locatorInternal}/
 * {@code normalizeText}/{@code ensurePageValid}/{@code ensureContextValid}）访问状态，
 * 日志路由回 {@code BasePage.class} 保持生产溯源一致。
 *
 * <p>全部方法均为 framework-internal。
 */
public final class PageInteractions {

    private static final Logger log = LoggerFactory.getLogger(BasePage.class);

    private PageInteractions() {
    }

    public static Object executeJavaScript(BasePage bp, String script, Object... args) {
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        return (frame != null) ? frame.evaluate(script, args) : bp.getPage().evaluate(script, args);
    }

    public static boolean getPageSourceContains(BasePage bp, String text) {
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        String content = (frame != null) ? frame.content() : bp.getPage().content();
        return bp.normalizeText(content).contains(bp.normalizeText(text));
    }

    public static String getPageSource(BasePage bp) {
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        return (frame != null) ? frame.content() : bp.getPage().content();
    }

    public static int getPageSize(BasePage bp) {
        bp.ensureContextValid();
        return bp.getContext().pages().size();
    }

    public static BoundingBox getElementBoundingBox(BasePage bp, String selector) {
        return bp.locatorInternal(selector).boundingBox();
    }

    public static boolean isClosed(BasePage bp) {
        Page page = bp.getPage();
        return page != null && page.isClosed();
    }

    public static void bringToFront(BasePage bp) {
        bp.ensurePageValid();
        bp.getPage().bringToFront();
    }

    public static void keyDown(BasePage bp, String selector, String key) {
        bp.locatorInternal(selector).focus();
        bp.getPage().keyboard().down(key);
    }

    public static void keyUp(BasePage bp, String selector, String key) {
        bp.locatorInternal(selector).focus();
        bp.getPage().keyboard().up(key);
    }

    public static void press(BasePage bp, String selector, String key) {
        bp.locatorInternal(selector).press(key);
    }

    public static void waitForTimeout(BasePage bp, int milliseconds) {
        bp.ensurePageValid();
        bp.getPage().waitForTimeout((double) milliseconds);
    }

    public static void acceptAlert(BasePage bp) {
        bp.ensurePageValid();
        bp.getPage().onceDialog(Dialog::accept);
    }

    public static void dismissAlert(BasePage bp) {
        bp.ensurePageValid();
        bp.getPage().onceDialog(Dialog::dismiss);
    }

    public static void acceptAlert(BasePage bp, Runnable trigger) {
        bp.ensurePageValid();
        bp.getPage().onceDialog(Dialog::accept);
        if (trigger != null) {
            trigger.run();
        }
    }

    public static void dismissAlert(BasePage bp, Runnable trigger) {
        bp.ensurePageValid();
        bp.getPage().onceDialog(Dialog::dismiss);
        if (trigger != null) {
            trigger.run();
        }
    }

    public static byte[] takeScreenshot(BasePage bp) {
        bp.ensurePageValid();
        Frame frame = bp.getCurrentFrame();
        return (frame != null)
                ? frame.frameElement().screenshot()
                : bp.getPage().screenshot();
    }

    public static byte[] takeElementScreenshot(BasePage bp, String selector) {
        return bp.locatorInternal(selector).screenshot();
    }
}
