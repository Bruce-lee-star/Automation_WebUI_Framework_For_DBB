package com.hsbc.cmb.dbb.hk.automation.framework.exceptions;

/**
 * 元素操作异常基类
 */
public class ElementException extends RuntimeException {
    public ElementException(String message) {
        super(message);
    }

    public ElementException(String message, Throwable cause) {
        super(message, cause);
    }
}