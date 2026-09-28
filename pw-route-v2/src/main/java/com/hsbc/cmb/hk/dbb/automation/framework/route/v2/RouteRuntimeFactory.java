package com.hsbc.cmb.hk.dbb.automation.framework.route.v2;

import com.microsoft.playwright.BrowserContext;

import java.util.concurrent.ConcurrentMap;

/**
 * Route V2 运行时工厂（SPI 契约）。
 *
 * <p>创建收口：{@link RouteEngine2} 不再直接 new 运行时，经 {@link RouteRuntimeFactoryRegistry}
 * 解析本接口，使运行时整体可经 SPI/factory 替换（真多态 + 测试可注入替身）。
 */
public interface RouteRuntimeFactory {

    /**
     * 为指定 context 创建运行时。
     *
     * @param context       非 null 的 BrowserContext
     * @param config        运行时配置（非 null）
     * @param ownerRegistry 全局运行时注册表（context→runtime 映射），用于关闭时自检移除
     * @return 非 null 的 {@link RouteRuntime}
     * @throws NullPointerException 若 context / config 为 null
     */
    RouteRuntime create(BrowserContext context, RouteV2Config config,
                        ConcurrentMap<BrowserContext, RouteRuntime> ownerRegistry);
}
