package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.web.snapshot.SnapshotReportGenerator;
import com.hsbc.cmb.hk.dbb.automation.framework.web.snapshot.SnapshotResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.snapshot.SnapshotTester;
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
 * Snapshot Test Listener
 * Automatically integrates with Serenity BDD test lifecycle to generate snapshot reports
 * 
 * <p>Usage:</p>
 * <pre>
 * # In serenity.properties
 * snapshot.testing.enabled=true
 * snapshot.baseline.dir=src/test/resources/snapshots/baselines
 * snapshot.dir=target/snapshots
 * </pre>
 * 
 * <p>The listener will automatically:</p>
 * <ul>
 *   <li>Generate snapshot report at test suite finish</li>
 *   <li>Log summary statistics</li>
 *   <li>Clean up resources</li>
 * </ul>
 */
public class SnapshotTestListener implements StepListener {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotTestListener.class);

    private static final ThreadLocal<Boolean> reportGenerated = ThreadLocal.withInitial(() -> false);

    /**
     * Generate final snapshot report (ensure only called once)
     */
    public static void generateFinalReport() {
        // Prevent duplicate report generation
        if (reportGenerated.get()) {
            logger.debug("Snapshot report already generated, skipping");
            return;
        }

        List<SnapshotResult> results = SnapshotReportGenerator.getResults();
        if (results.isEmpty()) {
            logger.debug("No snapshot tests executed, skipping report generation");
            return;
        }

        try {
            String reportPath = SnapshotTester.generateReport();
            if (reportPath != null) {
                reportGenerated.set(true);
                
                // Log summary
                String summary = SnapshotTester.getSummary();
                logger.info("Snapshot Test Report Generated: {}", reportPath);
                logger.info("Snapshot Test Summary: {}", summary);
            }
        } catch (Exception e) {
            logger.error("Failed to generate snapshot test report: {}", e.getMessage(), e);
        }
    }

    /**
     * Clear snapshot results (typically called at the start of a new scenario)
     */
    public static void clearResults() {
        SnapshotTester.clearResults();
        reportGenerated.set(false);
    }

    /**
     * Check if snapshot testing has any results
     */
    public static boolean hasResults() {
        return !SnapshotReportGenerator.getResults().isEmpty();
    }

    /**
     * Get snapshot test summary
     */
    public static String getSummary() {
        return SnapshotTester.getSummary();
    }

    /**
     * Cleanup resources
     */
    private static void cleanupResources() {
        try {
            clearResults();
        } catch (Exception e) {
            logger.error("Error during SnapshotTestListener cleanup: {}", e.getMessage(), e);
        } finally {
            // Always clean up ThreadLocal to prevent memory leaks
            reportGenerated.remove();
        }
    }

    // ==================== StepListener Implementation ====================

    @Override
    public void testSuiteStarted(Class<?> storyClass) {
        // Reset report flag at test suite start
        reportGenerated.set(false);
    }

    @Override
    public void testSuiteStarted(Story story) {
        // Reset report flag at test suite start
        reportGenerated.set(false);
    }

    @Override
    public void testSuiteFinished() {
        // Generate single aggregated report at test suite finish
        generateFinalReport();
        cleanupResources();
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
        if (!reportGenerated.get() && hasResults()) {
            logger.debug("testRunFinished called - performing final snapshot report generation");
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
