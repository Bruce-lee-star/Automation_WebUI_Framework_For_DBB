package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FrameworkFlags} 契约测试。
 *
 * <p>重点锁定两个已修复的坑（防回退）：
 * <ol>
 *   <li><b>确定性</b>：开关 set → clear 后必须回到默认值。
 *       这正是此前 {@code ConfigSource} 经 Serenity SPI 源<b>缓存属性快照</b>导致的非确定行为
 *       （clear 之后仍读到旧值 "true"）；</li>
 *   <li><b>优先级</b>：系统属性 &gt; 环境变量 &gt; 默认值。</li>
 * </ol>
 */
public class FrameworkFlagsTest {

    private static final String KEY = "framework.flags.test.key";
    private static final String BOOL_KEY = "framework.flags.test.enabled";

    @AfterEach
    public void tearDown() {
        System.clearProperty(KEY);
        System.clearProperty(BOOL_KEY);
    }

    /** 未配置时回落到默认值。 */
    @Test
    public void fallsBackToDefaultWhenUnset() {
        assertEquals("fallback", FrameworkFlags.resolve(KEY, "fallback"));
        assertTrue(FrameworkFlags.isEnabled(BOOL_KEY, true), "fallback");
        assertFalse(FrameworkFlags.isEnabled(BOOL_KEY, false));
    }

    /** 系统属性优先。 */
    @Test
    public void systemPropertyWins() {
        System.setProperty(KEY, "from-sys");
        assertEquals("from-sys", FrameworkFlags.resolve(KEY, "fallback"));
    }

    /** 布尔开关：系统属性 "true" 生效。 */
    @Test
    public void booleanFlagFromSystemProperty() {
        System.setProperty(BOOL_KEY, "true");
        assertTrue(FrameworkFlags.isEnabled(BOOL_KEY, false));
        System.setProperty(BOOL_KEY, "false");
        assertFalse(FrameworkFlags.isEnabled(BOOL_KEY, true));
    }

    /**
     * 【回归防护】开关 set → clear 后必须恢复默认。
     * <p>此前经 {@code ConfigSource} 解析时，Serenity SPI 源会保留属性快照，
     * clear 之后仍读到旧值 "true"，使"默认关闭"的安全开关被意外打开。
     */
    @Test
    public void clearingPropertyRestoresDefaultDeterministically() {
        assertFalse(FrameworkFlags.isEnabled(BOOL_KEY, false), "初始应为默认关闭");

        System.setProperty(BOOL_KEY, "true");
        assertTrue(FrameworkFlags.isEnabled(BOOL_KEY, false), "设置后应开启");

        System.clearProperty(BOOL_KEY);
        assertFalse(FrameworkFlags.isEnabled(BOOL_KEY, false), "clear 后必须回到默认（SPI 缓存回归防护）");
    }

    /** 空白值视为未配置，回落到默认。 */
    @Test
    public void blankValueIsTreatedAsUnset() {
        System.setProperty(KEY, "   ");
        assertEquals("fallback", FrameworkFlags.resolve(KEY, "fallback"));
    }

    /** 环境变量名映射：大写 + 点/连字符转下划线。 */
    @Test
    public void envKeyMapping() {
        assertEquals("FRAMEWORK_FLAGS_TEST_KEY", FrameworkFlags.toEnvKey(KEY));
    }
}
