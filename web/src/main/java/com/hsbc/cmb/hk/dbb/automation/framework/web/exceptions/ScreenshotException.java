package com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions;

public class ScreenshotException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ScreenshotException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public ScreenshotException(String message) {
        super(message);
    }
}