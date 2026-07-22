package com.hsbc.cmb.hk.dbb.automation.framework.web.utils;

import com.hsbc.cmb.hk.dbb.automation.framework.api.utility.JsonUtils;
import com.jayway.jsonpath.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NLS（多语言）工具。
 *
 * <p>设计要点：
 * <ol>
 *   <li>定位表达式只认「文件路径 + key」，语言类型不作为参数传入。</li>
 *   <li>当前语言由语言切换按钮调用 {@link #setLanguage(String)} 显式告知框架；
 *       未设置就调用 {@link #get(String, String)} 会抛异常，强制「切语言必须先通知框架」。</li>
 *   <li>nls 文件结构：{ 语言Key: { key: value, ... }, ... }，语言 Key 由 setLanguage 指定。</li>
 *   <li>文件路径支持 classpath 相对路径（如 "nls/login.nls.json"）或文件系统绝对路径。</li>
 * </ol>
 */
public final class NLSUtils {

    private static final Logger log = LoggerFactory.getLogger(NLSUtils.class);

    /** 当前语言：仅由 setLanguage 设置；初始为 null，get 前必须先设置 */
    private static final ThreadLocal<String> currentLang = new ThreadLocal<>();

    /** 缓存：filePath -> (lang -> (key -> value)) */
    private static final Map<String, Map<String, Map<String, String>>> CACHE =
            new ConcurrentHashMap<>();

    private static final TypeRef<Map<String, Map<String, String>>> LANG_TABLE_TYPE =
            new TypeRef<Map<String, Map<String, String>>>() {};

    private NLSUtils() {
    }

    /**
     * 语言切换时调用，告知框架当前显示的语言。
     * 值应取自对应 nls 文件第一层的语言 Key（如 "en"、"zh"）。
     *
     * @param lang 语言标识
     */
    public static void setLanguage(String lang) {
        currentLang.set(lang);
        log.info("[NLS] language switched to: {}", lang);
    }

    public static String getLanguage() {
        return currentLang.get();
    }

    /** 清理当前线程的语言状态，避免污染后续用例 */
    public static void reset() {
        currentLang.remove();
    }

    /**
     * 绑定一个 nls 文件，返回一个句柄。之后调用方只需传 key，
     * 文件路径只在此处出现一次。文件内容按路径缓存（仅解析一次），
     * 语言仍由 {@link #setLanguage(String)} 全局决定。
     *
     * @param filePath nls 文件完整路径（classpath 相对或文件系统绝对）
     * @return 绑定该文件的句柄
     */
    public static NlsBundle bind(String filePath) {
        return new NlsBundle(filePath);
    }

    /**
     * 绑定某个 nls 文件的句柄：调用方只需传 key，无需重复写文件路径。
     * 语言由 {@link NLSUtils#setLanguage(String)} 全局控制，与具体 bundle 无关。
     */
    public static final class NlsBundle {
        private final String filePath;

        private NlsBundle(String filePath) {
            this.filePath = filePath;
        }

        /** 取当前语言下该文件内某个 key 的可访问名 */
        public String get(String key) {
            return NLSUtils.get(filePath, key);
        }

        /** 返回绑定的文件路径 */
        public String path() {
            return filePath;
        }
    }

    /**
     * 取当前语言下，指定 nls 文件中某个 key 的可访问名。
     *
     * @param filePath nls 文件完整路径（classpath 相对或文件系统绝对），如 "nls/login.nls.json"
     * @param key      功能内 key，如 "username"
     * @return 当前语言下的文本
     */
    public static String get(String filePath, String key) {
        String lang = currentLang.get();
        if (lang == null || lang.isEmpty()) {
            throw new IllegalStateException(
                    "[NLS] current language not set — call setLanguage(\"xx\") first "
                            + "(e.g. right after switching language in the UI). file=" + filePath);
        }
        Map<String, Map<String, String>> byLang = load(filePath);
        Map<String, String> table = byLang.get(lang);
        if (table == null) {
            throw new IllegalStateException(
                    "[NLS] no language table '" + lang + "' in file: " + filePath);
        }
        String value = table.get(key);
        if (value == null) {
            throw new IllegalStateException(
                    "[NLS] missing key '" + key + "' for lang=" + lang + " in file: " + filePath);
        }
        return value;
    }

    /**
     * 返回 nls 文件原始结构（语言 Key → key → value），供需要在“文本值 → key”反向查表时使用
     * （如 {@code RoleElementPicker} 把拾取的 a11y name 反查对应 nls key，避免重新派生 slug）。
     * 内容按路径缓存，仅解析一次。
     *
     * @param filePath nls 文件完整路径（classpath 相对或文件系统绝对）
     * @return 语言 Key → key → value 的二维表
     */
    public static Map<String, Map<String, String>> rawTables(String filePath) {
        return load(filePath);
    }

    private static Map<String, Map<String, String>> load(String filePath) {
        return CACHE.computeIfAbsent(filePath, NLSUtils::readAndParse);
    }

    private static Map<String, Map<String, String>> readAndParse(String filePath) {
        Map<String, Map<String, String>> parsed = JsonUtils.fromJson(readFile(filePath), LANG_TABLE_TYPE);
        if (parsed == null) {
            throw new IllegalStateException("[NLS] failed to parse file: " + filePath);
        }
        return parsed;
    }

    private static String readFile(String filePath) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(filePath)) {
            if (in != null) {
                try {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException("[NLS] read failed: " + filePath, e);
                }
            }
        } catch (IOException ignored) {
            // classpath 资源不存在，继续尝试文件系统
        }
        Path p = Paths.get(filePath);
        if (Files.exists(p)) {
            try {
                return Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("[NLS] read failed: " + filePath, e);
            }
        }
        throw new IllegalStateException("[NLS] file not found (classpath or filesystem): " + filePath);
    }
}
