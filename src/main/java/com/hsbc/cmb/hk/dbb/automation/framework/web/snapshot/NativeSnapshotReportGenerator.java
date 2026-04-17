package com.hsbc.cmb.hk.dbb.automation.framework.web.snapshot;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Playwright 原生快照测试 HTML 报告生成器
 * <p>
 * 生成独立的 HTML 报告，展示视觉快照和 ARIA 快照的测试结果。
 * </p>
 *
 * @see PlaywrightSnapshotSupport
 */
public class NativeSnapshotReportGenerator {

    private static final Logger logger = LoggerFactory.getLogger(NativeSnapshotReportGenerator.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 生成 HTML 报告到默认目录
     */
    public static String generate() {
        return generate("target/snapshot-reports/native");
    }

    /**
     * 生成 HTML 报告到指定目录
     */
    public static String generate(String outputDir) {
        try {
            List<NativeSnapshotResult> results = PlaywrightSnapshotSupport.getResults();

            if (results.isEmpty()) {
                logger.info("[NativeSnapshotReport] No results to report");
                return null;
            }

            // 创建输出目录
            Path outputPath = Paths.get(outputDir);
            Files.createDirectories(outputPath);

            // 生成报告文件
            String reportFileName = "native-snapshot-report-" +
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".html";
            Path reportPath = outputPath.resolve(reportFileName);

            // 生成 HTML 内容
            String html = generateHtml(results);
            Files.writeString(reportPath, html);

            logger.info("[NativeSnapshotReport] Report generated: {}", reportPath.toAbsolutePath());
            return reportPath.toAbsolutePath().toString();

        } catch (IOException e) {
            logger.error("[NativeSnapshotReport] Failed to generate report", e);
            return null;
        }
    }

    private static String generateHtml(List<NativeSnapshotResult> results) {
        NativeSnapshotResult.Stats stats = PlaywrightSnapshotSupport.getStats();

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"en\">\n");
        sb.append("<head>\n");
        sb.append("    <meta charset=\"UTF-8\">\n");
        sb.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("    <title>Playwright Native Snapshot Report</title>\n");
        sb.append("    <style>\n");
        sb.append(getCss());
        sb.append("    </style>\n");
        sb.append("</head>\n");
        sb.append("<body>\n");
        sb.append("    <div class=\"container\">\n");
        sb.append("        <header>\n");
        sb.append("            <h1>Playwright Native Snapshot Report</h1>\n");
        sb.append("            <p class=\"timestamp\">Generated: ").append(LocalDateTime.now().format(TIMESTAMP_FORMATTER)).append("</p>\n");
        sb.append("        </header>\n");

        // 统计摘要
        sb.append("        <section class=\"stats-section\">\n");
        sb.append("            <h2>Test Summary</h2>\n");
        sb.append("            <div class=\"stats-grid\">\n");
        sb.append("                <div class=\"stat-card\">\n");
        sb.append("                    <span class=\"stat-value\">").append(stats.getTotal()).append("</span>\n");
        sb.append("                    <span class=\"stat-label\">Total</span>\n");
        sb.append("                </div>\n");
        sb.append("                <div class=\"stat-card stat-pass\">\n");
        sb.append("                    <span class=\"stat-value\">").append(stats.getPassed()).append("</span>\n");
        sb.append("                    <span class=\"stat-label\">Passed</span>\n");
        sb.append("                </div>\n");
        sb.append("                <div class=\"stat-card stat-fail\">\n");
        sb.append("                    <span class=\"stat-value\">").append(stats.getFailed()).append("</span>\n");
        sb.append("                    <span class=\"stat-label\">Failed</span>\n");
        sb.append("                </div>\n");
        sb.append("                <div class=\"stat-card stat-error\">\n");
        sb.append("                    <span class=\"stat-value\">").append(stats.getError()).append("</span>\n");
        sb.append("                    <span class=\"stat-label\">Error</span>\n");
        sb.append("                </div>\n");
        sb.append("            </div>\n");
        sb.append("            <div class=\"pass-rate\">\n");
        sb.append("                Pass Rate: <strong>").append(String.format("%.1f%%", stats.getPassRate())).append("</strong>\n");
        sb.append("            </div>\n");
        sb.append("        </section>\n");

        // 类型分布
        sb.append("        <section class=\"type-section\">\n");
        sb.append("            <h2>Snapshot Type Distribution</h2>\n");
        sb.append("            <div class=\"type-badges\">\n");
        sb.append("                <span class=\"type-badge type-visual\">Visual: ").append(stats.getVisual()).append("</span>\n");
        sb.append("                <span class=\"type-badge type-aria\">ARIA: ").append(stats.getAria()).append("</span>\n");
        sb.append("            </div>\n");
        sb.append("        </section>\n");

        // 饼图
        if (stats.getTotal() > 0) {
            sb.append("        <section class=\"chart-section\">\n");
            sb.append("            <h2>Result Distribution</h2>\n");
            sb.append(generatePieChart(stats));
            sb.append("        </section>\n");
        }

        // 详细结果表格
        sb.append("        <section class=\"results-section\">\n");
        sb.append("            <h2>Test Results</h2>\n");
        sb.append("            <table class=\"results-table\">\n");
        sb.append("                <thead>\n");
        sb.append("                    <tr>\n");
        sb.append("                        <th>Status</th>\n");
        sb.append("                        <th>Type</th>\n");
        sb.append("                        <th>Name</th>\n");
        sb.append("                        <th>Duration</th>\n");
        sb.append("                        <th>Details</th>\n");
        sb.append("                    </tr>\n");
        sb.append("                </thead>\n");
        sb.append("                <tbody>\n");

        for (NativeSnapshotResult result : results) {
            sb.append("                    <tr class=\"")
              .append(result.isPassed() ? "row-pass" : result.isFailed() ? "row-fail" : "row-error")
              .append("\">\n");
            sb.append("                        <td><span class=\"status-badge ")
              .append(result.isPassed() ? "badge-pass" : result.isFailed() ? "badge-fail" : "badge-error")
              .append("\">").append(result.getStatus()).append("</span></td>\n");
            sb.append("                        <td><span class=\"type-indicator ")
              .append(result.isVisual() ? "type-visual" : "type-aria")
              .append("\">").append(result.getType().name()).append("</span></td>\n");
            sb.append("                        <td class=\"name-cell\">").append(escapeHtml(result.getName())).append("</td>\n");
            sb.append("                        <td>").append(result.getDurationMs()).append("ms</td>\n");
            sb.append("                        <td class=\"details-cell\">").append(escapeHtml(result.getDetails())).append("</td>\n");
            sb.append("                    </tr>\n");
        }

        sb.append("                </tbody>\n");
        sb.append("            </table>\n");
        sb.append("        </section>\n");

        // ARIA 快照详情（如果有）
        boolean hasAriaResults = results.stream().anyMatch(NativeSnapshotResult::isAria);
        if (hasAriaResults) {
            sb.append("        <section class=\"aria-section\">\n");
            sb.append("            <h2>ARIA Snapshot Details</h2>\n");
            for (NativeSnapshotResult result : results) {
                if (result.isAria()) {
                    sb.append("            <div class=\"aria-card\">\n");
                    sb.append("                <h3>").append(escapeHtml(result.getName())).append("</h3>\n");
                    if (result.getExpected() != null) {
                        sb.append("                <div class=\"aria-snapshot\">\n");
                        sb.append("                    <pre>").append(escapeHtml(result.getExpected())).append("</pre>\n");
                        sb.append("                </div>\n");
                    }
                    if (!result.isPassed() && result.getActual() != null) {
                        sb.append("                <div class=\"aria-diff\">\n");
                        sb.append("                    <h4>Actual (different)</h4>\n");
                        sb.append("                    <pre>").append(escapeHtml(result.getActual())).append("</pre>\n");
                        sb.append("                </div>\n");
                    }
                    sb.append("            </div>\n");
                }
            }
            sb.append("        </section>\n");
        }

        sb.append("    </div>\n");
        sb.append("</body>\n");
        sb.append("</html>\n");

        return sb.toString();
    }

