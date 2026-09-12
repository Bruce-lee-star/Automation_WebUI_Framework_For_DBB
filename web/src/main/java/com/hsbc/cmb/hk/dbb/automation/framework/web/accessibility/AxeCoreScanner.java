package com.hsbc.cmb.hk.dbb.automation.framework.web.accessibility;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
// 注：本类已彻底移除对 com.deque.html.axecore:playwright Java 包装器的依赖，
//     扫描经 AxeCoreScriptProvider 注入自带 axe.min.js 实现，结果模型为自有 AxeRule/AxeNode。
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

/**
 * Axe-core Accessibility Scanner
 * <p>
 * 集成 axe-core 做 WCAG 合规检测。底层不再依赖 Deque 的 {@code com.deque.html.axecore:playwright}
 * Java 包装器，而是经 {@link AxeCoreScriptProvider} 注入框架自带的 {@code axe.min.js} 并调用
 * {@code axe.run}，结果映射为框架自有 {@link AxeRule}/{@link AxeNode} 模型。从而使 axe-core 版本
 * 与 Playwright 版本完全解耦、独立演进。
 * <p>
 * 本类为稳定公开门面（业务/监听器/AxeCoreListener 调用），对外 API 与结果字段模型保持零变更。
 */
public class AxeCoreScanner {

    private static final Logger logger = LoggerFactory.getLogger(AxeCoreScanner.class);

    //  T3-1 收拢：三处 static ThreadLocal 迁入 TestContext（三者原本均为默认 null 语义，迁移后等价）
    private static final ContextKey<List> RESULTS_KEY = ContextKey.of("axeScanner.results", List.class);
    private static final ContextKey<Boolean> INITIALIZED_KEY = ContextKey.of("axeScanner.initialized", Boolean.class);
    private static final ContextKey<AxeScanConfig> CONFIG_KEY = ContextKey.of("axeScanner.config", AxeScanConfig.class);

    /** 取当前线程的扫描结果 List（等价原 results()，未初始化时为 null）。 */
    @SuppressWarnings("unchecked")
    private static List<AxeScanResult> results() {
        return (List<AxeScanResult>) TestContextHolder.get().get(RESULTS_KEY);
    }

    /**
     * Configuration for Axe-core scanning
     */
    public static class AxeScanConfig {
        private String projectName = PlaywrightManager.config().getProjectName();
        private boolean includeViolations = true;
        private boolean includeIncomplete = true;
        private boolean includePasses = false;
        private List<String> tags = new ArrayList<>();
        private List<String> rules = new ArrayList<>();
        private List<String> excludeRules = new ArrayList<>();
        private String reportOutputDir = FrameworkConfigManager.getString(WebFrameworkConfig.AXE_SCAN_OUTPUT_DIR);

        public String getProjectName() { return projectName; }
        public void setProjectName(String projectName) { this.projectName = projectName; }
        public boolean isIncludeViolations() { return includeViolations; }
        public void setIncludeViolations(boolean includeViolations) { this.includeViolations = includeViolations; }
        public boolean isIncludeIncomplete() { return includeIncomplete; }
        public void setIncludeIncomplete(boolean includeIncomplete) { this.includeIncomplete = includeIncomplete; }
        public boolean isIncludePasses() { return includePasses; }
        public void setIncludePasses(boolean includePasses) { this.includePasses = includePasses; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
        public List<String> getRules() { return rules; }
        public void setRules(List<String> rules) { this.rules = rules; }
        public List<String> getExcludeRules() { return excludeRules; }
        public void setExcludeRules(List<String> excludeRules) { this.excludeRules = excludeRules; }
        public String getReportOutputDir() { return reportOutputDir; }
        public void setReportOutputDir(String reportOutputDir) { this.reportOutputDir = reportOutputDir; }
    }

    /**
     * Result of a single page axe-core scan
     */
    public static class AxeScanResult {
        private String pageName;
        private String pageUrl;
        private int violationCount;
        private int incompleteCount;
        private int passCount;
        private boolean scanError;
        private String scanErrorMessage;
        private List<AxeRule> violations = new ArrayList<>();
        private List<AxeRule> incomplete = new ArrayList<>();
        private List<AxeRule> passes = new ArrayList<>();

        public AxeScanResult(String pageName, String pageUrl) {
            this.pageName = pageName;
            this.pageUrl = pageUrl;
        }

