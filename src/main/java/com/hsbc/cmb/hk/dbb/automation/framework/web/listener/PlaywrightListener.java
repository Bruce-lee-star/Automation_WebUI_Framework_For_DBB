package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkCore;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring.RealApiMonitor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.hsbc.cmb.hk.dbb.automation.retry.configuration.RerunConfiguration;
import com.hsbc.cmb.hk.dbb.automation.retry.executor.RerunProcessExecutor;
import com.hsbc.cmb.hk.dbb.automation.retry.listener.PlaywrightRetryListener;
import com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.strategy.ScreenshotStrategy;
import net.thucydides.model.steps.StepListener;
import net.thucydides.model.steps.StepFailure;
import net.thucydides.model.steps.ExecutedStepDescription;
import net.thucydides.model.domain.DataTable;
import net.thucydides.model.domain.Story;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import net.thucydides.model.screenshots.ScreenshotAndHtmlSource;
import net.thucydides.model.util.EnvironmentVariables;
import net.thucydides.model.environment.SystemEnvironmentVariables;
import net.thucydides.core.steps.StepEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class PlaywrightListener implements StepListener {

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightListener.class);

    private final ScreenshotStrategy screenshotStrategy;

    // 用于跟踪当前测试结果，用于 FOR_FAILURES 等略
    private final ThreadLocal<TestResult> currentTestResult = new ThreadLocal<>();

    private static final ThreadLocal<Long> testStartTime = new ThreadLocal<>();
    private static final ThreadLocal<Long> stepStartTime = new ThreadLocal<>();
    private static final ThreadLocal<String> currentTestName = new ThreadLocal<>();
    private static final ThreadLocal<String> currentStepName = new ThreadLocal<>();

    // 记录当前 Cucumber 级别步骤，避免为 Serenity 子步骤重复截图
    private static final ThreadLocal<String> currentCucumberStep = new ThreadLocal<>();

    // 防止截图触发的 stepFinished 递归调用
    private static final ThreadLocal<Boolean> takingScreenshot = ThreadLocal.withInitial(() -> false);

    // 存储当前步骤的截图列表
    private static final ThreadLocal<List<ScreenshotAndHtmlSource>> currentStepScreenshots = ThreadLocal.withInitial(ArrayList::new);

    private static final AtomicLong totalTests = new AtomicLong(0);
    private static final AtomicLong passedTests = new AtomicLong(0);
    private static final AtomicLong failedTests = new AtomicLong(0);
    private static final AtomicLong skippedTests = new AtomicLong(0);
    private static final AtomicLong screenshotCounter = new AtomicLong(0);

    private static final ConcurrentHashMap<String, Object> testData = new ConcurrentHashMap<>();

    // 用于防止testSuiteFinished被多次调用时重复输出日志
    private static volatile boolean testSuiteFinishedLogged = false;

    public PlaywrightListener() {
        // 从环境变量中读取截图策略配置
        EnvironmentVariables environmentVariables = SystemEnvironmentVariables.currentEnvironmentVariables();
        this.screenshotStrategy = ScreenshotStrategy.from(environmentVariables);

        LoggingConfigUtil.logInfoIfVerbose(logger, "Screenshot strategy initialized: {}", screenshotStrategy);
    }

    @Override
    public void testStarted(String testName) {
        currentTestName.set(testName);
        testStartTime.set(System.currentTimeMillis());
        currentTestResult.set(TestResult.PENDING); // 初始化为PENDING，避免默认为SUCCESS导致统计错误
        totalTests.incrementAndGet();

        // 测试开始时截图（根据策略决定）
        if (screenshotStrategy == ScreenshotStrategy.BEFORE_AND_AFTER_EACH_STEP) {
            takeScreenshotAndRegister("TEST_START_" + testName);
        }
        recordTestData("testStart", System.currentTimeMillis());
        LoggingConfigUtil.logInfoIfVerbose(logger, "Test initialized: {}", testName);
    }

    private void testFinishedInternal() {
        Long startTime = testStartTime.get();
        if (startTime == null) {
            return;
        }

        long duration = System.currentTimeMillis() - startTime;
        String testName = currentTestName.get();

        // 根据策略和测试结果决定是否截图
        TestResult result = currentTestResult.get();
        boolean shouldTakeScreenshot = screenshotStrategy.shouldTakeScreenshotFor(result);

        if (shouldTakeScreenshot && screenshotStrategy != ScreenshotStrategy.DISABLED) {
            takeScreenshotAndRegister("TEST_END_" + testName);
        }

        recordTestData("testEnd", System.currentTimeMillis());
        recordTestData("testDuration", duration);

        if (result != null) {
            recordTestData("testResult", result);
            if (result == TestResult.SUCCESS) {
                passedTests.incrementAndGet();
            } else if (result == TestResult.PENDING) {
                // PENDING表示测试结果未知，可能是测试中途失败或超时
                // 将其计为失败，以确保准确统计
                logger.warn("Test result is PENDING, counting as failed: {}", testName);
                failedTests.incrementAndGet();
            } else if (result == TestResult.FAILURE || result == TestResult.ERROR) {
                failedTests.incrementAndGet();
            } else if (result == TestResult.SKIPPED) {
                skippedTests.incrementAndGet();
            }
        } else {
            // result为null，说明测试没有正常完成，计为失败
            logger.warn("Test result is null, counting as failed: {}", testName);
            failedTests.incrementAndGet();
        }

        LoggingConfigUtil.logInfoIfVerbose(logger, "Test completed: {} in {}ms (Result: {})", testName, duration, result);

        // 检查是否需要重试失败的测试
        checkAndTriggerRerun();

        // 清理线程本地变量
        cleanupThreadLocals();
    }

    /**
     * 自动断言API监控结果（如果监控失败）
     */
    private void autoAssertApiMonitoringIfNeeded() {
        // 检查是否有监控失败
        RealApiMonitor.checkAndThrowMonitoringFailure();
    }

    @Override
    public void testSkipped() {
        skippedTests.incrementAndGet();
        LoggingConfigUtil.logInfoIfVerbose(logger, "Test skipped: {}", currentTestName.get());
        recordTestData("testSkipped", true);
    }

    @Override
    public void stepStarted(ExecutedStepDescription step) {
        if (step == null) return;

        // 清空当前步骤的截图列表
        currentStepScreenshots.get().clear();

        stepStartTime.set(System.currentTimeMillis());
        currentStepName.set(step.getTitle());
        LoggingConfigUtil.logDebugIfVerbose(logger, "Step started: {}", step.getTitle());

        // 标记当前步骤为 Cucumber 步骤
        currentCucumberStep.set(step.getTitle());

        // BEFORE_AND_AFTER_EACH_STEP 策略：在 Cucumber 步骤开始时截图
        if (screenshotStrategy == ScreenshotStrategy.BEFORE_AND_AFTER_EACH_STEP) {
            takeScreenshotAndRegister("STEP_BEFORE_" + step.getTitle());
        }

        recordTestData("stepStart_" + step.getTitle(), System.currentTimeMillis());
    }

    @Override
    public void stepFinished() {
        Long startTime = stepStartTime.get();
        if (startTime == null) return;

        long duration = System.currentTimeMillis() - startTime;
        recordTestData("stepDuration", duration);
        LoggingConfigUtil.logDebugIfVerbose(logger, "Step completed in {}ms", duration);

        // 只为 Cucumber 级别步骤截图
        String stepName = currentStepName.get();
        String cucumberStep = currentCucumberStep.get();

        if (stepName != null && !stepName.isEmpty() && stepName.equals(cucumberStep)) {
            if (screenshotStrategy == ScreenshotStrategy.AFTER_EACH_STEP) {
                takeScreenshotAndRegister("STEP_" + sanitizeName(stepName));
            } else if (screenshotStrategy == ScreenshotStrategy.BEFORE_AND_AFTER_EACH_STEP) {
                takeScreenshotAndRegister("STEP_AFTER_" + sanitizeName(stepName));
            }
            // 清除 Cucumber 步骤记录
            currentCucumberStep.remove();
        }

        // 手动调用 StepEventBus 的 stepFinished 方法来传递截图
        List<ScreenshotAndHtmlSource> stepScreenshots = currentStepScreenshots.get();
        if (stepScreenshots != null && !stepScreenshots.isEmpty()) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Manually calling StepEventBus.stepFinished() with {} screenshots", stepScreenshots.size());
            try {
                StepEventBus.getEventBus().stepFinished(stepScreenshots, ZonedDateTime.now());
                LoggingConfigUtil.logDebugIfVerbose(logger, "Successfully called StepEventBus.stepFinished() with screenshots");
                // 清空截图列表，避免重复添加
                stepScreenshots.clear();
            } catch (Exception e) {
                logger.error("Failed to call StepEventBus.stepFinished() with screenshots", e);
            }
        } else {
            LoggingConfigUtil.logDebugIfVerbose(logger, "No screenshots to pass to StepEventBus.stepFinished()");
        }
    }

    @Override
    public void stepFailed(StepFailure failure) {
        if (failure == null) return;

        logger.error("Step failure detected: {}", failure.getException().getMessage());
        recordTestData("stepFailure", failure.getException().getMessage());
        recordTestData("stepFailureCause", failure.getException().getClass().getSimpleName());

        // 注意：不在 stepFailed 中 increment failedTests，避免与 testFinished 重复计数
        // 测试失败统计由 testFinished 统一处理

        // FOR_FAILURES 策略：步骤失败时截图
        if (screenshotStrategy == ScreenshotStrategy.FOR_FAILURES) {
            takeFailureScreenshot(null);
        }

        // 步骤失败时，将截图传递给 Serenity
        List<ScreenshotAndHtmlSource> stepScreenshots = currentStepScreenshots.get();
        if (stepScreenshots != null && !stepScreenshots.isEmpty()) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Manually calling StepEventBus.stepFinished() with {} screenshots after step failure", stepScreenshots.size());
            try {
                StepEventBus.getEventBus().stepFinished(stepScreenshots, ZonedDateTime.now());
                LoggingConfigUtil.logDebugIfVerbose(logger, "Successfully called StepEventBus.stepFinished() with failure screenshots");
                // 清空截图列表，避免重复处理
                stepScreenshots.clear();
            } catch (Exception e) {
                logger.error("Failed to call StepEventBus.stepFinished() with failure screenshots", e);
            }
        }

        recordTestData("stepFailureStackTrace", getStackTrace(failure.getException()));
    }

    @Override
    public void lastStepFailed(StepFailure failure) {
        String errorMsg = failure != null ? failure.getException().getMessage() : "Unknown";
        LoggingConfigUtil.logErrorIfVerbose(logger, "Last step failed: {}", errorMsg);
        recordTestData("lastStepFailure", errorMsg);

        takeScreenshotAndRegister("FINAL_FAILURE");

        // 步骤失败时，将截图传递给 Serenity
        List<ScreenshotAndHtmlSource> stepScreenshots = currentStepScreenshots.get();
        if (stepScreenshots != null && !stepScreenshots.isEmpty()) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Manually calling StepEventBus.stepFinished() with {} screenshots after last step failure", stepScreenshots.size());
            try {
                StepEventBus.getEventBus().stepFinished(stepScreenshots, ZonedDateTime.now());
                LoggingConfigUtil.logDebugIfVerbose(logger, "Successfully called StepEventBus.stepFinished() with last step failure screenshots");
                // 清空截图列表，避免重复处理
                stepScreenshots.clear();
            } catch (Exception e) {
                logger.error("Failed to call StepEventBus.stepFinished() with last step failure screenshots", e);
            }
        }

        // 获取浏览器重启策略
        String restartBrowserForEach = SystemEnvironmentVariables.currentEnvironmentVariables()
                .getProperty("serenity.restart.browser.for.each", "scenario");

        // 无论重启策略如何，都清理当前的上下文和页面，以便重试时使用新的上下文和页面
        LoggingConfigUtil.logInfoIfVerbose(logger, "Last step failed - cleaning up context and page resources (strategy: {})", restartBrowserForEach);
        try {
            PlaywrightManager.closePage();
            PlaywrightManager.closeContext();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Cleaned up page and context resources after last step failure");
        } catch (Exception e) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Failed to clean up resources after last step failure: {}", e.getMessage());
        }
    }

    @Override
    public void stepIgnored() {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Step ignored");
        recordTestData("stepIgnored", true);
    }

    /**
     * 截图并添加到当前步骤的截图列表
     *
     * @param screenshotName 截图名称
     * @return 截图对象
     */
    private ScreenshotAndHtmlSource takeScreenshot(String screenshotName) {
        // 防止递归调用
        if (takingScreenshot.get()) {
            LoggingConfigUtil.logDebugIfVerbose(
                    logger, "Skipping screenshot - already taking screenshot to prevent recursion");
            return null;
        }

        try {
            takingScreenshot.set(true);

            String screenshotPath = PlaywrightManager.takeScreenshot(screenshotName);

            if (screenshotPath != null) {
                File pngFile = new File(screenshotPath);
                File htmlFile = new File(screenshotPath.replace(".png", ".html"));

                if (pngFile.exists()) {
                    ScreenshotAndHtmlSource screenshotAndHtmlSource =
                            new ScreenshotAndHtmlSource(pngFile, htmlFile.exists() && htmlFile.length() > 0 ? htmlFile : null);

                    LoggingConfigUtil.logDebugIfVerbose(logger, "Screenshot captured: {} -> {}", screenshotName, pngFile.getName());
                    screenshotCounter.incrementAndGet();

                    return screenshotAndHtmlSource;
                } else {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Screenshot file not found: {}", screenshotPath);
                }
            }

        } catch (Exception e) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Failed to capture screenshot: {}", screenshotName, e);
        } finally {
            takingScreenshot.set(false);
        }

        return null;
    }

    /**
     * 截图并添加到当前步骤的截图列表
     *
     * @param screenshotName 截图名称
     */
    private void takeScreenshotAndRegister(String screenshotName) {
        ScreenshotAndHtmlSource screenshot = takeScreenshot(screenshotName);
        if (screenshot != null) {
            currentStepScreenshots.get().add(screenshot);
            LoggingConfigUtil.logDebugIfVerbose(
                    logger, "Screenshot added to step: {}", screenshotName);
        }
    }

    private void takeFailureScreenshot(ExecutedStepDescription step) {
        String stepName = (step != null && step.getTitle() != null) ? step.getTitle() : "unknown_step";
        String sanitizedStepName = sanitizeFilename(stepName);
        String screenshotName = "FAILURE_" + (sanitizedStepName != null ? sanitizedStepName : "step");
        takeScreenshotAndRegister(screenshotName);
    }

    /**
     * Sanitize filename by replacing invalid characters
     */
    private String sanitizeFilename(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "unnamed";
        }
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private void recordTestData(String key, Object value) {
        String testName = currentTestName.get();
        if (testName != null && key != null) {
            String dataKey = testName + "." + key;
            testData.put(dataKey, value);

            // 所有测试数据都通过 LoggingConfigUtil 控制输出
            LoggingConfigUtil.logDebugIfVerbose(
                    logger, "Test data recorded: {} = {}", key, value);
        } else {
            if (testName == null) {
                logger.warn("Cannot record test data: testName is null");
            }
            if (key == null) {
                logger.warn("Cannot record test data: key is null");
            }
        }
    }

    private void cleanupThreadLocals() {
        testStartTime.remove();
        currentTestName.remove();
        currentTestResult.remove();
        stepStartTime.remove();
        currentStepName.remove();
    }

    private String sanitizeName(String name) {
        return sanitizeNameStatic(name);
    }


    private static String sanitizeNameStatic(String name) {
        return name == null ? "unnamed" : name.replaceAll("[^a-zA-Z0-9]", "_");
    }

    private String getStackTrace(Throwable throwable) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            throwable.printStackTrace(new java.io.PrintStream(baos));
            return baos.toString();
        } catch (Exception e) {
            return "Failed to get stack trace: " + e.getMessage();
        }
    }

    @Override
    public void stepFinished(List<ScreenshotAndHtmlSource> screenshots) {
        stepFinishedInternal(screenshots, null);
    }

    @Override
    public void stepFinished(List<ScreenshotAndHtmlSource> screenshots, ZonedDateTime timestamp) {
        stepFinishedInternal(screenshots, timestamp);
    }

    private void stepFinishedInternal(List<ScreenshotAndHtmlSource> screenshots, ZonedDateTime timestamp) {
        Long startTime = stepStartTime.get();
        if (startTime == null) return;

        long duration = System.currentTimeMillis() - startTime;
        recordTestData("stepDuration", duration);
        if (timestamp != null) {
            recordTestData("stepFinishTimestamp", timestamp.toInstant().toEpochMilli());
        }

        // 根据截图策略在步骤结束时截图
        String stepName = currentStepName.get();
        String cucumberStep = currentCucumberStep.get();

        // 只为 Cucumber 级别步骤截图
        if (stepName != null && !stepName.isEmpty() && stepName.equals(cucumberStep)) {
            if (screenshotStrategy == ScreenshotStrategy.AFTER_EACH_STEP) {
                takeScreenshotAndRegister("STEP_" + sanitizeName(stepName));
            } else if (screenshotStrategy == ScreenshotStrategy.BEFORE_AND_AFTER_EACH_STEP) {
                takeScreenshotAndRegister("STEP_AFTER_" + sanitizeName(stepName));
            }
            // 清除 Cucumber 步骤记录
            currentCucumberStep.remove();
        }

        // 将当前步骤的截图合并到 Serenity 传入的截图列表中
        List<ScreenshotAndHtmlSource> stepScreenshots = currentStepScreenshots.get();
        if (stepScreenshots != null && !stepScreenshots.isEmpty()) {
            LoggingConfigUtil.logDebugIfVerbose(
                    logger, "Before merge: stepScreenshots.size={}, screenshots.size={}",
                    stepScreenshots.size(), screenshots != null ? screenshots.size() : 0);

            if (screenshots != null) {
                // 追加我们的截图
                screenshots.addAll(stepScreenshots);
                LoggingConfigUtil.logDebugIfVerbose(
                        logger, "After merge: screenshots.size={}", screenshots.size());
            }
            // 清空当前步骤的截图列表，避免影响下一个步骤
            stepScreenshots.clear();
            LoggingConfigUtil.logDebugIfVerbose(
                    logger, "Cleared stepScreenshots after merging");
        } else {
            LoggingConfigUtil.logDebugIfVerbose(
                    logger, "No step screenshots to merge");
        }

        if (screenshots != null && !screenshots.isEmpty()) {
            recordTestData("stepScreenshotsCount", screenshots.size());
            // 手动调用 StepEventBus 的 stepFinished 方法来传递截图
            LoggingConfigUtil.logDebugIfVerbose(
                    logger, "Manually calling StepEventBus.stepFinished() with {} screenshots", screenshots.size());
            try {
                StepEventBus.getEventBus().stepFinished(screenshots, ZonedDateTime.now());
                LoggingConfigUtil.logDebugIfVerbose(
                        logger, "Successfully called StepEventBus.stepFinished() with screenshots");
            } catch (Exception e) {
                logger.error("Failed to call StepEventBus.stepFinished() with screenshots", e);
            }
        } else {
            LoggingConfigUtil.logDebugIfVerbose(
                    logger, "Serenity screenshot list is empty or null");
        }

        LoggingConfigUtil.logDebugIfVerbose(logger, "Step completed in {}ms", duration);
    }

    @Override
    public void takeScreenshots(List<ScreenshotAndHtmlSource> screenshots) {
        LoggingConfigUtil.logDebugIfVerbose(logger, "takeScreenshots called with {} screenshots",
                screenshots != null ? screenshots.size() : 0);

        // 将当前步骤的截图添加到传入的列表中
        List<ScreenshotAndHtmlSource> stepScreenshots = currentStepScreenshots.get();
        if (stepScreenshots != null && !stepScreenshots.isEmpty() && screenshots != null) {
            screenshots.addAll(stepScreenshots);
            LoggingConfigUtil.logDebugIfVerbose(logger, "Added {} screenshots from currentStepScreenshots to takeScreenshots list", stepScreenshots.size());
            // 不清空列表，因为 stepFinished 也会处理
        }
    }

    @Override
    public void takeScreenshots(TestResult result, List<ScreenshotAndHtmlSource> screenshots) {
        LoggingConfigUtil.logDebugIfVerbose(logger, "takeScreenshots called with result {} and {} screenshots",
                result, screenshots != null ? screenshots.size() : 0);

        // 将当前步骤的截图添加到传入的列表中
        List<ScreenshotAndHtmlSource> stepScreenshots = currentStepScreenshots.get();
        if (stepScreenshots != null && !stepScreenshots.isEmpty() && screenshots != null) {
            screenshots.addAll(stepScreenshots);
            LoggingConfigUtil.logDebugIfVerbose(logger, "Added {} screenshots from currentStepScreenshots to takeScreenshots list (result: {})",
                    stepScreenshots.size(), result);
        }
    }

    @Override
    public void testSuiteStarted(Class<?> testClass) {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Test suite started for class: {}", testClass);
    }

    @Override
    public void testSuiteStarted(Story story) {
        LoggingConfigUtil.logDebugIfVerbose(
                logger, "Test suite started for story: {}", story.getStoryName());
    }

    @Override
    public void testSuiteFinished() {
        LoggingConfigUtil.logDebugIfVerbose(
                logger, "Test suite finished");

        // 防止多次调用时重复输出清理日志
        synchronized (PlaywrightListener.class) {
            if (!testSuiteFinishedLogged) {
                testSuiteFinishedLogged = true;
                LoggingConfigUtil.logInfoIfVerbose(logger, "Cleaning up all Playwright resources at test suite finish");
                try {
                    PlaywrightManager.cleanupForFeature();
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Cleaned up all resources at test suite finish");
                } catch (Exception e) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Failed to clean up resources at test suite finish: {}", e.getMessage());
                }
            } else {
                // 已记录过，只执行清理但不输出日志
                try {
                    PlaywrightManager.cleanupForFeature();
                } catch (Exception e) {
                    logger.debug("Failed to cleanup resources: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public void testStarted(String testName, String testMethod) {
        currentTestName.set(testName);
        testStartTime.set(System.currentTimeMillis());
        totalTests.incrementAndGet();

        try {
            FrameworkCore.getInstance().beforeTest();
            recordTestData("testStart", System.currentTimeMillis());
            recordTestData("testMethod", testMethod);
            logger.info("Test initialized: {} (method: {})", testName, testMethod);
        } catch (Exception e) {
            logger.error("Failed to initialize Playwright for test: {}", testName, e);
        }
    }

    @Override
    public void testStarted(String testName, String testMethod, ZonedDateTime startTime) {
        currentTestName.set(testName);
        testStartTime.set(startTime != null ? startTime.toInstant().toEpochMilli() : System.currentTimeMillis());
        totalTests.incrementAndGet();

        try {
            FrameworkCore.getInstance().beforeTest();
            long start = startTime != null ? startTime.toInstant().toEpochMilli() : System.currentTimeMillis();
            recordTestData("testStart", start);
            recordTestData("testMethod", testMethod);
            recordTestData("startTimeZoned", startTime);
            logger.info("Test initialized: {} (method: {}, startTime: {})", testName, testMethod, startTime);
        } catch (Exception e) {
            logger.error("Failed to initialize Playwright for test: {}", testName, e);
        }
    }

    @Override
    public void testFinished(TestOutcome result) {
        logger.info("Test finished: {}", result);
        // 更新当前测试结果
        if (result != null && result.getResult() != null) {
            currentTestResult.set(result.getResult());
        }
        testFinishedInternal();

        // 自动断言API监控结果
        autoAssertApiMonitoringIfNeeded();

        // 获取浏览器重启策略
        String restartBrowserForEach = SystemEnvironmentVariables.currentEnvironmentVariables()
                .getProperty("serenity.restart.browser.for.each", "scenario");

        // 根据浏览器重启策略决定清理方式
        if ("scenario".equalsIgnoreCase(restartBrowserForEach)) {
            // Scenario 模式：测试结束后立即清理所有资源（Context/Page/Browser）
            logger.info("Cleaning up Playwright resources (scenario-level restart)");
            PlaywrightManager.cleanupForScenario();
        } else {
            // Feature 模式：只清理页面状态，保留 Context/Page 供复用
            logger.debug("Feature mode - cleaning page state while keeping Context/Page");
            PlaywrightManager.cleanupPageState();
        }

        // 检查是否需要重试失败的测试
        checkAndTriggerRerun();
    }

    @Override
    public void testFinished(TestOutcome result, boolean isInDataDrivenTest, ZonedDateTime finishTime) {
        if (result == null) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "TestOutcome is null in testFinished with time, skipping processing");
            return;
        }

        LoggingConfigUtil.logDebugIfVerbose(logger, "Test finished: {}, isDataDriven: {}, finishTime: {}", result, isInDataDrivenTest, finishTime);
        Long startTime = testStartTime.get();
        if (startTime == null) {
            return;
        }

        long duration = System.currentTimeMillis() - startTime;
        String testName = currentTestName.get();

        recordTestData("testEnd", finishTime != null ? finishTime.toInstant().toEpochMilli() : System.currentTimeMillis());
        recordTestData("testDuration", duration);
        recordTestData("isDataDrivenTest", isInDataDrivenTest);

        if (result != null && result.getResult() != null) {
            recordTestData("testResult", result.toString());

            switch (result.getResult()) {
                case SUCCESS:
                    passedTests.incrementAndGet();
                    break;
                case PENDING:
                    // PENDING表示测试结果未知，可能是测试中途失败或超时
                    // 将其计为失败，以确保准确统计
                    LoggingConfigUtil.logWarnIfVerbose(logger, "Test result is PENDING, counting as failed: {}", testName);
                    failedTests.incrementAndGet();
                    break;
                case FAILURE:
                case ERROR:
                case UNDEFINED:
                    failedTests.incrementAndGet();
                    break;
                case SKIPPED:
                    skippedTests.incrementAndGet();
                    break;
                default:
                    LoggingConfigUtil.logWarnIfVerbose(logger, "Unknown test result: {}", result.getResult());
                    break;
            }
        } else {
            // result为null或result.getResult()为null，计为失败
            LoggingConfigUtil.logWarnIfVerbose(logger, "Test result is null, counting as failed: {}", testName);
            failedTests.incrementAndGet();
        }

        LoggingConfigUtil.logInfoIfVerbose(logger, "Test completed: {} in {}ms (DataDriven: {}, Result: {})", testName, duration, isInDataDrivenTest, result);

        // 自动断言API监控结果
        autoAssertApiMonitoringIfNeeded();

        // 重置监控失败标志
        RealApiMonitor.resetMonitoringFailure();

        cleanupThreadLocals();
    }

    @Override
    public void testRetried() {
        logger.debug("Test retried");
    }

    @Override
    public void skippedStepStarted(ExecutedStepDescription step) {
        logger.debug("Skipped step started: {}", step);
    }

    @Override
    public void stepFailed(StepFailure failure, List<ScreenshotAndHtmlSource> screenshots,
                           boolean takeScreenshotOnFailure, ZonedDateTime timestamp) {
        if (failure == null) return;

        logger.error("Step failure detected: {}", failure.getException().getMessage());

        recordTestData("stepFailure", failure.getException().getMessage());
        recordTestData("stepFailureCause", failure.getException().getClass().getSimpleName());
        if (timestamp != null) {
            recordTestData("stepFailureTimestamp", timestamp.toInstant().toEpochMilli());
        }

        if (takeScreenshotOnFailure) {
            takeFailureScreenshot(null);
        }

        // 步骤失败时，将截图传递给 Serenity
        List<ScreenshotAndHtmlSource> stepScreenshots = currentStepScreenshots.get();
        if (stepScreenshots != null && !stepScreenshots.isEmpty()) {
            logger.debug("Manually calling StepEventBus.stepFinished() with {} screenshots after step failure (param version)", stepScreenshots.size());
            try {
                StepEventBus.getEventBus().stepFinished(stepScreenshots, ZonedDateTime.now());
                logger.debug("Successfully called StepEventBus.stepFinished() with failure screenshots (param version)");
                // 清空截图列表，避免重复处理
                stepScreenshots.clear();
            } catch (Exception e) {
                logger.error("Failed to call StepEventBus.stepFinished() with failure screenshots (param version)", e);
            }
        }

        recordTestData("stepFailureStackTrace", getStackTrace(failure.getException()));
    }

    @Override
    public void stepPending() {
        logger.debug("Step pending");
    }

    @Override
    public void stepPending(String description) {
        logger.debug("Step pending: {}", description);
    }

    @Override
    public void testFailed(TestOutcome result, Throwable throwable) {
        logger.error("Test failed: {}", result, throwable);

        // 注意：不在 testFailed 中 increment failedTests，避免与 testFinished 重复计数
        // 测试失败统计由 testFinished 统一处理

        // 立即检查是否需要重试
        checkAndTriggerRerun();
    }

    @Override
    public void testIgnored() {
        logger.debug("Test ignored");
        skippedTests.incrementAndGet();
    }

    @Override
    public void testPending() {
        logger.debug("Test pending");
    }

    @Override
    public void testIsManual() {
        logger.debug("Test is manual");
    }

    @Override
    public void notifyScreenChange() {
        logger.debug("Screen change notified");
        takeScreenshotAndRegister("SCREEN_CHANGE");
    }

    @Override
    public void useExamplesFrom(DataTable dataTable) {
        logger.debug("Using examples from data table with {} rows", dataTable.getRows().size());
    }

    @Override
    public void addNewExamplesFrom(DataTable dataTable) {
        logger.debug("Adding new examples from data table with {} rows", dataTable.getRows().size());
    }

    @Override
    public void exampleStarted(Map<String, String> data) {
        logger.debug("Example started with data: {}", data);
    }

    @Override
    public void exampleFinished() {
        logger.debug("Example finished");
    }

    @Override
    public void assumptionViolated(String message) {
        logger.debug("Assumption violated: {}", message);
    }

    @Override
    public void testRunFinished() {
        logger.info(getPerformanceStats());

        // 检查是否需要重试失败的测试
        checkAndTriggerRerun();


    }



    private void checkAndTriggerRerun() {
        // 只在首轮运行时检查（非重试模式）
        String rerunMode = System.getProperty("rerun.mode", "false");
        if (Boolean.parseBoolean(rerunMode)) {
            logger.debug("In rerun mode, skipping rerun check");
            return;
        }

        // 检查是否设置了 rerunFailingTestsCount
        String rerunCountStr = System.getProperty("rerunFailingTestsCount");
        if (rerunCountStr == null || rerunCountStr.trim().isEmpty()) {
            logger.debug("rerunFailingTestsCount not set, skipping rerun");
            return;
        }

        // 检查是否有失败的测试
        long failedCount = failedTests.get();
        if (failedCount == 0) {
            logger.debug("No failed tests, no rerun needed");
            return;
        }

        // 有失败的测试，触发 rerun（单进程模式：不启动新进程）
        logger.info("Found {} failed tests, triggering rerun in same JVM process...", failedCount);
        triggerRerunInSameProcess(rerunCountStr);
    }

    /**
     * 在同一个进程中执行 rerun（使用文件持久化共享数据）
     */
    private void triggerRerunInSameProcess(String rerunCountStr) {
        try {
            int maxRerunAttempts = Integer.parseInt(rerunCountStr);
            logger.info("Starting rerun with max attempts: {}", maxRerunAttempts);

            // 保存当前重试数据到文件，供 rerun 进程使用
            PlaywrightRetryListener.getInstance().saveToFile();

            RerunConfiguration configuration = RerunConfiguration.getInstance();
            configuration.lazyInitialize();

            RerunProcessExecutor executor = new RerunProcessExecutor(configuration);

            executor.startSession();

            // 循环执行多轮重试，直到成功或达到最大次数
            RerunProcessExecutor.RerunExecutionResult result = null;
            boolean rerunSuccess = false;

            for (int round = 1; round <= maxRerunAttempts; round++) {
                result = executor.executeRerun(round, maxRerunAttempts);

                if (result.isSuccess()) {
                    rerunSuccess = true;
                    logger.info("Rerun completed successfully at round {}/{}", round, maxRerunAttempts);
                    break;
                } else {
                    logger.warn("Rerun round {}/{} failed - exit code: {}", round, maxRerunAttempts, result.getExitCode());
                }
            }

            if (!rerunSuccess) {
                logger.error("All rerun attempts failed after {} rounds", maxRerunAttempts);
            }

        } catch (Exception e) {
            logger.error("Failed to execute rerun", e);
        }
    }

    public static String getPerformanceStats() {
        return String.format(
                "Performance Statistics:\n" +
                        "Total Tests: %d\n" +
                        "Passed: %d (%.1f%%)\n" +
                        "Failed: %d (%.1f%%)\n" +
                        "Skipped: %d (%.1f%%)\n" +
                        "Screenshots Taken: %d",
                totalTests.get(),
                passedTests.get(),
                calculatePercentage(passedTests.get()),
                failedTests.get(),
                calculatePercentage(failedTests.get()),
                skippedTests.get(),
                calculatePercentage(skippedTests.get()),
                screenshotCounter.get()
        );
    }

    private static double calculatePercentage(long value) {
        return totalTests.get() > 0 ? (value * 100.0 / totalTests.get()) : 0.0;
    }

    public static void resetStats() {
        totalTests.set(0);
        passedTests.set(0);
        failedTests.set(0);
        skippedTests.set(0);
        screenshotCounter.set(0);
        testData.clear();
    }

}
