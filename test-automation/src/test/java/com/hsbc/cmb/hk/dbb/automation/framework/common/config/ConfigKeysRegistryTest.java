package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigKeys} 统一配置键注册表契约测试（C-6 / M-2）。
 *
 * <p>固化：① 注册表非空；② 全部键唯一（与类加载期 fail-fast 静态校验互锁）；
 * ③ 键名/默认值/描述均非空；④ 键名遵循结构性命名约定（见
 * {@link #keysFollowStructuralNamingConvention()}）。
 * 新增配置键时若重复或格式违规，本测试与 {@link ConfigKeys#validateUniqueKeys()} 均会暴露。
 */
class ConfigKeysRegistryTest {

    @Test
    void registryIsNonEmpty() {
        assertFalse(ConfigKeys.all().isEmpty(), "注册表不应为空");
    }

    @Test
    void allKeysAreUniqueAndWellFormed() {
        Set<String> seen = new HashSet<>();
        for (ConfigKeys c : ConfigKeys.all()) {
            String key = c.key();
            assertTrue(key != null && !key.isBlank(), "键名不可为空: " + c);
            assertTrue(seen.add(key), "注册表存在重复配置键: " + key);
            assertTrue(c.defaultValue() != null, "默认值不可为 null: " + key);
            assertTrue(c.description() != null && !c.description().isBlank(), "描述不可为空: " + key);
        }
    }

    /**
     * 键名结构性约定：以<b>小写字母</b>开头，仅由 {@code [A-Za-z0-9.-]} 组成（段间以 {@code .} 分隔）。
     *
     * <p><b>为什么不是「全小写点分」</b>：注册表收敛为全量键后（2026-09-20），实测真实键集中存在
     * 历史遗留的驼峰段与连字符，例如 {@code playwright.page.error.failOnError}、
     * {@code playwright.browser.chrome.executablePath}、{@code playwright.browser.slowMo}、
     * {@code http.ssl.relax-validation}、{@code http.connection.pool.max-total}、
     * {@code serenity.output-directory}。本测试此前只覆盖少量手工登记的键，故未暴露该事实。
     *
     * <p>这些键名是<b>配置契约</b>（用户 {@code serenity.properties} / {@code -D} 覆盖按字面匹配），
     * 重命名会静默丢弃既有用户配置，因此不修正键名，而是把断言校正为真实成立的结构性规则；
     * 新增键应尽量遵循全小写点分（{@code a-z0-9.}）。
     */
    @Test
    void keysFollowStructuralNamingConvention() {
        for (ConfigKeys c : ConfigKeys.all()) {
            assertTrue(c.key().matches("^[a-z][A-Za-z0-9.-]*$"),
                    "键名应以小写字母开头、仅含 [A-Za-z0-9.-] : " + c.key());
        }
    }
}
