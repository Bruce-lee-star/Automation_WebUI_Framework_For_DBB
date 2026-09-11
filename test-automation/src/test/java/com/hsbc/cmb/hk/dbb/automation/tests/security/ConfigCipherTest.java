package com.hsbc.cmb.hk.dbb.automation.tests.security;

import com.hsbc.cmb.hk.dbb.automation.framework.common.security.ConfigCipher;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SecretValue;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link ConfigCipher} / {@link SecretValue} 加解密往返与透明解密回归测试。
 *
 * <p>主密钥通过系统属性 {@code config.master.key} 注入（测试用固定 32 字节密钥），
 * 与运行时环境变量 {@code CONFIG_MASTER_KEY} 等价。
 */
public class ConfigCipherTest {

    private static final String TEST_MASTER_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private String savedProp;

    @BeforeEach
    public void setUp() {
        savedProp = System.getProperty("config.master.key");
        System.setProperty("config.master.key", TEST_MASTER_KEY);
    }

    @AfterEach
    public void tearDown() {
        if (savedProp == null) {
            System.clearProperty("config.master.key");
        } else {
            System.setProperty("config.master.key", savedProp);
        }
    }

    @Test
    public void encryptDecryptRoundTrip() {
        String plain = "b2g3ifd";
        String enc = ConfigCipher.encrypt(plain);
        assertTrue( ConfigCipher.isEncrypted(enc), "应为 ENC(...) 形态");
        assertEquals(plain, ConfigCipher.decrypt(enc));
    }

    @Test
    public void decryptIfNeededPassesThroughPlaintext() {
        assertEquals("open", SecretValue.decryptIfNeeded("open"));
        assertNull(SecretValue.decryptIfNeeded(null));
        assertFalse(ConfigCipher.isEncrypted("just-a-value"));
    }

    @Test
    public void decryptIfNeededDecryptsEncValue() {
        assertEquals("s3cr3t", SecretValue.decryptIfNeeded(ConfigCipher.encrypt("s3cr3t")));
    }

    @Test
    public void decryptIfNeededDecryptsBareCiphertext() {
        // D2-2：裸 base64 默认不再当密文（避免普通配置值被误判并"解"坏）；
        // 仅显式开启 opt-in 开关后才解密，故此处须显式启用。
        System.setProperty(SecretValue.ALLOW_BARE_BASE64_KEY, "true");
        try {
            String enc = ConfigCipher.encrypt("b4re-secret");
            // 去掉 ENC(...) 包裹，模拟裸 base64 密文
            String bare = enc.substring("ENC(".length(), enc.length() - ")".length());
            assertEquals("b4re-secret", SecretValue.decryptIfNeeded(bare));
        } finally {
            System.clearProperty(SecretValue.ALLOW_BARE_BASE64_KEY);
        }
    }

    @Test
    public void decryptIfNeededReturnsNonCiphertextAsIs() {
        // 普通明文（含非 base64 字符）原样返回，不抛异常
        assertEquals("authorization,password", SecretValue.decryptIfNeeded("authorization,password"));
        // 合法 base64 但非本框架密文应原样返回（解密尝试失败，保留原串）
        String fakeBase64 = Base64.getEncoder()
                .encodeToString("not-a-real-ciphertext".getBytes(StandardCharsets.UTF_8));
        assertEquals(fakeBase64, SecretValue.decryptIfNeeded(fakeBase64));
    }

    @Test
    public void randomizedIvYieldsDifferentCiphertextEachTime() {
        String a = ConfigCipher.encrypt("alpha");
        String b = ConfigCipher.encrypt("alpha");
        assertNotEquals("IV 随机，密文应不同", a, b);
        assertEquals("alpha", ConfigCipher.decrypt(b));
    }

    @Test
    public void unicodePlaintextRoundTrip() {
        String plain = "密码-🔒-p@ss/中文字符";
        assertEquals(plain, ConfigCipher.decrypt(ConfigCipher.encrypt(plain)));
    }

    @Test
    public void missingMasterKeyThrows() throws Exception {
        // 重定向 user.home 到空的临时目录，确保固定路径下无密钥文件，避免误读真实用户目录
        String savedHome = System.getProperty("user.home");
        File home = Files.createTempDirectory("dbb-test-home-empty").toFile();
        home.deleteOnExit();
        System.setProperty("user.home", home.getAbsolutePath());
        System.clearProperty("config.master.key");
        try {
            ConfigCipher.encrypt("x");
            fail("主密钥缺失时应抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("CONFIG_MASTER_KEY"));
        } finally {
            System.setProperty("user.home", savedHome);
        }
    }

    @Test
    public void masterKeyReadFromUserHomeFile() throws Exception {
        // 重定向 user.home 到临时目录，验证固定路径 ~/.dbb_automation_master_key 被读取（不触碰真实用户目录）
        String savedHome = System.getProperty("user.home");
        File home = Files.createTempDirectory("dbb-test-home").toFile();
        home.deleteOnExit();
        File keyFile = new File(home, ".dbb_automation_master_key");
        keyFile.deleteOnExit();
        Files.write(keyFile.toPath(), TEST_MASTER_KEY.getBytes(StandardCharsets.UTF_8));
        System.setProperty("user.home", home.getAbsolutePath());
        String savedProp = System.getProperty("config.master.key");
        try {
            System.clearProperty("config.master.key");
            String plain = "file-keyed-secret";
            String enc = ConfigCipher.encrypt(plain);
            assertEquals(plain, ConfigCipher.decrypt(enc));
        } finally {
            if (savedProp == null) {
                System.clearProperty("config.master.key");
            } else {
                System.setProperty("config.master.key", savedProp);
            }
            System.setProperty("user.home", savedHome);
        }
    }
}
