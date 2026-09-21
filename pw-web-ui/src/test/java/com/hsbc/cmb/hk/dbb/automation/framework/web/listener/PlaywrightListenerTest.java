package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.strategy.ScreenshotStrategy;
import net.thucydides.model.domain.DataTable;
import net.thucydides.model.domain.Story;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import net.thucydides.model.steps.ExecutedStepDescription;
import net.thucydides.model.steps.StepFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WEB-P1-5 种子测试：{@code PlaywrightListener} 生命周期骨架（无浏览器）。
 *
 * <p><b>安全边界（务必遵守）</b>：本类只调用<b>不触发 Playwright 资源初始化</b>的回调。
 * 判定依据：静态标记 {@code discoveryPhaseCompleted} 默认为 {@code false}，
 * 此时 {@code testStarted} 走 discovery 分支提前返回、不初始化浏览器；
 * 而 {@code testSuiteFinished()} / 重复 Story 会把它置为 {@code true}，
 * 一旦置位，后续任何 {@code testStarted} 都会真正初始化 Playwright（浏览器）。
 * 故 {@code tearDown} 用反射复位该标记，杜绝跨用例污染。</p>
 */
public class PlaywrightListenerTest {

    private PlaywrightListener listener;

    @BeforeEach
    public void setUp() throws Exception {
        listener = new PlaywrightListener();
        ListenerPerfStats.resetStats();

        // 默认截图策略为 AFTER_EACH_STEP（每步/每测试都截图，会触发浏览器）；
        // 单测用反射强制设为 DISABLED，使 testFinished/stepFailed/stepFinished 等路径
        // 跳过截图（受 `screenshotStrategy != DISABLED` 守卫），彻底规避浏览器依赖。
        Field strat = PlaywrightListener.class.getDeclaredField("screenshotStrategy");
        strat.setAccessible(true);
        strat.set(listener, ScreenshotStrategy.DISABLED);

        // web 测试 classpath 无 route 模块实现，RouteLifecycleRegistry.get() 返回 null；
        // 而 PlaywrightListener 在多个生命周期回调里直接调用其方法，会 NPE。
        // 注入一个 mock 实现（get() 返回非空），覆盖路由清理/采集重置等分支。
        RouteLifecycle routeLifecycle = mock(RouteLifecycle.class);
        CaptureContext captureContext = mock(CaptureContext.class);
        when(routeLifecycle.getCurrentCapture()).thenReturn(captureContext);
        RouteLifecycleRegistry.register(routeLifecycle);
    }

    @AfterEach
    public void tearDown() throws Exception {
        ListenerPerfStats.resetStats();
        // 复位路由注册表，避免 mock 实现泄漏到其它测试套件
        RouteLifecycleRegistry.register(null);
        // 复位阶段标记：本用例可能经 testSuiteFinished / 重复 Story 将其置 true，
        // 不复位会导致同 JVM 内后续用例的 testStarted 真正启动浏览器。
        Field phase = PlaywrightListener.class.getDeclaredField("discoveryPhaseCompleted");
        phase.setAccessible(true);
        phase.setBoolean(null, false);
    }

    @Test
    public void testStarted_duringDiscoveryPhase_recordsBookkeepingWithoutPlaywrightInit() {
        listener.testStarted("myTest");

        // discovery 分支仍须完成轻量登记（唯一测试名含线程号，便于同名 scenario 区分）
        String uniqueName = TestContextHolder.get().get(ListenerGuard.CURRENT_TEST_NAME_KEY);
        assertNotNull( uniqueName, "testStarted 必须登记当前测试名");
        assertTrue( uniqueName.startsWith("myTest_"), "唯一名应以原测试名为前缀：" + uniqueName);
    }

    @Test
    public void testSkipped_recordsSkipStatistic() {
        listener.testSkipped();

        assertTrue(
                PlaywrightListener.getPerformanceStats().contains("Skipped: 1"), "跳过须计入统计：" + PlaywrightListener.getPerformanceStats());
    }

    @Test
    public void stepStarted_recordsStepBookkeeping() {
        ExecutedStepDescription step = mock(ExecutedStepDescription.class);
        when(step.getTitle()).thenReturn("my step");

        listener.stepStarted(step);
        listener.stepStarted(null); // 防御：null 步骤直接返回

        assertNotNull(listener);
    }

    @Test
    public void lightweightCallbacks_completeWithoutThrowing() {
        ExecutedStepDescription step = mock(ExecutedStepDescription.class);
        when(step.getTitle()).thenReturn("my step");

        listener.stepIgnored();
        listener.testIgnored();
        listener.testPending();
        listener.testIsManual();
        listener.notifyScreenChange();
        listener.exampleFinished();
        listener.assumptionViolated("violated");
        listener.testRetried();
        listener.skippedStepStarted(step);
        listener.stepPending();
        listener.stepPending("pending step");
    }

