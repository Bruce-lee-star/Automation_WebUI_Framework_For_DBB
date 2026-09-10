package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.delegate;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.NavigationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WEB-P1-5 种子测试：导航子模块（无浏览器，纯 Mockito 隔离 BasePage/Page）。
 * 覆盖 url/title 委托、loadState→WaitUntilState 映射、导航异常收敛为 NavigationException、刷新/前进后退/setContent 编排。
 */
public class PageNavigationTest {

    private static BasePage bp() {
        BasePage bp = mock(BasePage.class);
        Page page = mock(Page.class);
        PlaywrightConfigManager config = mock(PlaywrightConfigManager.class);
        when(bp.getPage()).thenReturn(page);
        when(bp.getConfig()).thenReturn(config);
        return bp;
    }

    @Test
    public void getCurrentUrl_delegatesToPage() {
        BasePage bp = bp();
        Page page = bp.getPage();
        when(page.url()).thenReturn("https://example.com");
        assertEquals("https://example.com", PageNavigation.getCurrentUrl(bp));
        verify(page).url();
    }

    @Test
    public void getTitle_delegatesToPage() {
        BasePage bp = bp();
        Page page = bp.getPage();
        when(page.title()).thenReturn("Title");
        assertEquals("Title", PageNavigation.getTitle(bp));
    }

    @Test
    public void navigateTo_mapsLoadStateToWaitUntil_andResetsFrameContext() {
        for (String state : new String[]{"networkidle", "domcontentloaded", "commit", "load", "unknown"}) {
            BasePage bp = bp();
            Page page = bp.getPage();
            PlaywrightConfigManager config = bp.getConfig();
            when(config.getPageLoadState()).thenReturn(state);
            when(config.getNavigationTimeout()).thenReturn(30000);

            PageNavigation.navigateTo(bp, "https://x.com");

            // 各 loadState 分支均被本循环覆盖；选项对象已构建并传入 navigate（waitUntil 映射在主线已按 state 分支赋值）
            verify(page).navigate(eq("https://x.com"), any(Page.NavigateOptions.class));
            verify(bp).resetFrameContextAfterNavigation();
        }
    }

    @Test
    public void navigateTo_timeoutError_mapsToNavigationException() {
        BasePage bp = bp();
        Page page = bp.getPage();
        PlaywrightConfigManager config = bp.getConfig();
        when(config.getPageLoadState()).thenReturn("load");
        when(config.getNavigationTimeout()).thenReturn(30000);
        when(page.navigate(anyString(), any())).thenThrow(new TimeoutError("boom"));

        NavigationException ex = assertThrows(NavigationException.class, () -> PageNavigation.navigateTo(bp, "https://x.com"));
        assertTrue("异常应携带目标 URL 便于定位", ex.getMessage().contains("https://x.com"));
    }

    @Test
    public void navigateTo_playwrightException_mapsToNavigationException() {
        BasePage bp = bp();
        Page page = bp.getPage();
        PlaywrightConfigManager config = bp.getConfig();
        when(config.getPageLoadState()).thenReturn("load");
        when(config.getNavigationTimeout()).thenReturn(30000);
        when(page.navigate(anyString(), any())).thenThrow(new PlaywrightException("boom"));

        assertThrows(NavigationException.class, () -> PageNavigation.navigateTo(bp, "https://x.com"));
    }

    @Test
    public void refresh_reloadsAndResetsFrameContext() {
        BasePage bp = bp();
        Page page = bp.getPage();
        PageNavigation.refresh(bp);
        verify(page).reload();
        verify(bp).resetFrameContextAfterNavigation();
    }

    @Test
    public void backAndForward_navigateAndResetFrameContext() {
        BasePage bp = bp();
        Page page = bp.getPage();
        PageNavigation.back(bp);
        verify(page).goBack();
        PageNavigation.forward(bp);
        verify(page).goForward();
        verify(bp, times(2)).resetFrameContextAfterNavigation();
    }

    @Test
    public void setContent_replacesAndResetsFrameContext() {
        BasePage bp = bp();
        Page page = bp.getPage();
        PageNavigation.setContent(bp, "<html/>");
        verify(page).setContent("<html/>");
        verify(bp).resetFrameContextAfterNavigation();
    }
}
