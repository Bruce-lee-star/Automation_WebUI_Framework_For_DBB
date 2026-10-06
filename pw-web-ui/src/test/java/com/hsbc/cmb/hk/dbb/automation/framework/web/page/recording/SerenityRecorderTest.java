package com.hsbc.cmb.hk.dbb.automation.framework.web.page.recording;

import org.junit.Before;
import org.junit.Test;

import static com.hsbc.cmb.hk.dbb.automation.framework.web.JUnit4Assertions.assertDoesNotThrow;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 0 专属 UT：验证 {@link SerenityRecorder} 从原 {@code SerenityBasePage} 旧类（已删除）行为逐字迁移。
 * 集成层（verbose 存储 + Serenity 报告写入）由全护盾（600+ 用例）覆盖。
 */
public class SerenityRecorderTest {

    private SerenityRecorder recorder;

    @Before
    public void setUp() {
        recorder = new SerenityRecorder();
    }

    @Test
    public void record_runsOperation() {
        boolean[] ran = {false};
        recorder.record("test-action", "detail", () -> ran[0] = true);
        assertTrue("operation must be executed by record()", ran[0]);
    }

    @Test
    public void recordAndReturn_returnsResult() {
        String result = recorder.recordAndReturn("test-action", "detail", () -> "ok");
        assertEquals("ok", result);
    }

    @Test
    public void recordAndReturn_nullDetailFallsBackToResult() {
        Integer result = recorder.recordAndReturn("test-action", null, () -> 42);
        assertEquals(Integer.valueOf(42), result);
    }

    @Test
    public void recordVerification_doesNotThrow() {
        assertDoesNotThrow(() -> {
            recorder.recordVerification("v-pass", true);
            recorder.recordVerification("v-fail", false);
        });
    }

    @Test
    public void dataAccessors_safeWhenEmpty() {
        assertNotNull(recorder.getSerenityTestDataMap());
        assertNull(recorder.getSerenityTestData("missing"));
        assertDoesNotThrow(recorder::clearSerenityTestData);
    }
}
