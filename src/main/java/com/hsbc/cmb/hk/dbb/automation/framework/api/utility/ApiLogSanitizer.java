package com.hsbc.cmb.hk.dbb.automation.framework.api.utility;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SensitiveDataSanitizer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API 层日志脱敏入口（P2-26）。
 * <p>
 * 背景：{@code AbstractApiJobHelper} / {@code Entity} / {@code EntityBuilder} / {@code ConfigProvider}
 * 原先以 INFO 级别打印 header、cookie、请求体与各类参数的<b>原始值</b>，其中
 * {@code Authorization}、会话 Cookie、含密码的请求体会明文落盘到构建日志，
 * 构成凭证泄露（CI 日志通常可被广泛读取）。
 * <p>
 * 设计取舍：本类<b>不重复实现</b>脱敏规则，而是委托给
 * {@link SensitiveDataSanitizer} —— 它是全框架统一的安全基础设施（而非 web 专属功能），
 * 委托可保证 API 层与 Route/Monitor 层的敏感判定口径完全一致，避免出现两套标准。
 * 本类仅负责把 API 层的 {@code Map<String, Object>} 数据结构适配到其
 * {@code Map<String, String>} 入口。
 */
public final class ApiLogSanitizer {

    private ApiLogSanitizer() {
    }

    /**
     * 将 headers / cookies / params 等键值对转为可直接打日志的脱敏字符串。
     * 命中敏感名（Authorization、Cookie、token、secret…）的值整体替换为掩码。
     *
     * @param values 原始键值对，允许 null
     * @return 脱敏后的字符串（入参为 null 时返回 "null"）
     */
    public static String toLogString(Map<String, Object> values) {
        if (values == null) {
            return "null";
        }
        Map<String, String> converted = new LinkedHashMap<>(Math.max(values.size(), 1));
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Object v = e.getValue();
            converted.put(String.valueOf(e.getKey()), v == null ? null : String.valueOf(v));
        }
        return SensitiveDataSanitizer.sanitizeHeaders(converted).toString();
    }

    /**
     * 脱敏单个键值（用于 "Added header: name = value" 这类逐条日志）。
     *
     * @param name  键名（header / cookie / 参数名）
     * @param value 原始值
     * @return 脱敏后的值；敏感键返回统一掩码
     */
    public static Object valueForLog(String name, Object value) {
        if (value == null) {
            return null;
        }
        String key = String.valueOf(name);
        Map<String, String> single = new LinkedHashMap<>(1);
        single.put(key, String.valueOf(value));
        return SensitiveDataSanitizer.sanitizeHeaders(single).get(key);
    }

    /**
     * 脱敏请求/响应体（JSON / XML / form-urlencoded / 自由文本自动分派）。
     *
     * @param body 原始体，允许 null
     * @return 脱敏后的体
     */
    public static String bodyForLog(String body) {
        return SensitiveDataSanitizer.sanitizeBody(body);
    }
}
