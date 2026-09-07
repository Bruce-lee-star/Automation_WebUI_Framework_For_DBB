package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.microsoft.playwright.PlaywrightException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link BrowserCrashGuard} 单测（固化设计文档 9.6 / R7）：
 * ① 崩溃型异常被正确识别（Playwright 类 / 消息特征）；正常异常不误判；
 * ② {@code isEnabled()} 受配置与实时系统属性控制；
 * ③ 单飞恢复：注入恢复动作后 {@code recover()} 计数且并发安全；
 * ④ 与 {@link ConcurrentContextExecutor} 集成：崩溃任务被重跑一次后成功、非崩溃失败不重跑。
 */
public class BrowserCrashGuardTest {

    @BeforeClass
    public static void enableGuard() {
        System.setProperty("serenity.playwright.concurrent.browser.crash.guard.enabled", "true");
    }

    @AfterClass
    public static void restore() {
        System.clearProperty("serenity.playwright.concurrent.browser.crash.guard.enabled");
    }

    /** 每个用例后复位注入的恢复动作，避免静态状态串扰其它测试。 */
    @After
    public void resetAction() {
        BrowserCrashGuard.setRecoveryActionForTesting(null);
    }

    // ==================== ① 崩溃检测 ====================

    @Test
    public void detectsCrashByMessageSignature() {
        assertTrue(BrowserCrashGuard.isCrash(new RuntimeException("Target page, context or browser has been closed")));
        assertTrue(BrowserCrashGuard.isCrash(new IllegalStateException("Browser has been closed by crash")));
        assertTrue(BrowserCrashGuard.isCrash(new Exception("connection closed unexpectedly")));
        Throwable wrapped = new RuntimeException("wrapper", new IllegalStateException("execution context was destroyed"));
        assertTrue(BrowserCrashGuard.isCrash(wrapped));
    }

    @Test
    public void detectsCrashByPlaywrightExceptionClass() {
        assertTrue(BrowserCrashGuard.isCrash(new PlaywrightException("navigation interrupted")));
    }

    @Test
    public void doesNotFlagNormalFailuresAsCrash() {
        assertFalse(BrowserCrashGuard.isCrash(new AssertionError("expected true but false")));
        assertFalse(BrowserCrashGuard.isCrash(new RuntimeException("business validation boom")));
        assertFalse(BrowserCrashGuard.isCrash(null));
    }

    // ==================== ② 开关 ====================

    @Test
    public void enabledByDefaultAndViaSystemProperty() {
        assertTrue("默认应启用", BrowserCrashGuard.isEnabled());
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
        assertTrue("重跑后应成功", r.isSuccess());
        assertEquals("value should be ok-2", "ok-2", r.valueOrThrow());
        assertEquals("任务应被调用两次（首失败 + 重跑）", 2, task.attempts.get());
        assertTrue("应触发至少一次恢复", rebuilds.get() >= 1);
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
        assertFalse("业务失败不应被重跑", results.get(0).isSuccess());
        assertEquals("业务失败不触发恢复", 0, recoverCalls.get());
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
            assertTrue("任务 " + i + " 重跑后应成功", results.get(i).isSuccess());
            assertEquals("任务 " + i + " 应被调用两次", 2, spies.get(i).attempts.get());
        }
        assertTrue("并发崩溃应触发至少一次恢复（单飞）", rebuilds.get() >= 1);
    }
}
