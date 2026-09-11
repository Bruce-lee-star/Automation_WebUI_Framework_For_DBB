package com.hsbc.cmb.hk.dbb.automation.framework.common.assertion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * D3-2 硬断言门面契约测试。
 *
 * <p>重点验证两件事：
 * <ol>
 *   <li><b>通过时不抛、失败时抛</b> {@link FrameworkAssertionError}；</li>
 *   <li>{@link FrameworkAssertionError} <b>必须是 AssertionError</b> ——
 *       这是 JUnit / Cucumber / Serenity 能把失败识别为"断言失败"而非"错误"的前提；</li>
 *   <li>失败信息<b>同时包含 expected 与 actual</b>，且入参为 null 时不抛 NPE。</li>
 * </ol>
 */
public class FrameworkAssertionsTest {

    // ── 通过路径：不抛异常 ──

    @Test
    public void passingAssertionsDoNotThrow() {
        FrameworkAssertions.assertEquals("a", "a", "same");
        FrameworkAssertions.assertNotEquals("a", "b", "different");
        FrameworkAssertions.assertNotNull("non-null", "x");
        FrameworkAssertions.assertTrue(true, "true");
        FrameworkAssertions.assertFalse(false, "false");
        FrameworkAssertions.assertContains("hello world", "world", "contains");
        FrameworkAssertions.assertStatusCode(200, 200, "status");
    }

    // ── 失败路径：抛 FrameworkAssertionError ──

    @Test
    public void assertEqualsThrowsWithExpectedAndActual() {
        try {
            FrameworkAssertions.assertEquals(200, 404, "status code");
            fail("应抛出 FrameworkAssertionError");
        } catch (FrameworkAssertionError e) {
            assertTrue(e.getMessage().contains("200"), "失败信息应含 expected");
            assertTrue(e.getMessage().contains("404"), "失败信息应含 actual");
            assertTrue(e.getMessage().contains("status code"), "失败信息应含自定义 message");
        }
    }

    @Test
    public void assertNotNullThrowsOnNull() {
        try {
            FrameworkAssertions.assertNotNull(null, "token must exist");
            fail("应抛出 FrameworkAssertionError");
        } catch (FrameworkAssertionError e) {
            assertTrue(e.getMessage().contains("token must exist"));
        }
    }

    @Test
    public void assertContainsIsNullSafe() {
        // null 入参不得抛 NPE，必须统一转为断言失败
        try {
            FrameworkAssertions.assertContains(null, "x", "null haystack");
            fail("应抛出 FrameworkAssertionError");
        } catch (FrameworkAssertionError e) {
            assertTrue(e.getMessage().contains("null haystack"));
        }

        try {
            FrameworkAssertions.assertContains("body", null, "null needle");
            fail("应抛出 FrameworkAssertionError");
        } catch (FrameworkAssertionError e) {
            assertTrue(e.getMessage().contains("null needle"));
        }
    }

    @Test
    public void assertStatusCodeMismatchThrows() {
        try {
            FrameworkAssertions.assertStatusCode(200, 500, "login");
            fail("应抛出 FrameworkAssertionError");
        } catch (FrameworkAssertionError e) {
            assertTrue(e.getMessage().contains("login"));
        }
    }

    @Test
    public void failAlwaysThrows() {
        try {
            FrameworkAssertions.fail("boom");
            fail("应抛出 FrameworkAssertionError");
        } catch (FrameworkAssertionError e) {
            assertEquals("boom", e.getMessage());
        }
    }

    // ── 类型契约 ──

    @Test
    public void errorTypeIsAssertionError() {
        // 关键契约：必须是 AssertionError，否则测试引擎会把它归类为 error 而非 failure
        try {
            FrameworkAssertions.assertTrue(false, "x");
            fail("应抛出");
        } catch (Throwable t) {
            assertTrue(t instanceof AssertionError, "FrameworkAssertionError 必须是 AssertionError 子类");
            assertTrue(t instanceof FrameworkAssertionError);
        }
    }

    @Test
    public void nullMessageFallsBackToDefault() {
        try {
            FrameworkAssertions.assertTrue(false, null);
            fail("应抛出");
        } catch (FrameworkAssertionError e) {
            assertFalse(e.getMessage().contains("null"), "message 为 null 时应回落默认文案，不应出现 'null'");
        }
    }
}
