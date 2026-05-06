package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.web.accessibility.AxeCoreScanner;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
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

/**
 * Axe-core Accessibility Test Listener
 * Automatically integrates with Serenity BDD test lifecycle
 */
public class AxeCoreListener implements StepListener {

    private static final Logger logger = LoggerFactory.getLogger(AxeCoreListener.class);

    private static final ThreadLocal<Boolean> axeEnabled = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> reportGenerated = ThreadLocal.withInitial(() -> false);

    /**
     * Initialize before test suite
     */
    public static void initializeIfNeeded() {
        if (!AxeCoreScanner.isInitialized()) {
            try {
                AxeCoreScanner.AxeScanConfig config = new AxeCoreScanner.AxeScanConfig();

                // Read configuration using FrameworkConfigManager
                boolean enabled = FrameworkConfigManager.getBoolean(FrameworkConfig.AXE_SCAN_ENABLED);
                String projectName = PlaywrightManager.config().getProjectName();
                String tags = FrameworkConfigManager.getString(FrameworkConfig.AXE_SCAN_TAGS);
                String outputDir = FrameworkConfigManager.getString(FrameworkConfig.AXE_SCAN_OUTPUT_DIR);

                axeEnabled.set(enabled);
                reportGenerated.set(false);  // Reset report flag

                if (axeEnabled.get()) {
                    config.setProjectName(projectName);
                    config.setReportOutputDir(outputDir);

                    // Parse tags
                    if (tags != null && !tags.isEmpty()) {
                        for (String tag : tags.split(",")) {
                            config.getTags().add(tag.trim());
                        }
                    }

                    AxeCoreScanner.initialize(config);
                    logger.info("AxeCoreListener initialized with project: {}, tags: {}", projectName, tags);
                }
            } catch (Exception e) {
                logger.error("Failed to initialize AxeCoreListener: {}", e.getMessage(), e);
                axeEnabled.set(false);
            }
        }
    }

    /**
     * Scan the current page
     */
    public static AxeCoreScanner.AxeScanResult scanCurrentPage(String pageName) {
        initializeIfNeeded();

        if (!axeEnabled.get()) {
            logger.debug("Axe-core scanning is disabled");
            return null;
        }

        try {
            return AxeCoreScanner.scanPage(pageName);
        } catch (Exception e) {
            logger.error("Error during axe-core scan: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Scan a specific element on the current page
     */
    public static AxeCoreScanner.AxeScanResult scanElement(String pageName, String contextSelector) {
        initializeIfNeeded();

        if (!axeEnabled.get()) {
            logger.debug("Axe-core scanning is disabled");
            return null;
        }

        try {
            return AxeCoreScanner.scanPage(pageName, contextSelector);
        } catch (Exception e) {
            logger.error("Error during axe-core scan: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Generate final report (ensure only called once)
     */
    public static void generateFinalReport() {
        // Prevent duplicate report generation
        if (reportGenerated.get()) {
            logger.debug("Report already generated, skipping");
            return;
        }
        
        if (AxeCoreScanner.isInitialized()) {
            try {
                AxeCoreScanner.generateReport();
                reportGenerated.set(true);
            } catch (Exception e) {
                logger.error("Failed to generate axe-core report: {}", e.getMessage(), e);
            }
        }
    }
    
    /**
     * Cleanup resources (ThreadLocal and AxeCoreScanner)
     */
    private static void cleanupResources() {
        try {
            if (AxeCoreScanner.isInitialized()) {
                AxeCoreScanner.cleanup();
            }
        } catch (Exception e) {
            logger.error("Error during AxeCoreScanner cleanup: {}", e.getMessage(), e);
        } finally {
            // Always clean up ThreadLocals to prevent memory leaks
            axeEnabled.remove();
            reportGenerated.remove();
        }
    }

    /**
     * Check if axe scanning is enabled
     */
    public static boolean isEnabled() {
        return axeEnabled.get();
    }

    // ==================== StepListener Implementation ====================
    // 注意：AxeCoreListener 仅在测试套件级别工作（初始化/报告生成），不需要逐步骤追踪。
    // 以下空方法体均为 StepListener 接口强制要求的实现，故意留空。

    @Override
    public void testSuiteStarted(Class<?> storyClass) { initializeIfNeeded(); }

    @Override
    public void testSuiteStarted(Story story) { initializeIfNeeded(); }

    @Override
    public void testSuiteFinished() {
        if (axeEnabled.get()) { generateFinalReport(); cleanupResources(); }
    }

    @Override
    public void testStarted(String description) { /* axe-core 不需要逐测试跟踪 */ }

    @Override
    public void testStarted(String description, String id) { /* axe-core 不需要逐测试跟踪 */ }

    @Override
    public void testStarted(String description, String id, ZonedDateTime startTime) { /* no-op */ }

    @Override
    public void testFinished(TestOutcome result) { /* 报告在 testSuiteFinished 统一生成 */ }

    @Override
    public void testFinished(TestOutcome testOutcome, boolean b, ZonedDateTime time) { /* no-op */ }

    @Override
    public void testRetried() { /* no-op */ }

    @Override
    public void stepStarted(ExecutedStepDescription executedStepDescription) { /* no-op */ }

    @Override
    public void skippedStepStarted(ExecutedStepDescription executedStepDescription) { /* no-op */ }

    @Override
    public void stepFailed(StepFailure stepFailure) { /* no-op */ }

    @Override
    public void stepFailed(StepFailure failure, List<ScreenshotAndHtmlSource> sources, boolean b, ZonedDateTime time) { /* no-op */ }

    @Override
    public void lastStepFailed(StepFailure stepFailure) { /* no-op */ }

    @Override
    public void stepIgnored() { /* no-op */ }

    @Override
    public void stepPending() { /* no-op */ }

    @Override
    public void stepPending(String message) { /* no-op */ }

    @Override
    public void stepFinished() { /* no-op */ }

    @Override
    public void stepFinished(List<ScreenshotAndHtmlSource> sources, ZonedDateTime time) { /* no-op */ }

    @Override
    public void testFailed(TestOutcome outcome, Throwable throwable) { /* no-op */ }

    @Override
    public void testIgnored() { /* no-op */ }

    @Override
    public void testSkipped() { /* no-op */ }

    @Override
    public void testPending() { /* no-op */ }

    @Override
    public void testIsManual() { /* no-op */ }

    @Override
    public void notifyScreenChange() { /* no-op */ }

    @Override
    public void useExamplesFrom(DataTable table) { /* no-op */ }

    @Override
    public void addNewExamplesFrom(DataTable table) { /* no-op */ }

    @Override
    public void exampleStarted(Map<String, String> data) { /* no-op */ }

    @Override
    public void exampleFinished() { /* no-op */ }

    @Override
    public void assumptionViolated(String message) { /* no-op */ }

    @Override
    public void testRunFinished() {
        // 兜底：确保即使 testSuiteFinished 未被调用也能清理资源
        if (axeEnabled.get()) { logger.debug("testRunFinished fallback - performing final cleanup"); generateFinalReport(); cleanupResources(); }
    }

    @Override
    public void takeScreenshots(List<ScreenshotAndHtmlSource> sources) { /* no-op */ }

    @Override
    public void takeScreenshots(TestResult result, List<ScreenshotAndHtmlSource> sources) { /* no-op */ }

    @Override
    public void recordScreenshot(String name, byte[] bytes) { /* no-op */ }
}
