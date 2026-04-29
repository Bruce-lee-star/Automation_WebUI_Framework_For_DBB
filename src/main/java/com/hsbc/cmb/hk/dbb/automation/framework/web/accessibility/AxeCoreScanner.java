package com.hsbc.cmb.hk.dbb.automation.framework.web.accessibility;

import com.deque.html.axecore.playwright.AxeBuilder;
import com.deque.html.axecore.results.AxeResults;
import com.deque.html.axecore.results.CheckedNode;
import com.deque.html.axecore.results.Rule;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
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

/**
 * Axe-core Accessibility Scanner
 * Integration with Deque's axe-core library for WCAG compliance testing
 */
public class AxeCoreScanner {

    private static final Logger logger = LoggerFactory.getLogger(AxeCoreScanner.class);

    private static final ThreadLocal<List<AxeScanResult>> results = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> initialized = new ThreadLocal<>();
    private static final ThreadLocal<AxeScanConfig> config = new ThreadLocal<>();

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
        private String reportOutputDir = FrameworkConfigManager.getString(FrameworkConfig.AXE_SCAN_OUTPUT_DIR);

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
        private List<Rule> violations = new ArrayList<>();
        private List<Rule> incomplete = new ArrayList<>();
        private List<Rule> passes = new ArrayList<>();

        public AxeScanResult(String pageName, String pageUrl) {
            this.pageName = pageName;
            this.pageUrl = pageUrl;
        }

        public String getPageName() { return pageName; }
        public String getPageUrl() { return pageUrl; }
        public int getViolationCount() { return violationCount; }
        public int getIncompleteCount() { return incompleteCount; }
        public int getPassCount() { return passCount; }
        public List<Rule> getViolations() { return violations; }
        public void setViolations(List<Rule> violations) {
            this.violations = violations != null ? violations : new ArrayList<>();
            this.violationCount = this.violations.size();
        }
        public List<Rule> getIncomplete() { return incomplete; }
        public void setIncomplete(List<Rule> incomplete) {
            this.incomplete = incomplete != null ? incomplete : new ArrayList<>();
            this.incompleteCount = this.incomplete.size();
        }
        public void setPasses(List<Rule> passes) {
            this.passes = passes != null ? passes : new ArrayList<>();
            this.passCount = this.passes.size();
        }

        public boolean isPassed() {
            return violationCount == 0;
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
        if (initialized.get() != null && initialized.get()) {
            logger.info("AxeCoreScanner already initialized");
            return;
        }
        results.set(new ArrayList<>());
        config.set(scanConfig);
        initialized.set(true);
        logger.info("AxeCoreScanner initialized with project: {}", scanConfig.getProjectName());
    }

    /**
     * Check if scanner is initialized
     */
    public static boolean isInitialized() {
        return initialized.get() != null && initialized.get();
    }

    /**
     * Set configuration
     */
    public static void setConfig(AxeScanConfig scanConfig) {
        config.set(scanConfig);
    }

    /**
     * Get configuration
     */
    public static AxeScanConfig getConfig() {
        return config.get();
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
        if (!isInitialized()) {
            initialize();
        }

        AxeScanConfig scanConfig = config.get();
        AxeScanResult result = new AxeScanResult(pageName, page.url());

        try {
            logger.info("Starting axe-core scan for: {}", pageName);
            logger.debug("Page URL before scan: {}", page.url());
            logger.debug("Page count in context before scan: {}", page.context().pages().size());

            // Build axe scanner with configuration
            AxeBuilder axeBuilder = new AxeBuilder(page);

            // Set tags (WCAG levels)
            if (scanConfig.getTags() != null && !scanConfig.getTags().isEmpty()) {
                axeBuilder.withTags(scanConfig.getTags());
                logger.debug("Axe-core tags: {}", scanConfig.getTags());
            }

            // Set specific rules to run
            if (scanConfig.getRules() != null && !scanConfig.getRules().isEmpty()) {
                axeBuilder.withRules(scanConfig.getRules());
                logger.debug("Axe-core rules: {}", scanConfig.getRules());
            }

            // Exclude specific rules
            if (scanConfig.getExcludeRules() != null && !scanConfig.getExcludeRules().isEmpty()) {
                axeBuilder.disableRules(scanConfig.getExcludeRules());
                logger.debug("Axe-core excluded rules: {}", scanConfig.getExcludeRules());
            }

            // Run axe-core analysis
            logger.debug("Running axe-core analyze()...");
            AxeResults axeResults = axeBuilder.analyze();
            logger.debug("Axe-core analyze() completed");
            logger.debug("Page count in context after scan: {}", page.context().pages().size());

            // Process results
            result.setViolations(axeResults.getViolations());
            result.setIncomplete(axeResults.getIncomplete());
            result.setPasses(axeResults.getPasses());

            // Store result
            results.get().add(result);

            logger.info("Axe-core scan completed for {}: {} violations, {} incomplete, {} passes",
                pageName, result.getViolationCount(), result.getIncompleteCount(), result.getPassCount());
        } catch (Exception e) {
            logger.error("Error during axe-core scan for {}: {}", pageName, e.getMessage(), e);
        }

        return result;
    }

