package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureManager;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * G3 并发安全护盾：共享 Browser + 并发 Context 场景下，多任务各自隔离到本 Context 的采集存储，
 * 不跨任务污染、不抛 ConcurrentModificationException。
 */
public class ApiCaptureContextConcurrencyTest {

    private static CapturedApiCall sampleCall(String endpoint, int i) {
        return new CapturedApiCall.Builder()
                .endpoint(endpoint)
                .method("GET")
                .requestUrl("https://x" + endpoint + "?i=" + i)
                .requestHeaders(java.util.Collections.emptyMap())
                .responseHeaders(java.util.Collections.emptyMap())
                .statusCode(200)
                .responseBody("{\"n\":" + i + "}")
                .timestamp(System.currentTimeMillis())
                .fromMock(false)
                .captureSource("TEST")
                .handleType(RouteHandleType.MONITOR)
                .build();
    }

    @Test
    public void concurrentContextsAreIsolatedAndExceptionFree() throws Exception {
        final int threads = 8;
        final int callsPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        BrowserContext[] contexts = new BrowserContext[threads];
        for (int i = 0; i < threads; i++) {
            contexts[i] = mock(BrowserContext.class);
        }

        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    BrowserContext ctx = contexts[idx];
                    // 绑定到本线程，使采集存储按 Context 隔离路由（并发路径）
                    ApiCaptureContext.bindCurrentContext(ctx);
                    ApiCaptureContext cap = ApiCaptureContext.forContext(ctx);
                    String endpoint = "/api/t" + idx;
                    for (int i = 0; i < callsPerThread; i++) {
                        cap.storeApiCall(sampleCall(endpoint, i));
                    }
                    // 本任务存储隔离自验：数量正确
                    int mine = ApiCaptureManager.getInstance().getApiCalls(endpoint).size();
                    if (mine != callsPerThread) {
                        error.compareAndSet(null,
                                new AssertionError("context " + idx + " expected " + callsPerThread + " got " + mine));
                    }
                    // 跨任务不串扰：其它任务的 endpoint 不应出现在本任务存储中
                    for (int other = 0; other < threads; other++) {
                        if (other == idx) continue;
                        if (!ApiCaptureManager.getInstance().getApiCalls("/api/t" + other).isEmpty()) {
                            error.compareAndSet(null,
                                    new AssertionError("context " + idx + " leaked data from context " + other));
                        }
                    }
                } catch (Throwable th) {
                    error.compareAndSet(null, th);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertNull( error.get(), "concurrent capture must not throw or leak: " + error.get());
    }

    @Test
    public void removeAllContextsIsSafeUnderConcurrentWrites() throws Exception {
        final int writers = 6;
        final int removers = 2;
        ExecutorService pool = Executors.newFixedThreadPool(writers + removers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        BrowserContext[] contexts = new BrowserContext[writers];
        for (int i = 0; i < writers; i++) {
            contexts[i] = mock(BrowserContext.class);
        }

        for (int t = 0; t < writers; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    ApiCaptureContext cap = ApiCaptureContext.forContext(contexts[idx]);
                    for (int i = 0; i < 500; i++) {
                        cap.storeApiCall(sampleCall("/api/w" + idx, i));
                    }
                } catch (Throwable th) {
                    error.compareAndSet(null, th);
                }
            });
        }
        for (int r = 0; r < removers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        ApiCaptureContext.removeAllContexts();
                    }
                } catch (Throwable th) {
                    error.compareAndSet(null, th);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertNull( error.get(), "removeAllContexts under concurrent writes must not throw: " + error.get());
    }
}
