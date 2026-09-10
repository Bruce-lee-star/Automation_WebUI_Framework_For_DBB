package com.hsbc.cmb.hk.dbb.automation.framework.route.core;

import com.microsoft.playwright.BrowserContext;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * T3-1 收拢验证：{@link ApiCaptureLifecycle} 的当前 Context 绑定（原 static ThreadLocal
 * {@code CURRENT_CONTEXT}）已迁入 {@link com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder}。
 * 本测试证明收拢后 per-thread 绑定隔离正确、并发绑定安全。
 */
public class ApiCaptureLifecycleContextTest {

    @Test
    public void perThreadContextIsolation() throws Exception {
        BrowserContext ctxA = mock(BrowserContext.class);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            ApiCaptureLifecycle.bindCurrentContext(ctxA);
            Future<BrowserContext> otherSees = pool.submit(ApiCaptureLifecycle::currentContextOrNull);
            BrowserContext other = otherSees.get(5, TimeUnit.SECONDS);

            assertNull("其他线程不应看到主线程绑定的 Context", other);
            assertSame("主线程仍读到自身 Context", ctxA, ApiCaptureLifecycle.currentContextOrNull());
        } finally {
            ApiCaptureLifecycle.unbindCurrentContext();
            pool.shutdown();
        }
    }

    @Test
    public void concurrentBindDoesNotThrow() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    BrowserContext ctx = mock(BrowserContext.class);
                    ApiCaptureLifecycle.bindCurrentContext(ctx);
                    assertSame(ctx, ApiCaptureLifecycle.currentContextOrNull());
                    return true;
                } finally {
                    ApiCaptureLifecycle.unbindCurrentContext();
                }
            }));
        }
        start.countDown();
        for (Future<Boolean> f : futures) {
            assertTrue(f.get(5, TimeUnit.SECONDS));
        }
        pool.shutdown();
    }
}
