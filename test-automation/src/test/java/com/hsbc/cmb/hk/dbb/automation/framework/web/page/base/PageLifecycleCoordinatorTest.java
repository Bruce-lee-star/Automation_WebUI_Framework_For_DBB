package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.TimeoutException;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PageLifecycleCoordinator 委派类行为护盾（T5-5 模块 5）：验证页面切换 / 弹窗 / 下载 / 关闭的
 * 编排逻辑正确转发到 BasePage 的包级私有生命周期 seam（isPageClosed / onPageSwitched /
 * setPageReference / safeBringToFront / findLastAvailablePage）及 Playwright Page/Context API；
 * 纯 Mockito 隔离、不依赖浏览器。
 *
 * <p>本测试与 BasePage 同包，以便对包级私有 seam 做白盒校验（verify(bp).setPageReference(...) 等）
 * 并直接注入 protected 的 {@code page} / {@code context} 字段（Mockito 桩化 ensureContextValid 后
 * 字段需由测试显式赋值，以贴合原实现"先 ensureContextValid 再读字段"的语义）。
 */
public class PageLifecycleCoordinatorTest {

    private static BasePage mockBp() {
        BasePage bp = mock(BasePage.class);
        Page page = mock(Page.class);
        when(bp.getPage()).thenReturn(page);
        when(bp.getPageRaw()).thenReturn(page);   // logPageSwitchInfo 读取当前 page 的 url/title
        return bp;
    }

