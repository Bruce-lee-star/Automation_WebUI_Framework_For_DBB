package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.common.assertion.SoftAssertions;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.PageEventMonitor;
import net.serenitybdd.core.Serenity;
import net.thucydides.core.steps.StepEventBus;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 *  收口 {@code PlaywrightListener} 的「框架级失败传播」职责
 * （原 checkAndMarkApiAssertionFailures / checkAndFailOnApiAssertions / checkAndFailOnPageErrors 三方法）。
 *
 * <p>这三方法是 B 改造中在步骤结束时兜底抛 AssertionError、使 IDE 正确标红的核心 seam，
 * 与监听器的生命周期编排正交。迁出后监听器只负责事件路由，失败聚合逻辑在此单一收敛，
 * 并复用 {@link ListenerGuard} 的 apiFailureAlreadyHandled 守卫与 {@link ListenerPerfStats} 的数据记录，
 * 保证与原实现行为完全等价（含防重入死循环与防重复打印）。</p>
 */
final class StepFailureAggregator {

    private static final Logger logger = LoggerFactory.getLogger(StepFailureAggregator.class);

    private StepFailureAggregator() {
    }

    /**
     * 核心方法：检查 API 断言失败并标记测试结果为失败（testFinished 兜底路径）。
     *
     * @param result   当前测试产出（失败时置 FAILURE）
     * @param testName 当前测试名（用于报告归属）
     */
    static void checkAndMarkApiAssertionFailures(TestOutcome result, String testName) {
        // ROUTE-P0-1：用 resolveFailureCapture() 兜底解析失败上下文（优先 per-context，否则 SHARED），
        // 避免 route 事件线程经 SHARED 记录的失败标志被漏检。
        RouteLifecycle lifecycle = RouteLifecycleRegistry.get();
        if (lifecycle == null) {
            //  route 生命周期未注册（如非 route 场景 / 未引入 route 模块）：无失败上下文可解析，安全跳过。
            return;
        }
        CaptureContext context = lifecycle.resolveFailureCapture();
        if (context == null) {
            return;
        }

        long timeoutMs = FrameworkConfigManager.getLong(WebFrameworkConfig.API_ASSERTION_WAIT_TIMEOUT);
        try {
            boolean completed = context.awaitCompletion(timeoutMs);
            if (!completed) {
                logger.warn("Timed out waiting for API requests to complete ({} active, timeout={}ms)",
                        context.getActiveRequests(), timeoutMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted while waiting for API requests to complete");
        }

        if (context.hasAssertionFailures()) {
            //  防重复：若 checkAndFailOnApiAssertions 已在步骤结束时经 StepEventBus 触发过一次，
            // 则跳过详细日志与 Serenity 记录，避免 testFailed 回调与 testFinished 各调一次导致重复打印。
            boolean alreadyHandledAtStep = ListenerGuard.guards().isApiFailureAlreadyHandled();

            if (!alreadyHandledAtStep) {
                logger.error("API assertions failed for test: {}", testName);

                String failureReport = context.buildFailureReport();
                logger.error("API assertion failure details:\n{}", failureReport);

                ListenerPerfStats.record(testName, "apiAssertionFailure",
                        "API assertions failed during test execution");
                ListenerPerfStats.record(testName, "apiAssertionFailureDetails", failureReport);

                try {
                    Serenity.recordReportData()
                            .withTitle("API ASSERTION FAILURES DETECTED")
                            .andContents(failureReport);
                } catch (Exception e) {
                    logger.debug("Failed to record API assertion failure to Serenity report", e);
                }
            }

            //  无论如何都确保 result 被标记为 FAILURE（兜底安全网）
            if (result != null) {
                result.setResult(TestResult.FAILURE);
            }
        }
    }

    /**
     * D3-2：场景末统一上报<b>软断言</b>失败（collect → assertAll 入 Serenity）。
     *
     * <p>与 {@link #checkAndMarkApiAssertionFailures} 走<b>同一 seam</b>：
     * {@code StepEventBus.testFailed(AssertionError)} + Serenity 报告 + {@code result} 置 FAILURE。
     *
     * <p>设计取舍：此处<b>只标记不抛出</b> —— 本方法在 {@code testFinished} 收尾阶段执行，
     * 抛异常会打断 Serenity 自身的收尾流程（资源清理 / 报告落盘），得不偿失；
     * 而 {@code result.setResult(FAILURE)} 已足以让用例判红。
     * 需要立即中断的场景请使用 {@link FrameworkAssertions} 硬断言。
     */
    static void checkAndMarkSoftAssertionFailures(TestOutcome result) {
        if (!SoftAssertions.hasFailures()) {
            return;
        }
        //  先渲染再清空：保证"报告里看到的"与"记录的"是同一份内容，且绝不把失败带到下一场景
        String details = SoftAssertions.renderFailures();
        SoftAssertions.clearForCurrentThread();

        logger.error("Soft assertion failures detected at scenario end:\n{}", details);

        try {
            StepEventBus.getEventBus().testFailed(new AssertionError(details));
            Serenity.recordReportData()
                    .withTitle("Soft Assertion Failures")
                    .andContents(details);
        } catch (Exception e) {
            logger.error("Failed to report soft assertion failures to Serenity", e);
        }

        //  无论如何都确保 result 被标记为 FAILURE（兜底安全网）
        if (result != null) {
            result.setResult(TestResult.FAILURE);
        }
    }

    /**
     * 框架级：每个步骤结束时自动检查 API 断言是否失败，经 StepEventBus 即时标记失败。
     * 通过 apiFailureAlreadyHandled 守卫防止同一 case 内回调链递归重入（死循环）。
     */
    static void checkAndFailOnApiAssertions() {
        // ROUTE-P0-1：用 resolveFailureCapture() 兜底解析失败上下文（优先 per-context，否则 SHARED），
        // 避免 route 事件线程经 SHARED 记录的失败标志被漏检。
        RouteLifecycle lifecycle = RouteLifecycleRegistry.get();
        if (lifecycle == null) {
            //  route 生命周期未注册（如非 route 场景 / 未引入 route 模块）：无失败上下文可解析，安全跳过。
            return;
        }
        CaptureContext context = lifecycle.resolveFailureCapture();
        if (context == null) {
            return;
        }

        if (!context.hasAssertionFailures()) {
            return;
        }

        //  防重入：同一 case 里只通过 StepEventBus 标记一次，防止回调链路再次进入此方法。
        if (ListenerGuard.guards().isApiFailureAlreadyHandled()) {
            return;
        }

        if (context.getActiveRequests() > 0) {
            try {
                boolean completed = context.awaitCompletion(5000);
                if (!completed) {
                    logger.warn("Step finished but {} API request(s) still active after 5s timeout",
                            context.getActiveRequests());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.debug("Interrupted while waiting for API request completion in step check");
            }
        }

        //  标记已处理，必须在调用 StepEventBus 之前设置（防回调重入）。
        ListenerGuard.guards().setApiFailureAlreadyHandled(true);

        String report = context.buildFailureReport();
        String details = context.buildFailureDetails();
        logger.error("API assertions failed during step - marking test as failed via StepEventBus:\n{}", report);

        try {
            StepEventBus.getEventBus().testFailed(new AssertionError(details));
            Serenity.recordReportData()
                    .withTitle("API Assertion Failures")
                    .andContents(details);
        } catch (Exception e) {
            logger.error("Failed to call StepEventBus.testFailed() for API assertion failure", e);
        }

        //  关键：直接在 stepFinished 回调中抛 AssertionError，使异常沿
        //   StepInterceptor → Cucumber → JUnit4 传播，令 IDE runner 正确标红。
        throw new AssertionError("API assertion failures detected — failing scenario:\n" + details);
    }

    /**
     * 框架级未捕获页面异常检查（每个步骤结束时兜底执行）。
     * 仅当 playwright.page.error.failOnError=true 时生效：将 PageEventMonitor 收集的未捕获 JS 异常
     * 经 Serenity 标记失败并抛出。默认关闭（仅记录日志）；drain 即清空，天然幂等。
     */
    static void checkAndFailOnPageErrors() {
        if (!WebFrameworkConfig.PLAYWRIGHT_PAGE_ERROR_FAIL.getBooleanValue()) {
            return;
        }
        List<String> errors = PageEventMonitor.drainPendingPageErrors();
        if (errors.isEmpty()) {
            return;
        }
        String details = String.join("\n", errors);
        logger.error("Uncaught page errors detected during step - marking test as failed via StepEventBus:\n{}", details);
        try {
            StepEventBus.getEventBus().testFailed(new AssertionError(details));
            Serenity.recordReportData()
                    .withTitle("Uncaught Page Errors")
                    .andContents(details);
        } catch (Exception e) {
            logger.error("Failed to call StepEventBus.testFailed() for page errors", e);
        }
        throw new AssertionError("Uncaught page errors detected — failing scenario:\n" + details);
    }
}
