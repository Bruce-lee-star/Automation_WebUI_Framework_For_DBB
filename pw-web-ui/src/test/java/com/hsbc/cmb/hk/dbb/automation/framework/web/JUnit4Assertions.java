package com.hsbc.cmb.hk.dbb.automation.framework.web;

import org.junit.function.ThrowingRunnable;

import static org.junit.Assert.fail;

/**
 * JUnit 4 缺失断言的补齐（<b>测试侧工具</b>，不涉生产代码）。
 *
 * <p><b>为什么需要它</b>：JUnit 4.13 只补了 {@code assertThrows}，并<b>没有</b>
 * {@code assertDoesNotThrow}（那是 JUnit 5 的 API）。本项目 2026-10 统一回 JUnit 4 后，
 * 原先散落在各测试里的 {@code Assertions.assertDoesNotThrow(...)} 调用点改用本类同名方法，
 * 语义保持一致：动作正常返回即通过；抛出任何异常/错误即失败并带上原始异常。</p>
 */
public final class JUnit4Assertions {

    private JUnit4Assertions() {
    }

    /** 断言动作不抛异常（失败信息取默认文案）。 */
    public static void assertDoesNotThrow(ThrowingRunnable action) {
        assertDoesNotThrow(action, "预期不抛异常");
    }

    /** 断言动作不抛异常；失败时输出给定信息 + 原始异常（便于定位）。 */
    public static void assertDoesNotThrow(ThrowingRunnable action, String message) {
        try {
            action.run();
        } catch (Throwable e) {
            fail(message + " —— 但抛出了 " + e);
        }
    }
}
