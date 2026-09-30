package com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine;

import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * 页面交互与状态操作工厂（WEB-P1-2 Phase 4b）：从 {@link BasePage} 下沉交互动作
 * （键盘/按键/弹窗/截图/脚本执行）与页面状态读取（源码/闭合/尺寸/BoundingBox/聚焦）。
 *
 * <p>与 {@link LocatorFactory}/{@link CookieManager}/{@link PageFrameShadow}/{@link PageViewport}/
 * {@link PageAccessibility} 同源模式：静态工具类、接收 {@code BasePage bp}、经 bp 公开 API
 * （{@code getPage}/{@code getCurrentFrame}/{@code getContext}/{@code locatorInternal}/
 * {@code normalizeText}/{@code ensurePageValid}/{@code ensureContextValid}）访问状态。
 *
 * <p>全部方法均为 framework-internal。
 */
public final class PageInteractions {

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
        try {
            bp.getPage().waitForTimeout((double) milliseconds);
        } catch (PlaywrightException e) {
            // page.waitForTimeout 内部委托给主框架，属 frame 绑定调用。DBB 登录后 en-US↔en-us
            // 路由规范化会销毁重建主框架（及其下全部子框架），令绑定的 frame 失效而抛
            // Object doesn't exist / frame detached。此时改用线程级休眠兜底：既不再依赖已
            // 失效的 frame，又保留"延迟 ms"的语义（纯定时等待本就不该绑定到具体 frame）。
            if (isNavigationOrContextLoss(e)) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(milliseconds));
                if (Thread.interrupted()) {
                    Thread.currentThread().interrupt();
                }
            } else {
                throw e;
            }
        }
    }

    /**
     * 判定异常是否由导航/执行上下文销毁引起（可降级兜底），与 {@code ElementOperationSupport}
     * 同源：路由规范化（en-US↔en-us）/重定向/SPA 软导航会销毁重建 DOM，此期间 frame 绑定调用
     * 会立即抛 PlaywrightException（非 TimeoutError），需降级为线程级休眠。
     */
    private static boolean isNavigationOrContextLoss(PlaywrightException e) {
        String m = e.getMessage();
        if (m == null) {
            return false;
        }
        m = m.toLowerCase();
        return m.contains("execution context") || m.contains("context was destroyed")
                || m.contains("object doesn't exist") || m.contains("does not exist")
                || m.contains("navigat") || m.contains("frame detached")
                || m.contains("frame was detached");
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
