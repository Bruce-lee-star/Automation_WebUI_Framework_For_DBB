package com.hsbc.cmb.hk.dbb.automation.framework.web.accessibility;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Accessibility Test Collector (Simplified Version)
 * Collects accessibility test results for all pages during test execution
 * Users only need to provide descriptive page names, e.g.: "Login Page", "Home Page", "Order Input Page"
 * 
 * Features:
 * - Individual HTML report for each page
 * - Final aggregated report
 * - Page object automatically retrieved from PlaywrightManager
 * - All reports saved to target/accessibility/ directory
 */
public class AccessibilityScanner {

    private static final Logger logger = LoggerFactory.getLogger(AccessibilityScanner.class);

    // ThreadLocal for thread safety
    private static final ThreadLocal<List<PageResult>> results = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<String>> generatedReports = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<LocalDateTime> testStartTime = ThreadLocal.withInitial(LocalDateTime::now);
    private static final ThreadLocal<ScanConfig> scanConfig = ThreadLocal.withInitial(ScanConfig::new);

    /**
     * Scanner configuration options
     */
    public static class ScanConfig {
        private String projectName = "Accessibility Test Project";
        private boolean checkColorContrast = false; // Disabled by default for page display testing
        private boolean checkKeyboardNavigation = true;
        private boolean checkMenuNavigation = true;
        private boolean includeScreenshots = true;

        public String getProjectName() { return projectName; }
        public void setProjectName(String projectName) { this.projectName = projectName; }
        public boolean isCheckColorContrast() { return checkColorContrast; }
        public void setCheckColorContrast(boolean checkColorContrast) { this.checkColorContrast = checkColorContrast; }
        public boolean isCheckKeyboardNavigation() { return checkKeyboardNavigation; }
        public void setCheckKeyboardNavigation(boolean checkKeyboardNavigation) { this.checkKeyboardNavigation = checkKeyboardNavigation; }
        public boolean isCheckMenuNavigation() { return checkMenuNavigation; }
        public void setCheckMenuNavigation(boolean checkMenuNavigation) { this.checkMenuNavigation = checkMenuNavigation; }
        public boolean isIncludeScreenshots() { return includeScreenshots; }
        public void setIncludeScreenshots(boolean includeScreenshots) { this.includeScreenshots = includeScreenshots; }
    }

    /**
     * Single page accessibility test result
     */
    public static class PageResult {
        private String pageName;
        private String pageUrl;
        private String pageTitle;
        private LocalDateTime testTime;
        private int totalIssues;
        private int elementsScanned;
        private int totalChecks; // Total WCAG checks performed
        private int passedChecks; // WCAG checks that passed
        private String firstFocusElement; // First element focused by Tab navigation
        private String startElement; // Element from which test started (if specified)
        private String endElement; // Element at which test stopped (if specified)
        private List<String> tabNavigationOrder; // Elements visited by Tab navigation
        private List<String> shiftTabNavigationOrder; // Elements visited by Shift+Tab navigation
        private Map<AccessibilityEngine.IssueSeverity, Integer> issueCounts;
        private List<AccessibilityEngine.AccessibilityIssue> issues;
        private boolean passed;

        public PageResult(String pageName, String pageUrl, String pageTitle) {
            this.pageName = pageName;
            this.pageUrl = pageUrl;
            this.pageTitle = pageTitle;
            this.testTime = LocalDateTime.now();
            this.issueCounts = new EnumMap<>(AccessibilityEngine.IssueSeverity.class);
            this.issues = new ArrayList<>();
            this.tabNavigationOrder = new ArrayList<>();
            this.shiftTabNavigationOrder = new ArrayList<>();
        }

        // Getters and Setters
        public String getPageName() { return pageName; }
        public String getPageUrl() { return pageUrl; }
        public String getPageTitle() { return pageTitle; }
        public LocalDateTime getTestTime() { return testTime; }
        public int getTotalIssues() { return totalIssues; }
        public void setTotalIssues(int totalIssues) { this.totalIssues = totalIssues; }
        public int getElementsScanned() { return elementsScanned; }
        public void setElementsScanned(int elementsScanned) { this.elementsScanned = elementsScanned; }
        public int getTotalChecks() { return totalChecks; }
        public void setTotalChecks(int totalChecks) { this.totalChecks = totalChecks; }
        public int getPassedChecks() { return passedChecks; }
        public void setPassedChecks(int passedChecks) { this.passedChecks = passedChecks; }
        public String getFirstFocusElement() { return firstFocusElement; }
        public void setFirstFocusElement(String firstFocusElement) { this.firstFocusElement = firstFocusElement; }
        public String getStartElement() { return startElement; }
        public void setStartElement(String startElement) { this.startElement = startElement; }
        public String getEndElement() { return endElement; }
        public void setEndElement(String endElement) { this.endElement = endElement; }
        public List<String> getTabNavigationOrder() { return tabNavigationOrder; }
        public void setTabNavigationOrder(List<String> tabNavigationOrder) { this.tabNavigationOrder = tabNavigationOrder; }
        public List<String> getShiftTabNavigationOrder() { return shiftTabNavigationOrder; }
        public void setShiftTabNavigationOrder(List<String> shiftTabNavigationOrder) { this.shiftTabNavigationOrder = shiftTabNavigationOrder; }
        public Map<AccessibilityEngine.IssueSeverity, Integer> getIssueCounts() { return issueCounts; }
        public List<AccessibilityEngine.AccessibilityIssue> getIssues() { return issues; }
        public boolean isPassed() { return passed; }
        public void setPassed(boolean passed) { this.passed = passed; }

        public void addIssue(AccessibilityEngine.AccessibilityIssue issue) {
            issues.add(issue);
            issueCounts.put(issue.getSeverity(), issueCounts.getOrDefault(issue.getSeverity(), 0) + 1);
            totalIssues = issues.size();
        }

        /**
         * Get WCAG compliance rate (passed checks / total checks)
         */
        public double getWcagComplianceRate() {
            if (totalChecks <= 0) return 100.0;
            return (passedChecks * 100.0) / totalChecks;
        }
    }

    private static volatile boolean initialized = false;

    /**
     * Check if scanner has been initialized
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Initialize collector (call before test starts)
     * Safe to call multiple times - will only initialize once
     */
    public static void initialize() {
        if (initialized) {
            logger.info("AccessibilityScanner already initialized, skipping");
            return;
        }
        results.set(new ArrayList<>());
        generatedReports.set(new ArrayList<>());
        testStartTime.set(LocalDateTime.now());
        initialized = true;
        logger.info("AccessibilityScanner initialized");
    }

    /**
     * Initialize collector with configuration (call before test starts)
     * Safe to call multiple times - will only initialize once
     *
     * @param config Scanner configuration
     */
    public static void initialize(ScanConfig config) {
        if (initialized) {
            logger.info("AccessibilityScanner already initialized, skipping (project: {})", config.getProjectName());
            // Still update config even if already initialized
            scanConfig.set(config);
            return;
        }
        results.set(new ArrayList<>());
        generatedReports.set(new ArrayList<>());
        testStartTime.set(LocalDateTime.now());
        scanConfig.set(config);
        initialized = true;
        logger.info("AccessibilityScanner initialized with project: {}", config.getProjectName());
    }

    /**
     * Set scanner configuration
     */
    public static void setConfig(ScanConfig config) {
        scanConfig.set(config);
    }

    /**
     * Get current scanner configuration
     */
    public static ScanConfig getConfig() {
        return scanConfig.get();
    }

