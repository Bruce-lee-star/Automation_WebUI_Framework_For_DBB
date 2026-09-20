package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code %msg} 出口脱敏的「不破坏日志本身」回归测试（固化评审 F-10 修复）。
 *
 * <p><b>修复前的两处自伤</b>：</p>
 * <ol>
 *   <li>{@code maskSensitiveKeyValues} 用 {@code split("(\\s+)")} 切词后<b>统一以单个空格重组</b> ——
 *       多行日志的缩进、tab、连续空白被整体抹平，异常栈可读性受损（安全收益为 0）；</li>
 *   <li>自由文本 JWT 正则只要求「前两段各 ≥8 字符」，于是异常栈里的包名
 *       （如 {@code automation.framework.common}）被误判为 JWT 而遮蔽 —— 本类族 Javadoc 早已声明
 *       {@code sanitizeLine} 刻意不跑该规则以规避此风险，但 {@code sanitizeLogMessage}（{@code %msg} 出口）
 *       两级都跑，又把风险引了回来。</li>
 * </ol>
 *
 * <p>修复后：只做<b>原位</b>替换（空白分隔符逐字保留），且 JWT 规则要求 header 以 {@code eyJ} 起头。</p>
 */
public class FreeTextScannerLogHygieneTest {

    private static final String SECRET = "s3cr3t-do-not-leak";

    @Test
    public void indentationAndTabsSurviveSanitization() {
        String log = "Caused by: Boom\n\tat app.Foo.bar(Foo.java:1)\n    password=" + SECRET;
        String out = SensitiveDataSanitizer.sanitizeLogMessage(log);

        assertFalse(out.contains(SECRET), "敏感值必须被遮蔽");
        assertTrue(out.contains("\n\tat app.Foo.bar"), "tab 缩进必须原样保留（不得被抹平为单空格）：" + out);
        assertTrue(out.contains("\n    password="), "行首空格缩进必须原样保留：" + out);
    }

    @Test
    public void fullyQualifiedClassNamesAreNotMaskedAsJwt() {
        String log = "Caused by: java.lang.IllegalStateException at automation.framework.common.security.FreeTextScanner";
        String out = SensitiveDataSanitizer.sanitizeLogMessage(log);

        assertTrue(out.contains("automation.framework.common"), "包名（两段各 ≥8 字符）不得被误判为 JWT 遮蔽：" + out);
        assertTrue(out.contains("java.lang.IllegalStateException"), "异常类名不得被遮蔽：" + out);
    }

    @Test
    public void realJwtSignatureIsStillMasked() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String out = SensitiveDataSanitizer.sanitizeLogMessage("token=" + jwt);

        assertFalse(out.contains("SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"),
                "真 JWT（header 以 eyJ 起头）的签名段仍必须遮蔽：" + out);
    }

    @Test
    public void normalMessageStaysByteIdentical() {
        String plain = "user logged in successfully";
        assertTrue(plain.equals(SensitiveDataSanitizer.sanitizeLogMessage(plain)),
                "无敏感内容的消息必须原样通过（脱敏不得引入任何改写）");
    }
}
