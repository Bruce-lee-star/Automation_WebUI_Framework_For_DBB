package com.hsbc.cmb.hk.dbb.automation.framework.api.core.endpoint;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ConfigProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.api.domain.enums.ConfigKeys;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Endpoint Provider - Manages endpoint configurations from config files
 *
 * Provides methods to retrieve endpoint configuration for specific HTTP methods
 */
public class EndpointProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(EndpointProvider.class);

    /**
     * Get endpoint configuration by endpoint name and HTTP method
     * Supports two formats:
     * 1. Simple: endpointName="pet", method="get" -> loads end-points.pet.get
     * 2. Complex: endpointName="pet.findById", method="get" -> loads end-points.pet.findById.get
     *
     * @param endpointName endpoint name (e.g., "pet", "pet.findById", "pet.findByStatus")
     * @param method HTTP method (e.g., "get", "post", "put", "delete")
     * @return EndpointConfig object or null if not found
     */
    public static EndpointConfig getEndpoint(String endpointName, String method) {
        String lowerMethod = method.toLowerCase();
        String endpointPath;

        // Support both simple and complex endpoint names
        if (endpointName.contains(".")) {
            // Complex format: "pet.findById" -> "end-points.pet.findById.get"
            endpointPath = ConfigKeys.END_POINTS.toString() + "." + endpointName + "." + lowerMethod;
        } else {
            // Simple format: "pet" -> "end-points.pet.get"
            endpointPath = ConfigKeys.END_POINTS.toString() + "." + endpointName + "." + lowerMethod;
        }

        LOGGER.info("Loading endpoint configuration: {}", endpointPath);

        try {
            Config globalConfig = ConfigProvider.getConfig();
            if (!globalConfig.hasPath(endpointPath)) {
                LOGGER.warn("Endpoint configuration not found: {}", endpointPath);
                return null;
            }

            Config endpointConfig = globalConfig.getConfig(endpointPath);
            return buildEndpointConfig(endpointName, lowerMethod.toUpperCase(), endpointConfig);

        } catch (Exception e) {
            LOGGER.error("Failed to load endpoint configuration: {}", endpointPath, e);
            return null;
        }
    }

    /**
     * Check if endpoint exists for a specific method
     * Supports two formats:
     * 1. Simple: endpointName="pet", method="get" -> checks end-points.pet.get
     * 2. Complex: endpointName="pet.findById", method="get" -> checks end-points.pet.findById.get
     *
     * @param endpointName endpoint name (e.g., "pet", "pet.findById", "pet.findByStatus")
     * @param method HTTP method
     * @return true if endpoint exists, false otherwise
     */
    public static boolean hasEndpoint(String endpointName, String method) {
        String lowerMethod = method.toLowerCase();
        String endpointPath;

        // Support both simple and complex endpoint names
        if (endpointName.contains(".")) {
            endpointPath = ConfigKeys.END_POINTS.toString() + "." + endpointName + "." + lowerMethod;
        } else {
            endpointPath = ConfigKeys.END_POINTS.toString() + "." + endpointName + "." + lowerMethod;
        }

        Config globalConfig = ConfigProvider.getConfig();
        return globalConfig.hasPath(endpointPath);
    }

    /**
     * Build EndpointConfig object from Config
     */
    private static EndpointConfig buildEndpointConfig(String endpointName, String method, Config config) {
        EndpointConfig endpointConfig = new EndpointConfig(endpointName, method);

        // Path
        if (config.hasPath("path")) {
            endpointConfig.setPath(config.getString("path"));
        }

        // Description
        if (config.hasPath("description")) {
            endpointConfig.setDescription(config.getString("description"));
        }

        // Payload file
        if (config.hasPath("payload")) {
            endpointConfig.setPayloadFile(config.getString("payload"));
        }

        // Query parameters
        if (config.hasPath(ConfigKeys.QUERY_PARAMS.toString())) {
            Map<String, Object> queryParams = new HashMap<>();
            ConfigObject queryParamsObj = config.getObject(ConfigKeys.QUERY_PARAMS.toString());
            queryParamsObj.forEach((key, value) -> queryParams.put(key, value.unwrapped()));
            endpointConfig.setQueryParams(queryParams);
        }

        // Path parameters
        if (config.hasPath(ConfigKeys.PATH_PARAMS.toString())) {
            Map<String, Object> pathParams = new HashMap<>();
            ConfigObject pathParamsObj = config.getObject(ConfigKeys.PATH_PARAMS.toString());
            pathParamsObj.forEach((key, value) -> pathParams.put(key, value.unwrapped()));
            endpointConfig.setPathParams(pathParams);
        }

        // Form parameters
        if (config.hasPath(ConfigKeys.FORM_PARAMS.toString())) {
            Map<String, Object> formParams = new HashMap<>();
            ConfigObject formParamsObj = config.getObject(ConfigKeys.FORM_PARAMS.toString());
            formParamsObj.forEach((key, value) -> formParams.put(key, value.unwrapped()));
            endpointConfig.setFormParams(formParams);
        }

        // Headers
        if (config.hasPath(ConfigKeys.HEADERS.toString())) {
            Map<String, Object> headers = new HashMap<>();
            ConfigObject headersObj = config.getObject(ConfigKeys.HEADERS.toString());
            headersObj.forEach((key, value) -> headers.put(key, value.unwrapped()));
            endpointConfig.setHeaders(headers);
        }

        // Cookies
        if (config.hasPath(ConfigKeys.COOKIES.toString())) {
            Map<String, Object> cookies = new HashMap<>();
            ConfigObject cookiesObj = config.getObject(ConfigKeys.COOKIES.toString());
            cookiesObj.forEach((key, value) -> cookies.put(key, value.unwrapped()));
            endpointConfig.setCookies(cookies);
        }

        LOGGER.info("Built endpoint config: {}", endpointConfig);
        return endpointConfig;
    }
}
