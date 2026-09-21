package com.hsbc.cmb.hk.dbb.automation.framework.api.logging;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SanitizingPrintStream} 出口脱敏覆盖测试（固化评审 F-09 修复）。
 *
 * <p><b>修复前的破口</b>：只重写了 {@code print(String)} / {@code println(String)} / {@code println(Object)}，
 * 而 {@code printf/format} 仅脱敏 <b>format</b>、实参 {@code args} <b>原样透传</b> ——
 * {@code printf("Authorization: %s", token)} 即明文写出（该链路不经 logback，{@code %msg} 出口规则帮不上）。</p>
 *
 * <p>现覆盖：文本漏斗 {@code write(String)}（{@code print/println} 家族最终都经它）、
 * {@code printf/format}（先整体格式化再整体脱敏）、{@code append}、字节写入；且格式化/脱敏失败均 fail-closed。</p>
 */
public class SanitizingPrintStreamTest {

    private static final String SECRET = "SuperSecret123";
    private static final String BEARER = "Bearer abcDEF123456ghi";

    private static String capture(Consumer<SanitizingPrintStream> action) {
        ByteArrayOutputStream bos = SanitizingPrintStream.captureBuffer();
        SanitizingPrintStream ps = SanitizingPrintStream.to(bos);
        action.accept(ps);
        ps.flush();
        return SanitizingPrintStream.decode(bos);
    }

    @Test
    public void printfWithSecretArgumentIsMasked() {
        String out = capture(ps -> ps.printf("form: password=%s", SECRET));
        assertFalse(out.contains(SECRET), "printf 的实参也必须脱敏（原实现只脱敏 format）：" + out);
    }

    @Test
    public void formatWithBearerArgumentIsMasked() {
        String out = capture(ps -> ps.format("Authorization: %s", BEARER));
        assertFalse(out.contains("abcDEF123456ghi"), "format 的实参也必须脱敏：" + out);
    }

    @Test
    public void printAndPrintlnStillMaskedViaWriteFunnel() {
        String printed = capture(ps -> ps.print("password=" + SECRET));
        assertFalse(printed.contains(SECRET), "print 必须仍被脱敏：" + printed);

        String printedObject = capture(ps -> ps.println((Object) ("password=" + SECRET)));
        assertFalse(printedObject.contains(SECRET), "println(Object) 必须仍被脱敏：" + printedObject);
    }

    @Test
    public void printObjectAndAppendAndByteWriteAreMasked() {
        String viaPrintObject = capture(ps -> ps.print((Object) ("password=" + SECRET)));
        assertFalse(viaPrintObject.contains(SECRET), "print(Object) 必须脱敏（JDK 走私有 write(String)，无法用漏斗拦截）：" + viaPrintObject);

        String viaAppend = capture(ps -> ps.append("password=" + SECRET));
        assertFalse(viaAppend.contains(SECRET), "append(CharSequence) 必须脱敏：" + viaAppend);

        byte[] raw = ("password=" + SECRET).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String viaBytes = capture(ps -> ps.write(raw, 0, raw.length));
        assertFalse(viaBytes.contains(SECRET), "write(byte[],int,int) 必须脱敏：" + viaBytes);
    }

    @Test
    public void formatFailureFailsClosedInsteadOfLeaking() {
        String out = capture(ps -> ps.printf("%d", "not-a-number"));
        assertTrue(out.contains(SanitizingPrintStream.SUPPRESSED),
                "格式化失败必须 fail-closed 输出抑制占位符（绝不回退原文）：" + out);
    }

    @Test
    public void plainTextPassesThroughUnchanged() {
        String out = capture(ps -> ps.print("user logged in successfully"));
        assertTrue(out.contains("user logged in successfully"), "无敏感内容必须原样通过：" + out);
    }
}
