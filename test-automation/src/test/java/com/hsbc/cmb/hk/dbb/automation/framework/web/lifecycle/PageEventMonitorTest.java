package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.PageEventMonitor;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.function.Consumer;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 回归护盾：锁定 {@link PageEventMonitor} 的页面级诊断监听<b>注册行为与幂等性</b>。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code register(BrowserContext)} 经 {@code context.onPage} 接线，并对新页面传播全部监听；</li>
 *   <li>{@code register(Page)} 对同一 Page 幂等（多次调用仅注册一次）；</li>
 *   <li>null 安全（不抛异常、无交互）。</li>
 * </ul>
 *
 * <p>纯 Mockito 隔离，不依赖浏览器运行时；与 {@link PageEventMonitor} 同包以访问其 package-private 静态方法。
 */
@SuppressWarnings("unchecked")
public class PageEventMonitorTest {

    @Test
    public void registerContextWiresOnPageAndPropagatesToNewPages() {
        BrowserContext context = mock(BrowserContext.class);
        ArgumentCaptor<Consumer<Page>> captor = ArgumentCaptor.forClass(Consumer.class);

        PageEventMonitor.register(context);

        verify(context, times(1)).onPage(captor.capture());

        Page newPage = mock(Page.class);
        captor.getValue().accept(newPage);

        verify(newPage, times(1)).onPageError(any());
        verify(newPage, times(1)).onConsoleMessage(any());
        verify(newPage, times(1)).onRequestFailed(any());
        verify(newPage, times(1)).onCrash(any());
        verify(newPage, times(1)).onClose(any());
    }

    @Test
    public void registerPageIsIdempotent() {
        Page page = mock(Page.class);

        PageEventMonitor.register(page);
        PageEventMonitor.register(page);

        verify(page, times(1)).onPageError(any());
        verify(page, times(1)).onConsoleMessage(any());
        verify(page, times(1)).onRequestFailed(any());
        verify(page, times(1)).onCrash(any());
        verify(page, times(1)).onClose(any());
    }

    @Test
    public void registerNullSafe() {
        // 不应抛异常
        PageEventMonitor.register((Page) null);
        PageEventMonitor.register((BrowserContext) null);
    }

    @Test
    public void pageErrorHandlerSafeWhenFlagOff() {
        // 默认开关（playwright.page.error.failOnError=false）下：onPageError 仅记录日志，
        // 不抛异常、不收集；drain 返回空列表（契约保证非空、不抛 NPE）。
        Page page = mock(Page.class);
        PageEventMonitor.register(page);

        ArgumentCaptor<Consumer<String>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(page).onPageError(captor.capture());

        captor.getValue().accept("boom: undefined is not a function");
        assertTrue(PageEventMonitor.drainPendingPageErrors().isEmpty());
    }
}
