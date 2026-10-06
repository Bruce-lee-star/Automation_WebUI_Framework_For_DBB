package com.hsbc.cmb.hk.dbb.automation.framework.api.core.endpoint;

import java.util.Map;

/**
 * Endpoint Configuration - Represents configuration for a specific HTTP method of an endpoint
 *
 * Supports two configuration formats:
 *
 * 1. Simple Format - Single endpoint with multiple HTTP methods:
 * ```
 * end-points {
 *     user {
 *         get {
 *             path = "/user/{id}"
 *             method = "GET"
 *             query-params {
 *                 status = "active"
 *             }
 *             headers {
 *                 Accept = "application/json"
 *             }
 *         }
 *         post {
 *             path = "/user"
 *             method = "POST"
 *             payload = "create-user.json"
 *             headers {
 *                 Accept = "application/json"
 *                 Content-Type = "application/json"
 *             }
 *         }
 *     }
 * }
 * ```
 * Usage: loadEndpointConfig("user", "get")
 *
 * 2. Multi-Endpoint Format - Multiple endpoints under same entity:
 * ```
 * end-points {
 *     pet {
 *         findByStatus {
 *             get {
 *                 path = "/pet/findByStatus"
 *                 method = "GET"
 *                 query-params {
 *                     status = "available"
 *                 }
 *             }
 *         }
 *         getById {
 *             get {
 *                 path = "/pet/{petId}"
 *                 method = "GET"
 *                 path-params {
 *                     petId = "1"
 *                 }
 *             }
 *             delete {
 *                 path = "/pet/{petId}"
 *                 method = "DELETE"
 *                 path-params {
 *                     petId = "1"
 *                 }
 *             }
 *         }
 *         create {
 *             post {
 *                 path = "/pet"
 *                 method = "POST"
 *                 payload = "create-pet.json"
 *             }
 *         }
 *     }
 * }
 * ```
 * Usage examples:
 * - loadEndpointConfig("pet.findByStatus", "get")
 * - loadEndpointConfig("pet.getById", "get")
 * - loadEndpointConfig("pet.getById", "delete")
 * - loadEndpointConfig("pet.create", "post")
 */
public class EndpointConfig {
    private String endpointName;  // e.g., "user"
    private String method;        // e.g., "GET", "POST", "PUT", "DELETE"
    private String path;          // e.g., "/user/{id}"
    private String description;   // Optional description
    private String payloadFile;   // Payload file for POST/PUT/PATCH

    // Request parameters
    private Map<String, Object> queryParams;
    private Map<String, Object> pathParams;
    private Map<String, Object> formParams;
    private Map<String, Object> headers;
    private Map<String, Object> cookies;

    // Constructors
    public EndpointConfig() {
    }

    public EndpointConfig(String endpointName, String method) {
        this.endpointName = endpointName;
        this.method = method;
    }

    // Getters and Setters
    public String getEndpointName() {
        return endpointName;
    }

    public void setEndpointName(String endpointName) {
        this.endpointName = endpointName;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPayloadFile() {
        return payloadFile;
    }

    public void setPayloadFile(String payloadFile) {
        this.payloadFile = payloadFile;
    }

    public Map<String, Object> getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(Map<String, Object> queryParams) {
        this.queryParams = queryParams;
    }

    public Map<String, Object> getPathParams() {
        return pathParams;
    }

    public void setPathParams(Map<String, Object> pathParams) {
        this.pathParams = pathParams;
    }

    public Map<String, Object> getFormParams() {
        return formParams;
    }

    public void setFormParams(Map<String, Object> formParams) {
        this.formParams = formParams;
    }

    public Map<String, Object> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, Object> headers) {
        this.headers = headers;
    }

    public Map<String, Object> getCookies() {
        return cookies;
    }

    public void setCookies(Map<String, Object> cookies) {
        this.cookies = cookies;
    }

    @Override
    public String toString() {
        return "EndpointConfig{" +
                "endpointName='" + endpointName + '\'' +
                ", method='" + method + '\'' +
                ", path='" + path + '\'' +
                ", description='" + description + '\'' +
                ", payloadFile='" + payloadFile + '\'' +
                '}';
    }
}
