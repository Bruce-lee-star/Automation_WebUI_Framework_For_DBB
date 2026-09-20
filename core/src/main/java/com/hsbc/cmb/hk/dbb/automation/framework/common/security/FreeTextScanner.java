package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自由文本脱敏扫描器（SensitiveDataSanitizer 拆分专项 · 阶段 3 抽取）。
 *
 * <p>职责：处理<b>无结构/弱结构</b>文本中出域风险最高的三类形态 ——
 * ① 行内 {@code key[:=]value}（日志与异常栈 message 的常见形态）；
 * ② 认证凭据（{@code Bearer/Basic/Digest} 后的 token、独立 JWT、URL 内嵌凭据）；
 * ③ URLEncode 内层 JSON 凭据（云测平台 caps 场景）。
 *
 * <p><b>两级互补（不可合并为一级）</b>：
 * <ul>
 *   <li>{@link #sanitizeLine(String)} 只做 {@code key[:=]value} 遮蔽，<b>刻意不跑</b> Bearer/JWT 等自由文本正则——
 *       其 JWT 规则会把异常栈里的包名（如 {@code automation.framework.common}——首两段均 ≥8 字符）
 *       误判为 JWT 而误罩，既破坏栈可读性又无安全收益；</li>
 *   <li>{@link #sanitizeFreeText(String)} 以"行内首个 {@code :/=} 之前"为 key，对 {@code "Login failed: password=x"}
 *       这类 {@code : } 早于 {@code =} 的消息会漏判；</li>
 *   <li>{@link #sanitizeLogMessage(String)} 则<b>两级都跑</b>，专供 {@code %msg} 出口，消除二者盲区。</li>
 * </ul>
 *
 * <p>判定所需的敏感键/值谓词由 {@link SanitizerRules} 提供；本类不持有任何可变状态。
 */
final class FreeTextScanner {

    private FreeTextScanner() {
    }

    // ── 值级识别候选（仅作定位，最终由识别器校验位/格式把关，避免误报）──
    private static final Pattern FREE_TEXT_PAN = Pattern.compile("\\b\\d(?:[ \\-]?\\d){12,18}\\b");
    private static final Pattern FREE_TEXT_IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b");
    private static final Pattern FREE_TEXT_HKID = Pattern.compile("\\b[A-Z]{1,2}\\d{6}[0-9A]\\b");
    private static final Pattern FREE_TEXT_TRACK = Pattern.compile("(?i)%B[0-9]{1,19}\\^|;[0-9]{1,19}=");
    private static final Pattern FREE_TEXT_CHINA_ID = Pattern.compile("\\b\\d{17}[0-9Xx]\\b");
    private static final Pattern FREE_TEXT_CHINA_MOBILE = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    private static final Pattern FREE_TEXT_CHINA_PASSPORT = Pattern.compile("\\b[EGDSP]\\d{8}\\b");
    private static final Pattern FREE_TEXT_CHINA_HKMO = Pattern.compile("\\b[CHMW]\\d{8}\\b");
    private static final Pattern FREE_TEXT_CHINA_USCC = Pattern.compile("\\b[0-9A-HJ-NP-RT-UW-Y]{18}\\b");

    // 银行卡 CVV 无校验位，靠相邻标签（cvv/cvc/cid/security code）定位后遮蔽数字部分
    private static final Pattern FREE_TEXT_CVV =
            Pattern.compile("(?i)\\b(cv[cv2]?|cid|cvc2?|security[ _-]?code)\\s*[:=#]?\\s*(\\d{3,4})\\b");

    /** 修复 R2：Bearer/Basic/Digest 等认证方案后的凭证（保留方案名，遮蔽凭据）。 */
    private static final Pattern FREE_TEXT_AUTH_SCHEME =
            Pattern.compile("(?i)(\\b(?:Bearer|Basic|Digest|APIKey|Token)\\s+)([A-Za-z0-9._~+/-]+=*)");

    /** 修复 R2：独立 JWT（三段式 base64url，header.payload.signature）。 */
    private static final Pattern FREE_TEXT_JWT =
            Pattern.compile("(?i)([A-Za-z0-9_=-]{8,}\\.[A-Za-z0-9_=-]{8,}\\.)([A-Za-z0-9_=-]+)");

    /** 修复 R2：URL 中的 {@code ?token=xxx} 形态凭据。 */
    private static final Pattern FREE_TEXT_URL_CREDENTIAL =
            Pattern.compile("(?i)([?&](?:token|access_token|api_key|apikey|secret|password|key|auth)=)([^&\\s\"']+)");

    /**
     * 修复 S1：URLEncode 内层 JSON 凭据（云测平台 caps 场景）。
     * <p>BrowserStack 把 caps JSON 整体 URLEncode 后塞进 URL：
     * {@code wss://.../playwright?caps=%7B%22browserstack.accessKey%22%3A%22SECRET%22%7D}，
     * 此时 {@code :} 被编码为 {@code %3A}、{@code "} 为 {@code %22}，明文分隔符匹配不到。
     * <p>分组：1=key 前引号，2=key，3=key 后引号，4=分隔符(%3A/%3D)，5=值前引号，6=值。
     */
    private static final Pattern FREE_TEXT_URL_ENCODED_SECRET = Pattern.compile(
            "(?i)(%22|%27)?([\\w.\\-]*(?:key|secret|token|password|passwd|pwd|credential)[\\w.\\-]*)"
                    + "(%22|%27)?(%3A|%3D)(%22|%27)?([^&%\\s\"']*)");

    // ═══════════════════════════════════════════════════════════════
    // 公开入口
    // ═══════════════════════════════════════════════════════════════

    /**
     * 纯文本兜底遮蔽：逐行查找 {@code 敏感词<分隔符>值} 形态并遮蔽值部分。
     * <p>覆盖日志片段、非结构化响应等场景。宁可过度遮蔽，不可漏出。
     */
    static String sanitizeFreeText(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(maskFreeTextLine(lines[i]));
        }
        return out.toString();
    }

    /**
     * 单行脱敏（异常栈 message / caused-by message）—— C-2 / L-1 专用入口。
     *
     * <p>仅做 {@code key[:=]value} 遮蔽（form 行内 + 逐词扫描），覆盖 {@code "msg: password=x"}（{@code :} 早于 {@code =}）形态。
     * <b>刻意不跑</b> Bearer/JWT 等自由文本正则：其 JWT 规则会把异常栈里的包名误判为 JWT 而误罩。
     * 含嵌入换行时按行分别处理，避免跨行误罩。
     */
    static String sanitizeLine(String line) {
        if (line == null) {
            return null;
        }
        String[] lines = line.split("\n", -1);
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(maskSensitiveKeyValues(sanitizeFormLike(lines[i])));
        }
        return out.toString();
    }

    /**
     * 日志消息出口（{@code %msg}）专用入口 —— 修复 CORE-C1。
     * <p>{@link #sanitizeFreeText(String)} 与 {@link #sanitizeLine(String)} 盲区互补，故本入口<b>两级都跑</b>：
     * 先 {@code key[:=]value}（行内任意位置），再自由文本 token 兜底，保证 {@code %msg} 出口既不漏
     * {@code password=} / {@code token=}，也不漏 {@code Authorization: Bearer ...}。
     */
    static String sanitizeLogMessage(String text) {
        if (text == null) {
            return null;
        }
        return sanitizeFreeText(sanitizeLine(text));
    }

    // ═══════════════════════════════════════════════════════════════
    // 行内 key[:=]value 遮蔽
    // ═══════════════════════════════════════════════════════════════

    /**
     * 行内任意位置的敏感 {@code key[:=]value} 遮蔽（C-2 / L-1 关键补充）。
     * <p>按空白切词、逐词以<b>词内</b>首个 {@code :/=} 之前为 key，命中敏感清单即遮蔽其值，
     * 故 {@code password=...} 即使不在行首也能被命中。
     */
    private static String maskSensitiveKeyValues(String text) {
        if (text == null) {
            return text;
        }
        String[] tokens = text.split("(\\s+)");
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(maskKeyValueToken(tokens[i]));
        }
        return sb.toString();
    }

    /** 仅对 {@code key[:=]value} 形态且 key 命中敏感清单的词做值遮蔽（不跑 Bearer/JWT 正则，避免误罩包名）。 */
    private static String maskKeyValueToken(String token) {
        int sep = -1;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == ':' || c == '=') {
                sep = i;
                break;
            }
        }
        if (sep <= 0 || sep >= token.length() - 1) {
            return token;
        }
        String key = SanitizerRules.stripKeyDecoration(token.substring(0, sep).trim());
        if (SanitizerRules.INSTANCE.isBodyKey(key) || SanitizerRules.INSTANCE.isHeaderKey(key)) {
            return token.substring(0, sep + 1) + " " + SanitizerRules.MASK;
        }
        return token;
    }

    /**
     * 单行处理：找到 {@code 敏感词 : = 值} 结构后遮蔽值；无该结构（或 key 非敏感）时仍走 token 正则兜底
     * （修复 R2：行内可能含 Bearer/JWT/URL token）。
     */
    private static String maskFreeTextLine(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        int sep = -1;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ':' || c == '=') { sep = i; break; }
        }
        if (sep <= 0 || sep >= line.length() - 1) {
            return maskFreeTextTokens(line);
        }
        String key = SanitizerRules.stripKeyDecoration(line.substring(0, sep).trim());
        if (SanitizerRules.INSTANCE.isBodyKey(key)) {
            return line.substring(0, sep + 1) + " " + SanitizerRules.MASK;
        }
        return maskFreeTextTokens(line);
    }

    // ═══════════════════════════════════════════════════════════════
    // token / 凭据正则兜底
    // ═══════════════════════════════════════════════════════════════

    /** 修复 R2 / S1：覆盖自由文本中的 Bearer token、独立 JWT、URL 内嵌凭据、URLEncode 内层凭据、CVV、值级识别。 */
    private static String maskFreeTextTokens(String text) {
        if (text == null) {
            return null;
        }
        // Bearer / Basic / Digest 等认证方案后的凭证（保留方案名）
        text = FREE_TEXT_AUTH_SCHEME.matcher(text)
                .replaceAll(m -> m.group(1) + " " + SanitizerRules.MASK);
        // 独立 JWT（三段式 base64url）。原写法 replaceAll("$1"+MASK+"$3") 引用不存在的 $3，
        // 命中即抛 IndexOutOfBoundsException（与"脱敏不得引入新故障"相悖）；现仅保留前缀组 + 掩码。
        text = FREE_TEXT_JWT.matcher(text).replaceAll("$1" + SanitizerRules.MASK);
        // URL 中 ?token=xxx 形态
        text = FREE_TEXT_URL_CREDENTIAL.matcher(text).replaceAll("$1" + SanitizerRules.MASK);
        // 修复 S1：URLEncode 内层 JSON 凭据（云测平台 caps 里的 accessKey 等）
        text = maskUrlEncodedSecrets(text);
        // CVV/CVC 无校验位：仅遮蔽紧跟标签的 3~4 位数字（保留标签便于审计）
        text = FREE_TEXT_CVV.matcher(text)
                .replaceAll(mr -> Matcher.quoteReplacement(mr.group(1)) + " " + SanitizerRules.MASK);
        text = maskValueRecognizersInFreeText(text);
        return text;
    }

    /**
     * 修复 S1：逐匹配遮蔽 URLEncode 后的 {@code key%3Avalue} / {@code %22key%22%3A%22value%22} 形态。
     * <p>仅当 key 解码后命中敏感词表才遮蔽，避免误伤普通 URL 参数。
     */
    private static String maskUrlEncodedSecrets(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher m = FREE_TEXT_URL_ENCODED_SECRET.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = SanitizerRules.urlDecodeQuiet(m.group(2));
            String replacement;
            if (SanitizerRules.INSTANCE.isBodyKey(key)) {
                replacement = SanitizerRules.nullToEmpty(m.group(1)) + m.group(2) + SanitizerRules.nullToEmpty(m.group(3))
                        + m.group(4) + SanitizerRules.nullToEmpty(m.group(5)) + SanitizerRules.MASK;
            } else {
                replacement = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 自由文本中按值级识别器遮蔽候选串（PAN/IBAN/HKID/轨道数据/国内身份证/手机号/护照/港澳通行证/统一社会信用代码）。 */
    private static String maskValueRecognizersInFreeText(String text) {
        if (text == null) {
            return text;
        }
        Pattern[] patterns = {FREE_TEXT_PAN, FREE_TEXT_IBAN, FREE_TEXT_HKID, FREE_TEXT_TRACK,
                FREE_TEXT_CHINA_ID, FREE_TEXT_CHINA_MOBILE,
                FREE_TEXT_CHINA_PASSPORT, FREE_TEXT_CHINA_HKMO, FREE_TEXT_CHINA_USCC};
        for (Pattern p : patterns) {
            Matcher m = p.matcher(text);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String tok = m.group();
                String replacement = SanitizerRules.INSTANCE.isValueSensitive(tok) ? SanitizerRules.MASK : m.group(0);
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            text = sb.toString();
        }
        return text;
    }

    /**
     * form 形态的 {@code key=value} 遮蔽（仅遮蔽命中敏感键的值）。
     *
     * <p>供 {@link #sanitizeLine(String)} 复用：异常栈 message 常含 {@code password=xxx} 片段，
     * 但整体并非合法 form 体，故此处仅按 form 的键值规则扫描、<b>不做格式判定</b>。
     * <p>实现唯一：直接复用 {@link BodySanitizers#sanitizeForm(String)}，避免同一键值遮蔽规则两处实现导致口径漂移。
     */
    private static String sanitizeFormLike(String text) {
        return BodySanitizers.sanitizeForm(text);
    }
}
