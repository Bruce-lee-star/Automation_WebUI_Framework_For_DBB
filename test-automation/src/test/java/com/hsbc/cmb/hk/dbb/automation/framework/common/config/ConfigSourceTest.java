package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import com.hsbc.cmb.hk.dbb.automation.framework.common.security.ConfigCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ConfigSource#resolve(String, String)} 解析链固化（2026-09-07）：
 * ① 无覆盖时回退默认值；② 运行时 {@code System.setProperty} 实时优先于默认值（与 {@code FrameworkConfig.setValue} 语义一致，
 * 且不受 Serenity 启动期快照影响）；③ 明文值经解析链原样返回（解密由 {@code SecretValue} 负责，不破坏明文）。
 * 环境变量分支（{@code System.getenv(toEnvKey(key))}）无法在 JVM 内动态设置，其映射由 {@code toEnvKey} 与 Serenity 对齐，
 * 由既有 Serenity ENV 解析 + 本分支共同保证所有 {@code FrameworkConfig} 均可被环境变量覆盖。
 */
public class ConfigSourceTest {

    private static final String KEY = "config.source.test.live.override";

    private static final String TEST_MASTER_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @AfterEach
    public void cleanup() {
        System.clearProperty(KEY);
    }

    @Test
    public void resolvesDefaultWhenNoOverride() {
        assertEquals("fallback", ConfigSource.resolve(KEY, "fallback"));
    }

    @Test
    public void liveSystemPropertyOverridesDefault() {
        System.setProperty(KEY, "runtime-value");
        try {
            assertEquals("runtime-value", ConfigSource.resolve(KEY, "fallback"));
        } finally {
            System.clearProperty(KEY);
        }
    }

    @Test
    public void plainValuePassedThroughUnchanged() {
        assertEquals("plain", ConfigSource.resolve("config.source.test.plain", "plain"));
    }

    /** C-9：兜底默认值不参与裸密文启发式 —— 即便它形如合法密文，也原样返回（不解密）。 */
    @Test
    public void bareCiphertextDefaultIsNotDecrypted() {
        String saved = System.getProperty("config.master.key");
        System.setProperty("config.master.key", TEST_MASTER_KEY);
        try {
            String enc = ConfigCipher.encrypt("secret-default");
            String bare = enc.substring("ENC(".length(), enc.length() - 1);
            assertEquals(bare, ConfigSource.resolve("config.source.test.bare.default", bare),
                    "C-9：兜底默认值应原样返回，不尝试裸密文解密");
        } finally {
            if (saved == null) {
                System.clearProperty("config.master.key");
            } else {
                System.setProperty("config.master.key", saved);
            }
        }
    }

    /** C-9：显式 ENC(...) 标记的默认值仍解密。 */
    @Test
    public void encMarkedDefaultIsStillDecrypted() {
        String saved = System.getProperty("config.master.key");
        System.setProperty("config.master.key", TEST_MASTER_KEY);
        try {
            String enc = ConfigCipher.encrypt("secret-default");
            assertEquals("secret-default", ConfigSource.resolve("config.source.test.enc.default", enc));
        } finally {
            if (saved == null) {
                System.clearProperty("config.master.key");
            } else {
                System.setProperty("config.master.key", saved);
            }
        }
    }
}
