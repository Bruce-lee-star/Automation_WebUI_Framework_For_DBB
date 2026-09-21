package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E-2：汇总报告历史趋势列。仅当存在上一场快照时渲染「本次/上次耗时 + 变化率」，
 * 近 5 场失败 ≥3 次的场景打 FLAKY 标记；无历史时输出与 golden 基线一致。
 */
class SummaryReportTrendTest {

    @TempDir
    File folder;

    private static final String FAIL_JSON = "{\n"
            + "  \"name\": \"Login OK\",\n"
            + "  \"result\": \"FAILURE\",\n"
            + "  \"duration\": 1234,\n"
            + "  \"userStory\": { \"storyName\": \"Trend Feature\" },\n"
            + "  \"testFailureCause\": { \"message\": \"boom\" },\n"
            + "  \"startTime\": \"2026-09-01T10:00:00.000000+08:00\"\n"
            + "}";

    @Test
    void noHistory_doesNotRenderTrendColumns() throws Exception {
        File dir = newReport("no-history");
        writeOutcome(dir);

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        String html = readHtml(dir);
        assertFalse(html.contains("Last run (ms)"), "无历史不应渲染趋势列");
        assertFalse(html.contains("FLAKY"), "无历史不应有 flaky 标记");
    }

    @Test
    void withHistory_rendersTrendColumnsAndDelta() throws Exception {
        File dir = newReport("with-history");
        writeOutcome(dir);
        // 上一场：同名场景耗时 1000ms
        new TrendStore(dir.toPath()).save(new RunSummary(
                "2020-01-01_00-00-00", "old", 1, 0, 1, Map.of("Login OK", 1000L), List.of("Login OK")));

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        String html = readHtml(dir);
        assertTrue(html.contains("Last run (ms)"), "有历史应渲染趋势列");
        assertTrue(html.contains(">1000<"), "应显示上期耗时 1000");
        assertTrue(html.contains("+23%"), "变化率应为 +23%（(1234-1000)/1000）");
    }

    @Test
    void flakyMarkedWhenFailedInAtLeastThreeRecentRuns() throws Exception {
        File dir = newReport("flaky");
        writeOutcome(dir);
        TrendStore store = new TrendStore(dir.toPath());
        for (String id : List.of("2020-01-01_00-00-00", "2020-01-02_00-00-00", "2020-01-03_00-00-00")) {
            store.save(new RunSummary(id, "t", 1, 0, 1, Map.of("Login OK", 1000L), List.of("Login OK")));
        }

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        assertTrue(readHtml(dir).contains("FLAKY"), "近 5 场失败 ≥3 次应打 flaky 标记");
    }

    /** 创建用例报告目录；mkdirs 失败即抛（不静默忽略返回值，SpotBugs RV_RETURN_VALUE_IGNORED_BAD_PRACTICE）。 */
    private File newReport(String name) {
        File dir = new File(folder, name);
        if (!dir.mkdirs() && !dir.exists()) {
            throw new IllegalStateException("Cannot create test report dir: " + dir);
        }
        return dir;
    }

    private static void writeOutcome(File dir) throws Exception {
        Files.writeString(new File(dir, "a.json").toPath(), FAIL_JSON, StandardCharsets.UTF_8);
    }

    private static String readHtml(File dir) throws Exception {
        return Files.readString(dir.toPath().resolve("serenity-summary.html"), StandardCharsets.UTF_8);
    }
}
