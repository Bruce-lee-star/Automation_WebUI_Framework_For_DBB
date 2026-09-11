package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WEB-P1-5 种子测试：元素门面 {@code PageElement}（无浏览器，Mockito 隔离 BasePage/Page/Locator）。
 * 覆盖两种构造的入参守卫、选择器/页面访问器、以及底层 Locator 的三条解析路径
 * （动态 supplier / 页面选择器 / 组合定位 nth）。
 */
public class PageElementTest {

    private static BasePage bpWith(Page page) {
        BasePage bp = mock(BasePage.class);
        when(bp.getPage()).thenReturn(page);
        return bp;
    }

    // ---------- 入参守卫 ----------

    @Test
    public void supplierConstructor_rejectsNullSupplier() {
        BasePage bp = bpWith(mock(Page.class));
        assertThrows(IllegalArgumentException.class, () -> new PageElement(null, "desc", bp));
    }

    @Test
    public void supplierConstructor_rejectsBlankDescription() {
        BasePage bp = bpWith(mock(Page.class));
        assertThrows(IllegalArgumentException.class, () -> new PageElement(() -> mock(Locator.class), "  ", bp));
    }

    @Test
    public void supplierConstructor_rejectsNullPage() {
        assertThrows(IllegalArgumentException.class, () -> new PageElement(() -> mock(Locator.class), "desc", null));
    }

    @Test
    public void selectorConstructor_rejectsBlankSelector() {
        BasePage bp = bpWith(mock(Page.class));
        assertThrows(IllegalArgumentException.class, () -> new PageElement("  ", bp));
    }

    @Test
    public void selectorConstructor_rejectsNullPage() {
        assertThrows(IllegalArgumentException.class, () -> new PageElement("#sel", null));
    }

    // ---------- 访问器 ----------

    @Test
    public void getters_exposeSelectorAndOwningPage() {
        BasePage bp = bpWith(mock(Page.class));
        PageElement el = new PageElement("#login", bp);

        assertEquals("#login", el.getSelector());
        assertSame(bp, el.getPage());
    }

    // ---------- Locator 解析路径 ----------

    @Test
    public void locatorInternal_prefersDynamicSupplier() {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        PageElement el = new PageElement(() -> loc, "role=button", bp);

        assertSame( loc,  el.locatorInternal(), "动态 supplier 优先，保证语言/页面切换后自动重解析");
        verify(bp).getPage();
    }

    @Test
    public void locatorInternal_fallsBackToPageSelectorResolution() {
        BasePage bp = bpWith(mock(Page.class));
        Locator loc = mock(Locator.class);
        when(bp.locatorInternal("#login")).thenReturn(loc);
        PageElement el = new PageElement("#login", bp);

        assertSame(loc, el.locatorInternal());
        verify(bp).locatorInternal("#login");
    }

    // ---------- 组合定位 ----------

    @Test
    public void nth_returnsNewElementWithIndexedSelector_andResolvesLazily() {
        BasePage bp = bpWith(mock(Page.class));
        PageElement el = new PageElement("#link", bp);

        PageElement second = el.nth(2);

        assertNotSame( el,  second, "nth 应返回新元素，不原地修改");
        assertTrue(
                second.getSelector().contains("nth=2"), "新元素选择器须带索引便于诊断：" + second.getSelector());
    }
}
