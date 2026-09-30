package com.hsbc.cmb.hk.dbb.automation.framework.route.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 轻量 MediaType 解析 —— 从 Content-Type 头提取主类型 / 子类型 / charset。
 *
 * <p>用途：内容类型感知的 Payload 处理（MONITOR 断言分派；后续 MOCK 字段替换、
 * MODIFY body 编解码共用）。不依赖外部库，只做判定所需的最小子集。
 *
 * <p>判定语义：
 * <ul>
 *   <li>{@link #isJson()}：子类型含 {@code json}（application/json、application/hal+json、text/json…）；</li>
 *   <li>{@link #isFormUrlEncoded()}：{@code application/x-www-form-urlencoded}；</li>
 *   <li>{@link #isText()}：{@code text/*}、XML / JSON 家族、表单 —— 可按文本解码；</li>
 *   <li>{@link #charset()}：Content-Type 的 charset 参数，缺失默认 UTF-8。</li>
 * </ul>
 *
 * <p>不可变，可安全并发共享。
 */
public final class MediaType {

    private final String raw;
    private final String type;
    private final String subtype;
    private final Charset charset;

    private MediaType(String raw, String type, String subtype, Charset charset) {
        this.raw = raw;
        this.type = type;
        this.subtype = subtype;
        this.charset = charset;
    }

    /** 解析 Content-Type 头；null / 空 → 视为未知类型（text 判定为 false，不猜测）。 */
    public static MediaType parse(String contentType) {
        if (contentType == null || contentType.trim().isEmpty()) {
            return new MediaType(null, null, null, StandardCharsets.UTF_8);
        }
        String head = contentType.trim();
        // 去掉参数段，先取主类型/子类型
        String mainPart = head;
        int semi = head.indexOf(';');
        if (semi >= 0) {
            mainPart = head.substring(0, semi).trim();
        }
        String typePart = "application";
        String subtypePart = mainPart;
        int slash = mainPart.indexOf('/');
        if (slash > 0) {
            typePart = mainPart.substring(0, slash).trim();
            subtypePart = mainPart.substring(slash + 1).trim();
        }
        Charset charset = StandardCharsets.UTF_8;
        if (semi >= 0) {
            charset = parseCharset(head.substring(semi + 1));
        }
        return new MediaType(head, typePart.toLowerCase(Locale.ROOT), subtypePart.toLowerCase(Locale.ROOT), charset);
    }

    private static Charset parseCharset(String params) {
        for (String param : params.split(";")) {
            String p = param.trim();
            if (!p.startsWith("charset=")) {
                continue;
            }
            String name = p.substring("charset=".length()).trim();
            if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) {
                name = name.substring(1, name.length() - 1);
            }
            try {
                return Charset.forName(name);
            } catch (Exception e) {
                return StandardCharsets.UTF_8; // 无法识别的 charset 名 → 默认 UTF-8，不抛
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** 原始 Content-Type 头（可能为 null）。 */
    public String raw() {
        return raw;
    }

    /** 主类型（小写；未知为 null）。 */
    public String type() {
        return type;
    }

    /** 子类型（小写；未知为 null）。 */
    public String subtype() {
        return subtype;
    }

    public boolean isJson() {
        return subtype != null && subtype.contains("json");
    }

    public boolean isFormUrlEncoded() {
        return "application".equals(type) && "x-www-form-urlencoded".equals(subtype);
    }

    /** 可按文本解码的类型：text/*、JSON/XML 家族、表单。未知/二进制（image、octet-stream…）返回 false。 */
    public boolean isText() {
        if (subtype == null) {
            return false;
        }
        if ("text".equals(type)) {
            return true;
        }
        return subtype.contains("json") || subtype.contains("xml") || isFormUrlEncoded();
    }

    /** 解码 charset（缺失默认 UTF-8）。 */
    public Charset charset() {
        return charset;
    }
}
