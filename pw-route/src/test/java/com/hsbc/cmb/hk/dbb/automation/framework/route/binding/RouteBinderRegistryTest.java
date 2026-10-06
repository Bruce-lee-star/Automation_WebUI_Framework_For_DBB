package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link RouteBinderRegistry} SPI 解析链路验证（纯单元，不启浏览器 / 驱动）。
 *
 * <p>覆盖三条路径：默认 SPI 解析、{@link RouteBinderRegistry#setInstance} 注入覆盖、
 * {@link RouteBinderRegistry#reset} 复位后重新经 SPI 解析回默认实现。</p>
 */
public class RouteBinderRegistryTest {

    @After
    public void tearDown() {
        RouteBinderRegistry.reset();
    }

    @Test
    public void instanceResolvesToDefaultImplementation() {
        RouteBinder binder = RouteBinderRegistry.instance();
        assertNotNull("SPI 解析应返回非 null 绑定实现", binder);
        // Class.isInstance 替代 instanceof：语义等价，且满足 SpotBugs JUA_DONT_ASSERT_INSTANCEOF_IN_TESTS
        assertTrue("默认应为 DefaultRouteBinder（SPI 注册或 fail-safe 回退）",
                DefaultRouteBinder.class.isInstance(binder));
    }

    @Test
    public void setInstanceOverridesAndResetRestoresDefault() {
        RouteBinder stub = (ctx, spec, rt) -> null;
        RouteBinderRegistry.setInstance(stub);
        assertSame("注入实现应被 instance() 返回", stub, RouteBinderRegistry.instance());

        RouteBinderRegistry.reset();
        assertTrue("reset 后应经 SPI 重新解析回默认实现",
                DefaultRouteBinder.class.isInstance(RouteBinderRegistry.instance()));
    }
}
