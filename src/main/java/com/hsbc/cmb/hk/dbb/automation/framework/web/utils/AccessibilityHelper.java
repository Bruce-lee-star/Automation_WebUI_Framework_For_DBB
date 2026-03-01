package com.hsbc.cmb.hk.dbb.automation.framework.web.utils;

import com.microsoft.playwright.Page;
import net.serenitybdd.core.Serenity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;


public class AccessibilityHelper {
    
    private static final Logger logger = LoggerFactory.getLogger(AccessibilityHelper.class);
    
    /**
     * 检查整个页面的无障碍性
     * 
     * @param page Playwright Page对象
     * @return 无障碍检查结果列表
     */
    public static List<String> checkPageAccessibility(Page page) {
        List<String> issues = new ArrayList<>();
        
        if (page == null) {
            issues.add("Page object is null, cannot perform accessibility check");
            return issues;
        }
        
        logger.info("Starting accessibility check...");
        
        // 1. Check page title
        checkPageTitle(page, issues);
        
        // 2. Check page language
        checkPageLanguage(page, issues);
        
        // 3. Check image alt attributes
        checkImagesAltText(page, issues);
        
        // 4. Check form labels
        checkFormLabels(page, issues);
        
        // 5. Check link text
        checkLinkText(page, issues);
        
        // 6. Check heading hierarchy
        checkHeadingHierarchy(page, issues);
        
        // 7. Check button accessibility
        checkButtonAccessibility(page, issues);
        
        // 8. Check focus management
        checkFocusManagement(page, issues);
        
        // 9. Check color contrast
        checkColorContrast(page, issues);
        
        // 10. Check ARIA attributes
        checkAriaAttributes(page, issues);
        
        logger.info("Accessibility check completed, found {} issues", issues.size());
        
        return issues;
    }
    
    /**
     * 检查页面标题
     */
    private static void checkPageTitle(Page page, List<String> issues) {
        try {
            String title = page.title();
            if (title == null || title.trim().isEmpty()) {
                issues.add("Missing page title or empty title tag");
            } else if (title.length() > 60) {
                issues.add("Page title too long (" + title.length() + " characters), recommended max 60 characters");
            }
            logger.debug("Page title check: {}", title);
        } catch (Exception e) {
            issues.add("Error checking page title: " + e.getMessage());
        }
    }
    
    /**
     * 检查页面语言
     */
    private static void checkPageLanguage(Page page, List<String> issues) {
        try {
            String lang = page.evaluate("document.documentElement.getAttribute('lang')").toString();
            if (lang == null || lang.trim().isEmpty()) {
                issues.add("Missing lang attribute on html element");
            }
            logger.debug("Page language check: {}", lang);
        } catch (Exception e) {
            issues.add("Error checking page language: " + e.getMessage());
        }
    }
    
    /**
     * 检查图片alt属性
     */
    private static void checkImagesAltText(Page page, List<String> issues) {
        try {
            int missingAlt = (int) page.evaluate("() => { " +
                 "const images = document.querySelectorAll('img:not([alt]), img[alt=\"\"]'); " +
                "return images.length; " +
            "}");
            
            if (missingAlt > 0) {
                issues.add("Found " + missingAlt + " images missing alt attribute or with empty alt");
            }
            logger.debug("Image alt attribute check completed");
        } catch (Exception e) {
            issues.add("Error checking image alt attributes: " + e.getMessage());
        }
    }
    
    /**
     * 检查表单标签
     */
    private static void checkFormLabels(Page page, List<String> issues) {
        try {
            int unlabeledInputs = (int) page.evaluate("() => { " +
                "const inputs = document.querySelectorAll('input[type=\"text\"], input[type=\"email\"], ' + " +
                    "'input[type=\"password\"], input[type=\"number\"], textarea, select'); " +
                "return Array.from(inputs).filter(input => " +
                    "!input.labels || input.labels.length === 0).length; " +
            "}");
            
            if (unlabeledInputs > 0) {
                issues.add("Found " + unlabeledInputs + " form elements missing label tags");
            }
            logger.debug("Form labels check completed");
        } catch (Exception e) {
            issues.add("Error checking form labels: " + e.getMessage());
        }
    }
    
