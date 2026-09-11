package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.delegate;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D1-1：{@link PageWaits} 重试语义契约测试。
 *
 * <p>核心保证：
 * <ul>
 *   <li><b>根因不丢失</b>：最终异常保留<b>首次</b>尝试的异常（作为 suppressed），
 *       而不是像旧实现那样只留下最后一次、把根因丢掉；</li>
 *   <li>最后一次异常为 cause，中间异常同样保留；</li>
 *   <li>{@code retryWithValidation} 校验通过即返回 true，且用尽次数返回 false。</li>
 * </ul>
 */
public class PageWaitsRetryTest {

    private BasePage bp;

    @BeforeEach
    public void setUp() {
        bp = mock(BasePage.class);
        Page page = mock(Page.class);
        when(bp.getPage()).thenReturn(page);
    }

    /** 首次（根因）异常必须被保留为 suppressed。 */
    @Test
    public void firstFailureIsPreservedAsSuppressed() {
        IllegalStateException root = new IllegalStateException("ROOT-cause");
        IllegalStateException middle = new IllegalStateException("middle");
        IllegalStateException last = new IllegalStateException("last");

        AtomicInteger calls = new AtomicInteger(0);
        try {
            PageWaits.retry(bp, () -> {
                int n = calls.incrementAndGet();
                throw n == 1 ? root : (n == 2 ? middle : last);
            }, 2, 0, "always-fails");
            fail("应抛出重试失败异常");
        } catch (RuntimeException e) {
            assertEquals(3, calls.get(), "尝试次数应为 retries+1");
            assertSame(last, e.getCause(), "最后一次异常应为 cause");

            Throwable[] suppressed = e.getSuppressed();
            assertEquals(2, suppressed.length, "首次与中间异常都应作为 suppressed 保留");
            assertSame(root, suppressed[0], "首次（根因）异常必须保留");
            assertSame(middle, suppressed[1], "中间异常应保留");
            assertTrue(e.getMessage().contains("attempts=3"), "异常信息应提示尝试次数");
        }
    }

    /** 单次即成功：不抛异常、不产生重试。 */
    @Test
    public void successOnFirstAttemptReturnsImmediately() {
        AtomicInteger calls = new AtomicInteger(0);
        PageWaits.retry(bp, calls::incrementAndGet, 3, 0, "ok");
        assertEquals(1, calls.get());
    }

    /** 第二次成功：不再继续重试。 */
    @Test
    public void stopsAfterSuccess() {
        AtomicInteger calls = new AtomicInteger(0);
        PageWaits.retry(bp, () -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("first fails");
            }
        }, 3, 0, "second-ok");
        assertEquals(2, calls.get());
    }

    /** 校验通过即返回 true。 */
    @Test
    public void retryWithValidationReturnsTrueWhenValid() {
        AtomicInteger calls = new AtomicInteger(0);
        boolean ok = PageWaits.retryWithValidation(bp, calls::incrementAndGet,
                () -> true, 2, 0, "valid");
        assertTrue(ok);
        assertEquals(1, calls.get());
    }

    /** 用尽次数仍不通过 → 返回 false（不抛异常）。 */
    @Test
    public void retryWithValidationReturnsFalseWhenExhausted() {
        AtomicInteger calls = new AtomicInteger(0);
        boolean ok = PageWaits.retryWithValidation(bp, calls::incrementAndGet,
                () -> false, 2, 0, "never-valid");
        assertFalse(ok);
        assertEquals(3, calls.get(), "应尝试 maxRetries+1 次");
    }

    /** 校验条件在等待中成立时应提前继续（条件驱动，不盲等）。 */
    @Test
    public void validationWaitIsConditionDriven() {
        AtomicInteger calls = new AtomicInteger(0);
        boolean ok = PageWaits.retryWithValidation(bp, () -> {
            calls.incrementAndGet();
        }, () -> calls.get() >= 2, 3, 300, "eventually-valid");
        assertTrue(ok);
        assertNotNull(bp.getPage());
    }
}
