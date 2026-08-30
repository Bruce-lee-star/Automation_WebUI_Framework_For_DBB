package com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;

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

        int ownerCount = byOwner.size();
        LoggingConfigUtil.logInfoIfVerbose(LOGGER,
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
            LOGGER.warn("[ApiMonitor] 写出 {} 失败：{}", JSON_REPORT, e.getMessage());
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
                        sb.append("  - **URL**：").append(nullToDash(call.getRequestUrl())).append("\n");
                        sb.append("  - **失败原因**：").append(nullToDash(call.getReason())).append("\n");
                        sb.append("  - **触发 Scenario**：").append(String.join(", ", call.getScenarios())).append("\n");
                        sb.append("  - **Request Body**：\n```\n").append(nullToDash(call.getRequestBody())).append("\n```\n");
                        sb.append("  - **Response Body**：\n```\n").append(nullToDash(call.getResponseBody())).append("\n```\n");
                        sb.append("\n");
                    }
                }
            }
            Path path = Paths.get(MD_REPORT);
            Files.createDirectories(path.getParent());
            Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
            // ⭐ 修复 S4：该报告含 URL / 请求响应体等业务数据，收紧为仅属主可读写（600），
            //    避免多用户 CI 节点上被同机其它账号读取。非 POSIX 文件系统静默跳过。
            restrictToOwnerOnly(path);
        } catch (IOException e) {
            LOGGER.warn("[ApiMonitor] 写出 {} 失败：{}", MD_REPORT, e.getMessage());
        }
    }

    /** ⭐ 修复 S4：best-effort 收紧为「仅属主可读写」；非 POSIX 文件系统（Windows）静默忽略。 */
    private static void restrictToOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统：不支持，忽略
        } catch (Exception e) {
            LOGGER.debug("[ApiMonitor] Could not restrict permissions on '{}': {}", path, e.getMessage());
        }
    }

    private static String nullToDash(String s) {
        return (s == null || s.isEmpty()) ? "-" : s;
    }
}
