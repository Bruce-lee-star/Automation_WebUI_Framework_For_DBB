package com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions;

public class ValidationException extends RuntimeException {
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public ValidationException(String message) {
        super(message);
    }
}