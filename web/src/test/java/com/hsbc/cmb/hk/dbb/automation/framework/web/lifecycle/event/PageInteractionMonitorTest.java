package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归护盾：锁定 {@link PageInteractionMonitor} 的页面级交互事件监听<b>注册行为与处理器语义</b>。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code register(BrowserContext)} 经 {@code context.onPage} 接线，并对新页面传播交互监听；</li>
 *   <li>{@code register(Page)} 仅注册 onFrameNavigated（导航轨迹）与 onPopup（未受管弹窗）两类诊断监听，
 *       不注册 onDialog / onFileChooser（交互处置交回业务层既有方法）；</li>
 *   <li>onFrameNavigated 记录主框架导航轨迹，drain 可回放；</li>
 *   <li>onPopup 记录未受管弹窗，markPopupClaimed 后 drain 清空；</li>
 *   <li>null 安全（注册与处理器均不抛异常）。</li>
 * </ul>
 *
 * <p>纯 Mockito 隔离，不依赖浏览器运行时；与 {@link PageInteractionMonitor} 同包以访问其 package-private 静态方法。
 */
@SuppressWarnings("unchecked")
public class PageInteractionMonitorTest {

    // ===================== 注册接缝 =====================

    @Test
    public void registerContextWiresOnPageAndPropagatesInteractions() {
        BrowserContext context = mock(BrowserContext.class);
        org.mockito.ArgumentCaptor<Consumer<Page>> captor = forClass(Consumer.class);

        PageInteractionMonitor.register(context);

        verify(context, times(1)).onPage(captor.capture());

        Page newPage = mock(Page.class);
        captor.getValue().accept(newPage);

        // 仅导航轨迹 + 未受管弹窗两类诊断；不注册 onDialog / onFileChooser（处置交回业务）
        verify(newPage, times(1)).onFrameNavigated(any());
        verify(newPage, times(1)).onPopup(any());
        verify(newPage, times(0)).onDialog(any());
        verify(newPage, times(0)).onFileChooser(any());
    }

    @Test
    public void registerPageRegistersDiagnosticsOnly() {
        Page page = mock(Page.class);
        PageInteractionMonitor.register(page);

        verify(page, times(1)).onFrameNavigated(any());
        verify(page, times(1)).onPopup(any());
        verify(page, times(0)).onDialog(any());
        verify(page, times(0)).onFileChooser(any());
    }

    // ===================== 1. onFrameNavigated =====================

    @Test
    public void onFrameNavigatedRecordsMainFrameIntoTrail() {
        Page page = mock(Page.class);
        PageInteractionMonitor.register(page);

        org.mockito.ArgumentCaptor<Consumer<Frame>> captor = forClass(Consumer.class);
        verify(page).onFrameNavigated(captor.capture());

        Frame frame = mock(Frame.class);
        when(frame.page()).thenReturn(page);
        when(page.mainFrame()).thenReturn(frame);
        when(frame.url()).thenReturn("https://example.com/dashboard");

        PageInteractionMonitor.drainNavigationTrail(); // 清空本线程残留
        captor.getValue().accept(frame);

        String trail = PageInteractionMonitor.drainNavigationTrail();
        assertTrue(trail.contains("[main] https://example.com/dashboard"), "trail=" + trail);
    }

    // ===================== 2. onPopup =====================

    @Test
    public void onPopupRecordedAndClaimedClears() {
        Page page = mock(Page.class);
        PageInteractionMonitor.register(page);

        org.mockito.ArgumentCaptor<Consumer<Page>> captor = forClass(Consumer.class);
        verify(page).onPopup(captor.capture());

        Page popup = mock(Page.class);
        when(popup.url()).thenReturn("https://example.com/popup");
        when(popup.title()).thenReturn("Report");

        PageInteractionMonitor.drainUnmanagedPopups(); // 清空残留
        captor.getValue().accept(popup);

        String recorded = PageInteractionMonitor.drainUnmanagedPopups();
        assertTrue(recorded.contains("url=https://example.com/popup"), "popups=" + recorded);

        // 框架认领后再次 drain 应为空（已在 markPopupClaimed 中剔除）
        PageInteractionMonitor.markPopupClaimed(popup);
        assertEquals("", PageInteractionMonitor.drainUnmanagedPopups());
    }

    // ===================== null 安全 =====================

    @Test
    public void nullSafeRegistrationAndHandlers() {
        PageInteractionMonitor.register((Page) null);
        PageInteractionMonitor.register((BrowserContext) null);

        // 处理器入参为 null 不应抛异常
        PageInteractionMonitor.markPopupClaimed(null);
        assertEquals("", PageInteractionMonitor.drainNavigationTrail());
        assertEquals("", PageInteractionMonitor.drainUnmanagedPopups());
    }
}
