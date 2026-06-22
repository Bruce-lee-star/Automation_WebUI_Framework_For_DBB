package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 统一代理解析器 —— 所有代理场景的唯一配置入口。
 *
 * <h3>配置键</h3>
 * <pre>{@code
 * playwright.proxy.http          = http://proxy.company.com:8888
 * playwright.proxy.https         = https://proxy.company.com:8443   （HTTP/HTTPS 各自独立，互不回退）
 * playwright.proxy.http.username = domain\\user
 * playwright.proxy.http.password = mypass
 * playwright.proxy.https.username = domain\\user
 * playwright.proxy.https.password = mypass
 * }</pre>
 *
 * <h3>各场景独立开关</h3>
 * <ul>
 *   <li>Context 代理  →  {@code playwright.context.proxy.enabled}</li>
 *   <li>下载代理      →  {@code playwright.download.proxy.enabled}</li>
 *   <li>BS CDP 代理   →  {@code browserstack.proxy.enabled}</li>
 * </ul>
 *
 * <h3>使用方</h3>
 * <ul>
 *   <li>{@link PlaywrightManager} — BrowserStack CDP 代理</li>
 *   <li>{@link PlaywrightContextManager} — 浏览器 Context 代理</li>
 *   <li>{@link PlaywrightInitializer} — 浏览器下载代理</li>
 * </ul>
 */
public final class ProxyConfigResolver {

    private ProxyConfigResolver() {
    }

    /**
     * 构建 HTTP 代理完整 URL（含认证凭据）。
     *
     * @return {@code http://[user:pass@]host:port}，未配置返回 null
     */
    public static String getHttpProxyUrl() {
        String proxy = nonBlank(FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_PROXY_HTTP));
        if (proxy == null) return null;
        String user = nonBlank(FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_PROXY_HTTP_USERNAME));
        String pass = nonBlank(FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_PROXY_HTTP_PASSWORD));
        return buildUrl(proxy, user, pass);
    }

    /**
     * 构建 HTTPS 代理完整 URL（含认证凭据）。
     * <p>HTTP 与 HTTPS 各自独立，互不回退。
     *
     * @return {@code http://[user:pass@]host:port}，未配置返回 null
     */
    public static String getHttpsProxyUrl() {
        String proxy = nonBlank(FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_PROXY_HTTPS));
        if (proxy == null) return null;
        String user = nonBlank(FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_PROXY_HTTPS_USERNAME));
        String pass = nonBlank(FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_PROXY_HTTPS_PASSWORD));
        return buildUrl(proxy, user, pass);
    }

    // ────────────────── 内部实现 ──────────────────

    /**
     * 构建完整代理 URL，自动补充 scheme、注入认证凭据。
     */
    private static String buildUrl(String proxy, String user, String pass) {
        String normalized = ensureScheme(proxy.trim());
        if (user != null && pass != null) {
            String encodedUser = urlEncode(user.trim());
            String encodedPass = urlEncode(pass.trim());
            return normalized.replaceFirst("^(https?://)",
                    "$1" + encodedUser + ":" + encodedPass + "@");
        }
        return normalized;
    }

    /**
     * 确保 URL 有 scheme 前缀。{@code proxy:8080 → http://proxy:8080}
     */
    static String ensureScheme(String url) {
        if (url.matches("^https?://.*")) return url;
        return "http://" + url;
    }

    static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    private static String nonBlank(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
