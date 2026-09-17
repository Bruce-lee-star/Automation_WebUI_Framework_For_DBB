package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * E-2：{@link TrendStore} 快照读写契约（目录隔离、最新优先、空历史）。
 */
class TrendStoreTest {

    @TempDir
    File folder;

    @Test
    void saveThenPrevious_roundTrips() {
        TrendStore store = new TrendStore(folder.toPath());

        store.save(new RunSummary("2026-09-01_10-00-00", "2026-09-01T10:00", 3, 2, 1,
                Map.of("a", 100L, "b", 200L), List.of("b")));

        RunSummary prev = store.previous();
        assertNotNull(prev, "保存后应能读回快照");
        assertEquals("2026-09-01_10-00-00", prev.buildId());
        assertEquals(3, prev.total());
        assertEquals(1, prev.failed());
        assertEquals(100L, prev.scenarioDurationMs().get("a"));
        assertEquals(List.of("b"), prev.failedScenarios());
    }

    @Test
    void previous_returnsNullWhenNoHistory() {
        assertNull(new TrendStore(folder.toPath()).previous(), "无历史应返回 null");
    }

    @Test
    void recent_returnsNewestFirst() {
        TrendStore store = new TrendStore(folder.toPath());
        store.save(new RunSummary("2026-01-01_00-00-00", "t1", 1, 1, 0, Map.of(), List.of()));
        store.save(new RunSummary("2026-02-01_00-00-00", "t2", 1, 1, 0, Map.of(), List.of()));

        List<RunSummary> recent = store.recent(2);

        assertEquals(2, recent.size());
        assertEquals("2026-02-01_00-00-00", recent.get(0).buildId(), "最新应排在最前");
    }
}
