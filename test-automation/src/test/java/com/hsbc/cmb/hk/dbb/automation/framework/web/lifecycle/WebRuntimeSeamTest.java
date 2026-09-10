package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.core.RuntimeProvider;
import com.microsoft.playwright.Page;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/**
 * WEB-P0-2 seam 固化测试：验证 {@link PlaywrightManager} 的 Provider 注入点可替换、
 * 且 {@code resetProvider()} 能复位默认实现，从而核心类可经 mock 脱离真实浏览器单测。
 */
public class WebRuntimeSeamTest {

    @After
    public void tearDown() {
        PlaywrightManager.resetProvider();
    }

    @Test
    public void getPage_returnsInjectedProviderPage() {
        RuntimeProvider mockProvider = Mockito.mock(RuntimeProvider.class);
        Page mockPage = Mockito.mock(Page.class);
        Mockito.when(mockProvider.getPage()).thenReturn(mockPage);

        PlaywrightManager.setProvider(mockProvider);
        assertSame(mockPage, PlaywrightManager.getPage());
    }

    @Test
    public void resetProvider_restoresDefaultImplementation() {
        RuntimeProvider mockProvider = Mockito.mock(RuntimeProvider.class);
        PlaywrightManager.setProvider(mockProvider);
        PlaywrightManager.resetProvider();

        // 复位后应回到默认实现（不为注入的 mock），且默认实现非空
        assertNotSame(mockProvider, PlaywrightManager.getProvider());
        assertNotNull(PlaywrightManager.getProvider());
    }

    @Test
    public void setProvider_rejectsNull() {
        try {
            PlaywrightManager.setProvider(null);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 语义化拒绝 null，防误用
        } finally {
            PlaywrightManager.resetProvider();
        }
    }
}
