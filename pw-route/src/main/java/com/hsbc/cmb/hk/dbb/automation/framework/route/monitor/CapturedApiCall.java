package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;

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
 *   <li><b>敏感头脱敏</b>：authorization / cookie / set-cookie / token 等敏感头值一律打码为
 *       {@link SensitiveDataSanitizer#maskToken()}（与全框架统一掩码一致），只保留键名；</li>
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

    /** 日志展示用 JSON 缩进器（线程安全，复用同一实例）。 */
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

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

    /** 脱敏 + 不可变拷贝；null 视为空 Map。掩码取全框架统一值（{@link SensitiveDataSanitizer#maskToken()}）。 */
    private static Map<String, String> sanitize(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        String mask = SensitiveDataSanitizer.maskToken();
        Map<String, String> copy = new LinkedHashMap<>(headers.size());
        for (Map.Entry<String, String> e : headers.entrySet()) {
            copy.put(e.getKey(), isSensitive(e.getKey()) ? mask : e.getValue());
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

    /** 单行摘要（供日志 / 报告首行展示）。URL 已脱敏（query 命中敏感键即整体丢弃）。 */
    public String summary() {
        String safeUrl = SensitiveDataSanitizer.sanitizeUrl(url);
        if (timedOut) {
            return "[timeout] " + method + " " + safeUrl + " (pattern=" + pattern + ")";
        }
        return "[status=" + responseStatus + "] " + method + " " + safeUrl + " (pattern=" + pattern + ')';
    }

    /**
     * 多行 pretty 明细（供日志展示）：规则 / 请求 / 响应三段式，头逐行、体自动 JSON 缩进。
     *
     * <p><b>绝不抛异常、绝不影响主流程</b>：体是否为 JSON 均可——JSON 体自动缩进，非 JSON 体原样
     * （仅脱敏），解析失败一律回退；任何展示期异常都退化为 {@link #summary()}。</p>
     *
     * <p>头值构造期已脱敏；体先经 {@link SensitiveDataSanitizer#sanitizeBody(String)} 打码，
     * 响应体超限在段内附 {@code [truncated]} 标记。示例：
     * <pre>{@code
     * [Route] captured api {
     *   pattern   : profile/list
     *   request   : POST https://host/.../profile/list
     *   duration  : 417ms
     *   reqHeaders:
     *     accept: application/json
     *     cookie: ***
     *   reqBody:
     *     {
     *       "user": "u"
     *     }
     *   response  : 200
     *   respHeaders:
     *     content-type: application/json
     *   respBody:
     *     {
     *       "updateContctOverlayFlag": "Y"
     *     }
     * }
     * }</pre>
     */
    public String detail() {
        try {
            return renderDetail();
        } catch (RuntimeException e) {
            // 展示失败绝不上抛：退化为摘要，采集/断言等主流程不受影响
            return summary();
        }
    }

    /** pretty 明细渲染（受 {@link #detail()} 的 catch 保护）。 */
    private String renderDetail() {
        StringBuilder d = new StringBuilder("{\n");
        d.append("  pattern   : ").append(pattern).append('\n');
        d.append("  request   : ").append(method).append(' ')
                .append(SensitiveDataSanitizer.sanitizeUrl(url)).append('\n');
        if (durationMs >= 0) {
            d.append("  duration  : ").append(durationMs).append("ms\n");
        }
        appendHeaders(d, "reqHeaders", requestHeaders);
        appendBody(d, "reqBody", requestBodyPreview, false);
        d.append("  response  : ").append(timedOut ? "[timeout]" : String.valueOf(responseStatus)).append('\n');
        appendHeaders(d, "respHeaders", responseHeaders);
        appendBody(d, "respBody", responseBody, responseBodyTruncated);
        d.append('}');
        return d.toString();
    }

    /** 头段落：逐行 {@code key: value}。 */
    private static void appendHeaders(StringBuilder d, String label, Map<String, String> headers) {
        d.append("  ").append(label).append(":\n");
        if (headers == null || headers.isEmpty()) {
            d.append("    (none)\n");
            return;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            d.append("    ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
    }

    /** 体段落：整体缩进 4 空格（JSON 自动 pretty；空体整段省略）。 */
    private static void appendBody(StringBuilder d, String label, String body, boolean truncated) {
        if (body == null || body.isEmpty()) {
            return;
        }
        d.append("  ").append(label).append(':');
        if (truncated) {
            d.append(" [truncated]");
        }
        d.append('\n');
        for (String line : prettyBody(body).split("\n", -1)) {
            d.append("    ").append(line).append('\n');
        }
    }

    /**
     * 体脱敏 + JSON 缩进。非 JSON 体（HTML / 文本 / 二进制文本等）<b>原样返回</b>（已脱敏），
     * 绝不因"不是 JSON"而报错；脱敏失败时宁可不出内容也不泄露。
     */
    private static String prettyBody(String body) {
        String safe;
        try {
            safe = SensitiveDataSanitizer.sanitizeBody(body);
        } catch (RuntimeException e) {
            return "[body unavailable]";
        }
        String trimmed = safe == null ? "" : safe.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return PRETTY_GSON.toJson(JsonParser.parseString(trimmed));
            } catch (RuntimeException notJson) {
                // 看起来像 JSON 但解析失败 → 回退脱敏原文
                return safe;
            }
        }
        return safe;
    }
}
