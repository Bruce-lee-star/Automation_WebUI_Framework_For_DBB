package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

import java.util.HashSet;
import java.util.Set;

/**
 *  收口 {@code PlaywrightListener} 的 per-thread 守卫与失败日志去重状态。
 *
 * <p>原状态散落在监听器顶部：{@code LISTENER_GUARDS_KEY} 守卫（防重入 / 防双重处理）与
 * {@code REPORTED_FAILURES_KEY} 失败日志去重集合，二者均为 per-thread。统一迁入本类后，
 * 监听器只负责生命周期编排，状态语义聚合且可被 {@link StepFailureAggregator} 共享复用，
 * 消除「新增回调路径漏清某个 ThreadLocal → 跨 scenario 残留」的缺口（T3-1 TestContext 接缝的集中落地）。</p>
 *
 * <p><b>线程安全：</b>所有状态经 {@link TestContextHolder} 的 per-thread 存储访问，天然线程隔离；
 * 无共享可变静态状态，{@link #clearForThread()} 仅移除当前线程的 ContextKey。</p>
 */
final class ListenerGuard {

    /** 当前测试名 ContextKey（监听器与失败聚合器共享，避免跨类重复定义）。 */
    static final ContextKey<String> CURRENT_TEST_NAME_KEY =
            ContextKey.of("playwrightListener.currentTestName", String.class);

    /** 防重入 / 防双重处理守卫，收拢为单个 per-thread 状态对象。 */
    private static final ContextKey<ListenerGuardState> LISTENER_GUARDS_KEY =
            ContextKey.of("playwrightListener.listenerGuards", ListenerGuardState.class);

    /** 失败日志去重集合（同一 Throwable 实例一次 scenario 内只完整打印一次）。 */
    private static final ContextKey<Set> REPORTED_FAILURES_KEY =
            ContextKey.of("playwrightListener.reportedFailures", Set.class);

    private ListenerGuard() {
    }

    /** 取本线程的守卫状态（惰性创建，等价原 PlaywrightListener.guards()）。 */
    static ListenerGuardState guards() {
        return TestContextHolder.get().computeIfAbsent(LISTENER_GUARDS_KEY, ListenerGuardState::new);
    }

    /** 取本线程的失败去重集合（惰性创建）。 */
    @SuppressWarnings("unchecked")
    private static Set<String> reportedFailures() {
        return (Set<String>) TestContextHolder.get().computeIfAbsent(REPORTED_FAILURES_KEY, HashSet::new);
    }

    /**
     * 同一异常首次出现返回 true 并打印 error 级摘要；后续出现返回 false（调用方静默）。
     *
     * @param t       失败异常
     * @param summary 人类可读的失败摘要（短消息）
     * @return 是否应完整打印（首次为 true）
     */
    static boolean shouldReportFailure(Throwable t, String summary) {
        if (t == null) {
            return reportedFailures().add("null:" + (summary == null ? "" : summary));
        }
        String key = t.getClass().getName() + "|"
                + (t.getMessage() == null ? "" : t.getMessage().split("\n", -1)[0]) + "|"
                + System.identityHashCode(t);
        return reportedFailures().add(key);
    }

    /** 清空本线程守卫状态与失败去重记录（scenario 结束时调用，防跨 scenario 残留）。 */
    static void clearForThread() {
        TestContextHolder.get().remove(LISTENER_GUARDS_KEY);
        TestContextHolder.get().remove(REPORTED_FAILURES_KEY);
    }

    /** per-thread 守卫状态：收拢原 4 个布尔守卫（takingScreenshot 已迁至 FailureScreenshotHandler）。 */
    static final class ListenerGuardState {
        private boolean failureScreenshotsAlreadySent;
        private boolean stepFinishProcessed;
        private boolean stepFinishReentrant;
        private boolean apiFailureAlreadyHandled;

        boolean isFailureScreenshotsAlreadySent() {
            return failureScreenshotsAlreadySent;
        }

        void setFailureScreenshotsAlreadySent(boolean v) {
            failureScreenshotsAlreadySent = v;
        }

        boolean isStepFinishProcessed() {
            return stepFinishProcessed;
        }

        void setStepFinishProcessed(boolean v) {
            stepFinishProcessed = v;
        }

        boolean isStepFinishReentrant() {
            return stepFinishReentrant;
        }

        void setStepFinishReentrant(boolean v) {
            stepFinishReentrant = v;
        }

        boolean isApiFailureAlreadyHandled() {
            return apiFailureAlreadyHandled;
        }

        void setApiFailureAlreadyHandled(boolean v) {
            apiFailureAlreadyHandled = v;
        }
    }
}
