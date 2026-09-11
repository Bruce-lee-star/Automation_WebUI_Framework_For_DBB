package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.state.PlaywrightRuntimeState;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestartImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistry;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3 真多态验证（DI 二期 WEB-P1-6）：替换 {@link PlaywrightRuntime} 组合根中的协作者实现后，
 * 经 {@link PlaywrightManager} 门面 / provider seam 触达的运行时行为随之改变 —— 证明组合根可整体替换，
 * 达成验收 ②（每接口 ≥2 实现：生产 {@code *Impl} + 测试替身）。全程 Mockito 替身，<b>不启动真实浏览器</b>。
 */
public class PlaywrightRuntimePolymorphismTest {

    @AfterEach
    public void tearDown() {
        // 复位组合根 + provider seam，避免污染其它测试
        PlaywrightRuntime.resetInstance();
        PlaywrightManager.resetProvider();
    }

    @Test
    public void swappingBrowserRegistryChangesGetBrowser() {
        Browser fake = mock(Browser.class);
        PlaywrightRuntime rt = new PlaywrightRuntime(
                browserRegistryReturning(fake, null),
                ContextRegistryImpl.INSTANCE,
                PageRegistryImpl.INSTANCE,
                BrowserStartupImpl.INSTANCE,
                BrowserRestartImpl.INSTANCE,
                BrowserCleanupImpl.INSTANCE, PlaywrightRuntimeState.INSTANCE);
        PlaywrightRuntime.setInstance(rt);
        assertSame(fake, PlaywrightManager.getBrowser());
    }

    @Test
    public void swappingBrowserRegistryChangesGetPlaywright() {
        Playwright fake = mock(Playwright.class);
        PlaywrightRuntime rt = new PlaywrightRuntime(
                browserRegistryReturning(null, fake),
                ContextRegistryImpl.INSTANCE,
                PageRegistryImpl.INSTANCE,
                BrowserStartupImpl.INSTANCE,
                BrowserRestartImpl.INSTANCE,
                BrowserCleanupImpl.INSTANCE, PlaywrightRuntimeState.INSTANCE);
        PlaywrightRuntime.setInstance(rt);
        assertSame(fake, PlaywrightManager.getPlaywright());
    }

    @Test
    public void swappingContextRegistryChangesGetContext() {
        BrowserContext fake = mock(BrowserContext.class);
        PlaywrightRuntime rt = new PlaywrightRuntime(
                BrowserRegistryImpl.INSTANCE,
                contextRegistryReturning(fake),
                PageRegistryImpl.INSTANCE,
                BrowserStartupImpl.INSTANCE,
                BrowserRestartImpl.INSTANCE,
                BrowserCleanupImpl.INSTANCE, PlaywrightRuntimeState.INSTANCE);
        PlaywrightRuntime.setInstance(rt);
        assertSame(fake, PlaywrightManager.getContext());
    }

    @Test
    public void swappingPageRegistryChangesGetPage() {
        Page fake = mock(Page.class);
        PlaywrightRuntime rt = new PlaywrightRuntime(
                BrowserRegistryImpl.INSTANCE,
                ContextRegistryImpl.INSTANCE,
                pageRegistryReturning(fake),
                BrowserStartupImpl.INSTANCE,
                BrowserRestartImpl.INSTANCE,
                BrowserCleanupImpl.INSTANCE, PlaywrightRuntimeState.INSTANCE);
        PlaywrightRuntime.setInstance(rt);
        assertSame(fake, PlaywrightManager.getPage());
    }

    @Test
    public void swappingBrowserRegistryChangesFacadeEnsureConfigId() {
        // ensureConfigId 经 Phase 2 委托路由到 PlaywrightRuntime.instance().browserRegistry
        BrowserRegistry stub = mock(BrowserRegistry.class);
        when(stub.ensureConfigId()).thenReturn("UNIT_TEST_CONFIG_ID");
        PlaywrightRuntime rt = new PlaywrightRuntime(
                stub,
                ContextRegistryImpl.INSTANCE,
                PageRegistryImpl.INSTANCE,
                BrowserStartupImpl.INSTANCE,
                BrowserRestartImpl.INSTANCE,
                BrowserCleanupImpl.INSTANCE, PlaywrightRuntimeState.INSTANCE);
        PlaywrightRuntime.setInstance(rt);
        assertEquals("UNIT_TEST_CONFIG_ID", PlaywrightManager.ensureConfigId());
    }

    @Test
    public void setInstanceThenResetRestoresDefault() {
        PlaywrightRuntime swapped = new PlaywrightRuntime(
                mock(BrowserRegistry.class),
                ContextRegistryImpl.INSTANCE,
                PageRegistryImpl.INSTANCE,
                BrowserStartupImpl.INSTANCE,
                BrowserRestartImpl.INSTANCE,
                BrowserCleanupImpl.INSTANCE, PlaywrightRuntimeState.INSTANCE);
        PlaywrightRuntime.setInstance(swapped);
        assertSame(swapped, PlaywrightRuntime.instance());

        PlaywrightRuntime.resetInstance();
        assertNotSame(swapped, PlaywrightRuntime.instance());
    }

    private static BrowserRegistry browserRegistryReturning(Browser browser, Playwright playwright) {
        BrowserRegistry stub = mock(BrowserRegistry.class);
        if (browser != null) {
            when(stub.getBrowser()).thenReturn(browser);
        }
        if (playwright != null) {
            when(stub.getPlaywright()).thenReturn(playwright);
        }
        return stub;
    }

    private static ContextRegistry contextRegistryReturning(BrowserContext ctx) {
        ContextRegistry stub = mock(ContextRegistry.class);
        when(stub.getContext()).thenReturn(ctx);
        return stub;
    }

    private static PageRegistry pageRegistryReturning(Page page) {
        PageRegistry stub = mock(PageRegistry.class);
        when(stub.getPage()).thenReturn(page);
        return stub;
    }
}
