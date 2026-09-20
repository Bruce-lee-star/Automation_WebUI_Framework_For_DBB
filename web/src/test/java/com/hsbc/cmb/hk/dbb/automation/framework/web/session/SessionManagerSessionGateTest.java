package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并行语义契约（企业级期望）：<b>同一 sessionKey 严格串行、不同 sessionKey 并行</b>。
 *
 * <p>背景：并行下同一会话被两个 scenario 同时使用会互相踩踏（SSO 单会话互踢、storageState 覆写、
 * 首页/权限态交错），表现为随机 401 / 重登录失败 / 断言漂移。{@link SessionManager} 在会话读写入口
 * 按 sessionKey 加闸（{@code acquireSessionGate} / {@code releaseSessionGate}），本测试直接验证该语义。
 *
 * <p>零网络依赖：只驱动闸门本身，不创建浏览器 / 不登录。
 */
class SessionManagerSessionGateTest {

    /** 显式开启闸门（等价于并行为真时的 auto 行为），避免依赖引擎并行开关。 */
    @BeforeEach
    void enableGate() {
        System.setProperty("serenity.playwright.concurrent.partition.enabled", "true");
    }

    @AfterEach
    void resetGate() {
        System.clearProperty("serenity.playwright.concurrent.partition.enabled");
    }

    /** 同 sessionKey：前者未释放时，后者必须被阻塞；前者释放后立即进入。 */
    @Test
    void sameSessionKey_isSerialized() throws Exception {
        CountDownLatch holderIn = new CountDownLatch(1);
        CountDownLatch holderRelease = new CountDownLatch(1);
        CountDownLatch waiterReady = new CountDownLatch(1);
        AtomicBoolean secondEntered = new AtomicBoolean(false);

        Thread holder = new Thread(() -> {
            SessionManager.acquireSessionGate("O63_SIT1_WP7UAT2_2");
            holderIn.countDown();                 // 确定性握手：持有者已真正持锁
            try {
                holderRelease.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                SessionManager.releaseSessionGate();
            }
        }, "gate-holder");
        holder.start();
        assertTrue(holderIn.await(2, TimeUnit.SECONDS), "持有者应能进入闸门");

        Thread waiter = new Thread(() -> {
            waiterReady.countDown();               // 即将尝试进入临界区（使观测窗口有意义）
            SessionManager.acquireSessionGate("O63_SIT1_WP7UAT2_2");  // 同 key → 必须被阻塞
            secondEntered.set(true);
            SessionManager.releaseSessionGate();
        }, "gate-waiter");
        waiter.start();
        assertTrue(waiterReady.await(2, TimeUnit.SECONDS), "等待者应在超时前就绪");

        // 等待方已就绪后观测其是否违规进入：不再用 sleep 猜调度（原 sleep(200) 会在等待方未调度时假通过）
        assertFalse(awaitTrue(secondEntered, NON_ENTRY_OBSERVE_MS),
                "同一 sessionKey 必须串行：持有者未释放前，等待者不得进入");

        holderRelease.countDown();                 // 释放持有者
        waiter.join(3000);
        assertTrue(secondEntered.get(), "持有者释放后，等待者应立即进入");
        assertFalse(waiter.isAlive(), "等待者应在持有者释放后结束");
    }

    /** 不同 sessionKey：互不阻塞，可同时持有。 */
    @Test
    void differentSessionKeys_runInParallel() throws Exception {
        CountDownLatch firstIn = new CountDownLatch(1);
        AtomicBoolean secondEntered = new AtomicBoolean(false);

        Thread a = new Thread(() -> {
            SessionManager.acquireSessionGate("O63_SIT1_USER_A");
            firstIn.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                SessionManager.releaseSessionGate();
            }
        }, "gate-a");
        a.start();
        assertTrue(firstIn.await(2, TimeUnit.SECONDS));

        CountDownLatch bEntered = new CountDownLatch(1);
        Thread b = new Thread(() -> {
            SessionManager.acquireSessionGate("O63_SIT1_USER_B");
            secondEntered.set(true);
            bEntered.countDown();
            SessionManager.releaseSessionGate();
        }, "gate-b");
        b.start();
        assertTrue(bEntered.await(2, TimeUnit.SECONDS), "不同 sessionKey 不应互相阻塞（应可并行进入）");
        a.join(3000);
    }

    /** 未持有即释放：必须为 no-op（不得虚增许可、不得抛异常）。 */
    @Test
    void releaseWithoutAcquire_isNoOp() {
        SessionManager.releaseSessionGate();
        SessionManager.releaseSessionGate();
    }

    /** 「不可进入」的观测窗口：在等待方已就绪后，持续观察其是否违规进入临界区。 */
    private static final long NON_ENTRY_OBSERVE_MS = 300L;

    /** 有界轮询等待标志变真（超时返回当前值；上界仅防挂死，不作为通过判据）。 */
    private static boolean awaitTrue(AtomicBoolean flag, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!flag.get()) {
            if (System.nanoTime() - deadline >= 0) {
                return flag.get();
            }
            Thread.sleep(5);
        }
        return true;
    }
}
