package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.delegate;



import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.NavigationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PageNavigation 委派类行为护盾（T5-5 模块 2）：验证导航方法正确转发到 Playwright Page，
 * 帧重置在导航/刷新/回退/前进/setContent 后被调用，且 TimeoutError 被包装为 NavigationException。
 * 通过 additive 的 {@code getConfig()} seam 隔离静态 PlaywrightManager，纯 Mockito 隔离、不依赖浏览器。
 */
public class PageNavigationTest {

    private static PlaywrightConfigManager config(String state, int timeout) {
        PlaywrightConfigManager c = mock(PlaywrightConfigManager.class);
        when(c.getPageLoadState()).thenReturn(state);
        when(c.getNavigationTimeout()).thenReturn(timeout);
        return c;
    }

    private static BasePage mockBp(PlaywrightConfigManager config) {
        BasePage bp = mock(BasePage.class);
        when(bp.getPage()).thenReturn(mock(Page.class));
        when(bp.getConfig()).thenReturn(config);
        return bp;
    }

    @Test
    public void navigateTo_networkIdleUsesConfiguredWaitUntil() {
        BasePage bp = mockBp(config("networkidle", 30000));
        PageNavigation.navigateTo(bp, "http://x");
        verify(bp.getPage()).navigate(eq("http://x"), any(Page.NavigateOptions.class));
        verify(bp).resetFrameContextAfterNavigation();
    }

    @Test
    public void navigateTo_unknownStateFallsBackToLoad() {
        BasePage bp = mockBp(config("whatever", 1000));
        PageNavigation.navigateTo(bp, "http://x");
        verify(bp.getPage()).navigate(anyString(), any(Page.NavigateOptions.class));
        verify(bp).resetFrameContextAfterNavigation();
    }

    @Test
    public void navigateTo_wrapsTimeoutErrorAsNavigationException() {
        BasePage bp = mockBp(config("load", 5000));
        when(bp.getPage().navigate(anyString(), any(Page.NavigateOptions.class)))
                .thenThrow(new TimeoutError("timeout"));
        try {
            PageNavigation.navigateTo(bp, "http://x");
            fail("expected NavigationException");
        } catch (NavigationException e) {
            assertEquals("http://x", e.getUrl());
        }
    }

    @Test
    public void getCurrentUrl_delegates() {
        BasePage bp = mockBp(config("load", 1));
        when(bp.getPage().url()).thenReturn("http://cur");
        assertEquals("http://cur", PageNavigation.getCurrentUrl(bp));
    }

    @Test
    public void getTitle_delegates() {
        BasePage bp = mockBp(config("load", 1));
        when(bp.getPage().title()).thenReturn("T");
        assertEquals("T", PageNavigation.getTitle(bp));
    }

    @Test
    public void refresh_resetsFrameContext() {
        BasePage bp = mockBp(config("load", 1));
        PageNavigation.refresh(bp);
        verify(bp.getPage()).reload();
        verify(bp).resetFrameContextAfterNavigation();
    }

    @Test
    public void back_resetsFrameContext() {
        BasePage bp = mockBp(config("load", 1));
        PageNavigation.back(bp);
        verify(bp.getPage()).goBack();
        verify(bp).resetFrameContextAfterNavigation();
    }

    @Test
    public void forward_resetsFrameContext() {
        BasePage bp = mockBp(config("load", 1));
        PageNavigation.forward(bp);
        verify(bp.getPage()).goForward();
        verify(bp).resetFrameContextAfterNavigation();
    }

    @Test
    public void setContent_resetsFrameContext() {
        BasePage bp = mockBp(config("load", 1));
        PageNavigation.setContent(bp, "<html/>");
        verify(bp.getPage()).setContent("<html/>");
        verify(bp).resetFrameContextAfterNavigation();
    }

    @Test
    public void navigateToWithRetry_invokesRetryAndNavigate() {
        BasePage bp = mockBp(config("load", 1));
        // 让 mock 的 retry 真正执行传入的 Runnable（lambda 内调用 bp.navigateTo）
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(bp).retry(any(Runnable.class), anyInt(), anyInt(), anyString());
        PageNavigation.navigateToWithRetry(bp, "http://x", 3);
        verify(bp).navigateTo("http://x");
        verify(bp).retry(any(Runnable.class), eq(3), eq(1000), eq("navigate to: http://x"));
    }
}
