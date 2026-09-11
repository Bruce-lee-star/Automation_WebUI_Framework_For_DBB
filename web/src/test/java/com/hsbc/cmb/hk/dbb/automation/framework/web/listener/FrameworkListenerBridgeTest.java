package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4-1 官方 Adapter（{@link FrameworkListenerBridge}）契约测试。
 *
 * <p>核心保证：
 * <ul>
 *   <li>五个生命周期回调都能转发到 {@link FrameworkListener}；</li>
 *   <li><b>异常隔离</b>：某个监听器抛异常不影响其它监听器，也绝不中断主流程；</li>
 *   <li>非 {@link FrameworkListener} 的注册对象被忽略（不因类型误判而报错）；</li>
 *   <li><b>默认方法</b>：业务可只实现关心的回调，其余为空实现。</li>
 * </ul>
 */
public class FrameworkListenerBridgeTest {

    /** 记录全部回调的监听器（同时证明：只实现关心的方法即可）。 */
    private static final class RecordingListener implements FrameworkListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void beforeScenario(String scenarioName) {
            events.add("beforeScenario:" + scenarioName);
        }

        @Override
        public void afterScenario(String scenarioName, boolean failed) {
            events.add("afterScenario:" + scenarioName + ":" + failed);
        }

        @Override
        public void beforeStep(String stepName) {
            events.add("beforeStep:" + stepName);
        }

        @Override
        public void afterStep(String stepName) {
            events.add("afterStep:" + stepName);
        }

        @Override
        public void onFailure(String scenarioName, Throwable cause) {
            events.add("onFailure:" + scenarioName + ":" + (cause == null ? "null" : cause.getMessage()));
        }
    }

    /** 故意抛异常的监听器，用于验证异常隔离。 */
    private static final class ThrowingListener implements FrameworkListener {
        boolean called;

        @Override
        public void beforeScenario(String scenarioName) {
            called = true;
            throw new RuntimeException("listener boom");
        }
    }

    @AfterEach
    public void tearDown() {
        ListenerRegistry.cleanup();
    }

    /** 五个回调全部转发。 */
    @Test
    public void allLifecycleCallbacksAreForwarded() {
        RecordingListener listener = new RecordingListener();
        ListenerRegistry.registerListener(listener);

        FrameworkListenerBridge.beforeScenario("scenario-1");
        FrameworkListenerBridge.beforeStep("step-A");
        FrameworkListenerBridge.afterStep("step-A");
        FrameworkListenerBridge.onFailure("scenario-1", new IllegalStateException("bad"));
        FrameworkListenerBridge.afterScenario("scenario-1", true);

        assertEquals(5, listener.events.size());
        assertTrue(listener.events.contains("beforeScenario:scenario-1"));
        assertTrue(listener.events.contains("beforeStep:step-A"));
        assertTrue(listener.events.contains("afterStep:step-A"));
        assertTrue(listener.events.contains("onFailure:scenario-1:bad"));
        assertTrue(listener.events.contains("afterScenario:scenario-1:true"));
    }

    /** 异常隔离：抛异常的监听器不影响其它监听器，且不中断主流程。 */
    @Test
    public void throwingListenerDoesNotBreakOthers() {
        ThrowingListener bad = new ThrowingListener();
        RecordingListener good = new RecordingListener();
        ListenerRegistry.registerListener(bad);
        ListenerRegistry.registerListener(good);

        FrameworkListenerBridge.beforeScenario("scenario-2"); // 不得抛出

        assertTrue(bad.called, "抛异常的监听器应确实被调用");
        assertTrue(good.events.contains("beforeScenario:scenario-2"), "其余监听器仍必须收到通知");
    }

    /** 非 FrameworkListener 的注册对象被安全忽略。 */
    @Test
    public void nonFrameworkListenersAreIgnored() {
        ListenerRegistry.registerListener("i-am-not-a-listener");
        FrameworkListenerBridge.beforeScenario("scenario-3"); // 不得抛出
    }

    /** 默认方法：只实现一个回调的实现类可正常编译并被分发。 */
    @Test
    public void defaultMethodsAllowPartialImplementation() {
        final boolean[] called = {false};
        FrameworkListener partial = new FrameworkListener() {
            @Override
            public void afterStep(String stepName) {
                called[0] = true;
            }
        };
        ListenerRegistry.registerListener(partial);

        FrameworkListenerBridge.beforeScenario("x"); // 未实现 → 默认空实现，不报错
        FrameworkListenerBridge.afterStep("step-B");

        assertTrue(called[0], "实现的回调应被调用");
    }
}