    /**
     * 检查链接文本
     */
    private static void checkLinkText(Page page, List<String> issues) {
        try {
            int emptyLinks = (int) page.evaluate("() => { " +
                "const links = document.querySelectorAll('a'); " +
                "return Array.from(links).filter(link => " +
                    "!link.textContent.trim() && " +
                    "!link.querySelector('img[alt]')).length; " +
            "}");
            
            if (emptyLinks > 0) {
                issues.add("Found " + emptyLinks + " links missing text description");
            }
            logger.debug("Link text check completed");
        } catch (Exception e) {
            issues.add("Error checking link text: " + e.getMessage());
        }
    }
    
    /**
     * 检查标题层级
     */
    private static void checkHeadingHierarchy(Page page, List<String> issues) {
        try {
            String hierarchyIssue = (String) page.evaluate("() => { " +
                "const headings = document.querySelectorAll('h1, h2, h3, h4, h5, h6'); " +
                "let lastLevel = 0; " +
                "let issues = []; " +
                "headings.forEach(h => { " +
                    "const level = parseInt(h.tagName[1]); " +
                    "if (level > lastLevel + 1 && lastLevel !== 0) { " +
                        "issues.push('Incorrect heading hierarchy: ' + h.tagName + ' after H' + lastLevel); " +
                    "}" +
                    "lastLevel = level;" +
                "}); " +
                "return issues.join(', '); " +
            "}");
            
            if (hierarchyIssue != null && !hierarchyIssue.isEmpty()) {
                issues.add("Heading hierarchy issue: " + hierarchyIssue);
            }
            logger.debug("Heading hierarchy check completed");
        } catch (Exception e) {
            issues.add("Error checking heading hierarchy: " + e.getMessage());
        }
    }
    
    /**
     * 检查按钮可访问性
     */
    private static void checkButtonAccessibility(Page page, List<String> issues) {
        try {
            int emptyButtons = (int) page.evaluate("() => { " +
                "const buttons = document.querySelectorAll('button'); " +
                "return Array.from(buttons).filter(btn => " +
                    "!btn.textContent.trim() && " +
                    "!btn.getAttribute('aria-label') && " +
                    "!btn.getAttribute('title')).length; " +
            "}");
            
            if (emptyButtons > 0) {
                issues.add("Found " + emptyButtons + " buttons missing text description or aria-label");
            }
            logger.debug("Button accessibility check completed");
        } catch (Exception e) {
            issues.add("Error checking button accessibility: " + e.getMessage());
        }
    }
    
    /**
     * 检查焦点管理
     */
    private static void checkFocusManagement(Page page, List<String> issues) {
        try {
            int noFocusIndicators = (int) page.evaluate("() => { " +
                "const styles = window.getComputedStyle(document.body); " +
                "const hasFocusStyle = (element) => { " +
                    "const focusedStyles = window.getComputedStyle(element); " +
                    "return focusedStyles.outline !== 'none' || " +
                           "focusedStyles.boxShadow !== 'none'; " +
                "}; " +
                "return hasFocusStyle(document.body) ? 0 : 1; " +
            "}");
            
            if (noFocusIndicators > 0) {
                issues.add("Page may be missing focus visibility styles");
            }
            logger.debug("Focus management check completed");
        } catch (Exception e) {
            issues.add("Error checking focus management: " + e.getMessage());
        }
    }
    
    /**
     * 检查颜色对比度
     */
    private static void checkColorContrast(Page page, List<String> issues) {
        try {
            // Simple check: find elements with potential low contrast
            int potentialLowContrast = (int) page.evaluate("() => { " +
                "const elements = document.querySelectorAll('*'); " +
                "return Array.from(elements).filter(el => { " +
                    "const styles = window.getComputedStyle(el); " +
                    "const color = styles.color; " +
                    "const bg = styles.backgroundColor; " +
                    "return color !== 'rgba(0, 0, 0, 0)' && " +
                           "bg !== 'rgba(0, 0, 0, 0)' && " +
                           "color !== bg; " +
                "}).length; " +
            "}");
            
            logger.debug("Color contrast check completed");
        } catch (Exception e) {
            issues.add("Error checking color contrast: " + e.getMessage());
        }
    }
    
