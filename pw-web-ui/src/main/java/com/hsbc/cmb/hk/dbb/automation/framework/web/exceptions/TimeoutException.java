package com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions;

/**
 * 框架级等待超时异常（区别于 Playwright 原生的 {@link com.microsoft.playwright.TimeoutError}）
 * <p>
 * 用于框架内部的轮询等待超时场景（title/url/page/element 等的 waitFor 条件），
 * 继承自 {@link FrameworkException} 而非 {@link ElementException}，
 * 因为超时可能发生在页面导航、页面标题、URL 匹配等非元素场景。
 */
public class TimeoutException extends FrameworkException {

    private static final long serialVersionUID = 1L;

    public TimeoutException(String message) {
        super(message);
    }

    public TimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}