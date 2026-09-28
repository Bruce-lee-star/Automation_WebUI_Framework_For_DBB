package com.hsbc.cmb.hk.dbb.automation.framework.route.v2;

import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 工厂 SPI 收口验证（不依赖真实浏览器）：
 * <ul>
 *   <li>① 默认解析到 {@link DefaultRouteRuntimeFactory}（零回归）；</li>
 *   <li>② {@link RouteRuntimeFactoryRegistry#setInstance} 注入替身工厂 + {@link RouteRuntimeFactoryRegistry#reset} 复位；</li>
 *   <li>③ 引擎经注入工厂创建运行时 → 返回替身（真多态全链路生效）。</li>
 * </ul>
 */
public class RouteRuntimeFactoryRegistryTest {

    @After
    public void reset() {
        RouteRuntimeFactoryRegistry.reset();
    }

    @Test
    public void defaultResolvesToDefaultFactory() {
        RouteRuntimeFactoryRegistry.reset();
        RouteRuntimeFactory f = RouteRuntimeFactoryRegistry.instance();
        assertNotNull(f);
        assertTrue("默认必须解析到 DefaultRouteRuntimeFactory（零回归）", f instanceof DefaultRouteRuntimeFactory);
    }

    @Test
    public void setInstanceOverridesAndResetRestores() {
        RouteRuntimeFactory stub = new StubRouteRuntimeFactory();
        RouteRuntimeFactoryRegistry.setInstance(stub);
        assertSame(stub, RouteRuntimeFactoryRegistry.instance());

        RouteRuntimeFactoryRegistry.reset();
        assertTrue("reset 必须恢复默认工厂",
                RouteRuntimeFactoryRegistry.instance() instanceof DefaultRouteRuntimeFactory);
    }

    @Test
    public void engineUsesInjectedFactoryProducingStubRuntime() {
        BrowserContext ctx = mock(BrowserContext.class);
        RouteRuntimeFactoryRegistry.setInstance(new StubRouteRuntimeFactory());

        RouteRuntime rt = RouteEngine2.runtimeOf(ctx);
        assertTrue("引擎必须经由注入工厂创建运行时（真多态全链路生效）", rt instanceof StubRouteRuntime);

        RouteEngine2.shutdown(ctx);
        RouteRuntimeFactoryRegistry.reset();
    }
}