    /**
     * 检查ARIA属性
     */
    private static void checkAriaAttributes(Page page, List<String> issues) {
        try {
            int invalidAria = (int) page.evaluate("() => { " +
                "const elements = document.querySelectorAll('[aria-hidden=\"false\"]'); " +
                "return Array.from(elements).filter(el => { " +
                    "const styles = window.getComputedStyle(el); " +
                    "return styles.display === 'none' || styles.visibility === 'hidden'; " +
                "}).length; " +
            "}");
            
            if (invalidAria > 0) {
                issues.add("Found " + invalidAria + " elements with incorrect aria-hidden attribute");
            }
            logger.debug("ARIA attributes check completed");
        } catch (Exception e) {
            issues.add("Error checking ARIA attributes: " + e.getMessage());
        }
    }
    
    /**
     * 检查特定元素的无障碍性
     * 
     * @param page Playwright Page对象
     * @param selector CSS选择器
     * @return 无障碍检查结果
     */
    public static List<String> checkElementAccessibility(Page page, String selector) {
        List<String> issues = new ArrayList<>();
        
        try {
            boolean exists = page.locator(selector).count() > 0;
            if (!exists) {
                issues.add("Element not found for selector: " + selector);
                return issues;
            }
            
            // Check if element is visible
            boolean isVisible = page.locator(selector).isVisible();
            if (!isVisible) {
                issues.add("Element is not visible: " + selector);
            }
            
            // Check if element is accessible
            boolean isAccessible = (boolean) page.evaluate("sel => { " +
                "const el = document.querySelector(sel); " +
                "return el && !el.hasAttribute('aria-hidden'); " +
            "}", selector);
            
            if (!isAccessible) {
                issues.add("Element is hidden via ARIA: " + selector);
            }
            
            logger.debug("Element accessibility check completed: {}", selector);
            
        } catch (Exception e) {
            issues.add("Error checking element accessibility: " + e.getMessage());
        }
        
        return issues;
    }
    
    /**
     * 快速检查页面基本无障碍性（仅检查关键项）
     * 
     * @param page Playwright Page对象
     * @return 无障碍检查结果列表
     */
    public static List<String> quickAccessibilityCheck(Page page) {
        List<String> issues = new ArrayList<>();
        
        if (page == null) {
            issues.add("Page object is null, cannot perform accessibility check");
            return issues;
        }
        
        logger.info("Performing quick accessibility check...");
        
        // Check only key items
        checkPageTitle(page, issues);
        checkImagesAltText(page, issues);
        checkFormLabels(page, issues);
        
        logger.info("Quick accessibility check completed, found {} issues", issues.size());
        
        return issues;
    }
    
    /**
     * 输出无障碍检查报告
     * 
     * @param issues 无障碍问题列表
     * @return 格式化的报告字符串
     */
    public static String generateReport(List<String> issues) {
        if (issues.isEmpty()) {
            return " Accessibility check passed, no issues found";
        }
        
        StringBuilder report = new StringBuilder();
        report.append("Accessibility Check Results\n");
        report.append("==========================\n");
        report.append("Issues found: ").append(issues.size()).append("\n\n");
        
        for (int i = 0; i < issues.size(); i++) {
            report.append((i + 1)).append(". ").append(issues.get(i)).append("\n");
        }
        
        return report.toString();
    }
    
    /**
     * 检查页面无障碍性并自动记录到Serenity报告
     * 
     * @param page Playwright Page对象
     * @return 无障碍检查结果列表
     */
    public static List<String> checkPageAccessibilityWithReport(Page page) {
        List<String> issues = checkPageAccessibility(page);
        
        // Generate and record report to Serenity
        recordAccessibilityReportToSerenity(page, issues, "Full Accessibility Check");
        
        return issues;
    }
    
    /**
     * 快速检查页面无障碍性并自动记录到Serenity报告
     * 
     * @param page Playwright Page对象
     * @return 无障碍检查结果列表
     */
    public static List<String> quickAccessibilityCheckWithReport(Page page) {
        List<String> issues = quickAccessibilityCheck(page);
        
        // Generate and record report to Serenity
        recordAccessibilityReportToSerenity(page, issues, "Quick Accessibility Check");
        
        return issues;
    }
    
