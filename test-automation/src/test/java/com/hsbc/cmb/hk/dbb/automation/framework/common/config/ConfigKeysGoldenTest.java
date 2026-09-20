package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigKeys} 收敛 <b>golden 快照</b>测试 —— 默认值不漂移的守卫。
 *
 * <p><b>为什么需要它</b>：配置体系收敛（Web / API / Monitor 三侧的字面量定义全部迁移进
 * {@code ConfigKeys} 注册表）属于大批量机械搬运，一旦搬运中写错某个默认值（少一个字符、
 * 改错数字），配置行为会被静默改变——这类缺陷在没有守卫时极难发现：编译通过、测试可能仍通过，
 * 只有当某条配置走到兜底分支时才暴露。
 *
 * <p>本测试以 {@code config-keys-golden.txt}（<b>迁移前</b>从 Web 枚举 / API 常量 / Monitor 常量
 * 逐键导出的 {@code key=default} 基线）为基准，断言注册表与基线<b>逐键、逐字一致</b>，且两侧
 * 集合完全相等（既不缺键，也不多出未登记键）。
 *
 * <p><b>{@code <adaptive>} 标记</b>：个别键的默认值依赖运行环境（如
 * {@link ConfigKeys#WEB_PLAYWRIGHT_CONCURRENT_MAX} 按 CPU 核数自适应），基线无法固定为常量，
 * 故以 {@code <adaptive>} 占位；此类键只断言「存在且默认值非空白」，不断言具体取值。
 *
 * <p><b>更新方式</b>：若确认要变更某键的默认值，必须<b>同时</b>修改 {@code ConfigKeys} 与该基线文件，
 * 使变更在评审中显式可见（而不是被这条测试静默放行）。
 */
class ConfigKeysGoldenTest {

    /** 迁移前基线的类路径位置（test-automation/src/test/resources）。 */
    private static final String GOLDEN_RESOURCE = "/config-keys-golden.txt";

    /** 运行期自适应默认值的占位标记。 */
    private static final String ADAPTIVE_MARKER = "<adaptive>";

    @Test
    void registryMatchesGoldenSnapshot() throws Exception {
        Map<String, String> golden = loadGolden();

        // 非空性断言：采集失效（读到空基线 / 空注册表）会让本测试静默通过——那是最危险的失败模式。
        assertFalse(golden.isEmpty(), "golden 基线不应为空（资源未被打包会静默放行本测试）");
        assertTrue(ConfigKeys.values().length > 100,
                "注册表条目数异常偏少（实际 " + ConfigKeys.values().length + "），疑似采集/迁移不完整");

        Map<String, String> current = new LinkedHashMap<>();
        for (ConfigKeys c : ConfigKeys.values()) {
            current.put(c.key(), c.defaultValue());
        }

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : golden.entrySet()) {
            String key = e.getKey();
            String expected = e.getValue();
            String actual = current.get(key);
            if (actual == null) {
                problems.add("  - 注册表缺失键: '" + key + "'（基线默认=" + expected + "）");
                continue;
            }
            if (ADAPTIVE_MARKER.equals(expected)) {
                if (actual.isBlank()) {
                    problems.add("  - 自适应键默认值不应为空白: '" + key + "'");
                }
            } else if (!expected.equals(actual)) {
                problems.add("  - 默认值漂移: '" + key + "' 基线=" + expected + "，注册表=" + actual);
            }
        }
        for (String key : current.keySet()) {
            if (!golden.containsKey(key)) {
                problems.add("  - 注册表多出未登记键: '" + key + "'（请同步更新 golden 基线）");
            }
        }

        assertTrue(problems.isEmpty(),
                "ConfigKeys 注册表必须与迁移前基线逐键、逐字一致（收敛的机械搬运不得改变任何配置行为）：\n"
                        + String.join("\n", problems));

        assertEquals(golden.size(), current.size(), "注册表键数应与基线一致");
    }

    /** 读取 {@code key=default} 基线（按首个 '=' 切分，默认值本身可含 '='）。 */
    private static Map<String, String> loadGolden() throws Exception {
        Map<String, String> golden = new LinkedHashMap<>();
        try (InputStream in = ConfigKeysGoldenTest.class.getResourceAsStream(GOLDEN_RESOURCE)) {
            assertNotNull(in, "未找到 golden 基线资源: " + GOLDEN_RESOURCE);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int idx = trimmed.indexOf('=');
                    assertTrue(idx > 0, "基线行格式应为 key=default: " + trimmed);
                    golden.put(trimmed.substring(0, idx), trimmed.substring(idx + 1));
                }
            }
        }
        return golden;
    }
}