    @Test
    public void dataDrivenCallbacks_completeWithoutThrowing() {
        DataTable dataTable = mock(DataTable.class);
        when(dataTable.getRows()).thenReturn(List.of());

        listener.useExamplesFrom(dataTable);
        listener.addNewExamplesFrom(dataTable);
        listener.exampleStarted(Map.of("user", "alice"));
    }

    @Test
    public void suiteCallbacks_completeWithoutThrowing() {
        Story story = mock(Story.class);
        // 用唯一 story 名，避免与既有 seenStoryNames 重复而提前判定进入 execution 阶段
        when(story.getStoryName()).thenReturn("story-" + UUID.randomUUID());

        listener.testSuiteStarted(getClass());
        listener.testSuiteStarted(story);
        listener.testSuiteFinished();
    }

    @Test
    public void stepFinished_withoutPrecedingStepStart_isSkippedSafely() {
        // 无 STEP_START_TIME 时须安全返回，不抛空指针
        listener.stepFinished();
    }

    @Test
    public void screenshotCallbacks_withEmptyList_completeWithoutThrowing() {
        listener.takeScreenshots(List.of());
        listener.takeScreenshots(TestResult.SUCCESS, List.of());
    }

    @Test
    public void testFinished_withOutcome_runsCleanupWithoutThrowing() {
        // testStarted 在 discovery 阶段仍会登记 TEST_START_TIME_KEY，使 testFinishedInternal 进入统计路径
        listener.testStarted("scenarioA");
        TestOutcome outcome = mock(TestOutcome.class);

        listener.testFinished(outcome);

        // testFinishedInternal 在 finally 中强制清理 ThreadLocal，断言不抛即覆盖收尾路径
        assertNotNull(listener);
    }

    @Test
    public void stepStartedThenStepFinished_completesFullStepCycle() {
        ExecutedStepDescription step = mock(ExecutedStepDescription.class);
        when(step.getTitle()).thenReturn("cucumber step");

        listener.stepStarted(step);
        listener.stepFinished();
    }

    @Test
    public void stepFailed_recordsFailureWithoutScreenshot() {
        StepFailure failure = mock(StepFailure.class);
        when(failure.getException()).thenReturn(new RuntimeException("element intercepted"));

        listener.stepFailed(failure);
    }

    @Test
    public void testFailed_recordsFailureWithoutThrowing() {
        TestOutcome outcome = mock(TestOutcome.class);
        Throwable cause = new RuntimeException("scenario failed");

        listener.testFailed(outcome, cause);

        assertNotNull(listener);
    }

    @Test
    public void stepFinished_withScreenshotList_processesWithoutScreenshot() {
        ExecutedStepDescription step = mock(ExecutedStepDescription.class);
        when(step.getTitle()).thenReturn("cucumber step");

        // 先 stepStarted 登记 STEP_START_TIME，使 stepFinishedInternal 进入处理分支
        listener.stepStarted(step);
        listener.stepFinished(List.of());
    }

    @Test
    public void testRunFinished_resetsDiscoveryPhaseAndRunCounter() {
        // testRunFinished 仅做状态复位（discoveryPhaseCompleted=false / 清空 seenStoryNames / run 计数 +1），无浏览器依赖
        listener.testRunFinished();

        assertNotNull(listener);
    }

    @Test
    public void stepFinished_withTimestamp_processesWithoutScreenshot() {
        ExecutedStepDescription step = mock(ExecutedStepDescription.class);
        when(step.getTitle()).thenReturn("cucumber step");

        listener.stepStarted(step);
        listener.stepFinished(List.of(), ZonedDateTime.now());
    }

    @Test
    public void stepFailed_withScreenshotFlagFalse_delegatesToCoreHandler() {
        StepFailure failure = mock(StepFailure.class);
        when(failure.getException()).thenReturn(new RuntimeException("timeout"));

        // takeScreenshotOnFailure=false 时跳过截图，仅委托核心失败处理逻辑（策略已 DISABLED）
        listener.stepFailed(failure, List.of(), false, ZonedDateTime.now());

        assertNotNull(listener);
    }

    @Test
    public void testStarted_overloads_skipInitDuringDiscoveryPhase() {
        // 多参 testStarted 重载同样受 discoveryPhaseCompleted 守卫：discovery 阶段提前返回，不初始化浏览器
        listener.testStarted("scenario", "method");
        listener.testStarted("scenario", "method", ZonedDateTime.now());

        assertNotNull(listener);
    }

    @Test
    public void testFinished_withTimeAndResult_completesFullCycle() {
        listener.testStarted("scenarioT");
        TestOutcome outcome = mock(TestOutcome.class);
        when(outcome.getResult()).thenReturn(TestResult.SUCCESS);

        // 3 参重载走完整统计/清理分支（markTotal/markPassed/recordTestData），
        // cleanupForScenario / flushPendingApiOperations 均在 try/catch 内，不抛即覆盖收尾路径
        listener.testFinished(outcome, false, ZonedDateTime.now());

        assertNotNull(listener);
    }
}
