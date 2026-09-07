package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 页面错误汇聚单测（设计文档 9.10-⑤）：工作线程累积的未捕获页面异常，经 {@link TestContextBridge}
 * 在任务结束时 draining 并随 {@link ContextTaskResult} 回传编排线程（不在工作线程触碰 Serenity）。
 */
public class TestContextBridgeTest {

    /** 与 PageEventMonitor.PENDING_PAGE_ERRORS_KEY 同源的键，用于在任务线程暂存页面错误。 */
    @SuppressWarnings("unchecked")
    private static final ContextKey<List> PENDING_PAGE_ERRORS_KEY =
            ContextKey.of("pageEventMonitor.pendingPageErrors", List.class);

    @Test
    public void workerPageErrorsAggregatedIntoResult() {
        List<ContextTask<String>> tasks = new ArrayList<>();
        tasks.add(ContextTask.of("t1", () -> {
            //  模拟 PageEventMonitor 在工作线程累积的未捕获页面异常
            List<String> errs = (List<String>) TestContextHolder.get()
                    .computeIfAbsent(PENDING_PAGE_ERRORS_KEY, ArrayList::new);
            errs.add("uncaught page error: ReferenceError: x is not defined");
            return "ok";
        }));
        List<ContextTaskResult<String>> r = ConcurrentContextExecutor.runAll(tasks,
                ConcurrentContextOptions.builder().parallelism(1).build());

        assertTrue(r.get(0).isSuccess());
        assertTrue("page error drained into result",
                r.get(0).getPageErrors().contains("uncaught page error: ReferenceError: x is not defined"));
    }

    @Test
    public void pageErrorsDoNotFalseFailSuccessfulTask() {
        List<ContextTask<String>> tasks = new ArrayList<>();
        tasks.add(ContextTask.of("t2", () -> {
            List<String> errs = (List<String>) TestContextHolder.get()
                    .computeIfAbsent(PENDING_PAGE_ERRORS_KEY, ArrayList::new);
            errs.add("late page error on success path");
            return "ok";
        }));
        List<ContextTaskResult<String>> r = ConcurrentContextExecutor.runAll(tasks,
                ConcurrentContextOptions.builder().parallelism(1).build());

        //  成功任务不因页面错误而被判失败；但错误仍被汇聚进结果供编排线程统一标记
        assertTrue(r.get(0).isSuccess());
        assertFalse(r.get(0).getPageErrors().isEmpty());
        ConcurrentContextExecutor.assertAllSucceeded(r); // 不应抛
    }
}
