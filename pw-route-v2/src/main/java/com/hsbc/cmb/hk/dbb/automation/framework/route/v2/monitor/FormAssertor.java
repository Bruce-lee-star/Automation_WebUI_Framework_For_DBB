package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 表单断言器 —— {@code application/x-www-form-urlencoded} 响应体的字段断言。
 *
 * <p>语义：解析 {@code k=v&k2=v2}（允许重复键，保留全部值），断言时字段值集合
 * 须包含期望值（精确匹配）。解析失败的段按原样保留（不抛）。
 *
 * <p>纯函数、无状态，可安全并发调用。
 */
public final class FormAssertor {

    private static final Logger LOGGER = LoggerFactory.getLogger(FormAssertor.class);

    private FormAssertor() {
    }

    /**
     * 对表单响应体执行字段断言。
     *
     * @return 失败明细列表；空列表 = 全部通过。明细格式：
     *         {@code form field '<key>' expected=<value> actual=<values>}
     */
    public static List<String> assertAll(Map<String, String> assertions, String body) {
        Map<String, List<String>> fields = parse(body, StandardCharsets.UTF_8);
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> e : assertions.entrySet()) {
            List<String> actuals = fields.get(e.getKey());
            if (actuals == null || !actuals.contains(e.getValue())) {
                failures.add("form field '" + e.getKey() + "' expected=" + e.getValue()
                        + " actual=" + (actuals == null ? "<missing>" : actuals));
            }
        }
        return failures;
    }

    /** 解析表单体为 键→值列表（重复键保留全部值；无 '=' 的段按空值解析）。 */
    static Map<String, List<String>> parse(String body, Charset charset) {
        Map<String, List<String>> result = new HashMap<>();
        if (body == null || body.isEmpty()) {
            return result;
        }
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                k = URLDecoder.decode(k, charset);
                v = URLDecoder.decode(v, charset);
            } catch (IllegalArgumentException e) {
                // 解码失败：保留原始分段（k/v 维持未解码值），不抛、不猜测
                LOGGER.trace("form field decode failed, keep raw: {}", e.toString());
            }
            result.computeIfAbsent(k, key -> new ArrayList<>()).add(v);
        }
        return result;
    }
}
