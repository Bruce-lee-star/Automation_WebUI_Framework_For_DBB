package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T0-1 回归测试：URL 脱敏收口。
 *
 * <p>校验 {@link SensitiveDataSanitizer#sanitizeUrl(String)} 在含敏感 query 参数时剥离整个 query，
 * 确保 query 中的 token / sessionId 不会经 {@code getRequestUrl()}、报告输出等路径出域。
 */
public class SensitiveDataSanitizerUrlTest {

    @Test
    public void shouldStripSensitiveQueryParamsFromUrl() {
        String url = "https://api.example.com/login?token=secretToken123&sessionId=abc&user=alice";
        String masked = SensitiveDataSanitizer.sanitizeUrl(url);
        assertNotNull(masked);
        // 含敏感参数时整个 query 被剥离，敏感值不得出域
        assertFalse( masked.contains("secretToken123"), "token 明文不应出域");
        assertFalse( masked.contains("abc"), "sessionId 明文不应出域");
        assertFalse( masked.contains("user=alice"), "非敏感 query 也应随敏感剥离一并移除");
        assertTrue( masked.contains("/login"), "路径应保留");
    }

    @Test
    public void shouldKeepNonSensitiveQueryParams() {
        String url = "https://api.example.com/users?page=2&size=10";
        String masked = SensitiveDataSanitizer.sanitizeUrl(url);
        assertNotNull(masked);
        assertTrue( masked.contains("page=2"), "无非敏感参数时 URL 原样保留");
        assertTrue(masked.contains("size=10"));
    }

    @Test
    public void shouldReturnNullForNullUrl() {
        assertNull(SensitiveDataSanitizer.sanitizeUrl(null));
    }

    @Test
    public void shouldStripUserinfoFromUrl() {
        // JDBC URL 内嵌凭据（user:pass@）
        String jdbc = "jdbc:mysql://root:secret@localhost:3306/route_monitor";
        String maskedJdbc = SensitiveDataSanitizer.sanitizeUrl(jdbc);
        assertNotNull(maskedJdbc);
        assertFalse( maskedJdbc.contains("secret"), "JDBC URL 内嵌密码不应出域");
        assertFalse( maskedJdbc.contains("root"), "JDBC URL 内嵌账号不应出域");
        // HTTP URL 内嵌凭据
        String http = "https://admin:pwd123@api.example.com/login";
        String maskedHttp = SensitiveDataSanitizer.sanitizeUrl(http);
        assertNotNull(maskedHttp);
        assertFalse( maskedHttp.contains("pwd123"), "HTTP URL 内嵌密码不应出域");
        assertFalse( maskedHttp.contains("admin"), "HTTP URL 内嵌账号不应出域");
    }

    @Test
    public void shouldMaskUserConfiguredExtraHeaderKey() {
        SensitiveDataSanitizer.registerExtraSensitiveKeys("x-custom-secret", null, null);
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Custom-Secret", "topsecret");
        headers.put("Content-Type", "application/json");
        Map<String, String> masked = SensitiveDataSanitizer.sanitizeHeaders(headers);
        assertFalse( masked.get("X-Custom-Secret").contains("topsecret"), "自定义头应被脱敏");
        assertTrue( "application/json".equals(masked.get("Content-Type")), "非敏感头应保留");
    }

    @Test
    public void shouldMaskUserConfiguredExtraBodyKey() {
        SensitiveDataSanitizer.registerExtraSensitiveKeys(null, "myInternalToken", null);
        String body = "{\"my_internal_token\":\"abc123\",\"user\":\"alice\"}";
        String masked = SensitiveDataSanitizer.sanitizeBody(body);
        assertFalse( masked.contains("abc123"), "自定义体字段应被脱敏");
        assertTrue( masked.contains("alice"), "非敏感字段应保留");
    }

    @Test
    public void shouldStripUserConfiguredExtraQueryKeyViaConfigProperty() {
        String prop = "sensitive.data.extra.query.keys";
        System.setProperty(prop, "traceid");
        try {
            SensitiveDataSanitizer.reloadExtraKeysFromConfig();
            String url = "https://api.example.com/x?traceId=secretTrace&user=alice";
            String masked = SensitiveDataSanitizer.sanitizeUrl(url);
            assertFalse( masked.contains("secretTrace"), "自定义 query 参数应被脱敏");
            assertFalse( masked.contains("user=alice"), "敏感 query 存在时整 query 应移除");
        } finally {
            System.clearProperty(prop);
            SensitiveDataSanitizer.reloadExtraKeysFromConfig();
        }
    }
}
