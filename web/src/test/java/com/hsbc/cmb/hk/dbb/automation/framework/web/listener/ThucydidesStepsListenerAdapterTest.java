package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import net.thucydides.model.domain.DataTable;
import net.thucydides.model.domain.Story;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import net.thucydides.model.screenshots.ScreenshotAndHtmlSource;
import net.thucydides.model.steps.ExecutedStepDescription;
import net.thucydides.model.steps.StepFailure;
import net.thucydides.model.steps.StepListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WEB-P1-5 种子测试：Serenity 监听器适配器（无浏览器）。
 *
 * <p><b>测试脚手架要点</b>：适配器把全部生命周期事件转发给 {@code delegateListeners}。
 * 用例在 {@code setUp} 中先 {@code clearDelegateListeners()}（该列表为静态共享），
 * 再注入一个 mock {@link StepListener}——既把断言锚定在"转发契约"上，
 * 又隔离了真实的 {@code PlaywrightListener} / {@code AxeCoreListener}，避免测试触碰浏览器。</p>
 */
public class ThucydidesStepsListenerAdapterTest {

    private ThucydidesStepsListenerAdapter adapter;
    private StepListener delegate;

    @Before
    public void setUp() {
        adapter = new ThucydidesStepsListenerAdapter();
        adapter.clearDelegateListeners();
        delegate = mock(StepListener.class);
        adapter.addDelegateListener(delegate);
    }

    @After
    public void tearDown() {
        adapter.clearDelegateListeners();
    }

    // ---------- 委托监听器的增删管理 ----------

    @Test
    public void addDelegateListener_ignoresNullAndDuplicates() {
        adapter.clearDelegateListeners();

        adapter.addDelegateListener(null);
        assertEquals("null 监听器必须被忽略", 0, adapter.getDelegateListenerCount());

        StepListener once = mock(StepListener.class);
        adapter.addDelegateListener(once);
        adapter.addDelegateListener(once);
        assertEquals("同一监听器不得重复登记", 1, adapter.getDelegateListenerCount());
    }

    @Test
    public void removeDelegateListener_removesRegisteredListener() {
        assertEquals(1, adapter.getDelegateListenerCount());

        adapter.removeDelegateListener(delegate);

        assertEquals(0, adapter.getDelegateListenerCount());
    }

    @Test
    public void getAdapterStatus_reportsRegisteredDelegates() {
        String status = adapter.getAdapterStatus();

        assertTrue(status.contains("Delegate Listeners: 1"));
        assertTrue(status.contains("StepListener"));
    }

    // ---------- 测试生命周期事件转发 ----------

    @Test
    public void testLifecycleEvents_delegateToRegisteredListeners() {
        adapter.testStarted("myTest");
        adapter.testSkipped();
        adapter.testAborted();
        adapter.stepFinished();
        adapter.stepIgnored();
        adapter.testSuiteFinished();
        adapter.testRetried();
        adapter.stepPending();
        adapter.stepPending("pending desc");
        adapter.testIgnored();
        adapter.testPending();
        adapter.testIsManual();
        adapter.notifyScreenChange();
        adapter.exampleFinished();
        adapter.assumptionViolated("violated");
        adapter.testRunFinished();

        verify(delegate).testStarted("myTest");
        verify(delegate).testSkipped();
        verify(delegate).stepFinished();
        verify(delegate).stepIgnored();
        verify(delegate).testSuiteFinished();
        verify(delegate).testRetried();
        verify(delegate).stepPending();
        verify(delegate).stepPending("pending desc");
        verify(delegate).testIgnored();
        verify(delegate).testPending();
        verify(delegate).testIsManual();
        verify(delegate).notifyScreenChange();
        verify(delegate).exampleFinished();
        verify(delegate).assumptionViolated("violated");
        verify(delegate).testRunFinished();
    }

    @Test
    public void testStartedVariants_delegateToRegisteredListeners() {
        ZonedDateTime now = ZonedDateTime.now();

        adapter.testStarted("name", "method");
        adapter.testStarted("name", "method", now);
        adapter.testStarted("description", now);

        verify(delegate).testStarted("name", "method");
        verify(delegate).testStarted("name", "method", now);
        verify(delegate).testStarted("description", now);
    }

