package com.hsbc.cmb.hk.dbb.automation.framework.web.accessibility;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AxeCoreScriptProvider} 企业级单测（Mockito 隔离，不启动真实浏览器）。
 * <p>
 * 覆盖：① axe.run 结果 JSON → 框架自有 {@link AxeRule}/{@link AxeNode} 模型映射；
 * ② 自带 axe.min.js 资源加载（classpath 读取，缺失即抛 {@link AxeCoreException}）；
 * ③ 幂等注入探测（页面未注入时经 {@code addScriptTag} 注入）；④ 入参 null 校验。
 * <p>
 * 真实浏览器端到端扫描由全护盾中的无障碍套件覆盖。
 */
public class AxeCoreScriptProviderTest {

    private static final String SAMPLE_JSON = "{"
            + "\"violations\":[{\"id\":\"color-contrast\",\"impact\":\"serious\","
            + "\"description\":\"Elements must have sufficient color contrast\",\"help\":\"help text\","
            + "\"helpUrl\":\"https://dequeuniversity.com/rules/axe/4.10/color-contrast\","
            + "\"nodes\":[{\"target\":[\"#main\",\"div.banner\"],\"failureSummary\":\"Fix the contrast\"}]}],"
            + "\"incomplete\":[],"
            + "\"passes\":[{\"id\":\"document-title\",\"impact\":\"minor\","
            + "\"description\":\"Documents must contain a title element\",\"help\":\"h\",\"helpUrl\":\"https://x/y\","
            + "\"nodes\":[]}]"
            + "}";

    private static Page mockPageReturning(String axeRunJson) {
        Page page = mock(Page.class);
        when(page.evaluate(anyString(), any())).thenAnswer(inv -> {
            String script = inv.getArgument(0);
            if (script.contains("typeof window.axe")) {
                return Boolean.TRUE; // 视作已注入，跳过 addScriptTag
            }
            return axeRunJson;
        });
        return page;
    }

    @Test
    void runAxeMapsJsonToOwnModel() {
        Page page = mockPageReturning(SAMPLE_JSON);
        AxeCoreScanner.AxeScanConfig config = new AxeCoreScanner.AxeScanConfig();

        AxeCoreScriptProvider.AxeRunResult result = AxeCoreScriptProvider.runAxe(page, config, null);

        assertEquals(1, result.violations().size(), "violations 数量应映射正确");
        AxeRule violation = result.violations().get(0);
        assertEquals("color-contrast", violation.getId());
        assertEquals("serious", violation.getImpact());
        assertEquals("Elements must have sufficient color contrast", violation.getDescription());
        assertEquals(1, violation.getNodes().size(), "violation 节点应映射");
        List<String> target = violation.getNodes().get(0).getTarget();
        assertEquals(2, target.size(), "target 选择器数组应映射");
        assertEquals("#main", target.get(0));
        assertEquals("div.banner", target.get(1));
        assertEquals("Fix the contrast", violation.getNodes().get(0).getFailureSummary());

        assertTrue(result.incomplete().isEmpty(), "incomplete 应为空");
        assertEquals(1, result.passes().size(), "passes 数量应映射正确");
        assertEquals("document-title", result.passes().get(0).getId());
    }

    @Test
    void runAxeInjectsScriptWhenNotPresent() {
        Page page = mock(Page.class);
        when(page.evaluate(anyString(), any())).thenAnswer(inv -> {
            String script = inv.getArgument(0);
            if (script.contains("typeof window.axe")) {
                return Boolean.FALSE; // 未注入
            }
            return "{\"violations\":[],\"incomplete\":[],\"passes\":[]}";
        });

        AxeCoreScriptProvider.runAxe(page, new AxeCoreScanner.AxeScanConfig(), null);

        // 未注入时应触发 addScriptTag 注入自带 axe.min.js
        verify(page).addScriptTag(any(Page.AddScriptTagOptions.class));
    }

    @Test
    void runAxeRejectsNullPage() {
        assertThrows(AxeCoreException.class,
                () -> AxeCoreScriptProvider.runAxe(null, new AxeCoreScanner.AxeScanConfig(), null));
    }

    @Test
    void runAxeRejectsNullConfig() {
        Page page = mock(Page.class);
        assertThrows(AxeCoreException.class, () -> AxeCoreScriptProvider.runAxe(page, null, null));
    }

    @Test
    void loadScriptReadsBundledResource() {
        // 资源加载成功（自带 axe.min.js 已置于 web 模块 resources），缺失会抛 AxeCoreException
        String script = AxeCoreScriptProvider.loadScript();
        assertFalse(script.isEmpty(), "自带 axe.min.js 应从 classpath 成功加载且非空");
    }
}
