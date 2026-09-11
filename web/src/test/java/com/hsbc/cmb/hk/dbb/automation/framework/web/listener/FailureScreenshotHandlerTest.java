package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import net.thucydides.model.screenshots.ScreenshotAndHtmlSource;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link FailureScreenshotHandler} 的收拢验证（T3-1）：原 per-thread 重入守卫
 * {@code ThreadLocal<Boolean>} 已收拢为 {@code TestContext}/{@code ContextKey<Boolean>}。
 *
 * <p>web 模块无 Mockito，无法 stub {@link PlaywrightManager#takeScreenshot(String)}（依赖浏览器 Page）。
 * 本测试覆盖迁移后守卫路径的<b>安全不抛</b>与<b>并发不互相干扰</b>；per-thread 隔离性本身已由
 * {@code TestContextConcurrencyTest}（core）保证。</p>
 */
public class FailureScreenshotHandlerTest {

    /** 无浏览器 / 无 Page 时 takeScreenshot 返回 null，capture 须安全返回 null 且不抛（守卫路径不 NPE）。 */
    @Test
    public void captureReturnsNullSafelyWhenScreenshotUnavailable() {
        ScreenshotAndHtmlSource result = FailureScreenshotHandler.capture("someName");
        assertNull(result);
    }

    /** 两线程并发 capture 互不干扰、不抛（per-thread 重入守卫经 TestContext 隔离）。 */
    @Test
    public void concurrentCaptureDoesNotThrowOrInterfere() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        assertNull(FailureScreenshotHandler.capture("t" + Thread.currentThread().getId()));
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    }
                });
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
        if (error.get() != null) {
            throw new AssertionError("concurrent capture failed", error.get());
        }
    }
}
