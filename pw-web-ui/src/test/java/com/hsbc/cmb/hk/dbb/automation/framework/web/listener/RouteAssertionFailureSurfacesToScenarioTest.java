package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import net.thucydides.core.steps.BaseStepListener;
import net.thucydides.core.steps.StepEventBus;
import net.thucydides.model.domain.TestOutcome;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F4 / R-18（P0）端到端印证：route 断言失败必须透出为<b>用例失败</b>，不可静默判 PASS（假绿）。
 *
 * <p>链路：route 侧 {@code MonitorHandler.assertAndRecord} / {@code ApiAssertion} 经
 * {@code ApiCaptureContext.recordAssertionFailure} 置 {@code hasAssertionFailures} 标志 →
 * 步骤结束时 {@link StepFailureAggregator#checkAndFailOnApiAssertions()} 经
 * {@link StepEventBus#testFailed} 标记失败并抛 {@link AssertionError}，使 Cucumber/JUnit4 判 FAIL。</p>
 *
 * <p>本测试在 web 模块内（不依赖 route 实现类，故用 {@code core.common.route} 接口桩模拟 route 侧）
 * 验证该链路末端：只要失败标志已落袋，{@code checkAndFailOnApiAssertions()} 必触发
 * {@code testFailed(AssertionError)} 且抛出 {@link AssertionError}。{@code AssertionFailureSurfaceTest}
 * 已验证前段（标志经 per-context → SHARED 兜底被解析到），本测试补完后段（解析到后必置用例失败）。</p>
 */
public class RouteAssertionFailureSurfacesToScenarioTest {

    /** 捕获 testFailed 事件的监听器（复用 Serenity 基类，仅覆盖测试方法）。 */
    private static final class CapturingListener extends BaseStepListener {
        final List<Throwable> failures = new ArrayList<>();

        CapturingListener() {
            super(new File("target/route-assertion-surface-test"));
        }

        @Override
        public void testFailed(TestOutcome result, Throwable throwable) {
            failures.add(throwable);
        }
    }

    /** 桩实现：仅 resolveFailureCapture() 返回含断言失败的上下文，其余方法无操作。 */
    private static final class FailingRouteLifecycle implements RouteLifecycle {
        private final CaptureContext failingContext = new FailingCaptureContext();

        @Override
        public CaptureContext resolveFailureCapture() {
            return failingContext;
        }

        @Override public void resetCaptureCurrent() { }
        @Override public void stopCapture() { }
        @Override public CaptureContext getCurrentCapture() { return failingContext; }
        @Override public void setMonitorScenario(String name) { }
        @Override public void clearMonitorScenario() { }
        @Override public void clearMonitorFeature() { }
        @Override public void resetAll() { }
        @Override public void clearContext(Object ctx) { }
        @Override public void clearAll() { }
        @Override public void clearDispatchedRoutes() { }
        @Override public void stopContextEngine(Object ctx) { }
        @Override public void stopAllContextEngines() { }
        @Override public void shutdownRouteEngine() { }
        @Override public String sanitizeUrl(String url) { return url; }
    }

    /** 桩上下文：标记存在断言失败，明细非空。 */
    private static final class FailingCaptureContext implements CaptureContext {
        @Override public void markStepStart() { }
        @Override public boolean hasAssertionFailures() { return true; }
        @Override public String buildFailureReport() {
            return "API Assertion Failures (1)\nSTATUS expected=200 actual=500";
        }
        @Override public String buildFailureDetails() {
            return "STATUS expected=200 actual=500";
        }
        @Override public int getActiveRequests() { return 0; }
        @Override public boolean awaitCompletion(long timeoutMs) { return true; }
    }

    @Test
    public void routeAssertionFailureMarksScenarioAsFailed() {
        CapturingListener listener = new CapturingListener();
        RouteLifecycle previous = RouteLifecycleRegistry.get();
        StepEventBus.getEventBus().registerListener(listener);
        ListenerGuard.clearForThread(); // 确保 apiFailureAlreadyHandled 守卫为 false，链路不被提前短路
        RouteLifecycleRegistry.register(new FailingRouteLifecycle());
        try {
            AssertionError thrown = null;
            try {
                StepFailureAggregator.checkAndFailOnApiAssertions();
            } catch (AssertionError e) {
                thrown = e;
            }

            // ① 必须抛出 AssertionError —— 异常沿 StepInterceptor → Cucumber → JUnit4 传播，IDE 正确标红
            assertTrue( thrown != null, "route 断言失败必须抛 AssertionError 使用例判 FAIL");
            // ② 必须经 StepEventBus.testFailed 标记到 Serenity 报告模型
            assertEquals( 1,  listener.failures.size(), "必须经 StepEventBus.testFailed 上报断言失败");
            Throwable reported = listener.failures.get(0);
            assertTrue( reported instanceof AssertionError, "上报的失败应为 AssertionError");
            assertFalse( reported.getMessage() == null || reported.getMessage().isEmpty(), "失败明细不得为空");
        } finally {
            StepEventBus.getEventBus().dropListener(listener);
            RouteLifecycleRegistry.register(previous); // 还原，避免污染其它 web 测试
            ListenerGuard.clearForThread();
        }
    }
}
