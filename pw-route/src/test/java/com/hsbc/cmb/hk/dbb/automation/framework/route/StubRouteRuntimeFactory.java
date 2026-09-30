package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.microsoft.playwright.BrowserContext;

import java.util.concurrent.ConcurrentMap;

/**
 * 测试替身工厂：返回 {@link StubRouteRuntime}（不依赖真实浏览器）。
 */
public final class StubRouteRuntimeFactory implements RouteRuntimeFactory {

    @Override
    public RouteRuntime create(BrowserContext context, RouteConfig config,
                                ConcurrentMap<BrowserContext, RouteRuntime> ownerRegistry) {
        return new StubRouteRuntime();
    }
}
