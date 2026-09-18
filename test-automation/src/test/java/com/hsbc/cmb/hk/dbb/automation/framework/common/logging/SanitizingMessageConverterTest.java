package com.hsbc.cmb.hk.dbb.automation.framework.common.logging;

import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D2-4 日志出口强制脱敏契约测试。
 *
 * <p>核心保证：
 * <ul>
 *   <li>消息中的敏感键值对在<b>写出前</b>被遮蔽，调用方<b>无法绕过</b>；</li>
 *   <li>普通消息不受影响（脱敏不能把日志变得不可读，否则失去排障价值）；</li>
 *   <li>运维级总开关可整体关闭（默认开启）；</li>
 *   <li><b>端到端</b>：真的打印一条含密钥的日志，落盘文件中不得出现明文。</li>
 * </ul>
 */
public class SanitizingMessageConverterTest {

    private static final String SECRET = "hunter2-super-secret";

    @AfterEach
    public void tearDown() {
        System.clearProperty(SanitizingMessageConverter.ENABLED_KEY);
    }

    /** 敏感键值对被遮蔽。 */
    @Test
    public void sanitizesSecretKeyValueInMessage() {
        //  注意：sanitizeFreeText 以"行内第一个 : 或 ="之前作为 key，
        //  注：%msg 出口现走 sanitizeLogMessage（行内 key[:=]value + 自由文本两级），行首/行内敏感键均被遮蔽
        String out = convert("api call failed\npassword=" + SECRET + "\nretrying");
        assertFalse(out.contains(SECRET), "日志消息不得残留明文密钥");
        assertTrue(out.contains(SensitiveDataSanitizer.maskToken()), "应出现掩码");
    }

    /** CORE-C1 回归：{@code : } 早于 {@code =} 的行内凭据（旧实现漏判）也必须被遮蔽。 */
    @Test
    public void sanitizesInlineSecretAfterColon() {
        String out = convert("Login failed: password=" + SECRET);
        assertFalse(out.contains(SECRET), "行内 key=value（: 早于 =）不得残留明文");
        assertTrue(out.contains(SensitiveDataSanitizer.maskToken()), "应出现掩码");
    }

    /**
     * CORE-C1 回归：改造后 {@code %msg} 出口仍保留 Bearer/JWT 自由文本覆盖
     * （不能因改走 sanitizeLine 而丢失）。
     */
    @Test
    public void stillMasksBearerToken() {
        String out = convert("Authorization: Bearer " + SECRET);
        assertFalse(out.contains(SECRET), "Bearer 凭据不得残留明文");
    }

    /** CORE-C2 回归：脱敏器抛异常时必须抑制（不回退明文）。 */
    @Test
    public void sanitizerFailureSuppressesMessageInsteadOfLeaking() {
        SanitizingMessageConverter failing = new SanitizingMessageConverter() {
            @Override
            protected String sanitize(String message) {
                throw new IllegalStateException("boom");
            }
        };
        String out = convertWith(failing, "password=" + SECRET);
        assertFalse(out.contains(SECRET), "脱敏失败不得回退明文");
        assertEquals(SanitizingThrowableConverter.suppressedMarker(), out,
                "脱敏失败应输出抑制标记，与异常栈出口一致");
    }

    /** 普通消息原样通过（脱敏不得破坏可读性）。 */
    @Test
    public void leavesNormalMessageIntact() {
        String plain = "user logged in successfully";
        assertEquals(plain, convert(plain));
    }

    /** 运维级总开关关闭时原样通过。 */
    @Test
    public void killSwitchPassesThrough() {
        System.setProperty(SanitizingMessageConverter.ENABLED_KEY, "false");
        String raw = "password=" + SECRET;
        assertEquals(raw, convert(raw), "总开关关闭时应原样输出");
    }

    /**
     * 端到端：真的打一条含密钥的日志，落盘文件中不得出现明文。
     * <p>这是"出口强制"的最终证明 —— 调用方什么都没做，秘密也没落到磁盘上。
     */
    /**
     * 端到端：真的打一条含密钥的日志，落盘内容中<b>不得出现明文</b>。
     *
     * <p>用 {@link TestLogCapture} 挂独立临时 appender（pattern 走 {@code %msg}，
     * 即经过注册的 {@link SanitizingMessageConverter}），不依赖共享日志文件 ——
     * 避免全局吞吐与日志滚动导致的偶发失败。
     */
    @Test
    public void endToEndLogFileContainsNoPlaintextSecret() throws Exception {
        String marker = TestLogCapture.newMarker("sanitize-e2e-");
        try (TestLogCapture capture =
                     TestLogCapture.of(SanitizingMessageConverterTest.class, "%msg%n")) {
            capture.info(marker + "\npassword=" + SECRET);
            String content = capture.content();

            assertTrue(content.contains(marker), "落盘日志中应能找到标记行；实际内容=" + content);
            assertFalse(content.contains(SECRET), "落盘日志绝不能出现明文密钥（出口强制脱敏）");
        }
    }

    /**
     * 回归：{@link TestLogCapture#newMarker} 生成的标记必须对出口脱敏<b>免疫</b>。
     *
     * <p>背景（实测根因）：早期端到端标记用 {@code "e2e-" + System.nanoTime()}，而 PAN 值级识别器候选
     * 正则为 {@code \b\d(?:[ \-]?\d){12,18}\b}（13~19 位数字），命中后由 Luhn 校验裁定。长 uptime 的 JVM 中
     * {@code nanoTime()} 恰为 19 位数字，约 1/10 概率通过 Luhn → 标记被整体遮蔽为 {@code ***[REDACTED]}
     * → 端到端断言偶发「日志中找不到标记行」。{@code newMarker} 末尾补字母破除词边界，从根本上免疫。
     */
    @Test
    public void markerIsImmuneToValueRecognizers() {
        assertEquals("e2e-1758096000123456789z",
                SensitiveDataSanitizer.sanitizeFreeText("e2e-1758096000123456789z"),
                "末尾字母应破除 \\b\\d{13,19}\\b 边界，标记不得被 PAN 识别器整体遮蔽");
        assertEquals("e2e-4111111111111111z",
                SensitiveDataSanitizer.sanitizeFreeText("e2e-4111111111111111z"),
                "即便数字串本身 Luhn 合法，末尾字母也应使其不被整体遮蔽");

        String marker = TestLogCapture.newMarker("e2e-");
        assertEquals(marker, SensitiveDataSanitizer.sanitizeFreeText(marker),
                "newMarker 生成的标记必须原样通过出口脱敏");

        // 多样本：nanoTime 数字串各不相同，等价于覆盖「Luhn 恰好通过」的运气空间（原缺陷约 1/10 命中率）
        for (int i = 0; i < 200; i++) {
            String sample = TestLogCapture.newMarker("loop-" + i + "-");
            assertEquals(sample, SensitiveDataSanitizer.sanitizeFreeText(sample),
                    "newMarker 标记必须始终免疫出口脱敏（样本 " + i + "）");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════════════════

    private static String convert(String message) {
        return convertWith(new SanitizingMessageConverter(), message);
    }

    private static String convertWith(SanitizingMessageConverter converter, String message) {
        ch.qos.logback.classic.spi.LoggingEvent event =
                new ch.qos.logback.classic.spi.LoggingEvent();
        event.setMessage(message);
        converter.start();
        return converter.convert(event);
    }

}
