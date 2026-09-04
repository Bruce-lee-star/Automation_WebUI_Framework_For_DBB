package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SensitiveDataSanitizer 合规件回归护盾（T0-2）：覆盖 header/body/url/freeText 脱敏、
 * 规范化 key 匹配、附加键注册与统一掩码，确保敏感数据不因格式/字段名变体而漏出日志/报告。
 */
public class SensitiveDataSanitizerTest {

    private static String mask() {
        return SensitiveDataSanitizer.maskToken();
    }

    // ── sanitizeHeaders ──

    @Test
    public void sanitizeHeaders_masksSensitiveHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer abc123");
        headers.put("X-Api-Key", "topsecret");
        headers.put("Content-Type", "application/json");
        Map<String, String> out = SensitiveDataSanitizer.sanitizeHeaders(headers);
        assertTrue(out.get("Authorization").contains(mask()));
        assertTrue(out.get("X-Api-Key").contains(mask()));
        // 非敏感头原样保留
        assertEquals("application/json", out.get("Content-Type"));
        // 原密文不得出域
        assertFalse(out.get("Authorization").contains("abc123"));
    }

    @Test
    public void sanitizeHeaders_preservesNonSensitiveHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "playwright");
        Map<String, String> out = SensitiveDataSanitizer.sanitizeHeaders(headers);
        assertEquals("application/json", out.get("Accept"));
        assertEquals("playwright", out.get("User-Agent"));
    }

    @Test
    public void sanitizeHeaders_nullReturnsNull() {
        assertNull(SensitiveDataSanitizer.sanitizeHeaders(null));
    }

    @Test
    public void sanitizeHeaders_emptyValueNotMasked() {
        Map<String, String> headers = new HashMap<>();
        headers.put("password", "");
        headers.put("Authorization", "");
        Map<String, String> out = SensitiveDataSanitizer.sanitizeHeaders(headers);
        assertEquals("", out.get("password"));
        assertEquals("", out.get("Authorization"));
    }

    @Test
    public void sanitizeHeaders_registerExtraHeaderKeyApplied() {
        SensitiveDataSanitizer.registerExtraSensitiveKeys("mycustomheader", null, null);
        Map<String, String> headers = new HashMap<>();
        headers.put("My-Custom-Header", "do-not-leak");
        Map<String, String> out = SensitiveDataSanitizer.sanitizeHeaders(headers);
        assertTrue(out.get("My-Custom-Header").contains(mask()));
    }

    // ── sanitizeBody ──

    @Test
    public void sanitizeBody_jsonMasksNestedSensitiveKey() {
        String body = "{\"data\":{\"user\":{\"access_token\":\"supersecret\"}}}";
        String out = SensitiveDataSanitizer.sanitizeBody(body);
        assertNotNull(out);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("supersecret"));
    }

    @Test
    public void sanitizeBody_formUrlEncodedMasksPassword() {
        String body = "username=alice&password=s3cr3t";
        String out = SensitiveDataSanitizer.sanitizeBody(body);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("s3cr3t"));
        assertTrue(out.contains("username=alice"));
    }

    @Test
    public void sanitizeBody_xmlMasksElementText() {
        String body = "<Password>s3cr3t</Password>";
        String out = SensitiveDataSanitizer.sanitizeBody(body);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("s3cr3t"));
    }

    @Test
    public void sanitizeBody_nullReturnsNull() {
        assertNull(SensitiveDataSanitizer.sanitizeBody(null));
    }

    @Test
    public void sanitizeBody_registerExtraBodyKeyApplied() {
        SensitiveDataSanitizer.registerExtraSensitiveKeys(null, "customfield", null);
        String body = "{\"customfield\":\"leakme\"}";
        String out = SensitiveDataSanitizer.sanitizeBody(body);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("leakme"));
    }

    // ── sanitizeUrl ──

    @Test
    public void sanitizeUrl_masksSensitiveQueryParam() {
        String url = "https://api.example.com/login?token=abc123&user=alice";
        String out = SensitiveDataSanitizer.sanitizeUrl(url);
        assertNotNull(out);
        assertFalse(out.contains("token=abc123"));
        assertTrue(out.startsWith("https://api.example.com/login"));
    }

    @Test
    public void sanitizeUrl_noQueryUnchanged() {
        String url = "https://api.example.com/path/to/page";
        assertEquals(url, SensitiveDataSanitizer.sanitizeUrl(url));
    }

    @Test
    public void sanitizeUrl_nullReturnsNull() {
        assertNull(SensitiveDataSanitizer.sanitizeUrl(null));
    }

    // ── sanitizeFreeText ──

    @Test
    public void sanitizeFreeText_masksBearerToken() {
        String text = "Authorization: Bearer eyJhbGciOi.eyJzdWIi.c2ln";
        String out = SensitiveDataSanitizer.sanitizeFreeText(text);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("eyJhbGciOi"));
    }

    @Test
    public void sanitizeFreeText_preservesInnocentText() {
        String text = "scenario login step completed successfully";
        assertEquals(text, SensitiveDataSanitizer.sanitizeFreeText(text));
    }

    // ── key 规范化 / 掩码 / 配置 ──

    @Test
    public void isSensitiveBodyKey_detectsVariants() {
        assertTrue(SensitiveDataSanitizer.isSensitiveBodyKey("access_token"));
        assertTrue(SensitiveDataSanitizer.isSensitiveBodyKey("password"));
        assertTrue(SensitiveDataSanitizer.isSensitiveBodyKey("API-KEY")); // 规范化匹配
        assertFalse(SensitiveDataSanitizer.isSensitiveBodyKey("username"));
        assertFalse(SensitiveDataSanitizer.isSensitiveBodyKey("Content-Type"));
    }

    @Test
    public void maskToken_returnsMaskConstant() {
        assertEquals("***[REDACTED]", SensitiveDataSanitizer.maskToken());
    }

    @Test
    public void reloadExtraKeysFromConfig_idempotentNoThrow() {
        SensitiveDataSanitizer.reloadExtraKeysFromConfig();
        // 内置清单不受清空影响
        assertTrue(SensitiveDataSanitizer.isSensitiveBodyKey("password"));
    }
}
