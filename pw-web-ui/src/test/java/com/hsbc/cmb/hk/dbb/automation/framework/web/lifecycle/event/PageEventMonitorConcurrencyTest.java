package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link PageEventMonitor} 订阅契约：<b>幂等</b>（并发下每项至多注册一次；关闭时一次都不注册）
 * × <b>配置驱动</b>（{@code playwright.page.events.*}）。
 *
 * <p><b>契约</b>：
 * <ul>
 *   <li>{@code playwright.page.events.page.enabled}（默认 <b>true</b>）是 {@code context.onPage} 扇出总闸
 *       —— 开启时并发 register 只注册一次；</li>
 *   <li>四项诊断（console / pageError / requestFailed / crash）默认 <b>false</b>
 *       —— 关闭时不注册任何 handler（服务端不下发对应事件，从根上消除驱动侧对象回收类竞态）；</li>
 *   <li>显式开启后，仍保证并发下每项只注册一次（幂等护栏不受配置影响）。</li>
 * </ul>
 *
 * <p>纯 Mockito 隔离，不依赖浏览器运行时；与 {@link PageEventMonitor} 同包以访问 package-private 行为。
 */
public class PageEventMonitorConcurrencyTest {

    /** 四个诊断订阅的配置键（默认 false）。 */
    private static final String KEY_CONSOLE = "playwright.page.events.console.enabled";
    private static final String KEY_PAGE_ERROR = "playwright.page.events.pageError.enabled";
    private static final String KEY_REQUEST_FAILED = "playwright.page.events.requestFailed.enabled";
    private static final String KEY_CRASH = "playwright.page.events.crash.enabled";

    @Test
    // @DisplayName: "onPage 扇出（默认开启）：同一 BrowserContext 多线程并发 register，context.onPage 仅注册一次"
    public void registerContextExactlyOnceUnderConcurrency() throws InterruptedException {
        BrowserContext context = mock(BrowserContext.class);
        runConcurrent(16, () -> PageEventMonitor.register(context));
        verify(context, times(1)).onPage(any());
    }

    @Test
    // @DisplayName: "诊断订阅默认关闭：register(page) 四个诊断 handler 一个都不注册"
    public void diagnosticsDisabledByDefault_subscribesNothing() {
        Page page = mock(Page.class);

        PageEventMonitor.register(page);

        verify(page, never()).onPageError(any());
        verify(page, never()).onConsoleMessage(any());
        verify(page, never()).onRequestFailed(any());
        verify(page, never()).onCrash(any());
    }

    @Test
    // @DisplayName: "四项诊断显式开启后：同一 Page 多线程并发 register，每项各仅注册一次"
    public void enabledDiagnosticsAreRegisteredExactlyOnceUnderConcurrency() throws InterruptedException {
        //  ConfigSource.resolve 实时读 System.getProperty，故测试内 toggle 即时生效；用完必须复位防污染
        System.setProperty(KEY_CONSOLE, "true");
        System.setProperty(KEY_PAGE_ERROR, "true");
        System.setProperty(KEY_REQUEST_FAILED, "true");
        System.setProperty(KEY_CRASH, "true");
        try {
            Page page = mock(Page.class);
            runConcurrent(16, () -> PageEventMonitor.register(page));

            verify(page, times(1)).onPageError(any());
            verify(page, times(1)).onConsoleMessage(any());
            verify(page, times(1)).onRequestFailed(any());
            verify(page, times(1)).onCrash(any());
        } finally {
            System.clearProperty(KEY_CONSOLE);
            System.clearProperty(KEY_PAGE_ERROR);
            System.clearProperty(KEY_REQUEST_FAILED);
            System.clearProperty(KEY_CRASH);
        }
    }

    @Test
    // @DisplayName: "不同对象并发 register 互不干扰：各自独立注册一次"
    public void differentObjectsRegisterIndependently() throws InterruptedException {
        BrowserContext c1 = mock(BrowserContext.class);
        BrowserContext c2 = mock(BrowserContext.class);
        runConcurrent(2, () -> {
            PageEventMonitor.register(c1);
            PageEventMonitor.register(c2);
        });
        verify(c1, times(1)).onPage(any());
        verify(c2, times(1)).onPage(any());
    }

    @Test
    // @DisplayName: "并发 null 注册不抛异常"
    public void concurrentNullRegistrationIsSafe() throws InterruptedException {
        runConcurrent(8, () -> {
            PageEventMonitor.register((Page) null);
            PageEventMonitor.register((BrowserContext) null);
        });
    }

    private static void runConcurrent(int n, Runnable task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < n; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        task.run();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
        }
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }
}
