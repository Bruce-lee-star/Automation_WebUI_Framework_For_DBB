package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ConcurrencyGateTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WEB-P1-5 种子测试：SSO 感知并发闸门（无浏览器，纯逻辑）。
 *
 * <p><b>条目生命周期语义（2026-09-17 评审修复后）</b>：闸门条目在其<b>最后一个持有者</b> {@code release}
 * 时即被移除，故 {@code stats().activeGates} 表示「<b>在途</b>身份数」，正常收尾后应回落到基线。
 * 原实现在 {@code release} 中不移除条目、依赖 {@code MAX_GATES=4096} + 「按许可余量判空闲」的淘汰，
 * 该判据在 {@code computeIfAbsent → acquire} 窗口内会把即将取用的闸门误判为空闲（串行化破裂），
 * 且淘汰后 {@code release(key)} 会把许可错记到新条目上。修复与论证见 {@code GateEntry} 的类注释。
 *
 * <p>测试以「基线增量」方式断言，避免跨用例静态态串扰。
 */
public class ConcurrencyGateTest {

    private static final String ENABLED = WebFrameworkConfig.CONCURRENCY_PARTITION_ENABLED.getKey();
    private static final String PERMITS = WebFrameworkConfig.CONCURRENCY_PARTITION_PER_KEY_PERMITS.getKey();
    private static final String MAX_WAIT = WebFrameworkConfig.CONCURRENCY_PARTITION_MAX_WAIT_MS.getKey();
    private static final String FAIL_CLOSED = WebFrameworkConfig.CONCURRENCY_PARTITION_FAIL_CLOSED.getKey();

    /**
     * 前提确定性（本轮修复既有失败，<b>关键</b>）：本类用系统属性表达每条用例的配置前提，
     * 但 <b>{@code System.clearProperty} 并不能把配置拉回默认值</b> ——
     * {@code ConfigSource.resolve} 的第 1 步（实时系统属性）之后还有第 3 步「SPI 扩展配置源」，
     * web 侧即 Serenity 合并源（{@code SerenityConfigResolver} → {@code SystemEnvironmentVariables}），
     * 它是<b>启动期系统属性快照 + Typesafe {@code ConfigFactory} 静态缓存</b>：一旦前序用例设过某键，
     * 之后即使 {@code clearProperty}，{@code WebFrameworkConfig.getValue()} 仍返回<b>旧值</b>
     * （实测：{@code cfgValue=[true]}，且 {@code new SystemEnvironmentVariables()} 同样刷不掉）。
     * 生产侧不存在 {@code clearProperty}（仅有单向 {@code WebFrameworkConfig.setValue}），
     * 故该陈旧快照的实际影响域就是<b>单测隔离</b>。
     *
     * <p>应对：把四个键在<b>前后各显式写成其文档默认值</b>（{@link WebFrameworkConfig#getDefaultValue()}），
     * 而非清除 —— 前提恒定，且全程走"实时系统属性"通道，不受任何快照影响。
     * 仅引擎级并行开关可清理：{@code ConcurrencyGate.parseTriState} 是直接
     * {@code System.getProperty} 读的，不经配置层。</p>
     *
     * <p><b>注意</b>：必须拆成 {@code @BeforeEach} / {@code @AfterEach} 两个方法 —— 实测把两个注解标在
     * 同一个方法上时 {@code @AfterEach} 不生效，本类的 MAX_WAIT 等系统属性会泄漏给后续测试类
     * （曾导致 {@code SessionManagerSessionGateTest} 的等待方 300ms 就 fail-closed 而失败）。</p>
     */
    @BeforeEach
    public void resetConfigurationPremisesBeforeCase() {
        applyDefaultPremises();
    }

    /** 用例后复位：显式独立方法，确保 {@code @AfterEach} 真正生效（见上方说明）。 */
    @AfterEach
    public void resetConfigurationPremisesAfterCase() {
        applyDefaultPremises();
    }

