package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 *  P0 合规：敏感数据脱敏 —— 金融级数据安全要求。<b>全框架脱敏的统一入口（门面）。</b>
 *
 * <p><b>设计原则：脱敏是数据出域的强制收口，不是可选装饰。</b>
 * 任何把请求/响应数据写出进程边界的 sink（数据库、本地文件、Serenity 报告、
 * 失败报告、日志）都<b>必须</b>先经本类处理。
 *
 * <h3>本类职责</h3>
 * <p>仅保留<b>对外的稳定契约</b>（公开 API、URL 脱敏、Header 脱敏与委托分发）；
 * 实现已按关注点拆分为三个协作类（{@code SensitiveDataSanitizer} 拆分专项，C-7 / M-3 遗留项治理）：
 * <ul>
 *   <li>{@link SanitizerRules}：<b>规则注册表</b> —— 敏感头/体/query 键清单（不可变快照 + {@code volatile} 原子发布）、
 *       附加键、值级识别器与豁免、键规范化与掩码等共享原语；</li>
 *   <li>{@link BodySanitizers}：<b>体脱敏策略链</b> —— JSON 树递归 / XML 双通道 / form-urlencoded，
 *       由 {@code sanitizeBody} 按固定顺序派发，末位落到纯文本兜底；</li>
 *   <li>{@link FreeTextScanner}：<b>自由文本与凭据扫描</b> —— 行内 {@code key[:=]value}、
 *       Bearer/JWT/URL 内嵌凭据、URLEncode 内层凭据、值级识别候选。</li>
 * </ul>
 * 拆分为纯重构：判定口径、链路顺序、懒加载时机与掩码形态<b>逐字保持</b>，
 * 由 {@code SensitiveDataSanitizerBehaviorTest}（行为基线/等价网）钉住。
 *
 * <h3>历史修复的四个绕过缺口（现状由上述协作类承载）</h3>
 * <ol>
 *   <li><b>非 JSON 不脱敏</b>：原实现仅当 body 以 {@code {} 或 [} 开头才处理，导致 XML（SOAP/ISO20022）、
 *       {@code application/x-www-form-urlencoded} 登录表单、纯文本一律明文输出。现按格式分派到独立处理链。</li>
 *   <li><b>不递归嵌套结构</b>：原正则只匹配同一层，真实响应 {@code {"data":{"user":{"token":"..."}}}} 全部漏网。
 *       现改用 Jackson 树遍历，深度不限。</li>
 *   <li><b>保留前缀明文</b>：原实现保留前 6 字符，对 6 位 PIN / 短验证码等于完整泄露。现全量遮蔽。</li>
 *   <li><b>字段名匹配失效</b>：原清单写 {@code accesstoken} 匹配不上 {@code access_token}。现对 key 规范化
 *       （剥离 {@code _ - 空格 .} 后小写）再比对，一次覆盖 camelCase / snake_case / kebab-case / PascalCase。</li>
 * </ol>
 *
 * <h3>值级识别（T4-2）与配置外置</h3>
 * <p>除字段名白名单外，按<b>值内容</b>识别敏感数据（银行卡号(PAN/Luhn) / 银联卡 / IBAN / 香港身份证(HKID) /
 * 中国大陆身份证号(GB 11643 校验位) / 手机号 / 护照号 / 港澳通行证·回乡证 / 统一社会信用代码(GB 32100 mod-31) /
 * 信用卡轨道数据），见 {@link SensitiveValueRecognizer} 与 {@link BuiltinValueRecognizers}。
 * 银行卡 CVV 无校验位，按<b>字段名</b>识别，避免裸 3~4 位数字值级误报。识别器经 SPI 可扩展；
 * 全部脱敏为定长掩码，不泄露原值亦不泄露长度。</p>
 * <p><b>配置项</b>（系统属性优先，回退 Serenity 配置源 {@code serenity.conf}）：</p>
 * <ul>
 *   <li>{@code sensitive.data.rules.profile}：市场 profile 名（默认 {@code defaults}）。</li>
 *   <li>{@code sensitive.data.profile.<P>.{header,body,query}.keys}：某 profile 下附加敏感键
 *       （CSV，<b>叠加</b>在内置清单之上，不替换）。</li>
 *   <li>{@code sensitive.data.value.recognizers}：启用的识别器名（CSV；留空 = 全部启用）。</li>
 *   <li>{@code sensitive.data.value.excludes}：值级识别豁免名单（CSV），命中则不脱敏，抑制误报。</li>
 * </ul>
 */
public final class SensitiveDataSanitizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SensitiveDataSanitizer.class);

    /** 修复：URL authority 中的 userinfo（{@code user:pass@} 或 {@code user@}），用于剥离内嵌凭据。 */
    private static final Pattern URL_USERINFO =
            Pattern.compile("(?i)(://)(?:[^/@]+:[^/@]+|[^/@]+)@");

    private SensitiveDataSanitizer() {
    }

    // ═══════════════════════════════════════════════════════════════
    // 规则注册 / 重载（委托 SanitizerRules）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 强制从配置重新加载附加敏感键（清掉旧值后重读）。
     * 供测试与运行时热更新使用；与 {@link #registerExtraSensitiveKeys} 互不覆盖（先清后读）。
     */
    public static void reloadExtraKeysFromConfig() {
        SanitizerRules.INSTANCE.reloadExtraKeysFromConfig();
    }

    /**
     * 程序化注入附加敏感键（叠加）。供 web 层或测试在不依赖系统属性的场景下扩展脱敏范围。
     *
     * @param headerKeys 附加敏感头名（逗号分隔，可空）
     * @param bodyKeys   附加敏感体字段名（逗号分隔，可空）
     * @param queryKeys  附加敏感 URL query 参数名（逗号分隔，可空）
     */
    public static void registerExtraSensitiveKeys(String headerKeys, String bodyKeys, String queryKeys) {
        SanitizerRules.INSTANCE.registerExtraSensitiveKeys(headerKeys, bodyKeys, queryKeys);
    }

    /**
     * 按<b>值内容</b>判定是否敏感（与字段名无关）。
     *
     * @param value 原始值（容忍 null/空/含空白）
     * @return true 表示该值应被脱敏
     */
    public static boolean looksSensitiveByValue(String value) {
        return SanitizerRules.INSTANCE.isValueSensitive(value);
    }

    /** 运行时注册自定义值级识别器（与 SPI 机制互补）。 */
    public static void registerValueRecognizer(SensitiveValueRecognizer recognizer) {
        SanitizerRules.INSTANCE.registerValueRecognizer(recognizer);
    }

    /** 强制重载全部规则（测试 / 运行时热更新；与 {@link #reloadExtraKeysFromConfig()} 互补）。 */
    public static void reloadRules() {
        SanitizerRules.INSTANCE.reloadRules();
    }

    /**
     * 判断 body 字段是否敏感（规范化匹配）。
     * <p>由 {@code ApiMonitoringRecord} 等调用方复用，保证全框架判定一致。
     */
    public static boolean isSensitiveBodyKey(String key) {
        return SanitizerRules.INSTANCE.isBodyKey(key);
    }

    /** 供测试与诊断：返回统一掩码串。 */
    public static String maskToken() {
        return SanitizerRules.MASK;
    }

    // ═══════════════════════════════════════════════════════════════
    // 公开脱敏入口
    // ═══════════════════════════════════════════════════════════════

    /**
     * 返回脱敏后的 Header 副本；入参为 null 时返回 null。
     * <p>命中敏感 key（或值本身按内容敏感）时，值<b>整体</b>替换为掩码（不再保留前缀明文）。
     */
    public static Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        Map<String, String> sanitized = new HashMap<>(headers.size());
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if ((SanitizerRules.INSTANCE.isHeaderKey(key) || SanitizerRules.INSTANCE.isValueSensitive(value))
                    && value != null && !value.isEmpty()) {
                sanitized.put(key, SanitizerRules.MASK);
            } else {
                sanitized.put(key, value);
            }
        }
        return sanitized;
    }

    /**
     * 脱敏请求/响应体中的敏感字段（委托 {@link BodySanitizers} 的<b>策略链</b>）。
     *
     * <p>按内容形态自动分派，<b>不再因格式不识别而整体放行</b>：
     * JSON 对象/数组 → 树递归；XML/SOAP → 元素文本 + 属性值；form-urlencoded → 键值对；
     * 其它（纯文本/二进制文本）→ 关键字行级兜底遮蔽。
     *
     * @param body 原始体；null/空原样返回
     * @return 脱敏后的体
     */
    public static String sanitizeBody(String body) {
        return BodySanitizers.sanitizeBody(body);
    }

    /**
     * 脱敏 URL 中的敏感 query 参数（自包含实现，不依赖 web 层）。
     *
     * <p>先剥离 authority 中的 userinfo（{@code user:pass@}），覆盖 {@code http(s)://user:pass@host}
     * 与 {@code jdbc:mysql://user:pass@host} 这类内嵌凭据；再命中敏感 query 参数名时移除整个 query
     * （仅保留 path）；解析失败则降级到自由文本兜底，<b>绝不出域原始 URL</b>（可能含明文 token/key）。
     *
     * @param url 原始 URL
     * @return 脱敏后的 URL；null 入参返回 null
     */
    public static String sanitizeUrl(String url) {
        if (url == null) {
            return null;
        }
        // 先剥离 authority 中的 userinfo，避免明文账号密码经日志/报告出域
        String sanitized = stripUrlUserinfo(url);
        try {
            URI uri = new URI(sanitized);
            String rawQuery = uri.getRawQuery();
            if (rawQuery == null || rawQuery.isEmpty()) {
                return sanitized; // 无 query，返回已去 userinfo 的 URL
            }
            boolean hasSensitive = false;
            for (String pair : rawQuery.split("&")) {
                String[] kv = pair.split("=", 2);
                String key = kv[0].toLowerCase();
                try {
                    key = URLDecoder.decode(key, StandardCharsets.UTF_8.name()).toLowerCase();
                } catch (Exception e) {
                    // 解码失败则保留原始 key（不解码）继续敏感键判定，不中断脱敏流程（D7-3：不得静默）
                    LOGGER.debug("[SensitiveDataSanitizer] query key decode failed, keep raw key: {}", e.toString());
                }
                String val = kv.length > 1 ? kv[1] : "";
                if (SanitizerRules.INSTANCE.isQueryKey(key) || SanitizerRules.INSTANCE.isValueSensitive(val)) {
                    hasSensitive = true;
                    break;
                }
            }
            if (hasSensitive) {
                return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, uri.getFragment()).toString();
            }
            return sanitized;
        } catch (Exception e) {
            // 解析失败时绝不出域原始 URL（可能含明文 token/key），降级到自由文本脱敏
            String stripped = sanitized.replaceAll("[?#].*$", "");
            return sanitizeFreeText(stripped);
        }
    }

    /** 剥离 URL 中的 userinfo（{@code user:pass@} 或 {@code user@}），仅移除凭据、保留其余结构。 */
    private static String stripUrlUserinfo(String url) {
        return URL_USERINFO.matcher(url).replaceAll("$1***@");
    }

    /**
     * 纯文本兜底遮蔽：逐行查找 {@code 敏感词<分隔符>值} 形态并遮蔽值部分（委托 {@link FreeTextScanner}）。
     * <p>覆盖日志片段、非结构化响应等场景。宁可过度遮蔽，不可漏出。
     */
    public static String sanitizeFreeText(String text) {
        return FreeTextScanner.sanitizeFreeText(text);
    }

    /**
     * 脱敏单行（异常栈 message / caused-by message）—— C-2 / L-1 专用入口（委托 {@link FreeTextScanner}）。
     * <p>仅做 {@code key[:=]value} 遮蔽，<b>刻意不跑</b> Bearer/JWT 正则（其 JWT 规则会把异常栈包名误判为 JWT 而误罩）。
     */
    public static String sanitizeLine(String line) {
        return FreeTextScanner.sanitizeLine(line);
    }

    /**
     * 日志消息出口（{@code %msg}）专用脱敏入口 —— 修复 CORE-C1（委托 {@link FreeTextScanner}）。
     * <p>两级都跑：先 {@code key[:=]value}（行内任意位置），再自由文本 token 兜底，
     * 保证既不漏 {@code password=} / {@code token=}，也不漏 {@code Authorization: Bearer ...}。
     */
    public static String sanitizeLogMessage(String text) {
        return FreeTextScanner.sanitizeLogMessage(text);
    }
}
