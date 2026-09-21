package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证「有监控失败 / 数据丢失时，summary report（完整 HTML）确实渲染出该显示区域」。
 *
 * <p>经测试专用 SPI 实现 {@link TestMonitorFailureReportSink}（注册于 reporting 测试 classpath）注入样例数据，
 * 驱动真实 {@link SummaryReportGenerator} 走通「SPI 收集 → 片段渲染 → summary-report.ftlh 装配」全链路，
 * 断言最终产物 {@code serenity-summary.html} 包含该区域的关键标记。默认（无样例数据）时 sink 返回 empty，
 * 区域不渲染，不污染 golden/branch/trace 基线。
 */
public class SummaryReportMonitorSectionTest {

    @TempDir
    File folder;

    private File newReportDir(String name) {
        File dir = new File(folder, name);
        if (!dir.mkdirs() && !dir.exists()) {
            throw new IllegalStateException("Cannot create test report dir: " + dir);
        }
        return dir;
    }

    private static final String PROJECT_NAME = "Monitor Section Project";
    private static final String REPORT_URL = "https://reports.example.com/job/44/Serenity_20Summary_20Report/";

    @BeforeEach
    public void pinEnvironment() {
        System.setProperty("serenity.project.name", PROJECT_NAME);
        System.setProperty("serenity.report.url", REPORT_URL);
    }

    @AfterEach
    public void restoreEnvironment() {
        System.clearProperty("serenity.project.name");
        System.clearProperty("serenity.report.url");
        TestMonitorFailureReportSink.reset();
    }

    @Test
    public void reportDisplaysMonitorFailureSectionWhenDataPresent() throws Exception {
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
        TestMonitorFailureReportSink.setSampleData(data);

        File dir = newReportDir("monitor-report");
        writeOutcome(dir, "ok.json", "{\n"
                + "  \"name\": \"Happy path\",\n"
                + "  \"result\": \"SUCCESS\",\n"
                + "  \"duration\": 500,\n"
                + "  \"userStory\": { \"storyName\": \"Monitor Feature\" },\n"
                + "  \"scenarioId\": \"monitor-feature;happy-path\",\n"
                + "  \"startTime\": \"2026-09-01T10:00:00.000000+08:00\"\n"
                + "}");

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        String html = Files.readString(dir.toPath().resolve("serenity-summary.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("API 监控失败（按 Owner 汇总）"), "完整报告须含按 Owner 汇总标题");
        assertTrue(html.contains("team-a@hsbc.com"), "完整报告须含 owner");
        assertTrue(html.contains("/api/v1/transfer"), "完整报告须含 endpoint");
        assertTrue(html.contains("数据完整性告警（API 监控数据丢失）"), "完整报告须含数据丢失红框标题");
        assertTrue(html.contains("3 条"), "完整报告须含丢失条数");
        // 区域经模板装配进完整报告，指令/占位符须已解析
        assertFalse(html.contains("<#"), "Freemarker 指令不得泄漏进产物");
        assertFalse(html.contains("${"), "模板占位符须已解析");
    }

    @Test
    public void reportOmitsMonitorSectionWhenNoData() throws Exception {
        TestMonitorFailureReportSink.setSampleData(MonitorFailureReportData.empty());

        File dir = newReportDir("no-monitor-report");
        writeOutcome(dir, "ok.json", "{\n"
                + "  \"name\": \"Happy path\",\n"
                + "  \"result\": \"SUCCESS\",\n"
                + "  \"duration\": 500,\n"
                + "  \"userStory\": { \"storyName\": \"Monitor Feature\" },\n"
                + "  \"scenarioId\": \"monitor-feature;happy-path\",\n"
                + "  \"startTime\": \"2026-09-01T10:00:00.000000+08:00\"\n"
                + "}");

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        String html = Files.readString(dir.toPath().resolve("serenity-summary.html"), StandardCharsets.UTF_8);
        assertFalse(html.contains("API 监控失败（按 Owner 汇总）"), "无数据时不渲染监控区域");
        assertFalse(html.contains("数据完整性告警"), "无数据时不渲染数据丢失红框");
    }

    private static void writeOutcome(File dir, String fileName, String json) throws Exception {
        Files.writeString(new File(dir, fileName).toPath(), json, StandardCharsets.UTF_8);
    }
}
