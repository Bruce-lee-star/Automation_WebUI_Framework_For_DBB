package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配置敏感值透明解密入口。
 *
 * <p>任何配置读取方拿到字符串后调用 {@link #decryptIfNeeded(String)} 即可：
 * 若值为 {@code ENC(<base64>)} 显式标记，或裸 {@code <base64>} 密文（形似密文），
 * 则尝试用主密钥解密；其余原样返回。对既有明文配置零侵入。
 *
 * <p>解密失败（主密钥缺失 / 密文损坏）时<b>保留原串并告警</b>，绝不中断配置加载——
 * 避免单个 secret 配置错误拖垮整个框架启动。此时该值仍可能以 {@code ENC(...)} 形态
 * 被当作明文使用，调用方应在敏感场景下校验解码结果。
 */
public final class SecretValue {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretValue.class);

    private SecretValue() {
    }

    /**
     * 透明解密：{@code ENC(<base64>)} 显式标记或"形似密文"的裸 base64 均尝试解密；其余原样返回。
     *
     * <p>加密值两种写法皆可，皆由本方法透明还原：
     * <ul>
     *   <li>{@code ENC(<base64>)} —— 显式标记，始终尝试解密（解密失败告警并保留原串）；</li>
     *   <li>裸 {@code <base64>} 密文 —— 仅当 {@link ConfigCipher#looksLikeCiphertext(String)}
     *       判定为密文形态时尝试，避免对普通明文值试解密并刷错误日志。</li>
     * </ul>
     *
     * @param raw 原始配置值（可能为 {@code null}）
     * @return 解密后的值；非密文或解密失败时返回原值
     */
    public static String decryptIfNeeded(String raw) {
        if (raw == null) {
            return null;
        }
        if (ConfigCipher.isEncrypted(raw) || ConfigCipher.looksLikeCiphertext(raw)) {
            return tryDecrypt(raw);
        }
        return raw;
    }

    private static String tryDecrypt(String raw) {
        try {
            return ConfigCipher.decrypt(raw);
        } catch (Exception e) {
            LOGGER.error("配置值解密失败（主密钥可能缺失或密文损坏），保留原串：{}", e.getMessage());
            return raw;
        }
    }
}
