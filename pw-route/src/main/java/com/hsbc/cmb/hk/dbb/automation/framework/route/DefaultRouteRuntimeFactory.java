package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.microsoft.playwright.BrowserContext;

import java.util.Objects;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认运行时工厂：委托 {@link RouteRuntimeImpl#install}（行为零回归）。
 *
 * <p>经 {@code META-INF/services} 注册为 {@link RouteRuntimeFactory} 的 SPI 默认实现；
 * classpath 无显式注册时由 {@link RouteRuntimeFactoryRegistry} 回退到本类。
 */
public final class DefaultRouteRuntimeFactory implements RouteRuntimeFactory {

    @Override
    public RouteRuntime create(BrowserContext context, RouteConfig config,
                               ConcurrentMap<BrowserContext, RouteRuntime> ownerRegistry) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(config, "config");
        return RouteRuntimeImpl.install(context, config, ownerRegistry);
    }
}
