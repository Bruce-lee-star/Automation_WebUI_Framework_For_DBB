package com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * N-06 契约（doc 21 HIGH）：关闭 / 收尾阶段的清理失败必须<b>可观测且可断言</b>。
 *
 * <p><b>要防的缺陷</b>：{@code FrameworkCore} 的 JVM 关闭钩子与 {@code PlaywrightListener} 的套件收尾
 * 都把清理失败吞成 <b>verbose 门控</b>日志 —— 默认日志级别下完全无输出。而这恰恰是
 * 「孤儿浏览器进程堆积 → JVM 外内存耗尽（hs_err native OOM）」的唯一归因手段：泄漏无法被观测。</p>
 *
 * <p>修复后：失败既写<b>非 verbose 门控</b>的 ERROR（含堆栈），又递增
 * {@link ShutdownCoordinator#getFailureCount()}，使套件末尾 / CI 可用一行断言兜住。</p>
 *
 * <p>说明：{@link ShutdownCoordinator#runAll()} 内部的计数路径不在此直接触发 —— 该方法是全局的
 * （会执行并关闭其它组件已注册的关闭任务，污染同 JVM 的其它用例）。其计数改动为一行、可静态复核。</p>
 */
public class ShutdownCoordinatorFailureCountTest {

    @After
    public void tearDown() {
        ShutdownCoordinator.reset();
    }

    @Test
    // @DisplayName: "N-06：recordFailure 递增失败计数（关闭期清理失败可被断言）"
    public void recordFailureIncrementsCounter() {
        long before = ShutdownCoordinator.getFailureCount();

        ShutdownCoordinator.recordFailure("test/no-signal-guard", new IllegalStateException("simulated cleanup failure"));

        assertEquals("清理失败必须递增计数 —— 否则『泄漏』只能靠人读日志，CI 无法兜住", before + 1, ShutdownCoordinator.getFailureCount());
    }

    @Test
    // @DisplayName: "N-06：null 原因亦安全（不得因缺 cause 而 NPE，更不能静默不计数）"
    public void recordFailureIsNullSafe() {
        long before = ShutdownCoordinator.getFailureCount();

        ShutdownCoordinator.recordFailure("test/null-cause", null);

        assertEquals("无论是否有 cause，都必须计数（零信号失败是本次要消除的对象）", before + 1, ShutdownCoordinator.getFailureCount());
    }

    @Test
    // @DisplayName: "N-06：reset 复位失败计数（与清空关闭任务同义，保证用例隔离）"
    public void resetClearsCounter() {
        ShutdownCoordinator.recordFailure("test/reset", new RuntimeException("boom"));

        ShutdownCoordinator.reset();

        assertEquals("reset 必须复位失败计数", 0, ShutdownCoordinator.getFailureCount());
    }
}
