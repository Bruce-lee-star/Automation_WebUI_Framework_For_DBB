package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent;

import java.util.concurrent.CompletionException;

import java.util.List;

/**
 * 单任务执行结果（结构化，避免工作线程触碰 Serenity 事件总线）。
 *
 * <p>编排线程在 {@code runAll} 返回后遍历结果，对失败项调用 {@link #valueOrThrow()} 即可在
 * 自身线程上抛出，由既有 Serenity 步骤失败通道统一标记（设计文档 9.3 桥接原则）。</p>
 *
 * @apiNote <b>框架内部能力</b>：仅供 {@code framework.web.lifecycle} 包树协作者与并发桥接使用；业务代码不得直接依赖。
 */
public final class ContextTaskResult<T> {

    private final String taskName;
    private final boolean success;
    private final T value;
    private final Throwable failure;
    private final String threadName;
    private final long durationMillis;
    private final List<String> pageErrors;

    /** 是否因浏览器崩溃/句柄损坏而触发过自动重跑（WEB-P1-4：重跑事件需在报告中显式标注）。 */
    private final boolean replayed;
    /** 重跑类型：{@code "crash"}（断开型恢复）或 {@code "handle-corruption"}（强制重建恢复）；未重跑为 null。 */
    private final String recoveryType;
    /** 首次失败（触发重跑的那次）的简短诊断，用于报告标注与排障。 */
    private final String firstFailureMessage;

    private ContextTaskResult(String taskName, boolean success, T value, Throwable failure,
                              String threadName, long durationMillis, List<String> pageErrors,
                              boolean replayed, String recoveryType, String firstFailureMessage) {
        this.taskName = taskName;
        this.success = success;
        this.value = value;
        this.failure = failure;
        this.threadName = threadName;
        this.durationMillis = durationMillis;
        this.pageErrors = pageErrors;
        this.replayed = replayed;
        this.recoveryType = recoveryType;
        this.firstFailureMessage = firstFailureMessage;
    }

    public static <T> ContextTaskResult<T> success(String name, T value, String threadName, long durationMillis,
                                           List<String> pageErrors) {
        return new ContextTaskResult<>(name, true, value, null, threadName, durationMillis, pageErrors,
                false, null, null);
    }

    public static <T> ContextTaskResult<T> failure(String name, Throwable failure, String threadName, long durationMillis,
                                           List<String> pageErrors) {
        return new ContextTaskResult<>(name, false, null, failure, threadName, durationMillis, pageErrors,
                false, null, null);
    }

    public static <T> ContextTaskResult<T> cancelled(String name, String threadName) {
        return new ContextTaskResult<>(name, false, null,
                new IllegalStateException("Task cancelled (failFast) before execution"),
                threadName, 0L, List.of(), false, null, null);
    }

    /**
     * 由一次"崩溃恢复重跑"后的结果构造带重跑标注的副本（保留原结果的成功/失败/耗时/页面错误等全部字段，
     * 仅追加 {@code replayed=true} 与重跑类型 / 首次失败诊断）。供 {@link ConcurrentContextExecutor} 在重跑路径返回。
     *
     * @param base 重跑后的真实结果
     * @param recoveryType 重跑类型（{@code "crash"} / {@code "handle-corruption"}）
     * @param firstFailureMessage 触发重跑的首次失败诊断
     */
    public static <T> ContextTaskResult<T> replayed(ContextTaskResult<T> base, String recoveryType,
                                             String firstFailureMessage) {
        return new ContextTaskResult<>(base.taskName, base.success, base.value, base.failure,
                base.threadName, base.durationMillis, base.pageErrors, true, recoveryType, firstFailureMessage);
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

    /** 失败 / 取消原因（成功时为 null）。 */
    public Throwable getFailure() {
        return failure;
    }

    /** 是否因浏览器崩溃/句柄损坏触发过自动重跑。 */
    public boolean isReplayed() {
        return replayed;
    }

    /** 重跑类型：{@code "crash"} / {@code "handle-corruption"}；未重跑为 null。 */
    public String getRecoveryType() {
        return recoveryType;
    }

    /** 触发重跑的首次失败诊断（用于报告标注与排障）；未重跑为 null。 */
    public String getFirstFailureMessage() {
        return firstFailureMessage;
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
