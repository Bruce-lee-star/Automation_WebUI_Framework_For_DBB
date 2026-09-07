package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 企业级并发上下文执行器（设计文档 C2 + 第九节）。
 *
 * <p>以有界线程池（或 JDK21 虚拟线程）并发运行多个独立 {@link ContextTask}，每个任务在独立线程上获得
 * 独立 {@code BrowserContext}（由 {@link PlaywrightManager} 的 per-thread 隔离保证），任务结束后清理本线程
 * Context（不关闭共享 Browser）。失败 / 页面错误经结构化 {@link ContextTaskResult} 返回，<b>不在工作线程触碰
 * Serenity 事件总线</b>（桥接原则 9.3）。</p>
 */
public final class ConcurrentContextExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentContextExecutor.class);
    private static final int DEFAULT_HARD_CAP = 16;

    private ConcurrentContextExecutor() {
    }

    /** 使用 FrameworkConfig 默认选项运行。 */
    public static <T> List<ContextTaskResult<T>> runAll(List<ContextTask<T>> tasks) {
        return runAll(tasks, ConcurrentContextOptions.defaults());
    }

    /**
     * 并发运行全部任务，返回与输入等序的结果列表。
     */
    public static <T> List<ContextTaskResult<T>> runAll(List<ContextTask<T>> tasks, ConcurrentContextOptions options) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        int parallelism = options.resolvedParallelism(tasks.size());
        boolean virtual = options.useVirtualThreads();
        ExecutorService pool = virtual
                ? Executors.newVirtualThreadPerTaskExecutor()
                : new ThreadPoolExecutor(parallelism, parallelism, 0L, TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(), new ContextThreadFactory());

        int n = tasks.size();
        List<Future<ContextTaskResult<T>>> futures = new ArrayList<>(n);
        List<ContextTaskResult<T>> results = new ArrayList<>(Collections.nCopies(n, null));

        try {
            for (int i = 0; i < n; i++) {
                final ContextTask<T> task = tasks.get(i);
                futures.add(pool.submit(() -> executeOne(task)));
            }

            boolean stop = false;
            for (int i = 0; i < n; i++) {
                if (stop) {
                    futures.get(i).cancel(true);
                    results.set(i, ContextTaskResult.cancelled(tasks.get(i).name(), "cancelled(failFast)"));
                    continue;
                }
                ContextTaskResult<T> r = awaitOne(futures.get(i), tasks.get(i), options);
                results.set(i, r);
                if (options.failFast() && !r.isSuccess()) {
                    stop = true;
                }
            }
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
        return List.copyOf(results);
    }

    private static <T> ContextTaskResult<T> awaitOne(Future<ContextTaskResult<T>> f, ContextTask<T> task,
                                                     ConcurrentContextOptions options) {
        try {
            if (options.perTaskTimeoutMillis() > 0) {
                return f.get(options.perTaskTimeoutMillis(), TimeUnit.MILLISECONDS);
            }
            return f.get();
        } catch (TimeoutException te) {
            f.cancel(true);
            return ContextTaskResult.failure(task.name(),
                    new TimeoutException("task exceeded " + options.perTaskTimeoutMillis() + "ms"),
                    "timeout", 0L, List.of(), Map.of());
        } catch (ExecutionException ee) {
            return ContextTaskResult.failure(task.name(), ee.getCause(),
                    "executor", 0L, List.of(), Map.of());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ContextTaskResult.failure(task.name(), ie, "interrupted", 0L, List.of(), Map.of());
        } catch (CancellationException ce) {
            return ContextTaskResult.cancelled(task.name(), "cancelled");
        }
    }

    private static <T> ContextTaskResult<T> executeOne(ContextTask<T> task) {
        long start = System.nanoTime();
        String threadName = Thread.currentThread().getName();
        List<String> pageErrors = new ArrayList<>();
        try {
            MDC.put("concurrentTask", task.name());
            T value = task.call();
            pageErrors.addAll(safeDrainPageErrors());
            return ContextTaskResult.success(task.name(), value, threadName, elapsed(start), pageErrors, Map.of());
        } catch (Throwable t) {
            pageErrors.addAll(safeDrainPageErrors());
            LOGGER.error("[concurrent] task '{}' failed on {}: {}", task.name(), threadName, t.getMessage());
            return ContextTaskResult.failure(task.name(), t, threadName, elapsed(start), pageErrors, Map.of());
        } finally {
            MDC.remove("concurrentTask");
            // 关闭本线程 Context/Page（不关闭共享 Browser）；非初始化环境下吞掉，便于无头单测。
            try {
                PlaywrightManager.cleanupForScenario();
            } catch (Throwable ignore) {
                LOGGER.debug("[concurrent] cleanupForScenario no-op on {}", threadName);
            }
        }
    }

    private static List<String> safeDrainPageErrors() {
        try {
            return PageEventMonitor.drainPendingPageErrors();
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static final class ContextThreadFactory implements ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "dbb-ctx-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * 编排线程回放：任一失败则抛出（带全部失败汇总），由 Serenity 既有通道标记（桥接 9.3）。
     */
    public static <T> void assertAllSucceeded(List<ContextTaskResult<T>> results) {
        List<String> failures = new ArrayList<>();
        for (ContextTaskResult<T> r : results) {
            if (!r.isSuccess()) {
                try {
                    r.valueOrThrow();
                } catch (CompletionException ce) {
                    failures.add(ce.getMessage());
                }
            }
        }
        if (!failures.isEmpty()) {
            throw new CompletionException("ConcurrentContextExecutor: " + failures.size()
                    + " task(s) failed -> " + failures, null);
        }
    }
}
