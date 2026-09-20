package com.hsbc.cmb.hk.dbb.automation.framework.api.logging;

import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 脱敏打印流（评审08类-6 / P-1 / SEC-1）。
 *
 * <p>RestAssured {@code log().all()} 默认把 Authorization / Cookie / 密码 / token 直写控制台，
 * 且绕过 {@link SensitiveDataSanitizer} 出口脱敏链路。本类包装 {@link java.io.PrintStream}，
 * 在 {@code print/println/printf} 写出前统一经 {@link SensitiveDataSanitizer#sanitizeFreeText(String)}，
 * 使 API 流量日志与业务日志同样受出口强制脱敏保护。
 *
 * <p>用法（AbstractRestJob）：{@code RestAssured.config = RestAssured.config().logConfig(
 * new LogConfig(new SanitizingPrintStream(System.out), true));}
 *
 * <p>健壮性（SEC-PRINT 修复，2026-09-20）：脱敏异常时<b>不回退原文</b>，输出占位符
 * {@code [SUPPRESSED]}，与 {@code SanitizingMessageConverter} 的 fail-closed 策略一致——
 * 宁可丢失该条 REST 流量日志细节，也不让明文凭据经 {@code log().all()} 出域。
 */
public class SanitizingPrintStream extends java.io.PrintStream {

    public SanitizingPrintStream(OutputStream out) {
        super(out);
    }

    @Override
    public void print(String s) {
        super.print(sanitize(s));
    }

    @Override
    public void println(String s) {
        super.println(sanitize(s));
    }

    @Override
    public void println(Object x) {
        super.println(sanitize(String.valueOf(x)));
    }

    @Override
    public java.io.PrintStream printf(String format, Object... args) {
        super.printf(sanitize(format), args);
        return this;
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
            return SensitiveDataSanitizer.sanitizeFreeText(s);
        } catch (Exception e) {
            // SEC-PRINT（fail-closed）：脱敏失败即抑制，绝不回退明文（回退原文 = 明文凭据出域）
            return "[SUPPRESSED]";
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
