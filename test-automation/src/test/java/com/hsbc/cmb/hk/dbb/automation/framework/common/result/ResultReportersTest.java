package com.hsbc.cmb.hk.dbb.automation.framework.common.result;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4-2 {@link ResultReporters} 契约测试。
 *
 * <p>核心保证（"换报告引擎不需重写"的接缝）：
 * <ul>
 *   <li>结果广播到所有已注册实现；</li>
 *   <li><b>异常隔离</b>：某实现抛错不影响其它实现，也不中断主流程；</li>
 *   <li>无实现时静默 no-op（纯单测 / 非报告环境下不报错）；</li>
 *   <li>重复注册同一实例被忽略。</li>
 * </ul>
 */
public class ResultReportersTest {

    /** 记录型上报实现。 */
    private static final class RecordingReporter implements ResultReporter {
        final List<String> events = new ArrayList<>();

        @Override
        public void reportStep(StepResult step) {
            events.add("step:" + step.getTitle() + ":" + step.getResult());
        }

        @Override
        public void reportScenario(String scenarioName, TestResult result, long durationMs) {
            events.add("scenario:" + scenarioName + ":" + result);
        }
    }

    /** 永远抛异常的上报实现。 */
    private static final class ThrowingReporter implements ResultReporter {
        boolean called;

        @Override
        public void reportStep(StepResult step) {
            called = true;
            throw new RuntimeException("reporter boom");
        }

        @Override
        public void reportScenario(String scenarioName, TestResult result, long durationMs) {
            called = true;
            throw new RuntimeException("reporter boom");
        }
    }

    @BeforeEach
    @AfterEach
    public void resetReporters() {
        // 前后都清空：避免 SerenityReporter 自注册影响断言，也不污染其它用例
        ResultReporters.clear();
    }

    /** 广播到所有已注册实现。 */
    @Test
    public void broadcastsToAllRegisteredReporters() {
        RecordingReporter a = new RecordingReporter();
        RecordingReporter b = new RecordingReporter();
        ResultReporters.register(a);
        ResultReporters.register(b);
        assertEquals(2, ResultReporters.size());

        ResultReporters.reportStep(StepResult.of("step-1", TestResult.SUCCESS, 0L, 10L));
        ResultReporters.reportScenario("scenario-1", TestResult.FAILURE, 100L);

        assertTrue(a.events.contains("step:step-1:SUCCESS"));
        assertTrue(a.events.contains("scenario:scenario-1:FAILURE"));
        assertTrue(b.events.contains("step:step-1:SUCCESS"));
        assertTrue(b.events.contains("scenario:scenario-1:FAILURE"));
    }

    /** 异常隔离：抛错的实现不影响其它实现，也不中断主流程。 */
    @Test
    public void throwingReporterDoesNotBreakOthers() {
        ThrowingReporter bad = new ThrowingReporter();
        RecordingReporter good = new RecordingReporter();
        ResultReporters.register(bad);
        ResultReporters.register(good);

        ResultReporters.reportScenario("scenario-2", TestResult.ERROR, 5L); // 不得抛出

        assertTrue(bad.called, "抛错的实现应确实被调用");
        assertTrue(good.events.contains("scenario:scenario-2:ERROR"), "其它实现仍必须收到结果");
    }

    /** 无实现时静默 no-op。 */
    @Test
    public void noReportersIsSilentNoOp() {
        ResultReporters.reportStep(StepResult.of("step", TestResult.SUCCESS, 0L, 1L));
        ResultReporters.reportScenario("scenario", TestResult.SUCCESS, 1L);
        // 能走到这里即通过（无异常）
    }

    /** 重复注册与 null 注册被忽略。 */
    @Test
    public void duplicateAndNullRegistrationAreIgnored() {
        RecordingReporter reporter = new RecordingReporter();
        ResultReporters.register(reporter);
        ResultReporters.register(reporter);
        ResultReporters.register(null);
        assertEquals(1, ResultReporters.size());
    }

    /** 注销后不再收到结果。 */
    @Test
    public void unregisterStopsDelivery() {
        RecordingReporter reporter = new RecordingReporter();
        ResultReporters.register(reporter);
        ResultReporters.unregister(reporter);

        ResultReporters.reportScenario("scenario-3", TestResult.SUCCESS, 1L);

        assertFalse(reporter.events.contains("scenario:scenario-3:SUCCESS"), "注销后不应再收到结果");
    }
}
