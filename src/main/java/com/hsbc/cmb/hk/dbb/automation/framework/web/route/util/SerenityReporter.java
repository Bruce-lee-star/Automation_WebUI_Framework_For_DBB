package com.hsbc.cmb.hk.dbb.automation.framework.web.route.util;

import net.serenitybdd.core.Serenity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serenity 报告写入工具（主线程安全）
 *
 * <p>统一封装 Serenity.recordReportData() 调用，确保只在测试主线程写入报告。
 */
public final class SerenityReporter {

    private static final Logger logger = LoggerFactory.getLogger(SerenityReporter.class);

    private SerenityReporter() {}

    /**
     * 记录 API 操作到 Serenity 报告
     *
     * @param operation 操作类型（MONITOR / MOCK / MODIFY）
     * @param url       请求 URL
     * @param detail    详情内容
     */
    public static void recordApiOperation(String operation, String url, String detail) {
        try {
            String title = String.format("[API %s] %s", operation, url.length() > 80 ? url.substring(0, 80) + "..." : url);
            Serenity.recordReportData()
                    .withTitle(title)
                    .andContents(detail);
        } catch (Exception e) {
            logger.debug("Failed to record API operation to Serenity report: {}", e.getMessage());
        }
    }
}