        public String getPageName() { return pageName; }
        public String getPageUrl() { return pageUrl; }
        public int getViolationCount() { return violationCount; }
        public int getIncompleteCount() { return incompleteCount; }
        public int getPassCount() { return passCount; }
        public List<AxeRule> getViolations() { return violations; }
        public void setViolations(List<AxeRule> violations) {
            this.violations = violations != null ? violations : new ArrayList<>();
            this.violationCount = this.violations.size();
        }
        public List<AxeRule> getIncomplete() { return incomplete; }
        public void setIncomplete(List<AxeRule> incomplete) {
            this.incomplete = incomplete != null ? incomplete : new ArrayList<>();
            this.incompleteCount = this.incomplete.size();
        }
        public void setPasses(List<AxeRule> passes) {
            this.passes = passes != null ? passes : new ArrayList<>();
            this.passCount = this.passes.size();
        }

        public boolean isPassed() {
            return !scanError && violationCount == 0;
        }
        public boolean isScanError() { return scanError; }
        public String getScanErrorMessage() { return scanErrorMessage; }
        public void setScanError(boolean scanError, String message) {
            this.scanError = scanError;
            this.scanErrorMessage = message;
        }
    }

    /**
     * Initialize the scanner
     */
    public static void initialize() {
        initialize(new AxeScanConfig());
    }

    /**
     * Initialize the scanner with configuration
     */
    public static void initialize(AxeScanConfig scanConfig) {
        if (TestContextHolder.get().get(INITIALIZED_KEY) != null && TestContextHolder.get().get(INITIALIZED_KEY)) {
            logger.info("AxeCoreScanner already initialized");
            return;
        }
        TestContextHolder.get().set(RESULTS_KEY, new ArrayList<>());
        TestContextHolder.get().set(CONFIG_KEY, scanConfig);
        TestContextHolder.get().set(INITIALIZED_KEY, true);
        logger.info("AxeCoreScanner initialized with project: {}", scanConfig.getProjectName());
    }

    /**
     * Check if scanner is initialized
     */
    public static boolean isInitialized() {
        return TestContextHolder.get().get(INITIALIZED_KEY) != null && TestContextHolder.get().get(INITIALIZED_KEY);
    }

    /**
     * Set configuration
     */
    public static void setConfig(AxeScanConfig scanConfig) {
        TestContextHolder.get().set(CONFIG_KEY, scanConfig);
    }

    /**
     * Get configuration
     */
    public static AxeScanConfig getConfig() {
        return TestContextHolder.get().get(CONFIG_KEY);
    }

    /**
     * Scan current page for accessibility issues using axe-core
     * Automatically retrieves the current Page from PlaywrightManager
     * 
     * @param pageName Descriptive name for the page being scanned
     * @return AxeScanResult containing the scan results
     */
    public static AxeScanResult scanPage(String pageName) {
        return scanPage(pageName, PlaywrightManager.getPage());
    }
    
    /**
     * Scan current page with custom selector context
     * Automatically retrieves the current Page from PlaywrightManager
     * 
     * @param pageName Descriptive name for the page being scanned
     * @param contextSelector CSS selector to limit the scan scope
     * @return AxeScanResult containing the scan results
     */
    public static AxeScanResult scanPage(String pageName, String contextSelector) {
        return scanPage(pageName, PlaywrightManager.getPage(), contextSelector);
    }

    /**
     * Scan a page for accessibility issues using axe-core (with explicit Page)
     * 
     * @param pageName Descriptive name for the page being scanned
     * @param page Playwright Page object to scan
     * @return AxeScanResult containing the scan results
     */
    public static AxeScanResult scanPage(String pageName, Page page) {
        return scanPage(pageName, page, null);
    }

