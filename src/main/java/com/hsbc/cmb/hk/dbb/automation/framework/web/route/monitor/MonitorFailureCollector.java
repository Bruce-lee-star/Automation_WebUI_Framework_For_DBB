package com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API 监控失败归集器（单例）。
 *
 * <p>职责：
 * <ul>
 *   <li>收集所有监控断言失败的 API 调用（含完整 request / response）</li>
 *   <li><b>指纹去重</b>：同一 endpoint 的相同错误（pattern + statusCode + 响应体 hash）只保留一条，
 *       并累计触发该失败的 scenario 列表，避免一封邮件被同一错误刷屏</li>
 *   <li>按 {@code apiOwner} 分组，便于后续“谁的 API 发给谁”</li>
 * </ul>
 *
 * <p>注意：本类不负责发邮件，仅产出结构化数据。邮件由 CI（Jenkins emailext 等）读取
 * {@code target/monitor-failures-by-owner.json} 循环投递。
 */
public class MonitorFailureCollector {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MonitorFailureCollector.class);

    private static final String UNASSIGNED = "unassigned";

    /** 指纹 → 去重后的失败记录 */
    private final Map<String, FailedApiCall> dedupMap = new ConcurrentHashMap<>();

    /** 当前 scenario 名（由测试框架在 scenario 开始时 set） */
    private final ThreadLocal<String> currentScenario = new ThreadLocal<>();

    private static volatile MonitorFailureCollector INSTANCE;

    public static MonitorFailureCollector getInstance() {
        if (INSTANCE == null) {
            synchronized (MonitorFailureCollector.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MonitorFailureCollector();
                }
            }
        }
        return INSTANCE;
    }

    public static void reset() {
        INSTANCE = null;
    }

    /** 设置当前 scenario 名（测试框架 hook 调用） */
    public void setCurrentScenario(String name) {
        currentScenario.set(name);
    }

    public void clearCurrentScenario() {
        currentScenario.remove();
    }

    /**
     * 记录一次 API 监控失败。
     *
     * @param call     捕获到的调用快照（含 request/response）
     * @param pattern  匹配的监控 pattern
     * @param owner    apiOwner 邮箱（可为 null）
     * @param reason   失败原因（如 "status=500 expected=200" 或 JSONPath 断言详情）
     */
    public void record(CapturedApiCall call, String pattern, String owner, String reason) {
        String status = call.statusCode() == 0 ? "N/A" : String.valueOf(call.statusCode());
        String body = call.responseBody() == null ? "" : call.responseBody();
        // 指纹：pattern + 状态码 + 响应体 hash，确保同一错误的多次触发被合并
        String fingerprint = pattern + "|" + status + "|" + Integer.toHexString(body.hashCode());
        FailedApiCall existing = dedupMap.get(fingerprint);
        if (existing != null) {
            String scn = safeScenario();
            if (scn != null && !existing.getScenarios().contains(scn)) {
                existing.getScenarios().add(scn);
            }
            LoggingConfigUtil.logDebugIfVerbose(LOGGER,
                    "[ApiMonitor] 重复失败已合并：pattern='{}' status='{}'", pattern, status);
            return;
        }

        FailedApiCall rec = new FailedApiCall();
        rec.setFeature(currentFeature());
        rec.setPattern(pattern);
        rec.setOwner(owner == null || owner.trim().isEmpty() ? UNASSIGNED : owner.trim());
        rec.setStatus(status);
        rec.setMethod(call.method());
        rec.setRequestUrl(call.requestUrl());
        rec.setRequestHeaders(call.requestHeaders());
        rec.setRequestBody(call.requestBody());
        rec.setResponseHeaders(call.responseHeaders());
        rec.setResponseBody(call.responseBody());
        rec.setReason(reason);
        String scn = safeScenario();
        if (scn != null) {
            rec.getScenarios().add(scn);
        }
        dedupMap.put(fingerprint, rec);
        LoggingConfigUtil.logInfoIfVerbose(LOGGER,
                "[ApiMonitor] 记录 API 失败：owner='{}' pattern='{}' status='{}'", rec.getOwner(), pattern, status);
    }

    /** 按 owner 分组（供 CI 邮件循环） */
    public Map<String, List<FailedApiCall>> getFailuresByOwner() {
        Map<String, List<FailedApiCall>> byOwner = new LinkedHashMap<>();
        for (FailedApiCall call : dedupMap.values()) {
            byOwner.computeIfAbsent(call.getOwner(), k -> new ArrayList<>()).add(call);
        }
        return byOwner;
    }

    public int getFailureCount() {
        return dedupMap.size();
    }

    /** 清空（测试套件结束时） */
    public void clear() {
        dedupMap.clear();
        currentScenario.remove();
    }

    private String safeScenario() {
        try {
            return currentScenario.get();
        } catch (Exception e) {
            return null;
        }
    }

    /** feature 名通过 ThreadLocal 透传（可选，便于报告归类） */
    private final ThreadLocal<String> currentFeature = new ThreadLocal<>();

    public void setCurrentFeature(String feature) {
        currentFeature.set(feature);
    }

    public void clearCurrentFeature() {
        currentFeature.remove();
    }

    private String currentFeature() {
        try {
            return currentFeature.get();
        } catch (Exception e) {
            return null;
        }
    }

    /** 单条去重后的失败记录 */
    public static class FailedApiCall {
        private String feature;
        private String pattern;
        private String owner;
        private String status;
        private String method;
        private String requestUrl;
        private Map<String, String> requestHeaders;
        private String requestBody;
        private Map<String, String> responseHeaders;
        private String responseBody;
        private String reason;
        private final List<String> scenarios = new ArrayList<>();

        public String getFeature() {
            return feature;
        }

        public void setFeature(String feature) {
            this.feature = feature;
        }

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public String getRequestUrl() {
            return requestUrl;
        }

        public void setRequestUrl(String requestUrl) {
            this.requestUrl = requestUrl;
        }

        public Map<String, String> getRequestHeaders() {
            return requestHeaders;
        }

        public void setRequestHeaders(Map<String, String> requestHeaders) {
            this.requestHeaders = requestHeaders;
        }

        public String getRequestBody() {
            return requestBody;
        }

        public void setRequestBody(String requestBody) {
            this.requestBody = requestBody;
        }

        public Map<String, String> getResponseHeaders() {
            return responseHeaders;
        }

        public void setResponseHeaders(Map<String, String> responseHeaders) {
            this.responseHeaders = responseHeaders;
        }

        public String getResponseBody() {
            return responseBody;
        }

        public void setResponseBody(String responseBody) {
            this.responseBody = responseBody;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public List<String> getScenarios() {
            return scenarios;
        }
    }
}
