package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P0 验收：超时处置<b>不得在同一连接上制造"第二个消息泵"</b>。
 *
 * <p>Playwright Java 的客户端<b>没有独立读线程</b> —— 谁发起协议调用，谁就负责把消息读干净。因此判据不是
 * "有没有换线程"，而是"换线程之前，在途调用<b>是否真的结束了</b>"：
 * <ul>
 *   <li>可中断的在途调用（{@code shutdownNow} 能使其结束）⇒ 允许重建执行器（保留既有自愈语义）；</li>
 *   <li>不可中断的在途调用（native 卡死）⇒ <b>绝不新建线程</b>，只标记信道不可用 —— 否则第二个泵会错取回包，
 *       正是实测 dbb-3 中"换了线程照样卡满 30s"的成因。</li>
 * </ul>
 */
public class DriverChannelContainmentTest {

    @After
    public void cleanup() {
        GuardedDriverCallImpl.resetChannel();
    }

    @Test
    public void stoppableInFlightCallDrainsThenResetsChannel() throws Exception {
        GuardedDriverCallImpl call = new GuardedDriverCallImpl();
        long resetsBefore = GuardedDriverCallImpl.poisonResetCount();
        long failuresBefore = GuardedDriverCallImpl.channelFailureCount();
        CountDownLatch release = new CountDownLatch(1);

        Object first = call.guarded("bind:stoppable", 100L, GuardedDriverCall.OnTimeout.WARN_AND_ABANDON, () -> {
            release.await(5, TimeUnit.SECONDS);
            return "late";
        });

        assertNull("界内无回包 ⇒ 放弃等待并降级", first);
        assertEquals("信道故障计数必须 +1", failuresBefore + 1, GuardedDriverCallImpl.channelFailureCount());
        assertTrue("在途调用已收尾 ⇒ 允许重建驱动线程（自愈语义保留）",
                GuardedDriverCallImpl.poisonResetCount() > resetsBefore);
        assertTrue("在途调用已收尾 ⇒ 信道可确证可用", call.isChannelUsable());
        assertEquals("恢复后调用必须正常返回", "ok",
                call.guarded("bind:after", 5_000L, GuardedDriverCall.OnTimeout.WARN_AND_ABANDON, () -> "ok"));
        release.countDown();
    }

    @Test
    public void unstoppableInFlightCallIsNotBypassedByANewThread() throws Exception {
        GuardedDriverCallImpl call = new GuardedDriverCallImpl();
        int epochBefore = GuardedDriverCallImpl.driverEpochForTest();
        AtomicBoolean stop = new AtomicBoolean(false);

        Object first = call.guarded("bind:stuck", 100L, GuardedDriverCall.OnTimeout.WARN_AND_ABANDON, () -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!stop.get() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException ignored) {
                    // 故意吞掉中断：模拟 native 不可中断的在途调用
                }
            }
            return "finally";
        });

        assertNull("界内无回包 ⇒ 降级返回 null", first);
        assertFalse("在途调用未收尾 ⇒ 信道必须标记不可用", call.isChannelUsable());
        assertEquals("未确认收尾时绝不新建驱动线程（否则同一连接出现第二个消息泵）",
                epochBefore, GuardedDriverCallImpl.driverEpochForTest());

        // 关键判别：信道不可用期间，新调用**不得**被绕过到新线程上成功执行（那正是被取代的旧行为）
        Object second = call.guarded("bind:while-stuck", 200L, GuardedDriverCall.OnTimeout.WARN_AND_ABANDON, () -> "ok");
        assertNull("信道不可用期间新调用只能降级，不得另起线程执行", second);
        assertEquals("降级期间同样不得新建线程", epochBefore, GuardedDriverCallImpl.driverEpochForTest());

        stop.set(true);
        Thread.sleep(300L);
        GuardedDriverCallImpl.resetChannel();
        assertTrue("显式复位（调用方已换连接）后信道恢复可用", call.isChannelUsable());
    }
}
