package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.MonitorDataLossReporter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * API 监控失败报告写出器。
 *
 * <p>在整轮测试报告生成阶段调用，产出两份文件（供 CI 邮件插件读取投递）：
 * <ul>
 *   <li>{@code target/monitor-failures-by-owner.json} — 按 apiOwner 分组的失败清单（谁 API 发给谁）</li>
 *   <li>{@code target/monitor-failures-summary.md} — 人类可读摘要</li>
 * </ul>
 *
 * <p>本类不发送邮件，仅产出数据；邮件由 Jenkins {@code emailext} 等读取 JSON 循环发送。
 */
public class MonitorFailureReportWriter {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MonitorFailureReportWriter.class);

    public static final String JSON_REPORT = "target/monitor-failures-by-owner.json";
    public static final String MD_REPORT = "target/monitor-failures-summary.md";

    /**
     * 写出失败报告。无失败时仍写出空结构 JSON（便于 CI 判断是否跳过发送）。
     *
     * @return 失败 owner 数（>0 表示有失败需要通知）
     */
    public static int write() {
        MonitorFailureCollector collector = MonitorFailureCollector.getInstance();
        Map<String, List<MonitorFailureCollector.FailedApiCall>> byOwner = collector.getFailuresByOwner();

        writeJson(byOwner);
        writeMarkdown(byOwner);

        // R-4：写库失败/丢弃在报告尾部以红色提示呈现（数据丢失对测试运行可见）
        MonitorDataLossReporter loss = MonitorDataLossReporter.instance();
        if (loss.hasLoss()) {
            LOGGER.error("[ApiMonitor] ⚠ 数据完整性告警：本轮监控记录入库失败/丢弃共 {} 条，详见汇总报告尾部。",
                    loss.totalLoss());
        }

        int ownerCount = byOwner.size();
        VerboseLogging.logInfoIfVerbose(LOGGER,
                "[ApiMonitor] 失败报告已写出：owner 数={}, 去重后失败数={}",
                ownerCount, collector.getFailureCount());
        return ownerCount;
    }

    private static void writeJson(Map<String, List<MonitorFailureCollector.FailedApiCall>> byOwner) {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            Path path = Paths.get(JSON_REPORT);
            Files.createDirectories(path.getParent());
            Files.write(path, gson.toJson(byOwner).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.warn("[ApiMonitor] Failed to write {}: {}", JSON_REPORT, e.getMessage());
        }
    }

    private static void writeMarkdown(Map<String, List<MonitorFailureCollector.FailedApiCall>> byOwner) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# API 监控失败汇总\n\n");
            if (byOwner.isEmpty()) {
                sb.append("✅ 本轮无 API 监控失败。\n");
            } else {
                for (Map.Entry<String, List<MonitorFailureCollector.FailedApiCall>> entry : byOwner.entrySet()) {
                    sb.append("## 收件人（API Owner）：").append(entry.getKey()).append("\n\n");
                    for (MonitorFailureCollector.FailedApiCall call : entry.getValue()) {
                        sb.append("- **功能**：").append(nullToDash(call.getFeature())).append("\n");
                        sb.append("  - **Endpoint**：`").append(call.getPattern()).append("`\n");
                        sb.append("  - **状态**：").append(call.getStatus())
                                .append("  **方法**：").append(nullToDash(call.getMethod())).append("\n");
                        sb.append("  - **URL**：").append(nullToDash(SensitiveDataSanitizer.sanitizeUrl(call.getRequestUrl()))).append("\n");
                        sb.append("  - **失败原因**：").append(nullToDash(call.getReason())).append("\n");
                        sb.append("  - **触发 Scenario**：").append(String.join(", ", call.getScenarios())).append("\n");
                        sb.append("  - **Request Body**：\n```\n").append(nullToDash(call.getRequestBody())).append("\n```\n");
                        sb.append("  - **Response Body**：\n```\n").append(nullToDash(call.getResponseBody())).append("\n```\n");
                        sb.append("\n");
                    }
                }
            }
            // R-4：报告尾部红色提示——数据完整性告警（使监控数据丢失可见，比保证不丢失更现实）
            MonitorDataLossReporter loss = MonitorDataLossReporter.instance();
            if (loss.hasLoss()) {
                sb.append("\n## ⚠ 数据完整性告警（API 监控数据丢失）\n\n");
                sb.append("**本轮 API 监控记录入库失败 / 丢弃共 ").append(loss.totalLoss()).append(" 条**，")
                        .append("部分监控数据未落库（数据完整性受损）。\n\n");
                sb.append("按类别统计：\n");
                for (Map.Entry<String, Long> entry : loss.lossByCategory().entrySet()) {
                    sb.append("- `").append(entry.getKey()).append("`：").append(entry.getValue()).append(" 条\n");
                }
                sb.append("\n排查建议：检查 `monitor.db.*` 配置、数据库可用性、网络连接，")
                        .append("以及 Hikari 连接池与批量刷入器日志。\n");
            }

            Path path = Paths.get(MD_REPORT);
            Files.createDirectories(path.getParent());
            Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
            //  修复 S4：该报告含 URL / 请求响应体等业务数据，收紧为仅属主可读写（600），
            //    避免多用户 CI 节点上被同机其它账号读取。非 POSIX 文件系统静默跳过。
            restrictToOwnerOnly(path);
        } catch (IOException e) {
            LOGGER.warn("[ApiMonitor] Failed to write {}: {}", MD_REPORT, e.getMessage());
        }
    }

    /**  修复 S4：best-effort 收紧为「仅属主可读写」；非 POSIX 文件系统（Windows）静默忽略。 */
    private static void restrictToOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统（如 Windows）：本就不支持该权限模型，属预期分支，但不得静默（D7-3）
            LOGGER.debug("[ApiMonitor] POSIX permission model unsupported on this filesystem, "
                    + "skip restrictToOwnerOnly: {}", e.toString());
        } catch (Exception e) {
            LOGGER.debug("[ApiMonitor] Could not restrict permissions on '{}': {}", path, e.getMessage());
        }
    }

    private static String nullToDash(String s) {
        return (s == null || s.isEmpty()) ? "-" : s;
    }
}