    private static BrowserContext mockContext(BasePage bp, List<Page> pages) {
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.pages()).thenReturn(pages);
        bp.context = ctx;   // 同包可访问 protected 字段；ensureContextValid 被桩化为 no-op
        return ctx;
    }

    // ===================== switchToPage(int) =====================

    @Test
    public void switchToPage_byIndex_delegatesToSeams() {
        BasePage bp = mockBp();
        Page p0 = mock(Page.class), p1 = mock(Page.class);
        mockContext(bp, Arrays.asList(p0, p1));
        PageLifecycleCoordinator.switchToPage(bp, 1);
        verify(bp).setPageReference(p1);
        verify(bp).safeBringToFront();
        verify(bp).onPageSwitched();
    }

    @Test(expected = TimeoutException.class)
    public void switchToPage_byIndex_throwsWhenContextEmpty() {
        BasePage bp = mockBp();
        mockContext(bp, Collections.emptyList());
        PageLifecycleCoordinator.switchToPage(bp, 0);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void switchToPage_byIndex_throwsWhenOutOfRange() {
        BasePage bp = mockBp();
        mockContext(bp, Arrays.asList(mock(Page.class)));
        PageLifecycleCoordinator.switchToPage(bp, 5);
    }

    @Test
    public void switchToPage_byNegativeIndex_fallsBackWhenTargetClosed() {
        BasePage bp = mockBp();
        Page p0 = mock(Page.class), p1 = mock(Page.class);
        List<Page> pages = Arrays.asList(p0, p1);
        mockContext(bp, pages);
        when(bp.isPageClosed(p1)).thenReturn(true);          // 目标（index -1 = p1）已关闭
        when(bp.findLastAvailablePage(pages, 1)).thenReturn(p0);
        when(bp.isPageClosed(p0)).thenReturn(false);
        PageLifecycleCoordinator.switchToPage(bp, -1);
        verify(bp).findLastAvailablePage(pages, 1);          // 触发回退兜底
        verify(bp).setPageReference(p0);
    }

    @Test(expected = TimeoutException.class)
    public void switchToPage_byIndex_throwsWhenTargetClosedNoFallback() {
        BasePage bp = mockBp();
        Page p0 = mock(Page.class);
        mockContext(bp, Arrays.asList(p0));
        when(bp.isPageClosed(p0)).thenReturn(true);
        PageLifecycleCoordinator.switchToPage(bp, 0);
    }

    // ===================== switchToPage(Page) =====================

    @Test(expected = IllegalArgumentException.class)
    public void switchToPage_byPage_rejectsNull() {
        PageLifecycleCoordinator.switchToPage(mockBp(), null);
    }

    @Test(expected = TimeoutException.class)
    public void switchToPage_byPage_rejectsClosed() {
        BasePage bp = mockBp();
        Page p = mock(Page.class);
        when(p.isClosed()).thenReturn(true);
        PageLifecycleCoordinator.switchToPage(bp, p);
    }

    @Test
    public void switchToPage_byPage_delegatesAndReturnsPage() {
        BasePage bp = mockBp();
        Page p = mock(Page.class);
        when(p.isClosed()).thenReturn(false);
        assertSame(p, PageLifecycleCoordinator.switchToPage(bp, p));
        verify(bp).setPageReference(p);
        verify(bp).safeBringToFront();
        verify(bp).onPageSwitched();
    }

    // ===================== waitForNewPage =====================

    @Test
    public void waitForNewPage_withTrigger_acceptsAndSwitches() {
        BasePage bp = mockBp();
        Page p0 = mock(Page.class), newPage = mock(Page.class);
        mockContext(bp, Arrays.asList(p0));
        when(bp.context.waitForPage(any())).thenReturn(newPage);
        assertSame(newPage, PageLifecycleCoordinator.waitForNewPage(bp, () -> { }, 15));
        verify(bp).setPageReference(newPage);
        verify(bp).onPageSwitched();
    }

    @Test
    public void waitForNewPage_noTrigger_fastPathUsesExistingPage() {
        BasePage bp = mockBp();
        Page current = bp.getPageRaw();
        Page other = mock(Page.class);
        mockContext(bp, Arrays.asList(current, other));
        assertSame(other, PageLifecycleCoordinator.waitForNewPage(bp, 10));
        verify(bp).setPageReference(other);
        verify(bp).onPageSwitched();
    }

    @Test(expected = TimeoutException.class)
    public void waitForNewPage_wrapsTimeoutAsTimeoutException() {
        BasePage bp = mockBp();
        Page current = bp.getPageRaw();
        mockContext(bp, Arrays.asList(current));   // 仅当前页且不算"新页面"，走慢路径
        when(bp.context.waitForPage(any())).thenThrow(new com.microsoft.playwright.PlaywrightException("to"));
        PageLifecycleCoordinator.waitForNewPage(bp, 10);
    }

    // ===================== waitForDownload =====================

    @Test
    public void waitForDownload_delegatesToPage() {
        BasePage bp = mockBp();
        Page page = bp.getPage();
        Runnable trigger = mock(Runnable.class);
        // 模拟 Playwright 在下载触发后执行回调（与 waitForDownload 真实语义一致）
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(page).waitForDownload(any(Page.WaitForDownloadOptions.class), any());
        PageLifecycleCoordinator.waitForDownload(bp, trigger, 15);
        verify(page).waitForDownload(any(Page.WaitForDownloadOptions.class), any());
        verify(trigger).run();
    }

    // ===================== closeCurrentPage =====================

    @Test
    public void closeCurrentPage_multiplePages_closesCurrentAndSwitchesBack() {
        BasePage bp = mockBp();
        Page p0 = mock(Page.class), p1 = mock(Page.class);
        when(p1.context()).thenReturn(mock(BrowserContext.class));  // markFrameworkClose 需要非 null context
        bp.page = p1;                                // 当前页为 p1
        mockContext(bp, Arrays.asList(p0, p1));
        PageLifecycleCoordinator.closeCurrentPage(bp);
        verify(bp).setPageReference(p0);            // 回退到 index 0
        verify(bp).onPageSwitched();
    }

    @Test
    public void closeCurrentPage_singlePage_skipsClose() {
        BasePage bp = mockBp();
        Page only = mock(Page.class);
        bp.page = only;
        mockContext(bp, Arrays.asList(only));
        PageLifecycleCoordinator.closeCurrentPage(bp);
        verify(bp, never()).setPageReference(any());   // 仅当 page != only 才切；此处相等，不应调用
    }

    @Test
    public void closeCurrentPage_emptyContext_nullsPage() {
        BasePage bp = mockBp();
        mockContext(bp, Collections.emptyList());
        PageLifecycleCoordinator.closeCurrentPage(bp);
        verify(bp, never()).setPageReference(any());   // 空 context 不应有任何 setPageReference
    }

    // ===================== closeOtherPages =====================

    @Test
    public void closeOtherPages_closesAllExceptCurrent() {
        BasePage bp = mockBp();
        Page p0 = mock(Page.class), p1 = mock(Page.class), p2 = mock(Page.class);
        when(p0.context()).thenReturn(mock(BrowserContext.class));  // markFrameworkClose 需要非 null context
        when(p2.context()).thenReturn(mock(BrowserContext.class));
        bp.page = p1;                               // 当前页 p1 保留
        mockContext(bp, Arrays.asList(p0, p1, p2));
        // 默认 isClosed=false，因此 p0/p2 均会被 close()
        PageLifecycleCoordinator.closeOtherPages(bp);
        verify(p0).close();
        verify(p2).close();
    }

    @Test
    public void closeOtherPages_singlePage_skips() {
        BasePage bp = mockBp();
        Page only = mock(Page.class);
        bp.page = only;
        mockContext(bp, Arrays.asList(only));
        PageLifecycleCoordinator.closeOtherPages(bp);
        verify(only, never()).close();             // 仅当前页在列表中，p == page 跳过，不应 close
    }

    // ===================== 直接校验包级私有兜底 seam =====================

    @Test
    public void findLastAvailablePage_seamInvokedOnNegativeClosedIndex() {
        BasePage bp = mockBp();
        Page p0 = mock(Page.class), p1 = mock(Page.class);
        List<Page> pages = Arrays.asList(p0, p1);
        mockContext(bp, pages);
        when(bp.isPageClosed(p1)).thenReturn(true);
        when(bp.findLastAvailablePage(pages, 1)).thenReturn(p0);
        when(bp.isPageClosed(p0)).thenReturn(false);
        PageLifecycleCoordinator.switchToPage(bp, -1);
        verify(bp).findLastAvailablePage(pages, 1);
    }
}
