package com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions;

/**
 * 元素不可点击异常
 */
public class ElementNotClickableException extends ElementException {
    public ElementNotClickableException(String selector) {
        super("Element not clickable with selector: " + selector);
    }

    public ElementNotClickableException(String selector, Throwable cause) {
        super("Element not clickable with selector: " + selector, cause);
    }
}