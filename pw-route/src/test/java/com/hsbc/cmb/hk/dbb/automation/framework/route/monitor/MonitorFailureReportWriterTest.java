package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorFailureReportWriterTest {

    private static final Path MD = Path.of(MonitorFailureReportWriter.MD_REPORT);

    @AfterEach
    void tearDown() {
        MonitorDataLossReporter.instance().reset();
    }

    @Test
    void writeIncludesDataLossRedBannerAtTailWhenLossOccurred() throws Exception {
        MonitorDataLossReporter.instance().recordLoss("route_monitor_record", 7L);

        MonitorFailureReportWriter.write();

        assertTrue(Files.exists(MD), "summary md should be written");
        String content = Files.readString(MD, StandardCharsets.UTF_8);
        assertTrue(content.contains("数据完整性告警"), "报告尾部应包含数据完整性告警横幅");
        assertTrue(content.contains("route_monitor_record"), "报告应列出丢失类别");
        assertTrue(content.contains("7"), "报告应展示丢失条数");
    }

    @Test
    void writeOmitsDataLossBannerWhenNoLoss() throws Exception {
        MonitorDataLossReporter.instance().reset();

        MonitorFailureReportWriter.write();

        String content = Files.readString(MD, StandardCharsets.UTF_8);
        assertFalse(content.contains("数据完整性告警"), "无丢失时不应出现告警横幅");
    }
}
