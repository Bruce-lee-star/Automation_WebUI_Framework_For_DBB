package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;
import com.microsoft.playwright.Request;

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

    /** 请求体预览上限（与 {@code CapturedExchange} 对齐）。 */
    private static final int REQUEST_BODY_PREVIEW_MAX = 2048;

    /**
     * 字段式构造入口（响应侧可为空）——供 route 派发链与 monitor 结果证据共用一套渲染。
     */
    public static CapturedApiCall of(String pattern, String method, String url,
                                     Map<String, String> requestHeaders, String requestBodyPreview,
                                     Integer responseStatus, Map<String, String> responseHeaders,
                                     String responseBody, boolean responseBodyTruncated,
                                     long durationMs, boolean timedOut) {
        return new CapturedApiCall(pattern, method, url, requestHeaders, requestBodyPreview,
                responseStatus, responseHeaders, responseBody, responseBodyTruncated,
                durationMs, timedOut, System.currentTimeMillis());
    }

    /**
     * 从 Playwright 请求对象构造快照 —— 供<b>派发链</b>上报"命中/动作"证据（MOCK / MODIFY / DELAY
     * 没有响应侧观测队列，其请求侧信息只能这样取；请求体按 {@value #REQUEST_BODY_PREVIEW_MAX} 字节截断）。
     */
    public static CapturedApiCall ofRequest(String pattern, Request request, Integer responseStatus,
                                            Map<String, String> responseHeaders, String responseBody,
                                            boolean responseBodyTruncated, long durationMs, boolean timedOut) {
        String postData = request == null ? null : request.postData();
        String preview = postData == null ? null
                : (postData.length() > REQUEST_BODY_PREVIEW_MAX
                        ? postData.substring(0, REQUEST_BODY_PREVIEW_MAX) + "...[truncated]" : postData);
        return new CapturedApiCall(pattern,
                request == null ? null : request.method(),
                request == null ? null : request.url(),
                request == null ? Map.of() : request.headers(),
                preview, responseStatus, responseHeaders, responseBody, responseBodyTruncated,
                durationMs, timedOut, System.currentTimeMillis());
    }

    /** 在 {@link #detail()} 尾部追加自定义行（如 rule / applied / result），供路由证据使用。 */
    public String detailWith(String... extraLines) {
        StringBuilder d = new StringBuilder(detail());
        if (extraLines != null) {
            for (String line : extraLines) {
                if (line != null && !line.isEmpty()) {
                    for (String part : line.split("\n", -1)) {
                        d.append('\n').append("  ").append(part);
                    }
                }
            }
        }
        return d.toString();
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

    /** 单行摘要（供日志 / 报告首行展示）。URL 已脱敏；null 值不出现（不打印 null 占位）。 */
    public String summary() {
        StringBuilder s = new StringBuilder(96);
        if (timedOut) {
            s.append("[timeout] ");
        } else if (responseStatus != null) {
            s.append("[status=").append(responseStatus).append("] ");
        }
        if (method != null) {
            s.append(method).append(' ');
        }
        s.append(SensitiveDataSanitizer.sanitizeUrl(url));
        if (pattern != null && !pattern.isEmpty()) {
            s.append(" (pattern=").append(pattern).append(')');
        }
        return s.toString().trim();
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

    /**
     * pretty 明细渲染（受 {@link #detail()} 的 catch 保护）。
     *
     * <p><b>null / 空的字段整行不显示</b>（不打印 {@code null}、也不打印 {@code (none)} 占位）——
     * 报告里每一行都是"确实有值"的信息；块内文案一律英文（报告可被跨团队直接阅读）。</p>
     */
    private String renderDetail() {
        StringBuilder d = new StringBuilder("{\n");
        appendField(d, "pattern", pattern);
        if (method != null || url != null) {
            appendField(d, "request", (method == null ? "" : method + " ")
                    + SensitiveDataSanitizer.sanitizeUrl(url));
        }
        if (durationMs >= 0) {
            appendField(d, "duration", durationMs + "ms");
        }
        appendHeaders(d, "reqHeaders", requestHeaders);
        appendBody(d, "reqBody", requestBodyPreview, false);
        if (timedOut) {
            appendField(d, "response", "[timeout]");
        } else {
            appendField(d, "response", responseStatus);
        }
        appendHeaders(d, "respHeaders", responseHeaders);
        appendBody(d, "respBody", responseBody, responseBodyTruncated);
        d.append('}');
        return d.toString();
    }

    /** 单字段行（{@code label} 补齐到 10 字符后接 {@code ": "}）；null / 空值整行不输出。 */
    static void appendField(StringBuilder d, String label, Object value) {
        if (label == null || value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return;
        }
        d.append("  ").append(label);
        for (int i = label.length(); i < FIELD_LABEL_WIDTH; i++) {
            d.append(' ');
        }
        d.append(": ").append(text).append('\n');
    }

    /** 字段名对齐宽度（{@code "  " + label 补齐至该宽度 + ": "}）。 */
    private static final int FIELD_LABEL_WIDTH = 10;

    /** 头段落：逐行 {@code key: value}；整段为空（或值全为 null）时整段不输出。 */
    private static void appendHeaders(StringBuilder d, String label, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        d.append("  ").append(label);
        for (int i = label.length(); i < FIELD_LABEL_WIDTH; i++) {
            d.append(' ');
        }
        d.append(":\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getValue() != null) {
                d.append("    ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
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
