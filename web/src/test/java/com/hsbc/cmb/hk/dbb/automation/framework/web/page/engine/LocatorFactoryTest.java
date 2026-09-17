package com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    public void byTestId_primaryUsesGetByTestIdAndKeepsCompatAttributes() {
        Page page = mock(Page.class);
        Locator primary = mock(Locator.class);
        Locator compat = mock(Locator.class);
        Locator merged = mock(Locator.class);
        when(page.getByTestId("abc")).thenReturn(primary);
        when(page.locator(anyString())).thenReturn(compat);
        when(primary.or(compat)).thenReturn(merged);
        BasePage bp = bpWith(null, page);

        assertEquals(merged, LocatorFactory.byTestId(bp, "abc"));
        verify(page).getByTestId("abc");
        var captor = forClass(String.class);
        verify(page).locator(captor.capture());
        assertEquals("[data-test-id=\"abc\"],[data-test=\"abc\"],[data-qa=\"abc\"]",
                captor.getValue(), "兼容属性应为转义后的 CSS 选择器");
    }

    @Test
    public void byTestId_escapesSpecialCharactersToCloseInjectionSurface() {
        Page page = mock(Page.class);
        Locator primary = mock(Locator.class);
        Locator compat = mock(Locator.class);
        Locator merged = mock(Locator.class);
        when(page.getByTestId(anyString())).thenReturn(primary);
        when(page.locator(anyString())).thenReturn(compat);
        when(primary.or(compat)).thenReturn(merged);
        BasePage bp = bpWith(null, page);

        // 含双引号与反斜杠的 testId：原裸拼写法会破坏 selector（定位器注入面）
        String malicious = "a\"b\\c";
        assertEquals(merged, LocatorFactory.byTestId(bp, malicious));
        var captor = forClass(String.class);
        verify(page).locator(captor.capture());
        String sel = captor.getValue();
        // 注入面闭合：裸属性值（含未转义引号）不应出现
        assertFalse(sel.contains("[data-test-id=\"" + malicious + "\"]"),
                "testId 含特殊字符时不应出现裸属性值（定位器注入面未闭合）");
        // 转义已发生：双引号 → \"，反斜杠 → \\
        // （修正原写法：原先两侧是同一表达式，等于只校验了引号，反斜杠从未被断言 —— SpotBugs RpC_REPEATED_CONDITIONAL_TEST）
        assertTrue(sel.contains("\\\""), "testId 中的双引号应被转义为 \\\"");
        assertTrue(sel.contains("\\\\"), "testId 中的反斜杠应被转义为 \\\\");
        // 兼容性：三种历史兼容属性仍被匹配
        assertTrue(sel.contains("data-test-id") && sel.contains("data-test") && sel.contains("data-qa"),
                "应保留三种历史兼容测试属性的匹配");
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
