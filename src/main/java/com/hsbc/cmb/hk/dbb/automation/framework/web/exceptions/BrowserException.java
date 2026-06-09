package com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions;

public class BrowserException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BrowserException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public BrowserException(String message) {
        super(message);
    }
}