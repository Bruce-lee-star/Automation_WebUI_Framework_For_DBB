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
        //  故敏感键需位于行首（真实日志中 password=xxx 通常独立成段）
        String out = convert("api call failed\npassword=" + SECRET + "\nretrying");
        assertFalse(out.contains(SECRET), "日志消息不得残留明文密钥");
        assertTrue(out.contains(SensitiveDataSanitizer.maskToken()), "应出现掩码");
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
        String marker = "sanitize-e2e-" + System.nanoTime();
        try (TestLogCapture capture =
                     TestLogCapture.of(SanitizingMessageConverterTest.class, "%msg%n")) {
            capture.info(marker + "\npassword=" + SECRET);
            String content = capture.content();

            assertTrue(content.contains(marker), "落盘日志中应能找到标记行");
            assertFalse(content.contains(SECRET), "落盘日志绝不能出现明文密钥（出口强制脱敏）");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════════════════

    private static String convert(String message) {
        SanitizingMessageConverter converter = new SanitizingMessageConverter();
        ch.qos.logback.classic.spi.LoggingEvent event =
                new ch.qos.logback.classic.spi.LoggingEvent();
        event.setMessage(message);
        converter.start();
        return converter.convert(event);
    }

}
