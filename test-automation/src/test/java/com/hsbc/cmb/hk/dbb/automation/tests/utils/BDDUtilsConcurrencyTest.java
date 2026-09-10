package com.hsbc.cmb.hk.dbb.automation.tests.utils;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertSame;

/**
 * {@link BDDUtils} 登录信息收拢验证（T3-1）：原 {@code static ThreadLocal<BDDUtils>} 已收拢为
 * {@code TestContext}/{@code ContextKey<BDDUtils>}。验证并行 scenario 线程各自持有独立登录态、互不串扰。
 */
public class BDDUtilsConcurrencyTest {

    private static BDDUtils newInstance() throws Exception {
        Constructor<BDDUtils> c = BDDUtils.class.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    @Test
    public void loginInfoIsolatedAcrossThreads() throws Exception {
        final BDDUtils i1 = newInstance();
        final BDDUtils i2 = newInstance();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<BDDUtils> taskA = () -> {
                try {
                    BDDUtils.setCurrentLoginInfo(i1);
                    return BDDUtils.getCurrentLoginInfo();
                } finally {
                    BDDUtils.clearCurrentLoginInfo();
                    TestContextHolder.resetForCurrentThread();
                }
            };
            Callable<BDDUtils> taskB = () -> {
                try {
                    BDDUtils.setCurrentLoginInfo(i2);
                    return BDDUtils.getCurrentLoginInfo();
                } finally {
                    BDDUtils.clearCurrentLoginInfo();
                    TestContextHolder.resetForCurrentThread();
                }
            };
            Future<BDDUtils> fa = pool.submit(taskA);
            Future<BDDUtils> fb = pool.submit(taskB);
            assertSame(i1, fa.get());
            assertSame(i2, fb.get());
        } finally {
            pool.shutdown();
        }
    }
}
