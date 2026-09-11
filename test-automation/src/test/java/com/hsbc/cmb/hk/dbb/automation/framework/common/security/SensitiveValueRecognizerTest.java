package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4-2 值级识别 + 定长掩码 + 配置外置 专项护盾。
 *
 * <p>覆盖：银行卡号（PAN/Luhn）、IBAN（mod-97）、HKID（加权 mod-11）、信用卡轨道数据；
 * 各脱敏链路（JSON/XML/form/header/url/freeText）按「值内容」脱敏；定长掩码不泄露长度；
 * 字段名规则外置（profile 叠加）与值级识别豁免名单。
 */
public class SensitiveValueRecognizerTest {

    private static String mask() {
        return SensitiveDataSanitizer.maskToken();
    }

    // ── PAN / Luhn ──

    @Test
    public void pan_validLuhnDetected() {
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("4242424242424242"));
    }

    @Test
    public void pan_invalidLuhnNotDetected() {
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("4242424242424241"));
    }

    @Test
    public void pan_withSeparatorsDetected() {
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("4242 4242 4242 4242"));
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("4242-4242-4242-4242"));
    }

    @Test
    public void pan_tooShortNotDetected() {
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("123456789012"));
    }

    @Test
    public void pan_allSameDigitsSuppressed() {
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("1111111111111111"));
    }

    // ── IBAN (mod-97) ──

    @Test
    public void iban_validDetected() {
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("DE89370400440532013000"));
    }

    @Test
    public void iban_invalidNotDetected() {
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("DE89370400440532013001"));
    }

    // ── HKID (加权 mod-11) ──

    @Test
    public void hkid_validWithParenDetected() {
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("CA182361(1)"));
    }

    @Test
    public void hkid_validWithoutParenDetected() {
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("CA1823611"));
    }

    @Test
    public void hkid_validTwoLetterPrefixDetected() {
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("B111112(A)"));
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("B111117(0)"));
    }

    @Test
    public void hkid_invalidNotDetected() {
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("CA182361(2)"));
    }

    // ── 信用卡轨道数据 ──

    @Test
    public void track1Detected() {
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("%B4111111111111111^JOHN DOE^2512101000000000000000000000000?"));
    }

    @Test
    public void track2Detected() {
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue(";4111111111111111=25121010000000000000?"));
    }

    // ── 国内敏感信息：身份证 / 银联卡 / 手机号 ──

    @Test
    public void chinaId_validDetected() {
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("11010519491231002X"));
    }

    @Test
    public void chinaId_invalidChecksumNotDetected() {
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("110105194912310021"));
    }

    @Test
    public void chinaId_badFormatNotDetected() {
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("12345"));
    }

    @Test
    public void unionPay_validDetected() {
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("6212345678901232"));
    }

    @Test
    public void unionPay_invalidLuhnNotDetected() {
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("6212345678901235"));
    }

    @Test
    public void unionPay_nonUnionPayPrefixNotDetected() {
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("4212345678901234"));
    }

    @Test
    public void chinaMobile_validDetected() {
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("13800138000"));
    }

    @Test
    public void chinaMobile_badPrefixNotDetected() {
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("12345678901"));
    }

    @Test
    public void chinaMobile_wrongLengthNotDetected() {
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("1380013800"));
    }

    // ── 国内敏感信息（续）：护照 / 港澳通行证 / 统一社会信用代码 ──

    @Test
    public void chinaPassport_validDetected() {
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("E12345678"));
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("G12345678"));
    }

    @Test
    public void chinaPassport_invalidNotDetected() {
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("Z12345678")); // 非法前缀
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("E1234567"));  // 位数不对
    }

    @Test
    public void chinaHkMo_validDetected() {
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("C12345678")); // 往来港澳通行证
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("H12345678")); // 香港回乡证
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("M12345678")); // 澳门回乡证
    }

    @Test
    public void chinaHkMo_invalidNotDetected() {
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("X12345678"));
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("C1234567"));
    }

    @Test
    public void chinaUscc_validDetected() {
        // 腾讯统一社会信用代码（GB 32100-2015，mod-31 校验位）
        assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("91440300708461153K"));
    }

    @Test
    public void chinaUscc_invalidChecksumNotDetected() {
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("91440300708461153J"));
    }

    @Test
    public void chinaUscc_badFormatNotDetected() {
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("9144030070846115"));   // 17 位
        assertFalse(SensitiveDataSanitizer.looksSensitiveByValue("9144030070846115300")); // 19 位
    }

    @Test
    public void json_valueLevelMasksChinaPassport() {
        String body = "{\"note\":\"E12345678\"}";
        String out = SensitiveDataSanitizer.sanitizeBody(body);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("E12345678"));
    }

    @Test
    public void json_valueLevelMasksChinaHkMo() {
        String body = "{\"note\":\"C12345678\"}";
        String out = SensitiveDataSanitizer.sanitizeBody(body);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("C12345678"));
    }

    @Test
    public void json_valueLevelMasksChinaUscc() {
        String body = "{\"orgCode\":\"91440300708461153K\"}";
        String out = SensitiveDataSanitizer.sanitizeBody(body);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("91440300708461153K"));
    }

    @Test
    public void freeText_valueLevelMasksChinaPassport() {
        String text = "护照号 E12345678 已核验";
        String out = SensitiveDataSanitizer.sanitizeFreeText(text);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("E12345678"));
    }

    @Test
    public void freeText_valueLevelMasksChinaHkMo() {
        String text = "通行证 C12345678 有效";
        String out = SensitiveDataSanitizer.sanitizeFreeText(text);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("C12345678"));
    }

    @Test
    public void freeText_valueLevelMasksChinaUscc() {
        String text = "统一社会信用代码 91440300708461153K 登记";
        String out = SensitiveDataSanitizer.sanitizeFreeText(text);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("91440300708461153K"));
    }

    // ── 银行卡 CVV：按字段名 / 相邻标签识别（无校验位，避免裸 3~4 位数字误报）──

    @Test
    public void cvv_fieldNameMasksInJson() {
        String body = "{\"cvv\":\"123\"}";
        String out = SensitiveDataSanitizer.sanitizeBody(body);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("\"123\""));
    }

    @Test
    public void cvv_freeTextLabelMasks() {
        String text = "cvv: 123";
        String out = SensitiveDataSanitizer.sanitizeFreeText(text);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("123"));
    }

    @Test
    public void json_valueLevelMasksChinaId() {
        String body = "{\"note\":\"11010519491231002X\"}";
        String out = SensitiveDataSanitizer.sanitizeBody(body);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("11010519491231002X"));
    }

    @Test
    public void freeText_valueLevelMasksChinaId() {
        String text = "客户身份证号 11010519491231002X 已登记";
        String out = SensitiveDataSanitizer.sanitizeFreeText(text);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("11010519491231002X"));
    }

    @Test
    public void freeText_valueLevelMasksChinaMobile() {
        String text = "联系电话 13800138000 请回访";
        String out = SensitiveDataSanitizer.sanitizeFreeText(text);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("13800138000"));
    }

    // ── 各链路按值脱敏（字段名非敏感，但值是卡号）──

    @Test
    public void json_valueLevelMasksNoteField() {
        String body = "{\"note\":\"4242424242424242\"}";
        String out = SensitiveDataSanitizer.sanitizeBody(body);
        assertNotNull(out);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("4242424242424242"));
    }

    @Test
    public void json_nonSensitiveValuePreserved() {
        String body = "{\"note\":\"hello world\"}";
        String out = SensitiveDataSanitizer.sanitizeBody(body);
        assertTrue(out.contains("hello world"));
        assertFalse(out.contains(mask()));
    }

    @Test
    public void xml_valueLevelMasksNoteElement() {
        String body = "<Note>4242424242424242</Note>";
        String out = SensitiveDataSanitizer.sanitizeBody(body);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("4242424242424242"));
    }

    @Test
    public void header_valueLevelMasksPan() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Request-Id", "4242424242424242");
        headers.put("Content-Type", "application/json");
        Map<String, String> out = SensitiveDataSanitizer.sanitizeHeaders(headers);
        assertTrue(out.get("X-Request-Id").contains(mask()));
        assertEquals("application/json", out.get("Content-Type"));
    }

    @Test
    public void freeText_valueLevelMasksPan() {
        String text = "transaction ref 4242424242424242 completed";
        String out = SensitiveDataSanitizer.sanitizeFreeText(text);
        assertTrue(out.contains(mask()));
        assertFalse(out.contains("4242424242424242"));
    }

    // ── 定长掩码：不泄露长度 ──

    @Test
    public void fixedMask_doesNotLeakLength() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer abc123");
        Map<String, String> out = SensitiveDataSanitizer.sanitizeHeaders(headers);
        assertTrue(out.get("Authorization").contains(mask()));
        assertFalse( out.get("Authorization").contains("(len="), "定长掩码不得泄露原值长度");
        assertFalse(out.get("Authorization").contains("abc123"));
    }

    // ── 字段名规则外置（profile 叠加，不丢内置）──

    @Test
    public void config_overrideAddsBodyKeyWithoutLosingBuiltins() {
        String prop = "sensitive.data.profile.defaults.body.keys";
        System.setProperty(prop, "myfield,otherfield");
        try {
            SensitiveDataSanitizer.reloadRules();
            assertTrue( SensitiveDataSanitizer.isSensitiveBodyKey("myfield"), "配置新增字段应生效");
            assertTrue(SensitiveDataSanitizer.isSensitiveBodyKey("otherfield"));
            assertTrue( SensitiveDataSanitizer.isSensitiveBodyKey("password"), "内置敏感键不得因配置叠加而失效");
        } finally {
            System.clearProperty(prop);
            SensitiveDataSanitizer.reloadRules();
        }
    }

    // ── 值级识别豁免名单（抑制误报）──

    @Test
    public void exclusionList_suppressesFalsePositive() {
        String prop = "sensitive.data.value.excludes";
        System.setProperty(prop, "4242424242424242");
        try {
            SensitiveDataSanitizer.reloadRules();
            assertFalse( SensitiveDataSanitizer.looksSensitiveByValue("4242424242424242"), "豁免名单命中的值不应脱敏");
            assertTrue( SensitiveDataSanitizer.looksSensitiveByValue("5555555555554444"), "未豁免的有效卡号仍应识别");
        } finally {
            System.clearProperty(prop);
            SensitiveDataSanitizer.reloadRules();
        }
    }

    // ── SPI / 运行时注册自定义识别器 ──

    @Test
    public void registerValueRecognizer_customWorks() {
        SensitiveValueRecognizer custom = new SensitiveValueRecognizer() {
            @Override
            public String name() {
                return "CUSTOM";
            }

            @Override
            public boolean recognizes(String value) {
                return value != null && value.contains("SECRET-CUSTOM");
            }
        };
        SensitiveDataSanitizer.registerValueRecognizer(custom);
        try {
            assertTrue(SensitiveDataSanitizer.looksSensitiveByValue("xx SECRET-CUSTOM yy"));
        } finally {
            SensitiveDataSanitizer.reloadRules(); // 复位为内置识别器
        }
    }
}
