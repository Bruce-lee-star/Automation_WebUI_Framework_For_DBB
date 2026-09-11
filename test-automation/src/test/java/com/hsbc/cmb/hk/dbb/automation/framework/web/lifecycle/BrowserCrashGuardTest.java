package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTask;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCrashGuard;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BrowserCrashGuard} 单测（固化设计文档 9.6 / R7）：
 * ① 崩溃型异常被正确识别（Playwright 类 / 消息特征）；正常异常不误判；
 * ② {@code isEnabled()} 受配置与实时系统属性控制；
 * ③ 单飞恢复：注入恢复动作后 {@code recover()} 计数且并发安全；
 * ④ 与 {@link ConcurrentContextExecutor} 集成：崩溃任务被重跑一次后成功、非崩溃失败不重跑。
 */
public class BrowserCrashGuardTest {

    @BeforeAll
    public static void enableGuard() {
        System.setProperty("serenity.playwright.concurrent.browser.crash.guard.enabled", "true");
    }

    @AfterAll
    public static void restore() {
        System.clearProperty("serenity.playwright.concurrent.browser.crash.guard.enabled");
    }

    /** 每个用例后复位注入的恢复动作（含强制重建路径），避免静态状态串扰其它测试。 */
    @AfterEach
    public void resetAction() {
        BrowserCrashGuard.setRecoveryActionForTesting(null);
        BrowserCrashGuard.setForcedRecoveryActionForTesting(null);
    }

    // ==================== ① 崩溃检测 ====================

    @Test
    public void detectsCrashByMessageSignature() {
        assertTrue(BrowserCrashGuard.isCrash(new RuntimeException("Target page, context or browser has been closed")));
        assertTrue(BrowserCrashGuard.isCrash(new IllegalStateException("Browser has been closed by crash")));
        assertTrue(BrowserCrashGuard.isCrash(new Exception("connection closed unexpectedly")));
        // WEB-P1-4 收窄后的明确崩溃信号（Playwright 真实崩溃消息）
        assertTrue(BrowserCrashGuard.isCrash(new RuntimeException("Target crashed")));
        assertTrue(BrowserCrashGuard.isCrash(new IllegalStateException("Browser crashed!")));
        assertTrue(BrowserCrashGuard.isCrash(new RuntimeException("browser disconnected")));
    }

    @Test
    public void playwrightExceptionWithoutCrashSignatureIsNotCrash() {
        // WEB-P1-4：PlaywrightException 同时承载崩溃与超时 / 断言失败；移除类名模糊匹配后，
        // 无崩溃信号的 PlaywrightException（即使是 TimeoutError 子类）不再误判为崩溃，避免"重跑一次就好"掩盖真实缺陷。
        assertFalse(
                BrowserCrashGuard.isCrash(new PlaywrightException("waiting for selector to be visible")), "普通 PlaywrightException（无崩溃信号）不应触发重跑");
        assertFalse(
                BrowserCrashGuard.isCrash(new TimeoutError("Timeout 30000ms exceeded")), "元素超时（TimeoutError 子类）不应触发重跑");
    }

    @Test
    public void playwrightExceptionWithCrashSignatureIsStillCrash() {
        // 仅消息含明确崩溃信号才判崩溃（不再依赖类名）。
        assertTrue(BrowserCrashGuard.isCrash(new PlaywrightException("Browser has been closed by crash")));
    }

    @Test
    public void doesNotFlagNormalFailuresAsCrash() {
        assertFalse(BrowserCrashGuard.isCrash(new AssertionError("expected true but false")));
        assertFalse(BrowserCrashGuard.isCrash(new RuntimeException("business validation boom")));
        assertFalse(BrowserCrashGuard.isCrash(null));
        // WEB-P1-4：导航竞态消息不再误判为崩溃（避免掩盖真实缺陷）
        assertFalse(BrowserCrashGuard.isCrash(
                new RuntimeException("Execution context was destroyed, most likely because of a navigation")));
        // 元素超时（PlaywrightException 子类，无崩溃信号）不触发重跑
        assertFalse(BrowserCrashGuard.isCrash(new TimeoutError("Timeout 30000ms exceeded")));
    }

    // ==================== ①b 句柄注册表损坏检测（__adopt__ / previewUpdated） ====================

    @Test
    public void detectsHandleCorruptionByMessageSignature() {
        assertTrue(BrowserCrashGuard.isHandleCorruption(
                new RuntimeException("Navigation failed: Cannot find object to call __adopt__: page@74c35fa")));
        assertTrue(BrowserCrashGuard.isHandleCorruption(new IllegalStateException("wrap",
                new PlaywrightException("Cannot find object to call __adopt__: page@74c35fa29f431c10feee6f87dbcaa663"))));
        assertTrue(BrowserCrashGuard.isHandleCorruption(
                new RuntimeException("page.navigate: previewUpdated")));
        // 非句柄损坏的崩溃消息/正常失败不误判为句柄损坏
        assertFalse(BrowserCrashGuard.isHandleCorruption(new RuntimeException("Browser has been closed")));
        assertFalse(BrowserCrashGuard.isHandleCorruption(new AssertionError("expected true but false")));
        assertFalse(BrowserCrashGuard.isHandleCorruption(null));
    }

