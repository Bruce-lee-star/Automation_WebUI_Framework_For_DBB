package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.web.snapshot.NativeSnapshotReportGenerator;
import com.hsbc.cmb.hk.dbb.automation.framework.web.snapshot.PlaywrightSnapshotSupport;
import net.thucydides.model.domain.DataTable;
import net.thucydides.model.domain.Story;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import net.thucydides.model.screenshots.ScreenshotAndHtmlSource;
import net.thucydides.model.steps.ExecutedStepDescription;
import net.thucydides.model.steps.StepFailure;
import net.thucydides.model.steps.StepListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原生快照测试监听器
 * <p>
 * 自动集成到 Serenity BDD 测试生命周期，在测试套件结束时生成快照报告。
 * 与 {@link PlaywrightSnapshotSupport} 配合使用，无需手动调用 generateReport()。
 * </p>
 *
 * <p>工作流程：</p>
 * <ol>
 *   <li>测试执行中调用 {@code PlaywrightSnapshotSupport.of(page).visual()/aria().snapshot()} → 结果自动注册</li>
 *   <li>测试套件结束 → 本监听器自动生成报告并输出摘要</li>
 * </ol>
 *
 * @see PlaywrightSnapshotSupport
 * @see NativeSnapshotReportGenerator
 */
public class NativeSnapshotTestListener implements StepListener {

    private static final Logger logger = LoggerFactory.getLogger(NativeSnapshotTestListener.class);

    /** 已生成报告的线程名集合（防重复） */
    private static final Set<String> REPORT_GENERATED = ConcurrentHashMap.newKeySet();

    @Override
    public void testSuiteStarted(Class<?> storyClass) {
        REPORT_GENERATED.clear();
    }

    @Override
    public void testSuiteStarted(Story story) {
        REPORT_GENERATED.clear();
    }

    @Override
    public void testSuiteFinished() {
        generateReport();
    }

    @Override
    public void testRunFinished() {
        generateReport();
    }

    private void generateReport() {
        String threadName = Thread.currentThread().getName();
        if (!REPORT_GENERATED.add(threadName)) {
            return;
        }

        try {
            if (PlaywrightSnapshotSupport.getResults().isEmpty()) {
                logger.debug("[NativeSnapshot] No snapshot tests executed, skipping report");
                return;
            }

            String reportPath = NativeSnapshotReportGenerator.generate();
            if (reportPath != null) {
                logger.info("[NativeSnapshot] Report Generated: {}", reportPath);
                logger.info("[NativeSnapshot] Summary: {}", PlaywrightSnapshotSupport.getSummary());
            }
        } catch (Exception e) {
            logger.error("[NativeSnapshot] Failed to generate report: {}", e.getMessage(), e);
        } finally {
            PlaywrightSnapshotSupport.clearResults();
            REPORT_GENERATED.remove(threadName);
        }
    }

    // ==================== 不需要实现的方法 ====================

    @Override public void testStarted(String description) {}
    @Override public void testStarted(String description, String id) {}
    @Override public void testStarted(String s, String s1, ZonedDateTime zonedDateTime) {}
    @Override public void testFinished(TestOutcome result) {}
    @Override public void testFinished(TestOutcome testOutcome, boolean b, ZonedDateTime zonedDateTime) {}
    @Override public void testRetried() {}
    @Override public void stepStarted(ExecutedStepDescription executedStepDescription) {}
    @Override public void skippedStepStarted(ExecutedStepDescription executedStepDescription) {}
    @Override public void stepFailed(StepFailure stepFailure) {}
    @Override public void stepFailed(StepFailure stepFailure, List<ScreenshotAndHtmlSource> list, boolean b, ZonedDateTime zonedDateTime) {}
    @Override public void lastStepFailed(StepFailure stepFailure) {}
    @Override public void stepIgnored() {}
    @Override public void stepPending() {}
    @Override public void stepPending(String s) {}
    @Override public void stepFinished() {}
    @Override public void stepFinished(List<ScreenshotAndHtmlSource> list, ZonedDateTime zonedDateTime) {}
    @Override public void testFailed(TestOutcome testOutcome, Throwable throwable) {}
    @Override public void testIgnored() {}
    @Override public void testSkipped() {}
    @Override public void testPending() {}
    @Override public void testIsManual() {}
    @Override public void notifyScreenChange() {}
    @Override public void useExamplesFrom(DataTable dataTable) {}
    @Override public void addNewExamplesFrom(DataTable dataTable) {}
    @Override public void exampleStarted(Map<String, String> map) {}
    @Override public void exampleFinished() {}
    @Override public void assumptionViolated(String s) {}
    @Override public void takeScreenshots(List<ScreenshotAndHtmlSource> list) {}
    @Override public void takeScreenshots(TestResult testResult, List<ScreenshotAndHtmlSource> list) {}
    @Override public void recordScreenshot(String s, byte[] bytes) {}
}
