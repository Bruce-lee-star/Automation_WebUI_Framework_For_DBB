package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配置敏感值透明解密入口。
 *
 * <p>任何配置读取方拿到字符串后调用 {@link #decryptIfNeeded(String)} 即可：
 * 密文两种写法都支持 —— {@code ENC(<base64>)} 显式标记，或裸 {@code <base64>}（不加 {@code ENC} 包裹）。两者均会用主密钥透明解密。
 *
 * <p><b>双格式支持（用户需求）</b>：配置文件里的密文无论是否加 {@code ENC(...)} 包裹都
 * <b>默认解密</b>，无需任何开关。裸 {@code <base64>} 经 {@link ConfigCipher#looksLikeCiphertext}
 * 启发式识别（合法 base64 且长度超 IV），命中即尝试解密。
 *
 * <p><b>行为边界（两种写法都绑定本地主密钥）</b>：{@code ENC(...)} 与裸 {@code <base64>} 密文均用
 * 本地主密钥做 GCM 认证解密，<b>key 变则旧密文一律解不开</b>：
 * <ul>
 *   <li><b>失败快（默认）</b>：只要值是密文形态，解密失败（主密钥缺失 / key 与密文不匹配 / 密文被篡改）
 *       就抛 {@link IllegalStateException}，启动即失败，逼出配置错误（C-3，默认
 *       {@code -Dsecurity.secret.strict=true}）；</li>
 *   <li><b>仅非密文形态回退</b>：值<b>明显不是密文</b>（非合法 base64，或长度不足以含 IV + GCM 标签）
 *       时按明文原样返回，不影响普通配置值。</li>
 * </ul>
 * 如需关闭裸密文启发式（仅 {@code ENC(...)} 才解密），显式设
 * {@code framework.secret.allow-bare-base64=false}；如需排障降级（解密失败仅告警保留原串，勿用于生产），
 * 设 {@code -Dsecurity.secret.strict=false}。
 */
public final class SecretValue {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretValue.class);

    /**
     * 是否把"裸 base64"（不加 {@code ENC(...)} 包裹）当密文尝试解密。
     * <p>默认 <b>true</b>：裸密文与 {@code ENC(...)} 一并默认解密（用户需求：双格式都支持）。
     * 设 {@code false} 可关闭裸密文启发式（仅 {@code ENC(...)} 标记才解密），作为防御性收窄。
     * 经 {@link com.hsbc.cmb.hk.dbb.automation.framework.common.config.FrameworkFlags} 解析，
     * 保证开关行为确定（不走会缓存快照的 SPI 合并源）。
     */
    public static final String ALLOW_BARE_BASE64_KEY = "framework.secret.allow-bare-base64";

    private SecretValue() {
    }

    /**
     * 透明解密：{@code ENC(<base64>)} 与裸 {@code <base64>} 两种写法都解密，且都绑定本地主密钥。
     *
     * <p>加密值两种写法：
     * <ul>
     *   <li>{@code ENC(<base64>)} —— 显式标记，始终尝试解密；解密失败一律失败快（见下）；</li>
     *   <li>裸 {@code <base64>} —— 默认经 {@link ConfigCipher#looksLikeCiphertext(String)} 启发式识别
     *       （合法 base64 且长度超 IV），命中即尝试解密；显式设
     *       {@code framework.secret.allow-bare-base64=false} 可关闭；</li>
     *   <li>其余（明显非密文形态）—— 原样返回。</li>
     * </ul>
     *
     * <p>解密失败处理：<b>主密钥缺失 / GCM 认证失败（key 不匹配或密文被篡改）</b>视为配置错误，
     * 默认抛 {@link IllegalStateException}（失败快，C-3）；仅 {@code -Dsecurity.secret.strict=false}
     * 时降级为告警并保留原串（排障用）。<b>仅非密文形态</b>（base64 解码失败 / 长度过短）才原样返回。
     *
     * @param raw 原始配置值（可能为 {@code null}）
     * @return 解密后的值；明显非密文形态时返回原值
     * @throws IllegalStateException 密文形态但解密失败且处于严格模式（默认）
     */
    public static String decryptIfNeeded(String raw) {
        if (raw == null) {
            return null;
        }
        if (ConfigCipher.isEncrypted(raw)) {
            return tryDecrypt(raw, false); // 显式标记：严格失败快（C-3）
        }
        if (ConfigCipher.looksLikeCiphertext(raw)
                && com.hsbc.cmb.hk.dbb.automation.framework.common.config.FrameworkFlags
                        .isEnabled(ALLOW_BARE_BASE64_KEY, true)) {
            return tryDecrypt(raw, true); // 裸密文：默认支持；解密失败同样失败快
        }
        return raw;
    }

    /**
     * 调用 {@link ConfigCipher#decrypt} 解密，按异常类别决定"回退原串"还是"失败快"。
     *
     * @param bareForm 是否为裸 base64 路径。为 {@code true} 且值被判定为<b>非密文形态</b>
     *                 （{@link IllegalArgumentException}）时按明文回退原串；其余解密失败
     *                 （{@link IllegalStateException}：主密钥缺失 / key 不匹配 / 密文被篡改）
     *                 两种路径一律失败快。
     */
    private static String tryDecrypt(String raw, boolean bareForm) {
        try {
            return ConfigCipher.decrypt(raw);
        } catch (IllegalArgumentException e) {
            // 非密文形态（base64 解码失败 / 长度过短）。裸密文路径按明文回退；ENC(...) 标记则是配置错误。
            if (bareForm) {
                LOGGER.warn("Raw value is not ciphertext, treating as plaintext (keeping original): {}", e.getMessage());
                return raw;
            }
            return onDecryptFailure(raw, "配置值标记为 ENC(...) 但并非合法密文", e);
        } catch (IllegalStateException e) {
            // 主密钥缺失 / GCM 认证失败（key 与密文不匹配或密文被篡改）——一律失败快（严格模式）。
            return onDecryptFailure(raw, "配置值解密失败（主密钥缺失或与密文不匹配）", e);
        }
    }

    /**
     * 解密失败的统一出口：默认<b>严格失败快</b>（C-3，默认 {@code -Dsecurity.secret.strict=true}），
     * 抛 {@link IllegalStateException}；仅显式 {@code -Dsecurity.secret.strict=false} 时降级为告警并
     * 保留原串（排障用，勿用于生产）。
     */
    private static String onDecryptFailure(String raw, String message, Exception cause) {
        boolean strict = com.hsbc.cmb.hk.dbb.automation.framework.common.config.FrameworkFlags
                .isEnabled("security.secret.strict", true);
        if (strict) {
            throw new IllegalStateException(message + "，严禁以密文形态当值使用：" + cause.getMessage(), cause);
        }
        LOGGER.warn("{} (non-strict mode, keeping original): {}", message, cause.getMessage());
        return raw;
    }
}
