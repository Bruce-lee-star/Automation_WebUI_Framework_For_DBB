package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 统一代理解析器 —— 所有代理场景的唯一配置入口。
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li><b>共享地址</b>：{@code playwright.proxy.http} / {@code playwright.proxy.https} 作为代理服务器地址的唯一来源。</li>
 *   <li><b>默认凭据</b>：{@code playwright.proxy.http.username} / {@code playwright.proxy.http.password} 提供全局默认认证。</li>
 *   <li><b>场景覆盖</b>：当某个场景需要不同凭据（或不需凭据）时，可配置场景专属用户名/密码覆盖默认值。</li>
 * </ul>
 *
 * <h3>配置键</h3>
 * <pre>{@code
 * # ── 共享地址（所有场景统一使用） ──
 * playwright.proxy.http          = http://proxy.company.com:8888
 * playwright.proxy.https         = https://proxy.company.com:8443
 *
 * # ── 全局默认凭据（各场景未配置专属凭据时生效） ──
 * playwright.proxy.http.username  = shared_user
 * playwright.proxy.http.password  = shared_pass
 * playwright.proxy.https.username = shared_user
 * playwright.proxy.https.password = shared_pass
 *
 * # ── 场景专属凭据覆盖（优先级高于全局默认） ──
 * playwright.context.proxy.username         = ctx_only_user
 * playwright.context.proxy.password         = ctx_only_pass
 * playwright.browser.download.http.proxy.username = dl_user
 * playwright.browser.download.http.proxy.password = dl_pass
 * browserstack.proxy.username               = bs_cdp_user
 * browserstack.proxy.password               = bs_cdp_pass
 * browserstack.local.proxy.username         = bs_local_user
 * browserstack.local.proxy.password         = bs_local_pass
 * }</pre>
 *
 * <h3>各场景独立开关</h3>
 * <ul>
 *   <li>Context 代理  →  {@code playwright.context.proxy.enabled}</li>
 *   <li>下载代理      →  {@code playwright.download.proxy.enabled}</li>
 *   <li>BS CDP 代理   →  {@code browserstack.proxy.enabled}</li>
 *   <li>BS Local 代理 →  {@code browserstack.local.proxy.enabled}</li>
 * </ul>
 *
 * <h3>使用方</h3>
 * <ul>
 *   <li>{@link PlaywrightManager} — BrowserStack CDP 代理</li>
 *   <li>{@link PlaywrightContextManager} — 浏览器 Context 代理</li>
 *   <li>{@link PlaywrightInitializer} — 浏览器下载代理</li>
 *   <li>{@code BrowserStackLocalManager} — BrowserStack Local 隧道代理</li>
 * </ul>
 */
public final class ProxyConfigResolver {

    private ProxyConfigResolver() {
    }

    // ──────────── 通用方法（使用全局默认凭据）────────────

    /**
     * 构建 HTTP 代理完整 URL（使用全局默认凭据）。
     */
    public static String getHttpProxyUrl() {
        return getHttpProxyUrlInternal(null, null);
    }

    /**
     * 构建 HTTPS 代理完整 URL（使用全局默认凭据）。
     * <p>地址回退：若 {@code playwright.proxy.https} 未配置，使用 {@code playwright.proxy.http}。
     */
    public static String getHttpsProxyUrl() {
        return getHttpsProxyUrlInternal(null, null);
    }

    // ──────────── 场景专属方法（凭据覆盖）────────────

    /**
     * Context 场景 HTTP 代理 URL。
     * <p>凭据优先级：{@code playwright.context.proxy.username/password} &gt; 全局默认。
     */
    public static String getHttpProxyUrlForContext() {
        return getHttpProxyUrlInternal(
                FrameworkConfig.PLAYWRIGHT_CONTEXT_PROXY_USERNAME,
                FrameworkConfig.PLAYWRIGHT_CONTEXT_PROXY_PASSWORD);
    }

    /**
     * 下载场景 HTTP 代理 URL。
     * <p>凭据优先级：{@code playwright.browser.download.http.proxy.username/password} &gt; 全局默认。
     */
    public static String getHttpProxyUrlForDownload() {
        return getHttpProxyUrlInternal(
                FrameworkConfig.PLAYWRIGHT_BROWSER_DOWNLOAD_HTTP_PROXY_USERNAME,
                FrameworkConfig.PLAYWRIGHT_BROWSER_DOWNLOAD_HTTP_PROXY_PASSWORD);
    }

    /**
     * BrowserStack CDP 场景 HTTP 代理 URL。
     * <p>凭据优先级：{@code browserstack.proxy.username/password} &gt; 全局默认。
     */
    public static String getHttpProxyUrlForBrowserStackCdp() {
        return getHttpProxyUrlInternal(
                FrameworkConfig.BROWSERSTACK_PROXY_USERNAME,
                FrameworkConfig.BROWSERSTACK_PROXY_PASSWORD);
    }