    /**
     * Check page and collect results (Simplified API)
     * User only needs to provide descriptive page name, Page object is automatically retrieved from PlaywrightManager
     *
     * @param pageName Page name, e.g.: "Login Page", "Home Page", "Order Input Page"
     * @return Test result
     */
    public static PageResult checkAndCollect(String pageName) {
        try {
            logger.info("Collecting accessibility results for: {}", pageName);

            // Retrieve Page object from PlaywrightManager
            Page page = PlaywrightManager.getPage();
            
            if (page == null || page.isClosed()) {
                logger.error("Page is null or closed for accessibility check: {}", pageName);
                throw new IllegalStateException("Page is not available from PlaywrightManager");
            }

            // Get config
            ScanConfig config = scanConfig.get();
            boolean includeScreenshots = config.isIncludeScreenshots();

            // Execute accessibility check
            List<AccessibilityEngine.AccessibilityIssue> issues =
                AccessibilityEngine.checkPageAccessibilityEnhanced(page, includeScreenshots);

            // Filter issues based on config
            issues = filterIssuesByConfig(issues, config);

            // Create result object
            PageResult result = new PageResult(
                pageName,
                page.url(),
                page.title()
            );

            // Add all issues
            for (AccessibilityEngine.AccessibilityIssue issue : issues) {
                result.addIssue(issue);
            }

            // Count scanned elements
            int elementsScanned = countPageElements(page);
            result.setElementsScanned(elementsScanned);

            // Get Tab navigation order
            try {
                AccessibilityEngine.TabNavigationResult tabResult =
                    AccessibilityEngine.getTabNavigationResult(page, config.isIncludeScreenshots());
                result.setTabNavigationOrder(tabResult.getTabOrder());
                result.setShiftTabNavigationOrder(tabResult.getShiftTabOrder());
            } catch (Exception e) {
                logger.warn("Failed to get Tab navigation order: {}", e.getMessage());
            }

            // Get first focus element
            String firstFocusElement = getFirstFocusElement(page);
            result.setFirstFocusElement(firstFocusElement);

            // Calculate statistics
            AccessibilityEngine.TestStatistics stats =
                AccessibilityEngine.calculateStatistics(issues, 1);

            result.setTotalIssues(issues.size());
            result.setTotalChecks(stats.getTotalChecks());
            result.setPassedChecks(stats.getPassedChecks());

            // Determine if passed (no critical and high priority issues)
            long highPriorityCount = issues.stream()
                .filter(i -> i.getSeverity() == AccessibilityEngine.IssueSeverity.CRITICAL ||
                            i.getSeverity() == AccessibilityEngine.IssueSeverity.HIGH)
                .count();

            result.setPassed(highPriorityCount == 0);

            // Add to collector
            results.get().add(result);

            // Generate individual HTML report for each page
            String reportFileName = generateIndividualPageReport(result);
            generatedReports.get().add(reportFileName);

            logger.info("Accessibility check completed for: {}, issues: {}, elements: {}, passed: {}, report: {}",
                pageName, issues.size(), elementsScanned, result.isPassed(), reportFileName);

            return result;

        } catch (Exception e) {
            logger.error("Error collecting accessibility results for: {}", pageName, e);
            throw new RuntimeException("Failed to collect accessibility results: " + pageName, e);
        }
    }

    /**
     * Check page and collect results starting from a specific element
     * User can specify a target element to start the accessibility check
     *
     * @param pageName Page name, e.g.: "Login Page", "Home Page"
     * @param elementSelector CSS selector of the element to start from (user clicked/focused element)
     * @return Test result
     */
    public static PageResult checkAndCollectFromElement(String pageName, String elementSelector) {
        try {
            logger.info("Collecting accessibility results for: {} starting from element: {}", pageName, elementSelector);

            // Retrieve Page object from PlaywrightManager
            Page page = PlaywrightManager.getPage();
            
            if (page == null || page.isClosed()) {
                logger.error("Page is null or closed for accessibility check: {}", pageName);
                throw new IllegalStateException("Page is not available from PlaywrightManager");
            }

            // Get config
            ScanConfig config = scanConfig.get();
            boolean includeScreenshots = config.isIncludeScreenshots();

            // Verify element exists
            Locator startElement = page.locator(elementSelector);
            if (startElement.count() == 0) {
                logger.warn("Start element not found: {}, using full page check", elementSelector);
                return checkAndCollect(pageName);
            }

            // Execute accessibility check starting from element
            List<AccessibilityEngine.AccessibilityIssue> issues =
                AccessibilityEngine.checkPageAccessibilityFromElement(page, elementSelector, includeScreenshots);

            // Filter issues based on config
            issues = filterIssuesByConfig(issues, config);

            // Create result object
            PageResult result = new PageResult(
                pageName,
                page.url(),
                page.title()
            );
            result.setStartElement(elementSelector);

            // Add all issues
            for (AccessibilityEngine.AccessibilityIssue issue : issues) {
                result.addIssue(issue);
            }

            // Count scanned elements
            int elementsScanned = countPageElements(page);
            result.setElementsScanned(elementsScanned);

            // Get Tab navigation order
            try {
                AccessibilityEngine.TabNavigationResult tabResult =
                    AccessibilityEngine.getTabNavigationResult(page, config.isIncludeScreenshots());
                result.setTabNavigationOrder(tabResult.getTabOrder());
                result.setShiftTabNavigationOrder(tabResult.getShiftTabOrder());
            } catch (Exception e) {
                logger.warn("Failed to get Tab navigation order: {}", e.getMessage());
            }

            // Get first focus element
            String firstFocusElement = getFirstFocusElement(page);
            result.setFirstFocusElement(firstFocusElement);

            // Calculate statistics
            AccessibilityEngine.TestStatistics stats =
                AccessibilityEngine.calculateStatistics(issues, 1);

            result.setTotalIssues(issues.size());
            result.setTotalChecks(stats.getTotalChecks());
            result.setPassedChecks(stats.getPassedChecks());

            // Determine if passed (no critical and high priority issues)
            long highPriorityCount = issues.stream()
                .filter(i -> i.getSeverity() == AccessibilityEngine.IssueSeverity.CRITICAL ||
                            i.getSeverity() == AccessibilityEngine.IssueSeverity.HIGH)
                .count();

            result.setPassed(highPriorityCount == 0);

            // Add to collector
            results.get().add(result);

            // Generate individual HTML report for each page
            String reportFileName = generateIndividualPageReport(result);
            generatedReports.get().add(reportFileName);

            logger.info("Accessibility check completed for: {}, issues: {}, first focus: {}, start element: {}, report: {}",
                pageName, issues.size(), firstFocusElement, elementSelector, reportFileName);

            return result;

        } catch (Exception e) {
            logger.error("Error collecting accessibility results for: {} from element: {}", pageName, elementSelector, e);
            throw new RuntimeException("Failed to collect accessibility results: " + pageName, e);
        }
    }

    /**
     * Check page and collect results between two elements
     * User can specify start and end elements for testing a specific range
     *
     * @param pageName Page name, e.g.: "Login Page", "Home Page"
     * @param startElementSelector CSS selector of the element to start from
     * @param endElementSelector CSS selector of the element to stop at
     * @return Test result
     */
    public static PageResult checkAndCollectBetween(String pageName, String startElementSelector, String endElementSelector) {
        try {
            logger.info("Collecting accessibility results for: {} from element: {} to element: {}", pageName, startElementSelector, endElementSelector);

            // Retrieve Page object from PlaywrightManager
            Page page = PlaywrightManager.getPage();
            
            if (page == null || page.isClosed()) {
                logger.error("Page is null or closed for accessibility check: {}", pageName);
                throw new IllegalStateException("Page is not available from PlaywrightManager");
            }

            // Get config
            ScanConfig config = scanConfig.get();
            boolean includeScreenshots = config.isIncludeScreenshots();

            // Verify start element exists
            Locator startLocator = page.locator(startElementSelector);
            if (startLocator.count() == 0) {
                logger.warn("Start element not found: {}, using first focusable element", startElementSelector);
                startElementSelector = getFirstFocusElement(page);
            }

            // Verify end element exists
            Locator endLocator = page.locator(endElementSelector);
            if (endLocator.count() == 0) {
                logger.warn("End element not found: {}, will stop at browser navigation bar", endElementSelector);
                endElementSelector = null;
            }

            // Execute accessibility check between elements
            List<AccessibilityEngine.AccessibilityIssue> issues =
                AccessibilityEngine.checkPageAccessibilityBetween(page, startElementSelector, endElementSelector, includeScreenshots);

            // Filter issues based on config
            issues = filterIssuesByConfig(issues, config);

            // Create result object
            PageResult result = new PageResult(
                pageName,
                page.url(),
                page.title()
            );
            result.setStartElement(startElementSelector);
            result.setEndElement(endElementSelector);

            // Add all issues
            for (AccessibilityEngine.AccessibilityIssue issue : issues) {
                result.addIssue(issue);
            }

            // Count scanned elements
            int elementsScanned = countPageElements(page);
            result.setElementsScanned(elementsScanned);

            // Get Tab navigation order
            try {
                AccessibilityEngine.TabNavigationResult tabResult =
                    AccessibilityEngine.getTabNavigationResult(page, config.isIncludeScreenshots());
                result.setTabNavigationOrder(tabResult.getTabOrder());
                result.setShiftTabNavigationOrder(tabResult.getShiftTabOrder());
            } catch (Exception e) {
                logger.warn("Failed to get Tab navigation order: {}", e.getMessage());
            }

            // Get first focus element
            String firstFocusElement = getFirstFocusElement(page);
            result.setFirstFocusElement(firstFocusElement);

            // Calculate statistics
            AccessibilityEngine.TestStatistics stats =
                AccessibilityEngine.calculateStatistics(issues, 1);

            result.setTotalIssues(issues.size());
            result.setTotalChecks(stats.getTotalChecks());
            result.setPassedChecks(stats.getPassedChecks());

            // Determine if passed (no critical and high priority issues)
            long highPriorityCount = issues.stream()
                .filter(i -> i.getSeverity() == AccessibilityEngine.IssueSeverity.CRITICAL ||
                            i.getSeverity() == AccessibilityEngine.IssueSeverity.HIGH)
                .count();

            result.setPassed(highPriorityCount == 0);

            // Add to collector
            results.get().add(result);

            // Generate individual HTML report for each page
            String reportFileName = generateIndividualPageReport(result);
            generatedReports.get().add(reportFileName);

            logger.info("Accessibility check completed for: {}, issues: {}, start: {}, end: {}, report: {}",
                pageName, issues.size(), startElementSelector, endElementSelector, reportFileName);

            return result;

        } catch (Exception e) {
            logger.error("Error collecting accessibility results for: {} between elements", pageName, e);
            throw new RuntimeException("Failed to collect accessibility results: " + pageName, e);
        }
    }

