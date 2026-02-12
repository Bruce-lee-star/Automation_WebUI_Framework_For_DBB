package com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions;

/**
 * 元素不可见异常
 */
public class ElementNotVisibleException extends ElementException {
    public ElementNotVisibleException(String selector) {
        super("Element not visible with selector: " + selector);
    }

    public ElementNotVisibleException(String selector, Throwable cause) {
        super("Element not visible with selector: " + selector, cause);
    }
}