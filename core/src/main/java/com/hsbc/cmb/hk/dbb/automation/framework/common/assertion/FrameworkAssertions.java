package com.hsbc.cmb.hk.dbb.automation.framework.common.assertion;

import java.util.Objects;

/**
 * 统一<b>硬断言</b>门面（fail-fast）—— D3-2。
 *
 * <p><b>为什么需要它</b>：业务代码此前直接依赖 JUnit / Hamcrest / TestNG 的断言类，
 * 导致三后果：
 * <ul>
 *   <li>断言实现绑死在某个测试引擎上，换引擎或复用到非测试语境（如 Serenity step 库）即失效；</li>
 *   <li>失败信息格式不统一，"expected/actual" 有的有、有的没有，排障成本由每个用例各自承担；</li>
 *   <li>框架无法在断言失败时挂自己的钩子（埋点 / 报告 / 失败传播）。</li>
 * </ul>
 *
 * <p>本门面只依赖 JDK + 本包 {@link FrameworkAssertionError}，<b>core 零测试引擎耦合</b>；
 * 失败一律抛 {@link FrameworkAssertionError}（继承 {@link AssertionError}），
 * 因此 JUnit / Cucumber / Serenity 仍能正确识别为"断言失败"而非"错误"。
 *
 * <p>用法：
 * <pre>{@code
 * FrameworkAssertions.assertEquals(200, call.statusCode(), "login status");
 * FrameworkAssertions.assertContains(body, "\"code\":0", "login body");
 * }</pre>
 *
 * @see SoftAssertions 需要"收集多条失败后统一上报"时使用软断言
 */
public final class FrameworkAssertions {

    private FrameworkAssertions() {
        // 纯静态门面，禁止实例化
    }

    /** 相等（{@code Objects.equals} 语义，null 安全）。 */
    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new FrameworkAssertionError(format(message, expected, actual));
        }
    }

    /** 不相等。 */
    public static void assertNotEquals(Object unexpected, Object actual, String message) {
        if (Objects.equals(unexpected, actual)) {
            throw new FrameworkAssertionError(format(message, unexpected, actual));
        }
    }

    /** 非空。 */
    public static void assertNotNull(Object actual, String message) {
        if (actual == null) {
            throw new FrameworkAssertionError(defaultMsg(message, "expected non-null value"));
        }
    }

    /** 条件为真。 */
    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new FrameworkAssertionError(defaultMsg(message, "expected condition to be true"));
        }
    }

    /** 条件为假。 */
    public static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new FrameworkAssertionError(defaultMsg(message, "expected condition to be false"));
        }
    }

    /** 字符串包含（null 安全：任一侧为 null 即失败，不抛 NPE）。 */
    public static void assertContains(String haystack, String needle, String message) {
        if (haystack == null || needle == null || !haystack.contains(needle)) {
            throw new FrameworkAssertionError(format(message, needle, haystack));
        }
    }

    /** HTTP 状态码相等 —— 语义化包装，失败信息直接点明是状态码。 */
    public static void assertStatusCode(int expected, int actual, String message) {
        if (expected != actual) {
            throw new FrameworkAssertionError(format(
                    (message == null ? "status code" : message), expected, actual));
        }
    }

    /** 无条件失败（用于不可达分支与自定义校验）。 */
    public static FrameworkAssertionError fail(String message) {
        throw new FrameworkAssertionError(defaultMsg(message, "assertion failed"));
    }

    // ═══════════════════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════════════════

    private static String format(String message, Object expected, Object actual) {
        StringBuilder sb = new StringBuilder();
        sb.append(defaultMsg(message, "assertion failed"));
        sb.append(" | expected=").append(expected);
        sb.append(" | actual=").append(actual);
        return sb.toString();
    }

    private static String defaultMsg(String message, String fallback) {
        if (message == null || message.trim().isEmpty()) {
            return fallback;
        }
        return message;
    }
}
