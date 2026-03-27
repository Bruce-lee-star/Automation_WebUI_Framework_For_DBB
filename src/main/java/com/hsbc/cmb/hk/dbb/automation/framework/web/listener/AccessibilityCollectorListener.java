package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.web.accessibility.AccessibilityScanner;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import net.thucydides.model.steps.StepListener;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.Story;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listener to automatically manage AccessibilityScanner lifecycle
 * Initializes collector at test suite start and generates final report at test suite end
 * Users no longer need to manually call initialize() and cleanup()
 *
 * This listener is automatically registered via ThucydidesStepsListenerAdapter.
 * No need to use AccessibilityHooks for report generation.
 */
public class AccessibilityCollectorListener implements StepListener {

    private static final Logger logger = LoggerFactory.getLogger(AccessibilityCollectorListener.class);

    @Override
    public void testSuiteStarted(Class<?> testClass) {
        if (!AccessibilityScanner.isInitialized()) {
            try {
                // Get config from PlaywrightManager (reads from serenity.properties)
                AccessibilityScanner.ScanConfig config = PlaywrightManager.getAccessibilityScanConfig();
                AccessibilityScanner.initialize(config);
                logger.info("AccessibilityScanner automatically initialized for test suite: {}", testClass.getName());
                logger.info("  - Project: {}", config.getProjectName());
                logger.info("  - Color Contrast Check: {}", config.isCheckColorContrast());
                logger.info("  - Keyboard Navigation Check: {}", config.isCheckKeyboardNavigation());
                logger.info("  - Menu Navigation Check: {}", config.isCheckMenuNavigation());
            } catch (Exception e) {
                logger.error("Failed to initialize AccessibilityScanner", e);
            }
        }
    }

    @Override
    public void testSuiteStarted(Story story) {
        if (!AccessibilityScanner.isInitialized()) {
            try {
                // Get config from PlaywrightManager (reads from serenity.properties)
                AccessibilityScanner.ScanConfig config = PlaywrightManager.getAccessibilityScanConfig();
                AccessibilityScanner.initialize(config);
                logger.info("AccessibilityScanner automatically initialized for story: {}", story.getStoryName());
                logger.info("  - Project: {}", config.getProjectName());
                logger.info("  - Color Contrast Check: {}", config.isCheckColorContrast());
                logger.info("  - Keyboard Navigation Check: {}", config.isCheckKeyboardNavigation());
                logger.info("  - Menu Navigation Check: {}", config.isCheckMenuNavigation());
            } catch (Exception e) {
                logger.error("Failed to initialize AccessibilityScanner", e);
            }
        }
    }

    @Override
    public void testSuiteFinished() {
        if (AccessibilityScanner.isInitialized()) {
            try {
                logger.info("========================================");
                logger.info("Generating Final Accessibility Report");
                logger.info("========================================");

                AccessibilityScanner.generateFinalReport();

                logger.info("Final accessibility report generated successfully");
                logger.info("Check {} directory for reports", PlaywrightManager.getAccessibilityReportDirectory());

                AccessibilityScanner.cleanup();
                logger.info("AccessibilityScanner cleaned up");

            } catch (Exception e) {
                logger.error("Failed to generate final accessibility report or cleanup", e);
            }
        }
    }
    
    // Other StepListener methods (no-op implementations)
    @Override public void testStarted(String testName) {}
    @Override public void testFinished(TestOutcome result) {}
    @Override public void testSkipped() {}
    @Override public void stepStarted(net.thucydides.model.steps.ExecutedStepDescription step) {}
    @Override public void stepFinished() {}
    @Override public void stepFailed(net.thucydides.model.steps.StepFailure failure) {}
    @Override public void lastStepFailed(net.thucydides.model.steps.StepFailure failure) {}
    @Override public void stepIgnored() {}
    @Override public void testStarted(String testName, String testMethod) {}
    @Override public void testStarted(String testName, String testMethod, java.time.ZonedDateTime startTime) {}
    @Override public void testFinished(TestOutcome result, boolean isInDataDrivenTest, java.time.ZonedDateTime finishTime) {}
    @Override public void testRetried() {}
    @Override public void skippedStepStarted(net.thucydides.model.steps.ExecutedStepDescription step) {}
    @Override public void stepFinished(java.util.List<net.thucydides.model.screenshots.ScreenshotAndHtmlSource> screenshots) {}
    @Override public void stepFinished(java.util.List<net.thucydides.model.screenshots.ScreenshotAndHtmlSource> screenshots, java.time.ZonedDateTime timestamp) {}
    @Override public void stepFailed(net.thucydides.model.steps.StepFailure failure, java.util.List<net.thucydides.model.screenshots.ScreenshotAndHtmlSource> screenshots, boolean takeScreenshotOnFailure, java.time.ZonedDateTime timestamp) {}
    @Override public void takeScreenshots(java.util.List<net.thucydides.model.screenshots.ScreenshotAndHtmlSource> screenshots) {}
    @Override public void takeScreenshots(net.thucydides.model.domain.TestResult result, java.util.List<net.thucydides.model.screenshots.ScreenshotAndHtmlSource> screenshots) {}
    @Override public void stepPending() {}
    @Override public void stepPending(String description) {}
    @Override public void testFailed(TestOutcome result, Throwable throwable) {}
    @Override public void testIgnored() {}
    @Override public void testPending() {}
    @Override public void testIsManual() {}
    @Override public void notifyScreenChange() {}
    @Override public void useExamplesFrom(net.thucydides.model.domain.DataTable dataTable) {}
    @Override public void addNewExamplesFrom(net.thucydides.model.domain.DataTable dataTable) {}
    @Override public void exampleStarted(java.util.Map<String, String> data) {}
    @Override public void exampleFinished() {}
    @Override public void assumptionViolated(String message) {}
    @Override public void testRunFinished() {}
}