    /**
     * 检查特定元素无障碍性并自动记录到Serenity报告
     * 
     * @param page Playwright Page对象
     * @param selector CSS选择器
     * @param elementName 元素名称（用于报告）
     * @return 无障碍检查结果列表
     */
    public static List<String> checkElementAccessibilityWithReport(Page page, String selector, String elementName) {
        List<String> issues = checkElementAccessibility(page, selector);
        
        // Record element-specific report to Serenity
        recordElementAccessibilityReportToSerenity(page, selector, elementName, issues);
        
        return issues;
    }
    
    /**
     * 将无障碍检查结果记录到Serenity报告
     * 
     * @param page Playwright Page对象
     * @param issues 无障碍问题列表
     * @param checkType 检查类型（如"Full Check", "Quick Check"）
     */
    public static void recordAccessibilityReportToSerenity(Page page, List<String> issues, String checkType) {
        try {
            // Record summary data
            Serenity.recordReportData()
                .withTitle("Accessibility Check Summary")
                .andContents(generateHtmlSummary(issues, checkType, page));
            
            // Record detailed issues
            if (!issues.isEmpty()) {
                Serenity.recordReportData()
                    .withTitle("Accessibility Issues Detail")
                    .andContents(generateHtmlIssues(issues));
                
                // Record JSON data for potential further processing
                Serenity.recordReportData()
                    .withTitle("Accessibility Issues JSON")
                    .andContents(generateJsonReport(issues));
            }
            
            logger.info("Accessibility report recorded to Serenity successfully");
        } catch (Exception e) {
            logger.error("Failed to record accessibility report to Serenity: {}", e.getMessage());
        }
    }
    
    /**
     * 将元素无障碍检查结果记录到Serenity报告
     * 
     * @param page Playwright Page对象
     * @param selector CSS选择器
     * @param elementName 元素名称
     * @param issues 无障碍问题列表
     */
    public static void recordElementAccessibilityReportToSerenity(Page page, String selector, String elementName, List<String> issues) {
        try {
            String elementReport = generateElementReport(selector, elementName, issues);
            
            Serenity.recordReportData()
                .withTitle("Element Accessibility: " + elementName)
                .andContents(elementReport);
            
            logger.info("Element accessibility report recorded to Serenity: {}", elementName);
        } catch (Exception e) {
            logger.error("Failed to record element accessibility report to Serenity: {}", e.getMessage());
        }
    }
    
    /**
     * 生成HTML格式的摘要报告
     */
    private static String generateHtmlSummary(List<String> issues, String checkType, Page page) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String pageUrl = page != null ? page.url() : "N/A";
        String pageTitle = page != null ? page.title() : "N/A";
        
        StringBuilder html = new StringBuilder();
        html.append("<html><head><style>");
        html.append("body { font-family: Arial, sans-serif; margin: 20px; }");
        html.append("h1 { color: #333; }");
        html.append("table { border-collapse: collapse; width: 100%; margin: 20px 0; }");
        html.append("th, td { border: 1px solid #ddd; padding: 12px; text-align: left; }");
        html.append("th { background-color: #f2f2f2; }");
        html.append(".pass { color: green; font-weight: bold; }");
        html.append(".fail { color: red; font-weight: bold; }");
        html.append("</style></head><body>");
        
        html.append("<h1>Accessibility Check Report</h1>");
        html.append("<table><tr><th>Property</th><th>Value</th></tr>");
        html.append("<tr><td>Check Type</td><td>").append(checkType).append("</td></tr>");
        html.append("<tr><td>Timestamp</td><td>").append(timestamp).append("</td></tr>");
        html.append("<tr><td>Page URL</td><td>").append(pageUrl).append("</td></tr>");
        html.append("<tr><td>Page Title</td><td>").append(pageTitle).append("</td></tr>");
        
        String status = issues.isEmpty() ? "PASS" : "FAIL";
        String statusClass = issues.isEmpty() ? "pass" : "fail";
        html.append("<tr><td>Status</td><td class=\"").append(statusClass).append("\">").append(status).append("</td></tr>");
        html.append("<tr><td>Issues Found</td><td>").append(issues.size()).append("</td></tr>");
        html.append("</table>");
        
        html.append("</body></html>");
        
