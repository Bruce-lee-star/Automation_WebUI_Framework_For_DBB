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
     * G-5 的**替代落地（不拆分文件）**：把该条目陈述的真实危害——「新增配置键无校验、键名冲突被静默吞掉」——
     * 变成可执行校验：Web 侧枚举与 API 侧常量之间、以及各自内部，**配置键必须全局唯一**。
     *
     * <p>为什么「不拆分」是正确决策：
     * <ol>
     *   <li><b>Java 枚举无法跨文件拆分</b>——常量必须与其 {@code enum} 同文件。拆分意味着先退化为
     *       {@code class + static ConfigKey} 常量，将失去 {@code values()} 遍历能力；</li>
     *   <li>而 {@code WebFrameworkConfig.allConfigKeys()} 正依赖 {@code values()}，是「web 侧唯一枚举点」的
     *       现状价值所在。拆成多个子枚举后必须人工维护一个聚合清单——<b>恰好重新引入本条目要消除的
     *       「忘记登记」失败模式</b>；</li>
     *   <li>1690 行中约 1570 行是 <b>132 个声明式键</b>（21 个主题分区，每键约 11 行），仅约 110 行是行为
     *       （11 个访问器 + 1 个默认值解析）。长度是数据量的映射，不是职责混杂；换哪个文件都是这么多行。</li>
     * </ol>
     *
     * <p><b>不在本校验范围内的</b>：{@code ConfigKeys} 是「注册表骨架」，其键<b>刻意</b>与 Web/API 侧镜像
     * （用于审计与文档聚合，见其类注释），故重复是设计意图；跨枚举冲突才是真实风险。
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
     * 镜像一致性（G-5 / C-6 收口）：{@code MonitorConfig} 与 {@code ConfigKeys} 是 Web/API 侧配置的
     * <b>镜像</b>——{@code MonitorConfig} 的类注释明言「配置键名与默认值与 FrameworkConfig 中对应枚举
     * <b>逐字一致</b>」，{@code ConfigKeys} 则自述为汇总元数据的注册表骨架。
     *
     * <p>镜像<b>允许</b>同键重复（这是设计意图，故 {@link #configKeysAreGloballyUnique} 只校验 Web×API），
     * 但默认值<b>必须逐字一致</b>：否则同一配置键会存在两个默认值，查审计/生成文档的人被误导，
     * 而实际生效值取决于读取路径。本条正是实测缺陷的固化——`serenity.screenshot.strategy` 在
     * 注册表里写的是 {@code AFTER_FAILING_STEP}，而真实枚举是 {@code AFTER_EACH_STEP}。
     */
    @Test
    public void mirrorConfigKeysMustAgreeOnDefaults() throws Exception {
        Map<String, String> owners = new LinkedHashMap<>();
        for (WebFrameworkConfig c : WebFrameworkConfig.values()) {
            owners.putIfAbsent(c.configKey().key(), c.configKey().defaultValue());
        }
        for (Field f : ApiFrameworkConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == ConfigKey.class) {
                f.setAccessible(true);
                ConfigKey k = (ConfigKey) f.get(null);
                owners.putIfAbsent(k.key(), k.defaultValue());
            }
        }

        List<String> mismatches = new ArrayList<>();
        for (Field f : MonitorConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == MonitorConfig.Key.class) {
                f.setAccessible(true);
                MonitorConfig.Key k = (MonitorConfig.Key) f.get(null);
                checkMirrorDefault(owners, "MonitorConfig." + f.getName(), k.key(), k.defaultValue(), mismatches);
            }
        }
        for (ConfigKeys c : ConfigKeys.values()) {
            checkMirrorDefault(owners, "ConfigKeys." + c.name(), c.key(), c.defaultValue(), mismatches);
        }

        assertTrue(mismatches.isEmpty(),
                "镜像注册表的默认值必须与真实配置枚举逐字一致（否则同一键存在两个默认值，"
                        + "实际生效值取决于读取路径）：\n" + String.join("\n", mismatches));
    }

    /** 若该键在真实枚举中存在，则默认值必须一致；键不存在于真实枚举时不做判断（键可能属其他域）。 */
    private static void checkMirrorDefault(Map<String, String> owners, String mirrorOwner, String key,
                                          String mirrorDefault, List<String> mismatches) {
        String actual = owners.get(key);
        if (actual != null && !actual.equals(mirrorDefault)) {
            mismatches.add("  - '" + key + "'：" + mirrorOwner + " 默认=" + mirrorDefault
                    + "，真实枚举默认=" + actual);
        }
    }
}
