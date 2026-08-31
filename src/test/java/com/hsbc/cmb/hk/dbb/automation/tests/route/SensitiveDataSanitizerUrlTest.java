package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.web.route.util.SensitiveDataSanitizer;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
        assertFalse("token 明文不应出域", masked.contains("secretToken123"));
        assertFalse("sessionId 明文不应出域", masked.contains("abc"));
        assertFalse("非敏感 query 也应随敏感剥离一并移除", masked.contains("user=alice"));
        assertTrue("路径应保留", masked.contains("/login"));
    }

    @Test
    public void shouldKeepNonSensitiveQueryParams() {
        String url = "https://api.example.com/users?page=2&size=10";
        String masked = SensitiveDataSanitizer.sanitizeUrl(url);
        assertNotNull(masked);
        assertTrue("无非敏感参数时 URL 原样保留", masked.contains("page=2"));
        assertTrue(masked.contains("size=10"));
    }

    @Test
    public void shouldReturnNullForNullUrl() {
        assertNull(SensitiveDataSanitizer.sanitizeUrl(null));
    }
}
