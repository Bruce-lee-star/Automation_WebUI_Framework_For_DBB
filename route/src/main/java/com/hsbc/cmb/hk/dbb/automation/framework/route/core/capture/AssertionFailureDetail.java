package com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture;

/**
 * 断言失败详情 DTO（原 {@code ApiCaptureContext} 的内部静态类，Phase 5 抽离为独立类型）。
 */
public class AssertionFailureDetail {
    public final String url;
    public final String assertionType;   // "STATUS" / "JSONPATH"
    public final String expectedValue;
    public final String actualValue;
    public final String failMessage;

    AssertionFailureDetail(String url, String assertionType, String expectedValue,
                           String actualValue, String failMessage) {
        this.url = url;
        this.assertionType = assertionType;
        this.expectedValue = expectedValue;
        this.actualValue = actualValue;
        this.failMessage = failMessage;
    }

    @Override
    public String toString() {
        return String.format("  [%s] %s%s: expected='%s', actual='%s'",
                assertionType, extractEndpoint(url),
                failMessage != null ? " (" + failMessage + ")" : "",
                expectedValue != null ? expectedValue : "N/A",
                actualValue != null ? actualValue : "N/A");
    }

    private static String extractEndpoint(String url) {
        if (url == null || url.isEmpty()) return "N/A";
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();

            if (host == null) {
                // 无 host 时直接按长度截断
                return url.length() <= 60 ? url
                        : url.substring(0, 25) + "..." + url.substring(url.length() - 20);
            }

            String shortHost = abbreviateMiddle(host, 18, 14);
            String shortPath = abbreviatePath(path);
            return shortHost + shortPath;
        } catch (Exception e) {
            // 解析失败兜底：超长截断
            return url.length() <= 60 ? url
                    : url.substring(0, 25) + "..." + url.substring(url.length() - 20);
        }
    }

    /** 保留字符串首部 N 个字符 + ... + 尾部 M 个字符 */
    private static String abbreviateMiddle(String s, int headLen, int tailLen) {
        if (s == null || s.isEmpty()) return "";
        if (s.length() <= headLen + tailLen + 3) return s;
        return s.substring(0, headLen) + "..." + s.substring(s.length() - tailLen);
    }

    /** 路径保留首段/.../末段，且末段（endpoint 名）始终完整显示 */
    private static String abbreviatePath(String path) {
        if (path == null || path.isEmpty()) return "";
        if (path.length() <= 50) return path;

        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) return abbreviateMiddle(path, 25, 18);

        String endpoint = path.substring(lastSlash);       // /permissionLeftMenuConfig（完整保留）
        String prefix = path.substring(0, lastSlash);      // /portalserver/.../leftmenu

        // prefix 够短则不动
        if (prefix.length() <= 30) return prefix + endpoint;

        // 只缩写 prefix 中间部分，endpoint 原样输出
        int firstSlash = prefix.indexOf('/', 1);
        if (firstSlash < 0) {
            return abbreviateMiddle(prefix, 15, 8) + endpoint;
        }
        return prefix.substring(0, firstSlash) + "/..." + endpoint;
    }
}
