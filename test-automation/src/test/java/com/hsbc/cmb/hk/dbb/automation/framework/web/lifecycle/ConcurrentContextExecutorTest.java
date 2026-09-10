package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTask;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ConcurrentContextExecutor} 单元测试（无需真实浏览器：任务不触碰 Playwright，
 * finally 的 cleanupForScenario 被执行器吞掉，便于无头运行）。
 */
public class ConcurrentContextExecutorTest {

    @Test
    public void emptyTasksReturnsEmpty() {
        assertTrue(ConcurrentContextExecutor.runAll(List.of()).isEmpty());
    }

    @Test
    public void allSucceedAndOrderPreserved() {
        List<ContextTask<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int v = i;
            tasks.add(ContextTask.of("t" + i, () -> v * 2));
        }
        List<ContextTaskResult<Integer>> r = ConcurrentContextExecutor.runAll(tasks,
                ConcurrentContextOptions.builder().parallelism(2).build());
        assertEquals(5, r.size());
        for (int i = 0; i < 5; i++) {
            assertTrue(r.get(i).isSuccess());
            assertEquals(i * 2, r.get(i).valueOrThrow().intValue());
            assertEquals("t" + i, r.get(i).getTaskName());
        }
    }

    @Test
    public void threadNamesArePoolScoped() {
        List<ContextTask<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(ContextTask.of("t" + i, () -> Thread.currentThread().getName()));
        }
        List<ContextTaskResult<String>> r = ConcurrentContextExecutor.runAll(tasks,
                ConcurrentContextOptions.builder().parallelism(2).build());
        for (ContextTaskResult<String> res : r) {
            assertTrue(res.isSuccess());
            assertTrue(res.valueOrThrow().startsWith("dbb-ctx-"));
        }
    }

    @Test
    public void failureIsolatedNotPollutingPool() {
        List<ContextTask<Integer>> tasks = new ArrayList<>();
        tasks.add(ContextTask.of("ok1", () -> 1));
        tasks.add(ContextTask.of("bad", () -> {
            throw new RuntimeException("boom");
        }));
        tasks.add(ContextTask.of("ok2", () -> 2));
        List<ContextTaskResult<Integer>> r = ConcurrentContextExecutor.runAll(tasks,
                ConcurrentContextOptions.builder().parallelism(3).build());
        assertTrue(r.get(0).isSuccess());
        assertFalse(r.get(1).isSuccess());
        assertTrue(r.get(2).isSuccess());
        assertEquals("boom", r.get(1).getFailure().getMessage());
    }

    @Test(expected = java.util.concurrent.CompletionException.class)
    public void assertAllSucceededThrowsOnFailure() {
        List<ContextTask<Integer>> tasks = new ArrayList<>();
        tasks.add(ContextTask.of("ok", () -> 1));
        tasks.add(ContextTask.of("bad", () -> {
            throw new RuntimeException("x");
        }));
        List<ContextTaskResult<Integer>> r = ConcurrentContextExecutor.runAll(tasks,
                ConcurrentContextOptions.builder().parallelism(2).build());
        ConcurrentContextExecutor.assertAllSucceeded(r);
    }

    @Test
    public void timeoutMarksFailureNotHang() throws Exception {
        List<ContextTask<Integer>> tasks = new ArrayList<>();
        tasks.add(ContextTask.of("slow", () -> {
            Thread.sleep(1000);
            return 1;
        }));
        tasks.add(ContextTask.of("fast", () -> 2));
        long start = System.currentTimeMillis();
        List<ContextTaskResult<Integer>> r = ConcurrentContextExecutor.runAll(tasks,
                ConcurrentContextOptions.builder().parallelism(2).perTaskTimeoutMillis(200).build());
        long took = System.currentTimeMillis() - start;
        assertTrue("should not hang; took=" + took, took < 5000);
        assertFalse(r.get(0).isSuccess());
        assertTrue(r.get(1).isSuccess());
        assertEquals(2, r.get(1).valueOrThrow().intValue());
        assertNotNull(r.get(0).getFailure());
        assertTrue(r.get(0).getFailure() instanceof java.util.concurrent.TimeoutException);
    }

    @Test
    public void failFastCancelsRemaining() {
        List<ContextTask<Integer>> tasks = new ArrayList<>();
        tasks.add(ContextTask.of("first-fail", () -> {
            throw new RuntimeException("fail-first");
        }));
        tasks.add(ContextTask.of("second", () -> {
            Thread.sleep(300);
            return 2;
        }));
        tasks.add(ContextTask.of("third", () -> 3));
        List<ContextTaskResult<Integer>> r = ConcurrentContextExecutor.runAll(tasks,
                ConcurrentContextOptions.builder().parallelism(3).failFast(true).build());
        assertEquals(3, r.size());
        assertFalse(r.get(0).isSuccess());
        assertFalse(r.get(1).isSuccess());
        assertFalse(r.get(2).isSuccess());
    }

    @Test
    public void parallelismCapRespected() {
        int parallelism = 2;
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger current = new AtomicInteger(0);
        List<ContextTask<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(ContextTask.of("t" + i, () -> {
                int c = current.incrementAndGet();
                maxConcurrent.accumulateAndGet(c, Math::max);
                Thread.sleep(150);
                current.decrementAndGet();
                return c;
            }));
        }
        List<ContextTaskResult<Integer>> r = ConcurrentContextExecutor.runAll(tasks,
                ConcurrentContextOptions.builder().parallelism(parallelism).build());
        for (ContextTaskResult<Integer> res : r) {
            assertTrue(res.isSuccess());
        }
        assertTrue("maxConcurrent=" + maxConcurrent.get(), maxConcurrent.get() <= parallelism);
    }

    @Test
    public void threadReuseNoCrossContamination() {
        List<ContextTask<String>> round1 = makeNameTasks();
        List<ContextTask<String>> round2 = makeNameTasks();
        List<ContextTaskResult<String>> r1 = ConcurrentContextExecutor.runAll(round1,
                ConcurrentContextOptions.builder().parallelism(2).build());
        List<ContextTaskResult<String>> r2 = ConcurrentContextExecutor.runAll(round2,
                ConcurrentContextOptions.builder().parallelism(2).build());
        for (int i = 0; i < 3; i++) {
            assertTrue(r1.get(i).isSuccess());
            assertTrue(r2.get(i).isSuccess());
            assertEquals("t" + i, r1.get(i).getTaskName());
            assertEquals("t" + i, r2.get(i).getTaskName());
        }
    }

    @Test
    public void virtualThreadsRunAllSucceed() {
        List<ContextTask<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int v = i;
            tasks.add(ContextTask.of("vt" + i, () -> v));
        }
        List<ContextTaskResult<Integer>> r = ConcurrentContextExecutor.runAll(tasks,
                ConcurrentContextOptions.builder().parallelism(4).useVirtualThreads(true).build());
        assertEquals(5, r.size());
        for (int i = 0; i < 5; i++) {
            assertTrue(r.get(i).isSuccess());
        }
    }

    private List<ContextTask<String>> makeNameTasks() {
        List<ContextTask<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int idx = i;
            tasks.add(ContextTask.of("t" + i, () -> "result-" + idx));
        }
        return tasks;
    }
}
