package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.delegate;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.LocatorFactory;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 等待 / 重试 / 断言子模块（T5-5 拆分）。
 * <p>原 {@link BasePage} 的 {@code waitFor*} / {@code retry*} / {@code shouldBe*} 方法体下沉至此。
 * <p>仅依赖 {@link BasePage} 公开 API（{@code element()} / {@code locator()} / {@code getPage()}），
 * 其中 {@code getPage()} 内部已触发 {@code ensurePageValid()}，故行为与原实现零差异；公开 API 不变。
 */
public final class PageWaits {

    private static final Logger log = LoggerFactory.getLogger(PageWaits.class);

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
        if (!bp.locator(selector).isNotVisible()) {
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

    /**
     * 通用重试（签名与语义保持不变）。
     *
     * <p><b>D1-1：根因不再丢失</b> —— 旧实现只把<b>最后一次</b>异常作为 cause 抛出，
     * 首次（往往才是根因）与中间的异常全部丢失，排障只能看到"最后一次失败"的表象。
     * 现在各次尝试的异常都会被保留：最后一次作 cause，<b>首次及中间异常作 suppressed</b>。
     */
    public static void retry(BasePage bp, Runnable runnable, int retries, int intervalMs, String desc) {
        List<Throwable> failures = new ArrayList<>();
        for (int i = 0; i <= retries; i++) {
            try {
                runnable.run();
                return;
            } catch (Exception e) {
                failures.add(e);
                if (i == retries) {
                    throw buildRetryFailure(desc, failures);
                }
                bp.getPage().waitForTimeout((double) intervalMs);
            }
        }
    }

    /**
     * D1-1：<b>条件驱动</b>重试 —— 替代"盲等固定间隔"的旧模式。
     *
     * <p>每次执行操作前，先把等待<b>委托给 {@code Locator.waitFor}</b>：
     * Playwright 内部按条件自动等待，元素一就绪立即返回，
     * 而不是无论是否就绪都睡满固定间隔（旧实现的主要耗时来源，即"轮询"）。
     *
     * <p>失败时同样保留全部尝试的异常（最后一次为 cause，含首次在内的其余为 suppressed）。
     *
     * @param selector       目标元素选择器（等其就绪后再执行操作）
     * @param waitTimeoutMs  单次就绪等待上限（毫秒）
     */
    public static void retryOnReady(BasePage bp, String selector, Runnable operation,
                                    int retries, int waitTimeoutMs, String desc) {
        List<Throwable> failures = new ArrayList<>();
        for (int i = 0; i <= retries; i++) {
            try {
                //  经 LocatorFactory 统一取原生 Locator（保留 iframe / shadow 上下文适配）；
                //  不要直接用 page.locator(selector)，那会丢掉当前 frame 与 shadow 穿透
                LocatorFactory.bySelector(bp, selector)
                        .waitFor(new Locator.WaitForOptions().setTimeout(waitTimeoutMs));
                operation.run();
                return;
            } catch (Exception e) {
                failures.add(e);
                log.debug("[PageWaits] retryOnReady attempt failed, will retry: {}", e.toString());
            }
        }
        throw buildRetryFailure(desc + " (selector=" + selector + ")", failures);
    }

    /**
     * 带校验的重试（返回是否成功）。
     *
     * <p><b>D1-1：轮询改为条件驱动</b> —— 旧实现每次失败后都盲睡满整个
     * {@code retryIntervalMs}；现在改为<b>等待校验条件成立即继续</b>（小步长探测），
     * 条件一满足立刻进入下一次尝试，不再无谓等待。
     */
    public static boolean retryWithValidation(BasePage bp, Runnable operation, BooleanSupplier validation,
                                              int maxRetries, int retryIntervalMs, String desc) {
        for (int i = 0; i <= maxRetries; i++) {
            try {
                operation.run();
                if (validation.getAsBoolean()) return true;
            } catch (Exception e) {
                // 验证失败或操作抛异常：等待后重试（重试路径属预期，但不得静默，D7-3）
                log.debug("[PageWaits] retry attempt failed, will retry: {}", e.toString());
            }
            if (i < maxRetries) {
                waitUntil(bp, validation, retryIntervalMs);
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════════════════

    /** 以小步长等待条件成立，<b>成立即返回</b>（避免固定间隔盲等）。 */
    private static void waitUntil(BasePage bp, BooleanSupplier condition, int maxWaitMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, maxWaitMs);
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (Exception e) {
                // 条件校验本身抛异常：继续等至超时
                log.debug("[PageWaits] validation check threw during wait: {}", e.toString());
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            bp.getPage().waitForTimeout((double) Math.min(50L, remaining));
        }
    }

    /**
     * 汇总重试失败：最后一次异常作 cause，<b>首次及其余尝试的异常作 suppressed</b>（D1-1）。
     * 这样"最后一次的表象"与"首次的根因"都能在堆栈里看到。
     */
    private static RuntimeException buildRetryFailure(String desc, List<Throwable> failures) {
        if (failures.isEmpty()) {
            return new RuntimeException("Retry failed: " + desc);
        }
        Throwable last = failures.get(failures.size() - 1);
        RuntimeException out = new RuntimeException(
                "Retry failed: " + desc + " (attempts=" + failures.size() + ")", last);
        for (int i = 0; i < failures.size() - 1; i++) {
            out.addSuppressed(failures.get(i));
        }
        return out;
    }
}