    // ==================== ② 开关 ====================

    @Test
    public void enabledByDefaultAndViaSystemProperty() {
        assertTrue( BrowserCrashGuard.isEnabled(), "默认应启用");
        System.setProperty("serenity.playwright.concurrent.browser.crash.guard.enabled", "false");
        try {
            assertFalse(BrowserCrashGuard.isEnabled());
        } finally {
            System.setProperty("serenity.playwright.concurrent.browser.crash.guard.enabled", "true");
        }
    }

    // ==================== ③ 单飞恢复 ====================

    @Test
    public void recoverInvokesInjectedActionAndCounts() {
        AtomicInteger calls = new AtomicInteger();
        BrowserCrashGuard.setRecoveryActionForTesting(() -> {
            calls.incrementAndGet();
            return true;
        });
        long before = BrowserCrashGuard.rebuildCount();
        assertTrue(BrowserCrashGuard.recover());
        assertTrue(BrowserCrashGuard.recover());
        assertEquals(2, calls.get());
        assertEquals(before + 2, BrowserCrashGuard.rebuildCount());
    }

    @Test
    public void recoverReturnsFalseWhenActionThrows() {
        BrowserCrashGuard.setRecoveryActionForTesting(() -> {
            throw new RuntimeException("rebuild failed");
        });
        assertFalse(BrowserCrashGuard.recover());
    }

    @Test
    public void forcedRecoveryInvokesForcedActionAndCounts() {
        AtomicInteger forcedCalls = new AtomicInteger();
        BrowserCrashGuard.setForcedRecoveryActionForTesting(() -> {
            forcedCalls.incrementAndGet();
            return true;
        });
        long before = BrowserCrashGuard.rebuildCount();
        assertTrue(BrowserCrashGuard.recoverForced());
        assertTrue(BrowserCrashGuard.recoverForced());
        assertEquals(2, forcedCalls.get());
        assertEquals(before + 2, BrowserCrashGuard.rebuildCount());
    }

    @Test
    public void recoverAndRecoverForcedUseIndependentActions() {
        AtomicInteger normalCalls = new AtomicInteger();
        AtomicInteger forcedCalls = new AtomicInteger();
        BrowserCrashGuard.setRecoveryActionForTesting(() -> {
            normalCalls.incrementAndGet();
            return true;
        });
        BrowserCrashGuard.setForcedRecoveryActionForTesting(() -> {
            forcedCalls.incrementAndGet();
            return true;
        });
        assertTrue(BrowserCrashGuard.recover());
        assertTrue(BrowserCrashGuard.recoverForced());
        assertEquals(1, normalCalls.get());
        assertEquals(1, forcedCalls.get());
    }

    // ==================== ④ 与执行器集成：重跑 ====================

    /** 首次调用抛崩溃异常、第二次成功的可重跑任务。 */
    private static final class FlakyCrashTask implements ContextTask<String> {
        final AtomicInteger attempts = new AtomicInteger();
        final List<String> trace = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String name() {
            return "flaky-crash";
        }

        @Override
        public String call() throws Exception {
            int n = attempts.incrementAndGet();
            trace.add("call-" + n);
            if (n == 1) {
                throw new RuntimeException("Browser has been closed unexpectedly");
            }
            return "ok-" + n;
        }
    }

    @Test
    public void crashTaskIsReplayedOnceAndSucceeds() {
        AtomicLong rebuilds = new AtomicLong();
        BrowserCrashGuard.setRecoveryActionForTesting(() -> {
            rebuilds.incrementAndGet();
            return true;
        });
        FlakyCrashTask task = new FlakyCrashTask();
        List<ContextTaskResult<String>> results = ConcurrentContextExecutor.runAll(List.of(task));

        assertEquals(1, results.size());
        ContextTaskResult<String> r = results.get(0);
        assertTrue( r.isSuccess(), "重跑后应成功");
        assertEquals( "ok-2",  r.valueOrThrow(), "value should be ok-2");
        assertEquals( 2,  task.attempts.get(), "任务应被调用两次（首失败 + 重跑）");
        assertTrue( rebuilds.get() >= 1, "应触发至少一次恢复");
        // WEB-P1-4 验收 ②：重跑事件必须可在结果中识别，供编排线程在 Serenity 报告显式标注。
        assertTrue( r.isReplayed(), "重跑成功后仍应标注 replayed=true");
        assertEquals( "crash",  r.getRecoveryType(), "重跑类型应为 crash");
    }

    @Test
    public void nonCrashFailureIsNotReplayed() {
        AtomicInteger recoverCalls = new AtomicInteger();
        BrowserCrashGuard.setRecoveryActionForTesting(() -> {
            recoverCalls.incrementAndGet();
            return true;
        });
        ContextTask<String> task = ContextTask.of("business-fail", () -> {
            throw new AssertionError("real business assertion failure");
        });
        List<ContextTaskResult<String>> results = ConcurrentContextExecutor.runAll(List.of(task));

        assertEquals(1, results.size());
        assertFalse( results.get(0).isSuccess(), "业务失败不应被重跑");
        assertEquals( 0,  recoverCalls.get(), "业务失败不触发恢复");
    }

