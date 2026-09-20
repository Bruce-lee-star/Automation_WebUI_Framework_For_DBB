package com.hsbc.cmb.hk.dbb.automation.framework.config;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ApiFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigKey;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigKeys;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.MonitorConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *
 * <p><b>2026-09-20 收敛后模型变更</b>：{@link ConfigKeys} 由「镜像注册表骨架」升级为
 * <b>唯一事实来源（SSoT）</b>——Web 枚举 / API 常量 / Monitor 常量均已退化为引用注册表的<b>门面</b>，
 * 同一配置键在全框架只有一处定义。因此：
 * <ul>
 *   <li>删除了原「镜像默认值一致性」校验（镜像已不存在，该模型下该风险在结构上被消除）；
 *       默认值不漂移改由 {@code ConfigKeysGoldenTest} 的 golden 快照守卫；</li>
 *   <li>新增「门面必须逐字委托注册表」校验（{@link #facadesMustDelegateToRegistry()}），
 *       确保门面不会重新长出本地字面量定义。</li>
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

    /**
     * 配置键必须全局唯一（G-5：跨枚举/枚举内重复键会被静默吞掉——同一键出现两个默认值时，
     * 实际生效值取决于读取路径，属难以定位的配置缺陷）。
     *
     * <p>收敛后 Web/API 侧不再持有字面量（键定义集中在 {@code ConfigKeys}），但两侧仍是
     * 独立模块的公开配置门面，其携带的键集合仍需唯一——故本校验保留。
     */
    @Test
    public void configKeysAreGloballyUnique() throws Exception {
        Map<String, List<String>> owners = new LinkedHashMap<>();

        for (WebFrameworkConfig c : WebFrameworkConfig.values()) {
            owners.computeIfAbsent(c.configKey().key(), k -> new ArrayList<>())
                    .add("WebFrameworkConfig." + c.name());
        }
        for (Field f : ApiFrameworkConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == ConfigKey.class) {
                f.setAccessible(true);
                ConfigKey k = (ConfigKey) f.get(null);
                owners.computeIfAbsent(k.key(), x -> new ArrayList<>())
                        .add("ApiFrameworkConfig." + f.getName());
            }
        }

        // 非空性断言：唯一性校验若「采集到空集合」会静默通过——那是最危险的失败模式，故先锁死采集规模
        assertTrue(owners.size() > 50,
                "应采集到全部 Web/API 配置键（实际仅 " + owners.size() + " 个）：采集失效会让本校验静默通过");

        List<String> duplicates = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : owners.entrySet()) {
            if (e.getValue().size() > 1) {
                duplicates.add("  - '" + e.getKey() + "' 被多处声明: " + e.getValue());
            }
        }

        assertTrue(duplicates.isEmpty(),
                "配置键必须全局唯一（G-5：跨枚举/枚举内重复键会被静默吞掉——同一键出现两个默认值时，"
                        + "实际生效值取决于读取路径，属难以定位的配置缺陷）：\n" + String.join("\n", duplicates));
    }

    /**
     * 门面一致性（收敛后取代原「镜像默认值一致性」）：{@link ConfigKeys} 是配置键的<b>唯一事实来源</b>，
     * {@code WebFrameworkConfig} / {@code ApiFrameworkConfig} / {@code MonitorConfig} 是<b>门面</b>。
     *
     * <p>本测试按命名约定把每个门面常量映射回其注册表条目（Web → {@code WEB_<枚举名>}，
     * API → {@code API_<字段名>}，Monitor → {@code <字段名>}），并断言三元组<b>逐字一致</b>。
     * 若有人重新在门面里写回字面量（或改错 key / 默认值 / 描述），本测试立即失败——
     * 这正是收敛要长期守住的性质。
     */
    @Test
    public void facadesMustDelegateToRegistry() throws Exception {
        List<String> problems = new ArrayList<>();

        for (WebFrameworkConfig c : WebFrameworkConfig.values()) {
            ConfigKeys expected = lookup("WEB_" + c.name(), problems);
            if (expected == null) {
                continue;
            }
            assertTripleMatches(problems, "WebFrameworkConfig." + c.name(), expected, c.configKey());
        }

        for (Field f : ApiFrameworkConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == ConfigKey.class) {
                f.setAccessible(true);
                ConfigKeys expected = lookup("API_" + f.getName(), problems);
                if (expected == null) {
                    continue;
                }
                assertTripleMatches(problems, "ApiFrameworkConfig." + f.getName(), expected, (ConfigKey) f.get(null));
            }
        }

        for (Field f : MonitorConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == MonitorConfig.Key.class) {
                f.setAccessible(true);
                ConfigKeys expected = lookup(f.getName(), problems);
                if (expected == null) {
                    continue;
                }
                MonitorConfig.Key k = (MonitorConfig.Key) f.get(null);
                if (!expected.key().equals(k.key())) {
                    problems.add("  - MonitorConfig." + f.getName() + "：key 应为 '" + expected.key()
                            + "'，实际 '" + k.key() + "'");
                }
                if (!expected.defaultValue().equals(k.defaultValue())) {
                    problems.add("  - MonitorConfig." + f.getName() + "：默认值应为 '" + expected.defaultValue()
                            + "'，实际 '" + k.defaultValue() + "'");
                }
            }
        }

        assertTrue(problems.isEmpty(),
                "门面必须逐字委托注册表（不得重新持有本地定义；否则同一键再次出现两处定义）：\n"
                        + String.join("\n", problems));
    }

    /** 按名查找注册表条目；找不到则记入 problems 并返回 null。 */
    private static ConfigKeys lookup(String name, List<String> problems) {
        try {
            return ConfigKeys.valueOf(name);
        } catch (IllegalArgumentException e) {
            problems.add("  - 注册表缺少门面对应的条目: ConfigKeys." + name);
            return null;
        }
    }

    /** 断言注册表条目与门面三元组逐字一致。 */
    private static void assertTripleMatches(List<String> problems, String owner, ConfigKeys expected, ConfigKey actual) {
        if (!expected.key().equals(actual.key())) {
            problems.add("  - " + owner + "：key 应为 '" + expected.key() + "'，实际 '" + actual.key() + "'");
        }
        if (!expected.defaultValue().equals(actual.defaultValue())) {
            problems.add("  - " + owner + "：默认值应为 '" + expected.defaultValue()
                    + "'，实际 '" + actual.defaultValue() + "'");
        }
        if (!expected.description().equals(actual.description())) {
            problems.add("  - " + owner + "：描述应与注册表逐字一致");
        }
    }
}