    private static String getCss() {
        return """
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
                background: #f5f7fa;
                color: #333;
                line-height: 1.6;
            }
            .container { max-width: 1200px; margin: 0 auto; padding: 20px; }
            header {
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                color: white;
                padding: 30px;
                border-radius: 10px;
                margin-bottom: 30px;
            }
            header h1 { font-size: 2em; margin-bottom: 10px; }
            .timestamp { opacity: 0.8; font-size: 0.9em; }
            section { background: white; border-radius: 10px; padding: 25px; margin-bottom: 20px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
            h2 { color: #667eea; margin-bottom: 20px; border-bottom: 2px solid #f0f0f0; padding-bottom: 10px; }
            .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 15px; margin-bottom: 20px; }
            .stat-card { background: #f8f9fa; padding: 20px; border-radius: 8px; text-align: center; }
            .stat-value { display: block; font-size: 2.5em; font-weight: bold; color: #667eea; }
            .stat-label { display: block; color: #666; font-size: 0.9em; }
            .stat-pass .stat-value { color: #28a745; }
            .stat-fail .stat-value { color: #dc3545; }
            .stat-error .stat-value { color: #ffc107; }
            .pass-rate { text-align: center; font-size: 1.2em; color: #666; }
            .pass-rate strong { color: #667eea; }
            .type-badges { display: flex; gap: 15px; }
            .type-badge { padding: 8px 16px; border-radius: 20px; font-weight: 500; }
            .type-visual { background: #e3f2fd; color: #1976d2; }
            .type-aria { background: #f3e5f5; color: #7b1fa2; }
            .chart-container { display: flex; justify-content: center; align-items: center; padding: 20px; }
            .pie-chart {
                width: 200px;
                height: 200px;
                border-radius: 50%;
                display: flex;
                justify-content: center;
                align-items: center;
                box-shadow: 0 4px 15px rgba(0,0,0,0.15);
            }
            .pie-legend { display: flex; justify-content: center; gap: 20px; margin-top: 15px; flex-wrap: wrap; }
            .legend-item { display: flex; align-items: center; gap: 8px; }
            .legend-color { width: 16px; height: 16px; border-radius: 4px; }
            .results-table { width: 100%; border-collapse: collapse; }
            .results-table th, .results-table td { padding: 12px; text-align: left; border-bottom: 1px solid #eee; }
            .results-table th { background: #f8f9fa; font-weight: 600; color: #666; }
            .results-table tr:hover { background: #f8f9fa; }
            .status-badge { padding: 4px 10px; border-radius: 12px; font-size: 0.85em; font-weight: 500; }
            .badge-pass { background: #d4edda; color: #155724; }
            .badge-fail { background: #f8d7da; color: #721c24; }
            .badge-error { background: #fff3cd; color: #856404; }
            .type-indicator { font-size: 0.8em; padding: 2px 8px; border-radius: 4px; }
            .name-cell { font-weight: 500; }
            .details-cell { font-size: 0.9em; color: #666; max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
            .row-pass { background: #f0fff4; }
            .row-fail { background: #fff5f5; }
            .row-error { background: #fffdf0; }
            .aria-card { background: #f8f9fa; padding: 20px; border-radius: 8px; margin-bottom: 15px; }
            .aria-card h3 { color: #7b1fa2; margin-bottom: 10px; }
            .aria-snapshot, .aria-diff { margin-top: 10px; }
            .aria-snapshot h4, .aria-diff h4 { font-size: 0.9em; color: #666; margin-bottom: 5px; }
            .aria-snapshot pre, .aria-diff pre {
                background: #2d2d2d;
                color: #f8f8f2;
                padding: 15px;
                border-radius: 6px;
                overflow-x: auto;
                font-size: 0.85em;
                line-height: 1.4;
            }
            .aria-diff pre { border-left: 4px solid #dc3545; }
            footer { text-align: center; padding: 20px; color: #666; font-size: 0.9em; }
            """;
    }

