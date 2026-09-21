package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorDataLossReporterTest {

    @AfterEach
    void tearDown() {
        MonitorDataLossReporter.instance().reset();
    }

    @Test
    void recordLossAccumulatesTotalAndByCategory() {
        MonitorDataLossReporter r = MonitorDataLossReporter.instance();
        r.recordLoss("route_monitor_record", 3L);
        r.recordLoss("route_monitor_record", 2L);
        r.recordLoss("other", 1L);

        assertTrue(r.hasLoss());
        assertEquals(6L, r.totalLoss());
        Map<String, Long> byCategory = r.lossByCategory();
        assertEquals(2, byCategory.size());
        assertEquals(5L, byCategory.get("route_monitor_record"));
        assertEquals(1L, byCategory.get("other"));
    }

    @Test
    void recordLossWithNonPositiveCountIsNoOp() {
        MonitorDataLossReporter r = MonitorDataLossReporter.instance();
        r.recordLoss("c", 0L);
        r.recordLoss("c", -5L);
        assertFalse(r.hasLoss());
        assertEquals(0L, r.totalLoss());
    }

    @Test
    void resetClearsAllLoss() {
        MonitorDataLossReporter r = MonitorDataLossReporter.instance();
        r.recordLoss("c", 4L);
        assertTrue(r.hasLoss());
        r.reset();
        assertFalse(r.hasLoss());
        assertEquals(0L, r.totalLoss());
        assertTrue(r.lossByCategory().isEmpty());
    }
}
