package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.delegate;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;

import java.util.function.BooleanSupplier;

/**
 * 等待 / 重试 / 断言子模块（T5-5 拆分）。
 * <p>原 {@link BasePage} 的 {@code waitFor*} / {@code retry*} / {@code shouldBe*} 方法体下沉至此。
 * <p>仅依赖 {@link BasePage} 公开 API（{@code element()} / {@code locator()} / {@code getPage()}），
 * 其中 {@code getPage()} 内部已触发 {@code ensurePageValid()}，故行为与原实现零差异；公开 API 不变。
 */
public final class PageWaits {

    private PageWaits() {
        // 纯静态工具类，禁止实例化
    }

    public static void waitForElementExists(BasePage bp, String selector, int timeout) {
        bp.element(selector).waitForExists(timeout);
    }

    public static void waitForElementNotExists(BasePage bp, String selector, int timeout) {
        bp.element(selector).waitForNotExists(timeout);
    }

    public static void waitForElementEditable(BasePage bp, String selector, int timeout) {
        bp.element(selector).waitForEditable(timeout);
    }

    public static void waitForElementEnabled(BasePage bp, String selector, int timeout) {
        bp.element(selector).waitForEnabled(timeout);
    }

    public static void waitForElementDisabled(BasePage bp, String selector, int timeout) {
        bp.element(selector).waitForDisabled(timeout);
    }

    public static void waitForElementChecked(BasePage bp, String selector, int timeout) {
        bp.element(selector).waitForChecked(timeout);
    }

    public static void waitForElementNotChecked(BasePage bp, String selector, int timeout) {
        bp.element(selector).waitForNotChecked(timeout);
    }

    public static void waitForNetworkIdle(BasePage bp, int timeout) {
        bp.getPage().waitForLoadState(LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout((long) timeout * 1000));
    }

    public static void waitForPageFullyLoaded(BasePage bp, int timeout) {
        bp.getPage().waitForLoadState(LoadState.LOAD,
                new Page.WaitForLoadStateOptions().setTimeout((long) timeout * 1000));
    }

    public static void waitForDOMContentLoaded(BasePage bp, int timeout) {
        bp.getPage().waitForLoadState(LoadState.DOMCONTENTLOADED,
                new Page.WaitForLoadStateOptions().setTimeout((long) timeout * 1000));
    }

    public static void shouldBeVisible(BasePage bp, String selector) {
        if (!bp.locator(selector).isVisible()) {
            throw new ElementException("Element should be visible: " + selector);
        }
    }

    public static void shouldBeNotVisible(BasePage bp, String selector) {
        if (!bp.locator(selector).isHidden()) {
            throw new ElementException("Element should be hidden: " + selector);
        }
    }

    public static boolean retryWithValidation(BasePage bp, Runnable operation, BooleanSupplier validation,
                                              int maxRetries, String desc) {
        return retryWithValidation(bp, operation, validation, maxRetries, 500, desc);
    }

    public static void retry(BasePage bp, Runnable runnable, String desc) {
        retry(bp, runnable, 3, 1000, desc);
    }

    public static void retry(BasePage bp, Runnable runnable, int retries, int intervalMs, String desc) {
        for (int i = 0; i <= retries; i++) {
            try {
                runnable.run();
                return;
            } catch (Exception e) {
                if (i == retries) throw new RuntimeException("Retry failed: " + desc, e);
                bp.getPage().waitForTimeout((double) intervalMs);
            }
        }
    }

    public static boolean retryWithValidation(BasePage bp, Runnable operation, BooleanSupplier validation,
                                              int maxRetries, int retryIntervalMs, String desc) {
        for (int i = 0; i <= maxRetries; i++) {
            try {
                operation.run();
                if (validation.getAsBoolean()) return true;
            } catch (Exception ignored) {
                // 验证失败或操作抛异常：等待后重试
            }
            bp.getPage().waitForTimeout((double) retryIntervalMs);
        }
        return false;
    }
}
