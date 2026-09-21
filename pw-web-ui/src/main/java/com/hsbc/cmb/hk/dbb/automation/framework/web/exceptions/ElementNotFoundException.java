package com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions;

/**
 * 元素未找到异常
 */
public class ElementNotFoundException extends ElementException {

    private static final long serialVersionUID = 1L;

    public ElementNotFoundException(String selector) {
        super("Element not found with selector: " + selector);
    }

    public ElementNotFoundException(String selector, Throwable cause) {
        super("Element not found with selector: " + selector, cause);
    }
}