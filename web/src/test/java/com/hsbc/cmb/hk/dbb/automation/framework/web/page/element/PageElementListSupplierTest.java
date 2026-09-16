package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.BasePage;
import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PageElementList} 动态定位器路径单测（无浏览器，纯 Mockito）。
 *
 * <p>角色定位等多元素集合没有 CSS/XPath 选择器，只能由 supplier 动态构建 Locator：
 * 每次操作都重新解析（语言切换 / Page 重建后自动生效），且不得回退到选择器解析。
 * 同时回归保护既有「选择器路径」行为不变。
 */
class PageElementListSupplierTest {

    private static Locator mockListLocator(int count) {
        Locator locator = mock(Locator.class);
        Locator first = mock(Locator.class);
        when(locator.first()).thenReturn(first);
        when(locator.count()).thenReturn(count);
        return locator;
    }

    @Test
    void dynamicLocator_usesSupplierForEveryOperation_andSkipsSelectorResolution() {
        Locator locator = mockListLocator(3);
        AtomicInteger builds = new AtomicInteger();
        BasePage bp = mock(BasePage.class);

        PageElementList list = new PageElementList(() -> {
            builds.incrementAndGet();
            return locator;
        }, "role=LISTITEM[no-name]", bp);

        assertEquals(3, list.size());
        assertEquals("role=LISTITEM[no-name]", list.getSelector());
        assertEquals(1, builds.get(), "每次操作须经 supplier 重新构建（懒解析契约）");
        verify(bp, never()).locatorInternal(anyString());
    }

    @Test
    void dynamicLocator_get_returnsElementBoundToDescription() {
        Locator locator = mockListLocator(2);
        BasePage bp = mock(BasePage.class);

        PageElementList list = new PageElementList(() -> locator, "role=BUTTON[name:Submit]", bp);

        PageElement first = list.get(0);
        assertNotNull(first);
        assertEquals("role=BUTTON[name:Submit]", first.getSelector());
        assertNotNull(first.getPage());
    }

    @Test
    void nullSupplier_orBlankDescription_throwsIllegalArgumentException() {
        BasePage bp = mock(BasePage.class);
        Supplier<Locator> nullSupplier = null;

        assertThrows(IllegalArgumentException.class,
                () -> new PageElementList(nullSupplier, "role=BUTTON[no-name]", bp));
        assertThrows(IllegalArgumentException.class,
                () -> new PageElementList(() -> mock(Locator.class), "   ", bp));
    }

    @Test
    void selectorConstructor_stillResolvesBySelector() {
        Locator locator = mockListLocator(1);
        BasePage bp = mock(BasePage.class);
        when(bp.locatorInternal("#items")).thenReturn(locator);

        PageElementList list = new PageElementList("#items", bp);

        assertEquals(1, list.size());
        verify(bp).locatorInternal("#items");
        verify(locator.first()).waitFor(any(Locator.WaitForOptions.class));
    }
}
