package com.hsbc.cmb.dbb.hk.automation.framework.exceptions;

public class ScreenshotException extends RuntimeException {
    public ScreenshotException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public ScreenshotException(String message) {
        super(message);
    }
}