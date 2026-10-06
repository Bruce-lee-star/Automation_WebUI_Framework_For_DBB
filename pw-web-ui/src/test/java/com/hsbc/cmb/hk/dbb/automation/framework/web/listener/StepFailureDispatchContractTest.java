package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import net.thucydides.model.screenshots.ScreenshotAndHtmlSource;
import net.thucydides.model.steps.ExecutedStepDescription;
import net.thucydides.model.steps.StepFailure;
import net.thucydides.model.steps.StepListener;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * N-21 契约：适配器向 delegate 的<b>失败派发不得重复</b>（doc 21 候选 N-21）。
 *
 * <p><b>背景（经字节码取证）</b>：Serenity 的 {@code StepListener} 接口里，
 * {@code stepFailed(failure, screenshots, flag)} 是 <b>default 方法</b>，其实现会<b>向上</b>转调 4 参重载
 * {@code stepFailed(failure, screenshots, flag, ZonedDateTime.now())}。而适配器<b>自己也重写并派发</b>了
 * 4 参版本 —— 若适配器的 3 参重写再调用 {@code StepListener.super}，同一次失败就会经两条路径落到
 * 每个 delegate 上（delegate 只实现 4 参时，两遍落在同一个回调）。</p>
 *
 * <p><b>为何用动态代理而不是 Mockito mock 作为 delegate</b>：动态代理会把<b>每一次</b>调用都交给
 * {@link InvocationHandler}，且<b>不会自行执行接口 default 方法</b> —— 因此计到的正是
 * "适配器实际派发了几次"这一事实；用普通 mock 或手写实现都会因 default 链而混淆计数口径。</p>
 *
 * <p><b>可证伪性</b>：把适配器 3 参 {@code stepFailed} 里的 {@code StepListener.super} 调用加回去，
 * 本用例立即由 1 变 2 而失败（这正是修复前 {@code ThucydidesStepsListenerAdapterTest} 的
 * {@code times(1)} 偶发 {@code TooManyActualInvocations} 的同一现象）。</p>
 */
public class StepFailureDispatchContractTest {

    private final ThucydidesStepsListenerAdapter adapter = new ThucydidesStepsListenerAdapter();

    @After
    public void tearDown() {
        // delegateListeners 是静态共享列表：用例结束必须清空，避免污染其它用例
        adapter.clearDelegateListeners();
    }

    @Test
    // @DisplayName: "N-21：一次【3 参】stepFailed 事件 → 每个 delegate 只收到一次失败派发"
    public void threeArgStepFailedDispatchesOnce() {
        adapter.clearDelegateListeners();
        AtomicInteger failedCalls = new AtomicInteger();
        adapter.addDelegateListener(recordingListener(failedCalls));

        StepFailure failure = newStepFailure("3-arg boom");
        adapter.stepFailed(failure, List.<ScreenshotAndHtmlSource>of(), false);

        assertEquals("同一次失败只能派发一次：接口 default 已把 3 参【向上】转调 4 参，"
                        + "而 4 参版本本类自己也派发 → 再调 super 必然重复（N-21）", 1, failedCalls.get());
    }

    @Test
    // @DisplayName: "N-21（回归守卫）：一次【4 参】stepFailed 事件仍只派发一次"
    public void fourArgStepFailedDispatchesOnce() {
        adapter.clearDelegateListeners();
        AtomicInteger failedCalls = new AtomicInteger();
        adapter.addDelegateListener(recordingListener(failedCalls));

        StepFailure failure = newStepFailure("4-arg boom");
        adapter.stepFailed(failure, List.<ScreenshotAndHtmlSource>of(), false, ZonedDateTime.now());

        assertEquals("4 参重写本就只派发一次（不调 super）；此断言防止将来有人给它也加上 super 转调", 1, failedCalls.get());
    }

    @Test
    // @DisplayName: "N-21：1 参 stepFailed 亦只派发一次（Serenity 的 lastStepFailed 会复用它，不得再叠一层）"
    public void oneArgStepFailedDispatchesOnce() {
        adapter.clearDelegateListeners();
        AtomicInteger failedCalls = new AtomicInteger();
        adapter.addDelegateListener(recordingListener(failedCalls));

        adapter.stepFailed(newStepFailure("1-arg boom"));

        assertEquals("1 参重写只派发一次，不得叠加其它路径", 1, failedCalls.get());
    }

    // ---------- 测试脚手架 ----------

    private static StepFailure newStepFailure(String message) {
        return new StepFailure(mock(ExecutedStepDescription.class), new RuntimeException(message));
    }

    /**
     * 记录型 delegate：统计 {@code stepFailed} 各重载被调用的<b>总次数</b>。
     *
     * @param counter 计数器（每个重载调用一次即 +1）
     */
    private static StepListener recordingListener(AtomicInteger counter) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("stepFailed".equals(method.getName())) {
                counter.incrementAndGet();
            }
            // StepListener 全部方法返回 void（已按 javap 核对），故统一返回 null
            return null;
        };
        return (StepListener) Proxy.newProxyInstance(
                StepFailureDispatchContractTest.class.getClassLoader(),
                new Class<?>[]{StepListener.class},
                handler);
    }
}
