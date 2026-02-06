package com.hsbc.cmb.dbb.hk.automation.tests.utils;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import net.thucydides.model.environment.SystemEnvironmentVariables;
import net.thucydides.model.util.EnvironmentVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BDD Utility class for managing login information
 * Supports thread-safe access to login info across different Step classes
 */
public class BDDUtils {

    private static final Logger logger = LoggerFactory.getLogger(BDDUtils.class);
    private static final EnvironmentVariables environmentVariables = SystemEnvironmentVariables.createEnvironmentVariables();
    
    // ThreadLocal to store current thread's login information
    private static final ThreadLocal<BDDUtils> currentLoginInfo = new ThreadLocal<>();

    // Private fields to store login information
    private String username;
    private String url;
    private String password;
    private String profile;
    private String securityUrl;

    // Private constructor - use getLogonDBBInfo() instead
    private BDDUtils() {
    }

    /**
     * Get DBB login information based on environment and username
     *
     * @param env Environment identifier (O88_SIT1, O63_SIT1, O38_SIT1)
     * @param username Username (AABBCCDD, ABCDEW)
     * @return BDDUtils object containing user login information
     */
    public static BDDUtils getLogonDBBInfo(String env, String username) {
        logger.info("Get DBB login info - Environment: {}, Username: {}", env, username);
        
        BDDUtils bddUtils = new BDDUtils();
        
        try {
            // Extract environment identifier from env (e.g., extract O63 from O63_SIT1)
            String envPrefix = env.split("_")[0];  // Get part before underscore
            logger.debug("Extracted environment identifier: {}", envPrefix);
            
            // Get URL from environment configuration (required field)
            String url = environmentVariables.getProperty("environment." + env);
            if (url == null || url.isEmpty()) {
                String errorMsg = String.format("URL configuration is required but not found for environment: %s", env);
                logger.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }
            bddUtils.url = url;
            logger.debug("URL: {}", url);
            
            // Build userinfo prefix based on environment identifier
            String userinfoKey = "userinfo_" + envPrefix;
            logger.debug("UserInfo Key: {}", userinfoKey);
            
            // Set username (required field)
            bddUtils.username = username;
            
            // Get password (optional field)
            String passwordKey = userinfoKey + "." + username + ".password";
            String password = environmentVariables.getProperty(passwordKey);
            if (password == null || password.isEmpty()) {
                logger.warn("Password configuration not found for environment: {}, user: {}, using default empty value", env, username);
                bddUtils.password = "";
            } else {
                bddUtils.password = password;
                logger.debug("Password found");
            }
            
            // Get profile (optional field)
            String profileKey = userinfoKey + "." + username + ".profile";
            String profile = environmentVariables.getProperty(profileKey);
            if (profile == null || profile.isEmpty()) {
                logger.warn("Profile configuration not found for environment: {}, user: {}, using default empty value", env, username);
                bddUtils.profile = "";
            } else {
                bddUtils.profile = profile;
                logger.debug("Profile: {}", profile);
            }
            
            // Get token security url (optional field)
            String securityUrlKey = userinfoKey + "." + username + ".token.security.url";
            String securityUrl = environmentVariables.getProperty(securityUrlKey);
            if (securityUrl == null || securityUrl.isEmpty()) {
                logger.warn("Token security URL configuration not found for environment: {}, user: {}, using default empty value", env, username);
                bddUtils.securityUrl = "";
            } else {
                bddUtils.securityUrl = securityUrl;
                logger.debug("Security URL: {}", securityUrl);
            }
            
            logger.info("Successfully retrieved DBB login info - Environment: {}, User: {}, URL: {}, Profile: {}", 
                env, username, bddUtils.url, bddUtils.profile);
            
        } catch (Exception e) {
            logger.error("Failed to get DBB login info - Environment: {}, User: {}", env, username, e);
            throw new RuntimeException("Failed to get DBB login info: " + e.getMessage(), e);
        }
        
        return bddUtils;
    }

