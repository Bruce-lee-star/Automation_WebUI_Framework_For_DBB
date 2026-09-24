package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link PageEventMonitor} 幂等护栏在并发下的契约：无论多少线程对同一对象调用 {@code register}，
 * 诊断 handler 只注册一次（杜绝重复注册导致 API 抓取/下载/诊断被双重处理）。
 *
 * <p>纯 Mockito 隔离，不依赖浏览器运行时；与 {@link PageEventMonitor} 同包以访问 package-private 行为。</p>
 */
@DisplayName("PageEventMonitor：register 幂等护栏在并发下不重复注册 handler")
class PageEventMonitorConcurrencyTest {

    @Test
    @DisplayName("同一 BrowserContext 被多线程并发 register：context.onPage 仅注册一次")
    void registerContextExactlyOnceUnderConcurrency() throws InterruptedException {
        BrowserContext context = mock(BrowserContext.class);
        runConcurrent(16, () -> PageEventMonitor.register(context));
        verify(context, times(1)).onPage(any());
    }

    @Test
    @DisplayName("同一 Page 被多线程并发 register：四个诊断 handler 各仅注册一次")
    void registerPageExactlyOnceUnderConcurrency() throws InterruptedException {
        Page page = mock(Page.class);
        runConcurrent(16, () -> PageEventMonitor.register(page));
        verify(page, times(1)).onPageError(any());
        verify(page, times(1)).onConsoleMessage(any());
        verify(page, times(1)).onRequestFailed(any());
        verify(page, times(1)).onCrash(any());
    }

    @Test
    @DisplayName("不同对象并发 register 互不干扰：各自独立注册一次")
    void differentObjectsRegisterIndependently() throws InterruptedException {
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
    @DisplayName("并发 null 注册不抛异常")
    void concurrentNullRegistrationIsSafe() throws InterruptedException {
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
