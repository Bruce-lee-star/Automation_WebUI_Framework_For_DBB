package com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * T2-6 exception hierarchy unification verification.
 * After the 5 top-level exceptions extend FrameworkException, the entire
 * exception hierarchy must converge to FrameworkException, and any framework
 * exception must be catchable via {@code catch (FrameworkException)}.
 */
public class ExceptionHierarchyTest {

    /**
     * 断言「类型继承关系」而非「实例 instanceof」。
     *
     * <p>原写法 {@code new BrowserException("boom") instanceof FrameworkException} 在编译期即恒真
     * （静态类型已确定是 FrameworkException 的子类），既触发 SpotBugs 的 BC_VACUOUS_INSTANCEOF，
     * 实际上也并未验证任何运行时行为 —— 是个「看起来在测、其实恒过」的用例。
     * 改用 {@code isAssignableFrom} 才真正断言了异常层次结构。
     */
    @Test
    public void allExceptionTypesExtendFrameworkException() {
        assertTrue(FrameworkException.class.isAssignableFrom(BrowserException.class));
        assertTrue(FrameworkException.class.isAssignableFrom(ConfigurationException.class));
        assertTrue(FrameworkException.class.isAssignableFrom(ElementException.class));
        assertTrue(FrameworkException.class.isAssignableFrom(InitializationException.class));
        assertTrue(FrameworkException.class.isAssignableFrom(ScreenshotException.class));
        assertTrue(FrameworkException.class.isAssignableFrom(ElementNotFoundException.class));
        assertTrue(FrameworkException.class.isAssignableFrom(ElementOperationException.class));
    }

    @Test
    public void frameworkExceptionIsUnifiedCatchTarget() {
        boolean caught = false;
        try {
            throw new ElementNotFoundException("#missing");
        } catch (FrameworkException e) {
            caught = true;
            // 断言精确的运行时类型：比 instanceof 更强（instanceof 会放行子类），
            // 也避免 SpotBugs 的 JUA_DONT_ASSERT_INSTANCEOF_IN_TESTS。
            assertEquals(ElementNotFoundException.class, e.getClass());
            assertEquals("Element not found with selector: #missing", e.getMessage());
        }
        assertTrue("ElementNotFoundException should be caught as FrameworkException", caught);
    }

    @Test
    public void causeIsPreservedThroughHierarchy() {
        Throwable root = new IllegalStateException("root cause");
        FrameworkException wrapped = new ElementOperationException("click", "#id", "boom", root);
        assertEquals(root, wrapped.getCause());
    }
}
