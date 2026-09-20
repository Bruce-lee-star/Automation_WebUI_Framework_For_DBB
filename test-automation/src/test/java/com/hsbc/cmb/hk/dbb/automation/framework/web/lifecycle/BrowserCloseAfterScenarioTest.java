package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestart;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.LifecycleState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistry;
import com.microsoft.playwright.Browser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 固化「按 scenario/feature 关闭本线程 Browser」的核心逻辑（{@link BrowserCleanupImpl#closeBrowserForCurrentThread}），
 * 全程 Mockito 替身，<b>不启动真实浏览器</b>。验证：收尾时仅关闭并移除<b>当前线程</b>键下的 Browser，
 * 下一场景经 {@code getBrowser()} 懒重建；无存活 Browser 时为 no-op。
 */
public class BrowserCloseAfterScenarioTest {

    private static final String CONFIG_ID = "chromium_headless_";
    private static final String KEY = "42:" + CONFIG_ID;

    @AfterEach
    public void tearDown() {
        // 复位组合根 + 当前线程 configId，避免污染其它测试
        PlaywrightRuntime.resetInstance();
        TestContextHolder.get().remove(PlaywrightManager.CURRENT_CONFIG_ID_KEY);
    }

    @Test
    public void closeBrowserForCurrentThread_closesAndRemovesCurrentThreadBrowser() {
        Browser mockBrowser = mock(Browser.class);
        when(mockBrowser.isConnected()).thenReturn(true);

        LifecycleState state = mock(LifecycleState.class);
        BrowserRegistry reg = mock(BrowserRegistry.class);
        when(reg.keyFor(anyString())).thenReturn(KEY);
        when(state.getBrowser(KEY)).thenReturn(mockBrowser);

        PlaywrightRuntime rt = new PlaywrightRuntime(reg, mock(ContextRegistry.class), mock(PageRegistry.class),
                mock(BrowserStartup.class), mock(BrowserRestart.class), BrowserCleanupImpl.INSTANCE, state);
        PlaywrightRuntime.setInstance(rt);
        TestContextHolder.get().set(PlaywrightManager.CURRENT_CONFIG_ID_KEY, CONFIG_ID);

        int closed = PlaywrightManager.closeBrowserForCurrentThread();

        assertEquals(1, closed);
        verify(state).markClosing(mockBrowser);
        verify(state).removeBrowser(KEY);
    }

    @Test
    public void closeBrowserForCurrentThread_noBrowserIsNoOp() {
        LifecycleState state = mock(LifecycleState.class);
        BrowserRegistry reg = mock(BrowserRegistry.class);
        when(reg.keyFor(anyString())).thenReturn(KEY);
        when(state.getBrowser(KEY)).thenReturn(null);

        PlaywrightRuntime rt = new PlaywrightRuntime(reg, mock(ContextRegistry.class), mock(PageRegistry.class),
                mock(BrowserStartup.class), mock(BrowserRestart.class), BrowserCleanupImpl.INSTANCE, state);
        PlaywrightRuntime.setInstance(rt);
        TestContextHolder.get().set(PlaywrightManager.CURRENT_CONFIG_ID_KEY, CONFIG_ID);

        int closed = PlaywrightManager.closeBrowserForCurrentThread();

        assertEquals(0, closed);
        verify(state, never()).removeBrowser(anyString());
    }
}
