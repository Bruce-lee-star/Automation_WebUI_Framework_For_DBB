package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorFailureReportSink;
import net.thucydides.model.domain.Story;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import freemarker.template.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
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
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("EEEE MMMM dd yyyy 'at' HH:mm:ss");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    // T2-8：Freemarker 渲染配置。模板置于 src/main/resources/templates/，统一使用 .ftlh 扩展名
    // （setRecognizeStandardFileExtensions(true) → HTMLOutputFormat → 自动转义 & < > " '），
    // 彻底补上原 escape() 不转义双引号的缺口（T2-8 收尾项）。
    // 约定：① Java 仅组装数据模型，文本值一律交给模板 auto-escape，不再手动 escape()；
    //       ② 由 Java 预渲染的 HTML 片段（css / 6 个装配片段 / pieChart）在模板中以 ?no_esc 原样输出，避免二次转义。
    private static final Configuration FM_CFG = new Configuration(Configuration.VERSION_2_3_33);
    static {
        FM_CFG.setClassForTemplateLoading(SummaryReportGenerator.class, "/templates");
        FM_CFG.setDefaultEncoding("UTF-8");
        FM_CFG.setRecognizeStandardFileExtensions(true);
        FM_CFG.setLogTemplateExceptions(false);
        FM_CFG.setWrapUncheckedExceptions(true);
    }

    // 预编译正则表达式，避免在 parseDuration lambda 中重复编译
    private static final Pattern DURATION_PATTERN_MS = 
            Pattern.compile("(\\d+)\\s*m\\s*(\\d+)\\s*s", Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION_PATTERN_UNIT = 
            Pattern.compile("(\\d+)\\s*(m|s|h|ms)", Pattern.CASE_INSENSITIVE);

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
    private LocalDateTime testExecutionTime;  // 测试实际执行时间（取最早 startTime）
    private String csvFileName;
    private String zipFileName;

    public SummaryReportGenerator() {
        this(DEFAULT_REPORT_DIR);
    }

    public SummaryReportGenerator(String reportDir) {
        this.reportDir = validateAndNormalizeDirectory(Objects.requireNonNull(reportDir));
        this.projectName = loadProjectName();
        this.reportTitle = projectName; // Use project name as report title
        this.reportUrl = loadReportUrl();
        // fullReportUrl: 链接到 Serenity 完整报告的 index.html
        // Jenkins环境: 绝对URL → http://jenkins.cli:8888/job/Playwright/49/Serenity_20Summary_20Report/index.html
        // 本地环境: 相对链接 → ./index.html（指向同目录下的 Serenity 主页）
        if (reportUrl != null && !reportUrl.isEmpty() && !reportUrl.contains("${")) {
            String url = ensureTrailingSlash(reportUrl);
            this.fullReportUrl = url;
        } else {
            // 默认链接到同目录下的 Serenity index.html（标准 Sereny 报告入口）
            this.fullReportUrl = "./index.html";
        }
        init();
    }

    /**
     * Validate and normalize a directory path to prevent path traversal attacks.
     * Converts the path to absolute and ensures no ".." sequences exist in the resolved path.
     *
     * @param dir raw directory path (may be relative or absolute)
     * @return normalized absolute path string
     * @throws IllegalArgumentException if path traversal is detected
     */
    private static String validateAndNormalizeDirectory(String dir) {
        Path path = Paths.get(dir).normalize().toAbsolutePath();
        for (Path component : path) {
            if ("..".equals(component.getFileName().toString())) {
                throw new IllegalArgumentException("Path traversal rejected: " + dir);
            }
        }
        return path.toString();
    }

    /**
     * Safely resolve child path segments against a validated base directory,
     * preventing path traversal beyond the base.
     *
     * @param baseDir validated base directory
     * @param segments path segments to append
     * @return resolved Path guaranteed to be within baseDir
     * @throws IllegalArgumentException if the resolved path escapes the base directory
     */
    private static Path safeResolve(String baseDir, String... segments) {
        Path base = Paths.get(baseDir).toAbsolutePath().normalize();
        Path target = base;
        for (String segment : segments) {
            target = target.resolve(segment);
        }
        target = target.normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("Path traversal rejected: " + String.join("/", segments));
        }
        return target;
    }

    private String loadProjectName() {
        String name = System.getProperty("serenity.project.name");
        if (name != null && !name.isEmpty()) return name;

        try {
            Path propFile = Paths.get("serenity.properties");
            VerboseLogging.logDebugIfVerbose(logger, "Looking for serenity.properties at: {}", propFile.toAbsolutePath());

            if (Files.exists(propFile)) {
                // 使用 UTF-8 编码读取 properties 文件
                Properties props = new Properties();
                try (InputStream is = Files.newInputStream(propFile);
                     InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    props.load(isr);
                    name = props.getProperty("serenity.project.name");
                    if (name != null && !name.isEmpty()) {
                        VerboseLogging.logDebugIfVerbose(logger, "Loaded project name from serenity.properties: {}", name);
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
            VerboseLogging.logDebugIfVerbose(logger, "Using report URL from system property: {}", url);
            return url;
        }

        try {
            Path propFile = Paths.get("serenity.properties");
            VerboseLogging.logDebugIfVerbose(logger, "Looking for serenity.properties at: {}", propFile.toAbsolutePath());

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
                        VerboseLogging.logDebugIfVerbose(logger, "Loaded report URL from serenity.properties: {}", url);
                        return url;
                    }
                }
            } else {
                VerboseLogging.logDebugIfVerbose(logger, "serenity.properties not found at: {}", propFile.toAbsolutePath());
            }
        } catch (Exception e) {
            VerboseLogging.logDebugIfVerbose(logger, "Failed to read report URL: {}", e.getMessage());
        }

        // 如果没有配置 URL，尝试自动构建 Jenkins 报告 URL
        url = autoDetectReportUrl();
        if (url != null && !url.isEmpty()) {
            VerboseLogging.logDebugIfVerbose(logger, "Auto-detected report URL: {}", url);
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
        
        StringBuilder sb = new StringBuilder();
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
        VerboseLogging.logDebugIfVerbose(logger, "No serenity.report.url configured, using relative links for all URLs");
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
        VerboseLogging.logInfoIfVerbose(logger, "Report directory: {}", actualReportDir);

        File dir = safeResolve(actualReportDir).toFile();
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
        // 从 JSON 数据中取最早的 startTime 作为测试实际执行时间
        this.testExecutionTime = resolveTestExecutionTime();
        loadFeatureHtmlMapping(actualReportDir);
        loadScenarioHtmlMapping(actualReportDir);
        calculateResultCounts();
        loadDurationsFromIndexHtml(actualReportDir);  // 从 index.html 解析时间（与 Serenity 原生报告保持一致）
        calculateDurations();
        loadErrorTypeRules();

        long total = getTotalTests();
        VerboseLogging.logInfoIfVerbose(logger,
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
        //  无产物保护（T2-8 报告自动化前置）：Serenity 未产出任何测试结果时不生成报告。
        // 背景：报告生成的触发已上移到父 pom，框架自身模块（core / web / route 等）在 verify
        // 阶段同样会被调用；若照常生成，将产出一份「0 用例」的 HTML / CSV / ZIP，
        // 被邮件订阅方或 CI 误读为「全部通过」——典型的假绿。故直接跳过。
        if (getTotalTests() == 0) {
            logger.info("No Serenity test outcomes found in '{}' - skipping summary report generation.",
                    resolveReportDirectory(reportDir));
            return;
        }
        try {
            // 生成带时间戳的文件名
            String timestamp = reportTime.format(TIMESTAMP_FORMATTER);
            csvFileName = CSV_FILE_PREFIX + "-" + timestamp + ".csv";
            zipFileName = ZIP_FILE_PREFIX + "-" + timestamp + ".zip";

            // 解析实际的报告目录（自动处理 Jenkins 环境）
            String actualReportDir = resolveReportDirectory(reportDir);

            long total = getTotalTests();
            VerboseLogging.logInfoIfVerbose(logger,
                "Generating summary report: {} total tests, dir: {}", total, actualReportDir);

            // 生成 HTML 报告
            String html = buildFullNativeHtml();
            Path output = safeResolve(actualReportDir, SUMMARY_FILE);
            Files.writeString(output, html);
            logger.info("\n       - Summary report: {}", output.toUri());

            // 注入自定义 CSS（serenity.css → screen.css）
            injectCustomCss(actualReportDir);

            // 修复 Swiper 截图轮播（fade → slide + autoHeight）
            fixSwiperScreenshotsHtml(actualReportDir);

            // 生成 CSV 文件
            generateCsvReport(actualReportDir);

            // 生成 ZIP 包
            generateZipPackage(actualReportDir);

            // 生成 API 监控失败报告（按 owner 去重，供 Jenkins emailext 等循环发送）
            //  T2-8：经 core 抽象接口 + SPI 解耦 route（不再直接 import route 类），
            //    由 ServiceLoader 发现 route 提供的实现，打破 reporting ↔ route 循环依赖。
            writeMonitorFailureReports();

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

    /**
     * 写出 API 监控失败报告（按 owner 去重，供 Jenkins emailext 等循环发送）。
     *
     * <p> T2-8：经 core 抽象接口 + SPI 解耦 route（不再直接 import route 类），
     * 由 {@link ServiceLoader} 发现 route 提供的实现，打破 reporting ↔ route 循环依赖。
     *
     * <p>企业级健壮性约定：
     * <ul>
     *   <li><b>单次遍历缓冲</b>：ServiceLoader 惰性实例化 provider，先缓冲全部实例再复用，
     *       避免多次遍历导致重复实例化（原实现 write/clear 各遍历一次）。</li>
     *   <li><b>异常隔离</b>：逐个 provider 独立 try-catch，单个实现故障不影响其余实现与主报告。</li>
     *   <li><b>write 全成功后才统一 clear</b>：防止多套件同 JVM 运行时失败记录跨套件累积（OOM 路径）。</li>
     * </ul>
     */
    private void writeMonitorFailureReports() {
        try {
            ServiceLoader<MonitorFailureReportSink> loader =
                    ServiceLoader.load(MonitorFailureReportSink.class);

            // 缓冲 provider 实例（避免 ServiceLoader 多次遍历重复 new）
            java.util.List<MonitorFailureReportSink> sinks = new java.util.ArrayList<>();
            for (MonitorFailureReportSink sink : loader) {
                sinks.add(sink);
            }

            if (sinks.isEmpty()) {
                logger.warn("[ApiMonitor] 未通过 SPI 发现任何 MonitorFailureReportSink 实现，"
                        + "监控失败报告未写出（请检查 route 模块 META-INF/services 注册）。");
                return;
            }

            int totalOwners = 0;
            for (MonitorFailureReportSink sink : sinks) {
                try {
                    totalOwners += sink.write();
                } catch (Exception ex) {
                    logger.warn("[ApiMonitor] 某 MonitorFailureReportSink 写出失败（已隔离，不影响其余）：{}",
                            ex.getMessage());
                }
            }

            if (totalOwners > 0) {
                logger.info("[ApiMonitor] 已写出 API 监控失败报告（{} 个 owner），"
                        + "见 target/monitor-failures-by-owner.json", totalOwners);
            }

            // 全部 write 完成后再清空（单个 sink 清空异常不影响其余）
            for (MonitorFailureReportSink sink : sinks) {
                try {
                    sink.clear();
                } catch (Exception ex) {
                    logger.warn("[ApiMonitor] 某 MonitorFailureReportSink 清空失败（已隔离）：{}",
                            ex.getMessage());
                }
            }
        } catch (Exception ex) {
            logger.warn("[ApiMonitor] 写出监控失败报告异常（不影响主报告）：{}", ex.getMessage());
        }
    }

    private void generateCsvReport(String actualReportDir) {
        try {
            Path csvPath = safeResolve(actualReportDir, csvFileName);
            StringBuilder csv = new StringBuilder();

            // CSV 头部
            csv.append("Feature,Scenario,Result,Duration (ms),Error Message\n");

            // 测试数据
            for (TestOutcome t : testOutcomes) {
                String feature = normalizeFeatureName(getFeature(t));
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
                    escapeCsv(normalizeFeatureName(t.featureName)), escapeCsv(t.title), result, duration, error));
            }

            Files.write(csvPath, csv.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.error("Failed to generate CSV report", e);
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        // 如果包含逗号、引号、换行或回车，需要用引号包裹并转义引号
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private void generateZipPackage(String actualReportDir) {
        try {
            Path zipPath = safeResolve(actualReportDir, zipFileName);
            File reportDirectory = safeResolve(actualReportDir).toFile();

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
        // title 经模板 auto-escape；保留原 escape(null)→"" 的空安全语义
        String title = reportTitle == null ? "" : reportTitle;
        String css = getFullCss();

        StringBuilder frag = new StringBuilder();
        appendAlertBar(frag);               String alertBar = frag.toString();              frag.setLength(0);
        appendSummarySection(frag);         String summarySection = frag.toString();        frag.setLength(0);
        appendViewFullReportButton(frag);    String viewFullReportButton = frag.toString();  frag.setLength(0);
        appendCoverageSection(frag);         String coverageSection = frag.toString();       frag.setLength(0);
        appendFailureOverview(frag);         String failureOverview = frag.toString();       frag.setLength(0);
        appendFailureAndResultList(frag);    String failureAndResultList = frag.toString();

        Map<String, Object> model = new HashMap<>();
        model.put("title", title);
        model.put("css", css);
        model.put("alertBar", alertBar);
        model.put("summarySection", summarySection);
        model.put("viewFullReportButton", viewFullReportButton);
        model.put("coverageSection", coverageSection);
        model.put("failureOverview", failureOverview);
        model.put("failureAndResultList", failureAndResultList);

        try {
            return renderSummaryTemplate("summary-report.ftlh", model);
        } catch (TemplateException | IOException e) {
            throw new RuntimeException("Failed to render summary report template", e);
        }
    }

    private static String renderSummaryTemplate(String name, Map<String, Object> model)
            throws TemplateException, IOException {
        Template tpl = FM_CFG.getTemplate(name);
        StringWriter out = new StringWriter();
        tpl.process(model, out);
        // 统一归一为 LF：golden 基线以 LF 为准，模板文件在 Windows 工作区可能被写成 CRLF。
        // 注：模板自身的行尾风格会影响 Freemarker 对「文件末尾换行」的处理（CRLF 会被吞掉），
        // 故 .gitattributes 已钉死 *.ftlh 为 eol=lf，此处归一化仅作为防御性兜底。
        return out.toString().replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 报表内联 CSS。
     *
     * <p>为何<b>不走 Freemarker</b>：本段样式是 100% 静态内容，交给模板引擎解析只会
     * 引入 {@code ${} / <#>} 误判风险且毫无收益，故作为静态资源从 classpath 读取，
     * 再注入骨架模板的 {@code ${css}} 占位符。
     */
    private String getFullCss() {
        String name = "/templates/summary/report-styles.css";
        try (InputStream in = SummaryReportGenerator.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("缺少报表样式资源：" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取报表样式资源失败：" + name, e);
        }
    }

    private void appendAlertBar(StringBuilder sb) {
        Map<String, Object> model = new HashMap<>();
        model.put("title", reportTitle == null ? "" : reportTitle);
        try {
            sb.append(renderSummaryTemplate("summary/alert-bar.ftlh", model));
        } catch (TemplateException | IOException e) {
            throw new RuntimeException("Failed to render alert-bar fragment", e);
        }
    }

    private void appendSummarySection(StringBuilder sb) {
        long total = getTotalTests();
        long pass = count(TestResult.SUCCESS);
        long fail = count(TestResult.FAILURE);
        long error = count(TestResult.ERROR);
        long pending = count(TestResult.PENDING);
        long ignored = count(TestResult.IGNORED);
        long compromised = count(TestResult.COMPROMISED);

        int[] widths = calculateBarWidths(total, pass, pending, ignored, fail, error, compromised);
        List<Map<String, Object>> barCells = List.of(
                barCell("success-background", widths[0], pass, total, "passing tests"),
                barCell("pending-background", widths[1], pending, total, "pending tests"),
                barCell("ignored-background", widths[2], ignored, total, "ignored tests"),
                barCell("failure-background", widths[3], fail, total, "failing tests"),
                barCell("error-background", widths[4], error, total, "broken tests"),
                barCell("compromised-background", widths[5], compromised, total, "compromised tests"));

        List<Map<String, Object>> legendRows1 = List.of(
                legendRow("Passing", pass, "for-passing", "success-badge"),
                legendRow("Pending", pending, "for-pending", "pending-badge"),
                legendRow("Ignored", ignored, "for-ignored", "ignored-badge"));
        List<Map<String, Object>> legendRows2 = List.of(
                legendRow("Failing", fail, "for-failure", "failure-badge"),
                legendRow("Broken", error, "for-error", "error-badge"),
                legendRow("Compromised", compromised, "for-compromised", "compromised-badge"));

        Map<String, Object> timings = new HashMap<>();
        timings.put("total", format(totalDuration));
        timings.put("clock", format(clockTime));
        timings.put("avg", format((long) avgDuration));
        timings.put("max", format(maxDuration));
        timings.put("min", format(minDuration));

        Map<String, Object> model = new HashMap<>();
        model.put("total", total);
        model.put("totalSuffix", total != 1 ? "s" : "");
        model.put("execTime", (testExecutionTime != null ? testExecutionTime : reportTime).format(FORMATTER));
        model.put("barCells", barCells);
        model.put("legendRows1", legendRows1);
        model.put("legendRows2", legendRows2);
        model.put("timings", timings);
        try {
            sb.append(renderSummaryTemplate("summary/summary-section.ftlh", model));
        } catch (TemplateException | IOException e) {
            throw new RuntimeException("Failed to render summary-section fragment", e);
        }
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

    /**
     * Summary bar 单格的视图模型。{@code width <= 0} 时缺少 percent/padding 等键，
     * 模板据此走「单行零占比」分支——与原实现的 {@code if/else} 完全等价。
     */
    private static Map<String, Object> barCell(String cssClass, int width, long count, long total, String title) {
        Map<String, Object> m = new HashMap<>();
        m.put("cssClass", cssClass);
        m.put("width", width);
        if (width > 0) {
            m.put("padding", count > 0 ? "8px" : "0px");
            m.put("hasCount", count > 0);
            m.put("count", count);
            m.put("title", title);
            m.put("percent", total > 0 ? (int) ((count * 100) / total) : 0);
        }
        return m;
    }

    /**
     * 图例行的视图模型。{@code colorStyle} 复用 {@link #getColorStyle(String)}，
     * 保证与改造前的内联取值完全一致。
     */
    private static Map<String, Object> legendRow(String label, long count, String colorClass, String badgeClass) {
        Map<String, Object> m = new HashMap<>();
        m.put("label", label);
        m.put("count", count);
        m.put("colorClass", colorClass);
        m.put("badgeClass", badgeClass);
        m.put("colorStyle", getColorStyle(colorClass));
        return m;
    }

    private static String getColorStyle(String colorClass) {
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
        Map<String, Object> model = new HashMap<>();
        model.put("fullReportLink", this.fullReportUrl);
        model.put("zipLink", buildDownloadUrl(zipFileName));
        try {
            sb.append(renderSummaryTemplate("summary/view-full-report-button.ftlh", model));
        } catch (TemplateException | IOException e) {
            throw new RuntimeException("Failed to render view-full-report-button fragment", e);
        }
    }

    private void appendCoverageSection(StringBuilder sb) {
        List<Map<String, Object>> features = new ArrayList<>();
        for (Map.Entry<String, FeatureStats> entry : calculateFeatureStats().entrySet()) {
            String feature = entry.getKey();
            FeatureStats stats = entry.getValue();

            Map<String, Object> f = new HashMap<>();
            f.put("name", feature);
            f.put("link", buildHtmlLink(featureToHtmlMap.getOrDefault(feature, "index.html")));
            f.put("total", stats.total);
            f.put("passPercent", stats.passPercent());

            // Result bar：显示通过/失败/错误的混合比例
            int passWidth = stats.total > 0 ? (stats.passed * 100) / stats.total : 0;
            int failWidth = stats.total > 0 ? (stats.failed * 100) / stats.total : 0;
            int errorWidth = stats.total > 0 ? (stats.error * 100) / stats.total : 0;
            // 修正取整误差，确保三者和 = 100%（全部通过时 passWidth 已是 100）
            int sum = passWidth + failWidth + errorWidth;
            if (sum > 0 && sum < 100 && passWidth > 0) {
                passWidth += (100 - sum);
            }
            f.put("allPass", passWidth == 100 && stats.total > 0);
            f.put("passWidth", passWidth);
            f.put("failWidth", failWidth);
            f.put("errorWidth", errorWidth);
            f.put("passed", stats.passed);
            f.put("failed", stats.failed);
            f.put("error", stats.error);
            features.add(f);
        }

        Map<String, Object> model = new HashMap<>();
        model.put("features", features);
        try {
            sb.append(renderSummaryTemplate("summary/coverage-section.ftlh", model));
        } catch (TemplateException | IOException e) {
            throw new RuntimeException("Failed to render coverage-section fragment", e);
        }
    }

    private Map<String, FeatureStats> calculateFeatureStats() {
        Map<String, FeatureStats> map = new LinkedHashMap<>();
        for (TestOutcome t : testOutcomes) {
            String f = normalizeFeatureName(getFeature(t));
            map.computeIfAbsent(f, k -> new FeatureStats()).add(t.getResult());
        }
        for (SimpleTestOutcome t : simpleTestOutcomes) {
            map.computeIfAbsent(normalizeFeatureName(t.featureName), k -> new FeatureStats()).add(t.result);
        }
        return map;
    }

    /**
     * 归一化 Feature 名称，确保 testOutcomes 和 simpleTestOutcomes 两个数据源
     * 产生的 feature name 一致，避免同一个 Feature 在 Functional Coverage 中
     * 被错误地分成多个条目显示。
     *
     * <p>归一化规则：
     * <ol>
     *   <li>去除 .feature 扩展名（Serenity Story.getName() 可能返回路径）</li>
     *   <li>统一用最后一段作为 feature 名（如 web/baidu.feature → baidu）</li>
     *   <li>替换下划线/连字符为空格，首字母大写（美化显示）</li>
     * </ol>
     */
    private String normalizeFeatureName(String name) {
        if (name == null || name.isEmpty()) return "No Feature";
        String normalized = name.trim();

        // 1. 去掉 .feature 扩展名
        if (normalized.endsWith(".feature")) {
            normalized = normalized.substring(0, normalized.length() - 8);
        }

        // 2. 取最后一段（路径分割符 / 或 \ 之后的部分）
        int lastSlash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            normalized = normalized.substring(lastSlash + 1);
        }

        // 3. 替换分隔符为空格，便于阅读
        normalized = normalized.replace('_', ' ').replace('-', ' ').trim();

        // 4. 如果只有单个词，首字母大写；多词保持原样
        if (normalized.indexOf(' ') < 0 && normalized.length() > 0) {
            normalized = Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
        }

        return normalized.isEmpty() ? "No Feature" : normalized;
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

                String feature = normalizeFeatureName(getFeature(t));
                featureFailures.computeIfAbsent(feature, k -> new FeatureFailureStats()).increment();
            }
        }
        for (SimpleTestOutcome t : simpleTestOutcomes) {
            if (t.result == TestResult.FAILURE || t.result == TestResult.ERROR) {
                String error = t.errorMessage != null && !t.errorMessage.isEmpty() ? t.errorMessage : "Test failed";
                String errorType = extractErrorType(error);
                failureCounts.merge(errorType, 1, Integer::sum);
                featureFailures.computeIfAbsent(normalizeFeatureName(t.featureName), k -> new FeatureFailureStats()).increment();
            }
        }

        if (failureCounts.isEmpty()) {
            return;
        }

        List<Map<String, Object>> frequentFailures = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : failureCounts.entrySet()) {
            frequentFailures.add(failureRow(entry.getKey(), entry.getValue()));
        }
        List<Map<String, Object>> unstableFeatures = new ArrayList<>();
        for (Map.Entry<String, FeatureFailureStats> entry : featureFailures.entrySet()) {
            unstableFeatures.add(failureRow(entry.getKey(), entry.getValue().count));
        }

        Map<String, Object> model = new HashMap<>();
        model.put("frequentFailures", frequentFailures);
        model.put("unstableFeatures", unstableFeatures);
        try {
            // 错误类型饼图（此处 failureCounts 已保证非空）
            model.put("pieChart", renderErrorTypePieChart(failureCounts));
            sb.append(renderSummaryTemplate("summary/failure-overview.ftlh", model));
        } catch (TemplateException | IOException e) {
            throw new RuntimeException("Failed to render failure-overview fragment", e);
        }
    }

    /** 失败/不稳定条目行的视图模型。 */
    private static Map<String, Object> failureRow(String name, int count) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("count", count);
        return m;
    }

    /**
     * 生成错误类型分布饼图（纯 CSS conic-gradient）
     * 兼容 Outlook / Gmail / Apple Mail 等主流邮件客户端
     */
    private String renderErrorTypePieChart(Map<String, Integer> failureCounts)
            throws TemplateException, IOException {
        int total = failureCounts.values().stream().mapToInt(Integer::intValue).sum();
        // 使用区分度高的颜色（红、橙、蓝、绿、紫、青等）
        String[] colors = {"#e53935", "#ff9800", "#2196f3", "#4caf50", "#9c27b0",
                          "#00bcd4", "#ff5722", "#673ab7", "#009688", "#795548"};
        int pieSize = 240;

        Map<String, Object> model = new HashMap<>();
        model.put("pieSize", pieSize);
        model.put("single", failureCounts.size() == 1);

        if (failureCounts.size() == 1) {
            // 单分类 → 实心圆，中心只显示百分比
            Map.Entry<String, Integer> onlyEntry = failureCounts.entrySet().iterator().next();
            double pct = (double) onlyEntry.getValue() / total * 100;
            model.put("singleColor", colors[0]);
            model.put("singlePct", String.format("%.0f", pct));
        } else {
            // 多分类 → 饼图 + 底部图例
            StringBuilder gradient = new StringBuilder();
            List<Map<String, Object>> legend = new ArrayList<>();
            double currentPct = 0;
            int colorIdx = 0;

            for (Map.Entry<String, Integer> entry : failureCounts.entrySet()) {
                double pct = (double) entry.getValue() / total * 100;

                // conic-gradient: color start% end% (百分比直接是 0-100)
                if (gradient.length() > 0) gradient.append(", ");
                gradient.append(colors[colorIdx % colors.length])
                        .append(" ").append(String.format("%.2f", currentPct)).append("%")
                        .append(" ").append(String.format("%.2f", currentPct + pct)).append("%");

                Map<String, Object> leg = new HashMap<>();
                leg.put("color", colors[colorIdx % colors.length]);
                leg.put("name", entry.getKey());
                leg.put("pct", String.format("%.0f%%", pct));
                legend.add(leg);

                currentPct += pct;
                colorIdx++;
            }
            model.put("gradient", gradient.toString());
            model.put("legend", legend);
        }
        return renderSummaryTemplate("summary/error-type-pie-chart.ftlh", model);
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

        VerboseLogging.logDebugIfVerbose(logger,
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
                String feature = normalizeFeatureName(getFeature(t));
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
                failures.add(new FailureInfo(normalizeFeatureName(t.featureName), t.title, error, html, t.result));
            }
        }

        // Full Failure List：先按 feature 分组，保证同一 feature 只显示一次标题并聚合其下全部 scenario
        List<Map<String, Object>> failureGroups = new ArrayList<>();
        if (!failures.isEmpty()) {
            Map<String, List<FailureInfo>> failuresByFeature = new LinkedHashMap<>();
            for (FailureInfo f : failures) {
                failuresByFeature.computeIfAbsent(f.feature, k -> new ArrayList<>()).add(f);
            }
            for (Map.Entry<String, List<FailureInfo>> entry : failuresByFeature.entrySet()) {
                List<Map<String, Object>> scenarios = new ArrayList<>();
                for (FailureInfo f : entry.getValue()) {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("link", f.htmlLink);
                    s.put("name", f.scenario == null ? "" : f.scenario);
                    s.put("labelColor", resultColor(f.result));
                    s.put("labelText", f.result.name().toLowerCase());
                    s.put("color", resultColor(f.result));
                    s.put("hasError", f.error != null && !f.error.isEmpty());
                    s.put("error", f.error == null ? "" : truncateError(f.error));
                    scenarios.add(s);
                }
                Map<String, Object> group = new LinkedHashMap<>();
                group.put("feature", entry.getKey());
                group.put("scenarios", scenarios);
                failureGroups.add(group);
            }
        }

        // 先按归一化后的 feature 分组，保证同一 feature 只显示一次标题，
        // 并聚合该 feature 下的所有 scenario（testOutcomes + simpleTestOutcomes）。
        // 这样即使 testOutcomes 内部顺序交错，也不会出现同一 feature 重复展示的问题。
        Map<String, List<Map<String, Object>>> rowsByFeature = new LinkedHashMap<>();
        for (TestOutcome t : testOutcomes) {
            String scenarioHtml = scenarioToHtmlMap.getOrDefault(t.getName(), null);
            String html = buildHtmlLink(scenarioHtml != null ? scenarioHtml : "index.html");
            String error = t.getTestFailureMessage() != null ? t.getTestFailureMessage() : "Test failed";
            rowsByFeature.computeIfAbsent(normalizeFeatureName(getFeature(t)), k -> new ArrayList<>())
                    .add(resultRow(html, t.getName() == null ? "" : t.getName(), t.getResult(), error));
        }
        for (SimpleTestOutcome t : simpleTestOutcomes) {
            String scenarioHtml = scenarioToHtmlMap.getOrDefault(t.title, null);
            String html = buildHtmlLink(scenarioHtml != null ? scenarioHtml : "index.html");
            String error = t.errorMessage != null && !t.errorMessage.isEmpty() ? t.errorMessage : "Test failed";
            rowsByFeature.computeIfAbsent(normalizeFeatureName(t.featureName), k -> new ArrayList<>())
                    .add(resultRow(html, t.title == null ? "" : t.title, t.result, error));
        }

        List<Map<String, Object>> resultGroups = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : rowsByFeature.entrySet()) {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("feature", entry.getKey());
            group.put("rows", entry.getValue());
            resultGroups.add(group);
        }

        Map<String, Object> model = new HashMap<>();
        model.put("hasFailures", !failures.isEmpty());
        model.put("failureGroups", failureGroups);
        model.put("csvLink", buildDownloadUrl(csvFileName));
        model.put("resultGroups", resultGroups);
        try {
            sb.append(renderSummaryTemplate("summary/failure-and-result-list.ftlh", model));
        } catch (TemplateException | IOException e) {
            throw new RuntimeException("Failed to render failure-and-result-list fragment", e);
        }
    }

    /**
     * Full Test Results 的单行视图模型。
     * 错误块仅对失败/错误类结果渲染（SUCCESS / IGNORED / SKIPPED 不展示），与原实现完全一致。
     */
    private Map<String, Object> resultRow(String link, String name, TestResult result, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("link", link);
        m.put("name", name);
        m.put("labelColor", resultColor(result));
        m.put("labelText", result.name().toLowerCase());
        m.put("color", resultColor(result));
        m.put("hasError", result != TestResult.SUCCESS
                && result != TestResult.IGNORED
                && result != TestResult.SKIPPED);
        m.put("error", error == null ? "" : truncateError(error));
        return m;
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
        File dir = safeResolve(actualReportDir).toFile();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json") && !n.equals("summary.json"));
        if (files == null || files.length == 0) return;

        VerboseLogging.logDebugIfVerbose(logger, "Found {} json report files in {}", files.length, dir.getAbsolutePath());

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

                // 解析 startTime (ZonedDateTime 格式，如 2026-04-30T16:48:27.302528+08:00)
                if (jo.has("startTime") && jo.get("startTime").isJsonPrimitive()) {
                    try {
                        String startTimeStr = jo.get("startTime").getAsString();
                        // 处理可能包含微秒的时间格式（Java 的 DateTimeFormatter 只支持纳秒，最多9位）
                        if (startTimeStr.contains(".")) {
                            String[] parts = startTimeStr.split("\\.");
                            if (parts.length == 2) {
                                // parts[1] 包含纳秒+时区，如 "302528+08:00"
                                String afterDot = parts[1];
                                // 提取数字部分和时区部分
                                int tzIndex = -1;
                                for (int i = 0; i < afterDot.length(); i++) {
                                    char c = afterDot.charAt(i);
                                    if (c == '+' || c == '-' || c == 'Z') {
                                        tzIndex = i;
                                        break;
                                    }
                                }
                                String nanos = tzIndex >= 0 ? afterDot.substring(0, tzIndex) : afterDot;
                                String tz = tzIndex >= 0 ? afterDot.substring(tzIndex) : "";
                                // 补齐或截断到9位纳秒
                                if (nanos.length() < 9) {
                                    nanos = String.format("%-9s", nanos).replace(' ', '0');
                                } else if (nanos.length() > 9) {
                                    nanos = nanos.substring(0, 9);
                                }
                                startTimeStr = parts[0] + "." + nanos + tz;
                            }
                        }
                        outcome.startTime = ZonedDateTime.parse(startTimeStr);
                    } catch (Exception e) {
                        VerboseLogging.logWarnIfVerbose(logger, "Failed to parse startTime for {}: {}", name, e.getMessage());
                    }
                }

                simpleTestOutcomes.add(outcome);
            } catch (Exception e) {
                VerboseLogging.logWarnIfVerbose(logger, "Failed to parse JSON file: {} - {}", f.getName(), e.getMessage());
            }
        }
    }

    /**
     * 从已加载的测试结果中取最早的 startTime，作为测试实际执行时间。
     * 如果没有找到任何 startTime，回退到报告生成时间。
     */
    private LocalDateTime resolveTestExecutionTime() {
        ZonedDateTime earliest = simpleTestOutcomes.stream()
            .map(t -> t.startTime)
            .filter(Objects::nonNull)
            .min(ZonedDateTime::compareTo)
            .orElse(null);

        if (earliest != null) {
            return earliest.toLocalDateTime();
        }
        return null;
    }

    private void calculateResultCounts() {
        for (TestResult r : TestResult.values()) resultCounts.put(r, 0L);
        testOutcomes.forEach(t -> resultCounts.put(t.getResult(), resultCounts.get(t.getResult()) + 1));
        simpleTestOutcomes.forEach(t -> resultCounts.put(t.result, resultCounts.get(t.result) + 1));
    }

    /**
     * 直接从 index.html 解析时间数据，确保与 Serenity 原生报告完全一致。
     * index.html 实际 HTML 结构（多行格式）：
     *   <td><i class="bi bi-stopwatch"></i> Total
     *                           Duration
     *                       </td>
     *                       <td> 1m 11s</td>
     */
    private void loadDurationsFromIndexHtml(String actualReportDir) {
        Path indexFile = safeResolve(actualReportDir, "index.html");
        if (!Files.exists(indexFile)) {
            VerboseLogging.logWarnIfVerbose(logger, "index.html not found, skipping duration parsing from index.html");
            return;
        }
        try {
            String content = Files.readString(indexFile, StandardCharsets.UTF_8);

            // 解析时间字符串为毫秒的辅助方法
            java.util.function.Function<String, Long> parseDuration = (str) -> {
                if (str == null || str.trim().isEmpty()) return 0L;
                String s = str.trim();
                try {
                    long totalMs = 0;
                    Pattern p = DURATION_PATTERN_MS;
                    Matcher m = p.matcher(s);
                    if (m.find()) {
                        totalMs += Long.parseLong(m.group(1)) * 60_000L + Long.parseLong(m.group(2)) * 1_000L;
                    } else {
                        p = DURATION_PATTERN_UNIT;
                        m = p.matcher(s);
                        while (m.find()) {
                            long val = Long.parseLong(m.group(1));
                            switch (m.group(2).toLowerCase()) {
                                case "h": totalMs += val * 3_600_000L; break;
                                case "m": totalMs += val * 60_000L; break;
                                case "s": totalMs += val * 1_000L; break;
                                case "ms": totalMs += val; break;
                            }
                        }
                    }
                    return totalMs;
                } catch (Exception e) {
                    VerboseLogging.logDebugIfVerbose(logger, "Failed to parse duration string: {}", s);
                    return 0L;
                }
            };

            // 使用 DOTALL 模式，让 . 匹配换行符
            int flags = Pattern.DOTALL;

            // 解析 Total Duration
            // HTML: <i class="bi bi-stopwatch"></i> Total\n Duration\n </td>\n <td>41s</td>
            // 使用 bi-stopwatch（无 -fill）来区分 Total Duration 和 Total Execution Time
            Pattern totalDurPattern = Pattern.compile(
                "bi-stopwatch\"></i>\\s*Total\\s*Duration\\s*</td>\\s*<td>([\\s\\S]*?)</td>", flags);
            Matcher m = totalDurPattern.matcher(content);
            if (m.find()) {
                this.totalDuration = parseDuration.apply(m.group(1));
                VerboseLogging.logDebugIfVerbose(logger, "Parsed Total Duration from index.html: {}", m.group(1).trim());
            } else {
                VerboseLogging.logWarnIfVerbose(logger, "Total Duration NOT found in index.html");
            }

            // 解析 Total Execution Time (= clockTime)
            // HTML: <i class="bi bi-stopwatch-fill"></i> Total\n Execution Time\n </td>\n <td>41s</td>
            Pattern clockPattern = Pattern.compile(
                "bi-stopwatch-fill\"></i>\\s*Total\\s*Execution\\s*Time\\s*</td>\\s*<td>([\\s\\S]*?)</td>", flags);
            m = clockPattern.matcher(content);
            if (m.find()) {
                this.clockTime = parseDuration.apply(m.group(1));
                VerboseLogging.logDebugIfVerbose(logger, "Parsed Total Execution Time from index.html: {}", m.group(1).trim());
            } else {
                VerboseLogging.logWarnIfVerbose(logger, "Total Execution Time NOT found in index.html");
            }

            // 解析 Average Execution Time
            // HTML: <i class="bi bi-stopwatch"></i> Average\n Execution Time\n </td>\n <td>20s</td>
            Pattern avgPattern = Pattern.compile(
                "bi-stopwatch\"></i>\\s*Average\\s*Execution\\s*Time\\s*</td>\\s*<td>([\\s\\S]*?)</td>", flags);
            m = avgPattern.matcher(content);
            if (m.find()) {
                this.avgDuration = parseDuration.apply(m.group(1));
                VerboseLogging.logDebugIfVerbose(logger, "Parsed Avg Execution Time from index.html: {}", m.group(1).trim());
            }

            // 解析 Fastest Test (= minDuration)
            // HTML: <i class="bi bi-trophy"></i> Fastest Test\n </td>\n <td>16s</td>
            Pattern fastestPattern = Pattern.compile(
                "bi-trophy\"></i>\\s*Fastest\\s*Test\\s*</td>\\s*<td>([\\s\\S]*?)</td>", flags);
            m = fastestPattern.matcher(content);
            if (m.find()) {
                this.minDuration = parseDuration.apply(m.group(1));
                VerboseLogging.logDebugIfVerbose(logger, "Parsed Fastest Test from index.html: {}", m.group(1).trim());
            }

            // 解析 Slowest Test (= maxDuration)
            // HTML: <i class="bi bi-skip-start"></i> Slowest\n Test\n </td>\n <td>25s</td>
            Pattern slowestPattern = Pattern.compile(
                "bi-skip-start\"></i>\\s*Slowest\\s*Test\\s*</td>\\s*<td>([\\s\\S]*?)</td>", flags);
            m = slowestPattern.matcher(content);
            if (m.find()) {
                this.maxDuration = parseDuration.apply(m.group(1));
                VerboseLogging.logDebugIfVerbose(logger, "Parsed Slowest Test from index.html: {}", m.group(1).trim());
            }

            VerboseLogging.logInfoIfVerbose(logger,
                "Loaded durations from index.html: total={}, clock={}, avg={}, max={}, min={}",
                format(this.totalDuration), format(this.clockTime),
                format((long)this.avgDuration), format(this.maxDuration), format(this.minDuration));

        } catch (Exception e) {
            VerboseLogging.logWarnIfVerbose(logger, "Failed to parse durations from index.html: {}", e.getMessage());
        }
    }

    /**
     * 计算时间统计（仅作为备选，当 index.html 解析失败时使用）。
     * 优先使用 loadDurationsFromIndexHtml() 从 index.html 直接获取的时间数据，
     * 以确保与 Serenity 原生报告中的 Key Statistics 完全一致。
     */
    private void calculateDurations() {
        // 如果已经从 index.html 获取了数据（clockTime > 0 或 totalDuration > 0），直接返回
        if (this.clockTime > 0 || this.totalDuration > 0) {
            VerboseLogging.logDebugIfVerbose(logger,
                "Using durations from index.html, skipping calculation");
            return;
        }

        // 备选：从 TestOutcome/SimpleTestOutcome 计算
        List<Long> list = new ArrayList<>();
        List<ZonedDateTime> startTimes = new ArrayList<>();
        List<ZonedDateTime> endTimes = new ArrayList<>();

        testOutcomes.forEach(t -> {
            long dur = t.getDuration();
            list.add(dur);
            try {
                ZonedDateTime start = t.getStartTime();
                if (start != null) {
                    startTimes.add(start);
                    endTimes.add(start.plusNanos(dur * 1_000_000));
                }
            } catch (Exception e) {
                // 单条用例的时间解析失败不影响汇总，但不得静默（D7-3）
                logger.debug("[SummaryReport] skip outcome in time-window stats: {}", e.toString());
            }
        });

        for (SimpleTestOutcome s : simpleTestOutcomes) {
            list.add(s.duration);
            if (s.startTime != null) {
                startTimes.add(s.startTime);
                endTimes.add(s.startTime.plusNanos(s.duration * 1_000_000));
            }
        }

        if (list.isEmpty()) {
            totalDuration = minDuration = maxDuration = 0L;
            avgDuration = 0.0;
            clockTime = 0L;
            return;
        }

        totalDuration = list.stream().mapToLong(l -> l).sum();
        minDuration = list.stream().mapToLong(l -> l).min().orElse(0);
        maxDuration = list.stream().mapToLong(l -> l).max().orElse(0);
        avgDuration = list.stream().mapToLong(l -> l).average().orElse(0);

        if (!startTimes.isEmpty() && !endTimes.isEmpty()) {
            ZonedDateTime firstStart = startTimes.stream().min(ZonedDateTime::compareTo).orElse(null);
            ZonedDateTime lastEnd = endTimes.stream().max(ZonedDateTime::compareTo).orElse(null);
            if (firstStart != null && lastEnd != null) {
                clockTime = java.time.Duration.between(firstStart, lastEnd).toMillis();
            }
        }

        if (clockTime == 0 && !list.isEmpty()) {
            clockTime = (long)(totalDuration * 1.3);
        }
    }

    private void loadFeatureHtmlMapping(String actualReportDir) {
        Path indexFile = safeResolve(actualReportDir, "index.html");
        if (!Files.exists(indexFile)) {
            logger.debug("index.html not found at {}, skipping feature mapping", indexFile);
            return;
        }

        try {
            String content = Files.readString(indexFile, StandardCharsets.UTF_8);

            // Serenity BDD index.html 格式: data-title="..." data-link="..."
            // 兼容多种格式：单引号/双引号、data-link/data-href
            Pattern[] patterns = {
                // 标准格式: title:'xxx', link:"yyy"
                Pattern.compile("['\"]title['\"]?\\s*:\\s*['\"]([^'\"]+)['\"]?.+?['\"]link['\"]?\\s*:\\s*['\"]([^\"]+)['\"]", Pattern.DOTALL),
                // data 属性格式: data-title="xxx" data-link="yyy"
                Pattern.compile("data-title\\s*=\\s*[\"']([^\"']*)[\"']\\s+data-link\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.DOTALL),
                // 简化 href 格式
                Pattern.compile("<a[^>]+href=['\"]([^'\"]+)['\"][^>]*>([^<]+)</a>.*?(?:feature|story)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
            };

            for (Pattern p : patterns) {
                Matcher m = p.matcher(content);
                while (m.find()) {
                    String title = null;
                    String link = null;

                    if (m.groupCount() >= 2) {
                        title = m.group(1).trim();
                        link = m.group(2).trim();
                    } else if (m.groupCount() >= 1) {
                        link = m.group(1).trim();
                        // 从 link 路径提取标题
                        Path lp = Paths.get(link).getFileName();
                        title = lp.toString().replaceAll("-", " ");
                    }

                    if (title != null && link != null && !title.isEmpty() && !link.isEmpty()) {
                        featureToHtmlMap.putIfAbsent(normalizeFeatureName(title), link);
                    }
                }
            }

            logger.debug("Loaded {} feature mappings from index.html", featureToHtmlMap.size());
        } catch (Exception e) {
            VerboseLogging.logWarnIfVerbose(logger, "Failed to parse index.html: {}", e.getMessage());
        }
    }

    private void loadScenarioHtmlMapping(String actualReportDir) {
        // JSON 文件和 HTML 文件同名，直接映射
        File[] jsonFiles = safeResolve(actualReportDir).toFile().listFiles((d, n) -> n.endsWith(".json") && !n.equals("summary.json"));
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
                VerboseLogging.logWarnIfVerbose(logger, "Failed to load scenario mapping: {}", f.getName());
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
        ZonedDateTime startTime;

        public SimpleTestOutcome(String title, String rStr, long duration, String featureName) {
            this.title = title;
            this.duration = duration;
            this.featureName = featureName;
            this.scenarioId = title;
            this.errorMessage = "";
            this.startTime = null;
            try { this.result = TestResult.valueOf(rStr.toUpperCase()); }
            catch (Exception e) {
                VerboseLogging.logDebugIfVerbose(logger, "Unknown test result string '{}', defaulting to PENDING", rStr);
                this.result = TestResult.PENDING;
            }
        }
    }

    /**
     * 将 serenity.css 从 classpath 复制到报告 css 目录并追加到 screen.css 末尾。
     */
    private static void injectCustomCss(String reportDir) {
        try {
            Path cssDir = safeResolve(reportDir, "css");
            Files.createDirectories(cssDir);

            String customCss;
            try (var in = SummaryReportGenerator.class.getResourceAsStream("/report/serenity.css")) {
                if (in == null) {
                    logger.warn("serenity.css not found on classpath, skipping CSS injection");
                    return;
                }
                customCss = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            Path cssFile = cssDir.resolve("serenity.css");
            Files.writeString(cssFile, customCss, StandardCharsets.UTF_8);
            logger.debug("       - Copied serenity.css to report css/ directory");

            Path screenCss = cssDir.resolve("screen.css");
            if (Files.exists(screenCss)) {
                String existing = Files.readString(screenCss, StandardCharsets.UTF_8);
                if (!existing.contains(customCss.trim())) {
                    Files.writeString(screenCss,
                            existing + System.lineSeparator() + System.lineSeparator() + customCss,
                            StandardCharsets.UTF_8);
                    logger.debug("       - Appended serenity.css to screen.css");
                }
            }
        } catch (Exception e) {
            logger.warn("CSS injection skipped (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * 将截图轮播 effect 从 'fade' 改为 'slide' + autoHeight，
     * 解决混合高度截图的空白和重叠问题。
     */
    private static void fixSwiperScreenshotsHtml(String reportDir) {
        try {
            Path dir = safeResolve(reportDir);
            if (!Files.isDirectory(dir)) return;

            int fixedCount = 0;
            try (var stream = Files.newDirectoryStream(dir, "*_screenshots.html")) {
                for (Path file : stream) {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    if (content.contains("effect: 'fade'")) {
                        String fixed = content.replace("effect: 'fade'", "effect: 'slide', autoHeight: true");
                        Files.writeString(file, fixed, StandardCharsets.UTF_8);
                        fixedCount++;
                    }
                }
            }
            if (fixedCount > 0) {
                logger.debug("       - Swiper fix applied to {} screenshots page(s) (fade → slide + autoHeight)", fixedCount);
            }
        } catch (Exception e) {
            logger.warn("Swiper screenshots fix skipped (non-fatal): {}", e.getMessage());
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

        VerboseLogging.logInfoIfVerbose(logger, "Using report directory: {}", reportDir);

        // 支持通过环境变量指定 serenity.properties 路径
        String propertiesPath = System.getProperty("serenity.properties.path");
        if (propertiesPath != null && !propertiesPath.trim().isEmpty()) {
            System.setProperty("user.dir", new File(propertiesPath).getParent());
            VerboseLogging.logInfoIfVerbose(logger, "Setting working directory to: {}", System.getProperty("user.dir"));
        }

        new SummaryReportGenerator(reportDir).generateSummaryReport();
    }
}