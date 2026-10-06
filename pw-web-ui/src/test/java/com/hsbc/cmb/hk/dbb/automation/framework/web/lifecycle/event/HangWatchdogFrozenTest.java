package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * web 版看门狗冻结判定（去误报）契约：同栈集合连续 N 次采样不变才上报，且同一冻结剧集只报一次。
 */
public class HangWatchdogFrozenTest {

    @Before
    public void reset() {
        HangWatchdog.resetFrozenState();
    }

    private static StackTraceElement[] frozenStack() {
        return new StackTraceElement[] { new StackTraceElement("com.hsbc.Foo", "block", "Foo.java", 42) };
    }

    private static StackTraceElement[] otherStack() {
        return new StackTraceElement[] { new StackTraceElement("com.hsbc.Bar", "run", "Bar.java", 7) };
    }

    @Test
    public void frozenStackReportsOnceAfterThreshold() {
        assertFalse(HangWatchdog.recordStackSample(frozenStack()));
        assertFalse(HangWatchdog.recordStackSample(frozenStack()));
        assertTrue("第 3 次相同栈应上报", HangWatchdog.recordStackSample(frozenStack()));
        assertFalse("同冻结剧集不重复上报", HangWatchdog.recordStackSample(frozenStack()));
    }

    @Test
    public void changingStackNeverReports() {
        for (int i = 0; i < 10; i++) {
            assertFalse("栈在推进 ⇒ 永不误报", HangWatchdog.recordStackSample(i % 2 == 0 ? frozenStack() : otherStack()));
        }
    }

    @Test
    public void thawThenRefreezeReportsAgain() {
        HangWatchdog.recordStackSample(frozenStack());
        HangWatchdog.recordStackSample(frozenStack());
        assertTrue("首次冻结上报", HangWatchdog.recordStackSample(frozenStack()));
        assertFalse("同剧集不重复", HangWatchdog.recordStackSample(frozenStack()));
        HangWatchdog.recordStackSample(otherStack());                  // 解冻
        assertFalse(HangWatchdog.recordStackSample(otherStack()));
        assertTrue("重新冻结再次上报", HangWatchdog.recordStackSample(otherStack()));
    }
}
