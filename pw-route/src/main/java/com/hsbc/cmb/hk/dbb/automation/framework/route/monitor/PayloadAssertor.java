package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.util.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Payload 断言分派器 —— 按响应 Content-Type 路由 body 断言（IO 线程执行）。
 *
 * <p>内容类型感知（不把 JSON 当默认）：
 * <ul>
 *   <li>{@code application/json} 家族 → {@link JsonPathAssertor}；</li>
 *   <li><b>content-type 缺失</b> + JSONPath 断言 → 仍执行 {@link JsonPathAssertor}（见下）；</li>
 *   <li>{@code application/x-www-form-urlencoded} → {@link FormAssertor}；</li>
 *   <li>{@code text/*} / XML 等文本类型 → 包含匹配 / 正则匹配；</li>
 *   <li>明确声明的非 JSON / 二进制（image、octet-stream…）→ 明确的 {@code not applicable}
 *       失败明细，绝不猜测解析，绝不报误导性的 JSON 解析错误。</li>
 * </ul>
 *
 * <p><b>为什么 content-type 缺失时 JSONPath 断言仍然执行（2026-09-28 对齐老版语义）</b>：
 * 老版 {@code MonitorHandler} 的 JSONPath 断言是 {@code JsonPath.read(body, path)}，
 * <b>从不看 content-type</b>；而 {@code expectJsonPath(...)} 本身就是「我预期这是 JSON」的
 * <b>显式声明</b>，故缺失该头时不应拒绝（实测 DBB 端点 {@code leftmenu/permissionLeftMenuConfig}
 * 返回 200 却不带 Content-Type，收紧后误报 {@code jsonpath assertions not applicable}）。
 * 注意这不是"猜测"：body 若不是 JSON，{@link JsonPathAssertor} 会给出明确的解析失败明细；
 * 而 content-type <b>明确</b>声明为非 JSON（含二进制）时依旧拒绝，避免对二进制做误导性解析。</p>
 *
 * <p>纯函数、无状态、无 IO —— 可安全并发调用。
 */
public final class PayloadAssertor {

    private PayloadAssertor() {
    }

    /**
     * 对响应体执行规则配置的全部 body 断言。
     *
     * @param spec            规则快照（含 jsonPath / bodyContains / bodyRegex / formField 断言）
     * @param body            已按 charset 解码的响应体文本
     * @param contentTypeHeader 响应 Content-Type 头（决定断言路由）
     * @return 失败明细列表；空列表 = 全部通过
     */
    public static List<String> assertAll(ApiSpec spec, String body, String contentTypeHeader) {
        MediaType mediaType = MediaType.parse(contentTypeHeader);
        List<String> failures = new ArrayList<>();

        if (!spec.jsonPathAssertions().isEmpty()) {
            // content-type 缺失（raw==null）时仍执行：expectJsonPath 已是「预期 JSON」的显式声明，
            // 对齐老版 MonitorHandler（直接 JsonPath.read(body)）；body 非 JSON 由断言器给出明确失败明细。
            // content-type 明确为非 JSON / 二进制 → 保持拒绝（见类注释）。
            if (mediaType.isJson() || mediaType.raw() == null) {
                failures.addAll(JsonPathAssertor.assertAll(spec.jsonPathAssertions(), body));
            } else {
                failures.add("jsonpath assertions not applicable: body content-type is "
                        + describe(mediaType));
            }
        }

        if (!spec.formFieldAssertions().isEmpty()) {
            if (mediaType.isFormUrlEncoded()) {
                failures.addAll(FormAssertor.assertAll(spec.formFieldAssertions(), body));
            } else {
                failures.add("formField assertions not applicable: body content-type is "
                        + describe(mediaType));
            }
        }

        if (spec.expectBodyContains() != null) {
            if (mediaType.isText()) {
                if (!body.contains(spec.expectBodyContains())) {
                    failures.add("body does not contain '" + spec.expectBodyContains() + "'");
                }
            } else {
                failures.add("bodyContains not applicable: body content-type is "
                        + describe(mediaType));
            }
        }

        if (spec.expectBodyRegex() != null) {
            if (mediaType.isText()) {
                Pattern pattern = Pattern.compile(spec.expectBodyRegex());
                if (!pattern.matcher(body).find()) {
                    failures.add("body does not match regex '" + spec.expectBodyRegex() + "'");
                }
            } else {
                failures.add("bodyRegex not applicable: body content-type is "
                        + describe(mediaType));
            }
        }

        return failures;
    }

    private static String describe(MediaType mediaType) {
        String raw = mediaType.raw();
        return raw == null ? "<missing content-type>" : raw;
    }
}
