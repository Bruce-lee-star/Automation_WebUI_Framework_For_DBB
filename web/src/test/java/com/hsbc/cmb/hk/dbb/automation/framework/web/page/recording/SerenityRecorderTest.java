package com.hsbc.cmb.hk.dbb.automation.framework.web.page.recording;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 专属 UT：验证 {@link SerenityRecorder} 从 {@code SerenityBasePage} 行为逐字迁移。
 * 集成层（verbose 存储 + Serenity 报告写入）由全护盾（600+ 用例）覆盖。
 */
class SerenityRecorderTest {

    private SerenityRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new SerenityRecorder();
    }

    @Test
    void record_runsOperation() {
        boolean[] ran = {false};
        recorder.record("test-action", "detail", () -> ran[0] = true);
        assertTrue(ran[0], "operation must be executed by record()");
    }

    @Test
    void recordAndReturn_returnsResult() {
        String result = recorder.recordAndReturn("test-action", "detail", () -> "ok");
        assertEquals("ok", result);
    }

    @Test
    void recordAndReturn_nullDetailFallsBackToResult() {
        Integer result = recorder.recordAndReturn("test-action", null, () -> 42);
        assertEquals(Integer.valueOf(42), result);
    }

    @Test
    void recordVerification_doesNotThrow() {
        assertDoesNotThrow(() -> {
            recorder.recordVerification("v-pass", true);
            recorder.recordVerification("v-fail", false);
        });
    }

    @Test
    void dataAccessors_safeWhenEmpty() {
        assertNotNull(recorder.getSerenityTestDataMap());
        assertNull(recorder.getSerenityTestData("missing"));
        assertDoesNotThrow(recorder::clearSerenityTestData);
    }
}
