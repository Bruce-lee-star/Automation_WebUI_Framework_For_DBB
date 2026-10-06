package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 卡住诊断看门狗：采样文本格式的确定性回归守卫（2026-09-26）。
 *
 * <p>只锁"文本里必须出现什么"——这是排障时真正被依赖的部分：用例标识、已运行时长、
 * 场景线程的<b>完整栈</b>（否则定位不到阻塞点）、以及关闭该采样的开关。
 * 不测定时行为（那需要真实计时，会引入 flaky）。
 */
public class HangWatchdogRenderTest {

    @Test
    // @DisplayName: "采样文本必须含：用例标识 + 已运行时长 + 场景线程完整栈 + 关闭开关"
    public void rendersScenarioThreadStackAndDisableHint() {
        Thread owner = Thread.currentThread();

        String text = HangWatchdog.renderSample(owner, "case-xyz", 123_456L, 60_000L);

        assertNotNull(text);
        assertTrue("必须有可 grep 的固定前缀", text.contains("[HangWatchdog]"));
        assertTrue("必须能看出是哪个用例卡住", text.contains("case-xyz"));
        assertTrue("必须给出已运行时长（判断是否真的卡住）", text.contains("123456"));
        assertTrue("必须指明被采样的场景线程名（并行时必须能区分线程）", text.contains("场景线程 『" + owner.getName() + "』"));
        assertTrue("必须包含场景线程的完整栈帧 —— 否则定位不到阻塞点", text.contains("\n    at "));
        assertTrue("必须自带关闭方式，便于现场快速止血", text.contains("hang.watchdog.interval.ms=0"));
        assertTrue("必须有结束标记，避免与后续日志粘连", text.contains("采样结束"));
    }

    @Test
    // @DisplayName: "用例标识为 null 时不得抛异常（收尾竞态/无 MDC 的兜底路径）"
    public void nullScenarioIdIsSafe() {
        String text = HangWatchdog.renderSample(Thread.currentThread(), null, 1L, 1L);

        assertNotNull(text);
        assertTrue("无标识时应显式标注未知，而不是打印 null", text.contains("(未知)"));
    }

    @Test
    // @DisplayName: "未武装时 isArmed() 为 false（不产生任何采样输出）"
    public void notArmedByDefault() {
        HangWatchdog.onScenarioEnd();

        assertTrue("解除武装后不得保持武装状态，否则会无意义刷屏", !HangWatchdog.isArmed());
    }
}
