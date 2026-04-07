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
                String projectName = PlaywrightManager.getProjectName();
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

    @Override
    public void testSuiteStarted(Class<?> storyClass) {
        initializeIfNeeded();
    }

    @Override
    public void testSuiteStarted(Story story) {
        initializeIfNeeded();
    }


    @Override
    public void testSuiteFinished() {
        // Generate single aggregated report at test suite finish
        if (axeEnabled.get()) {
            generateFinalReport();
            cleanupResources();
        }
    }

    @Override
    public void testStarted(String description) {
    }

    @Override
    public void testStarted(String description, String id) {
    }

    @Override
    public void testStarted(String s, String s1, ZonedDateTime zonedDateTime) {
    }

    @Override
    public void testFinished(TestOutcome result) {
        // Do nothing - report will be generated at testSuiteFinished
    }

    @Override
    public void testFinished(TestOutcome testOutcome, boolean b, ZonedDateTime zonedDateTime) {

    }

    @Override
    public void testRetried() {

    }

    @Override
    public void stepStarted(ExecutedStepDescription executedStepDescription) {

    }

    @Override
    public void skippedStepStarted(ExecutedStepDescription executedStepDescription) {

    }

    @Override
    public void stepFailed(StepFailure stepFailure) {

    }

    @Override
    public void stepFailed(StepFailure stepFailure, List<ScreenshotAndHtmlSource> list, boolean b, ZonedDateTime zonedDateTime) {

    }

    @Override
    public void lastStepFailed(StepFailure stepFailure) {

    }

    @Override
    public void stepIgnored() {

    }

    @Override
    public void stepPending() {

    }

    @Override
    public void stepPending(String s) {

    }

    @Override
    public void stepFinished() {

    }

    @Override
    public void stepFinished(List<ScreenshotAndHtmlSource> list, ZonedDateTime zonedDateTime) {

    }

    @Override
    public void testFailed(TestOutcome testOutcome, Throwable throwable) {

    }

    @Override
    public void testIgnored() {

    }

    @Override
    public void testSkipped() {

    }

    @Override
    public void testPending() {

    }

    @Override
    public void testIsManual() {

    }

    @Override
    public void notifyScreenChange() {

    }

    @Override
    public void useExamplesFrom(DataTable dataTable) {

    }

    @Override
    public void addNewExamplesFrom(DataTable dataTable) {

    }

    @Override
    public void exampleStarted(Map<String, String> map) {

    }

    @Override
    public void exampleFinished() {

    }

    @Override
    public void assumptionViolated(String s) {

    }

    @Override
    public void testRunFinished() {
        // Fallback: ensure cleanup even if testSuiteFinished wasn't called
        // This is a safety net for edge cases
        if (axeEnabled.get()) {
            logger.debug("testRunFinished called - performing final cleanup");
            generateFinalReport();
            cleanupResources();
        }
    }

    @Override
    public void takeScreenshots(List<ScreenshotAndHtmlSource> list) {

    }

    @Override
    public void takeScreenshots(TestResult testResult, List<ScreenshotAndHtmlSource> list) {

    }

    @Override
    public void recordScreenshot(String s, byte[] bytes) {

    }
}
