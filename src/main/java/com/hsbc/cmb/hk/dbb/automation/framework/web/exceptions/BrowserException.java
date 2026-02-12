package com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions;

public class BrowserException extends RuntimeException {
    public BrowserException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public BrowserException(String message) {
        super(message);
    }
}