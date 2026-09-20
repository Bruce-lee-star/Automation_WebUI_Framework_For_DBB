package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java↔JS「拾取模式串」契约测试（固化评审 F-06 修复）。
 *
 * <p><b>背景</b>：{@link RolePickerConstants#MODE_SCAN_PAGE} 等常量是 Java 权威下发给
 * 浏览器侧 {@code window.__roleMode} 的字符串；面板脚本（{@code panel-core-a.js} /
 * {@code picker-core-b2.js}）以 {@code mode === '...'} 直接比对。两侧取值一旦错位
 * （曾为 Java {@code scan_page} vs JS {@code scanPage}），扫描态判定恒不成立 →
 * 扫描按钮态/提示失效、{@code focusin} 键盘可达拾取入口永久 early-return，且<b>不报错</b>。</p>
 *
 * <p>本测试以「实读 JS 资源字面量」的方式守护该契约：只要有人单方面改 Java 常量或 JS 比对串，
 * 立即在护盾内失败，而不再依赖人工比对 85 个脚本。与 {@code RolePickerConstantsTest}
 * 同包（{@code framework.web.page.scan}），故可访问包级私有的 {@code RolePickerConstants}。</p>
 *
 * @apiNote 测试专用；不启动浏览器。
 */
public class RolePickerModeContractTest {

    /** 参与模式串比对的浏览器侧脚本（相对 classpath，来自 framework-core 的 scan/js 资源）。 */
    private static final List<String> BROWSER_SCRIPTS = List.of(
            "scan/js/panel-core-a.js",
            "scan/js/picker-core-b2.js");

    private static String readScript(String resource) throws Exception {
        try (InputStream in = RolePickerModeContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "classpath 缺少浏览器脚本资源: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 每个 Java 模式常量都必须以「带引号字面量」原样出现在浏览器脚本中 —— 这是两侧能对上的充要条件。
     */
    @Test
    public void everyJavaModeConstantAppearsVerbatimInBrowserScripts() throws Exception {
        StringBuilder all = new StringBuilder();
        for (String s : BROWSER_SCRIPTS) {
            all.append(readScript(s)).append('\n');
        }
        String combined = all.toString();

        List<String> modes = List.of(
                RolePickerConstants.MODE_IDLE,
                RolePickerConstants.MODE_MANUAL,
                RolePickerConstants.MODE_SCAN_PAGE,
                RolePickerConstants.MODE_SCAN_REGION);
        for (String mode : modes) {
            assertTrue(combined.contains("'" + mode + "'") || combined.contains("\"" + mode + "\""),
                    "模式串 '" + mode + "' 未在浏览器脚本中原样出现 → Java 下发的 __roleMode 与 JS 比对值错位，"
                            + "该模式判定将恒不成立且静默失效");
        }
    }

    /**
     * 锁定扫描模式的「驼峰」取值（防止回归为下划线：JS 全链路使用 scanPage / scanRegion）。
     */
    @Test
    public void scanModesUseCamelCaseContractValues() {
        assertEquals("scanPage", RolePickerConstants.MODE_SCAN_PAGE,
                "MODE_SCAN_PAGE 必须与 panel-core-a.js / picker-core-b2.js 的 'scanPage' 一致");
        assertEquals("scanRegion", RolePickerConstants.MODE_SCAN_REGION,
                "MODE_SCAN_REGION 必须与 panel-core-a.js / picker-core-b2.js 的 'scanRegion' 一致");
    }
}