    /**
     * BrowserStack CDP 场景 HTTPS 代理 URL（wss 连接走此代理）。
     * <p>凭据优先级同 HTTP：{@code browserstack.proxy.username/password} &gt; 全局默认。
     * <p>地址回退：若 {@code playwright.proxy.https} 未配置，使用 {@code playwright.proxy.http} 作为代理地址
     * （大多数企业代理同一个服务器同时支持 HTTP 和 CONNECT 隧道）。
     */
    public static String getHttpsProxyUrlForBrowserStackCdp() {
        return getHttpsProxyUrlInternal(
                FrameworkConfig.BROWSERSTACK_PROXY_USERNAME,
                FrameworkConfig.BROWSERSTACK_PROXY_PASSWORD);
    }

    /**
     * BrowserStack Local 场景 HTTP 代理 URL。
     * <p>凭据优先级：{@code browserstack.local.proxy.username/password} &gt; 全局默认。
     */
    public static String getHttpProxyUrlForBrowserStackLocal() {
        return getHttpProxyUrlInternal(
                FrameworkConfig.BROWSERSTACK_LOCAL_PROXY_USERNAME,
                FrameworkConfig.BROWSERSTACK_LOCAL_PROXY_PASSWORD);
    }

    /**
     * 下载场景 HTTPS 代理 URL。
     * <p>地址回退：若 {@code playwright.proxy.https} 未配置，使用 {@code playwright.proxy.http} 作为代理地址。
     */
    public static String getHttpsProxyUrlForDownload() {
        return getHttpsProxyUrlInternal(
                FrameworkConfig.PLAYWRIGHT_BROWSER_DOWNLOAD_HTTPS_PROXY_USERNAME,
                FrameworkConfig.PLAYWRIGHT_BROWSER_DOWNLOAD_HTTPS_PROXY_PASSWORD);
    }

    /**
     * Context 场景 HTTPS 代理 URL。
     * <p>凭据优先级：{@code playwright.context.proxy.username/password} &gt; 全局默认。
     * <p>地址回退：若 {@code playwright.proxy.https} 未配置，使用 {@code playwright.proxy.http} 作为代理地址。
     */
    public static String getHttpsProxyUrlForContext() {
        return getHttpsProxyUrlInternal(
                FrameworkConfig.PLAYWRIGHT_CONTEXT_PROXY_USERNAME,
                FrameworkConfig.PLAYWRIGHT_CONTEXT_PROXY_PASSWORD);
    }

    // ──────────── URL 组件提取（供 BrowserStackLocal 命令行使用）────────────

    /**
     * 从代理 URL 提取主机名。
     * <p>{@code http://user:pass@proxy.com:8080 → proxy.com}
     * <p>内部使用 {@link URI} 解析，正确处置编码凭证、IPv6 等边界。
     */
    public static String extractHost(String proxyUrl) {
        if (proxyUrl == null) return null;
        URI uri = parseLenient(proxyUrl);
        return uri != null ? uri.getHost() : extractHostFallback(proxyUrl);
    }

    /**
     * 从代理 URL 提取端口号。
     */
    public static String extractPort(String proxyUrl) {
        if (proxyUrl == null) return null;
        URI uri = parseLenient(proxyUrl);
        if (uri == null) return extractPortFallback(proxyUrl);
        int port = uri.getPort();
        return port >= 0 ? String.valueOf(port) : null;
    }

    /**
     * 从代理 URL 提取用户名（已 URL 解码，含特殊字符的原始值）。
     * <p>由于 URL userinfo 中 {@code :} 是分隔符，提取时须用 {@link URI#getRawUserInfo()}
     * 按第一个字面 {@code :} 切分后再分别解码，因此避开了密码中编码 {@code :} 的歧义。
     */
    public static String extractUser(String proxyUrl) {
        if (proxyUrl == null) return null;
        URI uri = parseLenient(proxyUrl);
        if (uri == null) return extractUserFallback(proxyUrl);
        String rawInfo = uri.getRawUserInfo();
        if (rawInfo == null) return null;
        int colon = rawInfo.indexOf(':');
        return colon >= 0 ? urlDecode(rawInfo.substring(0, colon)) : urlDecode(rawInfo);
    }

    /**
     * 从代理 URL 提取密码（已 URL 解码，含特殊字符的原始值）。
     */
    public static String extractPass(String proxyUrl) {
        if (proxyUrl == null) return null;
        URI uri = parseLenient(proxyUrl);
        if (uri == null) return extractPassFallback(proxyUrl);
        String rawInfo = uri.getRawUserInfo();
        if (rawInfo == null) return null;
        int colon = rawInfo.indexOf(':');
        return colon >= 0 ? urlDecode(rawInfo.substring(colon + 1)) : null;
    }

