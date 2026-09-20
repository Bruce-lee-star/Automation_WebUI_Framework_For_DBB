package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SensitiveDataSanitizer} <b>行为基线</b>测试（SensitiveDataSanitizer 拆分专项 · 阶段 0）。
 *
 * <p>为什么先写它：该类的「整类策略链拆分」（C-7 / M-3 遗留项，952+ 行）属<b>安全关键</b>重构，
 * 拆分必须<b>逐字保持行为</b>。既有测试只覆盖并发 reload（{@code SensitiveDataSanitizerReloadConcurrencyTest}），
 * 缺少对「脱敏结果本身」的断言——一旦拆分改坏某条链路，除了并发竞态测试外无人发现。
 * 故本测试作为拆分前后的<b>等价网</b>：先锁定当前行为，重构后必须逐条仍绿。
 *
 * <p>断言采用<b>属性式</b>而非整串快照：
 * <ul>
 *   <li><b>敏感值绝不出现</b> + <b>非敏感值必须保留</b> —— 单向断言会放过"整段被清空"或"整体放行"两类错误实现；</li>
 *   <li><b>幂等</b> —— 重复脱敏结果不变（掩码本身不得再被视为敏感键/值）。</li>
 * </ul>
 * 覆盖四条链路：JSON 树递归（含嵌套与非法 JSON 兜底）、XML（元素文本 + 属性）、
 * form-urlencoded、自由文本（key=value / Bearer / JWT）+ URL（userinfo 与敏感 query）。
 */
class SensitiveDataSanitizerBehaviorTest {

    @Test
    void headersMaskSensitiveValueAndKeepOrdinaryValue() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer supersecret-token");
        headers.put("Accept", "application/json");

        Map<String, String> sanitized = SensitiveDataSanitizer.sanitizeHeaders(headers);

        assertFalse(sanitized.get("Authorization").contains("supersecret-token"),
                "Authorization 的值必须被遮蔽");
        assertEquals("application/json", sanitized.get("Accept"), "非敏感头必须原样保留");
        assertNull(SensitiveDataSanitizer.sanitizeHeaders(null), "null 入参返回 null");
    }

    @Test
    void jsonBodyMasksSensitiveFieldAtAnyDepthAndKeepsOrdinaryField() {
        String json = "{\"data\":{\"user\":{\"access_token\":\"tok-abc-123\",\"name\":\"bob\"}}}";

        String out = SensitiveDataSanitizer.sanitizeBody(json);

        assertFalse(out.contains("tok-abc-123"), "嵌套层敏感字段必须被遮蔽（深度不限）");
        assertTrue(out.contains("bob"), "非敏感字段必须保留");
    }

    @Test
    void jsonBodyAlsoMasksByValueWhenFieldNameIsInnocent() {
        // 字段名无关（note），值形如银行卡号 → 按值级识别遮蔽
        String json = "{\"note\":\"4111111111111111\"}";

        String out = SensitiveDataSanitizer.sanitizeBody(json);

        assertFalse(out.contains("4111111111111111"), "值级识别应遮蔽形如 PAN 的值");
    }

    @Test
    void malformedJsonFallsBackToTextMaskingInsteadOfPassingThrough() {
        String bad = "{\"password\":\"p@ssw0rd\",\"user\":\"alice\"";

        String out = SensitiveDataSanitizer.sanitizeBody(bad);

        assertFalse(out.contains("p@ssw0rd"), "非法 JSON 必须降级到文本兜底，绝不原样放行");
    }

    @Test
    void xmlBodyMasksElementTextAndAttributeValue() {
        String xml = "<Login><Password>s3cr3t</Password><Card number=\"4111111111111111\"/></Login>";

        String out = SensitiveDataSanitizer.sanitizeBody(xml);

        assertFalse(out.contains("s3cr3t"), "XML 元素文本必须被遮蔽");
        assertFalse(out.contains("4111111111111111"), "XML 属性值必须被遮蔽");
        assertTrue(out.contains("Login"), "非敏感标签结构必须保留");
    }

    @Test
    void formUrlEncodedBodyMasksSensitiveValueAndKeepsOrdinaryValue() {
        String form = "username=alice&password=s3cr3t-P@ss";

        String out = SensitiveDataSanitizer.sanitizeBody(form);

        assertFalse(out.contains("s3cr3t-P@ss"), "form 敏感键的值必须被遮蔽");
        assertTrue(out.contains("alice"), "非敏感键的值必须保留");
    }

    @Test
    void freeTextMasksKeyValueAndBearerAndJwt() {
        assertFalse(SensitiveDataSanitizer.sanitizeFreeText("password=s3cr3t").contains("s3cr3t"),
                "自由文本 key=value 形态必须遮蔽");

        String bearer = SensitiveDataSanitizer.sanitizeLogMessage("Authorization: Bearer abcDEF123456ghi");
        assertFalse(bearer.contains("abcDEF123456ghi"), "Bearer 凭据必须遮蔽");

        String jwt = SensitiveDataSanitizer.sanitizeLogMessage(
                "token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c");
        assertFalse(jwt.contains("SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"), "JWT 签名段必须遮蔽");
    }

    @Test
    void urlStripsUserinfoAndSensitiveQueryParam() {
        String withUserinfo = SensitiveDataSanitizer.sanitizeUrl("https://alice:p@ssw0rd@example.com/path?a=1");
        assertFalse(withUserinfo.contains("p@ssw0rd"), "URL 内嵌凭据必须被剥离");

        String withToken = SensitiveDataSanitizer.sanitizeUrl("https://example.com/path?access_token=abc123&page=2");
        assertFalse(withToken.contains("abc123"), "敏感 query 参数必须被移除");

        assertNull(SensitiveDataSanitizer.sanitizeUrl(null), "null 入参返回 null");
    }

    @Test
    void sanitizationIsIdempotentAndNullTolerant() {
        String json = "{\"password\":\"p@ss\",\"user\":\"alice\"}";
        String once = SensitiveDataSanitizer.sanitizeBody(json);
        assertEquals(once, SensitiveDataSanitizer.sanitizeBody(once), "重复脱敏结果必须稳定（幂等）");

        assertNull(SensitiveDataSanitizer.sanitizeBody(null), "null body 原样返回");
        assertNull(SensitiveDataSanitizer.sanitizeLine(null), "null line 返回 null");
        assertNull(SensitiveDataSanitizer.sanitizeLogMessage(null), "null message 返回 null");
    }

    @Test
    void keyNormalizationCoversNamingStyles() {
        assertTrue(SensitiveDataSanitizer.isSensitiveBodyKey("access_token"), "snake_case 命中");
        assertTrue(SensitiveDataSanitizer.isSensitiveBodyKey("accessToken"), "camelCase 命中");
        assertTrue(SensitiveDataSanitizer.isSensitiveBodyKey("Access-Token"), "kebab/Pascal 命中");
        assertFalse(SensitiveDataSanitizer.isSensitiveBodyKey("ordinaryField"), "普通字段不命中");
        assertFalse(SensitiveDataSanitizer.isSensitiveBodyKey(null), "null 安全返回 false");
    }
}
