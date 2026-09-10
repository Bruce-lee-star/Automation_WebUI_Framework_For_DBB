package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WEB-P1-5 种子测试：视口与滚动工厂（无浏览器，纯 Mockito 隔离 BasePage/Page/Locator）。
 * 覆盖 setViewportSize 与四种 scroll* 的 JS 表达式拼接。
 */
public class PageViewportTest {

    @Test
    public void setViewportSize_delegatesToPage_afterEnsurePageValid() {
        BasePage bp = mock(BasePage.class);
        Page page = mock(Page.class);
        when(bp.getPage()).thenReturn(page);

        PageViewport.setViewportSize(bp, 800, 600);

        verify(bp).ensurePageValid();
        verify(page).setViewportSize(800, 600);
    }

    @Test
    public void scrollTo_buildsScrollToExpression() {
        BasePage bp = mock(BasePage.class);
        Locator loc = mock(Locator.class);
        when(bp.locatorInternal(anyString())).thenReturn(loc);

        PageViewport.scrollTo(bp, "#s", 10, 20);

        verify(bp).locatorInternal("#s");
        verify(loc).evaluate("el => el.scrollTo(10,20)");
    }

    @Test
    public void scrollBy_buildsScrollByExpression() {
        BasePage bp = mock(BasePage.class);
        Locator loc = mock(Locator.class);
        when(bp.locatorInternal(anyString())).thenReturn(loc);

        PageViewport.scrollBy(bp, "#s", 5, 7);

        verify(loc).evaluate("el => el.scrollBy(5,7)");
    }

    @Test
    public void scrollToTopOf_setsScrollTopZero() {
        BasePage bp = mock(BasePage.class);
        Locator loc = mock(Locator.class);
        when(bp.locatorInternal(anyString())).thenReturn(loc);

        PageViewport.scrollToTopOf(bp, "#s");

        verify(loc).evaluate("el => el.scrollTop = 0");
    }

    @Test
    public void scrollToBottomOf_setsScrollTopToScrollHeight() {
        BasePage bp = mock(BasePage.class);
        Locator loc = mock(Locator.class);
        when(bp.locatorInternal(anyString())).thenReturn(loc);

        PageViewport.scrollToBottomOf(bp, "#s");

        verify(loc).evaluate("el => el.scrollTop = el.scrollHeight");
    }
}
