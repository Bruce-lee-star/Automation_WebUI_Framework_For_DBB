package com.hsbc.cmb.hk.dbb.automation.report;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
import java.util.regex.Matcher;
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
    private final String reportUrl;
    private final String reportBaseUrl; // 用于构建绝对 URL
    private final String fullReportUrl; // 完整Serenity报告URL（用于View full report按钮）
    private final List<TestOutcome> testOutcomes = new ArrayList<>();
    private final List<SimpleTestOutcome> simpleTestOutcomes = new ArrayList<>();
    private final Map<TestResult, Long> resultCounts = new EnumMap<>(TestResult.class);
    private final Map<String, String> featureToHtmlMap = new LinkedHashMap<>();
    private final Map<String, String> scenarioToHtmlMap = new LinkedHashMap<>();

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
        this.reportTitle = loadReportTitle();
        this.reportUrl = loadReportUrl();
        this.reportBaseUrl = extractReportBaseUrl();
        this.fullReportUrl = extractFullReportUrl(); // 基于reportUrl构建完整报告URL
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

    private String loadReportTitle() {
        String title = System.getProperty("serenity.summary.report.title");
        if (title != null && !title.isEmpty()) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Using report title from system property: {}", title);
            return title;
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
                    title = props.getProperty("serenity.summary.report.title");
                    if (title != null && !title.isEmpty()) return title;
                }
            }
        } catch (Exception e) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Failed to read report title: {}", e.getMessage());
        }

        // 默认标题，不与 project.name 重复
        return "Test Execution Report";
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
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?\\}");
        java.util.regex.Matcher matcher = pattern.matcher(value);
        
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
        return result;
    }

    /**
     * 自动检测并构建报告 URL
     */
    private String autoDetectReportUrl() {
        // 检查是否在 Jenkins 环境
        String buildUrl = System.getenv("BUILD_URL");
        if (buildUrl != null && !buildUrl.trim().isEmpty()) {
            // 在 Jenkins 环境，构建完整的报告 URL
            logger.info("Detected Jenkins environment, building report URL from BUILD_URL");

            // 确保以 / 结尾
            if (!buildUrl.endsWith("/")) {
                buildUrl += "/";
            }

            return buildUrl + "Serenity_20Report/";
        }

        // 本地环境，返回空（使用相对链接）
        LoggingConfigUtil.logDebugIfVerbose(logger, "Local environment detected, using relative links");
        return "";
    }

    /**
     * 从 reportUrl 提取基础 URL
     * 用于构建相对链接的绝对 URL
     *
     * @return 如果在 Jenkins 环境，返回基础 URL；否则返回空字符串
     */
    private String extractReportBaseUrl() {
        if (reportUrl != null && !reportUrl.isEmpty()) {
            // 确保以 / 结尾
            return reportUrl.endsWith("/") ? reportUrl : reportUrl + "/";
        }
        return "";
    }

    /**
     * 构建完整 Serenity 报告的 URL（用于 View full report 按钮）
     * 基于 serenity.report.url 配置（如 .../Serenity_20Summary_20Report/）
     * 自动定位到同级的 Serenity 原始报告目录
     *
     * @return 完整的 Serenity 报告 index.html 链接
     */
    private String extractFullReportUrl() {
        if (reportUrl == null || reportUrl.isEmpty()) {
            return "index.html"; // 本地环境使用相对路径
        }

        // reportUrl 格式示例：http://jenkins/job/Playwright/39/Serenity_20Summary_20Report/
        // 需要转换为：http://jenkins/job/Playwright/39/Serenity_20Report/index.html
        String base = reportUrl.endsWith("/") ? reportUrl : reportUrl + "/";

        // 尝试从 Summary_Report 路径推导原始 Report 路径
        // 常见模式：Serenity_20Summary_20Report -> Serenity_20Report
        if (base.contains("Serenity_20Summary")) {
            return base.replace("Serenity_20Summary", "Serenity") + "index.html";
        }

        // 其他情况：直接在当前目录找 index.html
        return base + "index.html";
    }

    /**
     * 将 HTML 文件名转换为 URL
     * 本地环境：返回相对链接（如 "abc123.html"）
     * Jenkins 环境：返回绝对 URL（如 "http://jenkins/job/123/Serenity_20Report/abc123.html"）
     *
     * @param htmlFile HTML 文件名或相对路径
     * @return 相对链接或绝对 URL
     */
    private String buildHtmlLink(String htmlFile) {
        // 如果已经是绝对 URL 或包含完整路径，直接返回
        if (htmlFile.startsWith("http://") || htmlFile.startsWith("https://")) {
            return htmlFile;
        }
        
        if (reportBaseUrl != null && !reportBaseUrl.isEmpty()) {
            // Jenkins 环境，使用绝对 URL
            return reportBaseUrl + htmlFile;
        }
        // 本地环境，使用相对链接
        return htmlFile;
    }

    private void init() {
        // 检测是否在 Jenkins 环境并自动调整路径
        String actualReportDir = resolveReportDirectory(reportDir);
        logger.info("Report directory: {}", actualReportDir);

        File dir = new File(actualReportDir);

        if (!dir.exists()) {
            logger.warn("Report directory does not exist, creating: {}", actualReportDir);
            dir.mkdirs();
        }

        long startInit = System.currentTimeMillis();
        loadTestOutcomes(actualReportDir);
        loadSimpleTestOutcomes(actualReportDir);
        loadFeatureHtmlMapping(actualReportDir);
        loadScenarioHtmlMapping(actualReportDir);
        calculateResultCounts();
        calculateDurations();
        this.clockTime = System.currentTimeMillis() - startInit;
        this.reportTime = LocalDateTime.now();

        logger.info("Test outcomes loaded: {}, Simple outcomes loaded: {}", testOutcomes.size(), simpleTestOutcomes.size());
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
            File absoluteDir = new File(userDir, reportDir);
            if (absoluteDir.exists()) {
                logger.info("Using current working directory (user.dir): {}", absoluteDir.getAbsolutePath());
                return absoluteDir.getAbsolutePath();
            }
        }

        // 其次检测 WORKSPACE 环境变量
        String workspace = System.getenv("WORKSPACE");
        if (workspace != null && !workspace.trim().isEmpty()) {
            File absoluteDir = new File(workspace, reportDir);
            logger.info("Using WORKSPACE environment variable: {}", absoluteDir.getAbsolutePath());
            return absoluteDir.getAbsolutePath();
        }

        // 本地环境，直接使用相对路径
        logger.info("Using local relative path: {}", reportDir);
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

            // 生成 HTML 报告
            String html = buildFullNativeHtml();
            Path output = Paths.get(actualReportDir, SUMMARY_FILE);
            Files.write(output, html.getBytes(StandardCharsets.UTF_8));
            System.out.println("       - Summary report: " + output.toUri());

            // 生成 CSV 文件
            generateCsvReport(actualReportDir);

            // 生成 ZIP 包
            generateZipPackage(actualReportDir);
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
        sb.append("    <title>Serenity BDD Test Results - L1 Controls Navigator</title>\n");
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

        // 使用 fullReportUrl（基于 serenity.report.url 配置自动推导）
        // 例如：serenity.report.url=.../Serenity_20Summary_20Report/ 
        //       → View full report 链接到 .../Serenity_20Report/index.html
        String fullReportLink = this.fullReportUrl;
        sb.append("                            <a style=\"text-transform:uppercase;color:#8accf2;text-decoration:none;font-weight:bold;padding:0.5em 1em;background:#316d91;margin-right:10px;display:inline-block;\" href=\"").append(fullReportLink).append("\" target=\"_blank\">View full report</a>\n");

        sb.append("                            <a style=\"text-transform:uppercase;color:#ffffff;text-decoration:none;font-weight:bold;padding:0.5em 1em;background:#52B255;border-radius:4px;display:inline-block;\" href=\"").append(zipFileName).append("\" download>Download ZIP</a>\n");
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
                failureCounts.merge("Test failed", 1, Integer::sum);
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
        sb.append("                        </td>\n");
        sb.append("                    </tr>\n");
    }

    private static class FeatureFailureStats {
        int count = 0;
        void increment() { count++; }
    }

    private String extractErrorType(String errorMessage) {
        if (errorMessage == null) return "Unknown error";
        if (errorMessage.contains("NullPointerException")) return "Null pointer exception";
        if (errorMessage.contains("AssertionError")) return "Assertion failure";
        if (errorMessage.contains("TimeoutException") || errorMessage.contains("Timeout")) return "Timeout";
        if (errorMessage.contains("ElementNotFound") || errorMessage.contains("Element not found")) return "Element not found";
        if (errorMessage.length() > 50) return errorMessage.substring(0, 50) + "...";
        return errorMessage;
    }

    private void appendFailureAndResultList(StringBuilder sb) {
        List<FailureInfo> failures = new ArrayList<>();

        for (TestOutcome t : testOutcomes) {
            if (t.getResult() == TestResult.FAILURE || t.getResult() == TestResult.ERROR) {
                String feature = getFeature(t);
                String html = buildHtmlLink(scenarioToHtmlMap.getOrDefault(t.getName(), featureToHtmlMap.getOrDefault(feature, "index.html")));
                String error = t.getTestFailureMessage() != null ? t.getTestFailureMessage() : "Test failed";
                failures.add(new FailureInfo(feature, t.getName(), error, html, t.getResult()));
            }
        }
        for (SimpleTestOutcome t : simpleTestOutcomes) {
            if (t.result == TestResult.FAILURE || t.result == TestResult.ERROR) {
                String html = buildHtmlLink(featureToHtmlMap.getOrDefault(t.featureName, "index.html"));
                String error = t.errorMessage != null && !t.errorMessage.isEmpty() ? t.errorMessage : "Test failed";
                failures.add(new FailureInfo(t.featureName, t.title, error, html, t.result));
            }
        }

        // Full Failure List
        if (!failures.isEmpty()) {
            sb.append("                    <tr>\n");
            sb.append("                        <td class=\"compact-wrapper\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding-left:24px;padding-right:24px;padding-top:4px;padding-bottom:4px;\">\n");
            sb.append("                            <h3 style=\"color:#222222;font-family:Helvetica, sans-serif;font-weight:400;line-height:1.4;margin:0;font-size:20px;text-align:center;\">Full Failure List</h3>\n");
            sb.append("                            <table class=\"failure-list failure-scoreboard\" style=\"border-width:1px;border-style:solid;border-color:#acb1b9;border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;\">\n");
            sb.append("                                <tr>\n");
            sb.append("                                    <th style=\"text-align:left;width:55%;\">Requirement</th>\n");
            sb.append("                                    <th style=\"text-align:left;width:45%;\">Failure</th>\n");
            sb.append("                                </tr>\n");

            String lastFeature = null;
            for (FailureInfo f : failures) {
                if (!f.feature.equals(lastFeature)) {
                    sb.append("                                <tr>\n");
                    sb.append("                                    <td colspan=\"2\" class=\"feature\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;font-style:italic;padding-left:16px;word-wrap:break-word;overflow-wrap:break-word;\">").append(escape(f.feature)).append("</td>\n");
                    sb.append("                                </tr>\n");
                    lastFeature = f.feature;
                }

                sb.append("                                <tr>\n");
                sb.append("                                    <td class=\"scenarioName\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;padding-left:32px;min-width:4em;width:55%;word-wrap:break-word;overflow-wrap:break-word;\">\n");
                sb.append("                                        <a href=\"").append(f.htmlLink).append("\" target=\"_blank\">").append(escape(f.scenario)).append("</a>\n");
                sb.append("                                    </td>\n");
                sb.append("                                    <td style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;width:45%;word-wrap:break-word;overflow-wrap:break-word;max-width:350px;\" class=\"frequent-failure for-error\">\n");
                sb.append("                                        <div>\n");
                sb.append("                                            <div>\n");
                sb.append("                                                <span class=\"frequent-failure for-error\" style=\"font-weight:bold;text-transform:uppercase;\">").append(f.result == TestResult.ERROR ? "error" : "failure").append("</span>\n");
                sb.append("                                            </div>\n");
                sb.append("                                            <div class=\"frequent-failure for-error\" style=\"padding-left:1em;padding-bottom:0.5em;\">").append(truncateError(escape(f.error))).append("</div>\n");
                sb.append("                                        </div>\n");
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
        sb.append("                                <a style=\"text-transform:uppercase;color:#ffffff;text-decoration:none;font-weight:bold;padding:0.3em 0.8em;background:#5FB0E0;border-radius:4px;font-size:12px;margin-left:15px;\" href=\"").append(csvFileName).append("\" download>Download CSV</a>\n");
        sb.append("                            </div>\n");
        sb.append("                            <table class=\"failure-list failure-scoreboard\" style=\"border-width:1px;border-style:solid;border-color:#acb1b9;border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;\">\n");
        sb.append("                                <tr>\n");
        sb.append("                                    <th style=\"text-align:left;width:35%;\">Requirement</th>\n");
        sb.append("                                    <th style=\"text-align:left;width:65%;\">Result</th>\n");
        sb.append("                                </tr>\n");

        String lastFeature = null;
        for (TestOutcome t : testOutcomes) {
            String feature = getFeature(t);
            String html = buildHtmlLink(scenarioToHtmlMap.getOrDefault(t.getName(), featureToHtmlMap.getOrDefault(feature, "index.html")));
            boolean isSuccess = t.getResult() == TestResult.SUCCESS;

            if (!feature.equals(lastFeature)) {
                sb.append("                                <tr>\n");
                sb.append("                                    <td colspan=\"2\" class=\"feature feature-title\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;font-style:italic;padding-left:16px;word-wrap:break-word;overflow-wrap:break-word;\">").append(escape(feature)).append("</td>\n");
                sb.append("                                </tr>\n");
                lastFeature = feature;
            }

            sb.append("                                <tr>\n");
            sb.append("                                    <td class=\"scenarioName\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;padding-left:32px;min-width:4em;width:35%;word-wrap:break-word;overflow-wrap:break-word;\">\n");
            sb.append("                                        <a href=\"").append(html).append("\" target=\"_blank\">").append(escape(t.getName())).append("</a>\n");
            sb.append("                                    </td>\n");
            sb.append("                                    <td style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;width:65%;word-wrap:break-word;overflow-wrap:break-word;max-width:500px;\">\n");

            if (!isSuccess) {
                String error = t.getTestFailureMessage() != null ? t.getTestFailureMessage() : "Test failed";
                sb.append("                                        <div>\n");
                sb.append("                                            <div>\n");
                sb.append("                                                <span class=\"frequent-failure for-error\" style=\"font-weight:bold;text-transform:uppercase;\">").append(t.getResult() == TestResult.ERROR ? "error" : "failure").append("</span>\n");
                sb.append("                                            </div>\n");
                sb.append("                                            <div class=\"frequent-failure for-error\" style=\"padding-left:1em;padding-bottom:0.5em;word-break:break-all;max-width:450px;\" title=\"").append(escape(error)).append("\">").append(truncateError(escape(error))).append("</div>\n");
                sb.append("                                        </div>\n");
            } else {
                sb.append("                                        <span class=\"for-passing\" style=\"font-weight:bold;text-transform:uppercase;\">success</span>\n");
            }

            sb.append("                                    </td>\n");
            sb.append("                                </tr>\n");
        }

        // 处理 simpleTestOutcomes
        for (SimpleTestOutcome t : simpleTestOutcomes) {
            String feature = t.featureName;
            String html = buildHtmlLink(scenarioToHtmlMap.getOrDefault(t.title, featureToHtmlMap.getOrDefault(feature, "index.html")));
            boolean isSuccess = t.result == TestResult.SUCCESS;

            if (!feature.equals(lastFeature)) {
                sb.append("                                <tr>\n");
                sb.append("                                    <td colspan=\"2\" class=\"feature feature-title\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;font-style:italic;padding-left:16px;\">").append(escape(feature)).append("</td>\n");
                sb.append("                                </tr>\n");
                lastFeature = feature;
            }

            sb.append("                                <tr>\n");
            sb.append("                                    <td class=\"scenarioName\" style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;padding-left:32px;min-width:4em;width:35%;word-wrap:break-word;overflow-wrap:break-word;\">\n");
            sb.append("                                        <a href=\"").append(html).append("\" target=\"_blank\">").append(escape(t.title)).append("</a>\n");
            sb.append("                                    </td>\n");
            sb.append("                                    <td style=\"font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;width:65%;word-wrap:break-word;overflow-wrap:break-word;max-width:500px;\">\n");

            if (!isSuccess) {
                sb.append("                                        <div>\n");
                sb.append("                                            <div>\n");
                sb.append("                                                <span class=\"frequent-failure for-error\" style=\"font-weight:bold;text-transform:uppercase;\">").append(t.result == TestResult.ERROR ? "error" : "failure").append("</span>\n");
                sb.append("                                            </div>\n");
                sb.append("                                            <div class=\"frequent-failure for-error\" style=\"padding-left:1em;padding-bottom:0.5em;word-break:break-all;max-width:450px;\">").append(truncateError(escape(t.errorMessage != null && !t.errorMessage.isEmpty() ? t.errorMessage : "Test failed"))).append("</div>\n");
                sb.append("                                        </div>\n");
            } else {
                sb.append("                                        <span class=\"for-passing\" style=\"font-weight:bold;text-transform:uppercase;\">success</span>\n");
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
        if (error.length() > 150) {
            return error.substring(0, 147) + "...";
        }
        return error;
    }

    // =============================================================
    // 数据加载
    // =============================================================
    private void loadTestOutcomes(String actualReportDir) {
        File dir = new File(actualReportDir);
        logger.info("Looking for .ser files in directory: {}", dir.getAbsolutePath());

        File[] files = dir.listFiles((d, n) -> n.endsWith(".ser"));
        if (files == null) {
            logger.warn("Directory does not exist or is not a directory: {}", dir.getAbsolutePath());
            return;
        }

        logger.info("Found {} .ser files in {}", files.length, dir.getAbsolutePath());
        if (files == null) return;
        for (File f : files) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
                Object o = ois.readObject();
                if (o instanceof TestOutcome) testOutcomes.add((TestOutcome) o);
                if (o instanceof List<?>) for (Object x : (List<?>) o) if (x instanceof TestOutcome) testOutcomes.add((TestOutcome) x);
            } catch (Exception e) { LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to read ser file: {}", f.getName()); }
        }
    }

    private void loadSimpleTestOutcomes(String actualReportDir) {
        // 加载 Serenity 生成的 JSON 报告文件
        File dir = new File(actualReportDir);
        logger.info("Looking for .json files in directory: {}", dir.getAbsolutePath());

        File[] files = dir.listFiles((d, n) -> n.endsWith(".json") && !n.equals("summary.json"));
        if (files == null || files.length == 0) {
            logger.warn("No .json files found in directory: {}", dir.getAbsolutePath());
            return;
        }

        logger.info("Found {} .json files in {}", files.length, dir.getAbsolutePath());

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
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "title:\\s*'([^']+)'.*?link:\\s*\"([^\"]+)\"",
                java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(content);
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
        // 支持通过环境变量或命令行参数指定报告目录
        String reportDir = System.getProperty("serenity.report.directory");
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
