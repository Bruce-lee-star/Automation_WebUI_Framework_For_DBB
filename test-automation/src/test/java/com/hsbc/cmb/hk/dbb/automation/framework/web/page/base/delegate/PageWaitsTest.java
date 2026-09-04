package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.delegate;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import org.junit.Test;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PageWaits 委派类行为护盾（T5-5 模块 1 补 UT）：验证委派正确转发到 BasePage 公开 API，
 * 行为与原实现一致；不依赖浏览器运行时（纯 Mockito 隔离）。
 */
public class PageWaitsTest {

    private static BasePage mockBp() {
        BasePage bp = mock(BasePage.class);
        when(bp.getPage()).thenReturn(mock(Page.class));
        return bp;
    }

    @Test
    public void waitForNetworkIdle_delegatesToPageWaitForLoadState() {
        BasePage bp = mockBp();
        PageWaits.waitForNetworkIdle(bp, 10);
        verify(bp.getPage()).waitForLoadState(eq(LoadState.NETWORKIDLE),
                any(Page.WaitForLoadStateOptions.class));
    }

    @Test
    public void waitForPageFullyLoaded_delegatesToLoadState() {
        BasePage bp = mockBp();
        PageWaits.waitForPageFullyLoaded(bp, 5);
        verify(bp.getPage()).waitForLoadState(eq(LoadState.LOAD),
                any(Page.WaitForLoadStateOptions.class));
    }

    @Test
    public void shouldBeVisible_throwsWhenLocatorNotVisible() {
        BasePage bp = mockBp();
        Locator locator = mock(Locator.class);
        when(bp.locator("x")).thenReturn(locator);
        when(locator.isVisible()).thenReturn(false);
        try {
            PageWaits.shouldBeVisible(bp, "x");
            fail("expected ElementException");
        } catch (ElementException e) {
            // 符合预期：不可见时应抛 ElementException
        }
        verify(locator).isVisible();
    }

    @Test
    public void shouldBeVisible_passesWhenLocatorVisible() {
        BasePage bp = mockBp();
        Locator locator = mock(Locator.class);
        when(bp.locator("x")).thenReturn(locator);
        when(locator.isVisible()).thenReturn(true);
        PageWaits.shouldBeVisible(bp, "x"); // 不应抛异常
        verify(locator).isVisible();
    }

    @Test
    public void retry_runsOperationAndReturns() {
        BasePage bp = mockBp();
        Runnable op = mock(Runnable.class);
        PageWaits.retry(bp, op, "desc");
        verify(op).run();
    }
}
