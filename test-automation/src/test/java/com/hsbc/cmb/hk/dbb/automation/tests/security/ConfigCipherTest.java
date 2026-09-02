package com.hsbc.cmb.hk.dbb.automation.tests.security;

import com.hsbc.cmb.hk.dbb.automation.framework.common.security.ConfigCipher;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SecretValue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    @Before
    public void setUp() {
        savedProp = System.getProperty("config.master.key");
        System.setProperty("config.master.key", TEST_MASTER_KEY);
    }

    @After
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
        assertTrue("应为 ENC(...) 形态", ConfigCipher.isEncrypted(enc));
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
    public void missingMasterKeyThrows() {
        System.clearProperty("config.master.key");
        try {
            ConfigCipher.encrypt("x");
            fail("主密钥缺失时应抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("CONFIG_MASTER_KEY"));
        }
    }
}
