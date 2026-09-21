package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-1「生效值单一视图」门禁：
 * <ul>
 *   <li>视图必须覆盖<b>全部</b>登记键，且每键只有一个生效值 + 一个来源层（不存在"同一键多个值"）；</li>
 *   <li>系统属性覆盖必须<b>可见且带来源标注</b>（这是"同键三层不同值可被观察"的最小证据）；</li>
 *   <li>不得存在<b>环境变量名冲突</b>（两个不同键归一后同名 ⇒ 静默共享同一个值）。</li>
 * </ul>
 */
public class ConfigEffectiveViewTest {

    private static final String PROBE_KEY = ConfigKeys.ASYNC_CORE_THREADS.key();

    @AfterEach
    void tearDown() {
        System.clearProperty(PROBE_KEY);
    }

    @Test
    public void snapshotCoversEveryRegisteredKeyExactlyOnce() {
        Map<String, ConfigEffectiveView.Entry> view = ConfigEffectiveView.snapshot();

        assertTrue(ConfigKeys.values().length > 100,
                "注册表条目数异常偏少（实际 " + ConfigKeys.values().length + "），疑似采集失效");
        assertEquals(ConfigKeys.values().length, view.size(), "视图必须与注册表一一对应（键数相等）");
        for (ConfigKeys key : ConfigKeys.values()) {
            ConfigEffectiveView.Entry entry = view.get(key.key());
            assertNotNull(entry, "视图缺少登记键：" + key.key());
            assertNotNull(entry.source(), "每个键必须标注来源层：" + key.key());
            assertNotNull(entry.value(), "生效值不得为 null（未配置应回落注册表默认值）：" + key.key());
        }
    }

    @Test
    public void systemPropertyOverrideIsVisibleWithProvenance() {
        ConfigEffectiveView.Entry before = ConfigEffectiveView.entry(ConfigKeys.ASYNC_CORE_THREADS);
        assertEquals(ConfigKeys.ASYNC_CORE_THREADS.defaultValue(), before.value(),
                "前置：探测键未被系统属性覆盖时应等于注册表默认值");

        System.setProperty(PROBE_KEY, "7");

        ConfigEffectiveView.Entry after = ConfigEffectiveView.entry(ConfigKeys.ASYNC_CORE_THREADS);
        assertEquals(ConfigEffectiveView.Source.SYSTEM_PROPERTY, after.source(),
                "P2-1：键被系统属性覆盖时，来源层必须显式可查（否则\"同键三层不同值\"无从观察）");
        assertEquals("7", after.value(), "生效值应为系统属性值");
    }

    @Test
    public void registryDefaultIsUsedWhenNoOverridePresent() {
        ConfigEffectiveView.Entry entry = ConfigEffectiveView.entry(ConfigKeys.ASYNC_MAX_PENDING_TIMEOUTS);
        //  系统属性 / 环境变量都未设置时（若 CI 设了则来源会变，故按来源断言而非硬编码值）
        if (entry.source() == ConfigEffectiveView.Source.REGISTRY_DEFAULT) {
            assertEquals(ConfigKeys.ASYNC_MAX_PENDING_TIMEOUTS.defaultValue(), entry.value(),
                    "未覆盖时必须回落注册表默认值（SSoT）");
        }
    }

    @Test
    public void noEnvNameCollisionsAmongRegisteredKeys() {
        assertTrue(ConfigEffectiveView.envNameCollisions().isEmpty(),
                "存在环境变量名冲突：两个不同登记键归一后同名 ⇒ 会静默共享同一个 env 值：\n"
                        + String.join("\n", ConfigEffectiveView.envNameCollisions()));
    }

    @Test
    public void strictModeKeyIsPrefixedAndLegacyAliasIsRegistered() {
        assertTrue(ConfigKeys.SECURITY_SECRET_STRICT.key().startsWith(FrameworkFlags.PREFIX),
                "P2-1：框架自有开关必须统一 framework. 前缀，实际："
                        + ConfigKeys.SECURITY_SECRET_STRICT.key());
        assertFalse(ConfigKeys.LEGACY_SECURITY_SECRET_STRICT.key().startsWith(FrameworkFlags.PREFIX),
                "旧键本就是无前缀形态（作为兼容别名保留）");
        assertNotEquals(FrameworkFlags.toEnvKey(ConfigKeys.SECURITY_SECRET_STRICT.key()),
                FrameworkFlags.toEnvKey(ConfigKeys.LEGACY_SECURITY_SECRET_STRICT.key()),
                "新旧键必须映射到不同环境变量名，否则兼容解析会互相覆盖");
        //  ASYNC_* 亦已登记（此前只在 AsyncPool 内以字面量存在）
        assertNotNull(ConfigKeys.valueOf("ASYNC_CORE_THREADS"), "P2-1：ASYNC_* 必须登记进注册表");
    }
}