        return html.toString();
    }
    
    /**
     * 生成HTML格式的详细问题列表
     */
    private static String generateHtmlIssues(List<String> issues) {
        if (issues.isEmpty()) {
            return "<div class='pass'>No accessibility issues found!</div>";
        }
        
        StringBuilder html = new StringBuilder();
        html.append("<html><head><style>");
        html.append("body { font-family: Arial, sans-serif; margin: 20px; }");
        html.append("h2 { color: #d32f2f; }");
        html.append("ol { margin: 20px 0; padding-left: 20px; }");
        html.append("li { margin: 10px 0; padding: 10px; background-color: #fff3e0; border-left: 4px solid #ff9800; }");
        html.append("</style></head><body>");
        
        html.append("<h2>Accessibility Issues Found (").append(issues.size()).append(")</h2>");
        html.append("<ol>");
        
        for (int i = 0; i < issues.size(); i++) {
            html.append("<li>").append(issues.get(i)).append("</li>");
        }
        
        html.append("</ol>");
        html.append("</body></html>");
        
        return html.toString();
    }
    
    /**
     * 生成JSON格式的报告
     */
    private static String generateJsonReport(List<String> issues) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"timestamp\": \"").append(LocalDateTime.now()).append("\",\n");
        json.append("  \"totalIssues\": ").append(issues.size()).append(",\n");
        json.append("  \"issues\": [\n");
        
        for (int i = 0; i < issues.size(); i++) {
            json.append("    \"").append(i + 1).append("\": \"").append(issues.get(i).replace("\"", "\\\"")).append("\"");
            if (i < issues.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }
        
        json.append("  ]\n");
        json.append("}");
        
        return json.toString();
    }
    
    /**
     * 生成元素无障碍报告
     */
    private static String generateElementReport(String selector, String elementName, List<String> issues) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        
        StringBuilder html = new StringBuilder();
        html.append("<html><head><style>");
        html.append("body { font-family: Arial, sans-serif; margin: 20px; }");
        html.append("h2 { color: #333; }");
        html.append("table { border-collapse: collapse; width: 100%; margin: 20px 0; }");
        html.append("th, td { border: 1px solid #ddd; padding: 12px; text-align: left; }");
        html.append("th { background-color: #f2f2f2; }");
        html.append(".pass { color: green; font-weight: bold; }");
        html.append(".fail { color: red; font-weight: bold; }");
        html.append("</style></head><body>");
        
        html.append("<h2>Element Accessibility Report</h2>");
        html.append("<table><tr><th>Property</th><th>Value</th></tr>");
        html.append("<tr><td>Element Name</td><td>").append(elementName).append("</td></tr>");
        html.append("<tr><td>Selector</td><td><code>").append(selector).append("</code></td></tr>");
        html.append("<tr><td>Timestamp</td><td>").append(timestamp).append("</td></tr>");
        
        String status = issues.isEmpty() ? "PASS" : "FAIL";
        String statusClass = issues.isEmpty() ? "pass" : "fail";
        html.append("<tr><td>Status</td><td class=\"").append(statusClass).append("\">").append(status).append("</td></tr>");
        html.append("<tr><td>Issues Found</td><td>").append(issues.size()).append("</td></tr>");
        html.append("</table>");
        
        if (!issues.isEmpty()) {
            html.append("<h3>Issues:</h3><ul>");
            for (String issue : issues) {
                html.append("<li>").append(issue).append("</li>");
            }
            html.append("</ul>");
        }
        
        html.append("</body></html>");
        
        return html.toString();
    }
    
    /**
     * 生成Markdown格式的报告
     * 
     * @param issues 无障碍问题列表
     * @param checkType 检查类型
     * @return Markdown格式报告
     */
    public static String generateMarkdownReport(List<String> issues, String checkType) {
        StringBuilder md = new StringBuilder();
        md.append("# Accessibility Check Report\n\n");
        md.append("**Check Type:** ").append(checkType).append("\n");
        md.append("**Timestamp:** ").append(LocalDateTime.now()).append("\n");
        md.append("**Status:** ").append(issues.isEmpty() ? " PASS" : " FAIL").append("\n");
        md.append("**Issues Found:** ").append(issues.size()).append("\n\n");
        
        if (!issues.isEmpty()) {
            md.append("## Issues Found\n\n");
            for (int i = 0; i < issues.size(); i++) {
                md.append((i + 1)).append(". ").append(issues.get(i)).append("\n");
            }
        } else {
            md.append(" No accessibility issues found!\n");
        }
        
        return md.toString();
    }
}