    /** 把四个键写回文档默认值，并清理引擎级并行开关（不经配置层、可直接清除）。 */
    private static void applyDefaultPremises() {
        restoreDefault(WebFrameworkConfig.CONCURRENCY_PARTITION_ENABLED);
        restoreDefault(WebFrameworkConfig.CONCURRENCY_PARTITION_PER_KEY_PERMITS);
        restoreDefault(WebFrameworkConfig.CONCURRENCY_PARTITION_MAX_WAIT_MS);
        restoreDefault(WebFrameworkConfig.CONCURRENCY_PARTITION_FAIL_CLOSED);
        System.clearProperty(CUCUMBER_PARALLEL);
        System.clearProperty(JUNIT_PARALLEL);
    }

    /** 把某配置键显式写回其代码内默认值（避免 {@code clearProperty} 触发 SPI 陈旧快照）。 */
    private static void restoreDefault(WebFrameworkConfig key) {
        System.setProperty(key.getKey(), key.getDefaultValue());
    }

    /** 引擎级并行开关（auto 判据）。 */
    private static final String CUCUMBER_PARALLEL = "cucumber.execution.parallel.enabled";
    private static final String JUNIT_PARALLEL = "junit.jupiter.execution.parallel.enabled";

    /**
     * <b>死锁回归守卫 + 默认 fail-closed（评审 F-11）</b>：许可被泄漏时，等待方必须<b>有界超时</b>
     * 而非永久 park（旧实现 {@code acquireUninterruptibly} 会让整套件卡死）；且超时后默认
     * <b>如实判失败</b>（抛 {@link ConcurrencyGateTimeoutException}）—— 原实现静默放行会让同身份
     * 串行化失效（SSO 互踢 / 随机 401），而用例仍<b>可能通过</b>，属最危险的静默降级。
     * 未真正持有者的 {@code release} 仍须 no-op（不得虚增许可破坏互斥）。
     */
    @Test
    public void leakedPermit_failsClosedByDefaultInsteadOfSilentlyProceeding() throws Exception {
        System.setProperty(ENABLED, "true");
        System.setProperty(MAX_WAIT, "300");
        System.setProperty(FAIL_CLOSED, "true");
        ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(dim("sessionkey", "LEAK-FAILCLOSED"));

        CountDownLatch holderIn = new CountDownLatch(1);
        AtomicBoolean holderDone = new AtomicBoolean(false);
        Thread holder = new Thread(() -> {
            ConcurrencyGate.acquire(key);
            holderIn.countDown();
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ConcurrencyGate.release(key);
            holderDone.set(true);
        }, "failclosed-holder");
        holder.start();
        assertTrue(holderIn.await(2, TimeUnit.SECONDS), "持有者应取得许可");

        AtomicLong waitedMs = new AtomicLong(-1);
        AtomicReference<Throwable> waiterError = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                ConcurrencyGate.acquire(key);
            } catch (Throwable t) {
                waiterError.set(t);
            }
            waitedMs.set(System.currentTimeMillis() - t0);
            ConcurrencyGate.release(key);   // 未真正持有 → no-op
        }, "failclosed-waiter");
        waiter.start();
        waiter.join(5000);

        assertFalse(waiter.isAlive(), "等待方必须已返回：闸门不得永久 park");
        assertTrue(waitedMs.get() >= 250, "等待应至少经历一次超时窗口（实测 " + waitedMs.get() + "ms）");
        assertTrue(waiterError.get() instanceof ConcurrencyGateTimeoutException,
                "默认 fail-closed：超时须抛 ConcurrencyGateTimeoutException，实际=" + waiterError.get());

        holder.join(5000);
        assertTrue(holderDone.get(), "持有者应完成其正常释放");

        //  许可未虚增：持有者释放后该 key 应可被正常获取/释放
        ConcurrencyGate.acquire(key);
        ConcurrencyGate.release(key);
    }

    /**
     * <b>逃生舱</b>：显式 {@code partition.fail.closed=false} 时退回「放行 + ERROR 日志」的旧行为
     * （不再抛异常），供确认「串行化失效不会造成会话破坏」的场景使用。
     */
    @Test
    public void leakedPermit_failsOpenWhenEscapeHatchEnabled() throws Exception {
        System.setProperty(ENABLED, "true");
        System.setProperty(MAX_WAIT, "300");
        System.setProperty(FAIL_CLOSED, "false");
        ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(dim("sessionkey", "LEAK-FAILOPEN"));

        CountDownLatch holderIn = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            ConcurrencyGate.acquire(key);
            holderIn.countDown();
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ConcurrencyGate.release(key);
        }, "failopen-holder");
        holder.start();
        assertTrue(holderIn.await(2, TimeUnit.SECONDS), "持有者应取得许可");

        AtomicReference<Throwable> waiterError = new AtomicReference<>();
        AtomicLong waitedMs = new AtomicLong(-1);
        Thread waiter = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                ConcurrencyGate.acquire(key);
            } catch (Throwable t) {
                waiterError.set(t);
            }
            waitedMs.set(System.currentTimeMillis() - t0);
            ConcurrencyGate.release(key);
        }, "failopen-waiter");
        waiter.start();
        waiter.join(5000);

        assertFalse(waiter.isAlive(), "等待方必须已返回（不得永久 park）");
        assertTrue(waitedMs.get() >= 250, "等待应至少经历一次超时窗口（实测 " + waitedMs.get() + "ms）");
        assertNull(waiterError.get(), "逃生舱开启时不应抛异常（回到旧 fail-open 行为）");

        holder.join(5000);
        ConcurrencyGate.acquire(key);
        ConcurrencyGate.release(key);
    }

    /**
     * 场景收口兜底：{@link ConcurrencyGate#releaseAllForCurrentThread()} 应归还本线程持有的全部许可
     * （覆盖调用方漏 release 的情形），归还后其它线程可正常进入。
     */
    @Test
    public void releaseAllForCurrentThread_recoversLeakedPermit() throws Exception {
        System.setProperty(ENABLED, "true");
        System.setProperty(MAX_WAIT, "400");
        ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(dim("sessionkey", "RECOVER-ME"));

        AtomicInteger released = new AtomicInteger();
        Thread leaky = new Thread(() -> {
            ConcurrencyGate.acquire(key);
            released.set(ConcurrencyGate.releaseAllForCurrentThread());   // 模拟场景收口兜底
        }, "leaky-scenario");
        leaky.start();
        leaky.join(3000);

        assertEquals(1, released.get(), "应归还 1 个泄漏持有的闸门");

        //  归还后：其它线程应立即进入（用闩锁确定性等待，不依赖耗时上界断言，杜绝负载下的误判）
        CountDownLatch nextEntered = new CountDownLatch(1);
        Thread next = new Thread(() -> {
            ConcurrencyGate.acquire(key);
            nextEntered.countDown();
            ConcurrencyGate.release(key);
        }, "next-scenario");
        next.start();
        assertTrue(nextEntered.await(3, TimeUnit.SECONDS),
                "兜底归还后其它线程应立即进入（不得因泄漏许可未回收而阻塞）");
        next.join(3000);
    }

    /**
     * <b>同线程重入守卫（2026-09-28）</b>：同一 key 在本线程二次 {@code acquire} 必须是 no-op ——
     * 旧实现在 permits=1 的公平 {@link java.util.concurrent.Semaphore} 上会<b>自锁</b>到 maxWait，
     * 再以 fail-closed 抛 {@link ConcurrencyGateTimeoutException}（框架多路径进入同一身份即误判场景失败）。
     * 同时验证不虚增持有：只记一条持有 → {@code releaseAllForCurrentThread} 归还 1 个许可，其它线程可立即进入。
     */
    @Test
    public void reentrantAcquireOnSameThreadIsNoOpAndDoesNotLeakPermit() throws Exception {
        System.setProperty(ENABLED, "true");
        ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(dim("sessionkey", "REENTRANT"));

        // 用「有界 join 的独立线程」观测自锁，而不是把 MAX_WAIT 改小 —— 后者会污染同 JVM 的其它测试类。
        AtomicInteger released = new AtomicInteger(-1);
        AtomicReference<Throwable> holderError = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try {
                ConcurrencyGate.acquire(key);
                ConcurrencyGate.acquire(key);                              // 重入：必须立即返回
                released.set(ConcurrencyGate.releaseAllForCurrentThread());
            } catch (Throwable t) {
                holderError.set(t);
            }
        }, "reentrant-holder");
        holder.start();
        holder.join(3000);

        assertFalse(holder.isAlive(), "同线程重入必须立即返回，不得自锁（err=" + holderError.get() + "）");
        assertNull(holderError.get(), "同线程重入不得抛异常");
        assertEquals(1, released.get(), "同线程重入只应记一条持有");

        CountDownLatch entered = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            ConcurrencyGate.acquire(key);
            entered.countDown();
            ConcurrencyGate.release(key);
        }, "reentrant-checker");
        other.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS), "重入不得泄漏许可：其它线程应可立即进入");
        other.join(3000);
    }

    /**
     * {@code auto}（默认三态）：<b>并行开启即自动启用、串行时 no-op、显式 false 为逃生舱</b>。
     *
     * <p>这是「同一 sessionKey 串行、不同 sessionKey 并行」成为<b>并行默认语义</b>的基础：
     * 若默认恒 off，并行下同身份会并发共用会话（SSO 互踢）——即用户反馈的缺陷。
     */
    @Test
    public void auto_enabledIffEngineParallelEnabled_explicitFalseWins() {
        // 前提（ENABLED="auto" + 无并行开关）由 resetConfigurationPremises() 显式建立；
        // 不可用 clearProperty —— 那会落到受 Serenity 启动期快照污染的 SPI 源（见类内注释）。
        assertFalse(ConcurrencyGate.isEnabled(), "串行运行（auto）不应启用闸门");

        System.setProperty(CUCUMBER_PARALLEL, "true");
        assertTrue(ConcurrencyGate.isEnabled(), "引擎级并行开启时 auto 应自动启用");
        System.clearProperty(CUCUMBER_PARALLEL);

        System.setProperty(JUNIT_PARALLEL, "true");
        assertTrue(ConcurrencyGate.isEnabled(), "JUnit5 并行开启时 auto 也应自动启用");
        System.clearProperty(JUNIT_PARALLEL);

        System.setProperty(ENABLED, "false");
        System.setProperty(CUCUMBER_PARALLEL, "true");
        assertFalse(ConcurrencyGate.isEnabled(), "显式 false 必须压过 auto（逃生舱）");
    }

    @Test
    public void disabledByDefault_noNewGateCreated() {
        // 串行 + auto（= 配置默认值）→ 不启用；前提由 resetConfigurationPremises() 建立
        assertFalse(ConcurrencyGate.isEnabled());
        int baseline = ConcurrencyGate.stats().activeGates;
        ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(dim("env", "sit1", "user", "alice-noop"));
        ConcurrencyGate.acquire(key);
        ConcurrencyGate.release(key);
        // 禁用时 acquire/release 为 no-op，不应向 GATES 写入任何闸门
        assertEquals( baseline,  ConcurrencyGate.stats().activeGates, "禁用时不得创建闸门");
    }

    @Test
    public void nullKey_noNewGateCreated_evenWhenEnabled() {
        System.setProperty(ENABLED, "true");
        assertTrue(ConcurrencyGate.isEnabled());
        int baseline = ConcurrencyGate.stats().activeGates;
        ConcurrencyGate.acquire(null);
        ConcurrencyGate.release(null);
        assertEquals( baseline,  ConcurrencyGate.stats().activeGates, "null key 不得创建闸门");
    }

    @Test
    public void enabled_createsOneGatePerUniqueKey() {
        System.setProperty(ENABLED, "true");
        System.setProperty(PERMITS, "1");
        int baseline = ConcurrencyGate.stats().activeGates;
        ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(dim("env", "sit1", "user", "alice-enabled"));
        ConcurrencyGate.acquire(key);
        assertEquals( baseline + 1,  ConcurrencyGate.stats().activeGates, "启用后应为该 key 创建一道闸门");
        ConcurrencyGate.release(key);
        // 条目随最后一个持有者释放即移除（Map 大小 == 在途身份数）
        assertEquals(baseline, ConcurrencyGate.stats().activeGates, "最后一个持有者释放后条目应被移除");
    }

    @Test
    public void differentIdentities_useDistinctGates() {
        System.setProperty(ENABLED, "true");
        int baseline = ConcurrencyGate.stats().activeGates;
        ConcurrencyPartitionKey alice = ConcurrencyPartitionKey.of(dim("env", "sit1", "user", "alice-distinct"));
        ConcurrencyPartitionKey bob = ConcurrencyPartitionKey.of(dim("env", "sit1", "user", "bob-distinct"));
        ConcurrencyGate.acquire(alice);
        ConcurrencyGate.acquire(bob);
        assertEquals( baseline + 2,  ConcurrencyGate.stats().activeGates, "不同身份应使用不同闸门");
        ConcurrencyGate.release(alice);
        ConcurrencyGate.release(bob);
        assertEquals(baseline, ConcurrencyGate.stats().activeGates, "释放后不应残留条目");
    }

    /** 条目仅在持有期间存在：acquire 后出现、release 后消失（回归守卫：旧实现永不移除）。 */
    @Test
    public void entryExistsOnlyWhileHeld() {
        System.setProperty(ENABLED, "true");
        int baseline = ConcurrencyGate.stats().activeGates;
        ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(dim("env", "sit1", "user", "held-once"));
        ConcurrencyGate.acquire(key);
        assertEquals(baseline + 1, ConcurrencyGate.stats().activeGates, "持有时应存在条目");
        ConcurrencyGate.release(key);
        assertEquals(baseline, ConcurrencyGate.stats().activeGates, "最后一个持有者释放后条目应被移除");
    }

    /** 顺序使用海量身份后不残留：Map 天然有界（不再依赖 4096 上限 + 惰性淘汰兜底）。 */
    @Test
    public void manySequentialIdentities_doNotAccumulate() {
        System.setProperty(ENABLED, "true");
        int baseline = ConcurrencyGate.stats().activeGates;
        for (int i = 0; i < 5000; i++) {
            ConcurrencyPartitionKey k = ConcurrencyPartitionKey.of(dim("env", "sit1", "user", "seq-" + i));
            ConcurrencyGate.acquire(k);
            ConcurrencyGate.release(k);
        }
        assertEquals(baseline, ConcurrencyGate.stats().activeGates,
                "顺序使用 5000 个身份后应无残留条目（旧实现会残留累计条目）");
    }

    /**
     * 关键不变量：某身份被持有期间，另一线程对<b>同一</b>身份必须被阻塞 —— 即便同时有海量其它身份
     * 在 churn（旧实现的淘汰机制正是被该 churn 触发，可能把"将被取用"的闸门误判为空闲而破坏串行化）。
     */
    @Test
    public void heldGateSurvivesChurn_andStillSerializes() throws Exception {
        System.setProperty(ENABLED, "true");
        System.setProperty(PERMITS, "1");
        ConcurrencyPartitionKey held = ConcurrencyPartitionKey.of(dim("env", "sit1", "user", "held-identity"));
        ConcurrencyGate.acquire(held);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> blocked = pool.submit(() -> {
                ConcurrencyGate.acquire(held);
                ConcurrencyGate.release(held);
            });

            // churn：制造大量其它身份，反复取用/释放（旧实现在此路径上执行淘汰）
            for (int i = 0; i < 5000; i++) {
                ConcurrencyPartitionKey other =
                        ConcurrencyPartitionKey.of(dim("env", "sit1", "user", "churn-" + i));
                ConcurrencyGate.acquire(other);
                ConcurrencyGate.release(other);
            }

            assertThrows(TimeoutException.class, () -> blocked.get(300, TimeUnit.MILLISECONDS),
                    "同一身份被持有时，另一线程必须被阻塞（churn 不得破坏串行化）");

            ConcurrencyGate.release(held);
            blocked.get(5, TimeUnit.SECONDS); // 释放后应立即可进入
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, String> dim(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
