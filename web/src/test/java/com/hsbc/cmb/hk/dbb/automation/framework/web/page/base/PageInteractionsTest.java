package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WEB-P1-5 种子测试：页面交互与状态工厂（无浏览器，纯 Mockito 隔离 BasePage/Page/Frame/Keyboard）。
 * 覆盖脚本执行（page/frame 双路径）、源码读取与包含判定、页面数、闭合判定、前置、键盘、弹窗、截图。
 */
public class PageInteractionsTest {

    private static BasePage bpWith(Frame frame, Page page) {
        BasePage bp = mock(BasePage.class);
        when(bp.getCurrentFrame()).thenReturn(frame);
        when(bp.getPage()).thenReturn(page);
        // normalizeText 为 BasePage 真实方法，mock 下需显式桩化为恒等以保持断言语义
        when(bp.normalizeText(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return bp;
    }

    @Test
    public void executeJavaScript_usesPageWhenNoFrame() {
        Page page = mock(Page.class);
        BasePage bp = bpWith(null, page);
        Object expected = new Object();
        when(page.evaluate(anyString(), any())).thenReturn(expected);

        assertEquals(expected, PageInteractions.executeJavaScript(bp, "1+1"));
    }

    @Test
    public void executeJavaScript_usesFrameWhenFramePresent() {
        Frame frame = mock(Frame.class);
        BasePage bp = bpWith(frame, mock(Page.class));
        Object expected = new Object();
        when(frame.evaluate(anyString(), any())).thenReturn(expected);

        assertEquals(expected, PageInteractions.executeJavaScript(bp, "1+1"));
    }

    @Test
    public void getPageSource_usesPageContentWhenNoFrame() {
        Page page = mock(Page.class);
        when(page.content()).thenReturn("<html>hi</html>");
        BasePage bp = bpWith(null, page);

        assertEquals("<html>hi</html>", PageInteractions.getPageSource(bp));
    }

    @Test
    public void getPageSource_usesFrameContentWhenFramePresent() {
        Frame frame = mock(Frame.class);
        when(frame.content()).thenReturn("<frame/>");
        BasePage bp = bpWith(frame, mock(Page.class));

        assertEquals("<frame/>", PageInteractions.getPageSource(bp));
    }

    @Test
    public void getPageSourceContains_trueWhenContentMatches() {
        Page page = mock(Page.class);
        when(page.content()).thenReturn("Hello World");
        BasePage bp = bpWith(null, page);

        assertTrue(PageInteractions.getPageSourceContains(bp, "Hello"));
    }

    @Test
    public void getPageSourceContains_falseWhenContentMissing() {
        Page page = mock(Page.class);
        when(page.content()).thenReturn("Hello World");
        BasePage bp = bpWith(null, page);

        assertFalse(PageInteractions.getPageSourceContains(bp, "Goodbye"));
    }

    @Test
    public void getPageSize_returnsContextPageCount() {
        BasePage bp = bpWith(null, mock(Page.class));
        BrowserContext ctx = mock(BrowserContext.class);
        when(bp.getContext()).thenReturn(ctx);
        when(ctx.pages()).thenReturn(List.of(mock(Page.class), mock(Page.class)));

        assertEquals(2, PageInteractions.getPageSize(bp));
    }

    @Test
    public void isClosed_trueWhenPageReportsClosed() {
        Page page = mock(Page.class);
        when(page.isClosed()).thenReturn(true);
        BasePage bp = bpWith(null, page);

        assertTrue(PageInteractions.isClosed(bp));
    }

    @Test
    public void isClosed_falseWhenPageReferenceIsNull() {
        BasePage bp = mock(BasePage.class);
        when(bp.getPage()).thenReturn(null);

        assertFalse(PageInteractions.isClosed(bp));
    }

    @Test
    public void bringToFront_delegatesToPage() {
        Page page = mock(Page.class);
        BasePage bp = bpWith(null, page);

        PageInteractions.bringToFront(bp);

        verify(page).bringToFront();
    }

    @Test
    public void keyDown_focusesElementThenPressesKeyDown() {
        Page page = mock(Page.class);
        Keyboard keyboard = mock(Keyboard.class);
        Locator loc = mock(Locator.class);
        when(page.keyboard()).thenReturn(keyboard);
        BasePage bp = bpWith(null, page);
        when(bp.locatorInternal(anyString())).thenReturn(loc);

        PageInteractions.keyDown(bp, "#input", "Shift");

        verify(loc).focus();
        verify(keyboard).down("Shift");
    }

    @Test
    public void keyUp_focusesElementThenReleasesKey() {
        Page page = mock(Page.class);
        Keyboard keyboard = mock(Keyboard.class);
        Locator loc = mock(Locator.class);
        when(page.keyboard()).thenReturn(keyboard);
        BasePage bp = bpWith(null, page);
        when(bp.locatorInternal(anyString())).thenReturn(loc);

        PageInteractions.keyUp(bp, "#input", "Shift");

        verify(keyboard).up("Shift");
    }

    @Test
    public void press_delegatesToLocatorPress() {
        Locator loc = mock(Locator.class);
        BasePage bp = bpWith(null, mock(Page.class));
        when(bp.locatorInternal(anyString())).thenReturn(loc);

        PageInteractions.press(bp, "#input", "Enter");

        verify(loc).press("Enter");
    }

    @Test
    public void waitForTimeout_delegatesToPage() {
        Page page = mock(Page.class);
        BasePage bp = bpWith(null, page);

        PageInteractions.waitForTimeout(bp, 500);

        verify(page).waitForTimeout(500.0);
    }

    @Test
    public void acceptAlert_registersOnceDialogHandler() {
        Page page = mock(Page.class);
        BasePage bp = bpWith(null, page);

        PageInteractions.acceptAlert(bp);

        verify(page).onceDialog(any());
    }

    @Test
    public void acceptAlert_withTrigger_runsTrigger() {
        Page page = mock(Page.class);
        BasePage bp = bpWith(null, page);
        Runnable trigger = mock(Runnable.class);

        PageInteractions.acceptAlert(bp, trigger);

        verify(page).onceDialog(any());
        verify(trigger).run();
    }

    @Test
    public void dismissAlert_registersOnceDialogHandler() {
        Page page = mock(Page.class);
        BasePage bp = bpWith(null, page);

        PageInteractions.dismissAlert(bp);

        verify(page).onceDialog(any());
    }

    @Test
    public void takeScreenshot_usesPageWhenNoFrame() {
        Page page = mock(Page.class);
        byte[] bytes = new byte[]{1, 2, 3};
        when(page.screenshot()).thenReturn(bytes);
        BasePage bp = bpWith(null, page);

        assertArrayEquals(bytes, PageInteractions.takeScreenshot(bp));
    }

    @Test
    public void takeElementScreenshot_usesLocator() {
        Locator loc = mock(Locator.class);
        byte[] bytes = new byte[]{4, 5};
        when(loc.screenshot()).thenReturn(bytes);
        BasePage bp = bpWith(null, mock(Page.class));
        when(bp.locatorInternal(anyString())).thenReturn(loc);

        assertArrayEquals(bytes, PageInteractions.takeElementScreenshot(bp, "#el"));
    }

    @Test
    public void getElementBoundingBox_usesLocator() {
        Locator loc = mock(Locator.class);
        BoundingBox box = new BoundingBox();
        when(loc.boundingBox()).thenReturn(box);
        BasePage bp = bpWith(null, mock(Page.class));
        when(bp.locatorInternal(anyString())).thenReturn(loc);

        assertEquals(box, PageInteractions.getElementBoundingBox(bp, "#el"));
    }
}
