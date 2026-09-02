package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 配置值对称加密工具（AES-256-GCM）。
 *
 * <p>用于"加密原始串入库、用时解密"：把敏感配置（密码 / token / API key）以
 * {@code ENC(<base64>)} 形式存入配置（{@code serenity.properties} / {@code application.conf} /
 * 环境变量），运行时经 {@link SecretValue#decryptIfNeeded(String)} 透明解密。
 * 明文值（非 {@code ENC(...)} 前缀）原样返回，故对既有配置零侵入。
 *
 * <p><b>主密钥</b>来自环境变量 {@code CONFIG_MASTER_KEY}（32 字节 = 64 位十六进制，
 * 可用 {@code openssl rand -hex 32} 生成）或系统属性 {@code config.master.key}。
 * 主密钥<b>不得入库</b>，应通过 CI Secret / 密钥库注入。
 *
 * <p>加密格式：{@code ENC( base64( iv(12B) || ciphertextWithGcmTag ) )}。
 *
 * <p>CLI 用法（生成/校验加密串）：
 * <pre>
 *   java com.hsbc...common.security.ConfigCipher encrypt "mySecret"
 *   java com.hsbc...common.security.ConfigCipher decrypt "ENC(...)"
 * </pre>
 */
public final class ConfigCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String ENC_PREFIX = "ENC(";
    private static final String ENC_SUFFIX = ")";
    private static final String ENV_MASTER_KEY = "CONFIG_MASTER_KEY";
    private static final String PROP_MASTER_KEY = "config.master.key";

    private static final SecureRandom RANDOM = new SecureRandom();

    private ConfigCipher() {
    }

    /** 判断给定值是否为本工具加密产物（{@code ENC(...)}）。 */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX);
    }

    /** 加密明文为 {@code ENC(...)} 形式。主密钥缺失时抛 {@link IllegalStateException}。 */
    public static String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        byte[] key = resolveMasterKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, ALGORITHM), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(out) + ENC_SUFFIX;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt config value", e);
        }
    }

    /**
     * 解密：接受 {@code ENC(...)} 或裸 base64 密文。主密钥缺失时抛 {@link IllegalStateException}
     * 并给出配置指引。
     */
    public static String decrypt(String token) {
        if (token == null) {
            return null;
        }
        String inner = isEncrypted(token)
                ? token.substring(ENC_PREFIX.length(), token.length() - ENC_SUFFIX.length())
                : token;
        byte[] key = resolveMasterKey();
        try {
            byte[] data = Base64.getDecoder().decode(inner);
            if (data.length <= IV_BYTES) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] ct = new byte[data.length - IV_BYTES];
            System.arraycopy(data, 0, iv, 0, IV_BYTES);
            System.arraycopy(data, IV_BYTES, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, ALGORITHM), new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to decrypt config value (check CONFIG_MASTER_KEY is set & matches the ciphertext)", e);
        }
    }

    private static byte[] resolveMasterKey() {
        String hex = System.getenv(ENV_MASTER_KEY);
        if (hex == null || hex.trim().isEmpty()) {
            hex = System.getProperty(PROP_MASTER_KEY);
        }
        if (hex == null || hex.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Master key missing. Set environment variable CONFIG_MASTER_KEY (64 hex chars = 32 bytes, "
                            + "e.g. `openssl rand -hex 32`) or system property config.master.key. "
                            + "Required to encrypt/decrypt ENC(...) config values.");
        }
        return hexToBytes(hex.trim());
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException(
                    "Master key must be an even-length hex string (32 bytes = 64 hex chars)");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseUnsignedInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /** CLI：生成/验证加密串。用法：ConfigCipher encrypt &lt;plaintext&gt; | decrypt &lt;ENC(...)&gt; */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: ConfigCipher encrypt <plaintext> | decrypt <ENC(...)>");
            System.exit(2);
            return;
        }
        try {
            if ("encrypt".equalsIgnoreCase(args[0])) {
                System.out.println(encrypt(args[1]));
            } else if ("decrypt".equalsIgnoreCase(args[0])) {
                System.out.println(decrypt(args[1]));
            } else {
                System.err.println("Unknown op: " + args[0]);
                System.exit(2);
            }
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }
}
