package com.hsbc.cmb.hk.dbb.automation.framework.route.diag;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * route2 临时诊断看门狗的最小行为验证（与 web 版 RenderTest 等价）。
 * 仅覆盖 arm/disarm 状态机、renderSample 格式、consumeHardHang 幂等；硬超时触发依赖真实时间，不在单测内。
 */
public class RouteHangWatchdogTest {

    @Test
    public void arm_then_disarm_changesArmedState() {
        HangWatchdog wd = new HangWatchdog("t");
        wd.arm();
        assertTrue("armed after arm()", wd.isArmed());
        wd.disarm();
        assertFalse("disarmed after disarm()", wd.isArmed());
    }

    @Test
    public void renderSample_containsRuntimeMarkerAndElapsed() {
        String s = HangWatchdog.renderSample(Thread.currentThread(), 1234L, 60_000L);
        assertNotNull(s);
        assertTrue(s.contains("Route-HangWatchdog"));
        assertTrue(s.contains("1234"));
        assertTrue(s.contains("业务线程"));
    }

    @Test
    public void consumeHardHang_initiallyNull() {
        assertNull(new HangWatchdog("t").consumeHardHang());
    }

    private static StackTraceElement[] frozenStack() {
        return new StackTraceElement[] { new StackTraceElement("com.hsbc.Foo", "block", "Foo.java", 42) };
    }

    private static StackTraceElement[] otherStack() {
        return new StackTraceElement[] { new StackTraceElement("com.hsbc.Bar", "run", "Bar.java", 7) };
    }

    @Test
    public void frozenStackReportsOnceAfterThreshold() {
        HangWatchdog wd = new HangWatchdog("t");
        // 默认阈值 3：前 2 次不报，第 3 次报，同剧集后续不重复刷屏
        assertFalse(wd.recordStackSample(frozenStack()));
        assertFalse(wd.recordStackSample(frozenStack()));
        assertTrue("第 3 次相同栈应上报", wd.recordStackSample(frozenStack()));
        assertFalse("同冻结剧集不重复上报", wd.recordStackSample(frozenStack()));
    }

    @Test
    public void changingStackNeverReports() {
        HangWatchdog wd = new HangWatchdog("t");
        for (int i = 0; i < 10; i++) {
            assertFalse("栈在推进 ⇒ 永不误报", wd.recordStackSample(i % 2 == 0 ? frozenStack() : otherStack()));
        }
    }

    @Test
    public void thawThenRefreezeReportsAgain() {
        HangWatchdog wd = new HangWatchdog("t");
        wd.recordStackSample(frozenStack());
        wd.recordStackSample(frozenStack());
        assertTrue(wd.recordStackSample(frozenStack()));      // 首次冻结上报
        assertFalse(wd.recordStackSample(frozenStack()));     // 同剧集不重复
        wd.recordStackSample(otherStack());                   // 解冻
        assertFalse(wd.recordStackSample(otherStack()));
        assertTrue("重新冻结再次上报", wd.recordStackSample(otherStack()));
    }
}