    /**
     * Scan a page with custom selector context (with explicit Page)
     * 
     * @param pageName Descriptive name for the page being scanned
     * @param page Playwright Page object to scan
     * @param contextSelector CSS selector to limit the scan scope (null for full page)
     * @return AxeScanResult containing the scan results
     */
    public static AxeScanResult scanPage(String pageName, Page page, String contextSelector) {
        if (!isInitialized()) {
            initialize();
        }

        AxeScanConfig scanConfig = TestContextHolder.get().get(CONFIG_KEY);
        AxeScanResult result = new AxeScanResult(pageName, page.url());

        try {
            boolean hasContext = contextSelector != null && !contextSelector.isEmpty();
            logger.info("Starting axe-core scan for: {}{}",
                pageName, hasContext ? " (context: " + contextSelector + ")" : "");
            logger.debug("Page URL before scan: {}", page.url());
            logger.debug("Page count in context before scan: {}", page.context().pages().size());

            // 经自带 axe.min.js 注入 + axe.run 执行扫描（解耦 Deque Java 包装器，参见 AxeCoreScriptProvider）
            if (hasContext) {
                logger.debug("Axe-core context selector: {}", contextSelector);
            }
            logger.debug("Running axe-core analyze() via injected script...");
            AxeCoreScriptProvider.AxeRunResult axeResults =
                    AxeCoreScriptProvider.runAxe(page, scanConfig, contextSelector);
            logger.debug("Axe-core analyze() completed");
            logger.debug("Page count in context after scan: {}", page.context().pages().size());

            // Process results
            result.setViolations(axeResults.violations());
            result.setIncomplete(axeResults.incomplete());
            result.setPasses(axeResults.passes());

            // Store result
            results().add(result);

            logger.info("Axe-core scan completed for {}: {} violations, {} incomplete, {} passes",
                pageName, result.getViolationCount(), result.getIncompleteCount(), result.getPassCount());

        } catch (Exception e) {
            logger.error("Error during axe-core scan for {}: {}", pageName, e.getMessage(), e);
            result.setScanError(true, e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    /**
     * Get all scan results
     */
    public static List<AxeScanResult> getResults() {
        return results();
    }

    /**
     * Generate aggregated HTML report
     */
    public static String generateReport() {
        if (!isInitialized() || results() == null || results().isEmpty()) {
            logger.warn("No results to generate report");
            return null;
        }

        AxeScanConfig scanConfig = TestContextHolder.get().get(CONFIG_KEY);
        List<AxeScanResult> allResults = results();

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        html.append("    <meta charset=\"UTF-8\">\n");
        html.append("    <title>").append(escapeHtml(scanConfig.getProjectName())).append(" - Axe-core Report</title>\n");
        html.append("    <style>\n").append(getReportStyles()).append("    </style>\n");
        html.append("</head>\n<body>\n    <div class=\"report\">\n");

        // Header
        html.append("        <h1 class=\"title\">").append(escapeHtml(scanConfig.getProjectName())).append("</h1>\n");
        html.append("        <h2 class=\"subtitle\">Axe-core Accessibility Test Report</h2>\n");
        html.append("        <p class=\"subtitle\">Generated: ").append(timestamp).append("</p>\n");

        // WCAG Standards - display under project name (only if tags are configured)
        if (scanConfig.getTags() != null && !scanConfig.getTags().isEmpty()) {
            html.append("        <p class=\"subtitle\">WCAG Standards: ");
            for (int i = 0; i < scanConfig.getTags().size(); i++) {
                if (i > 0) html.append(", ");
                html.append(escapeHtml(scanConfig.getTags().get(i)));
            }
            html.append("</p>\n");
        }

        // Overall statistics
        int totalPages = allResults.size();
        int totalViolations = allResults.stream().mapToInt(AxeScanResult::getViolationCount).sum();
        int totalIncomplete = allResults.stream().mapToInt(AxeScanResult::getIncompleteCount).sum();
        int totalPasses = allResults.stream().mapToInt(AxeScanResult::getPassCount).sum();
        int passedPages = (int) allResults.stream().filter(AxeScanResult::isPassed).count();

        html.append("        <div class=\"section\">\n");
        html.append("            <h2>Overall Statistics</h2>\n");
        html.append("            <div class=\"stats\">\n");
        html.append("                <div class=\"stat-item\">Pages Scanned<div class=\"num\">").append(totalPages).append("</div></div>\n");
        html.append("                <div class=\"stat-item\">Pages Passed<div class=\"num pass\">").append(passedPages).append("</div></div>\n");
        html.append("                <div class=\"stat-item\">Total Violations<div class=\"num\" style=\"color:#dc3545;\">").append(totalViolations).append("</div></div>\n");
        html.append("                <div class=\"stat-item\">Needs Review<div class=\"num\" style=\"color:#f9c74f;\">").append(totalIncomplete).append("</div></div>\n");
        html.append("                <div class=\"stat-item\">Rules Passed<div class=\"num\" style=\"color:#28a745;\">").append(totalPasses).append("</div></div>\n");
        html.append("            </div>\n        </div>\n");

        // Page results
        html.append("        <div class=\"section\">\n            <h2>Page Results</h2>\n");
        for (AxeScanResult result : allResults) {
            html.append(generatePageResultCard(result));
        }
        html.append("        </div>\n");

        // Violation details
        if (totalViolations > 0) {
            html.append("        <div class=\"section\">\n            <h2>Violation Details</h2>\n");
            for (AxeScanResult result : allResults) {
                if (!result.getViolations().isEmpty()) {
                    html.append("            <h3 style=\"color:#2c3e50;margin-top:20px;\">").append(escapeHtml(result.getPageName())).append("</h3>\n");
                    for (AxeRule violation : result.getViolations()) {
                        html.append(generateViolationDetail(violation, "violation"));
                    }
                }
            }
            html.append("        </div>\n");
        }

        // Needs Review details (Incomplete items)
        if (totalIncomplete > 0) {
            html.append("        <div class=\"section\">\n            <h2>Needs Review</h2>\n");
            html.append("            <p style=\"color:#7f8c8d;margin-bottom:15px;\">These items require manual review to determine if they are accessibility issues.</p>\n");
            for (AxeScanResult result : allResults) {
                if (!result.getIncomplete().isEmpty()) {
                    html.append("            <h3 style=\"color:#2c3e50;margin-top:20px;\">").append(escapeHtml(result.getPageName())).append("</h3>\n");
                    for (AxeRule incomplete : result.getIncomplete()) {
                        html.append(generateViolationDetail(incomplete, "incomplete"));
                    }
                }
            }
            html.append("        </div>\n");
        }

        html.append("    </div>\n</body>\n</html>");

        // Save report
        String reportPath = saveReport(html.toString(), scanConfig.getReportOutputDir());
        logger.info("Axe-core report generated: {}", reportPath);

        return html.toString();
    }

    private static String generatePageResultCard(AxeScanResult result) {
        String statusColor = result.isPassed() ? "#28a745" : "#dc3545";
        String statusText = result.isPassed() ? "PASS" : "FAIL";

        StringBuilder html = new StringBuilder();
        html.append("            <div class=\"result-card\">\n");
        html.append("                <div class=\"result-header\">\n");
        html.append("                    <span>").append(escapeHtml(result.getPageName())).append("</span>\n");
        html.append("                    <span class=\"badge\" style=\"background:").append(statusColor).append(";\">").append(statusText).append("</span>\n");
        html.append("                </div>\n");
        html.append("                <div class=\"result-body\">\n");
        html.append("                    <p><b>URL:</b> ").append(escapeHtml(result.getPageUrl())).append("</p>\n");
        html.append("                    <p><b>Violations:</b> <span style=\"color:#dc3545;\">").append(result.getViolationCount()).append("</span></p>\n");
        html.append("                    <p><b>Needs Review:</b> <span style=\"color:#f9c74f;\">").append(result.getIncompleteCount()).append("</span></p>\n");
        html.append("                    <p><b>Rules Passed:</b> <span style=\"color:#28a745;\">").append(result.getPassCount()).append("</span></p>\n");
        html.append("                </div>\n            </div>\n");
        return html.toString();
    }

    private static String generateViolationDetail(AxeRule rule, String type) {
        String impactColor = getImpactColor(rule.getImpact());
        String cardClass = "incomplete".equals(type) ? "incomplete-card" : "violation-card";

        StringBuilder html = new StringBuilder();
        html.append("            <div class=\"").append(cardClass).append("\">\n");
        html.append("                <div class=\"violation-header\" style=\"border-left:4px solid ").append(impactColor).append(";\">\n");
        html.append("                    <h4>").append(escapeHtml(rule.getId())).append("</h4>\n");
        html.append("                    <span class=\"impact\" style=\"background:").append(impactColor).append(";\">").append(rule.getImpact()).append("</span>\n");
        html.append("                </div>\n");
        html.append("                <div class=\"violation-body\">\n");
        html.append("                    <p><b>Description:</b> ").append(escapeHtml(rule.getDescription())).append("</p>\n");
        html.append("                    <p><b>Help:</b> ").append(escapeHtml(rule.getHelp())).append("</p>\n");
        html.append("                    <p><a href=\"").append(rule.getHelpUrl()).append("\" target=\"_blank\">Learn more</a></p>\n");
        html.append("                    <p><b>Affected Elements:</b> ").append(rule.getNodes().size()).append("</p>\n");

        int count = 0;
        for (AxeNode node : rule.getNodes()) {
            if (count++ >= 5) {
                html.append("                    <p style=\"color:#666;\">... and ").append(rule.getNodes().size() - 5).append(" more</p>\n");
                break;
            }
            html.append("                    <div class=\"node-detail\">\n");
            html.append("                        <code>").append(escapeHtml(String.valueOf(node.getTarget()))).append("</code>\n");
            if (node.getFailureSummary() != null) {
                html.append("                        <p class=\"failure\">").append(escapeHtml(node.getFailureSummary())).append("</p>\n");
            }
            html.append("                    </div>\n");
        }

        html.append("                </div>\n            </div>\n");
        return html.toString();
    }

    private static String getImpactColor(String impact) {
        if (impact == null) return "#6c757d";
        switch (impact.toLowerCase()) {
            case "critical": return "#dc3545";
            case "serious": return "#fd7e14";
            case "moderate": return "#f9c74f";
            case "minor": return "#17a2b8";
            default: return "#6c757d";
        }
    }

    private static String getReportStyles() {
        return "* { margin: 0; padding: 0; box-sizing: border-box; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }\n" +
               "body { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 20px; min-height: 100vh; }\n" +
               ".report { max-width: 1200px; margin: 0 auto; background: #fff; padding: 40px; border-radius: 16px; box-shadow: 0 20px 60px rgba(0,0,0,0.15); }\n" +
               ".title { text-align: center; margin-bottom: 10px; font-size: 32px; color: #2c3e50; }\n" +
               ".subtitle { text-align: center; color: #7f8c8d; margin-bottom: 30px; font-size: 14px; }\n" +
               ".section { margin-bottom: 30px; padding: 25px; background: #f8f9fa; border-radius: 12px; }\n" +
               ".section h2 { font-size: 20px; margin-bottom: 20px; color: #34495e; }\n" +
               ".stats { display: flex; gap: 20px; flex-wrap: wrap; }\n" +
               ".stat-item { flex: 1; min-width: 150px; background: #fff; padding: 20px; border-radius: 10px; text-align: center; border: 1px solid #e9ecef; }\n" +
               ".stat-item .num { font-size: 28px; font-weight: bold; margin-top: 10px; }\n" +
               ".pass { color: #28a745; }\n" +
               ".tags-container { display: flex; gap: 10px; flex-wrap: wrap; justify-content: center; }\n" +
               ".tag { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 6px 14px; border-radius: 20px; font-size: 12px; font-weight: bold; }\n" +
               ".result-card { border: 1px solid #e9ecef; border-radius: 10px; margin-bottom: 15px; overflow: hidden; }\n" +
               ".result-header { padding: 15px 20px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; display: flex; justify-content: space-between; align-items: center; }\n" +
               ".badge { padding: 6px 14px; border-radius: 20px; color: white; font-size: 12px; font-weight: bold; }\n" +
               ".result-body { padding: 20px; }\n" +
               ".result-body p { margin-bottom: 8px; color: #555; }\n" +
               ".violation-card { background: #fff; border-radius: 8px; margin-bottom: 15px; border: 1px solid #e9ecef; }\n" +
               ".incomplete-card { background: #fffbe6; border-radius: 8px; margin-bottom: 15px; border: 1px solid #ffe58f; }\n" +
               ".violation-header { padding: 15px; background: #f8f9fa; display: flex; justify-content: space-between; align-items: center; }\n" +
               ".violation-header h4 { color: #2c3e50; }\n" +
               ".impact { padding: 4px 12px; border-radius: 20px; color: white; font-size: 11px; text-transform: uppercase; }\n" +
               ".violation-body { padding: 15px; }\n" +
               ".violation-body p { margin-bottom: 10px; color: #555; }\n" +
               ".node-detail { background: #f8f9fa; padding: 10px; border-radius: 6px; margin-bottom: 8px; }\n" +
               ".node-detail code { font-size: 12px; color: #2c3e50; }\n" +
               ".failure { margin-top: 8px; font-size: 12px; color: #dc3545; }\n";
    }

    private static String saveReport(String html, String outputDir) {
        try {
            Path dirPath = Paths.get(outputDir);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }

            String fileName = "axe-accessibility-report-" + 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".html";
            Path filePath = dirPath.resolve(fileName);

            try (FileWriter writer = new FileWriter(filePath.toFile(), java.nio.charset.StandardCharsets.UTF_8)) {
                writer.write(html);
            }
            logger.info("Accessibility Report saved: {}", filePath.toString());
            return filePath.toString();
        } catch (IOException e) {
            logger.error("Failed to save report: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Cleanup and reset scanner
     */
    public static void cleanup() {
        //  修复 B-6：原 initialized.set(false) 仅置标记、ThreadLocal entry 仍驻留线程，
        //   线程池复用场景下 results/config/initialized 的 entry 长期不死（results 持 List 引用）。
        //   改为 .remove() 彻底清除 entry，下次 initialize 会重新 set，行为与 set(false) 等价但无泄漏。
        TestContextHolder.get().remove(RESULTS_KEY);
        TestContextHolder.get().remove(CONFIG_KEY);
        TestContextHolder.get().remove(INITIALIZED_KEY);
        logger.info("AxeCoreScanner cleanup completed");
    }

    private static String escapeHtml(String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&#39;");
    }
}
