package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * trace 文件命名与 sanitize 守卫（方案 A：每个用例一个 trace 文件）。
 *
 * <p>命名必须同时携带 <b>scenarioId + 起止时间 + 结果</b>：原实现只有"结束时间戳"，既无法按时间窗定位，
 * 也无法在报告里说明"这段 trace 覆盖哪段时间"；起止时间落进文件名后，"文件名即证据区间"。
 */
class ScenarioTraceNamingTest {

    @Test
    void nameCarriesScenarioIdWindowAndOutcome() {
        String name = ScenarioTraceRecorder.traceFileName("login_1_3", 1000L, 2500L, "FAIL");
        assertEquals("trace-login_1_3-1000-2500-FAIL.zip", name);
        assertTrue(name.startsWith("trace-"), "trace 文件应带统一前缀，便于保留治理识别");
        assertTrue(name.endsWith(".zip"));
    }

    @Test
    void onCloseFallbackOutcomeIsDistinguished() {
        String name = ScenarioTraceRecorder.traceFileName("scn", 5L, 9L, "ONCLOSE");
        assertTrue(name.contains("-ONCLOSE.zip"),
                "兜底导出（用例未走到收尾）必须与正常导出可区分，避免误读为完整用例证据");
    }

    @Test
    void unknownOutcomeWhenResultNotAvailable() {
        String name = ScenarioTraceRecorder.traceFileName("scn", 1L, 2L, null);
        assertTrue(name.contains("-UNKNOWN.zip"));
    }

    @Test
    void unsafeCharsAreSanitizedAndNullBecomesUnknown() {
        String name = ScenarioTraceRecorder.traceFileName("a/b:c d\\e", 1L, 2L, "PASS");
        assertFalse(name.contains("/") || name.contains(":") || name.contains("\\") || name.contains(" "),
                "文件名不得含路径分隔符/冒号/空格：" + name);
        assertEquals("trace-a_b_c_d_e-1-2-PASS.zip", name);

        assertEquals("unknown", ScenarioTraceRecorder.sanitizeForFileName(null));
        assertEquals("unknown", ScenarioTraceRecorder.sanitizeForFileName(""));
        // 全非法字符的输入会被逐字符替换为下划线（仍是合法文件名片段），而非回退为 unknown
        assertEquals("___", ScenarioTraceRecorder.sanitizeForFileName("///"));
    }

    @Test
    void nameFragmentIsTruncated() {
        String longId = "x".repeat(500);
        String fragment = ScenarioTraceRecorder.sanitizeForFileName(longId);
        assertEquals(120, fragment.length(), "片段应截断至 120 字符（含时间戳后仍不超文件系统上限）");
    }
}
