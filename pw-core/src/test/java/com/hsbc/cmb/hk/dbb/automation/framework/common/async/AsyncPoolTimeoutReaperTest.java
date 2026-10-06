package com.hsbc.cmb.hk.dbb.automation.framework.common.async;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertTrue;

/**
 * CT2-17 契约：超时强制取消由「单一周期裁决器」承担后，语义不得回归。
 *
 * <p>原实现每任务向 SCHEDULER 投一个哨兵，并在在途哨兵数触顶
 * （{@code ASYNC_MAX_PENDING_TIMEOUTS}）后<b>跳过投递</b> —— 即超时保护在全进程范围内静默失效。
 * 现改为「在途带超时任务登记表 + 周期裁决器」，本测试固化「超时仍会被真实
 * {@code cancel(true)} 中断」这一核心语义。
 */
public class AsyncPoolTimeoutReaperTest {

    @Test(timeout = 40_000)
    // @DisplayName: "CT2-17：超时任务必须被 cancel(true) 中断（裁决器取代哨兵后语义不回归）"
    public void timedOutTaskIsCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        long timeoutCountBefore = AsyncPool.getTimeoutCount();

        AsyncPool.runWithTimeout(() -> {
            started.countDown();
            try {
                // 模拟卡死任务：只对中断有反应
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        }, 500);

        assertTrue("任务应在池线程上启动", started.await(10, TimeUnit.SECONDS));
        assertTrue("超时后必须被取消并结束（否则超时保护形同失效）", finished.await(20, TimeUnit.SECONDS));
        assertTrue("超时任务应收到 cancel(true) 的中断信号", interrupted.get());
        assertTrue("超时计数应递增 —— 证明超时被裁决器真实裁决（而非静默跳过）", AsyncPool.getTimeoutCount() > timeoutCountBefore);
    }
}
