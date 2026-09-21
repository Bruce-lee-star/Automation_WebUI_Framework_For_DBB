package com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions;

/**
 * 框架基础异常类
 * <p>
 * 所有框架自定义异常的基类，继承自 RuntimeException（非受检异常）
 */
public class FrameworkException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FrameworkException(String message) {
        super(message);
    }

    public FrameworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
