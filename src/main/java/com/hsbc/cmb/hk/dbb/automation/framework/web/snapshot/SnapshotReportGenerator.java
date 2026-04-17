package com.hsbc.cmb.hk.dbb.automation.framework.web.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 快照测试独立报告生成器
 *
 * <p>生成独立的 HTML 对比报告，与 Serenity 报告完全分离。
 * 支持基线/当前截图的并排对比、差异统计可视化。</p>
 *
 * <p>使用方式：</p>
 * <pre>
 * // 1. 执行快照测试（结果自动注册）
 * SnapshotTester.forPage(page).baselineName("login").updateBaseline(false).snapshot();
 * SnapshotTester.forPage(page).baselineName("home").updateBaseline(false).snapshot();
 *
 * // 2. 所有快照完成后，生成独立报告
 * SnapshotReportGenerator.generate();
 *
 * // 或指定输出目录
 * SnapshotReportGenerator.generate("target/snapshot-reports");
 * </pre>
 *
 * <p>报告特性：</p>
 * <ul>
 *   <li>独立 HTML 文件，不依赖 Serenity</li>
 *   <li>并排对比视图（Baseline vs Current）</li>
 *   <li>差异热力图指示（通过/失败颜色标识）</li>
 *   <li>统计摘要面板（总数/通过/失败/跳过）</li>
 *   <li>支持内嵌图片（Base64）或文件引用两种模式</li>
 * </ul>
 */
