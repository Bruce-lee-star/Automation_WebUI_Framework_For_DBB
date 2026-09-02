package com.hsbc.cmb.hk.dbb.automation.tests.web;

import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStackManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkState;
import org.junit.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * T4-2 去全局变态的并发隔离验证（阶段二串扰验证的本地等价物）。
 *
 * <p>直接验证 {@code lastException} 与 BrowserStack session 的 ThreadLocal 化，在多线程并行场景下
 * 互不串扰。不依赖真实 BrowserStack / Playwright，纯内存并发断言——是真实环境放开并行开关前，
 * 可在本地确定性复现的"无串扰"证据。</p>
 */
public class FrameworkStateConcurrencyTest {

    private static final int THREADS = 8;

    /** lastException：多线程各自 set 不同异常，每个线程必须只读到自己的（ThreadLocal 隔离）。 */
    @Test
    public void lastExceptionIsolatedAcrossThreads() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        AtomicReferenceArray<Exception> results = new AtomicReferenceArray<>(THREADS);
        Thread[] threads = new Thread[THREADS];

        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                try {
                    barrier.await(); // 同时开始，制造最大竞争窗口
                    FrameworkState.getInstance().setLastException(new DummyException(idx));
                    Thread.yield();   // 让出 CPU，制造调度交错
                    results.set(idx, FrameworkState.getInstance().getLastException());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "scenario-" + idx);
            threads[i].start();
        }
        for (Thread t : threads) t.join(10000);

        for (int i = 0; i < THREADS; i++) {
            Exception e = results.get(i);
            assertEquals("线程 " + i + " 应只读到自身异常，不得串扰到他人", "err-" + i, e.getMessage());
        }
    }

    /** lastException：线程 A 设值后，从未设置的线程 B 必须读到 null（不串扰）。 */
    @Test
    public void unsetThreadSeesNullLastException() throws Exception {
        FrameworkState.getInstance().setLastException(new DummyException(99));
        assertEquals("err-99", FrameworkState.getInstance().getLastException().getMessage());

        AtomicReference<Exception> seen = new AtomicReference<>();
        Thread reader = new Thread(() -> seen.set(FrameworkState.getInstance().getLastException()), "fresh-reader");
        reader.start();
        reader.join(5000);

        assertNull("未设置过的线程必须读到 null，不得串扰到主线程异常", seen.get());
    }

    /** BrowserStack currentSessionId/Url：多线程各自 set，每个线程只读到自己的 session。 */
    @Test
    public void browserStackSessionIsolatedAcrossThreads() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        AtomicReferenceArray<String> idResults = new AtomicReferenceArray<>(THREADS);
        AtomicReferenceArray<String> urlResults = new AtomicReferenceArray<>(THREADS);
        Thread[] threads = new Thread[THREADS];

        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                try {
                    barrier.await();
                    BrowserStackManager.setCurrentSessionId("session-" + idx);
                    Thread.yield();
                    idResults.set(idx, BrowserStackManager.getCurrentSessionId());
                    urlResults.set(idx, BrowserStackManager.getCurrentSessionUrl());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "cloud-scenario-" + idx);
            threads[i].start();
        }
        for (Thread t : threads) t.join(10000);

        for (int i = 0; i < THREADS; i++) {
            assertEquals("线程 " + i + " 应只读到自身 sessionId", "session-" + i, idResults.get(i));
            assertEquals("线程 " + i + " 应只读到自身 sessionUrl",
                    "https://automate.browserstack.com/dashboard/v2/sessions/session-" + i,
                    urlResults.get(i));
        }
    }

    /** BrowserStack currentSessionId：线程 A 设值，未设置线程 B 必须读到 null。 */
    @Test
    public void unsetThreadSeesNullBrowserStackSession() throws Exception {
        BrowserStackManager.setCurrentSessionId("session-main");
        assertEquals("session-main", BrowserStackManager.getCurrentSessionId());

        AtomicReference<String> seen = new AtomicReference<>("dirty");
        Thread reader = new Thread(() -> seen.set(BrowserStackManager.getCurrentSessionId()), "fresh-reader");
        reader.start();
        reader.join(5000);

        assertNull("未设置过的线程必须读到 null，不得串扰到主线程 session", seen.get());
    }

    private static final class DummyException extends Exception {
        DummyException(int id) { super("err-" + id); }
    }
}
