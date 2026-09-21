package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorFailureReportData;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.SummaryReportGenerator;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.CapturedApiCall;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证「有监控失败 / 数据丢失时，summary report（完整 HTML）渲染出显示区域」——
 * 使用 <b>真实</b> {@link MonitorFailureReportWriterSink}（经 SPI 自动发现，非测试桩），
 * 真实 {@link MonitorFailureCollector} + 真实 {@link MonitorDataLossReporter}，驱动真实
 * {@link SummaryReportGenerator} 走通「SPI 收集 → 片段渲染 → summary-report.ftlh 装配」全链路。
 *
 * <p><b>与 reporting 模块测试的区别</b>：reporting 测试 classpath 无 route，只能用测试桩注入数据；
 * route 模块 classpath 含真实 sink（其 {@code META-INF/services} 已注册），故本测试由真实 sink
 * 的 {@code collectData()} 产出数据，验证的是<b>生产路径</b>的「报告显示」。
 */
public class SummaryReportRealSinkEndToEndTest {

    @TempDir
    File folder;

    private File newReportDir(String name) {
        File dir = new File(folder, name);
        if (!dir.mkdirs() && !dir.exists()) {
            throw new IllegalStateException("Cannot create test report dir: " + dir);
        }
        return dir;
    }

    private static final String PROJECT_NAME = "Real Sink Project";
    private static final String REPORT_URL = "https://reports.example.com/job/45/Serenity_20Summary_20Report/";

    @BeforeEach
    public void pinEnvironment() {
        System.setProperty("serenity.project.name", PROJECT_NAME);
        System.setProperty("serenity.report.url", REPORT_URL);
    }

    @AfterEach
    public void restoreEnvironment() {
        System.clearProperty("serenity.project.name");
        System.clearProperty("serenity.report.url");
        MonitorFailureCollector.getInstance().clear();
        MonitorDataLossReporter.instance().reset();
    }

    @Test
    public void reportDisplaysMonitorSectionFromRealRouteSink() throws Exception {
        // 真实失败快照（经 CapturedApiCall.Builder 构造，与线上捕获同构）
        CapturedApiCall call = new CapturedApiCall.Builder()
                .endpoint("/api/v1/transfer")
                .method("POST")
                .statusCode(500)
                .requestUrl("https://demo-host:8888/demo/api/v1/transfer")
                .requestBody("{\"amt\":100}")
                .responseBody("{\"error\":\"boom\"}")
                .responseHeaders(Collections.emptyMap())
                .requestHeaders(Collections.emptyMap())
                .timestamp(123L)
                .build();
        MonitorFailureCollector.getInstance()
                .record(call, "/api/v1/transfer", "team-a@hsbc.com", "status=500 expected=200");
        // 真实数据丢失（刷库背压 / 失败累计）—— 报告尾部红色提示的数据源
        MonitorDataLossReporter.instance().recordLoss("route_monitor_record", 3L);

        File dir = newReportDir("real-sink-report");
        writeOutcome(dir, "ok.json", successOutcomeJson());

        // 真实 MonitorFailureReportWriterSink 经 ServiceLoader 自动发现并 collectData()
        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        String html = Files.readString(
                dir.toPath().resolve("serenity-summary.html"), StandardCharsets.UTF_8);

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
        File dir = newReportDir("real-no-data");
        writeOutcome(dir, "ok.json", successOutcomeJson());

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        String html = Files.readString(
                dir.toPath().resolve("serenity-summary.html"), StandardCharsets.UTF_8);
        assertFalse(html.contains("API 监控失败（按 Owner 汇总）"), "无数据时不渲染监控区域");
        assertFalse(html.contains("数据完整性告警"), "无数据时不渲染数据丢失红框");
    }

    private static void writeOutcome(File dir, String fileName, String json) throws Exception {
        Files.writeString(new File(dir, fileName).toPath(), json, StandardCharsets.UTF_8);
    }

    private static String successOutcomeJson() {
        return "{\n"
                + "  \"name\": \"Happy path\",\n"
                + "  \"result\": \"SUCCESS\",\n"
                + "  \"duration\": 500,\n"
                + "  \"userStory\": { \"storyName\": \"Real Sink Feature\" },\n"
                + "  \"scenarioId\": \"real-sink-feature;happy-path\",\n"
                + "  \"startTime\": \"2026-09-01T10:00:00.000000+08:00\"\n"
                + "}";
    }
}
