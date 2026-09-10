package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.Test;

import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WEB-P1-5 种子测试：定位器工厂（无浏览器，纯 Mockito 隔离 BasePage/Page/Frame）。
 * 覆盖 null 守卫、frame 存在/不存在双路径、按名精确匹配、Pattern 变体、testid 选择器拼接。
 */
public class LocatorFactoryTest {

    private static BasePage bpWith(Frame frame, Page page) {
        BasePage bp = mock(BasePage.class);
        when(bp.getCurrentFrame()).thenReturn(frame);
        when(bp.getPage()).thenReturn(page);
        return bp;
    }

    @Test
    public void byAltText_nullBp_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> LocatorFactory.byAltText(null, "x"));
    }

    @Test
    public void byAltText_usesPageWhenNoFrame() {
        Page page = mock(Page.class);
        Locator loc = mock(Locator.class);
        when(page.getByAltText("alt")).thenReturn(loc);
        BasePage bp = bpWith(null, page);

        assertEquals(loc, LocatorFactory.byAltText(bp, "alt"));
        verify(page).getByAltText("alt");
    }

    @Test
    public void byAltText_usesFrameWhenFramePresent() {
        Frame frame = mock(Frame.class);
        Page page = mock(Page.class);
        Locator loc = mock(Locator.class);
        when(frame.getByAltText("alt")).thenReturn(loc);
        BasePage bp = bpWith(frame, page);

        assertEquals(loc, LocatorFactory.byAltText(bp, "alt"));
        verify(frame).getByAltText("alt");
    }

    @Test
    public void byRole_withNameExact_usesFrameOptions() {
        Frame frame = mock(Frame.class);
        Page page = mock(Page.class);
        Locator loc = mock(Locator.class);
        when(frame.getByRole(any(AriaRole.class), any(Frame.GetByRoleOptions.class))).thenReturn(loc);
        BasePage bp = bpWith(frame, page);

        assertEquals(loc, LocatorFactory.byRole(bp, AriaRole.BUTTON, "Submit", true));
        verify(frame).getByRole(any(AriaRole.class), any(Frame.GetByRoleOptions.class));
    }

    @Test
    public void byText_pattern_usesPage() {
        Page page = mock(Page.class);
        Locator loc = mock(Locator.class);
        when(page.getByText(any(Pattern.class))).thenReturn(loc);
        BasePage bp = bpWith(null, page);

        assertEquals(loc, LocatorFactory.byText(bp, Pattern.compile("hello")));
        verify(page).getByText(any(Pattern.class));
    }

    @Test
    public void byTestId_buildsMultiAttributeSelector() {
        Page page = mock(Page.class);
        Locator loc = mock(Locator.class);
        when(page.locator(anyString())).thenReturn(loc);
        BasePage bp = bpWith(null, page);

        assertEquals(loc, LocatorFactory.byTestId(bp, "abc"));
        var captor = forClass(String.class);
        verify(page).locator(captor.capture());
        assertEquals("data-testid selector should cover the four common test attributes",
                "[data-testid=\"abc\"],[data-test-id=\"abc\"],[data-test=\"abc\"],[data-qa=\"abc\"]",
                captor.getValue());
    }

    @Test
    public void byTitle_string_usesPage() {
        Page page = mock(Page.class);
        Locator loc = mock(Locator.class);
        when(page.getByTitle("t")).thenReturn(loc);
        BasePage bp = bpWith(null, page);

        assertEquals(loc, LocatorFactory.byTitle(bp, "t"));
        verify(page).getByTitle("t");
    }
}
