package com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-10 截断元数据语义守卫（2026-09-17）。
 *
 * <p>落库前 {@code MonitorHandler} 会按「原始长度 > 已存 body 长度」回填 {@link CapturedApiCall#markBodyTruncated(long)}。
 * 本类把该方法的边界语义固化为断言，确保「未截断不误标 / 截断后带原始字节数」这一不变量不被破坏。
 */
class CapturedApiCallTruncationTest {

    private static CapturedApiCall callWithBody(String body) {
        return new CapturedApiCall("/api/x", "GET", null, 200, null, body,
                1L, null, null, RouteHandleType.MONITOR);
    }

    @Test
    void 默认未截断() {
        CapturedApiCall c = callWithBody("abcdef");
        assertFalse(c.bodyTruncated());
        assertEquals(0L, c.originalBodyBytes());
    }

    @Test
    void 原始长度大于已存时标记截断_并记录原始字节数() {
        CapturedApiCall c = callWithBody("abcdef"); // 6
        c.markBodyTruncated(100);
        assertTrue(c.bodyTruncated());
        assertEquals(100L, c.originalBodyBytes());
    }

    @Test
    void 原始长度等于已存时不标记_防误标() {
        CapturedApiCall c = callWithBody("abcdef"); // 6
        c.markBodyTruncated(6);
        assertFalse(c.bodyTruncated(), "等于已存长度视为未截断，避免把「恰好等于上限」误报为截断");
        assertEquals(0L, c.originalBodyBytes());
    }

    @Test
    void 原始长度小于已存时不标记() {
        CapturedApiCall c = callWithBody("abcdef");
        c.markBodyTruncated(3);
        assertFalse(c.bodyTruncated());
        assertEquals(0L, c.originalBodyBytes());
    }

    @Test
    void 负数原始长度不标记() {
        CapturedApiCall c = callWithBody("abcdef");
        c.markBodyTruncated(-1);
        assertFalse(c.bodyTruncated());
        assertEquals(0L, c.originalBodyBytes());
    }

    @Test
    void 空body被标记时原始字节数为正() {
        CapturedApiCall c = callWithBody(""); // 0
        c.markBodyTruncated(2048);
        assertTrue(c.bodyTruncated());
        assertEquals(2048L, c.originalBodyBytes());
    }
}
