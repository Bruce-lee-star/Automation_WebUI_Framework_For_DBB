package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WEB-P1-5 种子测试：框架中立矩形值类型（无浏览器）。
 * 固化「public API 不泄漏 Playwright 类型」这一 API 边界：业务只接触 x/y/width/height。
 */
public class ElementRectTest {

    @Test
    public void constructorAndGetters_exposePlainCoordinates() {
        ElementRect rect = new ElementRect(1.5, 2.5, 300.0, 120.0);

        assertEquals(1.5, rect.getX(), 0.0);
        assertEquals(2.5, rect.getY(), 0.0);
        assertEquals(300.0, rect.getWidth(), 0.0);
        assertEquals(120.0, rect.getHeight(), 0.0);
    }

    @Test
    public void toString_includesAllDimensions() {
        String s = new ElementRect(1.0, 2.0, 3.0, 4.0).toString();

        assertTrue(s.contains("x=1.0"));
        assertTrue(s.contains("y=2.0"));
        assertTrue(s.contains("w=3.0"));
        assertTrue(s.contains("h=4.0"));
    }

    @Test
    public void doesNotLeakPlaywrightTypesInPublicApi() {
        // API 边界守卫：ElementRect 的 public 成员不得出现 com.microsoft.playwright 类型
        for (java.lang.reflect.Method m : ElementRect.class.getDeclaredMethods()) {
            String typeName = m.getReturnType().getName();
            assertFalse(
                    typeName.startsWith("com.microsoft.playwright"), "public API 不得泄漏 Playwright 类型：" + typeName);
            for (Class<?> p : m.getParameterTypes()) {
                assertFalse(
                        p.getName().startsWith("com.microsoft.playwright"), "public API 参数不得泄漏 Playwright 类型：" + p.getName());
            }
        }
    }
}
