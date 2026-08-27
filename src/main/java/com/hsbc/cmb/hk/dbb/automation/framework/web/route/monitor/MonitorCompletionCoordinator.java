package com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;

/**
 * Monitor 完成语义的浏览器无关协调器。
 *
 * <p>该类只承接已获取的响应快照，不接触 Playwright {@code Route}/{@code Response}。
 * 它是旧 route handler 与后续事件采集路径共用的内部实现，不改变 DSL、回调签名或
 * {@link ApiCaptureContext} 的查询和等待契约。</p>
 */
public final class MonitorCompletionCoordinator {
    private MonitorCompletionCoordinator() {
    }

    /** 保持既有 route Monitor 的完成、失败和副作用顺序。
     *  ⭐ P2-20：失败路径统一与 snapshot 版一致（recordFailure + signalFailFast + throw），
     *  避免 rule 版漏记失败报告导致失败报告缺失该记录。 */
    public static void complete(RouteRule rule, ApiCaptureContext context, MonitorResponse response) {
        MonitorRuleSnapshot snapshot = MonitorRuleSnapshot.from(rule);
        if (!MonitorAssertionEvaluator.evaluate(snapshot, response, context)) {
            MonitorResultRecorder.recordFailure(snapshot, response, failureReason(snapshot, response));
            if (context != null) context.signalFailFast();
            throw assertionFailure(snapshot, response);
        }
        if (context == null) return;
        completeSuccess(snapshot, rule, context, response);
    }

    /**
     * 采集阶段失败必须显式结束 Monitor，不能以日志加 return 的方式造成断言假通过。
     */
    public static void captureFailure(RouteRule rule, ApiCaptureContext context,
                                      MonitorResponse response, String reason) {
        MonitorRuleSnapshot snapshot = MonitorRuleSnapshot.from(rule);
        MonitorResultRecorder.recordFailure(snapshot, response, "capture failure: " + reason);
        if (context != null) context.signalFailFast();
        throw new RouteException.ApiAssertionException(rule.getUrlPattern(), "CAPTURE",
                "response available", reason);
    }

    /** 供不持有可变 RouteRule 的事件路径使用。 */
    public static void complete(MonitorRuleSnapshot snapshot, RouteRule sessionRule,
                                ApiCaptureContext context, MonitorResponse response) {
        if (!MonitorAssertionEvaluator.evaluate(snapshot, response, context)) {
            MonitorResultRecorder.recordFailure(snapshot, response, failureReason(snapshot, response));
            if (context != null) context.signalFailFast();
            throw assertionFailure(snapshot, response);
        }
        if (context == null) return;
        completeSuccess(snapshot, sessionRule, context, response);
    }

    private static void completeSuccess(MonitorRuleSnapshot snapshot, RouteRule sessionRule,
                                        ApiCaptureContext context, MonitorResponse response) {
        try {
            MonitorResultRecorder.record(snapshot, context, response,
                    sessionRule == null ? null : () -> RouteEngine.onMonitorMatch(sessionRule));
        } finally {
            context.decrementActiveRequests();
        }
    }

    private static RouteException.ApiAssertionException assertionFailure(MonitorRuleSnapshot snapshot,
                                                                           MonitorResponse response) {
        return new RouteException.ApiAssertionException(snapshot.urlPattern(), "ASSERTION",
                snapshot.expectedStatus() == null ? "N/A" : String.valueOf(snapshot.expectedStatus()),
                String.valueOf(response.status()));
    }

    private static String failureReason(MonitorRuleSnapshot snapshot, MonitorResponse response) {
        return "monitor assertion failed, expected="
                + (snapshot.expectedStatus() == null ? "N/A" : snapshot.expectedStatus())
                + ", actual=" + response.status();
    }

}
