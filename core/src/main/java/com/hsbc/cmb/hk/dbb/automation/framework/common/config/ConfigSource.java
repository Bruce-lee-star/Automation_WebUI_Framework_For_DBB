package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SecretValue;
import net.thucydides.model.environment.SystemEnvironmentVariables;

/**
 * 极薄配置源工具——统一"按 key 解析 + ENC(...) 透明解密"，供 Web(Serenity) 与 API(Typesafe) 两域复用，
 * 避免各自重复实现解密 / 解析逻辑。
 *
 * <p>Web 域通过 {@link #resolve(String, String)} 走 Serenity 的 {@code SystemEnvironmentVariables}
 * （合并 serenity.conf / serenity.properties / 系统属性）并透明解密；
 * API 域走 Typesafe Config 自有解析，仅对字符串值调用 {@link #decrypt(String)} 复用同一解密能力。
 *
 * <p>解密严格限定为字符串值：凡 {@code ENC(<base64>)} 或裸 {@code <base64>} 密文经
 * {@link SecretValue#decryptIfNeeded(String)} 透明解密，非密文原样返回；解密失败
 * （主密钥缺失 / 密文损坏）保留原串，绝不中断配置加载。
 */
public final class ConfigSource {

    private ConfigSource() {
    }

    /**
     * Web/Serenity 域：按 key 从环境解析配置值，并对 {@code ENC(<base64>)} / 裸 base64 密文透明解密。
     *
     * @param key          配置键（如 {@code playwright.browser.type}）
     * @param defaultValue 键缺失时的兜底默认值
     * @return 解析并解密后的配置值
     */
    public static String resolve(String key, String defaultValue) {
        String raw = SystemEnvironmentVariables.currentEnvironmentVariables().getProperty(key, defaultValue);
        return SecretValue.decryptIfNeeded(raw);
    }

    /**
     * 对任意原始配置值做透明解密（{@code ENC(<base64>)} 或裸 base64 密文）；Web/API 两域通用。
     *
     * @param raw 原始配置值（可能为密文）
     * @return 解密后的值；非密文或解密失败时原样返回
     */
    public static String decrypt(String raw) {
        return SecretValue.decryptIfNeeded(raw);
    }
}
