package com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.RouteUtil;
import com.jayway.jsonpath.JsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 浏览器无关的 Monitor 状态码和 JSONPath 断言器。 */
public final class MonitorAssertionEvaluator {
    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorAssertionEvaluator.class);
    private static final Map<String, JsonPath> JSONPATH_CACHE = new ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 1024;

    private MonitorAssertionEvaluator() {
    }

    public static boolean evaluate(RouteRule rule, MonitorResponse response, ApiCaptureContext context) {
        return evaluate(MonitorRuleSnapshot.from(rule), response, context);
    }

    public static boolean evaluate(MonitorRuleSnapshot rule, MonitorResponse response, ApiCaptureContext context) {
        boolean passed = true;
        Integer expected = rule.expectedStatus();
        if (expected != null && expected != response.status()) {
            if (context != null) {
                context.recordAssertionFailure(response.url(), "STATUS", String.valueOf(expected),
                        String.valueOf(response.status()), null);
            }
            passed = false;
        }
        Map<String, Object> assertions = rule.jsonPathAssertions();
        if (assertions != null && !assertions.isEmpty()) {
            if (response.bodyAvailability() == BodyAvailability.UNAVAILABLE
                    || response.bodyAvailability() == BodyAvailability.NOT_REQUESTED) {
                if (context != null) {
                    context.recordAssertionFailure(response.url(), "BODY_UNAVAILABLE", "JSON body",
                            response.bodyAvailability().name(), "body required by JSONPath assertion");
                }
                return false;
            }
            for (Map.Entry<String, Object> entry : assertions.entrySet()) {
                try {
                    Object actual = jsonPath(entry.getKey()).read(response.body());
                    if (!compare(actual, entry.getValue())) {
                        if (context != null) {
                            context.recordAssertionFailure(response.url(), "JSONPATH",
                                    String.valueOf(entry.getValue()), String.valueOf(actual),
                                    "path=" + entry.getKey());
                        }
                        passed = false;
                    }
                } catch (Exception e) {
                    LOGGER.debug("Monitor JSONPath evaluation failed: {}", e.getMessage());
                    if (context != null) {
                        context.recordAssertionFailure(response.url(), "JSONPATH",
                                String.valueOf(entry.getValue()), "ERROR", "path=" + entry.getKey());
                    }
                    passed = false;
                }
            }
        }
        return passed;
    }

    private static JsonPath jsonPath(String expression) {
        JsonPath cached = JSONPATH_CACHE.get(expression);
        if (cached != null) return cached;
        if (JSONPATH_CACHE.size() >= CACHE_LIMIT) {
            // 修复 P0-4：原实现 size 达上限后 clear() 全量清空，导致周期性 CPU 尖刺与缓存失效。
            // 改为弱一致性批量淘汰约 1/4 旧条目，保留热点编译结果，避免性能抖动。
            RouteUtil.evictOldestQuarter(JSONPATH_CACHE);
        }
        return JSONPATH_CACHE.computeIfAbsent(expression, RouteUtil::compileJsonPath);
    }

    /** 弱一致性批量移除约 1/4 条目（与 P0-1 同策略）。不保证精确，但足以抑制无限增长。
     * 注意：不能用 entrySet().iterator().remove()，ConcurrentHashMap 在结构变更时会抛 IllegalStateException；
     * 改为先收集候选 key，再逐个 remove(key)（原子且并发安全）。 */
    private static boolean compare(Object actual, Object expected) {
        if (actual == null || expected == null) return actual == expected;
        if (actual instanceof Number a && expected instanceof Number e) {
            return Math.abs(a.doubleValue() - e.doubleValue()) < 1e-9;
        }
        return actual.toString().equals(expected.toString());
    }
}
