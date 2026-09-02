package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配置敏感值透明解密入口。
 *
 * <p>任何配置读取方拿到字符串后调用 {@link #decryptIfNeeded(String)} 即可：
 * 若值为 {@code ENC(...)} 则尝试解密；否则原样返回。对既有明文配置零侵入。
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
     * 透明解密：{@code ENC(...)} 形式解密，其余原样返回。
     *
     * @param raw 原始配置值（可能为 {@code null}）
     * @return 解密后的值；非加密值或解密失败时返回原值
     */
    public static String decryptIfNeeded(String raw) {
        if (raw == null || !ConfigCipher.isEncrypted(raw)) {
            return raw;
        }
        try {
            return ConfigCipher.decrypt(raw);
        } catch (Exception e) {
            LOGGER.error("配置值解密失败（主密钥可能缺失或密文损坏），保留 ENC(...) 原串：{}", e.getMessage());
            return raw;
        }
    }
}
