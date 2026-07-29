package com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ConfigProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.api.utility.Constants;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class EntityBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(EntityBuilder.class);

    /**
     * Build an Entity with specified entity name
     * Configuration will be loaded from {entityName}.conf or {entityName}.properties
     *
     * @param entityName entity name to load configuration for
     * @return configured Entity instance
     * @throws IllegalArgumentException if entityName is null or empty
     */
    public static Entity build(String entityName) {
        // Validate entity name is not null or empty
        if (entityName == null || entityName.trim().isEmpty()) {
            String errorMsg = "Entity name is required! Cannot build empty entity.";
            LOGGER.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        entityName = entityName.trim();

        // Create entity and set name
        Entity entity = new Entity();
        entity.setEntityName(entityName);
        LOGGER.info("Start building entity, entity name: {}", entityName);

        try {
            // Load configuration for this entity
            Config config = ConfigProvider.config(entity);
            if (config == null || config.isEmpty()) {
                String errorMsg = "Loaded configuration is empty, entity name: " + entityName;
                LOGGER.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            // Build final entity (copy constructor + auto load headers etc.)
            Entity builtEntity = new Entity(entity);
            LOGGER.info("Entity built successfully, headers: {}", builtEntity.getRequestHeaders());
            return builtEntity;

        } catch (IllegalArgumentException e) {
            LOGGER.error("Entity build failed (empty configuration), entity name: {}", entityName, e);
            throw e;
        } catch (Exception e) {
            LOGGER.error("Entity build failed, entity name: {}", entityName, e);
            throw new RuntimeException("Entity build failed", e);
        }
    }

    /**
     * Build an Entity with specified entity name and environment
     * Configuration will be loaded from {entityName}.conf or {entityName}.properties
     * Environment-specific configuration will override base configuration
     *
     * @param entityName entity name to load configuration for
     * @param env environment name (e.g., "dev", "test", "prod")
     * @return configured Entity instance
     * @throws IllegalArgumentException if entityName is null or empty
     */
    public static Entity build(String entityName, String env) {
        // Set environment if provided
        if (env != null && !env.trim().isEmpty()) {
            System.setProperty(Constants.ENV, env.trim());
            LOGGER.info("Setting environment to: {}", env);
        }

        // Call the original build method
        return build(entityName);
    }

    /**
     * Build an Entity with null configuration (for dynamic setup)
     * This creates an Entity without loading any configuration file.
     * You can then dynamically set endpoint, baseUri, basePath, etc.
     * <p>
     * Example usage:
     * <pre>
     * Entity entity = EntityBuilder.buildNull();
     * entity.setBaseUri("https://api.example.com");
     * entity.setEndpoint("/users");
     * </pre>
     *
     * @return Entity instance with null configuration (for dynamic setup)
     */
    public static Entity buildNull() {
        LOGGER.info("Building Entity with null configuration (for dynamic setup)");
        Entity entity = new Entity();
        // Do not load any configuration
        // Do not call copy constructor
        // User can set properties dynamically
        return entity;
    }

    /**
     * Build an Entity using entity name from system properties
     * This method is kept for backward compatibility
     *
     * @return configured Entity instance
     */
    public static Entity build() {
        String entityName = System.getProperty(Constants.ENTITY);
        if (entityName == null || entityName.trim().isEmpty()) {
            // If no entity name in system properties, return a null entity for dynamic configuration
            LOGGER.info("No entity name found in system properties, building null entity for dynamic configuration");
            return buildNull();
        }
        return build(entityName);
    }
}
