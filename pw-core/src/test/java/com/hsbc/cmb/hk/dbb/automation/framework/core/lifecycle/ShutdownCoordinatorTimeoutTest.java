package com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * N-22 契约（doc 21 补记）：JVM 退出期的清理<b>不得无界</b> —— 否则 fork 会被 Surefire 硬杀。
 *
 * <p><b>实测来源</b>：`fw-inst3.log` 中 {@code [ERROR] Surefire is going to kill self fork JVM. The exit has
 * elapsed 30 seconds after System.exit(0).}。同一日志显示关闭编排器只打印了 3/5 个任务名 —— 后两个任务
 * <b>根本没执行</b>：{@code pw-core}（{@code PlaywrightManager.cleanupAll()}，W-1 为"避免硬杀留下孤儿
 * 浏览器"而注册）自身耗时约 27s，越过 Surefire 的 30s 宽限后被硬杀。原设计目标恰被这次硬杀推翻，
 * 且没有任何可断言信号。</p>
 *
 * <p>修复后：单任务有上限、总量有预算；超时/跳过一律 <b>记 ERROR + 递增可断言计数</b>，
 * 把"静默截断"换成"显式记账"。</p>
 */
public class ShutdownCoordinatorTimeoutTest {

    private static final String TASK_PROP = ShutdownCoordinator.TASK_TIMEOUT_PROPERTY;
    private static final String TOTAL_PROP = ShutdownCoordinator.TOTAL_BUDGET_PROPERTY;

    /** 用于放行"故意卡住"的任务线程（daemon，本会随 JVM 消亡；用例内放行以免线程跨用例堆积）。 */
    private CountDownLatch release;

    @Before
    public void setUp() {
        ShutdownCoordinator.reset();
        release = new CountDownLatch(1);
    }

    @After
    public void tearDown() {
        System.clearProperty(TASK_PROP);
        System.clearProperty(TOTAL_PROP);
        release.countDown();
        ShutdownCoordinator.reset();
    }

    @Test
    // @DisplayName: "N-22：卡住的清理任务不得超过单任务上限（原先会拖到 fork 被硬杀）"
    public void blockingTaskIsAbandonedAtPerTaskLimit() {
        System.setProperty(TASK_PROP, "300");
        System.setProperty(TOTAL_PROP, "5000");
        ShutdownCoordinator.register(100, "test/blocking", () -> awaitQuietly(release));

        long start = System.nanoTime();
        ShutdownCoordinator.runAll();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue("runAll 必须在上限附近返回（实测 " + elapsedMs + "ms）；否则 JVM 退出必然被 Surefire 硬杀，"
                        + "而硬杀会静默截断后续清理任务", elapsedMs < 3_000);
        assertEquals("被放弃的任务必须记账 —— 否则「清理没做完」仍是零信号", 1, ShutdownCoordinator.getFailureCount());
    }

    @Test
    // @DisplayName: "N-22：总预算耗尽 → 停止后续任务并记账（替代被硬杀后的静默截断）"
    public void totalBudgetExhaustionSkipsRemainingTasks() {
        System.setProperty(TASK_PROP, "2000");
        System.setProperty(TOTAL_PROP, "200");
        AtomicBoolean laterTaskRan = new AtomicBoolean(false);
        ShutdownCoordinator.register(100, "test/blocking", () -> awaitQuietly(release));
        ShutdownCoordinator.register(200, "test/later", () -> laterTaskRan.set(true));

        long start = System.nanoTime();
        ShutdownCoordinator.runAll();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue("总预算必须兜住整体耗时（实测 " + elapsedMs + "ms）", elapsedMs < 3_000);
        assertFalse("预算耗尽后不得再启动后续任务 —— 必须「显式跳过并记账」，而不是继续等到被硬杀", laterTaskRan.get());
        assertEquals("1 次超时放弃 + 1 次预算耗尽跳过，二者都必须记账", 2, ShutdownCoordinator.getFailureCount());
    }

    @Test
    // @DisplayName: "N-22：正常任务仍在 runAll 返回前完成（收尾语义不因「有界」而变弱）"
    public void fastTasksCompleteBeforeRunAllReturns() {
        AtomicBoolean ran = new AtomicBoolean(false);
        ShutdownCoordinator.register(100, "test/fast", () -> ran.set(true));

        ShutdownCoordinator.runAll();

        assertTrue("runAll 返回即代表清理已结束（HikariConfigFactoryTest 依赖此语义："
                + "调用后数据源必须已关闭）", ran.get());
        assertEquals("正常路径不得记账", 0, ShutdownCoordinator.getFailureCount());
    }

    @Test
    // @DisplayName: "N-22：非正预算不表示「无上限」，而是回落默认（与 N-16/N-19 同一纪律）"
    public void nonPositiveBudgetFallsBackToDefault() {
        System.setProperty(TASK_PROP, "0");
        assertEquals("0 不得被当作「无上限」—— 那正是本预算要消除的隐患", ShutdownCoordinator.DEFAULT_TASK_TIMEOUT_MS, ShutdownCoordinator.resolveBudget(TASK_PROP, ShutdownCoordinator.DEFAULT_TASK_TIMEOUT_MS));

        System.setProperty(TOTAL_PROP, "-1");
        assertEquals("负数同样回落默认", ShutdownCoordinator.DEFAULT_TOTAL_BUDGET_MS, ShutdownCoordinator.resolveBudget(TOTAL_PROP, ShutdownCoordinator.DEFAULT_TOTAL_BUDGET_MS));

        System.setProperty(TASK_PROP, "1234");
        assertEquals("正值必须原样生效，否则预算不可调", 1234, ShutdownCoordinator.resolveBudget(TASK_PROP, ShutdownCoordinator.DEFAULT_TASK_TIMEOUT_MS));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
