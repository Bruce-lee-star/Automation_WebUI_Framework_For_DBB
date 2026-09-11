package com.hsbc.cmb.hk.dbb.automation.tests.api.steps;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3-1 收拢验证：{@link ApiTestContext} 的共享 {@code BaseStep}（原 static ThreadLocal
 * {@code BASE_STEP}）已迁入 {@link com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder}。
 * 本测试证明收拢后 per-thread（scenario）隔离正确：未初始化的线程读 {@code baseStep()} 抛异常，
 * 不会串读到其他线程已初始化的实例。
 */
public class ApiTestContextConcurrencyTest {

    @Test
    public void perThreadBaseStepIsolation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            ApiTestContext.init("entityA");
            Future<Boolean> otherSees = pool.submit(() -> {
                try {
                    ApiTestContext.baseStep();
                    return false;
                } catch (IllegalStateException expected) {
                    return true;
                }
            });
            assertTrue( otherSees.get(5, TimeUnit.SECONDS), "其他线程不应看到主线程的 BaseStep（隔离）");
            assertNotNull( ApiTestContext.baseStep(), "主线程仍读到自身 BaseStep");
        } finally {
            ApiTestContext.clear();
            pool.shutdown();
        }
    }
}
