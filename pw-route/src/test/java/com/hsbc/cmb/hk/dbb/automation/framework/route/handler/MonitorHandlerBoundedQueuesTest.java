package com.hsbc.cmb.hk.dbb.automation.framework.route.handler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CT2-18 契约：{@code MonitorHandler} 的「Body 读取池」与「即时读体协调池」必须是<b>有界</b>队列。
 *
 * <p>原实现用无界 {@code LinkedBlockingQueue} —— 没有任何背压：CDP 读体一旦变慢（与 CT2-07 的
 * 线程阻塞叠加），任务与其持有的 {@code Route}/{@code Request}/{@code Response} 引用会无界堆积。
 *
 * <p>判据：{@code ArrayBlockingQueue.remainingCapacity()} 有限，而无界 {@code LinkedBlockingQueue}
 * 的 {@code remainingCapacity()} 恒为 {@code Integer.MAX_VALUE}。以此作为有界性的确定性判据，
 * 无需真实浏览器与网络。
 */
class MonitorHandlerBoundedQueuesTest {

    private static ThreadPoolExecutor executor(String fieldName) throws Exception {
        Field f = MonitorHandler.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (ThreadPoolExecutor) f.get(null);
    }

    @Test
    @DisplayName("CT2-18：两个 body 相关执行器都必须使用有界队列（否则无背压 → 引用无界堆积）")
    void monitorExecutorsUseBoundedQueues() throws Exception {
        ThreadPoolExecutor bodyRead = executor("bodyReadExecutor");
        ThreadPoolExecutor bodyCapture = executor("bodyCaptureExecutor");

        assertTrue(bodyRead.getQueue().remainingCapacity() < Integer.MAX_VALUE,
                "bodyReadExecutor 队列必须是有界的（无界队列无背压 → Response 引用无界堆积）；"
                        + "实际 remainingCapacity=" + bodyRead.getQueue().remainingCapacity());
        assertTrue(bodyCapture.getQueue().remainingCapacity() < Integer.MAX_VALUE,
                "bodyCaptureExecutor 队列必须是有界的（无界队列无背压 → 协调任务无界堆积）；"
                        + "实际 remainingCapacity=" + bodyCapture.getQueue().remainingCapacity());
    }
}
