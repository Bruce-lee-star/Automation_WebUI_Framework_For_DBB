package com.hsbc.cmb.hk.dbb.automation.framework.web.core;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;

/**
 * Playwright / Browser 层获取 seam（DI 改造一期，WEB-P0-2）。
 *
 * <p>{@code Playwright} 与 {@code Browser} 同属浏览器进程生命周期层（Browser 由 Playwright 启动），
 * 二者强耦合故收口于同一接口；与 {@link ContextProvider} / {@link PageProvider} 共同由
 * {@link RuntimeProvider} 组合，供 {@code PlaywrightManager} 静态门面委托。
 *
 * @apiNote 仅框架测试注入使用；生产代码不得实现本接口，始终走默认实现。
 */
public interface BrowserProvider {

    /**
     * 获取当前线程 {@code Playwright} 实例（不存在时按需初始化）。
     *
     * @return Playwright 实例
     */
    Playwright getPlaywright();

    /**
     * 获取当前线程 {@code Browser} 实例（不存在时按需初始化）。
     *
     * @return Browser 实例
     */
    Browser getBrowser();
}
