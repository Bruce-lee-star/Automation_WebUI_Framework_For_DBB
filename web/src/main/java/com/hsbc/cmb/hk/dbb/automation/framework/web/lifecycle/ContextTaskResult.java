package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

/**
 * 单任务执行结果（结构化，避免工作线程触碰 Serenity 事件总线）。
 *
 * <p>编排线程在 {@code runAll} 返回后遍历结果，对失败项调用 {@link #valueOrThrow()} 即可在
 * 自身线程上抛出，由既有 Serenity 步骤失败通道统一标记（设计文档 9.3 桥接原则）。</p>
 */
public final class ContextTaskResult<T> {

    private final String taskName;
    private final boolean success;
    private final T value;
    private final Throwable failure;
    private final String threadName;
    private final long durationMillis;
    private final List<String> pageErrors;
    private final Map<String, Object> diagnostics;

    private ContextTaskResult(String taskName, boolean success, T value, Throwable failure,
                              String threadName, long durationMillis, List<String> pageErrors,
                              Map<String, Object> diagnostics) {
        this.taskName = taskName;
        this.success = success;
        this.value = value;
        this.failure = failure;
        this.threadName = threadName;
        this.durationMillis = durationMillis;
        this.pageErrors = pageErrors;
        this.diagnostics = diagnostics;
    }

    static <T> ContextTaskResult<T> success(String name, T value, String threadName, long durationMillis,
                                           List<String> pageErrors, Map<String, Object> diagnostics) {
        return new ContextTaskResult<>(name, true, value, null, threadName, durationMillis, pageErrors, diagnostics);
    }

    static <T> ContextTaskResult<T> failure(String name, Throwable failure, String threadName, long durationMillis,
                                           List<String> pageErrors, Map<String, Object> diagnostics) {
        return new ContextTaskResult<>(name, false, null, failure, threadName, durationMillis, pageErrors, diagnostics);
    }

    static <T> ContextTaskResult<T> cancelled(String name, String threadName) {
        return new ContextTaskResult<>(name, false, null,
                new IllegalStateException("Task cancelled (failFast) before execution"),
                threadName, 0L, List.of(), Map.of());
    }

    public boolean isSuccess() {
        return success;
    }

    public String getTaskName() {
        return taskName;
    }

    public String getThreadName() {
        return threadName;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public List<String> getPageErrors() {
        return pageErrors;
    }

    public Map<String, Object> getDiagnostics() {
        return diagnostics;
    }

    /** 失败 / 取消原因（成功时为 null）。 */
    public Throwable getFailure() {
        return failure;
    }

    /** 成功时的结果；失败 / 取消时抛出（带页面错误诊断），便于编排线程回放失败。 */
    public T valueOrThrow() {
        if (success) {
            return value;
        }
        String msg = "Task '" + taskName + "' failed on thread " + threadName
                + " in " + durationMillis + "ms"
                + (pageErrors.isEmpty() ? "" : "; pageErrors=" + pageErrors);
        Throwable f = failure != null ? failure : new IllegalStateException("cancelled");
        throw new CompletionException(msg, f);
    }

    @Override
    public String toString() {
        return "ContextTaskResult{task=" + taskName + ", success=" + success
                + ", thread=" + threadName + ", durationMs=" + durationMillis
                + ", pageErrors=" + pageErrors.size() + '}';
    }
}
