package com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * T3-1 收拢验证：{@link PageObjectFactory} 的线程隔离实例存储（原静态
 * {@code ThreadLocal<Map<Class<?>, Object>> threadInstances} 已迁入
 * {@link com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder}，
 * 经惰性 helper 等价 per-thread Map）。
 * 本测试证明 THREAD_ISOLATED 实例按线程隔离：其他线程不会读到主线程注册的实例。
 */
public class PageObjectFactoryConcurrencyTest {

    public static class DummyPage {
    }

    @Test
    public void perThreadInstanceIsolation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Object mainInst = PageObjectFactory.getPage(DummyPage.class, PageObjectFactory.LifecycleStrategy.THREAD_ISOLATED);
            Future<Object> other = pool.submit(() ->
                    PageObjectFactory.getPage(DummyPage.class, PageObjectFactory.LifecycleStrategy.THREAD_ISOLATED));
            Object poolInst = other.get(5, TimeUnit.SECONDS);
            assertNotNull(poolInst);
            assertNotSame( mainInst,  poolInst, "其他线程应拿到独立的 THREAD_ISOLATED 实例");
        } finally {
            PageObjectFactory.clearAll();
            pool.shutdown();
        }
    }

    /** WEB-F1 回归：默认策略（不传 strategy）下，并行线程拿到互不相同的实例，不再跨线程共享。 */
    @Test
    public void defaultStrategyIsThreadIsolated() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Object mainInst = PageObjectFactory.getPage(DummyPage.class); // 默认配置
            Future<Object> other = pool.submit(() -> PageObjectFactory.getPage(DummyPage.class));
            Object poolInst = other.get(5, TimeUnit.SECONDS);
            assertNotNull(poolInst);
            assertNotSame(mainInst, poolInst, "默认策略应为 THREAD_ISOLATED：不同线程不得共享同一实例");
        } finally {
            PageObjectFactory.clearAll();
            pool.shutdown();
        }
    }
}
