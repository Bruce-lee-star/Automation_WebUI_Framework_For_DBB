package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.monitor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一次被采集 API 调用的完整快照（CAPTURE 能力的报告 DTO，不可变）。
 *
 * <p>与 {@link CapturedExchange}（monitor 断言导向）不同，本类是<b>信息导向</b>：
 * 请求侧（方法/URL/头/请求体预览）+ 响应侧（状态码/头/响应体/耗时）+ 元信息（pattern/记录时刻/超时），
 * 供业务层 dump 消费（诊断 / 报告 / 断言辅助）。
 *
 * <p>安全与容量防线：
 * <ul>
 *   <li><b>敏感头脱敏</b>：authorization / cookie / set-cookie / token 等敏感头值一律打码
 *       （{@code ******}），只保留键名——快照可安全落日志 / 报告；</li>
 *   <li><b>体截断</b>：请求体预览 ≤ 2 KiB；响应体受 {@code captureBodyLimitBytes} 约束，
 *       超限截断并置 {@link #responseBodyTruncated()}；</li>
 *   <li><b>防御拷贝</b>：header 快照不可变，杜绝调用方修改内部状态（EI_EXPOSE_REP2）。</li>
 * </ul>
 *
 * @param pattern                命中的规则 pattern
 * @param method                 请求方法（GET / POST / ...）
 * @param url                    请求完整 URL
 * @param requestHeaders         请求头（敏感值已脱敏，不可变）
 * @param requestBodyPreview     请求体预览（≤2 KiB；null=无请求体）
 * @param responseStatus         实际响应状态码（未等到响应为 null，配合 timedOut）
 * @param responseHeaders        响应头（敏感值已脱敏，不可变）
 * @param responseBody           响应体（captureBody 开启且有响应才非 null；受截断上限约束）
 * @param responseBodyTruncated  响应体是否被截断（超出 captureBodyLimitBytes）
 * @param durationMs             请求到响应耗时（毫秒；未响应为 -1）
 * @param timedOut               是否在超时窗口内未等到响应
 * @param recordedAtMillis       记录创建时刻（epoch millis）
 */
public record CapturedApiCall(String pattern, String method, String url,
                              Map<String, String> requestHeaders, String requestBodyPreview,
                              Integer responseStatus, Map<String, String> responseHeaders,
                              String responseBody, boolean responseBodyTruncated,
                              long durationMs, boolean timedOut, long recordedAtMillis) {

    /** 敏感头键（小写比较）：值一律打码。 */
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "api-key", "proxy-authenticate", "www-authenticate");

    /** 头键包含这些片段（小写）也视为敏感（如 x-auth-token）。 */
    private static final String[] SENSITIVE_HEADER_FRAGMENTS = {
            "token", "secret", "password", "credential"
    };

    /** 打码占位值。 */
    private static final String MASK = "******";

    /** 防御性拷贝构造：header 快照不可变 + 敏感头脱敏。 */
    public CapturedApiCall {
        requestHeaders = sanitize(requestHeaders);
        responseHeaders = sanitize(responseHeaders);
    }

    /** 覆盖 record accessor：返回不可变拷贝（防调用方修改内部快照）。 */
    @Override
    public Map<String, String> requestHeaders() {
        return Map.copyOf(requestHeaders);
    }

    /** 覆盖 record accessor：返回不可变拷贝（防调用方修改内部快照）。 */
    @Override
    public Map<String, String> responseHeaders() {
        return Map.copyOf(responseHeaders);
    }

    /** 脱敏 + 不可变拷贝；null 视为空 Map。 */
    private static Map<String, String> sanitize(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>(headers.size());
        for (Map.Entry<String, String> e : headers.entrySet()) {
            copy.put(e.getKey(), isSensitive(e.getKey()) ? MASK : e.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    /** 键名（小写）命中敏感集或含敏感片段 → 打码。 */
    private static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if (SENSITIVE_HEADERS.contains(lower)) {
            return true;
        }
        for (String fragment : SENSITIVE_HEADER_FRAGMENTS) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** 单行摘要（供日志 / 报告首行展示）。 */
    public String summary() {
        if (timedOut) {
            return "[timeout] " + method + " " + url + " (pattern=" + pattern + ")";
        }
        return "[status=" + responseStatus + "] " + method + " " + url + " (pattern=" + pattern + ')';
    }
}
