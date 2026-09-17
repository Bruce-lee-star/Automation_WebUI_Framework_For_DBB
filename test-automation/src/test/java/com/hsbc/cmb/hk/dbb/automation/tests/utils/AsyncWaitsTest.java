package com.hsbc.cmb.hk.dbb.automation.tests.utils;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-4：{@link AsyncWaits} 有界轮询原语的行为契约（成功即返回 / 超时有界返回 / 取值语义）。
 */
class AsyncWaitsTest {

    @Test
    void awaitTrue_returnsTrueWhenConditionEventuallyHolds() {
        AtomicInteger calls = new AtomicInteger();

        boolean ok = AsyncWaits.awaitTrue(Duration.ofMillis(500), Duration.ofMillis(5),
                () -> calls.incrementAndGet() >= 3);

        assertTrue(ok, "条件最终成立应返回 true");
        assertTrue(calls.get() >= 3, "应至少轮询至条件成立");
    }

    @Test
    void awaitTrue_returnsFalseOnTimeout_andRespectsTimeoutBound() {
        long start = System.nanoTime();

        boolean ok = AsyncWaits.awaitTrue(AsyncWaits.ms(80), AsyncWaits.ms(10), () -> false);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertFalse(ok, "条件始终不成立应返回 false");
        assertTrue(elapsedMs >= 80, "应等待至超时上界，实际 " + elapsedMs + "ms");
    }

    @Test
    void awaitResult_returnsFirstNonNullValue() {
        AtomicInteger calls = new AtomicInteger();

        String value = AsyncWaits.awaitResult(AsyncWaits.ms(500), AsyncWaits.ms(5),
                () -> calls.incrementAndGet() >= 2 ? "done" : null);

        assertEquals("done", value);
    }

    @Test
    void awaitResult_returnsNullOnTimeout() {
        String value = AsyncWaits.awaitResult(AsyncWaits.ms(60), AsyncWaits.ms(10), () -> null);

        assertNull(value, "超时仍无值应返回 null");
    }
}
