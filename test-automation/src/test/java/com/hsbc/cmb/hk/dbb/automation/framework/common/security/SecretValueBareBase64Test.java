package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * D2-2 密文判定收紧契约测试。
 *
 * <p>核心保证：
 * <ul>
 *   <li>{@code ENC(...)} 显式标记 —— <b>始终</b>解密（主加密写法，不受开关影响）；</li>
 *   <li>裸 base64 —— <b>默认不再当密文</b>，原样返回（避免普通配置值被误判并"解"坏）；</li>
 *   <li>裸 base64 —— 仅在显式开关 {@code framework.secret.allow-bare-base64=true} 下才解密。</li>
 * </ul>
 *
 * <p>主密钥不可用时（无 {@code ~/.dbb_automation_master_key} 且无法创建），
 * 加解密断言整体跳过 —— 本类验证的是"是否尝试解密"的判定逻辑，不验证加解密算法本身。
 */
public class SecretValueBareBase64Test {

    private static final String PLAIN = "plain-secret-123";

    /**
     * 测试专用主密钥（64 hex = 32 字节）。经系统属性 {@code config.master.key} 注入
     * —— 这样无需读写 {@code user.home} 下的真实密钥文件，测试零副作用、可重复。
     */
    private static final String TEST_MASTER_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @BeforeEach
    public void setUp() {
        System.setProperty("config.master.key", TEST_MASTER_KEY);
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty(SecretValue.ALLOW_BARE_BASE64_KEY);
        System.clearProperty("config.master.key");
    }

    /** 显式 ENC(...) 标记：无论开关如何都解密。 */
    @Test
    public void encMarkedValueIsAlwaysDecrypted() {
        String enc = encryptOrSkip();
        assertEquals(PLAIN, SecretValue.decryptIfNeeded(enc));
    }

    /** 裸 base64：默认不当密文，原样返回（普通配置值不再被误判）。 */
    @Test
    public void bareBase64IsNotDecryptedByDefault() {
        String bare = bareCiphertextOrSkip();
        assertEquals(bare, SecretValue.decryptIfNeeded(bare), "裸 base64 默认应原样返回，不再尝试解密");
    }

    /** 裸 base64：显式开启开关后才解密。 */
    @Test
    public void bareBase64DecryptedOnlyWhenOptInEnabled() {
        String bare = bareCiphertextOrSkip();
        System.setProperty(SecretValue.ALLOW_BARE_BASE64_KEY, "true");
        assertEquals(PLAIN, SecretValue.decryptIfNeeded(bare));
    }

    // ═══════════════════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════════════════

    private static String encryptOrSkip() {
        try {
            return ConfigCipher.encrypt(PLAIN);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "主密钥不可用（无法加解密），跳过本断言");
            return null;
        }
    }

    /** 去掉 ENC(...) 外壳，得到"裸 base64 密文"。 */
    private static String bareCiphertextOrSkip() {
        String enc = encryptOrSkip();
        return enc.substring("ENC(".length(), enc.length() - 1);
    }
}
