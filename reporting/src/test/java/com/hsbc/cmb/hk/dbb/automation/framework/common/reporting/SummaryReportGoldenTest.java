package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T2-8 阶段 0：{@link SummaryReportGenerator} 输出基线（golden）护盾。
 *
 * <p><b>为什么必须先有它</b>：T2-8 要把 2,184 行里的 HTML/CSS 硬编码拼接改为模板引擎渲染，
 * 验收标准是「输出产物与改造前<b>逐字节一致</b>」。若无基线，任何 CSS/文案/结构漂移都无法被发现，
 * 切换等于裸奔。本测试即该验收标准的可执行形式，也是后续每个切换步骤的回归网。
 *
 * <p><b>确定性设计</b>（快照测试的生命线）：
 * <ul>
 *   <li><b>项目名/报告 URL</b>：经 {@code serenity.project.name} / {@code serenity.report.url}
 *       系统属性钉死（构造期读取，无法注入），测试结束还原，不污染其它用例。</li>
 *   <li><b>时间</b>：fixture 提供 {@code startTime}，使报告展示
 *       {@code testExecutionTime}（取自最早 startTime）而非 {@code reportTime}
 *       （构造期 {@code LocalDateTime.now()}，必然漂移）。</li>
 *   <li><b>耗时</b>：fixture 固定 {@code duration}，且目录内无 {@code index.html}，
 *       故 {@code clockTime} 由确定性公式推导，非墙上时钟。</li>
 *   <li><b>顺序</b>：golden 用例只放 <b>1 个 JSON</b>，规避
 *       {@code File.listFiles()} 顺序随文件系统漂移导致的行序不确定性；
 *       多结果场景改用「集合断言」而非逐字节比对（见 CSV 用例）。</li>
 *   <li><b>路径与时间戳文件名</b>：HTML 中的绝对路径、CSV/ZIP 文件名里的
 *       {@code yyyy-MM-dd_HH-mm-ss} 统一规范化为占位符。</li>
 * </ul>
 *
 * <p><b>防假绿</b>：golden 基线不存在时，本测试写出基线后 <b>立即失败</b> 并提示人工评审后重跑，
 * 绝不「自动生成即通过」。基线须提交进版本库，之后任何漂移都硬失败。
 */
public class SummaryReportGoldenTest {

    @TempDir
    File folder;

    private static final String PROJECT_NAME = "Golden Baseline Project";
    private static final String REPORT_URL = "https://reports.example.com/job/42/Serenity_20Summary_20Report/";

    /** 基线文件（Maven surefire 工作目录 = 模块根目录 reporting/）。 */
    private static final Path GOLDEN_HTML =
            Paths.get("src", "test", "resources", "golden", "serenity-summary.golden.html");

    /**
     * 单条 fixture：覆盖失败结果、HTML 元字符（&amp; &lt; &gt;）、失败原因、确定性 start/end 时间。
     * 刻意不含双引号——双引号已由模板 auto-escape 统一转义（见安全契约用例），golden 基线不引双引号以保持字节稳定。
     */
    private static final String GOLDEN_JSON = "{\n"
            + "  \"name\": \"Checkout & Payment <special> chars\",\n"
            + "  \"result\": \"FAILURE\",\n"
            + "  \"duration\": 1234,\n"
            + "  \"userStory\": { \"storyName\": \"Golden Feature\" },\n"
            + "  \"scenarioId\": \"golden-feature;checkout-special\",\n"
            + "  \"testFailureCause\": { \"message\": \"expected <ok> but was <bad>\" },\n"
            + "  \"startTime\": \"2026-09-01T10:00:00.000000+08:00\"\n"
            + "}";

    @BeforeEach
    public void pinEnvironment() {
        System.setProperty("serenity.project.name", PROJECT_NAME);
        System.setProperty("serenity.report.url", REPORT_URL);
    }

    @AfterEach
    public void restoreEnvironment() {
        System.clearProperty("serenity.project.name");
        System.clearProperty("serenity.report.url");
    }

