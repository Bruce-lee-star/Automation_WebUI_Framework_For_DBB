package com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.microsoft.playwright.Route;

import java.util.Map;

/**
 * 统一的 Monitor 响应处理入口。
 *
 * <p>采集策略只负责提供响应快照，路由拦截器不应直接承载断言、记录和 fail-fast
 * 逻辑。当前实现复用既有断言实现，先建立稳定的迁移边界，后续可逐步移除
 * {@link MonitorHandler} 对响应处理的依赖。</p>
 */
public final class MonitorResponseProcessor {
    private MonitorResponseProcessor() {
    }

    public static void process(Route route, RouteRule rule, ApiCaptureContext context,
                               String url, int status, String body, String method,
                               String requestBody, Map<String, String> requestHeaders,
                               Map<String, String> responseHeaders) {
        process(route, rule, context, new MonitorResponse(url, status, body, method,
                requestBody, requestHeaders, responseHeaders));
    }

    /**
     * 新事件链入口：只处理浏览器无关的断言、fail-fast、结果记录和匹配计数。
     * 旧 route handler 入口继续保留，待 callback/report/persistence 迁移完成后切换。
     */
    public static void processEvent(RouteRule rule, ApiCaptureContext context,
                                    MonitorResponse response) {
        processEvent(MonitorRuleSnapshot.from(rule), rule, context, response);
    }

    public static void processEvent(MonitorRuleSnapshot snapshot, ApiCaptureContext context,
                                    MonitorResponse response) {
        processEvent(snapshot, null, context, response);
    }

    private static void processEvent(MonitorRuleSnapshot snapshot, RouteRule legacyRule,
                                     ApiCaptureContext context, MonitorResponse response) {
        MonitorCompletionCoordinator.complete(snapshot, legacyRule, context, response);
    }

    /**
     * 保留 Route 参数的兼容入口；Route 仅由调用方持有，处理器不再访问浏览器对象。
     */
    public static void process(Route route, RouteRule rule, ApiCaptureContext context,
                               MonitorResponse response) {
        MonitorCompletionCoordinator.complete(rule, context, response);
    }
}
