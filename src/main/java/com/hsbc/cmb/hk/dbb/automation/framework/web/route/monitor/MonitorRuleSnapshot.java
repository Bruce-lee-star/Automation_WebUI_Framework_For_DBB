package com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Monitor 运行时不可变快照；事件处理链不直接持有可变 RouteRule。
 */
public record MonitorRuleSnapshot(
        String urlPattern,
        RouteHandleType type,
        boolean monitorEnabled,
        boolean record,
        Integer expectedStatus,
        Map<String, Object> jsonPathAssertions,
        long timeoutMs,
        int minMatches,
        boolean autoStopOnMatch,
        Set<String> resourceTypes,
        String matchMethod,
        Map<String, String> matchHeaders,
        Map<String, String> matchQuery,
        String matchContentType,
        String matchReferrer,
        String matchOrigin,
        String matchFrameUrl,
        boolean onlyMainFrame,
        boolean onlyApiCall,
        List<?> monitorCallbacks
) {
    public MonitorRuleSnapshot {
        jsonPathAssertions = jsonPathAssertions == null ? Map.of() : Map.copyOf(jsonPathAssertions);
        resourceTypes = resourceTypes == null ? Set.of() : Set.copyOf(resourceTypes);
        matchHeaders = matchHeaders == null ? Map.of() : Map.copyOf(matchHeaders);
        matchQuery = matchQuery == null ? Map.of() : Map.copyOf(matchQuery);
        monitorCallbacks = monitorCallbacks == null ? List.of() : List.copyOf(monitorCallbacks);
    }

    /**
     * ⭐ P2-18：强类型回调列表（不可变，与 {@link #monitorCallbacks()} 同引用）。
     * 供 {@code MonitorResultRecorder.dispatchCallbacks} 直接复用，避免每次匹配二次 {@code List.copyOf}。
     */
    @SuppressWarnings("unchecked")
    public List<Object> monitorCallbacksTyped() {
        return (List<Object>) monitorCallbacks;
    }

    public static MonitorRuleSnapshot from(RouteRule rule) {
        return new MonitorRuleSnapshot(
                rule.getUrlPattern(), rule.getType(), rule.isMonitorEnabled(), rule.isRecord(),
                rule.getExpectedStatus(), rule.getJsonPathAssertions(), rule.getTimeoutMs(), rule.getMinMatches(),
                rule.isAutoStopOnMatch(), rule.getResourceTypeSet(), rule.getMatchMethod(),
                rule.getMatchHeaders(), rule.getMatchQuery(), rule.getMatchContentType(),
                rule.getMatchReferrer(), rule.getMatchOrigin(), rule.getMatchFrameUrl(),
                rule.isOnlyMainFrame(), rule.isOnlyApiCall(), rule.getMonitorCallbacks());
    }
}
