package com.hsbc.cmb.dbb.hk.automation.framework.exceptions;

public class BrowserException extends RuntimeException {
    public BrowserException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public BrowserException(String message) {
        super(message);
    }
}