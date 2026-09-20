package com.hsbc.cmb.hk.dbb.automation.framework.api.utility;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ApiLogSanitizer} 单测（评审 P-2 补测）。
 *
 * <p>为什么值得测：该类是 API 层打印 header / cookie / 请求体前的<b>最后一道出口</b>——一旦脱敏失效，
 * {@code Authorization}、会话 Cookie、含密码的请求体会明文落入构建日志（CI 日志通常可被广泛读取），
 * 属安全问题而非格式问题。故本测试按「<b>敏感值绝不出现 + 非敏感值必须保留</b>」双向断言：
 * 只断言「不含敏感值」会放过「整条日志被清空」的错误实现，只断言「保留非敏感值」会放过泄漏。
 */
class ApiLogSanitizerTest {

    @Test
    void sensitiveHeaderIsMaskedWhileNormalHeaderIsPreserved() {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer super-secret-token");
        headers.put("Accept", "application/json");

        String log = ApiLogSanitizer.toLogString(headers);

        assertThat(log).doesNotContain("super-secret-token");
        assertThat(log).contains("Accept").contains("application/json");
    }

    @Test
    void toLogStringHandlesNullMapAndNullValues() {
        assertThat(ApiLogSanitizer.toLogString(null)).isEqualTo("null");

        Map<String, Object> withNullValue = new LinkedHashMap<>();
        withNullValue.put("X-Nullable", null);
        assertThatCode(() -> ApiLogSanitizer.toLogString(withNullValue)).doesNotThrowAnyException();
    }

    @Test
    void valueForLogMasksSensitiveNameAndKeepsOrdinaryValue() {
        assertThat(String.valueOf(ApiLogSanitizer.valueForLog("Authorization", "Bearer abc123")))
                .doesNotContain("abc123");
        assertThat(ApiLogSanitizer.valueForLog("Accept", "application/json")).isEqualTo("application/json");
        assertThat(ApiLogSanitizer.valueForLog("Any-Name", null)).isNull();
    }

    @Test
    void bodyForLogMasksPasswordFieldButKeepsOrdinaryFields() {
        String log = ApiLogSanitizer.bodyForLog("{\"username\":\"bob\",\"password\":\"p@ssw0rd\"}");

        assertThat(log).doesNotContain("p@ssw0rd");
        assertThat(log).contains("bob");
        assertThatCode(() -> ApiLogSanitizer.bodyForLog(null)).doesNotThrowAnyException();
    }
}