    @Test
    public void concurrentCrashTasksAllRecoverAndSucceed() {
        AtomicLong rebuilds = new AtomicLong();
        BrowserCrashGuard.setRecoveryActionForTesting(() -> {
            rebuilds.incrementAndGet();
            return true;
        });
        int n = 4;
        List<ContextTask<String>> tasks = new ArrayList<>();
        List<FlakyCrashTask> spies = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            FlakyCrashTask t = new FlakyCrashTask();
            spies.add(t);
            tasks.add(t);
        }
        List<ContextTaskResult<String>> results = ConcurrentContextExecutor.runAll(tasks,
                ConcurrentContextOptions.builder().parallelism(4).build());

        assertEquals(n, results.size());
        for (int i = 0; i < n; i++) {
            assertTrue( results.get(i).isSuccess(), "任务 " + i + " 重跑后应成功");
            assertEquals( 2,  spies.get(i).attempts.get(), "任务 " + i + " 应被调用两次");
        }
        assertTrue( rebuilds.get() >= 1, "并发崩溃应触发至少一次恢复（单飞）");
    }

    // ==================== ⑤ 句柄损坏 → 强制恢复 + 重跑（E2E 实测缺口加固） ====================

    /** 首次调用抛句柄损坏异常（Chromium __adopt__）、第二次成功的可重跑任务。 */
    private static final class FlakyHandleCorruptionTask implements ContextTask<String> {
        final AtomicInteger attempts = new AtomicInteger();

        @Override
        public String name() {
            return "flaky-handle-corruption";
        }

        @Override
        public String call() {
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException("Navigation failed",
                        new PlaywrightException("Cannot find object to call __adopt__: page@74c35fa29f431c10feee6f87dbcaa663"));
            }
            return "ok-2";
        }
    }

    @Test
    public void handleCorruptionTaskIsReplayedAfterForcedRecovery() {
        AtomicLong forcedRebuilds = new AtomicLong();
        BrowserCrashGuard.setForcedRecoveryActionForTesting(() -> {
            forcedRebuilds.incrementAndGet();
            return true;
        });
        FlakyHandleCorruptionTask task = new FlakyHandleCorruptionTask();
        List<ContextTaskResult<String>> results = ConcurrentContextExecutor.runAll(List.of(task));

        assertEquals(1, results.size());
        ContextTaskResult<String> r = results.get(0);
        assertTrue( r.isSuccess(), "强制恢复重跑后应成功");
        assertEquals("ok-2", r.valueOrThrow());
        assertEquals( 2,  task.attempts.get(), "任务应被调用两次（首失败 + 强制恢复重跑）");
        assertTrue( forcedRebuilds.get() >= 1, "应触发强制恢复");
    }

    @Test
    public void handleCorruptionRoutesToForcedRecoveryNotPlainRecovery() {
        AtomicLong plainCalls = new AtomicLong();
        AtomicLong forcedCalls = new AtomicLong();
        BrowserCrashGuard.setRecoveryActionForTesting(() -> {
            plainCalls.incrementAndGet();
            return true;
        });
        BrowserCrashGuard.setForcedRecoveryActionForTesting(() -> {
            forcedCalls.incrementAndGet();
            return true;
        });
        ContextTask<String> task = ContextTask.of("handle-corrupt", () -> {
            throw new RuntimeException("wrap",
                    new PlaywrightException("Cannot find object to call __adopt__: page@abc"));
        });
        List<ContextTaskResult<String>> results = ConcurrentContextExecutor.runAll(List.of(task));
        assertFalse( results.get(0).isSuccess(), "句柄损坏经强制恢复后仍失败则如实返回失败（不掩盖缺陷）");
        assertEquals( 0,  plainCalls.get(), "句柄损坏应走强制恢复而非普通恢复");
        assertEquals( 1,  forcedCalls.get(), "强制恢复应被调用一次");
    }

    @Test
    public void plainCrashRoutesToPlainRecoveryNotForced() {
        AtomicLong plainCalls = new AtomicLong();
        AtomicLong forcedCalls = new AtomicLong();
        BrowserCrashGuard.setRecoveryActionForTesting(() -> {
            plainCalls.incrementAndGet();
            return true;
        });
        BrowserCrashGuard.setForcedRecoveryActionForTesting(() -> {
            forcedCalls.incrementAndGet();
            return true;
        });
        ContextTask<String> task = ContextTask.of("plain-crash", () -> {
            throw new RuntimeException("Browser has been closed unexpectedly");
        });
        List<ContextTaskResult<String>> results = ConcurrentContextExecutor.runAll(List.of(task));
        assertFalse( results.get(0).isSuccess(), "普通崩溃（无句柄损坏签名）不掩盖失败");
        assertEquals( 1,  plainCalls.get(), "普通崩溃应走断开型恢复");
        assertEquals( 0,  forcedCalls.get(), "普通崩溃不应触发强制恢复");
    }
}
