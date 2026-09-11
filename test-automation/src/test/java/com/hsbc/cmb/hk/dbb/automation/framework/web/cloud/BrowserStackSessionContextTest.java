package com.hsbc.cmb.hk.dbb.automation.framework.web.cloud;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

/**
 * T3-1 收拢验证：{@link BrowserStackManager} 的会话态（sessionId / sessionUrl / tempCaps）
 * 已从 {@code static ThreadLocal} 收拢进 {@link TestContextHolder}（per-thread 等价）。
 * 本测试证明收拢后 per-thread 隔离正确、并发写入安全。
 */
public class BrowserStackSessionContextTest {

    @Test
    public void perThreadSessionIsolation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            BrowserStackManager.setCurrentSessionId("main-session");
            Future<String> otherSees = pool.submit(BrowserStackManager::getCurrentSessionId);
            String other = otherSees.get(5, TimeUnit.SECONDS);

            assertNull( other, "其他线程不应看到主线程设置的 session");
            assertEquals( "main-session",  BrowserStackManager.getCurrentSessionId(), "主线程仍读到自身 session");
            assertNotNull( BrowserStackManager.getCurrentSessionUrl(), "主线程的 dashboard URL 应已生成");
        } finally {
            TestContextHolder.resetForCurrentThread();
            pool.shutdown();
        }
    }

    @Test
    public void concurrentSetSessionDoesNotThrow() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int id = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    BrowserStackManager.setCurrentSessionId("session-" + id);
                    assertNotNull(BrowserStackManager.getCurrentSessionId());
                    return true;
                } finally {
                    TestContextHolder.resetForCurrentThread();
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
