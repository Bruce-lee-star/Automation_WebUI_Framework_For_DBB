package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTask;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 致命缺陷3 修复验证：并发执行窗口内调用 {@link PlaywrightManager#cleanupAll()} 必须被运行时断言拒绝，
 * 防止单个 scenario 的异常关停所有线程的浏览器（并行整轮集体失败）。
 *
 * <p>探针 {@link ConcurrentContextExecutor#isConcurrentModeActive()} 在 runAll 提交任务后置位、finally 复位；
 * 并发任务内调用 cleanupAll 应立刻抛 {@link IllegalStateException}（在真正清理前），无副作用。</p>
 */
public class PlaywrightManagerCleanupAllGuardTest {

    @Test
    public void cleanupAllRejectsCallDuringConcurrentExecution() {
        List<ContextTask<String>> tasks = List.of(ContextTask.of("guard-probe", () -> {
            try {
                PlaywrightManager.cleanupAll();
                return "ALLOWED";
            } catch (IllegalStateException e) {
                return "REJECTED:" + e.getClass().getSimpleName();
            }
        }));

        List<ContextTaskResult<String>> results = ConcurrentContextExecutor.runAll(tasks);
        assertEquals(1, results.size());
        assertTrue(
                results.get(0).valueOrThrow().startsWith("REJECTED:"), "cleanupAll must reject concurrent-mode call with IllegalStateException");
    }
}
