package com.hsbc.cmb.hk.dbb.automation.framework.common.assertion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * D3-2 软断言门面契约测试。
 *
 * <p>核心保证：
 * <ul>
 *   <li><b>收集期不抛</b>：多条失败能一次跑完，而不是首条即中断；</li>
 *   <li><b>assertAll 聚合抛出</b>且失败全部体现在信息里；</li>
 *   <li><b>抛出即清空</b>：绝不带到下一场景；</li>
 *   <li><b>线程隔离</b>：并行 scenario 的失败互不污染（并行的前提）。</li>
 * </ul>
 */
public class SoftAssertionsTest {

    @AfterEach
    public void tearDown() {
        SoftAssertions.clearForCurrentThread();
    }

    /** 收集期不抛异常，多条失败被完整收集。 */
    @Test
    public void failuresAreCollectedWithoutThrowing() {
        SoftAssertions.assertEquals(200, 404, "status");
        SoftAssertions.assertContains("hello", "world", "body");
        SoftAssertions.assertTrue(false, "flag");
        SoftAssertions.assertNotNull(null, "token");

        assertTrue(SoftAssertions.hasFailures());

        List<AssertionFailure> failures = SoftAssertions.failuresForCurrentThread();
        assertEquals(4, failures.size());
        assertEquals(200, failures.get(0).getExpected(), "应收集到全部 4 条失败");
        assertEquals(404, failures.get(0).getActual());
    }

    /** assertAll 抛出聚合错误，且包含每一条失败。 */
    @Test
    public void assertAllThrowsAggregatedError() {
        SoftAssertions.assertEquals("a", "b", "first");
        SoftAssertions.assertTrue(false, "second");

        try {
            SoftAssertions.assertAll();
            fail("assertAll 应抛出 FrameworkAssertionError");
        } catch (FrameworkAssertionError e) {
            String msg = e.getMessage();
            assertTrue(msg.contains("first"), "聚合信息应含第 1 条失败");
            assertTrue(msg.contains("second"), "聚合信息应含第 2 条失败");
            assertTrue(msg.contains("2"), "聚合信息应含失败总数");
        }
    }

    /** assertAll 后必须清空（先快照再清空，绝不带到下一场景）。 */
    @Test
    public void assertAllClearsFailures() {
        SoftAssertions.assertTrue(false, "only-one");
        try {
            SoftAssertions.assertAll();
            fail("应抛出");
        } catch (FrameworkAssertionError ignored) {
            // 预期
        }

        assertFalse(SoftAssertions.hasFailures(), "assertAll 抛错后仍必须清空收集器");
        // 无失败时 assertAll 不抛
        SoftAssertions.assertAll();
    }

    /** 无失败时 assertAll 不抛异常。 */
    @Test
    public void assertAllNoOpWhenNoFailures() {
        SoftAssertions.assertEquals(1, 1, "ok");
        SoftAssertions.assertAll(); // 不得抛出
    }

    /** renderFailures 渲染可读报告且不清空（供 Serenity 报告复用）。 */
    @Test
    public void renderFailuresIsNonDestructive() {
        SoftAssertions.assertTrue(false, "render-me");
        String rendered = SoftAssertions.renderFailures();
        assertTrue(rendered.contains("render-me"));
        assertTrue(SoftAssertions.hasFailures(), "渲染不应清空收集器");

        SoftAssertions.clearForCurrentThread();
        assertEquals("", SoftAssertions.renderFailures(), "无失败时应渲染空串");
    }

    /** 线程隔离：子线程看不到主线程的失败，主线程也不受子线程影响。 */
    @Test
    public void failuresAreThreadIsolated() throws Exception {
        SoftAssertions.assertTrue(false, "main-thread-failure");

        final boolean[] childSawFailure = {true};
        final CountDownLatch done = new CountDownLatch(1);

        Thread child = new Thread(() -> {
            childSawFailure[0] = SoftAssertions.hasFailures();
            SoftAssertions.clearForCurrentThread();
            done.countDown();
        }, "soft-assert-child");
        child.start();
        assertTrue(done.await(5, TimeUnit.SECONDS), "子线程应在超时前完成");

        assertFalse(childSawFailure[0], "子线程不应看到主线程收集的失败");
        assertTrue(SoftAssertions.hasFailures(), "主线程的失败不应被子线程清理影响");
    }
}
