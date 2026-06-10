package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkCore;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.strategy.ScreenshotStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
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
import java.util.regex.Pattern;

public class PlaywrightListener implements StepListener {

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightListener.class);

    /**
     * 预编译 Pattern — 避免每次截图时重复编译正则表达式
     */
    private static final Pattern SANITIZE_FILENAME_PATTERN = Pattern.compile("[^a-zA-Z0-9_-]");
    private static final Pattern SANITIZE_NAME_PATTERN = Pattern.compile("[^a-zA-Z0-9]");

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

    // ⭐ 防止 stepFinishedInternal() 调用 StepEventBus.stepFinished() 导致递归重入
    private static final ThreadLocal<Boolean> stepFinishReentrantGuard = ThreadLocal.withInitial(() -> false);

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

    // ⭐ Rerun 检测：跟踪当前是第几次 run（0 = 首次运行，>=1 = rerun 轮次）
    private static volatile int currentRunNumber = 0;
    private static volatile boolean rerunStartedLogged = false;

    /**
     * ⭐ 阶段识别：Serenity CucumberWithSerenity 在 discovery 和 execution 两个阶段
     * 各触发一次完整的 testSuiteStarted → testStarted → testSuiteFinished 事件链。
     *
     * <p>Discovery 阶段仅扫描 feature 文件构建 Pickle 列表，不应触发任何
     * Playwright 资源初始化。本标记在首次 testSuiteStarted(Story) 检测到重复 story 时置为 true
     * （同一 story 在 discovery 和 execution 各触发一次，重复出现即进入执行阶段）。
     *
     * <p>在 testRunFinished 时重置，以支持同 JVM 内 rerun 场景。
     */
    private static volatile boolean discoveryPhaseCompleted = false;

    /**
     * ⭐ 阶段检测辅助集合：记录所有已见过的 Story 名称。
     * 当同一个 Story 在 testSuiteStarted(Story) 中被第二次看到时，
     * 说明 Serenity 已从 discovery 阶段进入 execution 阶段。
     */
    private final java.util.Set<String> seenStoryNames = new java.util.HashSet<>();

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

        // ⭐⭐⭐ 新增：重置 API 监控上下文
        ApiCaptureContext.resetCurrent();

        // ⭐⭐⭐ 阶段识别：discovery 阶段跳过 Playwright 资源初始化
        if (!discoveryPhaseCompleted) {
            // Discovery 阶段：只记录轻量级状态，不初始化 Playwright 资源
            // 避免重复创建 Playwright/Browser 实例导致浏览器多开、内存飙高
            LoggingConfigUtil.logDebugIfVerbose(logger,
                    "[Discovery Phase] Skipping Playwright init for test: {}", uniqueTestName);
            return;
        }

        // ⭐ 修复：补齐 FrameworkCore 初始化（与 testStarted(String,String) 保持一致）
        try {
            FrameworkCore.getInstance().beforeTest();
            recordTestData("testStart", System.currentTimeMillis());
            LoggingConfigUtil.logInfoIfVerbose(logger, "Test initialized: {}", uniqueTestName);
        } catch (Exception e) {
            logger.error("Failed to initialize Playwright for test: {}", uniqueTestName, e);
        }

        // 测试开始时截图（根据策略决定）
        if (screenshotStrategy == ScreenshotStrategy.BEFORE_AND_AFTER_EACH_STEP) {
            takeScreenshotAndRegister("TEST_START_" + uniqueTestName);
        }
    }

    private void testFinishedInternal() {
        Long startTime = testStartTime.get();
        if (startTime == null) {
            return;
        }

        // ⭐ 测试计数：在 testFinished 而非 testStarted 中计数
        // testStarted 受 discoveryPhaseCompleted 守卫影响，在 Serenity+Cucumber 场景下可能不计数
        // testFinished 是测试真正完成的确切时刻，计数更可靠
        totalTests.incrementAndGet();

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
        stepFinishReentrantGuard.set(false);

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
        // ⭐ 防递归重入：StepEventBus.stepFinished() 会重新触发事件分发
        if (stepFinishReentrantGuard.get()) {
            return;
        }
        stepFinishReentrantGuard.set(true);

        // ⭐ 防双重处理：如果参数化版 stepFinishedInternal 已经处理过，跳过
        if (stepFinishProcessed.get()) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "stepFinished() skipped - already processed by stepFinishedInternal");
            stepFinishReentrantGuard.set(false);
            return;
        }

        Long startTime = stepStartTime.get();
        if (startTime == null) {
            stepFinishReentrantGuard.set(false);
            return;
        }

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
                // ⭐ 防残留：传递新 list 副本，避免之后 clear() 影响 Serenity 保留的引用
                StepEventBus.getEventBus().stepFinished(new ArrayList<>(stepScreenshots), ZonedDateTime.now());
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
        stepFinishReentrantGuard.set(false);

        // ⭐⭐⭐ 框架级 API 断言检查（每个步骤结束时兜底执行）
        checkAndFailOnApiAssertions();
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

        // 截断为第一行，避免 buildDetailedErrorMessage 的多段落诊断输出污染日志
        String fullMsg = failure.getException().getMessage();
        String shortMsg = fullMsg;
        if (fullMsg != null) {
            int newlineIdx = fullMsg.indexOf('\n');
            if (newlineIdx > 0) {
                shortMsg = fullMsg.substring(0, newlineIdx).trim();
            }
            // 进一步限制长度，防止超长单行
            if (shortMsg.length() > 200) {
                shortMsg = shortMsg.substring(0, 197) + "...";
            }
        }
        logger.error("Step failure detected: {}", shortMsg);
        recordTestData("stepFailure", fullMsg);
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
                // ⭐ 防残留：传递新 list 副本
                StepEventBus.getEventBus().stepFinished(new ArrayList<>(stepScreenshots), ZonedDateTime.now());
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
                // ⭐ 防残留：传递新 list 副本
                StepEventBus.getEventBus().stepFinished(new ArrayList<>(stepScreenshots), ZonedDateTime.now());
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
     * Sanitize filename by replacing invalid characters.
     * 只替换非 a-zA-Z0-9_- 字符，保留下划线和连字符（用于文件名）
     */
    private static String sanitizeFilename(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "unnamed";
        }
        return SANITIZE_FILENAME_PATTERN.matcher(name).replaceAll("_");
    }

    /**
     * Sanitize name for general use — 替换所有非 a-zA-Z0-9 字符（用于 display name）
     */
    private static String sanitizeName(String name) {
        return name == null ? "unnamed" : SANITIZE_NAME_PATTERN.matcher(name).replaceAll("_");
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
    /**
     * ⭐ 自动清理当前线程的 RouteRegistry 条目（防内存泄漏 + 跨用例路由污染）。
     *
     * <p>在 testFinished 中调用，从 PlaywrightManager 获取当前线程的 Page / Context，
     * 清理 RouteRegistry 中对应的 pattern 记录，释放 RouteEngine 防重门控集合。
     *
     * <p>异常安全：PlaywrightManager.getPage()/getContext() 在某些异常路径下可能抛异常，
     * 逐个 try-catch 保证一个失败不影响另一个。
     */
    private void cleanupRouteRegistryForCurrentThread() {
        try {
            Object page = PlaywrightManager.getPage();
            if (page != null) {
                RouteRegistry.clearContext(page);
            }
        } catch (Exception e) {
            logger.debug("RouteRegistry cleanup for Page skipped: {}", e.getMessage());
        }
        try {
            Object context = PlaywrightManager.getContext();
            if (context != null) {
                RouteRegistry.clearContext(context);
            }
        } catch (Exception e) {
            logger.debug("RouteRegistry cleanup for BrowserContext skipped: {}", e.getMessage());
        }
        // RouteEngine.clearDispatchedRoutes() 已由 RouteRegistry.clearContext() 内部调用，无需重复
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
        stepFinishReentrantGuard.remove();  // ⭐ 清理重入防护标志
        // currentStepScreenshots 已由 clearStepScreenshotsImmediately() 处理

        // ⭐⭐⭐ 新增：清理 API 捕获上下文
        ApiCaptureContext.removeCurrent();
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
        // ⭐ 防递归重入：StepEventBus.stepFinished() 会触发事件重新分发到本监听器，
        // 导致 stepFinishedInternal() → StepEventBus.stepFinished() → stepFinishedInternal() → ... 无限递归
        if (stepFinishReentrantGuard.get()) {
            return;
        }
        stepFinishReentrantGuard.set(true);

        // ⭐ 防双重处理：如果无参版 stepFinished() 已经处理过，跳过截图和发送
        boolean alreadyProcessed = stepFinishProcessed.get();

        Long startTime = stepStartTime.get();
        if (startTime == null) {
            // 即使没有 startTime 也要清理标志
            stepFinishProcessed.remove();
            stepFinishReentrantGuard.set(false);
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
        
        // ⭐ 防残留（修复）：不再直接 addAll 到 Serenity 的 list（避免跨步骤共享 list 导致累积）。
        // 改为创建全新的 list，杜绝对 Serenity 内部数据结构的污染。
        List<ScreenshotAndHtmlSource> mergedScreenshots = null;
        if (stepScreenshots != null && !stepScreenshots.isEmpty()) {
            LoggingConfigUtil.logDebugIfVerbose(logger,
                    "Before merge: stepScreenshots.size={}, Serenity screenshots.size={}",
                    stepScreenshots.size(), screenshots != null ? screenshots.size() : 0);

            // 创建全新 list，合并 Serenity 传入的 + 当前步骤产生的截图
            int totalSize = stepScreenshots.size() + (screenshots != null ? screenshots.size() : 0);
            mergedScreenshots = new ArrayList<>(totalSize);
            if (screenshots != null && !screenshots.isEmpty()) {
                mergedScreenshots.addAll(screenshots);  // 先加 Serenity 的
            }
            mergedScreenshots.addAll(stepScreenshots);  // 再加当前步骤的
            
            LoggingConfigUtil.logDebugIfVerbose(
                    logger, "After merge: new mergedScreenshots.size={}", mergedScreenshots.size());
            
            // 清空当前步骤的截图列表，避免影响下一个步骤（防残留核心）
            stepScreenshots.clear();
            LoggingConfigUtil.logDebugIfVerbose(
                    logger, "Cleared stepScreenshots after merging");
        } else if (screenshots != null && !screenshots.isEmpty()) {
            // 当前步骤没有截图，但 Serenity 有 → 也用新 list 封装（避免直接传递 Serenity 的可变 list）
            mergedScreenshots = new ArrayList<>(screenshots);
        } else {
            LoggingConfigUtil.logDebugIfVerbose(
                    logger, "No step screenshots to merge (list is empty or null)");
        }

        // ⭐ 防双重发送：仅当无参版 stepFinished() 未处理时才发送 StepEventBus
        if (!alreadyProcessed && mergedScreenshots != null && !mergedScreenshots.isEmpty() && !failureScreenshotsAlreadySent.get()) {
            recordTestData("stepScreenshotsCount", mergedScreenshots.size());
            // 手动调用 StepEventBus 的 stepFinished 方法来传递截图
            LoggingConfigUtil.logDebugIfVerbose(
                    logger, "Manually calling StepEventBus.stepFinished() with {} screenshots", mergedScreenshots.size());
            try {
                StepEventBus.getEventBus().stepFinished(mergedScreenshots, ZonedDateTime.now());
                LoggingConfigUtil.logDebugIfVerbose(
                        logger, "Successfully called StepEventBus.stepFinished() with screenshots");
                // 标记已处理，防止无参版 stepFinished() 重复处理
                stepFinishProcessed.set(true);
            } catch (Exception e) {
                logger.error("Failed to call StepEventBus.stepFinished() with screenshots", e);
            }
        } else if (failureScreenshotsAlreadySent.get()) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Skipping StepEventBus.stepFinished() in stepFinishedInternal - already sent by stepFailed");
            // ⭐ 防残留：清空 Serenity 传入的 list
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
        stepFinishReentrantGuard.set(false);

        // ⭐⭐⭐ 框架级 API 断言检查（每个 Cucumber 步骤结束时自动执行）
        checkAndFailOnApiAssertions();

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

        // ⭐ 阶段检测：当同一个 Story 在 discovery 阶段被注册过，再次出现时说明进入 execution
        String storyName = story.getStoryName();
        if (!discoveryPhaseCompleted && !seenStoryNames.add(storyName)) {
            // 重复的 story → 已进入 execution phase
            discoveryPhaseCompleted = true;
            LoggingConfigUtil.logInfoIfVerbose(logger,
                    "Execution phase detected (duplicate story: {}), enabling Playwright init and cross-feature cleanup", storyName);
        }

        // ⭐ 跨 Feature Context 清理：新 Story 启动时关闭上一个 Story 的 Context
        // 仅在 execution 阶段执行清理，避免 discovery 阶段误关 Context
        if (discoveryPhaseCompleted) {
            LoggingConfigUtil.logInfoIfVerbose(logger,
                    "New story starting: {} — closing previous story's Context (cross-feature cleanup)", story.getStoryName());
            try {
                PlaywrightManager.cleanupForFeature();
            } catch (Exception e) {
                logger.warn("Failed to cleanup context at feature boundary for new story: {} — {}",
                        story.getStoryName(), e.getMessage());
            }
        }

        // ⭐ 重置：允许新 suite 再次触发清理
        testSuiteFinishedLogged = false;

        // ⭐ Rerun 日志：testRunFinished() 之后首次 testSuiteStarted 即为 rerun
        if (currentRunNumber > 0 && !rerunStartedLogged) {
            rerunStartedLogged = true;
            logger.info("==========================================================================");
            logger.info("  RERUN STARTING — Round {} (Maven Failsafe rerunFailingTestsCount)", currentRunNumber);
            logger.info("==========================================================================");
        }
    }

    @Override
    public void testSuiteFinished() {
        // ⭐⭐⭐ 阶段识别：首次 testSuiteFinished 标志 discovery 阶段结束
        discoveryPhaseCompleted = true;

        logger.info("Test suite finished");

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
        // ⭐⭐⭐ 阶段识别：discovery 阶段跳过 Playwright 资源初始化
        if (!discoveryPhaseCompleted) {
            LoggingConfigUtil.logDebugIfVerbose(logger,
                    "[Discovery Phase] Skipping Playwright init for test: {} (method: {})", testName, testMethod);
            return;
        }

        String uniqueTestName = testName + "_" + Thread.currentThread().threadId();
        currentTestName.set(uniqueTestName);
        testStartTime.set(System.currentTimeMillis());

        // ⭐⭐⭐ 新增：重置 API 捕获上下文
        ApiCaptureContext.resetCurrent();

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
        // ⭐⭐⭐ 阶段识别：discovery 阶段跳过 Playwright 资源初始化
        if (!discoveryPhaseCompleted) {
            LoggingConfigUtil.logDebugIfVerbose(logger,
                    "[Discovery Phase] Skipping Playwright init for test: {} (method: {}, time: {})",
                    testName, testMethod, startTime);
            return;
        }

        // 生成唯一名称：原名称 + 线程ID（保证同名 scenario 不合并）
        String uniqueTestName = testName + "_" + Thread.currentThread().threadId();
        currentTestName.set(uniqueTestName);
        testStartTime.set(startTime != null ? startTime.toInstant().toEpochMilli() : System.currentTimeMillis());

        // ⭐⭐⭐ 新增：重置 API 捕获上下文
        ApiCaptureContext.resetCurrent();

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

            // ⭐ 新增：自动清理当前线程的 RouteRegistry（防内存泄漏 + 跨用例污染）
            cleanupRouteRegistryForCurrentThread();

            // 获取浏览器重启策略
            String restartBrowserForEach = SystemEnvironmentVariables.currentEnvironmentVariables()
                    .getProperty("serenity.restart.browser.for.each", "scenario");

            // 根据浏览器重启策略决定清理方式
            // 统一调用 cleanupForScenario()：内部已按 restartStrategy 分支处理
            //  - Scenario 模式：关闭 Context/Page + 重置所有配置
            //  - Feature 模式：resetCustomContextOptionsForFeatureMode() + cleanupPageState()，保留 Context/Page
            // Feature 模式不能只调 cleanupPageState()，否则 customContextOptionsFlag 泄漏
            // 会导致下一个 scenario 的 getContext() 误触发 Context 重建，破坏 Feature 模式语义
            if ("scenario".equalsIgnoreCase(restartBrowserForEach)) {
                logger.info("Cleaning up Playwright resources (scenario-level restart)");
            } else {
                logger.debug("Feature mode - resetting custom options and cleaning page state while keeping Context/Page");
            }
            PlaywrightManager.cleanupForScenario();

            // 清理 BasePage 的 ThreadLocal 引用（防止线程复用时引用过期 Page 对象）
            BasePage.clearCurrentPage();
        } catch (Exception e) {
            logger.error("Error in testFinished, forcing cleanup", e);
            RouteEngine.clearDispatchedRoutes();
            throw e;
        } finally {
            // 确保异常和正常路径均清理 ThreadLocal 和 API 捕获上下文
            cleanupThreadLocals();
            ApiCaptureContext.removeCurrent();
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

            // ⭐ 测试计数：在 testFinished(TestOutcome,boolean,ZonedDateTime) 中计数
            // Serenity+Cucumber 实际调用这个重载而非 testFinished(TestOutcome)
            totalTests.incrementAndGet();

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
        } catch (Exception e) {
            logger.error("Error in testFinished with time, forcing cleanup", e);
            RouteEngine.clearDispatchedRoutes();
            throw e;
        } finally {
            // 【关键】finally 保证：无论中间是否抛异常，ThreadLocal 一定会被清理
            cleanupThreadLocals();
            // ⭐ 新增：自动清理当前线程的 RouteRegistry（防内存泄漏 + 跨用例污染）
            cleanupRouteRegistryForCurrentThread();

            // ⭐ 修复：补齐 Playwright 资源清理（与 testFinished(TestOutcome) 保持一致）
            // 统一调用 cleanupForScenario()：内部已按 restartStrategy 分支处理
            // Feature 模式不能只调 cleanupPageState()，否则 customContextOptionsFlag 泄漏
            try {
                PlaywrightManager.cleanupForScenario();
                BasePage.clearCurrentPage();
            } catch (Exception e) {
                logger.error("Failed to clean up Playwright resources after test: {}", e.getMessage());
            }
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
        // 简洁输出：仅显示测试名 + 异常消息第一行，不打印完整堆栈（由 Serenity 报告保留）
        String testTitle = result != null ? result.getTitle() : "unknown";
        String errorMsg = throwable != null ? throwable.getMessage() : "Unknown error";
        if (errorMsg != null && errorMsg.contains("\n")) {
            errorMsg = errorMsg.substring(0, errorMsg.indexOf('\n')).trim();
        }
        logger.error("Test failed: {} - {}", testTitle, errorMsg);

        // ⭐⭐⭐ 新增：检查 API 断言失败
        checkAndMarkApiAssertionFailures(result);

        // 注意：不在 testFailed 中 increment failedTests，避免与 testFinished 重复计数
        // 测试失败统计由 testFinished 统一处理
        // 重试能力由 Maven Failsafe Plugin 的 rerunFailingTestsCount 提供
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
        // ⭐⭐⭐ 阶段识别：重置 discovery 标记和 story 集合，支持同 JVM rerun 时识别新 discovery 阶段
        discoveryPhaseCompleted = false;
        seenStoryNames.clear();

        logger.info(getPerformanceStats());

        // ⭐ 标记当前 run 结束，为下一次 rerun 做准备
        currentRunNumber++;
        rerunStartedLogged = false;
    }



    // ═══════════════════════════════════════════════════════════════════
    // 原 checkAndTriggerRerun() / triggerRerunInSameProcess() 已删除。
    // 重试能力由 Maven Failsafe Plugin 的 rerunFailingTestsCount 提供。
    // ═══════════════════════════════════════════════════════════════════

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
     * ⭐⭐⭐ 核心方法：检查 API 断言失败并标记测试结果为失败
     *
     * <p>在测试结束时调用，等待所有异步 API 请求完成（最多 5 秒），
     * 如果 MonitorHandler 标记了断言失败，则强制设置 TestResult.FAILURE。
     */
    private void checkAndMarkApiAssertionFailures(TestOutcome result) {
        ApiCaptureContext context = ApiCaptureContext.getCurrent();
        if (context == null) {
            return;
        }

        // 等待所有异步 API 请求完成（通过系统属性配置超时，默认 15 秒，避免 CI 环境超时）
        long timeoutMs = Long.parseLong(
                System.getProperty("api.assertion.wait.timeout.ms", "15000"));
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
        // 不在此处 reset()，由 cleanupThreadLocals() 统一调用 ApiCaptureContext.removeCurrent()
    }

    // ═══════════════════════════════════════════════════════════════════
    // ⭐⭐⭐ 框架级 API 断言自动检查
    // ═══════════════════════════════════════════════════════════════════

    /**
     * ⭐⭐⭐ 框架级：在每个步骤结束时自动检查 API 断言是否失败。
     *
     * <p><b>单层检查（兜底路径）</b>：
     * 检查 {@link ApiCaptureContext#hasAssertionFailures()}。
     * 如果 MonitorHandler 标记了断言失败，则抛出 {@code AssertionError}，
     * 由 Serenity 捕获并标记当前 Step 为失败。
     *
     * <p>⭐ 不再使用 {@code Thread.interrupted()} fail-fast 机制：
     * {@code signalFailFast()} 虽然仍会中断主线程，但此处不再消费中断标记，
     * 避免中断导致后续 Playwright IO 操作（如 {@code page.waitForSelector}）
     * 抛出异常，从而保证后续 Scenario 正常执行。
     *
     * <p>Step 代码无需手动检查 — 框架自动处理。
     *
     * <p>性能：若无活跃请求且无断言失败，方法立即返回（零开销）。
     */
    private void checkAndFailOnApiAssertions() {
        ApiCaptureContext context = ApiCaptureContext.getCurrent();

        // ═══ 兜底检查：通过上下文标记检测 ═══
        if (!context.hasAssertionFailures()) {
            return;  // 无断言失败 → 零开销返回
        }

        // 等待异步存储任务完成（避免报告不完整）
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

        // 断言失败 → 直接抛出 AssertionError，由 Serenity 捕获并标记 Step 为失败
        String report = context.buildFailureReport();
        logger.error("API assertions failed during step:\n{}", report);
        throw new AssertionError("API assertion failures detected:\n" + report);
    }

    /**
     * ⭐⭐⭐ API 捕获上下文 — 已独立为顶层类。
     *
     * @see ApiCaptureContext
     */
    // 原内部类已迁移至 com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext

}
