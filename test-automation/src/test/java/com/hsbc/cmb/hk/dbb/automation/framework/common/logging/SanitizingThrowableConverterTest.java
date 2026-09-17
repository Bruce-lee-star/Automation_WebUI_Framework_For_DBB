package com.hsbc.cmb.hk.dbb.automation.framework.common.logging;

import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-2 / L-1 / L-3 异常栈脱敏契约测试。
 *
 * <p>核心保证：
 * <ul>
 *   <li>异常 message 内嵌的 {@code password=...}/{@code token=...} 在写出前被遮蔽，
 *        即便形如 {@code "Caused by: X: password=s3cr3t"}（{@code : } 早于 {@code =}）；</li>
 *   <li>caused-by 链的 message 同样被脱敏；</li>
 *   <li>普通异常 message 不受影响（脱敏不得破坏可读性）；</li>
 *   <li>脱敏设施自身失败时输出 {@code [LOG SUPPRESSED: sanitizer failure]} 而非原文（L-3）；</li>
 *   <li><b>端到端</b>：真打一条带异常的 ERROR，落盘（%ex 出口）不得出现明文凭据。</li>
 * </ul>
 */
public class SanitizingThrowableConverterTest {

    private static final String SECRET = "hunter2-super-secret";

    private static class Fixture extends SanitizingThrowableConverter {
        Fixture() {
            start();
        }

        /** 直接驱动（不依赖 logback 全局注册）。 */
        String convert(Throwable t) {
            LoggingEvent event = new LoggingEvent();
            event.setThrowableProxy(new ThrowableProxy(t));
            return convert(event);
        }

        @Override
        protected String sanitizeThrowableLine(String in) {
            return delegateSanitize(in);
        }

        /** 测试可覆写以模拟脱敏失败。 */
        String delegateSanitize(String in) {
            return super.sanitizeThrowableLine(in);
        }
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty(SanitizingMessageConverter.ENABLED_KEY);
    }

    /** 异常 message 内嵌 password=... 被遮蔽（覆盖 : 早于 = 的 caused-by 形态）。 */
    @Test
    public void masksEmbeddedSecretInExceptionMessage() {
        Throwable ex = new RuntimeException("login failed: password=" + SECRET);
        String out = new Fixture().convert(ex);
        assertFalse(out.contains(SECRET), "异常栈不得残留明文密钥");
        assertTrue(out.contains(SensitiveDataSanitizer.maskToken()), "应出现掩码");
    }

    /** caused-by 链的 message 同样被脱敏。 */
    @Test
    public void masksSecretInCausedByChain() {
        Throwable root = new IllegalStateException("password=" + SECRET);
        Throwable top = new RuntimeException("outer failure", root);
        String out = new Fixture().convert(top);
        assertFalse(out.contains(SECRET), "caused-by 链的明文密钥不得残留");
    }

    /** 普通异常 message 原样通过（脱敏不得破坏可读性）。 */
    @Test
    public void leavesNormalExceptionMessageIntact() {
        Throwable ex = new RuntimeException("connection refused to host:8080");
        String out = new Fixture().convert(ex);
        assertTrue(out.contains("connection refused to host:8080"),
                "普通异常 message 应原样保留");
        assertFalse(out.contains(SensitiveDataSanitizer.maskToken()),
                "无敏感键时不得出现掩码");
    }

    /** L-3：脱敏失败时输出抑制标记而非原文。 */
    @Test
    public void suppressesWhenSanitizerFails() {
        Fixture boom = new Fixture() {
            @Override
            String delegateSanitize(String in) {
                throw new RuntimeException("sanitizer down");
            }
        };
        String out = boom.convert(new RuntimeException("password=" + SECRET));
        assertTrue(out.contains(SanitizingThrowableConverter.suppressedMarker()),
                "脱敏失败必须输出 [LOG SUPPRESSED] 而非明文");
        assertFalse(out.contains(SECRET), "抑制路径不得泄露明文密钥");
    }

    /** 运维级总开关关闭时原样返回。 */
    @Test
    public void killSwitchPassesThrough() {
        System.setProperty(SanitizingMessageConverter.ENABLED_KEY, "false");
        Throwable ex = new RuntimeException("password=" + SECRET);
        assertTrue(new Fixture().convert(ex).contains(SECRET), "总开关关闭时应原样输出");
    }

    /**
     * 端到端：真打一条带异常的 ERROR，经注册的 {@code %ex} 转换词落盘不得出现明文。
     */
    @Test
    public void endToEndExOutputContainsNoPlaintextSecret() {
        String marker = TestLogCapture.newMarker("throwable-e2e-");
        try (TestLogCapture capture =
                     TestLogCapture.of(SanitizingThrowableConverterTest.class, "%msg | %ex%n")) {
            capture.error(marker, new RuntimeException("auth failed: password=" + SECRET));
            String content = capture.content();

            assertTrue(content.contains(marker), "落盘日志中应能找到标记行；实际内容=" + content);
            assertFalse(content.contains(SECRET), "落盘异常栈绝不能出现明文密钥（%ex 出口强制脱敏）");
        }
    }
}