    /**
     * Set current thread's login information
     * Call this method in LoginSteps after getting login info
     *
     * @param loginInfo BDDUtils object containing login information
     */
    public static void setCurrentLoginInfo(BDDUtils loginInfo) {
        currentLoginInfo.set(loginInfo);
        logger.debug("Set current login info for thread: {} - Username: {}", 
            Thread.currentThread().getId(), loginInfo.username);
    }

    /**
     * Get current thread's login information
     * Can be called from any Step class to access current scenario's login info
     *
     * @return BDDUtils object containing current thread's login information
     */
    public static BDDUtils getCurrentLoginInfo() {
        BDDUtils loginInfo = currentLoginInfo.get();
        if (loginInfo == null) {
            logger.warn("No login info found for current thread: {}", Thread.currentThread().getId());
            throw new IllegalStateException("No login information set for current thread. " +
                "Please call setCurrentLoginInfo() in LoginSteps first.");
        }
        return loginInfo;
    }

    /**
     * Get current thread's username
     * Convenience method to quickly get username
     *
     * @return Current thread's username
     */
    public static String getCurrentUsername() {
        return getCurrentLoginInfo().username;
    }

    /**
     * Get current thread's password
     * Convenience method to quickly get password
     *
     * @return Current thread's password
     */
    public static String getCurrentPassword() {
        return getCurrentLoginInfo().password;
    }

    /**
     * Get current thread's profile
     * Convenience method to quickly get profile
     *
     * @return Current thread's profile
     */
    public static String getCurrentProfile() {
        return getCurrentLoginInfo().profile;
    }

    /**
     * Get current thread's security URL
     * Convenience method to quickly get security URL
     *
     * @return Current thread's security URL
     */
    public static String getCurrentSecurityUrl() {
        return getCurrentLoginInfo().securityUrl;
    }

    /**
     * Get current thread's URL
     * Convenience method to quickly get URL
     *
     * @return Current thread's URL
     */
    public static String getCurrentUrl() {
        return getCurrentLoginInfo().url;
    }

    /**
     * Clear current thread's login information
     * Should be called at the end of each scenario to prevent memory leaks
     */
    public static void clearCurrentLoginInfo() {
        currentLoginInfo.remove();
        logger.debug("Cleared login info for thread: {}", Thread.currentThread().getId());
    }

    /**
     * Check if current thread has login information
     *
     * @return true if login info is set, false otherwise
     */
    public static boolean hasCurrentLoginInfo() {
        return currentLoginInfo.get() != null;
    }

    /**
     * Get property from environment variables
     *
     * @param key Property key
     * @return Property value
     */
    public static String getProperty(String key) {
        return environmentVariables.getProperty(key);
    }

    public static String getSecurityCode(String securityUrl) {
        try {
            // Send GET request using RestAssured
            logger.debug("Sending GET request to: {}", securityUrl);
            Response response = RestAssured
                .given()
                    .log().ifValidationFails()
                .when()
                    .get(securityUrl)
                .then()
                    .log().ifValidationFails()
                    .extract()
                    .response();

            // Log response details
            int statusCode = response.getStatusCode();
            String responseBody = response.getBody().asString();

            logger.info("GET request completed - Status Code: {}", statusCode);
            logger.debug("Response Body: {}", responseBody);

            // Check if request was successful
            if (statusCode >= 200 && statusCode < 300) {
                // Extract token from JSON response
                String token = response.jsonPath().getString("token");
                if (token != null && !token.isEmpty()) {
                    logger.info("Successfully retrieved security code token: {}", token);
                    return token;
                } else {
                    String errorMsg = String.format("Token not found or empty in response: %s", responseBody);
                    logger.error(errorMsg);
                    throw new RuntimeException(errorMsg);
                }
            } else {
                String errorMsg = String.format("Failed to get security code. Status code: %d, Response: %s",
                    statusCode, responseBody);
                logger.error(errorMsg);
                throw new RuntimeException(errorMsg);
            }

        } catch (Exception e) {
            String errorMsg = String.format("Exception occurred while getting security code from URL: %s", securityUrl);
            logger.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }

}
