package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.SerenityBusBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTask;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCrashGuard;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.TestContextBridge;

import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
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
 *
 * @apiNote <b>框架内部能力</b>：仅供 {@code framework.web.lifecycle} 包树协作者与并发桥接使用；业务代码不得直接依赖。
 */
public final class ConcurrentContextExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentContextExecutor.class);
    private static final int DEFAULT_HARD_CAP = 16;

    private ConcurrentContextExecutor() {
    }

    /** 使用 WebFrameworkConfig 默认选项运行。 */
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
        // 在编排线程（runAll 调用方）将"崩溃恢复重跑"事件显式标注到 Serenity 报告（WEB-P1-4 验收 ②）。
        // 工作线程从不触碰 Serenity 总线（桥接原则 9.3）；此处调用方即编排线程，安全。
        SerenityBusBridge.recordCrashRecoveryIfAny(results);
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
                    "timeout", 0L, List.of());
        } catch (ExecutionException ee) {
            return ContextTaskResult.failure(task.name(), ee.getCause(),
                    "executor", 0L, List.of());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ContextTaskResult.failure(task.name(), ie, "interrupted", 0L, List.of());
        } catch (CancellationException ce) {
            return ContextTaskResult.cancelled(task.name(), "cancelled");
        }
    }

    private static <T> ContextTaskResult<T> executeOne(ContextTask<T> task) {
        ContextTaskResult<T> r = runOnce(task);
        if (r.isSuccess() || !shouldAttemptRecovery(r)) {
            return r;
        }
        // 疑似浏览器故障：按类型走进程级单飞恢复后重跑一次（严格有界，见 BrowserCrashGuard.maxReplay）。
        //  - 句柄注册表损坏（__adopt__/previewUpdated，isConnected 仍为 true）→ 强制重建共享 Browser
        //    （否则在损坏 Browser 上重跑必再败，E2E 实测 2026-09-08 全批次级联失败即因此）；
        //  - 其余崩溃（断开/进程被杀）→ 断开型重建（连接仍在时 no-op，仅重跑）。
        boolean handleCorruption = BrowserCrashGuard.isHandleCorruption(r.getFailure());
        String type = handleCorruption ? "handle-corruption" : "crash";
        String firstMsg = firstFailureMessage(r.getFailure());
        LOGGER.warn("[concurrent] task '{}' failed with suspected browser {} ({}); replaying once after recovery",
                task.name(), type, firstMsg);
        boolean recovered = handleCorruption ? BrowserCrashGuard.recoverForced() : BrowserCrashGuard.recover();
        if (recovered) {
            ContextTaskResult<T> replayedResult = runOnce(task);
            // 携带重跑标注（任务名/类型/首次失败诊断），供编排线程在 Serenity 报告显式标注（WEB-P1-4 验收 ②）。
            return ContextTaskResult.replayed(replayedResult, type, firstMsg);
        }
        return r;
    }

    private static <T> boolean shouldAttemptRecovery(ContextTaskResult<T> r) {
        // WEB-P1-4：不再按异常类名模糊匹配崩溃，故此处须显式把"句柄注册表损坏"也纳入恢复门槛，
        // 否则 __adopt__ / previewUpdated 这类 Chromium 句柄损坏（PlaywrightException 子类、无崩溃信号文本）
        // 在移除类名匹配后会被漏判、不再触发强制重建重跑。正常业务失败（断言 / 超时）二者皆不满足，不重跑。
        return BrowserCrashGuard.isEnabled()
                && r.getFailure() != null
                && (BrowserCrashGuard.isCrash(r.getFailure())
                    || BrowserCrashGuard.isHandleCorruption(r.getFailure()));
    }

    private static String failureClass(Throwable t) {
        return t == null ? "null" : t.getClass().getSimpleName();
    }

    /** 首次失败的简短诊断（类名 + 消息），用于重跑事件报告标注与排障。 */
    private static String firstFailureMessage(Throwable t) {
        if (t == null) {
            return "null";
        }
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + ": " + (msg == null ? "null" : msg);
    }

    private static <T> ContextTaskResult<T> runOnce(ContextTask<T> task) {
        long start = System.nanoTime();
        String threadName = Thread.currentThread().getName();
        List<String> pageErrors = new ArrayList<>();
        try {
            MDC.put("concurrentTask", task.name());
            T value = task.call();
            pageErrors.addAll(TestContextBridge.drainPageErrors());
            return ContextTaskResult.success(task.name(), value, threadName, elapsed(start), pageErrors);
        } catch (Throwable t) {
            pageErrors.addAll(TestContextBridge.drainPageErrors());
            LOGGER.error("[concurrent] task '{}' failed on {}: {}", task.name(), threadName, t.getMessage());
            return ContextTaskResult.failure(task.name(), t, threadName, elapsed(start), pageErrors);
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
     * 编排线程回放：任一失败则经 Serenity 既有通道标记并抛出（带全部失败汇总，桥接 9.3）。
     */
    public static <T> void assertAllSucceeded(List<ContextTaskResult<T>> results) {
        SerenityBusBridge.replayFailures(results);
    }
}
