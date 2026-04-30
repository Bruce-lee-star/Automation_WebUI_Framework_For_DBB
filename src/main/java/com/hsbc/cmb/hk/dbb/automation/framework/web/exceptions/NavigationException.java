package com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions;

/**
 * 导航异常
 * <p>
 * 当页面导航失败时抛出，例如：
 * - URL 无效
 * - 页面加载超时
 * - 网络连接失败
 * - 页面跳转失败
 */
public class NavigationException extends FrameworkException {

    private final String url;
    private final Long timeoutMs;

    public NavigationException(String url, long timeoutMs, Throwable cause) {
        super(String.format("Navigation to [%s] failed after %d ms: %s", 
                url, timeoutMs, cause.getMessage()), cause);
        this.url = url;
        this.timeoutMs = timeoutMs;
    }

    public NavigationException(String url, String message) {
        super(String.format("Navigation to [%s] failed: %s", url, message));
        this.url = url;
        this.timeoutMs = null;
    }

    public NavigationException(String url, String message, Throwable cause) {
        super(String.format("Navigation to [%s] failed: %s", url, message), cause);
        this.url = url;
        this.timeoutMs = null;
    }

    public String getUrl() {
        return url;
    }

    public Long getTimeoutMs() {
        return timeoutMs;
    }
}
