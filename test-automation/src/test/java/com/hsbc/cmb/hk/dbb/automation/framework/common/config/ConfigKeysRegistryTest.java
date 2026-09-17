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
 * ③ 键名/默认值/描述均非空；④ 键名遵循小写点分约定（{@code a-z0-9.}）。
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

    @Test
    void keysFollowLowercaseDottedConvention() {
        for (ConfigKeys c : ConfigKeys.all()) {
            assertTrue(c.key().matches("^[a-z][a-z0-9.]*$"),
                    "键名应小写点分（a-z0-9.）: " + c.key());
        }
    }
}
