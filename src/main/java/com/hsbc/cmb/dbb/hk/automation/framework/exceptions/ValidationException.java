package com.hsbc.cmb.dbb.hk.automation.framework.exceptions;

public class ValidationException extends RuntimeException {
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public ValidationException(String message) {
        super(message);
    }
}