package com.hsbc.cmb.hk.dbb.automation.framework.web.config;

import net.thucydides.core.steps.BaseStepListener;
import net.thucydides.core.steps.StepEventBus;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AutoBrowser tags 读取（bus 语义 + 兜底注册）回归守卫（2026-09-26，A1 + A2）。
 *
 * <p><b>被守卫的真实缺陷</b>：旧实现从 {@code StepEventBus.getEventBus()}（per-thread ThreadLocal，
 * 测试/glue 线程上是空 bus）读 scenario tags，并在读不到时"自动注册一个空 BaseStepListener"兜底。
 * 后果：{@code getBaseStepListener()} 不再抛错，但 {@code getCurrentTestOutcome()} 恒为 null →
 * tags <b>静默为空</b> → {@code @firefox}/{@code @edge} 覆盖永久失效且<b>无任何告警</b>。
 *
 * <p>本用例经 {@code readScenarioTags(bus, ...)} 接缝注入<b>受控 bus</b>（Mockito 替身），
 * 完全绕开 JUnit5 下 Serenity 扩展会自注册 listener 的全局状态干扰，因而完全确定性。
 */
class AutoBrowserProcessorTagBusTest {

    @Test
    @DisplayName("A1+A2 守卫：bus 未就绪时返回空 tags，且不得注入空 listener、不得用会抛异常的 getter 探测")
    void mustNotInjectFallbackListenerNorProbeByThrowingGetter() {
        StepEventBus bus = mock(StepEventBus.class);
        when(bus.isBaseStepListenerRegistered()).thenReturn(false);

        String[] tags = AutoBrowserProcessor.readScenarioTags(bus, false);

        assertArrayEquals(new String[0], tags,
                "bus 未就绪时必须原样返回空数组（由下一个 step 自然重试）");
        verify(bus, never()).registerListener(any());
        verify(bus, never()).getBaseStepListener();
    }

    @Test
    @DisplayName("A1 守卫：bus 就绪时应能从该 bus 读到 scenario 标签（tags 不再恒为空）")
    void readsScenarioTagsFromGivenBus() {
        StepEventBus bus = mock(StepEventBus.class);
        BaseStepListener listener = mock(BaseStepListener.class);
        TestOutcome outcome = mock(TestOutcome.class);

        Set<TestTag> declared = new LinkedHashSet<>();
        declared.add(TestTag.withValue("firefox"));
        declared.add(TestTag.withValue("test"));

        when(bus.isBaseStepListenerRegistered()).thenReturn(true);
        when(bus.getBaseStepListener()).thenReturn(listener);
        when(listener.getCurrentTestOutcome()).thenReturn(outcome);
        when(outcome.getTags()).thenReturn(declared);

        String[] resolved = AutoBrowserProcessor.readScenarioTags(bus, false);

        assertArrayEquals(new String[]{"firefox", "test"}, resolved,
                "必须从传入的 bus 解出 scenario 标签（旧实现恒为空 → 覆盖永久失效）");
        assertNotNull(BrowserOverrideManager.extractBrowserFromTags(resolved),
                "解出的标签应可被识别为浏览器标签（firefox）——证明标签链路真正打通");
    }
}