    // ────────────────── 内部实现 ──────────────────

    private static String getHttpProxyUrlInternal(FrameworkConfig overrideUserKey,
                                                   FrameworkConfig overridePassKey) {
        String proxy = nonBlank(FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_PROXY_HTTP));
        if (proxy == null) return null;
        String user = resolveUser(FrameworkConfig.PLAYWRIGHT_PROXY_HTTP_USERNAME, overrideUserKey);
        String pass = resolvePass(FrameworkConfig.PLAYWRIGHT_PROXY_HTTP_PASSWORD, overridePassKey);
        return buildUrl(proxy, user, pass);
    }

    private static String getHttpsProxyUrlInternal(FrameworkConfig overrideUserKey,
                                                    FrameworkConfig overridePassKey) {
        String proxy = nonBlank(FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_PROXY_HTTPS));

        // ── 地址回退：未指定 HTTPS 代理时，使用 HTTP 代理作为 CONNECT 隧道入口 ──
        // 大多数企业代理同一个服务器同时处理 HTTP 请求和 HTTPS CONNECT 隧道。
        // wss:// 连接在 Node.js 中走 HTTPS_PROXY，不能回退到 HTTP_PROXY，
        // 所以必须确保 HTTPS_PROXY 也被设置（使用 HTTP 代理地址作为 fallback）。
        boolean usingHttpFallback = false;
        if (proxy == null) {
            proxy = nonBlank(FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_PROXY_HTTP));
            usingHttpFallback = (proxy != null);
        }

        if (proxy == null) return null;

        // 凭据解析：优先用 HTTPS 专属 key，回退到场景 override key
        // 如果地址来自 HTTP fallback，则凭据也用 HTTP 的（更合理）
        String user, pass;
        if (usingHttpFallback) {
            user = resolveUser(FrameworkConfig.PLAYWRIGHT_PROXY_HTTP_USERNAME, overrideUserKey);
            pass = resolvePass(FrameworkConfig.PLAYWRIGHT_PROXY_HTTP_PASSWORD, overridePassKey);
        } else {
            user = resolveUser(FrameworkConfig.PLAYWRIGHT_PROXY_HTTPS_USERNAME, overrideUserKey);
            pass = resolvePass(FrameworkConfig.PLAYWRIGHT_PROXY_HTTPS_PASSWORD, overridePassKey);
        }
        return buildUrl(proxy, user, pass);
    }

    /**
     * 凭据解析：场景专属 &gt; 全局默认。显式配置空字符串表示"不使用认证"。
     */
    private static String resolveUser(FrameworkConfig defaultKey, FrameworkConfig overrideKey) {
        if (overrideKey != null) {
            String override = FrameworkConfigManager.getString(overrideKey);
            if (override != null) {
                String trimmed = override.trim();
                // 显式空字符串 = 不需要认证
                if (trimmed.isEmpty()) return null;
                return trimmed;
            }
        }
        return nonBlank(FrameworkConfigManager.getString(defaultKey));
    }

    private static String resolvePass(FrameworkConfig defaultKey, FrameworkConfig overrideKey) {
        if (overrideKey != null) {
            String override = FrameworkConfigManager.getString(overrideKey);
            if (override != null) {
                String trimmed = override.trim();
                if (trimmed.isEmpty()) return null;
                return trimmed;
            }
        }
        return nonBlank(FrameworkConfigManager.getString(defaultKey));
    }

    /**
     * 构建完整代理 URL，自动补充 scheme、注入认证凭据。
     * <p>安全设计：
     * <ul>
     *   <li>剥离代理地址中可能已嵌入的 userinfo（防双注入）</li>
     *   <li>对用户名/密码中特殊字符（{@code @ : # % / ? &} 等）做 RFC 3986 编码</li>
     * </ul>
     */
    private static String buildUrl(String proxy, String user, String pass) {
        // 1. 剥离已存在的 userinfo，防 http://old:cred@host:port → 双注入
        String normalized = stripUserInfo(ensureScheme(proxy.trim()));
        if (user != null && pass != null) {
            String encodedUser = urlEncode(user.trim());
            String encodedPass = urlEncode(pass.trim());
            return normalized.replaceFirst("^(https?://)",
                    "$1" + encodedUser + ":" + encodedPass + "@");
        }
        return normalized;
    }

    /**
     * 剥离 URL 中可能已嵌入的 {@code user:password@} 部分。
     * <p>例如 {@code http://oldUser:oldPass@proxy.com:8080 → http://proxy.com:8080}
     */
    static String stripUserInfo(String url) {
        if (url == null) return null;
        // 用 URI 解析，无副作用的剥离
        URI uri = parseLenient(url);
        if (uri != null && uri.getRawUserInfo() != null) {
            String scheme = uri.getScheme() != null ? uri.getScheme() + "://" : "http://";
            String host = uri.getHost();
            if (host == null) return url; // 无法解析，原样返回
            int port = uri.getPort();
            String path = uri.getRawPath();
            if (path == null || path.isEmpty() || "/".equals(path)) path = "";
            return port >= 0
                    ? scheme + host + ":" + port + path
                    : scheme + host + path;
        }
        return url;
    }

    /**
     * 确保 URL 有 scheme 前缀。{@code proxy:8080 → http://proxy:8080}
     */
    static String ensureScheme(String url) {
        if (url.matches("^https?://.*")) return url;
        return "http://" + url;
    }

    /**
     * 对 URL userinfo 组件做 RFC 3986 编码。
     * <p>保留的字符仅限：{@code A-Z a-z 0-9 - . _ ~ ! $ & ' ( ) * + , ; =}
     * <p>关键编码：{@code @ → %40}、{@code : → %3A}、{@code # → %23}、
     * {@code % → %25}、{@code / → %2F}、{@code ? → %3F}、{@code <space> → %20}
     * <p>注意：使用 {@link URLEncoder} 做基准编码后，需纠正三点：
     * <ul>
     *   <li>{@code +} 不该出现（是 URLEncoder 对 form 编码的副作用，userinfo 中无此语义）</li>
     *   <li>{@code *} URLEncoder 不编码，但它是 sub-delim 可保留</li>
     *   <li>{@code ~} URLEncoder 不编码，它是 unreserved 可保留</li>
     * </ul>
     */
    static String urlEncode(String value) {
        if (value == null || value.isEmpty()) return value;
        try {
            String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8.name());
            // URLEncoder 把空格编码成 +，但 userinfo 中空格应为 %20
            encoded = encoded.replace("+", "%20");
            return encoded;
        } catch (Exception e) {
            // UTF-8 总是可用的，兜底：逐字符 %
            StringBuilder sb = new StringBuilder(value.length() * 3);
            for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
                int unsigned = b & 0xFF;
                if ((unsigned >= 'A' && unsigned <= 'Z')
                        || (unsigned >= 'a' && unsigned <= 'z')
                        || (unsigned >= '0' && unsigned <= '9')
                        || unsigned == '-' || unsigned == '.' || unsigned == '_'
                        || unsigned == '~') {
                    sb.append((char) unsigned);
                } else {
                    sb.append('%').append(String.format("%02X", unsigned));
                }
            }
            return sb.toString();
        }
    }

    private static String urlDecode(String value) {
        if (value == null) return null;
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            // 含非法 %XX 序列时回退
            return value;
        }
    }

    /**
     * 宽松的 URI 解析 —— 优于裸字符串操作，能正确处置编码、IPv6 等。
     *
     * @return 解析成功返回 {@link URI}，否则返回 {@code null}
     */
    private static URI parseLenient(String proxyUrl) {
        if (proxyUrl == null) return null;
        try {
            return new URI(proxyUrl);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    // ──────────── 提取回退（URI 解析失败时的兜底）────────────

    private static String extractHostFallback(String proxyUrl) {
        String url = proxyUrl.replaceFirst("(?i)^https?://", "");
        int atIdx = url.lastIndexOf('@');
        if (atIdx >= 0) url = url.substring(atIdx + 1);
        int colonIdx = url.lastIndexOf(':');
        return colonIdx >= 0 ? url.substring(0, colonIdx) : url;
    }

    private static String extractPortFallback(String proxyUrl) {
        String url = proxyUrl.replaceFirst("(?i)^https?://", "");
        int atIdx = url.lastIndexOf('@');
        if (atIdx >= 0) url = url.substring(atIdx + 1);
        int colonIdx = url.lastIndexOf(':');
        return colonIdx >= 0 ? url.substring(colonIdx + 1).replaceAll("/.*", "") : null;
    }

    private static String extractUserFallback(String proxyUrl) {
        String url = proxyUrl.replaceFirst("(?i)^https?://", "");
        int atIdx = url.indexOf('@');
        if (atIdx <= 0) return null;
        String auth = url.substring(0, atIdx);
        int colonIdx = auth.indexOf(':');
        return colonIdx >= 0 ? urlDecode(auth.substring(0, colonIdx)) : urlDecode(auth);
    }

    private static String extractPassFallback(String proxyUrl) {
        String url = proxyUrl.replaceFirst("(?i)^https?://", "");
        int atIdx = url.indexOf('@');
        if (atIdx <= 0) return null;
        String auth = url.substring(0, atIdx);
        int colonIdx = auth.indexOf(':');
        return colonIdx >= 0 ? urlDecode(auth.substring(colonIdx + 1)) : null;
    }

    private static String nonBlank(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
