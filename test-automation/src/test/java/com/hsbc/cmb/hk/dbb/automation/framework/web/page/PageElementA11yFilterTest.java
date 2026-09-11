package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PageElement 组合定位 / ARIA 快照能力护盾（T3-5 补全）：验证 filter / or 正确转发到
 * Playwright Locator 组合能力，且 ariaSnapshot 作为数据检索能力正确委托；纯 Mockito 隔离、不依赖浏览器。
 * 注：ARIA 快照的断言/校验应交由调用方或专用断言层完成，PageElement 本身不内嵌断言逻辑。
 */
public class PageElementA11yFilterTest {

    private static PageElement elWith(Locator loc) {
        BasePage bp = mock(BasePage.class);
        return new PageElement(() -> loc, "desc", bp);
    }

    @Test
    public void ariaSnapshot_delegatesToLocator() {
        Locator loc = mock(Locator.class);
        when(loc.ariaSnapshot()).thenReturn("- button \"OK\"");
        assertEquals("- button \"OK\"", elWith(loc).ariaSnapshot());
        verify(loc).ariaSnapshot();
    }

    @Test
    public void filter_text_buildsHasTextFilter() {
        Locator loc = mock(Locator.class);
        Locator filtered = mock(Locator.class);
        when(loc.filter(any(Locator.FilterOptions.class))).thenReturn(filtered);
        PageElement filteredEl = elWith(loc).filter("txt");
        filteredEl.locatorInternal();
        ArgumentCaptor<Locator.FilterOptions> cap = ArgumentCaptor.forClass(Locator.FilterOptions.class);
        verify(loc).filter(cap.capture());
        assertEquals("txt", cap.getValue().hasText);
    }

    @Test
    public void filterBy_buildsHasFilter() {
        Locator loc = mock(Locator.class);
        Locator has = mock(Locator.class);
        Locator filtered = mock(Locator.class);
        when(loc.filter(any(Locator.FilterOptions.class))).thenReturn(filtered);
        PageElement filteredEl = elWith(loc).filterBy(elWith(has));
        filteredEl.locatorInternal();
        ArgumentCaptor<Locator.FilterOptions> cap = ArgumentCaptor.forClass(Locator.FilterOptions.class);
        verify(loc).filter(cap.capture());
        assertEquals(has, cap.getValue().has);
    }

    @Test
    public void or_buildsUnionLocator() {
        Locator loc = mock(Locator.class);
        Locator other = mock(Locator.class);
        Locator union = mock(Locator.class);
        when(loc.or(other)).thenReturn(union);
        PageElement unionEl = elWith(loc).or(elWith(other));
        assertEquals(union, unionEl.locatorInternal());
        verify(loc).or(other);
    }

    @Test
    public void filterNot_buildsHasNotFilter() {
        Locator loc = mock(Locator.class);
        Locator hasNot = mock(Locator.class);
        Locator filtered = mock(Locator.class);
        when(loc.filter(any(Locator.FilterOptions.class))).thenReturn(filtered);
        PageElement filteredEl = elWith(loc).filterNot(elWith(hasNot));
        filteredEl.locatorInternal();
        ArgumentCaptor<Locator.FilterOptions> cap = ArgumentCaptor.forClass(Locator.FilterOptions.class);
        verify(loc).filter(cap.capture());
        assertEquals(hasNot, cap.getValue().hasNot);
    }

    @Test
    public void filter_noArg_returnsSameLocator() {
        Locator loc = mock(Locator.class);
        Locator filtered = mock(Locator.class);
        when(loc.filter()).thenReturn(filtered);
        assertEquals(filtered, elWith(loc).filter().locatorInternal());
        verify(loc).filter();
    }
}
