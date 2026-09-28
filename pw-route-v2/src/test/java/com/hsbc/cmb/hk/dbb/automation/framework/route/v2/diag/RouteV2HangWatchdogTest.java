package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.diag;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * route2 临时诊断看门狗的最小行为验证（与 web 版 RenderTest 等价）。
 * 仅覆盖 arm/disarm 状态机、renderSample 格式、consumeHardHang 幂等；硬超时触发依赖真实时间，不在单测内。
 */
public class RouteV2HangWatchdogTest {

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
        assertTrue(s.contains("RouteV2-HangWatchdog"));
        assertTrue(s.contains("1234"));
        assertTrue(s.contains("业务线程"));
    }

    @Test
    public void consumeHardHang_initiallyNull() {
        assertNull(new HangWatchdog("t").consumeHardHang());
    }
}
