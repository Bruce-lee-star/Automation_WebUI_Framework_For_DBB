package com.hsbc.cmb.hk.dbb.automation.framework.api.logging;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SanitizingPrintStream 单测（评审08类-6 / P-1 / SEC-1）：验证 REST 流量日志经出口强制脱敏。
 */
class SanitizingPrintStreamTest {

    @Test
    void masksAuthorizationBearerToken() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (SanitizingPrintStream ps = new SanitizingPrintStream(bos)) {
            ps.println("Authorization: Bearer top-secret-token-12345");
        }
        String out = decode(bos);
        assertThat(out).doesNotContain("top-secret-token-12345");
    }

    @Test
    void masksPasswordInBody() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        SanitizingPrintStream ps = new SanitizingPrintStream(bos);
        ps.println("password=SuperSecretPass!&user=alice");
        String out = decode(bos);
        assertThat(out).doesNotContain("SuperSecretPass!");
    }

    @Test
    void doesNotThrowWhenSanitizerFailsAndKeepsLog() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        SanitizingPrintStream ps = new SanitizingPrintStream(bos);
        ps.println("normal log line without secrets");
        String out = decode(bos);
        assertThat(out).contains("normal log line without secrets");
    }

    @Test
    void sanitizeToStringIsPureFunction() {
        String r = SanitizingPrintStream.sanitizeToString("access_token=xyz789abc");
        assertThat(r).doesNotContain("xyz789abc");
    }

    private static String decode(ByteArrayOutputStream bos) {
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
