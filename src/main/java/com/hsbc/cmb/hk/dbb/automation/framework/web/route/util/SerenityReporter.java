package com.hsbc.cmb.hk.dbb.automation.framework.web.route.util;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import net.serenitybdd.core.Serenity;
import net.thucydides.core.steps.StepEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serenity 报告写入工具（主线程安全）
 *
 * <p>统一封装 Serenity.recordReportData() 调用，确保只在测试主线程写入报告。
 * 非 Serenity 环境（如纯 JUnit 测试）自动静默降级。
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
            // isBaseStepListenerRegistered() 静默检查，不会像 getBaseStepListener() 那样
            // 在 listener 为 null 时打印 ERROR + dump Stack
            if (!StepEventBus.getEventBus().isBaseStepListenerRegistered()) {
                LoggingConfigUtil.logTraceIfVerbose(logger,
                        "[SerenityReporter] recordApiOperation SKIP: no listener registered for {} {}", operation, url);
                return;
            }
            String title = String.format("[API %s] %s", operation,
                    url.length() > 80 ? url.substring(0, 80) + "..." : url);
            Serenity.recordReportData()
                    .withTitle(title)
                    .andContents(detail);
            LoggingConfigUtil.logTraceIfVerbose(logger,
                    "[SerenityReporter] recordApiOperation OK: {} {}", operation, url);
        } catch (Exception e) {
            logger.debug("[SerenityReporter] Failed to record API operation: {}", e.getMessage());
        }
    }
}
