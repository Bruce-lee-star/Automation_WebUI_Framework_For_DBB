package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PatternBinder 与守护原语 SPI 的端到端真多态验证（不依赖真实浏览器）：
 * 注入 {@link StubGuardedDriverCall} 后，{@link PatternBinder#bind} 必须经注入原语执行（而非默认 daemon 实现）。
 */
public class PatternBinderGuardTest {

    @After
    public void reset() {
        GuardedDriverCallRegistry.reset();
    }

    @Test
    public void bindUsesInjectedGuard() {
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        RouteRuntime runtime = mock(RouteRuntime.class);

        StubGuardedDriverCall stub = new StubGuardedDriverCall();
        GuardedDriverCallRegistry.setInstance(stub);

        ApiSpec spec = ApiSpec.builder("/api/users/**", RouteCapability.MONITOR).build();
        // 只需触发一次绑定即可（断言锚定的是"注入的守护原语被调用"），故不接收返回值 ——
        // 原写法 `PatternBinder binder = ...` 从未使用该局部变量（SpotBugs DLS_DEAD_LOCAL_STORE）。
        PatternBinder.bind(ctx, spec, runtime);

        assertTrue("PatternBinder.bind 必须经由注入的守护原语执行（真多态全链路生效）", stub.callCount() == 1);
        GuardedDriverCallRegistry.reset();
    }
}
