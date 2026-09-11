package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;


import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkCore;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.SerenityReporter;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.strategy.ScreenshotStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.common.logging.LogContext;
import com.hsbc.cmb.hk.dbb.automation.framework.common.assertion.SoftAssertions;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.SerenityResultAdapter;
import com.hsbc.cmb.hk.dbb.automation.framework.common.result.ResultReporters;
import com.hsbc.cmb.hk.dbb.automation.framework.common.result.StepResult;
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
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

import java.io.ByteArrayOutputStream;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class PlaywrightListener implements StepListener {

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightListener.class);

    private final ScreenshotStrategy screenshotStrategy;

    // 用于跟踪当前测试结果，用于 FOR_FAILURES 等略
    private final ThreadLocal<TestResult> currentTestResult = new ThreadLocal<>();

    //  T3-1 收拢：4 个 static ThreadLocal 迁入 TestContext（原均为默认 null 语义，迁移后等价）
    private static final ContextKey<Long> TEST_START_TIME_KEY = ContextKey.of("playwrightListener.testStartTime", Long.class);
    private static final ContextKey<Long> STEP_START_TIME_KEY = ContextKey.of("playwrightListener.stepStartTime", Long.class);
    private static final ContextKey<String> CURRENT_TEST_NAME_KEY = ContextKey.of("playwrightListener.currentTestName", String.class);
    private static final ContextKey<String> CURRENT_STEP_NAME_KEY = ContextKey.of("playwrightListener.currentStepName", String.class);

    // 记录当前 Cucumber 级别步骤，避免为 Serenity 子步骤重复截图（ T3-1 收拢：迁入 TestContext）
    private static final ContextKey<String> CURRENT_CUCUMBER_STEP_KEY =
            ContextKey.of("playwrightListener.currentCucumberStep", String.class);


    /**
     *  失败传播机制说明（重要）。
     *
     * <p>Serenity listener 回调（stepFinished / testFinished）中检测到的任何失败
     * （API 断言、元素找不到、超时等）都必须通过 <b>抛出异常</b> 的方式传播，
     * 因为 {@code StepEventBus.testFailed()} 只影响 Serenity 报告模型，无法改变 Cucumber/JUnit4 判定。
     *
     * <p>关键事实：本框架的 Serenity 集成 <b>不会</b>为 {@code cucumber.properties} 注入的 glue 包
     * 注册 Cucumber {@code @After} hook（类会被加载但不注册 hook），因此不能在测试层用 @After 抛异常。
     * 正确做法是在已 SPI 注册的 Serenity {@code StepListener} 的 {@code stepFinished()} 回调中
     * 直接 {@code throw}，异常沿 {@code StepInterceptor → Cucumber → JUnit4} 传播，使 IDE 正确标红。
     *
     * <p>详见 {@link #checkAndFailOnApiAssertions()} 中的 {@code throw} 实现。
     */

    // 存储当前步骤的截图列表
    // （ T3-1 收拢：withInitial(ArrayList::new) 的惰性语义由 computeIfAbsent 等价保证）
    private static final ContextKey<List> CURRENT_STEP_SCREENSHOTS_KEY =
            ContextKey.of("playwrightListener.currentStepScreenshots", List.class);

    /** 取本线程的步骤截图列表（等价原 currentStepScreenshots()，惰性创建）。 */
    @SuppressWarnings("unchecked")
    private static List<ScreenshotAndHtmlSource> currentStepScreenshots() {
        return (List<ScreenshotAndHtmlSource>) TestContextHolder.get()
                .computeIfAbsent(CURRENT_STEP_SCREENSHOTS_KEY, ArrayList::new);
    }



    // 用于防止testSuiteFinished被多次调用时重复输出日志
    private static volatile boolean testSuiteFinishedLogged = false;

    //  Rerun 检测：跟踪当前是第几次 run（0 = 首次运行，>=1 = rerun 轮次）
    private static volatile int currentRunNumber = 0;
    private static volatile boolean rerunStartedLogged = false;

    /**
     *  阶段识别：Serenity CucumberWithSerenity 在 discovery 和 execution 两个阶段
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
     *  阶段检测辅助集合：记录所有已见过的 Story 名称。
     * 当同一个 Story 在 testSuiteStarted(Story) 中被第二次看到时，
     * 说明 Serenity 已从 discovery 阶段进入 execution 阶段。
     */
    private final Set<String> seenStoryNames = new HashSet<>();

    /** scenario 标识单调序号：保证同名 scenario（如 Scenario Outline 各示例行）也拿到唯一 id，避免按用例串联时串扰。 */
    private static final java.util.concurrent.atomic.AtomicLong SCENARIO_SEQ = new java.util.concurrent.atomic.AtomicLong();

    public PlaywrightListener() {
        // 从环境变量中读取截图策略配置
        EnvironmentVariables environmentVariables = SystemEnvironmentVariables.currentEnvironmentVariables();
        this.screenshotStrategy = ScreenshotStrategy.from(environmentVariables);

        VerboseLogging.logInfoIfVerbose(logger, "Screenshot strategy initialized: {}", screenshotStrategy);
    }

    // ── D4-1：FrameworkListener 桥接所需的 per-thread 状态 ──
    //  TestContextHolder 会在 testFinished 的 cleanupThreadLocals() 中被清空，
    //  而 afterScenario 需在那之后仍能拿到场景名，故此处独立持有并在收尾 remove()。
    private static final ThreadLocal<String> CURRENT_SCENARIO_NAME = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_STEP_TITLE = new ThreadLocal<>();
    private static final ThreadLocal<java.util.concurrent.atomic.AtomicBoolean> AFTER_STEP_FIRED =
            ThreadLocal.withInitial(java.util.concurrent.atomic.AtomicBoolean::new);
    /** D4-2：本步骤是否发生过失败（用于产出步骤级结果）。 */
    private static final ThreadLocal<java.util.concurrent.atomic.AtomicBoolean> STEP_FAILED =
            ThreadLocal.withInitial(java.util.concurrent.atomic.AtomicBoolean::new);

    @Override
    public void testStarted(String testName) {
        // 生成唯一名称：原名称 + 线程ID + 单调序号（保证同名 scenario 不合并、不碰撞）。
        // 用序号而非 System.currentTimeMillis()：毫秒分辨率下，同一线程、同毫秒内启动的多个同名
        // scenario（如 Scenario Outline 各示例行）会撞出相同 id，导致按用例串联时串扰。
        String uniqueTestName = testName + "_" + Thread.currentThread().threadId() + "_" + SCENARIO_SEQ.incrementAndGet();
        TestContextHolder.get().set(CURRENT_TEST_NAME_KEY,uniqueTestName);  //  修复：确保 currentTestName 被设置（与双参数版本一致）
        //  D3-1：把 scenario 标识写入 MDC，使控制台 / 落盘日志可按用例串联（并行执行排障关键）
        LogContext.beginScenario(uniqueTestName);
        //  D4-1：桥接业务监听器（业务只实现 FrameworkListener，不接触 Serenity 事件）
        CURRENT_SCENARIO_NAME.set(uniqueTestName);
        AFTER_STEP_FIRED.get().set(false);
        FrameworkListenerBridge.beforeScenario(uniqueTestName);
        TestContextHolder.get().set(TEST_START_TIME_KEY,System.currentTimeMillis());
        currentTestResult.set(TestResult.PENDING); // 初始化为PENDING，避免默认为SUCCESS导致统计错误

        //  新增：重置 API 监控上下文（route 未启用时整体跳过）
        withRouteLifecycle(lc -> {
            lc.resetCaptureCurrent();
            //  绑定当前 scenario 名：让 API 监控失败记录能归属到具体场景
            //   （MonitorFailureCollector 按指纹去重合并，同时累计触发该失败的场景列表）
            lc.setMonitorScenario(testName);
        });
        //  丢弃上一场景残留的待报告 API 记录，避免其被写入本场景报告（跨场景串扰）
        SerenityReporter.discardPendingApiOperations();
        //  重置 API 失败标记（每个新 case 重新开始追踪）
        ListenerGuard.guards().setApiFailureAlreadyHandled(false);

        //  安全清理：确保上一个 scenario 的采集引擎已释放
        withRouteLifecycle(RouteLifecycle::stopCapture);

        //  阶段识别：discovery 阶段跳过 Playwright 资源初始化
        if (!discoveryPhaseCompleted) {
            // Discovery 阶段：只记录轻量级状态，不初始化 Playwright 资源
            // 避免重复创建 Playwright/Browser 实例导致浏览器多开、内存飙高
            VerboseLogging.logDebugIfVerbose(logger,
                    "[Discovery Phase] Skipping Playwright init for test: {}", uniqueTestName);
            return;
        }

        //  修复：补齐 FrameworkCore 初始化（与 testStarted(String,String) 保持一致）
        try {
            FrameworkCore.getInstance().beforeTest();
            recordTestData("testStart", System.currentTimeMillis());
            VerboseLogging.logInfoIfVerbose(logger, "Test initialized: {}", uniqueTestName);
        } catch (Exception e) {
            logger.error("Failed to initialize Playwright for test: {}", uniqueTestName, e);
        }

        // 测试开始时截图（根据策略决定）
        if (screenshotStrategy == ScreenshotStrategy.BEFORE_AND_AFTER_EACH_STEP) {
            takeScreenshotAndRegister("TEST_START_" + uniqueTestName);
        }
    }

    private void testFinishedInternal() {
        Long startTime = TestContextHolder.get().get(TEST_START_TIME_KEY);
        if (startTime == null) {
            return;
        }

        //  测试计数：在 testFinished 而非 testStarted 中计数
        // testStarted 受 discoveryPhaseCompleted 守卫影响，在 Serenity+Cucumber 场景下可能不计数
        // testFinished 是测试真正完成的确切时刻，计数更可靠
        ListenerPerfStats.markTotal();

        // 【关键】使用 try-finally 确保 ThreadLocal 始终被清理，即使中间抛出异常
        try {
            long duration = System.currentTimeMillis() - startTime;
            String testName = TestContextHolder.get().get(CURRENT_TEST_NAME_KEY);

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
                ListenerPerfStats.markPassed();
            } else if (result == TestResult.PENDING) {
                // PENDING表示测试结果未知，可能是测试中途失败或超时
                // 将其计为失败，以确保准确统计
                logger.warn("Test result is PENDING, counting as failed: {}", testName);
                ListenerPerfStats.markFailed();
            } else if (result == TestResult.FAILURE || result == TestResult.ERROR) {
                ListenerPerfStats.markFailed();
            } else if (result == TestResult.SKIPPED) {
                ListenerPerfStats.markSkipped();
            }
        } else {
            // result为null，说明测试没有正常完成，计为失败
            logger.warn("Test result is null, counting as failed: {}", testName);
            ListenerPerfStats.markFailed();
        }

        VerboseLogging.logInfoIfVerbose(logger, "Test completed: {} in {}ms (Result: {})", testName, duration, result);
        } finally {
            //  安全清理：scenario 结束时立即停止采集引擎，而非等到下一个 scenario 开始
            //    避免 scenario 被中断（断言失败/超时）后引擎仍在运行
            try { withRouteLifecycle(RouteLifecycle::stopCapture); } catch (Exception e) {
                // 清理阶段兜底：stopCapture 失败不应阻断后续 ThreadLocal 清理（D7-3：不得静默）
                logger.debug("[PlaywrightListener] stopCapture failed during cleanup: {}", e.toString());
            }

            // 【关键】finally 保证：无论中间是否抛异常，ThreadLocal 一定会被清理
            cleanupThreadLocals();
        }
    }

    @Override
    public void testSkipped() {
        ListenerPerfStats.markSkipped();
        VerboseLogging.logInfoIfVerbose(logger, "Test skipped: {}", TestContextHolder.get().get(CURRENT_TEST_NAME_KEY));
        recordTestData("testSkipped", true);
        cleanupAfterAbnormalTermination("testSkipped");
    }

    /**
     * 异常/跳过路径的幂等清理。Serenity 不保证 skipped、ignored 或 step error
     * 一定随后触发完整的 testFinished，因此这些入口必须主动释放路由、采集和线程状态。
     */
    private void cleanupAfterAbnormalTermination(String reason) {
        //  异常终止路径：scenario 已被中断（断言失败/超时/跳过），
        //    此时做全局全量复位（resetAll 内部已异常隔离），确保路由/采集状态不残留到下个 case。
        //    resetAll 包含：停止采集引擎 + 清空 RouteRegistry 全量 + 兜底清空防重门控。
        try {
            withRouteLifecycle(RouteLifecycle::resetAll);
        } catch (Exception e) {
            logger.debug("RouteLifecycleRegistry.get().resetAll() on abnormal termination ({}) failed: {}", reason, e.getMessage());
        }
        cleanupThreadLocals();
    }

    /**
     *  核心修复：强制清空当前步骤截图列表（彻底杜绝残留）
     * 
     * 问题根因：ThreadLocal.remove() 只移除引用，不清除列表内容。
     * 如果外部代码在 remove 前拿到了列表引用，remove 后旧数据仍可通过该引用访问，
     * 导致 A 步骤的截图被 B 步骤拿到 → 报告错乱。
     * 
     * 正确做法：先清空内容 + 再移除引用，双保险。
     */
    private void clearStepScreenshotsImmediately() {
        try {
            List<ScreenshotAndHtmlSource> screenshots = currentStepScreenshots();
            if (screenshots != null && !screenshots.isEmpty()) {
                logger.debug("Force clearing {} leftover screenshot(s)", screenshots.size());
                screenshots.clear(); // 先清空内容（断开所有元素引用）
            }
        } catch (Exception e) {
            logger.debug("Error while clearing step screenshots", e);
        } finally {
            TestContextHolder.get().remove(CURRENT_STEP_SCREENSHOTS_KEY); // 再移除 ThreadLocal 引用
        }
    }

    @Override
    public void stepStarted(ExecutedStepDescription step) {
        if (step == null) return;

        //  第一步（最关键）：强制清空上一步骤残留的所有截图，根治脏数据
        clearStepScreenshotsImmediately();

        // 重置所有防双重处理标志
        ListenerGuard.guards().setStepFinishProcessed(false);
        ListenerGuard.guards().setFailureScreenshotsAlreadySent(false);
        ListenerGuard.guards().setStepFinishReentrant(false);

        // 用全新 ArrayList 替换旧列表（彻底断开任何外部引用）
        currentStepScreenshots().clear();

        TestContextHolder.get().set(STEP_START_TIME_KEY,System.currentTimeMillis());
        TestContextHolder.get().set(CURRENT_STEP_NAME_KEY,step.getTitle());
        VerboseLogging.logDebugIfVerbose(logger, "Step started: {}", step.getTitle());

        //  R4: 标记步骤起始时间戳，使 waitForApi/getLastApiCall 等查询
        // 只匹配本步骤内的 API 调用，隔离同一 Scenario 内跨 Step 的串扰。
        try {
            withRouteLifecycle(lc -> lc.getCurrentCapture().markStepStart());
        } catch (Exception e) {
            logger.warn("[PlaywrightListener] markStepStart failed: {}", e.getMessage());
        }

        // 标记当前步骤为 Cucumber 步骤
        TestContextHolder.get().set(CURRENT_CUCUMBER_STEP_KEY, step.getTitle());

        // BEFORE_AND_AFTER_EACH_STEP 策略：在 Cucumber 步骤开始时截图
        if (screenshotStrategy == ScreenshotStrategy.BEFORE_AND_AFTER_EACH_STEP) {
            takeScreenshotAndRegister("STEP_BEFORE_" + step.getTitle());
        }

        //  D4-1：桥接业务监听器的步骤开始回调
        CURRENT_STEP_TITLE.set(step.getTitle());
        AFTER_STEP_FIRED.get().set(false);
        FrameworkListenerBridge.beforeStep(step.getTitle());

        recordTestData("stepStart_" + step.getTitle(), System.currentTimeMillis());
    }

    /**
     * D4-1：触发 {@code afterStep} 回调，<b>每个步骤只触发一次</b>。
     * <p>Serenity 有多个 {@code stepFinished} 重载，同一事件可能落到不同分支，
     * 故用 per-thread 标志幂等，避免业务监听器收到重复通知。
     */
    private static void fireAfterStep() {
        if (!AFTER_STEP_FIRED.get().compareAndSet(false, true)) {
            return;
        }
        FrameworkListenerBridge.afterStep(CURRENT_STEP_TITLE.get());

        //  D4-2：产出步骤级结果（模型为框架自有类型，与报告引擎解耦）
        Long stepStart = TestContextHolder.get().get(STEP_START_TIME_KEY);
        boolean failed = STEP_FAILED.get().getAndSet(false);
        ResultReporters.reportStep(new StepResult(
                CURRENT_STEP_TITLE.get(),
                failed
                        ? com.hsbc.cmb.hk.dbb.automation.framework.common.result.TestResult.FAILURE
                        : com.hsbc.cmb.hk.dbb.automation.framework.common.result.TestResult.SUCCESS,
                stepStart == null ? 0L : stepStart,
                stepStart == null ? 0L : System.currentTimeMillis() - stepStart,
                null));
    }

    /**
     * D4-1：触发 {@code afterScenario} 并清理桥接 per-thread 状态。
     * <p>必须放在 {@code testFinished} 的 finally —— 无论正常 / 异常收尾都要通知业务监听器，
     * 且线程池复用前必须 remove，杜绝跨用例串扰。
     */
    private static void fireAfterScenario(boolean failed) {
        String scenarioName = CURRENT_SCENARIO_NAME.get();
        try {
            FrameworkListenerBridge.afterScenario(scenarioName, failed);
        } finally {
            CURRENT_SCENARIO_NAME.remove();
            CURRENT_STEP_TITLE.remove();
            AFTER_STEP_FIRED.remove();
        }
    }

    @Override
    public void stepFinished() {
        //  防递归重入：StepEventBus.stepFinished() 会重新触发事件分发
        if (ListenerGuard.guards().isStepFinishReentrant()) {
            return;
        }
        fireAfterStep();
        ListenerGuard.guards().setStepFinishReentrant(true);

        //  防双重处理：如果参数化版 stepFinishedInternal 已经处理过，跳过
        if (ListenerGuard.guards().isStepFinishProcessed()) {
            VerboseLogging.logDebugIfVerbose(logger, "stepFinished() skipped - already processed by stepFinishedInternal");
            ListenerGuard.guards().setStepFinishReentrant(false);
            return;
        }

        Long startTime = TestContextHolder.get().get(STEP_START_TIME_KEY);
        if (startTime == null) {
            ListenerGuard.guards().setStepFinishReentrant(false);
            return;
        }

        long duration = System.currentTimeMillis() - startTime;
        recordTestData("stepDuration", duration);
        VerboseLogging.logDebugIfVerbose(logger, "Step completed in {}ms", duration);

        // 只为 Cucumber 级别步骤截图
        String stepName = TestContextHolder.get().get(CURRENT_STEP_NAME_KEY);
        String cucumberStep = TestContextHolder.get().get(CURRENT_CUCUMBER_STEP_KEY);

        if (stepName != null && !stepName.isEmpty() && stepName.equals(cucumberStep)) {
            if (screenshotStrategy == ScreenshotStrategy.AFTER_EACH_STEP) {
                takeScreenshotAndRegister("STEP_" + FailureScreenshotHandler.sanitizeName(stepName));
            } else if (screenshotStrategy == ScreenshotStrategy.BEFORE_AND_AFTER_EACH_STEP) {
                takeScreenshotAndRegister("STEP_AFTER_" + FailureScreenshotHandler.sanitizeName(stepName));
            }
            // 清除 Cucumber 步骤记录
            TestContextHolder.get().remove(CURRENT_CUCUMBER_STEP_KEY);
        }

        // 手动调用 StepEventBus 的 stepFinished 方法来传递截图（仅在 stepFailed 未发送过时）
        List<ScreenshotAndHtmlSource> stepScreenshots = currentStepScreenshots();
        if (stepScreenshots != null && !stepScreenshots.isEmpty() && !ListenerGuard.guards().isFailureScreenshotsAlreadySent()) {
            VerboseLogging.logDebugIfVerbose(logger, "Manually calling StepEventBus.stepFinished() with {} screenshots", stepScreenshots.size());
            try {
                //  防残留：传递新 list 副本，避免之后 clear() 影响 Serenity 保留的引用
                StepEventBus.getEventBus().stepFinished(new ArrayList<>(stepScreenshots), ZonedDateTime.now());
                VerboseLogging.logDebugIfVerbose(logger, "Successfully called StepEventBus.stepFinished() with screenshots");
                // 清空截图列表，避免重复添加
                stepScreenshots.clear();
                // 标记已处理，防止参数化版本重复处理
                ListenerGuard.guards().setStepFinishProcessed(true);
            } catch (Exception e) {
                logger.error("Failed to call StepEventBus.stepFinished() with screenshots", e);
            }
        } else if (ListenerGuard.guards().isFailureScreenshotsAlreadySent()) {
            VerboseLogging.logDebugIfVerbose(logger, "Skipping StepEventBus.stepFinished() - already sent by stepFailed");
            if (stepScreenshots != null) stepScreenshots.clear();
            ListenerGuard.guards().setStepFinishProcessed(true);
        } else {
            VerboseLogging.logDebugIfVerbose(logger, "No screenshots to pass to StepEventBus.stepFinished()");
        }

        // 重置标志供下一个步骤使用
        //  不再 remove stepFinishProcessed 和 failureScreenshotsAlreadySent
        // 改为在 stepStarted 中重置为 false，防止步骤间窗口期注入脏数据
        ListenerGuard.guards().setFailureScreenshotsAlreadySent(false);
        ListenerGuard.guards().setStepFinishProcessed(false);
        ListenerGuard.guards().setStepFinishReentrant(false);

        //  框架级 API 断言检查（每个步骤结束时兜底执行）
        checkAndFailOnApiAssertions();
        //  框架级未捕获页面异常检查（开关受 playwright.page.error.failOnError 控制）
        checkAndFailOnPageErrors();
    }

    @Override
    public void stepFailed(StepFailure failure) {
        if (failure == null) return;

        //  D4-2：标记本步骤失败，供步骤级结果使用（在防重入判断之前，确保一定被记录）
        STEP_FAILED.get().set(true);

        //  防重复：如果已经发送过失败截图（如 stepFailed param 版已处理），直接跳过
        if (ListenerGuard.guards().isFailureScreenshotsAlreadySent()) {
            logger.debug("stepFailed: screenshots already sent by previous handler, skipping");
            return;
        }

        //  无论成功失败，先强制清空残留截图（防止异常场景污染下一步骤）
        clearStepScreenshotsImmediately();
        currentStepScreenshots().clear();  // 重新初始化供本步骤使用

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
        //  去重：同一异常实例只完整打印一次，后续任何级别均静默（不降级也不打 debug）
        if (ListenerGuard.shouldReportFailure(failure.getException(), shortMsg)) {
            logger.error("Step failure detected: {}", shortMsg);
        }
        recordTestData("stepFailure", fullMsg);
        recordTestData("stepFailureCause", failure.getException().getClass().getSimpleName());

        //  关键修复：无论什么策略，都在步骤失败瞬间截图
        // 这确保捕获的是失败时的真实页面状态（错误信息、弹窗等）
        // BEFORE_AND_AFTER_EACH_STEP / AFTER_EACH_STEP：补充一张 FAILURE 截图
        // FOR_FAILURES：正常走失败截图逻辑
        if (screenshotStrategy != ScreenshotStrategy.DISABLED) {
            String stepName = TestContextHolder.get().get(CURRENT_STEP_NAME_KEY);
            String sanitized = FailureScreenshotHandler.sanitizeFilename(stepName);
            takeScreenshotAndRegister("FAILURE_" + (sanitized != null ? sanitized : "step"));
            logger.info("Failure screenshot captured at step failure moment for: {}", stepName);
        }

        // 步骤失败时，将截图传递给 Serenity（仅一次）
        List<ScreenshotAndHtmlSource> stepScreenshots = currentStepScreenshots();
        if (stepScreenshots != null && !stepScreenshots.isEmpty()) {
            VerboseLogging.logDebugIfVerbose(logger, "Manually calling StepEventBus.stepFinished() with {} screenshots after step failure", stepScreenshots.size());
            try {
                //  防残留：传递新 list 副本
                StepEventBus.getEventBus().stepFinished(new ArrayList<>(stepScreenshots), ZonedDateTime.now());
                VerboseLogging.logDebugIfVerbose(logger, "Successfully called StepEventBus.stepFinished() with failure screenshots");
                // 标记已发送，防止后续 stepFinished() / lastStepFailed 重复调用
                ListenerGuard.guards().setFailureScreenshotsAlreadySent(true);
                // 清空截图列表，避免重复处理
                stepScreenshots.clear();
            } catch (Exception e) {
                logger.error("Failed to call StepEventBus.stepFinished() with failure screenshots", e);
            }
        }

        recordTestData("stepFailureStackTrace", getStackTrace(failure.getException()));
        cleanupAfterAbnormalTermination("stepFailed");
    }

    @Override
    public void lastStepFailed(StepFailure failure) {
        //  最优先检查：如果 stepFailed 已经发送过截图和报告，全部跳过（零开销）
        if (ListenerGuard.guards().isFailureScreenshotsAlreadySent()) {
            logger.debug("lastStepFailed: screenshots already sent by stepFailed, skipping all work");
            return;
        }

        //  无论成功失败，先强制清空残留截图（防止异常场景污染下一步骤/下一用例）
        clearStepScreenshotsImmediately();
        currentStepScreenshots().clear();

        String errorMsg = failure != null ? failure.getException().getMessage() : "Unknown";
        //  去重：与主 stepFailed / testFailed 共享同一异常实例判定，避免同一失败多次打印
        if (ListenerGuard.shouldReportFailure(failure != null ? failure.getException() : null, errorMsg)) {
            VerboseLogging.logErrorIfVerbose(logger, "Last step failed: {}", errorMsg);
        }
        recordTestData("lastStepFailure", errorMsg);

        takeScreenshotAndRegister("FINAL_FAILURE");

        // 步骤失败时，将截图传递给 Serenity
        List<ScreenshotAndHtmlSource> stepScreenshots = currentStepScreenshots();
        if (stepScreenshots != null && !stepScreenshots.isEmpty()) {
            VerboseLogging.logDebugIfVerbose(logger, "Manually calling StepEventBus.stepFinished() with {} screenshots after last step failure", stepScreenshots.size());
            try {
                //  防残留：传递新 list 副本
                StepEventBus.getEventBus().stepFinished(new ArrayList<>(stepScreenshots), ZonedDateTime.now());
                VerboseLogging.logDebugIfVerbose(logger, "Successfully called StepEventBus.stepFinished() with last step failure screenshots");
                ListenerGuard.guards().setFailureScreenshotsAlreadySent(true);  //  标记已发送，防止后续重复
                // 清空截图列表，避免重复处理
                stepScreenshots.clear();
            } catch (Exception e) {
                logger.error("Failed to call StepEventBus.stepFinished() with last step failure screenshots", e);
            }
        }

        // 获取浏览器重启策略（统一走 WebFrameworkConfig 枚举，键=serenity.playwright.restart.browser.for.each）
        String restartBrowserForEach = WebFrameworkConfig.SERENITY_PLAYWRIGHT_RESTART_BROWSER_FOR_EACH.getValue();

        // 无论重启策略如何，都清理当前的上下文和页面，以便重试时使用新的上下文和页面
        VerboseLogging.logInfoIfVerbose(logger, "Last step failed - cleaning up context and page resources (strategy: {})", restartBrowserForEach);
        try {
            PlaywrightRuntime.instance().pageRegistry.closePage();
            PlaywrightRuntime.instance().contextRegistry.closeContext();
            VerboseLogging.logInfoIfVerbose(logger, "Cleaned up page and context resources after last step failure");
        } catch (Exception e) {
            VerboseLogging.logInfoIfVerbose(logger, "Failed to clean up resources after last step failure: {}", e.getMessage());
        } finally {
            cleanupAfterAbnormalTermination("lastStepFailed");
        }
    }

    @Override
    public void stepIgnored() {
        VerboseLogging.logDebugIfVerbose(logger, "Step ignored");
        recordTestData("stepIgnored", true);
        cleanupAfterAbnormalTermination("stepIgnored");
    }

    /**
     * 截图并添加到当前步骤的截图列表
     *
     * @param screenshotName 截图名称
     * @return 截图对象
     */
    /**
     * 截图并添加到当前步骤的截图列表（捕获逻辑见 {@link FailureScreenshotHandler}）。
     *
     * @param screenshotName 截图名称
     */
    private void takeScreenshotAndRegister(String screenshotName) {
        ScreenshotAndHtmlSource screenshot = FailureScreenshotHandler.capture(screenshotName);
        if (screenshot != null) {
            currentStepScreenshots().add(screenshot);
            VerboseLogging.logDebugIfVerbose(
                    logger, "Screenshot added to step: {}", screenshotName);
        }
    }

    private void takeFailureScreenshot(ExecutedStepDescription step) {
        String stepName = (step != null && step.getTitle() != null) ? step.getTitle() : "unknown_step";
        String sanitizedStepName = FailureScreenshotHandler.sanitizeFilename(stepName);
        String screenshotName = "FAILURE_" + (sanitizedStepName != null ? sanitizedStepName : "step");
        // 失败/异常场景也遵循全局 fullPage 配置；无限滚动卡死问题已由稳定化逻辑解决，
        // 不再强制视口（强制视口会丢失全页截图信息）。
        takeScreenshotAndRegister(screenshotName);
    }

    private void recordTestData(String key, Object value) {
        ListenerPerfStats.record(TestContextHolder.get().get(CURRENT_TEST_NAME_KEY), key, value);
    }
    /**
     *  自动清理当前线程的 RouteRegistry 条目（防内存泄漏 + 跨用例路由污染）。
     *
     * <p>在 testFinished 中调用，从 PlaywrightManager 获取当前线程的 Page / Context，
     * 清理 RouteRegistry 中对应的 pattern 记录，释放 RouteEngine 防重门控集合。
     *
     * <p>异常安全：PlaywrightManager.getPage()/getContext() 在某些异常路径下可能抛异常，
     * 逐个 try-catch 保证一个失败不影响另一个。
     */
    private void cleanupRouteRegistryForCurrentThread() {
        try {
            Page page = PlaywrightManager.getPage();
            BrowserContext context = PlaywrightManager.getContext();
            //  统一走 RouteRegistry 释放路由层资源（停止 MonitorSession、unroute、清理注册表与防重门控）
            withRouteLifecycle(lc -> {
                lc.clearContext(page);
                lc.clearContext(context);
            });
        } catch (Exception e) {
            logger.debug("RouteRegistry cleanup for current thread skipped: {}", e.getMessage());
        }
    }

    private void cleanupThreadLocals() {
        //  最关键：先强制清空截图内容（防止残留），再移除所有 ThreadLocal
        clearStepScreenshotsImmediately();

        TestContextHolder.get().remove(TEST_START_TIME_KEY);
        TestContextHolder.get().remove(CURRENT_TEST_NAME_KEY);
        currentTestResult.remove();
        TestContextHolder.get().remove(STEP_START_TIME_KEY);
        TestContextHolder.get().remove(CURRENT_STEP_NAME_KEY);
        TestContextHolder.get().remove(CURRENT_CUCUMBER_STEP_KEY);
        //  清理收拢后的守卫标志与失败日志去重记录（防双重处理 / 重入 / API 失败），避免跨 scenario 残留
        ListenerGuard.clearForThread();
        //  清理 per-thread 截图重入标记
        FailureScreenshotHandler.clearThreadState();
        // currentStepScreenshots 已由 clearStepScreenshotsImmediately() 处理

        //  修复 M1：清理 API 监控失败归集器的 scenario/feature ThreadLocal，
        //    避免线程池复用时失败被错误归因到上一个 scenario（陈旧 ThreadLocal 残留）。
        withRouteLifecycle(lc -> {
            lc.clearMonitorScenario();
            lc.clearMonitorFeature();
            //  新增：清理 API 捕获上下文
            lc.resetCaptureCurrent();
        });
    }

    /**
     * 安全执行 route 生命周期操作。
     *
     * <p>route 模块可能不在 classpath（纯 web 测试，如 {@code framework-web} 单测）——
     * 此时 {@link RouteLifecycleRegistry#get()} 按设计返回 {@code null}
     * （见其 Javadoc：「调用方应做空判断或忽略」）。此处集中空值守卫，
     * 避免各生命周期回调裸调用导致 NPE；web 侧无 route 时整体跳过清理，语义与既有空判断一致。
     */
    private static void withRouteLifecycle(Consumer<RouteLifecycle> action) {
        RouteLifecycle routeLifecycle = RouteLifecycleRegistry.get();
        if (routeLifecycle != null) {
            action.accept(routeLifecycle);
        }
    }

    private String getStackTrace(Throwable throwable) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            throwable.printStackTrace(new java.io.PrintStream(baos, true, java.nio.charset.StandardCharsets.UTF_8));
            return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
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
        //  防递归重入：StepEventBus.stepFinished() 会触发事件重新分发到本监听器，
        // 导致 stepFinishedInternal() → StepEventBus.stepFinished() → stepFinishedInternal() → ... 无限递归
        if (ListenerGuard.guards().isStepFinishReentrant()) {
            return;
        }
        ListenerGuard.guards().setStepFinishReentrant(true);
        fireAfterStep();

        //  防双重处理：如果无参版 stepFinished() 已经处理过，跳过截图和发送
        boolean alreadyProcessed = ListenerGuard.guards().isStepFinishProcessed();

        Long startTime = TestContextHolder.get().get(STEP_START_TIME_KEY);
        if (startTime == null) {
            // 即使没有 startTime 也要清理标志
            ListenerGuard.guards().setStepFinishProcessed(false);
            ListenerGuard.guards().setStepFinishReentrant(false);
            return;
        }

        long duration = System.currentTimeMillis() - startTime;
        recordTestData("stepDuration", duration);
        if (timestamp != null) {
            recordTestData("stepFinishTimestamp", timestamp.toInstant().toEpochMilli());
        }

        // 根据截图策略在步骤结束时截图（ 仅未处理时，防止与 stepFinished() 无参版重复）
        String stepName = TestContextHolder.get().get(CURRENT_STEP_NAME_KEY);
        String cucumberStep = TestContextHolder.get().get(CURRENT_CUCUMBER_STEP_KEY);

        // 只为 Cucumber 级别步骤截图
        if (!alreadyProcessed && stepName != null && !stepName.isEmpty() && stepName.equals(cucumberStep)) {
            if (screenshotStrategy == ScreenshotStrategy.AFTER_EACH_STEP) {
                takeScreenshotAndRegister("STEP_" + FailureScreenshotHandler.sanitizeName(stepName));
            } else if (screenshotStrategy == ScreenshotStrategy.BEFORE_AND_AFTER_EACH_STEP) {
                takeScreenshotAndRegister("STEP_AFTER_" + FailureScreenshotHandler.sanitizeName(stepName));
            }
            // 清除 Cucumber 步骤记录
            TestContextHolder.get().remove(CURRENT_CUCUMBER_STEP_KEY);
        }

        // 将当前步骤的截图合并到 Serenity 传入的截图列表中
        List<ScreenshotAndHtmlSource> stepScreenshots = currentStepScreenshots();
        
        //  防残留（修复）：不再直接 addAll 到 Serenity 的 list（避免跨步骤共享 list 导致累积）。
        // 改为创建全新的 list，杜绝对 Serenity 内部数据结构的污染。
        List<ScreenshotAndHtmlSource> mergedScreenshots = null;
        if (stepScreenshots != null && !stepScreenshots.isEmpty()) {
            VerboseLogging.logDebugIfVerbose(logger,
                    "Before merge: stepScreenshots.size={}, Serenity screenshots.size={}",
                    stepScreenshots.size(), screenshots != null ? screenshots.size() : 0);

            // 创建全新 list，合并 Serenity 传入的 + 当前步骤产生的截图
            int totalSize = stepScreenshots.size() + (screenshots != null ? screenshots.size() : 0);
            mergedScreenshots = new ArrayList<>(totalSize);
            if (screenshots != null && !screenshots.isEmpty()) {
                mergedScreenshots.addAll(screenshots);  // 先加 Serenity 的
            }
            mergedScreenshots.addAll(stepScreenshots);  // 再加当前步骤的
            
            VerboseLogging.logDebugIfVerbose(
                    logger, "After merge: new mergedScreenshots.size={}", mergedScreenshots.size());
            
            // 清空当前步骤的截图列表，避免影响下一个步骤（防残留核心）
            stepScreenshots.clear();
            VerboseLogging.logDebugIfVerbose(
                    logger, "Cleared stepScreenshots after merging");
        } else if (screenshots != null && !screenshots.isEmpty()) {
            // 当前步骤没有截图，但 Serenity 有 → 也用新 list 封装（避免直接传递 Serenity 的可变 list）
            mergedScreenshots = new ArrayList<>(screenshots);
        } else {
            VerboseLogging.logDebugIfVerbose(
                    logger, "No step screenshots to merge (list is empty or null)");
        }

        //  防双重发送：仅当无参版 stepFinished() 未处理时才发送 StepEventBus
        if (!alreadyProcessed && mergedScreenshots != null && !mergedScreenshots.isEmpty() && !ListenerGuard.guards().isFailureScreenshotsAlreadySent()) {
            recordTestData("stepScreenshotsCount", mergedScreenshots.size());
            // 手动调用 StepEventBus 的 stepFinished 方法来传递截图
            VerboseLogging.logDebugIfVerbose(
                    logger, "Manually calling StepEventBus.stepFinished() with {} screenshots", mergedScreenshots.size());
            try {
                StepEventBus.getEventBus().stepFinished(mergedScreenshots, ZonedDateTime.now());
                VerboseLogging.logDebugIfVerbose(
                        logger, "Successfully called StepEventBus.stepFinished() with screenshots");
                // 标记已处理，防止无参版 stepFinished() 重复处理
                ListenerGuard.guards().setStepFinishProcessed(true);
            } catch (Exception e) {
                logger.error("Failed to call StepEventBus.stepFinished() with screenshots", e);
            }
        } else if (ListenerGuard.guards().isFailureScreenshotsAlreadySent()) {
            VerboseLogging.logDebugIfVerbose(logger, "Skipping StepEventBus.stepFinished() in stepFinishedInternal - already sent by stepFailed");
            //  防残留：清空 Serenity 传入的 list
            if (screenshots != null) screenshots.clear();
        } else {
            VerboseLogging.logDebugIfVerbose(
                    logger, "Serenity screenshot list is empty or null");
        }

        // 重置标志供下一个步骤使用
        //  不再 remove stepFinishProcessed 和 failureScreenshotsAlreadySent
        // 改为在 stepStarted 中重置为 false，防止步骤间窗口期注入脏数据
        ListenerGuard.guards().setFailureScreenshotsAlreadySent(false);
        ListenerGuard.guards().setStepFinishProcessed(false);
        ListenerGuard.guards().setStepFinishReentrant(false);

        //  框架级 API 断言检查（每个 Cucumber 步骤结束时自动执行）
        checkAndFailOnApiAssertions();
        //  框架级未捕获页面异常检查（开关受 playwright.page.error.failOnError 控制）
        checkAndFailOnPageErrors();

        VerboseLogging.logDebugIfVerbose(logger, "Step completed in {}ms", duration);
    }

    @Override
    public void takeScreenshots(List<ScreenshotAndHtmlSource> screenshots) {
        VerboseLogging.logDebugIfVerbose(logger, "takeScreenshots called with {} screenshots",
                screenshots != null ? screenshots.size() : 0);

        // 将当前步骤的截图添加到传入的列表中（仅在当前步骤活跃时，防止残留）
        List<ScreenshotAndHtmlSource> stepScreenshots = currentStepScreenshots();
        if (stepScreenshots != null && !stepScreenshots.isEmpty() && screenshots != null && TestContextHolder.get().get(STEP_START_TIME_KEY) != null) {
            screenshots.addAll(stepScreenshots);
            VerboseLogging.logDebugIfVerbose(logger, "Added {} screenshots from currentStepScreenshots to takeScreenshots list", stepScreenshots.size());
            // 合并后立即清空，防止重复注入
            stepScreenshots.clear();
        }
    }

    @Override
    public void takeScreenshots(TestResult result, List<ScreenshotAndHtmlSource> screenshots) {
        VerboseLogging.logDebugIfVerbose(logger, "takeScreenshots called with result {} and {} screenshots",
                result, screenshots != null ? screenshots.size() : 0);

        // 将当前步骤的截图添加到传入的列表中（仅在当前步骤活跃时）
        List<ScreenshotAndHtmlSource> stepScreenshots = currentStepScreenshots();
        if (stepScreenshots != null && !stepScreenshots.isEmpty() && screenshots != null && TestContextHolder.get().get(STEP_START_TIME_KEY) != null) {
            screenshots.addAll(stepScreenshots);
            VerboseLogging.logDebugIfVerbose(logger, "Added {} screenshots from currentStepScreenshots to takeScreenshots list (result: {})",
                    stepScreenshots.size(), result);
            // 合并后立即清空
            stepScreenshots.clear();
        }
    }



    @Override
    public void testSuiteStarted(Class<?> testClass) {
        VerboseLogging.logDebugIfVerbose(logger, "Test suite started for class: {}", testClass);
        //  重置：允许新 suite 再次触发清理
        testSuiteFinishedLogged = false;
    }

    @Override
    public void testSuiteStarted(Story story) {
        VerboseLogging.logDebugIfVerbose(
                logger, "Test suite started for story: {}", story.getStoryName());

        //  阶段检测：当同一个 Story 在 discovery 阶段被注册过，再次出现时说明进入 execution
        String storyName = story.getStoryName();
        if (!discoveryPhaseCompleted && !seenStoryNames.add(storyName)) {
            // 重复的 story → 已进入 execution phase
            discoveryPhaseCompleted = true;
            VerboseLogging.logInfoIfVerbose(logger,
                    "Execution phase detected (duplicate story: {}), enabling Playwright init and cross-feature cleanup", storyName);
        }

        //  跨 Feature Context 清理：新 Story 启动时关闭上一个 Story 的 Context
        // 仅在 execution 阶段执行清理，避免 discovery 阶段误关 Context
        if (discoveryPhaseCompleted) {
            VerboseLogging.logInfoIfVerbose(logger,
                    "New story starting: {} — closing previous story's Context (cross-feature cleanup)", story.getStoryName());
            try {
                PlaywrightManager.cleanupForFeature();
            } catch (Exception e) {
                logger.warn("Failed to cleanup context at feature boundary for new story: {} — {}",
                        story.getStoryName(), e.getMessage());
            }
        }

        //  重置：允许新 suite 再次触发清理
        testSuiteFinishedLogged = false;

        //  Rerun 日志：testRunFinished() 之后首次 testSuiteStarted 即为 rerun
        if (currentRunNumber > 0 && !rerunStartedLogged) {
            rerunStartedLogged = true;
            logger.info("==========================================================================");
            logger.info("  RERUN STARTING — Round {} (Maven Failsafe rerunFailingTestsCount)", currentRunNumber);
            logger.info("==========================================================================");
        }
    }

    @Override
    public void testSuiteFinished() {
        //  阶段识别：首次 testSuiteFinished 标志 discovery 阶段结束
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
        VerboseLogging.logInfoIfVerbose(logger, "Cleaning up all Playwright resources at test suite finish");
        
        try {
            PlaywrightManager.cleanupForFeature();
            VerboseLogging.logInfoIfVerbose(logger, "Cleaned up all resources at test suite finish");
        } catch (Exception e) {
            VerboseLogging.logInfoIfVerbose(logger, "Failed to clean up resources at test suite finish: {}", e.getMessage());
        }
    }

    @Override
    public void testStarted(String testName, String testMethod) {
        //  阶段识别：discovery 阶段跳过 Playwright 资源初始化
        if (!discoveryPhaseCompleted) {
            VerboseLogging.logDebugIfVerbose(logger,
                    "[Discovery Phase] Skipping Playwright init for test: {} (method: {})", testName, testMethod);
            return;
        }

        String uniqueTestName = testName + "_" + Thread.currentThread().threadId();
        TestContextHolder.get().set(CURRENT_TEST_NAME_KEY,uniqueTestName);
        TestContextHolder.get().set(TEST_START_TIME_KEY,System.currentTimeMillis());

        //  新增：重置 API 捕获上下文（route 未启用时整体跳过）
        withRouteLifecycle(lc -> {
            lc.resetCaptureCurrent();
            //  绑定当前 scenario 名：让 API 监控失败记录能归属到具体场景
            lc.setMonitorScenario(testName);
        });
        //  丢弃上一场景残留的待报告 API 记录，避免其被写入本场景报告（跨场景串扰）
        SerenityReporter.discardPendingApiOperations();
        //  重置 API 失败标记（每个新 case 重新开始追踪）
        ListenerGuard.guards().setApiFailureAlreadyHandled(false);

        //  安全清理：确保上一个 scenario 的采集引擎已释放
        withRouteLifecycle(RouteLifecycle::stopCapture);

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
        //  阶段识别：discovery 阶段跳过 Playwright 资源初始化
        if (!discoveryPhaseCompleted) {
            VerboseLogging.logDebugIfVerbose(logger,
                    "[Discovery Phase] Skipping Playwright init for test: {} (method: {}, time: {})",
                    testName, testMethod, startTime);
            return;
        }

        // 生成唯一名称：原名称 + 线程ID（保证同名 scenario 不合并）
        String uniqueTestName = testName + "_" + Thread.currentThread().threadId();
        TestContextHolder.get().set(CURRENT_TEST_NAME_KEY,uniqueTestName);
        TestContextHolder.get().set(TEST_START_TIME_KEY,startTime != null ? startTime.toInstant().toEpochMilli() : System.currentTimeMillis());

        //  新增：重置 API 捕获上下文（route 未启用时整体跳过）
        withRouteLifecycle(lc -> {
            lc.resetCaptureCurrent();
            //  绑定当前 scenario 名：让 API 监控失败记录能归属到具体场景
            lc.setMonitorScenario(testName);
        });
        //  丢弃上一场景残留的待报告 API 记录，避免其被写入本场景报告（跨场景串扰）
        SerenityReporter.discardPendingApiOperations();
        //  重置 API 失败标记（每个新 case 重新开始追踪）
        ListenerGuard.guards().setApiFailureAlreadyHandled(false);

        //  安全清理：确保上一个 scenario 的采集引擎已释放
        withRouteLifecycle(RouteLifecycle::stopCapture);

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
            //  新增：检查 API 断言失败并标记测试结果
            checkAndMarkApiAssertionFailures(result);
            //  D3-2：软断言收集到的失败在场景末统一上报（先于结果落定，确保计入本场景）
            StepFailureAggregator.checkAndMarkSoftAssertionFailures(result);
            // 更新当前测试结果
            if (result != null && result.getResult() != null) {
                currentTestResult.set(result.getResult());
            }
            testFinishedInternal();

            //  新增：自动清理当前线程的 RouteRegistry（防内存泄漏 + 跨用例污染）
            cleanupRouteRegistryForCurrentThread();

            // 获取浏览器重启策略（统一走 WebFrameworkConfig 枚举，键=serenity.playwright.restart.browser.for.each）
            String restartBrowserForEach = WebFrameworkConfig.SERENITY_PLAYWRIGHT_RESTART_BROWSER_FOR_EACH.getValue();

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

            //  采集管道清理：确保 scenario 结束时采集引擎释放（route 未启用时跳过）
            withRouteLifecycle(RouteLifecycle::stopCapture);
        } catch (Exception e) {
            //  testFinished 属收尾回调：清理阶段异常不应上抛中断 Serenity 收尾流程。
            // 仅记录日志 + 兜底清空防重门控，ThreadLocal 与 API 上下文清理交由 finally 保证。
            logger.error("Error in testFinished, forcing cleanup", e);
            try {
                withRouteLifecycle(RouteLifecycle::clearDispatchedRoutes);
            } catch (Exception re) {
                logger.debug("clearDispatchedRoutes on error path failed: {}", re.getMessage());
            }
        } finally {
            // 确保异常和正常路径均清理 ThreadLocal 和 API 捕获上下文
            cleanupThreadLocals();
            //  D3-1：解绑 MDC 中的 scenario 标识（Cucumber 线程会被线程池复用，不解绑会串扰下一用例）
            LogContext.endScenario();
            //  D3-2：兜底清空软断言收集器（防止未走上报路径时把失败带到下一场景）
            SoftAssertions.clearForCurrentThread();
            //  D4-1：通知业务监听器场景结束（放在 finally，异常收尾也要通知）
            fireAfterScenario(result != null && result.getResult() != null
                    && (result.getResult() == TestResult.FAILURE
                        || result.getResult() == TestResult.ERROR));
            withRouteLifecycle(RouteLifecycle::resetCaptureCurrent);
        }
    }

    @Override
    public void testFinished(TestOutcome result, boolean isInDataDrivenTest, ZonedDateTime finishTime) {
        if (result == null) {
            VerboseLogging.logWarnIfVerbose(logger, "TestOutcome is null in testFinished with time, skipping processing");
            return;
        }

        try {
            VerboseLogging.logDebugIfVerbose(logger, "Test finished: {}, isDataDriven: {}, finishTime: {}", result, isInDataDrivenTest, finishTime);

            //  新增：检查 API 断言失败并标记测试结果
            checkAndMarkApiAssertionFailures(result);
            //  D3-2：软断言收集到的失败在场景末统一上报（Cucumber 实际走本重载）
            StepFailureAggregator.checkAndMarkSoftAssertionFailures(result);

            //  将本场景产生的 API 记录刷入 Serenity 报告。必须在 Serenity 仍关联本场景时执行，
            //    否则残留会滞留在队列中，被下一场景的 flush 带走造成跨场景串扰。
            SerenityReporter.flushPendingApiOperations();

            Long startTime = TestContextHolder.get().get(TEST_START_TIME_KEY);
            if (startTime == null) {
                return;
            }

            long duration = System.currentTimeMillis() - startTime;
            String testName = TestContextHolder.get().get(CURRENT_TEST_NAME_KEY);

            //  测试计数：在 testFinished(TestOutcome,boolean,ZonedDateTime) 中计数
            // Serenity+Cucumber 实际调用这个重载而非 testFinished(TestOutcome)
            ListenerPerfStats.markTotal();

            recordTestData("testEnd", finishTime != null ? finishTime.toInstant().toEpochMilli() : System.currentTimeMillis());
            recordTestData("testDuration", duration);
            //  D4-2：结果经框架自有模型广播（框架只认 ResultReporter 端口，
            //  由 SerenityResultAdapter 收敛引擎方言 —— 换报告引擎时此处无需改动）
            ResultReporters.reportScenario(testName,
                    SerenityResultAdapter.toFramework(result.getResult()), duration);
            recordTestData("isDataDrivenTest", isInDataDrivenTest);

            if (result != null && result.getResult() != null) {
                recordTestData("testResult", result.toString());

                switch (result.getResult()) {
                    case SUCCESS:
                        ListenerPerfStats.markPassed();
                        break;
                    case PENDING:
                        VerboseLogging.logWarnIfVerbose(logger, "Test result is PENDING, counting as failed: {}", testName);
                        ListenerPerfStats.markFailed();
                        break;
                    case FAILURE:
                    case ERROR:
                    case UNDEFINED:
                        ListenerPerfStats.markFailed();
                        break;
                    case SKIPPED:
                        ListenerPerfStats.markSkipped();
                        break;
                    default:
                        VerboseLogging.logWarnIfVerbose(logger, "Unknown test result: {}", result.getResult());
                        break;
                }
            } else {
                VerboseLogging.logWarnIfVerbose(logger, "Test result is null, counting as failed: {}", testName);
                ListenerPerfStats.markFailed();
            }

            VerboseLogging.logInfoIfVerbose(logger, "Test completed: {} in {}ms (DataDriven: {}, Result: {})", testName, duration, isInDataDrivenTest, result);
        } catch (Exception e) {
            //  收尾回调异常不向上抛出，避免中断 Serenity 收尾流程；防重门控清空交由 finally 兜底。
            logger.error("Error in testFinished with time, forcing cleanup", e);
            try {
                withRouteLifecycle(RouteLifecycle::clearDispatchedRoutes);
            } catch (Exception re) {
                logger.debug("clearDispatchedRoutes on error path failed: {}", re.getMessage());
            }
        } finally {
            // 【关键】finally 保证：无论中间是否抛异常，ThreadLocal 一定会被清理
            cleanupThreadLocals();
            //  D3-1：解绑 MDC 中的 scenario 标识（Cucumber 实际走本重载；线程复用必须解绑）
            LogContext.endScenario();
            //  D3-2：兜底清空软断言收集器（含 startTime 为 null 等提前 return 路径）
            SoftAssertions.clearForCurrentThread();
            //  D4-1：通知业务监听器场景结束（Cucumber 实际走本重载）
            fireAfterScenario(result != null && result.getResult() != null
                    && (result.getResult() == TestResult.FAILURE
                        || result.getResult() == TestResult.ERROR));
            //  解绑当前线程的 scenario 归属：Cucumber 执行线程会被线程池复用，
            //   不 remove 会把上一个 scenario 名带到下一个用例（MonitorFailureCollector 归属串扰）
            withRouteLifecycle(RouteLifecycle::clearMonitorScenario);
            //  新增：自动清理当前线程的 RouteRegistry（防内存泄漏 + 跨用例污染）
            cleanupRouteRegistryForCurrentThread();

            //  修复：补齐 Playwright 资源清理（与 testFinished(TestOutcome) 保持一致）
            // 统一调用 cleanupForScenario()：内部已按 restartStrategy 分支处理
            // Feature 模式不能只调 cleanupPageState()，否则 customContextOptionsFlag 泄漏
            try {
                PlaywrightManager.cleanupForScenario();
                //  采集管道清理：确保 scenario 结束时采集引擎释放（route 未启用时跳过）
                withRouteLifecycle(RouteLifecycle::stopCapture);
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
        //  防重复 + 委托：统一交给无参 stepFailed(StepFailure) 处理截图和报告发送
        // 避免两个方法维护几乎相同逻辑导致的 drift 风险
        if (failure == null || ListenerGuard.guards().isFailureScreenshotsAlreadySent()) return;

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
        //  D4-1：把真实异常桥接给业务监听器（换引擎时业务代码无需改动）
        FrameworkListenerBridge.onFailure(CURRENT_SCENARIO_NAME.get(), throwable);
        String errorMsg = throwable != null ? throwable.getMessage() : "Unknown error";
        if (errorMsg != null && errorMsg.contains("\n")) {
            errorMsg = errorMsg.substring(0, errorMsg.indexOf('\n')).trim();
        }
        //  去重：同一异常若已在 stepFailed / lastStepFailed 打印过，此处完全静默（不论日志级别）
        if (ListenerGuard.shouldReportFailure(throwable, errorMsg)) {
            logger.error("Test failed: {} - {}", testTitle, errorMsg);
        }

        //  新增：检查 API 断言失败
        checkAndMarkApiAssertionFailures(result);

        // 注意：不在 testFailed 中 increment failedTests，避免与 testFinished 重复计数
        // 测试失败统计由 testFinished 统一处理
        // 重试能力由 Maven Failsafe Plugin 的 rerunFailingTestsCount 提供
    }

    @Override
    public void testIgnored() {
        logger.debug("Test ignored");
        ListenerPerfStats.markSkipped();
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
        //  禁用 SCREEN_CHANGE 截图 — 报告变长的最大元凶
        // Serenity 报告是纵向堆叠所有截图，每次页面变化（弹窗、动画、滚动、hover等）
        // 都会触发此回调 → 每个步骤产生 3~10 张 SCREEN_CHANGE 截图 → 报告巨长不可读
        //
        // 如果未来需要重新启用（仅用于调试特定场景），取消下面的注释即可：
        // if (TestContextHolder.get().get(STEP_START_TIME_KEY) != null && !ListenerGuard.guards().isStepFinishProcessed() && TestContextHolder.get().get(CURRENT_STEP_NAME_KEY) != null) {
        //     takeScreenshotAndRegister("SCREEN_CHANGE");
        // }
        VerboseLogging.logDebugIfVerbose(logger, "SCREEN_CHANGE screenshot disabled (report length optimization)");
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
        //  阶段识别：重置 discovery 标记和 story 集合，支持同 JVM rerun 时识别新 discovery 阶段
        discoveryPhaseCompleted = false;
        seenStoryNames.clear();

        logger.info(getPerformanceStats());

        //  标记当前 run 结束，为下一次 rerun 做准备
        currentRunNumber++;
        rerunStartedLogged = false;
    }

    public static String getPerformanceStats() {
        return ListenerPerfStats.getPerformanceStats();
    }

    public static void resetStats() {
        ListenerPerfStats.resetStats();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  API 监控断言集成
    // ═══════════════════════════════════════════════════════════════════

    /**
     *  核心方法：检查 API 断言失败并标记测试结果为失败
     *
     * <p>在测试结束时调用，等待所有异步 API 请求完成（最多 5 秒），
     * 如果 MonitorHandler 标记了断言失败，则强制设置 TestResult.FAILURE。
     */
    private void checkAndMarkApiAssertionFailures(TestOutcome result) {
        StepFailureAggregator.checkAndMarkApiAssertionFailures(
                result, TestContextHolder.get().get(CURRENT_TEST_NAME_KEY));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  框架级 API 断言自动检查
    // ═══════════════════════════════════════════════════════════════════

    /**
     *  框架级：在每个步骤结束时自动检查 API 断言是否失败。
     *
     * <p><b>通过 StepEventBus 即时标记失败</b>：
     * 使用 {@code apiFailureAlreadyHandled} ThreadLocal 标记防止同一 case 内
     * StepEventBus 回调链的递归重入（死循环）。
     *
     * <p>防重入机制：
     * <ol>
     *   <li>步骤结束 → checkAndFailOnApiAssertions() → set flag → StepEventBus.testFailed()</li>
     *   <li>→ PlaywrightListener.testFailed(TestOutcome,Throwable) → checkAndMarkApiAssertionFailures() ✅</li>
     *   <li>后续步骤结束 → checkAndFailOnApiAssertions() → flag already set → return ✅</li>
     * </ol>
     *
     * <p>标记在下一个 test/scenario 的 testStarted / stepStarted 中重置，
     * 确保不影响后续 Scenario 的执行。
     *
     * <p>同时 {@link #checkAndMarkApiAssertionFailures(TestOutcome)} 在 testFinished
     * 中作为兜底，再次通过 {@code result.setResult(TestResult.FAILURE)} 确保标记生效。
     */
    /**
     *  框架级未捕获页面异常检查（每个步骤结束时兜底执行）。
     * 仅当 {@code playwright.page.error.failOnError=true} 时生效：将 {@link PageEventMonitor}
     * 收集的未捕获 JS 异常经 Serenity 标记测试失败并抛出，使前端脚本错误即时暴露。
     * 默认关闭（仅记录日志），不影响既有行为；drain 即清空，天然幂等，不会跨步骤/跨 scenario 重复触发。
     */
    private void checkAndFailOnPageErrors() {
        StepFailureAggregator.checkAndFailOnPageErrors();
    }

    private void checkAndFailOnApiAssertions() {
        StepFailureAggregator.checkAndFailOnApiAssertions();
    }
}