    /**
     * Get the first focusable element on the page (first Tab stop)
     */
    private static String getFirstFocusElement(Page page) {
        try {
            Object result = page.evaluate("() => { " +
                "const focusableSelectors = 'a[href], button:not([disabled]), input:not([disabled]), " +
                "select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex=\"-1\"])'; " +
                "const focusableElements = document.querySelectorAll(focusableSelectors); " +
                "for (const el of focusableElements) { " +
                "  const styles = window.getComputedStyle(el); " +
                "  const rect = el.getBoundingClientRect(); " +
                "  if (styles.display !== 'none' && styles.visibility !== 'hidden' && rect.width > 0 && rect.height > 0) { " +
                "    return el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + (el.className ? '.' + el.className.split(' ').join('.') : ''); " +
                "  } " +
                "} " +
                "return null; " +
                "}");
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            logger.debug("Could not get first focus element: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Filter issues based on scan configuration
     */
    private static List<AccessibilityEngine.AccessibilityIssue> filterIssuesByConfig(
            List<AccessibilityEngine.AccessibilityIssue> issues, ScanConfig config) {
        
        return issues.stream().filter(issue -> {
            String desc = issue.getDescription().toLowerCase();
            String wcag = issue.getWcagCriteria() != null ? issue.getWcagCriteria().toLowerCase() : "";

            // Filter color contrast issues
            if (!config.isCheckColorContrast()) {
                if (desc.contains("contrast") || desc.contains("color") || wcag.contains("1.4.3")) {
                    return false;
                }
            }

            // Filter keyboard navigation issues
            if (!config.isCheckKeyboardNavigation()) {
                if (desc.contains("keyboard") || desc.contains("focus") || desc.contains("tab") ||
                    wcag.contains("2.1.1") || wcag.contains("2.1.2") || wcag.contains("2.4.3")) {
                    return false;
                }
            }

            // Filter menu navigation issues
            if (!config.isCheckMenuNavigation()) {
                if (desc.contains("menu") && (desc.contains("keyboard") || desc.contains("arrow") || desc.contains("expand"))) {
                    return false;
                }
            }

            return true;
        }).collect(java.util.stream.Collectors.toList());
    }

    /**
     * Count page elements for statistics
     */
    private static int countPageElements(Page page) {
        try {
            Object count = page.evaluate("() => {" +
                "const elements = document.querySelectorAll('img, input, select, textarea, button, a, h1, h2, h3, h4, h5, h6, [role], [aria-label], [tabindex]');" +
                "return elements.length;" +
                "}");
            return count != null ? ((Number) count).intValue() : 0;
        } catch (Exception e) {
            logger.warn("Failed to count page elements", e);
            return 0;
        }
    }

    /**
     * Generate individual HTML report for a single page
     *
     * @param result Page test result
     * @return Generated report file name (relative path for use in aggregated report)
     */
    private static String generateIndividualPageReport(PageResult result) {
        try {
            // Generate hash value for file name
            String hashInput = result.getPageName() + "_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId();
            String hash = generateHash(hashInput);
            String fileName = String.format("%s.html", hash);
            String reportPath = "target/accessibility/" + fileName;

            // Generate HTML report content
            String htmlReport = generatePageHtmlReport(result);

            // Save report
            saveReport(htmlReport, reportPath);

            logger.info("Individual page report generated: {}", reportPath);
            // Return only file name for relative link in aggregated report
            return fileName;

        } catch (Exception e) {
            logger.error("Failed to generate individual page report for: {}", result.getPageName(), e);
            throw new RuntimeException("Failed to generate individual page report: " + result.getPageName(), e);
        }
    }

    /**
     * Generate SHA-256 hash value for creating unique file name
     *
     * @param input Input string
     * @return SHA-256 hash value in hexadecimal format
     */
    private static String generateHash(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            StringBuilder hexString = new StringBuilder();

            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (Exception e) {
            logger.warn("Failed to generate hash, using fallback method", e);
            return Long.toHexString(System.currentTimeMillis()) +
                    Long.toHexString(System.nanoTime()) +
                    Long.toHexString(Thread.currentThread().getId());
        }
    }

    /**
     * Get all collected results
     */
    public static List<PageResult> getCollectedResults() {
        return results.get();
    }

    /**
     * Generate final aggregated report (call at end of test)
     * Summarizes accessibility test results for all pages and generates a comprehensive report
     */
    public static void generateFinalReport() {
        try {
            List<PageResult> allResults = results.get();
            List<String> individualReports = generatedReports.get();

            if (allResults.isEmpty()) {
                logger.warn("No accessibility results collected");
                return;
            }

            logger.info("Generating final aggregated accessibility report with {} page results", allResults.size());

            // Calculate overall statistics
            int totalIssues = allResults.stream().mapToInt(PageResult::getTotalIssues).sum();
            int passedTests = (int) allResults.stream().filter(PageResult::isPassed).count();

            // Merge all issues
            Map<AccessibilityEngine.IssueSeverity, Integer> overallIssueCounts = new EnumMap<>(AccessibilityEngine.IssueSeverity.class);
            List<AccessibilityEngine.AccessibilityIssue> allIssues = new ArrayList<>();

            for (PageResult result : allResults) {
                allIssues.addAll(result.getIssues());
                for (Map.Entry<AccessibilityEngine.IssueSeverity, Integer> entry : result.getIssueCounts().entrySet()) {
                    overallIssueCounts.put(entry.getKey(),
                        overallIssueCounts.getOrDefault(entry.getKey(), 0) + entry.getValue());
                }
            }

            // Calculate Page Pass Rate
            double pagePassRate = allResults.isEmpty() ? 0.0 :
                (passedTests * 100.0) / allResults.size();

            // Generate aggregated HTML report
            String htmlReport = generateFinalHtmlReport(allResults, totalIssues,
                passedTests, pagePassRate, overallIssueCounts, allIssues, individualReports);

            // Generate file name for aggregated report
            String reportPath = "target/accessibility/accessibility-summary.html";
            saveReport(htmlReport, reportPath);

            logger.info("Final aggregated accessibility report generated: {}", reportPath);
            logger.info("Summary: {} pages, {} issues, {} passed, page pass rate {}%",
                allResults.size(), totalIssues, passedTests, String.format("%.1f", pagePassRate));
            logger.info("Individual reports: {}", String.join(", ", individualReports));

        } catch (Exception e) {
            logger.error("Failed to generate final accessibility report", e);
        }
    }

    /**
     * Generate single page HTML report
     */
    private static String generatePageHtmlReport(PageResult result) {
        StringBuilder html = new StringBuilder();

        // HTML header
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"en\">\n");
        html.append("<head>\n");
        html.append("    <meta charset=\"UTF-8\" />\n");
        html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n");
        html.append("    <title>").append(result.getPageName()).append(" - Accessibility Test Report</title>\n");
        html.append("    <style>\n");
        html.append(getReportStyles());
        html.append("    </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("    <div class=\"report\">\n");

        // Title
        html.append("        <h1 class=\"title\">").append(result.getPageName()).append(" - Accessibility Test Report</h1>\n");
        html.append("        <p class=\"subtitle\">WCAG 2.2 AA Standard</p>\n");
        html.append("        <p class=\"subtitle\">Page URL: ").append(result.getPageUrl()).append("</p>\n");
        html.append("        <p class=\"subtitle\">Test Time: ")
              .append(result.getTestTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
              .append("</p>\n");

        // First focus element info
        if (result.getFirstFocusElement() != null && !result.getFirstFocusElement().isEmpty()) {
            html.append("        <p class=\"subtitle\" style=\"color: #3498db;\">🎯 First Tab Focus: <code>")
                  .append(escapeHtml(result.getFirstFocusElement())).append("</code></p>\n");
        }

        // Start element info (if test started from specific element)
        if (result.getStartElement() != null && !result.getStartElement().isEmpty()) {
            html.append("        <p class=\"subtitle\" style=\"color: #e67e22;\">📍 Test Started From: <code>")
                  .append(escapeHtml(result.getStartElement())).append("</code></p>\n");
        }

        // End element info (if test ended at specific element)
        if (result.getEndElement() != null && !result.getEndElement().isEmpty()) {
            html.append("        <p class=\"subtitle\" style=\"color: #9b59b6;\">🏁 Test Ended At: <code>")
                  .append(escapeHtml(result.getEndElement())).append("</code></p>\n");
        }

        // Test result - card style layout (same as Issue Severity Statistics)
        html.append("        <div class=\"section\">\n");
        html.append("            <h2>📊 Test Result</h2>\n");
        
        // Status card - more prominent display
        html.append("            <div style=\"text-align: center; margin-bottom: 25px;\">\n");
        if (result.isPassed()) {
            html.append("                <div style=\"display: inline-block; padding: 20px 50px; border-radius: 15px; font-size: 24px; font-weight: bold; background: linear-gradient(135deg, #d4edda 0%, #c3e6cb 100%); color: #155724; border: 3px solid #28a745; box-shadow: 0 4px 15px rgba(40, 167, 69, 0.3);\">\n");
            html.append("                    Test Passed\n");
            html.append("                </div>\n");
        } else {
            html.append("                <div style=\"display: inline-block; padding: 20px 50px; border-radius: 15px; font-size: 24px; font-weight: bold; background: linear-gradient(135deg, #f8d7da 0%, #f5c6cb 100%); color: #721c24; border: 3px solid #dc3545; box-shadow: 0 4px 15px rgba(220, 53, 69, 0.3);\">\n");
            html.append("                    Test Failed\n");
            html.append("                </div>\n");
        }
        html.append("            </div>\n");

        // Stats cards
        html.append("            <div class=\"stats\">\n");

        // Issues Found
        html.append("                <div class=\"stat-item\">\n");
        html.append("                    Issues Found<div class=\"num\" style=\"color: ")
              .append(result.getTotalIssues() > 0 ? "#dc3545" : "#28a745").append(";\">")
              .append(result.getTotalIssues()).append("</div>\n");
        html.append("                </div>\n");

        // WCAG Compliance Rate with formula
        double wcagRate = result.getWcagComplianceRate();
        String wcagRateColor = wcagRate >= 80 ? "#28a745" :
                               wcagRate >= 60 ? "#f9c74f" : "#dc3545";
        html.append("                <div class=\"stat-item\" title=\"Formula: (Passed Checks / Total Checks) × 100%\">\n");
        html.append("                    WCAG Compliance<div class=\"num\" style=\"color: ")
              .append(wcagRateColor).append(";\">")
              .append(String.format("%.1f%%", wcagRate)).append("</div>\n");
        html.append("                    <small style=\"color: #666; font-size: 11px;\">")
              .append(result.getPassedChecks()).append("/").append(result.getTotalChecks()).append(" checks passed</small>\n");
        html.append("                </div>\n");
        
        // Element statistics (if available)
        if (result.getElementsScanned() > 0) {
            int elementsWithIssues = Math.min(result.getTotalIssues(), result.getElementsScanned());
            int elementsWithoutIssues = result.getElementsScanned() - elementsWithIssues;

            // Elements Scanned
            html.append("                <div class=\"stat-item\">\n");
            html.append("                    Elements Scanned<div class=\"num\">")
                  .append(result.getElementsScanned()).append("</div>\n");
            html.append("                </div>\n");

            // Elements with Issues
            html.append("                <div class=\"stat-item\">\n");
            html.append("                    Elements with Issues<div class=\"num\" style=\"color: #dc3545;\">")
                  .append(elementsWithIssues).append("</div>\n");
            html.append("                </div>\n");

            // Elements Passed
            html.append("                <div class=\"stat-item\">\n");
            html.append("                    Elements Passed<div class=\"num\" style=\"color: #28a745;\">")
                  .append(elementsWithoutIssues).append("</div>\n");
            html.append("                </div>\n");
        }

        html.append("            </div>\n");
        html.append("        </div>\n");

        // Issue severity statistics
        html.append("        <div class=\"section\">\n");
        html.append("            <h2>⚠️ Issue Severity Statistics</h2>\n");
        html.append("            <div class=\"stats\">\n");

        for (AccessibilityEngine.IssueSeverity severity : new AccessibilityEngine.IssueSeverity[]{
                AccessibilityEngine.IssueSeverity.CRITICAL,
                AccessibilityEngine.IssueSeverity.HIGH,
                AccessibilityEngine.IssueSeverity.MEDIUM,
                AccessibilityEngine.IssueSeverity.LOW,
                AccessibilityEngine.IssueSeverity.INFO}) {
            int count = result.getIssueCounts().getOrDefault(severity, 0);
            html.append("                <div class=\"stat-item\">\n");
            html.append("                    ").append(severity.getDisplayName());
            html.append("                    <div class=\"num\" style=\"color: ")
                  .append(severity.getColor()).append(";\">").append(count).append("</div>\n");
            html.append("                </div>\n");
        }

        html.append("            </div>\n");
        html.append("        </div>\n");

        // Issue Type Statistics
        if (!result.getIssues().isEmpty()) {
            html.append("        <div class=\"section\">\n");
            html.append("            <h2>📋 Issue Type Distribution</h2>\n");
            html.append("            <div class=\"stats\">\n");

            Map<String, Integer> typeCounts = categorizeIssuesByType(result.getIssues());
            for (Map.Entry<String, Integer> entry : typeCounts.entrySet()) {
                String typeColor = getIssueTypeColor(entry.getKey());
                html.append("                <div class=\"issue-type-item\">\n");
                html.append("                    ").append(entry.getKey());
                html.append("                    <div class=\"num\" style=\"color: ")
                      .append(typeColor).append(";\">").append(entry.getValue()).append("</div>\n");
                html.append("                </div>\n");
            }

            html.append("            </div>\n");
            html.append("        </div>\n");
        }

        // Tab Navigation Order - Comparison Table
        if (!result.getTabNavigationOrder().isEmpty()) {
            html.append("        <div class=\"section\">\n");
            html.append("            <h2>⌨️ Keyboard Navigation Order</h2>\n");
            html.append("            <p style=\"color: #666; font-size: 13px; margin-bottom: 15px;\">")
                  .append("Note: Tab[i] should match Shift+Tab[i]. Shift+Tab starts from Tab's last element, ")
                  .append("so Shift+Tab[1] is Tab's second-to-last element (first visited by Shift+Tab).</p>\n");
            
            html.append("            <table style=\"width: 100%; border-collapse: collapse; background: #fff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 4px rgba(0,0,0,0.1);\">\n");
            html.append("                <thead>\n");
            html.append("                    <tr style=\"background: #34495e; color: #fff;\">\n");
            html.append("                        <th style=\"padding: 12px 15px; text-align: center; width: 80px;\">Step</th>\n");
            html.append("                        <th style=\"padding: 12px 15px; text-align: left;\">Tab (Forward)</th>\n");
            html.append("                        <th style=\"padding: 12px 15px; text-align: left;\">Shift+Tab (Expected)</th>\n");
            html.append("                        <th style=\"padding: 12px 15px; text-align: center; width: 100px;\">Match</th>\n");
            html.append("                    </tr>\n");
            html.append("                </thead>\n");
            html.append("                <tbody>\n");
            
            List<String> tabOrder = result.getTabNavigationOrder();
            List<String> shiftTabOrderRaw = result.getShiftTabNavigationOrder();
            
            // Reverse Shift+Tab order for display so Tab[i] aligns with Shift+Tab[i]
            // Original Shift+Tab visit order: [D, C, B, A] (from Tab's last element)
            // Reversed for comparison: [A, B, C, D] (matches Tab order)
            List<String> shiftTabOrder = new ArrayList<>(shiftTabOrderRaw);
            Collections.reverse(shiftTabOrder);
            
            int tabSize = tabOrder.size();
            int shiftTabSize = shiftTabOrder.size();
            int maxRows = Math.max(tabSize, shiftTabSize);
            
            for (int i = 0; i < maxRows; i++) {
                String bgColor = i % 2 == 0 ? "#f8f9fa" : "#ffffff";
                html.append("                    <tr style=\"background: ").append(bgColor).append(";\">\n");
                
                // Step number
                html.append("                        <td style=\"padding: 10px 15px; text-align: center; font-weight: bold; color: #2c3e50;\">")
                      .append(i + 1).append("</td>\n");
                
                // Tab element (forward order)
                html.append("                        <td style=\"padding: 10px 15px;\">\n");
                if (i < tabSize) {
                    html.append("                            <code style=\"background: #e8f4f8; padding: 4px 8px; border-radius: 4px; font-size: 13px;\">")
                          .append(escapeHtml(tabOrder.get(i))).append("</code>\n");
                } else {
                    html.append("                            <span style=\"color: #999;\">-</span>\n");
                }
                html.append("                        </td>\n");
                
                // Shift+Tab element (reversed for comparison)
                html.append("                        <td style=\"padding: 10px 15px;\">\n");
                if (i < shiftTabSize) {
                    html.append("                            <code style=\"background: #fef3e2; padding: 4px 8px; border-radius: 4px; font-size: 13px;\">")
                          .append(escapeHtml(shiftTabOrder.get(i))).append("</code>\n");
                } else {
                    html.append("                            <span style=\"color: #999;\">-</span>\n");
                }
                html.append("                        </td>\n");
                
                // Match indicator - compare Tab[i] with Shift+Tab[i]
                html.append("                        <td style=\"padding: 10px 15px; text-align: center;\">\n");
                if (i < tabSize && i < shiftTabSize) {
                    boolean match = tabOrder.get(i).equals(shiftTabOrder.get(i));
                    if (match) {
                        html.append("                            <span style=\"color: #28a745; font-weight: bold;\">✓</span>\n");
                    } else {
                        html.append("                            <span style=\"color: #dc3545; font-weight: bold;\">✗</span>\n");
                    }
                } else {
                    html.append("                            <span style=\"color: #999;\">-</span>\n");
                }
                html.append("                        </td>\n");
                
                html.append("                    </tr>\n");
            }
            
            html.append("                </tbody>\n");
            html.append("            </table>\n");
            html.append("            <p style=\"margin-top: 10px; color: #666; font-size: 13px;\">")
                  .append("Tab: ").append(tabSize).append(" elements | Shift+Tab: ")
                  .append(shiftTabOrderRaw.size()).append(" elements</p>\n");
            html.append("        </div>\n");
        }

        // Issue list
        html.append("        <div class=\"section\">\n");
        html.append("            <h2>🔍 Issue Details</h2>\n");

        if (result.getIssues().isEmpty()) {
            html.append("            <div style=\"padding: 20px; background: #e8f5e9; border-left: 4px solid #4caf50; border-radius: 8px;\">\n");
            html.append("                <h3 style=\"color: #2e7d32; margin: 0;\">✅ No Accessibility Issues Found</h3>\n");
            html.append("                <p style=\"margin: 10px 0 0; color: #388e3c;\">This page fully complies with WCAG 2.2 AA standards</p>\n");
            html.append("            </div>\n");
        } else {
            for (AccessibilityEngine.AccessibilityIssue issue : result.getIssues()) {
                html.append(generateIssueDetail(issue));
            }
        }

        html.append("        </div>\n");

        html.append("    </div>\n");
        html.append("    <script>\n");
        html.append("        function toggle(el) {\n");
        html.append("            el.nextElementSibling.classList.toggle('show');\n");
        html.append("        }\n");
        html.append("    </script>\n");
        html.append("</body>\n");
        html.append("</html>\n");

        return html.toString();
    }

    /**
     * Generate final aggregated HTML report
     */
    private static String generateFinalHtmlReport(
            List<PageResult> allResults,
            int totalIssues,
            int passedTests,
            double pagePassRate,
            Map<AccessibilityEngine.IssueSeverity, Integer> overallIssueCounts,
            List<AccessibilityEngine.AccessibilityIssue> allIssues,
            List<String> individualReports) {

        StringBuilder html = new StringBuilder();
        String projectName = scanConfig.get().getProjectName();

        // HTML header
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"en\">\n");
        html.append("<head>\n");
        html.append("    <meta charset=\"UTF-8\" />\n");
        html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n");
        html.append("    <title>").append(escapeHtml(projectName)).append(" - Accessibility Report</title>\n");
        html.append("    <style>\n");
        html.append(getReportStyles());
        html.append("    </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("    <div class=\"report\">\n");

        // Title with project name
        html.append("        <h1 class=\"title\">").append(escapeHtml(projectName)).append("</h1>\n");
        html.append("        <h2 class=\"subtitle\" style=\"font-size: 20px; color: #2c3e50; margin-bottom: 10px;\">Accessibility Test Report</h2>\n");
        html.append("        <p class=\"subtitle\">WCAG 2.2 AA Standard</p>\n");
        html.append("        <p class=\"subtitle\">Generated Time: ")
              .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
              .append("</p>\n");

        // Overall statistics
        int totalElementsScanned = allResults.stream().mapToInt(PageResult::getElementsScanned).sum();
        int totalElementsWithIssues = Math.min(totalIssues, totalElementsScanned);
        int totalElementsPassed = totalElementsScanned - totalElementsWithIssues;

        // Calculate overall WCAG compliance rate
        int totalChecks = allResults.stream().mapToInt(PageResult::getTotalChecks).sum();
        int totalPassedChecks = allResults.stream().mapToInt(PageResult::getPassedChecks).sum();
        double wcagComplianceRate = totalChecks > 0 ? (totalPassedChecks * 100.0 / totalChecks) : 100.0;

        html.append("        <div class=\"section\">\n");
        html.append("            <h2>📊 Overall Statistics</h2>\n");
        html.append("            <div class=\"stats\">\n");
        html.append("                <div class=\"stat-item\">\n");
        html.append("                    Pages Checked <div class=\"num\">").append(allResults.size()).append("</div>\n");
        html.append("                </div>\n");
        html.append("                <div class=\"stat-item\">\n");
        html.append("                    Pages Passed <div class=\"num pass\">").append(passedTests).append("</div>\n");
        html.append("                </div>\n");
        html.append("                <div class=\"stat-item\">\n");
        html.append("                    Total Issues <div class=\"num\">").append(totalIssues).append("</div>\n");
        html.append("                </div>\n");
        html.append("                <div class=\"stat-item\" title=\"Formula: (Pages Passed / Total Pages) × 100%\">\n");
        html.append("                    Page Pass Rate <div class=\"num\" style=\"color: ")
              .append(pagePassRate >= 80 ? "#2dc653" : pagePassRate >= 60 ? "#f9c74f" : "#e63946")
              .append(";\">").append(String.format("%.1f%%", pagePassRate)).append("</div>\n");
        html.append("                    <small style=\"color: #666; font-size: 11px;\">")
              .append(passedTests).append("/").append(allResults.size()).append(" pages passed</small>\n");
        html.append("                </div>\n");
        html.append("            </div>\n");
        // WCAG Compliance and Element statistics row
        html.append("            <div class=\"stats\" style=\"margin-top: 15px;\">\n");
        html.append("                <div class=\"stat-item\" title=\"Formula: (Passed Checks / Total Checks) × 100%\">\n");
        html.append("                    WCAG Compliance <div class=\"num\" style=\"color: ")
              .append(wcagComplianceRate >= 80 ? "#28a745" : wcagComplianceRate >= 60 ? "#f9c74f" : "#dc3545")
              .append(";\">").append(String.format("%.1f%%", wcagComplianceRate)).append("</div>\n");
        html.append("                    <small style=\"color: #666; font-size: 11px;\">")
              .append(totalPassedChecks).append("/").append(totalChecks).append(" checks passed</small>\n");
        html.append("                </div>\n");
        if (totalElementsScanned > 0) {
            html.append("                <div class=\"stat-item\">\n");
            html.append("                    Elements Scanned <div class=\"num\">").append(totalElementsScanned).append("</div>\n");
            html.append("                </div>\n");
            html.append("                <div class=\"stat-item\">\n");
            html.append("                    Elements with Issues <div class=\"num\" style=\"color: #dc3545;\">").append(totalElementsWithIssues).append("</div>\n");
            html.append("                </div>\n");
            html.append("                <div class=\"stat-item\">\n");
            html.append("                    Elements Passed <div class=\"num\" style=\"color: #28a745;\">").append(totalElementsPassed).append("</div>\n");
            html.append("                </div>\n");
        }
        html.append("            </div>\n");
        html.append("        </div>\n");

        // Issue severity statistics
        html.append("        <div class=\"section\">\n");
        html.append("            <h2>⚠️ Issue Severity Statistics</h2>\n");
        html.append("            <div class=\"stats\">\n");

        for (AccessibilityEngine.IssueSeverity severity : new AccessibilityEngine.IssueSeverity[]{
                AccessibilityEngine.IssueSeverity.CRITICAL,
                AccessibilityEngine.IssueSeverity.HIGH,
                AccessibilityEngine.IssueSeverity.MEDIUM,
                AccessibilityEngine.IssueSeverity.LOW,
                AccessibilityEngine.IssueSeverity.INFO}) {
            int count = overallIssueCounts.getOrDefault(severity, 0);
            html.append("                <div class=\"stat-item\">\n");
            html.append("                    ").append(severity.getDisplayName());
            html.append("                    <div class=\"num\" style=\"color: ")
                  .append(severity.getColor()).append(";\">").append(count).append("</div>\n");
            html.append("                </div>\n");
        }

        html.append("            </div>\n");
        html.append("        </div>\n");

        // Issue Type Distribution
        if (!allIssues.isEmpty()) {
            html.append("        <div class=\"section\">\n");
            html.append("            <h2>📋 Issue Type Distribution</h2>\n");
            html.append("            <div class=\"stats\">\n");

            Map<String, Integer> typeCounts = categorizeIssuesByType(allIssues);
            for (Map.Entry<String, Integer> entry : typeCounts.entrySet()) {
                String typeColor = getIssueTypeColor(entry.getKey());
                html.append("                <div class=\"issue-type-item\">\n");
                html.append("                    ").append(entry.getKey());
                html.append("                    <div class=\"num\" style=\"color: ")
                      .append(typeColor).append(";\">").append(entry.getValue()).append("</div>\n");
                html.append("                </div>\n");
            }

            html.append("            </div>\n");
            html.append("        </div>\n");
        }

        // Page results
        html.append("        <div class=\"section\">\n");
        html.append("            <h2>📋 Page Test Results</h2>\n");

        for (int i = 0; i < allResults.size(); i++) {
            PageResult result = allResults.get(i);
            String reportLink = i < individualReports.size() ? individualReports.get(i) : "";
            html.append(generateTestResultCard(result, reportLink));
        }

        html.append("        </div>\n");

        // All issues list (red highlighting)
        html.append("        <div class=\"section\">\n");
        html.append("            <h2>🔍 All Issues Summary</h2>\n");

        if (allIssues.isEmpty()) {
            html.append("            <div style=\"padding: 20px; background: #e8f5e9; border-left: 4px solid #4caf50; border-radius: 8px;\">\n");
            html.append("                <h3 style=\"color: #2e7d32; margin: 0;\">✅ No Accessibility Issues Found</h3>\n");
            html.append("                <p style=\"margin: 10px 0 0; color: #388e3c;\">All pages fully comply with WCAG 2.2 AA standards</p>\n");
            html.append("            </div>\n");
        } else {
            // Group issues by page
            for (PageResult result : allResults) {
                if (!result.getIssues().isEmpty()) {
                    html.append("            <h3 class=\"page-title\">")
                          .append(result.getPageName()).append("</h3>\n");
                    
                    for (AccessibilityEngine.AccessibilityIssue issue : result.getIssues()) {
                        html.append(generateIssueDetail(issue));
                    }
                }
            }
        }

        html.append("        </div>\n");

        // Conclusion
        html.append("        <div class=\"conclusion\">\n");
        html.append("            <h3>📌 Test Conclusion</h3>\n");
        html.append("            <p>This test checked ")
              .append(allResults.size()).append(" pages, ")
              .append("found ").append(totalIssues).append(" accessibility issues, ")
              .append("including ").append(overallIssueCounts.getOrDefault(AccessibilityEngine.IssueSeverity.CRITICAL, 0))
              .append(" critical issues and ")
              .append(overallIssueCounts.getOrDefault(AccessibilityEngine.IssueSeverity.HIGH, 0))
              .append(" high priority issues.</p>\n");

        html.append("            <p>Page Pass Rate: <b>").append(String.format("%.1f%%", pagePassRate))
              .append("</b>, ");
        if (pagePassRate >= 80) {
            html.append("Accessibility is excellent");
        } else if (pagePassRate >= 60) {
            html.append("Accessibility is good, needs improvement");
        } else {
            html.append("Accessibility needs attention");
        }
        html.append("</p>\n");

        if (!allIssues.isEmpty()) {
            html.append("            <p>Main issues: <b>");
            List<String> summaryPoints = generateSummaryPoints(allIssues);
            html.append(String.join(", ", summaryPoints));
            html.append("</b>.</p>\n");
            html.append("            <p>Recommend prioritizing fix for Critical and High severity issues to ensure core accessibility features function properly.</p>\n");
        }

        html.append("        </div>\n");

        html.append("    </div>\n");
        html.append("    <script>\n");
        html.append("        function toggle(el) {\n");
        html.append("            el.nextElementSibling.classList.toggle('show');\n");
        html.append("        }\n");
        html.append("    </script>\n");
        html.append("</body>\n");
        html.append("</html>\n");

        return html.toString();
    }

    /**
     * Generate test result card
     */
    private static String generateTestResultCard(PageResult result, String reportLink) {
        StringBuilder card = new StringBuilder();
        card.append("            <div class=\"test-result-card\">\n");
        card.append("                <div class=\"test-result-head\" onclick=\"toggle(this)\">\n");
        card.append("                    <span>").append(result.getPageName()).append("</span>\n");
        card.append("                    <span class=\"badge\" style=\"background: ")
              .append(result.isPassed() ? "#4caf50" : "#f44336").append(";\">")
              .append(result.isPassed() ? "✓ PASS" : "✗ FAIL").append("</span>\n");
        card.append("                </div>\n");
        card.append("                <div class=\"test-result-body\">\n");
        card.append("                    <p><b>Page Name:</b> ").append(result.getPageName()).append("</p>\n");
        card.append("                    <p><b>Page URL:</b> ").append(result.getPageUrl()).append("</p>\n");
        card.append("                    <p><b>Page Title:</b> ").append(result.getPageTitle()).append("</p>\n");
        card.append("                    <p><b>Test Time:</b> ")
              .append(result.getTestTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("</p>\n");
        card.append("                    <p><b>Issues Found:</b> ").append(result.getTotalIssues()).append("</p>\n");
        card.append("                    <p><b>WCAG Compliance:</b> ").append(String.format("%.1f%%", result.getWcagComplianceRate())).append("</p>\n");
        // First focus element
        if (result.getFirstFocusElement() != null && !result.getFirstFocusElement().isEmpty()) {
            card.append("                    <p><b>First Tab Focus:</b> <code style=\"background:#e8f4f8;padding:2px 6px;border-radius:3px;\">")
                  .append(escapeHtml(result.getFirstFocusElement())).append("</code></p>\n");
        }
        // Start element (if specified)
        if (result.getStartElement() != null && !result.getStartElement().isEmpty()) {
            card.append("                    <p><b>Test Started From:</b> <code style=\"background:#fef3e2;padding:2px 6px;border-radius:3px;\">")
                  .append(escapeHtml(result.getStartElement())).append("</code></p>\n");
        }
        // End element (if specified)
        if (result.getEndElement() != null && !result.getEndElement().isEmpty()) {
            card.append("                    <p><b>Test Ended At:</b> <code style=\"background:#f3e5f5;padding:2px 6px;border-radius:3px;\">")
                  .append(escapeHtml(result.getEndElement())).append("</code></p>\n");
        }
        // Element statistics
        if (result.getElementsScanned() > 0) {
            int elementsWithIssues = Math.min(result.getTotalIssues(), result.getElementsScanned());
            int elementsWithoutIssues = result.getElementsScanned() - elementsWithIssues;
            card.append("                    <p><b>Elements Scanned:</b> ").append(result.getElementsScanned()).append("</p>\n");
            card.append("                    <p><b>Elements with Issues:</b> <span style=\"color: #dc3545;\">")
                  .append(elementsWithIssues).append("</span></p>\n");
            card.append("                    <p><b>Elements Passed:</b> <span style=\"color: #28a745;\">")
                  .append(elementsWithoutIssues).append("</span></p>\n");
        }
        if (reportLink != null && !reportLink.isEmpty()) {
            card.append("                    <p><b>Detailed Report:</b> <a href=\"").append(reportLink)
                  .append("\" target=\"_blank\" style=\"color: #3498db; text-decoration: none;\">View Full Report</a></p>\n");
        }
        card.append("                </div>\n");
        card.append("            </div>\n");
        return card.toString();
    }

    /**
     * Generate issue detail (red highlighting)
     */
    private static String generateIssueDetail(AccessibilityEngine.AccessibilityIssue issue) {
        StringBuilder detail = new StringBuilder();
        detail.append("            <div class=\"issue-detail\">\n");
        detail.append("                <div class=\"issue-header\" onclick=\"toggle(this)\">\n");
        detail.append("                    <span class=\"issue-id\">").append(issue.getId()).append("</span>\n");
        detail.append("                    <span class=\"issue-severity\" style=\"color: ")
              .append(issue.getSeverity().getColor()).append(";\">")
              .append(issue.getSeverity().getDisplayName()).append("</span>\n");
        detail.append("                    <span class=\"issue-description\">").append(issue.getDescription());
        
        // Add hidden element indicator
        if (issue.isElementHidden()) {
            detail.append(" <span style=\"background:#ff9800;color:white;padding:2px 6px;border-radius:3px;font-size:11px;\">HIDDEN</span>");
        }
        
        detail.append("</span>\n");
        detail.append("                </div>\n");
        
        // Issue details (expandable section)
        detail.append("                <div class=\"issue-body\">\n");
        
        // Show warning for hidden elements
        if (issue.isElementHidden()) {
            detail.append("                    <div style=\"padding:12px;background:#fff3e0;border-left:4px solid #ff9800;margin-bottom:15px;border-radius:4px;\">\n");
            detail.append("                        <p style=\"margin:0;color:#e65100;font-weight:bold;\">⚠ Hidden/Invisible Element</p>\n");
            detail.append("                        <p style=\"margin:5px 0 0;color:#666;font-size:13px;\">This element is hidden or not visible on the page. Screenshot annotation may show an approximate location or visible parent element.</p>\n");
            detail.append("                    </div>\n");
        }
        
        if (issue.getCodeSnippet() != null && !issue.getCodeSnippet().isEmpty()) {
            // HTML code snippet - red highlight for problematic element
            detail.append("                    <div class=\"code-snippet\">\n");
            detail.append("                        <h4>Element HTML:</h4>\n");
            detail.append("                        <pre class=\"problematic-element\">").append(escapeHtml(issue.getCodeSnippet())).append("</pre>\n");
            detail.append("                    </div>\n");
        }
        
        // Display element selector (CSS selector to identify the problematic element)
        if (issue.getElementSelector() != null && !issue.getElementSelector().isEmpty()) {
            detail.append("                    <p><b>Element Selector:</b> <code>").append(escapeHtml(issue.getElementSelector())).append("</code></p>\n");
        }
        
        if (issue.getWcagCriteria() != null) {
            detail.append("                    <p><b>WCAG Standard:</b> ").append(issue.getWcagCriteria()).append("</p>\n");
        }
        
        if (issue.getFixSuggestion() != null) {
            detail.append("                    <p><b>Fix Suggestion:</b> ").append(issue.getFixSuggestion()).append("</p>\n");
        }
        
        // Display screenshot (if available)
        if (issue.getScreenshot() != null && issue.getScreenshot().length > 0) {
            String base64Image = java.util.Base64.getEncoder().encodeToString(issue.getScreenshot());
            detail.append("                    <div class=\"screenshot\">\n");
            detail.append("                        <h4>Screenshot:</h4>\n");
            detail.append("                        <img src=\"data:image/png;base64,").append(base64Image).append("\" alt=\"Issue Screenshot\" />\n");
            detail.append("                    </div>\n");
        }
        
        detail.append("                </div>\n");
        detail.append("            </div>\n");
        
        return detail.toString();
    }

    /**
     * Generate issue summary points
     */
    private static List<String> generateSummaryPoints(List<AccessibilityEngine.AccessibilityIssue> issues) {
        Map<String, Integer> categoryCounts = categorizeIssuesByType(issues);
        return categoryCounts.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .limit(4)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Categorize issues by type
     */
    private static Map<String, Integer> categorizeIssuesByType(List<AccessibilityEngine.AccessibilityIssue> issues) {
        Map<String, Integer> typeCounts = new LinkedHashMap<>();

        for (AccessibilityEngine.AccessibilityIssue issue : issues) {
            String type = categorizeIssueType(issue);
            typeCounts.put(type, typeCounts.getOrDefault(type, 0) + 1);
        }

        // Sort by count descending
        return typeCounts.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .collect(java.util.stream.Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (e1, e2) -> e1,
                    LinkedHashMap::new
                ));
    }

    /**
     * Categorize single issue to a type
     */
    private static String categorizeIssueType(AccessibilityEngine.AccessibilityIssue issue) {
        String description = issue.getDescription().toLowerCase();
        String wcag = issue.getWcagCriteria() != null ? issue.getWcagCriteria().toLowerCase() : "";

        // Image issues
        if (description.contains("alt") || description.contains("image") || description.contains("img")) {
            return "🖼️ Image";
        }

        // Form issues
        if (description.contains("label") || description.contains("form") || 
            description.contains("input") || description.contains("select") || 
            description.contains("textarea") || description.contains("autocomplete")) {
            return "📝 Form";
        }

        // Keyboard issues
        if (description.contains("keyboard") || description.contains("focus") || 
            description.contains("tab") || description.contains("key") ||
            wcag.contains("2.1.1") || wcag.contains("2.1.2") || wcag.contains("2.4.3")) {
            return "⌨️ Keyboard";
        }

        // Color contrast issues
        if (description.contains("contrast") || description.contains("color") || wcag.contains("1.4.3")) {
            return "🎨 Color";
        }

        // Link issues
        if (description.contains("link") || description.contains("href") || description.contains("anchor")) {
            return "🔗 Link";
        }

        // Heading issues
        if (description.contains("heading") || description.contains("h1") || 
            description.contains("h2") || description.contains("h3") ||
            description.contains("hierarchy")) {
            return "📑 Heading";
        }

        // Button issues
        if (description.contains("button") || description.contains("btn")) {
            return "🔘 Button";
        }

        // ARIA issues
        if (description.contains("aria") || description.contains("role") || 
            description.contains("screen reader")) {
            return "♿ ARIA";
        }

        // Title issues
        if (description.contains("title") || description.contains("page title") || wcag.contains("2.4.2")) {
            return "📄 Title";
        }

        // Language issues
        if (description.contains("lang") || description.contains("language") || wcag.contains("3.1.1")) {
            return "🌐 Language";
        }

        // Menu issues
        if (description.contains("menu") || description.contains("navigation")) {
            return "📋 Menu";
        }

        // Zoom issues
        if (description.contains("zoom") || description.contains("scale") || wcag.contains("1.4.4")) {
            return "🔍 Zoom";
        }

        // Other
        return "📦 Other";
    }

    /**
     * Get color for issue type
     */
    private static String getIssueTypeColor(String type) {
        if (type.contains("Image")) return "#9c27b0";
        if (type.contains("Form")) return "#2196f3";
        if (type.contains("Keyboard")) return "#ff5722";
        if (type.contains("Color")) return "#e91e63";
        if (type.contains("Link")) return "#00bcd4";
        if (type.contains("Heading")) return "#3f51b5";
        if (type.contains("Button")) return "#ff9800";
        if (type.contains("ARIA")) return "#4caf50";
        if (type.contains("Title")) return "#607d8b";
        if (type.contains("Language")) return "#009688";
        if (type.contains("Menu")) return "#795548";
        if (type.contains("Zoom")) return "#673ab7";
        return "#9e9e9e";
    }

    /**
     * Get report styles
     */
    private static String getReportStyles() {
        StringBuilder styles = new StringBuilder();
        styles.append("        * {\n");
        styles.append("            margin: 0;\n");
        styles.append("            padding: 0;\n");
        styles.append("            box-sizing: border-box;\n");
        styles.append("            font-family: \"Microsoft YaHei\", -apple-system, BlinkMacSystemFont, sans-serif;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        body {\n");
        styles.append("            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n");
        styles.append("            padding: 20px;\n");
        styles.append("            min-height: 100vh;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .report {\n");
        styles.append("            max-width: 1200px;\n");
        styles.append("            margin: 0 auto;\n");
        styles.append("            background: #fff;\n");
        styles.append("            padding: 40px;\n");
        styles.append("            border-radius: 16px;\n");
        styles.append("            box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .title {\n");
        styles.append("            text-align: center;\n");
        styles.append("            margin-bottom: 10px;\n");
        styles.append("            font-size: 32px;\n");
        styles.append("            color: #2c3e50;\n");
        styles.append("            font-weight: 700;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .subtitle {\n");
        styles.append("            text-align: center;\n");
        styles.append("            color: #7f8c8d;\n");
        styles.append("            margin-bottom: 30px;\n");
        styles.append("            font-size: 14px;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .section {\n");
        styles.append("            margin-bottom: 30px;\n");
        styles.append("            padding: 25px;\n");
        styles.append("            background: #f8f9fa;\n");
        styles.append("            border-radius: 12px;\n");
        styles.append("            border-left: 5px solid #3498db;\n");
        styles.append("            transition: all 0.3s ease;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .section:hover {\n");
        styles.append("            box-shadow: 0 5px 15px rgba(0, 0, 0, 0.1);\n");
        styles.append("            transform: translateY(-2px);\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .section h2 {\n");
        styles.append("            font-size: 20px;\n");
        styles.append("            margin-bottom: 20px;\n");
        styles.append("            color: #34495e;\n");
        styles.append("            font-weight: 600;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .stats {\n");
        styles.append("            display: flex;\n");
        styles.append("            gap: 20px;\n");
        styles.append("            flex-wrap: wrap;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .stat-item {\n");
        styles.append("            flex: 1;\n");
        styles.append("            min-width: 160px;\n");
        styles.append("            background: #fff;\n");
        styles.append("            padding: 20px;\n");
        styles.append("            border-radius: 10px;\n");
        styles.append("            text-align: center;\n");
        styles.append("            border: 1px solid #e9ecef;\n");
        styles.append("            transition: all 0.3s ease;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .stat-item:hover {\n");
        styles.append("            box-shadow: 0 5px 15px rgba(0, 0, 0, 0.1);\n");
        styles.append("            transform: translateY(-2px);\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .stat-item .num {\n");
        styles.append("            font-size: 28px;\n");
        styles.append("            font-weight: bold;\n");
        styles.append("            margin-top: 10px;\n");
        styles.append("            transition: transform 0.3s ease;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .stat-item:hover .num {\n");
        styles.append("            transform: scale(1.1);\n");
        styles.append("        }\n");
        styles.append("\n");
        // Issue Type specific style - don't expand to fill row
        styles.append("        .issue-type-item {\n");
        styles.append("            flex: 0 1 auto;\n");
        styles.append("            min-width: 140px;\n");
        styles.append("            max-width: 200px;\n");
        styles.append("            background: #fff;\n");
        styles.append("            padding: 15px 20px;\n");
        styles.append("            border-radius: 10px;\n");
        styles.append("            text-align: center;\n");
        styles.append("            border: 1px solid #e9ecef;\n");
        styles.append("            transition: all 0.3s ease;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .issue-type-item:hover {\n");
        styles.append("            box-shadow: 0 5px 15px rgba(0, 0, 0, 0.1);\n");
        styles.append("            transform: translateY(-2px);\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .issue-type-item .num {\n");
        styles.append("            font-size: 24px;\n");
        styles.append("            font-weight: bold;\n");
        styles.append("            margin-top: 8px;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .critical { color: #e74c3c; }\n");
        styles.append("        .high { color: #f39c12; }\n");
        styles.append("        .medium { color: #f1c40f; }\n");
        styles.append("        .low { color: #27ae60; }\n");
        styles.append("        .pass { color: #2ecc71; }\n");
        styles.append("\n");
        styles.append("        .result-status {\n");
        styles.append("            padding: 20px;\n");
        styles.append("            border-radius: 8px;\n");
        styles.append("            margin: 15px 0;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .result-status h3 {\n");
        styles.append("            margin: 0 0 10px 0;\n");
        styles.append("            font-size: 18px;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .result-status p {\n");
        styles.append("            margin: 5px 0;\n");
        styles.append("            color: #555;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .test-result-card {\n");
        styles.append("            border: 1px solid #e9ecef;\n");
        styles.append("            border-radius: 10px;\n");
        styles.append("            margin-bottom: 15px;\n");
        styles.append("            overflow: hidden;\n");
        styles.append("            transition: all 0.3s ease;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .test-result-card:hover {\n");
        styles.append("            box-shadow: 0 5px 15px rgba(0, 0, 0, 0.1);\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .test-result-head {\n");
        styles.append("            padding: 16px 20px;\n");
        styles.append("            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n");
        styles.append("            color: white;\n");
        styles.append("            cursor: pointer;\n");
        styles.append("            display: flex;\n");
        styles.append("            justify-content: space-between;\n");
        styles.append("            align-items: center;\n");
        styles.append("            font-weight: 600;\n");
        styles.append("            font-size: 16px;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .badge {\n");
        styles.append("            padding: 6px 14px;\n");
        styles.append("            border-radius: 20px;\n");
        styles.append("            color: white;\n");
        styles.append("            font-size: 12px;\n");
        styles.append("            font-weight: bold;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .test-result-body {\n");
        styles.append("            padding: 25px;\n");
        styles.append("            display: none;\n");
        styles.append("            border-top: 1px solid #e9ecef;\n");
        styles.append("            background: #fff;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .test-result-body.show {\n");
        styles.append("            display: block;\n");
        styles.append("            animation: slideDown 0.3s ease;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        @keyframes slideDown {\n");
        styles.append("            from { opacity: 0; transform: translateY(-10px); }\n");
        styles.append("            to { opacity: 1; transform: translateY(0); }\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .test-result-body p {\n");
        styles.append("            margin-bottom: 12px;\n");
        styles.append("            line-height: 1.8;\n");
        styles.append("            color: #555;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .test-result-body b {\n");
        styles.append("            color: #2c3e50;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .page-title {\n");
        styles.append("            font-size: 18px;\n");
        styles.append("            color: #2c3e50;\n");
        styles.append("            margin: 25px 0 15px 0;\n");
        styles.append("            padding-bottom: 8px;\n");
        styles.append("            border-bottom: 3px solid #3498db;\n");
        styles.append("            font-weight: 600;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .issue-detail {\n");
        styles.append("            margin-bottom: 20px;\n");
        styles.append("            border: 1px solid #e9ecef;\n");
        styles.append("            border-radius: 10px;\n");
        styles.append("            overflow: hidden;\n");
        styles.append("            background: #fff;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .issue-header {\n");
        styles.append("            padding: 15px 20px;\n");
        styles.append("            background: #f8f9fa;\n");
        styles.append("            border-bottom: 1px solid #e9ecef;\n");
        styles.append("            display: flex;\n");
        styles.append("            gap: 12px;\n");
        styles.append("            align-items: center;\n");
        styles.append("            cursor: pointer;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .issue-id {\n");
        styles.append("            background: #6c757d;\n");
        styles.append("            color: white;\n");
        styles.append("            padding: 4px 10px;\n");
        styles.append("            border-radius: 4px;\n");
        styles.append("            font-size: 12px;\n");
        styles.append("            font-weight: bold;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .issue-severity {\n");
        styles.append("            font-weight: bold;\n");
        styles.append("            font-size: 13px;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .issue-description {\n");
        styles.append("            flex: 1;\n");
        styles.append("            color: #495057;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .issue-body {\n");
        styles.append("            padding: 20px;\n");
        styles.append("            display: none;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .issue-body.show {\n");
        styles.append("            display: block;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .code-snippet {\n");
        styles.append("            margin: 15px 0;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .code-snippet h4 {\n");
        styles.append("            color: #6c757d;\n");
        styles.append("            margin-bottom: 8px;\n");
        styles.append("            font-size: 14px;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .problematic-element {\n");
        styles.append("            background: #f8d7da;\n");
        styles.append("            color: #721c24;\n");
        styles.append("            padding: 15px;\n");
        styles.append("            border-radius: 8px;\n");
        styles.append("            border-left: 4px solid #dc3545;\n");
        styles.append("            font-family: 'Courier New', monospace;\n");
        styles.append("            font-size: 13px;\n");
        styles.append("            line-height: 1.6;\n");
        styles.append("            overflow-x: auto;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .screenshot {\n");
        styles.append("            margin-top: 15px;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .screenshot h4 {\n");
        styles.append("            color: #6c757d;\n");
        styles.append("            margin-bottom: 10px;\n");
        styles.append("            font-size: 14px;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .screenshot img {\n");
        styles.append("            max-width: 100%;\n");
        styles.append("            border-radius: 8px;\n");
        styles.append("            border: 1px solid #dee2e6;\n");
        styles.append("            box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .conclusion {\n");
        styles.append("            padding: 30px;\n");
        styles.append("            background: linear-gradient(135deg, #667eea15 0%, #764ba215 100%);\n");
        styles.append("            border-radius: 12px;\n");
        styles.append("            margin-top: 30px;\n");
        styles.append("            border-left: 5px solid #3498db;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .conclusion h3 {\n");
        styles.append("            color: #2c3e50;\n");
        styles.append("            margin-bottom: 15px;\n");
        styles.append("            font-size: 20px;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .conclusion p {\n");
        styles.append("            line-height: 1.8;\n");
        styles.append("            color: #555;\n");
        styles.append("            margin-bottom: 12px;\n");
        styles.append("        }\n");
        styles.append("\n");
        styles.append("        .conclusion b {\n");
        styles.append("            color: #3498db;\n");
        styles.append("        }\n");
        
        return styles.toString();
    }

    /**
     * HTML escape
     */
    private static String escapeHtml(String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;")
                 .replace("<", "&lt;")
                 .replace(">", "&gt;")
                 .replace("\"", "&quot;")
                 .replace("'", "&#39;");
    }

    /**
     * Save report to file
     */
    private static void saveReport(String content, String path) {
        try {
            Path filePath = Paths.get(path);
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content);
            logger.info("Report saved to: {}", path);
        } catch (Exception e) {
            logger.error("Failed to save report to: {}", path, e);
        }
    }

    /**
     * Cleanup collector (call after test ends)
     */
    public static void cleanup() {
        results.remove();
        generatedReports.remove();
        testStartTime.remove();
        scanConfig.remove();
        initialized = false;
        logger.info("AccessibilityScanner cleaned up");
    }
}
