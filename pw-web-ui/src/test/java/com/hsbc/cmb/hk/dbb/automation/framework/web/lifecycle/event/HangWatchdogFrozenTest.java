package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * web 版看门狗冻结判定（去误报）契约：同栈集合连续 N 次采样不变才上报，且同一冻结剧集只报一次。
 */
public class HangWatchdogFrozenTest {

    @BeforeEach
    void reset() {
        HangWatchdog.resetFrozenState();
    }

    private static StackTraceElement[] frozenStack() {
        return new StackTraceElement[] { new StackTraceElement("com.hsbc.Foo", "block", "Foo.java", 42) };
    }

    private static StackTraceElement[] otherStack() {
        return new StackTraceElement[] { new StackTraceElement("com.hsbc.Bar", "run", "Bar.java", 7) };
    }

    @Test
    void frozenStackReportsOnceAfterThreshold() {
        assertFalse(HangWatchdog.recordStackSample(frozenStack()));
        assertFalse(HangWatchdog.recordStackSample(frozenStack()));
        assertTrue(HangWatchdog.recordStackSample(frozenStack()), "第 3 次相同栈应上报");
        assertFalse(HangWatchdog.recordStackSample(frozenStack()), "同冻结剧集不重复上报");
    }

    @Test
    void changingStackNeverReports() {
        for (int i = 0; i < 10; i++) {
            assertFalse(HangWatchdog.recordStackSample(i % 2 == 0 ? frozenStack() : otherStack()),
                    "栈在推进 ⇒ 永不误报");
        }
    }

    @Test
    void thawThenRefreezeReportsAgain() {
        HangWatchdog.recordStackSample(frozenStack());
        HangWatchdog.recordStackSample(frozenStack());
        assertTrue(HangWatchdog.recordStackSample(frozenStack()), "首次冻结上报");
        assertFalse(HangWatchdog.recordStackSample(frozenStack()), "同剧集不重复");
        HangWatchdog.recordStackSample(otherStack());                  // 解冻
        assertFalse(HangWatchdog.recordStackSample(otherStack()));
        assertTrue(HangWatchdog.recordStackSample(otherStack()), "重新冻结再次上报");
    }
}
