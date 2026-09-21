package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.MonitorFailureReportData;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.CapturedApiCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MonitorFailureReportWriterSink#collectData()} 验证：经 SPI 把归集器与数据丢失汇总
 * 收敛为跨模块安全的 {@link MonitorFailureReportData}，供 HTML 报告显示区域渲染。
 */
class MonitorFailureReportWriterSinkTest {

    @AfterEach
    void tearDown() {
        //  复位进程级单例，避免污染同 JVM 其余测试（与 Suite 级 clear 同语义）
        MonitorFailureCollector.getInstance().clear();
        MonitorDataLossReporter.instance().reset();
    }

    @Test
    void collectDataReturnsEmptyWhenNoFailures() {
        MonitorFailureReportData data = new MonitorFailureReportWriterSink().collectData();
        assertFalse(data.hasContent());
        assertEquals(0, data.getOwnerCount());
        assertEquals(0, data.getFailureCount());
        assertEquals(0L, data.getTotalDataLoss());
    }

    @Test
    void collectDataAggregatesFailuresByOwnerAndDataLoss() {
        CapturedApiCall call = new CapturedApiCall.Builder()
                .endpoint("/api/v1/transfer")
                .method("POST")
                .statusCode(500)
                .requestUrl("https://api.example.com/api/v1/transfer")
                .requestBody("{\"amt\":100}")
                .responseBody("{\"error\":\"boom\"}")
                .responseHeaders(Collections.emptyMap())
                .requestHeaders(Collections.emptyMap())
                .timestamp(123L)
                .build();

        MonitorFailureCollector.getInstance().record(
                call, "/api/v1/transfer", "team-a@hsbc.com", "status=500 expected=200");
        MonitorDataLossReporter.instance().recordLoss("route_monitor_record", 3L);

        MonitorFailureReportData data = new MonitorFailureReportWriterSink().collectData();
        assertTrue(data.hasContent());
        assertEquals(1, data.getOwnerCount());
        assertEquals(1, data.getFailureCount());
        assertEquals(3L, data.getTotalDataLoss());
        assertEquals(1, data.getOwners().size());
        assertEquals("team-a@hsbc.com", data.getOwners().get(0).getOwner());
        assertEquals(1, data.getOwners().get(0).getItems().size());
        assertEquals("/api/v1/transfer", data.getOwners().get(0).getItems().get(0).getPattern());
        assertEquals("500", data.getOwners().get(0).getItems().get(0).getStatus());

        Map<String, Long> loss = data.getDataLossByCategory();
        assertTrue(loss.containsKey("route_monitor_record"));
        assertEquals(3L, loss.get("route_monitor_record").longValue());
    }
}
