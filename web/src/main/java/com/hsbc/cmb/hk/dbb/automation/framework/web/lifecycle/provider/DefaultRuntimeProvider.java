package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.provider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import com.hsbc.cmb.hk.dbb.automation.framework.web.core.RuntimeProvider;

/**
 * {@link RuntimeProvider} 默认实现（thin adapter，WEB-P0-2 + WEB-P1-6 Phase 3）。
 *
 * <p>方法体经 {@link PlaywrightRuntime} 组合根（{@code PlaywrightRuntime.instance()}）委托到 6 个角色协作者，
 * <b>不搬运 77KB 真实逻辑</b>。组合根可整体替换（{@code PlaywrightRuntime.setInstance}），故替换任一协作者实现后，
 * 经本默认实现触达的运行时行为随之改变（真多态，达成验收 ②）。生产路径下 {@code PlaywrightManager} 公开 getter
 * 委托本实现，行为等价改造前；单测亦可经 {@code PlaywrightManager.setProvider(mock)} 替换本实现以脱离真实浏览器。
 *
 * <p>单例 {@code INSTANCE} 不可变，仅 {@code PlaywrightManager.resetProvider()} 可复位为默认；
 * 组合根引用每次调用实时获取（{@code PlaywrightRuntime.instance()}），故运行时换实现立即生效。</p>
 *
 * @apiNote <b>框架内部实现</b>：经 {@code PlaywrightManager.setProvider} 注入，业务代码勿直接依赖。
 */
public final class DefaultRuntimeProvider implements RuntimeProvider {

    public static final DefaultRuntimeProvider INSTANCE = new DefaultRuntimeProvider();

    private DefaultRuntimeProvider() {
    }

    @Override
    public Playwright getPlaywright() {
        return PlaywrightRuntime.instance().browserRegistry.getPlaywright();
    }

    @Override
    public Browser getBrowser() {
        return PlaywrightRuntime.instance().browserRegistry.getBrowser();
    }

    @Override
    public BrowserContext getContext() {
        return PlaywrightRuntime.instance().contextRegistry.getContext();
    }

    @Override
    public Page getPage() {
        return PlaywrightRuntime.instance().pageRegistry.getPage();
    }
}
