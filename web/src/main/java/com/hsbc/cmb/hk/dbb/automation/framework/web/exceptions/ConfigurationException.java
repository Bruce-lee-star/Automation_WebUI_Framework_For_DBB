package com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions;

public class ConfigurationException extends FrameworkException {

    private static final long serialVersionUID = 1L;

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public ConfigurationException(String message) {
        super(message);
    }
}