package com.hsbc.cmb.hk.dbb.automation.tests.verify;

import org.junit.jupiter.api.Assertions;

/**
 * B-5：步骤层断言收口（Step-layer assertion facade）。
 *
 * <p>步骤类（{@code *Steps}）不再直接依赖 {@code org.junit.jupiter.api.Assertions}——所有步骤层断言
 * 统一经本类转发，从而：① 步骤类与测试框架断言 API 解耦（ArchUnit
 * {@code LayeringArchTest#stepsMustNotUseJunitAssertionsDirectly} 固化）；② 断言集中为单一收口点，
 * 后续可平滑替换为业务语义 Verifier（如 {@code verifyCapturedStatus(call, 200)}）而不触及任何步骤。
 *
 * <p><b>定位</b>：本类为过渡收口层（转发 JUnit 断言，行为与直连逐字一致），是「断言下沉到 Service /
 * Verifier」的替换锚点；按 route 能力域提供语义化断言属后续增强。
 *
 * <p>线程安全：无状态静态方法。
 */
public final class RouteDemoVerifications {

    private RouteDemoVerifications() {
    }

    public static void assertTrue(boolean condition) {
        Assertions.assertTrue(condition);
    }

    public static void assertTrue(boolean condition, String message) {
        Assertions.assertTrue(condition, message);
    }

    public static void assertFalse(boolean condition) {
        Assertions.assertFalse(condition);
    }

    public static void assertFalse(boolean condition, String message) {
        Assertions.assertFalse(condition, message);
    }

    public static void assertNotNull(Object actual) {
        Assertions.assertNotNull(actual);
    }

    public static void assertNotNull(Object actual, String message) {
        Assertions.assertNotNull(actual, message);
    }

    public static void assertNull(Object actual) {
        Assertions.assertNull(actual);
    }

    public static void assertNull(Object actual, String message) {
        Assertions.assertNull(actual, message);
    }

    public static void assertEquals(Object expected, Object actual) {
        Assertions.assertEquals(expected, actual);
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        Assertions.assertEquals(expected, actual, message);
    }

    public static void fail(String message) {
        Assertions.fail(message);
    }
}