    /**
     * Scan a page with custom selector context (with explicit Page)
     * 
     * @param pageName Descriptive name for the page being scanned
     * @param page Playwright Page object to scan
     * @param contextSelector CSS selector to limit the scan scope
     * @return AxeScanResult containing the scan results
     */
    public static AxeScanResult scanPage(String pageName, Page page, String contextSelector) {
        if (!isInitialized()) {
            initialize();
        }

        AxeScanConfig scanConfig = config.get();
        AxeScanResult result = new AxeScanResult(pageName, page.url());

        try {
            logger.info("Starting axe-core scan for: {} (context: {})", pageName, contextSelector);
            logger.debug("Page URL before scan: {}", page.url());
            logger.debug("Page count in context before scan: {}", page.context().pages().size());

            AxeBuilder axeBuilder = new AxeBuilder(page);

            if (scanConfig.getTags() != null && !scanConfig.getTags().isEmpty()) {
                axeBuilder.withTags(scanConfig.getTags());
                logger.debug("Axe-core tags: {}", scanConfig.getTags());
            }

            if (scanConfig.getRules() != null && !scanConfig.getRules().isEmpty()) {
                axeBuilder.withRules(scanConfig.getRules());
                logger.debug("Axe-core rules: {}", scanConfig.getRules());
            }

            if (scanConfig.getExcludeRules() != null && !scanConfig.getExcludeRules().isEmpty()) {
                axeBuilder.disableRules(scanConfig.getExcludeRules());
                logger.debug("Axe-core excluded rules: {}", scanConfig.getExcludeRules());
            }

            // Include specific context
            if (contextSelector != null && !contextSelector.isEmpty()) {
                axeBuilder.include(contextSelector);
                logger.debug("Axe-core context selector: {}", contextSelector);
            }

            logger.debug("Running axe-core analyze()...");
            AxeResults axeResults = axeBuilder.analyze();
            logger.debug("Axe-core analyze() completed");
            logger.debug("Page count in context after scan: {}", page.context().pages().size());

            result.setViolations(axeResults.getViolations());
            result.setIncomplete(axeResults.getIncomplete());
            result.setPasses(axeResults.getPasses());

            results.get().add(result);

            logger.info("Axe-core scan completed for {}: {} violations, {} incomplete, {} passes",
                pageName, result.getViolationCount(), result.getIncompleteCount(), result.getPassCount());

        } catch (Exception e) {
            logger.error("Error during axe-core scan for {}: {}", pageName, e.getMessage(), e);
        }

        return result;
    }

    /**
     * Get all scan results
     */
    public static List<AxeScanResult> getResults() {
        return results.get();
    }

    /**
     * Generate aggregated HTML report
     */
    public static String generateReport() {
        if (!isInitialized() || results.get() == null || results.get().isEmpty()) {
            logger.warn("No results to generate report");
            return null;
        }

        AxeScanConfig scanConfig = config.get();
        List<AxeScanResult> allResults = results.get();

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
                    for (Rule violation : result.getViolations()) {
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
                    for (Rule incomplete : result.getIncomplete()) {
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

    private static String generateViolationDetail(Rule rule, String type) {
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
        for (CheckedNode node : rule.getNodes()) {
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

            try (FileWriter writer = new FileWriter(filePath.toFile())) {
                writer.write(html);
            }
            System.out.println(" - Accessibility Report: " + filePath.toUri());
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
        results.remove();
        config.remove();
        initialized.set(false);
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
