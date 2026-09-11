package com.hsbc.cmb.hk.dbb.automation.framework.api.core.services;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * T3-1 收拢验证：{@link TestServices} 的 per-thread 单例（原静态 {@code ThreadLocal<TestServices> THREAD_INSTANCE}
 * 已迁入 {@link com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder}；原实例级
 * {@code entityName}/{@code env} ThreadLocal 降级为普通实例字段，因实例已 per-thread 而语义等价）。
 * 本测试证明不同线程拿到独立实例、clear 后实例重置。
 */
public class TestServicesConcurrencyTest {

    @Test
    public void perThreadInstanceIsolation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            TestServices main = TestServices.initialize().withEntity("entityA");
            Future<TestServices> other = pool.submit(TestServices::initialize);
            TestServices poolInst = other.get(5, TimeUnit.SECONDS);
            assertNotSame( main,  poolInst, "其他线程应拿到独立的 TestServices 实例");
        } finally {
            TestServices.clear();
            pool.shutdown();
        }
    }

    @Test
    public void clearResetsInstance() {
        TestServices a = TestServices.initialize();
        TestServices.clear();
        TestServices b = TestServices.initialize();
        assertNotSame( a,  b, "clear 后 initialize 应返回新实例");
    }
}
