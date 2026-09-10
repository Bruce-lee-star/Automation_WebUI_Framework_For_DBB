package com.hsbc.cmb.hk.dbb.automation.framework.web.core;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;

import com.microsoft.playwright.Page;

/**
 * Page 层获取 seam（DI 改造一期，WEB-P0-2）。
 *
 * <p>与 {@link BrowserProvider} / {@link ContextProvider} 共同由 {@link RuntimeProvider} 组合，
 * 供 {@code PlaywrightManager} 静态门面委托。
 *
 * @apiNote 仅框架测试注入使用；生产代码不得实现本接口，始终走默认实现。
 */
public interface PageProvider {

    /**
     * 获取当前线程 {@code Page} 实例（不存在时按需创建，含断连守卫）。
     *
     * @return Page 实例
     */
    Page getPage();
}
