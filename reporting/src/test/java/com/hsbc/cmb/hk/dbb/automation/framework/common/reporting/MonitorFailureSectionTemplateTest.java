package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 {@code summary/monitor-failure-section.ftlh} 模板：
 * 有内容时渲染「按 Owner 汇总」+ 数据丢失红框；无内容时不渲染（保持报告整洁）。
 *
 * <p>本测试不依赖 route 模块（reporting 测试 classpath 无 route），直接构造
 * {@link MonitorFailureReportData} 驱动模板，验证模板与 DTO 绑定的正确性。
 */
class MonitorFailureSectionTemplateTest {

    private static final Configuration FM = new Configuration(Configuration.VERSION_2_3_33);
    static {
        FM.setClassForTemplateLoading(MonitorFailureSectionTemplateTest.class, "/templates");
        FM.setDefaultEncoding("UTF-8");
        FM.setRecognizeStandardFileExtensions(true);
        FM.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        FM.setLogTemplateExceptions(false);
        FM.setWrapUncheckedExceptions(true);
    }

    @Test
    void rendersOwnerFailuresAndDataLoss() throws Exception {
        List<MonitorFailureItem> items = new ArrayList<>();
        items.add(new MonitorFailureItem(
                "team-a@hsbc.com", "Login", "/api/v1/transfer", "500", "POST",
                "https://api.example.com/api/v1/transfer", "status=500 expected=200",
                List.of("Scenario A", "Scenario B"), "{\"amt\":100}", "{\"error\":\"boom\"}"));
        List<MonitorOwnerBlock> owners = new ArrayList<>();
        owners.add(new MonitorOwnerBlock("team-a@hsbc.com", items));

        Map<String, Long> loss = new LinkedHashMap<>();
        loss.put("route_monitor_record", 3L);
        MonitorFailureReportData data = new MonitorFailureReportData(owners, loss, 3L, 1, 1);

        String html = render(data);
        assertTrue(html.contains("API 监控失败（按 Owner 汇总）"), "应包含按 Owner 汇总标题");
        assertTrue(html.contains("team-a@hsbc.com"), "应包含 owner");
        assertTrue(html.contains("/api/v1/transfer"), "应包含 endpoint");
        assertTrue(html.contains("数据完整性告警（API 监控数据丢失）"), "应包含数据丢失红框标题");
        assertTrue(html.contains("3 条"), "应包含丢失条数");
        assertTrue(html.contains("Scenario A, Scenario B"), "应包含触发 scenario 列表");
    }

    @Test
    void rendersNothingWhenEmpty() throws Exception {
        String html = render(MonitorFailureReportData.empty());
        assertTrue(html.isEmpty(), "空数据应不渲染任何内容");
    }

    private String render(MonitorFailureReportData data) throws Exception {
        Template tpl = FM.getTemplate("summary/monitor-failure-section.ftlh");
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("report", data);
        StringWriter out = new StringWriter();
        tpl.process(model, out);
        return out.toString();
    }
}
