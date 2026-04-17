package com.hsbc.cmb.hk.dbb.automation.report;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import net.thucydides.model.domain.Story;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SummaryReportGenerator {
    private static final Logger logger = LoggerFactory.getLogger(SummaryReportGenerator.class);
    private static final String DEFAULT_REPORT_DIR = "target/site/serenity";
    private static final String SUMMARY_FILE = "serenity-summary.html";
    private static final String CSV_FILE_PREFIX = "test-results";
    private static final String ZIP_FILE_PREFIX = "test-report";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("EEEE MMMM dd yyyy 'at' HH:mm");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final String reportDir;
    private final String projectName;
    private final String reportTitle;
    private final String reportUrl;        // Base URL where reports are published (e.g., .../Serenity_20Summary_20Report/)
    private final String fullReportUrl;     // Optional: separate URL for full Serenity report
    private final List<TestOutcome> testOutcomes = new ArrayList<>();
    private final List<SimpleTestOutcome> simpleTestOutcomes = new ArrayList<>();
    private final Map<TestResult, Long> resultCounts = new EnumMap<>(TestResult.class);
    private final Map<String, String> featureToHtmlMap = new LinkedHashMap<>();
    private final Map<String, String> scenarioToHtmlMap = new LinkedHashMap<>();

    // 自定义错误分类规则：label → pattern（正则），按配置顺序排列
    private final List<ErrorTypeRule> errorTypeRules = new ArrayList<>();

    private long totalDuration;
    private long minDuration;
    private long maxDuration;
    private double avgDuration;
    private long clockTime;
    private LocalDateTime reportTime;
    private String csvFileName;
    private String zipFileName;

    public SummaryReportGenerator() {
        this(DEFAULT_REPORT_DIR);
    }

    public SummaryReportGenerator(String reportDir) {
        this.reportDir = Objects.requireNonNull(reportDir);
        this.projectName = loadProjectName();
        this.reportTitle = projectName; // Use project name as report title
        this.reportUrl = loadReportUrl();
        // fullReportUrl: 直接链接到报告目录（不指定具体文件）
        // Jenkins环境: 绝对URL → http://jenkins.cli:8888/job/Playwright/49/Serenity_20Summary_20Report/
        // 本地环境: 相对链接 → ./ 或当前目录
        if (reportUrl != null && !reportUrl.isEmpty() && !reportUrl.contains("${")) {
            this.fullReportUrl = ensureTrailingSlash(reportUrl);
        } else {
            this.fullReportUrl = "./";
        }
        init();
    }

    private String loadProjectName() {
        String name = System.getProperty("serenity.project.name");
        if (name != null && !name.isEmpty()) return name;

        try {
            Path propFile = Paths.get("serenity.properties");
            LoggingConfigUtil.logDebugIfVerbose(logger, "Looking for serenity.properties at: {}", propFile.toAbsolutePath());

            if (Files.exists(propFile)) {
                // 使用 UTF-8 编码读取 properties 文件
                Properties props = new Properties();
                try (InputStream is = Files.newInputStream(propFile);
                     InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    props.load(isr);
                    name = props.getProperty("serenity.project.name");
                    if (name != null && !name.isEmpty()) {
                        LoggingConfigUtil.logDebugIfVerbose(logger, "Loaded project name from serenity.properties: {}", name);
                        return name;
                    }
                }
            } else {
                logger.warn("serenity.properties not found at: {}", propFile.toAbsolutePath());
            }
        } catch (Exception e) {
            logger.warn("Failed to read serenity.properties: {}", e.getMessage());
        }

        return "Serenity Automation Test Report";
    }

    private String loadReportUrl() {
        String url = System.getProperty("serenity.report.url");
        if (url != null && !url.isEmpty()) {
            url = resolveEnvironmentVariables(url);
            LoggingConfigUtil.logDebugIfVerbose(logger, "Using report URL from system property: {}", url);
            return url;
        }

        try {
            Path propFile = Paths.get("serenity.properties");
            LoggingConfigUtil.logDebugIfVerbose(logger, "Looking for serenity.properties at: {}", propFile.toAbsolutePath());

            if (Files.exists(propFile)) {
                // 使用 UTF-8 编码读取 properties 文件
                Properties props = new Properties();
                try (InputStream is = Files.newInputStream(propFile);
                     InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    props.load(isr);
                    url = props.getProperty("serenity.report.url");
                    if (url != null && !url.isEmpty()) {
                        // 解析环境变量占位符
                        url = resolveEnvironmentVariables(url);
                        LoggingConfigUtil.logDebugIfVerbose(logger, "Loaded report URL from serenity.properties: {}", url);
                        return url;
                    }
                }
            } else {
                LoggingConfigUtil.logDebugIfVerbose(logger, "serenity.properties not found at: {}", propFile.toAbsolutePath());
            }
        } catch (Exception e) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Failed to read report URL: {}", e.getMessage());
        }

        // 如果没有配置 URL，尝试自动构建 Jenkins 报告 URL
        url = autoDetectReportUrl();
        if (url != null && !url.isEmpty()) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Auto-detected report URL: {}", url);
            return url;
        }

        return ""; // 默认为空
    }

    /**
     * 解析字符串中的环境变量占位符
     * 支持格式：${ENV_VAR} 或 ${ENV_VAR:default_value}
     * 
     * @param value 包含环境变量占位符的字符串
     * @return 解析后的字符串
     */
    private String resolveEnvironmentVariables(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        // 匹配 ${ENV_VAR} 或 ${ENV_VAR:default} 格式
        Pattern pattern = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?\\}");
        Matcher matcher = pattern.matcher(value);
        
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String envVarName = matcher.group(1);
            String defaultValue = matcher.group(2);
            
            String envValue = System.getenv(envVarName);
            if (envValue != null && !envValue.isEmpty()) {
                matcher.appendReplacement(sb, envValue.replace("\\", "\\\\").replace("$", "\\$"));
            } else if (defaultValue != null) {
                // 使用默认值，并递归解析（默认值中可能包含其他环境变量）
                String resolvedDefault = resolveEnvironmentVariables(defaultValue);
                matcher.appendReplacement(sb, resolvedDefault.replace("\\", "\\\\").replace("$", "\\$"));
            } else {
                // 环境变量不存在且无默认值，保留原始文本
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);

        String result = sb.toString();
        logger.debug("Resolved environment variables: '{}' -> '{}'", value, result);

        // 如果解析后仍包含未替换的 ${...} 占位符 → 说明不在 Jenkins 环境
        // 本地开发应使用相对路径，避免 file:///${...} 这种无效链接
        if (result.contains("${")) {
            logger.debug("Unresolved env vars detected in '{}', treating as non-Jenkins environment", result);
            return "";
        }

        return result;
    }

    /**
     * 自动检测并构建报告 URL
     * 注意：路径依赖 Jenkins publishHTML 配置，无法可靠猜测，故返回空使用相对链接
     */
    private String autoDetectReportUrl() {
        // 不再硬编码路径，因为 publishHTML reportName 可变
        // 让所有链接使用相对路径，兼容任何 Jenkins 发布配置
        LoggingConfigUtil.logDebugIfVerbose(logger, "No serenity.report.url configured, using relative links for all URLs");
        return "";
    }

    private String ensureTrailingSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    /**
     * Convert HTML filename to full URL.
     * Local env: returns relative link (e.g., "abc123.html")
     * Jenkins env: returns absolute URL based on reportUrl (e.g., "http://jenkins/.../Serenity_20Summary_20Report/abc123.html")
     */
    private String buildHtmlLink(String htmlFile) {
        if (htmlFile.startsWith("http://") || htmlFile.startsWith("https://")) {
            return htmlFile;
        }

        if (reportUrl != null && !reportUrl.isEmpty() && !reportUrl.contains("${")) {
            return ensureTrailingSlash(reportUrl) + htmlFile;
        }
        return htmlFile;
    }

    /** Build download URL for ZIP/CSV files. Uses reportUrl as base in Jenkins environment. */
    private String buildDownloadUrl(String fileName) {
        if (reportUrl != null && !reportUrl.isEmpty() && !reportUrl.contains("${")) {
            return ensureTrailingSlash(reportUrl) + fileName;
        }
        // 本地环境或 reportUrl 无效时使用相对路径
        return fileName;
    }

    private void init() {
        String actualReportDir = resolveReportDirectory(reportDir);
        LoggingConfigUtil.logInfoIfVerbose(logger, "Report directory: {}", actualReportDir);

        File dir = new File(actualReportDir);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (created) {
                logger.info("Created report directory: {}", actualReportDir);
            } else {
                logger.error("Failed to create report directory: {}", actualReportDir);
            }
        }

        this.reportTime = LocalDateTime.now();
        loadTestOutcomes(actualReportDir);
        loadFeatureHtmlMapping(actualReportDir);
        loadScenarioHtmlMapping(actualReportDir);
        calculateResultCounts();
        calculateDurations();
        loadErrorTypeRules();

        long total = getTotalTests();
        LoggingConfigUtil.logInfoIfVerbose(logger,
            "Loaded {} test outcomes, {} simple outcomes, {} total tests",
            testOutcomes.size(), simpleTestOutcomes.size(), total);

        if (total == 0) {
            logger.warn("No test outcomes found in '{}'. " +
                "Ensure serenity:aggregate has completed before summary report generation.", actualReportDir);
        }
    }

    /**
     * 解析报告目录，自动检测 Jenkins 环境并转换路径
     */
    private String resolveReportDirectory(String reportDir) {
        // 如果已经是绝对路径，直接使用
        File dir = new File(reportDir);
        if (dir.isAbsolute()) {
            return reportDir;
        }

        // 优先使用 user.dir (Java 当前工作目录)
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.trim().isEmpty()) {
            return new File(userDir, reportDir).getAbsolutePath();
        }

        // 其次检测 WORKSPACE 环境变量
        String workspace = System.getenv("WORKSPACE");
        if (workspace != null && !workspace.trim().isEmpty()) {
            return new File(workspace, reportDir).getAbsolutePath();
        }

        // 返回相对路径，让 JVM 解析
        return reportDir;
    }

    public void generateSummaryReport() {
        try {
            // 生成带时间戳的文件名
            String timestamp = reportTime.format(TIMESTAMP_FORMATTER);
            csvFileName = CSV_FILE_PREFIX + "-" + timestamp + ".csv";
            zipFileName = ZIP_FILE_PREFIX + "-" + timestamp + ".zip";

            // 解析实际的报告目录（自动处理 Jenkins 环境）
            String actualReportDir = resolveReportDirectory(reportDir);

            long total = getTotalTests();
            LoggingConfigUtil.logInfoIfVerbose(logger,
                "Generating summary report: {} total tests, dir: {}", total, actualReportDir);

            // 生成 HTML 报告
            String html = buildFullNativeHtml();
            Path output = Paths.get(actualReportDir, SUMMARY_FILE);
            Files.write(output, html.getBytes(StandardCharsets.UTF_8));
            System.out.println("       - Summary report: " + output.toUri());

            // 生成 CSV 文件
            generateCsvReport(actualReportDir);

            // 生成 ZIP 包
            generateZipPackage(actualReportDir);

            long pass = count(TestResult.SUCCESS);
            if (total > 0 && pass == total) {
                logger.info("All {} tests passed! Summary report generated at: {}", total, output.toAbsolutePath());
            } else if (total > 0) {
                long fail = count(TestResult.FAILURE) + count(TestResult.ERROR);
                logger.info("Summary report generated: {} passed, {} failed out of {}", 
                    pass, fail, total);
            }
        } catch (Exception e) {
            logger.error("Failed to generate summary report", e);
        }
    }

    private void generateCsvReport(String actualReportDir) {
        try {
            Path csvPath = Paths.get(actualReportDir, csvFileName);
            StringBuilder csv = new StringBuilder();

            // CSV 头部
            csv.append("Feature,Scenario,Result,Duration (ms),Error Message\n");

            // 测试数据
            for (TestOutcome t : testOutcomes) {
                String feature = getFeature(t);
                String result = t.getResult().name();
                long duration = t.getDuration();
                String error = t.getTestFailureMessage() != null ?
                    escapeCsv(t.getTestFailureMessage()) : "";
                csv.append(String.format("%s,%s,%s,%d,%s\n",
                    escapeCsv(feature), escapeCsv(t.getName()), result, duration, error));
            }

            for (SimpleTestOutcome t : simpleTestOutcomes) {
                String result = t.result.name();
                long duration = t.duration;
                String error = t.result != TestResult.SUCCESS ? "Test failed" : "";
                csv.append(String.format("%s,%s,%s,%d,%s\n",
                    escapeCsv(t.featureName), escapeCsv(t.title), result, duration, error));
            }

            Files.write(csvPath, csv.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.error("Failed to generate CSV report", e);
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        // 如果包含逗号、引号或换行，需要用引号包裹并转义引号
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private void generateZipPackage(String actualReportDir) {
        try {
            Path zipPath = Paths.get(actualReportDir, zipFileName);
            File reportDirectory = new File(actualReportDir);

            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
                // 递归打包整个目录（不包含顶层目录名）
                zipDirectory(reportDirectory, "", zos);
            }
        } catch (Exception e) {
            logger.error("Failed to generate ZIP package", e);
        }
    }
    
    private void zipDirectory(File folder, String parentFolder, ZipOutputStream zos) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            // 跳过所有 ZIP 文件和 CSV 文件
            if ((file.getName().startsWith(ZIP_FILE_PREFIX) && file.getName().endsWith(".zip")) ||
                (file.getName().startsWith(CSV_FILE_PREFIX) && file.getName().endsWith(".csv"))) continue;
            
            if (file.isDirectory()) {
                // 递归处理子目录
                String subFolder = parentFolder.isEmpty() ? file.getName() : parentFolder + "/" + file.getName();
                zipDirectory(file, subFolder, zos);
            } else {
                // 添加文件到 ZIP
                String entryName = parentFolder.isEmpty() ? file.getName() : parentFolder + "/" + file.getName();
                addToZip(zos, file.toPath(), entryName);
            }
        }
    }
    
    private void addToZip(ZipOutputStream zos, Path file, String entryName) throws IOException {
        if (!Files.exists(file)) return;
        
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        Files.copy(file, zos);
        zos.closeEntry();
    }

    private String buildFullNativeHtml() {
        StringBuilder sb = new StringBuilder();
        
        sb.append("<!doctype html>\n");
        sb.append("<html>\n");
        sb.append("<head>\n");
        sb.append("    <meta name=\"viewport\" content=\"width=device-width\">\n");
        sb.append("    <meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">\n");
        sb.append("    <title>").append(escape(reportTitle)).append(" - Test Report</title>\n");
        sb.append(getFullCss());
        sb.append("</head>\n");
        sb.append("<body style=\"font-family:Helvetica, sans-serif;-webkit-font-smoothing:antialiased;font-size:14px;line-height:1.4;-ms-text-size-adjust:100%;-webkit-text-size-adjust:100%;background-color:#f6f6f6;margin:0;padding:0;width:100%;overflow-x:hidden;\">\n");

        // 外层结构
        sb.append("<table border=\"0\" cellpadding=\"0\" cellspacing=\"0\" class=\"body\" style=\"border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;\">\n");
        sb.append("    <tr>\n");
        sb.append("        <td style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;\">&nbsp;</td>\n");
        sb.append("        <td class=\"container\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;margin:0 auto !important;max-width:600px;padding:0;padding-top:24px;width:90%;\">\n");
        sb.append("            <div class=\"content\" style=\"box-sizing:border-box;display:block;margin:0 auto;max-width:100%;padding:0;\">\n");
        sb.append("                <table border=\"0\" cellpadding=\"0\" cellspacing=\"0\" class=\"main\" style=\"border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;background:#fff;border-radius:4px;width:100%;\">\n");

        appendAlertBar(sb);
        appendSummarySection(sb);
        appendViewFullReportButton(sb);
        appendCoverageSection(sb);
        appendFailureOverview(sb);
        appendFailureAndResultList(sb);

        sb.append("                    <tr class=\"footer\" style=\"clear:both;padding-top:24px;text-align:center;width:100%;\">\n");
        sb.append("                        <td style=\"font-family:Helvetica, sans-serif;vertical-align:top;color:#999999;font-size:12px;text-align:center;\">\n");
        sb.append("                            Report produced by Serenity BDD\n");
        sb.append("                        </td>\n");
        sb.append("                    </tr>\n");

        sb.append("                </table>\n");
        sb.append("            </div>\n");
        sb.append("        </td>\n");
        sb.append("        <td style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;\">&nbsp;</td>\n");
        sb.append("    </tr>\n");
        sb.append("</table>\n");
        sb.append("</body>\n");
        sb.append("</html>");

        return sb.toString();
    }

    private String getFullCss() {
        return """
                <style media="all" type="text/css">
                    * { box-sizing: border-box; }
                    
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                        -webkit-font-smoothing: antialiased;
                        font-size: 14px;
                        line-height: 1.5;
                        -ms-text-size-adjust: 100%;
                        -webkit-text-size-adjust: 100%;
                        background-color: #f6f6f6;
                        margin: 0;
                        padding: 20px;
                        width: 100%;
                        min-height: 100vh;
                    }
                    
                    /* ====== 响应式容器：适配 Jenkins iframe 和直接访问 ====== */
                    .body-table {
                        width: 100%;
                        min-height: 100vh;
                    }
                    
                    .container {
                        margin: 0 auto !important;
                        width: 95% !important;
                        max-width: 1200px !important;  /* 宽屏：适合Jenkins浏览器查看 */
                        padding: 20px !important;
                    }
                    
                    /* 小屏幕回退（移动端）*/
                    @media (max-width: 768px) {
                        .container {
                            width: 100% !important;
                            padding: 10px !important;
                        }
                        body { padding: 10px; }
                    }
                    
                    table {
                        border-collapse: separate;
                        mso-table-lspace: 0pt;
                        mso-table-rspace: 0pt;
                        width: 100%;
                    }
                    table td {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                        font-size: 14px;
                        vertical-align: top;
                    }
                    .content {
                        box-sizing: border-box;
                        display: block;
                        margin: 0 auto;
                        max-width: 100%;
                        padding: 0;
                    }
                    .main {
                        background: #fff;
                        border-radius: 8px;  /* 更圆润的边角 */
                        box-shadow: 0 2px 12px rgba(0,0,0,0.08);  /* 轻微阴影增强层次感 */
                        width: 100%;
                        overflow: hidden;  /* 防止内容溢出 */
                    }
                    .wrapper {
                        box-sizing: border-box;
                        padding: 24px;
                    }
                    .compact-wrapper {
                        box-sizing: border-box;
                        padding-left: 24px;
                        padding-right: 24px;
                        padding-top: 12px;
                        padding-bottom: 12px;
                    }
                    .content-block { 
                        padding-top: 0; 
                        padding-bottom: 20px; 
                    }
                    .flush-top { margin-top: 0; padding-top: 0; }
                    .flush-bottom { margin-bottom: 0; padding-bottom: 0; }
                    .header { margin-bottom: 16px; margin-top: 0; width: 100%; }
                    .footer { 
                        clear: both; 
                        padding: 20px; 
                        text-align: center; 
                        width: 100%; 
                        background-color: #f8f9fa;  /* 浅灰背景区分footer */
                        border-top: 1px solid #e9ecef;
                    }
                    .footer td,.footer p,.footer span,.footer a { 
                        color:#6c757d; 
                        font-size:13px; 
                        text-align:center; 
                    }
                    .section-callout { background-color:#1abc9c; color:#ffffff; }
                    .section-callout-subtle { background-color:#f7f7f7; border-bottom:1px solid #e9e9e9; border-top:1px solid #e9e9e9; }
                    .alert { min-width:100%; }
                    .alert td { 
                        border-radius:8px 8px 0 0; 
                        color:#ffffff; 
                        font-size:16px;  /* 稍大的标题 */
                        font-weight:600;
                        padding:28px; 
                        text-align:center;
                    }
                    .alert.alert-success td { 
                        background: linear-gradient(135deg, #5FB0E0 0%, #4A9BD1 100%);  /* 渐变背景 */
                    }
                    .align-center { text-align:center; }
                    .align-right { text-align:right; }
                    .align-left { text-align:left; }
                    .text-link { 
                        color:#007bff !important; 
                        text-decoration:none !important;
                        transition: color 0.2s ease;
                    }
                    .text-link:hover {
                        text-decoration: underline !important;
                        color: #0056b3 !important;
                    }
                    
                    /* ====== 按钮样式增强 ====== */
                    a[style*="background"] {
                        display: inline-block !important;
                        padding: 10px 20px !important;
                        border-radius: 6px !important;
                        font-weight: 600 !important;
                        text-decoration: none !important;
                        transition: all 0.2s ease !important;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.1) !important;
                    }
                    a[style*="background"]:hover {
                        transform: translateY(-1px);
                        box-shadow: 0 4px 8px rgba(0,0,0,0.15) !important;
                    }
                    .legend { border:1px solid #acb1b9; margin-top:20px; }
                    .legend .overview { font-weight:bold; font-size:1.1em; color:#515151; background-color:#EBEBEB; padding:4px 0 4px 5px; }
                    .legend td { vertical-align:middle; }
                    .legend-key { font-weight:bold; padding-left:4px; font-size:0.9em; white-space:nowrap; border-top:solid 0.5px #DDDDDD; border-bottom:solid 0.5px #DDDDDD; border-left:solid 0.5px #DDDDDD; }
                    .legend-result { font-weight:bold; font-size:0.9em; padding-left:4px; text-align:right; border-top:solid 0.5px #DDDDDD; border-bottom:solid 0.5px #DDDDDD; border-right:solid 0.5px #DDDDDD; }
                    .legend-result span { border-radius:4px; padding:2px 4px; white-space:nowrap; }
                    .environment { border:1px solid #acb1b9; background-color:#EBEBEB; }
                    .environment td { color:#69727f; font-size:0.9em; text-align:center; }
                    .result-bar td { text-align:center; font-size:0.9em; }
                    .success-background { background-color:#52B255; color:white; }
                    .pending-background { background-color:#5FB0E0; color:white; }
                    .ignored-background { background-color:#C9C9C9; color:black; }
                    .failure-background { background-color:#f44336; color:white; }
                    .error-background { background-color:#ECA43A; color:white; }
                    .compromised-background { background-color:#9C77AD; color:white; }
                    .for-success { color:#52B255; }
                    .for-passing { color:#52B255; }
                    .for-failure { color:#f44336; }
                    .for-error { color:#ECA43A; }
                    .for-pending { color:#5FB0E0; }
                    .for-ignored { color:#acb1b9; }
                    .for-compromised { color:#9C77AD; }
                    .failure-scoreboard { border:1px solid #acb1b9; border-collapse:separate; }
                    .failure-scoreboard th { text-align:left; }
                    .failure-scoreboard tr:nth-child(even) { background:#f5f5f5 }
                    .summary-bar { width:100%; }
                    .summary { height:30px; font-size:1em; font-weight:bold; text-align:center; padding-top:10px; vertical-align:middle; white-space:nowrap; }
                    .timings { border:0 solid #acb1b9; padding:0 5px; }
                    .timings th { color:grey; text-align:right; font-size:0.9em; }
                    .timings td { color:grey; text-align:right; font-size:0.9em; }
                    .test-results-table { 
                        border:1px solid #dee2e6; 
                        margin-bottom:20px; 
                        border-collapse:collapse;
                        border-radius: 8px;  /* 圆角 */
                        overflow: hidden;   /* 防止圆角被裁剪 */
                        box-shadow: 0 1px 3px rgba(0,0,0,0.05);  /* 轻微阴影 */
                    }
                    .test-results-table th { 
                        border:1px solid #dee2e6; 
                        padding:12px 16px; 
                        background: linear-gradient(180deg, #f8f9fa 0%, #e9ecef 100%);
                        text-align:left;
                        font-weight: 600;
                        font-size: 13px;
                        color: #495057;
                        text-transform: uppercase;
                        letter-spacing: 0.5px;
                    }
                    .test-results-table td { 
                        border:1px solid #dee2e6; 
                        padding:12px 16px;
                    }
                    .test-results-table tr:hover {
                        background-color: #f8f9fa;  /* 悬停高亮 */
                    }
                    .categories tr td { border:0.5px solid #dddddd; }
                    tr.categories, th.categories, td.categories { border:0.5px solid grey; }
                    .failure-list { border:1px solid grey; }
                    .feature { font-style:italic; }
                    .scenarioName { padding-left:8px; min-width:4em; width:55%; }
                    .tag-title { text-transform:capitalize; }
                    .tag-subtitle { text-transform:capitalize; width:50%; }
                    h3 { font-size:20px; text-align:center; color:#222222; font-weight:400; margin:0; }
                    h4 { font-size:18px; margin-top:20px; color:#222222; font-weight:500; }
                    .summary-bar-cell { height:2em; vertical-align:middle; }
                    .count-badge { padding:0px; width:25px; text-align:center; }
                    .frequent-failure { font-weight:bold; }
                </style>""";
    }

    private void appendAlertBar(StringBuilder sb) {
        sb.append("                    <tr>\n");
        sb.append("                        <td style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;\">\n");
        sb.append("                            <table border=\"0\" cellpadding=\"0\" cellspacing=\"0\" class=\"alert alert-success\" style=\"border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;min-width:100%;\">\n");
        sb.append("                                <tr>\n");
        sb.append("                                    <td align=\"center\" style=\"font-family:Helvetica, sans-serif;vertical-align:top;border-radius:4px 4px 0 0;color:#ffffff;background-color:#5FB0E0;font-size:1.3em;font-weight:400;padding:24px;text-align:center;\">\n");
        sb.append("                                        <span class=\"test-suite-title\" style=\"font-size:1.3em;color:white;font-weight:bold;\"><span>").append(escape(reportTitle)).append("</span></span>\n");
        sb.append("                                    </td>\n");
        sb.append("                                </tr>\n");
        sb.append("                            </table>\n");
        sb.append("                        </td>\n");
        sb.append("                    </tr>\n");
    }

    private void appendSummarySection(StringBuilder sb) {
        long total = getTotalTests();
        long pass = count(TestResult.SUCCESS);
        long fail = count(TestResult.FAILURE);
        long error = count(TestResult.ERROR);
        long pending = count(TestResult.PENDING);
        long ignored = count(TestResult.IGNORED);
        long compromised = count(TestResult.COMPROMISED);
        long skipped = count(TestResult.SKIPPED);

        sb.append("                    <tr>\n");
        sb.append("                        <td class=\"wrapper\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding:24px;\">\n");
        sb.append("                            <table border=\"0\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;\">\n");
        sb.append("                                <tr>\n");
        sb.append("                                    <td style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;\">\n");

        // Summary bar
        sb.append("                                        <table cellspacing=\"0\" cellpadding=\"0\" class=\"summary-bar\" style=\"border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;\">\n");
        sb.append("                                            <tr>\n");

        int[] widths = calculateBarWidths(total, pass, pending, ignored, fail, error, compromised);
        appendBarCell(sb, "success-background", widths[0], pass, total, "passing tests");
        appendBarCell(sb, "pending-background", widths[1], pending, total, "pending tests");
        appendBarCell(sb, "ignored-background", widths[2], ignored, total, "ignored tests");
        appendBarCell(sb, "failure-background", widths[3], fail, total, "failing tests");
        appendBarCell(sb, "error-background", widths[4], error, total, "broken tests");
        appendBarCell(sb, "compromised-background", widths[5], compromised, total, "compromised tests");

        sb.append("                                            </tr>\n");
        sb.append("                                        </table>\n");

        // Legend overview
        sb.append("                                        <table cellspacing=\"0\" cellpadding=\"2\" class=\"legend\" style=\"border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;border:1px solid #acb1b9;margin-top:20px;\">\n");
        sb.append("                                            <tr>\n");
        sb.append("                                                <td class=\"overview\" colspan=\"6\" style=\"font-family:Helvetica, sans-serif;vertical-align:middle;font-weight:bold;font-size:1.1em;color:#515151;background-color:#EBEBEB;padding:4px 0 4px 5px;\"><span>").append(total).append(" test").append(total != 1 ? "s" : "").append(" executed on</span>\n");
        sb.append("                                                    <span>").append(reportTime.format(FORMATTER)).append("</span>\n");
        sb.append("                                                </td>\n");
        sb.append("                                            </tr>\n");
        sb.append("                                        </table>\n");

        // Result counts
        sb.append("                                        <table cellspacing=\"0\" cellpadding=\"2\" style=\"border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;\">\n");
        sb.append("                                            <tr>\n");
        appendLegendRow(sb, "Passing", pass, "for-passing", "success-badge");
        appendLegendRow(sb, "Pending", pending, "for-pending", "pending-badge");
        appendLegendRow(sb, "Ignored", ignored, "for-ignored", "ignored-badge");
        sb.append("                                            </tr>\n");
        sb.append("                                            <tr>\n");
        appendLegendRow(sb, "Failing", fail, "for-failure", "failure-badge");
        appendLegendRow(sb, "Broken", error, "for-error", "error-badge");
        appendLegendRow(sb, "Compromised", compromised, "for-compromised", "compromised-badge");
        sb.append("                                            </tr>\n");
        sb.append("                                        </table>\n");

        // Timings
        sb.append("                                        <table class=\"timings\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;padding:0 5px;\">\n");
        sb.append("                                            <tr>\n");
        sb.append("                                                <th style=\"color:grey;text-align:right;font-size:0.9em;\">Total test execution time</th>\n");
        sb.append("                                                <th style=\"color:grey;text-align:right;font-size:0.9em;\">Total clock time</th>\n");
        sb.append("                                                <th style=\"color:grey;text-align:right;font-size:0.9em;\">Average test execution time</th>\n");
        sb.append("                                                <th style=\"color:grey;text-align:right;font-size:0.9em;\">Max test execution time</th>\n");
        sb.append("                                                <th style=\"color:grey;text-align:right;font-size:0.9em;\">Min test execution time</th>\n");
        sb.append("                                            </tr>\n");
        sb.append("                                            <tr>\n");
        sb.append("                                                <td style=\"font-family:Helvetica, sans-serif;vertical-align:top;color:grey;text-align:right;font-size:0.9em;\">").append(format(totalDuration)).append("</td>\n");
        sb.append("                                                <td style=\"font-family:Helvetica, sans-serif;vertical-align:top;color:grey;text-align:right;font-size:0.9em;\">").append(format(clockTime)).append("</td>\n");
        sb.append("                                                <td style=\"font-family:Helvetica, sans-serif;vertical-align:top;color:grey;text-align:right;font-size:0.9em;\">").append(format((long) avgDuration)).append("</td>\n");
        sb.append("                                                <td style=\"font-family:Helvetica, sans-serif;vertical-align:top;color:grey;text-align:right;font-size:0.9em;\">").append(format(maxDuration)).append("</td>\n");
        sb.append("                                                <td style=\"font-family:Helvetica, sans-serif;vertical-align:top;color:grey;text-align:right;font-size:0.9em;\">").append(format(minDuration)).append("</td>\n");
        sb.append("                                            </tr>\n");
        sb.append("                                        </table>\n");

        sb.append("                                    </td>\n");
        sb.append("                                </tr>\n");
        sb.append("                            </table>\n");
        sb.append("                        </td>\n");
        sb.append("                    </tr>\n");
    }

    private int[] calculateBarWidths(long total, long... counts) {
        int[] widths = new int[counts.length];
        if (total == 0) return widths;

        int used = 0;
        for (int i = 0; i < counts.length; i++) {
            widths[i] = (int) ((counts[i] * 100) / total);
            used += widths[i];
        }

        // Distribute remaining
        int remaining = 100 - used;
        for (int i = 0; i < widths.length && remaining > 0; i++) {
            if (widths[i] > 0) {
                widths[i]++;
                remaining--;
            }
        }

        return widths;
    }

    private void appendBarCell(StringBuilder sb, String cssClass, int width, long count, long total, String title) {
        if (width > 0) {
            sb.append("                                                <td class=\"").append(cssClass).append(" summary summary-bar-cell\" width=\"").append(width).append("%\" valign=\"middle\" align=\"center\" style=\"padding:").append(count > 0 ? "8px" : "0px").append(";\">\n");
            if (count > 0) {
                int percent = total > 0 ? (int)((count * 100) / total) : 0;
                sb.append("                                                        <span class=\"summary\" title=\"").append(count).append(" ").append(title).append("\">").append(percent).append("%</span>\n");
            }
            sb.append("                                                </td>\n");
        } else {
            sb.append("                                                <td class=\"").append(cssClass).append(" summary summary-bar-cell\" width=\"0%\" valign=\"middle\" align=\"center\" style=\"padding:0px;\"></td>\n");
        }
    }

    private void appendLegendRow(StringBuilder sb, String label, long count, String colorClass, String badgeClass) {
        sb.append("                                                <td class=\"").append(colorClass).append(" legend-key legend-label\" width=\"30%\" style=\"font-family:Helvetica, sans-serif;vertical-align:top;font-weight:bold;padding-left:4px;font-size:0.9em;white-space:nowrap;border-top:solid 0.5px #DDDDDD;border-bottom:solid 0.5px #DDDDDD;border-left:solid 0.5px #DDDDDD;").append(getColorStyle(colorClass)).append("\">").append(label).append("</td>\n");
        sb.append("                                                <td class=\"").append(colorClass).append(" legend-result\" style=\"font-family:Helvetica, sans-serif;vertical-align:top;font-weight:bold;font-size:0.9em;padding-left:4px;border-top:solid 0.5px #DDDDDD;border-bottom:solid 0.5px #DDDDDD;border-right:solid 0.5px #DDDDDD;text-align:right;").append(getColorStyle(colorClass)).append("\">\n");
        sb.append("                                                    <span class=\"").append(badgeClass).append("\" style=\"border-radius:4px;padding:2px 4px;white-space:nowrap;\">").append(count).append("</span>\n");
        sb.append("                                                </td>\n");
    }

    private String getColorStyle(String colorClass) {
        return switch (colorClass) {
            case "for-passing", "for-success" -> "color:#52B255;";
            case "for-pending" -> "color:#5FB0E0;";
            case "for-ignored" -> "color:#acb1b9;";
            case "for-failure" -> "color:#f44336;";
            case "for-error" -> "color:#ECA43A;";
            case "for-compromised" -> "color:#9C77AD;";
            default -> "";
        };
    }

    private void appendViewFullReportButton(StringBuilder sb) {
        sb.append("                    <tr>\n");
        sb.append("                        <td class=\"compact-wrapper\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding-left:24px;padding-right:24px;padding-top:4px;padding-bottom:4px;\">\n");
        sb.append("                            <div style=\"display:flex;flex-wrap:wrap;gap:10px;align-items:center;\">\n");

        String fullReportLink = this.fullReportUrl;
        sb.append("                                <a style=\"text-transform:uppercase;color:#8accf2;text-decoration:none;font-weight:bold;padding:10px 24px;background:#316d91;border-radius:4px;font-size:14px;display:inline-block;white-space:nowrap;\" href=\"").append(fullReportLink).append("\" target=\"_blank\">View full report</a>\n");

        String zipLink = buildDownloadUrl(zipFileName);
        sb.append("                                <a style=\"text-transform:uppercase;color:#ffffff;text-decoration:none;font-weight:bold;padding:10px 24px;background:#52B255;border-radius:4px;font-size:14px;display:inline-block;white-space:nowrap;\" href=\"").append(zipLink).append("\" target=\"_blank\">Download ZIP</a>\n");

        sb.append("                            </div>\n");
        sb.append("                        </td>\n");
        sb.append("                    </tr>\n");
    }

    private void appendCoverageSection(StringBuilder sb) {
        sb.append("                    <tr>\n");
        sb.append("                        <td class=\"compact-wrapper\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding-left:24px;padding-right:24px;padding-top:4px;padding-bottom:4px;\">\n");
        sb.append("                            <h3 style=\"color:#222222;font-family:Helvetica, sans-serif;font-weight:400;line-height:1.4;margin:0;font-size:20px;text-align:center;\">Functional Coverage</h3>\n");
        sb.append("                        </td>\n");
        sb.append("                    </tr>\n");
        sb.append("                    <tr>\n");
        sb.append("                        <td class=\"compact-wrapper\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding-left:24px;padding-right:24px;padding-top:4px;padding-bottom:4px;\">\n");
        sb.append("                            <h4 class=\"tag-title\" style=\"color:#222222;font-family:Helvetica, sans-serif;line-height:1.4;margin:0;font-weight:500;font-size:18px;margin-top:20px;text-transform:capitalize;\">Feature</h4>\n");
        sb.append("                            <table class=\"test-results-table categories\" style=\"border-collapse:collapse;width:100%;border:1px solid grey;margin-bottom:26px;\">\n");
        sb.append("                                <tr>\n");
        sb.append("                                    <th width=\"60%\">Category</th>\n");
        sb.append("                                    <th class=\"tag-coverage\" width=\"5%\" style=\"text-align:left;white-space:nowrap;\">Tests</th>\n");
        sb.append("                                    <th class=\"tag-coverage\" width=\"5%\" style=\"text-align:left;white-space:nowrap;\">Pass</th>\n");
        sb.append("                                    <th class=\"tag-coverage\" style=\"text-align:left;white-space:nowrap;\">Results</th>\n");
        sb.append("                                </tr>\n");

        Map<String, FeatureStats> statsMap = calculateFeatureStats();
        for (Map.Entry<String, FeatureStats> entry : statsMap.entrySet()) {
            String feature = entry.getKey();
            FeatureStats stats = entry.getValue();
            String htmlLink = buildHtmlLink(featureToHtmlMap.getOrDefault(feature, "index.html"));

            sb.append("                                <tr>\n");
            sb.append("                                    <td class=\"tag-subtitle categories\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;text-transform:capitalize;width:50%;border:0.5px solid #dddddd;\"><a href=\"").append(htmlLink).append("\" target=\"_blank\">").append(escape(feature)).append("</a></td>\n");
            sb.append("                                    <td style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;border:0.5px solid #dddddd;\">").append(stats.total).append("</td>\n");
            sb.append("                                    <td style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;border:0.5px solid #dddddd;\">").append(stats.passPercent()).append("%</td>\n");
            sb.append("                                    <td style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;border:0.5px solid #dddddd;\">\n");

            // Result bar - 显示通过/失败/错误的混合比例
            int passWidth = stats.total > 0 ? (stats.passed * 100) / stats.total : 0;
            int failWidth = stats.total > 0 ? (stats.failed * 100) / stats.total : 0;
            int errorWidth = stats.total > 0 ? (stats.error * 100) / stats.total : 0;

            sb.append("                                        <table cellspacing=\"0\" cellpadding=\"0\" class=\"result-bar\" width=\"100%\" style=\"border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;\">\n");
            sb.append("                                            <tr>\n");

            // 通过部分（绿色）
            if (passWidth > 0) {
                sb.append("                                                <td class=\"success-background\" title=\"").append(stats.passed).append(" passing tests (").append(passWidth).append("%)\" width=\"").append(passWidth).append("%\" style=\"font-family:Helvetica, sans-serif;vertical-align:middle;background-color:#52B255;color:white;text-align:center;font-size:0.9em;padding:4px;min-width:20px;\"><span>").append(stats.passed).append("</span></td>\n");
            }
            // 失败部分（红色）
            if (failWidth > 0) {
                sb.append("                                                <td class=\"failure-background\" title=\"").append(stats.failed).append(" failing tests (").append(failWidth).append("%)\" width=\"").append(failWidth).append("%\" style=\"font-family:Helvetica, sans-serif;vertical-align:middle;background-color:#f44336;color:white;text-align:center;font-size:0.9em;padding:4px;min-width:20px;\"><span>").append(stats.failed).append("</span></td>\n");
            }
            // 错误部分（橙色）
            if (errorWidth > 0) {
                sb.append("                                                <td class=\"error-background\" title=\"").append(stats.error).append(" broken tests (").append(errorWidth).append("%)\" width=\"").append(errorWidth).append("%\" style=\"font-family:Helvetica, sans-serif;vertical-align:middle;background-color:#ECA43A;color:white;text-align:center;font-size:0.9em;padding:4px;min-width:20px;\"><span>").append(stats.error).append("</span></td>\n");
            }
            // 全部通过时显示绿色背景
            if (passWidth == 100 && stats.total > 0) {
                sb.append("                                                <td class=\"success-background\" title=\"100% passing\" width=\"100%\" style=\"font-family:Helvetica, sans-serif;vertical-align:middle;background-color:#52B255;color:white;text-align:center;font-size:0.9em;padding:4px;\"><span title=\"All ").append(stats.passed).append(" tests passed\">").append(stats.passPercent()).append("%</span></td>\n");
            }

            sb.append("                                            </tr>\n");
            sb.append("                                        </table>\n");
            sb.append("                                    </td>\n");
            sb.append("                                </tr>\n");
        }

        sb.append("                            </table>\n");
        sb.append("                        </td>\n");
        sb.append("                    </tr>\n");
    }

    private Map<String, FeatureStats> calculateFeatureStats() {
        Map<String, FeatureStats> map = new LinkedHashMap<>();
        for (TestOutcome t : testOutcomes) {
            String f = getFeature(t);
            map.computeIfAbsent(f, k -> new FeatureStats()).add(t.getResult());
        }
        for (SimpleTestOutcome t : simpleTestOutcomes) {
            map.computeIfAbsent(t.featureName, k -> new FeatureStats()).add(t.result);
        }
        return map;
    }

    private static class FeatureStats {
        int total = 0, passed = 0, failed = 0, error = 0;
        void add(TestResult r) {
            total++;
            if (r == TestResult.SUCCESS) passed++;
            else if (r == TestResult.FAILURE) failed++;
            else if (r == TestResult.ERROR) error++;
        }
        int passPercent() { return total == 0 ? 0 : (passed * 100) / total; }
    }

    private void appendFailureOverview(StringBuilder sb) {
        Map<String, Integer> failureCounts = new LinkedHashMap<>();
        Map<String, FeatureFailureStats> featureFailures = new LinkedHashMap<>();

        for (TestOutcome t : testOutcomes) {
            if (t.getResult() == TestResult.FAILURE || t.getResult() == TestResult.ERROR) {
                String error = t.getTestFailureMessage() != null ? t.getTestFailureMessage() : "Test failed";
                String errorType = extractErrorType(error);
                failureCounts.merge(errorType, 1, Integer::sum);

                String feature = getFeature(t);
                featureFailures.computeIfAbsent(feature, k -> new FeatureFailureStats()).increment();
            }
        }
        for (SimpleTestOutcome t : simpleTestOutcomes) {
            if (t.result == TestResult.FAILURE || t.result == TestResult.ERROR) {
                String error = t.errorMessage != null && !t.errorMessage.isEmpty() ? t.errorMessage : "Test failed";
                String errorType = extractErrorType(error);
                failureCounts.merge(errorType, 1, Integer::sum);
                featureFailures.computeIfAbsent(t.featureName, k -> new FeatureFailureStats()).increment();
            }
        }

        if (failureCounts.isEmpty()) {
            return;
        }

        sb.append("                    <tr>\n");
        sb.append("                        <td class=\"compact-wrapper\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding-left:24px;padding-right:24px;padding-top:4px;padding-bottom:4px;\">\n");
        sb.append("                            <h3 style=\"color:#222222;font-family:Helvetica, sans-serif;font-weight:400;line-height:1.4;margin:0;font-size:20px;text-align:center;\">Test Failure Overview</h3>\n");
        sb.append("                        </td>\n");
        sb.append("                    </tr>\n");
        sb.append("                    <tr>\n");
        sb.append("                        <td class=\"compact-wrapper\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding-left:24px;padding-right:24px;padding-top:4px;padding-bottom:4px;\">\n");
        sb.append("                            <table style=\"border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;\">\n");
        sb.append("                                <tr>\n");

        // Most Frequent Failures
        sb.append("                                    <td style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;\">\n");
        sb.append("                                        <table class=\"failure-scoreboard\" style=\"border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;border-style:solid;border-width:1px;border-color:#acb1b9;\">\n");
        sb.append("                                            <tr>\n");
        sb.append("                                                <th colspan=\"2\" style=\"text-align:left;\">Most Frequent Failures</th>\n");
        sb.append("                                            </tr>\n");

        for (Map.Entry<String, Integer> entry : failureCounts.entrySet()) {
            sb.append("                                            <tr class=\"for-failure\" style=\"color:#f44336;\">\n");
            sb.append("                                                <td width=\"100%\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;\" class=\"frequent-failure for-error\">").append(escape(entry.getKey())).append("</td>\n");
            sb.append("                                                <td style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;\"><span class='count-badge for-failure' style=\"color:#f44336;\">").append(entry.getValue()).append("</span></td>\n");
            sb.append("                                            </tr>\n");
        }

        sb.append("                                        </table>\n");
        sb.append("                                    </td>\n");

        // Most Unstable Features
        sb.append("                                    <td style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;\">\n");
        sb.append("                                        <table class=\"failure-scoreboard\" style=\"border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;border-style:solid;border-width:1px;border-color:#acb1b9;\">\n");
        sb.append("                                            <tr>\n");
        sb.append("                                                <th style=\"text-align:left;\">Most Unstable Features</th>\n");
        sb.append("                                                <th style=\"text-align:left;\">Fails</th>\n");
        sb.append("                                            </tr>\n");

        for (Map.Entry<String, FeatureFailureStats> entry : featureFailures.entrySet()) {
            sb.append("                                            <tr class=\"for-failure\" style=\"color:#f44336;\">\n");
            sb.append("                                                <td class=\"unstable-feature\" width=\"100%\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;\">").append(escape(entry.getKey())).append("</td>\n");
            sb.append("                                                <td style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;\"><span class='count-badge for-failure' style=\"color:#f44336;\">").append(entry.getValue().count).append("</span></td>\n");
            sb.append("                                            </tr>\n");
        }

        sb.append("                                        </table>\n");
        sb.append("                                    </td>\n");
        sb.append("                                </tr>\n");
        sb.append("                            </table>\n");

        // 错误类型饼图
        if (!failureCounts.isEmpty()) {
            appendErrorTypePieChart(sb, failureCounts);
        }

        sb.append("                        </td>\n");
        sb.append("                    </tr>\n");
    }

    /**
     * 生成错误类型分布饼图（纯 CSS conic-gradient）
     * 兼容 Outlook / Gmail / Apple Mail 等主流邮件客户端
     */
    private void appendErrorTypePieChart(StringBuilder sb, Map<String, Integer> failureCounts) {
        int total = failureCounts.values().stream().mapToInt(Integer::intValue).sum();
        String[] colors = {"#e53935", "#f44336", "#ef5350", "#e57373", "#ef9a9a",
                          "#ff8a65", "#ff7043", "#f06292", "#ba68c8", "#9575cd"};

        sb.append("                            <div style=\"margin-top:20px;text-align:center;\">\n");
        sb.append("                                <h4 style=\"margin:0 0 12px 0;font-size:16px;color:#333;\">Error Type Distribution</h4>\n");

        int pieSize = 200;
        int halfPie = pieSize / 2;
        int colorIdx = 0;

        if (failureCounts.size() == 1) {
            // 单分类 → 实心圆，中心显示标签+百分比
            Map.Entry<String, Integer> onlyEntry = failureCounts.entrySet().iterator().next();
            String label = escape(onlyEntry.getKey());
            double pct = (double) onlyEntry.getValue() / total * 100;

            sb.append("                                <div style=\"display:inline-block;width:")
              .append(pieSize).append("px;height:").append(pieSize)
              .append("px;border-radius:50%;background:").append(colors[0])
              .append(";box-shadow:0 2px 8px rgba(0,0,0,0.15);position:relative;\">\n");
            sb.append("                                    <div style=\"position:absolute;top:50%;left:50%")
              .append(";transform:translate(-50%,-50%);text-align:center;\">\n");
            sb.append("                                        <div style=\"font-size:13px;font-weight:bold;color:#fff;\">")
              .append(label).append("</div>\n");
            sb.append("                                        <div style=\"font-size:22px;font-weight:bold;color:#fff;margin-top:2px;\">")
              .append(String.format("%.0f", pct)).append("%</div>\n");
            sb.append("                                    </div>\n");
            sb.append("                                </div>\n");

        } else {
            // 多分类 → 饼图 + 环绕标签
            List<double[]> labelPositions = new ArrayList<>();
            StringBuilder gradient = new StringBuilder();
            double startAngle = -90;

            for (Map.Entry<String, Integer> entry : failureCounts.entrySet()) {
                double pct = (double) entry.getValue() / total * 100;
                double sweepAngle = pct * 360;
                double midAngle = startAngle + sweepAngle / 2;

                if (gradient.length() > 0) gradient.append(", ");
                gradient.append(colors[colorIdx % colors.length])
                  .append(" ").append(String.format("%.6f", startAngle)).append("%")
                  .append(" ").append(String.format("%.6f", startAngle + pct)).append("%");

                double labelRadius = halfPie + 28;
                double lx = halfPie + labelRadius * Math.cos(Math.toRadians(midAngle));
                double ly = halfPie + labelRadius * Math.sin(Math.toRadians(midAngle));
                labelPositions.add(new double[]{midAngle, lx, ly});

                startAngle += sweepAngle;
                colorIdx++;
            }

            // 饼图容器（加宽以容纳左右标签）
            sb.append("                                <div style=\"display:inline-block;")
              .append("position:relative;width:").append(pieSize + 120).append("px;height:")
              .append(pieSize).append("px;vertical-align:middle;\">\n");

            // 饼图本体（偏左）
            sb.append("                                    <div style=\"display:inline-block;")
              .append("position:absolute;left:").append((pieSize + 120 - pieSize) / 2)
              .append("px;top:0;width:").append(pieSize).append("px;height:").append(pieSize)
              .append("px;border-radius:50%;background:conic-gradient(")
              .append(gradient).append(");box-shadow:0 2px 8px rgba(0,0,0,0.15);")
              .append("\"></div>\n");

            // 标签
            int posIdx = 0;
            for (Map.Entry<String, Integer> entry : failureCounts.entrySet()) {
                double pct = (double) entry.getValue() / total * 100;
                double[] pos = labelPositions.get(posIdx++);
                double midAngle = pos[0];
                double lx = pos[1];
                double ly = pos[2];

                String textAlign = (midAngle > -90 && midAngle < 90) ? "text-align:left;margin-left:6px;" : "text-align:right;margin-right:6px;";

                sb.append("                                    <div style=\"position:absolute;")
                  .append("left:").append(round(lx)).append("px;top:").append(round(ly))
                  .append("px;transform:translate(-50%,-50%);white-space:nowrap;")
                  .append(textAlign).append("font-size:11px;color:#333;line-height:1.3;\">\n");
                sb.append("                                        <strong>")
                  .append(escape(entry.getKey()))
                  .append("</strong> ")
                  .append(String.format("%.0f", pct)).append("% (")
                  .append(entry.getValue()).append(")\n");
                sb.append("                                    </div>\n");
            }
            sb.append("                                </div>\n");
        }

        // 图例
        sb.append("                                <table style=\"margin:10px auto 4px auto;border-collapse:collapse;font-size:12px;\">\n");
        colorIdx = 0;
        for (Map.Entry<String, Integer> entry : failureCounts.entrySet()) {
            double pct = (double) entry.getValue() / total * 100;
            sb.append("                                    <tr>\n");
            sb.append("                                        <td style=\"padding:2px 10px 2px 0;white-space:nowrap;text-align:right;\">")
              .append("<span style=\"display:inline-block;width:10px;height:10px;background:")
              .append(colors[colorIdx % colors.length])
              .append(";border-radius:2px;vertical-align:middle;margin-right:6px;\"></span>")
              .append(escape(entry.getKey())).append("</td>\n");
            sb.append("                                        <td style=\"padding:2px 8px;color:#666;text-align:center;\">")
              .append(entry.getValue()).append("</td>\n");
            sb.append("                                        <td style=\"padding:2px 0;color:#999;\">")
              .append(String.format("%.0f", pct)).append("%</td>\n");
            sb.append("                                    </tr>\n");
            colorIdx++;
        }
        sb.append("                                </table>\n");
        sb.append("                            </div>\n");
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static class FeatureFailureStats {
        int count = 0;
        void increment() { count++; }
    }

    private String extractErrorType(String errorMessage) {
        if (errorMessage == null) return "Unknown error";

        for (ErrorTypeRule rule : errorTypeRules) {
            if (rule.matches(errorMessage)) {
                return rule.label;
            }
        }
        // 都没匹配上 → Other
        return "Other";
    }

    /**
     * 加载错误分类规则：优先用户配置 → 无则使用内置默认规则
     */
    private void loadErrorTypeRules() {
        errorTypeRules.clear();
        try {
            // 1. 尝试从 serenity.properties 或系统属性读取自定义配置
            String raw = System.getProperty("report.error.types");
            if (raw == null || raw.isEmpty()) {
                Properties props = new Properties();
                Path propPath = Paths.get("serenity.properties");
                if (Files.exists(propPath)) {
                    try (InputStream is = Files.newInputStream(propPath)) {
                        props.load(is);
                        raw = props.getProperty("report.error.types");
                    }
                }
            }

            // 2. 有自定义配置 → 解析 JSON 格式
            if (raw != null && !raw.trim().isEmpty()) {
                parseJsonErrorTypes(raw);
            }

            // 3. 始终加载内置默认分类（追加在用户配置之后，作为兜底）
            loadBuiltinErrorTypes();
        } catch (Exception e) {
            logger.debug("Failed to load error type rules: {}", e.getMessage());
            if (errorTypeRules.isEmpty()) {
                loadBuiltinErrorTypes();
            }
        }

        LoggingConfigUtil.logDebugIfVerbose(logger,
            "Loaded {} error types: {}", errorTypeRules.size(),
            errorTypeRules.stream().map(ErrorTypeRule::toString).collect(Collectors.joining(", ")));
    }

    /**
     * 解析 JSON 格式的错误类型配置
     * JSON 格式: [{"label":"Api Issue","keywords":["HTTP 500","502","api failed"]}, ...]
     * 兼容旧格式：如果 JSON 解析失败，回退到逗号分隔格式
     */
    private void parseJsonErrorTypes(String raw) {
        String trimmed = raw.trim();
        // 检测是否是 JSON 格式
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
            // 旧逗号分隔格式兼容
            for (String entry : trimmed.split(",")) {
                String e = entry.trim();
                if (!e.isEmpty()) {
                    errorTypeRules.add(new ErrorTypeRule(e));
                }
            }
            return;
        }

        try {
            // 用 Gson 解析（项目已有 Gson 依赖）
            Gson gson = new Gson();
            JsonArray arr = gson.fromJson(trimmed, JsonArray.class);
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject obj = arr.get(i).getAsJsonObject();
                    String label = obj.has("label") ? obj.get("label").getAsString() : null;
                    List<String> keywords = null;
                    if (obj.has("keywords") && obj.get("keywords").isJsonArray()) {
                        JsonArray kwArr = obj.getAsJsonArray("keywords");
                        keywords = new ArrayList<>();
                        for (int j = 0; j < kwArr.size(); j++) {
                            keywords.add(kwArr.get(j).getAsString());
                        }
                    }
                    if (label != null && !label.isEmpty()) {
                        errorTypeRules.add(new ErrorTypeRule(label, keywords));
                    }
                }
            }
        } catch (Exception ex) {
            logger.debug("JSON parse failed for report.error.types, trying legacy format: {}", ex.getMessage());
            // 回退到逗号分隔
            for (String entry : trimmed.split(",")) {
                String item = entry.trim().replace("[", "").replace("]", "").replace("{", "")
                    .replace("}", "").replace("\"", "");
                if (!item.isEmpty()) {
                    errorTypeRules.add(new ErrorTypeRule(item));
                }
            }
        }
    }

    /** 内置默认错误类型（基于 Playwright 自动化常见异常分类） */
    private void loadBuiltinErrorTypes() {
        // ── 1. 超时问题（最高频，约80%用例会遇到）──
        addBuiltin("Timeout Error",
            "TimeoutException", "timed out after", "timeout", "超时",
            "waiting timed out", "exceeded timeout", "slow operation",
            "waiting for", "time out");

        // ── 2. 元素操作失败（定位/可见/遮挡）──
        addBuiltin("Element Not Found",
            "ElementNotFound", "element not found", "locator not found",
            "No element found", "selector not found", "LocatorError",
            "unable to locate element", "元素找不到", "元素不存在",
            "waiting for element failed");
        addBuiltin("Element Handle Error",
            "ElementHandleError", "element is not visible",
            "element click intercepted", "element is hidden",
            "element is disabled", "not visible", "not interactable",
            "is not visible", "is disabled", "is detached from DOM",
            "obscures", "outside viewport");

        // ── 3. 页面导航失败 ──
        addBuiltin("Navigation Failed",
            "NavigationTimeout", "navigation failed", "page not loaded",
            "page load failed", "url invalid", "页面加载失败",
            "about:blank", "net::ERR_", "failed to navigate",
            "loading failed", "target page crashed",
            "page crashed", "page closed");

        // ── 4. 断言失败（测试期望不符）──
        addBuiltin("Assertion Failed",
            "AssertionError", "expected but was", "assert",
            "Expected.*but was", "实际.*期望", "condition did not match",
            "expected.*actual", "assertion failure", "mismatch",
            "assertThat", "expected.*found");

        // ── 5. 空指针 / 对象未初始化（编码不规范）──
        addBuiltin("Null Pointer Exception",
            "NullPointerException", "null pointer", "NPE",
            "null value", "cannot be null", "null cannot be cast",
            "Cannot invoke method", "null cannot be assigned to");

        // ── 6. Code Issue（代码问题：非法状态、类型转换、IO等）──
        addBuiltin("Code Issue",
            "IllegalStateException", "illegal state",
            "NoSuchElementException", "no such element",
            "ClassCastException", "class cast",
            "IOException", "io exception",
            "IndexOutOfBoundsException", "index out of bounds",
            "IllegalArgumentException", "illegal argument",
            "UnsupportedOperationException", "unsupported operation",
            "NumberFormatException", "number format",
            "ConcurrentModificationException", "concurrent modification",
            "文件不存在", "file not found",
            "cannot access", "access denied");

        // ── 7. 浏览器 / 页面已关闭 ──
        addBuiltin("Browser/Page Closed",
            "PageClosedException", "TargetClosedException",
            "page has been closed", "target has been closed",
            "browser has been closed", "context has been closed",
            "Protocol error: Target closed", "has been closed",
            "browser not connected", "connection disposed");

        // ── 8. 环境问题（网络、数据库、配置）──
        addBuiltin("Environment Issue",
            "Connection refused", "connection reset", "connect failed",
            "database unavailable", "DB connection failed",
            "config missing", "environment not ready",
            "service unavailable", "host unreachable",
            "socket timeout", "connect timed out",
            "network is unreachable", "ECONNREFUSED",
            "ENOTFOUND", "proxy error", "ProxyException",
            "WebSocketException", "websocket disconnected");

        // ── 9. API 接口问题 ──
        addBuiltin("API Issue",
            "ApiException", "API failed", "API error",
            "HTTP 500", "502 Bad Gateway", "503 Service Unavailable",
            "504 Gateway Timeout", "internal server error", "bad gateway",
            "接口异常", "接口失败", "api call failed", "rest client error",
            "status code", "http request failed");

        // ── 10. 权限 / 认证问题 ──
        addBuiltin("Auth Failed",
            "Unauthorized", "401 Forbidden", "403 Forbidden",
            "authentication failed", "access denied",
            "login failed", "session expired", "token expired",
            "permission denied", "not authorized", "登录失败",
            "forbidden", "unauthenticated");

        // ── 11. 浏览器启动 / 版本问题 ──
        addBuiltin("Browser Launch Error",
            "BrowserTypeLaunchException", "browser launch failed",
            "Failed to launch chromium", "Failed to launch firefox",
            "VersionException", "version mismatch",
            "executable doesn't exist", "no browser installed",
            "chromium not found", "sandbox", "no-sandbox");

        // ── 12. 数据验证问题 ──
        addBuiltin("Data Validation Error",
            "DataIntegrityViolation", "data too long",
            "constraint violation", "unique constraint",
            "foreign key", "invalid data", "validation failed",
            "数据校验", "duplicate entry", "field required");
    }

    private void addBuiltin(String label, String... keywords) {
        for (String kw : keywords) {
            errorTypeRules.add(new ErrorTypeRule(label, kw));
        }
    }

    /**
     * 错误分类规则
     * - JSON 配置: new ErrorTypeRule("Api Issue", ["HTTP 500", "502"]) → label 显示, 多关键字匹配
     * - 旧逗号配置: new ErrorTypeRule("Api Issue") → label 本身作为关键字
     * - 内置默认:  new ErrorTypeRule("Env Error", "connection refused") → 单关键字
     */
    static class ErrorTypeRule {
        final String label;
        final List<String> keywords;

        /** 旧格式：label 既是显示名也是匹配关键字 */
        ErrorTypeRule(String label) {
            this.label = label;
            this.keywords = Collections.singletonList(label);
        }

        /** 单关键字（内置默认） */
        ErrorTypeRule(String label, String keyword) {
            this.label = label;
            this.keywords = Collections.singletonList(keyword);
        }

        /** 多关键字（JSON 配置） */
        ErrorTypeRule(String label, List<String> keywords) {
            this.label = label;
            this.keywords = keywords != null && !keywords.isEmpty()
                ? keywords : Collections.singletonList(label);
        }

        boolean matches(String errorMessage) {
            if (errorMessage == null || keywords == null) return false;
            String lower = errorMessage.toLowerCase();
            for (String kw : keywords) {
                if (kw != null && lower.contains(kw.toLowerCase())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private void appendFailureAndResultList(StringBuilder sb) {
        List<FailureInfo> failures = new ArrayList<>();

        for (TestOutcome t : testOutcomes) {
            if (t.getResult() == TestResult.FAILURE || t.getResult() == TestResult.ERROR) {
                String feature = getFeature(t);
                // Priority: scenario-specific link > index.html (never use feature link for scenarios)
                String scenarioHtml = scenarioToHtmlMap.getOrDefault(t.getName(), null);
                String html = buildHtmlLink(scenarioHtml != null ? scenarioHtml : "index.html");
                String error = t.getTestFailureMessage() != null ? t.getTestFailureMessage() : "Test failed";
                failures.add(new FailureInfo(feature, t.getName(), error, html, t.getResult()));
            }
        }
        for (SimpleTestOutcome t : simpleTestOutcomes) {
            if (t.result == TestResult.FAILURE || t.result == TestResult.ERROR) {
                String scenarioHtml = scenarioToHtmlMap.getOrDefault(t.title, null);
                String html = buildHtmlLink(scenarioHtml != null ? scenarioHtml : "index.html");
                String error = t.errorMessage != null && !t.errorMessage.isEmpty() ? t.errorMessage : "Test failed";
                failures.add(new FailureInfo(t.featureName, t.title, error, html, t.result));
            }
        }

        // Full Failure List
        if (!failures.isEmpty()) {
            sb.append("                    <tr>\n");
            sb.append("                        <td class=\"compact-wrapper\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding-left:24px;padding-right:24px;padding-top:4px;padding-bottom:4px;\">\n");
            sb.append("                            <h3 style=\"color:#222222;font-family:Helvetica, sans-serif;font-weight:400;line-height:1.4;margin:0;font-size:20px;text-align:center;\">Full Failure List</h3>\n");
            sb.append("                            <table class=\"failure-list failure-scoreboard\" style=\"border-width:1px;border-style:solid;border-color:#dee2e6;border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;\">\n");
            sb.append("                                <tr>\n");
            sb.append("                                    <th style=\"text-align:left;width:50%;padding:10px 12px;background:linear-gradient(180deg,#f8f9fa 0%,#e9ecef 100%);font-weight:600;font-size:13px;color:#495057;\">Requirement</th>\n");
            sb.append("                                    <th style=\"text-align:left;width:50%;padding:10px 12px;background:linear-gradient(180deg,#f8f9fa 0%,#e9ecef 100%);font-weight:600;font-size:13px;color:#495057;\">Failure</th>\n");
            sb.append("                                </tr>\n");

            String lastFeature = null;
            for (FailureInfo f : failures) {
                if (!f.feature.equals(lastFeature)) {
                    sb.append("                                <tr>\n");
                    sb.append("                                    <td colspan=\"2\" class=\"feature\" style=\"font-family:Helvetica, sans-serif;font-size:14px;font-weight:600;vertical-align:top;padding:8px 16px;background-color:#f0f4f8;border-bottom:2px solid #dee2e6;color:#3d5a80;\">").append(escape(f.feature)).append("</td>\n");
                    sb.append("                                </tr>\n");
                    lastFeature = f.feature;
                }

                sb.append("                                <tr>\n");
                sb.append("                                    <td class=\"scenarioName\" style=\"font-family:Helvetica, sans-serif;font-size:13px;vertical-align:top;padding:10px 24px;width:50%;word-wrap:break-word;overflow-wrap:break-word;border-bottom:1px solid #eee;\">\n");
                sb.append("                                        <a href=\"").append(f.htmlLink).append("\" target=\"_blank\" style=\"color:#0066cc;text-decoration:none;\">").append(escape(f.scenario)).append("</a>\n");
                sb.append("                                    </td>\n");
                sb.append("                                    <td class=\"scenarioResult\" style=\"font-family:Helvetica, sans-serif;font-size:13px;vertical-align:top;padding:10px 12px;width:50%;word-wrap:break-word;overflow-wrap:break-word;border-bottom:1px solid #eee;\">\n");
                appendResultLabel(sb, f.result);
                if (f.error != null && !f.error.isEmpty()) {
                    sb.append("<div style=\"margin-top:4px;padding-left:1em;color:").append(resultColor(f.result)).append(";font-size:11px;line-height:1.3;word-break:break-all;\">").append(truncateError(escape(f.error))).append("</div>\n");
                }
                sb.append("                                    </td>\n");
                sb.append("                                </tr>\n");
            }

            sb.append("                            </table>\n");
            sb.append("                        </td>\n");
            sb.append("                    </tr>\n");
        }

        // Full Test Results
        sb.append("                    <tr>\n");
        sb.append("                        <td class=\"compact-wrapper\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding-left:24px;padding-right:24px;padding-top:4px;padding-bottom:4px;\">\n");
        sb.append("                            <div style=\"text-align:center;\">\n");
        sb.append("                                <h3 style=\"color:#222222;font-family:Helvetica, sans-serif;font-weight:400;line-height:1.4;margin:0;font-size:20px;text-align:center;display:inline;\">Full Test Results</h3>\n");
        sb.append("                                <a style=\"text-transform:uppercase;color:#ffffff;text-decoration:none;font-weight:bold;padding:0.3em 0.8em;background:#5FB0E0;border-radius:4px;font-size:12px;margin-left:15px;\" href=\"").append(buildDownloadUrl(csvFileName)).append("\" target=\"_blank\">Download CSV</a>\n");
        sb.append("                            </div>\n");
        sb.append("                            <table class=\"failure-list failure-scoreboard\" style=\"border-width:1px;border-style:solid;border-color:#dee2e6;border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;\">\n");
        sb.append("                                <tr>\n");
        sb.append("                                    <th style=\"text-align:left;width:50%;padding:12px 16px;background:linear-gradient(180deg,#f8f9fa 0%,#e9ecef 100%);font-weight:600;font-size:13px;color:#495057;\">Requirement</th>\n");
        sb.append("                                    <th style=\"text-align:left;width:50%;padding:12px 16px;background:linear-gradient(180deg,#f8f9fa 0%,#e9ecef 100%);font-weight:600;font-size:13px;color:#495057;\">Result</th>\n");
        sb.append("                                </tr>\n");

        String lastFeature = null;
        for (TestOutcome t : testOutcomes) {
            String feature = getFeature(t);
            String scenarioHtml = scenarioToHtmlMap.getOrDefault(t.getName(), null);
            String html = buildHtmlLink(scenarioHtml != null ? scenarioHtml : "index.html");

            if (!feature.equals(lastFeature)) {
                sb.append("                                <tr>\n");
                sb.append("                                    <td colspan=\"2\" class=\"feature feature-title\" style=\"font-family:Helvetica, sans-serif;font-size:14px;font-weight:600;vertical-align:top;padding:8px 16px;background-color:#f0f4f8;border-bottom:2px solid #dee2e6;color:#3d5a80;\">").append(escape(feature)).append("</td>\n");
                sb.append("                                </tr>\n");
                lastFeature = feature;
            }

            sb.append("                                <tr>\n");
            sb.append("                                    <td class=\"scenarioName\" style=\"font-family:Helvetica, sans-serif;font-size:13px;vertical-align:top;padding:10px 24px;width:50%;word-wrap:break-word;overflow-wrap:break-word;border-bottom:1px solid #eee;\">\n");
            sb.append("                                        <a href=\"").append(html).append("\" target=\"_blank\" style=\"color:#0066cc;text-decoration:none;\">").append(escape(t.getName())).append("</a>\n");
            sb.append("                                    </td>\n");
            sb.append("                                    <td style=\"font-family:Helvetica, sans-serif;font-size:13px;vertical-align:top;padding:10px 12px;width:50%;word-wrap:break-word;overflow-wrap:break-word;border-bottom:1px solid #eee;\">\n");

            appendResultLabel(sb, t.getResult());
            if (t.getResult() != TestResult.SUCCESS && t.getResult() != TestResult.IGNORED && t.getResult() != TestResult.SKIPPED) {
                String error = t.getTestFailureMessage() != null ? t.getTestFailureMessage() : "Test failed";
                sb.append("<div style=\"margin-top:4px;padding-left:1em;color:").append(resultColor(t.getResult())).append(";font-size:11px;line-height:1.3;word-break:break-all;\">").append(truncateError(escape(error))).append("</div>\n");
            }

            sb.append("                                    </td>\n");
            sb.append("                                </tr>\n");
        }

        // 处理 simpleTestOutcomes
        for (SimpleTestOutcome t : simpleTestOutcomes) {
            String feature = t.featureName;
            String scenarioHtml = scenarioToHtmlMap.getOrDefault(t.title, null);
            String html = buildHtmlLink(scenarioHtml != null ? scenarioHtml : "index.html");

            if (!feature.equals(lastFeature)) {
                sb.append("                                <tr>\n");
                sb.append("                                    <td colspan=\"2\" class=\"feature feature-title\" style=\"font-family:Helvetica, sans-serif;font-size:14px;font-weight:600;vertical-align:top;padding:8px 16px;background-color:#f0f4f8;border-bottom:2px solid #dee2e6;color:#3d5a80;\">").append(escape(feature)).append("</td>\n");
                sb.append("                                </tr>\n");
                lastFeature = feature;
            }

            sb.append("                                <tr>\n");
            sb.append("                                    <td class=\"scenarioName\" style=\"font-family:Helvetica, sans-serif;font-size:13px;vertical-align:top;padding:10px 24px;width:50%;word-wrap:break-word;overflow-wrap:break-word;border-bottom:1px solid #eee;\">\n");
            sb.append("                                        <a href=\"").append(html).append("\" target=\"_blank\" style=\"color:#0066cc;text-decoration:none;\">").append(escape(t.title)).append("</a>\n");
            sb.append("                                    </td>\n");
            sb.append("                                    <td style=\"font-family:Helvetica, sans-serif;font-size:13px;vertical-align:top;padding:10px 12px;width:50%;word-wrap:break-word;overflow-wrap:break-word;border-bottom:1px solid #eee;\">\n");

            appendResultLabel(sb, t.result);
            if (t.result != TestResult.SUCCESS && t.result != TestResult.IGNORED && t.result != TestResult.SKIPPED) {
                sb.append("<div style=\"margin-top:4px;padding-left:1em;color:").append(resultColor(t.result)).append(";font-size:11px;line-height:1.3;word-break:break-all;\">").append(truncateError(escape(t.errorMessage != null && !t.errorMessage.isEmpty() ? t.errorMessage : "Test failed"))).append("</div>\n");
            }

            sb.append("                                    </td>\n");
            sb.append("                                </tr>\n");
        }

        sb.append("                            </table>\n");
        sb.append("                        </td>\n");
        sb.append("                    </tr>\n");
    }

    private static class FailureInfo {
        String feature;
        String scenario;
        String error;
        String htmlLink;
        TestResult result;

        FailureInfo(String feature, String scenario, String error, String htmlLink, TestResult result) {
            this.feature = feature;
            this.scenario = scenario;
            this.error = error;
            this.htmlLink = htmlLink;
            this.result = result;
        }
    }

    // =============================================================
    // 工具方法
    // =============================================================

    /** Get font color for a test result type (no background, text-only). */
    private String resultColor(TestResult r) {
        if (r == TestResult.SUCCESS) return "#52B255";
        if (r == TestResult.FAILURE) return "#f44336";
        if (r == TestResult.ERROR) return "#ECA43A";
        if (r == TestResult.PENDING) return "#5FB0E0";
        if (r == TestResult.IGNORED || r == TestResult.SKIPPED) return "#9e9e9e";
        if (r == TestResult.COMPROMISED) return "#9C77AD";
        return "#666666";
    }

    /** Build result label span - colored bold text. */
    private void appendResultLabel(StringBuilder sb, TestResult r) {
        sb.append("<span style=\"color:").append(resultColor(r))
          .append(";font-size:12px;font-weight:bold;text-transform:uppercase;\">")
          .append(r.name().toLowerCase()).append("</span>");
    }

    private long count(TestResult r) {
        return resultCounts.getOrDefault(r, 0L);
    }

    private long getTotalTests() {
        return testOutcomes.size() + simpleTestOutcomes.size();
    }

    private String getFeature(TestOutcome t) {
        Story s = t.getUserStory();
        return s == null ? "No Feature" : s.getName();
    }

    private String format(long ms) {
        if (ms < 1000) return ms + "ms";
        long seconds = Duration.ofMillis(ms).getSeconds();
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        long secs = seconds % 60;
        return minutes + "m " + secs + "s";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 截断错误信息，超过150字符用省略号代替
     */
    private String truncateError(String error) {
        if (error == null || error.isEmpty()) return "";
        if (error.length() > 300) {
            return error.substring(0, 300) + "...";
        }
        return error;
    }

    // =============================================================
    // 数据加载（Serenity BDD JSON 格式）
    // =============================================================
    private void loadTestOutcomes(String actualReportDir) {
        File dir = new File(actualReportDir);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json") && !n.equals("summary.json"));
        if (files == null || files.length == 0) return;

        LoggingConfigUtil.logDebugIfVerbose(logger, "Found {} json report files in {}", files.length, dir.getAbsolutePath());

        Gson gson = new Gson();
        for (File f : files) {
            try {
                String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                JsonObject jo = gson.fromJson(content, JsonObject.class);

                // 提取测试名称
                String name = jo.has("name") ? jo.get("name").getAsString() : "Unknown";

                // 提取测试结果
                String r = jo.has("result") ? jo.get("result").getAsString() : "SUCCESS";

                // 提取持续时间（毫秒）
                long dur = 0;
                if (jo.has("duration")) {
                    dur = jo.get("duration").getAsLong();
                }

                // 提取 feature 名称
                String feature = "No Feature";
                if (jo.has("userStory") && jo.get("userStory").isJsonObject()) {
                    JsonObject userStory = jo.getAsJsonObject("userStory");
                    if (userStory.has("storyName")) {
                        feature = userStory.get("storyName").getAsString();
                    } else if (userStory.has("displayName")) {
                        feature = userStory.get("displayName").getAsString();
                    }
                }

                // 提取场景 ID
                String scenarioId = jo.has("scenarioId") ? jo.get("scenarioId").getAsString() : name;

                // 提取错误信息
                String errorMessage = "";
                if (jo.has("testFailureCause") && jo.get("testFailureCause").isJsonObject()) {
                    JsonObject failureCause = jo.getAsJsonObject("testFailureCause");
                    if (failureCause.has("message")) {
                        errorMessage = failureCause.get("message").getAsString();
                    } else if (failureCause.has("errorType")) {
                        errorMessage = failureCause.get("errorType").getAsString();
                    }
                } else if (jo.has("errorMessage")) {
                    errorMessage = jo.get("errorMessage").getAsString();
                }

                SimpleTestOutcome outcome = new SimpleTestOutcome(name, r, dur, feature);
                outcome.scenarioId = scenarioId;
                outcome.errorMessage = errorMessage;
                simpleTestOutcomes.add(outcome);
            } catch (Exception e) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to parse JSON file: {} - {}", f.getName(), e.getMessage());
            }
        }
    }

    private void calculateResultCounts() {
        for (TestResult r : TestResult.values()) resultCounts.put(r, 0L);
        testOutcomes.forEach(t -> resultCounts.put(t.getResult(), resultCounts.get(t.getResult()) + 1));
        simpleTestOutcomes.forEach(t -> resultCounts.put(t.result, resultCounts.get(t.result) + 1));
    }

    private void calculateDurations() {
        List<Long> list = new ArrayList<>();
        testOutcomes.forEach(t -> list.add(t.getDuration()));
        simpleTestOutcomes.forEach(t -> list.add(t.duration));
        if (list.isEmpty()) {
            totalDuration = minDuration = maxDuration = 0L;
            avgDuration = 0.0;
            return;
        }
        totalDuration = list.stream().mapToLong(l -> l).sum();
        minDuration = list.stream().mapToLong(l -> l).min().orElse(0);
        maxDuration = list.stream().mapToLong(l -> l).max().orElse(0);
        avgDuration = list.stream().mapToLong(l -> l).average().orElse(0);
    }

    private void loadFeatureHtmlMapping(String actualReportDir) {
        Path indexFile = Paths.get(actualReportDir, "index.html");
        if (!Files.exists(indexFile)) return;

        try {
            String content = Files.readString(indexFile, StandardCharsets.UTF_8);
            Pattern p = Pattern.compile(
                "title:\\s*'([^']+)'.*?link:\\s*\"([^\"]+)\"",
                Pattern.DOTALL);
            Matcher m = p.matcher(content);
            while (m.find()) {
                String title = m.group(1);
                String link = m.group(2);
                featureToHtmlMap.put(title, link);
            }
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to parse index.html: {}", e.getMessage());
        }
    }

    private void loadScenarioHtmlMapping(String actualReportDir) {
        // JSON 文件和 HTML 文件同名，直接映射
        File[] jsonFiles = new File(actualReportDir).listFiles((d, n) -> n.endsWith(".json") && !n.equals("summary.json"));
        if (jsonFiles == null) return;

        for (File f : jsonFiles) {
            try {
                String baseName = f.getName().replace(".json", "");
                String htmlLink = baseName + ".html";

                // 读取 JSON 文件获取场景名称
                String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                JsonObject jo = new Gson().fromJson(content, JsonObject.class);
                String name = jo.has("name") ? jo.get("name").getAsString() : null;

                if (name != null) {
                    scenarioToHtmlMap.put(name, htmlLink);
                }
            } catch (Exception e) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to load scenario mapping: {}", f.getName());
            }
        }
    }

    private static class SimpleTestOutcome {
        String title;
        TestResult result;
        long duration;
        String featureName;
        String scenarioId;
        String errorMessage;

        public SimpleTestOutcome(String title, String rStr, long duration, String featureName) {
            this.title = title;
            this.duration = duration;
            this.featureName = featureName;
            this.scenarioId = title;
            this.errorMessage = "";
            try { this.result = TestResult.valueOf(rStr.toUpperCase()); }
            catch (Exception e) { this.result = TestResult.PENDING; }
        }
    }

    public static void main(String[] args) {
        // Priority: command line arg > system property > env > default
        String reportDir = null;
        if (args != null && args.length > 0 && !args[0].trim().isEmpty()) {
            reportDir = args[0].trim();
        }
        if (reportDir == null || reportDir.isEmpty()) {
            reportDir = System.getProperty("serenity.report.directory");
        }
        if (reportDir == null || reportDir.trim().isEmpty()) {
            reportDir = System.getenv("SERENITY_REPORT_DIR");
        }
        if (reportDir == null || reportDir.trim().isEmpty()) {
            reportDir = DEFAULT_REPORT_DIR;
        }

        LoggingConfigUtil.logInfoIfVerbose(logger, "Using report directory: {}", reportDir);

        // 支持通过环境变量指定 serenity.properties 路径
        String propertiesPath = System.getProperty("serenity.properties.path");
        if (propertiesPath != null && !propertiesPath.trim().isEmpty()) {
            System.setProperty("user.dir", new File(propertiesPath).getParent());
            LoggingConfigUtil.logInfoIfVerbose(logger, "Setting working directory to: {}", System.getProperty("user.dir"));
        }

        new SummaryReportGenerator(reportDir).generateSummaryReport();
    }
}