    private static String generatePieChart(NativeSnapshotResult.Stats stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("            <div class=\"chart-container\">\n");

        // 构建 conic-gradient
        double passedPct = stats.getTotal() > 0 ? (stats.getPassed() * 100.0 / stats.getTotal()) : 0;
        double failedPct = stats.getTotal() > 0 ? (stats.getFailed() * 100.0 / stats.getTotal()) : 0;
        double errorPct = stats.getTotal() > 0 ? (stats.getError() * 100.0 / stats.getTotal()) : 0;

        String gradient = "";
        if (passedPct > 0) {
            gradient += "#28a745 " + String.format("%.2f", passedPct) + "% " + String.format("%.2f", passedPct) + "%, ";
        }
        if (failedPct > 0) {
            gradient += "#dc3545 " + String.format("%.2f", passedPct) + "% " + String.format("%.2f", passedPct + failedPct) + "%, ";
        }
        if (errorPct > 0) {
            gradient += "#ffc107 " + String.format("%.2f", passedPct + failedPct) + "% " + String.format("%.2f", 100) + "%";
        }

        if (gradient.isEmpty()) {
            gradient = "#999 0% 100%";
        } else if (gradient.endsWith(", ")) {
            gradient = gradient.substring(0, gradient.length() - 2);
        }

        sb.append("                <div class=\"pie-chart\" style=\"background: conic-gradient(").append(gradient).append(");\">\n");
        sb.append("                    <span style=\"background: white; padding: 15px; border-radius: 50%; box-shadow: 0 2px 8px rgba(0,0,0,0.1);\">\n");
        sb.append("                        <strong>").append(String.format("%.0f%%", stats.getPassRate())).append("</strong>\n");
        sb.append("                    </span>\n");
        sb.append("                </div>\n");
        sb.append("            </div>\n");
        sb.append("            <div class=\"pie-legend\">\n");
        sb.append("                <div class=\"legend-item\"><div class=\"legend-color\" style=\"background: #28a745;\"></div> Passed (").append(stats.getPassed()).append(")</div>\n");
        sb.append("                <div class=\"legend-item\"><div class=\"legend-color\" style=\"background: #dc3545;\"></div> Failed (").append(stats.getFailed()).append(")</div>\n");
        sb.append("                <div class=\"legend-item\"><div class=\"legend-color\" style=\"background: #ffc107;\"></div> Error (").append(stats.getError()).append(")</div>\n");
        sb.append("            </div>\n");

        return sb.toString();
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
}
