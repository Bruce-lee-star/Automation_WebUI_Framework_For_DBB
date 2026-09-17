package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E-3：Trace 挂进汇总报告（失败清单 Trace 下载列）。
 *
 * <p>契约：仅当报告目录下存在与失败场景匹配的 {@code traces/trace-<scenarioId>-<ts>.zip} 时才渲染 Trace 列；
 * 无 traces 目录或无匹配时**不渲染**（保证既有 golden 基线逐字节不变，且绝不产生悬空链接）。
 */
class SummaryReportTraceReportTest {

    @TempDir
    File folder;

    /** 单个 FAILURE 场景（name = "Login OK"），用于验证 trace 名称匹配。 */
    private static final String FAIL_JSON = "{\n"
            + "  \"name\": \"Login OK\",\n"
            + "  \"result\": \"FAILURE\",\n"
            + "  \"duration\": 100,\n"
            + "  \"userStory\": { \"storyName\": \"Trace Feature\" },\n"
            + "  \"testFailureCause\": { \"message\": \"boom\" },\n"
            + "  \"startTime\": \"2026-09-01T10:00:00.000000+08:00\"\n"
            + "}";

    @Test
    void noTracesDir_doesNotRenderTraceColumn() throws Exception {
        File dir = newReport("no-traces");
        writeOutcome(dir, "a.json", FAIL_JSON);

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        String html = readHtml(dir);
        assertFalse(html.contains("class=\"scenarioTrace\""), "无 traces 目录时不应渲染 Trace 单元格");
        assertFalse(html.contains(">Trace</th>"), "无 traces 目录时不应渲染 Trace 表头");
    }

    @Test
    void matchingTrace_rendersDownloadLink() throws Exception {
        File dir = newReport("matching");
        writeOutcome(dir, "a.json", FAIL_JSON);
        // trace id = sanitize(testName + "_" + threadId + "_" + seq)（PlaywrightContextManager）
        writeTrace(dir, "trace-Login_OK_12_3-1756800000000.zip");

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        String html = readHtml(dir);
        assertTrue(html.contains("class=\"scenarioTrace\""), "有匹配 trace 时应渲染 Trace 列");
        assertTrue(html.contains("traces/trace-Login_OK_12_3-1756800000000.zip"),
                "应链接到对应 trace 文件，实际片段见产物");
    }

    @Test
    void nonMatchingTrace_doesNotRenderColumn() throws Exception {
        File dir = newReport("non-matching");
        writeOutcome(dir, "a.json", FAIL_JSON);
        writeTrace(dir, "trace-Other_1_1-1756800000000.zip");

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        String html = readHtml(dir);
        assertFalse(html.contains("class=\"scenarioTrace\""),
                "无匹配 trace 时应降级为不渲染该列（绝不产生悬空链接）");
    }

    /** 创建用例报告目录；mkdirs 失败即抛（不静默忽略返回值，SpotBugs RV_RETURN_VALUE_IGNORED_BAD_PRACTICE）。 */
    private File newReport(String name) {
        File dir = new File(folder, name);
        if (!dir.mkdirs() && !dir.exists()) {
            throw new IllegalStateException("Cannot create test report dir: " + dir);
        }
        return dir;
    }

    private static void writeOutcome(File dir, String fileName, String json) throws Exception {
        Files.writeString(new File(dir, fileName).toPath(), json, StandardCharsets.UTF_8);
    }

    private static void writeTrace(File dir, String traceFileName) throws Exception {
        File traces = new File(dir, "traces");
        if (!traces.mkdirs() && !traces.exists()) {
            throw new IllegalStateException("Cannot create traces dir: " + traces);
        }
        Files.writeString(new File(traces, traceFileName).toPath(), "dummy", StandardCharsets.UTF_8);
    }

    private static String readHtml(File dir) throws Exception {
        return Files.readString(dir.toPath().resolve("serenity-summary.html"), StandardCharsets.UTF_8);
    }
}
