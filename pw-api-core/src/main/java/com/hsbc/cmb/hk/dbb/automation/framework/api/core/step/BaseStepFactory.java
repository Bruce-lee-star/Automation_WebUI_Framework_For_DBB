package com.hsbc.cmb.hk.dbb.automation.framework.api.core.step;

/**
 * BaseStepFactory - Factory class for creating BaseStep instances
 * This class provides controlled access to BaseStep construction,
 * ensuring BaseStep can only be created through TestServices
 */
public class BaseStepFactory {

    /**
     * Create a BaseStep with null entity (for dynamic configuration)
     * Can be used to set endpoint, baseUri, etc. dynamically
     *
     * @return BaseStep instance with null entity
     */
    public static BaseStep createWithNullEntity() {
        return new BaseStep(null);
    }

    /**
     * Create a BaseStep with specified entity name
     * Will load configuration from {entityName}.conf or {entityName}.properties
     *
     * @param entityName the entity name to load configuration for
     * @return BaseStep instance with configured entity
     */
    public static BaseStep createWithEntity(String entityName) {
        return new BaseStep(entityName);
    }

    /**
     * Create a BaseStep with specified entity name and environment
     * Will load configuration from {entityName}.conf or {entityName}.properties
     * Environment-specific configuration will override base configuration
     *
     * @param entityName the entity name to load configuration for
     * @param env environment name (e.g., "dev", "test", "prod")
     * @return BaseStep instance with configured entity
     */
    public static BaseStep createWithEntity(String entityName, String env) {
        if (env != null && !env.trim().isEmpty()) {
            // Create entity with environment parameter
            return new BaseStep(entityName, env);
        }
        // If env is null, call the original method
        return new BaseStep(entityName);
    }
}
