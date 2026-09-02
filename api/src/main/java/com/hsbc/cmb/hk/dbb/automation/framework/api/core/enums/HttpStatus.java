package com.hsbc.cmb.hk.dbb.automation.framework.api.core.enums;

/**
 * HTTP Status codes enumeration for response assertion
 */
public enum HttpStatus {
    
    // 1xx Informational
    CONTINUE(100, "Continue"),
    SWITCHING_PROTOCOLS(101, "Switching Protocols"),
    PROCESSING(102, "Processing"),
    EARLY_HINTS(103, "Early Hints"),
    
    // 2xx Success
    OK(200, "OK"),
    CREATED(201, "Created"),
    ACCEPTED(202, "Accepted"),
    NON_AUTHORITATIVE_INFORMATION(203, "Non-Authoritative Information"),
    NO_CONTENT(204, "No Content"),
    RESET_CONTENT(205, "Reset Content"),
    PARTIAL_CONTENT(206, "Partial Content"),
    MULTI_STATUS(207, "Multi-Status"),
    ALREADY_REPORTED(208, "Already Reported"),
    IM_USED(226, "IM Used"),
    
    // 3xx Redirection
    MULTIPLE_CHOICES(300, "Multiple Choices"),
    MOVED_PERMANENTLY(301, "Moved Permanently"),
    FOUND(302, "Found"),
    SEE_OTHER(303, "See Other"),
    NOT_MODIFIED(304, "Not Modified"),
    USE_PROXY(305, "Use Proxy"),
    TEMPORARY_REDIRECT(307, "Temporary Redirect"),
    PERMANENT_REDIRECT(308, "Permanent Redirect"),
    
    // 4xx Client Error
    BAD_REQUEST(400, "Bad Request"),
    UNAUTHORIZED(401, "Unauthorized"),
    PAYMENT_REQUIRED(402, "Payment Required"),
    FORBIDDEN(403, "Forbidden"),
    NOT_FOUND(404, "Not Found"),
    METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
    NOT_ACCEPTABLE(406, "Not Acceptable"),
    PROXY_AUTHENTICATION_REQUIRED(407, "Proxy Authentication Required"),
    REQUEST_TIMEOUT(408, "Request Timeout"),
    CONFLICT(409, "Conflict"),
    GONE(410, "Gone"),
    LENGTH_REQUIRED(411, "Length Required"),
    PRECONDITION_FAILED(412, "Precondition Failed"),
    PAYLOAD_TOO_LARGE(413, "Payload Too Large"),
    URI_TOO_LONG(414, "URI Too Long"),
    UNSUPPORTED_MEDIA_TYPE(415, "Unsupported Media Type"),
    RANGE_NOT_SATISFIABLE(416, "Range Not Satisfiable"),
    EXPECTATION_FAILED(417, "Expectation Failed"),
    MISDIRECTED_REQUEST(421, "Misdirected Request"),
    UNPROCESSABLE_ENTITY(422, "Unprocessable Entity"),
    LOCKED(423, "Locked"),
    FAILED_DEPENDENCY(424, "Failed Dependency"),
    TOO_EARLY(425, "Too Early"),
    UPGRADE_REQUIRED(426, "Upgrade Required"),
    PRECONDITION_REQUIRED(428, "Precondition Required"),
    TOO_MANY_REQUESTS(429, "Too Many Requests"),
    REQUEST_HEADER_FIELDS_TOO_LARGE(431, "Request Header Fields Too Large"),
    UNAVAILABLE_FOR_LEGAL_REASONS(451, "Unavailable For Legal Reasons"),
    
    // 5xx Server Error
    INTERNAL_SERVER_ERROR(500, "Internal Server Error"),
    NOT_IMPLEMENTED(501, "Not Implemented"),
    BAD_GATEWAY(502, "Bad Gateway"),
    SERVICE_UNAVAILABLE(503, "Service Unavailable"),
    GATEWAY_TIMEOUT(504, "Gateway Timeout"),
    HTTP_VERSION_NOT_SUPPORTED(505, "HTTP Version Not Supported"),
    VARIANT_ALSO_NEGOTIATES(506, "Variant Also Negotiates"),
    INSUFFICIENT_STORAGE(507, "Insufficient Storage"),
    LOOP_DETECTED(508, "Loop Detected"),
    NOT_EXTENDED(510, "Not Extended"),
    NETWORK_AUTHENTICATION_REQUIRED(511, "Network Authentication Required");
    
    private final int code;
    private final String reason;
    
    HttpStatus(int code, String reason) {
        this.code = code;
        this.reason = reason;
    }
    
    /**
     * Get the HTTP status code
     * @return HTTP status code
     */
    public int getCode() {
        return code;
    }
    
    /**
     * Get the reason phrase
     * @return Reason phrase
     */
    public String getReason() {
        return reason;
    }
    
    /**
     * Find HttpStatus by code
     * @param code HTTP status code
     * @return HttpStatus enum
     * @throws IllegalArgumentException if code not found
     */
    public static HttpStatus fromCode(int code) {
        for (HttpStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown HTTP status code: " + code);
    }
    
    /**
     * Check if the code matches this status
     * @param code HTTP status code to check
     * @return true if matches, false otherwise
     */
    public boolean matches(int code) {
        return this.code == code;
    }
    
    /**
     * Check if this is a 1xx informational status
     * @return true if informational
     */
    public boolean isInformational() {
        return code >= 100 && code < 200;
    }
    
    /**
     * Check if this is a 2xx success status
     * @return true if successful
     */
    public boolean isSuccess() {
        return code >= 200 && code < 300;
    }
    
    /**
     * Check if this is a 3xx redirection status
     * @return true if redirection
     */
    public boolean isRedirection() {
        return code >= 300 && code < 400;
    }
    
    /**
     * Check if this is a 4xx client error status
     * @return true if client error
     */
    public boolean isClientError() {
        return code >= 400 && code < 500;
    }
    
    /**
     * Check if this is a 5xx server error status
     * @return true if server error
     */
    public boolean isServerError() {
        return code >= 500 && code < 600;
    }
    
    /**
     * Check if this is an error status (4xx or 5xx)
     * @return true if error
     */
    public boolean isError() {
        return isClientError() || isServerError();
    }
    
    @Override
    public String toString() {
        return code + " " + reason;
    }
}