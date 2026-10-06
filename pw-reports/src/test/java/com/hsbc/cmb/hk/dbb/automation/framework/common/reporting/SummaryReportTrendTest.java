package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Full Test Results 表格的列口径与耗时取值契约。
 *
 * <p><b>当前形态</b>：恒定三列 {@code Requirement | Result | This run (s)}。历史对比列
 * （上一场耗时 / 变化率 Δ）与跨场 FLAKY 标记已整体下线 —— 报告目录每场全清（见 test-automation 的
 * {@code clean-serenity-report} 插件），不存在跨场历史可比，故连生成侧的 trend 落盘（TrendStore）
 * 也一并移除。本类守住：① 列口径不得回归；② 耗时取 {@code endTime − startTime}（墙钟），
 * 仅缺 {@code endTime} 时回退 JSON 的 {@code duration} 字段。</p>
 */
public class SummaryReportTrendTest {
    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();
    private File folder;

    @Before
    public void initTempFolder() {
        folder = tempFolder.getRoot();
    }

    private static final String FAIL_JSON = "{\n"
            + "  \"name\": \"Login OK\",\n"
            + "  \"result\": \"FAILURE\",\n"
            + "  \"duration\": 1234,\n"
            + "  \"userStory\": { \"storyName\": \"Trend Feature\" },\n"
            + "  \"testFailureCause\": { \"message\": \"boom\" },\n"
            + "  \"startTime\": \"2026-09-01T10:00:00.000000+08:00\"\n"
            + "}";

    @Test
    public void noHistory_doesNotRenderTrendColumns() throws Exception {
        File dir = newReport("no-history");
        writeOutcome(dir);

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        String html = readHtml(dir);
        assertTrue("本场耗时列恒定渲染", html.contains("This run (s)"));
        assertFalse("对比列已下线（不得回归）", html.contains("Last run"));
        assertFalse("无历史不应有 flaky 标记", html.contains("FLAKY"));
    }

    @Test
    public void resultsTableRendersSecondsOnlyWithoutComparisonColumns() throws Exception {
        File dir = newReport("columns");
        writeOutcome(dir);

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        String html = readHtml(dir);
        assertTrue("本场耗时列恒定渲染", html.contains("This run (s)"));
        // 单位为秒（固定 2 位小数）：1234ms → 1.23
        assertTrue("本次 1234ms 应以秒显示为 1.23", html.contains(">1.23<"));
        // 历史对比列与跨场 FLAKY 已整体下线（不得回归）
        assertFalse("不得渲染「上一场」列", html.contains("Last run"));
        assertFalse("不得渲染变化率 Δ 列", html.contains("&#916;"));
        assertFalse("不得渲染 FLAKY 徽标", html.contains("FLAKY"));
        // 跨场历史落盘（TrendStore）亦随之下线：不得再产生 trend-history 目录
        assertFalse("不得再落 trend-history 快照", new File(dir, "trend-history").exists());
    }

    /**
     * 耗时取值口径：优先 {@code endTime − startTime}（墙钟），仅当缺 {@code endTime} 时回退 JSON 的
     * {@code duration} 字段。
     *
     * <p>回归背景：Serenity JSON 的 {@code duration} 并不是场景耗时（实测同一条记录 duration=3683ms，
     * 而 start→end=67.08s；Serenity 原生页面对同一场景显示「1m 8s」，failsafe 用例耗时亦同量级），
     * 早期实现直接取该字段 ⇒ 报告里的「This run」系统性低估（4.49s 这类值）。</p>
     */
    @Test
    public void durationPrefersWallClockAndFallsBackToJsonField() throws Exception {
        File dir = newReport("duration-source");
        // a：duration 与 start/end 刻意不一致 —— 取 duration 得 1.23，取墙钟得 42.00
        Files.writeString(new File(dir, "a.json").toPath(), "{\n"
                + "  \"name\": \"Wall Clock Wins\",\n"
                + "  \"result\": \"SUCCESS\",\n"
                + "  \"duration\": 1234,\n"
                + "  \"userStory\": { \"storyName\": \"Duration Source\" },\n"
                + "  \"startTime\": \"2026-10-06T10:00:00.000+08:00\",\n"
                + "  \"endTime\": \"2026-10-06T10:00:42.000+08:00\"\n"
                + "}", StandardCharsets.UTF_8);
        // b：缺 endTime（老报告形态）⇒ 回退 duration=2000
        Files.writeString(new File(dir, "b.json").toPath(), "{\n"
                + "  \"name\": \"Fallback To Json\",\n"
                + "  \"result\": \"SUCCESS\",\n"
                + "  \"duration\": 2000,\n"
                + "  \"userStory\": { \"storyName\": \"Duration Source\" },\n"
                + "  \"startTime\": \"2026-10-06T10:01:00.000+08:00\"\n"
                + "}", StandardCharsets.UTF_8);

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        String html = readHtml(dir);
        assertTrue("有 endTime 时应取墙钟 42.00s", html.contains(">42.00<"));
        assertFalse("有 endTime 时不得回退到 JSON 的 duration", html.contains(">1.23<"));
        assertTrue("缺 endTime 时应回退到 duration=2000ms ⇒ 2.00s", html.contains(">2.00<"));
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
