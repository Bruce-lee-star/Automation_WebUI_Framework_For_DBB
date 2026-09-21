package com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WEB-P1-5 种子测试：web 异常体系契约（无浏览器）。
 * 固化「语义化异常 + cause 链不丢失 + 关键上下文入消息」三要素，便于生产溯源。
 */
public class WebExceptionsTest {

    private static final Throwable CAUSE = new IllegalStateException("root cause");

    @Test
    public void frameworkException_carriesMessageAndCause() {
        FrameworkException ex = new FrameworkException("boom", CAUSE);

        assertEquals("boom", ex.getMessage());
        assertSame( CAUSE,  ex.getCause(), "cause 链必须保留，避免根因丢失");
        assertTrue(ex instanceof RuntimeException);
    }

    @Test
    public void browserException_supportsMessageAndCauseConstructors() {
        assertEquals("down", new BrowserException("down").getMessage());
        BrowserException withCause = new BrowserException("down", CAUSE);
        assertSame(CAUSE, withCause.getCause());
    }

    @Test
    public void navigationException_includesUrlAndCause() {
        NavigationException ex = new NavigationException("https://x.com", 30000L, CAUSE);

        assertTrue( ex.getMessage().contains("https://x.com"), "异常消息须携带目标 URL 便于定位");
        assertSame(CAUSE, ex.getCause());
    }

    @Test
    public void navigationException_supportsMessageVariant() {
        NavigationException ex = new NavigationException("https://x.com", "navigation aborted");

        assertTrue(ex.getMessage().contains("navigation aborted"));
    }

    @Test
    public void elementNotFoundException_includesSelector_andExtendsElementException() {
        ElementNotFoundException ex = new ElementNotFoundException("#login");

        assertTrue(ex.getMessage().contains("#login"));
        assertTrue( ex instanceof ElementException, "元素未找到须可统一按 ElementException 捕获");
    }

    @Test
    public void elementNotFoundException_supportsCauseVariant() {
        ElementNotFoundException ex = new ElementNotFoundException("#login", CAUSE);
        assertSame(CAUSE, ex.getCause());
    }

    @Test
    public void elementOperationException_includesOperationAndSelector() {
        ElementOperationException ex = new ElementOperationException("click", "#submit", "intercepted");

        // 快速构造器中 operation/selector 为独立字段（不并入 message），须经 getter 断言
        assertEquals("click", ex.getOperation());
        assertEquals("#submit", ex.getSelector());
        assertTrue(ex.getMessage().contains("intercepted"));
    }

    @Test
    public void timeoutException_supportsMessageAndCauseConstructors() {
        assertEquals("slow", new TimeoutException("slow").getMessage());
        assertSame(CAUSE, new TimeoutException("slow", CAUSE).getCause());
    }

    @Test
    public void configurationAndInitializationAndScreenshotExceptions_carryCause() {
        assertSame(CAUSE, new ConfigurationException("bad config", CAUSE).getCause());
        assertSame(CAUSE, new InitializationException("init failed", CAUSE).getCause());
        assertSame(CAUSE, new ScreenshotException("shot failed", CAUSE).getCause());
    }
}
