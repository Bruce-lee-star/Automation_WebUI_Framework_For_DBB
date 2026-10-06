package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser;

import com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * N-22 契约（doc 21 §8.6）：{@code Browser.close()} 的阻塞时长<b>必须被框架设限</b>。
 *
 * <p><b>为什么框架必须自己设限</b>：Playwright 1.62.0 的 {@code Browser.CloseOptions} 只有
 * {@code setReason(String)}、<b>没有 timeout</b>（已按字节码核实），故 {@code close()} 会一直等浏览器进程
 * 退出 —— 实测某次 pw-web-ui 单测退出时该等待长达 <b>26.5s</b>，越过 Surefire 的 30s 宽限后 fork 被硬杀：
 * 后续关闭任务从未执行，只留下 {@code [ERROR] Surefire is going to kill self fork JVM}。</p>
 *
 * <p>修复后：单次关闭由看门狗设限，超限即<b>放弃等待</b>（该关闭仍在 daemon 线程继续，JVM 退出后由
 * Playwright driver 的进程树回收兜底）并<b>记 ERROR + 计数</b>，且<b>不阻断</b> {@code cleanupAll}
 * 后续的路由排空 / AsyncPool / ThreadLocal 清理。</p>
 */
public class BrowserCleanupImplRunBoundedTest {

    private CountDownLatch release;

    @After
    public void tearDown() {
        if (release != null) {
            release.countDown();
        }
        //  刻意【不】调用 ShutdownCoordinator.reset()：它清空的是本 JVM 全局已登记的关闭任务
        //  （含 FrameworkCore 的 pw-core 收口），在 pw-web-ui 的测试 JVM 里属越界副作用。
        //  本用例全部使用差值断言（before / before+1），无需复位计数即可隔离。
    }

    @Test
    // @DisplayName: "N-22：卡住的 close 必须在看门狗上限处被放弃并记账（原先会拖到 fork 被硬杀）"
    public void blockingActionIsAbandonedAtLimit() {
        release = new CountDownLatch(1);
        long before = ShutdownCoordinator.getFailureCount();

        long start = System.nanoTime();
        boolean completed = BrowserCleanupImpl.runBounded("test/browser-close:chromium",
                () -> awaitQuietly(release), 300);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertFalse("超限必须返回 false（= 放弃等待），否则继续阻塞就还会越过 surefire 宽限", completed);
        assertTrue("必须在上限附近返回（实测 " + elapsedMs + "ms，上限 300ms）", elapsedMs < 3_000);
        assertEquals("放弃必须记账 —— 否则『关闭没做完』仍是零信号", before + 1, ShutdownCoordinator.getFailureCount());
    }

    @Test
    // @DisplayName: "N-22：正常 close 在限内完成且不记账（正常路径零噪音）"
    public void fastActionCompletesWithoutAccounting() {
        AtomicBoolean ran = new AtomicBoolean(false);
        long before = ShutdownCoordinator.getFailureCount();

        boolean completed = BrowserCleanupImpl.runBounded("test/browser-close:fast", () -> ran.set(true), 2_000);

        assertTrue("快动作必须判为限内完成", completed);
        assertTrue("动作必须真的被执行", ran.get());
        assertEquals("正常路径不得记账", before, ShutdownCoordinator.getFailureCount());
    }

    @Test
    // @DisplayName: "N-22：关闭动作抛异常仍算已结束（不得被误判为超时，也不得逃逸成线程噪声）"
    public void throwingActionIsNotMistakenForTimeout() {
        long before = ShutdownCoordinator.getFailureCount();

        boolean completed = BrowserCleanupImpl.runBounded("test/browser-close:boom",
                () -> {
                    throw new IllegalStateException("boom");
                }, 2_000);

        assertTrue("线程已结束即视为完成；异常由看门狗内兜住并 warn（与原先 cleanupAll 的 catch 同语义）", completed);
        assertEquals("异常本身不由看门狗记账 —— 避免与既有 warn 语义双重记账", before, ShutdownCoordinator.getFailureCount());
    }

    @Test
    // @DisplayName: "N-23：cleanupAll 内部预算判定（预算内 true / 已超预算 false）"
    public void internalBudgetWindowIsHonoured() {
        assertTrue("刚起步必须在预算内，否则会把「正常关闭」误判为超预算而跳过", BrowserCleanupImpl.hasBudgetLeft(System.nanoTime(), 5_000));

        assertFalse("已超预算必须判 false —— 否则慢关闭会吃光预算、连带跳过后续收尾步骤", BrowserCleanupImpl.hasBudgetLeft(System.nanoTime() - TimeUnit.SECONDS.toNanos(10), 5_000));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
