package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * CG-P2-N12 回归：拾取脚本外置为 {@code scan/js/*.js} 资源后，构建期/测试期校验其 JS 语法。
 *
 * <p>原实现把约 18.5k 字符浏览器 JS 内联在 Java 字符串常量里（{@code RolePickerScripts} /
 * {@code RolePickerScriptInjector}），转录极易引入语法错误却无 headless 覆盖。现所有脚本来自资源文件，
 * 本测试对每个常量（含按 context 组合的 {@code START_SCRIPT} / {@code merge-key-shim} 片段）与门控模板
 * （{@code gate-init.js} 经占位符替换后的运行时字符串）执行 {@code node --check}，确保注入到浏览器的
 * 脚本语法合法。作为 {@code mvn test} 一环运行，等价于构建期校验（另有 {@code core} 模块 validate 阶段的
 * {@code tools/validate_picker_js.js} 对原始资源文件做 V8 解析）。
 *
 * <p>若运行环境无 {@code node}，本测试整体跳过（不阻断无 node 的 CI），由 validate 阶段插件兜底。
 */
public class RolePickerScriptsJsValidationTest {

    private static final String NODE = "node";

    private boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder(NODE, "--version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void checkScript(String name, String js) throws Exception {
        Assume.assumeTrue("node not available - skipping JS syntax validation", nodeAvailable());
        assertTrue(name + " must be non-empty", js != null && !js.trim().isEmpty());
        File tmp = Files.createTempFile("picker-js-", ".js").toFile();
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), js.getBytes("UTF-8"));
        Process p = new ProcessBuilder(NODE, "--check", tmp.getAbsolutePath()).start();
        int exit = p.waitFor();
        String stderr = new String(p.getErrorStream().readAllBytes(), "UTF-8");
        assertEquals(name + " failed node --check:\n" + stderr, 0, exit);
    }

    /**
     * START_SCRIPT / PANEL_SCRIPT 在运行时经 {@code concat} 拼接为单一合法脚本，但其组成片段
     * （{@code *-core-a/b/b1/b2.js} 对应的 {@code START_SCRIPT_A/B1/B2}、{@code PANEL_SCRIPT_A/B}）各自是
     * 未闭合片段，单独 {@code node --check} 必失败。这些片段的正确性已由拼接后的 {@code START_SCRIPT} /
     * {@code PANEL_SCRIPT} 常量（同样在本循环内被校验）完整覆盖，故此处跳过单个片段，避免误报。
     */
    private static final java.util.Set<String> COMPOSED_PART_CONSTANTS = java.util.Set.of(
            "START_SCRIPT_A", "START_SCRIPT_B1", "START_SCRIPT_B2",
            "PANEL_SCRIPT_A", "PANEL_SCRIPT_B");

    @Test
    public void everyConstantIsValidJs() throws Exception {
        List<Field> fields = new ArrayList<>();
        for (Field f : RolePickerScripts.class.getDeclaredFields()) {
            if (f.getType() != String.class) {
                continue;
            }
            int m = f.getModifiers();
            if (Modifier.isStatic(m) && Modifier.isFinal(m)) {
                f.setAccessible(true);
                fields.add(f);
            }
        }
        Assume.assumeTrue("node not available - skipping JS syntax validation", nodeAvailable());
        assertTrue("expected numerous script constants", fields.size() >= 30);
        for (Field f : fields) {
            if (COMPOSED_PART_CONSTANTS.contains(f.getName())) {
                continue; // see javadoc above: validated via the composed START_SCRIPT / PANEL_SCRIPT
            }
            checkScript(f.getName(), (String) f.get(null));
        }
    }

    @Test
    public void gateInitTemplateComposesToValidJs() throws Exception {
        // 覆盖门控脚本的两个运行时分支：带 NLS + 非强制、无 NLS + 强制
        checkScript("gatedPickerInitScript(nls,false)", RolePickerScriptInjector.gatedPickerInitScript("{\"exact\":{}}", false));
        checkScript("gatedPickerInitScript(null,true)", RolePickerScriptInjector.gatedPickerInitScript(null, true));
    }
}
