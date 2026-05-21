package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkCore;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiMonitorContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.strategy.ScreenshotStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.hsbc.cmb.hk.dbb.automation.retry.configuration.RerunConfiguration;
import com.hsbc.cmb.hk.dbb.automation.retry.executor.RerunProcessExecutor;
import com.hsbc.cmb.hk.dbb.automation.retry.listener.PlaywrightRetryListener;
import net.thucydides.core.steps.StepEventBus;
import net.thucydides.model.domain.DataTable;
import net.thucydides.model.domain.Story;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import net.thucydides.model.environment.SystemEnvironmentVariables;
import net.thucydides.model.screenshots.ScreenshotAndHtmlSource;
import net.thucydides.model.steps.ExecutedStepDescription;
import net.thucydides.model.steps.StepFailure;
import net.thucydides.model.steps.StepListener;
import net.thucydides.model.util.EnvironmentVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import java.util.Map;

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

    // 防止 stepFailed 和 stepFinished 重复调用 StepEventBus.stepFinished()
    private static final ThreadLocal<Boolean> failureScreenshotsAlreadySent = ThreadLocal.withInitial(() -> false);

    // ⭐ 防止 stepFinished() 无参版与 stepFinishedInternal() 参数化版双重处理
    private static final ThreadLocal<Boolean> stepFinishProcessed = ThreadLocal.withInitial(() -> false);

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

    // ⭐⭐⭐ API 断言失败跟踪 — 每个测试线程独立的上下文
    private static final ThreadLocal<ApiMonitorContext> apiMonitorContext = ThreadLocal.withInitial(ApiMonitorContext::new);

    public PlaywrightListener() {
        // 从环境变量中读取截图策略配置
        EnvironmentVariables environmentVariables = SystemEnvironmentVariables.currentEnvironmentVariables();
        this.screenshotStrategy = ScreenshotStrategy.from(environmentVariables);

        LoggingConfigUtil.logInfoIfVerbose(logger, "Screenshot strategy initialized: {}", screenshotStrategy);
    }

    @Override
    public void testStarted(String testName) {
        // 生成唯一名称：原名称 + 线程ID（保证同名 scenario 不合并）
        String uniqueTestName = testName + "_" + Thread.currentThread().threadId() + "_" + System.currentTimeMillis();
        currentTestName.set(uniqueTestName);  // ⭐ 修复：确保 currentTestName 被设置（与双参数版本一致）
        testStartTime.set(System.currentTimeMillis());
        currentTestResult.set(TestResult.PENDING); // 初始化为PENDING，避免默认为SUCCESS导致统计错误
        totalTests.incrementAndGet();

        // ⭐⭐⭐ 新增：重置 API 监控上下文
        resetApiMonitorContext();

        // 测试开始时截图（根据策略决定）
        if (screenshotStrategy == ScreenshotStrategy.BEFORE_AND_AFTER_EACH_STEP) {
            takeScreenshotAndRegister("TEST_START_" + uniqueTestName);
        }
        recordTestData("testStart", System.currentTimeMillis());
        LoggingConfigUtil.logInfoIfVerbose(logger, "Test initialized: {}", uniqueTestName);
    }

    private void testFinishedInternal() {
        Long startTime = testStartTime.get();
        if (startTime == null) {
            return;
        }

        // 【关键】使用 try-finally 确保 ThreadLocal 始终被清理，即使中间抛出异常
        try {
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
        } finally {
            // 【关键】finally 保证：无论中间是否抛异常，ThreadLocal 一定会被清理
            cleanupThreadLocals();
        }
    }

    @Override
    public void testSkipped() {
        skippedTests.incrementAndGet();
        LoggingConfigUtil.logInfoIfVerbose(logger, "Test skipped: {}", currentTestName.get());
        recordTestData("testSkipped", true);
    }

    /**
     * ⭐⭐⭐ 核心修复：强制清空当前步骤截图列表（彻底杜绝残留）
     * 
     * 问题根因：ThreadLocal.remove() 只移除引用，不清除列表内容。
     * 如果外部代码在 remove 前拿到了列表引用，remove 后旧数据仍可通过该引用访问，
     * 导致 A 步骤的截图被 B 步骤拿到 → 报告错乱。
     * 
     * 正确做法：先清空内容 + 再移除引用，双保险。
     */
    private void clearStepScreenshotsImmediately() {
        try {
            List<ScreenshotAndHtmlSource> screenshots = currentStepScreenshots.get();
            if (screenshots != null && !screenshots.isEmpty()) {
                logger.debug("Force clearing {} leftover screenshot(s)", screenshots.size());
                screenshots.clear(); // 先清空内容（断开所有元素引用）
            }
        } catch (Exception e) {
            logger.debug("Error while clearing step screenshots", e);
        } finally {
            currentStepScreenshots.remove(); // 再移除 ThreadLocal 引用
        }
    }

    @Override
    public void stepStarted(ExecutedStepDescription step) {
        if (step == null) return;

        // ⭐⭐⭐ 第一步（最关键）：强制清空上一步骤残留的所有截图，根治脏数据
        clearStepScreenshotsImmediately();

        // 重置所有防双重处理标志
        stepFinishProcessed.set(false);
        failureScreenshotsAlreadySent.set(false);

        // 用全新 ArrayList 替换旧列表（彻底断开任何外部引用）
        currentStepScreenshots.set(new ArrayList<>());

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
        // ⭐ 防双重处理：如果参数化版 stepFinishedInternal 已经处理过，跳过
        if (stepFinishProcessed.get()) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "stepFinished() skipped - already processed by stepFinishedInternal");
            return;
        }

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

        // 手动调用 StepEventBus 的 stepFinished 方法来传递截图（仅在 stepFailed 未发送过时）
        List<ScreenshotAndHtmlSource> stepScreenshots = currentStepScreenshots.get();
        if (stepScreenshots != null && !stepScreenshots.isEmpty() && !failureScreenshotsAlreadySent.get()) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Manually calling StepEventBus.stepFinished() with {} screenshots", stepScreenshots.size());
            try {
                StepEventBus.getEventBus().stepFinished(stepScreenshots, ZonedDateTime.now());
                LoggingConfigUtil.logDebugIfVerbose(logger, "Successfully called StepEventBus.stepFinished() with screenshots");
                // 清空截图列表，避免重复添加
                stepScreenshots.clear();
                // 标记已处理，防止参数化版本重复处理
                stepFinishProcessed.set(true);
            } catch (Exception e) {
                logger.error("Failed to call StepEventBus.stepFinished() with screenshots", e);
            }
        } else if (failureScreenshotsAlreadySent.get()) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Skipping StepEventBus.stepFinished() - already sent by stepFailed");
            if (stepScreenshots != null) stepScreenshots.clear();
            stepFinishProcessed.set(true);
        } else {
            LoggingConfigUtil.logDebugIfVerbose(logger, "No screenshots to pass to StepEventBus.stepFinished()");
        }

        // 重置标志供下一个步骤使用
        // ⭐ 不再 remove stepFinishProcessed 和 failureScreenshotsAlreadySent
        // 改为在 stepStarted 中重置为 false，防止步骤间窗口期注入脏数据
        failureScreenshotsAlreadySent.set(false);
        stepFinishProcessed.set(false);
    }

    @Override
    public void stepFailed(StepFailure failure) {
        if (failure == null) return;

        // ⭐ 防重复：如果已经发送过失败截图（如 stepFailed param 版已处理），直接跳过
        if (failureScreenshotsAlreadySent.get()) {
            logger.debug("stepFailed: screenshots already sent by previous handler, skipping");
            return;
        }

        // ⭐ 无论成功失败，先强制清空残留截图（防止异常场景污染下一步骤）
        clearStepScreenshotsImmediately();
        currentStepScreenshots.set(new ArrayList<>());  // 重新初始化供本步骤使用

        logger.error("Step failure detected: {}", failure.getException().getMessage());
        recordTestData("stepFailure", failure.getException().getMessage());
        recordTestData("stepFailureCause", failure.getException().getClass().getSimpleName());

        // ⭐ 关键修复：无论什么策略，都在步骤失败瞬间截图
        // 这确保捕获的是失败时的真实页面状态（错误信息、弹窗等）
        // BEFORE_AND_AFTER_EACH_STEP / AFTER_EACH_STEP：补充一张 FAILURE 截图
        // FOR_FAILURES：正常走失败截图逻辑
        if (screenshotStrategy != ScreenshotStrategy.DISABLED) {
            String stepName = currentStepName.get();
            String sanitized = sanitizeFilename(stepName);
            takeScreenshotAndRegister("FAILURE_" + (sanitized != null ? sanitized : "step"));
            logger.info("Failure screenshot captured at step failure moment for: {}", stepName);
        }

        // 步骤失败时，将截图传递给 Serenity（仅一次）
        List<ScreenshotAndHtmlSource> stepScreenshots = currentStepScreenshots.get();
        if (stepScreenshots != null && !stepScreenshots.isEmpty()) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Manually calling StepEventBus.stepFinished() with {} screenshots after step failure", stepScreenshots.size());
            try {
                StepEventBus.getEventBus().stepFinished(stepScreenshots, ZonedDateTime.now());
                LoggingConfigUtil.logDebugIfVerbose(logger, "Successfully called StepEventBus.stepFinished() with failure screenshots");
                // 标记已发送，防止后续 stepFinished() / lastStepFailed 重复调用
                failureScreenshotsAlreadySent.set(true);
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
        // ⭐ 最优先检查：如果 stepFailed 已经发送过截图和报告，全部跳过（零开销）
        if (failureScreenshotsAlreadySent.get()) {
            logger.debug("lastStepFailed: screenshots already sent by stepFailed, skipping all work");
            return;
        }

        // ⭐ 无论成功失败，先强制清空残留截图（防止异常场景污染下一步骤/下一用例）
        clearStepScreenshotsImmediately();
        currentStepScreenshots.set(new ArrayList<>());

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
                failureScreenshotsAlreadySent.set(true);  // ⭐ 标记已发送，防止后续重复
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
            // 在测试生命周期之外调用时，静默跳过（DEBUG级别日志）
            if (testName == null) {
                logger.debug("Skip recording test data: testName is null (key={})", key);
            }
            if (key == null) {
                logger.debug("Skip recording test data: key is null");
            }
        }
    }

    private void cleanupThreadLocals() {
        // ⭐⭐⭐ 最关键：先强制清空截图内容（防止残留），再移除所有 ThreadLocal
        clearStepScreenshotsImmediately();

        testStartTime.remove();
        currentTestName.remove();
        currentTestResult.remove();
        stepStartTime.remove();
        currentStepName.remove();
        currentCucumberStep.remove();
        takingScreenshot.remove();
        failureScreenshotsAlreadySent.remove();
        stepFinishProcessed.remove();  // ⭐ 清理防双重处理标志
        // currentStepScreenshots 已由 clearStepScreenshotsImmediately() 处理

        // ⭐⭐⭐ 新增：清理 API 监控上下文
        apiMonitorContext.remove();
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
        // ⭐ 防双重处理：如果无参版 stepFinished() 已经处理过，跳过截图和发送
        boolean alreadyProcessed = stepFinishProcessed.get();

        Long startTime = stepStartTime.get();
        if (startTime == null) {
            // 即使没有 startTime 也要清理标志
            stepFinishProcessed.remove();
            return;
        }

        long duration = System.currentTimeMillis() - startTime;
        recordTestData("stepDuration", duration);
        if (timestamp != null) {
            recordTestData("stepFinishTimestamp", timestamp.toInstant().toEpochMilli());
        }

        // 根据截图策略在步骤结束时截图（⭐ 仅未处理时，防止与 stepFinished() 无参版重复）
        String stepName = currentStepName.get();
        String cucumberStep = currentCucumberStep.get();

        // 只为 Cucumber 级别步骤截图
        if (!alreadyProcessed && stepName != null && !stepName.isEmpty() && stepName.equals(cucumberStep)) {
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
        
        // ⭐ 防残留：合并前强制清理上一步骤遗留的截图（如 SCREEN_CHANGE 等非当前步骤产生的）
        if (stepScreenshots != null && !stepScreenshots.isEmpty()) {
            LoggingConfigUtil.logDebugIfVerbose(logger,
                    "Before merge cleanup: stepScreenshots.size={} (will be merged into Serenity list)",
                    stepScreenshots.size());

            if (screenshots != null) {
                // 追加我们的截图
                screenshots.addAll(stepScreenshots);
                LoggingConfigUtil.logDebugIfVerbose(
                        logger, "After merge: total screenshots.size={}", screenshots.size());
            }
            // 清空当前步骤的截图列表，避免影响下一个步骤（防残留核心）
            stepScreenshots.clear();
            LoggingConfigUtil.logDebugIfVerbose(
                    logger, "Cleared stepScreenshots after merging");
        } else {
            LoggingConfigUtil.logDebugIfVerbose(
                    logger, "No step screenshots to merge (list is empty or null)");
        }

        // ⭐ 防双重发送：仅当无参版 stepFinished() 未处理时才发送 StepEventBus
        if (!alreadyProcessed && screenshots != null && !screenshots.isEmpty() && !failureScreenshotsAlreadySent.get()) {
            recordTestData("stepScreenshotsCount", screenshots.size());
            // 手动调用 StepEventBus 的 stepFinished 方法来传递截图
            LoggingConfigUtil.logDebugIfVerbose(
                    logger, "Manually calling StepEventBus.stepFinished() with {} screenshots", screenshots.size());
            try {
                StepEventBus.getEventBus().stepFinished(screenshots, ZonedDateTime.now());
                LoggingConfigUtil.logDebugIfVerbose(
                        logger, "Successfully called StepEventBus.stepFinished() with screenshots");
                // 标记已处理，防止无参版 stepFinished() 重复处理
                stepFinishProcessed.set(true);
            } catch (Exception e) {
                logger.error("Failed to call StepEventBus.stepFinished() with screenshots", e);
            }
        } else if (failureScreenshotsAlreadySent.get()) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Skipping StepEventBus.stepFinished() in stepFinishedInternal - already sent by stepFailed");
            // ⭐ 防残留：即使已发送过，也要确保列表被清空
            if (screenshots != null) screenshots.clear();
        } else {
            LoggingConfigUtil.logDebugIfVerbose(
                    logger, "Serenity screenshot list is empty or null");
        }

        // 重置标志供下一个步骤使用
        // ⭐ 不再 remove stepFinishProcessed 和 failureScreenshotsAlreadySent
        // 改为在 stepStarted 中重置为 false，防止步骤间窗口期注入脏数据
        failureScreenshotsAlreadySent.set(false);
        stepFinishProcessed.set(false);

        LoggingConfigUtil.logDebugIfVerbose(logger, "Step completed in {}ms", duration);
    }

    @Override
    public void takeScreenshots(List<ScreenshotAndHtmlSource> screenshots) {
        LoggingConfigUtil.logDebugIfVerbose(logger, "takeScreenshots called with {} screenshots",
                screenshots != null ? screenshots.size() : 0);

        // 将当前步骤的截图添加到传入的列表中（仅在当前步骤活跃时，防止残留）
        List<ScreenshotAndHtmlSource> stepScreenshots = currentStepScreenshots.get();
        if (stepScreenshots != null && !stepScreenshots.isEmpty() && screenshots != null && stepStartTime.get() != null) {
            screenshots.addAll(stepScreenshots);
            LoggingConfigUtil.logDebugIfVerbose(logger, "Added {} screenshots from currentStepScreenshots to takeScreenshots list", stepScreenshots.size());
            // 合并后立即清空，防止重复注入
            stepScreenshots.clear();
        }
    }

    @Override
    public void takeScreenshots(TestResult result, List<ScreenshotAndHtmlSource> screenshots) {
        LoggingConfigUtil.logDebugIfVerbose(logger, "takeScreenshots called with result {} and {} screenshots",
                result, screenshots != null ? screenshots.size() : 0);

        // 将当前步骤的截图添加到传入的列表中（仅在当前步骤活跃时）
        List<ScreenshotAndHtmlSource> stepScreenshots = currentStepScreenshots.get();
        if (stepScreenshots != null && !stepScreenshots.isEmpty() && screenshots != null && stepStartTime.get() != null) {
            screenshots.addAll(stepScreenshots);
            LoggingConfigUtil.logDebugIfVerbose(logger, "Added {} screenshots from currentStepScreenshots to takeScreenshots list (result: {})",
                    stepScreenshots.size(), result);
            // 合并后立即清空
            stepScreenshots.clear();
        }
    }

    @Override
    public void recordScreenshot(String s, byte[] bytes) {

    }

    @Override
    public void testSuiteStarted(Class<?> testClass) {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Test suite started for class: {}", testClass);
        // ⭐ 重置：允许新 suite 再次触发清理
        testSuiteFinishedLogged = false;
    }

    @Override
    public void testSuiteStarted(Story story) {
        LoggingConfigUtil.logDebugIfVerbose(
                logger, "Test suite started for story: {}", story.getStoryName());
        // ⭐ 重置：允许新 suite 再次触发清理
        testSuiteFinishedLogged = false;
    }

    @Override
    public void testSuiteFinished() {
        LoggingConfigUtil.logDebugIfVerbose(
                logger, "Test suite finished");

        // 使用原子操作确保只清理一次
        synchronized (PlaywrightListener.class) {
            if (testSuiteFinishedLogged) {
                return; // 已清理过，直接返回
            }
            testSuiteFinishedLogged = true;
        }
        
        // 清理逻辑
        LoggingConfigUtil.logInfoIfVerbose(logger, "Cleaning up all Playwright resources at test suite finish");
        
        try {
            PlaywrightManager.cleanupForFeature();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Cleaned up all resources at test suite finish");
        } catch (Exception e) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Failed to clean up resources at test suite finish: {}", e.getMessage());
        }
    }

    @Override
    public void testStarted(String testName, String testMethod) {
        String uniqueTestName = testName + "_" + Thread.currentThread().threadId();
        currentTestName.set(uniqueTestName);
        testStartTime.set(System.currentTimeMillis());
        totalTests.incrementAndGet();

        // ⭐⭐⭐ 新增：重置 API 监控上下文
        resetApiMonitorContext();

        try {
            FrameworkCore.getInstance().beforeTest();
            recordTestData("testStart", System.currentTimeMillis());
            recordTestData("testMethod", testMethod);
            logger.info("Test initialized: {} (method: {})", uniqueTestName, testMethod);
        } catch (Exception e) {
            logger.error("Failed to initialize Playwright for test: {}", uniqueTestName, e);
        }
    }

    @Override
    public void testStarted(String testName, String testMethod, ZonedDateTime startTime) {
        // 生成唯一名称：原名称 + 线程ID（保证同名 scenario 不合并）
        String uniqueTestName = testName + "_" + Thread.currentThread().threadId();
        currentTestName.set(uniqueTestName);
        testStartTime.set(startTime != null ? startTime.toInstant().toEpochMilli() : System.currentTimeMillis());
        totalTests.incrementAndGet();

        // ⭐⭐⭐ 新增：重置 API 监控上下文
        resetApiMonitorContext();

        try {
            FrameworkCore.getInstance().beforeTest();
            long start = startTime != null ? startTime.toInstant().toEpochMilli() : System.currentTimeMillis();
            recordTestData("testStart", start);
            recordTestData("testMethod", testMethod);
            recordTestData("startTimeZoned", startTime);
            logger.info("Test initialized: {} (method: {}, startTime: {})", uniqueTestName, testMethod, startTime);
        } catch (Exception e) {
            logger.error("Failed to initialize Playwright for test: {}", uniqueTestName, e);
        }
    }

    @Override
    public void testFinished(TestOutcome result) {
        try {
            logger.info("Test finished: {}", result);
            // ⭐⭐⭐ 新增：检查 API 断言失败并标记测试结果
            checkAndMarkApiAssertionFailures(result);
            // 更新当前测试结果
            if (result != null && result.getResult() != null) {
                currentTestResult.set(result.getResult());
            }
            testFinishedInternal();

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

            // 清理 BasePage 的 ThreadLocal 引用（防止线程复用时引用过期 Page 对象）
            BasePage.clearCurrentPage();

            // 检查是否需要重试失败的测试
            checkAndTriggerRerun();
        } catch (Exception e) {
            logger.error("Error in testFinished, forcing cleanup", e);
            // 确保异常时也清理
            cleanupThreadLocals();
            throw e;
        } finally {
            // ⭐⭐⭐ 新增：清理 API 监控上下文
            apiMonitorContext.remove();
        }
    }

    @Override
    public void testFinished(TestOutcome result, boolean isInDataDrivenTest, ZonedDateTime finishTime) {
        if (result == null) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "TestOutcome is null in testFinished with time, skipping processing");
            return;
        }

        try {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Test finished: {}, isDataDriven: {}, finishTime: {}", result, isInDataDrivenTest, finishTime);

            // ⭐⭐⭐ 新增：检查 API 断言失败并标记测试结果
            checkAndMarkApiAssertionFailures(result);

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
                LoggingConfigUtil.logWarnIfVerbose(logger, "Test result is null, counting as failed: {}", testName);
                failedTests.incrementAndGet();
            }

            LoggingConfigUtil.logInfoIfVerbose(logger, "Test completed: {} in {}ms (DataDriven: {}, Result: {})", testName, duration, isInDataDrivenTest, result);
        } finally {
            // 【关键】finally 保证：无论中间是否抛异常，ThreadLocal 一定会被清理
            cleanupThreadLocals();
            // ⭐⭐⭐ 新增：清理 API 监控上下文
            apiMonitorContext.remove();
        }
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
        // ⭐ 防重复 + 委托：统一交给无参 stepFailed(StepFailure) 处理截图和报告发送
        // 避免两个方法维护几乎相同逻辑导致的 drift 风险
        if (failure == null || failureScreenshotsAlreadySent.get()) return;

        if (timestamp != null) {
            recordTestData("stepFailureTimestamp", timestamp.toInstant().toEpochMilli());
        }

        // Serenity 要求的 takeScreenshotOnFailure 标志处理
        if (takeScreenshotOnFailure) {
            takeFailureScreenshot(null);
        }

        // 委托给核心失败处理逻辑（截图 + StepEventBus 发送 + 标记已发送）
        stepFailed(failure);
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

        // ⭐⭐⭐ 新增：检查 API 断言失败
        checkAndMarkApiAssertionFailures(result);

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
        // ⭐⭐⭐ 禁用 SCREEN_CHANGE 截图 — 报告变长的最大元凶
        // Serenity 报告是纵向堆叠所有截图，每次页面变化（弹窗、动画、滚动、hover等）
        // 都会触发此回调 → 每个步骤产生 3~10 张 SCREEN_CHANGE 截图 → 报告巨长不可读
        //
        // 如果未来需要重新启用（仅用于调试特定场景），取消下面的注释即可：
        // if (stepStartTime.get() != null && !stepFinishProcessed.get() && currentStepName.get() != null) {
        //     takeScreenshotAndRegister("SCREEN_CHANGE");
        // }
        LoggingConfigUtil.logDebugIfVerbose(logger, "SCREEN_CHANGE screenshot disabled (report length optimization)");
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

    // ═══════════════════════════════════════════════════════════════════
    // ⭐⭐⭐ API 监控断言集成
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 获取当前线程的 API 监控上下文（供 MonitorHandler 等外部组件调用）
     */
    public static ApiMonitorContext getCurrentApiMonitorContext() {
        return apiMonitorContext.get();
    }

    /**
     * 重置当前测试的 API 监控上下文
     */
    private void resetApiMonitorContext() {
        ApiMonitorContext context = apiMonitorContext.get();
        if (context != null) {
            context.reset();
        }
    }

    /**
     * ⭐⭐⭐ 核心方法：检查 API 断言失败并标记测试结果为失败
     *
     * <p>在测试结束时调用，等待所有异步 API 请求完成（最多 5 秒），
     * 如果 MonitorHandler 标记了断言失败，则强制设置 TestResult.FAILURE。
     */
    private void checkAndMarkApiAssertionFailures(TestOutcome result) {
        ApiMonitorContext context = apiMonitorContext.get();
        if (context == null) {
            return;
        }

        // 等待所有异步 API 请求完成（最多 5 秒，使用 wait/notify 避免忙等待）
        try {
            boolean completed = context.awaitCompletion(5_000);
            if (!completed) {
                logger.warn("Timed out waiting for API requests to complete ({} active)", context.getActiveRequests());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted while waiting for API requests to complete");
        }

        // 检查是否有 API 断言失败
        if (context.hasAssertionFailures()) {
            logger.error("API assertions failed for test: {}", currentTestName.get());

            // 生成详细失败报告
            String failureReport = context.buildFailureReport();
            logger.error("API assertion failure details:\n{}", failureReport);

            // 将测试结果标记为失败
            if (result != null) {
                result.setResult(TestResult.FAILURE);
            }

            // 记录失败信息
            recordTestData("apiAssertionFailure", "API assertions failed during test execution");
            recordTestData("apiAssertionFailureDetails", failureReport);

            // 记录到 Serenity 报告
            try {
                net.serenitybdd.core.Serenity.recordReportData()
                    .withTitle("API ASSERTION FAILURES DETECTED")
                    .andContents(failureReport);
            } catch (Exception e) {
                logger.debug("Failed to record API assertion failure to Serenity report", e);
            }
        }

        // 等待结束后清理上下文（确保下一个测试使用全新上下文）
        context.reset();
    }

    /**
     * ⭐⭐⭐ API 监控上下文 — 已独立为顶层类。
     *
     * @see ApiMonitorContext
     */
    // 原内部类已迁移至 com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiMonitorContext

}
