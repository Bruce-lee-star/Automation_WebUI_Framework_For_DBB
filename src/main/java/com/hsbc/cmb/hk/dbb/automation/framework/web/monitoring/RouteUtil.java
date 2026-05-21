package com.hsbc.cmb.hk.dbb.automation.framework.web.monitoring;

import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 通用路由工具类
 * 包含字符清洗、正则转换、请求体处理等工具方法
 *
 * <p>设计原则：
 * <ul>
 *   <li>Route 回调只做极快操作</li>
 *   <li>Response 数据在 route 线程立即拷贝</li>
 *   <li>所有 IO/JSON/日志异步处理</li>
 * </ul>
 */
public class RouteUtil {

    /**
     * 线程安全：禁止实例化
     */
    private RouteUtil() {}

    /**
     * 字符串安全转正则（特殊字符自动转义）
     *
     * @param source 原始字符串
     * @return 编译后的正则 Pattern
     */
    public static Pattern toRegex(String source) {
        if (source == null || source.isEmpty()) {
            return Pattern.compile(".*");
        }
        // 转义正则特殊字符
        String escape = source.replaceAll("[.*+?^${}()|\\[\\]\\\\]", "\\\\$0");
        return Pattern.compile(escape);
    }

    /**
     * 将 glob pattern 转换为正则表达式
     * 支持 * 和 ** 通配符
     *
     * @param globPattern glob 模式
     * @return 正则表达式字符串
     */
    public static String globToRegex(String globPattern) {
        if (globPattern == null || globPattern.isEmpty()) {
            return ".*";
        }
        // 移除开头斜杠
        String raw = globPattern.startsWith("/") ? globPattern.substring(1) : globPattern;
        // 转义正则特殊字符
        String escaped = raw.replaceAll("([\\\\\\.\\[\\]\\(\\)\\?\\+\\*\\^\\$\\{\\}\\|])", "\\\\$1");
        // 替换 ** -> .* 和 * -> [^/]*
        String reg = escaped.replace("**", "\u0000PLACEHOLDER_DOUBLE")
                           .replace("*", "[^/]*")
                           .replace("\u0000PLACEHOLDER_DOUBLE", ".*");
        return ".*" + reg + ".*";
    }

    /**
     * 检查 URL 是否匹配正则模式
     *
     * @param url 待检查的 URL
     * @param regexPattern 正则表达式
     * @return true 如果匹配
     */
    public static boolean matches(String url, String regexPattern) {
        if (url == null || regexPattern == null) {
            return false;
        }
        try {
            return url.matches(regexPattern);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 清除 \u0001 等不可见控制字符
     * 用于清理响应体中的控制字符
     *
     * @param content 原始内容
     * @return 清理后的内容
     */
    public static String cleanControlChar(String content) {
        if (content == null) return "";
        // 清除 0-31 所有 ASCII 控制字符
        return content.replaceAll("[\u0000-\u001F]", "");
    }

    /**
     * 构建 resume 请求配置
     * 用于 route.resume(options) 时设置自定义请求头/请求体
     *
     * @param request 原始请求
     * @param appendHeaders 要追加/覆盖的请求头
     * @return Route.ResumeOptions 配置对象
     */
    public static Route.ResumeOptions buildResumeOptions(Request request, Map<String, String> appendHeaders) {
        Route.ResumeOptions options = new Route.ResumeOptions();
        // 保留原始请求头
        if (request != null) {
            options.setHeaders(request.headers());
        }
        // 追加/覆盖请求头
        if (appendHeaders != null && !appendHeaders.isEmpty()) {
            if (request != null) {
                Map<String, String> headers = new java.util.HashMap<>(request.headers());
                headers.putAll(appendHeaders);
                options.setHeaders(headers);
            } else {
                options.setHeaders(appendHeaders);
            }
        }
        return options;
    }

    /**
     * 安全截断长字符串
     * 用于日志输出，避免过长
     *
     * @param str 原始字符串
     * @param maxLength 最大长度
     * @return 截断后的字符串
     */
    public static String truncate(String str, int maxLength) {
        if (str == null) return "";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }

    /**
     * 简化 URL 用于日志显示
     * 移除查询参数，只保留路径
     *
     * @param url 完整 URL
     * @return 简化后的 URL
     */
    public static String simplifyUrl(String url) {
        if (url == null) return "";
        int queryIndex = url.indexOf('?');
        if (queryIndex > 0) {
            return url.substring(0, Math.min(queryIndex, 80));
        }
        return url.length() > 80 ? url.substring(0, 80) + "..." : url;
    }

    /**
     * 从 URL 中提取路径部分
     *
     * @param url 完整 URL
     * @return URL 路径
     */
    public static String extractPath(String url) {
        if (url == null) return "";
        try {
            int queryIndex = url.indexOf('?');
            String path = queryIndex > 0 ? url.substring(0, queryIndex) : url;
            int hostIndex = path.indexOf("://");
            if (hostIndex > 0) {
                int pathStart = path.indexOf('/', hostIndex + 3);
                return pathStart > 0 ? path.substring(pathStart) : "/";
            }
            return path;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 检查请求体是否为 JSON
     *
     * @param body 请求体
     * @return true 如果看起来像 JSON
     */
    public static boolean isJson(String body) {
        if (body == null || body.isEmpty()) return false;
        String trimmed = body.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
            || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    /**
     * 安全解析 JSON 字符串
     * 避免解析失败导致异常
     *
     * @param json JSON 字符串
     * @return 解析后的 Map，如果解析失败返回 null
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> safeParseJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
