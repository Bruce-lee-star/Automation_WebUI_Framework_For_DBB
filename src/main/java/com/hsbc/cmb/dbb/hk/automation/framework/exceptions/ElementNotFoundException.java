package com.hsbc.cmb.dbb.hk.automation.framework.exceptions;

/**
 * 元素未找到异常
 */
public class ElementNotFoundException extends ElementException {
    public ElementNotFoundException(String selector) {
        super("Element not found with selector: " + selector);
    }

    public ElementNotFoundException(String selector, Throwable cause) {
        super("Element not found with selector: " + selector, cause);
    }
}