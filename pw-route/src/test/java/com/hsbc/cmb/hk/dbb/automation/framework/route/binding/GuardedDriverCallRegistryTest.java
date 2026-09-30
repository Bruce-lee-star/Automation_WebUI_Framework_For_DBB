package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 守护原语 SPI 收口验证（不依赖真实浏览器）：
 * ① 默认解析到 {@link GuardedDriverCallImpl}（零回归）；
 * ② setInstance 注入替身 + reset 复位；
 * ③ 注入替身后 guarded 真正执行 action（真多态）。
 */
public class GuardedDriverCallRegistryTest {

    @After
    public void reset() {
        GuardedDriverCallRegistry.reset();
    }

    @Test
    public void defaultResolvesToDefaultImpl() {
        GuardedDriverCallRegistry.reset();
        GuardedDriverCall g = GuardedDriverCallRegistry.instance();
        assertNotNull(g);
        assertTrue("默认必须解析到 GuardedDriverCallImpl（零回归）", g instanceof GuardedDriverCallImpl);
    }

    @Test
    public void setInstanceOverridesAndResetRestores() {
        StubGuardedDriverCall stub = new StubGuardedDriverCall();
        GuardedDriverCallRegistry.setInstance(stub);
        assertSame(stub, GuardedDriverCallRegistry.instance());

        GuardedDriverCallRegistry.reset();
        assertTrue("reset 必须恢复默认实现",
                GuardedDriverCallRegistry.instance() instanceof GuardedDriverCallImpl);
    }

    @Test
    public void injectedStubExecutesAction() throws Exception {
        StubGuardedDriverCall stub = new StubGuardedDriverCall();
        GuardedDriverCallRegistry.setInstance(stub);

        String result = GuardedDriverCallRegistry.instance().guarded("test", 1000,
                GuardedDriverCall.OnTimeout.FAIL_FAST, () -> "ok");
        assertEquals("ok", result);
        assertTrue("注入的守护原语必须实际执行 action", stub.callCount() == 1);

        GuardedDriverCallRegistry.reset();
    }
}