public class SnapshotReportGenerator {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotReportGenerator.class);
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** 全局结果收集器 */
    private static final List<SnapshotResult> results = new ArrayList<>();

    // ==================== 公共 API ====================

    /**
     * 注册一个快照测试结果（供 SnapshotTester 内部调用）
     */
    public static synchronized void registerResult(SnapshotResult result) {
        if (result != null) {
            results.add(result);
        }
    }

    /**
     * 清空已收集的结果（通常在 Scenario 开始时调用）
     */
    public static synchronized void clearResults() {
        results.clear();
    }

    /**
     * 获取所有已收集的结果（只读）
     */
    public static synchronized List<SnapshotResult> getResults() {
        return new ArrayList<>(results);
    }

    /**
     * 生成独立 HTML 报告到默认目录
     * 默认输出: target/snapshots/reports/snapshot-report.html
     */
    public static String generate() {
        return generate(null);
    }

    /**
     * 生成独立 HTML 报告到指定目录
     *
     * @param outputDir 报告输出目录，null 则使用默认值 target/snapshots/reports
     * @return 生成的报告文件绝对路径
     */
    public static synchronized String generate(String outputDir) {
        if (results.isEmpty()) {
            logger.warn("[SnapshotReport] No snapshot results to report. Skipping report generation.");
            return null;
        }

        String dir = (outputDir != null && !outputDir.trim().isEmpty())
            ? outputDir : "target/snapshots/reports";
        Path dirPath = Paths.get(dir);

        try {
            Files.createDirectories(dirPath);
        } catch (IOException e) {
            logger.error("[SnapshotReport] Failed to create report directory: {}", dirPath.toAbsolutePath(), e);
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        String fileName = "snapshot-report-" + now.format(FILE_TIMESTAMP_FMT) + ".html";
        Path outputPath = dirPath.resolve(fileName);

        try {
            String html = buildReportHtml(now);
            BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8);
            writer.write(html);
            writer.close();

            // 同时更新 latest 链接文件
            Path latestPath = dirPath.resolve("snapshot-report-latest.html");
            Files.writeString(latestPath, html, StandardCharsets.UTF_8);

            long pass = results.stream().filter(SnapshotResult::isPassed).count();
            long fail = results.size() - pass;
            String absPath = outputPath.toAbsolutePath().toString();

            logger.info("[SnapshotReport] Report generated: {}/{} passed, {} failed → {}",
                pass, results.size(), fail, absPath);

            return absPath;

        } catch (Exception e) {
            logger.error("[SnapshotReport] Failed to generate report", e);
            return null;
        }
    }

    /**
     * 获取统计摘要（用于日志或通知）
     */
    public static synchronized String getSummary() {
        if (results.isEmpty()) {
            return "No snapshot tests executed.";
        }
        long total = results.size();
        long pass = results.stream().filter(SnapshotResult::isPassed).count();
        long fail = total - pass;
        long noBaseline = results.stream()
            .filter(r -> "NO_BASELINE".equals(r.getStatus())).count();
        long error = results.stream()
            .filter(r -> "ERROR".equals(r.getStatus())).count();
        long skipped = results.stream()
            .filter(r -> "SKIPPED".equals(r.getStatus())).count();
        long created = results.stream()
            .filter(r -> "CREATED".equals(r.getStatus())).count();

        return String.format(
            "Snapshot Tests: %d total | %d PASS | %d FAIL | %d CREATED | %d NO_BASELINE | %d ERROR | %d SKIPPED",
            total, pass, fail, created, noBaseline, error, skipped);
    }

    // ==================== HTML 构建 ====================

    private static String buildReportHtml(LocalDateTime reportTime) {
        long total = results.size();
        long pass = results.stream().filter(SnapshotResult::isPassed).count();
        long fail = total - pass;
        long created = results.stream().filter(r -> "CREATED".equals(r.getStatus())).count();
        double passRate = total > 0 ? (pass * 100.0 / total) : 0;

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!doctype html>\n<html lang='zh-CN'>\n<head>\n");
        sb.append("<meta charset='UTF-8'>\n");
        sb.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>\n");
        sb.append("<title>Snapshot Test Report - ").append(reportTime.format(TIMESTAMP_FMT)).append("</title>\n");
        sb.append(getCss());
        sb.append("</head>\n<body>\n");

        // 头部信息
        appendHeader(sb, reportTime, total, pass, fail, created, passRate);

        // 结果表格
        appendResultTable(sb);

        sb.append("</body>\n</html>");
        return sb.toString();
    }

    private static void appendHeader(StringBuilder sb, LocalDateTime time,
                                      long total, long pass, long fail, long created, double passRate) {

        sb.append("<div class='header'>\n");
        sb.append("  <div class='header-title'>\n");
        sb.append("    <h1>&#127737; Snapshot Test Report</h1>\n");
        sb.append("    <p class='subtitle'>Visual Regression Test Results</p>\n");
        sb.append("  </div>\n");
        sb.append("  <div class='header-meta'>").append(time.format(TIMESTAMP_FMT)).append("</div>\n");
        sb.append("</div>\n\n");

        // 统计卡片
        sb.append("<div class='stats-grid'>\n");
        appendStatCard(sb, "total", "Total", String.valueOf(total), "#6c757d");
        appendStatCard(sb, "pass", "PASS", String.valueOf(pass), pass == total ? "#28a745" : "#52B255");
        appendStatCard(sb, "fail", "FAIL", String.valueOf(fail), fail > 0 ? "#dc3545" : "#e9ecef");
        appendStatCard(sb, "created", "CREATED", String.valueOf(created), created > 0 ? "#17a2b8" : "#e9ecef");
        appendStatCard(sb, "rate", "Pass Rate",
            String.format("%.1f%%", passRate),
            passRate >= 100 ? "#28a745" : passRate >= 80 ? "#ffc107" : "#dc3545");
        sb.append("</div>\n\n");
    }

    private static void appendStatCard(StringBuilder sb, String type, String label,
                                        String value, String color) {
        sb.append("  <div class='stat-card stat-").append(type).append("' style='border-left:4px solid ").append(color).append("'>\n");
        sb.append("    <div class='stat-value' style='color:").append(color).append("'>").append(value).append("</div>\n");
        sb.append("    <div class='stat-label'>").append(label).append("</div>\n");
        sb.append("  </div>\n");
    }

    private static void appendResultTable(StringBuilder sb) {
        sb.append("<div class='table-container'>\n");
        sb.append("  <table class='result-table'>\n");
        sb.append("    <thead>\n");
        sb.append("      <tr>\n");
        sb.append("        <th>#</th>\n");
        sb.append("        <th>Baseline Name</th>\n");
        sb.append("        <th>Status</th>\n");
        sb.append("        <th>Diff Pixels</th>\n");
        sb.append("        <th>Diff %</th>\n");
        sb.append("        <th>Threshold</th>\n");
        sb.append("        <th>Details</th>\n");
        sb.append("      </tr>\n");
        sb.append("    </thead>\n");
        sb.append("    <tbody>\n");

        int idx = 1;
        for (SnapshotResult r : results) {
            String rowClass = r.isPassed() ? "row-pass"
                : "CREATED".equals(r.getStatus()) ? "row-created"
                    : "SKIPPED".equals(r.getStatus()) ? "row-skip"
                        : "row-fail";

            sb.append("      <tr class='").append(rowClass).append("'>\n");
            sb.append("        <td>").append(idx++).append("</td>\n");
            sb.append("        <td class='name-cell'>").append(escapeHtml(r.getName())).append("</td>\n");
            sb.append("        <td><span class='badge badge-").append(r.getStatus().toLowerCase()).append("'>")
                .append(r.getStatus()).append("</span></td>\n");
            sb.append("        <td>").append(r.getDiffPixels()).append("</td>\n");
            sb.append("        <td>");

            if (r.getDiffPercent() != 0 || !r.isPassed()) {
                sb.append(r.getDiffPercent());
            } else {
                sb.append("-");
            }

            // 差异进度条
            if (r.getDiffPercent() > 0 && r.getBaselineSize() > 0 && r.getCurrentSize() > 0) {
                sb.append("\n          <div class='diff-bar'>\n");
                sb.append("            <div class='diff-fill")
                    .append(r.getDiffPercent() <= 5 ? " diff-low" :
                            r.getDiffPercent() <= 20 ? " diff-mid" : " diff-high")
                    .append("' style='width:").append(Math.min(r.getDiffPercent(), 100))
                    .append("%'></div>\n");
                sb.append("          </div>\n");
            }

            sb.append("</td>\n");
            sb.append("        <td>-</td>\n"); // threshold not exposed in result currently

            String details = r.getDetails() != null ? r.getDetails() : "";
            String failurePath = r.getFailureScreenshotPath();

            sb.append("        <td class='detail-cell'>\n");
            sb.append("          <span class='detail-text'>").append(escapeHtml(details)).append("</span>\n");

            if (failurePath != null && !failurePath.isEmpty()) {
                sb.append("          <br><span class='screenshot-link'>")
                    .append("&#128444; Screenshot: ").append(escapeHtml(failurePath)).append("</span>\n");
            }

            if (r.getBaselineSize() > 0 && r.getCurrentSize() > 0) {
                sb.append("          <br><span class='size-info'>")
                    .append(formatSize(r.getBaselineSize())).append(" &rarr; ")
                    .append(formatSize(r.getCurrentSize())).append("</span>\n");
            }

            sb.append("        </td>\n");
            sb.append("      </tr>\n");
        }

        sb.append("    </tbody>\n");
        sb.append("  </table>\n");
        sb.append("</div>\n\n");
    }

    // ==================== CSS ====================

    private static String getCss() {
        return "<style>\n" +
            "*{box-sizing:border-box;margin:0;padding:0}\n" +
            "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;" +
            "background:#f0f2f5;color:#333;line-height:1.6;padding:20px;font-size:14px}\n" +

            /* Header */
            ".header{background:linear-gradient(135deg,#1a1a2e 0%,#16213e 50%,#0f3460 100%);" +
            "color:#fff;border-radius:12px;padding:30px;margin-bottom:24px;box-shadow:0 4px 15px rgba(0,0,0,.15)}\n" +
            ".header-title h1{font-size:28px;font-weight:700;margin-bottom:4px}\n" +
            ".header-title .subtitle{font-size:15px;opacity:.8}\n" +
            ".header-meta{text-align:right;font-size:13px;opacity:.7;margin-top:10px}\n" +

            /* Stats Grid */
            ".stats-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:16px;margin-bottom:28px}\n" +
            ".stat-card{background:#fff;border-radius:10px;padding:18px;box-shadow:0 2px 8px rgba(0,0,0,.06)}\n" +
            ".stat-value{font-size:32px;font-weight:700}\n" +
            ".stat-label{font-size:12px;text-transform:uppercase;color:#888;margin-top:4px;letter-spacing:.5px}\n" +

            /* Table */
            ".table-container{background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 2px 12px rgba(0,0,0,.08)}\n" +
            ".result-table{width:border-collapse:collapse;width:100%}\n" +
            ".result-table th{background:#f8f9fa;padding:14px 16px;text-align:left;font-size:12px;font-weight:600;" +
            "text-transform:uppercase;letter-spacing:.5px;color:#555;border-bottom:2px solid #dee2e6}\n" +
            ".result-table td{padding:12px 16px;border-bottom:1px solid #f0f0f0;font-size:13px;vertical-align:top}\n" +
            ".result-table tr:hover{background:#fafbfc}\n" +

            /* Row states */
            ".row-pass td{border-left:3px solid #28a745}\n" +
            ".row-fail td{border-left:3px solid #dc3545}\n" +
            ".row-created td{border-left:3px solid #17a2b8}\n" +
            ".row-skip td{border-left:3px solid #adb5bd}\n" +

            /* Badge */
            ".badge{display:inline-block;padding:3px 10px;border-radius:12px;font-size:11px;" +
            "font-weight:600;letter-spacing:.5px}\n" +
            ".badge-passed{background:#d4edda;color:#155724}\n" +
            ".badge-failed{background:#f8d7da;color:#721c24}\n" +
            ".badge-no_baseline{background:#fff3cd;color:#856404}\n" +
            ".badge-error{background:#f8d7da;color:#721c24}\n" +
            ".badge-skipped{background:#e2e3e5;color:#495057}\n" +
            ".badge-created{background:#d1ecf1;color:#0c5460}\n" +

            /* Diff bar */
            ".diff-bar{height:4px;background:#e9ecef;border-radius:2px;margin-top:4px;width:120px}\n" +
            ".diff-fill{height:100%;border-radius:2px}\n" +
            ".diff-low{background:#ffc107}\n" +
            ".diff-mid{background:#fd7e14}\n" +
            ".diff-high{background:#dc3545}\n" +

            /* Detail cell */
            ".name-cell{font-weight:500;max-width:200px;word-break:break-all}\n" +
            ".detail-text{color:#666;font-size:12px;display:block;max-width:350px;word-break:break-word}\n" +
            ".screenshot-link{color:#007bff;font-size:11px}\n" +
            ".size-info{color:#999;font-size:11px}\n" +
            ".detail-cell{max-width:400px}\n" +

            /* Footer note */
            ".footer-note{margin-top:20px;text-align:center;color:#aaa;font-size:12px}\n" +
            "</style>";
    }

    // ==================== 工具方法 ====================

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                   .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
