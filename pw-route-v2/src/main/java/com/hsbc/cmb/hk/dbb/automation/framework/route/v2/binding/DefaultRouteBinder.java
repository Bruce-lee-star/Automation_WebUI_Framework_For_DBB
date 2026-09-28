package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.RouteRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.microsoft.playwright.BrowserContext;

/**
 * {@link RouteBinder} 的默认实现（经 {@code META-INF/services} 注册到 {@link RouteBinderRegistry}）。
 *
 * <p>职责单一：委托 {@link PatternBinder#bind} 静态工厂创建并注册绑定；自身无状态、线程安全，
 * 可被 {@link RouteBinderRegistry#instance()} 并发调用。classpath 无 SPI 注册或加载失败时，
 * {@link RouteBinderRegistry#resolve} 回退本类，行为等价于默认开启（零回归）。</p>
 *
 * <p>将来若需替换绑定策略（如测试 Mock、不同驱动绑定方式），只需提供另一 {@link RouteBinder}
 * 实现并在 {@code META-INF/services} 登记，无需改动 {@link RouteRuntime}。</p>
 */
public final class DefaultRouteBinder implements RouteBinder {

    @Override
    public PatternBinder bind(BrowserContext context, ApiSpec spec, RouteRuntime runtime) {
        return PatternBinder.bind(context, spec, runtime);
    }
}
