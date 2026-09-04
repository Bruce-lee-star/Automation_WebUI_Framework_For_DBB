package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.delegate;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.microsoft.playwright.Locator;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PageElementActions 委派类行为护盾（T5-5 模块 3）：验证元素操作正确转发到 element()/locator() 及
 * 缺口方法 dblclick/dispatchEvent 转发到 Playwright Locator；纯 Mockito 隔离、不依赖浏览器。
 */
public class PageElementActionsTest {

    private static BasePage mockBp() {
        BasePage bp = mock(BasePage.class);
        when(bp.element(anyString())).thenReturn(mock(PageElement.class));
        when(bp.locator(anyString())).thenReturn(mock(Locator.class));
        return bp;
    }

    @Test
    public void click_delegatesToElement() {
        BasePage bp = mockBp();
        PageElementActions.click(bp, "x");
        verify(bp.element("x")).click();
    }

    @Test
    public void type_delegatesToElement() {
        BasePage bp = mockBp();
        PageElementActions.type(bp, "x", "hello");
        verify(bp.element("x")).type("hello");
    }

    @Test
    public void getText_delegatesToElement() {
        BasePage bp = mockBp();
        when(bp.element("x").getText()).thenReturn("txt");
        assertEquals("txt", PageElementActions.getText(bp, "x"));
    }

    @Test
    public void isVisible_delegatesToElement() {
        BasePage bp = mockBp();
        when(bp.element("x").isVisible()).thenReturn(true);
        assertEquals(true, PageElementActions.isVisible(bp, "x"));
    }

    @Test
    public void getElementCount_delegatesToLocatorCount() {
        BasePage bp = mockBp();
        when(bp.locator("x").count()).thenReturn(3);
        assertEquals(3, PageElementActions.getElementCount(bp, "x"));
    }

    @Test
    public void getAttributeValue_normalizesViaBasePage() {
        BasePage bp = mockBp();
        when(bp.getAttribute("x", "href")).thenReturn("  RAW  ");
        when(bp.normalizeText("  RAW  ")).thenReturn("RAW");
        assertEquals("RAW", PageElementActions.getAttributeValue(bp, "x", "href", "def"));
    }

    @Test
    public void getAttributeValue_returnsDefaultWhenNull() {
        BasePage bp = mockBp();
        when(bp.getAttribute("x", "href")).thenReturn(null);
        assertEquals("def", PageElementActions.getAttributeValue(bp, "x", "href", "def"));
    }

    @Test
    public void focus_delegatesToLocator() {
        BasePage bp = mockBp();
        PageElementActions.focus(bp, "x");
        verify(bp.locator("x")).focus();
    }

    @Test
    public void hover_delegatesToLocator() {
        BasePage bp = mockBp();
        PageElementActions.hover(bp, "x");
        verify(bp.locator("x")).hover();
    }

    @Test
    public void dragAndDrop_delegatesToLocatorDragTo() {
        BasePage bp = mockBp();
        PageElementActions.dragAndDrop(bp, "src", "dst");
        verify(bp.locator("src")).dragTo(bp.locator("dst"));
    }

    @Test
    public void dblclick_delegatesToLocator() {
        BasePage bp = mockBp();
        PageElementActions.dblclick(bp, "x");
        verify(bp.locator("x")).dblclick();
    }

    @Test
    public void dispatchEvent_delegatesToLocator() {
        BasePage bp = mockBp();
        PageElementActions.dispatchEvent(bp, "x", "click");
        verify(bp.locator("x")).dispatchEvent(eq("click"));
    }
}
