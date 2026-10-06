package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionFailure;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionProbe;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionRegistry;
import net.thucydides.core.steps.BaseStepListener;
import net.thucydides.core.steps.StepEventBus;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P0 端到端印证：Route V2 MONITOR 断言失败必须透出为<b>用例失败</b>，不可静默判 PASS（假绿）。
 *
 * <p>链路：V2 {@code MonitorSink} 结算失败（status/body/超时定案）→ {@code RouteAssertionProbeImpl}
 * 经 {@link RouteAssertionRegistry} 暴露 → 步骤结束时
 * {@link StepFailureAggregator#checkAndFailOnRouteAssertions()} 抛 {@link AssertionError}
 * （沿 StepInterceptor → Cucumber → JUnit4 传播，IDE 正确标红）；用例收尾
 * {@link StepFailureAggregator#checkAndMarkRouteAssertionFailures(TestOutcome)} 兜底置 FAILURE。
 *
 * <p>本测试在 web 模块内（不依赖 route-v2 实现类，用 registry 注册桩探针）验证链路末端：
 * 消费式语义保证两条上报路径天然防重（第二次调用为空）、探针未注册时安全跳过。
 */
public class RouteAssertionFailureSurfacesToScenarioTest {

    /** 捕获 testFailed 事件的监听器（复用 Serenity 基类，仅覆盖测试方法）。 */
    private static final class CapturingListener extends BaseStepListener {
        final List<Throwable> failures = new ArrayList<>();

        CapturingListener() {
            super(new File("target/routev2-assertion-surface-test"));
        }

        @Override
        public void testFailed(TestOutcome result, Throwable throwable) {
            failures.add(throwable);
        }
    }

    /** 桩探针：首次调用返回一条失败，之后消费为空（复刻 v2 消费式语义）。 */
    private static final class FailingProbe implements RouteAssertionProbe {
        private boolean consumed;

        @Override
        public List<RouteAssertionFailure> drainAndResolveFailures() {
            if (consumed) {
                return List.of();
            }
            consumed = true;
            return List.of(new RouteAssertionFailure(
                    "/api/login", "POST", "https://host/api/login",
                    200, 500, List.of(), false, 60_000L));
        }
    }

    @Test
    public void routeV2AssertionFailureFailsStepAndIsIdempotent() {
        CapturingListener listener = new CapturingListener();
        StepEventBus.getEventBus().registerListener(listener);
        RouteAssertionRegistry.register(new FailingProbe());
        try {
            // ① 步骤结束路径：必须抛 AssertionError
            AssertionError thrown = null;
            try {
                StepFailureAggregator.checkAndFailOnRouteAssertions();
            } catch (AssertionError e) {
                thrown = e;
            }
            assertTrue("V2 断言失败必须抛 AssertionError 使用例判 FAIL", thrown != null);
            assertFalse("失败明细不得为空", thrown.getMessage() == null || thrown.getMessage().isEmpty());
            assertTrue("明细必须含失败规则", thrown.getMessage().contains("/api/login"));

            // ② 必须经 StepEventBus.testFailed 上报
            assertEquals("必须经 StepEventBus.testFailed 上报断言失败", 1, listener.failures.size());
            assertTrue(listener.failures.get(0) instanceof AssertionError);

            // ③ 消费式幂等：第二次调用（同一场景后续步骤收尾）不得再抛
            AssertionError second = null;
            try {
                StepFailureAggregator.checkAndFailOnRouteAssertions();
            } catch (AssertionError e) {
                second = e;
            }
            assertNull("消费式语义：失败取走后不得重复抛错", second);
        } finally {
            StepEventBus.getEventBus().dropListener(listener);
            RouteAssertionRegistry.clear();
        }
    }

    @Test
    public void routeV2AssertionFailureMarksScenarioResultAsFailed() {
        TestOutcome outcome = new TestOutcome("demo scenario");
        RouteAssertionRegistry.register(new FailingProbe());
        try {
            StepFailureAggregator.checkAndMarkRouteAssertionFailures(outcome);
            assertEquals("用例收尾必须兜底标记 FAILURE", TestResult.FAILURE, outcome.getResult());
        } finally {
            RouteAssertionRegistry.clear();
        }
    }

    @Test
    public void absentProbeSkipsSafely() {
        RouteAssertionRegistry.clear();
        // 未注册探针：两条路径都必须安全跳过（不抛、不标记）
        StepFailureAggregator.checkAndFailOnRouteAssertions();
        TestOutcome outcome = new TestOutcome("demo scenario");
        StepFailureAggregator.checkAndMarkRouteAssertionFailures(outcome);
        assertNotEquals("无探针不得误标 FAILURE", TestResult.FAILURE, outcome.getResult());
    }
}
