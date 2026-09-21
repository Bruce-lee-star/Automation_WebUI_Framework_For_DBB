package com.hsbc.cmb.hk.dbb.automation.framework.api.logging;

import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 脱敏打印流（评审08类-6 / P-1 / SEC-1）。
 *
 * <p>RestAssured {@code log().all()} 默认把 Authorization / Cookie / 密码 / token 直写控制台，
 * 且绕过 {@link SensitiveDataSanitizer} 出口脱敏链路。本类包装 {@link java.io.PrintStream}，
 * 在写出前统一经 {@link SensitiveDataSanitizer#sanitizeLogMessage(String)}（日志出口两级入口）；
 * 该链路<b>不经 logback</b>，故 {@code %msg} 出口规则对它无效 —— 必须在本类自兜底。</p>
 *
 * <p>用法（AbstractRestJob）：{@code RestAssured.config = RestAssured.config().logConfig(
 * new LogConfig(new SanitizingPrintStream(System.out), true));}</p>
 *
 * <p><b>覆盖范围（评审 F-09 修复，2026-09-21）</b>：原实现只重写了 {@code print(String)} /
 * {@code println(String)} / {@code println(Object)}，存在三类漏罩：</p>
 * <ol>
 *   <li>{@code printf/format} 仅脱敏 <b>format</b>、实参 {@code args} 原样透传 ——
 *       {@code printf("Authorization: %s", token)} 即明文出域；</li>
 *   <li>{@code print(Object)} / {@code print(char[])} 等文本重载未覆盖（JDK 中它们直接走
 *       {@code PrintStream} 的私有 {@code write(String)}，无法用单一漏斗拦截，只能逐个重写）；</li>
 *   <li>{@code append(CharSequence…)} 与 {@code write(byte[]…)} 未覆盖。</li>
 * </ol>
 * <p>现全部覆盖，且 {@code printf/format} 采用「先整体格式化、再整体脱敏」——避免按格式片段逐段
 * 脱敏使敏感串被切碎而漏罩。</p>
 *
 * <p>健壮性（SEC-PRINT 修复，2026-09-20）：脱敏异常（含格式化失败）时<b>不回退原文</b>，输出占位符
 * {@link #SUPPRESSED}，与 {@code SanitizingMessageConverter} 的 fail-closed 策略一致 ——
 * 宁可丢失该条 REST 流量日志细节，也不让明文凭据经 {@code log().all()} 出域。</p>
 */
public class SanitizingPrintStream extends java.io.PrintStream {

    /** 脱敏失败（含格式化失败）时的抑制占位符。 */
    static final String SUPPRESSED = "[SUPPRESSED]";

    public SanitizingPrintStream(OutputStream out) {
        super(out);
    }

    // ==================== print / println（可承载文本的重载） ====================
    // 注：PrintStream 的 write(String) 为 private，无法作为统一漏斗，故此处逐个重写文本重载；
    //     内部一律调用 super 的对应方法（其再走私有 write(String)），不会递归进本类。

    @Override
    public void print(String s) {
        super.print(sanitize(s));
    }

    @Override
    public void println(String s) {
        super.println(sanitize(s));
    }

    @Override
    public void print(Object x) {
        super.print(sanitize(String.valueOf(x)));
    }

    @Override
    public void println(Object x) {
        super.println(sanitize(String.valueOf(x)));
    }

    @Override
    public void print(char[] s) {
        super.print(sanitize(s == null ? "null" : new String(s)));
    }

    @Override
    public void println(char[] s) {
        super.println(sanitize(s == null ? "null" : new String(s)));
    }

    // ==================== printf / format（F-09 修复重点） ====================

    @Override
    public java.io.PrintStream printf(String format, Object... args) {
        writeFormatted(() -> String.format(format, args));
        return this;
    }

    @Override
    public java.io.PrintStream printf(Locale l, String format, Object... args) {
        writeFormatted(() -> String.format(l, format, args));
        return this;
    }

    @Override
    public java.io.PrintStream format(String format, Object... args) {
        writeFormatted(() -> String.format(format, args));
        return this;
    }

    @Override
    public java.io.PrintStream format(Locale l, String format, Object... args) {
        writeFormatted(() -> String.format(l, format, args));
        return this;
    }

    // ==================== 追加 / 字节写入 ====================

    @Override
    public java.io.PrintStream append(CharSequence csq) {
        super.append(sanitize(csq == null ? "null" : csq.toString()));
        return this;
    }

    @Override
    public java.io.PrintStream append(CharSequence csq, int start, int end) {
        String base = csq == null ? "null" : csq.toString();
        super.append(sanitize(base.substring(start, end)));
        return this;
    }

    @Override
    public void write(byte[] buf, int off, int len) {
        if (buf == null) {
            return;
        }
        byte[] safe = sanitize(new String(buf, off, len, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        super.write(safe, 0, safe.length);
    }

    @Override
    public void write(byte[] buf) throws IOException {
        if (buf == null) {
            return;
        }
        byte[] safe = sanitize(new String(buf, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        // 走 super 的 3 参重载，避免再次进入本类的 3 参重载造成重复脱敏
        super.write(safe, 0, safe.length);
    }

    /**
     * 写出「先整体格式化、再整体脱敏」的文本。
     *
     * <p>格式化与脱敏分离是刻意的：若按格式片段逐段脱敏，敏感串会被切碎而漏罩。</p>
     * <p>格式化本身失败（格式串与实参不匹配 / format 为 null）同样 fail-closed 输出
     * {@link #SUPPRESSED}，既不回退原文，也不因日志调用而中断业务流程。</p>
     *
     * @param formatter 惰性格式化（失败由本方法兜底）
     */
    private void writeFormatted(Supplier<String> formatter) {
        String formatted;
        try {
            formatted = formatter.get();
        } catch (Exception e) {
            super.print(SUPPRESSED);
            return;
        }
        super.print(sanitize(formatted));
    }

    /** 便捷构造（供测试/调用方）。 */
    public static SanitizingPrintStream to(OutputStream out) {
        return new SanitizingPrintStream(out);
    }

    /** 便捷：脱敏为字符串（供单测断言）。 */
    public static String sanitizeToString(String raw) {
        return sanitize(raw);
    }

    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        try {
            //  用「日志出口」两级入口（key[:=]value 逐词遮蔽 + 自由文本 token 兜底），而<b>不是</b>
            //  sanitizeFreeText —— 后者以「行内首个 :/= 之前」为 key，对 `form: password=x`
            //  （: 早于 =）这类 REST 日志常见形态会漏判（评审 F-09 复核新增发现）。
            return SensitiveDataSanitizer.sanitizeLogMessage(s);
        } catch (Exception e) {
            // SEC-PRINT（fail-closed）：脱敏失败即抑制，绝不回退明文（回退原文 = 明文凭据出域）
            return SUPPRESSED;
        }
    }

    /** 仅供单测：取内部缓冲（避免依赖具体 OutputStream 实现）。 */
    static ByteArrayOutputStream captureBuffer() {
        return new ByteArrayOutputStream();
    }

    static String decode(ByteArrayOutputStream bos) {
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
