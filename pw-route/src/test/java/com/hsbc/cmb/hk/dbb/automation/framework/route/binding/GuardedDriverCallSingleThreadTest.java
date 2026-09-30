package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.GuardedDriverCall.OnTimeout;
import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T6 契约（2026-09-29）：驱动调用单线程化 + 毒化重置（绝不波及 Context）。
 *
 * <p>守护三条不可回退语义：
 * <ol>
 *   <li><b>收敛</b>：所有 guarded 驱动调用在<b>专用驱动线程</b>（名称前缀 {@code route-v2-driver-}）串行执行，
 *       任意时刻并发度 = 1（消除共享连接并发竞态，根治 {@code Object doesn't exist}）；</li>
 *   <li><b>不丢回包</b>：并发提交 N 个调用，全部拿到各自结果（无丢失 / 无静默降级）；</li>
 *   <li><b>毒化重置</b>：某调用超时（驱动无回包）→ 仅重建驱动线程（POISON_RESETS 递增），
 *       后续调用经新线程正常返回；<b>绝不关闭 / 重建 Context / Page</b>。</li>
 * </ol>
 */
public class GuardedDriverCallSingleThreadTest {

    @After
    public void reset() {
        GuardedDriverCallRegistry.reset(); // 回到默认（真实）实现
    }

    /** 场景 1：并发调用必须收敛到单一专用驱动线程，且任意时刻并发度 = 1（串行）。 */
    @Test
    public void driverCallsAreSerializedOnSingleThread() throws Exception {
        GuardedDriverCall guard = GuardedDriverCallRegistry.instance();
        int n = 64;
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxInFlight = new AtomicInteger(0);
        ConcurrentHashMap.KeySetView<String, Boolean> names = ConcurrentHashMap.newKeySet();
        CountDownLatch allDone = new CountDownLatch(n);

        List<Thread> callers = IntStream.range(0, n).mapToObj(i -> new Thread(() -> {
            guard.guarded("bind:t" + i, 5_000L, OnTimeout.WARN_AND_ABANDON, () -> {
                int cur = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(cur, Math::max);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                inFlight.decrementAndGet();
                names.add(Thread.currentThread().getName());
                return null;
            });
            allDone.countDown();
        }, "caller-" + i)).collect(Collectors.toList());
        callers.forEach(Thread::start);
        allDone.await();

        assertEquals("任意时刻仅一个驱动调用在执行（单线程串行，根治并发竞态）", 1, maxInFlight.get());
        assertFalse("驱动调用必须运行在专用线程（route-v2-driver-），而非调用方线程", names.isEmpty());
        for (String name : names) {
            assertTrue("驱动线程命名须为 route-v2-driver-*：" + name, name.startsWith("route-v2-driver-"));
        }
    }

    /** 场景 2：并发提交 N 个调用，全部拿到各自结果（不丢回包、不静默降级）。 */
    @Test
    public void concurrentCallsDoNotDropReplies() throws Exception {
        GuardedDriverCall guard = GuardedDriverCallRegistry.instance();
        int n = 128;
        ConcurrentLinkedQueue<Integer> results = new ConcurrentLinkedQueue<>();
        CountDownLatch allDone = new CountDownLatch(n);

        List<Thread> callers = IntStream.range(0, n).mapToObj(i -> new Thread(() -> {
            Integer r = guard.guarded("bind:c" + i, 5_000L, OnTimeout.WARN_AND_ABANDON, () -> i);
            if (r != null) {
                results.add(r);
            }
            allDone.countDown();
        }, "caller-" + i)).collect(Collectors.toList());
        callers.forEach(Thread::start);
        allDone.await();

        assertEquals("并发 N 个调用必须全部拿到各自结果（无一丢回包）", n, results.size());
        for (int i = 0; i < n; i++) {
            assertTrue("结果集必须包含每个调用的回包（缺 " + i + "）", results.contains(i));
        }
    }

    /** 场景 3：超时（无回包）→ 毒化重置仅重建驱动线程，后续调用经新线程正常返回，绝不波及 Context/Page。 */
    @Test
    public void timeoutPoisonResetsDriverThreadWithoutTouchingContext() throws Exception {
        GuardedDriverCall guard = GuardedDriverCallRegistry.instance();
        long before = GuardedDriverCallImpl.poisonResetCount();

        // 卡死 action：等待一个永不自动释放的 latch（等价于驱动无回包）；shutdownNow 会以中断使其结束。
        CountDownLatch blocked = new CountDownLatch(1);
        Integer first = guard.guarded("bind:hang", 100, OnTimeout.WARN_AND_ABANDON, () -> {
            try {
                blocked.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return 999;
        });
        assertNull("超时（无回包）→ WARN_AND_ABANDON 必须返回 null（降级，不抛、不波及 Context）", first);
        assertTrue("毒化重置必须触发（POISON_RESETS 至少 +1）", GuardedDriverCallImpl.poisonResetCount() > before);

        // 毒化后新调用必须经重建线程正常返回（信道已重建，Context/Page 未动）。
        Integer after = guard.guarded("bind:recover", 5_000L, OnTimeout.WARN_AND_ABANDON, () -> 1);
        assertEquals("毒化重置后新驱动调用必须正常返回（信道已重建）", Integer.valueOf(1), after);

        // 释放 latch：被遗弃的卡死线程在 shutdownNow 中断后应已自然结束（守护线程，不挂 JVM）。
        blocked.countDown();
    }
}
