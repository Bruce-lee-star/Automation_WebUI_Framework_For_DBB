package com.hsbc.cmb.hk.dbb.automation.framework.api.utility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Environment utility class for retrieving environment variables and system properties
 * This class provides a unified interface for accessing environment configuration
 * and is a replacement for the deprecated SystemEnvironmentVariables in older Serenity versions
 */
public class EnvironmentUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnvironmentUtils.class);

    /**
     * Get the current environment instance (for compatibility)
     *
     * @return this instance for method chaining
     */
    public static EnvironmentUtils currentEnvironment() {
        return new EnvironmentUtils();
    }

    /**
     * Get property value from system properties
     *
     * @param key the property key
     * @return the property value, or null if not found
     */
    public String getProperty(String key) {
        return System.getProperty(key);
    }

    /**
     * Get property value from system properties with default value
     *
     * @param key the property key
     * @param defaultValue the default value if property not found
     * @return the property value, or default value if not found
     */
    public static String getProperty(String key, String defaultValue) {
        return System.getProperty(key, defaultValue);
    }

    /**
     * Get environment variable value
     *
     * @param key the environment variable key
     * @return the environment variable value, or null if not found
     */
    public String getEnvironmentVariable(String key) {
        return System.getenv(key);
    }

    /**
     * Set system property value
     *
     * @param key the property key
     * @param value the property value
     */
    public void setProperty(String key, String value) {
        System.setProperty(key, value);
        LOGGER.debug("Set system property: {} = {}", key, value);
    }

    /**
     * Clear system property
     *
     * @param key the property key
     */
    public void clearProperty(String key) {
        System.clearProperty(key);
        LOGGER.debug("Cleared system property: {}", key);
    }

    /**
     * Get current environment name (for Serenity compatibility)
     * This returns the environment from system properties
     *
     * @return the current environment name
     */
    public String getEnvironmentName() {
        return getProperty("env");
    }
}
