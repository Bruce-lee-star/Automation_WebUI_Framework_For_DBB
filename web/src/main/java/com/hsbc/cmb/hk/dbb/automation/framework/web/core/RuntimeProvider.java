package com.hsbc.cmb.hk.dbb.automation.framework.web.core;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * web 运行时对象获取 seam（DI 改造一期，WEB-P0-2）。
 *
 * <p>聚合 Browser / Context / Page / Playwright 获取能力于单接口，供
 * {@code PlaywrightManager} 静态门面委托。生产路径走
 * {@code DefaultRuntimeProvider}（真实逻辑），单测经
 * {@code PlaywrightManager.setProvider(...)} 注入 mock 以脱离真实浏览器。
 *
 * <p>由评审要求的 {@link BrowserProvider} + {@link ContextProvider} + {@link PageProvider}
 * 三接口组合而成：既保留细粒度 seam 语义，又维持单入口
 * {@code setProvider(RuntimeProvider)} 以简化注入与复位（避免三 setter 的状态协调）。
 *
 * @apiNote 仅框架测试注入使用；生产代码不得实现本接口或调用 {@code setProvider}，始终走默认实现。
 */
public interface RuntimeProvider extends BrowserProvider, ContextProvider, PageProvider {
    @Override
    Playwright getPlaywright();

    @Override
    Browser getBrowser();

    @Override
    BrowserContext getContext();

    @Override
    Page getPage();
}
