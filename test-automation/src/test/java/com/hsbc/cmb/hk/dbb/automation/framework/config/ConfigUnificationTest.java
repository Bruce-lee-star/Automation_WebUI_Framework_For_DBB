package com.hsbc.cmb.hk.dbb.automation.framework.config;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ApiFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigKey;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WEB-P1-7「配置体系收敛」验收固化：
 * <ul>
 *   <li>验收① 无同名类：{@code WebFrameworkConfig} 与 {@code ApiFrameworkConfig} 类名互异；</li>
 *   <li>验收② 两侧配置项均有 key / 默认 / 描述 三元组：
 *       Web 侧经枚举 {@link WebFrameworkConfig#configKey()} 暴露，
 *       API 侧经 {@link ApiFrameworkConfig} 的 {@link ConfigKey} 静态常量暴露。</li>
 * </ul>
 */
public class ConfigUnificationTest {

    @Test
    public void noSameNamedFrameworkConfigClass() {
        assertNotEquals("WEB-P1-7 验收①：两侧配置类名不得相同",
                WebFrameworkConfig.class.getName(), ApiFrameworkConfig.class.getName());
        assertFalse(
                WebFrameworkConfig.class.getSimpleName().equals(ApiFrameworkConfig.class.getSimpleName()), "WEB-P1-7 验收①：简单类名不得相同");
    }

    @Test
    public void webConfigKeysCarryTriples() {
        assertTrue( WebFrameworkConfig.values().length > 0, "Web 侧配置项不应为空");
        for (WebFrameworkConfig c : WebFrameworkConfig.values()) {
            ConfigKey k = c.configKey();
            assertNotNull( k, "configKey 不得为 null：" + c.name());
            assertFalse( k.key().isBlank(), "key 不得为空：" + c.name());
            assertNotNull( k.defaultValue(), "defaultValue 不得为 null：" + c.name());
            assertFalse( k.description().isBlank(), "description 不得为空：" + c.name());
        }
    }

    @Test
    public void apiConfigKeysCarryTriples() throws Exception {
        List<ConfigKey> keys = new ArrayList<>();
        for (Field f : ApiFrameworkConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == ConfigKey.class) {
                f.setAccessible(true);
                keys.add((ConfigKey) f.get(null));
            }
        }
        assertFalse( keys.isEmpty(), "API 侧应声明 ConfigKey 三元组常量");
        for (ConfigKey k : keys) {
            assertFalse( k.key().isBlank(), "key 不得为空：" + k);
            assertNotNull( k.defaultValue(), "defaultValue 不得为 null：" + k.key());
            assertFalse( k.description().isBlank(), "description 不得为空：" + k.key());
        }
    }
}
