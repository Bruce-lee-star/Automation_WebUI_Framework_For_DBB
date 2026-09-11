package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配置敏感值透明解密入口。
 *
 * <p>任何配置读取方拿到字符串后调用 {@link #decryptIfNeeded(String)} 即可：
 * 若值为 {@code ENC(<base64>)} 显式标记则尝试用主密钥解密；其余原样返回。
 *
 * <p><b>D2-2 收紧</b>：裸 {@code <base64>} <b>不再默认当密文</b> —— 普通配置值
 * （{@code false}、{@code admin}、订单号等）极易被"形似密文"启发式误判，
 * 既在每次读取时刷解密失败日志，又存在把明文"解"坏的风险。
 * 确需裸密文时显式开启开关 {@code framework.secret.allow-bare-base64=true}。
 *
 * <p>解密失败（主密钥缺失 / 密文损坏）时<b>保留原串并告警</b>，绝不中断配置加载——
 * 避免单个 secret 配置错误拖垮整个框架启动。此时该值仍可能以 {@code ENC(...)} 形态
 * 被当作明文使用，调用方应在敏感场景下校验解码结果。
 */
public final class SecretValue {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretValue.class);

    /**
     * 是否把"裸 base64"当密文尝试解密（D2-2）。
     * <p>默认 <b>false</b>：只有 {@code ENC(...)} 显式标记才解密。
     * 经 {@link com.hsbc.cmb.hk.dbb.automation.framework.common.config.FrameworkFlags} 解析，
     * 保证开关行为确定（不走会缓存快照的 SPI 合并源）。
     */
    public static final String ALLOW_BARE_BASE64_KEY = "framework.secret.allow-bare-base64";

    private SecretValue() {
    }

    /**
     * 透明解密：<b>仅</b> {@code ENC(<base64>)} 显式标记始终尝试解密；
     * 裸 base64 需显式开关 {@link #ALLOW_BARE_BASE64_KEY} 开启；其余原样返回。
     *
     * <p>加密值两种写法：
     * <ul>
     *   <li>{@code ENC(<base64>)} —— <b>推荐</b>，显式标记，始终尝试解密（失败告警并保留原串）；</li>
     *   <li>裸 {@code <base64>} —— <b>默认不再识别</b>（D2-2），
     *       仅当开关开启且 {@link ConfigCipher#looksLikeCiphertext(String)} 判定为密文形态时尝试。</li>
     * </ul>
     *
     * @param raw 原始配置值（可能为 {@code null}）
     * @return 解密后的值；非密文或解密失败时返回原值
     */
    public static String decryptIfNeeded(String raw) {
        if (raw == null) {
            return null;
        }
        if (ConfigCipher.isEncrypted(raw)) {
            return tryDecrypt(raw);
        }
        if (ConfigCipher.looksLikeCiphertext(raw)
                && com.hsbc.cmb.hk.dbb.automation.framework.common.config.FrameworkFlags
                        .isEnabled(ALLOW_BARE_BASE64_KEY, false)) {
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
