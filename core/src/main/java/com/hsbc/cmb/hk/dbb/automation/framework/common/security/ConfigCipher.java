package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 配置值对称加密工具（AES-256-GCM）。
 *
 * <p>用于"加密原始串入库、用时解密"：把敏感配置（密码 / token / API key）以
 * {@code ENC(<base64>)} 形式存入配置（{@code serenity.properties} / {@code application.conf} /
 * 环境变量），运行时经 {@link SecretValue#decryptIfNeeded(String)} 透明解密。
 * 明文值（非密文形态）原样返回，故对既有配置零侵入。读取端同时接受 {@code ENC(<base64>)}
 * 与裸 {@code <base64>} 密文（见 {@link #looksLikeCiphertext}）。
 *
 * <p><b>主密钥</b>按序解析：① 环境变量 {@code CONFIG_MASTER_KEY}；② 系统属性
 * {@code config.master.key}；③ 用户目录下的密钥文件 {@code ~/.dbb_automation_master_key}
 * （内容为 64 位十六进制，文件名与路径固定，跨操作系统由 {@code user.home} 定位）。
 * 密钥文件不存在或不可读时静默跳过，由上层在三种来源皆缺时统一报错；框架<b>不会自动创建</b>该文件。
 * 密钥为 32 字节 = 64 位十六进制，可用 {@code openssl rand -hex 32} 生成。主密钥<b>不得入库</b>；
 * 本地文件应仅本人可读（建议 {@code chmod 600}）。
 *
 * <p><b>安全须知</b>：Base64 仅把"IV + 密文"的二进制转成文本以便写入配置，<b>不提供任何保密性</b>；
 * 真正的保密性来自 {@code CONFIG_MASTER_KEY} + AES-256-GCM。切勿把"仅经 Base64 编码"的明文当密文下发
 * （那等于明文，任何人都能解开）。必须用本类的 {@link #encrypt(String)} 生成密文，例如 CLI：
 * {@code java ...ConfigCipher encrypt "<明文>"}。
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

    /** 用户目录下的主密钥文件名（内容为 64 位十六进制，32 字节）；跨操作系统由 {@code user.home} 定位，路径固定。 */
    private static final String MASTER_KEY_FILE_NAME = ".dbb_automation_master_key";

    private ConfigCipher() {
    }

    /** 判断给定值是否为本工具加密产物（{@code ENC(...)}）。 */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX);
    }

    /**
     * 判断原始配置值是否"看起来像密文"：合法 base64 且解码后长度超过 IV，
     * 用于透明解密时避免对每个普通明文值都尝试解密。兼容 {@code ENC(...)} 包裹与裸 base64 两种形态。
     *
     * @param raw 原始配置值
     * @return 若为密文形态则为 {@code true}
     */
    public static boolean looksLikeCiphertext(String raw) {
        if (raw == null) {
            return false;
        }
        String inner = isEncrypted(raw)
                ? raw.substring(ENC_PREFIX.length(), raw.length() - ENC_SUFFIX.length())
                : raw;
        try {
            return Base64.getDecoder().decode(inner).length > IV_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
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
        if (isBlank(hex)) {
            hex = System.getProperty(PROP_MASTER_KEY);
        }
        if (isBlank(hex)) {
            hex = readMasterKeyFromFile();
        }
        if (isBlank(hex)) {
            throw new IllegalStateException(
                    "Master key missing. Provide it via environment variable CONFIG_MASTER_KEY (64 hex chars = 32 "
                            + "bytes, e.g. `openssl rand -hex 32`), system property config.master.key, or the key "
                            + "file " + resolveKeyFilePath() + " (content: 64 hex chars). Required to "
                            + "encrypt/decrypt config values.");
        }
        return hexToBytes(hex.trim());
    }

    /** 主密钥文件固定路径：{@code user.home}/.{@code MASTER_KEY_FILE_NAME}，跨操作系统由默认文件系统定位。 */
    private static String resolveKeyFilePath() {
        return Paths.get(System.getProperty("user.home", ""), MASTER_KEY_FILE_NAME).toString();
    }

    /** 从固定路径的密钥文件读取主密钥；文件不存在或不可读时静默返回 {@code null}，由上层统一报错。 */
    private static String readMasterKeyFromFile() {
        Path path = Paths.get(resolveKeyFilePath());
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException(
                    "Master key must be an even-length hex string (32 bytes = 64 hex chars)");
        }
        // 修复 CORE-P1-N3：强制 32 字节 = 64 hex，杜绝"弱密钥静默降级 AES-128"（合规红线）。
        if (hex.length() != 64) {
            throw new IllegalArgumentException(
                    "Master key must be exactly 64 hex chars (32 bytes) for AES-256-GCM; got "
                            + hex.length() + " hex chars (" + (hex.length() / 2) + " bytes). "
                            + "AES-128 is not allowed for compliance. Generate with `openssl rand -hex 32`.");
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
