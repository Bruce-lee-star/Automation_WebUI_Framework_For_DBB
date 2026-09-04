package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.delegate;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.NavigationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 导航子模块（T5-5 拆分）。
 * <p>原 {@link BasePage} 的 {@code navigateTo} / {@code navigateToWithRetry} / {@code getCurrentUrl} /
 * {@code getTitle} / {@code refresh} / {@code back} / {@code forward} / {@code setContent} 方法体下沉至此。
 * <p>仅依赖 {@link BasePage} 公开 API（{@code getPage()} / {@code getConfig()} /
 * {@code resetFrameContextAfterNavigation()}），其中 {@code getPage()} 内部已触发 {@code ensurePageValid()}，
 * 故行为与原实现零差异；公开 API 不变。
 */
public final class PageNavigation {

    private static final Logger logger = LoggerFactory.getLogger(PageNavigation.class);

    private PageNavigation() {
        // 纯静态工具类，禁止实例化
    }

    public static void navigateTo(BasePage bp, String url) {
        PlaywrightConfigManager config = bp.getConfig();
        String pageLoadState = config.getPageLoadState();
        Page.NavigateOptions options = new Page.NavigateOptions();
        options.setTimeout((long) config.getNavigationTimeout());
        // 根据配置设置等待策略
        switch (pageLoadState.toLowerCase()) {
            case "networkidle":
                options.setWaitUntil(WaitUntilState.NETWORKIDLE);
                break;
            case "domcontentloaded":
                options.setWaitUntil(WaitUntilState.DOMCONTENTLOADED);
                break;
            case "commit":
                options.setWaitUntil(WaitUntilState.COMMIT);
                break;
            default:
                options.setWaitUntil(WaitUntilState.LOAD);
        }
        try {
            // navigate 已经根据 options 中的 waitUntil 等待页面加载
            // 不需要再额外 waitForLoadState，避免重复等待
            bp.getPage().navigate(url, options);
            logger.debug("Navigation completed (waitUntil={}): {}", pageLoadState, url);
            bp.resetFrameContextAfterNavigation();
        } catch (TimeoutError e) {
            // TimeoutError 必须放在 PlaywrightException 前面（因为 TimeoutError 继承 PlaywrightException）
            throw new NavigationException(url, config.getNavigationTimeout(), e);
        } catch (PlaywrightException e) {
            throw new NavigationException(url, "Navigation failed: " + e.getMessage(), e);
        }
    }

    public static String getCurrentUrl(BasePage bp) {
        return bp.getPage().url();
    }

    public static String getTitle(BasePage bp) {
        return bp.getPage().title();
    }

    public static void refresh(BasePage bp) {
        bp.getPage().reload();
        bp.resetFrameContextAfterNavigation();
    }

    public static void back(BasePage bp) {
        bp.getPage().goBack();
        bp.resetFrameContextAfterNavigation();
    }

    public static void forward(BasePage bp) {
        bp.getPage().goForward();
        bp.resetFrameContextAfterNavigation();
    }

    public static void setContent(BasePage bp, String html) {
        bp.getPage().setContent(html);
        // 替换页面内容后，所有 iframe 均被销毁，必须重置 iframe 上下文
        bp.resetFrameContextAfterNavigation();
    }

    public static void navigateToWithRetry(BasePage bp, String url, int retries) {
        bp.retry(() -> bp.navigateTo(url), retries, 1000, "navigate to: " + url);
    }
}