    /**
     * 核心门禁：HTML 产物必须与 golden 基线<b>逐字节一致</b>。
     * 这是 T2-8 模板化的唯一验收凭据——模板改写后本用例仍须全绿。
     */
    @Test
    public void htmlMatchesGoldenBaseline() throws Exception {
        File dir = new File(folder, "golden-report");
        dir.mkdirs();
        writeOutcome(dir, "golden.json", GOLDEN_JSON);

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        Path html = dir.toPath().resolve("serenity-summary.html");
        assertTrue(Files.exists(html), "serenity-summary.html 必须生成");
        String actual = normalize(Files.readString(html, StandardCharsets.UTF_8), dir);
        assertFalse(actual.isEmpty(), "HTML 产物不应为空");

        if (!Files.exists(GOLDEN_HTML)) {
            Files.createDirectories(GOLDEN_HTML.getParent());
            Files.writeString(GOLDEN_HTML, actual, StandardCharsets.UTF_8);
            fail("Golden 基线已生成于 " + GOLDEN_HTML.toAbsolutePath()
                    + " —— 请人工评审后提交，再重跑本用例（防「自动生成即通过」的假绿）。");
        }

        String golden = Files.readString(GOLDEN_HTML, StandardCharsets.UTF_8);
        if (!golden.equals(actual)) {
            // 失败必须可诊断：dump 实际产物 + 定位首个差异（assertEquals 会打印两份 35KB 全文，无法读）
            Path dump = Paths.get("target", "golden-actual.html");
            Files.createDirectories(dump.getParent());
            Files.writeString(dump, actual, StandardCharsets.UTF_8);
            int at = firstDifference(golden, actual);
            fail("HTML 输出已从 golden 基线漂移，首个差异位置=" + at
                    + "（golden 长度=" + golden.length() + "，actual 长度=" + actual.length() + "）\n"
                    + "  golden: …" + snippet(golden, at) + "…\n"
                    + "  actual: …" + snippet(actual, at) + "…\n"
                    + "实际产物已写出至 " + dump.toAbsolutePath()
                    + "；若为 T2-8 有意为之的结构变更，请人工确认差异后更新基线，否则即为回归。");
        }
    }

    /**
     * CSV 契约：表头固定，含逗号/双引号的字段必须按 RFC 4180 加引号且内部引号翻倍。
     * CSV 内容不含 reportTime，可完全逐字节断言（文件名含时间戳，故按前缀查找）。
     */
    @Test
    public void csvQuotesFieldsContainingCommasAndQuotes() throws Exception {
        File dir = new File(folder, "csv-report");
        dir.mkdirs();
        writeOutcome(dir, "csv.json", "{\n"
                + "  \"name\": \"Login, with \\\"quoted\\\" value\",\n"
                + "  \"result\": \"SUCCESS\",\n"
                + "  \"duration\": 900,\n"
                + "  \"userStory\": { \"storyName\": \"Feature, One\" },\n"
                + "  \"startTime\": \"2026-09-01T09:00:00.000000+08:00\"\n"
                + "}");

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        Path csv = findOne(dir, "test-results-", ".csv");
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertEquals("Feature,Scenario,Result,Duration (ms),Error Message", lines.get(0), "表头契约");
        assertEquals(2, lines.size(), "表头 + 1 条数据行");

        String row = lines.get(1);
        assertTrue(row.startsWith("\"Feature, One\","), "含逗号的 feature 必须加引号，实际：" + row);
        assertTrue(row.contains("\"Login, with \"\"quoted\"\" value\""),
                "含逗号与双引号的 scenario 必须加引号且引号翻倍，实际：" + row);
        assertTrue(row.endsWith(",SUCCESS,900,"), "结果/耗时/错误信息列（成功时为空），实际：" + row);
    }