    @Test
    public void testFinishedVariants_delegateToRegisteredListeners() {
        TestOutcome outcome = mock(TestOutcome.class);
        ZonedDateTime now = ZonedDateTime.now();

        adapter.testFinished(outcome);
        adapter.testFinished(outcome, false);
        adapter.testFinished(outcome, false, now);

        verify(delegate, atLeastOnce()).testFinished(outcome);
        verify(delegate).testFinished(outcome, false);
        verify(delegate).testFinished(outcome, false, now);
    }

    @Test
    public void testFinished_nullOutcome_isDefensivelySkipped() {
        adapter.testFinished(null);
        adapter.testFinished(null, false, ZonedDateTime.now());

        verify(delegate, never()).testFinished((TestOutcome) null);
    }

    @Test
    public void testFailed_delegatesWithoutRethrowing() {
        TestOutcome outcome = mock(TestOutcome.class);
        Throwable cause = new RuntimeException("boom");

        // 监听器回调中抛出异常会中断 Serenity 事件分发，故适配器只转发、不再抛出
        adapter.testFailed(outcome, cause);

        verify(delegate).testFailed(outcome, cause);
    }

    // ---------- 步骤事件转发 ----------

    @Test
    public void stepEvents_delegateWithDescriptionsAndFailures() {
        ExecutedStepDescription step = mock(ExecutedStepDescription.class);
        when(step.getTitle()).thenReturn("my step");
        StepFailure failure = mock(StepFailure.class);
        when(failure.getException()).thenReturn(new RuntimeException("step boom"));
        List<ScreenshotAndHtmlSource> shots = List.of();
        ZonedDateTime now = ZonedDateTime.now();

        adapter.stepStarted(step);
        adapter.skippedStepStarted(step);
        adapter.stepFailed(failure);
        adapter.lastStepFailed(failure);
        adapter.stepFailed(failure, shots, false);
        adapter.stepFailed(failure, shots, false, now);

        verify(delegate).stepStarted(step);
        verify(delegate).skippedStepStarted(step);
        verify(delegate).stepFailed(failure);
        verify(delegate).lastStepFailed(failure);
        verify(delegate).stepFailed(failure, shots, false);
        verify(delegate).stepFailed(failure, shots, false, now);
    }

    @Test
    public void stepEvents_nullArguments_areDefensivelySkipped() {
        adapter.stepStarted(null);
        adapter.stepFailed(null);

        verify(delegate, never()).stepStarted(any(ExecutedStepDescription.class));
        verify(delegate, never()).stepFailed(any(StepFailure.class));
    }

    // ---------- 套件 / 数据驱动 / 截图事件转发 ----------

    @Test
    public void suiteEvents_delegateAndResetSuiteLevelDeduplication() {
        Story story = mock(Story.class);
        when(story.getName()).thenReturn("my story");

        adapter.testSuiteStarted(getClass());
        adapter.testSuiteStarted(story);
        adapter.testSuiteFinished();

        verify(delegate).testSuiteStarted(getClass());
        verify(delegate).testSuiteStarted(story);
        verify(delegate).testSuiteFinished();
    }

    @Test
    public void dataDrivenEvents_delegateToRegisteredListeners() {
        DataTable dataTable = mock(DataTable.class);
        when(dataTable.getRows()).thenReturn(List.of());
        Map<String, String> data = Map.of("user", "alice");

        adapter.useExamplesFrom(dataTable);
        adapter.addNewExamplesFrom(dataTable);
        adapter.exampleStarted(data);

        verify(delegate).useExamplesFrom(dataTable);
        verify(delegate).addNewExamplesFrom(dataTable);
        verify(delegate).exampleStarted(data);
    }

    @Test
    public void screenshotEvents_delegateToRegisteredListeners() {
        List<ScreenshotAndHtmlSource> shots = List.of();
        ZonedDateTime now = ZonedDateTime.now();

        adapter.takeScreenshots(shots);
        adapter.takeScreenshots(TestResult.SUCCESS, shots);
        adapter.stepFinished(shots);
        adapter.stepFinished(shots, now);

        verify(delegate).takeScreenshots(shots);
        verify(delegate).takeScreenshots(TestResult.SUCCESS, shots);
        verify(delegate).stepFinished(shots);
        verify(delegate).stepFinished(shots, now);
    }
}
