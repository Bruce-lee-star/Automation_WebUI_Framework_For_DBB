package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 驱动竞态分类器契约守卫（自愈策略的<b>唯一判据</b>，判错会导致"该重试的不重试 / 不该重试的乱重试"）。
 *
 * <p>锁定的契约：
 * <ol>
 *   <li>只认驱动自产的两段稳定文案（{@code Object doesn't exist} / {@code interrupted by another navigation}）；
 *       文案取自 playwright 1.62 驱动源码的唯一产出点。</li>
 *   <li>{@link TimeoutError} <b>整条 cause 链</b>上出现即判 NONE（超时是语义失败，重试只会掩盖问题）。</li>
 *   <li>非 {@code PlaywrightException} 家族的异常（即便文案命中）一律 NONE —— 不误伤业务异常。</li>
 *   <li>支持 cause 链遍历（异常可能被上层包装）。</li>
 * </ol>
 */
class DriverRaceErrorsTest {

    /** 实际日志中的文案（1.txt:959，playwright 1.62 Connection.getExistingObject）。 */
    private static final String OBJECT_GONE_MESSAGE =
            "Object doesn't exist: response@1256921968353cbde3445ff7a129b1ce";

    /** 实际日志中的文案（1.txt:992，驱动导航逻辑）。 */
    private static final String INTERRUPTED_MESSAGE =
            "Navigation to \"https://x/logon\" is interrupted by another navigation to \"https://x/home\"";

    @Test
    @DisplayName("对象句柄生命周期竞态：Object doesn't exist（response@/request@/frame@）")
    void objectLifecycleRace_isClassified() {
        assertEquals(DriverRaceErrors.Kind.OBJECT_LIFECYCLE_RACE,
                DriverRaceErrors.classify(new PlaywrightException(OBJECT_GONE_MESSAGE)));
        assertEquals(DriverRaceErrors.Kind.OBJECT_LIFECYCLE_RACE,
                DriverRaceErrors.classify(new PlaywrightException("Object doesn't exist: request@abc")));
        assertEquals(DriverRaceErrors.Kind.OBJECT_LIFECYCLE_RACE,
                DriverRaceErrors.classify(new PlaywrightException("Object doesn't exist: frame@b90d5f")));
        assertTrue(DriverRaceErrors.isSelfHealable(new PlaywrightException(OBJECT_GONE_MESSAGE)));
    }

    @Test
    @DisplayName("导航打断竞态：interrupted by another navigation")
    void interruptedByAnotherNavigation_isClassified() {
        assertEquals(DriverRaceErrors.Kind.INTERRUPTED_BY_ANOTHER_NAVIGATION,
                DriverRaceErrors.classify(new PlaywrightException(INTERRUPTED_MESSAGE)));
        assertTrue(DriverRaceErrors.isSelfHealable(new PlaywrightException(INTERRUPTED_MESSAGE)));
    }

    @Test
    @DisplayName("超时是语义失败：整条 cause 链上出现 TimeoutError 即判 NONE（即便文案命中）")
    void timeoutIsNeverSelfHealable() {
        assertEquals(DriverRaceErrors.Kind.NONE, DriverRaceErrors.classify(new TimeoutError("Timeout 30000ms exceeded")));
        //  文案命中竞态标记，但类型是超时 ⇒ 仍不得自愈（防"重试掩盖超时"）
        assertEquals(DriverRaceErrors.Kind.NONE,
                DriverRaceErrors.classify(new TimeoutError(OBJECT_GONE_MESSAGE)));
        //  链上任意环节是超时 ⇒ 同样否决
        assertEquals(DriverRaceErrors.Kind.NONE,
                DriverRaceErrors.classify(new RuntimeException(new TimeoutError(OBJECT_GONE_MESSAGE))));
        assertFalse(DriverRaceErrors.isSelfHealable(new TimeoutError(OBJECT_GONE_MESSAGE)));
    }

    @Test
    @DisplayName("非驱动家族异常一律 NONE（即便文案相同）——不误伤业务异常")
    void nonDriverExceptions_areNeverSelfHealable() {
        assertEquals(DriverRaceErrors.Kind.NONE,
                DriverRaceErrors.classify(new IllegalStateException("Object doesn't exist: response@x")));
        assertEquals(DriverRaceErrors.Kind.NONE,
                DriverRaceErrors.classify(new PlaywrightException("Target page, context or browser has been closed")));
        assertEquals(DriverRaceErrors.Kind.NONE, DriverRaceErrors.classify(new PlaywrightException("boom")));
        assertEquals(DriverRaceErrors.Kind.NONE, DriverRaceErrors.classify(new PlaywrightException((String) null)));
        assertEquals(DriverRaceErrors.Kind.NONE, DriverRaceErrors.classify(null));
    }

    @Test
    @DisplayName("支持 cause 链遍历：异常被上层包装后仍可分类")
    void wrappedCause_isClassified() {
        assertEquals(DriverRaceErrors.Kind.OBJECT_LIFECYCLE_RACE,
                DriverRaceErrors.classify(new RuntimeException("wrapper", new PlaywrightException(OBJECT_GONE_MESSAGE))));
        assertEquals(DriverRaceErrors.Kind.INTERRUPTED_BY_ANOTHER_NAVIGATION,
                DriverRaceErrors.classify(new RuntimeException("wrapper",
                        new RuntimeException("inner", new PlaywrightException(INTERRUPTED_MESSAGE)))));
    }

    @Test
    @DisplayName("同一链上两类竞态并存时，对象生命周期竞态优先定论（更明确的驱动内部错误）")
    void objectLifecycleRace_takesPrecedenceOverInterrupted() {
        assertEquals(DriverRaceErrors.Kind.OBJECT_LIFECYCLE_RACE,
                DriverRaceErrors.classify(new PlaywrightException(INTERRUPTED_MESSAGE,
                        new PlaywrightException(OBJECT_GONE_MESSAGE))));
    }
}