    /** ZIP 契约：必须打包汇总报告本体（条目名不含时间戳，可稳定断言）。 */
    @Test
    public void zipPackageContainsSummaryArtifacts() throws Exception {
        File dir = new File(folder, "zip-report");
        dir.mkdirs();
        writeOutcome(dir, "zip.json", GOLDEN_JSON);

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        Path zip = findOne(dir, "test-report-", ".zip");
        Set<String> entries = new TreeSet<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                entries.add(en.nextElement().getName());
            }
        }
        assertTrue(entries.contains("serenity-summary.html"),
                "ZIP 必须包含 serenity-summary.html，实际条目：" + entries);
    }

    /**
     * 安全契约：固化报告产物对外部数据（用例名/异常堆栈等）的 HTML 转义。
     * Freemarker 模板（.ftlh）默认 HTML auto-escape，统一转义 &amp; &lt; &gt; &quot; &#39;，
     * 彻底闭合 T2-8 收尾项「{@code escape()} 不转义双引号」的缺口。
     */
    @Test
    public void htmlEscapesHtmlMetacharactersFromOutcomeData() throws Exception {
        File dir = new File(folder, "escape-report");
        dir.mkdirs();
        writeOutcome(dir, "escape.json", "{\n"
                + "  \"name\": \"<script>alert(1)</script> & \\\"quoted\\\" more\",\n"
                + "  \"result\": \"FAILURE\",\n"
                + "  \"duration\": 100,\n"
                + "  \"userStory\": { \"storyName\": \"<img src=x onerror=alert(1)>\" },\n"
                + "  \"testFailureCause\": { \"message\": \"boom <&>\" },\n"
                + "  \"startTime\": \"2026-09-01T08:00:00.000000+08:00\"\n"
                + "}");

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        String html = Files.readString(
                dir.toPath().resolve("serenity-summary.html"), StandardCharsets.UTF_8);
        assertFalse(html.contains("<script>alert(1)"), "原始 <script> 绝不可进入报告产物");
        assertFalse(html.contains("<img src=x onerror=alert(1)>"), "原始 <img onerror> 绝不可进入报告产物");
        assertTrue(html.contains("&lt;script&gt;"), "必须转义为 &lt;script&gt;");
        assertTrue(html.contains("&amp;"), "必须转义为 &amp;");
        assertTrue(html.contains("&quot;"), "双引号必须转义为 &quot;（闭合 T2-8 收尾项）");
        assertFalse(html.contains("\"quoted\""), "原始 \"quoted\" 不得进入报告产物");
    }

    /** 首个差异字符下标；若仅是长度不同，返回较短串长度。 */
    private static int firstDifference(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return i;
            }
        }
        return n;
    }

    /** 差异点上下文快照（换行压平为空格，便于单行阅读）。 */
    private static String snippet(String s, int at) {
        int from = Math.max(0, at - 80);
        int to = Math.min(s.length(), at + 80);
        return s.substring(from, to).replace("\n", " ");
    }

    private static void writeOutcome(File dir, String fileName, String json) throws Exception {
        Files.writeString(new File(dir, fileName).toPath(), json, StandardCharsets.UTF_8);
    }

    /** 抹去机器相关项：报告目录绝对路径、产物文件名里的时间戳。 */
    private static String normalize(String html, File dir) {
        String out = html.replace(dir.getAbsolutePath().replace('\\', '/'), "{{REPORT_DIR}}");
        out = out.replace(dir.getAbsolutePath(), "{{REPORT_DIR}}");
        // 产物文件名时间戳格式 = TIMESTAMP_FORMATTER "yyyy-MM-dd_HH-mm-ss"（如 2026-09-05_21-45-37）
        out = out.replaceAll("test-results-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}", "test-results-{{TS}}");
        out = out.replaceAll("test-report-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}", "test-report-{{TS}}");
        return out;
    }

    private static Path findOne(File dir, String prefix, String suffix) throws Exception {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir.toPath(), prefix + "*" + suffix)) {
            for (Path p : ds) {
                return p;
            }
        }
        fail("目录下未找到 " + prefix + "*" + suffix + "：" + dir);
        return null;
    }
}
