package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import com.microsoft.playwright.Page;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单个网络请求的轻量状态，不持有 Playwright Response/Route。 */
public final class NetworkRequestState {
    private final Page page;
    private final String requestId;
    private final String method;
    private final String url;
    private final Map<String, String> requestHeaders;
    private final String requestBody;
    private final String resourceType;
    private final boolean mainFrame;
    private volatile Integer status;
    private volatile Map<String, String> responseHeaders = Map.of();
    private volatile String contentType;
    private final AtomicBoolean processed = new AtomicBoolean();

    public boolean markProcessed() { return processed.compareAndSet(false, true); }
    public boolean isProcessed() { return processed.get(); }

    public NetworkRequestState(Page page, String requestId, String method, String url,
                               Map<String, String> requestHeaders, String requestBody,
                               String resourceType, boolean mainFrame) {
        this.page = page;
        this.requestId = requestId;
        this.method = method;
        this.url = url;
        this.requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
        this.requestBody = requestBody;
        this.resourceType = resourceType;
        this.mainFrame = mainFrame;
    }

    public Page page() { return page; }
    public String requestId() { return requestId; }
    public String method() { return method; }
    public String url() { return url; }
    public Map<String, String> requestHeaders() { return requestHeaders; }
    public String requestBody() { return requestBody; }
    public String resourceType() { return resourceType; }
    public boolean mainFrame() { return mainFrame; }
    public Integer status() { return status; }
    public Map<String, String> responseHeaders() { return responseHeaders; }
    public String contentType() { return contentType; }

    public void response(int status, Map<String, String> headers) {
        this.status = status;
        this.responseHeaders = headers == null ? Map.of() : Map.copyOf(headers);
        String type = this.responseHeaders.get("content-type");
        this.contentType = type == null ? this.responseHeaders.get("Content-Type") : type;
    }
}
