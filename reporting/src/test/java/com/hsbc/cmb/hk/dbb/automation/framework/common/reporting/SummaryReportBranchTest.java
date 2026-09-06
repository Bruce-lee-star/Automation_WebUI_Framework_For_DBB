package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 {@link SummaryReportGoldenTest} 的 golden 基线<b>触及不到</b>的分支。
 *
 * <p>单条 fixture 的 golden 用例只走「单一错误类型」路径（饼图为实心圆分支），
 * 因而无法守护以下分支的模板化正确性——本类即为这些分支的回归网：
 * <ul>
 *   <li><b>多错误类型饼图</b>：走 {@code conic-gradient} + 底部图例分支。
 *       该分支在模板中存在「同一行内无换行拼接」的精密排版（原实现若干
 *       {@code sb.append} 刻意不带 {@code \n}），一旦换行被多/漏输出即破坏产物。</li>
 *   <li><b>无失败用例</b>：{@code hasFailures=false} 时「Full Failure List」整段须缺席。</li>
 * </ul>
 */
public class SummaryReportBranchTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String PROJECT_NAME = "Branch Coverage Project";
    private static final String REPORT_URL = "https://reports.example.com/job/43/Serenity_20Summary_20Report/";

    @Before
    public void pinEnvironment() {
        System.setProperty("serenity.project.name", PROJECT_NAME);
        System.setProperty("serenity.report.url", REPORT_URL);
    }

    @After
    public void restoreEnvironment() {
        System.clearProperty("serenity.project.name");
        System.clearProperty("serenity.report.url");
    }

    /**
     * 多错误类型：饼图必须走 conic-gradient 分支，且每个分类都要出现在底部图例中。
     * 断言用「包含」而非逐字节比对——多结果场景受文件扫描顺序影响，顺序不确定。
     */
    @Test
    public void pieChartUsesConicGradientAndLegendForMultipleErrorTypes() throws Exception {
        File dir = folder.newFolder("multi-error-report");
        writeOutcome(dir, "assert.json", "AssertionError: expected <ok> but was <bad>");
        writeOutcome(dir, "timeout.json", "TimeoutException: timed out after 30000ms");

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        String html = Files.readString(dir.toPath().resolve("serenity-summary.html"), StandardCharsets.UTF_8);

        assertTrue("多分类饼图必须渲染 conic-gradient，实际未出现",
                html.contains("conic-gradient(from -90deg"));
        assertTrue("图例须含 Assertion Failed 分类", html.contains("Assertion Failed"));
        assertTrue("图例须含 Timeout Error 分类", html.contains("Timeout Error"));
        assertTrue("须含 Failure Analysis 标题", html.contains("Failure Analysis"));
        // 模板指令不得泄漏进产物
        assertFalse("Freemarker 指令泄漏进产物", html.contains("<#"));
        assertFalse("模板占位符未解析", html.contains("${"));
    }

    /** 无失败用例：Full Failure List 整段缺席，Full Test Results 仍须渲染。 */
    @Test
    public void failureListSectionAbsentWhenNoFailures() throws Exception {
        File dir = folder.newFolder("success-only-report");
        Files.writeString(new File(dir, "ok.json").toPath(), "{\n"
                + "  \"name\": \"Happy path\",\n"
                + "  \"result\": \"SUCCESS\",\n"
                + "  \"duration\": 500,\n"
                + "  \"userStory\": { \"storyName\": \"Branch Feature\" },\n"
                + "  \"scenarioId\": \"branch-feature;happy-path\",\n"
                + "  \"startTime\": \"2026-09-01T10:00:00.000000+08:00\"\n"
                + "}", StandardCharsets.UTF_8);

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        String html = Files.readString(dir.toPath().resolve("serenity-summary.html"), StandardCharsets.UTF_8);

        assertFalse("无失败时不得出现 Full Failure List", html.contains("Full Failure List"));
        assertTrue("Full Test Results 始终渲染", html.contains("Full Test Results"));
        assertFalse("无失败时不得出现 Test Failure Overview", html.contains("Test Failure Overview"));
    }

    private static void writeOutcome(File dir, String fileName, String failureMessage) throws Exception {
        Files.writeString(new File(dir, fileName).toPath(), "{\n"
                + "  \"name\": \"" + fileName.replace(".json", "") + "\",\n"
                + "  \"result\": \"FAILURE\",\n"
                + "  \"duration\": 800,\n"
                + "  \"userStory\": { \"storyName\": \"Branch Feature\" },\n"
                + "  \"scenarioId\": \"branch-feature;" + fileName.replace(".json", "") + "\",\n"
                + "  \"testFailureCause\": { \"message\": \"" + failureMessage + "\" },\n"
                + "  \"startTime\": \"2026-09-01T10:00:00.000000+08:00\"\n"
                + "}", StandardCharsets.UTF_8);
    }
}
