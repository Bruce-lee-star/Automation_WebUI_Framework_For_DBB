package com.hsbc.cmb.hk.dbb.automation.framework.web.utils;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

/**
 * T3-1 收拢验证：{@link NLSUtils} 的线程级语言覆盖 {@code threadLangOverride}
 * 已从 {@code static ThreadLocal} 收拢进 {@link TestContextHolder}（per-thread 等价）。
 * 本测试证明收拢后单线程双轨语义不变，且并发调用安全（不抛异常、不串数据导致 NPE）。
 *
 * <p>注意：{@code globalLang} 是真正的全局值（多 scenario 共享当前语言），其并发串扰属
 * 既有语义，本步不改变；故并发测试仅断言「不抛异常 + 不 NPE」，不断言具体语言值。
 */
public class NLSUtilsLangContextTest {

    @Test
    public void singleThreadSetThenGetReturnsLanguage() {
        try {
            NLSUtils.setLanguage("en");
            assertEquals("en", NLSUtils.getLanguage());
        } finally {
            NLSUtils.reset();
        }
    }

    @Test
    public void resetClearsLanguage() {
        NLSUtils.setLanguage("zh");
        NLSUtils.reset();
        assertNull(NLSUtils.getLanguage());
    }

    @Test
    public void concurrentSetLanguageDoesNotThrow() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    NLSUtils.setLanguage(System.identityHashCode(Thread.currentThread()) % 2 == 0 ? "en" : "zh");
                    NLSUtils.getLanguage(); // 并发下 global 共享语义可能返回 null（已清除），不抛异常即可
                    return true;
                } finally {
                    NLSUtils.reset();
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
