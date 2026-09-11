package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SecretValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;

/**
 * 极薄配置源工具——统一"按 key 解析 + ENC(...) 透明解密"，供 Web(Serenity) 与 API(Typesafe) 两域复用，
 * 避免各自重复实现解密 / 解析逻辑。
 *
 * <p>解析优先级（CORE-P0-1：core 不再耦合任何配置框架）：
 * <ol>
 *   <li>实时系统属性（最高优先级）：支持运行时动态覆盖 / 测试 toggle，且保证
 *       {@code WebFrameworkConfig.setValue(key, v)}（本质是 System.setProperty）即时生效，
 *       不受任何框架启动期快照影响；</li>
 *   <li>环境变量：点/连字符转下划线 + 大写（与 Serenity 的 ENV 映射一致），补齐"仅直读
 *       System.getProperty 会漏掉 ENV"的缺口，使所有 WebFrameworkConfig / ApiFrameworkConfig 均支持 ENV 覆盖；</li>
 *   <li>SPI 扩展配置源：由上层模块（web）通过 JDK {@link ServiceLoader} 提供
 *       {@link ConfigResolver} 实现（如 Serenity 的 serenity.conf/private 合并源）。
 *       core 在编译期与运行期均不依赖 Serenity；无上层实现时该来源为空，自然降级；</li>
 *   <li>默认值。</li>
 * </ol>
 *
 * <p>API 域走 Typesafe Config 自有解析，仅对字符串值复用 {@link #decrypt(String)} 同一解密能力。</p>
 *
 * <p>解密严格限定为字符串值：凡 {@code ENC(<base64>)} 或裸 {@code <base64>} 密文经
 * {@link SecretValue#decryptIfNeeded(String)} 透明解密，非密文原样返回；解密失败
 * （主密钥缺失 / 密文损坏）保留原串，绝不中断配置加载。</p>
 */
public final class ConfigSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigSource.class);

    private ConfigSource() {
    }

    /** SPI 发现的配置解析器缓存（懒加载、线程安全；缺实现/异常时降级为空列表）。 */
    private static volatile List<ConfigResolver> resolvers;

    private static List<ConfigResolver> resolvers() {
        if (resolvers == null) {
            synchronized (ConfigSource.class) {
                if (resolvers == null) {
                    List<ConfigResolver> list = new ArrayList<>();
                    try {
                        for (ConfigResolver r : ServiceLoader.load(ConfigResolver.class)) {
                            list.add(r);
                        }
                    } catch (Throwable e) {
                        // SPI 不可用不影响内置解析（系统属性 / 环境变量 / 默认值），但不得静默（D7-3）
                        LOGGER.debug("[ConfigSource] ConfigResolver SPI unavailable, "
                                + "fallback to builtin sources: {}", e.toString());
                    }
                    resolvers = list;
                }
            }
        }
        return resolvers;
    }

    /**
     * 按 key 解析配置值，并对 {@code ENC(<base64>)} / 裸 base64 密文透明解密。
     *
     * @param key          配置键（如 {@code playwright.browser.type}）
     * @param defaultValue 键缺失时的兜底默认值
     * @return 解析并解密后的配置值
     */
    public static String resolve(String key, String defaultValue) {
        // 1) 实时系统属性（最高优先级）：支持运行时动态覆盖 / 测试 toggle，
        //    且保证 FrameworkConfig.setValue(key, v)（本质是 System.setProperty）即时生效。
        String fromSys = System.getProperty(key);
        if (fromSys != null) {
            return SecretValue.decryptIfNeeded(fromSys);
        }
        // 2) 环境变量：与 Serenity 的 ENV 映射一致（点/连字符转下划线 + 大写），
        //    补齐"仅直读 System.getProperty 会漏掉 ENV"的缺口，使所有 WebFrameworkConfig / ApiFrameworkConfig 均支持 ENV 覆盖。
        String fromEnv = System.getenv(toEnvKey(key));
        if (fromEnv != null) {
            return SecretValue.decryptIfNeeded(fromEnv);
        }
        // 3) SPI 扩展配置源（web 提供 Serenity 合并源；缺实现时为空，降级到默认值）。
        for (ConfigResolver r : resolvers()) {
            String fromResolver = r.resolve(key);
            if (fromResolver != null) {
                return SecretValue.decryptIfNeeded(fromResolver);
            }
        }
        // 4) 默认值。
        return SecretValue.decryptIfNeeded(defaultValue);
    }

    /**
     * 配置键 → 环境变量名：大写，并将 {@code .} 与 {@code -} 规范为 {@code _}
     * （与 Serenity 的 ENV 映射保持一致）。
     * 例：{@code serenity.playwright.shared.browser.enabled} → {@code SERENITY_PLAYWRIGHT_SHARED_BROWSER_ENABLED}。
     */
    static String toEnvKey(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
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
