package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import freemarker.template.Configuration;
import freemarker.template.Template;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评审 F-14 / P1-8 回归：报告模板必须<b>对 null / 缺失字段免疫</b>。
 *
 * <p><b>原失效链</b>：模板里的 {@code ${x}} 无 {@code !}/{@code ?} 兜底 —— 任一字段为 null 即抛
 * {@code InvalidReferenceException} → 片段渲染失败 → 向上抛 → 外层只记一行日志 →
 * <b>HTML / CSV / ZIP 与监控报告全部不产出</b>（排障时最需要的产物恰好缺失）。</p>
 *
 * <p>本测试两层固化：① 静态扫描全部模板，断言<b>不存在</b>未兜底的 {@code ${...}}；
 * ② 用「字段全为 null」的模型真实渲染被点名的模板，断言仍能产出内容。</p>
 */
public class ReportTemplateRobustnessTest {

    /** 全部报告模板（新增模板必须登记于此，否则本网扫不到）。 */
    private static final List<String> ALL_TEMPLATES = List.of(
            "/templates/summary-report.ftlh",
            "/templates/summary/alert-bar.ftlh",
            "/templates/summary/coverage-section.ftlh",
            "/templates/summary/error-type-pie-chart.ftlh",
            "/templates/summary/failure-and-result-list.ftlh",
            "/templates/summary/failure-overview.ftlh",
            "/templates/summary/monitor-failure-section.ftlh",
            "/templates/summary/summary-section.ftlh",
            "/templates/summary/view-full-report-button.ftlh");

    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{([^}]*)}");

    @Test
    public void everyTemplateGuardsDynamicInterpolationsWithDefaults() throws Exception {
        List<String> unguarded = new ArrayList<>();
        for (String path : ALL_TEMPLATES) {
            String src = read(path);
            Matcher m = INTERPOLATION.matcher(src);
            while (m.find()) {
                String expr = m.group(1);
                //  F-14：插值须带 ! （缺失/为 null 时取默认）或 ? （内建兜底如 ?c / ?has_content / ?no_esc）。
                if (!expr.contains("!") && !expr.contains("?")) {
                    unguarded.add(path + " -> ${" + expr + "}");
                }
            }
        }
        assertTrue(unguarded.isEmpty(),
                "F-14：以下插值缺 !/? 兜底 —— 字段为 null 时会让整份报告不产出，必须补兜底：\n"
                        + String.join("\n", unguarded));
    }

    @Test
    public void failureAndResultListRendersWithNullFields() throws Exception {
        String src = read("/templates/summary/failure-and-result-list.ftlh");
        Configuration cfg = new Configuration(Configuration.DEFAULT_INCOMPATIBLE_IMPROVEMENTS);
        cfg.setDefaultEncoding("UTF-8");
        Template tpl = new Template("failure-and-result-list.ftlh", new StringReader(src), cfg);

        String html = render(tpl, nullHeavyModel());

        assertFalse(html.isBlank(), "字段全为 null 时模板仍必须产出内容（不得抛异常）");
        assertTrue(html.contains("FLAKY"), "布尔兜底应生效（flaky=true 仍渲染 FLAKY 标记）");
    }

    /** 字段全为 null / 缺失的模型：模拟「SPI 数据不齐 / 用例无失败详情」的最坏输入。 */
    private static Map<String, Object> nullHeavyModel() {
        Map<String, Object> row = new HashMap<>();
        row.put("link", null);
        row.put("name", null);
        row.put("labelColor", null);
        row.put("labelText", null);
        row.put("color", null);
        row.put("error", null);
        row.put("traceLink", null);
        row.put("flaky", true);
        row.put("hasError", true);
        row.put("hasTrace", true);
        row.put("currentMs", null);
        row.put("previousMs", null);
        row.put("deltaPct", null);

        Map<String, Object> group = new HashMap<>();
        group.put("feature", null);
        //  失败列表段用 scenarios 作行集合、结果列表段用 rows（与生成器实际模型一致）
        group.put("scenarios", List.of(row));
        group.put("rows", List.of(row));
        group.put("link", null);
        group.put("name", null);

        Map<String, Object> model = new HashMap<>();
        model.put("hasFailures", true);
        model.put("hasTraces", true);
        model.put("hasTrend", true);
        model.put("csvLink", null);
        model.put("failureGroups", List.of(group));
        model.put("resultGroups", List.of(group));
        return model;
    }

    private static String render(Template tpl, Map<String, Object> model) throws Exception {
        StringWriter out = new StringWriter();
        tpl.process(model, out);
        return out.toString();
    }

    private static String read(String path) throws Exception {
        try (InputStream in = ReportTemplateRobustnessTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "类路径未找到报告模板：" + path + "（资源未打包会让本网静默失效）");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
